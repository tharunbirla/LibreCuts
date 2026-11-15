// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer and Concat contributors
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! Writing the timeline to a video file: one export at a time, on a thread
//! of its own, reporting through a listener and stopping when asked.
//!
//! The file is written into the app's cache; the app publishes it to the
//! phone's Movies folder once it is finished.

use std::sync::Arc;

use concat_host::export::{ExportSpec, Progress, RateMode, VideoCodec};

use crate::library::even;
use crate::session::MobileSession;
use crate::{MobileError, context};

/// What an export reports.
#[uniffi::export(with_foreign)]
pub trait ExportListener: Send + Sync {
    /// How far along it is, 0 to 1.
    fn on_progress(&self, fraction: f32);
    /// It finished: the file written.
    fn on_done(&self, path: String);
    /// It failed or was cancelled (`cancelled` true).
    fn on_failed(&self, message: String, cancelled: bool);
}

/// How the file is made.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ExportSettings {
    /// The output's short side in pixels (720, 1080, 1440, 2160); the long
    /// side follows the canvas's shape.
    pub resolution: u32,
    /// Frames a second (24, 25, 30, 50, 60).
    pub fps: u32,
    /// Target bitrate in kbps; 0 lets the encoder choose for the size.
    pub bitrate_kbps: u32,
    /// H.265 rather than H.264.
    pub hevc: bool,
    /// The file to write.
    pub output: String,
}

/// The size of an export at `resolution` for a `width` by `height` canvas.
#[uniffi::export]
pub fn export_size(width: u32, height: u32, resolution: u32) -> Vec<u32> {
    let short = width.min(height).max(1);
    let scale = f64::from(resolution.max(2)) / f64::from(short);
    vec![
        even((f64::from(width) * scale).round() as u32),
        even((f64::from(height) * scale).round() as u32),
    ]
}

/// A bitrate for a frame size and rate that looks right on a phone: about
/// what phone cameras record at.
#[uniffi::export]
pub fn suggested_bitrate_kbps(width: u32, height: u32, fps: u32) -> u32 {
    let pixels = f64::from(width) * f64::from(height);
    // a fifth of a bit a pixel a frame: about 12 Mbps for 1080p at 30 fps, what phone editors suggest
    let per_frame = pixels * 0.2;
    ((per_frame * f64::from(fps.max(1)) / 1000.0) as u32).clamp(1000, 120_000)
}

/// Starts writing `session`'s timeline as `settings` say. Refused while
/// another export runs.
#[uniffi::export]
pub fn start_export(
    session: Arc<MobileSession>,
    settings: ExportSettings,
    listener: Arc<dyn ExportListener>,
) -> Result<(), MobileError> {
    let context = context()?;
    let job = context
        .exporter
        .begin()
        .map_err(|_| MobileError::Busy("An export is already running.".to_owned()))?;
    let request = session.export_request(&settings)?;
    std::thread::Builder::new()
        .name("capcraft-export".to_owned())
        .spawn(move || {
            let cancel = job.cancel_handle();
            let mut last = -1.0f32;
            let result = concat_host::export::run(&request, &cancel, |progress: Progress| {
                let fraction = fraction_of(progress);
                if fraction - last >= 0.005 || fraction >= 1.0 {
                    last = fraction;
                    listener.on_progress(fraction);
                }
            });
            match result {
                Ok(path) => listener.on_done(path),
                Err(message) => listener.on_failed(message, job.cancelled()),
            }
            drop(job);
        })
        .map_err(|error| MobileError::Failed(format!("could not start the export: {error}")))?;
    Ok(())
}

/// Asks the running export to stop.
#[uniffi::export]
pub fn cancel_export() {
    if let Ok(context) = context() {
        context.exporter.cancel();
    }
}

/// Drawing the frames is most of the work; mixing the sound and the mux
/// the rest. The stages are the engine's own names for them.
fn fraction_of(progress: Progress) -> f32 {
    let done = if progress.total > 0 {
        (progress.frame as f32 / progress.total as f32).clamp(0.0, 1.0)
    } else {
        0.0
    };
    match progress.stage {
        "rendering" => done * 0.9,
        "mixing audio" => 0.9 + done * 0.06,
        "muxing" => 0.96 + done * 0.04,
        // a stage this bridge does not know: hold where drawing ended
        _ => 0.9,
    }
}

impl MobileSession {
    /// The engine's request for `settings`, made under the session's lock.
    pub(crate) fn export_request(
        &self,
        settings: &ExportSettings,
    ) -> Result<concat_export::ExportRequest, MobileError> {
        self.with_session(|session| {
            let canvas = session.settings();
            let size = export_size(canvas.width, canvas.height, settings.resolution);
            let (width, height) = (size[0], size[1]);
            let bitrate = if settings.bitrate_kbps > 0 {
                settings.bitrate_kbps
            } else {
                suggested_bitrate_kbps(width, height, settings.fps)
            };
            let spec = ExportSpec {
                output: settings.output.clone(),
                crf: 20,
                preset: "veryfast".to_owned(),
                codec: if settings.hevc {
                    VideoCodec::Hevc
                } else {
                    VideoCodec::H264
                },
                ten_bit: false,
                // the target to hold; the phone's encoder holds it in VBR
                rate_mode: RateMode::Cbr,
                bitrate_kbps: bitrate,
                color_range: Default::default(),
                hdr: false,
            };
            let titles = context()
                .map(|context| {
                    context
                        .titles
                        .clips(session.project(), width, height)
                        .into_iter()
                        .map(|title| title.clip)
                        .collect()
                })
                .unwrap_or_default();
            let mut request = concat_host::export::request(session, &spec, titles);
            crate::background::add(
                session.project(),
                &canvas,
                std::path::Path::new(session.path()),
                &mut request.clips,
            );
            request.width = width;
            request.height = height;
            request.rate_num = i64::from(settings.fps.max(1));
            request.rate_den = 1;
            Ok(request)
        })
    }
}

#[cfg(test)]
mod tests {
    use super::{Progress, fraction_of};

    fn at(frame: i64, total: i64, stage: &'static str) -> f32 {
        fraction_of(Progress {
            frame,
            total,
            stage,
        })
    }

    /// The ring moves with the frames drawn, then steps through the sound
    /// and the mux, and never goes backwards.
    #[test]
    fn progress_follows_the_engines_stages() {
        assert_eq!(at(0, 300, "rendering"), 0.0);
        assert!((at(150, 300, "rendering") - 0.45).abs() < 1e-6);
        let steps = [
            at(299, 300, "rendering"),
            at(0, 300, "mixing audio"),
            at(300, 300, "muxing"),
        ];
        assert!(steps.windows(2).all(|pair| pair[0] <= pair[1]), "{steps:?}");
        assert_eq!(steps[2], 1.0);
    }
}

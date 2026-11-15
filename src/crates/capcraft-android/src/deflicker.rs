// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! Remove flickers: a copy of a clip's file in which each frame's
//! brightness is evened out to that of the frames around it, so the beat
//! of a lamp or a screen in the shot no longer pulses; the clip then shows
//! the copy, in one undo step.
//!
//! Two passes. The first reads every frame small and grey for its average
//! brightness; a moving average of those over about a quarter of a second
//! is the brightness the shot should have. The second scales each frame's
//! colour by the ratio and writes the copy with the original's sound. The
//! copy is kept in the project's cache, named after the file, and the
//! timeline keeps which file it was made from (`capcraftDeflickered`), so
//! the clip can go back to it.

use std::hash::{Hash, Hasher};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, OnceLock};

use concat_core::Frame;
use concat_media::{DecodeOptions, Decoder, FrameSource};
use concat_project::Command;
use serde_json::{Value, json};

use crate::MobileError;
use crate::session::MobileSession;
use crate::speech::SpeechListener;
use crate::stabilize::{CopyWriter, shrink};

/// Where the timeline keeps, for each evened-out copy's path, the file it
/// was made from.
pub const DEFLICKERED: &str = "capcraftDeflickered";

/// How far, in frames either way, the brightness is averaged.
const WINDOW: usize = 7;
/// The most a frame is brightened or darkened.
const MOST: f64 = 1.4;

fn cancelled() -> &'static AtomicBool {
    static CANCEL: OnceLock<AtomicBool> = OnceLock::new();
    CANCEL.get_or_init(|| AtomicBool::new(false))
}

/// Asks the flicker removal in hand to stop after the frame it is on.
#[uniffi::export]
pub fn cancel_deflicker() {
    cancelled().store(true, Ordering::Relaxed);
}

/// The gain for each frame of brightnesses `means`: the moving average
/// around it over its own, held within [`MOST`] either way.
pub(crate) fn gains(means: &[f64]) -> Vec<f64> {
    (0..means.len())
        .map(|i| {
            let lo = i.saturating_sub(WINDOW);
            let hi = (i + WINDOW + 1).min(means.len());
            let target = means[lo..hi].iter().sum::<f64>() / (hi - lo) as f64;
            (target / means[i].max(1.0)).clamp(1.0 / MOST, MOST)
        })
        .collect()
}

/// `frame` with its colour scaled by `gain`, as eight-bit RGBA.
fn brightened(frame: &Frame, gain: f64) -> Frame {
    let step = frame.bytes_per_pixel();
    let high = if step == 8 { 1 } else { 0 };
    let source = frame.pixels();
    let count = frame.width() as usize * frame.height() as usize;
    let gain = gain as f32;
    let mut out = vec![0u8; count * 4];
    for (i, pixel) in out.chunks_exact_mut(4).enumerate() {
        for c in 0..3 {
            let v = f32::from(source[i * step + c * (step / 4) + high]);
            pixel[c] = (v * gain).round().clamp(0.0, 255.0) as u8;
        }
        pixel[3] = source[i * step + 3 * (step / 4) + high];
    }
    Frame::from_rgba(frame.width(), frame.height(), out).unwrap_or_else(|| frame.clone())
}

#[uniffi::export]
impl MobileSession {
    /// Evens out the flicker in `clip_id`'s footage (`on`) on a thread of
    /// its own, reporting through `listener`, and points the clip at the
    /// copy; `on` false points it back at the file the copy was made from.
    pub fn remove_flicker(
        self: Arc<Self>,
        clip_id: String,
        on: bool,
        listener: Arc<dyn SpeechListener>,
    ) -> Result<(), MobileError> {
        let (current, project) = self.with_session(|session| {
            let project = session.project();
            let clip = project
                .active()
                .clips
                .iter()
                .find(|clip| clip.id == clip_id)
                .ok_or_else(|| MobileError::Failed("That clip is gone.".to_owned()))?;
            let media = project
                .media
                .iter()
                .find(|item| item.id == clip.media_id)
                .ok_or_else(|| MobileError::Failed("The clip's file is gone.".to_owned()))?;
            Ok((media.path.clone(), session.path().to_owned()))
        })?;
        let mut made = match serde_json::from_str::<Value>(&self.extra(DEFLICKERED.to_owned())) {
            Ok(Value::Object(map)) => map,
            _ => serde_json::Map::new(),
        };
        let original = made
            .get(&current)
            .and_then(|entry| entry.get("original"))
            .and_then(Value::as_str)
            .map(str::to_owned)
            .unwrap_or_else(|| current.clone());
        if !on {
            if original == current {
                listener.on_done(None);
                return Ok(());
            }
            let item = concat_host::media::probe(&original)?.to_new_media();
            let view = self.run(vec![Command::ReplaceClipMedia {
                clip_id,
                item,
                source_start: None,
                enhanced: false,
                offset: 0.0,
            }])?;
            listener.on_done(Some(view));
            return Ok(());
        }
        if original != current {
            // already evened out
            listener.on_done(None);
            return Ok(());
        }
        let target = target_for(Path::new(&project), &original)
            .ok_or_else(|| MobileError::Failed("The clip's file cannot be read.".to_owned()))?;
        cancelled().store(false, Ordering::Relaxed);
        std::thread::Builder::new()
            .name("capcraft-deflicker".to_owned())
            .spawn(move || {
                let written = if target.is_file() {
                    Ok(())
                } else {
                    deflicker(&original, &target, &|fraction| {
                        listener.on_progress(fraction, "work".to_owned());
                        !cancelled().load(Ordering::Relaxed)
                    })
                };
                if let Err(message) = written {
                    listener.on_failed(message);
                    return;
                }
                let copy = target.to_string_lossy().into_owned();
                let placed = concat_host::media::probe(&copy)
                    .map_err(MobileError::from)
                    .and_then(|info| {
                        made.insert(copy.clone(), json!({ "original": original }));
                        self.run(vec![
                            Command::ReplaceClipMedia {
                                clip_id,
                                item: info.to_new_media(),
                                source_start: None,
                                enhanced: false,
                                offset: 0.0,
                            },
                            Command::SetTimelineExtra {
                                key: DEFLICKERED.to_owned(),
                                value: Some(Value::Object(made)),
                            },
                        ])
                    });
                match placed {
                    Ok(view) => listener.on_done(Some(view)),
                    Err(error) => listener.on_failed(error.to_string()),
                }
            })
            .map_err(|error| MobileError::Failed(format!("could not start: {error}")))?;
        Ok(())
    }
}

/// Where the evened-out copy of `media` lives under `project`.
fn target_for(project: &Path, media: &str) -> Option<PathBuf> {
    let meta = std::fs::metadata(media).ok()?;
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    media.hash(&mut hasher);
    meta.len().hash(&mut hasher);
    if let Ok(modified) = meta.modified()
        && let Ok(since) = modified.duration_since(std::time::UNIX_EPOCH)
    {
        since.as_secs().hash(&mut hasher);
    }
    Some(
        project
            .join("cache")
            .join(format!("deflicker-{:016x}.mp4", hasher.finish())),
    )
}

/// Writes the evened-out copy of `media` to `target`; `step` hears the
/// fraction done and answers whether to go on.
pub(crate) fn deflicker(
    media: &str,
    target: &Path,
    step: &dyn Fn(f32) -> bool,
) -> Result<(), String> {
    let info = concat_media::probe(media).map_err(|error| error.to_string())?;
    let video = info
        .video
        .as_ref()
        .ok_or_else(|| "The clip has no picture.".to_owned())?;
    let rate = video.frame_rate;
    let expected = info
        .duration
        .map(|duration| duration.as_f64() * rate.fps().as_f64())
        .unwrap_or(0.0)
        .max(1.0);
    let options = DecodeOptions::default().in_software().threaded(2);
    let mut decoder = Decoder::open(media, &options).map_err(|error| error.to_string())?;
    let mut means = Vec::new();
    let mut size = (0u32, 0u32);
    while let Some(frame) = decoder.next_frame().map_err(|error| error.to_string())? {
        size = (frame.width(), frame.height());
        means.push(shrink(&frame).mean());
        if !step((means.len() as f64 / expected * 0.3).min(0.3) as f32) {
            return Err("Remove flickers was cancelled.".to_owned());
        }
    }
    drop(decoder);
    if means.is_empty() {
        return Err("The clip has no picture.".to_owned());
    }
    let gains = gains(&means);
    let mut writer = CopyWriter::create(media, target, size, rate, info.audio.is_some())?;
    let mut decoder = Decoder::open(media, &options).map_err(|error| error.to_string())?;
    let mut index = 0usize;
    while let Some(frame) = decoder.next_frame().map_err(|error| error.to_string())? {
        let gain = gains.get(index).copied().unwrap_or(1.0);
        let out = if (gain - 1.0).abs() < 0.002 {
            frame
        } else {
            brightened(&frame, gain)
        };
        if let Err(error) = writer.write(out) {
            writer.abandon();
            return Err(error);
        }
        index += 1;
        if !step((0.3 + index as f64 / means.len() as f64 * 0.65).min(0.95) as f32) {
            writer.abandon();
            return Err("Remove flickers was cancelled.".to_owned());
        }
    }
    drop(decoder);
    let finished = writer.finish();
    step(1.0);
    finished
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_steady_shot_is_left_alone() {
        assert!(gains(&[120.0; 30]).iter().all(|g| (g - 1.0).abs() < 1e-9));
    }

    #[test]
    fn a_pulse_is_evened_out() {
        // every fourth frame a lamp flares
        let means: Vec<f64> = (0..40)
            .map(|i| if i % 4 == 0 { 150.0 } else { 110.0 })
            .collect();
        let g = gains(&means);
        let after: Vec<f64> = means.iter().zip(&g).map(|(m, g)| m * g).collect();
        let spread = |v: &[f64]| {
            v.iter().cloned().fold(f64::MIN, f64::max) - v.iter().cloned().fold(f64::MAX, f64::min)
        };
        assert!(
            spread(&after[8..32]) < spread(&means[8..32]) * 0.2,
            "{:?}",
            &after[8..32]
        );
    }

    #[test]
    fn a_fade_to_black_is_not_fought_beyond_reason() {
        let means: Vec<f64> = (0..30).map(|i| 200.0 - i as f64 * 6.0).collect();
        assert!(gains(&means).iter().all(|g| *g <= MOST && *g >= 1.0 / MOST));
    }
}

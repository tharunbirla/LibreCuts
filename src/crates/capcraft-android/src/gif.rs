// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! GIF export: the timeline drawn frame by frame at a small size, the way the
//! preview draws it, and written as a looping GIF. The engine's exporter
//! makes video files only; a GIF is short and small, so drawing each frame
//! through the preview's path costs little and needs nothing new from it.

use std::fs::File;
use std::io::BufWriter;
use std::sync::Arc;
use std::sync::atomic::Ordering;

use crate::export::ExportListener;
use crate::library::even;
use crate::session::MobileSession;
use crate::{MobileError, context};

/// How a GIF is made.
#[derive(Debug, Clone, uniffi::Record)]
pub struct GifSettings {
    /// Where the GIF is written.
    pub output: String,
    /// The picture's short side in pixels; the long side follows the
    /// canvas's shape.
    pub short_side: u32,
    /// Pictures per second.
    pub fps: u32,
}

/// The frame size for a canvas of `width` by `height` at `short_side`,
/// even on both sides and within what a GIF can hold.
pub(crate) fn gif_size(width: u32, height: u32, short_side: u32) -> (u32, u32) {
    let short = width.min(height).max(1) as f64;
    let scale = f64::from(short_side.clamp(64, 1080)) / short;
    let size = |side: u32| even(((f64::from(side) * scale).round() as u32).clamp(2, 4096));
    (size(width), size(height))
}

/// The delay after each picture, in the hundredths of a second GIF counts in.
pub(crate) fn gif_delay(fps: u32) -> u16 {
    (100.0 / f64::from(fps.clamp(1, 50))).round() as u16
}

/// Draws the open project into a GIF at `settings` on a thread of its own,
/// telling `listener` how far it has got. Shares the video export's one slot,
/// so `cancel_export` stops it too.
#[uniffi::export]
pub fn start_gif_export(
    session: Arc<MobileSession>,
    settings: GifSettings,
    listener: Arc<dyn ExportListener>,
) -> Result<(), MobileError> {
    let context = context()?;
    let job = context
        .exporter
        .begin()
        .map_err(|_| MobileError::Busy("An export is already running.".to_owned()))?;
    let view = session.view();
    if view.duration_us <= 0 {
        return Err(MobileError::Failed(
            "There is nothing to export.".to_owned(),
        ));
    }
    let (width, height) = gif_size(view.width, view.height, settings.short_side);
    std::thread::Builder::new()
        .name("capcraft-gif".to_owned())
        .spawn(move || {
            let cancel = job.cancel_handle();
            let result = write(
                &session,
                &settings,
                width,
                height,
                view.duration_us,
                &|fraction| {
                    listener.on_progress(fraction);
                    !cancel.load(Ordering::Relaxed)
                },
            );
            match result {
                Ok(()) => listener.on_done(settings.output.clone()),
                Err(message) => {
                    let _ = std::fs::remove_file(&settings.output);
                    listener.on_failed(message, job.cancelled());
                }
            }
            drop(job);
        })
        .map_err(|error| MobileError::Failed(format!("could not start the export: {error}")))?;
    Ok(())
}

/// Writes the GIF; `step` hears the fraction done after each picture and
/// answers whether to go on.
pub(crate) fn write(
    session: &MobileSession,
    settings: &GifSettings,
    width: u32,
    height: u32,
    duration_us: i64,
    step: &dyn Fn(f32) -> bool,
) -> Result<(), String> {
    let fps = settings.fps.clamp(1, 50);
    let frames = ((duration_us as f64 / 1e6) * f64::from(fps))
        .ceil()
        .max(1.0) as i64;
    let file = File::create(&settings.output).map_err(|error| error.to_string())?;
    let mut encoder = gif::Encoder::new(BufWriter::new(file), width as u16, height as u16, &[])
        .map_err(|error| error.to_string())?;
    encoder
        .set_repeat(gif::Repeat::Infinite)
        .map_err(|error| error.to_string())?;
    let delay = gif_delay(fps);
    for index in 0..frames {
        let at = (index as f64 * 1e6 / f64::from(fps)).round() as i64;
        let mut picture = session
            .frame(
                at.min(duration_us - 1),
                width,
                height,
                crate::session::Motion::Playing,
            )
            .map_err(|error| error.to_string())?;
        if picture.width != width || picture.height != height {
            return Err("a frame came back the wrong size".to_owned());
        }
        // speed 10: NeuQuant's own balance of time and colour
        let mut frame =
            gif::Frame::from_rgba_speed(width as u16, height as u16, &mut picture.rgba, 10);
        frame.delay = delay;
        encoder
            .write_frame(&frame)
            .map_err(|error| error.to_string())?;
        if !step((index + 1) as f32 / frames as f32) {
            return Err("export cancelled".to_owned());
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::{gif_delay, gif_size};

    #[test]
    fn a_tall_canvas_keeps_its_shape_at_the_short_side() {
        assert_eq!(gif_size(1080, 1920, 480), (480, 852));
        assert_eq!(gif_size(1920, 1080, 320), (568, 320));
    }

    #[test]
    fn delays_are_in_hundredths() {
        assert_eq!(gif_delay(10), 10);
        assert_eq!(gif_delay(15), 7);
        assert_eq!(gif_delay(0), 100);
    }
}

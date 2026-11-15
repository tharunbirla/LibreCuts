// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! Motion blur: a copy of a clip's file in which each frame is blended with
//! the frames around it, so whatever moves leaves a trail; the clip then
//! shows the copy, in one undo step.
//!
//! The blend reaches `blur` frames either way (both ways, ahead only or
//! behind only, by `direction`), each weighed less the farther it is, and
//! the result is mixed with the frame itself by `blend`. Twice reaches
//! twice as far. The copy is named after the file and the settings, in the
//! project's cache, so asking twice costs nothing; the timeline keeps which
//! file each copy was made from (`capcraftBlurred`), so the clip can go back.

use std::collections::VecDeque;
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
use crate::stabilize::CopyWriter;

/// Where the timeline keeps, for each blurred copy's path, the file it was
/// made from and the settings.
pub const BLURRED: &str = "capcraftBlurred";

/// The most frames the blend reaches either way.
const REACH: usize = 6;
/// The most memory the frames held for the blend may take.
const HELD_BYTES: usize = 240 * 1024 * 1024;

/// The blur in hand; `cancel_motion_blur` stops it.
fn cancelled() -> &'static AtomicBool {
    static CANCEL: OnceLock<AtomicBool> = OnceLock::new();
    CANCEL.get_or_init(|| AtomicBool::new(false))
}

/// Asks the blur in hand to stop after the frame it is on.
#[uniffi::export]
pub fn cancel_motion_blur() {
    cancelled().store(true, Ordering::Relaxed);
}

/// How a blur is made: frames reached either way, which ways, how much of
/// the blend shows.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct Settings {
    /// Frames reached either way.
    pub reach: usize,
    /// Frames before count.
    pub before: bool,
    /// Frames after count.
    pub after: bool,
    /// How much of the blend shows over the frame itself, 0 to 1.
    pub blend: f32,
}

impl Settings {
    /// From the sheet's numbers: `blur` and `blend` 0-100, `direction` 0
    /// both ways, 1 ahead, 2 behind, `times` 1 or 2.
    pub(crate) fn of(blur: u32, blend: u32, direction: u32, times: u32) -> Self {
        let reach = ((f64::from(blur.min(100)) / 100.0 * REACH as f64).round() as usize)
            * times.clamp(1, 2) as usize;
        Settings {
            reach,
            before: direction != 1,
            after: direction != 2,
            blend: blend.min(100) as f32 / 100.0,
        }
    }
}

#[uniffi::export]
impl MobileSession {
    /// Blurs what moves in `clip_id`'s footage (see the module) on a thread
    /// of its own, reporting through `listener`, and points the clip at the
    /// blurred copy; `blur` 0 points it back at the file the copy was made
    /// from.
    pub fn motion_blur(
        self: Arc<Self>,
        clip_id: String,
        blur: u32,
        blend: u32,
        direction: u32,
        times: u32,
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
        let mut made = blurred(&self);
        // the file to blur, or to go back to: never a copy of a copy
        let original = made
            .get(&current)
            .and_then(|entry| entry.get("original"))
            .and_then(Value::as_str)
            .map(str::to_owned)
            .unwrap_or_else(|| current.clone());
        let settings = Settings::of(blur, blend, direction, times);
        if settings.reach == 0 || settings.blend <= 0.0 {
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
        let target = target_for(Path::new(&project), &original, &settings)
            .ok_or_else(|| MobileError::Failed("The clip's file cannot be read.".to_owned()))?;
        cancelled().store(false, Ordering::Relaxed);
        std::thread::Builder::new()
            .name("capcraft-motion-blur".to_owned())
            .spawn(move || {
                let written = if target.is_file() {
                    Ok(())
                } else {
                    blur_copy(&original, &target, &settings, &|fraction| {
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
                        made.insert(
                            copy.clone(),
                            json!({ "original": original, "blur": blur, "blend": blend, "direction": direction, "times": times }),
                        );
                        self.run(vec![
                            Command::ReplaceClipMedia {
                                clip_id,
                                item: info.to_new_media(),
                                source_start: None,
                                enhanced: false,
                                offset: 0.0,
                            },
                            Command::SetTimelineExtra {
                                key: BLURRED.to_owned(),
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

/// What the timeline keeps under [`BLURRED`].
fn blurred(session: &MobileSession) -> serde_json::Map<String, Value> {
    match serde_json::from_str::<Value>(&session.extra(BLURRED.to_owned())) {
        Ok(Value::Object(map)) => map,
        _ => serde_json::Map::new(),
    }
}

/// Where the blurred copy of `media` with `settings` lives under `project`:
/// named by the file's path, size, modification time and the settings.
fn target_for(project: &Path, media: &str, settings: &Settings) -> Option<PathBuf> {
    let meta = std::fs::metadata(media).ok()?;
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    media.hash(&mut hasher);
    meta.len().hash(&mut hasher);
    if let Ok(modified) = meta.modified()
        && let Ok(since) = modified.duration_since(std::time::UNIX_EPOCH)
    {
        since.as_secs().hash(&mut hasher);
    }
    settings.reach.hash(&mut hasher);
    settings.before.hash(&mut hasher);
    settings.after.hash(&mut hasher);
    ((settings.blend * 100.0).round() as u32).hash(&mut hasher);
    Some(
        project
            .join("cache")
            .join(format!("motion-blur-{:016x}.mp4", hasher.finish())),
    )
}

/// `frame`'s pixels as eight-bit RGBA.
fn rgba8(frame: &Frame) -> Vec<u8> {
    let step = frame.bytes_per_pixel();
    if step == 4 {
        return frame.pixels().to_vec();
    }
    // sixteen bits a channel, little-endian: the high byte of each
    frame.pixels().chunks_exact(2).map(|pair| pair[1]).collect()
}

/// The frame at `index` of `held` (frames `first..`) blended with those
/// around it as `settings` says: a weighted average, each neighbour weighed
/// by how near it is, mixed with the frame itself by the blend.
pub(crate) fn blend_at(
    held: &VecDeque<Vec<u8>>,
    first: usize,
    index: usize,
    settings: &Settings,
) -> Vec<u8> {
    let me = &held[index - first];
    let lo = if settings.before {
        index.saturating_sub(settings.reach).max(first)
    } else {
        index
    };
    let hi = if settings.after {
        (index + settings.reach).min(first + held.len() - 1)
    } else {
        index
    };
    let reach = settings.reach as f32 + 1.0;
    let weights: Vec<(usize, f32)> = (lo..=hi)
        .map(|i| (i - first, reach - (i as f32 - index as f32).abs()))
        .collect();
    let total: f32 = weights.iter().map(|(_, w)| w).sum();
    let mix = settings.blend;
    let mut out = vec![0u8; me.len()];
    let threads = std::thread::available_parallelism()
        .map(|n| n.get().min(6))
        .unwrap_or(2);
    let band = me.len().div_ceil(threads).div_ceil(4) * 4;
    std::thread::scope(|scope| {
        for (part, chunk) in out.chunks_mut(band.max(4)).enumerate() {
            let weights = &weights;
            scope.spawn(move || {
                let start = part * band.max(4);
                for (k, value) in chunk.iter_mut().enumerate() {
                    let at = start + k;
                    let mut sum = 0.0f32;
                    for (frame, weight) in weights {
                        sum += f32::from(held[*frame][at]) * weight;
                    }
                    let own = f32::from(me[at]);
                    *value = (own + (sum / total - own) * mix).round().clamp(0.0, 255.0) as u8;
                }
            });
        }
    });
    out
}

/// Writes the blurred copy of `media` to `target`; `step` hears the fraction
/// done and answers whether to go on.
pub(crate) fn blur_copy(
    media: &str,
    target: &Path,
    settings: &Settings,
    step: &dyn Fn(f32) -> bool,
) -> Result<(), String> {
    let info = concat_media::probe(media).map_err(|error| error.to_string())?;
    let video = info
        .video
        .as_ref()
        .ok_or_else(|| "The clip has no picture to blur.".to_owned())?;
    let rate = video.frame_rate;
    let expected = info
        .duration
        .map(|duration| duration.as_f64() * rate.fps().as_f64())
        .unwrap_or(0.0)
        .max(1.0);
    let frame_bytes = (video.width as usize * video.height as usize * 4).max(1);
    // a big picture holds fewer frames
    let settings = Settings {
        reach: settings
            .reach
            .min((HELD_BYTES / frame_bytes).saturating_sub(1) / 2)
            .max(1),
        ..*settings
    };
    let ahead = if settings.after { settings.reach } else { 0 };
    let behind = if settings.before { settings.reach } else { 0 };
    let size = (video.width, video.height);
    let mut writer = CopyWriter::create(media, target, size, rate, info.audio.is_some())?;
    let options = DecodeOptions::default().in_software().threaded(2);
    let mut decoder = Decoder::open(media, &options).map_err(|error| error.to_string())?;
    let mut held: VecDeque<Vec<u8>> = VecDeque::new();
    // the index of the first held frame, of the next to read, of the next to write
    let mut first = 0usize;
    let mut read = 0usize;
    let mut next = 0usize;
    let mut ended = false;
    let mut shape = size;
    loop {
        if !ended {
            match decoder.next_frame().map_err(|error| error.to_string()) {
                Ok(Some(frame)) => {
                    shape = (frame.width(), frame.height());
                    held.push_back(rgba8(&frame));
                    read += 1;
                }
                Ok(None) => ended = true,
                Err(error) => {
                    writer.abandon();
                    return Err(error);
                }
            }
        }
        // every frame whose frames ahead are in (or will never come) is written
        while next < read && (ended || next + ahead < read) {
            let pixels = blend_at(&held, first, next, &settings);
            let frame = Frame::from_rgba(shape.0, shape.1, pixels)
                .ok_or_else(|| "A blurred frame came out the wrong size.".to_owned());
            if let Err(error) = frame.and_then(|frame| writer.write(frame)) {
                writer.abandon();
                return Err(error);
            }
            next += 1;
            // the frames no later one needs
            while first + behind < next && !held.is_empty() {
                held.pop_front();
                first += 1;
            }
            if !step((next as f64 / expected).min(0.97) as f32) {
                writer.abandon();
                return Err("Motion blur was cancelled.".to_owned());
            }
        }
        if ended && next >= read {
            break;
        }
    }
    drop(decoder);
    if read == 0 {
        writer.abandon();
        return Err("The clip has no picture to blur.".to_owned());
    }
    let finished = writer.finish();
    step(1.0);
    finished
}

#[cfg(test)]
mod tests {
    use super::*;

    fn held(values: &[u8]) -> VecDeque<Vec<u8>> {
        values.iter().map(|v| vec![*v; 8]).collect()
    }

    #[test]
    fn a_still_picture_is_left_as_it_is() {
        let frames = held(&[90, 90, 90, 90, 90]);
        let settings = Settings::of(100, 100, 0, 1);
        assert_eq!(blend_at(&frames, 0, 2, &settings), vec![90; 8]);
    }

    #[test]
    fn a_flash_spreads_into_the_frames_around_it_the_way_asked() {
        let frames = held(&[0, 0, 0, 240, 0, 0, 0]);
        let both = Settings::of(50, 100, 0, 1);
        let ahead = Settings::of(50, 100, 1, 1);
        let behind = Settings::of(50, 100, 2, 1);
        // the frame before the flash sees it only when frames ahead count
        assert!(blend_at(&frames, 0, 2, &both)[0] > 0);
        assert!(blend_at(&frames, 0, 2, &ahead)[0] > 0);
        assert_eq!(blend_at(&frames, 0, 2, &behind)[0], 0);
        // and none of it at no blend
        let none = Settings::of(50, 0, 0, 1);
        assert_eq!(blend_at(&frames, 0, 2, &none)[0], 0);
        // the flash itself is dimmed by its neighbours
        assert!(blend_at(&frames, 0, 3, &both)[0] < 240);
    }

    #[test]
    fn twice_reaches_twice_as_far() {
        assert_eq!(
            Settings::of(50, 100, 0, 2).reach,
            2 * Settings::of(50, 100, 0, 1).reach
        );
    }
}

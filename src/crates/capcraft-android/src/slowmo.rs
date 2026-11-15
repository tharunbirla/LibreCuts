// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! Smooth slow motion (Optical flow): a slowed clip's stretch of its file
//! made again with the frames that slow motion lacks drawn in between, so
//! it glides instead of stepping.
//!
//! The copy runs at the file's rate divided by the clip's speed, so at
//! that speed every one of its frames lands on a frame of the timeline. An
//! in-between frame is drawn from the frames either side: how each block of
//! the picture moved from one to the next is found on small grey copies,
//! and each pixel is read from both frames shifted along that motion by
//! its share of the way, then blended. The copy carries the stretch's
//! sound, and the clip keeps its speed and shows the copy from its start.
//! The timeline keeps which file and in-point each copy stands for
//! (`capcraftSmoothed`), so the clip can go back to them.

use std::hash::{Hash, Hasher};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, OnceLock};

use concat_core::Frame;
use concat_core::time::{FrameRate, Rational};
use concat_media::{DecodeOptions, Decoder, FrameSource};
use concat_project::Command;
use serde_json::{Value, json};

use crate::MobileError;
use crate::session::MobileSession;
use crate::speech::SpeechListener;
use crate::stabilize::{CopyWriter, Gray, shrink};

/// Where the timeline keeps, for each smoothed copy's path, the file and
/// in-point it stands for.
pub const SMOOTHED: &str = "capcraftSmoothed";

/// A block's side, in the small copies' pixels.
const BLOCK: usize = 8;
/// How far a block may have moved between frames, in those pixels.
const SEARCH: i32 = 6;
/// The fastest the copy may run, frames a second.
const MOST_FPS: f64 = 240.0;

fn cancelled() -> &'static AtomicBool {
    static CANCEL: OnceLock<AtomicBool> = OnceLock::new();
    CANCEL.get_or_init(|| AtomicBool::new(false))
}

/// Asks the smoothing in hand to stop after the frame it is on.
#[uniffi::export]
pub fn cancel_smooth_slow_motion() {
    cancelled().store(true, Ordering::Relaxed);
}

/// How each block of the picture moved from one frame to the next: a grid
/// of moves in full-size pixels, one per block.
pub(crate) struct Flow {
    cols: usize,
    rows: usize,
    /// Full-size pixels a block's side covers.
    side: f32,
    moves: Vec<(f32, f32)>,
}

impl Flow {
    /// The move at full-size pixel (`x`, `y`), blended from the blocks
    /// around it so the picture does not tear along their edges.
    fn at(&self, x: f32, y: f32) -> (f32, f32) {
        let gx = (x / self.side - 0.5).clamp(0.0, (self.cols - 1) as f32);
        let gy = (y / self.side - 0.5).clamp(0.0, (self.rows - 1) as f32);
        let (x0, y0) = (gx.floor() as usize, gy.floor() as usize);
        let (x1, y1) = ((x0 + 1).min(self.cols - 1), (y0 + 1).min(self.rows - 1));
        let (fx, fy) = (gx - x0 as f32, gy - y0 as f32);
        let get = |cx: usize, cy: usize| self.moves[cy * self.cols + cx];
        let mix =
            |a: (f32, f32), b: (f32, f32), t: f32| (a.0 + (b.0 - a.0) * t, a.1 + (b.1 - a.1) * t);
        mix(
            mix(get(x0, y0), get(x1, y0), fx),
            mix(get(x0, y1), get(x1, y1), fx),
            fy,
        )
    }
}

/// The block moves from `a` to `b`: for each block of `a`, the offset
/// into `b` with the least summed difference (the smaller move of equals).
pub(crate) fn flow(a: &Gray, b: &Gray) -> Flow {
    let (w, h) = a.size();
    let cols = (w / BLOCK).max(1);
    let rows = (h / BLOCK).max(1);
    let mut moves = Vec::with_capacity(cols * rows);
    for row in 0..rows {
        for col in 0..cols {
            let (bx, by) = ((col * BLOCK) as i32, (row * BLOCK) as i32);
            let mut best = (0i32, 0i32, u64::MAX);
            for dy in -SEARCH..=SEARCH {
                for dx in -SEARCH..=SEARCH {
                    if bx + dx < 0
                        || by + dy < 0
                        || bx + dx + BLOCK as i32 > w as i32
                        || by + dy + BLOCK as i32 > h as i32
                    {
                        continue;
                    }
                    let mut total = 0u64;
                    for y in 0..BLOCK as i32 {
                        for x in 0..BLOCK as i32 {
                            let here = a.at((bx + x) as usize, (by + y) as usize);
                            let there = b.at((bx + dx + x) as usize, (by + dy + y) as usize);
                            total += u64::from(here.abs_diff(there));
                        }
                    }
                    let nearer = dx * dx + dy * dy < best.0 * best.0 + best.1 * best.1;
                    if total < best.2 || (total == best.2 && nearer) {
                        best = (dx, dy, total);
                    }
                }
            }
            let scale = a.factor() as f32;
            moves.push((best.0 as f32 * scale, best.1 as f32 * scale));
        }
    }
    Flow {
        cols,
        rows,
        side: (BLOCK * a.factor()) as f32,
        moves,
    }
}

/// `frame`'s pixels as eight-bit RGBA.
fn rgba8(frame: &Frame) -> Vec<u8> {
    if frame.bytes_per_pixel() == 4 {
        return frame.pixels().to_vec();
    }
    frame.pixels().chunks_exact(2).map(|pair| pair[1]).collect()
}

/// The frame `t` of the way from `a` to `b` (eight-bit RGBA, `width` by
/// `height`), each pixel read from both along the move `flow` gives it.
pub(crate) fn between(
    a: &[u8],
    b: &[u8],
    width: usize,
    height: usize,
    flow: &Flow,
    t: f32,
) -> Vec<u8> {
    let mut out = vec![0u8; width * height * 4];
    let threads = std::thread::available_parallelism()
        .map(|n| n.get().min(6))
        .unwrap_or(2);
    let band = height.div_ceil(threads);
    let read = |pixels: &[u8], x: f32, y: f32, c: usize| -> f32 {
        let x = x.clamp(0.0, (width - 1) as f32);
        let y = y.clamp(0.0, (height - 1) as f32);
        let (x0, y0) = (x.floor() as usize, y.floor() as usize);
        let (x1, y1) = ((x0 + 1).min(width - 1), (y0 + 1).min(height - 1));
        let (fx, fy) = (x - x0 as f32, y - y0 as f32);
        let p = |xx: usize, yy: usize| f32::from(pixels[(yy * width + xx) * 4 + c]);
        let top = p(x0, y0) + (p(x1, y0) - p(x0, y0)) * fx;
        let bottom = p(x0, y1) + (p(x1, y1) - p(x0, y1)) * fx;
        top + (bottom - top) * fy
    };
    std::thread::scope(|scope| {
        for (part, rows) in out.chunks_mut(band * width * 4).enumerate() {
            scope.spawn(move || {
                let first = part * band;
                for (row, line) in rows.chunks_mut(width * 4).enumerate() {
                    let y = (first + row) as f32;
                    for x in 0..width {
                        let (mx, my) = flow.at(x as f32, y);
                        let (ax, ay) = (x as f32 - mx * t, y - my * t);
                        let (bx, by) = (x as f32 + mx * (1.0 - t), y + my * (1.0 - t));
                        for c in 0..4 {
                            let v = read(a, ax, ay, c) * (1.0 - t) + read(b, bx, by, c) * t;
                            line[x * 4 + c] = v.round().clamp(0.0, 255.0) as u8;
                        }
                    }
                }
            });
        }
    });
    out
}

/// What a smoothing needs: the file, the stretch of it, the clip's speed.
pub(crate) struct Stretch {
    pub media: String,
    /// Where in the file it starts, seconds.
    pub from: f64,
    /// How much of the file it covers, seconds.
    pub span: f64,
    pub speed: f64,
}

/// Writes the smoothed copy of `stretch` to `target`; `step` hears the
/// fraction done and answers whether to go on.
pub(crate) fn smooth_copy(
    stretch: &Stretch,
    target: &Path,
    step: &dyn Fn(f32) -> bool,
) -> Result<(), String> {
    let info = concat_media::probe(&stretch.media).map_err(|error| error.to_string())?;
    let video = info
        .video
        .as_ref()
        .ok_or_else(|| "The clip has no picture.".to_owned())?;
    let source_fps = video.frame_rate.fps().as_f64().max(1.0);
    let out_fps = (source_fps / stretch.speed.clamp(0.01, 1.0)).min(MOST_FPS);
    let rate = FrameRate::new(Rational::new((out_fps * 1000.0).round() as i64, 1000));
    let needed = (stretch.span * source_fps).ceil() as u64 + 2;
    let options = DecodeOptions::default()
        .in_software()
        .threaded(2)
        .starting_at(Rational::new(
            (stretch.from * 1_000_000.0).round() as i64,
            1_000_000,
        ))
        .limited_to(needed);
    let mut decoder = Decoder::open(&stretch.media, &options).map_err(|error| error.to_string())?;
    let frames_out = (stretch.span * out_fps).round().max(1.0) as usize;
    let silent = target.with_extension("picture.mp4");
    let mut writer = CopyWriter::create(
        &stretch.media,
        &silent,
        (video.width, video.height),
        rate,
        false,
    )?;
    // the two source frames either side of where the copy has got to
    let mut earlier: Option<(usize, Frame, Vec<u8>, Gray)> = None;
    let mut later: Option<(usize, Frame, Vec<u8>, Gray)> = None;
    let mut read = 0usize;
    let mut motion: Option<(usize, Flow)> = None;
    let mut ended = false;
    for k in 0..frames_out {
        let at = k as f64 * source_fps / out_fps;
        let index = at.floor() as usize;
        let t = (at - index as f64) as f32;
        // read on until the frames either side are in hand
        while !ended && later.as_ref().is_none_or(|(i, ..)| *i <= index) {
            match decoder.next_frame() {
                Ok(Some(frame)) => {
                    let pixels = rgba8(&frame);
                    let small = shrink(&frame);
                    earlier = later.take();
                    later = Some((read, frame, pixels, small));
                    read += 1;
                }
                Ok(None) => ended = true,
                Err(error) => {
                    writer.abandon();
                    return Err(error.to_string());
                }
            }
        }
        let frame = match (&earlier, &later) {
            (Some((i, a, a_pixels, a_small)), Some((_, _, b_pixels, b_small)))
                if *i == index && t > 0.02 =>
            {
                if motion.as_ref().is_none_or(|(m, _)| *m != index) {
                    motion = Some((index, flow(a_small, b_small)));
                }
                let (_, f) = motion.as_ref().expect("just made");
                let pixels = between(
                    a_pixels,
                    b_pixels,
                    a.width() as usize,
                    a.height() as usize,
                    f,
                    t,
                );
                Frame::from_rgba(a.width(), a.height(), pixels).unwrap_or_else(|| a.clone())
            }
            (Some((i, a, ..)), _) if *i == index => a.clone(),
            (_, Some((_, b, ..))) => b.clone(),
            (Some((_, a, ..)), None) => a.clone(),
            (None, None) => break,
        };
        if let Err(error) = writer.write(frame) {
            writer.abandon();
            return Err(error);
        }
        if !step((k as f64 / frames_out as f64 * 0.9) as f32) || cancelled().load(Ordering::Relaxed)
        {
            writer.abandon();
            return Err("Optical flow was cancelled.".to_owned());
        }
    }
    drop(decoder);
    writer.finish()?;
    // the stretch's own sound under it, when the file has any
    let finished = if info.audio.is_some() {
        let sound = target.with_extension("sound.m4a");
        let clip = concat_media::audio::AudioClip {
            path: PathBuf::from(&stretch.media),
            stream: None,
            start: 0.0,
            duration: stretch.span,
            source_start: stretch.from,
            speed: 1.0,
            preserve_pitch: false,
            volume: 1.0,
            volume_curve: Default::default(),
            fade_in: 0.0,
            fade_out: 0.0,
            filter_chain: String::new(),
        };
        let made = concat_media::audio::mix_to_file(&[clip], stretch.span, &sound)
            .and_then(|()| concat_media::audio::mux(&silent, &sound, target))
            .map_err(|error| error.to_string());
        let _ = std::fs::remove_file(&sound);
        let _ = std::fs::remove_file(&silent);
        made
    } else {
        std::fs::rename(&silent, target).map_err(|error| format!("{}: {error}", target.display()))
    };
    step(1.0);
    finished
}

/// Where the smoothed copy of `stretch` lives under `project`.
fn target_for(project: &Path, stretch: &Stretch) -> Option<PathBuf> {
    let meta = std::fs::metadata(&stretch.media).ok()?;
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    stretch.media.hash(&mut hasher);
    meta.len().hash(&mut hasher);
    if let Ok(modified) = meta.modified()
        && let Ok(since) = modified.duration_since(std::time::UNIX_EPOCH)
    {
        since.as_secs().hash(&mut hasher);
    }
    for value in [stretch.from, stretch.span, stretch.speed] {
        ((value * 1000.0).round() as i64).hash(&mut hasher);
    }
    Some(
        project
            .join("cache")
            .join(format!("smooth-{:016x}.mp4", hasher.finish())),
    )
}

#[uniffi::export]
impl MobileSession {
    /// Smooths a slowed clip's motion (`on`) on a thread of its own,
    /// reporting through `listener`, and has the clip show the smoothed
    /// copy; `on` false puts back the file and in-point it stood for.
    pub fn smooth_slow_motion(
        self: Arc<Self>,
        clip_id: String,
        on: bool,
        listener: Arc<dyn SpeechListener>,
    ) -> Result<(), MobileError> {
        let (current, source_start, duration, speed, project) = self.with_session(|session| {
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
            Ok((
                media.path.clone(),
                clip.source_start,
                clip.duration,
                clip.speed,
                session.path().to_owned(),
            ))
        })?;
        let mut made = match serde_json::from_str::<Value>(&self.extra(SMOOTHED.to_owned())) {
            Ok(Value::Object(map)) => map,
            _ => serde_json::Map::new(),
        };
        let stood_for = made.get(&current).cloned();
        if !on {
            let Some(entry) = stood_for else {
                listener.on_done(None);
                return Ok(());
            };
            let original = entry
                .get("original")
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_owned();
            let item = concat_host::media::probe(&original)?.to_new_media();
            let view = self.run(vec![Command::ReplaceClipMedia {
                clip_id,
                item,
                source_start: entry.get("sourceStart").and_then(Value::as_f64),
                enhanced: false,
                offset: 0.0,
            }])?;
            listener.on_done(Some(view));
            return Ok(());
        }
        if stood_for.is_some() {
            listener.on_done(None);
            return Ok(());
        }
        if speed > 0.95 {
            return Err(MobileError::Failed("Slow the clip down first.".to_owned()));
        }
        let stretch = Stretch {
            media: current.clone(),
            from: source_start,
            span: duration * speed,
            speed,
        };
        let target = target_for(Path::new(&project), &stretch)
            .ok_or_else(|| MobileError::Failed("The clip's file cannot be read.".to_owned()))?;
        cancelled().store(false, Ordering::Relaxed);
        std::thread::Builder::new()
            .name("capcraft-slowmo".to_owned())
            .spawn(move || {
                let written = if target.is_file() {
                    Ok(())
                } else {
                    smooth_copy(&stretch, &target, &|fraction| {
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
                            json!({ "original": current, "sourceStart": source_start }),
                        );
                        self.run(vec![
                            Command::ReplaceClipMedia {
                                clip_id,
                                item: info.to_new_media(),
                                source_start: Some(0.0),
                                enhanced: false,
                                offset: 0.0,
                            },
                            Command::SetTimelineExtra {
                                key: SMOOTHED.to_owned(),
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

#[cfg(test)]
mod tests {
    use super::*;

    /// A dark picture with a bright square at (x, y), as eight-bit RGBA.
    fn picture(width: usize, height: usize, x: usize, y: usize) -> Frame {
        let mut pixels = vec![20u8; width * height * 4];
        for py in y..(y + 40).min(height) {
            for px in x..(x + 40).min(width) {
                let at = (py * width + px) * 4;
                pixels[at..at + 3].copy_from_slice(&[230, 230, 230]);
            }
        }
        for alpha in pixels.chunks_exact_mut(4) {
            alpha[3] = 255;
        }
        Frame::from_rgba(width as u32, height as u32, pixels).expect("a frame")
    }

    /// Where the bright square's middle is in `pixels`.
    fn centre(pixels: &[u8], width: usize) -> (f64, f64) {
        let (mut sx, mut sy, mut n) = (0.0, 0.0, 0.0);
        for (i, px) in pixels.chunks_exact(4).enumerate() {
            if px[0] > 125 {
                sx += (i % width) as f64;
                sy += (i / width) as f64;
                n += 1.0;
            }
        }
        (sx / n, sy / n)
    }

    #[test]
    fn the_frame_between_shows_the_square_halfway() {
        let (w, h) = (320, 180);
        let a = picture(w, h, 100, 70);
        let b = picture(w, h, 110, 70);
        let f = flow(&shrink(&a), &shrink(&b));
        let mid = between(a.pixels(), b.pixels(), w, h, &f, 0.5);
        let (x, y) = centre(&mid, w);
        // the square sits halfway rather than as two faded copies
        assert!((x - 124.5).abs() < 3.0, "{x}");
        assert!((y - 89.5).abs() < 3.0, "{y}");
    }

    #[test]
    fn a_still_picture_stays_still() {
        let (w, h) = (320, 180);
        let a = picture(w, h, 100, 70);
        let f = flow(&shrink(&a), &shrink(&a));
        assert!(f.moves.iter().all(|m| *m == (0.0, 0.0)));
        assert_eq!(
            between(a.pixels(), a.pixels(), w, h, &f, 0.3),
            a.pixels().to_vec()
        );
    }
}

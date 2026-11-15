// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! Stabilize: a steadied copy of a shaky clip's file, which the clip then
//! shows in one undo step.
//!
//! Two passes over the footage. The first reads every frame small and grey
//! and finds how far the picture moved from the frame before (the offset
//! that matches the two best). Those steps, added up, are the path the
//! camera shook along; a moving average of it is the path a steady hand
//! would have taken. The second pass shifts each frame by the difference,
//! zoomed in just enough that no edge shows, and writes the copy with the
//! original's sound. Only movement across and up and down is undone, which
//! is most of what a hand adds.
//!
//! The copy is named after the file and the strength, in the project's
//! cache, so asking twice costs nothing. The timeline keeps which file each
//! copy was made from (`capcraftStabilized`), so the clip can go back to it.

use std::hash::{Hash, Hasher};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, OnceLock};

use concat_core::Frame;
use concat_media::{
    DecodeOptions, Decoder, EncodeOptions, Encoder, FrameSink, FrameSource, RateMode, VideoCodec,
};
use concat_project::Command;
use serde_json::{Value, json};

use crate::MobileError;
use crate::session::MobileSession;
use crate::speech::SpeechListener;

/// Where the timeline keeps, for each steadied copy's path, the file it
/// was made from and how strongly.
pub const STABILIZED: &str = "capcraftStabilized";

/// The analysis frames' width, about: the frame is shrunk by a whole factor.
const SMALL: u32 = 160;
/// How far, in analysis pixels, one frame may have moved from the last.
const SEARCH: i32 = 12;

/// The strengths: how many frames either side the steady path averages
/// over, and the most the picture may be zoomed to hide the edges.
fn strength(level: u32) -> (usize, f64) {
    match level {
        1 => (8, 1.08),
        3 => (30, 1.3),
        _ => (15, 1.18),
    }
}

/// The steadying in hand; `cancel_stabilize` stops it.
fn running() -> &'static AtomicBool {
    static CANCEL: OnceLock<AtomicBool> = OnceLock::new();
    CANCEL.get_or_init(|| AtomicBool::new(false))
}

/// Asks the steadying in hand to stop after the frame it is on.
#[uniffi::export]
pub fn cancel_stabilize() {
    running().store(true, Ordering::Relaxed);
}

#[uniffi::export]
impl MobileSession {
    /// Steadies the footage of `clip_id` at `level` (1 least cropping, 2
    /// recommended, 3 most stable) on a thread of its own, reporting through
    /// `listener`, and points the clip at the steadied copy; level 0 points
    /// it back at the file the copy was made from.
    pub fn stabilize(
        self: Arc<Self>,
        clip_id: String,
        level: u32,
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
        let mut made = stabilized(&self);
        // the file to steady, or to go back to: never a copy of a copy
        let original = made
            .get(&current)
            .and_then(|entry| entry.get("original"))
            .and_then(Value::as_str)
            .map(str::to_owned)
            .unwrap_or_else(|| current.clone());
        if level == 0 {
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
        let target = target_for(Path::new(&project), &original, level)
            .ok_or_else(|| MobileError::Failed("The clip's file cannot be read.".to_owned()))?;
        running().store(false, Ordering::Relaxed);
        std::thread::Builder::new()
            .name("capcraft-stabilize".to_owned())
            .spawn(move || {
                let written = if target.is_file() {
                    Ok(())
                } else {
                    steady(&original, &target, level, &|fraction| {
                        listener.on_progress(fraction, "work".to_owned());
                        !running().load(Ordering::Relaxed)
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
                            json!({ "original": original, "level": level }),
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
                                key: STABILIZED.to_owned(),
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

/// What the timeline keeps under [`STABILIZED`].
fn stabilized(session: &MobileSession) -> serde_json::Map<String, Value> {
    match serde_json::from_str::<Value>(&session.extra(STABILIZED.to_owned())) {
        Ok(Value::Object(map)) => map,
        _ => serde_json::Map::new(),
    }
}

/// Where the steadied copy of `media` at `level` lives under `project`:
/// named by the file's path, size and modification time. None for a file
/// that cannot be stat'ed.
pub(crate) fn target_for(project: &Path, media: &str, level: u32) -> Option<PathBuf> {
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
            .join(format!("stabilize-{:016x}-{level}.mp4", hasher.finish())),
    )
}

/// A frame shrunk and grey, for finding movement.
pub(crate) struct Gray {
    width: usize,
    height: usize,
    luma: Vec<u8>,
    /// How many source pixels one of these stands for, across and down.
    factor: usize,
}

impl Gray {
    /// Its width and height, in its own pixels.
    pub(crate) fn size(&self) -> (usize, usize) {
        (self.width, self.height)
    }

    /// How many source pixels one of its pixels stands for, across and down.
    pub(crate) fn factor(&self) -> usize {
        self.factor
    }

    /// Its brightness at (`x`, `y`), which must be inside it.
    pub(crate) fn at(&self, x: usize, y: usize) -> u8 {
        self.luma[y * self.width + x]
    }

    /// The picture's average brightness, 0 to 255.
    pub(crate) fn mean(&self) -> f64 {
        let total: u64 = self.luma.iter().map(|&v| u64::from(v)).sum();
        total as f64 / self.luma.len().max(1) as f64
    }
}

/// `frame` shrunk by a whole factor to about [`SMALL`] wide, as luma.
pub(crate) fn shrink(frame: &Frame) -> Gray {
    let (width, height) = (frame.width() as usize, frame.height() as usize);
    let factor = (width / SMALL as usize).max(1);
    let (w, h) = (width / factor, height / factor);
    let step = frame.bytes_per_pixel();
    // eight-bit RGBA, or the high byte of each sixteen-bit channel
    let high = if step == 8 { 1 } else { 0 };
    let pixels = frame.pixels();
    let mut luma = vec![0u8; w * h];
    for y in 0..h {
        for x in 0..w {
            let mut sum = 0u32;
            for dy in 0..factor {
                let row = (y * factor + dy) * width;
                for dx in 0..factor {
                    let at = (row + x * factor + dx) * step;
                    let channel = |c: usize| u32::from(pixels[at + c * (step / 4) + high]);
                    sum += (channel(0) * 77 + channel(1) * 150 + channel(2) * 29) >> 8;
                }
            }
            luma[y * w + x] = (sum / (factor * factor) as u32) as u8;
        }
    }
    Gray {
        width: w,
        height: h,
        luma,
        factor,
    }
}

/// How far the picture moved from `a` to `b`, in source pixels: the offset
/// at which `b` matches `a` best (the least summed difference over the
/// middle of the frame), refined between whole pixels.
pub(crate) fn shift(a: &Gray, b: &Gray) -> (f64, f64) {
    let (w, h) = (a.width as i32, a.height as i32);
    if w <= 2 * SEARCH + 2 || h <= 2 * SEARCH + 2 || a.width != b.width || a.height != b.height {
        return (0.0, 0.0);
    }
    let cost = |dx: i32, dy: i32| -> u64 {
        let mut total = 0u64;
        for y in (SEARCH..h - SEARCH).step_by(2) {
            for x in (SEARCH..w - SEARCH).step_by(2) {
                let now = b.luma[(y * w + x) as usize];
                let then = a.luma[((y - dy) * w + (x - dx)) as usize];
                total += u64::from(now.abs_diff(then));
            }
        }
        total
    };
    let mut best = (0, 0, u64::MAX);
    for dy in -SEARCH..=SEARCH {
        for dx in -SEARCH..=SEARCH {
            let c = cost(dx, dy);
            if c < best.2 {
                best = (dx, dy, c);
            }
        }
    }
    let (bx, by, c0) = best;
    // a parabola through the best and its neighbours puts the low point
    // between pixels
    let refine = |minus: u64, plus: u64| -> f64 {
        let (m, p, z) = (minus as f64, plus as f64, c0 as f64);
        let curve = m + p - 2.0 * z;
        if curve > 0.0 {
            ((m - p) / (2.0 * curve)).clamp(-0.5, 0.5)
        } else {
            0.0
        }
    };
    let fx = if bx.abs() < SEARCH {
        refine(cost(bx - 1, by), cost(bx + 1, by))
    } else {
        0.0
    };
    let fy = if by.abs() < SEARCH {
        refine(cost(bx, by - 1), cost(bx, by + 1))
    } else {
        0.0
    };
    let factor = a.factor as f64;
    ((f64::from(bx) + fx) * factor, (f64::from(by) + fy) * factor)
}

/// For each frame, how far to move it so the picture follows a steady
/// path: the moving average of the shaken path (over `radius` frames
/// either side) less the shaken path itself. `steps[i]` is how far frame
/// `i` moved from frame `i - 1`; the first is ignored.
pub(crate) fn corrections(steps: &[(f64, f64)], radius: usize) -> Vec<(f64, f64)> {
    let mut path = Vec::with_capacity(steps.len());
    let (mut x, mut y) = (0.0, 0.0);
    for (index, (dx, dy)) in steps.iter().enumerate() {
        if index > 0 {
            x += dx;
            y += dy;
        }
        path.push((x, y));
    }
    let n = path.len();
    (0..n)
        .map(|index| {
            let from = index.saturating_sub(radius);
            let to = (index + radius + 1).min(n);
            let count = (to - from) as f64;
            let (sx, sy) = path[from..to]
                .iter()
                .fold((0.0, 0.0), |(ax, ay), (px, py)| (ax + px, ay + py));
            (sx / count - path[index].0, sy / count - path[index].1)
        })
        .collect()
}

/// `frame` moved by (`dx`, `dy`) source pixels and zoomed by `zoom` about
/// its middle, read with bilinear weights; the output is eight-bit RGBA at
/// the frame's size.
pub(crate) fn warp(frame: &Frame, dx: f64, dy: f64, zoom: f64) -> Frame {
    let (width, height) = (frame.width() as usize, frame.height() as usize);
    let step = frame.bytes_per_pixel();
    let high = if step == 8 { 1 } else { 0 };
    let source = frame.pixels();
    let mut out = vec![0u8; width * height * 4];
    let (cx, cy) = (width as f64 / 2.0, height as f64 / 2.0);
    let threads = std::thread::available_parallelism()
        .map(|n| n.get().min(6))
        .unwrap_or(2);
    let band = height.div_ceil(threads);
    std::thread::scope(|scope| {
        for (part, rows) in out.chunks_mut(band * width * 4).enumerate() {
            scope.spawn(move || {
                let first = part * band;
                for (row, line) in rows.chunks_mut(width * 4).enumerate() {
                    let y = (first + row) as f64 + 0.5;
                    let sy = ((y - cy) / zoom + cy - dy - 0.5).clamp(0.0, (height - 1) as f64);
                    let y0 = sy.floor() as usize;
                    let y1 = (y0 + 1).min(height - 1);
                    let fy = sy - y0 as f64;
                    for x in 0..width {
                        let sx = ((x as f64 + 0.5 - cx) / zoom + cx - dx - 0.5)
                            .clamp(0.0, (width - 1) as f64);
                        let x0 = sx.floor() as usize;
                        let x1 = (x0 + 1).min(width - 1);
                        let fx = sx - x0 as f64;
                        for c in 0..4 {
                            let at = |xx: usize, yy: usize| {
                                f64::from(source[(yy * width + xx) * step + c * (step / 4) + high])
                            };
                            let top = at(x0, y0) * (1.0 - fx) + at(x1, y0) * fx;
                            let bottom = at(x0, y1) * (1.0 - fx) + at(x1, y1) * fx;
                            line[x * 4 + c] = (top * (1.0 - fy) + bottom * fy).round() as u8;
                        }
                    }
                }
            });
        }
    });
    Frame::from_rgba(width as u32, height as u32, out).unwrap_or_else(|| frame.clone())
}

/// Writes the steadied copy of `media` to `target`; `step` hears the
/// fraction done and answers whether to go on.
pub(crate) fn steady(
    media: &str,
    target: &Path,
    level: u32,
    step: &dyn Fn(f32) -> bool,
) -> Result<(), String> {
    let info = concat_media::probe(media).map_err(|error| error.to_string())?;
    let video = info
        .video
        .as_ref()
        .ok_or_else(|| "The clip has no picture to steady.".to_owned())?;
    let rate = video.frame_rate;
    let expected = info
        .duration
        .map(|duration| duration.as_f64() * rate.fps().as_f64())
        .unwrap_or(0.0)
        .max(1.0);
    let options = DecodeOptions::default().in_software().threaded(2);

    // the first pass: how the picture moved, frame to frame
    let mut decoder = Decoder::open(media, &options).map_err(|error| error.to_string())?;
    let mut steps = Vec::new();
    let mut last: Option<Gray> = None;
    let mut size = (0u32, 0u32);
    while let Some(frame) = decoder.next_frame().map_err(|error| error.to_string())? {
        size = (frame.width(), frame.height());
        let small = shrink(&frame);
        steps.push(
            last.as_ref()
                .map_or((0.0, 0.0), |before| shift(before, &small)),
        );
        last = Some(small);
        if !step((steps.len() as f64 / expected * 0.35).min(0.35) as f32) {
            return Err("Stabilize was cancelled.".to_owned());
        }
    }
    drop(decoder);
    if steps.is_empty() {
        return Err("The clip has no picture to steady.".to_owned());
    }
    let (radius, most) = strength(level);
    let mut moves = corrections(&steps, radius);
    // zoom in just enough to hide the edges the moves bring in, up to the
    // strength's limit, and hold each move inside what that zoom hides
    let (width, height) = (f64::from(size.0), f64::from(size.1));
    let reach = moves.iter().fold(0.0f64, |m, (x, y)| {
        m.max(2.0 * x.abs() / width).max(2.0 * y.abs() / height)
    });
    let zoom = (1.0 + reach * 1.05).clamp(1.0, most);
    let (room_x, room_y) = (
        width * (1.0 - 1.0 / zoom) / 2.0,
        height * (1.0 - 1.0 / zoom) / 2.0,
    );
    for (x, y) in &mut moves {
        *x = x.clamp(-room_x, room_x);
        *y = y.clamp(-room_y, room_y);
    }

    // the second pass: each frame moved and written
    let mut writer = CopyWriter::create(media, target, size, rate, info.audio.is_some())?;
    let mut decoder = Decoder::open(media, &options).map_err(|error| error.to_string())?;
    let mut index = 0usize;
    while let Some(frame) = decoder.next_frame().map_err(|error| error.to_string())? {
        let (dx, dy) = moves.get(index).copied().unwrap_or((0.0, 0.0));
        if let Err(error) = writer.write(warp(&frame, dx, dy, zoom)) {
            writer.abandon();
            return Err(error);
        }
        index += 1;
        if !step((0.35 + index as f64 / steps.len() as f64 * 0.6).min(0.95) as f32) {
            writer.abandon();
            return Err("Stabilize was cancelled.".to_owned());
        }
    }
    drop(decoder);
    let finished = writer.finish();
    step(1.0);
    finished
}

/// A copy of a clip's file being written frame by frame, in H.264 at the
/// suggested rate, which takes the original's sound when it is done.
pub(crate) struct CopyWriter {
    encoder: Encoder,
    media: PathBuf,
    target: PathBuf,
    partial_video: PathBuf,
    partial_muxed: PathBuf,
    size: (u32, u32),
    audio: bool,
}

impl CopyWriter {
    /// Starts the copy of `media` at `target`, `size` (rounded down to even)
    /// at `rate`; `audio` says whether the original has sound to carry over.
    pub(crate) fn create(
        media: &str,
        target: &Path,
        size: (u32, u32),
        rate: concat_core::time::FrameRate,
        audio: bool,
    ) -> Result<Self, String> {
        if let Some(folder) = target.parent() {
            std::fs::create_dir_all(folder)
                .map_err(|error| format!("{}: {error}", folder.display()))?;
        }
        let partial_video = target.with_extension("part.mp4");
        let partial_muxed = target.with_extension("muxed.part.mp4");
        let (out_w, out_h) = (size.0 & !1, size.1 & !1);
        let fps = rate.fps().as_f64().round().max(1.0) as u32;
        let encoder = Encoder::create(
            &partial_video,
            out_w,
            out_h,
            rate,
            &EncodeOptions {
                codec: VideoCodec::H264,
                preset: "medium".to_owned(),
                crf: 18,
                // a phone's encoder keeps to a rate: the suggested one, already generous, since the
                // copy stands in for the original for as long as the project lives
                rate_mode: RateMode::Cbr,
                bitrate_kbps: crate::export::suggested_bitrate_kbps(out_w, out_h, fps),
                ten_bit: false,
                color_range: concat_media::ColorRange::Limited,
                hardware: true,
                threads: 2,
                software_only: false,
            },
        )
        .map_err(|error| error.to_string())?;
        Ok(Self {
            encoder,
            media: PathBuf::from(media),
            target: target.to_owned(),
            partial_video,
            partial_muxed,
            size: (out_w, out_h),
            audio,
        })
    }

    /// Writes the next frame, cut to the copy's even size if it is larger.
    pub(crate) fn write(&mut self, frame: Frame) -> Result<(), String> {
        let frame = if (frame.width(), frame.height()) == self.size {
            frame
        } else {
            crop(&frame, self.size.0, self.size.1)
        };
        self.encoder
            .write_frame(&frame)
            .map_err(|error| error.to_string())
    }

    /// Stops, leaving nothing behind.
    pub(crate) fn abandon(self) {
        drop(self.encoder);
        let _ = std::fs::remove_file(&self.partial_video);
    }

    /// Finishes the picture, lays the original's sound under it and puts
    /// the copy in place.
    pub(crate) fn finish(mut self) -> Result<(), String> {
        let encoded = self.encoder.finish().map_err(|error| error.to_string());
        drop(self.encoder);
        if let Err(error) = encoded {
            let _ = std::fs::remove_file(&self.partial_video);
            return Err(error);
        }
        let finished = if self.audio {
            let muxed =
                concat_media::audio::mux(&self.partial_video, &self.media, &self.partial_muxed)
                    .map_err(|error| error.to_string());
            let _ = std::fs::remove_file(&self.partial_video);
            muxed.and_then(|()| {
                std::fs::rename(&self.partial_muxed, &self.target)
                    .map_err(|error| format!("{}: {error}", self.target.display()))
            })
        } else {
            std::fs::rename(&self.partial_video, &self.target)
                .map_err(|error| format!("{}: {error}", self.target.display()))
        };
        if finished.is_err() {
            let _ = std::fs::remove_file(&self.partial_muxed);
        }
        finished
    }
}

/// `frame` cut to `width` by `height` from its top-left.
fn crop(frame: &Frame, width: u32, height: u32) -> Frame {
    let mut out = Frame::black(width, height);
    let bytes = width as usize * 4;
    let stride = frame.width() as usize * 4;
    let source = frame.pixels();
    let pixels = out.pixels_mut();
    for y in 0..height as usize {
        pixels[y * bytes..(y + 1) * bytes].copy_from_slice(&source[y * stride..y * stride + bytes]);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::{corrections, shift, shrink, warp};
    use concat_core::Frame;

    /// A picture with detail everywhere, moved by (`ox`, `oy`) pixels.
    fn pattern(width: u32, height: u32, ox: i32, oy: i32) -> Frame {
        let mut pixels = Vec::with_capacity((width * height * 4) as usize);
        for y in 0..height as i32 {
            for x in 0..width as i32 {
                let (u, v) = (f64::from(x - ox), f64::from(y - oy));
                let value = 128.0
                    + 60.0 * (u / 23.0).sin() * (v / 17.0).cos()
                    + 50.0 * ((u + v) / 41.0).sin();
                let g = value.clamp(0.0, 255.0) as u8;
                pixels.extend_from_slice(&[g, g / 2, 255 - g, 255]);
            }
        }
        Frame::from_rgba(width, height, pixels).expect("sized")
    }

    #[test]
    fn a_moved_picture_is_found_where_it_went() {
        let a = shrink(&pattern(640, 360, 0, 0));
        let b = shrink(&pattern(640, 360, 12, -8));
        let (dx, dy) = shift(&a, &b);
        assert!((dx - 12.0).abs() < 2.5, "{dx}");
        assert!((dy + 8.0).abs() < 2.5, "{dy}");
        let (sx, sy) = shift(&a, &a);
        assert!(sx.abs() < 0.5 && sy.abs() < 0.5);
    }

    #[test]
    fn a_steady_pan_needs_no_correction_but_a_jolt_does() {
        // a pan of two pixels a frame: the average follows it
        let pan = vec![(2.0, 0.0); 40];
        let still = corrections(&pan, 5);
        assert!(
            still[10..30]
                .iter()
                .all(|(x, y)| x.abs() < 1e-9 && y.abs() < 1e-9)
        );
        // one jolt of ten pixels, there and back
        let mut jolt = vec![(0.0, 0.0); 40];
        jolt[20] = (10.0, 0.0);
        jolt[21] = (-10.0, 0.0);
        let moves = corrections(&jolt, 5);
        assert!(
            moves[20].0 < -8.0,
            "the jolted frame is pulled back: {:?}",
            moves[20]
        );
    }

    #[test]
    fn moving_a_frame_moves_its_picture() {
        let frame = pattern(64, 48, 0, 0);
        let moved = warp(&frame, 3.0, 0.0, 1.0);
        let at = |f: &Frame, x: usize, y: usize| f.pixels()[(y * 64 + x) * 4];
        assert_eq!(at(&moved, 30, 20), at(&frame, 27, 20));
    }
}

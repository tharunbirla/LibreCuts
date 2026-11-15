// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! Tracking: a title or sticker made to follow something in the picture
//! under it; and camera tracking, a clip panned and zoomed in on itself so
//! something in it stays in the middle of the frame.
//!
//! The main-track footage under the follower is read small and grey, a
//! fixed number of looks a second, from where the follower starts to where
//! it (or the footage) ends. A patch around the chosen point is looked for
//! in each look near where it was in the last (the offset with the least
//! summed difference), and the path it takes becomes the follower's
//! position keys, as one undo step.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use concat_core::time::{FrameRate, Rational};
use concat_media::{DecodeOptions, Decoder, FrameSource};
use concat_project::Command;
use concat_project::model::ClipKind;
use serde_json::json;

use crate::MobileError;
use crate::session::{MobileSession, TimelineView};

/// What a tracking job reports.
#[uniffi::export(with_foreign)]
pub trait TrackListener: Send + Sync {
    /// How far along it is, 0 to 1.
    fn on_progress(&self, fraction: f32);
    /// It finished: the view with the follower's new keys.
    fn on_done(&self, result: TimelineView);
    /// It failed or was cancelled.
    fn on_failed(&self, message: String);
}

/// Looks a second at the footage.
const LOOKS: i64 = 15;
/// Every how many looks a key is set.
const KEY_EVERY: usize = 3;
/// The width the footage is read at.
const WIDTH: u32 = 240;
/// Half the patch's side, in those pixels.
const PATCH: i32 = 9;
/// How far the patch may move between looks, in those pixels.
const REACH: i32 = 12;
/// How many looks either side camera tracking averages, so the camera glides.
const SMOOTH: usize = 2;

fn cancelled() -> &'static AtomicBool {
    static FLAG: AtomicBool = AtomicBool::new(false);
    &FLAG
}

/// Stops the tracking in progress.
#[uniffi::export]
pub fn cancel_tracking() {
    cancelled().store(true, Ordering::Relaxed);
}

/// A look at the footage: its luma, `width` by `height`.
pub(crate) struct Look {
    width: usize,
    height: usize,
    luma: Vec<u8>,
}

impl Look {
    /// A look made of `luma`, for tests and for frames read in.
    pub(crate) fn new(width: usize, height: usize, luma: Vec<u8>) -> Self {
        Look {
            width,
            height,
            luma,
        }
    }

    fn of(frame: &concat_core::frame::Frame) -> Self {
        let (width, height) = (frame.width() as usize, frame.height() as usize);
        let step = frame.bytes_per_pixel();
        // eight-bit RGBA, or the high byte of each sixteen-bit channel
        let high = if step == 8 { 1 } else { 0 };
        let pixels = frame.pixels();
        let mut luma = vec![0u8; width * height];
        for (i, value) in luma.iter_mut().enumerate() {
            let at = i * step;
            let channel = |c: usize| u32::from(pixels[at + c * (step / 4) + high]);
            *value = ((channel(0) * 77 + channel(1) * 150 + channel(2) * 29) >> 8) as u8;
        }
        Look::new(width, height, luma)
    }
}

/// Where the patch around `at` in `before` is in `now`: the offset, within
/// [`REACH`], at which the two differ least. A patch that runs off the
/// picture stays where it was.
pub(crate) fn follow(before: &Look, now: &Look, at: (f64, f64)) -> (f64, f64) {
    let (w, h) = (before.width as i32, before.height as i32);
    if before.width != now.width || before.height != now.height {
        return at;
    }
    let (cx, cy) = (at.0.round() as i32, at.1.round() as i32);
    let mut best = (0, 0, u64::MAX);
    for dy in -REACH..=REACH {
        for dx in -REACH..=REACH {
            let mut total = 0u64;
            let mut inside = true;
            'patch: for py in -PATCH..=PATCH {
                for px in -PATCH..=PATCH {
                    let (ax, ay) = (cx + px, cy + py);
                    let (bx, by) = (ax + dx, ay + dy);
                    if ax < 0
                        || ay < 0
                        || ax >= w
                        || ay >= h
                        || bx < 0
                        || by < 0
                        || bx >= w
                        || by >= h
                    {
                        inside = false;
                        break 'patch;
                    }
                    let then = before.luma[(ay * w + ax) as usize];
                    let here = now.luma[(by * w + bx) as usize];
                    total += u64::from(then.abs_diff(here));
                }
            }
            // of equally good places the nearest wins, so a plain stretch holds still
            let nearer = dx * dx + dy * dy < best.0 * best.0 + best.1 * best.1;
            if inside && (total < best.2 || (total == best.2 && nearer)) {
                best = (dx, dy, total);
            }
        }
    }
    if best.2 == u64::MAX {
        return at;
    }
    (at.0 + f64::from(best.0), at.1 + f64::from(best.1))
}

/// What the job needs, read from the timeline under the lock.
struct Plan {
    path: String,
    /// Where in the file the follower starts, seconds.
    from: f64,
    speed: f64,
    /// How long to follow, seconds of timeline.
    span: f64,
    follower_duration: f64,
    base: (f64, f64),
    frame: (f64, f64),
    /// The main clip's own scale and offset, as fractions of the frame.
    fit: (f64, f64, f64),
}

#[uniffi::export]
impl MobileSession {
    /// Makes the clip `follower_id` follow what is at (`x`, `y`) - fractions
    /// of the frame - in the main-track footage under it, from where it
    /// starts to where it or the footage ends: its position keys are set
    /// along the path, as one undo step. Runs on a thread of its own.
    pub fn track(
        self: Arc<Self>,
        follower_id: String,
        x: f64,
        y: f64,
        listener: Arc<dyn TrackListener>,
    ) -> Result<(), MobileError> {
        let plan = self.with_session(|session| {
            let project = session.project();
            let timeline = project.active();
            let follower = timeline
                .clips
                .iter()
                .find(|clip| clip.id == follower_id)
                .ok_or_else(|| MobileError::Failed("That clip is gone.".to_owned()))?;
            let main_track = timeline
                .tracks
                .first()
                .ok_or_else(|| MobileError::Failed("There is no video to follow.".to_owned()))?;
            let main = timeline
                .clips
                .iter()
                .find(|clip| {
                    clip.track_id == main_track.id
                        && clip.start <= follower.start + 1e-6
                        && follower.start < clip.start + clip.duration
                })
                .ok_or_else(|| {
                    MobileError::Failed("There is no video under it to follow.".to_owned())
                })?;
            let media = project
                .media
                .iter()
                .find(|item| item.id == main.media_id)
                .ok_or_else(|| MobileError::Failed("The video under it is missing.".to_owned()))?;
            let end = (follower.start + follower.duration).min(main.start + main.duration);
            Ok(Plan {
                path: media.path.clone(),
                from: main.source_start + (follower.start - main.start) * main.speed,
                speed: main.speed,
                span: (end - follower.start).max(0.0),
                follower_duration: follower.duration.max(1e-6),
                base: (follower.offset_x, follower.offset_y),
                frame: (
                    f64::from(timeline.video.width),
                    f64::from(timeline.video.height),
                ),
                fit: (main.scale, main.offset_x, main.offset_y),
            })
        })?;
        if plan.span < 0.1 {
            return Err(MobileError::Failed(
                "There is too little video under it to follow.".to_owned(),
            ));
        }
        cancelled().store(false, Ordering::Relaxed);
        std::thread::Builder::new()
            .name("capcraft-tracking".to_owned())
            .spawn(
                move || match self.follow_path(&follower_id, x, y, &plan, &*listener) {
                    Ok(view) => listener.on_done(view),
                    Err(message) => listener.on_failed(message),
                },
            )
            .map_err(|error| MobileError::Failed(format!("could not start: {error}")))?;
        Ok(())
    }

    /// Camera tracking: keeps what is at (`x`, `y`) - fractions of the
    /// frame - in the middle of the frame by moving the video clip
    /// `clip_id` itself, grown by `zoom` so its edges stay out of sight; its
    /// position keys are set along the way, as one undo step. Runs on a
    /// thread of its own.
    pub fn camera_track(
        self: Arc<Self>,
        clip_id: String,
        x: f64,
        y: f64,
        zoom: f64,
        listener: Arc<dyn TrackListener>,
    ) -> Result<(), MobileError> {
        let plan = self.with_session(|session| {
            let project = session.project();
            let timeline = project.active();
            let clip = timeline
                .clips
                .iter()
                .find(|clip| clip.id == clip_id)
                .ok_or_else(|| MobileError::Failed("That clip is gone.".to_owned()))?;
            if !matches!(clip.kind, ClipKind::Video) {
                return Err(MobileError::Failed(
                    "Camera tracking needs a video.".to_owned(),
                ));
            }
            let media = project
                .media
                .iter()
                .find(|item| item.id == clip.media_id)
                .ok_or_else(|| MobileError::Failed("The clip's file is missing.".to_owned()))?;
            Ok(Plan {
                path: media.path.clone(),
                from: clip.source_start,
                speed: clip.speed,
                span: clip.duration,
                follower_duration: clip.duration.max(1e-6),
                base: (clip.offset_x, clip.offset_y),
                frame: (
                    f64::from(timeline.video.width),
                    f64::from(timeline.video.height),
                ),
                fit: (clip.scale, clip.offset_x, clip.offset_y),
            })
        })?;
        if plan.span < 0.1 {
            return Err(MobileError::Failed(
                "The clip is too short to track.".to_owned(),
            ));
        }
        cancelled().store(false, Ordering::Relaxed);
        std::thread::Builder::new()
            .name("capcraft-camera".to_owned())
            .spawn(
                move || match self.camera_path(&clip_id, x, y, zoom, &plan, &*listener) {
                    Ok(view) => listener.on_done(view),
                    Err(message) => listener.on_failed(message),
                },
            )
            .map_err(|error| MobileError::Failed(format!("could not start: {error}")))?;
        Ok(())
    }
}

/// A point followed through the footage: where it was at each look, in the
/// small reading's pixels, and where the footage is shown on the frame.
struct Followed {
    path: Vec<(f64, f64)>,
    small_h: u32,
    /// The footage's size on the frame, in frame pixels.
    shown: (f64, f64),
}

impl MobileSession {
    /// Follows what is at (`x`, `y`) - fractions of the frame - through the
    /// footage `plan` names, `LOOKS` times a second.
    fn read_path(
        x: f64,
        y: f64,
        plan: &Plan,
        listener: &dyn TrackListener,
    ) -> Result<Followed, String> {
        let info = concat_media::probe(&plan.path).map_err(|error| error.to_string())?;
        let video = info
            .video
            .as_ref()
            .ok_or_else(|| "The video has no picture.".to_owned())?;
        let (media_w, media_h) = (f64::from(video.width), f64::from(video.height));
        let small_h = ((f64::from(WIDTH) * media_h / media_w).round() as u32 / 2 * 2).max(2);
        let looks = (plan.span * LOOKS as f64).ceil() as u64 + 1;
        let rate = ((LOOKS as f64 * plan.speed) * 1000.0).round().max(1.0) as i64;
        let options = DecodeOptions::default()
            .in_software()
            .threaded(2)
            .starting_at(Rational::new(
                (plan.from * 1_000_000.0).round() as i64,
                1_000_000,
            ))
            .scaled_to(WIDTH, small_h)
            .at_rate(FrameRate::new(Rational::new(rate, 1000)))
            .limited_to(looks);
        // the footage fitted into the frame, as the engine draws a main clip
        let (frame_w, frame_h) = plan.frame;
        let fit = (frame_w / media_w).min(frame_h / media_h) * plan.fit.0;
        let (shown_w, shown_h) = (media_w * fit, media_h * fit);
        let left = frame_w / 2.0 + plan.fit.1 * frame_w - shown_w / 2.0;
        let top = frame_h / 2.0 + plan.fit.2 * frame_h - shown_h / 2.0;
        let mut point = (
            (x * frame_w - left) / shown_w * f64::from(WIDTH),
            (y * frame_h - top) / shown_h * f64::from(small_h),
        );
        let mut decoder = Decoder::open(&plan.path, &options).map_err(|error| error.to_string())?;
        let mut path: Vec<(f64, f64)> = Vec::new();
        let mut last: Option<Look> = None;
        while let Some(frame) = decoder.next_frame().map_err(|error| error.to_string())? {
            if cancelled().load(Ordering::Relaxed) {
                return Err("Tracking was cancelled.".to_owned());
            }
            let look = Look::of(&frame);
            if let Some(before) = &last {
                point = follow(before, &look, point);
            }
            path.push(point);
            last = Some(look);
            listener.on_progress((path.len() as f64 / looks as f64).min(1.0) as f32);
            if path.len() as u64 >= looks {
                break;
            }
        }
        if path.is_empty() {
            return Err("No picture was read to follow.".to_owned());
        }
        Ok(Followed {
            path,
            small_h,
            shown: (shown_w, shown_h),
        })
    }

    fn follow_path(
        &self,
        follower_id: &str,
        x: f64,
        y: f64,
        plan: &Plan,
        listener: &dyn TrackListener,
    ) -> Result<TimelineView, String> {
        let Followed {
            path,
            small_h,
            shown: (shown_w, shown_h),
        } = Self::read_path(x, y, plan, listener)?;
        let (frame_w, frame_h) = plan.frame;
        let first = path[0];
        // the path as position keys: a look's move in small pixels is that
        // share of the shown footage, then of the frame
        let mut commands = vec![
            serde_json::from_value::<Command>(
                json!({"op": "clearClipKeys", "clipId": follower_id, "property": "offsetX"}),
            ),
            serde_json::from_value::<Command>(
                json!({"op": "clearClipKeys", "clipId": follower_id, "property": "offsetY"}),
            ),
        ]
        .into_iter()
        .collect::<Result<Vec<_>, _>>()
        .map_err(|error| error.to_string())?;
        for (index, p) in path.iter().enumerate() {
            if index % KEY_EVERY != 0 && index + 1 != path.len() {
                continue;
            }
            let t = index as f64 / LOOKS as f64;
            let at = (t / plan.follower_duration).clamp(0.0, 1.0);
            let dx = (p.0 - first.0) / f64::from(WIDTH) * shown_w / frame_w;
            let dy = (p.1 - first.1) / f64::from(small_h) * shown_h / frame_h;
            for (property, value) in [("offsetX", plan.base.0 + dx), ("offsetY", plan.base.1 + dy)]
            {
                let key = json!({"op": "setClipKey", "clipId": follower_id, "property": property, "at": at, "value": value});
                commands.push(
                    serde_json::from_value::<Command>(key).map_err(|error| error.to_string())?,
                );
            }
        }
        self.run(commands).map_err(|error| error.to_string())
    }
}

impl MobileSession {
    /// Camera tracking's keys: the clip grown by `zoom` and moved, look by
    /// look, so the followed point sits mid-frame - never so far that the
    /// frame's edge shows past the picture - over a path evened out so the
    /// camera glides.
    fn camera_path(
        &self,
        clip_id: &str,
        x: f64,
        y: f64,
        zoom: f64,
        plan: &Plan,
        listener: &dyn TrackListener,
    ) -> Result<TimelineView, String> {
        let Followed {
            path,
            small_h,
            shown: (shown_w, shown_h),
        } = Self::read_path(x, y, plan, listener)?;
        let (frame_w, frame_h) = plan.frame;
        let zoom = zoom.clamp(1.0, 3.0);
        let smooth: Vec<(f64, f64)> = (0..path.len())
            .map(|i| {
                let lo = i.saturating_sub(SMOOTH);
                let hi = (i + SMOOTH + 1).min(path.len());
                let n = (hi - lo) as f64;
                let (sx, sy) = path[lo..hi]
                    .iter()
                    .fold((0.0, 0.0), |sum, p| (sum.0 + p.0, sum.1 + p.1));
                (sx / n, sy / n)
            })
            .collect();
        let mut commands = [
            json!({"op": "clearClipKeys", "clipId": clip_id, "property": "offsetX"}),
            json!({"op": "clearClipKeys", "clipId": clip_id, "property": "offsetY"}),
            json!({"op": "setClipTransform", "clipId": clip_id, "scale": plan.fit.0 * zoom}),
        ]
        .into_iter()
        .map(serde_json::from_value::<Command>)
        .collect::<Result<Vec<_>, _>>()
        .map_err(|error| error.to_string())?;
        // how far the grown picture may move before its edge shows
        let room_x = ((shown_w * zoom - frame_w) / 2.0).max(0.0);
        let room_y = ((shown_h * zoom - frame_h) / 2.0).max(0.0);
        for (index, p) in smooth.iter().enumerate() {
            if index % KEY_EVERY != 0 && index + 1 != smooth.len() {
                continue;
            }
            let at = (index as f64 / LOOKS as f64 / plan.follower_duration).clamp(0.0, 1.0);
            // the point from the picture's middle, on the frame, once grown
            let rx = (p.0 / f64::from(WIDTH) - 0.5) * shown_w * zoom;
            let ry = (p.1 / f64::from(small_h) - 0.5) * shown_h * zoom;
            let dx = (-rx).clamp(-room_x, room_x);
            let dy = (-ry).clamp(-room_y, room_y);
            for (property, value) in [("offsetX", dx / frame_w), ("offsetY", dy / frame_h)] {
                let key = json!({"op": "setClipKey", "clipId": clip_id, "property": property, "at": at, "value": value});
                commands.push(
                    serde_json::from_value::<Command>(key).map_err(|error| error.to_string())?,
                );
            }
        }
        self.run(commands).map_err(|error| error.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A dark picture with a bright square at (x, y).
    fn square_at(x: usize, y: usize) -> Look {
        let (w, h) = (80, 60);
        let mut luma = vec![20u8; w * h];
        for row in y..y + 8 {
            for col in x..x + 8 {
                luma[row * w + col] = 230;
            }
        }
        Look::new(w, h, luma)
    }

    #[test]
    fn a_patch_is_followed_where_it_went() {
        let before = square_at(30, 20);
        let now = square_at(34, 23);
        let to = follow(&before, &now, (34.0, 24.0));
        assert_eq!(to, (38.0, 27.0));
    }

    #[test]
    fn a_patch_at_the_edge_stays_put() {
        let before = square_at(0, 0);
        let now = square_at(2, 2);
        assert_eq!(follow(&before, &now, (1.0, 1.0)), (1.0, 1.0));
    }
}

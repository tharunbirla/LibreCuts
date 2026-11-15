// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! Auto reframe: a clip zoomed in to fill the frame - whatever its shape
//! against the frame's - and moved so the person in it stays in view.
//!
//! The person model finds the person in the clip's stretch of its file
//! (the masks Remove BG keeps, fetched and found the first time); the
//! middle of its mask twice a second, evened out with its neighbours, is the
//! point the frame follows, and it becomes the clip's position keys, never
//! so far that the frame's edge shows past the picture. One undo step.

use std::sync::Arc;

use concat_host::cutout::{AnalyseRequest, Progress};
use concat_project::Command;
use concat_project::model::{ClipKind, MediaKind, Subject};
use concat_vision::mask::Mask;
use concat_vision::store::{MaskStore, mask_dir};
use serde_json::json;

use crate::MobileError;
use crate::session::{MobileSession, TimelineView};
use crate::speech::SpeechListener;

/// Points a second the path is read at: few, since the person model is slow on a phone and
/// the keys between them glide.
const LOOKS: f64 = 2.0;
/// Looks either side the path is evened out over.
const SMOOTH: usize = 1;

/// The middle of what `mask` keeps, as fractions of its width and height,
/// or None when it keeps nothing.
pub(crate) fn middle(mask: &Mask) -> Option<(f64, f64)> {
    let (w, h) = (mask.width() as usize, mask.height() as usize);
    let (mut sx, mut sy, mut total) = (0.0f64, 0.0f64, 0.0f64);
    for (i, &v) in mask.bytes().iter().enumerate() {
        if v > 32 {
            let weight = f64::from(v);
            sx += (i % w) as f64 * weight;
            sy += (i / w) as f64 * weight;
            total += weight;
        }
    }
    (total > 0.0).then(|| ((sx / total + 0.5) / w as f64, (sy / total + 0.5) / h as f64))
}

/// `points` evened out over [`SMOOTH`] looks either side.
fn evened(points: &[(f64, f64)]) -> Vec<(f64, f64)> {
    (0..points.len())
        .map(|i| {
            let lo = i.saturating_sub(SMOOTH);
            let hi = (i + SMOOTH + 1).min(points.len());
            let n = (hi - lo) as f64;
            let (x, y) = points[lo..hi]
                .iter()
                .fold((0.0, 0.0), |s, p| (s.0 + p.0, s.1 + p.1));
            (x / n, y / n)
        })
        .collect()
}

/// What the job needs from the timeline.
struct Plan {
    media: String,
    size: (u32, u32),
    from: f64,
    span: f64,
    duration: f64,
    frame: (f64, f64),
    project: String,
}

#[uniffi::export]
impl MobileSession {
    /// Reframes `clip_id` around the person in it on a thread of its own,
    /// reporting through `listener` ("download" while the model is
    /// fetched).
    pub fn auto_reframe(
        self: Arc<Self>,
        clip_id: String,
        listener: Arc<dyn SpeechListener>,
    ) -> Result<(), MobileError> {
        let plan = self.with_session(|session| {
            let project = session.project();
            let timeline = project.active();
            let clip = timeline
                .clips
                .iter()
                .find(|clip| clip.id == clip_id)
                .ok_or_else(|| MobileError::Failed("That clip is gone.".to_owned()))?;
            if !matches!(clip.kind, ClipKind::Video | ClipKind::Image) {
                return Err(MobileError::Failed(
                    "Auto reframe is for videos and photos.".to_owned(),
                ));
            }
            let media = project
                .media
                .iter()
                .find(|item| item.id == clip.media_id)
                .ok_or_else(|| MobileError::Failed("The clip's file is gone.".to_owned()))?;
            let still = matches!(media.kind, MediaKind::Image);
            Ok(Plan {
                media: media.path.clone(),
                size: (media.width.unwrap_or(1920), media.height.unwrap_or(1080)),
                from: if still { 0.0 } else { clip.source_start },
                span: if still {
                    0.0
                } else {
                    clip.duration * clip.speed
                },
                duration: clip.duration.max(1e-6),
                frame: (
                    f64::from(timeline.video.width),
                    f64::from(timeline.video.height),
                ),
                project: session.path().to_owned(),
            })
        })?;
        let cutouts = crate::cutout::cutouts()?;
        std::thread::Builder::new()
            .name("capcraft-reframe".to_owned())
            .spawn(
                move || match self.reframe(&clip_id, &plan, cutouts, &*listener) {
                    Ok(view) => listener.on_done(Some(view)),
                    Err(message) => listener.on_failed(message),
                },
            )
            .map_err(|error| MobileError::Failed(format!("could not start: {error}")))?;
        Ok(())
    }
}

impl MobileSession {
    fn reframe(
        &self,
        clip_id: &str,
        plan: &Plan,
        cutouts: &concat_host::Cutouts,
        listener: &dyn SpeechListener,
    ) -> Result<TimelineView, String> {
        let still = plan.span <= 0.0;
        let request = AnalyseRequest {
            project: plan.project.clone().into(),
            media_path: plan.media.clone(),
            media_size: plan.size,
            still,
            subject: Subject::Person,
            // only the instants the path is read at, not every one the model could look at
            ranges: if still {
                vec![(0.0, 0.04)]
            } else {
                let looks = (plan.duration * LOOKS).ceil() as usize + 1;
                (0..looks)
                    .map(|i| {
                        let t = plan.from
                            + (i as f64 / LOOKS).min(plan.duration) * plan.span / plan.duration;
                        (t, t)
                    })
                    .collect()
            },
        };
        cutouts.analyse(&request, &mut |progress| match progress {
            Progress::Fetching { received, total } => {
                listener.on_progress(received as f32 / total.max(1) as f32, "download".to_owned())
            }
            Progress::Analysing(done) => listener.on_progress(done * 0.9, "work".to_owned()),
        })?;
        let store = MaskStore::open(&mask_dir(
            std::path::Path::new(&plan.project),
            &plan.media,
            Subject::Person,
        ));
        // the person's middle, look by look; a look without one keeps the last
        let looks = if still {
            1
        } else {
            (plan.duration * LOOKS).ceil() as usize + 1
        };
        let mut points = Vec::with_capacity(looks);
        let mut times = Vec::with_capacity(looks);
        let mut last = (0.5, 0.5);
        for i in 0..looks {
            // a look's place in the clip, and the instant of the file it shows
            let along = (i as f64 / LOOKS).min(plan.duration);
            let t = plan.from + along * plan.span / plan.duration;
            if let Some(found) = store.mask_at(t).as_deref().and_then(middle) {
                last = found;
            }
            points.push(last);
            times.push(along / plan.duration);
        }
        let points = evened(&points);
        // the picture as the engine fits it, then grown until it fills the frame
        let (fw, fh) = plan.frame;
        let (mw, mh) = (f64::from(plan.size.0), f64::from(plan.size.1));
        let fit = (fw / mw).min(fh / mh);
        let (shown_w, shown_h) = (mw * fit, mh * fit);
        let scale = (fw / shown_w).max(fh / shown_h);
        let room_x = ((shown_w * scale - fw) / 2.0).max(0.0);
        let room_y = ((shown_h * scale - fh) / 2.0).max(0.0);
        let mut commands = [
            json!({"op": "clearClipKeys", "clipId": clip_id, "property": "offsetX"}),
            json!({"op": "clearClipKeys", "clipId": clip_id, "property": "offsetY"}),
            json!({"op": "setClipTransform", "clipId": clip_id, "scale": scale, "offsetX": 0.0, "offsetY": 0.0, "rotation": 0.0}),
        ]
        .into_iter()
        .map(serde_json::from_value::<Command>)
        .collect::<Result<Vec<_>, _>>()
        .map_err(|error| error.to_string())?;
        let offset = |p: (f64, f64)| {
            let dx = (-(p.0 - 0.5) * shown_w * scale).clamp(-room_x, room_x);
            let dy = (-(p.1 - 0.5) * shown_h * scale).clamp(-room_y, room_y);
            (dx / fw, dy / fh)
        };
        if points.len() == 1 {
            let (x, y) = offset(points[0]);
            commands.push(
                serde_json::from_value(json!({"op": "setClipTransform", "clipId": clip_id, "offsetX": x, "offsetY": y}))
                    .map_err(|error| error.to_string())?,
            );
        } else {
            for (p, at) in points.iter().zip(&times) {
                let at = at.clamp(0.0, 1.0);
                let (x, y) = offset(*p);
                for (property, value) in [("offsetX", x), ("offsetY", y)] {
                    commands.push(
                        serde_json::from_value(
                            json!({"op": "setClipKey", "clipId": clip_id, "property": property, "at": at, "value": value}),
                        )
                        .map_err(|error| error.to_string())?,
                    );
                }
            }
        }
        listener.on_progress(1.0, "work".to_owned());
        self.run(commands).map_err(|error| error.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_middle_of_a_mask_is_where_it_keeps() {
        let (w, h) = (40u32, 20u32);
        let mut data = vec![0u8; (w * h) as usize];
        for y in 5..15 {
            for x in 28..36 {
                data[(y * w + x) as usize] = 255;
            }
        }
        let mask = Mask::from_bytes(w, h, data).expect("a mask");
        let (x, y) = middle(&mask).expect("something kept");
        assert!((x - 0.8).abs() < 0.01, "{x}");
        assert!((y - 0.5).abs() < 0.01, "{y}");
        assert!(middle(&Mask::filled(w, h, 0)).is_none());
    }

    #[test]
    fn a_jumpy_path_is_evened_out() {
        let points: Vec<(f64, f64)> = (0..10)
            .map(|i| (if i % 2 == 0 { 0.4 } else { 0.6 }, 0.5))
            .collect();
        let even = evened(&points);
        assert!(even[3..7].iter().all(|p| (p.0 - 0.5).abs() < 0.05));
    }
}

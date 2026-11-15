// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer and Concat contributors
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! The canvas background: what shows where a picture does not fill the
//! frame. A blurred copy of the main-track clip filling the frame behind
//! it, a colour, or a picture.
//!
//! Each main-track clip may have its own (`capcraftBackgrounds`, by clip
//! id), and the project one for the rest (`capcraftBackground`, which is
//! all a project made before had); Apply to all sets the project's and
//! clears the clips' own. Both are kept on the timeline's extra fields, set
//! with the engine's `SetTimelineExtra`, so they are saved and undone like
//! any edit, and drawn by adding clips under everything else to what the
//! monitor and the export render, each for its clip's stretch of time:
//! every other clip moves up one lane.

use std::path::Path;

use concat_export::{ClipKind, ExportClip};
use concat_project::DocumentSettings;
use concat_project::model::{AppliedFilter, Project};

use crate::session::MobileSession;
use serde::Deserialize;

/// Where the project's background is kept on the timeline.
pub const KEY: &str = "capcraftBackground";

/// Where each main-track clip's own background is kept, by clip id.
pub const CLIPS_KEY: &str = "capcraftBackgrounds";

/// A background, as stored.
#[derive(Deserialize, Debug, Clone)]
#[serde(tag = "kind", rename_all = "lowercase")]
enum Background {
    /// The main-track clip, enlarged to fill and blurred: `level` 1 to 4.
    Blur { level: u32 },
    /// A colour, `#rrggbb`.
    Color { color: String },
    /// A picture file, filling the frame.
    Image { path: String },
    /// None for this clip, whatever the project's is.
    None,
}

fn background_of(project: &Project) -> Option<Background> {
    let value = project.active().extra.get(KEY)?;
    serde_json::from_value(value.clone()).ok()
}

/// The main-track clips' own backgrounds, by clip id.
fn clip_backgrounds(project: &Project) -> std::collections::HashMap<String, Background> {
    project
        .active()
        .extra
        .get(CLIPS_KEY)
        .and_then(|value| value.as_object())
        .map(|map| {
            map.iter()
                .filter_map(|(id, value)| {
                    Some((id.clone(), serde_json::from_value(value.clone()).ok()?))
                })
                .collect()
        })
        .unwrap_or_default()
}

/// The scale over "fit" that makes a `w` by `h` picture fill the frame.
fn cover_scale(w: f64, h: f64, frame_w: f64, frame_h: f64) -> f64 {
    let fit = (frame_w / w.max(1.0)).min(frame_h / h.max(1.0));
    let cover = (frame_w / w.max(1.0)).max(frame_h / h.max(1.0));
    cover / fit.max(1e-9)
}

/// A clip that shows `path` over `[start, start + duration)` on lane 0,
/// filling the frame, silent.
fn filling(
    path: String,
    w: u32,
    h: u32,
    start: f64,
    duration: f64,
    settings: &DocumentSettings,
) -> ExportClip {
    ExportClip {
        path,
        muted: true,
        volume: 0.0,
        has_audio: Some(false),
        media_width: Some(w),
        media_height: Some(h),
        scale: cover_scale(
            f64::from(w),
            f64::from(h),
            f64::from(settings.width),
            f64::from(settings.height),
        ),
        ..ExportClip::blank(ClipKind::Image, start, duration.max(0.04), 0)
    }
}

/// Adds the backgrounds of the main-track clips, each its own or else the
/// project's, under `clips`, each for its clip's stretch of time.
pub(crate) fn add(
    project: &Project,
    settings: &DocumentSettings,
    project_dir: &Path,
    clips: &mut Vec<ExportClip>,
) {
    let global = background_of(project);
    let own = clip_backgrounds(project);
    if global.is_none() && own.is_empty() {
        return;
    }
    // the main track's clips by where they start, which is how a rendered clip is told back to its id
    let timeline = project.active();
    let starts: Vec<(&str, f64)> = timeline
        .tracks
        .first()
        .map(|main| {
            timeline
                .clips
                .iter()
                .filter(|clip| clip.track_id == main.id)
                .map(|clip| (clip.id.as_str(), clip.start))
                .collect()
        })
        .unwrap_or_default();
    let (frame_w, frame_h) = (f64::from(settings.width), f64::from(settings.height));
    let mut sizes: std::collections::HashMap<String, Option<(u32, u32)>> =
        std::collections::HashMap::new();
    let mut under: Vec<ExportClip> = Vec::new();
    for clip in clips
        .iter()
        .filter(|clip| clip.track == 0 && matches!(clip.kind, ClipKind::Video | ClipKind::Image))
    {
        let id = starts
            .iter()
            .find(|(_, start)| (start - clip.start).abs() < 1e-6)
            .map(|(id, _)| *id);
        let Some(background) = id.and_then(|id| own.get(id)).or(global.as_ref()) else {
            continue;
        };
        match background {
            Background::Blur { level } => {
                let radius = match level {
                    0 | 1 => 12.0,
                    2 => 22.0,
                    3 => 34.0,
                    _ => 50.0,
                };
                let (w, h) = match (clip.media_width, clip.media_height) {
                    (Some(w), Some(h)) => (f64::from(w), f64::from(h)),
                    _ => (frame_w, frame_h),
                };
                let mut blur = AppliedFilter::new("concat.gaussian-blur");
                blur.params.insert("radius".to_owned(), radius);
                under.push(ExportClip {
                    track: 0,
                    muted: true,
                    volume: 0.0,
                    has_audio: Some(false),
                    animation: Vec::new(),
                    transition: None,
                    transition_shapes: Vec::new(),
                    cutout: None,
                    effects: vec![blur],
                    scale: cover_scale(w, h, frame_w, frame_h) * 1.05,
                    offset_x: 0.0,
                    offset_y: 0.0,
                    rotation: 0.0,
                    opacity: 1.0,
                    stretch_x: 1.0,
                    stretch_y: 1.0,
                    blend: String::new(),
                    ..clip.clone()
                });
            }
            Background::Color { color } => {
                if let Some(file) = colour_file(project_dir, color, settings) {
                    let (w, h) = colour_size(settings);
                    under.push(filling(file, w, h, clip.start, clip.duration, settings));
                }
            }
            Background::Image { path } => {
                // a picture's size, probed once however many clips show it
                let size = *sizes.entry(path.clone()).or_insert_with(|| {
                    concat_host::media::probe(path)
                        .ok()
                        .and_then(|info| info.video)
                        .map(|video| (video.width, video.height))
                });
                if let Some((w, h)) = size {
                    under.push(filling(
                        path.clone(),
                        w,
                        h,
                        clip.start,
                        clip.duration,
                        settings,
                    ));
                }
            }
            Background::None => {}
        }
    }
    if under.is_empty() {
        return;
    }
    for clip in clips.iter_mut() {
        clip.track += 1;
    }
    under.append(clips);
    *clips = under;
}

/// The size of a colour background's picture: small, the frame's shape.
fn colour_size(settings: &DocumentSettings) -> (u32, u32) {
    let w = 64u32;
    let h = ((f64::from(w) * f64::from(settings.height) / f64::from(settings.width.max(1))).round()
        as u32)
        .max(2);
    (w, h)
}

/// A picture of one colour in the project's cache, made once.
fn colour_file(project_dir: &Path, color: &str, settings: &DocumentSettings) -> Option<String> {
    let hex = color.trim_start_matches('#');
    let value = u32::from_str_radix(hex.get(..6)?, 16).ok()?;
    let rgb = [(value >> 16) as u8, (value >> 8) as u8, value as u8];
    let (w, h) = colour_size(settings);
    let folder = project_dir.join("cache");
    let file = folder.join(format!("background-{hex}-{w}x{h}.png"));
    if !file.is_file() {
        std::fs::create_dir_all(&folder).ok()?;
        let pixels: Vec<u8> = (0..w * h)
            .flat_map(|_| [rgb[0], rgb[1], rgb[2], 255])
            .collect();
        let writer = std::io::BufWriter::new(std::fs::File::create(&file).ok()?);
        let mut encoder = png::Encoder::new(writer, w, h);
        encoder.set_color(png::ColorType::Rgba);
        encoder.set_depth(png::BitDepth::Eight);
        encoder
            .write_header()
            .ok()?
            .write_image_data(&pixels)
            .ok()?;
    }
    Some(file.to_string_lossy().into_owned())
}

/// A background's JSON checked, or None for empty.
fn parsed(json: &str) -> Result<Option<serde_json::Value>, crate::MobileError> {
    if json.trim().is_empty() {
        return Ok(None);
    }
    let value: serde_json::Value = serde_json::from_str(json)
        .map_err(|error| crate::MobileError::Failed(format!("not a background: {error}")))?;
    serde_json::from_value::<Background>(value.clone())
        .map_err(|error| crate::MobileError::Failed(format!("not a background: {error}")))?;
    Ok(Some(value))
}

#[uniffi::export]
impl MobileSession {
    /// Sets the project's canvas background for every clip - Apply to all -
    /// clearing the clips' own: `json` is `{"kind":"blur","level":2}`,
    /// `{"kind":"color","color":"#ff0000"}` or `{"kind":"image","path":"…"}`;
    /// empty takes it off. One undo step.
    pub fn set_background(
        &self,
        json: String,
    ) -> Result<crate::session::TimelineView, crate::MobileError> {
        let value = parsed(&json)?;
        self.run(vec![
            concat_project::Command::SetTimelineExtra {
                key: KEY.to_owned(),
                value,
            },
            concat_project::Command::SetTimelineExtra {
                key: CLIPS_KEY.to_owned(),
                value: None,
            },
        ])
    }

    /// Sets main-track clip `clip_id`'s own background (`json` as
    /// `set_background` takes, or `{"kind":"none"}` for none), or with
    /// empty `json` lets it show the project's again. One undo step.
    pub fn set_clip_background(
        &self,
        clip_id: String,
        json: String,
    ) -> Result<crate::session::TimelineView, crate::MobileError> {
        let value = parsed(&json)?;
        let mut map = self.with_session(|session| {
            Ok(session
                .project()
                .active()
                .extra
                .get(CLIPS_KEY)
                .and_then(|value| value.as_object().cloned())
                .unwrap_or_default())
        })?;
        match value {
            Some(value) => map.insert(clip_id, value),
            None => map.remove(&clip_id),
        };
        self.run(vec![concat_project::Command::SetTimelineExtra {
            key: CLIPS_KEY.to_owned(),
            value: (!map.is_empty()).then_some(serde_json::Value::Object(map)),
        }])
    }

    /// The background clip `clip_id` shows (its own, else the project's),
    /// as `set_background` takes it; empty for none.
    pub fn clip_background(&self, clip_id: String) -> String {
        self.with_session(|session| {
            let extra = &session.project().active().extra;
            let own = extra
                .get(CLIPS_KEY)
                .and_then(|map| map.get(&clip_id))
                .cloned();
            Ok(match own {
                Some(value) if value.get("kind").and_then(|kind| kind.as_str()) == Some("none") => {
                    String::new()
                }
                Some(value) => value.to_string(),
                None => extra
                    .get(KEY)
                    .map(|value| value.to_string())
                    .unwrap_or_default(),
            })
        })
        .unwrap_or_default()
    }

    /// The project's canvas background as stored (the JSON `set_background`
    /// takes), or empty.
    pub fn background(&self) -> String {
        self.with_session(|session| {
            Ok(session
                .project()
                .active()
                .extra
                .get(KEY)
                .map(|value| value.to_string())
                .unwrap_or_default())
        })
        .unwrap_or_default()
    }
}

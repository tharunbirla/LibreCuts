// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer and Concat contributors
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! The phone timeline's rows over the engine's lanes.
//!
//! The engine's lanes are untyped and stacked bottom first: the last lane is
//! drawn on top. The phone timeline gives each lane a role by what it holds:
//!
//! - the bottom lane is the **main** track: video and stills end to end, no
//!   gaps, every edit on it closing up behind itself;
//! - a lane of video or stills above it is an **overlay** row;
//! - a lane of looks (filter packages) is a **filter** row, a lane of other
//!   picture packages an **effect** row;
//! - a lane of titles or shapes is a **text** row;
//! - a lane of sound is an **audio** row.
//!
//! An empty lane (other than the main one) has no role and is not shown.
//! New lanes are put where their role draws right: an effect that treats the
//! main track alone just above it, overlays above that, effects that treat
//! every picture and filters above every picture, text above everything;
//! sound goes on top, where it draws nothing. A layer treats what is beneath
//! its lane, so where an effect's lane sits is what it treats: the lanes
//! that treat every picture are listed in the timeline extra
//! [`ALL_VIDEOS`], and an effect lane above an overlay treats every picture
//! too, as it draws.

use concat_effects::Catalogue;
use concat_effects::Kind;
use concat_project::model::{Clip, ClipKind, Project, Timeline};

use crate::micros;

/// What a row of the phone timeline is for.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum RowKind {
    /// The gapless bottom track of video and stills.
    Main,
    /// Pictures over the main track.
    Overlay,
    /// Effect layers.
    Effect,
    /// Filter (look) layers.
    Filter,
    /// Titles, captions and shapes.
    Text,
    /// Sound.
    Audio,
}

/// The timeline extra listing the effect lanes whose effects treat every
/// picture beneath them, overlays too, rather than the main track alone.
pub const ALL_VIDEOS: &str = "capcraftAllVideos";

/// The order lanes of `kind` take in the stack, bottom first. An effect that
/// treats the main track alone (`all` false) sits under the overlays; one
/// that treats every picture sits over them, with the looks.
pub(crate) fn tier(kind: RowKind, all: bool) -> u8 {
    match kind {
        RowKind::Main => 0,
        RowKind::Effect if !all => 1,
        RowKind::Overlay => 2,
        RowKind::Effect | RowKind::Filter => 3,
        RowKind::Text => 4,
        RowKind::Audio => 5,
    }
}

/// The lanes [`ALL_VIDEOS`] lists.
pub(crate) fn all_videos(timeline: &Timeline) -> Vec<String> {
    match timeline.extra.get(ALL_VIDEOS) {
        Some(serde_json::Value::Array(ids)) => ids
            .iter()
            .filter_map(|id| id.as_str().map(str::to_owned))
            .collect(),
        _ => Vec::new(),
    }
}

/// Whether the lane at `index` is an effect lane that treats every picture
/// beneath it: listed in [`ALL_VIDEOS`], or above an overlay.
pub(crate) fn reaches_all(timeline: &Timeline, index: usize) -> bool {
    let Some(track) = timeline.tracks.get(index) else {
        return false;
    };
    role(timeline, index) == Some(RowKind::Effect)
        && (all_videos(timeline).contains(&track.id)
            || (1..index).any(|below| role(timeline, below) == Some(RowKind::Overlay)))
}

/// The tier of the lane at `index`, or None for an empty lane.
fn lane_tier(timeline: &Timeline, index: usize) -> Option<u8> {
    role(timeline, index).map(|kind| tier(kind, reaches_all(timeline, index)))
}

/// One clip, as the timeline and the panels show it. Times in microseconds.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ClipView {
    /// The engine's id ("c12").
    pub id: String,
    /// The lane it sits on.
    pub track_id: String,
    /// "video", "image", "audio", "text", "shape" or "layer".
    pub kind: String,
    /// The bin item behind it; empty for text, shapes and layers.
    pub media_id: String,
    /// The label.
    pub name: String,
    /// The file behind it; empty for text, shapes and layers.
    pub path: String,
    /// Where it starts on the timeline.
    pub start_us: i64,
    /// How long it runs on the timeline.
    pub duration_us: i64,
    /// How far into the file it starts.
    pub source_start_us: i64,
    /// How long the file is; 0 when unknown or a still.
    pub media_duration_us: i64,
    /// The file's picture width; 0 for sound and for clips with no file.
    pub media_width: u32,
    /// The file's picture height.
    pub media_height: u32,
    /// Playback rate, 1 being normal.
    pub speed: f64,
    /// Whether a speed curve is set.
    pub curved: bool,
    /// Linear gain, 1 being unity.
    pub volume: f64,
    /// How long the sound rises from silence at the head.
    pub fade_in_us: i64,
    /// How long it falls to silence at the tail.
    pub fade_out_us: i64,
    /// Whether the clip carries sound the timeline plays.
    pub audible: bool,
    /// Whether its own sound is switched off.
    pub muted: bool,
    /// The words, for a title.
    pub text: String,
    /// The first package of a layer, or of the clip's picture chain.
    pub effect_id: String,
    /// The transition on the cut into this clip, if any.
    pub transition_id: String,
    /// How long that transition runs.
    pub transition_us: i64,
    /// Where the clip's keys sit, as times from its start (all properties).
    pub keys_us: Vec<i64>,
    /// The whole clip as the engine holds it, as JSON, for the panels.
    pub json: String,
}

/// One row of the phone timeline.
#[derive(Debug, Clone, uniffi::Record)]
pub struct RowView {
    /// The lane behind the row.
    pub track_id: String,
    /// What the row is for.
    pub kind: RowKind,
    /// The lane's sound is off.
    pub muted: bool,
    /// The lane's pictures are shown.
    pub visible: bool,
    /// For an effect row: its effects treat every picture beneath them,
    /// overlays too, not the main track alone.
    pub all_videos: bool,
    /// Its clips, by start.
    pub clips: Vec<ClipView>,
}

/// The role of the lane at `index`, or None for an empty lane.
pub(crate) fn role(timeline: &Timeline, index: usize) -> Option<RowKind> {
    if index == 0 {
        return Some(RowKind::Main);
    }
    let track = timeline.tracks.get(index)?;
    let clip = timeline
        .clips
        .iter()
        .find(|clip| clip.track_id == track.id)?;
    Some(kind_of(clip))
}

/// The file-name prefix of the stickers the app makes (pictures it draws
/// itself): they live on text rows, over the video, not on overlay rows.
pub const STICKER_PREFIX: &str = "sticker-";

/// The row a clip of this kind belongs on (never Main: that is a place).
pub(crate) fn kind_of(clip: &Clip) -> RowKind {
    match clip.kind {
        ClipKind::Image if clip.name.starts_with(STICKER_PREFIX) => RowKind::Text,
        ClipKind::Video | ClipKind::Image => RowKind::Overlay,
        // a layer over the soundtrack, as Concat's desktop editor lays them
        ClipKind::Audio | ClipKind::SoundLayer => RowKind::Audio,
        ClipKind::Text | ClipKind::Shape => RowKind::Text,
        ClipKind::Layer => layer_kind(clip.video_effects.first().map(|link| link.id.as_str())),
    }
}

/// A layer applying `effect_id` is a filter row's when the package is a look.
pub(crate) fn layer_kind(effect_id: Option<&str>) -> RowKind {
    let is_filter = effect_id
        .and_then(|id| Catalogue::builtin().get(id))
        .is_some_and(|package| package.kind() == Kind::Filter);
    if is_filter {
        RowKind::Filter
    } else {
        RowKind::Effect
    }
}

/// The rows of the active timeline: main first, then the others from the
/// bottom of the stack up. Empty lanes are left out.
pub fn rows(project: &Project) -> Vec<RowView> {
    let timeline = project.active();
    timeline
        .tracks
        .iter()
        .enumerate()
        .filter_map(|(index, track)| {
            let kind = role(timeline, index)?;
            let mut clips: Vec<ClipView> = timeline
                .clips
                .iter()
                .filter(|clip| clip.track_id == track.id)
                .map(|clip| clip_view(project, clip))
                .collect();
            clips.sort_by_key(|clip| clip.start_us);
            Some(RowView {
                track_id: track.id.clone(),
                kind,
                muted: track.muted,
                visible: track.visible,
                all_videos: reaches_all(timeline, index),
                clips,
            })
        })
        .collect()
}

/// One clip as the bridge hands it over.
pub(crate) fn clip_view(project: &Project, clip: &Clip) -> ClipView {
    let media = project.media.iter().find(|item| item.id == clip.media_id);
    let kind = match clip.kind {
        ClipKind::Video => "video",
        ClipKind::Image => "image",
        ClipKind::Audio => "audio",
        ClipKind::Text => "text",
        ClipKind::Shape => "shape",
        ClipKind::Layer => "layer",
        ClipKind::SoundLayer => "soundlayer",
    };
    let audible = match clip.kind {
        ClipKind::Audio => true,
        ClipKind::Video => media.is_some_and(|item| item.has_audio) && clip.muted != Some(true),
        _ => false,
    };
    let mut keys: Vec<i64> = clip
        .keys
        .iter()
        .map(|key| micros(key.at * clip.duration))
        .collect();
    keys.sort_unstable();
    keys.dedup();
    ClipView {
        id: clip.id.clone(),
        track_id: clip.track_id.clone(),
        kind: kind.to_owned(),
        media_id: clip.media_id.clone(),
        name: clip.name.clone(),
        path: media.map(|item| item.path.clone()).unwrap_or_default(),
        start_us: micros(clip.start),
        duration_us: micros(clip.duration),
        source_start_us: micros(clip.source_start),
        media_duration_us: media
            .and_then(|item| item.duration)
            .map(micros)
            .unwrap_or(0),
        media_width: media.and_then(|item| item.width).unwrap_or(0),
        media_height: media.and_then(|item| item.height).unwrap_or(0),
        speed: clip.speed,
        curved: clip.speed_curve.is_some(),
        volume: clip.volume,
        fade_in_us: micros(clip.fade_in),
        fade_out_us: micros(clip.fade_out),
        audible,
        muted: clip.muted == Some(true) || (clip.kind == ClipKind::Video && clip.volume <= 0.0),
        text: clip
            .text
            .as_ref()
            .map(|style| style.content.clone())
            .unwrap_or_default(),
        effect_id: clip
            .video_effects
            .first()
            .map(|link| link.id.clone())
            .unwrap_or_default(),
        transition_id: clip
            .transition_in
            .as_ref()
            .map(|transition| transition.id.clone())
            .unwrap_or_default(),
        transition_us: clip
            .transition_in
            .as_ref()
            .map(|transition| micros(transition.duration))
            .unwrap_or(0),
        keys_us: keys,
        json: serde_json::to_string(clip).unwrap_or_default(),
    }
}

/// Where a new lane for `kind` goes: just above the highest lane of its own
/// tier or any lower tier, so the stack stays in tier order. A new effect
/// lane treats the main track alone.
pub(crate) fn insert_index(timeline: &Timeline, kind: RowKind) -> usize {
    insert_index_at(timeline, tier(kind, false))
}

/// Where a new lane of tier `wanted` goes (see [`tier`]).
pub(crate) fn insert_index_at(timeline: &Timeline, wanted: u8) -> usize {
    let mut index = 1;
    for (at, _) in timeline.tracks.iter().enumerate() {
        if lane_tier(timeline, at).is_some_and(|tier| tier <= wanted) {
            index = at + 1;
        }
    }
    index
}

/// A lane of `kind` with `[start, start + duration)` free, lowest first, or
/// None when a new lane is needed. Only lanes already holding that kind are
/// considered, and empty lanes that sit where a new one would go. An effect
/// lane found treats the main track alone.
pub(crate) fn free_lane(
    timeline: &Timeline,
    kind: RowKind,
    start: f64,
    duration: f64,
) -> Option<String> {
    free_lane_at(timeline, kind, false, start, duration)
}

/// [`free_lane`] for an effect that treats every picture (`all`) or the
/// main track alone.
pub(crate) fn free_lane_at(
    timeline: &Timeline,
    kind: RowKind,
    all: bool,
    start: f64,
    duration: f64,
) -> Option<String> {
    let wanted_at = insert_index_at(timeline, tier(kind, all));
    let listed = all_videos(timeline);
    timeline
        .tracks
        .iter()
        .enumerate()
        .skip(1)
        .find(|(index, track)| {
            let fits = timeline.is_free(&track.id, start, duration, &[]);
            match role(timeline, *index) {
                Some(role) => role == kind && fits && reaches_all(timeline, *index) == all,
                // An empty lane right where the new one would go, kept for
                // every picture only if it is listed so.
                None => {
                    (*index == wanted_at || *index + 1 == wanted_at)
                        && listed.contains(&track.id) == all
                }
            }
        })
        .map(|(_, track)| track.id.clone())
}

/// Where the main track ends, in seconds.
pub(crate) fn main_end(timeline: &Timeline) -> f64 {
    let Some(main) = timeline.tracks.first() else {
        return 0.0;
    };
    timeline
        .clips
        .iter()
        .filter(|clip| clip.track_id == main.id)
        .map(|clip| clip.start + clip.duration)
        .fold(0.0, f64::max)
}

/// The clip boundary on the main track nearest `time`: where a clip
/// inserted "at the playhead" lands without cutting into anything.
pub(crate) fn main_boundary(timeline: &Timeline, time: f64) -> f64 {
    let Some(main) = timeline.tracks.first() else {
        return 0.0;
    };
    let mut best = 0.0;
    let mut best_gap = f64::INFINITY;
    for clip in timeline
        .clips
        .iter()
        .filter(|clip| clip.track_id == main.id)
    {
        for edge in [clip.start, clip.start + clip.duration] {
            let gap = (edge - time).abs();
            if gap < best_gap {
                best_gap = gap;
                best = edge;
            }
        }
    }
    best
}

/// The moves that close every gap on the main track, its clips end to end
/// from zero in their order; None when there is nothing to close.
pub(crate) fn pack_main(timeline: &Timeline) -> Option<Vec<concat_project::commands::ClipMove>> {
    let main = timeline.tracks.first()?;
    let mut clips: Vec<&Clip> = timeline
        .clips
        .iter()
        .map(|clip| clip.as_ref())
        .filter(|clip| clip.track_id == main.id)
        .collect();
    clips.sort_by(|a, b| a.start.total_cmp(&b.start));
    let mut at = 0.0;
    let mut moves = Vec::new();
    let mut gap = false;
    for clip in clips {
        if (clip.start - at).abs() > 1e-6 {
            gap = true;
        }
        moves.push(concat_project::commands::ClipMove {
            clip_id: clip.id.clone(),
            track_id: main.id.clone(),
            start: at,
        });
        at += clip.duration;
    }
    gap.then_some(moves)
}

/// Whether the clip sits on the main track.
pub(crate) fn on_main(timeline: &Timeline, clip_id: &str) -> bool {
    let Some(main) = timeline.tracks.first() else {
        return false;
    };
    timeline
        .clips
        .iter()
        .any(|clip| clip.id == clip_id && clip.track_id == main.id)
}

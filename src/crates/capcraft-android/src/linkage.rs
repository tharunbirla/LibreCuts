// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! Linkage: what sits over a main-track clip - a title, a sticker, an
//! effect or filter layer, an overlay, the clip's own extracted sound -
//! follows that clip when an edit moves it along the main track (a clip
//! before it deleted, trimmed, sped up, or the clips reordered).
//!
//! Every edit goes through [`MobileSession::apply_all`]: before it, each
//! follower is tied to the main clip under its start; after it, a follower
//! the edit did not move itself goes as far as its main clip went. On by
//! default; the timeline keeps it switched off, and the main clips whose
//! followers stay put, under `capcraftLinkage`.

use std::collections::{HashMap, HashSet};
use std::sync::atomic::{AtomicBool, Ordering};

use concat_project::commands::ClipMove;
use concat_project::model::{Clip, ClipKind, Timeline};
use serde_json::Value;

use crate::lanes;

/// Where the timeline keeps the switch (`"off": true`) and the main clips
/// whose followers stay put (`"unlinked": [ids]`).
pub const LINKAGE: &str = "capcraftLinkage";

/// The switch, the same for every project, as the app last set it.
static ON: AtomicBool = AtomicBool::new(true);

/// Switches linkage on or off for every project (the app keeps the choice).
#[uniffi::export]
pub fn set_linkage(on: bool) {
    ON.store(on, Ordering::Relaxed);
}

/// Whether linkage is on.
#[uniffi::export]
pub fn linkage_on() -> bool {
    ON.load(Ordering::Relaxed)
}

/// The main clips and their followers as they stood before an edit.
pub(crate) struct Before {
    /// Each main clip's start, seconds.
    owners: HashMap<String, f64>,
    /// Each follower: its id, its main clip's, and its start.
    followers: Vec<(String, String, f64)>,
}

/// Whether a clip off the main track goes with the main clip under it: all
/// but sound, which goes only when it was pulled out of a video.
fn follows(clip: &Clip) -> bool {
    clip.kind != ClipKind::Audio || clip.detached_from.is_some()
}

/// The followers of `timeline`'s main clips, or None with linkage off.
pub(crate) fn before(timeline: &Timeline) -> Option<Before> {
    if !linkage_on() {
        return None;
    }
    let settings = timeline.extra.get(LINKAGE);
    if settings.and_then(|s| s.get("off")).and_then(Value::as_bool) == Some(true) {
        return None;
    }
    let unlinked: HashSet<&str> = settings
        .and_then(|s| s.get("unlinked"))
        .and_then(Value::as_array)
        .map(|ids| ids.iter().filter_map(Value::as_str).collect())
        .unwrap_or_default();
    let main = timeline.tracks.first()?;
    let mains: Vec<&Clip> = timeline
        .clips
        .iter()
        .map(|clip| clip.as_ref())
        .filter(|clip| clip.track_id == main.id)
        .collect();
    if mains.is_empty() {
        return None;
    }
    let owners = mains
        .iter()
        .map(|clip| (clip.id.clone(), clip.start))
        .collect();
    let followers = timeline
        .clips
        .iter()
        .filter(|clip| clip.track_id != main.id && follows(clip))
        .filter_map(|clip| {
            let owner = mains.iter().find(|owner| {
                clip.start >= owner.start - 1e-6 && clip.start < owner.start + owner.duration - 1e-6
            })?;
            (!unlinked.contains(owner.id.as_str()))
                .then(|| (clip.id.clone(), owner.id.clone(), clip.start))
        })
        .collect();
    Some(Before { owners, followers })
}

/// The moves that carry the followers along after an edit: each by as far
/// as its main clip went, on its own lane when there is room there, else
/// on a free lane of its kind. A follower the edit moved itself, or whose
/// main clip is gone, stays where it is.
pub(crate) fn follow(before: &Before, after: &Timeline) -> Vec<ClipMove> {
    let Some(main) = after.tracks.first() else {
        return Vec::new();
    };
    let moving: Vec<&str> = before
        .followers
        .iter()
        .map(|(id, _, _)| id.as_str())
        .collect();
    let mut moves = Vec::new();
    for (id, owner, start) in &before.followers {
        let (Some(old), Some(now)) = (
            before.owners.get(owner),
            after
                .clips
                .iter()
                .find(|clip| clip.id == *owner && clip.track_id == main.id),
        ) else {
            continue;
        };
        let shift = now.start - old;
        if shift.abs() < 1e-6 {
            continue;
        }
        let Some(clip) = after.clips.iter().find(|clip| clip.id == *id) else {
            continue;
        };
        if (clip.start - start).abs() > 1e-6 {
            continue;
        }
        let target = (start + shift).max(0.0);
        let track = if after.is_free(&clip.track_id, target, clip.duration, &moving) {
            clip.track_id.clone()
        } else {
            lanes::free_lane(after, lanes::kind_of(clip), target, clip.duration)
                .unwrap_or_else(|| clip.track_id.clone())
        };
        moves.push(ClipMove {
            clip_id: id.clone(),
            start: target,
            track_id: track,
        });
    }
    moves
}

#[cfg(test)]
mod tests {
    use super::*;
    use concat_project::model::Track;
    use std::sync::Arc;

    fn clip(id: &str, track: &str, kind: ClipKind, start: f64, duration: f64) -> Arc<Clip> {
        Arc::new(Clip {
            id: id.to_owned(),
            track_id: track.to_owned(),
            kind,
            start,
            duration,
            ..Clip::default()
        })
    }

    fn timeline(clips: Vec<Arc<Clip>>) -> Timeline {
        let mut timeline = Timeline::default();
        for id in ["main", "text", "music"] {
            timeline.tracks.push(Track {
                id: id.to_owned(),
                visible: true,
                muted: false,
                extra: Default::default(),
            });
        }
        timeline.clips = clips;
        timeline
    }

    #[test]
    fn a_title_goes_with_its_clip_and_music_stays() {
        let before_edit = timeline(vec![
            clip("a", "main", ClipKind::Video, 0.0, 4.0),
            clip("b", "main", ClipKind::Video, 4.0, 4.0),
            clip("t", "text", ClipKind::Text, 5.0, 2.0),
            clip("m", "music", ClipKind::Audio, 5.0, 2.0),
        ]);
        let tied = before(&before_edit).expect("on");
        // the first clip deleted, the second closed up to zero
        let after_edit = timeline(vec![
            clip("b", "main", ClipKind::Video, 0.0, 4.0),
            clip("t", "text", ClipKind::Text, 5.0, 2.0),
            clip("m", "music", ClipKind::Audio, 5.0, 2.0),
        ]);
        let moves = follow(&tied, &after_edit);
        assert_eq!(moves.len(), 1);
        assert_eq!(moves[0].clip_id, "t");
        assert!((moves[0].start - 1.0).abs() < 1e-9);
    }

    #[test]
    fn switched_off_or_unlinked_nothing_follows() {
        let mut edit = timeline(vec![
            clip("a", "main", ClipKind::Video, 0.0, 4.0),
            clip("t", "text", ClipKind::Text, 1.0, 2.0),
        ]);
        edit.extra
            .insert(LINKAGE.to_owned(), serde_json::json!({"off": true}));
        assert!(before(&edit).is_none());
        edit.extra
            .insert(LINKAGE.to_owned(), serde_json::json!({"unlinked": ["a"]}));
        assert!(before(&edit).expect("on").followers.is_empty());
    }

    #[test]
    fn what_the_edit_moved_itself_is_left_alone() {
        let before_edit = timeline(vec![
            clip("a", "main", ClipKind::Video, 0.0, 4.0),
            clip("b", "main", ClipKind::Video, 4.0, 4.0),
            clip("t", "text", ClipKind::Text, 5.0, 2.0),
        ]);
        let tied = before(&before_edit).expect("on");
        let after_edit = timeline(vec![
            clip("a", "main", ClipKind::Video, 0.0, 2.0),
            clip("b", "main", ClipKind::Video, 2.0, 4.0),
            clip("t", "text", ClipKind::Text, 9.0, 2.0),
        ]);
        assert!(follow(&tied, &after_edit).is_empty());
    }
}

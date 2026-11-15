// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! Body effects: a look that treats the person in a clip apart from what
//! is behind them. A copy of the clip goes on an overlay lane right above
//! it with its background taken off by the segmentation model; the clip
//! under it gets the background's look (blurred, grey, darkened), the copy
//! the person's (an outline). The timeline keeps which copy belongs to
//! which clip and what was added (`capcraftBody`), so the effect can be
//! changed or taken off again, each in one undo step.

use std::sync::Arc;

use concat_project::Command;
use concat_project::model::{Cutout, CutoutMode, Subject};
use serde_json::{Value, json};

use crate::MobileError;
use crate::lanes::{self, RowKind};
use crate::session::{MobileSession, State, copy_look};
use crate::speech::SpeechListener;

/// Where the timeline keeps each clip's body effect.
pub const BODY: &str = "capcraftBody";

/// A body effect's links: those the clip under the person gets, and those
/// the person's copy gets.
fn looks(kind: &str) -> Option<(Vec<Value>, Vec<Value>)> {
    Some(match kind {
        "blur" => (
            vec![json!({"id": "concat.gaussian-blur", "params": {"radius": 18}})],
            Vec::new(),
        ),
        "pop" => (
            vec![json!({"id": "concat.silver", "params": {}})],
            Vec::new(),
        ),
        "spot" => (
            vec![json!({"id": "concat.adjust", "params": {"exposure": -1.6, "saturation": -40}})],
            Vec::new(),
        ),
        "neon" => (
            vec![json!({"id": "concat.adjust", "params": {"exposure": -0.9}})],
            vec![
                json!({"id": "capcraft.outline", "params": {"width": 8, "strength": 90, "hue": 190}}),
            ],
        ),
        // a wide, soft golden glow round the person
        "aura" => (
            vec![json!({"id": "concat.adjust", "params": {"exposure": -0.5}})],
            vec![
                json!({"id": "capcraft.outline", "params": {"width": 26, "strength": 65, "hue": 45}}),
            ],
        ),
        // a thin bright line traced round the person on a dimmed picture
        "lines" => (
            vec![json!({"id": "concat.adjust", "params": {"exposure": -0.7}})],
            vec![
                json!({"id": "capcraft.outline", "params": {"width": 3, "strength": 100, "hue": 300}}),
            ],
        ),
        // the background soft and glowing, the person sharp
        "dream" => (
            vec![
                json!({"id": "concat.gaussian-blur", "params": {"radius": 6}}),
                json!({"id": "concat.glow", "params": {"amount": 70}}),
            ],
            Vec::new(),
        ),
        // the background running through the colours, the person as they are
        "trippy" => (
            vec![json!({"id": "capcraft.rainbow", "params": {"amount": 70, "speed": 0.8}})],
            Vec::new(),
        ),
        // the background breaking up, the person whole
        "glitch" => (
            vec![json!({"id": "capcraft.glitch", "params": {"amount": 70}})],
            Vec::new(),
        ),
        // the person inked as a comic, the background printed on paper
        "comic" => (
            vec![
                json!({"id": "capcraft.comic-ink", "params": {"amount": 50, "levels": 5}}),
                json!({"id": "capcraft.paper", "params": {"amount": 80}}),
            ],
            vec![json!({"id": "capcraft.comic-ink", "params": {"amount": 90, "levels": 4}})],
        ),
        _ => return None,
    })
}

/// A command from its JSON, or the reason it is not one.
fn command(value: Value) -> Result<Command, MobileError> {
    serde_json::from_value(value).map_err(|error| MobileError::Failed(error.to_string()))
}

/// `chain` without the last link of each id in `added`.
fn without(chain: &[Value], added: &[Value]) -> Vec<Value> {
    let mut chain = chain.to_vec();
    for id in added.iter().filter_map(Value::as_str) {
        if let Some(at) = chain
            .iter()
            .rposition(|link| link.get("id").and_then(Value::as_str) == Some(id))
        {
            chain.remove(at);
        }
    }
    chain
}

#[uniffi::export]
impl MobileSession {
    /// Puts body effect `kind` ("blur", "pop", "spot", "neon", "aura",
    /// "lines", "dream", "trippy", "glitch" or "comic") on
    /// `clip_id`, in place of any it had; an empty `kind` takes it off. The
    /// person's masks are then found on a thread of its own, reported
    /// through `listener`.
    pub fn body_effect(
        self: Arc<Self>,
        clip_id: String,
        kind: String,
        listener: Arc<dyn SpeechListener>,
    ) -> Result<(), MobileError> {
        let media = {
            let mut state = self.lock();
            let gesture = self.gesture();
            let made = Self::body_commands(&mut state, &clip_id, &kind, &gesture);
            state.session.end_gesture();
            match made {
                Err(error) => return Err(error),
                Ok(None) => {
                    let view = Self::changed(&mut state, None);
                    drop(state);
                    listener.on_done(Some(view));
                    return Ok(());
                }
                Ok(Some(media)) => {
                    // drawn at once without the masks; again when they are in
                    Self::changed(&mut state, None);
                    media
                }
            }
        };
        self.find_masks(media, listener)
    }
}

impl MobileSession {
    /// The edits for [`MobileSession::body_effect`] under one gesture: the
    /// old effect off, the new one on. The bin item whose masks the copy
    /// needs, or None when there is no new effect.
    fn body_commands(
        state: &mut State,
        clip_id: &str,
        kind: &str,
        gesture: &str,
    ) -> Result<Option<String>, MobileError> {
        let timeline = state.session.project().active().clone();
        if !timeline.clips.iter().any(|clip| clip.id == clip_id) {
            return Err(MobileError::Failed("That clip is gone.".to_owned()));
        }
        let mut kept = match timeline.extra.get(BODY) {
            Some(Value::Object(map)) => map.clone(),
            _ => serde_json::Map::new(),
        };
        // the effect it has comes off first: the copy, and what the clip was given
        if let Some(old) = kept.remove(clip_id) {
            let added: Vec<Value> = old
                .get("added")
                .and_then(Value::as_array)
                .cloned()
                .unwrap_or_default();
            let mut commands = Vec::new();
            if let Some(copy) = old.get("copy").and_then(Value::as_str)
                && timeline.clips.iter().any(|clip| clip.id == copy)
            {
                commands.push(Command::RemoveClips {
                    clip_ids: vec![copy.to_owned()],
                    ripple: false,
                });
            }
            if let Some(clip) = timeline.clips.iter().find(|clip| clip.id == clip_id) {
                let chain =
                    serde_json::to_value(&clip.video_effects).unwrap_or(Value::Array(Vec::new()));
                let chain = chain.as_array().cloned().unwrap_or_default();
                commands.push(command(json!({
                    "op": "updateClip",
                    "clipId": clip_id,
                    "patch": {"videoEffects": without(&chain, &added)},
                }))?);
            }
            Self::apply_all(state, commands, Some(gesture))?;
        }
        let Some((under, over)) = looks(kind) else {
            Self::apply_all(
                state,
                vec![Command::SetTimelineExtra {
                    key: BODY.to_owned(),
                    value: (!kept.is_empty()).then_some(Value::Object(kept)),
                }],
                Some(gesture),
            )?;
            return Ok(None);
        };
        let timeline = state.session.project().active().clone();
        let clip = timeline
            .clips
            .iter()
            .find(|clip| clip.id == clip_id)
            .cloned()
            .ok_or_else(|| MobileError::Failed("That clip is gone.".to_owned()))?;
        // the copy on a lane of its own over the clip; it is placed at the file's
        // whole length before it is cut to the clip's, so the lane must be free for that
        let whole = state
            .session
            .project()
            .media
            .iter()
            .find(|item| item.id == clip.media_id)
            .and_then(|item| item.duration)
            .unwrap_or(clip.duration)
            .max(clip.duration);
        let track = match lanes::free_lane(&timeline, RowKind::Overlay, clip.start, whole) {
            Some(track) => track,
            None => {
                let index = lanes::insert_index(&timeline, RowKind::Overlay);
                Self::apply_all(state, vec![Command::InsertTrack { index }], Some(gesture))?
                    .ok_or_else(|| MobileError::Failed("The new lane has no id.".to_owned()))?
            }
        };
        let copy = Self::apply_all(
            state,
            vec![Command::AddClip {
                media_id: clip.media_id.clone(),
                track_id: track,
                start: clip.start,
                ripple: false,
            }],
            Some(gesture),
        )?
        .ok_or_else(|| MobileError::Failed("The copy was not made.".to_owned()))?;
        copy_look(state, &clip, &copy, gesture)?;
        let mine = serde_json::to_value(&clip.video_effects).unwrap_or(Value::Array(Vec::new()));
        let mine = mine.as_array().cloned().unwrap_or_default();
        let mut person = mine.clone();
        person.extend(over);
        let mut background = mine;
        background.extend(under.iter().cloned());
        let added: Vec<Value> = under
            .iter()
            .filter_map(|link| link.get("id").cloned())
            .collect();
        kept.insert(
            clip_id.to_owned(),
            json!({"copy": copy, "kind": kind, "added": added}),
        );
        Self::apply_all(
            state,
            vec![
                command(json!({
                    "op": "updateClip",
                    "clipId": copy,
                    "patch": {"videoEffects": person, "volume": 0.0},
                }))?,
                Command::SetClipCutout {
                    clip_id: copy.clone(),
                    cutout: Some(Cutout {
                        mode: CutoutMode::Auto,
                        subject: Subject::Auto,
                        feather: 0.002,
                        strokes: Vec::new(),
                    }),
                },
                command(json!({
                    "op": "updateClip",
                    "clipId": clip_id,
                    "patch": {"videoEffects": background},
                }))?,
                Command::SetTimelineExtra {
                    key: BODY.to_owned(),
                    value: Some(Value::Object(kept)),
                },
            ],
            Some(gesture),
        )?;
        Ok(Some(clip.media_id.clone()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_effect_has_a_look_for_the_background() {
        for kind in [
            "blur", "pop", "spot", "neon", "aura", "lines", "dream", "trippy", "glitch", "comic",
        ] {
            let (under, _) = looks(kind).expect("a look");
            assert!(!under.is_empty(), "{kind}");
        }
        assert!(looks("").is_none());
    }

    #[test]
    fn taking_an_effect_off_takes_only_what_it_added() {
        let chain = vec![
            json!({"id": "concat.gaussian-blur", "params": {"radius": 3}}),
            json!({"id": "concat.vhs"}),
            json!({"id": "concat.gaussian-blur", "params": {"radius": 18}}),
        ];
        let left = without(&chain, &[json!("concat.gaussian-blur")]);
        assert_eq!(left, chain[..2].to_vec());
    }
}

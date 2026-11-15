// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! What each undo step is called, for the notice an undo or a redo shows
//! ("Undo: Split"). The engine keeps the steps but not their names, so the
//! bridge keeps a name beside each one, taken from the first command of the
//! step, and follows the engine's own count of steps so a step it did not
//! see made is still there, as a plain "Edit".

use std::collections::VecDeque;

use concat_host::Session;
use concat_project::Command;
use serde_json::Value;

/// The name of a step nothing more is known about.
const PLAIN: &str = "Edit";

/// The names of the steps undo can go back through, oldest first, and of
/// those redo can go forward through, the next one last.
pub(crate) struct Names {
    back: VecDeque<String>,
    forward: Vec<String>,
    recorded: u64,
}

impl Names {
    /// Names for a session just opened: any steps it already holds are plain.
    pub(crate) fn new(session: &Session) -> Self {
        let mut names = Self {
            back: VecDeque::new(),
            forward: Vec::new(),
            recorded: session.steps_recorded(),
        };
        names.align(session);
        names
    }

    /// After commands ran: a step they made is called `name`; a step they
    /// folded into keeps the name it had, unless that was plain - a lane
    /// made for a clip is a step of no name until the clip lands on it.
    pub(crate) fn after(&mut self, session: &Session, name: &str) {
        let made = session.steps_recorded().saturating_sub(self.recorded);
        self.recorded = session.steps_recorded();
        for _ in 0..made {
            self.back.push_back(name.to_owned());
        }
        if made > 0 {
            self.forward.clear();
        } else if name != PLAIN
            && let Some(last) = self.back.back_mut()
            && last == PLAIN
        {
            *last = name.to_owned();
        }
        self.align(session);
    }

    /// After an undo: the step it went back through can be redone.
    pub(crate) fn undone(&mut self, session: &Session) {
        if let Some(name) = self.back.pop_back() {
            self.forward.push(name);
        }
        self.align(session);
    }

    /// After a redo: the step it went forward through can be undone again.
    pub(crate) fn redone(&mut self, session: &Session) {
        if let Some(name) = self.forward.pop() {
            self.back.push_back(name);
        }
        self.align(session);
    }

    /// What an undo now would take back.
    pub(crate) fn to_undo(&self) -> String {
        self.back.back().cloned().unwrap_or_default()
    }

    /// What a redo now would bring back.
    pub(crate) fn to_redo(&self) -> String {
        self.forward.last().cloned().unwrap_or_default()
    }

    /// As many names as the engine has steps: steps it recorded without
    /// a word here are plain, and the oldest names go as its history drops
    /// its oldest steps.
    fn align(&mut self, session: &Session) {
        let unseen = session.steps_recorded().saturating_sub(self.recorded);
        self.recorded = session.steps_recorded();
        for _ in 0..unseen {
            self.back.push_back(PLAIN.to_owned());
        }
        while self.back.len() > session.undo_depth() {
            self.back.pop_front();
        }
        while self.back.len() < session.undo_depth() {
            self.back.push_front(PLAIN.to_owned());
        }
        while self.forward.len() > session.redo_depth() {
            self.forward.remove(0);
        }
        while self.forward.len() < session.redo_depth() {
            self.forward.insert(0, PLAIN.to_owned());
        }
    }
}

/// What a step made of `commands` is called: by its first command, and for
/// a change to a clip by the part of it that changed.
pub(crate) fn name_of(commands: &[Command]) -> String {
    let Some(first) = commands.first() else {
        return PLAIN.to_owned();
    };
    if let Command::Batch { commands } = first {
        return name_of(commands);
    }
    let value = serde_json::to_value(first).unwrap_or(Value::Null);
    let name = match value.get("op").and_then(Value::as_str).unwrap_or_default() {
        "addClip" | "addClipAtFirstFree" | "addMedia" => "Add clip",
        "addTextClip" => "Add text",
        "addShapeClip" => "Add sticker",
        "addLayerClip" => "Add effect",
        "setClipSpeed" | "setClipSpeedCurve" => "Speed",
        "setClipKey" | "clearClipKey" | "clearClipKeys" | "setEffectKey" | "clearEffectKey"
        | "clearEffectKeys" => "Keyframe",
        "setClipCutout" | "addCutoutStroke" => "Remove background",
        "moveClips" => "Move",
        "trimClip" => "Trim",
        "splitClips" => "Split",
        "replaceClipMedia" => "Replace",
        "setClipEnhanced" => "Enhance",
        "freezeFrame" => "Freeze",
        "mergeClips" => "Merge",
        "removeClips" => "Delete",
        "setClipTransform" => "Transform",
        "detachAudio" => "Extract audio",
        "reattachAudio" => "Recover audio",
        "setTimelineVideo" => "Format",
        "updateClip" => patch_name(value.get("patch")),
        // CapCraft's own settings kept on the timeline, by where they are kept
        "setTimelineExtra" => match value.get("key").and_then(Value::as_str).unwrap_or_default() {
            "capcraftBackground" | "capcraftBackgrounds" => "Background",
            "capcraftAnimations" => "Animation",
            _ => PLAIN,
        },
        _ => PLAIN,
    };
    name.to_owned()
}

/// What a change to a clip is called, by the first part of it set.
fn patch_name(patch: Option<&Value>) -> &'static str {
    let set = |key: &str| {
        patch
            .and_then(|patch| patch.get(key))
            .is_some_and(|value| !value.is_null())
    };
    [
        ("text", "Edit text"),
        ("videoEffects", "Effects"),
        ("filters", "Audio effects"),
        ("volume", "Volume"),
        ("muted", "Mute"),
        ("fadeIn", "Fade"),
        ("fadeOut", "Fade"),
        ("opacity", "Opacity"),
        ("blend", "Blend"),
        ("crop", "Crop"),
        ("flipH", "Mirror"),
        ("flipV", "Flip"),
        ("transitionIn", "Transition"),
        ("shape", "Style"),
        ("name", "Rename"),
    ]
    .into_iter()
    .find(|(key, _)| set(key))
    .map_or(PLAIN, |(_, name)| name)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_step_is_named_by_its_first_command() {
        let split = serde_json::from_value::<Command>(
            serde_json::json!({"op": "splitClips", "clipIds": ["a"], "time": 1.0}),
        )
        .expect("a split");
        assert_eq!(name_of(&[split]), "Split");
        let words = serde_json::from_value::<Command>(serde_json::json!({
            "op": "updateClip", "clipId": "a", "patch": {"text": {"content": "Hi"}}
        }))
        .expect("a text change");
        assert_eq!(name_of(&[words]), "Edit text");
        assert_eq!(name_of(&[]), "Edit");
    }
}

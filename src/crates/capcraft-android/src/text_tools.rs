// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! A title's tools: Lasting text, which keeps it on screen from the first
//! frame to the last; Auto split, which deals a long title out into one
//! clip per line or sentence, one after the other in its time; and fonts
//! of one's own, added to the project from a file.

use concat_project::Command;
use concat_project::commands::{ClipMove, TrimEdge};
use serde_json::json;

use crate::MobileError;
use crate::frames::FontFace;
use crate::lanes;
use crate::session::{MobileSession, TimelineView};

/// Whether `ch` ends a sentence.
fn ends_sentence(ch: char) -> bool {
    matches!(ch, '.' | '!' | '?' | '…' | '。' | '！' | '？')
}

/// The pieces of `words` Auto split deals out: its lines, or, when it is
/// one line, its sentences. Fewer than two pieces is nothing to split, and
/// comes back empty.
pub(crate) fn parts(words: &str) -> Vec<String> {
    let lines: Vec<&str> = words
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .collect();
    if lines.len() >= 2 {
        return lines.into_iter().map(str::to_owned).collect();
    }
    let mut found = Vec::new();
    let mut current = String::new();
    let mut chars = lines
        .first()
        .copied()
        .unwrap_or_default()
        .chars()
        .peekable();
    while let Some(ch) = chars.next() {
        current.push(ch);
        if !ends_sentence(ch) {
            continue;
        }
        // "...", "?!" and a closing quote or bracket stay with the sentence
        while let Some(&next) = chars.peek() {
            if ends_sentence(next) || matches!(next, '"' | '\'' | '”' | '’' | ')' | '」' | '』')
            {
                current.push(next);
                chars.next();
            } else {
                break;
            }
        }
        // a full stop of the wide scripts needs no space after it
        let wide = matches!(ch, '。' | '！' | '？');
        if wide || chars.peek().is_none_or(|next| next.is_whitespace()) {
            let sentence = current.trim();
            if !sentence.is_empty() {
                found.push(sentence.to_owned());
            }
            current.clear();
        }
    }
    let rest = current.trim();
    if !rest.is_empty() {
        found.push(rest.to_owned());
    }
    if found.len() < 2 { Vec::new() } else { found }
}

#[uniffi::export]
impl MobileSession {
    /// Keeps `clip_id` on screen for the whole video: from the start to
    /// where the main track ends, on its own row if that is free for so
    /// long, else on another or a new one. One undo step.
    pub fn lasting_text(&self, clip_id: String) -> Result<TimelineView, MobileError> {
        let mut state = self.lock();
        let timeline = state.session.project().active().clone();
        let clip = timeline
            .clips
            .iter()
            .find(|clip| clip.id == clip_id)
            .ok_or_else(|| MobileError::Failed("That clip is gone.".to_owned()))?
            .clone();
        let end = lanes::main_end(&timeline);
        if end <= 0.0 {
            return Err(MobileError::Failed("Add a video first.".to_owned()));
        }
        let gesture = self.gesture();
        let result = (|| {
            let lane = if timeline.is_free(&clip.track_id, 0.0, end, &[clip_id.as_str()]) {
                clip.track_id.clone()
            } else {
                match lanes::free_lane(&timeline, lanes::kind_of(&clip), 0.0, end) {
                    Some(lane) => lane,
                    None => Self::apply_all(
                        &mut state,
                        vec![Command::InsertTrack {
                            index: lanes::insert_index(&timeline, lanes::kind_of(&clip)),
                        }],
                        Some(&gesture),
                    )?
                    .ok_or_else(|| "the new lane has no id".to_owned())?,
                }
            };
            Self::apply_all(
                &mut state,
                vec![
                    Command::MoveClips {
                        moves: vec![ClipMove {
                            clip_id: clip_id.clone(),
                            track_id: lane,
                            start: 0.0,
                        }],
                    },
                    Command::TrimClip {
                        clip_id: clip_id.clone(),
                        edge: TrimEdge::End,
                        delta: end - clip.duration,
                        ripple: false,
                    },
                ],
                Some(&gesture),
            )?;
            Ok::<_, MobileError>(())
        })();
        state.session.end_gesture();
        result?;
        Ok(Self::changed(&mut state, None))
    }

    /// Deals title `clip_id` out into one clip per line, or per sentence
    /// when it is one line, each as long as its share of the words, one
    /// after the other where the title was. Errs when there is only one
    /// piece. One undo step.
    pub fn auto_split_text(&self, clip_id: String) -> Result<TimelineView, MobileError> {
        let mut state = self.lock();
        let timeline = state.session.project().active().clone();
        let clip = timeline
            .clips
            .iter()
            .find(|clip| clip.id == clip_id)
            .ok_or_else(|| MobileError::Failed("That clip is gone.".to_owned()))?
            .clone();
        let words = clip
            .text
            .as_ref()
            .map(|text| text.content.clone())
            .unwrap_or_default();
        let pieces = parts(&words);
        if pieces.is_empty() {
            return Err(MobileError::Failed(
                "There is too little text to split.".to_owned(),
            ));
        }
        // where each piece after the first begins, by its share of the letters
        let weights: Vec<f64> = pieces
            .iter()
            .map(|piece| piece.chars().count().max(1) as f64)
            .collect();
        let total: f64 = weights.iter().sum();
        let mut cuts = Vec::new();
        let mut so_far = 0.0;
        for weight in &weights[..weights.len() - 1] {
            so_far += weight;
            cuts.push(clip.start + clip.duration * so_far / total);
        }
        let gesture = self.gesture();
        let result = (|| {
            // the last cut first, so each split is of the piece still holding the rest
            for time in cuts.iter().rev() {
                Self::apply_all(
                    &mut state,
                    vec![Command::SplitClips {
                        clip_ids: vec![clip_id.clone()],
                        time: *time,
                    }],
                    Some(&gesture),
                )?;
            }
            let after = state.session.project().active().clone();
            let mut split: Vec<_> = after
                .clips
                .iter()
                .filter(|piece| {
                    piece.track_id == clip.track_id
                        && piece.start >= clip.start - 1e-6
                        && piece.start < clip.start + clip.duration - 1e-6
                })
                .collect();
            split.sort_by(|a, b| a.start.total_cmp(&b.start));
            let mut patches = Vec::new();
            for (piece, words) in split.iter().zip(&pieces) {
                let Some(mut style) = piece.text.clone() else {
                    continue;
                };
                style.content = words.clone();
                patches.push(
                    serde_json::from_value::<Command>(json!({
                        "op": "updateClip",
                        "clipId": piece.id,
                        "patch": {"text": style},
                    }))
                    .map_err(|error| MobileError::Failed(error.to_string()))?,
                );
            }
            Self::apply_all(&mut state, patches, Some(&gesture))?;
            Ok::<_, MobileError>(())
        })();
        state.session.end_gesture();
        result?;
        Ok(Self::changed(&mut state, None))
    }
}

#[uniffi::export]
impl MobileSession {
    /// Adds the font file at `path` (already in the project's folder) to
    /// the project, under each family it carries, and hands those back.
    /// Errs on a file that is not a font. One undo step.
    pub fn add_font(&self, path: String) -> Result<Vec<FontFace>, MobileError> {
        let families = concat_text::families_in(std::path::Path::new(&path));
        if families.is_empty() {
            return Err(MobileError::Failed(
                "That file is not a font this app can read.".to_owned(),
            ));
        }
        let commands = families
            .iter()
            .map(|family| Command::AddFont {
                family: family.clone(),
                path: path.clone(),
            })
            .collect();
        self.run(commands)?;
        Ok(families
            .into_iter()
            .map(|family| FontFace {
                family,
                path: path.clone(),
            })
            .collect())
    }

    /// The fonts added to this project, each family once, for the font
    /// picker to list before the ones every project has.
    pub fn project_fonts(&self) -> Vec<FontFace> {
        let state = self.lock();
        let mut faces: Vec<FontFace> = Vec::new();
        for font in &state.session.project().fonts {
            if !faces.iter().any(|face| face.family == font.family) {
                faces.push(FontFace {
                    family: font.family.clone(),
                    path: font.path.clone(),
                });
            }
        }
        faces
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lines_come_apart_before_sentences() {
        assert_eq!(parts("One. Two.\nThree"), vec!["One. Two.", "Three"]);
        assert_eq!(
            parts("Hello there. How are you? Fine..."),
            vec!["Hello there.", "How are you?", "Fine..."]
        );
    }

    #[test]
    fn short_words_and_dotted_numbers_stay_whole() {
        assert!(parts("Hi").is_empty());
        assert!(parts("Version 2.5 is out").is_empty());
        assert_eq!(parts("你好。再见。"), vec!["你好。", "再见。"]);
    }
}

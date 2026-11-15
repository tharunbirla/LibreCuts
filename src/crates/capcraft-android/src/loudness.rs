// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! Loudness adjustment: every clip with sound brought to the loudness of
//! one of them, so a quiet voice-over and a loud song sit together.
//!
//! The chosen clip's stretch of sound is measured - the mean power of its
//! 400 ms blocks, the near-silent ones left out, as broadcast loudness is
//! gated - and the engine's loudness package is set to that level on it
//! and on every other clip with sound, as one undo step.

use concat_media::{AudioDecoder, AudioOptions, SampleFormat};
use concat_project::Command;
use concat_project::model::{ClipKind, MediaKind};
use serde_json::{Value, json};

use crate::MobileError;
use crate::session::{MobileSession, TimelineView};

/// The engine's loudness package and the levels it takes.
const LOUDNESS: &str = "concat.loudness";
const QUIETEST: f64 = -30.0;
const LOUDEST: f64 = -12.0;

/// The loudness of `samples` (mono, at `rate`), in dB of full scale: the
/// mean power of its 400 ms blocks, leaving out blocks quieter than -60
/// dB; None when all of it is that quiet.
pub(crate) fn level(samples: &[f32], rate: u32) -> Option<f64> {
    let block = (rate as usize * 2 / 5).max(1);
    let powers: Vec<f64> = samples
        .chunks(block)
        .filter(|chunk| chunk.len() * 2 >= block)
        .map(|chunk| {
            chunk
                .iter()
                .map(|&s| f64::from(s) * f64::from(s))
                .sum::<f64>()
                / chunk.len() as f64
        })
        .filter(|&power| power > 1e-6)
        .collect();
    if powers.is_empty() {
        return None;
    }
    let mean = powers.iter().sum::<f64>() / powers.len() as f64;
    Some(10.0 * mean.log10())
}

#[uniffi::export]
impl MobileSession {
    /// Brings every clip with sound to `clip_id`'s loudness (`on`), or
    /// takes the loudness adjustment off every clip (`on` false). One undo
    /// step.
    pub fn match_loudness(&self, clip_id: String, on: bool) -> Result<TimelineView, MobileError> {
        let (path, from, span, stream, sounding) = self.with_session(|session| {
            let project = session.project();
            let timeline = project.active();
            let clip = timeline
                .clips
                .iter()
                .find(|clip| clip.id == clip_id)
                .ok_or_else(|| MobileError::Failed("That clip is gone.".to_owned()))?;
            let media = project
                .media
                .iter()
                .find(|item| item.id == clip.media_id)
                .ok_or_else(|| MobileError::Failed("The clip's file is gone.".to_owned()))?;
            // every clip whose sound plays: sound clips, and pictures from files with sound
            let sounding: Vec<(String, Vec<Value>)> = timeline
                .clips
                .iter()
                .filter(|other| {
                    other.muted != Some(true)
                        && match other.kind {
                            ClipKind::Audio => true,
                            ClipKind::Video => project
                                .media
                                .iter()
                                .find(|item| item.id == other.media_id)
                                .is_some_and(|item| {
                                    item.has_audio && !matches!(item.kind, MediaKind::Image)
                                }),
                            _ => false,
                        }
                })
                .map(|other| {
                    let chain =
                        serde_json::to_value(&other.filters).unwrap_or(Value::Array(Vec::new()));
                    (
                        other.id.clone(),
                        chain.as_array().cloned().unwrap_or_default(),
                    )
                })
                .collect();
            Ok((
                media.path.clone(),
                clip.source_start,
                clip.duration * clip.speed,
                clip.audio_stream.map(|index| index as usize),
                sounding,
            ))
        })?;
        let target = if on {
            let mut decoder = AudioDecoder::open(
                &path,
                &AudioOptions {
                    start: Some(from),
                    duration: Some(span.max(0.1)),
                    filters: Vec::new(),
                    rate: 16_000,
                    channels: 1,
                    format: SampleFormat::F32,
                    stream,
                },
            )
            .map_err(|error| MobileError::Failed(error.to_string()))?;
            let samples = decoder
                .collect_f32()
                .map_err(|error| MobileError::Failed(error.to_string()))?;
            let measured = level(&samples, 16_000)
                .ok_or_else(|| MobileError::Failed("The clip is silent.".to_owned()))?;
            Some(measured.clamp(QUIETEST, LOUDEST).round())
        } else {
            None
        };
        let mut commands = Vec::new();
        for (id, chain) in sounding {
            let mut next: Vec<Value> = chain
                .into_iter()
                .filter(|link| link.get("id").and_then(Value::as_str) != Some(LOUDNESS))
                .collect();
            if let Some(target) = target {
                // cleaned first and levelled after, so the level is of the cleaned sound
                next.push(json!({"id": LOUDNESS, "params": {"target": target}}));
            }
            commands.push(
                serde_json::from_value::<Command>(
                    json!({"op": "updateClip", "clipId": id, "patch": {"filters": next}}),
                )
                .map_err(|error| MobileError::Failed(error.to_string()))?,
            );
        }
        self.run(commands)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_tone_at_half_scale_measures_about_minus_nine() {
        // a sine at amplitude 0.5 has a mean power of 0.125: -9 dB
        let samples: Vec<f32> = (0..32_000).map(|i| 0.5 * (i as f32 * 0.07).sin()).collect();
        let measured = level(&samples, 16_000).expect("a level");
        assert!((measured + 9.03).abs() < 0.2, "{measured}");
    }

    #[test]
    fn silence_has_no_level_and_quiet_gaps_do_not_pull_it_down() {
        assert!(level(&vec![0.0; 16_000], 16_000).is_none());
        // three whole 400 ms blocks of tone, then a second of silence
        let mut samples: Vec<f32> = (0..19_200).map(|i| 0.5 * (i as f32 * 0.07).sin()).collect();
        samples.extend(std::iter::repeat_n(0.0, 16_000));
        let measured = level(&samples, 16_000).expect("a level");
        assert!((measured + 9.03).abs() < 0.3, "{measured}");
    }
}

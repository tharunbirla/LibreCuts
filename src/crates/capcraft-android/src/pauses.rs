// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! Remove pauses: the quiet stretches between what is said, cut out of the
//! main track so the talk runs on.
//!
//! Each main clip's sound is read small; a stretch at least `shortest`
//! long that stays well under the clip's own loudness (25 dB under it, and
//! under -40 dB of full scale) is a pause. A little of each pause is kept at
//! either end, so words are not clipped, and the rest of every pause goes
//! in one undo step. A transcript of the talk comes back moved to match, so
//! the lines stay under what they say.

use concat_media::{AudioDecoder, AudioOptions, SampleFormat};
use concat_project::model::{ClipKind, MediaKind};

use crate::loudness::level;
use crate::session::{MobileSession, TimelineView};
use crate::speech::TranscriptLine;
use crate::{MobileError, micros, seconds};

/// The rate the sound is read at for this.
const RATE: u32 = 8_000;
/// How long each measured slice is, seconds.
const SLICE: f64 = 0.02;
/// How much of a pause is kept at either end, seconds.
const KEEP: f64 = 0.12;

/// The pauses in `samples` (mono, at `rate`): stretches at least
/// `shortest` seconds long whose slices all stay under the quiet line,
/// each trimmed by [`KEEP`] at either end, in seconds from the start.
pub(crate) fn pauses(samples: &[f32], rate: u32, shortest: f64) -> Vec<(f64, f64)> {
    let Some(loud) = level(samples, rate) else {
        return Vec::new();
    };
    let line = (loud - 25.0).min(-40.0);
    let slice = ((rate as f64 * SLICE) as usize).max(1);
    let quiet: Vec<bool> = samples
        .chunks(slice)
        .map(|chunk| {
            let power = chunk
                .iter()
                .map(|&s| f64::from(s) * f64::from(s))
                .sum::<f64>()
                / chunk.len().max(1) as f64;
            10.0 * power.max(1e-12).log10() < line
        })
        .collect();
    let mut found = Vec::new();
    let mut run_start: Option<usize> = None;
    for (i, &q) in quiet.iter().chain(std::iter::once(&false)).enumerate() {
        match (q, run_start) {
            (true, None) => run_start = Some(i),
            (false, Some(from)) => {
                let (start, end) = (from as f64 * SLICE, i as f64 * SLICE);
                if end - start >= shortest {
                    let (start, end) = (start + KEEP, end - KEEP);
                    if end - start > 0.05 {
                        found.push((start, end));
                    }
                }
                run_start = None;
            }
            _ => {}
        }
    }
    found
}

/// Where `t` (seconds) lands once every range of `cuts` is taken out: back
/// by all that was cut before it, and onto a cut's start when inside one.
fn after_cuts(t: f64, cuts: &[(f64, f64)]) -> f64 {
    t - cuts
        .iter()
        .map(|&(a, b)| (t - a).clamp(0.0, (b - a).max(0.0)))
        .sum::<f64>()
}

/// `lines` moved to where they sit once `cuts` are taken out; a line that
/// was all pause is dropped.
fn moved(lines: Vec<TranscriptLine>, cuts: &[(f64, f64)]) -> Vec<TranscriptLine> {
    lines
        .into_iter()
        .filter_map(|line| {
            let start = micros(after_cuts(seconds(line.start_us), cuts));
            let end = micros(after_cuts(seconds(line.end_us), cuts));
            (end > start).then_some(TranscriptLine {
                start_us: start,
                end_us: end,
                ..line
            })
        })
        .collect()
}

/// What removing pauses leaves: the timeline, and the transcript moved to
/// match it.
#[derive(Debug, Clone, uniffi::Record)]
pub struct PausesRemoved {
    /// The timeline without the pauses.
    pub view: TimelineView,
    /// The transcript's lines where they now sit.
    pub lines: Vec<TranscriptLine>,
}

#[uniffi::export]
impl MobileSession {
    /// Cuts every pause of at least `shortest_us` out of the main track's
    /// talk, as one undo step, and moves `lines` (the talk's transcript) to
    /// match. Errs when there is none.
    pub fn remove_pauses(
        &self,
        shortest_us: i64,
        lines: Vec<TranscriptLine>,
    ) -> Result<PausesRemoved, MobileError> {
        let shortest = seconds(shortest_us).max(0.2);
        // each main clip with sound: where it sits, its speed, and its stretch of file
        let clips = self.with_session(|session| {
            let project = session.project();
            let timeline = project.active();
            let Some(main) = timeline.tracks.first() else {
                return Ok(Vec::new());
            };
            Ok(timeline
                .clips
                .iter()
                .filter(|clip| {
                    clip.track_id == main.id
                        && clip.kind == ClipKind::Video
                        && clip.muted != Some(true)
                })
                .filter_map(|clip| {
                    let media = project.media.iter().find(|item| item.id == clip.media_id)?;
                    (media.has_audio && !matches!(media.kind, MediaKind::Image)).then(|| {
                        (
                            clip.start,
                            clip.speed.max(1e-6),
                            media.path.clone(),
                            clip.source_start,
                            clip.duration * clip.speed,
                            clip.audio_stream.map(|index| index as usize),
                        )
                    })
                })
                .collect::<Vec<_>>())
        })?;
        let mut cuts = Vec::new();
        for (start, speed, path, from, span, stream) in clips {
            let mut decoder = AudioDecoder::open(
                &path,
                &AudioOptions {
                    start: Some(from),
                    duration: Some(span.max(0.05)),
                    filters: Vec::new(),
                    rate: RATE,
                    channels: 1,
                    format: SampleFormat::F32,
                    stream,
                },
            )
            .map_err(|error| MobileError::Failed(error.to_string()))?;
            let samples = decoder
                .collect_f32()
                .map_err(|error| MobileError::Failed(error.to_string()))?;
            // a pause in the file is a pause on the timeline, shrunk or stretched by the speed
            cuts.extend(
                pauses(&samples, RATE, shortest * speed)
                    .into_iter()
                    .map(|(a, b)| (start + a / speed, start + b / speed)),
            );
        }
        if cuts.is_empty() {
            return Err(MobileError::Failed("No pauses were found.".to_owned()));
        }
        let view = self.cut_ranges(&cuts)?;
        Ok(PausesRemoved {
            view,
            lines: moved(lines, &cuts),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tone(seconds: f64) -> Vec<f32> {
        (0..(seconds * RATE as f64) as usize)
            .map(|i| 0.4 * (i as f32 * 0.3).sin())
            .collect()
    }

    #[test]
    fn a_long_quiet_between_two_sounds_is_found_and_trimmed() {
        let mut samples = tone(1.0);
        samples.extend(std::iter::repeat_n(0.0, RATE as usize));
        samples.extend(tone(1.0));
        let found = pauses(&samples, RATE, 0.5);
        assert_eq!(found.len(), 1, "{found:?}");
        let (a, b) = found[0];
        assert!((a - (1.0 + KEEP)).abs() < 0.03, "{a}");
        assert!((b - (2.0 - KEEP)).abs() < 0.03, "{b}");
    }

    #[test]
    fn lines_come_forward_by_the_pauses_before_them() {
        let line = |a: f64, b: f64| TranscriptLine {
            start_us: micros(a),
            end_us: micros(b),
            text: String::new(),
        };
        // a pause from 1.5 to 2.5 s and one from 4 to 5 s
        let cuts = [(1.5, 2.5), (4.0, 5.0)];
        let lines = moved(
            vec![
                line(0.0, 1.4),
                line(2.6, 3.9),
                line(4.2, 4.8),
                line(5.1, 6.0),
            ],
            &cuts,
        );
        let times: Vec<(i64, i64)> = lines.iter().map(|l| (l.start_us, l.end_us)).collect();
        // the one inside the second pause is gone
        assert_eq!(
            times,
            vec![
                (0, 1_400_000),
                (1_600_000, 2_900_000),
                (3_100_000, 4_000_000)
            ]
        );
    }

    #[test]
    fn a_short_breath_is_left_alone() {
        let mut samples = tone(1.0);
        samples.extend(std::iter::repeat_n(0.0, (RATE as f64 * 0.3) as usize));
        samples.extend(tone(1.0));
        assert!(pauses(&samples, RATE, 0.5).is_empty());
    }
}

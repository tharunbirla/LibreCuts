// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! Beats: the moments a piece of sound hits, found from its waveform, for the
//! timeline's beat marks and for edges to snap to.
//!
//! The waveform peaks the timeline already draws (and caches beside the
//! project) are enough: a hit is a sudden rise in loudness that stands out
//! from the bars around it. Nothing is decoded twice.

use concat_media::peaks::Peaks;

use crate::{MobileError, micros, seconds};

/// The step the loudness is read at: a hundredth of a second.
const FRAME: f64 = 0.01;

/// Where the sound of `path` hits between `from_us` and `to_us` (times in
/// the file, not the timeline). `dense` keeps closer and weaker hits; the
/// sparse reading keeps the strong ones a beat apart. `project` is the
/// project folder, where the waveform is cached.
#[uniffi::export]
pub fn detect_beats(
    path: String,
    project: String,
    from_us: i64,
    to_us: i64,
    dense: bool,
) -> Result<Vec<i64>, MobileError> {
    let peaks = concat_host::media::peaks(
        &path,
        None,
        (!project.is_empty()).then_some(project.as_str()),
    )?;
    let from = seconds(from_us).max(0.0);
    let to = seconds(to_us).max(from);
    Ok(onsets(&envelope(&peaks, from, to), from, dense)
        .into_iter()
        .map(micros)
        .collect())
}

/// The loudest sample of each frame from `from` to `to`, in 0..1.
fn envelope(peaks: &Peaks, from: f64, to: f64) -> Vec<f32> {
    let frames = ((to - from) / FRAME).floor().max(0.0) as usize;
    (0..frames)
        .map(|index| {
            let start = (from + index as f64 * FRAME) as f32;
            let (low, high) = peaks.extremes(start, start + FRAME as f32);
            high.max(-low)
        })
        .collect()
}

/// The times (from `from`, a frame apart) where `level` jumps: each frame's
/// rise over the loudest of the few before it, kept where it is the largest
/// rise nearby and clearly above the rises around it.
pub(crate) fn onsets(level: &[f32], from: f64, dense: bool) -> Vec<f64> {
    // a log scale, so a hit counts the same in a quiet bar as in a loud one
    let loudness: Vec<f32> = level.iter().map(|value| (value + 1e-3).ln()).collect();
    let rise: Vec<f32> = (0..loudness.len())
        .map(|index| {
            let past = loudness[index.saturating_sub(3)..index]
                .iter()
                .copied()
                .fold(f32::NEG_INFINITY, f32::max);
            if past.is_finite() {
                (loudness[index] - past).max(0.0)
            } else {
                0.0
            }
        })
        .collect();
    // frames either side the average rise is taken over, the shortest gap
    // between hits in seconds, and how far above that average a hit stands
    let (window, gap, lift) = if dense {
        (25, 0.18, 1.3)
    } else {
        (50, 0.35, 1.8)
    };
    let mut hits = Vec::new();
    let mut last = f64::NEG_INFINITY;
    for index in 0..rise.len() {
        let around = &rise[index.saturating_sub(window)..(index + window + 1).min(rise.len())];
        let mean = around.iter().sum::<f32>() / around.len() as f32;
        let nearby = &rise[index.saturating_sub(5)..(index + 6).min(rise.len())];
        let peak = nearby.iter().all(|other| *other <= rise[index]);
        let at = from + index as f64 * FRAME;
        if peak && rise[index] > mean * lift + 0.15 && at - last >= gap {
            hits.push(at);
            last = at;
        }
    }
    hits
}

#[cfg(test)]
mod tests {
    use super::onsets;

    /// A quiet bed with a sharp hit every half second, each dying away.
    fn clicks(seconds: f64, every: f64) -> Vec<f32> {
        let frames = (seconds / super::FRAME) as usize;
        (0..frames)
            .map(|index| {
                let t = index as f64 * super::FRAME;
                let since = t % every;
                (0.05 + 0.9 * (-since * 20.0).exp()) as f32
            })
            .collect()
    }

    #[test]
    fn finds_regular_hits() {
        let found = onsets(&clicks(4.0, 0.5), 10.0, false);
        // the first frame has no past to rise over; every later hit is found
        assert_eq!(found.len(), 7, "{found:?}");
        for (index, at) in found.iter().enumerate() {
            let wanted = 10.5 + index as f64 * 0.5;
            assert!((at - wanted).abs() < 0.02, "{at} for {wanted}");
        }
    }

    #[test]
    fn steady_sound_has_no_hits() {
        assert!(onsets(&[0.4; 300], 0.0, true).is_empty());
    }
}

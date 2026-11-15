// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! Caption files: SubRip (`.srt`) and WebVTT (`.vtt`) read into titles on a
//! text row, the way auto captions are placed, and the timeline's captions
//! written out as SubRip.

use concat_project::model::ClipKind;
use concat_project::srt;

use crate::MobileError;
use crate::session::{MobileSession, TimelineView};

/// One cue of a caption file: from, to (seconds) and the words.
pub(crate) type Cue = (f64, f64, String);

#[uniffi::export]
impl MobileSession {
    /// The cues of a SubRip or WebVTT file's text, placed as titles on a
    /// text row of their own at their times, as one undo step.
    pub fn import_captions(&self, text: String) -> Result<TimelineView, MobileError> {
        let cues = parse(&text);
        if cues.is_empty() {
            return Err(MobileError::Failed(
                "There are no captions in that file.".to_owned(),
            ));
        }
        self.place_captions(&cues, false)
    }

    /// The timeline's captions as a SubRip file's text, in the order they
    /// play: the titles Auto captions, Auto lyrics, Enter captions or a
    /// caption file made; with none of those, the row with the most titles
    /// (see `concat_project::srt::busiest_title_lane`).
    pub fn captions_srt(&self) -> Result<String, MobileError> {
        let cues = self.with_session(|session| {
            let timeline = session.project().active();
            let ids = crate::speech::caption_ids(timeline);
            if ids.is_empty() {
                return Ok(srt::busiest_title_lane(timeline)
                    .map(|lane| srt::title_cues(timeline, Some(&lane)))
                    .unwrap_or_default());
            }
            let mut cues: Vec<srt::Cue> = timeline
                .clips
                .iter()
                .filter(|clip| clip.kind == ClipKind::Text && ids.contains(&clip.id))
                .filter_map(|clip| {
                    Some(srt::Cue {
                        start: clip.start,
                        end: clip.start + clip.duration,
                        text: clip.text.as_ref()?.content.clone(),
                    })
                })
                .collect();
            cues.sort_by(|a, b| a.start.total_cmp(&b.start));
            Ok(cues)
        })?;
        if cues.is_empty() {
            return Err(MobileError::Failed(
                "There are no captions to export.".to_owned(),
            ));
        }
        Ok(srt::write(&cues))
    }
}

/// The cues of a SubRip or WebVTT text, by start. A cue is a line with
/// `-->` between two times, then its words up to a blank line; numbers,
/// headers, notes and styling tags are dropped.
pub(crate) fn parse(text: &str) -> Vec<Cue> {
    let text = text
        .trim_start_matches('\u{feff}')
        .replace("\r\n", "\n")
        .replace('\r', "\n");
    let mut cues = Vec::new();
    let mut lines = text.lines().peekable();
    while let Some(line) = lines.next() {
        let Some((from, to)) = timing(line) else {
            continue;
        };
        let mut words = Vec::new();
        while let Some(next) = lines.peek() {
            if next.trim().is_empty() {
                break;
            }
            words.push(clean(next));
            lines.next();
        }
        let words = words.join("\n").trim().to_owned();
        if !words.is_empty() && to > from {
            cues.push((from, to, words));
        }
    }
    cues.sort_by(|a, b| a.0.total_cmp(&b.0));
    cues
}

/// The two times of a cue's timing line, `00:00:01,000 --> 00:00:03,500`
/// (VTT also writes `.` for `,`, may leave out the hours, and may add cue
/// settings after the second time).
fn timing(line: &str) -> Option<(f64, f64)> {
    let (from, rest) = line.split_once("-->")?;
    let to = rest.split_whitespace().next()?;
    Some((time(from.trim())?, time(to)?))
}

/// `hh:mm:ss,mmm`, `hh:mm:ss.mmm` or `mm:ss.mmm` in seconds.
fn time(text: &str) -> Option<f64> {
    let text = text.replace(',', ".");
    let parts: Vec<&str> = text.split(':').collect();
    let (hours, minutes, seconds) = match parts.as_slice() {
        [h, m, s] => (
            h.parse::<f64>().ok()?,
            m.parse::<f64>().ok()?,
            s.parse::<f64>().ok()?,
        ),
        [m, s] => (0.0, m.parse::<f64>().ok()?, s.parse::<f64>().ok()?),
        _ => return None,
    };
    let total = hours * 3600.0 + minutes * 60.0 + seconds;
    total.is_finite().then_some(total.max(0.0))
}

/// A line of words without its markup: `<i>`, `<b>`, `<font ...>`, VTT's
/// `<c.class>` and voice tags, and SubRip's `{\an8}` placement codes.
fn clean(line: &str) -> String {
    let mut out = String::with_capacity(line.len());
    let mut depth_angle = false;
    let mut depth_brace = false;
    for c in line.chars() {
        match c {
            '<' => depth_angle = true,
            '>' if depth_angle => depth_angle = false,
            '{' => depth_brace = true,
            '}' if depth_brace => depth_brace = false,
            _ if depth_angle || depth_brace => {}
            _ => out.push(c),
        }
    }
    out.trim().to_owned()
}

#[cfg(test)]
mod tests {
    use super::parse;

    #[test]
    fn reads_subrip() {
        let srt = "\u{feff}1\r\n00:00:01,000 --> 00:00:03,500\r\nHello <i>there</i>\r\n\r\n2\r\n00:00:04,250 --> 00:00:06,000\r\n{\\an8}Two\r\nlines\r\n";
        let cues = parse(srt);
        assert_eq!(cues.len(), 2);
        assert_eq!(cues[0], (1.0, 3.5, "Hello there".to_owned()));
        assert_eq!(cues[1], (4.25, 6.0, "Two\nlines".to_owned()));
    }

    #[test]
    fn reads_webvtt() {
        let vtt = "WEBVTT\n\nNOTE made by hand\n\nintro\n00:01.000 --> 00:02.500 align:start\n<v Sam>Hi</v>\n\n01:00:00.000 --> 01:00:01.000\nLate\n";
        let cues = parse(vtt);
        assert_eq!(
            cues,
            vec![
                (1.0, 2.5, "Hi".to_owned()),
                (3600.0, 3601.0, "Late".to_owned())
            ]
        );
    }

    #[test]
    fn skips_broken_cues() {
        assert!(parse("1\n00:00:05,000 --> 00:00:02,000\nBackwards\n\nnot a cue\n").is_empty());
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer and Concat contributors
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! Speech on the phone, all of it local: captions from what is said in the
//! clips (whisper.cpp) and voices reading titles aloud (Kokoro and Pocket
//! through sherpa-onnx). The models are downloaded on first use, checked
//! against their digests, and kept in the engine's folder.

use std::sync::{Arc, OnceLock};

use concat_project::Command;
use concat_project::model::{ClipKind, TextStyle};
use concat_speech::transcribe::TranscribeRequest;
use concat_speech::tts::SpeakRequest;
use concat_speech::{DownloadProgress, JobStage, Speech, Transcriber};

use crate::lanes::RowKind;
use crate::session::MobileSession;
use crate::{MobileError, context};

fn transcriber() -> &'static Transcriber {
    static ONE: OnceLock<Transcriber> = OnceLock::new();
    ONE.get_or_init(Transcriber::new)
}

fn speech() -> &'static Speech {
    static ONE: OnceLock<Speech> = OnceLock::new();
    ONE.get_or_init(Speech::new)
}

/// What a long speech job reports.
#[uniffi::export(with_foreign)]
pub trait SpeechListener: Send + Sync {
    /// How far along it is, 0 to 1, and what it is doing ("download",
    /// "unpack", "read", "load", "work").
    fn on_progress(&self, fraction: f32, stage: String);
    /// It finished: `result` is the view after the edit, or empty for a
    /// download.
    fn on_done(&self, result: Option<crate::session::TimelineView>);
    /// It failed or was cancelled.
    fn on_failed(&self, message: String);
}

/// One line of the transcript: what was said, from when to when on the
/// timeline.
#[derive(Debug, Clone, uniffi::Record)]
pub struct TranscriptLine {
    /// Where it starts, microseconds into the timeline.
    pub start_us: i64,
    /// Where it ends.
    pub end_us: i64,
    /// The words.
    pub text: String,
}

/// What a transcript job reports.
#[uniffi::export(with_foreign)]
pub trait TranscriptListener: Send + Sync {
    /// How far along it is, 0 to 1, and what it is doing.
    fn on_progress(&self, fraction: f32, stage: String);
    /// It finished with these lines, in time order.
    fn on_done(&self, lines: Vec<TranscriptLine>);
    /// It failed or was cancelled.
    fn on_failed(&self, message: String);
}

/// What is heard on the timeline: each audible clip's window of its file,
/// with where the clip starts and its speed, to be heard with `model_id`.
fn heard(
    session: &MobileSession,
    model_id: &str,
) -> Result<Vec<(f64, f64, TranscribeRequest)>, MobileError> {
    session.with_session(|session| {
        let project = session.project();
        Ok(project
            .active()
            .clips
            .iter()
            .filter(|clip| {
                clip.muted != Some(true)
                    && match clip.kind {
                        ClipKind::Audio => true,
                        ClipKind::Video => project
                            .media
                            .iter()
                            .any(|item| item.id == clip.media_id && item.has_audio),
                        _ => false,
                    }
            })
            .filter_map(|clip| {
                let media = project.media.iter().find(|item| item.id == clip.media_id)?;
                Some((
                    clip.start,
                    clip.speed,
                    TranscribeRequest {
                        path: media.path.clone(),
                        audio_stream: clip.audio_stream,
                        source_start: clip.source_start,
                        window: clip.duration * clip.speed,
                        model_id: model_id.to_owned(),
                    },
                ))
            })
            .collect())
    })
}

/// A downloadable model.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SpeechModel {
    /// The id the engine knows it by.
    pub id: String,
    /// The engine's name for it.
    pub label: String,
    /// "whisper", "kokoro" or "pocket".
    pub family: String,
    /// About how big the download is.
    pub size_bytes: u64,
    /// Whether it is on the phone.
    pub downloaded: bool,
}

/// A voice a model reads with.
#[derive(Debug, Clone, uniffi::Record)]
pub struct Voice {
    /// The engine's number for it.
    pub id: i32,
    /// Its name.
    pub name: String,
    /// "kokoro" or "pocket" (the model family it needs).
    pub family: String,
}

fn family_name(family: &concat_speech::tts::Family) -> String {
    serde_json::to_value(family)
        .ok()
        .and_then(|value| value.as_str().map(str::to_lowercase))
        .unwrap_or_default()
}

/// The caption models.
#[uniffi::export]
pub fn caption_models() -> Result<Vec<SpeechModel>, MobileError> {
    let status = Transcriber::status(&context()?.dirs)?;
    Ok(status
        .models
        .into_iter()
        .map(|model| SpeechModel {
            id: model.id,
            label: model.label,
            family: "whisper".to_owned(),
            size_bytes: model.size_bytes,
            downloaded: model.downloaded,
        })
        .collect())
}

/// The voice models.
#[uniffi::export]
pub fn voice_models() -> Result<Vec<SpeechModel>, MobileError> {
    let status = Speech::status(&context()?.dirs)?;
    Ok(status
        .models
        .into_iter()
        .filter(|model| family_name(&model.family) != "chatterbox")
        .map(|model| SpeechModel {
            id: model.id,
            label: model.label,
            family: family_name(&model.family),
            size_bytes: model.size_bytes,
            downloaded: model.downloaded,
        })
        .collect())
}

/// The voices.
#[uniffi::export]
pub fn voices() -> Result<Vec<Voice>, MobileError> {
    let status = Speech::status(&context()?.dirs)?;
    Ok(status
        .voices
        .into_iter()
        .filter(|voice| family_name(&voice.family) != "chatterbox")
        .map(|voice| Voice {
            id: voice.id,
            name: voice.name,
            family: family_name(&voice.family),
        })
        .collect())
}

fn report_download(listener: &Arc<dyn SpeechListener>, progress: DownloadProgress) {
    let fraction = if progress.total > 0 {
        (progress.received as f32 / progress.total as f32).clamp(0.0, 1.0)
    } else {
        0.0
    };
    let stage = if progress.unpacking {
        "unpack"
    } else {
        "download"
    };
    listener.on_progress(fraction, stage.to_owned());
}

/// Downloads a caption model (whisper) or a voice model, on a thread of its
/// own.
#[uniffi::export]
pub fn download_speech_model(
    id: String,
    family: String,
    listener: Arc<dyn SpeechListener>,
) -> Result<(), MobileError> {
    let dirs = context()?.dirs.clone();
    std::thread::Builder::new()
        .name("capcraft-download".to_owned())
        .spawn(move || {
            let report = |progress: DownloadProgress| report_download(&listener, progress);
            let result = if family == "whisper" {
                transcriber().download_model(&dirs, &id, report)
            } else {
                speech().download_model(&dirs, &id, report)
            };
            match result {
                Ok(()) => listener.on_done(None),
                Err(message) => listener.on_failed(message),
            }
        })
        .map_err(|error| MobileError::Failed(format!("could not start the download: {error}")))?;
    Ok(())
}

/// Stops a download in progress.
#[uniffi::export]
pub fn cancel_speech() {
    transcriber().cancel_download();
    transcriber().cancel();
    speech().cancel_download();
    speech().cancel();
}

fn stage_of(stage: JobStage) -> (f32, String) {
    match stage {
        JobStage::Reading => (0.0, "read".to_owned()),
        JobStage::Loading => (0.0, "load".to_owned()),
        JobStage::Working { done, .. } => (done, "work".to_owned()),
    }
}

/// The most words one caption shows: phrases short enough to read at a
/// glance, as phone editors' captions are.
const WORDS_PER_CAPTION: usize = 6;

/// A recognised phrase cut into captions of a few words each, the time
/// shared out by how many letters each holds.
fn chunks(from: f64, to: f64, text: &str) -> Vec<(f64, f64, String)> {
    let words: Vec<&str> = text.split_whitespace().collect();
    if words.len() <= WORDS_PER_CAPTION {
        return vec![(from, to, words.join(" "))];
    }
    let pieces: Vec<String> = words
        .chunks(
            WORDS_PER_CAPTION.min(
                words
                    .len()
                    .div_ceil(words.len().div_ceil(WORDS_PER_CAPTION)),
            ),
        )
        .map(|chunk| chunk.join(" "))
        .collect();
    let letters: usize = pieces.iter().map(|piece| piece.len()).sum::<usize>().max(1);
    let mut at = from;
    pieces
        .into_iter()
        .map(|piece| {
            let length = (to - from) * piece.len() as f64 / letters as f64;
            let caption = (at, at + length, piece);
            at += length;
            caption
        })
        .collect()
}

/// The look of a caption: white words with a dark outline, low on the frame.
fn caption_style(text: &str) -> TextStyle {
    TextStyle {
        content: text.to_owned(),
        font_family: "Inter".to_owned(),
        font_size: 0.034,
        font_weight: 700.0,
        stroke_width: 0.004,
        stroke_color: "#000000".to_owned(),
        shadow: false,
        max_width: 0.8,
        ..TextStyle::default()
    }
}

#[uniffi::export]
impl MobileSession {
    /// Captions from what is said in every audible clip, with `model_id`,
    /// placed as titles on a text row of their own: one per phrase, at the
    /// time it is said. Runs on a thread of its own; one undo step.
    pub fn auto_captions(
        self: Arc<Self>,
        model_id: String,
        replace: bool,
        uppercase: bool,
        remove_punctuation: bool,
        listener: Arc<dyn SpeechListener>,
    ) -> Result<(), MobileError> {
        self.caption(model_id, replace, false, uppercase, remove_punctuation, listener)
    }

    /// Lyrics: [`MobileSession::auto_captions`] of what is sung, each
    /// sound's file first cleaned to its voice (as Isolate voice does, and
    /// kept for it) so the music under the singing does not drown it.
    pub fn auto_lyrics(
        self: Arc<Self>,
        model_id: String,
        replace: bool,
        uppercase: bool,
        remove_punctuation: bool,
        listener: Arc<dyn SpeechListener>,
    ) -> Result<(), MobileError> {
        self.caption(model_id, replace, true, uppercase, remove_punctuation, listener)
    }
}

impl MobileSession {
    /// The captions of every audible clip, the voice cleaned out of each
    /// file first when `lyrics` asks; see [`MobileSession::auto_captions`].
    fn caption(
        self: Arc<Self>,
        model_id: String,
        replace: bool,
        lyrics: bool,
        uppercase: bool,
        remove_punctuation: bool,
        listener: Arc<dyn SpeechListener>,
    ) -> Result<(), MobileError> {
        let dirs = context()?.dirs.clone();
        let mut windows = heard(&self, &model_id)?;
        if windows.is_empty() {
            return Err(MobileError::Failed(
                "There is no sound on the timeline to caption.".to_owned(),
            ));
        }
        let project = self.with_session(|session| Ok(session.path().to_owned()))?;
        std::thread::Builder::new()
            .name("capcraft-captions".to_owned())
            .spawn(move || {
                if lyrics {
                    let count = windows.len() as f32;
                    for (index, (_, _, request)) in windows.iter_mut().enumerate() {
                        let base = index as f32;
                        let clean = crate::voice::cleaned(
                            std::path::Path::new(&project),
                            &request.path,
                            &|fraction, stage| {
                                listener
                                    .on_progress((base + fraction) / count * 0.5, stage.to_owned());
                                true
                            },
                        );
                        match clean {
                            Ok(path) => request.path = path.to_string_lossy().into_owned(),
                            Err(message) => {
                                listener.on_failed(message);
                                return;
                            }
                        }
                    }
                }
                let count = windows.len() as f32;
                let mut captions: Vec<(f64, f64, String)> = Vec::new();
                for (index, (start, speed, request)) in windows.iter().enumerate() {
                    let listener_for_progress = Arc::clone(&listener);
                    let base = index as f32;
                    let result = transcriber().transcribe(&dirs, request, move |stage| {
                        let (done, name) = stage_of(stage);
                        let fraction = (base + done) / count;
                        listener_for_progress.on_progress(
                            if lyrics {
                                0.5 + fraction * 0.5
                            } else {
                                fraction
                            },
                            name,
                        );
                    });
                    match result {
                        Ok(segments) => {
                            for segment in segments {
                                let text = crate::caption_text::format_caption(
                                    segment.text.trim(), uppercase, remove_punctuation,
                                );
                                if text.trim().is_empty() {
                                    continue;
                                }
                                let from = start + segment.start / speed.max(1e-6);
                                let to = start + segment.end / speed.max(1e-6);
                                if to - from > 0.05 {
                                    captions.extend(chunks(from, to, &text));
                                }
                            }
                        }
                        Err(message) => {
                            listener.on_failed(message);
                            return;
                        }
                    }
                }
                if captions.is_empty() {
                    listener.on_failed("No speech was found.".to_owned());
                    return;
                }
                captions.sort_by(|a, b| a.0.total_cmp(&b.0));
                // Phrases that overlap are trimmed to follow each other.
                for i in 1..captions.len() {
                    if captions[i].0 < captions[i - 1].1 {
                        captions[i - 1].1 = captions[i].0;
                    }
                }
                match self.place_captions(&captions, replace) {
                    Ok(view) => listener.on_done(Some(view)),
                    Err(error) => listener.on_failed(error.to_string()),
                }
            })
            .map_err(|error| MobileError::Failed(format!("could not start: {error}")))?;
        Ok(())
    }
}

#[uniffi::export]
impl MobileSession {
    /// What is said on the timeline, line by line with the time of each,
    /// heard with `model_id`, for the transcript to edit by: nothing is
    /// placed. Runs on a thread of its own.
    pub fn transcript(
        self: Arc<Self>,
        model_id: String,
        listener: Arc<dyn TranscriptListener>,
    ) -> Result<(), MobileError> {
        let dirs = context()?.dirs.clone();
        let windows = heard(&self, &model_id)?;
        if windows.is_empty() {
            return Err(MobileError::Failed(
                "There is no sound on the timeline to transcribe.".to_owned(),
            ));
        }
        std::thread::Builder::new()
            .name("capcraft-transcript".to_owned())
            .spawn(move || {
                let count = windows.len() as f32;
                let mut lines: Vec<TranscriptLine> = Vec::new();
                for (index, (start, speed, request)) in windows.iter().enumerate() {
                    let progress = Arc::clone(&listener);
                    let base = index as f32;
                    let result = transcriber().transcribe(&dirs, request, move |stage| {
                        let (done, name) = stage_of(stage);
                        progress.on_progress((base + done) / count, name);
                    });
                    match result {
                        Ok(segments) => {
                            for segment in segments {
                                let text = segment.text.trim().to_owned();
                                let from = start + segment.start / speed.max(1e-6);
                                let to = start + segment.end / speed.max(1e-6);
                                if !text.is_empty() && to - from > 0.05 {
                                    lines.push(TranscriptLine {
                                        start_us: crate::micros(from),
                                        end_us: crate::micros(to),
                                        text,
                                    });
                                }
                            }
                        }
                        Err(message) => {
                            listener.on_failed(message);
                            return;
                        }
                    }
                }
                lines.sort_by_key(|line| line.start_us);
                listener.on_done(lines);
            })
            .map_err(|error| MobileError::Failed(format!("could not start: {error}")))?;
        Ok(())
    }

    /// Reads a title's words aloud with `model_id` and `voice`, and puts
    /// the reading on an audio row where the title starts. Runs on a thread
    /// of its own.
    pub fn text_to_speech(
        self: Arc<Self>,
        clip_id: String,
        model_id: String,
        voice: i32,
        listener: Arc<dyn SpeechListener>,
    ) -> Result<(), MobileError> {
        let (text, start) = self.with_session(|session| {
            let clip = session
                .project()
                .active()
                .clips
                .iter()
                .find(|clip| clip.id == clip_id)
                .ok_or_else(|| MobileError::Failed("That title is gone.".to_owned()))?;
            let text = clip
                .text
                .as_ref()
                .map(|style| style.content.clone())
                .unwrap_or_default();
            Ok((text, clip.start))
        })?;
        if text.trim().is_empty() {
            return Err(MobileError::Failed(
                "The title has no words to read.".to_owned(),
            ));
        }
        speak_onto(self, text, start, model_id, voice, listener)
    }

    /// Reads `text` aloud with `model_id` and `voice`, and puts the reading
    /// on an audio row at `at_us`: words typed for the purpose, with no
    /// title to read them from. Runs on a thread of its own.
    pub fn speak_text(
        self: Arc<Self>,
        text: String,
        at_us: i64,
        model_id: String,
        voice: i32,
        listener: Arc<dyn SpeechListener>,
    ) -> Result<(), MobileError> {
        if text.trim().is_empty() {
            return Err(MobileError::Failed(
                "There are no words to read.".to_owned(),
            ));
        }
        speak_onto(self, text, crate::seconds(at_us), model_id, voice, listener)
    }
}

/// Reads `text` aloud on a thread of its own and puts the reading on an
/// audio row at `start` seconds, telling `listener` how it goes.
fn speak_onto(
    session: Arc<MobileSession>,
    text: String,
    start: f64,
    model_id: String,
    voice: i32,
    listener: Arc<dyn SpeechListener>,
) -> Result<(), MobileError> {
    let dirs = context()?.dirs.clone();
    let project = session.with_session(|s| Ok(s.path().to_owned()))?;
    std::thread::Builder::new()
        .name("capcraft-speech".to_owned())
        .spawn(move || {
            let request = SpeakRequest {
                model_id,
                voice,
                reference: None,
                text,
                speed: 1.0,
                pauses: None,
                steps: None,
                project,
            };
            let listener_for_progress = Arc::clone(&listener);
            let result = speech().speak(&dirs, &request, move |stage| {
                let (done, name) = stage_of(stage);
                listener_for_progress.on_progress(done, name);
            });
            let spoken = match result {
                Ok(spoken) => spoken,
                Err(message) => {
                    listener.on_failed(message);
                    return;
                }
            };
            let placed = session
                .import(spoken.path)
                .and_then(|media| session.add_audio(media.id, crate::micros(start)));
            match placed {
                Ok(view) => listener.on_done(Some(view)),
                Err(error) => listener.on_failed(error.to_string()),
            }
        })
        .map_err(|error| MobileError::Failed(format!("could not start: {error}")))?;
    Ok(())
}

impl MobileSession {
    /// The captions as titles on one text row (a free one, or a new one),
    /// as one undo step; with `replace`, the captions made before are taken
    /// off in the same step. The titles made are kept on the timeline as
    /// its captions ([`CAPTIONS`]).
    pub(crate) fn place_captions(
        &self,
        captions: &[(f64, f64, String)],
        replace: bool,
    ) -> Result<crate::session::TimelineView, MobileError> {
        let first = captions.first().map_or(0.0, |c| c.0);
        let last = captions.last().map_or(0.0, |c| c.1);
        let (known, before) = self.with_session(|session| {
            let timeline = session.project().active();
            Ok((
                caption_ids(timeline),
                timeline
                    .clips
                    .iter()
                    .filter(|clip| clip.kind == ClipKind::Text)
                    .map(|clip| clip.id.clone())
                    .collect::<std::collections::HashSet<_>>(),
            ))
        })?;
        let make = |track_id: String| -> Vec<Command> {
            let mut commands = Vec::new();
            if replace && !known.is_empty() {
                commands.push(Command::RemoveClips {
                    clip_ids: known.clone(),
                    ripple: false,
                });
            }
            commands.extend(
                captions
                    .iter()
                    .map(|(from, to, text)| Command::AddTextClip {
                        track_id: Some(track_id.clone()),
                        above: false,
                        start: *from,
                        style: Some(caption_style(text)),
                        duration: Some((to - from).max(0.1)),
                        offset_y: Some(0.32),
                    }),
            );
            commands
        };
        let then = |after: &concat_project::model::Timeline| -> Vec<Command> {
            let mut list = if replace { Vec::new() } else { known.clone() };
            list.extend(
                after
                    .clips
                    .iter()
                    .filter(|clip| clip.kind == ClipKind::Text && !before.contains(&clip.id))
                    .map(|clip| clip.id.clone()),
            );
            vec![Command::SetTimelineExtra {
                key: CAPTIONS.to_owned(),
                value: Some(serde_json::json!(list)),
            }]
        };
        self.place_many(RowKind::Text, first, last - first, make, then)
    }
}

#[uniffi::export]
impl MobileSession {
    /// How many of the timeline's captions are still on it.
    pub fn caption_count(&self) -> u32 {
        self.with_session(|session| Ok(caption_ids(session.project().active()).len() as u32))
            .unwrap_or(0)
    }
}

/// Where the timeline keeps the ids of the titles that are its captions.
pub const CAPTIONS: &str = "capcraftCaptions";

/// The captions still on `timeline`, by id.
pub(crate) fn caption_ids(timeline: &concat_project::model::Timeline) -> Vec<String> {
    let Some(serde_json::Value::Array(ids)) = timeline.extra.get(CAPTIONS) else {
        return Vec::new();
    };
    ids.iter()
        .filter_map(|id| id.as_str())
        .filter(|id| timeline.clips.iter().any(|clip| clip.id == *id))
        .map(str::to_owned)
        .collect()
}

/// The lines of typed `text`, a caption each; blank lines are dropped.
fn caption_lines(text: &str) -> Vec<String> {
    text.lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .map(str::to_owned)
        .collect()
}

/// How long a typed caption of `line` stays up when nothing reads it: half
/// a second a word, between one and six seconds.
fn reading_time(line: &str) -> f64 {
    (0.5 * line.split_whitespace().count() as f64).clamp(1.0, 6.0)
}

/// The breath between one read caption and the next, in seconds.
const BETWEEN_LINES: f64 = 0.15;

#[uniffi::export]
impl MobileSession {
    /// Typed captions, as the reference's Enter captions makes them: each
    /// line of `text` a caption, one after another from `at_us`, each up for
    /// as long as it takes to read, on one text row in one undo step and kept
    /// as the timeline's captions.
    pub fn enter_captions(
        &self,
        text: String,
        at_us: i64,
    ) -> Result<crate::session::TimelineView, MobileError> {
        let lines = caption_lines(&text);
        if lines.is_empty() {
            return Err(MobileError::Failed(
                "There are no words to caption.".to_owned(),
            ));
        }
        let mut at = crate::seconds(at_us).max(0.0);
        let captions: Vec<(f64, f64, String)> = lines
            .into_iter()
            .map(|line| {
                let from = at;
                at += reading_time(&line);
                (from, at, line)
            })
            .collect();
        self.place_captions(&captions, false)
    }

    /// Typed captions read aloud: each line of `text` spoken with `model_id`
    /// and `voice`, its caption up for as long as the reading, and the
    /// readings on an audio row under them. Runs on a thread of its own.
    pub fn enter_captions_spoken(
        self: Arc<Self>,
        text: String,
        at_us: i64,
        model_id: String,
        voice: i32,
        listener: Arc<dyn SpeechListener>,
    ) -> Result<(), MobileError> {
        let lines = caption_lines(&text);
        if lines.is_empty() {
            return Err(MobileError::Failed(
                "There are no words to caption.".to_owned(),
            ));
        }
        let dirs = context()?.dirs.clone();
        let project = self.with_session(|session| Ok(session.path().to_owned()))?;
        let start = crate::seconds(at_us).max(0.0);
        std::thread::Builder::new()
            .name("capcraft-captions".to_owned())
            .spawn(move || {
                let count = lines.len() as f32;
                let mut readings = Vec::new();
                for (index, line) in lines.iter().enumerate() {
                    let request = SpeakRequest {
                        model_id: model_id.clone(),
                        voice,
                        reference: None,
                        text: line.clone(),
                        speed: 1.0,
                        pauses: None,
                        steps: None,
                        project: project.clone(),
                    };
                    let progress = Arc::clone(&listener);
                    let base = index as f32;
                    match speech().speak(&dirs, &request, move |stage| {
                        let (done, name) = stage_of(stage);
                        progress.on_progress((base + done) / count, name);
                    }) {
                        Ok(spoken) => readings.push(spoken),
                        Err(message) => {
                            listener.on_failed(message);
                            return;
                        }
                    }
                }
                // back to back, each caption as long as its reading
                let mut at = start;
                let mut captions = Vec::new();
                let mut sounds = Vec::new();
                for (line, spoken) in lines.into_iter().zip(readings) {
                    let length = spoken.duration.max(0.5);
                    captions.push((at, at + length, line));
                    sounds.push((at, length, spoken.path));
                    at += length + BETWEEN_LINES;
                }
                let placed = self
                    .place_captions(&captions, false)
                    .and_then(|_| self.place_readings(&sounds));
                match placed {
                    Ok(view) => listener.on_done(Some(view)),
                    Err(error) => listener.on_failed(error.to_string()),
                }
            })
            .map_err(|error| MobileError::Failed(format!("could not start: {error}")))?;
        Ok(())
    }
}

impl MobileSession {
    /// Readings of typed captions, `(start, length, file)` each, brought in
    /// and put on one audio row at their times as one undo step.
    fn place_readings(
        &self,
        sounds: &[(f64, f64, String)],
    ) -> Result<crate::session::TimelineView, MobileError> {
        let mut placed = Vec::new();
        for (at, _, path) in sounds {
            placed.push((*at, self.import(path.clone())?.id));
        }
        let first = sounds.first().map_or(0.0, |sound| sound.0);
        let last = sounds.last().map_or(0.0, |sound| sound.0 + sound.1);
        self.place_many(
            RowKind::Audio,
            first,
            last - first,
            |track| {
                placed
                    .iter()
                    .map(|(at, media_id)| Command::AddClip {
                        media_id: media_id.clone(),
                        track_id: track.clone(),
                        start: *at,
                        ripple: false,
                    })
                    .collect()
            },
            |_| Vec::new(),
        )
    }
}

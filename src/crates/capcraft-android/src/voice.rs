// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! Isolate voice: a clip's sound with everything but the voice taken out,
//! by a small speech-enhancement network (GTCRN, through k2-fsa's
//! sherpa-onnx), fetched the first time and run on the phone.
//!
//! The whole file's sound is read at the network's rate, cleaned, encoded
//! and laid under the file's own picture (copied, not re-encoded); the clip
//! then shows the copy, in one undo step. The timeline keeps which file
//! each copy was made from (`capcraftIsolated`), so the clip can go back.

use std::hash::{Hash, Hasher};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, OnceLock};

use concat_media::{AudioDecoder, AudioOptions, SampleFormat};
use concat_project::Command;
use serde_json::{Value, json};
use sherpa_onnx::{
    OfflineSpeechDenoiser, OfflineSpeechDenoiserConfig, OfflineSpeechDenoiserGtcrnModelConfig,
    OfflineSpeechDenoiserModelConfig,
};

use crate::session::MobileSession;
use crate::speech::SpeechListener;
use crate::{MobileError, context};

/// Where the timeline keeps, for each cleaned copy's path, the file it was
/// made from.
pub const ISOLATED: &str = "capcraftIsolated";

/// The network: its file, where it comes from, how big it is and its digest.
const MODEL_FILE: &str = "gtcrn_simple.onnx";
const MODEL_UPSTREAM: &str = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/gtcrn_simple.onnx";
const MODEL_BYTES: u64 = 535_638;
const MODEL_SHA256: &str = "e77603ac0c23dac3227dd2d7135b3a585cbee2679048aecfa886657d3ae1b534";

fn cancelled() -> &'static AtomicBool {
    static CANCEL: OnceLock<AtomicBool> = OnceLock::new();
    CANCEL.get_or_init(|| AtomicBool::new(false))
}

/// Asks the voice isolation in hand to stop.
#[uniffi::export]
pub fn cancel_isolate_voice() {
    cancelled().store(true, Ordering::Relaxed);
}

/// The network on disk, fetched and checked first when it is not there yet.
pub(crate) fn model(progress: &mut dyn FnMut(f32)) -> Result<PathBuf, String> {
    let folder = context()
        .map_err(|error| error.to_string())?
        .dirs
        .data
        .join("speech-enhancement");
    let path = folder.join(MODEL_FILE);
    if path.is_file() && concat_host::models::verify(&path, MODEL_SHA256).is_ok() {
        return Ok(path);
    }
    std::fs::create_dir_all(&folder).map_err(|error| format!("{}: {error}", folder.display()))?;
    let partial = folder.join(format!("{MODEL_FILE}.part"));
    let mut last = String::from("no source to fetch it from");
    for url in concat_host::models::sources(MODEL_FILE, MODEL_UPSTREAM) {
        let fetched = concat_host::models::download(
            &url,
            &partial,
            MODEL_BYTES,
            cancelled(),
            "Isolate voice was cancelled.",
            &mut |received, total| progress(received as f32 / total.max(1) as f32),
        );
        match fetched.and_then(|_| concat_host::models::verify(&partial, MODEL_SHA256)) {
            Ok(()) => {
                std::fs::rename(&partial, &path)
                    .map_err(|error| format!("{}: {error}", path.display()))?;
                return Ok(path);
            }
            Err(error) => {
                if cancelled().load(Ordering::Relaxed) {
                    return Err(error);
                }
                let _ = std::fs::remove_file(&partial);
                last = error;
            }
        }
    }
    Err(format!("The voice model could not be fetched: {last}"))
}

/// `samples` (mono, -1 to 1) as a sixteen-bit WAV file at `rate`.
fn write_wav(path: &Path, samples: &[f32], rate: u32) -> Result<(), String> {
    let data = samples.len() as u32 * 2;
    let mut out = Vec::with_capacity(44 + data as usize);
    out.extend_from_slice(b"RIFF");
    out.extend_from_slice(&(36 + data).to_le_bytes());
    out.extend_from_slice(b"WAVEfmt ");
    out.extend_from_slice(&16u32.to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes());
    out.extend_from_slice(&rate.to_le_bytes());
    out.extend_from_slice(&(rate * 2).to_le_bytes());
    out.extend_from_slice(&2u16.to_le_bytes());
    out.extend_from_slice(&16u16.to_le_bytes());
    out.extend_from_slice(b"data");
    out.extend_from_slice(&data.to_le_bytes());
    for sample in samples {
        out.extend_from_slice(&((sample.clamp(-1.0, 1.0) * 32767.0).round() as i16).to_le_bytes());
    }
    std::fs::File::create(path)
        .and_then(|mut file| file.write_all(&out))
        .map_err(|error| format!("{}: {error}", path.display()))
}

/// Writes the copy of `media` with only its voice to `target` (a video
/// file keeps its picture, copied; sound alone comes out as AAC);
/// `step` hears the fraction done and answers whether to go on.
pub(crate) fn isolate(
    media: &str,
    model: &Path,
    target: &Path,
    step: &dyn Fn(f32) -> bool,
) -> Result<(), String> {
    let info = concat_media::probe(media).map_err(|error| error.to_string())?;
    if info.audio.is_none() {
        return Err("The clip has no sound.".to_owned());
    }
    let denoiser = OfflineSpeechDenoiser::create(&OfflineSpeechDenoiserConfig {
        model: OfflineSpeechDenoiserModelConfig {
            gtcrn: OfflineSpeechDenoiserGtcrnModelConfig {
                model: Some(model.to_string_lossy().into_owned()),
            },
            num_threads: 2,
            ..Default::default()
        },
    })
    .ok_or_else(|| "The voice model would not load.".to_owned())?;
    let rate = denoiser.sample_rate().max(8_000) as u32;
    let mut decoder = AudioDecoder::open(
        media,
        &AudioOptions {
            start: None,
            duration: None,
            filters: Vec::new(),
            rate,
            channels: 1,
            format: SampleFormat::F32,
            stream: None,
        },
    )
    .map_err(|error| error.to_string())?;
    let samples = decoder.collect_f32().map_err(|error| error.to_string())?;
    if samples.is_empty() {
        return Err("The clip has no sound.".to_owned());
    }
    if !step(0.2) {
        return Err("Isolate voice was cancelled.".to_owned());
    }
    // a minute at a time, so it can be stopped and heard to move
    let chunk = rate as usize * 60;
    let mut clean = Vec::with_capacity(samples.len());
    for (i, part) in samples.chunks(chunk).enumerate() {
        let out = denoiser.run(part, rate as i32);
        clean.extend_from_slice(&out.samples);
        let done = ((i + 1) * chunk).min(samples.len()) as f32 / samples.len() as f32;
        if !step(0.2 + done * 0.65) {
            return Err("Isolate voice was cancelled.".to_owned());
        }
    }
    if let Some(folder) = target.parent() {
        std::fs::create_dir_all(folder)
            .map_err(|error| format!("{}: {error}", folder.display()))?;
    }
    let wav = target.with_extension("voice.wav");
    let aac = target.with_extension("voice.m4a");
    write_wav(&wav, &clean, rate)?;
    let seconds = clean.len() as f64 / f64::from(rate);
    let clip = concat_media::audio::AudioClip {
        path: wav.clone(),
        stream: None,
        start: 0.0,
        duration: seconds,
        source_start: 0.0,
        speed: 1.0,
        preserve_pitch: false,
        volume: 1.0,
        volume_curve: Default::default(),
        fade_in: 0.0,
        fade_out: 0.0,
        filter_chain: String::new(),
    };
    let encoded =
        concat_media::audio::mix_to_file(&[clip], seconds, &aac).map_err(|error| error.to_string());
    let _ = std::fs::remove_file(&wav);
    encoded?;
    let finished = if info.video.is_some() {
        let muxed = concat_media::audio::mux(Path::new(media), &aac, target)
            .map_err(|error| error.to_string());
        let _ = std::fs::remove_file(&aac);
        muxed
    } else {
        std::fs::rename(&aac, target).map_err(|error| format!("{}: {error}", target.display()))
    };
    step(1.0);
    finished
}

/// The copy of `media` with only its voice, under `project`'s cache: made
/// (the network fetched first if need be) when it is not there yet.
pub(crate) fn cleaned(
    project: &Path,
    media: &str,
    step: &dyn Fn(f32, &str) -> bool,
) -> Result<PathBuf, String> {
    let video = concat_media::probe(media)
        .map_err(|error| error.to_string())?
        .video
        .is_some();
    let target =
        target_for(project, media, video).ok_or_else(|| format!("{media} cannot be read"))?;
    if !target.is_file() {
        let model = model(&mut |fraction| {
            step(fraction, "download");
        })?;
        isolate(media, &model, &target, &|fraction| step(fraction, "work"))?;
    }
    Ok(target)
}

/// Where the cleaned copy of `media` lives under `project`.
fn target_for(project: &Path, media: &str, video: bool) -> Option<PathBuf> {
    let meta = std::fs::metadata(media).ok()?;
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    media.hash(&mut hasher);
    meta.len().hash(&mut hasher);
    if let Ok(modified) = meta.modified()
        && let Ok(since) = modified.duration_since(std::time::UNIX_EPOCH)
    {
        since.as_secs().hash(&mut hasher);
    }
    let kind = if video { "mp4" } else { "m4a" };
    Some(
        project
            .join("cache")
            .join(format!("voice-{:016x}.{kind}", hasher.finish())),
    )
}

#[uniffi::export]
impl MobileSession {
    /// Keeps only the voice in `clip_id`'s sound (`on`) on a thread of its
    /// own, reporting through `listener` ("download" while the network is
    /// fetched), and points the clip at the copy; `on` false points it back
    /// at the file the copy was made from.
    pub fn isolate_voice(
        self: Arc<Self>,
        clip_id: String,
        on: bool,
        listener: Arc<dyn SpeechListener>,
    ) -> Result<(), MobileError> {
        let (current, video, project) = self.with_session(|session| {
            let project = session.project();
            let clip = project
                .active()
                .clips
                .iter()
                .find(|clip| clip.id == clip_id)
                .ok_or_else(|| MobileError::Failed("That clip is gone.".to_owned()))?;
            let media = project
                .media
                .iter()
                .find(|item| item.id == clip.media_id)
                .ok_or_else(|| MobileError::Failed("The clip's file is gone.".to_owned()))?;
            let video = !matches!(media.kind, concat_project::model::MediaKind::Audio);
            Ok((media.path.clone(), video, session.path().to_owned()))
        })?;
        let mut made = match serde_json::from_str::<Value>(&self.extra(ISOLATED.to_owned())) {
            Ok(Value::Object(map)) => map,
            _ => serde_json::Map::new(),
        };
        let original = made
            .get(&current)
            .and_then(|entry| entry.get("original"))
            .and_then(Value::as_str)
            .map(str::to_owned);
        if !on || original.is_some() {
            if let (false, Some(original)) = (on, original) {
                let item = concat_host::media::probe(&original)?.to_new_media();
                let view = self.run(vec![Command::ReplaceClipMedia {
                    clip_id,
                    item,
                    source_start: None,
                    enhanced: false,
                    offset: 0.0,
                }])?;
                listener.on_done(Some(view));
            } else {
                listener.on_done(None);
            }
            return Ok(());
        }
        let target = target_for(Path::new(&project), &current, video)
            .ok_or_else(|| MobileError::Failed("The clip's file cannot be read.".to_owned()))?;
        cancelled().store(false, Ordering::Relaxed);
        std::thread::Builder::new()
            .name("capcraft-voice".to_owned())
            .spawn(move || {
                let written = if target.is_file() {
                    Ok(())
                } else {
                    model(&mut |fraction| listener.on_progress(fraction, "download".to_owned()))
                        .and_then(|model| {
                            isolate(&current, &model, &target, &|fraction| {
                                listener.on_progress(fraction, "work".to_owned());
                                !cancelled().load(Ordering::Relaxed)
                            })
                        })
                };
                if let Err(message) = written {
                    listener.on_failed(message);
                    return;
                }
                let copy = target.to_string_lossy().into_owned();
                let placed = concat_host::media::probe(&copy)
                    .map_err(MobileError::from)
                    .and_then(|info| {
                        made.insert(copy.clone(), json!({ "original": current }));
                        self.run(vec![
                            Command::ReplaceClipMedia {
                                clip_id,
                                item: info.to_new_media(),
                                source_start: None,
                                enhanced: false,
                                offset: 0.0,
                            },
                            Command::SetTimelineExtra {
                                key: ISOLATED.to_owned(),
                                value: Some(Value::Object(made)),
                            },
                        ])
                    });
                match placed {
                    Ok(view) => listener.on_done(Some(view)),
                    Err(error) => listener.on_failed(error.to_string()),
                }
            })
            .map_err(|error| MobileError::Failed(format!("could not start: {error}")))?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_wav_says_what_it_holds() {
        let path =
            std::env::temp_dir().join(format!("capcraft-voice-wav-{}.wav", std::process::id()));
        write_wav(&path, &[0.0, 0.5, -0.5, 1.0], 16_000).expect("written");
        let bytes = std::fs::read(&path).expect("read");
        assert_eq!(&bytes[0..4], b"RIFF");
        assert_eq!(
            u32::from_le_bytes(bytes[24..28].try_into().unwrap()),
            16_000
        );
        assert_eq!(u32::from_le_bytes(bytes[40..44].try_into().unwrap()), 8);
        assert_eq!(bytes.len(), 44 + 8);
        let _ = std::fs::remove_file(&path);
    }
}

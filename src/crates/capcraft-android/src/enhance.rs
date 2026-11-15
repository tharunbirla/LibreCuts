// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! Enhance: a clearer copy of a clip's picture, made on the phone by the
//! engine's restoration model (fetched the first time), which the clip
//! then shows; switched off, the clip shows its original again without the
//! model running a second time. A photo takes a moment; footage goes frame
//! by frame and takes a good while.

use std::path::Path;
use std::sync::{Arc, OnceLock};

use concat_host::Enhancers;
use concat_host::enhance::{EnhanceRequest, Progress, target_for};
use concat_project::Command;
use concat_project::model::MediaKind;

use crate::session::MobileSession;
use crate::speech::SpeechListener;
use crate::{MobileError, context};

fn enhancers() -> Result<&'static Enhancers, MobileError> {
    static ONE: OnceLock<Enhancers> = OnceLock::new();
    let data = context()?.dirs.data.clone();
    Ok(ONE.get_or_init(|| Enhancers::new(&data)))
}

/// Stops the enhancement in progress.
#[uniffi::export]
pub fn cancel_enhance() {
    if let Ok(service) = enhancers() {
        service.cancel();
    }
}

/// What the clip shows now, for Enhance: its picture's file and size, and
/// whether a copy was made before.
struct Current {
    path: String,
    still: bool,
    width: u32,
    height: u32,
    made_before: bool,
    project: String,
}

#[uniffi::export]
impl MobileSession {
    /// Shows a clearer copy of `clip_id`'s picture (`on`), made on a thread
    /// of its own the first time and reported through `listener`; or its
    /// original again (`on` false). One undo step either way.
    pub fn enhance(
        self: Arc<Self>,
        clip_id: String,
        on: bool,
        listener: Arc<dyn SpeechListener>,
    ) -> Result<(), MobileError> {
        let current = self.with_session(|session| {
            let project = session.project();
            let clip = project
                .active()
                .clips
                .iter()
                .find(|clip| clip.id == clip_id)
                .ok_or_else(|| MobileError::Failed("That clip is gone.".to_owned()))?;
            let made_before = clip.enhanced.is_some();
            // the copy is always made from the original, never from a copy
            let source = clip
                .enhanced
                .as_ref()
                .map_or(clip.media_id.as_str(), |e| e.original.as_str());
            let media = project
                .media
                .iter()
                .find(|item| item.id == source)
                .ok_or_else(|| MobileError::Failed("The clip's file is gone.".to_owned()))?;
            Ok(Current {
                path: media.path.clone(),
                still: matches!(media.kind, MediaKind::Image),
                width: media.width.unwrap_or(1920),
                height: media.height.unwrap_or(1080),
                made_before,
                project: session.path().to_owned(),
            })
        })?;
        if !on || current.made_before {
            let view = self.run(vec![Command::SetClipEnhanced { clip_id, on }])?;
            listener.on_done(Some(view));
            return Ok(());
        }
        let factor = concat_vision::enhance::factor_for(current.width, current.height);
        // the whole file, kept as the model answers it
        let target = target_for(
            Path::new(&current.project),
            &current.path,
            factor,
            1.0,
            current.still,
            None,
        )
        .ok_or_else(|| MobileError::Failed("The clip's file cannot be read.".to_owned()))?;
        let service = enhancers()?;
        std::thread::Builder::new()
            .name("capcraft-enhance".to_owned())
            .spawn(move || {
                let request = EnhanceRequest {
                    media_path: current.path,
                    still: current.still,
                    factor,
                    strength: 1.0,
                    target,
                    span: None,
                };
                let made = service.enhance(&request, &mut |progress| match progress {
                    Progress::Fetching { received, total } => listener
                        .on_progress(received as f32 / total.max(1) as f32, "download".to_owned()),
                    Progress::Analysing(fraction) => {
                        listener.on_progress(fraction, "work".to_owned())
                    }
                });
                let copy = match made {
                    Ok(copy) => copy,
                    Err(message) => {
                        listener.on_failed(message);
                        return;
                    }
                };
                let placed = concat_host::media::probe(&copy.to_string_lossy())
                    .map_err(MobileError::from)
                    .and_then(|info| {
                        self.run(vec![Command::ReplaceClipMedia {
                            clip_id,
                            item: info.to_new_media(),
                            source_start: None,
                            enhanced: true,
                            offset: 0.0,
                        }])
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

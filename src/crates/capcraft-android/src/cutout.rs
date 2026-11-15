// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer and Concat contributors
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! Removing a picture's background: the clip gets a cutout (one undo step),
//! then the masks are found on the phone by the engine's segmentation
//! models, downloaded on first use, frame by frame for footage. A smart
//! stroke of a custom cutout is read the same way, by the brush model: the
//! thing under it found in its frame and followed along the clip.

use std::sync::{Arc, OnceLock};

use concat_host::cutout::Progress;
use concat_host::{Brushes, Cutouts, RegionRequest};
use concat_project::Command;
use concat_project::model::{Cutout, CutoutMode, MediaKind, Subject};

use crate::session::MobileSession;
use crate::speech::SpeechListener;
use crate::{MobileError, context};

pub(crate) fn cutouts() -> Result<&'static Cutouts, MobileError> {
    static ONE: OnceLock<Cutouts> = OnceLock::new();
    let data = context()?.dirs.data.clone();
    Ok(ONE.get_or_init(|| Cutouts::new(&data)))
}

/// The brush model's service, loaded once.
fn brushes() -> Result<&'static Brushes, MobileError> {
    static ONE: OnceLock<Brushes> = OnceLock::new();
    let data = context()?.dirs.data.clone();
    Ok(ONE.get_or_init(|| Brushes::new(&data)))
}

/// What a job reports, as a listener hears it: a download's share, then the work's.
fn reported(listener: &dyn SpeechListener, progress: Progress) {
    match progress {
        Progress::Fetching { received, total } => listener.on_progress(
            if total > 0 {
                received as f32 / total as f32
            } else {
                0.0
            },
            "download".to_owned(),
        ),
        Progress::Analysing(done) => listener.on_progress(done, "work".to_owned()),
    }
}

#[uniffi::export]
impl MobileSession {
    /// Takes the background off a picture clip (`on`), finding its masks on
    /// a thread of its own and reporting through `listener`; or puts it back
    /// (`on` false).
    pub fn remove_background(
        self: Arc<Self>,
        clip_id: String,
        on: bool,
        listener: Arc<dyn SpeechListener>,
    ) -> Result<(), MobileError> {
        let cutout = on.then(|| Cutout {
            mode: CutoutMode::Auto,
            subject: Subject::Auto,
            feather: 0.002,
            strokes: Vec::new(),
        });
        let view = self.apply(
            serde_json::to_string(&Command::SetClipCutout {
                clip_id: clip_id.clone(),
                cutout,
            })
            .map_err(|error| MobileError::Failed(error.to_string()))?,
            None,
        )?;
        if !on {
            listener.on_done(Some(view));
            return Ok(());
        }
        let media = self.with_session(|session| {
            Ok(session
                .project()
                .active()
                .clips
                .iter()
                .find(|clip| clip.id == clip_id)
                .map(|clip| clip.media_id.clone())
                .unwrap_or_default())
        })?;
        self.find_masks(media, listener)
    }

    /// Stops a background removal in progress.
    pub fn cancel_background(&self) {
        if let Ok(cutouts) = cutouts() {
            cutouts.cancel();
        }
    }

    /// Reads what clip `clip_id`'s smart strokes lie on, on a thread of its
    /// own: the brush model (fetched the first time) finds the thing under
    /// each stroke not yet read, in the frame it was painted on, and follows
    /// it along the clip; the picture is drawn again when it is done.
    /// Done at once when no stroke wants reading.
    pub fn read_smart_strokes(
        self: Arc<Self>,
        clip_id: String,
        listener: Arc<dyn SpeechListener>,
    ) -> Result<(), MobileError> {
        let requests = self.with_session(|session| {
            let project = session.project();
            let Some(clip) = project
                .active()
                .clips
                .iter()
                .find(|clip| clip.id == clip_id)
            else {
                return Ok(Vec::new());
            };
            let (Some(cutout), Some(media)) = (
                clip.cutout.as_ref(),
                project.media.iter().find(|item| item.id == clip.media_id),
            ) else {
                return Ok(Vec::new());
            };
            let ranges = vec![(
                clip.source_start,
                clip.source_start + clip.duration * clip.speed,
            )];
            Ok(cutout
                .strokes
                .iter()
                .filter(|stroke| stroke.is_smart())
                .map(|stroke| RegionRequest {
                    project: std::path::PathBuf::from(session.path()),
                    media_path: media.path.clone(),
                    media_size: (media.width.unwrap_or(1), media.height.unwrap_or(1)),
                    still: matches!(media.kind, MediaKind::Image),
                    subject: cutout.subject,
                    ranges: ranges.clone(),
                    stroke: stroke.clone(),
                })
                .filter(RegionRequest::outstanding)
                .collect::<Vec<_>>())
        })?;
        if requests.is_empty() {
            listener.on_done(Some(self.refreshed()));
            return Ok(());
        }
        let brushes = brushes()?;
        std::thread::Builder::new()
            .name("capcraft-brush".to_owned())
            .spawn(move || {
                for request in &requests {
                    let mut report = |progress: Progress| reported(listener.as_ref(), progress);
                    if let Err(message) = brushes.read(request, &mut report) {
                        listener.on_failed(message);
                        return;
                    }
                }
                // the regions are on disk now: draw again
                listener.on_done(Some(self.refreshed()));
            })
            .map_err(|error| MobileError::Failed(format!("could not start: {error}")))?;
        Ok(())
    }

    /// Stops a smart stroke being read.
    pub fn cancel_smart_strokes(&self) {
        if let Ok(brushes) = brushes() {
            brushes.cancel();
        }
    }
}

impl MobileSession {
    /// Finds the masks of the bin item `media` on a thread of its own,
    /// reporting through `listener`, and draws again when they are in.
    pub(crate) fn find_masks(
        self: Arc<Self>,
        media: String,
        listener: Arc<dyn SpeechListener>,
    ) -> Result<(), MobileError> {
        let requests = self.with_session(|session| {
            let project = session.project();
            Ok(
                Cutouts::requests(project, std::path::Path::new(session.path()))
                    .into_iter()
                    .filter(|(id, _)| *id == media)
                    .map(|(_, request)| request)
                    .collect::<Vec<_>>(),
            )
        })?;
        let cutouts = cutouts()?;
        std::thread::Builder::new()
            .name("capcraft-cutout".to_owned())
            .spawn(move || {
                for request in &requests {
                    let mut report = |progress: Progress| reported(listener.as_ref(), progress);
                    if let Err(message) = cutouts.analyse(request, &mut report) {
                        listener.on_failed(message);
                        return;
                    }
                }
                // the masks are on disk now: draw again
                listener.on_done(Some(self.refreshed()));
            })
            .map_err(|error| MobileError::Failed(format!("could not start: {error}")))?;
        Ok(())
    }
}

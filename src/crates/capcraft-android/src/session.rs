// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer and Concat contributors
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! One open project: the engine's [`Session`] behind a lock, the phone
//! timeline's operations over it, and the monitor's frames.
//!
//! Every operation is one undo step. An operation that needs a new lane
//! first adds the lane and then the clip as two moves of one gesture, which
//! the engine folds into a single step.

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};

use concat_export::ExportClip;
use concat_host::preview::FrameSpec;
use concat_host::session::Session;
use concat_project::commands::{ClipPatch, NewMedia};
use concat_project::model::{ClipKind, MediaKind, Timeline, VideoSettings};
use concat_project::{Command, DocumentSettings};
use serde_json::{Value, json};

use crate::lanes::{self, RowKind, RowView};
use crate::library::even;
use crate::{MobileError, Picture, context, micros, seconds};

/// A painted title's block size and its centre's offset from the clip's
/// centre, in frame pixels.
type TitleBlock = ((u32, u32), (i32, i32));

/// How big a sticker starts, over "fits the frame".
const STICKER_SCALE: f64 = 0.35;

/// How long a still (or a file of unknown length) runs when placed: the
/// engine's own default.
const STILL_SECONDS: f64 = 5.0;

/// What the monitor's picture is doing, which says which copy of each file
/// a frame is read from.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Motion {
    /// At rest: the files themselves, the exact frame.
    Still,
    /// Playing, or written out frame after frame: the files themselves,
    /// and the frames ahead decoded early.
    Playing,
    /// The playhead under a finger: each file's scrub copy where it has
    /// one, nothing decoded ahead.
    Scrubbing,
}

/// The open timeline, as the editor draws it.
#[derive(Debug, Clone, uniffi::Record)]
pub struct TimelineView {
    /// Changes with every edit, undo and redo.
    pub revision: u64,
    /// The project's title.
    pub name: String,
    /// Frame width in pixels.
    pub width: u32,
    /// Frame height in pixels.
    pub height: u32,
    /// Frame rate numerator.
    pub fps_num: i64,
    /// Frame rate denominator.
    pub fps_den: i64,
    /// Where the last clip ends, in microseconds.
    pub duration_us: i64,
    /// Whether there is an edit to undo.
    pub can_undo: bool,
    /// Whether there is an edit to redo.
    pub can_redo: bool,
    /// The rows, main first.
    pub rows: Vec<RowView>,
    /// The id the operation minted (a new clip), or empty.
    pub created_id: String,
}

/// Where a picture or title sits on the frame at one instant. Positions and
/// sizes are fractions of the frame's width and height.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ClipBox {
    /// The clip.
    pub clip_id: String,
    /// Centre, across.
    pub cx: f64,
    /// Centre, down.
    pub cy: f64,
    /// Width.
    pub width: f64,
    /// Height.
    pub height: f64,
    /// Clockwise degrees.
    pub rotation: f64,
    /// The clip's scale there (1 fits the frame).
    pub scale: f64,
    /// The clip's offset across there, as the engine stores it.
    pub offset_x: f64,
    /// The clip's offset down there.
    pub offset_y: f64,
}

/// One file added to the project's bin.
#[derive(Debug, Clone, uniffi::Record)]
pub struct MediaInfo {
    /// The bin id ("m3").
    pub id: String,
    /// "video", "audio" or "image".
    pub kind: String,
    /// Length in microseconds; 0 for a still.
    pub duration_us: i64,
    /// Picture width; 0 for sound.
    pub width: u32,
    /// Picture height; 0 for sound.
    pub height: u32,
    /// Whether the file carries sound.
    pub has_audio: bool,
}

/// The engine session for one open project.
#[derive(uniffi::Object)]
pub struct MobileSession {
    state: Mutex<State>,
    gestures: AtomicU64,
}

pub(crate) struct State {
    pub(crate) session: Session,
    revision: u64,
    /// The flattened clip list for the monitor, kept per revision and size.
    flat: Option<(u64, u32, u32, Arc<Vec<ExportClip>>)>,
    /// What each undo step is called, for the notice an undo shows.
    names: crate::history::Names,
    /// The videos whose scrub copies have been seen to.
    scrubbed: std::collections::HashSet<String>,
}

impl MobileSession {
    pub(crate) fn lock(&self) -> MutexGuard<'_, State> {
        // The engine keeps itself consistent between commands; losing the
        // project because one call panicked would be worse.
        self.state.lock().unwrap_or_else(PoisonError::into_inner)
    }

    /// A gesture name no other operation uses.
    pub(crate) fn gesture(&self) -> String {
        format!("capcraft-{}", self.gestures.fetch_add(1, Ordering::Relaxed))
    }

    /// Applies `commands` as one undo step and returns the new view.
    pub(crate) fn run(&self, commands: Vec<Command>) -> Result<TimelineView, MobileError> {
        let mut state = self.lock();
        let created = Self::apply_all(&mut state, commands, None)?;
        Ok(Self::changed(&mut state, created))
    }

    pub(crate) fn apply_all(
        state: &mut State,
        commands: Vec<Command>,
        gesture: Option<&str>,
    ) -> Result<Option<String>, MobileError> {
        let label = crate::history::name_of(&commands);
        let command = match commands.len() {
            0 => return Ok(None),
            1 => commands
                .into_iter()
                .next()
                .ok_or_else(|| "no command".to_owned())?,
            _ => Command::Batch { commands },
        };
        // The main track never keeps a gap: whatever an edit left open on it
        // (a speed change, a freeze, a move) closes in the same undo step.
        static OWN: AtomicU64 = AtomicU64::new(0);
        let own = gesture
            .is_none()
            .then(|| format!("capcraft-pack-{}", OWN.fetch_add(1, Ordering::Relaxed)));
        let name = gesture.or(own.as_deref());
        // what follows the main clips, as it stood: carried along after the edit
        let tied = crate::linkage::before(state.session.project().active());
        let result = state.session.apply_within(name, command);
        if result.is_ok()
            && let Some(moves) = lanes::pack_main(state.session.project().active())
        {
            let _ = state
                .session
                .apply_within(name, Command::MoveClips { moves });
        }
        if result.is_ok()
            && let Some(tied) = &tied
        {
            let moves = crate::linkage::follow(tied, state.session.project().active());
            if !moves.is_empty() {
                let _ = state
                    .session
                    .apply_within(name, Command::MoveClips { moves });
            }
        }
        if own.is_some() {
            state.session.end_gesture();
        }
        state.names.after(&state.session, &label);
        Ok(result?.created_id)
    }

    /// After an edit: a new revision, the sound handed to playback, the view.
    pub(crate) fn changed(state: &mut State, created: Option<String>) -> TimelineView {
        state.revision += 1;
        crate::playback::sync(&state.session);
        crate::scrubs::see_to(&state.session, &mut state.scrubbed);
        let mut view = Self::view_of(state);
        view.created_id = created.unwrap_or_default();
        view
    }

    fn view_of(state: &State) -> TimelineView {
        let session = &state.session;
        let project = session.project();
        let settings = session.settings();
        let duration = project
            .active()
            .clips
            .iter()
            .map(|clip| clip.start + clip.duration)
            .fold(0.0, f64::max);
        TimelineView {
            revision: state.revision,
            name: settings.name,
            width: settings.width,
            height: settings.height,
            fps_num: settings.rate_num,
            fps_den: settings.rate_den,
            duration_us: micros(duration),
            can_undo: session.can_undo(),
            can_redo: session.can_redo(),
            rows: lanes::rows(project),
            created_id: String::new(),
        }
    }

    /// Puts a clip of `kind` where it belongs: on a free lane of its kind,
    /// or on a new lane inserted at the right place in the stack. `make`
    /// builds the placing command for the lane chosen.
    fn place(
        &self,
        kind: RowKind,
        start: f64,
        duration: f64,
        make: impl FnOnce(String) -> Command,
    ) -> Result<TimelineView, MobileError> {
        self.place_many(
            kind,
            start,
            duration,
            |track| vec![make(track)],
            |_| Vec::new(),
        )
    }

    /// [`MobileSession::place`] for several commands on one lane (captions),
    /// then the commands `then` makes from the timeline as placed, all one
    /// undo step.
    pub(crate) fn place_many(
        &self,
        kind: RowKind,
        start: f64,
        duration: f64,
        make: impl FnOnce(String) -> Vec<Command>,
        then: impl FnOnce(&Timeline) -> Vec<Command>,
    ) -> Result<TimelineView, MobileError> {
        let mut state = self.lock();
        let timeline = state.session.project().active().clone();
        let gesture = self.gesture();
        let placed = (|| {
            let track = match lanes::free_lane(&timeline, kind, start, duration) {
                Some(track) => track,
                None => {
                    let index = lanes::insert_index(&timeline, kind);
                    Self::apply_all(
                        &mut state,
                        vec![Command::InsertTrack { index }],
                        Some(&gesture),
                    )?
                    .ok_or_else(|| "the new lane has no id".to_owned())?
                }
            };
            let created = Self::apply_all(&mut state, make(track), Some(&gesture))?;
            let follow = then(state.session.project().active());
            Self::apply_all(&mut state, follow, Some(&gesture))?;
            Ok::<_, MobileError>(created)
        })();
        state.session.end_gesture();
        let created = placed?;
        Ok(Self::changed(&mut state, created))
    }

    fn timeline(&self) -> Timeline {
        self.lock().session.project().active().clone()
    }

    /// A lane for an effect that treats every picture (`all`) or the main
    /// track alone, free over `[start, start + duration)`: one there is, or a
    /// new one put where that kind sits, listed in [`lanes::ALL_VIDEOS`] when
    /// it treats every picture.
    fn effect_lane(
        state: &mut State,
        timeline: &Timeline,
        all: bool,
        start: f64,
        duration: f64,
        gesture: &str,
    ) -> Result<String, MobileError> {
        if let Some(lane) = lanes::free_lane_at(timeline, RowKind::Effect, all, start, duration) {
            return Ok(lane);
        }
        let index = lanes::insert_index_at(timeline, lanes::tier(RowKind::Effect, all));
        let lane = Self::apply_all(state, vec![Command::InsertTrack { index }], Some(gesture))?
            .ok_or_else(|| "the new lane has no id".to_owned())?;
        if all {
            let mut listed = lanes::all_videos(timeline);
            listed.push(lane.clone());
            Self::apply_all(
                state,
                vec![Command::SetTimelineExtra {
                    key: lanes::ALL_VIDEOS.to_owned(),
                    value: Some(json!(listed)),
                }],
                Some(gesture),
            )?;
        }
        Ok(lane)
    }

    /// A new revision with nothing edited: for when what is on disk behind
    /// the timeline changed (a cutout's masks arrived), so it is drawn again.
    pub(crate) fn refreshed(&self) -> TimelineView {
        if let Ok(context) = context() {
            context.monitor.clear();
        }
        let mut state = self.lock();
        state.revision += 1;
        state.flat = None;
        Self::view_of(&state)
    }

    /// Runs `with` on the engine session, under the lock.
    pub(crate) fn with_session<R>(
        &self,
        with: impl FnOnce(&Session) -> Result<R, MobileError>,
    ) -> Result<R, MobileError> {
        let state = self.lock();
        with(&state.session)
    }

    /// The bin item's length in seconds (five for a still or unknown).
    fn media_length(&self, media_id: &str) -> f64 {
        let state = self.lock();
        state
            .session
            .project()
            .media
            .iter()
            .find(|item| item.id == media_id)
            .and_then(|item| match item.kind {
                MediaKind::Image => None,
                _ => item.duration,
            })
            .unwrap_or(STILL_SECONDS)
    }

    /// Adds `item`, read from `path`, to the bin, or answers with the entry
    /// a file already there has.
    fn add_to_bin(&self, path: String, item: NewMedia) -> Result<MediaInfo, MobileError> {
        let mut state = self.lock();
        let existing = |state: &State| {
            state
                .session
                .project()
                .media
                .iter()
                .find(|media| media.path == path)
                .map(|media| media.id.clone())
        };
        let id = match existing(&state) {
            Some(id) => id,
            None => {
                state.session.apply(Command::AddMedia { item })?;
                state.revision += 1;
                let state = &mut *state;
                crate::scrubs::see_to(&state.session, &mut state.scrubbed);
                existing(state).ok_or_else(|| "the file did not land in the bin".to_owned())?
            }
        };
        let media = state
            .session
            .project()
            .media
            .iter()
            .find(|media| media.id == id)
            .cloned()
            .ok_or_else(|| "the bin lost the file".to_owned())?;
        Ok(MediaInfo {
            id,
            kind: match media.kind {
                MediaKind::Video => "video",
                MediaKind::Audio => "audio",
                MediaKind::Image => "image",
            }
            .to_owned(),
            duration_us: media.duration.map(micros).unwrap_or(0),
            width: media.width.unwrap_or(0),
            height: media.height.unwrap_or(0),
            has_audio: media.has_audio,
        })
    }
}

/// A file's bin entry as its sound alone, or an error for a file with none.
fn sound_only(summary: &concat_host::media::MediaSummary) -> Result<NewMedia, MobileError> {
    if summary.audio.is_none() {
        return Err("that file has no sound".to_owned().into());
    }
    Ok(NewMedia {
        kind: MediaKind::Audio,
        width: None,
        height: None,
        frame_rate: None,
        frame_rate_fraction: None,
        video_codec: None,
        color_space: Default::default(),
        ..summary.to_new_media()
    })
}

/// Parses one engine command from its JSON (`{"op": "splitClips", ...}`).
fn parse(command: &str) -> Result<Command, MobileError> {
    serde_json::from_str(command)
        .map_err(|error| MobileError::Failed(format!("not a command: {error}")))
}

#[uniffi::export]
impl MobileSession {
    /// Opens the project in `path`.
    #[uniffi::constructor]
    pub fn open(path: String) -> Result<Arc<Self>, MobileError> {
        let info =
            concat_host::projects::open(&path).unwrap_or_else(|_| concat_host::ProjectInfo {
                path: path.clone(),
                name: String::new(),
                width: 1080,
                height: 1920,
                rate_num: 30,
                rate_den: 1,
                opened_at: 0,
                canvas: [0, 0, 0, 255],
            });
        let session = Session::open_info(&info)?;
        let names = crate::history::Names::new(&session);
        let mut state = State {
            session,
            revision: 1,
            flat: None,
            names,
            scrubbed: std::collections::HashSet::new(),
        };
        crate::playback::sync(&state.session);
        crate::scrubs::sweep(&state.session);
        crate::scrubs::see_to(&state.session, &mut state.scrubbed);
        Ok(Arc::new(MobileSession {
            state: Mutex::new(state),
            gestures: AtomicU64::new(0),
        }))
    }

    /// The project folder.
    pub fn path(&self) -> String {
        self.lock().session.path().to_owned()
    }

    /// The timeline as it stands.
    pub fn view(&self) -> TimelineView {
        Self::view_of(&self.lock())
    }

    /// Applies one engine command given as JSON. With `gesture` named, the
    /// commands sent under the same name (a slider being dragged) fold into
    /// one undo step until [`MobileSession::end_gesture`].
    pub fn apply(
        &self,
        command: String,
        gesture: Option<String>,
    ) -> Result<TimelineView, MobileError> {
        let command = parse(&command)?;
        let mut state = self.lock();
        let created = Self::apply_all(&mut state, vec![command], gesture.as_deref())?;
        Ok(Self::changed(&mut state, created))
    }

    /// Applies several engine commands (a JSON array) as one undo step.
    pub fn apply_batch(&self, commands: String) -> Result<TimelineView, MobileError> {
        let commands: Vec<Command> = serde_json::from_str(&commands)
            .map_err(|error| MobileError::Failed(format!("not a list of commands: {error}")))?;
        self.run(commands)
    }

    /// Ends the gesture in progress.
    pub fn end_gesture(&self) {
        self.lock().session.end_gesture();
    }

    /// Steps back one edit.
    pub fn undo(&self) -> TimelineView {
        let mut state = self.lock();
        state.session.undo();
        let State { session, names, .. } = &mut *state;
        names.undone(session);
        Self::changed(&mut state, None)
    }

    /// Steps forward one edit.
    pub fn redo(&self) -> TimelineView {
        let mut state = self.lock();
        state.session.redo();
        let State { session, names, .. } = &mut *state;
        names.redone(session);
        Self::changed(&mut state, None)
    }

    /// What an undo now would take back ("Split", "Edit text"), for the
    /// notice it shows; empty when there is nothing to undo.
    pub fn undo_name(&self) -> String {
        self.lock().names.to_undo()
    }

    /// What a redo now would bring back; empty when there is nothing.
    pub fn redo_name(&self) -> String {
        self.lock().names.to_redo()
    }

    /// Writes the project to its folder.
    pub fn save(&self) -> Result<(), MobileError> {
        let (path, document) = self.lock().session.prepare_save(None);
        concat_host::projects::save(&path, &document)?;
        Ok(())
    }

    /// Renames the project (saved with the next save).
    pub fn rename(&self, name: String) -> Result<(), MobileError> {
        let (path, document) = self.lock().session.prepare_save(Some(&name));
        concat_host::projects::save(&path, &document)?;
        Ok(())
    }

    /// Reads a file and adds it to the bin, without placing it anywhere.
    /// A file already in the bin is answered with its existing entry.
    pub fn import(&self, path: String) -> Result<MediaInfo, MobileError> {
        let summary = concat_host::media::probe(&path)?;
        self.add_to_bin(path, summary.to_new_media())
    }

    /// Reads a file and adds only its sound to the bin: a video brought in
    /// for its sound comes in as sound, so a clip of it is a sound clip, not
    /// a video on an audio row. A file with no sound is refused.
    pub fn import_sound(&self, path: String) -> Result<MediaInfo, MobileError> {
        let item = sound_only(&concat_host::media::probe(&path)?)?;
        self.add_to_bin(path, item)
    }

    /// Puts bin items on the main track, one after another, at the clip
    /// boundary nearest `at_us` (the end, for a timeline with nothing on
    /// it); later clips move along to make room. A project with nothing on
    /// it yet takes its frame from the first picture, as phone editors'
    /// "original" ratio does.
    pub fn add_to_main(
        &self,
        media_ids: Vec<String>,
        at_us: i64,
    ) -> Result<TimelineView, MobileError> {
        let timeline = self.timeline();
        let main = timeline
            .tracks
            .first()
            .map(|track| track.id.clone())
            .ok_or_else(|| "the timeline has no lanes".to_owned())?;
        let empty = lanes::main_end(&timeline) <= 0.0;
        let mut at = if empty {
            0.0
        } else {
            lanes::main_boundary(&timeline, seconds(at_us))
        };
        let mut commands = Vec::new();
        if empty && let Some(first) = media_ids.first() {
            let state = self.lock();
            let project = state.session.project();
            if let Some(item) = project.media.iter().find(|item| &item.id == first)
                && let (Some(width), Some(height)) = (item.width, item.height)
            {
                let mut video = project.active().video;
                let (w, h) = fit_frame(width, height);
                video.width = w;
                video.height = h;
                commands.push(Command::SetTimelineVideo {
                    timeline_id: project.active_timeline_id.clone(),
                    video,
                });
            }
        }
        for id in &media_ids {
            commands.push(Command::AddClip {
                media_id: id.clone(),
                track_id: main.clone(),
                start: at,
                ripple: true,
            });
            at += self.media_length(id);
        }
        self.run(commands)
    }

    /// Puts a bin item on an overlay row at `at_us`.
    pub fn add_overlay(&self, media_id: String, at_us: i64) -> Result<TimelineView, MobileError> {
        let start = seconds(at_us).max(0.0);
        let length = self.media_length(&media_id);
        self.place(RowKind::Overlay, start, length, |track_id| {
            Command::AddClip {
                media_id,
                track_id,
                start,
                ripple: false,
            }
        })
    }

    /// Puts a bin item's sound on an audio row at `at_us`.
    pub fn add_audio(&self, media_id: String, at_us: i64) -> Result<TimelineView, MobileError> {
        let start = seconds(at_us).max(0.0);
        let length = self.media_length(&media_id);
        self.place(RowKind::Audio, start, length, |track_id| Command::AddClip {
            media_id,
            track_id,
            start,
            ripple: false,
        })
    }

    /// Puts a sticker (a picture the app drew, already in the bin) on a
    /// text row at `at_us`, for `duration_us`.
    pub fn add_sticker(
        &self,
        media_id: String,
        at_us: i64,
        duration_us: i64,
    ) -> Result<TimelineView, MobileError> {
        let start = seconds(at_us).max(0.0);
        let duration = seconds(duration_us).max(0.1);
        let mut state = self.lock();
        let timeline = state.session.project().active().clone();
        let gesture = self.gesture();
        // Placed at the engine's length for a still, then cut to the one asked
        // for, a third of the frame wide: one undo step.
        let result = (|| {
            let lane = match lanes::free_lane(&timeline, RowKind::Text, start, STILL_SECONDS) {
                Some(lane) => lane,
                None => Self::apply_all(
                    &mut state,
                    vec![Command::InsertTrack {
                        index: lanes::insert_index(&timeline, RowKind::Text),
                    }],
                    Some(&gesture),
                )?
                .ok_or_else(|| "the new lane has no id".to_owned())?,
            };
            let created = Self::apply_all(
                &mut state,
                vec![Command::AddClip {
                    media_id,
                    track_id: lane,
                    start,
                    ripple: false,
                }],
                Some(&gesture),
            )?
            .ok_or_else(|| "the sticker has no id".to_owned())?;
            let placed = state
                .session
                .project()
                .active()
                .clips
                .iter()
                .find(|clip| clip.id == created)
                .map_or(STILL_SECONDS, |clip| clip.duration);
            Self::apply_all(
                &mut state,
                vec![
                    Command::TrimClip {
                        clip_id: created.clone(),
                        edge: concat_project::commands::TrimEdge::End,
                        delta: duration - placed,
                        ripple: false,
                    },
                    Command::SetClipTransform {
                        clip_id: created.clone(),
                        scale: Some(STICKER_SCALE),
                        offset_x: None,
                        offset_y: None,
                        rotation: None,
                        stretch_x: None,
                        stretch_y: None,
                    },
                ],
                Some(&gesture),
            )?;
            Ok::<String, MobileError>(created)
        })();
        state.session.end_gesture();
        let created = result?;
        Ok(Self::changed(&mut state, Some(created)))
    }

    /// Puts a title on a text row at `at_us`: `style` is the engine's text
    /// style as JSON (empty for the default), `duration_us` its length.
    pub fn add_text(
        &self,
        at_us: i64,
        duration_us: i64,
        style: String,
    ) -> Result<TimelineView, MobileError> {
        let start = seconds(at_us).max(0.0);
        let duration = seconds(duration_us).max(0.1);
        let style = if style.trim().is_empty() {
            None
        } else {
            Some(
                serde_json::from_str(&style)
                    .map_err(|error| MobileError::Failed(format!("not a text style: {error}")))?,
            )
        };
        self.place(RowKind::Text, start, duration, |track_id| {
            Command::AddTextClip {
                track_id: Some(track_id),
                above: false,
                start,
                style,
                duration: Some(duration),
                offset_y: None,
            }
        })
    }

    /// Puts a shape of the engine's own on a text row at `at_us` for
    /// `duration_us`: `style` is its shape style as JSON
    /// (`{"kind":"circle","fill":"#ffffff"}`), `name` what its lane calls it.
    pub fn add_shape(
        &self,
        at_us: i64,
        duration_us: i64,
        style: String,
        name: String,
    ) -> Result<TimelineView, MobileError> {
        let start = seconds(at_us).max(0.0);
        let duration = seconds(duration_us).max(0.1);
        let style = serde_json::from_str(&style)
            .map_err(|error| MobileError::Failed(format!("not a shape: {error}")))?;
        self.place(RowKind::Text, start, duration, |track_id| {
            Command::AddShapeClip {
                track_id: Some(track_id),
                above: false,
                start,
                style: Some(style),
                duration: Some(duration),
                name,
            }
        })
    }

    /// Puts a layer applying package `effect_id` on an effect or filter row
    /// at `at_us` for `duration_us`, labelled `name`.
    pub fn add_layer(
        &self,
        effect_id: String,
        name: String,
        at_us: i64,
        duration_us: i64,
    ) -> Result<TimelineView, MobileError> {
        let start = seconds(at_us).max(0.0);
        let duration = seconds(duration_us).max(0.1);
        let kind = lanes::layer_kind(Some(&effect_id));
        self.place(kind, start, duration, |track_id| Command::AddLayerClip {
            track_id: Some(track_id),
            start,
            duration: Some(duration),
            effect_id,
            name,
            sound: false,
        })
    }

    /// Deletes clips. Main-track clips close up behind them; clips on the
    /// other rows leave their space.
    pub fn delete(&self, clip_ids: Vec<String>) -> Result<TimelineView, MobileError> {
        let timeline = self.timeline();
        let (main, other): (Vec<String>, Vec<String>) = clip_ids
            .into_iter()
            .partition(|id| lanes::on_main(&timeline, id));
        let mut commands = Vec::new();
        if !main.is_empty() {
            commands.push(Command::RemoveClips {
                clip_ids: main,
                ripple: true,
            });
        }
        if !other.is_empty() {
            commands.push(Command::RemoveClips {
                clip_ids: other,
                ripple: false,
            });
        }
        self.run(commands)
    }

    /// Splits clips at `at_us`.
    pub fn split(&self, clip_ids: Vec<String>, at_us: i64) -> Result<TimelineView, MobileError> {
        self.run(vec![Command::SplitClips {
            clip_ids,
            time: seconds(at_us),
        }])
    }

    /// Takes `start_us..end_us` out of the main track and closes the gap
    /// behind it: the clips across either end are split there and the pieces
    /// between removed, as one undo step. The transcript's delete.
    pub fn cut_range(&self, start_us: i64, end_us: i64) -> Result<TimelineView, MobileError> {
        let (start, end) = (seconds(start_us), seconds(end_us));
        if end <= start {
            return Ok(self.view());
        }
        self.cut_ranges(&[(start, end)])
    }
}

impl MobileSession {
    /// Takes every range of `ranges` (seconds) out of the main track, the
    /// last first so the earlier ones stay where they were, as one undo step.
    pub(crate) fn cut_ranges(&self, ranges: &[(f64, f64)]) -> Result<TimelineView, MobileError> {
        let gesture = self.gesture();
        let mut state = self.lock();
        let mut ranges = ranges.to_vec();
        ranges.sort_by(|a, b| b.0.total_cmp(&a.0));
        let cut = (|| {
            for (start, end) in ranges {
                if end > start {
                    Self::cut_one(&mut state, start, end, &gesture)?;
                }
            }
            Ok::<_, MobileError>(())
        })();
        state.session.end_gesture();
        cut?;
        Ok(Self::changed(&mut state, None))
    }

    /// One range out of the main track under `gesture`: the clips across
    /// either end split there, the pieces between removed, the gap closed.
    fn cut_one(state: &mut State, start: f64, end: f64, gesture: &str) -> Result<(), MobileError> {
        // A whisker either side, so a cut at a clip's own edge is no split.
        const EDGE: f64 = 1e-4;
        let main_clips = |state: &State| -> Vec<(String, f64, f64)> {
            let timeline = state.session.project().active();
            let Some(main) = timeline.tracks.first() else {
                return Vec::new();
            };
            timeline
                .clips
                .iter()
                .filter(|clip| clip.track_id == main.id)
                .map(|clip| (clip.id.clone(), clip.start, clip.start + clip.duration))
                .collect()
        };
        for at in [start, end] {
            let across: Vec<String> = main_clips(state)
                .into_iter()
                .filter(|(_, from, to)| from + EDGE < at && at < to - EDGE)
                .map(|(id, ..)| id)
                .collect();
            if !across.is_empty() {
                let split = Command::SplitClips {
                    clip_ids: across,
                    time: at,
                };
                Self::apply_all(state, vec![split], Some(gesture))?;
            }
        }
        let inside: Vec<String> = main_clips(state)
            .into_iter()
            .filter(|(_, from, to)| *from >= start - EDGE && *to <= end + EDGE)
            .map(|(id, ..)| id)
            .collect();
        if !inside.is_empty() {
            let remove = Command::RemoveClips {
                clip_ids: inside,
                ripple: true,
            };
            Self::apply_all(state, vec![remove], Some(gesture))?;
        }
        Ok(())
    }
}

#[uniffi::export]
impl MobileSession {
    /// Drags one edge of a clip by `delta_us` (positive is to the right):
    /// `head` for the start, else the end. On the main track the clips after
    /// it follow.
    pub fn trim(
        &self,
        clip_id: String,
        head: bool,
        delta_us: i64,
    ) -> Result<TimelineView, MobileError> {
        let ripple = lanes::on_main(&self.timeline(), &clip_id);
        self.run(vec![Command::TrimClip {
            clip_id,
            edge: if head {
                concat_project::commands::TrimEdge::Start
            } else {
                concat_project::commands::TrimEdge::End
            },
            delta: seconds(delta_us),
            ripple,
        }])
    }

    /// Moves a clip of a row other than main to `start_us` on `track_id`
    /// (empty keeps its lane).
    pub fn move_clip(
        &self,
        clip_id: String,
        track_id: String,
        start_us: i64,
    ) -> Result<TimelineView, MobileError> {
        let timeline = self.timeline();
        let clip = timeline
            .clips
            .iter()
            .find(|clip| clip.id == clip_id)
            .ok_or_else(|| "that clip is gone".to_owned())?;
        let track = if track_id.is_empty() {
            clip.track_id.clone()
        } else {
            track_id
        };
        let start = timeline.nearest_free_start(
            &track,
            seconds(start_us).max(0.0),
            clip.duration,
            &[clip_id.as_str()],
        );
        self.run(vec![Command::MoveClips {
            moves: vec![concat_project::commands::ClipMove {
                clip_id,
                track_id: track,
                start,
            }],
        }])
    }

    /// Moves a main-track clip to position `index` among the main track's
    /// clips, the others closing up around it.
    pub fn reorder_main(&self, clip_id: String, index: u32) -> Result<TimelineView, MobileError> {
        let timeline = self.timeline();
        let main = timeline
            .tracks
            .first()
            .map(|track| track.id.clone())
            .ok_or_else(|| "the timeline has no lanes".to_owned())?;
        let mut clips: Vec<_> = timeline
            .clips
            .iter()
            .filter(|clip| clip.track_id == main)
            .collect();
        clips.sort_by(|a, b| a.start.total_cmp(&b.start));
        let from = clips
            .iter()
            .position(|clip| clip.id == clip_id)
            .ok_or_else(|| "that clip is not on the main track".to_owned())?;
        let moving = clips.remove(from);
        let to = (index as usize).min(clips.len());
        clips.insert(to, moving);
        let mut at = 0.0;
        let moves = clips
            .iter()
            .map(|clip| {
                let start = at;
                at += clip.duration;
                concat_project::commands::ClipMove {
                    clip_id: clip.id.clone(),
                    track_id: main.clone(),
                    start,
                }
            })
            .collect();
        self.run(vec![Command::MoveClips { moves }])
    }

    /// Copies a clip: on the main track the copy follows it and the rest
    /// move along; elsewhere it lands right after it, on a free row.
    pub fn duplicate(&self, clip_id: String) -> Result<TimelineView, MobileError> {
        let timeline = self.timeline();
        let clip = timeline
            .clips
            .iter()
            .find(|clip| clip.id == clip_id)
            .cloned()
            .ok_or_else(|| "that clip is gone".to_owned())?;
        let end = clip.start + clip.duration;
        if lanes::on_main(&timeline, &clip_id) {
            // Same media, same cut: added, then given the original's look.
            let mut state = self.lock();
            let gesture = self.gesture();
            let created = Self::apply_all(
                &mut state,
                vec![Command::AddClip {
                    media_id: clip.media_id.clone(),
                    track_id: clip.track_id.clone(),
                    start: end,
                    ripple: true,
                }],
                Some(&gesture),
            )?;
            if let Some(id) = &created {
                let _ = copy_look(&mut state, &clip, id, &gesture);
            }
            state.session.end_gesture();
            return Ok(Self::changed(&mut state, created));
        }
        let kind = lanes::kind_of(&clip);
        // a copy of an effect treats what the original treats
        let all = timeline
            .tracks
            .iter()
            .position(|track| track.id == clip.track_id)
            .is_some_and(|index| lanes::reaches_all(&timeline, index));
        let mut state = self.lock();
        let gesture = self.gesture();
        let track = if kind == RowKind::Effect {
            Self::effect_lane(&mut state, &timeline, all, end, clip.duration, &gesture)?
        } else {
            match lanes::free_lane(&timeline, kind, end, clip.duration) {
                Some(track) => track,
                None => Self::apply_all(
                    &mut state,
                    vec![Command::InsertTrack {
                        index: lanes::insert_index(&timeline, kind),
                    }],
                    Some(&gesture),
                )?
                .ok_or_else(|| "the new lane has no id".to_owned())?,
            }
        };
        let add = match clip.kind {
            ClipKind::Text => Command::AddTextClip {
                track_id: Some(track),
                above: false,
                start: end,
                style: clip.text.clone(),
                duration: Some(clip.duration),
                offset_y: None,
            },
            ClipKind::Layer => Command::AddLayerClip {
                track_id: Some(track),
                start: end,
                duration: Some(clip.duration),
                effect_id: clip
                    .video_effects
                    .first()
                    .map(|link| link.id.clone())
                    .unwrap_or_default(),
                name: clip.name.clone(),
                sound: false,
            },
            _ => Command::AddClip {
                media_id: clip.media_id.clone(),
                track_id: track,
                start: end,
                ripple: false,
            },
        };
        let created = Self::apply_all(&mut state, vec![add], Some(&gesture))?;
        if let Some(id) = &created {
            let _ = copy_look(&mut state, &clip, id, &gesture);
        }
        state.session.end_gesture();
        Ok(Self::changed(&mut state, created))
    }

    /// Sets the frame size (the canvas ratio), keeping the frame rate.
    pub fn set_canvas(&self, width: u32, height: u32) -> Result<TimelineView, MobileError> {
        let (timeline_id, mut video): (String, VideoSettings) = {
            let state = self.lock();
            let project = state.session.project();
            (project.active_timeline_id.clone(), project.active().video)
        };
        video.width = even(width);
        video.height = even(height);
        self.run(vec![Command::SetTimelineVideo { timeline_id, video }])
    }

    /// Sets the frame rate.
    pub fn set_fps(&self, fps: u32) -> Result<TimelineView, MobileError> {
        let (timeline_id, mut video): (String, VideoSettings) = {
            let state = self.lock();
            let project = state.session.project();
            (project.active_timeline_id.clone(), project.active().video)
        };
        video.rate_num = i64::from(fps.max(1));
        video.rate_den = 1;
        self.run(vec![Command::SetTimelineVideo { timeline_id, video }])
    }

    /// The composited frame at `time_us`, `width` by `height` (the canvas's
    /// shape at the size the screen shows it). `motion` says which copy of
    /// each file it is read from and what is decoded ahead: see [`Motion`].
    pub fn frame(
        &self,
        time_us: i64,
        width: u32,
        height: u32,
        motion: Motion,
    ) -> Result<Picture, MobileError> {
        let context = context()?;
        let (width, height) = (even(width), even(height));
        let (clips, settings, color_space) = {
            let mut state = self.lock();
            let settings = state.session.settings();
            let revision = state.revision;
            let cached = state.flat.as_ref().is_some_and(|(at, w, h, _)| {
                *at == revision && *w == settings.width && *h == settings.height
            });
            let clips = if cached {
                state
                    .flat
                    .as_ref()
                    .map(|entry| Arc::clone(&entry.3))
                    .unwrap_or_default()
            } else {
                let clips = Arc::new(monitor_clips(&state.session, &settings));
                state.flat = Some((
                    revision,
                    settings.width,
                    settings.height,
                    Arc::clone(&clips),
                ));
                clips
            };
            (clips, settings, state.session.video().color_space)
        };
        let spec = FrameSpec {
            time: seconds(time_us).max(0.0),
            width,
            height,
            moving: motion == Motion::Playing,
            proxy: false,
            scrub: motion == Motion::Scrubbing,
            color_space,
            canvas: settings.canvas,
        };
        let rgba = context
            .monitor
            .frame(Arc::clone(&clips), &settings, spec)
            .map_err(MobileError::Failed)?;
        // A drag goes either way, and its next instant is a cheap read of
        // the scrub copy: nothing is decoded ahead of it.
        let ahead = match motion {
            Motion::Playing => concat_media::prefetch::AHEAD,
            Motion::Still => 2,
            Motion::Scrubbing => 0,
        };
        if ahead > 0 {
            let monitor = context.monitor.clone();
            std::thread::spawn(move || monitor.prefetch(clips, &settings, spec, ahead));
        }
        Ok(Picture {
            width,
            height,
            rgba,
        })
    }

    /// The keyframe button: a key at `time_us` on every property the clip
    /// can key (its place, size, turn and opacity, and its volume when it
    /// has sound), holding the values it shows there; or, when there is a
    /// key there already, all of them taken off. One undo step.
    pub fn toggle_keyframe(
        &self,
        clip_id: String,
        time_us: i64,
    ) -> Result<TimelineView, MobileError> {
        use concat_project::model::{KeyEase, KeyProperty};
        let clip = self
            .timeline()
            .clips
            .iter()
            .find(|clip| clip.id == clip_id)
            .map(|clip| clip.as_ref().clone())
            .ok_or_else(|| "that clip is gone".to_owned())?;
        let at = ((seconds(time_us) - clip.start) / clip.duration.max(1e-6)).clamp(0.0, 1.0);
        let mut properties = vec![
            KeyProperty::Scale,
            KeyProperty::OffsetX,
            KeyProperty::OffsetY,
            KeyProperty::Rotation,
            KeyProperty::Opacity,
        ];
        if matches!(clip.kind, ClipKind::Video | ClipKind::Audio) {
            properties.push(KeyProperty::Volume);
        }
        if matches!(clip.kind, ClipKind::Audio) {
            properties.retain(|property| *property == KeyProperty::Volume);
        }
        // a frame either side counts as "here"
        let near = 1.0 / 30.0 / clip.duration.max(1e-6);
        let here = clip.keys.iter().any(|key| (key.at - at).abs() <= near);
        let commands = if here {
            clip.keys
                .iter()
                .filter(|key| (key.at - at).abs() <= near)
                .map(|key| Command::ClearClipKey {
                    clip_id: clip_id.clone(),
                    property: key.property,
                    at: key.at,
                })
                .collect()
        } else {
            properties
                .into_iter()
                .map(|property| Command::SetClipKey {
                    clip_id: clip_id.clone(),
                    property,
                    at,
                    value: clip.value_at(property, at),
                    ease: KeyEase::LINEAR,
                })
                .collect()
        };
        self.run(commands)
    }

    /// Where each picture and title visible at `time_us` sits on the frame,
    /// the topmost first: what the preview draws its selection frame from
    /// and hit-tests taps against.
    pub fn clip_boxes(&self, time_us: i64) -> Vec<ClipBox> {
        let Ok(context) = context() else {
            return Vec::new();
        };
        let state = self.lock();
        let project = state.session.project();
        let timeline = project.active();
        let settings = state.session.settings();
        let (frame_w, frame_h) = (f64::from(settings.width), f64::from(settings.height));
        let time = seconds(time_us);
        let titles: std::collections::HashMap<String, TitleBlock> = context
            .titles
            .clips(project, settings.width, settings.height)
            .into_iter()
            .map(|title| (title.clip_id, (title.block, title.offset)))
            .collect();
        let mut boxes: Vec<(usize, ClipBox)> = Vec::new();
        for clip in &timeline.clips {
            if time < clip.start || time >= clip.start + clip.duration {
                continue;
            }
            let Some(index) = timeline
                .tracks
                .iter()
                .position(|track| track.id == clip.track_id)
            else {
                continue;
            };
            let at = ((time - clip.start) / clip.duration.max(1e-6)).clamp(0.0, 1.0);
            use concat_project::model::KeyProperty;
            let scale = clip.value_at(KeyProperty::Scale, at);
            let offset_x = clip.value_at(KeyProperty::OffsetX, at);
            let offset_y = clip.value_at(KeyProperty::OffsetY, at);
            let rotation = clip.value_at(KeyProperty::Rotation, at);
            let (width, height, shift) = match clip.kind {
                ClipKind::Video | ClipKind::Image => {
                    let media = project.media.iter().find(|item| item.id == clip.media_id);
                    let (w, h) = media
                        .and_then(|item| Some((f64::from(item.width?), f64::from(item.height?))))
                        .unwrap_or((frame_w, frame_h));
                    let (w, h) = match &clip.crop {
                        Some(crop) => (
                            w * (1.0 - crop.left - crop.right).max(0.01),
                            h * (1.0 - crop.top - crop.bottom).max(0.01),
                        ),
                        None => (w, h),
                    };
                    let fit = (frame_w / w.max(1.0)).min(frame_h / h.max(1.0));
                    (
                        w * fit * scale * clip.stretch_x,
                        h * fit * scale * clip.stretch_y,
                        (0.0, 0.0),
                    )
                }
                ClipKind::Text | ClipKind::Shape => {
                    let Some(((bw, bh), (ox, oy))) = titles.get(&clip.id) else {
                        continue;
                    };
                    (
                        f64::from(*bw) * scale,
                        f64::from(*bh) * scale,
                        (f64::from(*ox) * scale, f64::from(*oy) * scale),
                    )
                }
                _ => continue,
            };
            let cx = (0.5 + offset_x) * frame_w + shift.0;
            let cy = (0.5 + offset_y) * frame_h + shift.1;
            boxes.push((
                index,
                ClipBox {
                    clip_id: clip.id.clone(),
                    cx: cx / frame_w,
                    cy: cy / frame_h,
                    width: width / frame_w,
                    height: height / frame_h,
                    rotation,
                    scale,
                    offset_x,
                    offset_y,
                },
            ));
        }
        boxes.sort_by(|a, b| b.0.cmp(&a.0));
        boxes.into_iter().map(|(_, b)| b).collect()
    }

    /// The frame at `time_us` as it would look with package `effect_id`
    /// laid over the whole picture, `width` by `height`: the tiles of the
    /// filter and effect pickers. Nothing is added to the timeline.
    pub fn frame_with(
        &self,
        time_us: i64,
        effect_id: String,
        width: u32,
        height: u32,
    ) -> Result<Picture, MobileError> {
        let context = context()?;
        let (width, height) = (even(width), even(height));
        let (mut clips, settings, color_space) = {
            let state = self.lock();
            let settings = state.session.settings();
            let clips = monitor_clips(&state.session, &settings);
            (clips, settings, state.session.video().color_space)
        };
        let top = clips
            .iter()
            .map(|clip| clip.track)
            .max()
            .map_or(0, |top| top + 1);
        let end = clips
            .iter()
            .map(|clip| clip.start + clip.duration)
            .fold(1.0, f64::max);
        clips.push(ExportClip {
            muted: true,
            volume: 0.0,
            effects: vec![concat_project::model::AppliedFilter::new(effect_id)],
            has_audio: Some(false),
            ..ExportClip::blank(concat_export::ClipKind::Layer, 0.0, end, top)
        });
        let spec = FrameSpec {
            time: seconds(time_us).max(0.0),
            width,
            height,
            moving: false,
            proxy: false,
            scrub: false,
            color_space,
            canvas: settings.canvas,
        };
        let rgba = context
            .monitor
            .frame(Arc::new(clips), &settings, spec)
            .map_err(MobileError::Failed)?;
        Ok(Picture {
            width,
            height,
            rgba,
        })
    }

    /// A value the app keeps on the active timeline under `key` (see the
    /// `setTimelineExtra` command), as JSON, or empty when there is none.
    pub fn extra(&self, key: String) -> String {
        self.lock()
            .session
            .project()
            .active()
            .extra
            .get(&key)
            .map(|value| value.to_string())
            .unwrap_or_default()
    }

    /// The whole clip list as the engine's document holds it, as JSON: for
    /// panels that read a property this bridge does not list.
    pub fn document(&self) -> String {
        self.lock().session.document().to_string()
    }

    /// Moves the row holding `clip_id` among the rows of its kind, which is
    /// what decides what covers what: `step` rows towards the front
    /// (positive) or the back (negative), stopping at either end. The main
    /// track never moves.
    pub fn restack(&self, clip_id: String, step: i32) -> Result<TimelineView, MobileError> {
        let timeline = self.timeline();
        let clip = timeline
            .clips
            .iter()
            .find(|clip| clip.id == clip_id)
            .ok_or_else(|| "that clip is gone".to_owned())?;
        let Some(from) = timeline
            .tracks
            .iter()
            .position(|track| track.id == clip.track_id)
        else {
            return Ok(self.view());
        };
        let kind = lanes::role(&timeline, from);
        if from == 0 || kind.is_none() {
            return Ok(self.view());
        }
        // an effect keeps to the lanes that treat what it treats
        let all = lanes::reaches_all(&timeline, from);
        let peers: Vec<usize> = (1..timeline.tracks.len())
            .filter(|index| {
                lanes::role(&timeline, *index) == kind
                    && lanes::reaches_all(&timeline, *index) == all
            })
            .collect();
        let Some(place) = peers.iter().position(|index| *index == from) else {
            return Ok(self.view());
        };
        let wanted = (place as i64 + i64::from(step)).clamp(0, peers.len() as i64 - 1) as usize;
        if wanted == place {
            return Ok(self.view());
        }
        self.run(vec![Command::MoveTrack {
            track_id: clip.track_id.clone(),
            index: peers[wanted],
        }])
    }

    /// Sets what the effect `clip_id` treats, as the reference's Object
    /// does: the main track alone (`all` false, as new effects start) or
    /// every picture beneath it, overlays too. A layer treats what is under
    /// its lane, so the clip moves, at the same time, to a lane under the
    /// overlays or over them.
    pub fn set_reach(&self, clip_id: String, all: bool) -> Result<TimelineView, MobileError> {
        let timeline = self.timeline();
        let clip = timeline
            .clips
            .iter()
            .find(|clip| clip.id == clip_id)
            .cloned()
            .ok_or_else(|| "that clip is gone".to_owned())?;
        let Some(from) = timeline
            .tracks
            .iter()
            .position(|track| track.id == clip.track_id)
        else {
            return Ok(self.view());
        };
        if lanes::role(&timeline, from) != Some(RowKind::Effect)
            || lanes::reaches_all(&timeline, from) == all
        {
            return Ok(self.view());
        }
        let mut state = self.lock();
        let gesture = self.gesture();
        let result = (|| {
            let lane = Self::effect_lane(
                &mut state,
                &timeline,
                all,
                clip.start,
                clip.duration,
                &gesture,
            )?;
            Self::apply_all(
                &mut state,
                vec![Command::MoveClips {
                    moves: vec![concat_project::commands::ClipMove {
                        clip_id: clip.id.clone(),
                        track_id: lane,
                        start: clip.start,
                    }],
                }],
                Some(&gesture),
            )?;
            Ok::<(), MobileError>(())
        })();
        state.session.end_gesture();
        result?;
        Ok(Self::changed(&mut state, Some(clip_id)))
    }

    /// Puts `clip_id` just above (`above`) or just below the lane holding
    /// `target_id`, whatever each holds: a title can go under an overlay, as
    /// the reference's Arrange layers puts one. A clip alone on its lane moves
    /// with the lane; one that shares it moves by itself onto a new lane there,
    /// leaving the others where they were. The main track never moves and
    /// nothing goes under it.
    pub fn arrange(
        &self,
        clip_id: String,
        target_id: String,
        above: bool,
    ) -> Result<TimelineView, MobileError> {
        let timeline = self.timeline();
        let lane_of = |id: &str| {
            let clip = timeline.clips.iter().find(|clip| clip.id == id)?;
            timeline
                .tracks
                .iter()
                .position(|track| track.id == clip.track_id)
        };
        let (Some(from), Some(target)) = (lane_of(&clip_id), lane_of(&target_id)) else {
            return Ok(self.view());
        };
        if from == 0 || from == target || (target == 0 && !above) {
            return Ok(self.view());
        }
        let lane = timeline.tracks[from].id.clone();
        let shared = timeline
            .clips
            .iter()
            .any(|clip| clip.track_id == lane && clip.id != clip_id);
        if shared {
            let Some(clip) = timeline.clips.iter().find(|clip| clip.id == clip_id) else {
                return Ok(self.view());
            };
            let start = clip.start;
            let mut state = self.lock();
            let gesture = self.gesture();
            let result = (|| {
                // a new lane over the target pushes nothing below it; under it, the target goes up one
                let index = if above { target + 1 } else { target.max(1) };
                let fresh = Self::apply_all(
                    &mut state,
                    vec![Command::InsertTrack { index }],
                    Some(&gesture),
                )?
                .ok_or_else(|| "the new lane has no id".to_owned())?;
                Self::apply_all(
                    &mut state,
                    vec![Command::MoveClips {
                        moves: vec![concat_project::commands::ClipMove {
                            clip_id: clip_id.clone(),
                            track_id: fresh,
                            start,
                        }],
                    }],
                    Some(&gesture),
                )?;
                Ok::<(), MobileError>(())
            })();
            state.session.end_gesture();
            result?;
            return Ok(Self::changed(&mut state, Some(clip_id)));
        }
        // the lane comes out before it goes back in, so a target above it is one lower
        let index = match (above, from < target) {
            (true, true) => target,
            (true, false) => target + 1,
            (false, true) => target - 1,
            (false, false) => target,
        };
        self.run(vec![Command::MoveTrack {
            track_id: timeline.tracks[from].id.clone(),
            index: index.max(1),
        }])
    }

    /// Moves a main-track clip onto an overlay row at the same time; the
    /// main track closes up behind it.
    pub fn to_overlay(&self, clip_id: String) -> Result<TimelineView, MobileError> {
        let timeline = self.timeline();
        let clip = timeline
            .clips
            .iter()
            .find(|clip| clip.id == clip_id)
            .cloned()
            .ok_or_else(|| "that clip is gone".to_owned())?;
        if !lanes::on_main(&timeline, &clip_id) {
            return Ok(self.view());
        }
        let main = timeline.tracks[0].id.clone();
        let mut state = self.lock();
        let gesture = self.gesture();
        let lane = match lanes::free_lane(&timeline, RowKind::Overlay, clip.start, clip.duration) {
            Some(lane) => lane,
            None => Self::apply_all(
                &mut state,
                vec![Command::InsertTrack {
                    index: lanes::insert_index(&timeline, RowKind::Overlay),
                }],
                Some(&gesture),
            )?
            .ok_or_else(|| "the new lane has no id".to_owned())?,
        };
        let mut moves = vec![concat_project::commands::ClipMove {
            clip_id: clip.id.clone(),
            track_id: lane,
            start: clip.start,
        }];
        let mut later: Vec<_> = timeline
            .clips
            .iter()
            .filter(|other| {
                other.track_id == main && other.start >= clip.start + clip.duration - 1e-6
            })
            .collect();
        later.sort_by(|a, b| a.start.total_cmp(&b.start));
        moves.extend(
            later
                .into_iter()
                .map(|other| concat_project::commands::ClipMove {
                    clip_id: other.id.clone(),
                    track_id: main.clone(),
                    start: (other.start - clip.duration).max(0.0),
                }),
        );
        let result = Self::apply_all(
            &mut state,
            vec![Command::MoveClips { moves }],
            Some(&gesture),
        );
        state.session.end_gesture();
        result?;
        Ok(Self::changed(&mut state, Some(clip_id)))
    }

    /// Moves a clip of an overlay row onto the main track, at the clip
    /// boundary nearest its start; the main track makes room.
    pub fn to_main(&self, clip_id: String) -> Result<TimelineView, MobileError> {
        let timeline = self.timeline();
        let clip = timeline
            .clips
            .iter()
            .find(|clip| clip.id == clip_id)
            .cloned()
            .ok_or_else(|| "that clip is gone".to_owned())?;
        if lanes::on_main(&timeline, &clip_id) || !clip.kind.is_visual() {
            return Ok(self.view());
        }
        let main = timeline.tracks[0].id.clone();
        let end = lanes::main_end(&timeline);
        let at = if clip.start >= end {
            end
        } else {
            lanes::main_boundary(&timeline, clip.start)
        };
        let mut later: Vec<_> = timeline
            .clips
            .iter()
            .filter(|other| other.track_id == main && other.start >= at - 1e-6)
            .collect();
        // the last first, so no move lands on a clip that has not moved yet
        later.sort_by(|a, b| b.start.total_cmp(&a.start));
        let mut moves: Vec<_> = later
            .into_iter()
            .map(|other| concat_project::commands::ClipMove {
                clip_id: other.id.clone(),
                track_id: main.clone(),
                start: other.start + clip.duration,
            })
            .collect();
        moves.push(concat_project::commands::ClipMove {
            clip_id: clip.id.clone(),
            track_id: main,
            start: at,
        });
        let view = self.run(vec![Command::MoveClips { moves }])?;
        Ok(TimelineView {
            created_id: clip_id,
            ..view
        })
    }

    /// Points a clip at another file (the user's replacement), keeping its
    /// length, place and look; a sound clip takes only the file's sound.
    pub fn replace_media(
        &self,
        clip_id: String,
        path: String,
    ) -> Result<TimelineView, MobileError> {
        let summary = concat_host::media::probe(&path)?;
        let sound = self
            .lock()
            .session
            .project()
            .active()
            .clips
            .iter()
            .any(|clip| clip.id == clip_id && clip.kind == ClipKind::Audio);
        let item = if sound {
            sound_only(&summary)?
        } else {
            summary.to_new_media()
        };
        self.run(vec![Command::ReplaceClipMedia {
            clip_id,
            item,
            source_start: Some(0.0),
            enhanced: false,
            offset: 0.0,
        }])
    }

    /// A freeze frame at `at_us`: the picture there held for three seconds,
    /// the clip split around it and the rest of the track moved along.
    pub fn freeze(&self, clip_id: String, at_us: i64) -> Result<TimelineView, MobileError> {
        let timeline = self.timeline();
        let clip = timeline
            .clips
            .iter()
            .find(|clip| clip.id == clip_id)
            .cloned()
            .ok_or_else(|| "that clip is gone".to_owned())?;
        let time = seconds(at_us);
        let still = if clip.kind == ClipKind::Video {
            let (media_path, folder) = {
                let state = self.lock();
                let media = state
                    .session
                    .project()
                    .media
                    .iter()
                    .find(|item| item.id == clip.media_id)
                    .map(|item| item.path.clone())
                    .ok_or_else(|| "the clip's file is gone".to_owned())?;
                (
                    media,
                    std::path::PathBuf::from(state.session.path()).join("media"),
                )
            };
            let source = clip.source_start + (time - clip.start).max(0.0) * clip.speed;
            let probe = concat_host::media::probe(&media_path)?;
            let width = probe.video.as_ref().map_or(1080, |video| video.width);
            let frame = concat_host::media::still_at(&media_path, source, width)?;
            std::fs::create_dir_all(&folder)
                .map_err(|error| format!("could not create {}: {error}", folder.display()))?;
            let file = folder.join(format!("freeze-{}-{}.png", clip.id, micros(source)));
            write_png(&file, &crate::Picture::of(frame))?;
            Some(concat_host::media::probe(&file.to_string_lossy())?.to_new_media())
        } else {
            None
        };
        self.run(vec![Command::FreezeFrame {
            clip_id,
            time,
            duration: Some(3.0),
            still,
        }])
    }

    /// Takes a video clip's sound out onto an audio row, the video then
    /// silent.
    pub fn extract_audio(&self, clip_id: String) -> Result<TimelineView, MobileError> {
        let mut state = self.lock();
        let gesture = self.gesture();
        let created = Self::apply_all(
            &mut state,
            vec![Command::DetachAudio { clip_id }],
            Some(&gesture),
        );
        let created = match created {
            Ok(created) => created,
            Err(error) => {
                state.session.end_gesture();
                return Err(error);
            }
        };
        if let Some(sound) = &created {
            let _ = Self::relocate(&mut state, sound, RowKind::Audio, &gesture);
            let name = extracted_name(state.session.project().active());
            let _ = Self::apply_all(
                &mut state,
                vec![Command::UpdateClip {
                    clip_id: sound.clone(),
                    patch: ClipPatch {
                        name: Some(name),
                        ..ClipPatch::default()
                    },
                }],
                Some(&gesture),
            );
        }
        state.session.end_gesture();
        Ok(Self::changed(&mut state, created))
    }

    /// Plays a clip backwards: a reversed copy of its stretch of the file is
    /// made (this takes a while) and the clip shows that instead.
    pub fn reverse(&self, clip_id: String) -> Result<TimelineView, MobileError> {
        let (request, clip_id) = {
            let state = self.lock();
            let project = state.session.project();
            let clip = project
                .active()
                .clips
                .iter()
                .find(|clip| clip.id == clip_id)
                .ok_or_else(|| "that clip is gone".to_owned())?;
            let media = project
                .media
                .iter()
                .find(|item| item.id == clip.media_id)
                .ok_or_else(|| "the clip's file is gone".to_owned())?;
            let audio_only = clip.kind == ClipKind::Audio;
            let start = clip.source_start;
            let duration = clip.duration * clip.speed;
            let target = concat_host::reverse::target_for(
                std::path::Path::new(state.session.path()),
                &media.path,
                start,
                duration,
                audio_only,
            )
            .ok_or_else(|| "the clip's file cannot be read".to_owned())?;
            (
                concat_host::reverse::ReverseRequest {
                    media_path: media.path.clone(),
                    audio_only,
                    start,
                    duration,
                    target,
                },
                clip.id.clone(),
            )
        };
        let copy = concat_host::reverse::Reversers::new().reverse(&request, &mut |_| {})?;
        let item = concat_host::media::probe(&copy.to_string_lossy())?.to_new_media();
        self.run(vec![Command::ReplaceClipMedia {
            clip_id,
            item,
            source_start: Some(0.0),
            enhanced: false,
            offset: 0.0,
        }])
    }
}

impl MobileSession {
    /// Moves a clip the engine placed itself onto a lane of `kind`, when
    /// the lane it got holds other kinds: a free lane of the kind, or a new
    /// one, as a move of `gesture`.
    fn relocate(
        state: &mut State,
        clip_id: &str,
        kind: RowKind,
        gesture: &str,
    ) -> Result<(), MobileError> {
        let timeline = state.session.project().active().clone();
        let Some(clip) = timeline.clips.iter().find(|clip| clip.id == clip_id) else {
            return Ok(());
        };
        let alone = timeline
            .clips
            .iter()
            .filter(|other| other.track_id == clip.track_id && other.id != clip.id)
            .all(|other| lanes::kind_of(other) == kind);
        let on_main = timeline.tracks.first().map(|track| &track.id) == Some(&clip.track_id);
        if alone && !on_main {
            return Ok(());
        }
        let lane = match lanes::free_lane(&timeline, kind, clip.start, clip.duration) {
            Some(lane) if lane != clip.track_id => lane,
            _ => Self::apply_all(
                state,
                vec![Command::InsertTrack {
                    index: lanes::insert_index(&timeline, kind),
                }],
                Some(gesture),
            )?
            .ok_or_else(|| "the new lane has no id".to_owned())?,
        };
        Self::apply_all(
            state,
            vec![Command::MoveClips {
                moves: vec![concat_project::commands::ClipMove {
                    clip_id: clip_id.to_owned(),
                    track_id: lane,
                    start: clip.start,
                }],
            }],
            Some(gesture),
        )?;
        Ok(())
    }
}

/// Writes a picture as a PNG.
fn write_png(path: &std::path::Path, picture: &crate::Picture) -> Result<(), MobileError> {
    let file = std::fs::File::create(path)
        .map_err(|error| format!("could not write {}: {error}", path.display()))?;
    let mut encoder =
        png::Encoder::new(std::io::BufWriter::new(file), picture.width, picture.height);
    encoder.set_color(png::ColorType::Rgba);
    encoder.set_depth(png::BitDepth::Eight);
    let mut writer = encoder
        .write_header()
        .map_err(|error| format!("could not write the still: {error}"))?;
    writer
        .write_image_data(&picture.rgba)
        .map_err(|error| format!("could not write the still: {error}"))?;
    Ok(())
}

/// The timeline flattened for the monitor, titles painted and rejoined.
pub(crate) fn monitor_clips(session: &Session, settings: &DocumentSettings) -> Vec<ExportClip> {
    let mut clips = session.flattened_clips();
    if let Ok(context) = context() {
        for title in context
            .titles
            .clips(session.project(), settings.width, settings.height)
        {
            clips.push(title.clip);
        }
    }
    crate::background::add(
        session.project(),
        settings,
        std::path::Path::new(session.path()),
        &mut clips,
    );
    clips
}

/// A frame for a picture `width` by `height`: its shape, 1080 on the short
/// side at most, even sides.
fn fit_frame(width: u32, height: u32) -> (u32, u32) {
    let short = width.min(height).max(1);
    if short <= 1080 {
        return (even(width), even(height));
    }
    let scale = 1080.0 / f64::from(short);
    (
        even((f64::from(width) * scale).round() as u32),
        even((f64::from(height) * scale).round() as u32),
    )
}

/// What an extracted sound is called: "Extracted audio" and the next number
/// after those already on the timeline.
fn extracted_name(timeline: &Timeline) -> String {
    const NAME: &str = "Extracted audio";
    let last = timeline
        .clips
        .iter()
        .filter_map(|clip| clip.name.strip_prefix(NAME)?.parse::<u32>().ok())
        .max()
        .unwrap_or(0);
    format!("{NAME}{}", last + 1)
}

/// Gives clip `to` - just added, from the same file - the cut and look of
/// `from`: its speed, in-point and length, placement and keys, sound and
/// chains, under `gesture`. The length goes last, once the speed has set
/// the copy's.
pub(crate) fn copy_look(
    state: &mut State,
    from: &concat_project::model::Clip,
    to: &str,
    gesture: &str,
) -> Result<(), MobileError> {
    let mut commands = Vec::new();
    if let Some(curve) = &from.speed_curve {
        commands.push(Command::SetClipSpeedCurve {
            clip_id: to.to_owned(),
            curve: Some(curve.clone()),
            ripple: false,
        });
    } else if (from.speed - 1.0).abs() > f64::EPSILON {
        commands.push(Command::SetClipSpeed {
            clip_id: to.to_owned(),
            speed: from.speed,
            ripple: false,
        });
    }
    let mut value = serde_json::to_value(from).unwrap_or(Value::Null);
    let mut patch = serde_json::Map::new();
    if let Some(object) = value.as_object_mut() {
        for key in [
            "volume",
            "fadeIn",
            "fadeOut",
            "opacity",
            "flipH",
            "flipV",
            "blend",
            "crop",
            "filters",
            "videoEffects",
            "text",
            "muted",
            "preservePitch",
        ] {
            if let Some(field) = object.remove(key) {
                patch.insert(key.to_owned(), field);
            }
        }
    }
    if let Ok(command) = serde_json::from_value::<Command>(json!({
        "op": "updateClip",
        "clipId": to,
        "patch": Value::Object(patch),
    })) {
        commands.push(command);
    }
    commands.push(Command::SetClipTransform {
        clip_id: to.to_owned(),
        scale: Some(from.scale),
        offset_x: Some(from.offset_x),
        offset_y: Some(from.offset_y),
        rotation: Some(from.rotation),
        stretch_x: Some(from.stretch_x),
        stretch_y: Some(from.stretch_y),
    });
    // the in-point: the same file again, from where the original starts
    if from.source_start > 1e-9
        && let Some(path) = state
            .session
            .project()
            .media
            .iter()
            .find(|item| item.id == from.media_id)
            .map(|item| item.path.clone())
        && let Ok(info) = concat_host::media::probe(&path)
    {
        commands.push(Command::ReplaceClipMedia {
            clip_id: to.to_owned(),
            item: info.to_new_media(),
            source_start: Some(from.source_start),
            enhanced: false,
            offset: 0.0,
        });
    }
    for key in &from.keys {
        commands.push(Command::SetClipKey {
            clip_id: to.to_owned(),
            property: key.property,
            at: key.at,
            value: key.value,
            ease: key.ease,
        });
    }
    MobileSession::apply_all(state, commands, Some(gesture))?;
    let now = state
        .session
        .project()
        .active()
        .clips
        .iter()
        .find(|clip| clip.id == to)
        .map(|clip| clip.duration);
    if let Some(now) = now
        && (now - from.duration).abs() > 1e-6
    {
        MobileSession::apply_all(
            state,
            vec![Command::TrimClip {
                clip_id: to.to_owned(),
                edge: concat_project::commands::TrimEdge::End,
                delta: from.duration - now,
                ripple: false,
            }],
            Some(gesture),
        )?;
    }
    Ok(())
}

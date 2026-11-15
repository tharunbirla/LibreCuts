// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer and Concat contributors
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! The projects on this phone: one folder each under the app's `projects`
//! folder, named by when it was made rather than by its title, so two
//! projects may share a title and a rename never moves a folder.
//!
//! A folder holds the engine's document (`concat.json`), the media copied in
//! (`media/`), the engine's caches (`cache/`) and the cover the app saves
//! (`cover.jpg`).

use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use concat_host::projects;
use concat_host::session::Session;
use concat_project::DocumentSettings;
use serde_json::Value;

use crate::{MobileError, context, micros};

/// The cover picture the app keeps beside a project's document.
pub const COVER: &str = "cover.jpg";

/// One project, as the home screen lists it.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ProjectSummary {
    /// The project folder.
    pub path: String,
    /// The title.
    pub name: String,
    /// Frame size of the timeline being edited.
    pub width: u32,
    /// Frame height.
    pub height: u32,
    /// Length of the timeline, in microseconds.
    pub duration_us: i64,
    /// When the document last changed, in milliseconds since the epoch.
    pub modified_ms: i64,
    /// Bytes the folder takes, media and caches included.
    pub size_bytes: i64,
    /// The cover picture, when the app has saved one.
    pub cover: Option<String>,
}

/// Every project, the most recently changed first.
#[uniffi::export]
pub fn list_projects() -> Result<Vec<ProjectSummary>, MobileError> {
    let root = &context()?.projects;
    let mut found: Vec<ProjectSummary> = std::fs::read_dir(root)
        .map_err(|error| format!("could not read {}: {error}", root.display()))?
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| path.is_dir() && projects::is_project(path))
        .filter_map(|path| summary(&path).ok())
        .collect();
    found.sort_by(|a, b| b.modified_ms.cmp(&a.modified_ms));
    Ok(found)
}

/// One project's summary.
#[uniffi::export]
pub fn project_summary(path: String) -> Result<ProjectSummary, MobileError> {
    summary(Path::new(&path))
}

/// Makes an empty project: `name` is its title, the frame `width` by
/// `height` at `fps` frames a second.
#[uniffi::export]
pub fn create_project(
    name: String,
    width: u32,
    height: u32,
    fps: u32,
) -> Result<ProjectSummary, MobileError> {
    let root = &context()?.projects;
    let folder = fresh_folder(root)?;
    let settings = DocumentSettings {
        name: name.trim().to_owned(),
        width: even(width),
        height: even(height),
        rate_num: i64::from(fps.max(1)),
        rate_den: 1,
        canvas: [0, 0, 0, 255],
    };
    let mut session = Session::open(&folder.to_string_lossy(), settings)?;
    session.save(None)?;
    summary(&folder)
}

/// Gives a project a new title.
#[uniffi::export]
pub fn rename_project(path: String, name: String) -> Result<ProjectSummary, MobileError> {
    let folder = inside_library(&path)?;
    let name = name.trim();
    if name.is_empty() {
        return Err(MobileError::Failed("A project needs a name.".to_owned()));
    }
    let mut document = projects::read_document(&path)?
        .ok_or_else(|| MobileError::Failed("That project has no document.".to_owned()))?;
    if let Some(object) = document.as_object_mut() {
        object.insert("name".to_owned(), Value::String(name.to_owned()));
    }
    projects::save(&path, &document)?;
    summary(&folder)
}

/// The names of the files in a project's cache that clips show instead of
/// their media (reversed, enhanced and steadied copies): kept when the cache
/// is cleared and copied with the project, unlike the previews and waveforms
/// beside them, which are made again on use.
pub const KEPT_COPIES: [&str; 3] = ["reverse-", "enhance-", "stabilize-"];

/// Copies the [`KEPT_COPIES`] in `from` (a project's cache) into `to`.
fn copy_kept_copies(from: &Path, to: &Path) -> std::io::Result<()> {
    let Ok(entries) = std::fs::read_dir(from) else {
        return Ok(());
    };
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().into_owned();
        if KEPT_COPIES.iter().any(|prefix| name.starts_with(prefix)) && !name.contains(".part.") {
            std::fs::create_dir_all(to)?;
            std::fs::copy(entry.path(), to.join(&name))?;
        }
    }
    Ok(())
}

/// Copies a project - its media, document and cover - into a new folder
/// titled `name`. The caches are not copied; they are made again on use.
#[uniffi::export]
pub fn duplicate_project(path: String, name: String) -> Result<ProjectSummary, MobileError> {
    let from = inside_library(&path)?;
    let root = &context()?.projects;
    let to = fresh_folder(root)?;
    copy_tree(&from, &to, &from.join("cache"))
        .map_err(|error| format!("could not copy the project: {error}"))?;
    // the copies clips are pointed at live in the cache too, and cannot be made again from it
    copy_kept_copies(&from.join("cache"), &to.join("cache"))
        .map_err(|error| format!("could not copy the project: {error}"))?;
    // Media paths in the document name the old folder: point them here.
    let old = from.to_string_lossy().into_owned();
    let new = to.to_string_lossy().into_owned();
    if let Some(mut document) = projects::read_document(&new)? {
        repoint(&mut document, &old, &new);
        if let Some(object) = document.as_object_mut() {
            object.insert("name".to_owned(), Value::String(name.trim().to_owned()));
        }
        projects::save(&new, &document)?;
    }
    summary(&to)
}

/// Deletes a project folder and everything in it.
#[uniffi::export]
pub fn delete_project(path: String) -> Result<(), MobileError> {
    let folder = inside_library(&path).or_else(|_| inside_trash(&path))?;
    std::fs::remove_dir_all(&folder)
        .map_err(|error| MobileError::Failed(format!("could not delete the project: {error}")))
}

/// How long a project stays in the trash before it goes for good.
const TRASH_DAYS: u64 = 30;

/// The file in a trashed project's folder saying when it was put there.
const TRASHED_AT: &str = "trashed-at";

/// The trash: beside the projects folder, one folder a project, as they were.
fn trash_root() -> Result<PathBuf, MobileError> {
    let root = context()?.projects.with_file_name("trash");
    std::fs::create_dir_all(&root)
        .map_err(|error| format!("could not create {}: {error}", root.display()))?;
    Ok(root)
}

/// The first free name for `name` in `under`.
fn free_place(under: &Path, name: &std::ffi::OsStr) -> PathBuf {
    let first = under.join(name);
    if !first.exists() {
        return first;
    }
    (2..)
        .map(|n| under.join(format!("{}-{n}", name.to_string_lossy())))
        .find(|place| !place.exists())
        .unwrap_or(first)
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Moves a project into the trash, where it is kept for thirty days.
#[uniffi::export]
pub fn trash_project(path: String) -> Result<(), MobileError> {
    let folder = inside_library(&path)?;
    let name = folder
        .file_name()
        .ok_or_else(|| MobileError::Failed("That is not a project.".to_owned()))?;
    let place = free_place(&trash_root()?, name);
    std::fs::rename(&folder, &place)
        .map_err(|error| MobileError::Failed(format!("could not move the project: {error}")))?;
    std::fs::write(place.join(TRASHED_AT), now_ms().to_string())
        .map_err(|error| MobileError::Failed(format!("could not mark the project: {error}")))
}

/// The projects in the trash, the last put there first; those kept past
/// thirty days are deleted on the way.
#[uniffi::export]
pub fn list_trash() -> Result<Vec<ProjectSummary>, MobileError> {
    let root = trash_root()?;
    let oldest = now_ms().saturating_sub(TRASH_DAYS * 24 * 3600 * 1000);
    let mut found: Vec<(u64, ProjectSummary)> = Vec::new();
    for entry in std::fs::read_dir(&root)
        .map_err(|error| format!("could not read {}: {error}", root.display()))?
        .filter_map(Result::ok)
    {
        let path = entry.path();
        if !path.is_dir() || !projects::is_project(&path) {
            continue;
        }
        let at = std::fs::read_to_string(path.join(TRASHED_AT))
            .ok()
            .and_then(|text| text.trim().parse::<u64>().ok())
            .unwrap_or(0);
        if at < oldest {
            let _ = std::fs::remove_dir_all(&path);
            continue;
        }
        if let Ok(summary) = summary(&path) {
            found.push((at, summary));
        }
    }
    found.sort_by(|a, b| b.0.cmp(&a.0));
    Ok(found.into_iter().map(|(_, summary)| summary).collect())
}

/// Puts a trashed project back among the others.
#[uniffi::export]
pub fn restore_project(path: String) -> Result<ProjectSummary, MobileError> {
    let folder = inside_trash(&path)?;
    let name = folder
        .file_name()
        .ok_or_else(|| MobileError::Failed("That is not a project.".to_owned()))?;
    let place = free_place(&context()?.projects, name);
    std::fs::rename(&folder, &place)
        .map_err(|error| MobileError::Failed(format!("could not restore the project: {error}")))?;
    let _ = std::fs::remove_file(place.join(TRASHED_AT));
    summary(&place)
}

/// `path` as a project folder directly in the trash, or an error.
fn inside_trash(path: &str) -> Result<PathBuf, MobileError> {
    let root = std::fs::canonicalize(trash_root()?)
        .map_err(|error| format!("could not read the trash: {error}"))?;
    let folder = std::fs::canonicalize(path)
        .map_err(|error| format!("could not find the project: {error}"))?;
    if folder.parent() == Some(root.as_path()) && projects::is_project(&folder) {
        Ok(folder)
    } else {
        Err(MobileError::Failed(
            "That is not a trashed project.".to_owned(),
        ))
    }
}

/// The folder a project's imported media are copied into, made if missing.
#[uniffi::export]
pub fn media_folder(path: String) -> Result<String, MobileError> {
    let folder = Path::new(&path).join("media");
    std::fs::create_dir_all(&folder)
        .map_err(|error| format!("could not create {}: {error}", folder.display()))?;
    Ok(folder.to_string_lossy().into_owned())
}

/// The summary of the project in `folder`.
pub(crate) fn summary(folder: &Path) -> Result<ProjectSummary, MobileError> {
    let path = folder.to_string_lossy().into_owned();
    let document = projects::read_document(&path)?
        .ok_or_else(|| MobileError::Failed(format!("{path} holds no project")))?;
    let name = document
        .get("name")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_owned();
    let timeline = active_timeline(&document);
    let video = timeline.and_then(|timeline| timeline.get("video"));
    let number = |key: &str| {
        video
            .and_then(|video| video.get(key))
            .and_then(Value::as_u64)
            .unwrap_or(0) as u32
    };
    let duration = timeline
        .and_then(|timeline| timeline.get("clips"))
        .and_then(Value::as_array)
        .map(|clips| {
            clips
                .iter()
                .map(|clip| {
                    let field = |key: &str| clip.get(key).and_then(Value::as_f64).unwrap_or(0.0);
                    field("start") + field("duration")
                })
                .fold(0.0, f64::max)
        })
        .unwrap_or(0.0);
    let manifest = projects::manifest_path(folder);
    let modified_ms = std::fs::metadata(&manifest)
        .and_then(|meta| meta.modified())
        .ok()
        .and_then(|time| time.duration_since(UNIX_EPOCH).ok())
        .map_or(0, |elapsed| elapsed.as_millis() as i64);
    let cover = folder.join(COVER);
    Ok(ProjectSummary {
        path,
        name,
        width: number("width"),
        height: number("height"),
        duration_us: micros(duration),
        modified_ms,
        size_bytes: folder_size(folder) as i64,
        cover: cover
            .is_file()
            .then(|| cover.to_string_lossy().into_owned()),
    })
}

/// The document's active timeline, or its first.
fn active_timeline(document: &Value) -> Option<&Value> {
    let timelines = document.get("timelines")?.as_array()?;
    let active = document.get("activeTimelineId").and_then(Value::as_str);
    timelines
        .iter()
        .find(|timeline| timeline.get("id").and_then(Value::as_str) == active)
        .or_else(|| timelines.first())
}

/// A new, empty folder no project has: named by the time, to the
/// millisecond, and claimed by creating it, so two projects made at once
/// never share one.
fn fresh_folder(root: &Path) -> Result<PathBuf, MobileError> {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |elapsed| elapsed.as_millis());
    for stamp in now..now + 1000 {
        let candidate = root.join(format!("p{stamp}"));
        match std::fs::create_dir(&candidate) {
            Ok(()) => return Ok(candidate),
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(error) => {
                return Err(MobileError::Failed(format!(
                    "could not create {}: {error}",
                    candidate.display()
                )));
            }
        }
    }
    Err(MobileError::Failed(
        "could not find a free project folder".to_owned(),
    ))
}

/// `path`, when it is a project folder inside the library: nothing outside
/// it is ever deleted or rewritten.
fn inside_library(path: &str) -> Result<PathBuf, MobileError> {
    let root = std::fs::canonicalize(&context()?.projects)
        .map_err(|error| format!("could not read the projects folder: {error}"))?;
    let folder = std::fs::canonicalize(path)
        .map_err(|error| format!("could not find the project: {error}"))?;
    if folder.parent() == Some(root.as_path()) && projects::is_project(&folder) {
        Ok(folder)
    } else {
        Err(MobileError::Failed("That is not a project.".to_owned()))
    }
}

/// Copies `from` into `to`, leaving out `skip`.
fn copy_tree(from: &Path, to: &Path, skip: &Path) -> std::io::Result<()> {
    std::fs::create_dir_all(to)?;
    for entry in std::fs::read_dir(from)? {
        let entry = entry?;
        let source = entry.path();
        if source == skip {
            continue;
        }
        let target = to.join(entry.file_name());
        if entry.file_type()?.is_dir() {
            copy_tree(&source, &target, skip)?;
        } else {
            std::fs::copy(&source, &target)?;
        }
    }
    Ok(())
}

/// Every string in `value` that starts with `old` starts with `new` instead.
fn repoint(value: &mut Value, old: &str, new: &str) {
    match value {
        Value::String(text) if text.starts_with(old) => {
            *text = format!("{new}{}", &text[old.len()..]);
        }
        Value::Array(items) => items.iter_mut().for_each(|item| repoint(item, old, new)),
        Value::Object(map) => map.values_mut().for_each(|item| repoint(item, old, new)),
        _ => {}
    }
}

/// Bytes under `folder`.
fn folder_size(folder: &Path) -> u64 {
    std::fs::read_dir(folder)
        .map(|entries| {
            entries
                .filter_map(Result::ok)
                .map(|entry| match entry.file_type() {
                    Ok(kind) if kind.is_dir() => folder_size(&entry.path()),
                    Ok(_) => entry.metadata().map_or(0, |meta| meta.len()),
                    Err(_) => 0,
                })
                .sum()
        })
        .unwrap_or(0)
}

/// The nearest even number at least 2: encoders want even frame sides.
pub(crate) fn even(value: u32) -> u32 {
    (value.max(2) / 2) * 2
}

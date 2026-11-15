// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer and Concat contributors
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! Pictures and shapes the timeline draws: filmstrips for clips, stills for
//! covers and pickers, waveforms for sound, and the package catalogue the
//! filter, effect, transition and voice pickers list.

use concat_effects::{Catalogue, Kind};

use crate::{MobileError, Picture, seconds};

/// A strip of `count` frames evenly spaced between `from_us` and `to_us` of
/// a video file, each `height` pixels tall, as one picture side by side.
#[uniffi::export]
pub fn filmstrip(
    path: String,
    from_us: i64,
    to_us: i64,
    count: u32,
    height: u32,
) -> Result<Picture, MobileError> {
    let to = if to_us <= 0 {
        f64::INFINITY
    } else {
        seconds(to_us)
    };
    let frame = concat_host::media::filmstrip_between(
        &path,
        seconds(from_us).max(0.0),
        to,
        count,
        height.max(2),
    )?;
    Ok(Picture::of(frame))
}

/// One frame of a video file at `at_us` (or a still image), `width` across.
#[uniffi::export]
pub fn still(path: String, at_us: i64, width: u32) -> Result<Picture, MobileError> {
    let frame = concat_host::media::still_at(&path, seconds(at_us).max(0.0), width.max(2))?;
    Ok(Picture::of(frame))
}

/// A file's waveform: the loudest sample of each of `buckets` equal slices
/// between `from_us` and `to_us`, in 0..1. `project` is the project folder,
/// where the decode is cached.
#[uniffi::export]
pub fn waveform(
    path: String,
    project: String,
    from_us: i64,
    to_us: i64,
    buckets: u32,
) -> Result<Vec<f32>, MobileError> {
    let peaks = concat_host::media::peaks(
        &path,
        None,
        (!project.is_empty()).then_some(project.as_str()),
    )?;
    let buckets = buckets.clamp(1, 4096) as usize;
    let from = seconds(from_us).max(0.0) as f32;
    let to = (seconds(to_us) as f32).max(from + 0.001);
    let step = (to - from) / buckets as f32;
    Ok((0..buckets)
        .map(|index| {
            let start = from + step * index as f32;
            let (low, high) = peaks.extremes(start, start + step);
            high.max(-low).clamp(0.0, 1.0)
        })
        .collect())
}

/// One package of the engine's catalogue.
#[derive(Debug, Clone, uniffi::Record)]
pub struct PackageInfo {
    /// The id a clip's chain names ("concat.glow").
    pub id: String,
    /// The engine's own name for it.
    pub name: String,
    /// "effect", "filter", "audio", "transition" or "generator".
    pub kind: String,
    /// The engine's category ("Stylize").
    pub category: String,
    /// The shelves it is listed on.
    pub shelves: Vec<String>,
    /// The parameter its intensity slider moves, or empty.
    pub intensity: String,
    /// Its parameters.
    pub params: Vec<ParamInfo>,
}

/// One parameter of a package.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ParamInfo {
    /// The key a chain sets.
    pub key: String,
    /// The engine's label.
    pub label: String,
    /// Lowest value.
    pub min: f64,
    /// Highest value.
    pub max: f64,
    /// Starting value.
    pub default: f64,
    /// Smallest step.
    pub step: f64,
    /// Unit, e.g. "%".
    pub unit: String,
}

/// Every package of `kind` ("effect", "filter", "audio", "transition"), in
/// the catalogue's order.
#[uniffi::export]
pub fn packages(kind: String) -> Vec<PackageInfo> {
    let wanted = match kind.as_str() {
        "effect" => Kind::Effect,
        "filter" => Kind::Filter,
        "audio" => Kind::Audio,
        "transition" => Kind::Transition,
        "generator" => Kind::Generator,
        _ => return Vec::new(),
    };
    let mut found: Vec<_> = Catalogue::builtin().of_kind(wanted).collect();
    found.sort_by_key(|package| package.manifest.effect.order);
    found
        .into_iter()
        .map(|package| {
            let meta = &package.manifest.effect;
            PackageInfo {
                id: meta.id.clone(),
                name: meta.name.clone(),
                kind: kind.clone(),
                category: meta.category.clone(),
                shelves: meta.shelves.clone(),
                intensity: meta.intensity.clone().unwrap_or_default(),
                params: package
                    .manifest
                    .params
                    .iter()
                    .map(|param| ParamInfo {
                        key: param.key.clone(),
                        label: param.label.clone(),
                        min: param.min,
                        max: param.max,
                        default: param.default,
                        step: param.step,
                        unit: param.unit.clone(),
                    })
                    .collect(),
            }
        })
        .collect()
}

/// One font face titles can be set in.
#[derive(Debug, Clone, uniffi::Record)]
pub struct FontFace {
    /// The family name a title's style names.
    pub family: String,
    /// A file of the face (its regular weight where there is one), for the
    /// font picker to draw the name in.
    pub path: String,
}

/// The faces [`font_faces`] found, once worked out: the lock is held while
/// they are, so two pickers opening together do not write the files at once.
static FACES: std::sync::Mutex<Option<Vec<FontFace>>> = std::sync::Mutex::new(None);

/// The faces the engine sets titles in, each family once, in the engine's
/// order. The files are written to the cache the first time, and the list
/// is worked out once a process.
///
/// A file is never rewritten in place: its names are read through a memory
/// map, as the picker's typefaces read it, and a file cut short under a map
/// ends the process (SIGBUS) - which two pickers opening at once did, one
/// rewriting a face the other was reading, once the engine's faces changed
/// and the cached files no longer matched. A new file is written beside the
/// old and renamed over it, so a map of the old one reads on undisturbed.
#[uniffi::export]
pub fn font_faces() -> Result<Vec<FontFace>, MobileError> {
    let mut found = FACES
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if let Some(faces) = found.as_ref() {
        return Ok(faces.clone());
    }
    let folder = crate::context()?.cache.join("fonts");
    std::fs::create_dir_all(&folder)
        .map_err(|error| format!("could not create {}: {error}", folder.display()))?;
    let mut faces: Vec<FontFace> = Vec::new();
    for (index, bytes) in concat_text::BASE_FONTS.iter().enumerate() {
        let file = folder.join(format!("face-{index}.ttf"));
        let fresh = std::fs::metadata(&file).is_ok_and(|meta| meta.len() == bytes.len() as u64);
        if !fresh {
            let part = folder.join(format!("face-{index}.ttf.part"));
            std::fs::write(&part, bytes)
                .and_then(|()| std::fs::rename(&part, &file))
                .map_err(|error| format!("could not write {}: {error}", file.display()))?;
        }
        for family in concat_text::families_in(&file) {
            if !faces.iter().any(|face| face.family == family) {
                faces.push(FontFace {
                    family,
                    path: file.to_string_lossy().into_owned(),
                });
            }
        }
    }
    *found = Some(faces.clone());
    Ok(faces)
}

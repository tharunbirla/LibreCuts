// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! Scrub copies: a small copy of a video whose every frame is a picture of
//! its own, written once into the project's cache and read while the
//! playhead is dragged.
//!
//! A drag asks for instants all over a file, forwards and back. The file
//! holds a whole picture only every second or so, so each instant decodes
//! from the last whole picture forward, at the file's full size: a phone
//! keeps up with playing it, not with being dragged across it. Its scrub
//! copy answers each instant with one seek and one small decode. Every
//! video gets one, written on the scheduler's proxy lane ahead of the
//! proxies; the playhead at rest reads the file itself, playback the proxy.
//! The copy is named after the file's path, size and modification time,
//! like a proxy, so a file replaced on disk gets a fresh one.

use std::collections::HashSet;
use std::hash::{Hash, Hasher};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

use concat_core::time::{FrameRate, Rational};
use concat_media::decode::{DecodeOptions, Decoder, FrameSource};
use concat_media::{ColorRange, FrameSink, ScrubWriter};

/// The copy's long side, never more than the file's: coarse, and shown
/// only while it moves under a finger. A minute of it is about ten
/// megabytes.
const LONG_SIDE: u32 = 480;
/// The copy keeps at most this many frames a second: a drag lands between
/// them, and the playhead at rest shows the file's own frame.
const MOST_FPS: i64 = 15;
/// Each frame's JPEG quantiser, 2 (best) to 31 (worst).
const QUALITY: u8 = 6;

/// The copy's size for a `width` by `height` file: the long side
/// [`LONG_SIDE`], or the file's own where that is smaller, the aspect
/// kept, both sides even.
pub fn size_for(width: u32, height: u32) -> (u32, u32) {
    let (width, height) = (width.max(2), height.max(2));
    let long = width.max(height);
    let scale = f64::from(LONG_SIDE.min(long)) / f64::from(long);
    let side = |px: u32| (((f64::from(px) * scale).round() as u32) & !1).max(2);
    (side(width), side(height))
}

/// The copy's frame rate for a file at `rate`: the file's own, or
/// [`MOST_FPS`] for a faster one.
pub fn rate_for(rate: FrameRate) -> FrameRate {
    if rate.fps() > Rational::from_int(MOST_FPS) {
        FrameRate::new(Rational::from_int(MOST_FPS))
    } else {
        rate
    }
}

/// Where the scrub copy of `media` lives under `project`, named by the
/// file's path, size and modification time and by the range it is read
/// as. None for a file that cannot be stat'ed.
pub fn path_for(project: &Path, media: &str, range: Option<ColorRange>) -> Option<PathBuf> {
    let meta = std::fs::metadata(media).ok()?;
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    media.hash(&mut hasher);
    meta.len().hash(&mut hasher);
    if let Some(range) = range {
        range.name().hash(&mut hasher);
    }
    if let Ok(modified) = meta.modified()
        && let Ok(since) = modified.duration_since(std::time::UNIX_EPOCH)
    {
        since.as_secs().hash(&mut hasher);
    }
    Some(
        project
            .join("cache")
            .join("scrub")
            .join(format!("{:016x}.mov", hasher.finish())),
    )
}

/// Sees to it that the video `media` - `width` by `height`, read as
/// `range` - has a scrub copy: an existing one is adopted into the reader
/// pool at once; otherwise one is queued on the proxy lane and adopted when
/// written. HDR files get none: the copy is eight-bit, and the light of
/// one would change under the finger. Asking again while it is being
/// written is free. Returns whether the file has, or will have, a copy.
pub fn ensure(
    project: &Path,
    media: &str,
    width: u32,
    height: u32,
    range: Option<ColorRange>,
    hdr: bool,
) -> bool {
    if hdr || width == 0 || height == 0 {
        return false;
    }
    let Some(target) = path_for(project, media, range) else {
        return false;
    };
    let pool = crate::scheduler().pool();
    if target.is_file() {
        if pool.scrub_of(Path::new(media)).as_deref() != Some(target.as_path()) {
            pool.adopt_scrub(Path::new(media), target);
        }
        return true;
    }
    {
        let mut writing = writing()
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if !writing.insert(target.clone()) {
            return true;
        }
    }
    let source = media.to_owned();
    let size = size_for(width, height);
    crate::scheduler().submit(concat_media::Priority::Proxy, move || {
        let started = std::time::Instant::now();
        match write(&source, &target, size, range) {
            Ok(frames) => {
                crate::scheduler()
                    .pool()
                    .adopt_scrub(Path::new(&source), target.clone());
                log::info!(
                    "{source}: scrub copy of {frames} frames written in {:.1} s",
                    started.elapsed().as_secs_f64()
                );
            }
            Err(error) => log::warn!("{source}: no scrub copy: {error}"),
        }
        writing()
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .remove(&target);
    });
    true
}

/// The copies being written now, by target, so one is never queued twice
/// and a sweep leaves it for its writer to finish.
fn writing() -> &'static Mutex<HashSet<PathBuf>> {
    static WRITING: OnceLock<Mutex<HashSet<PathBuf>>> = OnceLock::new();
    WRITING.get_or_init(|| Mutex::new(HashSet::new()))
}

/// Removes the copies under `project` that `keep` does not name: those of
/// media no longer in the project, or read as a range they no longer are.
/// One being written is left for its writer. Returns how many files went.
pub fn sweep(project: &Path, keep: &HashSet<PathBuf>) -> usize {
    let dir = project.join("cache").join("scrub");
    let Ok(entries) = std::fs::read_dir(&dir) else {
        return 0;
    };
    let writing = writing()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let mut removed = 0;
    for entry in entries.flatten() {
        let path = entry.path();
        if path.extension().is_some_and(|extension| extension == "mov")
            && !keep.contains(&path)
            && !writing.contains(&path)
            && std::fs::remove_file(&path).is_ok()
        {
            removed += 1;
        }
    }
    removed
}

/// Writes the scrub copy of `source`, read as `range`, at `size` to
/// `target`, through a temporary name moved into place only when whole.
/// Each slot of the copy's rate takes the file's frame nearest it, so a
/// sixty-frame file comes out at fifteen and a variable-rate one at an
/// even pace. In software: on a phone on every core, as its pictures are too
/// small to be given one of the chip's few decoders (see
/// `concat_media::hardware`); elsewhere on two threads, as a proxy is, so
/// the machine stays usable. Returns the frames written.
pub fn write(
    source: &str,
    target: &Path,
    (width, height): (u32, u32),
    range: Option<ColorRange>,
) -> Result<u64, String> {
    let info = concat_media::probe(source).map_err(|error| error.to_string())?;
    let rate = info
        .video
        .as_ref()
        .map(|video| video.frame_rate)
        .ok_or_else(|| "no video stream".to_owned())?;
    let copy_rate = rate_for(rate);
    if let Some(folder) = target.parent() {
        std::fs::create_dir_all(folder)
            .map_err(|error| format!("{}: {error}", folder.display()))?;
    }
    let partial = target.with_extension("part.mov");
    let mut options = DecodeOptions::default()
        .scaled_to(width, height)
        .in_range(range);
    if !cfg!(target_os = "android") {
        options = options.in_software().threaded(2);
    }
    let mut decoder = Decoder::open(source, &options).map_err(|error| error.to_string())?;
    let mut writer = ScrubWriter::create(&partial, width, height, copy_rate, QUALITY)
        .map_err(|error| error.to_string())?;
    let result = (|| {
        let slot = copy_rate.frame_duration();
        let half = slot / Rational::from_int(2);
        let mut decoded: i64 = 0;
        while let Some(frame) = decoder.next_frame().map_err(|error| error.to_string())? {
            let at = decoder
                .position()
                .unwrap_or_else(|| rate.time_of_frame(decoded));
            decoded += 1;
            // Every slot up to this frame's instant, and half a slot past
            // it, shows this frame: the nearest one to each.
            while copy_rate.time_of_frame(writer.written() as i64) <= at + half {
                writer
                    .write_frame(&frame)
                    .map_err(|error| error.to_string())?;
            }
        }
        writer.finish().map_err(|error| error.to_string())?;
        Ok(writer.written())
    })();
    drop(writer);
    drop(decoder);
    match result {
        Ok(frames) => std::fs::rename(&partial, target)
            .map(|()| frames)
            .map_err(|error| format!("{}: {error}", target.display())),
        Err(error) => {
            let _ = std::fs::remove_file(&partial);
            Err(error)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use concat_core::frame::Frame;
    use concat_media::{EncodeOptions, Encoder};

    #[test]
    fn a_scrub_copy_is_small_and_never_larger_than_the_file() {
        assert_eq!(size_for(3840, 2160), (480, 270));
        assert_eq!(size_for(1080, 1920), (270, 480));
        assert_eq!(size_for(320, 180), (320, 180));
        let (w, h) = size_for(1921, 1081);
        assert!(w % 2 == 0 && h % 2 == 0);
    }

    #[test]
    fn a_fast_file_is_copied_at_fifteen() {
        let fifteen = FrameRate::from_int(15);
        assert_eq!(rate_for(FrameRate::SIXTY), fifteen);
        assert_eq!(rate_for(FrameRate::NTSC_30), fifteen);
        assert_eq!(rate_for(FrameRate::FILM), fifteen);
        assert_eq!(rate_for(FrameRate::from_int(12)), FrameRate::from_int(12));
    }

    /// A sixty-frame file's copy is fifteen frames a second, each the file's
    /// frame nearest that instant, and is adopted and read by a scrubbing
    /// request while a paused one still reads the file.
    #[test]
    fn a_written_scrub_copy_is_read_while_scrubbing_only() {
        let dir = std::env::temp_dir().join(format!("concat-scrub-copy-{}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("temp dir");
        let media = dir.join("fast.mp4");
        {
            let mut encoder = Encoder::create(
                &media,
                1280,
                720,
                FrameRate::SIXTY,
                &EncodeOptions {
                    preset: "ultrafast".to_owned(),
                    hardware: false,
                    ..EncodeOptions::default()
                },
            )
            .expect("encodes");
            for index in 0..60u8 {
                let mut frame = Frame::black(1280, 720);
                frame.fill([index * 4, 20, 200, 255]);
                encoder.write_frame(&frame).expect("writes");
            }
            encoder.finish().expect("finishes");
        }
        let project = dir.join("project");
        let source = media.to_string_lossy().into_owned();
        assert!(ensure(&project, &source, 1280, 720, None, false));
        crate::scheduler().drain();
        let copy = path_for(&project, &source, None).expect("a path");
        let info = concat_media::probe(&copy).expect("the copy was written");
        let video = info.video.expect("has pictures");
        assert_eq!((video.width, video.height), (480, 270));
        assert_eq!(video.frame_rate, FrameRate::from_int(15));
        let pool = crate::scheduler().pool();
        assert_eq!(
            pool.scrub_of(Path::new(&source)).as_deref(),
            Some(copy.as_path())
        );
        // Two thirds of a second in, the file's frame 40 (red 160); the copy's
        // frame there is the file's nearest, two frames earlier.
        let time = FrameRate::SIXTY.time_of_frame(40);
        let scrubbing = pool
            .frame(&concat_media::FrameRequest::new(&source, time, 320, 180).for_scrub(true))
            .expect("reads the copy");
        let red = scrubbing.pixels()[0];
        assert!(
            red.abs_diff(160) < 16,
            "the copy's frame came back red {red}"
        );
        let paused = pool
            .frame(&concat_media::FrameRequest::new(&source, time, 320, 180))
            .expect("reads the file");
        assert_eq!((paused.width(), paused.height()), (320, 180));
        // Asked again, it is adopted, not written again; an HDR file gets none.
        assert!(ensure(&project, &source, 1280, 720, None, false));
        assert!(!ensure(&project, &source, 1280, 720, None, true));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_sweep_keeps_what_is_named() {
        let scratch =
            std::env::temp_dir().join(format!("concat-scrub-sweep-{}", std::process::id()));
        let dir = scratch.join("cache").join("scrub");
        std::fs::create_dir_all(&dir).expect("a scrub folder");
        let kept = dir.join("1111111111111111.mov");
        let stale = dir.join("2222222222222222.mov");
        for file in [&kept, &stale] {
            std::fs::write(file, b"x").expect("writes");
        }
        let keep: HashSet<PathBuf> = [kept.clone()].into_iter().collect();
        assert_eq!(sweep(&scratch, &keep), 1);
        assert!(kept.is_file() && !stale.is_file());
        let _ = std::fs::remove_dir_all(&scratch);
    }
}

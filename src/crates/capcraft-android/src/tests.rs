// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer and Concat contributors
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! Host tests of the bridge: the library, the rows and the operations that
//! need no media file.

use crate::lanes::RowKind;
use crate::library::{
    create_project, delete_project, duplicate_project, list_projects, list_trash, rename_project,
    restore_project, trash_project,
};
use crate::pauses::PausesRemoved;
use crate::session::MobileSession;

/// The engine, started once for the test process in a scratch folder.
fn start() {
    let root = std::env::temp_dir().join(format!("capcraft-tests-{}", std::process::id()));
    let files = root.join("files");
    let cache = root.join("cache");
    crate::init(
        files.to_string_lossy().into_owned(),
        cache.to_string_lossy().into_owned(),
    )
    .expect("the engine starts");
}

#[test]
fn a_project_is_made_listed_renamed_and_deleted() {
    start();
    let made = create_project("Trip".to_owned(), 1080, 1920, 30).expect("made");
    assert_eq!((made.width, made.height), (1080, 1920));
    assert!(
        list_projects()
            .expect("listed")
            .iter()
            .any(|p| p.path == made.path)
    );
    let renamed = rename_project(made.path.clone(), "Holiday".to_owned()).expect("renamed");
    assert_eq!(renamed.name, "Holiday");
    delete_project(made.path.clone()).expect("deleted");
    assert!(
        !list_projects()
            .expect("listed")
            .iter()
            .any(|p| p.path == made.path)
    );
}

#[test]
fn titles_go_on_text_rows_above_the_main_track() {
    start();
    let made = create_project("Words".to_owned(), 1080, 1920, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    let view = session
        .add_text(0, 3_000_000, String::new())
        .expect("a title lands");
    let text_rows: Vec<_> = view
        .rows
        .iter()
        .filter(|row| row.kind == RowKind::Text)
        .collect();
    assert_eq!(text_rows.len(), 1);
    assert_eq!(text_rows[0].clips.len(), 1);
    assert_eq!(text_rows[0].clips[0].duration_us, 3_000_000);
    // A second title over the first needs a row of its own; one after it
    // shares the row.
    let view = session
        .add_text(1_000_000, 1_000_000, String::new())
        .expect("overlapping");
    assert_eq!(
        view.rows
            .iter()
            .filter(|row| row.kind == RowKind::Text)
            .count(),
        2
    );
    let view = session
        .add_text(5_000_000, 1_000_000, String::new())
        .expect("after");
    assert_eq!(
        view.rows
            .iter()
            .filter(|row| row.kind == RowKind::Text)
            .count(),
        2
    );
    // One undo step each.
    let view = session.undo();
    assert_eq!(
        view.rows
            .iter()
            .filter(|row| row.kind == RowKind::Text)
            .map(|row| row.clips.len())
            .sum::<usize>(),
        2
    );
    let view = session.undo();
    assert_eq!(
        view.rows
            .iter()
            .filter(|row| row.kind == RowKind::Text)
            .count(),
        1
    );
    delete_project(made.path).expect("deleted");
}

#[test]
fn the_main_row_is_always_listed_first() {
    start();
    let made = create_project("Empty".to_owned(), 1920, 1080, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    let view = session.view();
    assert_eq!(view.rows.first().map(|row| row.kind), Some(RowKind::Main));
    assert_eq!(view.duration_us, 0);
    delete_project(made.path).expect("deleted");
}

/// One of our own test clips (`CAPCRAFT_TESTMEDIA` names their folder), or
/// None when the folder is not given: those tests then pass without running.
fn media(name: &str) -> Option<String> {
    let folder = std::env::var("CAPCRAFT_TESTMEDIA").ok()?;
    let path = std::path::Path::new(&folder).join(name);
    path.is_file().then(|| path.to_string_lossy().into_owned())
}

/// The main row's clips as (start, duration) in microseconds.
fn main_spans(view: &crate::session::TimelineView) -> Vec<(i64, i64)> {
    view.rows
        .iter()
        .find(|row| row.kind == RowKind::Main)
        .map(|row| {
            row.clips
                .iter()
                .map(|c| (c.start_us, c.duration_us))
                .collect()
        })
        .unwrap_or_default()
}

/// Whether the main row's clips run end to end from zero, to a frame.
fn gapless(view: &crate::session::TimelineView) -> bool {
    let mut at = 0;
    main_spans(view).into_iter().all(|(start, duration)| {
        let fits = (start - at).abs() < 40_000;
        at = start + duration;
        fits
    })
}

#[test]
fn a_first_clip_gives_the_project_its_frame_and_cuts_close_up() {
    start();
    let Some(bars) = media("cc-a-bars-16x9.mp4") else {
        return;
    };
    let made = create_project("Cuts".to_owned(), 1080, 1920, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    let item = session.import(bars).expect("imports");
    let view = session
        .add_to_main(vec![item.id.clone()], 0)
        .expect("placed");
    assert_eq!(
        (view.width, view.height),
        (1920, 1080),
        "the frame follows the first picture"
    );
    assert_eq!(main_spans(&view).len(), 1);
    // split, then delete the head: the tail closes up to zero
    let clip = view.rows[0].clips[0].id.clone();
    let view = session.split(vec![clip.clone()], 4_000_000).expect("split");
    assert_eq!(main_spans(&view).len(), 2);
    let view = session.delete(vec![clip]).expect("deleted");
    assert!(gapless(&view), "{:?}", main_spans(&view));
    assert_eq!(main_spans(&view)[0].0, 0);
    delete_project(made.path).expect("deleted");
}

/// The timeline's captions written as SubRip (issue #10): with none, the
/// busiest row of titles stands in; with captions, they alone are written,
/// in the order they play, and the SubRip reader reads them back as they
/// were. A timeline with no titles has nothing to write.
#[test]
fn captions_are_written_as_subrip() {
    start();
    let made = create_project("Subtitles".to_owned(), 1080, 1920, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    assert!(session.captions_srt().is_err(), "nothing to write yet");
    session
        .add_text(
            20_000_000,
            2_000_000,
            r#"{"content":"A title card"}"#.to_owned(),
        )
        .expect("a title");
    let alone =
        concat_project::srt::parse(&session.captions_srt().expect("the title")).expect("SubRip");
    let words: Vec<_> = alone.iter().map(|cue| cue.text.as_str()).collect();
    assert_eq!(words, ["A title card"]);

    session
        .enter_captions("First line\nSecond line here".to_owned(), 1_000_000)
        .expect("captions");
    let written = session.captions_srt().expect("written");
    assert!(written.starts_with("1\n00:00:01,000 --> "), "{written}");
    let cues = concat_project::srt::parse(&written).expect("SubRip again");
    let words: Vec<_> = cues.iter().map(|cue| cue.text.as_str()).collect();
    assert_eq!(words, ["First line", "Second line here"], "{written}");
    assert!(
        cues[0].end <= cues[1].start && cues[1].start < cues[1].end,
        "{written}"
    );
    delete_project(made.path).expect("deleted");
}

/// The mean over every pixel's colour channels of `channel(a, b)`.
fn mean_channels(a: &crate::Picture, b: &crate::Picture, channel: impl Fn(u8, u8) -> i32) -> f64 {
    let pixels = a.rgba.chunks_exact(4).zip(b.rgba.chunks_exact(4));
    let total: i64 = pixels
        .map(|(a, b)| (0..3).map(|c| i64::from(channel(a[c], b[c]))).sum::<i64>())
        .sum();
    total as f64 / (a.rgba.len() / 4 * 3) as f64
}

/// A `.cube` table from the phone becomes a filter like the built-ins
/// (issue #11): listed under "Imported" by its file's name and drawn over
/// the picture; a file that is not a table is refused; and once removed it
/// is unlisted, and the picture draws without it.
#[test]
fn an_imported_cube_is_a_filter_until_it_is_removed() {
    start();
    let Some(bars) = media("cc-a-bars-16x9.mp4") else {
        return;
    };
    let made = create_project("Looks".to_owned(), 1920, 1080, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    let item = session.import(bars).expect("imports");
    session.add_to_main(vec![item.id], 0).expect("placed");
    let dir = std::env::temp_dir().join(format!("capcraft-looks-{}", std::process::id()));
    std::fs::create_dir_all(&dir).expect("a scratch folder");
    // every channel turned over: red changes fastest, then green, then blue
    let mut table = String::from("TITLE \"Negative\"\nLUT_3D_SIZE 2\n");
    for index in 0..8 {
        let [r, g, b] = [index & 1, (index >> 1) & 1, (index >> 2) & 1].map(|bit| 1 - bit);
        table.push_str(&format!("{r} {g} {b}\n"));
    }
    let path = dir.join("Negative Film.cube");
    std::fs::write(&path, table).expect("the table");
    let id = crate::looks::import_look(path.to_string_lossy().into_owned()).expect("imported");
    assert_eq!(id, "user.negative-film");
    let listed = crate::frames::packages("filter".to_owned());
    let look = listed.iter().find(|p| p.id == id).expect("listed");
    assert_eq!(
        (look.name.as_str(), look.category.as_str()),
        ("Negative Film", "Imported")
    );
    let at = 1_000_000;
    let plain = session
        .frame(at, 64, 36, crate::session::Motion::Still)
        .expect("a frame");
    let graded = session
        .frame_with(at, id.clone(), 64, 36)
        .expect("a frame with the look");
    let changed = mean_channels(&plain, &graded, |a, b| (i32::from(a) - i32::from(b)).abs());
    let turned = mean_channels(&plain, &graded, |a, b| {
        (i32::from(a) + i32::from(b) - 255).abs()
    });
    assert!(changed > 40.0, "the look changed the picture: {changed}");
    assert!(turned < 8.0, "every channel turned over: {turned}");

    let junk = dir.join("notes.cube");
    std::fs::write(&junk, "not a table").expect("a file");
    assert!(crate::looks::import_look(junk.to_string_lossy().into_owned()).is_err());
    let listed = crate::frames::packages("filter".to_owned());
    assert!(!listed.iter().any(|p| p.id == "user.notes"));

    crate::looks::remove_look(id.clone()).expect("removed");
    let listed = crate::frames::packages("filter".to_owned());
    assert!(!listed.iter().any(|p| p.id == id));
    let without = session
        .frame_with(at, id.clone(), 64, 36)
        .expect("a frame without the look");
    let left = mean_channels(&plain, &without, |a, b| (i32::from(a) - i32::from(b)).abs());
    assert!(left < 2.0, "the picture draws without it: {left}");
    assert!(crate::looks::remove_look("concat.noir".to_owned()).is_err());
    let _ = std::fs::remove_dir_all(&dir);
    delete_project(made.path).expect("deleted");
}

/// A song carries its album cover as a one-frame picture stream, and a
/// video brought in for its sound has a picture too: both come in as sound,
/// and land on an audio row as sound clips (issue #7).
#[test]
fn songs_with_covers_and_videos_taken_for_sound_are_sound() {
    start();
    let (Some(mp3), Some(m4a), Some(bars), Some(still)) = (
        media("cc-r-song-cover.mp3"),
        media("cc-s-song-cover.m4a"),
        media("cc-a-bars-16x9.mp4"),
        media("cc-d-still.jpg"),
    ) else {
        return;
    };
    let made = create_project("Songs".to_owned(), 1080, 1920, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    let songs = [mp3, m4a].map(|song| session.import(song).expect("imports"));
    let taken = session.import_sound(bars).expect("its sound imports");
    for item in songs.iter().chain([&taken]) {
        assert_eq!(item.kind, "audio", "{}", item.id);
        assert_eq!((item.width, item.height), (0, 0), "{}", item.id);
        assert!(item.has_audio, "{}", item.id);
    }
    assert!(
        session.import_sound(still).is_err(),
        "a picture has no sound to take"
    );
    let mut view = session.view();
    for item in songs.iter().chain([&taken]) {
        view = session.add_audio(item.id.clone(), 0).expect("placed");
    }
    let clips: Vec<_> = view
        .rows
        .iter()
        .flat_map(|row| row.clips.iter().map(move |clip| (row.kind, clip)))
        .collect();
    assert_eq!(clips.len(), 3);
    for (row, clip) in clips {
        assert_eq!(
            (row, clip.kind.as_str()),
            (RowKind::Audio, "audio"),
            "{}",
            clip.name
        );
    }
    delete_project(made.path).expect("deleted");
}

#[test]
fn a_faster_clip_leaves_no_gap_on_the_main_track() {
    start();
    let (Some(a), Some(b)) = (media("cc-a-bars-16x9.mp4"), media("cc-c-gradient-9x16.mp4")) else {
        return;
    };
    let made = create_project("Speed".to_owned(), 1080, 1920, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    let a = session.import(a).expect("imports").id;
    let b = session.import(b).expect("imports").id;
    let view = session.add_to_main(vec![a, b], 0).expect("placed");
    let first = view.rows[0].clips[0].id.clone();
    let view = session
        .apply(
            format!(r#"{{"op":"setClipSpeed","clipId":"{first}","speed":2.0}}"#),
            None,
        )
        .expect("faster");
    assert!(gapless(&view), "{:?}", main_spans(&view));
    // and slower pushes the rest along
    let view = session
        .apply(
            format!(r#"{{"op":"setClipSpeed","clipId":"{first}","speed":0.5}}"#),
            None,
        )
        .expect("slower");
    assert!(gapless(&view), "{:?}", main_spans(&view));
    // one undo step each
    let view = session.undo();
    assert!(gapless(&view));
    delete_project(made.path).expect("deleted");
}

#[test]
fn a_clip_moves_to_an_overlay_row_and_back() {
    start();
    let (Some(a), Some(b)) = (media("cc-a-bars-16x9.mp4"), media("cc-b-testsrc-9x16.mp4")) else {
        return;
    };
    let made = create_project("Overlay".to_owned(), 1080, 1920, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    let a = session.import(a).expect("imports").id;
    let b = session.import(b).expect("imports").id;
    let view = session.add_to_main(vec![a, b], 0).expect("placed");
    let second = view.rows[0].clips[1].id.clone();
    let view = session.to_overlay(second.clone()).expect("to overlay");
    assert_eq!(main_spans(&view).len(), 1);
    assert!(view.rows.iter().any(|row| row.kind == RowKind::Overlay));
    let view = session.to_main(second).expect("to main");
    assert_eq!(main_spans(&view).len(), 2);
    assert!(gapless(&view));
    assert!(!view.rows.iter().any(|row| row.kind == RowKind::Overlay));
    delete_project(made.path).expect("deleted");
}

#[test]
fn a_blurred_background_lies_under_every_lane() {
    start();
    let Some(bars) = media("cc-a-bars-16x9.mp4") else {
        return;
    };
    let made = create_project("Background".to_owned(), 1080, 1920, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    let item = session.import(bars).expect("imports");
    session.add_to_main(vec![item.id], 0).expect("placed");
    session.set_canvas(1080, 1920).expect("portrait");
    session
        .set_background(r#"{"kind":"blur","level":2}"#.to_owned())
        .expect("blur");
    let clips = session
        .with_session(|s| Ok(crate::session::monitor_clips(s, &s.settings())))
        .expect("flattened");
    assert_eq!(clips.len(), 2, "the clip and its blurred copy");
    let under = clips
        .iter()
        .find(|clip| clip.track == 0)
        .expect("a copy on lane 0");
    assert!(
        under
            .effects
            .iter()
            .any(|link| link.id == "concat.gaussian-blur")
    );
    assert!(under.scale > 1.5, "it fills the frame: {}", under.scale);
    assert!(
        clips.iter().any(|clip| clip.track == 1),
        "the clip itself moved up"
    );
    // and off again, as an edit that undoes
    let view = session.undo();
    assert!(view.can_redo);
    delete_project(made.path).expect("deleted");
}

#[test]
fn a_caption_file_becomes_titles_in_one_step() {
    start();
    let made = create_project("Subtitles".to_owned(), 1080, 1920, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    let srt =
        "1\n00:00:00,500 --> 00:00:02,000\nFirst\n\n2\n00:00:02,000 --> 00:00:04,000\nSecond\n";
    let view = session.import_captions(srt.to_owned()).expect("placed");
    let rows: Vec<_> = view
        .rows
        .iter()
        .filter(|row| row.kind == RowKind::Text)
        .collect();
    assert_eq!(rows.len(), 1, "the cues share one row");
    let words: Vec<_> = rows[0]
        .clips
        .iter()
        .map(|clip| clip.text.as_str())
        .collect();
    assert_eq!(words, ["First", "Second"]);
    assert_eq!(rows[0].clips[0].start_us, 500_000);
    assert!(session.import_captions("nothing here".to_owned()).is_err());
    let view = session.undo();
    assert!(view.rows.iter().all(|row| row.kind != RowKind::Text));
    delete_project(made.path).expect("deleted");
}

#[test]
fn a_gif_holds_every_picture_at_the_asked_size() {
    start();
    let Some(gradient) = media("cc-c-gradient-9x16.mp4") else {
        return;
    };
    let made = create_project("Gif".to_owned(), 1080, 1920, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    let item = session.import(gradient).expect("imports");
    let view = session.add_to_main(vec![item.id], 0).expect("placed");
    let output = std::path::Path::new(&made.path).join("test.gif");
    let settings = crate::gif::GifSettings {
        output: output.to_string_lossy().into_owned(),
        short_side: 120,
        fps: 2,
    };
    let (width, height) = crate::gif::gif_size(view.width, view.height, settings.short_side);
    crate::gif::write(
        &session,
        &settings,
        width,
        height,
        view.duration_us,
        &|_| true,
    )
    .expect("written");
    let mut options = gif::DecodeOptions::new();
    options.set_color_output(gif::ColorOutput::RGBA);
    let mut decoder = options
        .read_info(std::fs::File::open(&output).expect("there"))
        .expect("a gif");
    assert_eq!(
        (u32::from(decoder.width()), u32::from(decoder.height())),
        (width, height)
    );
    let mut pictures = 0;
    while decoder.read_next_frame().expect("readable").is_some() {
        pictures += 1;
    }
    // eight seconds at two a second
    assert_eq!(pictures, 16);
    delete_project(made.path).expect("deleted");
}

/// How far the picture jumps from frame to frame, on average, in pixels.
fn jitter(path: &str) -> (f64, usize) {
    use concat_media::FrameSource;
    let mut decoder =
        concat_media::Decoder::open(path, &concat_media::DecodeOptions::default().in_software())
            .expect("opens");
    let mut last = None;
    let (mut total, mut count) = (0.0, 0usize);
    while let Some(frame) = decoder.next_frame().expect("reads") {
        let small = crate::stabilize::shrink(&frame);
        if let Some(before) = &last {
            let (dx, dy) = crate::stabilize::shift(before, &small);
            total += (dx * dx + dy * dy).sqrt();
        }
        last = Some(small);
        count += 1;
    }
    (total / count.max(2) as f64, count)
}

#[test]
fn a_shaky_clip_comes_out_steadier() {
    start();
    let Some(shaky) = media("cc-j-shaky-16x9.mp4") else {
        return;
    };
    let dir = std::env::temp_dir().join(format!("capcraft-steady-{}", std::process::id()));
    std::fs::create_dir_all(&dir).expect("made");
    let target = dir.join("steady.mp4");
    crate::stabilize::steady(&shaky, &target, 2, &|_| true).expect("steadied");
    let (before, frames_before) = jitter(&shaky);
    let steadied = target.to_string_lossy().into_owned();
    let (after, frames_after) = jitter(&steadied);
    assert_eq!(frames_after, frames_before, "every frame kept");
    assert!(
        after < before * 0.5,
        "jitter {before:.1} px became {after:.1} px"
    );
    let info = concat_media::probe(&target).expect("probes");
    assert!(info.audio.is_some(), "the sound came along");
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn new_captions_can_replace_the_old_in_one_step() {
    start();
    let made = create_project("Recaption".to_owned(), 1080, 1920, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    session
        .add_text(8_000_000, 1_000_000, String::new())
        .expect("a title of the user's own");
    let first = [(0.0, 1.0, "One".to_owned()), (1.0, 2.0, "Two".to_owned())];
    session.place_captions(&first, false).expect("placed");
    assert_eq!(session.caption_count(), 2);
    let second = [(0.0, 3.0, "Three".to_owned())];
    let view = session.place_captions(&second, true).expect("replaced");
    assert_eq!(session.caption_count(), 1);
    let words: Vec<_> = view
        .rows
        .iter()
        .flat_map(|row| row.clips.iter())
        .filter(|clip| clip.kind == "text")
        .map(|clip| clip.text.clone())
        .collect();
    assert!(words.contains(&"Three".to_owned()));
    assert!(!words.contains(&"One".to_owned()), "{words:?}");
    assert_eq!(words.len(), 2, "the user's own title stays: {words:?}");
    // one step back brings the first captions back
    session.undo();
    assert_eq!(session.caption_count(), 2);
    delete_project(made.path).expect("deleted");
}

#[test]
fn a_duplicate_keeps_the_copies_its_clips_show() {
    start();
    let made = create_project("Copies".to_owned(), 1080, 1920, 30).expect("made");
    let cache = std::path::Path::new(&made.path).join("cache");
    std::fs::create_dir_all(&cache).expect("cache");
    std::fs::write(cache.join("stabilize-0123-2.mp4"), b"steadied").expect("copy");
    std::fs::write(cache.join("reverse-4567-0-1000.mp4"), b"reversed").expect("copy");
    std::fs::write(cache.join("peaks-89ab"), b"made again on use").expect("preview");
    let twin = duplicate_project(made.path.clone(), "Copies 2".to_owned()).expect("duplicated");
    let twin_cache = std::path::Path::new(&twin.path).join("cache");
    assert!(twin_cache.join("stabilize-0123-2.mp4").is_file());
    assert!(twin_cache.join("reverse-4567-0-1000.mp4").is_file());
    assert!(
        !twin_cache.join("peaks-89ab").exists(),
        "previews are not copied"
    );
    delete_project(twin.path).expect("deleted");
    delete_project(made.path).expect("deleted");
}

#[test]
fn a_cut_range_comes_out_of_the_main_track_in_one_step() {
    start();
    let (Some(a), Some(b)) = (media("cc-a-bars-16x9.mp4"), media("cc-c-gradient-9x16.mp4")) else {
        return;
    };
    let made = create_project("Cut range".to_owned(), 1080, 1920, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    let a = session.import(a).expect("imports").id;
    let b = session.import(b).expect("imports").id;
    let view = session.add_to_main(vec![a, b], 0).expect("placed");
    let before = view.duration_us;
    let first_end = main_spans(&view)[0].1;
    // two seconds across the cut between the clips: both are split, the middle goes
    let view = session
        .cut_range(first_end - 1_000_000, first_end + 1_000_000)
        .expect("cut");
    assert!(gapless(&view), "{:?}", main_spans(&view));
    assert!(
        (before - view.duration_us - 2_000_000).abs() < 40_000,
        "{} -> {}",
        before,
        view.duration_us
    );
    assert_eq!(main_spans(&view).len(), 2, "{:?}", main_spans(&view));
    // and one undo brings it all back
    let view = session.undo();
    assert!((view.duration_us - before).abs() < 40_000);
    delete_project(made.path).expect("deleted");
}

/// The names of the clips on the sound rows.
fn sound_names(view: &crate::session::TimelineView) -> Vec<String> {
    view.rows
        .iter()
        .filter(|row| row.kind == RowKind::Audio)
        .flat_map(|row| row.clips.iter().map(|clip| clip.name.clone()))
        .collect()
}

#[test]
fn extracted_sounds_are_numbered_and_can_go_back() {
    start();
    let (Some(a), Some(b)) = (
        media("cc-b-testsrc-9x16.mp4"),
        media("cc-f-speech-9x16.mp4"),
    ) else {
        return;
    };
    let made = create_project("Extract".to_owned(), 1080, 1920, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    let a = session.import(a).expect("imports").id;
    let b = session.import(b).expect("imports").id;
    let view = session.add_to_main(vec![a, b], 0).expect("placed");
    let main: Vec<String> = view.rows[0]
        .clips
        .iter()
        .map(|clip| clip.id.clone())
        .collect();
    session.extract_audio(main[0].clone()).expect("extracted");
    let view = session.extract_audio(main[1].clone()).expect("extracted");
    let mut names = sound_names(&view);
    names.sort();
    assert_eq!(names, ["Extracted audio1", "Extracted audio2"]);
    assert!(view.rows[0].clips.iter().all(|clip| clip.muted));
    // the first goes back into its clip; the other stays out
    let command = format!(r#"{{"op":"reattachAudio","clipId":"{}"}}"#, main[0]);
    let view = session.apply(command, None).expect("recovered");
    assert_eq!(sound_names(&view), ["Extracted audio2"]);
    assert!(!view.rows[0].clips[0].muted);
    delete_project(made.path).expect("deleted");
}

#[test]
fn a_trashed_project_can_come_back_or_go_for_good() {
    start();
    let made = create_project("Bin".to_owned(), 1080, 1920, 30).expect("made");
    trash_project(made.path.clone()).expect("trashed");
    assert!(
        !list_projects()
            .expect("lists")
            .iter()
            .any(|p| p.path == made.path)
    );
    let trashed = list_trash()
        .expect("lists the trash")
        .into_iter()
        .find(|p| p.name == "Bin")
        .expect("in the trash");
    let back = restore_project(trashed.path.clone()).expect("restored");
    assert!(
        list_projects()
            .expect("lists")
            .iter()
            .any(|p| p.path == back.path)
    );
    assert!(
        !list_trash()
            .expect("lists the trash")
            .iter()
            .any(|p| p.path == trashed.path)
    );
    // and from the trash it can be deleted outright
    trash_project(back.path).expect("trashed again");
    let again = list_trash()
        .expect("lists the trash")
        .into_iter()
        .find(|p| p.name == "Bin")
        .expect("in the trash");
    delete_project(again.path).expect("deleted for good");
    assert!(
        !list_trash()
            .expect("lists the trash")
            .iter()
            .any(|p| p.name == "Bin")
    );
}

/// A tracking job's report, waited on.
struct Waiter(std::sync::mpsc::Sender<Result<crate::session::TimelineView, String>>);

impl crate::tracking::TrackListener for Waiter {
    fn on_progress(&self, _fraction: f32) {}
    fn on_done(&self, result: crate::session::TimelineView) {
        let _ = self.0.send(Ok(result));
    }
    fn on_failed(&self, message: String) {
        let _ = self.0.send(Err(message));
    }
}

#[test]
fn a_title_tracking_still_footage_keeps_its_place() {
    start();
    let Some(bars) = media("cc-a-bars-16x9.mp4") else {
        return;
    };
    let made = create_project("Track".to_owned(), 1080, 1920, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    let item = session.import(bars).expect("imports");
    session.add_to_main(vec![item.id], 0).expect("placed");
    let view = session
        .add_text(0, 2_000_000, String::new())
        .expect("a title");
    let title = view.created_id.clone();
    let (send, wait) = std::sync::mpsc::channel();
    std::sync::Arc::clone(&session)
        .track(title.clone(), 0.5, 0.5, std::sync::Arc::new(Waiter(send)))
        .expect("starts");
    let view = wait
        .recv_timeout(std::time::Duration::from_secs(120))
        .expect("finishes")
        .expect("tracks");
    let clip = view
        .rows
        .iter()
        .flat_map(|row| row.clips.iter())
        .find(|c| c.id == title)
        .expect("the title is there");
    assert!(clip.keys_us.len() >= 5, "{:?}", clip.keys_us);
    // still footage: the title has nowhere to go
    let json: serde_json::Value = serde_json::from_str(&clip.json).expect("parses");
    let keys = json["keys"].as_array().cloned().unwrap_or_default();
    let moves: Vec<_> = keys
        .iter()
        .filter(|key| key["property"] == "offsetX")
        .collect();
    assert!(!moves.is_empty(), "{json}");
    for key in moves {
        let value = key["value"].as_f64().unwrap_or(0.0);
        assert!(value.abs() < 0.01, "{key}");
    }
    delete_project(made.path).expect("deleted");
}

#[test]
fn a_title_on_shaky_footage_moves_with_it() {
    start();
    let Some(shaky) = media("cc-j-shaky-16x9.mp4") else {
        return;
    };
    let made = create_project("Track shaky".to_owned(), 1920, 1080, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    let item = session.import(shaky).expect("imports");
    session.add_to_main(vec![item.id], 0).expect("placed");
    let view = session
        .add_text(0, 3_000_000, String::new())
        .expect("a title");
    let title = view.created_id.clone();
    let (send, wait) = std::sync::mpsc::channel();
    std::sync::Arc::clone(&session)
        .track(title.clone(), 0.5, 0.5, std::sync::Arc::new(Waiter(send)))
        .expect("starts");
    let view = wait
        .recv_timeout(std::time::Duration::from_secs(120))
        .expect("finishes")
        .expect("tracks");
    let clip = view
        .rows
        .iter()
        .flat_map(|row| row.clips.iter())
        .find(|c| c.id == title)
        .expect("the title is there");
    let json: serde_json::Value = serde_json::from_str(&clip.json).expect("parses");
    let reach = json["keys"]
        .as_array()
        .cloned()
        .unwrap_or_default()
        .iter()
        .filter(|key| key["property"] == "offsetX" || key["property"] == "offsetY")
        .map(|key| key["value"].as_f64().unwrap_or(0.0).abs())
        .fold(0.0f64, f64::max);
    // the camera shakes by tens of pixels: the title goes with it
    assert!(reach > 0.004, "{reach}");
    assert!(reach < 0.2, "{reach}");
    delete_project(made.path).expect("deleted");
}

#[test]
fn camera_tracking_zooms_in_and_moves_the_clip_with_its_subject() {
    start();
    let Some(shaky) = media("cc-j-shaky-16x9.mp4") else {
        return;
    };
    let made = create_project("Camera track".to_owned(), 1920, 1080, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    let item = session.import(shaky).expect("imports");
    let view = session.add_to_main(vec![item.id], 0).expect("placed");
    let id = view.rows[0].clips[0].id.clone();
    let (send, wait) = std::sync::mpsc::channel();
    std::sync::Arc::clone(&session)
        .camera_track(
            id.clone(),
            0.5,
            0.5,
            1.25,
            std::sync::Arc::new(Waiter(send)),
        )
        .expect("starts");
    let view = wait
        .recv_timeout(std::time::Duration::from_secs(120))
        .expect("finishes")
        .expect("tracks");
    let clip = view.rows[0]
        .clips
        .iter()
        .find(|c| c.id == id)
        .expect("the clip is there");
    let json: serde_json::Value = serde_json::from_str(&clip.json).expect("parses");
    assert!(
        (json["scale"].as_f64().unwrap_or(1.0) - 1.25).abs() < 1e-6,
        "{}",
        json["scale"]
    );
    let moves: Vec<f64> = json["keys"]
        .as_array()
        .cloned()
        .unwrap_or_default()
        .iter()
        .filter(|key| key["property"] == "offsetX" || key["property"] == "offsetY")
        .map(|key| key["value"].as_f64().unwrap_or(0.0))
        .collect();
    assert!(moves.len() > 4, "{moves:?}");
    // the camera follows the shake, but never past the room the zoom gives: an eighth of the frame
    let reach = moves.iter().fold(0.0f64, |m, v| m.max(v.abs()));
    assert!(reach > 0.002, "{reach}");
    assert!(reach <= 0.125 + 1e-9, "{reach}");
    delete_project(made.path).expect("deleted");
}

#[test]
fn a_blurred_copy_keeps_every_frame_and_the_sound() {
    start();
    let Some(shaky) = media("cc-j-shaky-16x9.mp4") else {
        return;
    };
    let dir = std::env::temp_dir().join(format!("capcraft-blur-{}", std::process::id()));
    std::fs::create_dir_all(&dir).expect("made");
    let target = dir.join("blurred.mp4");
    let settings = crate::motionblur::Settings::of(50, 80, 0, 1);
    crate::motionblur::blur_copy(&shaky, &target, &settings, &|_| true).expect("blurred");
    let (_, frames_before) = jitter(&shaky);
    let (_, frames_after) = jitter(&target.to_string_lossy());
    assert_eq!(frames_after, frames_before, "every frame kept");
    let info = concat_media::probe(&target).expect("probes");
    assert!(info.audio.is_some(), "the sound came along");
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn an_evened_out_copy_keeps_every_frame_and_the_sound() {
    start();
    let Some(shaky) = media("cc-j-shaky-16x9.mp4") else {
        return;
    };
    let dir = std::env::temp_dir().join(format!("capcraft-flicker-{}", std::process::id()));
    std::fs::create_dir_all(&dir).expect("made");
    let target = dir.join("even.mp4");
    crate::deflicker::deflicker(&shaky, &target, &|_| true).expect("evened out");
    let (_, frames_before) = jitter(&shaky);
    let (_, frames_after) = jitter(&target.to_string_lossy());
    assert_eq!(frames_after, frames_before, "every frame kept");
    let info = concat_media::probe(&target).expect("probes");
    assert!(info.audio.is_some(), "the sound came along");
    let _ = std::fs::remove_dir_all(&dir);
}

/// A listener that hears nothing out: for jobs whose result a test does not wait on.
struct Unheard;

impl crate::speech::SpeechListener for Unheard {
    fn on_progress(&self, _fraction: f32, _stage: String) {}
    fn on_done(&self, _result: Option<crate::session::TimelineView>) {}
    fn on_failed(&self, _message: String) {}
}

#[test]
fn a_body_effect_lays_a_cut_out_copy_over_the_clip_and_comes_off_cleanly() {
    start();
    let Some(bars) = media("cc-a-bars-16x9.mp4") else {
        return;
    };
    let made = create_project("Body".to_owned(), 1920, 1080, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    let item = session.import(bars).expect("imports");
    let view = session.add_to_main(vec![item.id], 0).expect("placed");
    let id = view.rows[0].clips[0].id.clone();
    let effects = |view: &crate::session::TimelineView, clip: &str| -> Vec<String> {
        let c = view
            .rows
            .iter()
            .flat_map(|row| row.clips.iter())
            .find(|c| c.id == clip)
            .expect("there");
        let json: serde_json::Value = serde_json::from_str(&c.json).expect("parses");
        json["videoEffects"]
            .as_array()
            .cloned()
            .unwrap_or_default()
            .iter()
            .filter_map(|link| link["id"].as_str().map(str::to_owned))
            .collect()
    };
    std::sync::Arc::clone(&session)
        .body_effect(id.clone(), "neon".to_owned(), std::sync::Arc::new(Unheard))
        .expect("on");
    session.cancel_background();
    let view = session.view();
    let overlay: Vec<_> = view
        .rows
        .iter()
        .filter(|row| row.kind == RowKind::Overlay)
        .flat_map(|row| row.clips.iter())
        .collect();
    assert_eq!(overlay.len(), 1, "one copy over the clip");
    let copy = overlay[0];
    assert_eq!(copy.start_us, view.rows[0].clips[0].start_us);
    assert_eq!(copy.duration_us, view.rows[0].clips[0].duration_us);
    let copy_json: serde_json::Value = serde_json::from_str(&copy.json).expect("parses");
    assert!(
        !copy_json["cutout"].is_null(),
        "the copy has its background off"
    );
    assert_eq!(effects(&view, &copy.id), ["capcraft.outline"]);
    assert_eq!(effects(&view, &id), ["concat.adjust"]);
    // a different effect replaces it; none takes it all off
    std::sync::Arc::clone(&session)
        .body_effect(id.clone(), "blur".to_owned(), std::sync::Arc::new(Unheard))
        .expect("changed");
    session.cancel_background();
    let view = session.view();
    assert_eq!(effects(&view, &id), ["concat.gaussian-blur"]);
    std::sync::Arc::clone(&session)
        .body_effect(id.clone(), String::new(), std::sync::Arc::new(Unheard))
        .expect("off");
    let view = session.view();
    assert!(
        view.rows
            .iter()
            .filter(|row| row.kind == RowKind::Overlay)
            .all(|row| row.clips.is_empty())
    );
    assert!(effects(&view, &id).is_empty());
    delete_project(made.path).expect("deleted");
}

#[test]
fn a_duplicate_keeps_the_cut_and_the_placement() {
    start();
    let Some(bars) = media("cc-a-bars-16x9.mp4") else {
        return;
    };
    let made = create_project("Duplicate cut".to_owned(), 1920, 1080, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    let item = session.import(bars).expect("imports");
    let view = session.add_to_main(vec![item.id], 0).expect("placed");
    let id = view.rows[0].clips[0].id.clone();
    for command in [
        format!(r#"{{"op":"trimClip","clipId":"{id}","edge":"start","delta":1.0}}"#),
        format!(r#"{{"op":"trimClip","clipId":"{id}","edge":"end","delta":-2.0}}"#),
        format!(
            r#"{{"op":"setClipTransform","clipId":"{id}","scale":1.3,"offsetX":0.1,"rotation":12}}"#
        ),
    ] {
        session.apply(command, None).expect("edited");
    }
    let view = session.duplicate(id.clone()).expect("duplicated");
    let json_of = |clip: &str| -> serde_json::Value {
        let c = view
            .rows
            .iter()
            .flat_map(|row| row.clips.iter())
            .find(|c| c.id == clip)
            .expect("there");
        serde_json::from_str(&c.json).expect("parses")
    };
    let (a, b) = (json_of(&id), json_of(&view.created_id));
    for key in ["sourceStart", "duration", "scale", "offsetX", "rotation"] {
        let (x, y) = (
            a[key].as_f64().unwrap_or(0.0),
            b[key].as_f64().unwrap_or(0.0),
        );
        assert!((x - y).abs() < 1e-3, "{key}: {x} then {y}");
    }
    delete_project(made.path).expect("deleted");
}

#[test]
fn a_title_follows_its_clip_when_the_one_before_goes() {
    start();
    let (Some(a), Some(b)) = (media("cc-a-bars-16x9.mp4"), media("cc-c-gradient-9x16.mp4")) else {
        return;
    };
    let made = create_project("Linkage".to_owned(), 1080, 1920, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    let a = session.import(a).expect("imports").id;
    let b = session.import(b).expect("imports").id;
    let view = session.add_to_main(vec![a, b], 0).expect("placed");
    let first = view.rows[0].clips[0].clone();
    let second_start = view.rows[0].clips[1].start_us;
    let view = session
        .add_text(second_start + 1_000_000, 2_000_000, String::new())
        .expect("a title");
    let title = view.created_id.clone();
    let start_of = |view: &crate::session::TimelineView, id: &str| {
        view.rows
            .iter()
            .flat_map(|row| row.clips.iter())
            .find(|c| c.id == id)
            .map(|c| c.start_us)
    };
    let command = format!(
        r#"{{"op":"removeClips","clipIds":["{}"],"ripple":true}}"#,
        first.id
    );
    let view = session.apply(command, None).expect("deleted");
    // the second clip is first now, and the title still sits a second into it
    assert_eq!(view.rows[0].clips[0].start_us, 0);
    let now = start_of(&view, &title).expect("the title is there");
    assert!((now - 1_000_000).abs() < 40_000, "{now}");
    // and one undo puts both back
    let view = session.undo();
    let back = start_of(&view, &title).expect("the title is there");
    assert!((back - (second_start + 1_000_000)).abs() < 40_000, "{back}");
    delete_project(made.path).expect("deleted");
}

#[test]
fn a_smoothed_stretch_has_the_frames_slow_motion_needs() {
    start();
    let Some(shaky) = media("cc-j-shaky-16x9.mp4") else {
        return;
    };
    let dir = std::env::temp_dir().join(format!("capcraft-slowmo-{}", std::process::id()));
    std::fs::create_dir_all(&dir).expect("made");
    let target = dir.join("smooth.mp4");
    let stretch = crate::slowmo::Stretch {
        media: shaky.clone(),
        from: 1.0,
        span: 1.0,
        speed: 0.25,
    };
    crate::slowmo::smooth_copy(&stretch, &target, &|_| true).expect("smoothed");
    let info = concat_media::probe(&target).expect("probes");
    let source = concat_media::probe(&shaky).expect("probes");
    let fps = info
        .video
        .as_ref()
        .expect("a picture")
        .frame_rate
        .fps()
        .as_f64();
    let source_fps = source
        .video
        .as_ref()
        .expect("a picture")
        .frame_rate
        .fps()
        .as_f64();
    // four times the frames a second, over the one second of the stretch
    assert!(
        (fps - source_fps * 4.0).abs() < 1.0,
        "{fps} from {source_fps}"
    );
    let length = info.duration.map(|d| d.as_f64()).unwrap_or(0.0);
    assert!((length - 1.0).abs() < 0.15, "{length}");
    assert!(info.audio.is_some(), "the stretch's sound came along");
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn the_voice_comes_out_under_the_same_picture() {
    start();
    let Some(speech) = media("cc-f-speech-9x16.mp4") else {
        return;
    };
    // the network is fetched on first use; with no way to fetch it there is nothing to try
    let Ok(model) = crate::voice::model(&mut |_| {}) else {
        return;
    };
    let dir = std::env::temp_dir().join(format!("capcraft-voice-{}", std::process::id()));
    std::fs::create_dir_all(&dir).expect("made");
    let target = dir.join("voice.mp4");
    crate::voice::isolate(&speech, &model, &target, &|_| true).expect("isolated");
    let before = concat_media::probe(&speech).expect("probes");
    let after = concat_media::probe(&target).expect("probes");
    assert!(after.video.is_some() && after.audio.is_some());
    let (a, b) = (
        before.duration.map(|d| d.as_f64()).unwrap_or(0.0),
        after.duration.map(|d| d.as_f64()).unwrap_or(0.0),
    );
    assert!((a - b).abs() < 0.3, "{a} became {b}");
    let _ = std::fs::remove_dir_all(&dir);
}

/// A listener that hands back how a job ended, for a test to wait on.
struct Ended(std::sync::mpsc::Sender<Result<Option<crate::session::TimelineView>, String>>);

impl crate::speech::SpeechListener for Ended {
    fn on_progress(&self, _fraction: f32, _stage: String) {}
    fn on_done(&self, result: Option<crate::session::TimelineView>) {
        let _ = self.0.send(Ok(result));
    }
    fn on_failed(&self, message: String) {
        let _ = self.0.send(Err(message));
    }
}

#[test]
fn a_wide_clip_reframed_fills_a_tall_frame() {
    start();
    let Some(bars) = media("cc-a-bars-16x9.mp4") else {
        return;
    };
    let made = create_project("Reframe".to_owned(), 1080, 1920, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    let item = session.import(bars).expect("imports");
    let view = session.add_to_main(vec![item.id], 0).expect("placed");
    let id = view.rows[0].clips[0].id.clone();
    // the first clip gave the project its own wide frame: back to a tall one
    session.set_canvas(1080, 1920).expect("tall");
    let (send, wait) = std::sync::mpsc::channel();
    std::sync::Arc::clone(&session)
        .auto_reframe(id.clone(), std::sync::Arc::new(Ended(send)))
        .expect("starts");
    // the person model is fetched on first use; with no way to fetch it there is nothing to try
    let Ok(Some(view)) = wait
        .recv_timeout(std::time::Duration::from_secs(300))
        .expect("finishes")
    else {
        delete_project(made.path).expect("deleted");
        return;
    };
    let clip = view.rows[0]
        .clips
        .iter()
        .find(|c| c.id == id)
        .expect("there");
    let json: serde_json::Value = serde_json::from_str(&clip.json).expect("parses");
    // a 16:9 picture fills a 9:16 frame at 16/9 over 9/16 of its fitted size
    let scale = json["scale"].as_f64().unwrap_or(1.0);
    assert!(
        (scale - (16.0 / 9.0) / (9.0 / 16.0)).abs() < 0.05,
        "{scale}"
    );
    // the picture moves only as far as the zoom leaves room: none up and down, and across
    // no more than the grown picture overhangs the frame on each side
    let keys = json["keys"].as_array().cloned().unwrap_or_default();
    assert!(!keys.is_empty());
    let room = (scale - 1.0) / 2.0;
    for key in &keys {
        let value = key["value"].as_f64().unwrap_or(9.0);
        match key["property"].as_str() {
            Some("offsetY") => assert!(value.abs() < 1e-6, "{value}"),
            _ => assert!(value.abs() <= room + 1e-6, "{value} past {room}"),
        }
    }
    delete_project(made.path).expect("deleted");
}

#[test]
fn every_clip_with_sound_comes_to_one_loudness() {
    start();
    let (Some(speech), Some(tones)) = (media("cc-f-speech-9x16.mp4"), media("cc-e-tones.m4a"))
    else {
        return;
    };
    let made = create_project("Loudness".to_owned(), 1080, 1920, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    let speech = session.import(speech).expect("imports").id;
    let tones = session.import(tones).expect("imports").id;
    let view = session.add_to_main(vec![speech], 0).expect("placed");
    let main = view.rows[0].clips[0].id.clone();
    let view = session.add_audio(tones, 0).expect("a sound");
    let sound = view.created_id.clone();
    let view = session.match_loudness(main.clone(), true).expect("matched");
    let target_of = |view: &crate::session::TimelineView, id: &str| -> Option<f64> {
        let c = view
            .rows
            .iter()
            .flat_map(|row| row.clips.iter())
            .find(|c| c.id == id)?;
        let json: serde_json::Value = serde_json::from_str(&c.json).ok()?;
        json["filters"]
            .as_array()?
            .iter()
            .find(|link| link["id"] == "concat.loudness")
            .and_then(|link| link["params"]["target"].as_f64())
    };
    let (a, b) = (target_of(&view, &main), target_of(&view, &sound));
    assert!(a.is_some(), "the measured clip is levelled too");
    assert_eq!(a, b, "one loudness for both");
    let level = a.unwrap();
    assert!((-30.0..=-12.0).contains(&level), "{level}");
    // and off takes it off them all
    let view = session.match_loudness(main.clone(), false).expect("off");
    assert!(target_of(&view, &main).is_none() && target_of(&view, &sound).is_none());
    delete_project(made.path).expect("deleted");
}

#[test]
fn removing_pauses_shortens_the_talk_and_leaves_no_gap() {
    start();
    let Some(speech) = media("cc-f-speech-9x16.mp4") else {
        return;
    };
    let made = create_project("Pauses".to_owned(), 1080, 1920, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    let item = session.import(speech).expect("imports");
    let before = session
        .add_to_main(vec![item.id], 0)
        .expect("placed")
        .duration_us;
    match session.remove_pauses(300_000, Vec::new()) {
        Ok(PausesRemoved { view, .. }) => {
            assert!(
                view.duration_us < before,
                "{} then {}",
                before,
                view.duration_us
            );
            assert!(gapless(&view), "{:?}", main_spans(&view));
            // one undo brings the talk back whole
            assert_eq!(session.undo().duration_us, before);
        }
        Err(error) => assert!(error.to_string().contains("No pauses"), "{error}"),
    }
    delete_project(made.path).expect("deleted");
}

#[test]
fn lasting_text_spans_the_video_and_auto_split_deals_out_the_sentences() {
    start();
    let Some(bars) = media("cc-a-bars-16x9.mp4") else {
        return;
    };
    let made = create_project("Lasting".to_owned(), 1080, 1920, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    let item = session.import(bars).expect("imports");
    let end = session
        .add_to_main(vec![item.id], 0)
        .expect("placed")
        .duration_us;
    let view = session
        .add_text(
            1_000_000,
            3_000_000,
            r#"{"content":"First words here. Then some more!"}"#.to_owned(),
        )
        .expect("a title");
    let title = view.created_id.clone();
    let texts = |view: &crate::session::TimelineView| -> Vec<(i64, i64, String)> {
        let mut found: Vec<_> = view
            .rows
            .iter()
            .filter(|row| row.kind == RowKind::Text)
            .flat_map(|row| row.clips.iter())
            .map(|clip| {
                let json: serde_json::Value = serde_json::from_str(&clip.json).expect("json");
                (
                    clip.start_us,
                    clip.duration_us,
                    json["text"]["content"]
                        .as_str()
                        .unwrap_or_default()
                        .to_owned(),
                )
            })
            .collect();
        found.sort();
        found
    };

    // split by sentence, each as long as its share of the letters, end to end
    let split = texts(&session.auto_split_text(title.clone()).expect("splits"));
    assert_eq!(split.len(), 2, "{split:?}");
    assert_eq!(split[0].2, "First words here.");
    assert_eq!(split[1].2, "Then some more!");
    assert_eq!(split[0].0, 1_000_000);
    assert!(
        (split[0].0 + split[0].1 - split[1].0).abs() <= 1,
        "{split:?}"
    );
    assert!(
        (split[1].0 + split[1].1 - 4_000_000).abs() <= 1,
        "{split:?}"
    );
    // one undo brings the title back whole
    assert_eq!(texts(&session.undo()).len(), 1);

    // lasting: from the first frame to the end of the video
    let lasting = texts(&session.lasting_text(title).expect("lasts"));
    assert_eq!(lasting.len(), 1);
    assert_eq!(lasting[0].0, 0);
    assert!((lasting[0].1 - end).abs() <= 1, "{lasting:?} to {end}");
    let back = texts(&session.undo());
    assert_eq!((back[0].0, back[0].1), (1_000_000, 3_000_000));
    delete_project(made.path).expect("deleted");
}

#[test]
fn undo_and_redo_say_what_they_take_back() {
    start();
    let made = create_project("Names".to_owned(), 1080, 1920, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    assert_eq!(session.undo_name(), "");
    // a title on a lane of its own: the lane is no step of its own name
    let title = session
        .add_text(0, 3_000_000, String::new())
        .expect("a title")
        .created_id;
    assert_eq!(session.undo_name(), "Add text");
    session.split(vec![title], 1_000_000).expect("split");
    assert_eq!(session.undo_name(), "Split");
    session.undo();
    assert_eq!(session.undo_name(), "Add text");
    assert_eq!(session.redo_name(), "Split");
    session.redo();
    assert_eq!(session.undo_name(), "Split");
    assert_eq!(session.redo_name(), "");
    delete_project(made.path).expect("deleted");
}

#[test]
fn a_clip_keeps_its_own_background_until_apply_to_all() {
    start();
    let Some(bars) = media("cc-a-bars-16x9.mp4") else {
        return;
    };
    let made = create_project("Backgrounds".to_owned(), 1080, 1920, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    let item = session.import(bars).expect("imports");
    let view = session
        .add_to_main(vec![item.id.clone(), item.id], 0)
        .expect("placed");
    let main: Vec<String> = view.rows[0]
        .clips
        .iter()
        .map(|clip| clip.id.clone())
        .collect();
    assert_eq!(main.len(), 2);
    let red = r##"{"kind":"color","color":"#ff0000"}"##;
    session
        .set_clip_background(main[0].clone(), red.to_owned())
        .expect("set on one");
    assert!(session.clip_background(main[0].clone()).contains("ff0000"));
    assert_eq!(session.clip_background(main[1].clone()), "");
    assert_eq!(session.undo_name(), "Background");
    // a clip of its own with none shows none, whatever the project's
    session
        .set_background(r#"{"kind":"blur","level":2}"#.to_owned())
        .expect("apply to all");
    assert!(session.clip_background(main[0].clone()).contains("blur"));
    session
        .set_clip_background(main[1].clone(), r#"{"kind":"none"}"#.to_owned())
        .expect("none on one");
    assert_eq!(session.clip_background(main[1].clone()), "");
    assert!(session.clip_background(main[0].clone()).contains("blur"));
    delete_project(made.path).expect("deleted");
}

#[test]
fn a_shape_lands_on_a_text_row_and_takes_a_new_paint() {
    start();
    let made = create_project("Shapes".to_owned(), 1080, 1920, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    let view = session
        .add_shape(
            0,
            3_000_000,
            r##"{"kind":"circle","fill":"#ffffff"}"##.to_owned(),
            "Circle".to_owned(),
        )
        .expect("a shape");
    let id = view.created_id.clone();
    let row = view
        .rows
        .iter()
        .find(|row| row.clips.iter().any(|clip| clip.id == id))
        .expect("its row");
    assert_eq!(row.kind, RowKind::Text);
    let view = session
        .apply(
            serde_json::json!({
                "op": "updateClip",
                "clipId": id,
                "patch": {"shape": {"kind": "circle", "fill": "#ff0000", "stroke": "#000000", "strokeWidth": 0.01}},
            })
            .to_string(),
            None,
        )
        .expect("repainted");
    let clip = view
        .rows
        .iter()
        .flat_map(|row| row.clips.iter())
        .find(|clip| clip.id == id)
        .expect("still there");
    let json: serde_json::Value = serde_json::from_str(&clip.json).expect("json");
    assert_eq!(json["shape"]["fill"], "#ff0000");
    assert_eq!(session.undo_name(), "Style");
    delete_project(made.path).expect("deleted");
}

/// The rows bottom of the stack first, an effect row that treats every
/// picture told from one that treats the main track alone.
fn stack(view: &crate::session::TimelineView) -> Vec<&'static str> {
    view.rows
        .iter()
        .map(|row| match row.kind {
            RowKind::Main => "main",
            RowKind::Overlay => "overlay",
            RowKind::Effect if row.all_videos => "every picture",
            RowKind::Effect => "main alone",
            RowKind::Filter => "filter",
            RowKind::Text => "text",
            RowKind::Audio => "audio",
        })
        .collect()
}

#[test]
fn an_effect_treats_the_main_track_alone_or_every_picture() {
    start();
    let (Some(a), Some(b)) = (media("cc-a-bars-16x9.mp4"), media("cc-b-testsrc-9x16.mp4")) else {
        return;
    };
    let made = create_project("Object".to_owned(), 1080, 1920, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    let a = session.import(a).expect("imports").id;
    let b = session.import(b).expect("imports").id;
    session.add_to_main(vec![a], 0).expect("placed");
    // a new effect treats the main track alone, and an overlay put on after
    // it goes over it, out of its reach
    let effect = session
        .add_layer(
            "capcraft.disco-lights".to_owned(),
            "Disco Lights".to_owned(),
            0,
            2_000_000,
        )
        .expect("an effect")
        .created_id;
    let view = session.add_overlay(b, 0).expect("an overlay");
    assert_eq!(stack(&view), ["main", "main alone", "overlay"]);
    let overlay = view.created_id.clone();
    // every picture: over the overlay, in one undo step
    let view = session
        .set_reach(effect.clone(), true)
        .expect("every picture");
    assert_eq!(stack(&view), ["main", "overlay", "every picture"]);
    let view = session.undo();
    assert_eq!(stack(&view), ["main", "main alone", "overlay"]);
    session
        .set_reach(effect.clone(), true)
        .expect("every picture");
    // a copy treats what its original does; a new effect, the main track
    let view = session.duplicate(effect.clone()).expect("a copy");
    let copy = view.created_id.clone();
    let row = view
        .rows
        .iter()
        .find(|row| row.clips.iter().any(|clip| clip.id == copy))
        .expect("the copy's row");
    assert!(row.all_videos);
    let view = session
        .add_layer(
            "capcraft.confetti".to_owned(),
            "Confetti".to_owned(),
            0,
            1_000_000,
        )
        .expect("another effect");
    assert_eq!(
        stack(&view),
        ["main", "main alone", "overlay", "every picture"]
    );
    // with the overlay gone the choice is kept, and a new overlay goes under it
    session.delete(vec![overlay]).expect("deleted");
    let c = session
        .import(media("cc-b-testsrc-9x16.mp4").expect("clip"))
        .expect("imports")
        .id;
    let view = session.add_overlay(c, 0).expect("a new overlay");
    assert_eq!(
        stack(&view),
        ["main", "main alone", "overlay", "every picture"]
    );
    // and back to the main track alone
    let view = session.set_reach(effect, false).expect("main alone");
    assert!(stack(&view).ends_with(&["overlay", "every picture"]));
    assert_eq!(stack(&view)[1], "main alone");
    delete_project(made.path).expect("deleted");
}

#[test]
fn a_title_can_go_under_an_overlay_and_back() {
    start();
    let (Some(a), Some(b)) = (media("cc-a-bars-16x9.mp4"), media("cc-b-testsrc-9x16.mp4")) else {
        return;
    };
    let made = create_project("Arrange".to_owned(), 1080, 1920, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    let a = session.import(a).expect("imports").id;
    let b = session.import(b).expect("imports").id;
    session.add_to_main(vec![a], 0).expect("placed");
    let overlay = session.add_overlay(b, 0).expect("an overlay").created_id;
    let view = session
        .add_text(0, 2_000_000, String::new())
        .expect("a title");
    let title = view.created_id.clone();
    assert_eq!(stack(&view), ["main", "overlay", "text"]);
    let view = session
        .arrange(title.clone(), overlay.clone(), false)
        .expect("under");
    assert_eq!(stack(&view), ["main", "text", "overlay"]);
    // never under the main track
    let main = view.rows[0].clips[0].id.clone();
    let view = session
        .arrange(title.clone(), main, false)
        .expect("refused");
    assert_eq!(stack(&view), ["main", "text", "overlay"]);
    let view = session
        .arrange(title.clone(), overlay.clone(), true)
        .expect("over");
    assert_eq!(stack(&view), ["main", "overlay", "text"]);
    // a title sharing its row goes under the overlay alone; the other stays
    let later = session
        .add_text(3_000_000, 1_000_000, String::new())
        .expect("a later title")
        .created_id;
    let view = session
        .arrange(later.clone(), overlay, false)
        .expect("alone");
    assert_eq!(stack(&view), ["main", "text", "overlay", "text"]);
    let row_of = |id: &str| {
        view.rows
            .iter()
            .position(|row| row.clips.iter().any(|clip| clip.id == id))
    };
    assert!(row_of(&later) < row_of(&title));
    delete_project(made.path).expect("deleted");
}

#[test]
fn typed_lines_become_captions_one_after_another() {
    start();
    let made = create_project("Typed".to_owned(), 1080, 1920, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    let view = session
        .enter_captions(
            "First line here\n\n  A second, longer line of words  \nThird".to_owned(),
            1_000_000,
        )
        .expect("captions");
    let titles: Vec<_> = view
        .rows
        .iter()
        .filter(|row| row.kind == RowKind::Text)
        .flat_map(|row| row.clips.iter())
        .collect();
    assert_eq!(titles.len(), 3, "blank lines are dropped");
    assert_eq!(titles[0].text, "First line here");
    assert_eq!(titles[1].text, "A second, longer line of words");
    assert_eq!(titles[0].start_us, 1_000_000);
    // back to back, the longer line up longer
    assert!((titles[1].start_us - (titles[0].start_us + titles[0].duration_us)).abs() < 2_000);
    assert!(titles[1].duration_us > titles[0].duration_us);
    assert_eq!(session.caption_count(), 3);
    // one undo step
    let view = session.undo();
    assert!(!view.rows.iter().any(|row| row.kind == RowKind::Text));
    delete_project(made.path).expect("deleted");
}

/// The mean brightness of a frame of `session` at `at_us`, small.
fn brightness(session: &MobileSession, at_us: i64) -> f64 {
    let picture = session
        .frame(at_us, 64, 36, crate::session::Motion::Still)
        .expect("a frame");
    let sum: u64 = picture
        .rgba
        .chunks(4)
        .map(|px| u64::from(px[0]) + u64::from(px[1]) + u64::from(px[2]))
        .sum();
    sum as f64 / (picture.rgba.len() / 4) as f64 / 3.0
}

#[test]
fn an_outro_on_an_effect_row_closes_toward_the_layers_end() {
    start();
    let Some(bars) = media("cc-a-bars-16x9.mp4") else {
        return;
    };
    let made = create_project("Outro".to_owned(), 320, 180, 30).expect("made");
    let session = MobileSession::open(made.path.clone()).expect("opens");
    let item = session.import(bars).expect("imports").id;
    session.add_to_main(vec![item], 0).expect("placed");
    session
        .add_layer(
            "capcraft.fade-out".to_owned(),
            "Fade Out".to_owned(),
            1_000_000,
            2_000_000,
        )
        .expect("an outro");
    let early = brightness(&session, 1_200_000);
    let late = brightness(&session, 2_960_000);
    assert!(
        early > 40.0,
        "the picture shows early in the layer: {early}"
    );
    assert!(
        late < early * 0.25,
        "and has all but gone at its end: {late} after {early}"
    );
    delete_project(made.path).expect("deleted");
}

/// A drag shows what the playhead at rest will: each video's scrub copy
/// draws its picture turned the way the file's rotation says, at the
/// instant asked for - a sixty-frame file and an HEVC one too.
#[test]
fn a_drag_shows_what_the_playhead_at_rest_will() {
    use crate::session::Motion;
    start();
    let clips = [
        ("cc-m-rotated-9x16.mp4", (1080, 1920), (90, 160)),
        ("cc-g-4k60.mp4", (1920, 1080), (160, 90)),
        ("cc-h-hevc-1080.mp4", (1920, 1080), (160, 90)),
    ];
    for (name, (width, height), (w, h)) in clips {
        let Some(path) = media(name) else {
            continue;
        };
        let made = create_project(name.to_owned(), width, height, 30).expect("made");
        let session = MobileSession::open(made.path.clone()).expect("opens");
        let item = session.import(path).expect("imports").id;
        session.add_to_main(vec![item], 0).expect("placed");
        concat_host::scheduler().drain();
        let copies = std::fs::read_dir(std::path::Path::new(&made.path).join("cache/scrub"))
            .map(|entries| entries.flatten().count())
            .unwrap_or(0);
        assert_eq!(copies, 1, "{name}: one scrub copy written");
        for at in [1_000_000, 3_500_000, 7_250_000] {
            let still = session.frame(at, w, h, Motion::Still).expect("a frame");
            let dragged = session.frame(at, w, h, Motion::Scrubbing).expect("a frame");
            assert_eq!(still.rgba.len(), dragged.rgba.len());
            let apart: u64 = still
                .rgba
                .iter()
                .zip(&dragged.rgba)
                .map(|(a, b)| u64::from(a.abs_diff(*b)))
                .sum();
            let mean = apart as f64 / still.rgba.len() as f64;
            assert!(
                mean < 20.0,
                "{name} at {at} us: the dragged picture is {mean:.1} apart from the still one"
            );
        }
        delete_project(made.path).expect("deleted");
    }
}

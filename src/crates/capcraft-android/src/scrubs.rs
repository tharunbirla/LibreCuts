// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! Each video's scrub copy, read while the playhead is dragged: written in
//! the background into the project's cache, one at a time, from the moment
//! the video is added or the project opens. Playback and the playhead at
//! rest read the files themselves. See `concat_host::scrub`.

use std::collections::HashSet;
use std::path::Path;

use concat_host::session::Session;
use concat_project::model::MediaKind;

/// One video a copy is made from: its path, size, the range it is read as
/// and whether it is HDR.
struct Video {
    path: String,
    width: u32,
    height: u32,
    range: Option<concat_media::ColorRange>,
    hdr: bool,
}

/// The project's videos, every one, by id.
fn videos(session: &Session) -> Vec<(String, Video)> {
    session
        .project()
        .media
        .iter()
        .filter(|item| item.kind == MediaKind::Video && !item.placeholder)
        .filter_map(|item| {
            Some((
                item.id.clone(),
                Video {
                    path: item.path.clone(),
                    width: item.width?,
                    height: item.height?,
                    range: item.color_range.map(concat_export::engine_range),
                    hdr: item.color_space.is_hdr(),
                },
            ))
        })
        .collect()
}

/// Queues the copy of every video not in `seen`, then adds it there. A
/// copy already written is adopted at once.
pub(crate) fn see_to(session: &Session, seen: &mut HashSet<String>) {
    let project = Path::new(session.path());
    for (id, video) in videos(session) {
        if seen.insert(id) {
            concat_host::scrub::ensure(
                project,
                &video.path,
                video.width,
                video.height,
                video.range,
                video.hdr,
            );
        }
    }
}

/// Removes the copies of media no longer in the project, or read as a
/// range they no longer are: nothing else clears them.
pub(crate) fn sweep(session: &Session) {
    let project = Path::new(session.path());
    let keep: HashSet<_> = videos(session)
        .iter()
        .filter_map(|(_, video)| concat_host::scrub::path_for(project, &video.path, video.range))
        .collect();
    let swept = concat_host::scrub::sweep(project, &keep);
    if swept > 0 {
        log::info!(
            "{swept} stale scrub copies swept from {}",
            project.display()
        );
    }
}

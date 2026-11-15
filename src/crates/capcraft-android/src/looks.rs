// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! Looks of the user's own: a `.cube` table from a grading tool made into a
//! filter by the engine (`concat_effects::looks`), kept in the engine's
//! folder where Concat's desktop editor keeps its imported looks, and
//! offered beside the built-in filters under "Imported". A clip names one
//! by id as it names any filter; one whose look is gone draws without it.

use std::path::{Path, PathBuf};

use concat_effects::Catalogue;
use concat_host::AppDirs;

use crate::{MobileError, context};

/// The ids every imported look starts with.
const PREFIX: &str = "user.";

/// Where imported looks are kept: one package folder each.
fn folder(dirs: &AppDirs) -> PathBuf {
    dirs.config.join("effects")
}

/// Offers the looks imported before, at start: any an earlier build made
/// are rewritten onto the current look first, and one that will not load
/// is left out, with its reason in the log.
pub(crate) fn install(dirs: &AppDirs) {
    let dir = folder(dirs);
    for id in concat_effects::looks::upgrade(&dir) {
        log::info!("{id}: the imported look now reads its table in light");
    }
    for error in Catalogue::install(&dir) {
        log::warn!("an imported look is not offered: {error}");
    }
}

/// Makes the `.cube` table at `path` a filter named after the file, and
/// returns its id. A second table of the same name replaces the first. A
/// file that is not a table is refused, and nothing is kept of it.
#[uniffi::export]
pub fn import_look(path: String) -> Result<String, MobileError> {
    let dir = folder(&context()?.dirs);
    std::fs::create_dir_all(&dir)
        .map_err(|error| format!("could not create {}: {error}", dir.display()))?;
    let id = concat_effects::looks::import(&dir, Path::new(&path))
        .map_err(|error| format!("that file is not a .cube table: {error}"))?;
    let errors = Catalogue::install(&dir);
    if Catalogue::builtin().get(&id).is_none() {
        let _ = std::fs::remove_dir_all(dir.join(&id));
        Catalogue::install(&dir);
        let reason = errors
            .iter()
            .map(ToString::to_string)
            .collect::<Vec<_>>()
            .join("; ");
        return Err(format!("that table could not be made a look: {reason}").into());
    }
    Ok(id)
}

/// Removes the imported look `id`; clips that use it draw without it. A
/// built-in filter is refused.
#[uniffi::export]
pub fn remove_look(id: String) -> Result<(), MobileError> {
    let named = id
        .strip_prefix(PREFIX)
        .is_some_and(|rest| !rest.is_empty() && !rest.contains(['/', '\\', '.']));
    if !named {
        return Err(format!("{id} is not an imported look").into());
    }
    let dir = folder(&context()?.dirs);
    let target = dir.join(&id);
    if target.is_dir() {
        std::fs::remove_dir_all(&target)
            .map_err(|error| format!("could not remove {}: {error}", target.display()))?;
    }
    for error in Catalogue::install(&dir) {
        log::warn!("an imported look is not offered: {error}");
    }
    Ok(())
}

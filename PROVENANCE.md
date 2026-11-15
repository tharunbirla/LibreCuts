# NexusCut: CapCraft base migration (experimental)

This branch starts from CapCraft Android https://github.com/artcraft-android/capcraft-android at commit `31b4109dbee216612c5416eb400532e4d01a8a16`. It replaces the old NexusCut working tree only on this branch; the old history and existing branches remain intact. The parent before the migration is in the branch history. The previous tree's root license is retained at `legacy/LICENSE.previous` for reference.

CapCraft and its Concat engine are licensed under AGPL-3.0-or-later. The root `LICENSE`, upstream `NOTICE`, `THIRD_PARTY_NOTICES.md`, app notices and third-party licenses are retained; consult those notices for component-specific obligations. No relicensing of third-party code is implied.

Local modifications: optional uppercase and optional removal of the six indicated punctuation marks (period and comma between digits are preserved) for newly generated captions/lyrics. They run before caption chunks are inserted into the actual timeline, and affect the saved text, SRT export and rendering. The existing transcript and alternate caption panel retain their original defaults.

This is an initial technical migration, not a promise that old NexusCut projects can be opened or automatically transferred. NVIDIA BNR is not integrated. Upstream name, package and icon remain unchanged to avoid implying an established release or disrupting the build. The CI produces a debug APK for arm64 only. Device, model, rendering, SRT and export behavior require manual verification.

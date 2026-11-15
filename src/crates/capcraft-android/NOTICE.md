# CapCraft for Android: notices

CapCraft is free software: you can redistribute it and/or modify it under the terms of the GNU
Affero General Public License, version 3 or (at your option) any later version, as published by
the Free Software Foundation (`LICENSE`). It is built on the engine of Concat (Jareer and Concat
contributors, AGPL-3.0-or-later); the engine's own third-party notices are in
`THIRD_PARTY_NOTICES.md`.

CapCraft is not affiliated with, endorsed by or connected to ByteDance or CapCut.

## In the Android app

| Component | Licence | Use |
|---|---|---|
| Lucide icons (Lucide Contributors, portions Cole Bemis / Feather) | ISC (`LICENSE-lucide.txt`) | the app's icons, as vector paths |
| Material Symbols (Google) | Apache-2.0 (`LICENSE-material-symbols.txt`) | some of the app's icons, as vector paths |
| Material icons' camera glyph (Google) | Apache-2.0 (`LICENSE-material-symbols.txt`) | the launcher icon, redrawn with its body hollow |
| Kotlin standard library and coroutines (JetBrains) | Apache-2.0 | the app's code |
| AndroidX, Jetpack Compose, Material 3 (Google) | Apache-2.0 | the app's interface |
| JNA (Java Native Access) | Apache-2.0 | calling the engine from Kotlin |
| UniFFI (Mozilla) | MPL-2.0 | the engine's Kotlin bindings |
| `gif` and `png` crates (image-rs contributors) | MIT OR Apache-2.0 | writing GIF exports and freeze-frame stills |
| sherpa-onnx (Xiaomi Corporation, k2-fsa) | Apache-2.0 | running the speech-enhancement model for Isolate voice |

Models downloaded on first use (speech recognition, voices, background removal, picture enhancement) come with the
licences listed in `THIRD_PARTY_NOTICES.md`. Isolate voice downloads GTCRN (Xiaobin Rong and contributors, MIT), as
converted and published by the sherpa-onnx project.

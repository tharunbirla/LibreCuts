<p align="center">
  <img src="docs/images/capcraft-512.png" alt="CapCraft's app icon" width="128">
</p>

<h1 align="center">CapCraft for Android</h1>

<p align="center">
  <b>CapCraft-android is an open-source reimplementation of the CapCut android app.</b><br>
  Many credits to the original maintainers, <a href="https://github.com/jub0t">jub0t</a> and the Concat contributors.
</p>

<p align="center">
  <a href="https://github.com/artcraft-android/capcraft-android/releases"><img src="https://raw.githubusercontent.com/Kunzisoft/Github-badge/main/get-it-on-github.png" alt="Get it on GitHub" height="60"></a>
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/artcraft-android/capcraft-android"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="60"></a>
</p>

<p align="center">
  <img alt="Rust engine" src="https://img.shields.io/badge/engine-Rust-lightblue?style=flat&logo=rust&logoColor=white">
  <img alt="Kotlin and Jetpack Compose" src="https://img.shields.io/badge/UI-Kotlin%20%C2%B7%20Jetpack%20Compose-blue?style=flat">
  <img alt="Minimum: Android 10 (API 29)" src="https://img.shields.io/badge/min-Android%2010%20%28API%2029%29-blue?style=flat">
  <img alt="Target: Android 16 (API 36)" src="https://img.shields.io/badge/target-Android%2016%20%28API%2036%29-blue?style=flat">
  <img alt="License: AGPL-3.0-or-later" src="https://img.shields.io/badge/license-AGPL--3.0--or--later-blue?style=flat">
  <img alt="Status: beta" src="https://img.shields.io/badge/status-beta-lightblue?style=flat">
</p>

<p align="center">
  <a href="#editing">Editing</a> ·
  <a href="#filters-effects-and-transitions">Filters, effects &amp; transitions</a> ·
  <a href="#text-and-stickers">Text &amp; stickers</a> ·
  <a href="#captions-and-sound">Captions &amp; sound</a> ·
  <a href="#export-and-home">Export &amp; Home</a> ·
  <a href="#planned-features">Planned features</a> ·
  <a href="#building-instructions">Building</a>
</p>

<br>

<p align="center">
  <img src="docs/images/editor.png" alt="The editor: a moving gradient test clip with the title Made with CapCraft on a pink plate and a yellow WOW sticker, the transport row, and the timeline with the main track, a sticker row, a Rainbow effect row and Add audio, over the tools Edit, Audio, Text, Effects, Overlay, Captions, Filters, Adjust and Stickers" width="250">
  <br>
  <sub>The editor: the preview, the transport row, a timeline with rows for text, stickers, effects and sound, and the tools.</sub>
</p>

<br>

## Editing

<table align="center">
<tr>
<td align="center"><img src="docs/images/clip-tools.png" alt="A clip selected on the main track with its trim handles and length, and the clip tools: Split, Volume, Animations, Effects, Delete, Speed, Beats and Crop" width="250"></td>
<td align="center"><img src="docs/images/sounds.png" alt="The Sound FX panel: a search field, the kinds Swooshes, Pops, Bells, Booms, Game, Cartoon and Transition, and the sounds Whoosh, Swoosh down, Whip, Riser, Fall and Pop, each with its length and an add button" width="250"></td>
</tr>
<tr>
<td align="center"><sub>A clip selected: split, volume, animations, speed, crop and more</sub></td>
<td align="center"><sub>Sound effects, made by the app</sub></td>
</tr>
</table>

<br>

## Filters, effects and transitions

<table align="center">
<tr>
<td align="center"><img src="docs/images/filters.png" alt="The Filters panel on the Cinematic shelf: None, Teal &amp; Orange, Moody (chosen) and Cool Blue, each a preview of the clip itself, and an intensity slider with Apply to all" width="250"></td>
<td align="center"><img src="docs/images/effects.png" alt="The Video effects panel: the shelves Motion, Light, Distort, Retro, Stylize, Split and Blur, and the effects Shake, Flash, Pulse, Glitch, VHS, Old Film and Rainbow, each previewed on the frame" width="250"></td>
</tr>
<tr>
<td align="center"><sub>80+ filters, previewed on the clip</sub></td>
<td align="center"><sub>Video effects, on a clip or on a row of their own</sub></td>
</tr>
</table>

<p align="center">
  <img src="docs/images/transitions.png" alt="The Transitions panel at a cut: None, Dissolve, Blur Dissolve, Film Burn, Zoom, Slide, Push, Spin, Swirl, Glitch, RGB Split, Linear Wipe, Iris, Shape Wipe, Light Leak and Cube, every tile animating with the two clips" width="250">
  <br>
  <sub>Transitions, every tile animated with your own clips.</sub>
</p>

<br>

## Text and stickers

<table align="center">
<tr>
<td align="center"><img src="docs/images/text.png" alt="The text sheet for Made with CapCraft: the Templates, Fonts, Styles, Effects, Animations and Bubbles tabs, and templates such as BIG NEWS, Weekend, SALE, Day One, WOW!, ROAD TRIP, Cheers, LIVE, TOP 5, Sunday, Oh no!, Ready?, vibes, Chapter 1 and THE END" width="250"></td>
<td align="center"><img src="docs/images/stickers.png" alt="The Stickers panel on its Labels shelf: NEW, SALE, LIVE, WOW, OMG, LOL, HOT and TOP 10, with the WOW label placed on the preview" width="250"></td>
</tr>
<tr>
<td align="center"><sub>Text: templates, fonts, styles, word art, animations and bubbles</sub></td>
<td align="center"><sub>Stickers, labels and doodles, drawn by the app</sub></td>
</tr>
</table>

<br>

## Captions and sound

<p align="center">
  <img src="docs/images/captions.png" alt="Captions made on the phone from a speech test clip: The quick brown fox jumps over on a black plate, a row of caption clips on the timeline, and the tools Enter captions, Auto Captions, Caption templates, Auto lyrics and Import captions" width="250">
  <br>
  <sub>Captions, made on the phone from what is said.</sub>
</p>

<br>

## Export and Home

<table align="center">
<tr>
<td align="center"><img src="docs/images/export.png" alt="The export settings: Video, GIF and Photo tabs, and sliders for resolution (480p to 4k, set at 1080p), frame rate (24 to 60, set at 30) and bitrate, with the estimated file size" width="250"></td>
<td align="center"><img src="docs/images/home.png" alt="Home: New video and Edit photo, a row of recent projects, and the tools AutoCut, Retouch, Photo tools, Shoot and record, Auto enhance, Cover maker, Auto captions, Remove background, Adjust speed and Audio tools" width="250"></td>
</tr>
<tr>
<td align="center"><sub>Export at 480p to 4K, 24 to 60 fps, or as a GIF or photo</sub></td>
<td align="center"><sub>Home: start a video or a photo, then your projects</sub></td>
</tr>
</table>

<br>

## Planned features

CapCraft for Android is in beta.

| Area | Status |
|---|---|
| Projects from the phone's videos and photos, or shared to the app; autosave; rename, duplicate, delete | ✅ |
| The timeline: trim, move, split, delete, duplicate, replace, multi-select, undo and redo; pinch to zoom | ✅ |
| Speed and curves, freeze frame, reverse, volume and fades, crop, mask, chroma key, keyframes | ✅ |
| Overlays and layers with blend modes, opacity, transform and animations | ✅ |
| Filters, adjustments (HSL, curves, colour wheels), video effects, body effects and transitions | ✅ |
| Text: templates, fonts, styles, word art, bubbles, animations and text to speech | ✅ |
| Stickers, labels, shapes and drawing | ✅ |
| Captions made on the phone, typed captions, caption templates and imported subtitles | ✅ |
| Sound effects, voiceover, extract audio, voice effects, noise reduction and isolate voice | ✅ |
| Remove background (a model downloaded once, run on the phone) | ✅ |
| Aspect ratios, backgrounds, cover, AutoCut, collage | ✅ |
| Export at 480p to 4K, 24 to 60 fps, GIF and photo, saved to the gallery and shared | ✅ |
| A music library, downloadable effects and templates | ⬜ |
| Generative AI, cloud storage, accounts | 🚫 CapCraft is a local app |

<sub>✅ works today · 🚧 in progress · ⬜ not started · 🚫 not planned</sub>

<br>

## Building Instructions
*One Gradle command builds the app: it compiles the Rust engine with [cargo-ndk](https://github.com/bbqsrc/cargo-ndk) and generates the Kotlin bindings. FFmpeg and sherpa-onnx are fetched and built first, once.*

* Clone this repository
* Make sure you have at least 10 GB of free disk space
* In `src`, with `ANDROID_HOME` pointing at your Android SDK:
  ```
  scripts/ffmpeg-mobile.sh aarch64-linux-android
  scripts/sherpa-mobile.sh aarch64-linux-android
  eval "$(scripts/mobile-env.sh aarch64-linux-android)"
  ```
* then enter `src/crates/capcraft-android/android` and run `./gradlew assembleRelease`.

Build time dependencies:

<details>
  <summary>required tools</summary>

  ```
      Linux or macOS (on Windows, WSL)
      rustup (it installs the Rust release rust-toolchain.toml names)
      rustup target add aarch64-linux-android
      cargo install cargo-ndk --version 4.1.2 --locked
      Android SDK (API 37) and NDK 29.0.14206865
      JDK 17 or newer
      cmake, make, clang (libclang-dev), curl
  ```
</details>

*Builds are only tested in WSL Ubuntu on Windows 11.*

<br>

## License

[AGPL-3.0-or-later](LICENSE), CapCraft for Android is not affiliated with, endorsed by or connected to ByteDance or CapCut.

<br>

## Misc info for myself

Try to use [semantic commit](https://kapeli.com/cheat_sheets/Semantic_Commits.docset/Contents/Resources/Documents/index)
messages. Use `feat:` / `fix:` / `chore:` / `docs:` / etc.

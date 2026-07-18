# LibreCuts

<div align="center">
  <img src="src/images/featureGraphic.png" alt="LibreCuts Banner" width="100%"/>
  <br/>
  <br/>

  <a href="https://github.com/sponsors/tharunbirla">
    <img src="https://img.shields.io/badge/Sponsor_LibreCuts-ea4aaa?style=for-the-badge&logo=githubsponsors&logoColor=white" height="35" alt="Sponsor tharunbirla" />
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge" height="35" alt="License" />
  </a>
  <br/>
  <br/>
  <a href="https://github.com/tharunbirla/LibreCuts/releases/latest">
    <img src="src/images/badges/badge_github.png" alt="Get it on GitHub" height="96" />
  </a>
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/tharunbirla/LibreCuts">
    <img src="src/images/badges/badge_obtainium.png" height="96" alt="Get it on Obtainium" />
  </a>
  <a href="https://discord.gg/gwr3nE7YW">
    <img src="src/images/badges/badge_discord.png" height="96" alt="Join Discord" />
  </a>
</div>

<br/>

**LibreCuts** is a free, open-source video editor for Android that prioritizes simplicity, efficiency, and privacy. Built for seamless performance, it empowers creators to easily select, edit, and export watermark-free videos locally on their device.

---

## ✊ Keep Android Open

[![Keep Android Open](https://img.shields.io/badge/Keep-Android_Open-brightgreen?style=for-the-badge&logo=android)](https://keepandroidopen.org/)

Google's mandatory developer verification policy goes into effect in **September 2026** (in just a few months). This mandate requires all independent developers to submit government ID and centrally register with Google, threatening user privacy, sideloading freedom, and the distribution of free and open-source software (FOSS) on Android. 

Help resist this gatekeeping and support the movement at [keepandroidopen.org](https://keepandroidopen.org/).

---

## 🚀 Features

- **Trim** - Remove unwanted parts from the beginning or end of a video clip with a real-time timeline control.
- **Overlays** - Place text, stickers, images, GIFs, and video overlays on top of video clips to create engaging content. Includes support for continuous media looping.
- **Subtitles (Captions)** - Import custom `.srt` subtitle files with a dedicated toolbar slider for resizing and fully interactive touch-based positioning directly on the video preview.
- **Layer Management** - Easily reorder overlay layers to control what renders on top.
- **Audio** - Manage soundtracks effortlessly by importing custom music or audio tracks, recording voice overs, applying audio ducking and fades, amplifying volume up to 200%, and muting original audio.
- **Audio Export** - Export your project's entire audio mix as a standalone MP3 file.
- **Snapshots** - Capture and save high-quality frame grabs (snapshots) directly from the video editor.
- **Crop** - Adjust the aspect ratio of a video with custom cropping support.
- **Merge** - Combine multiple video segments into a continuous sequence with drag-to-rearrange functionality.
- **Transition** - Apply transitions with animated visual previews in the toolbar.
- **Speed** - Change the speed of a video clip using a custom speed slider for granular control.
- **Adjust & Filters** - Modify video brightness, contrast, saturation, and apply color filters.
- **Reverse** - Reverse video playback.
- **Timeline Organization** - Enhanced editing with snapping functionality, overlay duplication, freeze frame actions, and improved UI visual styling.
- **Hardware Acceleration** - Super-fast and reliable video exports using device hardware-accelerated `h264_mediacodec` encoding (with seamless automatic fallback to software encoding for maximum device compatibility) and accurate FFmpeg progress calculation.

## 📱 Screenshots

<div align="center">
  <table>
    <tr>
      <td align="center"><img src="src/images/sc_1.png" width="100%" alt="Home Screen"/></td>
      <td align="center"><img src="src/images/sc_2.png" width="100%" alt="Editor Screen"/></td>
      <td align="center"><img src="src/images/sc_3.png" width="100%" alt="Audio Import"/></td>
      <td align="center"><img src="src/images/sc_4.png" width="100%" alt="Timeline"/></td>
    </tr>
    <tr>
      <td align="center"><b>Home Screen</b></td>
      <td align="center"><b>Editor Screen</b></td>
      <td align="center"><b>Audio Import</b></td>
      <td align="center"><b>Timeline</b></td>
    </tr>
  </table>
</div>

## 💖 Support LibreCuts

LibreCuts is built with passion and provided to the community for free. If this app has helped you create amazing videos, consider supporting its continued development! Your sponsorship helps keep the project alive and growing.

<div align="center">
  <br/>
  <a href="https://github.com/sponsors/tharunbirla"><img src="https://img.shields.io/badge/sponsor-30363D?style=for-the-badge&logo=GitHub-Sponsors&logoColor=%23EA4AAA" alt="GitHub Sponsors" /></a>
  &nbsp;&nbsp;
  <a href="https://www.patreon.com/tharunbirla"><img src="https://img.shields.io/badge/Patreon-F96854?style=for-the-badge&logo=patreon&logoColor=white" alt="Patreon" /></a>
  &nbsp;&nbsp;
  <a href="https://ko-fi.com/tharunbirla"><img src="https://img.shields.io/badge/Ko--fi-F16061?style=for-the-badge&logo=ko-fi&logoColor=white" alt="Ko-Fi" /></a>
  <br/>
  <br/>
</div>

## 🛠️ Getting Started

### Prerequisites

- Android Studio
- Android SDK

### Installation

1. **Clone the repository**:
   ```bash
   git clone https://github.com/tharunbirla/LibreCuts.git
   ```
2. **Open the project in Android Studio**:
   - Launch Android Studio and select "Open an existing Android Studio project."
   - Navigate to the cloned directory and select it.
3. **Build the project**:
   - Click on "Build" in the menu, then select "Make Project."
4. **Run the app**:
   - Connect an Android device or start an emulator.
   - Click on the "Run" button in Android Studio.

## 🔒 Permissions

LibreCuts requires the following permissions to function properly:

- **READ_EXTERNAL_STORAGE**: To read videos from the device.
- **WRITE_EXTERNAL_STORAGE**: (For older Android versions) To save edited videos.
- **POST_NOTIFICATIONS**: To show notifications related to video editing.
- **READ_MEDIA_AUDIO/VIDEO/IMAGES**: For accessing media files on devices running Android 13 (API level 33) and above.

## 🔧 Troubleshooting & Support

If you encounter any export failures, codec errors, or unexpected crashes during your editing workflow:
- Refer to our comprehensive [Error Codes & Troubleshooting Guide](https://github.com/tharunbirla/LibreCuts/wiki/Error-Codes-&-Troubleshooting) on the Wiki.
- Join our [Discord Community](https://discord.gg/gwr3nE7YW) for real-time support, suggestions, and app updates.

## 🤝 Contributing

Contributions are welcome! If you have suggestions or improvements, feel free to create a pull request or open an issue.

1. Fork the repository.
2. Create a new branch for your feature or bug fix.
3. Commit your changes.
4. Push to the branch.
5. Submit a pull request.

## 📝 License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

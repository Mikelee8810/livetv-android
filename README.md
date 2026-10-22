# Live TV for Android TV

A high-performance, modern Android TV live television streaming client engineered with a 1:1 TiviMate-style UI/UX, built natively in Kotlin and AndroidX Media3 (ExoPlayer).

Designed for Google TV, Android TV streaming boxes, and smart TVs with remote-first D-pad navigation.

---

## Features

- **TiviMate-Grade D-Pad Navigation**:
  - Full-featured custom canvas EPG grid with smooth 60fps scrolling, live time indicators, current-program progress bars, and channel logo caching.
  - Left Slide Rail (Search, TV, Recordings, My List, Settings).
  - Categorized channel browsing with multi-group filtering and channel counts.
  - Seamless in-guide Video Preview window with zero flicker and rounded-corner composition.
  - 1:1 TiviMate right-side slide-over Settings Drawer covering all 8 core configuration sections.

- **Playback & Engine**:
  - Powered by **AndroidX Media3 (ExoPlayer)** with hardware decoding and adaptive HLS streaming.
  - Multi-stream buffer tuning (Small, Medium, Large, Very Large) and audio decoder fallback.
  - Aspect ratio control: Auto/Fit, Fill, Zoom, 16:9, 4:3.
  - In-player quick-access OSD (Info bar, Channel Quick-Switcher, Audio track selection, Subtitle selection, Favorites toggle, Number Zap).
  - Background stream proxy via embedded `OnDeviceStreamServer` for local manifest proxying and authenticated stream headers.

- **Design & Theming**:
  - Custom modern theme system ("Lagoon" dark teal/amber default, AMOLED deep black, Slate neutral dark) with 6 selectable accent palettes (Lagoon, Ember, Orchid, Coral, Sky, Lime).
  - Custom vector icons and Leanback TV banner (no generic AI assets).
  - Adjustable typography scale (Small, Medium, Large).

- **TV Integration**:
  - Android TV Leanback TV Input Framework integration (`LiveTvInputService`, `LiveTvSetupActivity`).
  - TitanTV & XMLTV EPG ingestion with progressive snapshot caching.

---

## Building from Source

### Prerequisites
- JDK 17 (`JAVA_HOME` pointing to JDK 17)
- Android SDK (API 34)

### Build Debug APK
```bash
./gradlew :app:assembleDebug
```

Output APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### Install to Android TV Device via ADB
```bash
adb connect <DEVICE_IP>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## License
MIT License.

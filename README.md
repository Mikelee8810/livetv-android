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

## What's new in 4.1.0

- **Domain rot fixed.** DaddyLive moved: `dlhd.st` and `dlstreams.st` now redirect to `thedaddy.pk`. Every upstream domain lives in one place now, `StreamEndpoints.java` (primary `thedaddy.pk`, mirrors `daddylive.at`, `daddylive.app`), so the next domain hop is a one file change.
- **Live channel catalog.** The bundled `channels.m3u` was a frozen snapshot of 509 channels. The app now fetches the live 24/7 list (899 channels when this shipped), merges new channels into the guide, and caches the list for 12 hours so startup never waits on the network. The bundled file stays as the offline fallback.
- **Stronger stream resolver.** Extraction fallbacks ported from [dlhd-cli](https://github.com/Lunatic16/dlhd-cli): direct `.m3u8` scan, generic `atob()` scan, `file:` configs, plus 3 tries with exponential backoff on 5xx. Resolved URLs are cached 5 minutes instead of 30, because the streams are tokenized and time-limited. Non-playlist responses are rejected instead of stalling the player, and a segment level 403 now force-refreshes the playlist token and retries, like the manifest path always did.
- **Route failover.** Player retries used to replay the same dead URL. They now step through the resolver's 8 routes (including the `hub` player borrowed from [dlhd-web](https://github.com/Lunatic16/dlhd-web)), and the route that works for a channel is remembered and tried first next time.
- **A real DaddyLive EPG source.** The guide now also pulls [Mikoshi-nyudo/dlhd](https://github.com/Mikoshi-nyudo/dlhd)'s DaddyLive XMLTV, regenerated every 12 hours, so guide data no longer depends only on the reverse-engineered TitanTV guest flow.
## What's new in 4.2.7

- **Watchdog tuned by its own soak data.** The 4.2.6 ten-minute on-TV soak proved the replay loop is dead (zero errors, zero retries) but caught the new watchdog firing on tune and channel-change transients - rendered counts stuck at 2-3 frames while the renderer waited for its first keyframe - and one false verdict flipped the TV to the software decoder, which then dropped frames in bursts. The watchdog now has a 12-second grace period after every (re)start, only calls a freeze once a stream has genuinely rendered video (60+ frames), and resets its recovery count on every channel change.
- **Segments fail fast now.** The on-device proxy fetched stream segments with the manifest retry policy - three attempts, backoff sleeps, 12-second reads - so one segment that hung upstream could block for ~38 seconds, drain the whole buffer and read on screen as a long freeze. Segments (and their token-refresh retry) now make a single attempt on a short-timeout client; ExoPlayer hears about a dead segment in seconds and moves on. Page, manifest and token fetches keep the retry policy.
- **Decoder rebuilds are capped.** A stream that kept failing as a decoder error rebuilt the player forever, never surfacing an error or spending the normal retry budget. After three decoder rebuilds without reaching playing state, the normal retry and error path takes over.
- Line-by-line audit against MyTV-Android and OwnTV_Core: their players recover by re-preparing and keep configuration conservative - the same shape 4.2.6 moved to - so no further player changes were warranted.

## What's new in 4.2.6

- **The replay loop is fixed at the root.** The freeze watchdog was taking its "frames are rendering" heartbeat from VideoFrameMetadataListener. On the MediaTek hardware decoder that callback goes silent while the picture is perfectly fine, so the watchdog kept seek-rescuing a healthy stream every few seconds and replaying the buffered seconds forever - the freezing, buffering, same-clip-replaying behaviour reported on 4.2.5. The watchdog now reads DecoderCounters.renderedOutputBufferCount, the renderer's own count of frames actually displayed, and only acts when frames stop for a full 7 seconds while the timeline keeps moving. Its recoveries also changed to match how production players (and Media3's own MediaTek handling) do it: re-prepare first, rebuild the player on the other decoder second, and no seek-rescues.

## What's new in 4.2.5

- **The decoder default was the last pin.** On-device logs under 4.2.4 still showed software decoding: the Video decoder preference *defaulted* to Software on MediaTek hardware, so "hardware first" never engaged for anyone who had not changed the setting by hand. The default is now Auto (hardware first, software fallback), and Settings > Playback > Video decoder offers Auto alongside the manual choices. Anyone who deliberately picked Software keeps it.

## What's new in 4.2.4

- **Hardware decoding actually engages now.** The first 4.2.3 build asked for the hardware decoder but returned the platform's decoder list unordered - and on the Google TV Streamer that list starts with the software codec, so nothing changed on device (verified in its logs, still 400+ dropped-frame bursts). The selector now orders the list explicitly in both directions. The 4.2.3 notes below describe the rest of this speed pass, all still included.

## What's new in 4.2.3

Speed and smoothness, driven by on-device measurements:

- **Hardware decoding first on the Google TV Streamer.** The software AVC decoder could not keep up with HD: the TV's own logs showed dropped frames in bursts of 400+. The app now prefers the MediaTek hardware decoder, and the old freeze risk is covered two ways: the frame watchdog escalates to the other decoder when frames stop, and decoder errors climb the same ladder. Settings > Playback > Video decoder still forces either one.
- **Faster channel changes.** The guide now pre-resolves the channel you are resting on (the embed walk and token happen in the background), and a resolved channel stays cached for 15 minutes instead of 5, so zapping back is quick.
- **Calmer startup.** The EPG refresh waits 15 seconds after launch instead of fighting the first playback for CPU with its ~25 MB of downloads.
- **Bigger live cushion.** Playback now sits 10 seconds behind the live edge (was 8) with a 12-25 second buffer, trading a little latency for far fewer stalls.

## What's new in 4.2.2

- **The stutter is fixed.** Streams were dying about 25 seconds in, every time: the segment cache added in 4.2.0 also cached the live playlist, so every playlist reload served the same frozen manifest and playback ran off the end of the window. Live HLS no longer goes through a cache at all. On top of that, the retry budget no longer resets the moment a stream recovers (which made the failure loop forever), retries only change route on every second failure, and playback now waits for a solid buffer before starting or resuming.
- **The guide no longer goes blank.** A catalog reload that came back empty, or a remembered channel group that momentarily had nothing in it, could clear the whole guide while the preview kept playing. Empty reloads are now ignored and the guide falls back to the full channel list.
- **The app can update itself.** Settings > Other > Check for updates compares the installed version against the latest GitHub release, offers the download, and hands the new APK to the system installer. It also checks quietly a few seconds after the app starts - Settings > Other > Auto-check for updates turns that off.

## What's new in 4.2.1

- **New icon.** The thin TV outline is gone. The app now wears the ON AIR studio sign: a glossy red neon sign in a chrome frame, because that is what the app is.

## What's new in 4.2.0

- **Decoder fallback ladder** (pattern from [OwnTV_Core](https://github.com/ahXN00/OwnTV_Core)). A decoder failure no longer replays into the same broken decoder: the player climbs a ladder (your preference, then forced software, then forced hardware), rebuilds, and Stream health shows which step it is on.
- **Catch-up** (pattern from [ondo-iptv](https://github.com/markedoe/ondo-iptv)). A live programme's details now offer "Restart from beginning" and "Back to live", and a past programme offers "Watch from start" while it is still inside the stream's replay window. DaddyLive windows are short (about 30 seconds of segments), so catch-up reaches back minutes, not hours; the app says so plainly when a moment has slid out of the window instead of faking it.
- **UI polish.** The flat look is gone: main surfaces use a soft vertical gradient, guide channel cards and programme cells get rounder corners with subtle edges, the selected row carries an accent edge, the focused cell gets an accent ring, live programmes show an accent progress tick along the bottom of their cell, and menus, dialogs, and drawers get matching rounded corners, edges, and elevation.
- Still deliberately not included: OwnTV's libmpv second engine and Ondo's Xtream timeshift modes. DaddyLive streams are not Xtream and carry no timeshift parameters, and libmpv would triple the APK for a fallback the decoder ladder already covers.
- **Segment cache.** A 200 MB LRU ExoPlayer cache (pattern borrowed from StreamVaultX) so re-zapping and guide previews do not re-download live segments.
- **Real reminders, no fake recordings.** "Set reminder" now actually saves the reminder, arms it in AlarmManager, posts a notification before the program starts, survives reboots, and tapping the notification opens that channel. The old "Record" action and Recordings screen promised recordings the app never made, so they are gone.
- **Stream health.** Settings, Playback, Stream health shows the live route, codec, resolution, bitrate, buffer, retries, and dropped frames from the player watchdog.
- **Under the hood.** Media3 1.4.1 to 1.8.0, compileSdk 35, R8 minification and resource shrinking on release builds, `allowBackup` off, unit tests for the resolver and live catalog parser, and a GitHub Actions workflow that runs the tests and builds the APK on every push.

Patterns verified against the live DaddyLive player on 2026-10-30 with REA (Reverse Engineer Anything): the token API needs the nested player page as Referer with its origin, tokens live 120 seconds and renew at half-life, and only manifest requests carry the token.

## License
MIT License.

# LiveTV v4.4.0 — Full Audit & Fix Register

Date: 2026-11-03 · Base: v4.3.3 (main @ 74591ce) · Method: every source file read line-by-line
(4 parallel audit passes: video/player, navigation+rest, live-stream research, upstream diff),
plus live end-to-end stream probing with ffmpeg/ffprobe.

## Phase 1 — Inventory (61 source files, 11,825 lines counted; task brief said 11,617)

Kotlin/Java sources (lines): MainActivity.kt 1038 · OnDeviceStreamServer.java 1861 ·
PlayerEngine.kt 1103 · EpgRepository.java 627 · GuideView.kt 480 · PlayerOverlay.kt 771 ·
SettingsDrawer.kt 685 · TitanTvSource.java 439 · ChannelSyncHelper.java 407 ·
LiveTvInputService.java 330 · EpgSnapshot.java 313 · XmlTvParser.java 305 ·
ChannelCategorizer.java 300 · CdnLiveEpgSource.java 261 · Repo.kt 255 · ChannelCatalog.java 248 ·
Updater.kt 238 · LiveCatalog.java 211 · SearchPanel.kt 196 · Dialogs.kt 175 · ListPanel.kt 169 ·
Reminders.kt 166 · Prefs.kt 165 · ChannelClassifier.java 133 · Channel.java 119 ·
ProgramDetailsDialog.kt 90 · ChannelMenu.kt 76 · Theme.kt 68 · StreamEndpoints.java 67 ·
LiveTvSetupActivity.java 57 · Logos.kt 51 · Program.java 38 · EpgProgressiveMerge.java 37 ·
ChannelCategory.java 27 · CatalogReport.java 20 · StreamResolverTest.java 118 · UpdaterTest.java 38.
Plus AndroidManifest.xml 49, 22 res XML files, gradle files (app 42 / root 4 / settings 4 /
gradle.properties 3), proguard-rules.pro, assets (channels.m3u, titan_custom_channels.json).
Detailed per-pass registers: hidden-audit-video.md (V-01–V-38), hidden-audit-nav.md
(N1–N24, R1–R9), hidden-research-live.md, hidden-diff-upstream.md (D1–D14) — working files in
this repo during the audit; this file is the shipped summary.

## Phase 2 — Live evidence (2026-11-03 ~19:00 EST, ffprobe/ffmpeg verified)

- Domains: dlhd.pk and dlhd.st 301 → **thedaddy.pk** (200, current primary; StreamEndpoints
  correct). dlstreams.st → dlive.sx → thedaddy.pk. daddylive.at / daddylive.app/channel are
  200 front-ends, not resolver backends.
- **dlhd-cli (v2-dlhd.py) is broken live**: outputs only the fallback stream-<id>.php, never an
  .m3u8 (iframe regexes hardcode dlhd.st, no token flow). The app's flow is the correct one.
- End-to-end resolve WORKS (ch 51/302/339): thedaddy.pk/stream/stream-<id>.php → iframe
  dembed.st/premiumtv/daddy.php → plaintext EDGE / CHANNEL=premium<id> / SRC=…/index.m3u8.
  Token gate is Origin-sensitive (403 without dembed Origin, 200 ttl:120 with the app's headers).
- The token URL is a **media playlist, not a master**: no EXT-X-STREAM-INF, no audio group, no
  CODECS, no EXT-X-KEY. 6 segments (~36 s window) on tiktokcdn signed `.image` URLs.
- Segments: raw = PNG (512×769–1115, ctype 2); after IDAT inflate + unfilter, row 0 =
  `TIKTIKPX` + BE length + gzip. A standalone replica of the app's `unwrapPayload` decoded all
  probed segments to clean MPEG-TS: **H.264 Main 1280×720 L4.0 @ 59.94 fps + AAC LC 48 kHz
  stereo**; ffmpeg decoded 1080 video frames / 3 segments, zero errors.
  **Upstream video is healthy — not HEVC, not encrypted, not audio-only.**
- Upstream diff (StepDaddyLiveHD, daddylive-stream-resolver 2026-11-02, dlhd-web, DaddyLiveHD,
  EasyProxy, Kodi jetextractors): the known-good players **strip PDT and add nothing**;
  EasyProxy documents a sibling player variant as "green/black picture, working audio" in a
  valid TS and rejects routes only after fetching and inspecting a segment.

## Root causes fixed for "audio plays, video black"

1. **Synthetic PDT injection** (server): the proxy stripped upstream PDT and injected its own
   wall-clock-anchored PDT while the TS carries its own PTS timeline (~21 h offset measured) —
   the biggest app-side deviation from every current upstream player. → PDT now stripped, never
   synthesized (matches daddylive-stream-resolver/EasyProxy recipe).
2. **No route validation** (diff D1): a route was accepted + cached 15 min as "last good" on a
   manifest 200/regex alone, never fetching media — an audio-only/black-video variant, once
   picked, stuck. → resolve now fetches the first segment, unwraps it, and requires a video PID
   in the PMT (`tsHasVideoPid`, unit-tested) before accepting/caching a route.
3. **Audio-only rendition selectable + invisible** (V-01/V-05): track selector had no video
   floor; every watchdog required ≥60 rendered frames before it could see a freeze, so
   sound-with-no-picture ran forever. → min video bitrate + max size + viewport + tunneling in
   the selector, an onTracksChanged override that force-selects a real video track, a video
   track picker API, and a dedicated no-video watchdog verdict (READY + position advancing +
   no video format / 0 rendered frames → route change, then decoder flip).
4. **HLS fallback disabled** (V-02): the load-error policy returned null from
   getFallbackSelectionFor, killing variant fallback. → policy now extends
   DefaultLoadErrorHandlingPolicy (fallback inherited, fail-fast retries kept).
5. **Cross-rendition manifest collision** (V-03): last-good/PDT state keyed only id:route — the
   audio media playlist could be served as the master/video playlist. → last-good keyed per
   playlist URL; stale serving only answers for the playlist that failed.
6. **Token stamped on every sub-playlist** (V-10): session token + cache-buster appended to
   variant/audio manifests, invalidating their signatures. → token only on the top-level
   playlist (new `topLevel` flag through serve→proxyManifest, incl. the 403-renew path).
7. **Non-TS resources hard-failed** (V-04/D6): EXT-X-KEY keys, fMP4 init, WebVTT, raw AAC all
   threw in the TS unwrapper; everything served as video/mp2t. → byte-for-byte pass-through for
   genuine non-TS resources, payload-derived MIME, plus new unwrap coverage: TIKTIKTSGZ and a
   generic triple-sync TS scan (from daddylive-stream-resolver).
8. **Relative playlist URIs lost the parent query** (D5) → 404s on signed variant playlists.
   → playlist targets inherit the parent query (segments do not; t=/_= never inherited).
9. **Decoder/surface rebuild defects** (V-06/V-07/V-12/V-15/V-16/V-35): initPlayer(surfaceView)
   never stored the surface (rebuild = surfaceless, audio-only); flip double-attached the
   surface; ladder was %2 in the selector vs %3 in logs and collapsed off-Streamer; decoder
   query exceptions fed the rebuild loop; release() left stale surfaces; play() never reset
   readySinceMs. → all fixed in PlayerEngine.

## Navigation defects fixed (Phase 4)

- N1 Search UP-from-first-result was dead code (ListPanel never forwarded DPAD_UP/DOWN) →
  forwarded; callers that don't care return false.
- N5 Info-only rows were focusable dead stops → focusable iff the row has an action.
- N6 Main menu opened focused on item 2 ("TV Guide") → opens on item 1 ("Search").
- N7 closeSettings() end-action focused the hidden guide over fullscreen → guarded
  (!isFullscreen, no search/menu open).
- N8 Guide cursor time never advanced; after hours RIGHT was a swallowed dead key → selTime
  rides the wall clock in the tick; indexOfFirst == -1 re-anchors to now on LEFT/RIGHT.
- N9/N20 noted (dispatch vs onEdge duplication) — dispatch categories-BACK divergence left as
  documented below; dead menu-item listener left in place (harmless, dispatch wins).
- N10 Settings focus memory only saved on re-render → recorded on push/handleBack.
- N11 ListPanel.focusRow 80 ms delayed grab could steal focus after the surface changed →
  submit-generation + isShown guard.
- N12 Digits handled before the quick-list check (typing in quick list zapped channels) →
  quick-list check first.
- N14 PIN dialog lacked numpad digits/NUMPAD_ENTER → added.
- N15 Long message dialogs couldn't be scrolled → ScrollView focusable.
- N16 Settings/Search didn't disable the guide behind them → guideView.isFocusable toggled
  in show/close for both (mirrors the drawers).
- N18 GuideView scroll animator not cancelled on detach → cancelled.
- N21 Initial reload callback yanked focus to the guide behind open drawers → guarded.

## Remaining defects fixed (Phase 6)

- N2 EPG progress callback fired per source: Settings "Update EPG now" stacked a dialog per
  source and cleared in-flight guards after the first. → Repo treats per-source callbacks as
  progress; completion effects run once, on the terminal report.
- N3 A failed EPG load overwrote the good guide with an empty snapshot and stamped it fresh
  (30 min suppression). → total failure keeps the previous guide, no timestamp, honest status.
- N4 Dead settings wired: Confirm-exit now read (Prefs default flipped to true to preserve the
  actual historical behaviour), seek keys use prefs.seekStepSec, number zap honours
  prefs.numberZap. Still dead (documented, not wired): auto-start on boot/wake toggles (no
  boot-launch component exists — wiring needs a manifest receiver + product decision), UDP
  proxy (no proxy consumer exists in the player path), playerClock/sleepMinutes/pinMethod/
  pinRelock/guideHours/epgKeepDays (Prefs-only remnants with no UI surface after Se6).
- R1 EPG cache filename was 32-bit String.hashCode → SHA-256 of the source URL.
- R3 Logos waiter list grew per guide draw call → capped at 8 waiters per URL.
- R6 XmlTvParser fabricated a stop-less programme's stop at the window end (+24 h) → capped
  at start + 2 h.
- R7 XmlTvParser dropped minute-precision XMLTV timestamps silently → HHmm formatters added.
- V-08 is resolved by the PDT change (no PDT is emitted for any playlist now).
- V-13 (extension-renderer mode is global, driven by the audio pref) — documented, not
  changed: no extension video renderers are on the classpath today, so the mode is a no-op
  for video; subclassing the factory is risk without a device to verify on.

## Not fixed, with reasons

- D3/D4 (resolver implements 2 of ≥9 upstream extractor families; one-hop iframe regex):
  the dembed family is the one live-verified working today (Phase 2); porting 7 more
  extractor families blind, with no failing live sample, risks breaking the working route.
  The new segment-level validation at least makes a dead/audio-only route fail over to the
  next route instead of sticking.
- D8/D13 (WebM/hub AES, StepDaddy CHANNEL_KEY/newkso flow): same reason — not the live path
  (StepDaddy snapshot is 2025-10-13, superseded by dembed in the 2026 resolver).
- D2 (player pinning across re-resolve): partially mitigated — the route cache still re-walks
  after 2 manifest failures by design (S1); pinning conflicts with the token-renewal design.
- V-09 (`.m3u8` substring classification), V-11 (per-rendition prefetch caches), V-17 (no
  onStop/PiP handling; lifecycle resume re-seats at live edge — deliberate per audit M4),
  V-22–V-34 med/low items, N13/N17/N19/N22–N24, R2 (single-thread EPG pool; coalesced-callback
  drop — mitigated by N2's terminal handling in Repo, pool sizing left for a device-verified
  change), R4/R5: logged in the hidden registers; lower severity than the shipped fixes and
  each needs on-device verification this environment cannot do (no Android device/SDK here).
- N4 leftovers listed above.

## Phase 7 — Verification

- Unit tests + builds run in GitHub Actions (no local JDK/Android SDK on this VM): see the
  v4.4.0 PR/branch runs and the release run linked in the release notes.
- Proxy-level proof: the Phase-2 replica of unwrap+decode (ffmpeg, 1080 frames, H.264+AAC)
  stands; `tsHasVideoPid` is covered by new unit tests (video PMT accepted, audio-only PMT
  rejected) alongside the existing TIKTIKPX fixture test.
- versionCode 30 / versionName 4.4.0.

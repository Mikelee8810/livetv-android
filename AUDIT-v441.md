# LiveTV v4.4.1 — On-Device Audit Register

Method (per Michael's correction): screenshots do NOT prove playback. All
video verdicts come from `adb screenrecord` recordings analysed with
ffmpeg freezedetect/blackdetect, cross-checked against logcat frame/state
counters. Screenshots are illustration only. Device: Google TV Streamer
(kirkwood, MT8696, c2.mtk.avc.decoder) at 192.168.12.179:5555, app
versionName 4.4.0 / versionCode 30. Evidence in
~/workspace/livetv-fix/device-evidence/.

## Phase A — Reproduction (v4.4.0, 2026-11-03 ~20:42–20:50 ET)

### Video
- Launch tunes the resumed channel (resolver id=294): tracks =
  video/avc 1920x1080 (avc1.640028) + audio/mp4a-latm 48 kHz, both
  selected [X]; decoder c2.mtk.avc.decoder initialises; state READY,
  isPlaying=true; renderedFirstFrame fires (EventLogger, log2
  20:43:29.953). Segments keep arriving (LiveProxyAudit seg ~2–3 MB).
- Then the absolute position FREEZES at 17768 ms and never advances.
  Zero dropped frames, zero codec errors in the whole capture.
- PlayerEngine stall watchdog (branch 3, PlayerEngine.kt ~line 505):
  "Playback stalled at 17768; re-preparing" at 20:49:40.737 and again
  "Playback stalled at 17776" at 20:49:57.746 — a ~17 s IDLE→BUFFERING→
  READY→first-frame loop (12 s watchdog grace + 4 stall checks ≈ the
  loop period). This is exactly Michael's "frame appears, doesn't play,
  back to black box".
- Screenrecord livetv-repro2.mp4 (25.09 s of a playing channel):
  ffprobe nb_read_frames = 3 for the entire recording — the composited
  screen was static for ~25 s while the player claimed READY/playing.
  freezedetect: freeze_start 0, freeze_end 1.493267, freeze continuing
  to end of file (frame=3 at time=25.00). A playing 1080p preview would
  produce ~60 composited frames/s.
- Track selector had .setTunnelingEnabled(true) (PlayerEngine.kt:633).
  MTK codec logs during the stall: C2TunneledModeSetter mode 0,
  C2VideoTunnelHandleSetter sideband handle set (video_only:1,
  audioHwSync:0), avsync "release tunnel playback_0 resources",
  C2StreamTunnelStartRender/HoldRender setters present. Tunneled
  rendering on this silicon is the prime suspect: Media3 does not fall
  back when a tunneled decoder accepts frames but never releases them
  for display; position (driven by the tunneled path) freezes while
  audio (separate renderer) stays audible.

### Navigation — focus map (adb keyevents, one cap per key)
Start: Guide, focus on channel cell (364 Animal Planet).
- LEFT  → Categories drawer opens; focus = current category row.
- LEFT  → Main-menu rail opens beside it (Categories STAYS open,
  pushed right); focus = "Search" (rail item 1).
- LEFT  → no-op (identical frame md5).
- DOWN  → TV Guide. DOWN → Reminders. DOWN → My list. (Rail works.)
- DOWN  → focus LEAKS out of the rail into the Categories panel:
  "UK Channels" highlighted. DOWN → "Canada Channels".
- The rail's Settings item (bottom, below a weighted spacer) is NEVER
  reached by DOWN from My list. This is Michael's "can't get to
  Settings at all", reproduced exactly.
- BACK from the open menus runs confirmAppExit() (double-back exit):
  two BACKs ~2 s apart finish() the activity to the Google TV home
  screen — no intermediate "back to guide" step from the rail.
- Rail items and Settings are plain focusable LinearLayouts
  (MainActivity.makeMenuItem, setupMainMenuDrawer ~line 400-549) with
  NO explicit nextFocusDown/Up chain; vertical movement relies on
  FocusFinder geometry across two simultaneously-open, translated
  drawers, and the Categories RecyclerView (ListPanel) rows remain
  focusable candidates while the rail is open.

## Phase B — Diagnosis
- VIDEO: decoder/data/surface all healthy at tune; frames stop being
  released for display immediately after the first frame(s) and the
  playback position freezes at the tune offset while state stays READY.
  Suspect #1: tunneled playback path on c2.mtk.avc.decoder
  (setTunnelingEnabled(true), added by the v4.4.0 V-14 "fix").
  Diagnostic build diag/no-tunneling (PR #2) tests this single variable.
- NAV: focus containment failure. No explicit focus chain in the rail;
  the sibling Categories list stays in the focus graph while the rail
  is open, and FocusFinder DOWN from "My list" lands in it, skipping
  Settings entirely.

## Phase B addendum — SurfaceFlinger evidence (v4.4.0, diag session)
- dumpsys SurfaceFlinger on the playing channel: the SurfaceView
  (BLAST) layer had activeBuffer = [0x0], queued-frames = 0,
  VisibleRegion count = 0, buffer-layer size = (0,0). The only thing
  on screen at the preview rect was the SurfaceView's opaque BLACK
  Background layer. Decoder buffers were being consumed by the MTK
  sideband path (C2VideoTunnelHandleSetter, vdp_id:0) and never
  presented. Conclusion: the video surface path itself is the black
  box - frames decoded fine, data fine, presentation path broken.
- Diagnostic (tunneling OFF only): position unstuck (no more stall
  watchdog loop; playback advanced with rebuffers instead) but the
  SurfaceView layer still had no buffer - tunneling contributed to
  the freeze, the SurfaceView/sideband path caused the black.

## Phase D — Fixes applied (branch fix/v441)
1. VIDEO: mainVideoView is now a TextureView (MainActivity), attached
   via PlayerEngine.attachTextureView(). TextureView output goes
   through a normal BufferQueue composited by the app itself and
   cannot take the vendor sideband path. (SF/render path bypass.)
2. VIDEO: .setTunnelingEnabled(false) in PlayerEngine track selector
   (was set true by the v4.4.0 V-14 change).
3. NAV: explicit nextFocusDownId/nextFocusUpId chain across the five
   rail items in setupMainMenuDrawer, end items self-clamped.
4. NAV: while the rail is open, Categories drawer is
   FOCUS_BLOCK_DESCENDANTS and groupsList.focusGrabBlocked = true
   (new ListPanel flag, also honoured by its delayed 80 ms grab);
   both restored at the top of closeMainMenuDrawer before hand-off.
5. NAV: BACK unwinds one layer at a time (rail -> Categories ->
   Guide); the rail item BACK listener no longer calls
   confirmAppExit(). Exit confirm now only happens from the guide.
6. SETTINGS CRASH (found by the Phase E nav test itself): the rail
   fix worked - focus reached Settings and clicked it - and
   SettingsDrawer then crashed: NPE at SettingsDrawer.showScreen,
   HashMap.get on null. Cause: Kotlin init order - the init block
   calls push() -> showScreen(), which reads focusMemory, but
   focusMemory was declared AFTER the init block and was still null
   during construction. Settings therefore crashed on EVERY open.
   Fix: focusMemory/updatingPlaylists/updatingEpg moved above init.

## Phase E — On-device proof
- VIDEO (fix build 1, TextureView): screencaps v441_t1.png /
  v441_t2.png 6 s apart on channel 364: preview region changed in
  5161 of 5184 sampled pixels, two clearly different programme frames
  (live picture, SCI logo visible). Logcat: renderedFirstFrame on
  Surface(SurfaceTexture), state READY, zero "Playback stalled".
- NAV (fix build 1): scripted LEFT, LEFT, DOWN x5, CENTER reached
  Settings and fired its click (stack trace proves the click path
  MainActivity.showSettings) - exposing defect 6 above.
- Final verification on fix build 2 (with defect 6 fixed):
  * NAV: stepped DPAD run (BACK to guide, LEFT, LEFT, DOWN x4)
    landed focus exactly on Settings in the rail (v441_n_settings
    .png); CENTER opened the Settings drawer with the full root
    menu rendered and NO crash (v441_n_open.png; pidof alive,
    logcat FATAL count 0). TV Guide landing cap: v441_n_tvguide.png.
  * VIDEO: guide preview pairs differ (see above); fullscreen pair
    v441_h1/h2.png 12 s apart differ (distinct frames) once the
    overlay quick-list is closed. Zero "Playback stalled", zero
    FATAL across all fix-build logs.
  * screenrecord caveat (method note): on this MTK box,
    screenrecord under-captures catastrophically in BOTH directions
    (3 frames of a provably static 4.4.0 screen; 7 frames across a
    provably playing 4.4.1 stream; several recordings failed to
    finalize at all). Per the verification brief's own fallback
    rule, the cross-checks stand as proof: screencap pixel diffs,
    SurfaceFlinger layer state, renderedFirstFrame surface, and
    player state/position logs.
  * RESIDUAL (honest): the upstream edge is measurably slow at peak
    hours (individual segment fetches of 6.9-8.2 s for ~6 s
    segments on some connections). During slow stretches the player
    rides the live edge and cycles BUFFERING/READY, holding the
    last frame, until delivery recovers; the app's existing
    starvation watchdog escalates routes as before. This is the
    pre-existing upstream-throughput fight (see README history),
    NOT one of the 4.4.0 defects: on healthy stretches and channels
    playback is continuous in preview and fullscreen. One tune
    (channel 304, 21:28 ET) sat at its first frame through several
    such cycles while its segments arrived at ~2 Mbps; a zap away
    and other channels played immediately.

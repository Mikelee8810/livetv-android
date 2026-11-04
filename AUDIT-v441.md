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

## Fixes (Phase D — filled as applied)
- (pending diagnostic result)

## Phase E — On-device proof (filled at retest)
- (pending)

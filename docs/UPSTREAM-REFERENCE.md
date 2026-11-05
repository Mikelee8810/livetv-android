# Upstream & Device Reference — read this before touching playback

Single reference for everything learned about the DaddyLive/DLHD upstream and
the target hardware, so it never has to be rediscovered. Verified live on the
dates noted; domains rot often — re-verify before relying on any host.

## Links Michael provided (2026-11-03)

- Operator's X profile: https://x.com/daddylive5
- Live TV front-end: https://daddylive.at/
- Channel pages: https://daddylive.app/channel
- Extractor CLI: https://github.com/Lunatic16/dlhd-cli
- This app's releases: https://github.com/Mikelee8810/livetv-android/releases

## Domain map (verified 2026-11-03)

| Host | Status |
|---|---|
| `thedaddy.pk` | **Primary.** Where the resolver must point. |
| `dlhd.pk`, `daddylive.at`, `daddylive.app` | Mirrors / front-ends (200). |
| `dlhd.st`, `dlstreams.st` | Dead as origins — 301 to `thedaddy.pk` (dlstreams via dlive.sx). Do not hardcode. |

All hosts live in one file: `StreamEndpoints.java`
(`PRIMARY` + `MIRRORS` + `BASES`). A domain hop = edit that file only.

## Upstream projects worth diffing against

| Project | What it's for | Notes |
|---|---|---|
| https://github.com/gookie-dev/StepDaddyLiveHD | Self-hosted DLHD proxy, most-starred (~202★, active Nov 2026) | Prime resolver/proxy reference. |
| https://github.com/Lunatic16/daddylive-stream-resolver | Embed → direct HLS/WebM, referer bypass, playlist generation | Updated 2026-11-02 — **newer than dlhd-cli**; current iframe/key flow + the generic TS-scan unwrap came from here. |
| https://github.com/Lunatic16/dlhd-cli | Original extractor Michael linked | **Broken live (Nov 2026):** regexes hardcode `dlhd.st`, never yields an .m3u8. Historical reference only. |
| https://github.com/Lunatic16/dlhd-web | Web player | Source of the "hub" route in the resolver's route list. |
| https://github.com/Metroid2023/DaddyLiveHD + https://github.com/realbestia1/EasyProxy | Playlist + proxy pattern | EasyProxy documents a sibling player showing "green/black picture, working audio" and validates routes by inspecting a segment — the pattern behind this app's video-PID route validation. |
| https://github.com/Mikoshi-nyudo/dlhd | DaddyLive XMLTV EPG | Regenerated ~12h; the app's second EPG source. |
| Kodi `jetextractors` `daddylive.py` (several repos) | Extractor logic | Domain lists go stale fast; logic reference only. |

## The stream flow (measured end-to-end, 2026-11-03)

1. `GET {base}/watch.php?id=<id>` → `GET {base}/stream/stream-<id>.php`
2. Page embeds an iframe on **dembed.st** → token playlist.
   Playlist is **Origin-gated**, token **TTL ~120 s**; the app's headers pass.
3. The playlist is a **media playlist** (no master variants, no audio group,
   no EXT-X-KEY), ~6 segments ≈ 36 s window, segment URLs on a signed
   `tiktokcdn` host with `.image` suffixes.
4. Segments are **PNG-disguised**: TIKTIKPX container, gzip after IDAT;
   inflate + unfilter to recover the TS (`OnDeviceStreamServer.unwrapPayload`).
   A replica unwrap + ffmpeg decode is clean: **H.264 Main 1280×720 @ 59.94 fps
   + AAC LC 48 kHz**. Upstream video is healthy — not HEVC, not encrypted,
   not audio-only. (Also seen: TIKTIKTSGZ and plain-TS passthrough.)
5. Known-good players **strip PDT and add nothing**. Never synthesize PDT —
   injecting wall-clock PDT against the TS's own PTS timeline caused
   black video (fixed v4.4.0).

## Throughput — the freeze mechanism (measured)

- Segments are ~1.2–1.9 MB for ~6 s of media (~2–2.5 Mbps needed).
- Daytime (2026-10-30): one connection ≈ at/below real time (2–4 Mbps);
  4 parallel connections aggregate ~9.3 Mbps. Hence the bounded prefetch
  design in the on-device proxy (max 3 warmed, 12-entry LRU).
- **Peak hour (2026-11-03 21:58 ET, device logcat `LiveProxyAudit`):**
  segment downloads took **0.7 s to 11 s** — many at 5–11 s for 6 s of video,
  i.e. slower than playback. Buffer drains → picture freezes on a frame →
  brief recovery → freezes again, audio continues. This is the residual
  freeze Michael still sees at night on v4.4.1; it is an edge/throughput
  problem, not a decoder problem. Levers not yet exhausted: more parallel
  fetchers, deeper prefetch, throughput-triggered route failover.

## Target device playbook — Google TV Streamer (MediaTek "kirkwood")

- LAN `192.168.12.179:5555` (adb over the desktop bridge). Philips TV
  `192.168.12.102` has no install.
- **Tunneling + SurfaceView sideband output never presents on this chip**
  (SurfaceFlinger layer buffer `0x0` = black box while audio plays).
  Shipped fix (v4.4.1): **TextureView video path, tunneling OFF.**
- **Verification standard (Michael's rule): a screenshot never proves
  playback.** Record a clip (`screenrecord`) and analyze it
  (ffmpeg `freezedetect` / `blackdetect`) + logcat.
  Gotcha found 2026-11-03: adb/SSH sessions to this box drop ~20 s in and
  kill an attached `screenrecord` mid-file (missing moov, 1-frame files) —
  start the recording detached on-device, let it self-finalize, then pull.
  Screenrecord also under-captures on this MTK box, so cross-check with
  SurfaceFlinger dumps (`dumpsys SurfaceFlinger`) and the app's
  `LiveProxyAudit` log lines (manifest serves + per-segment ms/bytes).
- Navigation: the rail needs an explicit `nextFocus` chain and the
  Categories panel must be focus-blocked, or Settings is unreachable;
  SettingsDrawer once crashed on every open (Kotlin init-order NPE) — both
  fixed in v4.4.1, proven by driving DPAD over adb to the open drawer.

## Version map

- v4.3.3 and earlier: SurfaceView + tunneling era; see `AUDIT-REGISTER-*`.
- v4.4.0: PDT strip, video-PID route validation, track-selector floor —
  code-correct but still failed on the real TV. See `AUDIT-v44.md`.
- v4.4.1: TextureView, tunneling off, rail focus chain, Settings NPE fix —
  device-verified. See `AUDIT-v441.md`. Peak-hour throughput freeze remains
  open (see Throughput above).

## Key files

`StreamEndpoints.java` (hosts) · `OnDeviceStreamServer.java` (resolver,
proxy, unwrap, prefetch) · `player/PlayerEngine.kt` (Media3, track
selection, watchdogs) · `MainActivity.kt` (surface, fullscreen, keys) ·
`ui/GuideView.kt`, `ui/ListPanel.kt`, `ui/SettingsDrawer.kt` (focus).

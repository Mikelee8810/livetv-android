# LiveTV — LIVE research findings (hidden)

Run: Tue 2026-11-03 ~18:58–19:02 EST (2026-11-04 00:00 UTC), from this VM via egress proxy.
Method: curl -sIL, the repo's own `v2-dlhd.py`, a Python replica of the Android app's resolver
(`OnDeviceStreamServer.resolveSingle`), a Python replica of its `unwrapPayload`, ffmpeg/ffprobe 8.1.2.
No app source was edited. Tokens / signed-URL secrets are redacted; hosts + path patterns only.

---

## 1. Domains — live redirect evidence (`curl -sIL`)

| URL | Result |
|---|---|
| `https://daddylive.at/` | **200 direct, no redirect.** Own page ("DaddyLive – DaddyLiveHD \| CrackStreams…"). A mirror front-end, not the resolver backend. |
| `https://daddylive.app/channel` | **200 direct, no redirect.** Own page ("Daddylive – DaddyLiveHD \| Live TV Channels…"). |
| `https://dlhd.pk/` | **301 → `https://thedaddy.pk/` → 200** |
| `https://thedaddy.pk/` | **200 direct.** Current primary. Title "HOME \| DaddyLiveHD … DaddyLive.PK – DLHD". |
| `https://dlhd.st/` | **301 → `https://thedaddy.pk/` → 200** (dead as a host, redirect only) |
| `https://dlhd.st/24-7-channels.php` | **301 → `https://thedaddy.pk/24-7-channels.php` → 200** |
| `https://dlhd.pk/24-7-channels.php` | **301 → `https://thedaddy.pk/24-7-channels.php` → 200** |
| `https://dlstreams.st/` | **301 → `https://dlive.sx/` → 301 → `https://thedaddy.pk/` → 200** |

**Final domain for everything the resolver uses: `thedaddy.pk`.**
`StreamEndpoints.java` is correct today: `PRIMARY=https://thedaddy.pk`, mirrors `dlhd.pk` (itself a
301 to the primary — useless as a fallback), `daddylive.at`, `daddylive.app` (both are separate
front-ends, not verified to serve `/stream/stream-<id>.php`).

## 2. Python CLI vs Android app — base domain & flow comparison

`dlhd-cli/v2-dlhd.py` (951 lines):
- Hardcodes `API_BASE = https://dlhd.st` (works only because it 301-redirects; `httpx` follows it).
- Its Stage-1 iframe regexes hardcode the host: `https?://dlhd\.st/stream/stream-…\.php` and
  `https?://dlhd\.st/…`. After the redirect the live pages are on `thedaddy.pk`, so both patterns miss.
- It has **no EDGE/CHANNEL/SRC + playlist-token flow at all**, and its token/header model is a static
  `Referer: https://dlhd.st/24-7-channels.php` / `Origin: https://dlhd.st`.
- **Live proof it is broken on the current site:** `v2-dlhd.py --id 51 --play` and `--id 302 --play`
  both "succeed" but output only the fallback `https://dlhd.st/stream/stream-<id>.php` (its
  `watch.php?id=` last-resort branch) — **not an .m3u8**. It never extracts a stream today.

Android app (`OnDeviceStreamServer.resolveSingle` + `StreamEndpoints`):
- Primary `thedaddy.pk`, routes `ROUTES = {ntvs, stream, cast, watch, plus, casting, player, hub}`,
  player page `/{route}/stream-<id>.php` (route 0 = `/stream/stream-<id>.php`).
- Follows the player-page iframe, then expects `EDGE = …`, `CHANNEL = …`, `SRC = …` in the player HTML,
  mints a token at `{EDGE}/api/playlist-token?slug={CHANNEL}`, appends `t=<token>&_=<ms>` to the
  manifest URL, renews at half the TTL. Fallback chain: `source: atob(…)` / joined-var fragments,
  then dlhd-cli-style direct-.m3u8 / any-atob / `file:` / playerFrame / data-url patterns.
- Its `request()` sends app UA (`Android 14; Google TV Streamer … Chrome/120`) + `Referer` +
  **`Origin` derived from the referer's scheme://host** — this turns out to be load-bearing (§3).

## 3. End-to-end LIVE resolve — channel 51 (ABC USA), replicated exactly

All steps live, 2026-11-03 ~23:58 UTC:

1. `GET https://dlhd.st/24-7-channels.php` → 200 at `https://thedaddy.pk/24-7-channels.php`,
   208,527 bytes, **900 `watch.php?id=` links** (51, 206, 283, 302, 303, …).
2. `GET https://thedaddy.pk/watch.php?id=51` → iframes: `https://thedaddy.pk/stream/stream-51.php` (×2).
3. Route pages for ch 51 (all 200, each with exactly one player iframe):
   `stream`/`watch`/`hub` → `https://dembed.st/premiumtv/daddy.php?id=51`;
   `cast` → `https://tossequip.net/e/…`; `plus` → `https://ehgembxe.cfd/…`;
   `casting` → `https://matchora.to/embed/channel/…`; `player` → `https://tv-bu1.blogspot.com/p/e1.html?id=51a`.
4. Player page `dembed.st/premiumtv/daddy.php?id=51` declares in plaintext:
   `EDGE = https://edge.wedd48c.top`, `CHANNEL = premium51`,
   `SRC = https://edge.wedd48c.top/premium51/index.m3u8`.
5. Token: `GET {EDGE}/api/playlist-token?slug=premium51`
   - With browser UA + `Referer: <player page>` but **no/wrong Origin → `403 forbidden`** (body: `forbidden`).
   - With the **app's exact headers** (app UA, Referer = player page, `Origin: https://dembed.st`)
     → **200 `{"token":"<redacted>","ttl":120}`**.
   - Bare `SRC` without token → `403 forbidden`.
   ⇒ The token gate is Origin-sensitive: Origin must be the **embed host's** origin, not the top site's.
   The app gets this right; dlhd-cli's static Origin would 403.
6. Final stream URL (pattern, token redacted):
   `https://edge.wedd48c.top/premium51/index.m3u8?t=<token>&_=<epoch-ms>`
   Same shape verified live for ch 302 (`premium302`) and ch 339 (`premium339`), same edge host.

## 4. Playlist anatomy — this is a MEDIA playlist, there is NO master playlist

Full live playlist for ch 51 (segment URLs redacted to host/pattern; identical structure for 302, 339):

```
#EXTM3U
#EXT-X-TARGETDURATION:7
#EXT-X-VERSION:3
#EXT-X-MEDIA-SEQUENCE:43477
#EXT-X-PROGRAM-DATE-TIME:2026-11-03T23:58:26.057Z
#EXTINF:6.006,
https://p19-common-sign.tiktokcdn-us.com/tiktok-obj-tx/<hash>~tplv-tiktokx-origin.image?dr=…&x-expires=…&x-signature=<redacted>&…
#EXTINF:6.006,
https://p16-common-sign.tiktokcdn-us.com/tiktok-obj-tx/<hash>~tplv-tiktokx-origin.image?…(signed)…
… (6 segments total, ~36 s sliding window)
```

Direct answers to the variant questions:
- **Multiple variants? NO.** Zero `#EXT-X-STREAM-INF`. The token URL *is* the media playlist.
- **Separate AUDIO group? NO.** No `#EXT-X-MEDIA` rendition tags at all (the one "MEDIA" hit is
  `#EXT-X-MEDIA-SEQUENCE`). Audio is muxed inside the TS segments.
- **First/top variant audio-only? N/A — there is no variant list to mis-select from.**
- **CODECS attributes? None present** (no master playlist to carry them).
- **`#EXT-X-KEY`? NO.** No encryption anywhere in the playlist. Segments are signed-URL only.
- Segment URLs: absolute, on `p16/p19-common-sign.tiktokcdn-us.com`, `….image` suffix,
  `Content-Type: image/png` upstream. HEAD → 200; `Range: bytes=0-262143` → **206, bytes identical**
  to the full download (the app's 256 KB parallel-chunk segment fetch is still viable on this CDN).

## 5. Segments — disguised PNG carrying gzip'd MPEG-TS (verified by decoding)

Downloaded segments 0–2 of ch 51 live (≈1.14 MB, 1.39 MB, 1.67 MB):

- `file`: `PNG image data, 512 x 769 / 512 x 933 / 512 x 1115, 8-bit/color RGB, non-interlaced`
  (width fixed 512, height grows with payload size — same family as the repo fixture
  `tiktikpx-sample.png`, 512x605, ch 339, 2026-10-30).
- Raw ffprobe of the .bin sees only a PNG still image — **a stock player demuxing the raw segment
  gets a picture, not a transport stream.**
- PNG structure: `IHDR` + many 8192-byte `IDAT` chunks + `IEND`; **no `TIKTIKPX`/`TIKTIKRAW` string
  anywhere in the raw file, nothing appended after IEND.** The marker is in the *decoded pixels*:
  after zlib-inflating IDAT and un-filtering the rows (bpp=3, ctype=2), row 0 begins
  `TIKTIKPX` + 4-byte big-endian length (1,179,995 / 1,432,513 / 1,711,772), followed immediately by
  gzip magic `1f 8b 08 00`. Gunzipping that payload yields clean MPEG-TS
  (sync `0x47` at 0 and 188, length an exact multiple of 188).
- Python replica of the app's `unwrapPayload` PNG branch decoded all 3 segments successfully —
  **the app's unwrap algorithm matches the live format today** (geometry/payload grew vs the fixture:
  heights up to 1115 vs 605, gzip up to ~1.71 MB vs 0.93 MB, TS up to ~1.75 MB).

### ffprobe of the UNWRAPPED transport streams (actual output, abridged)

seg0.ts (1,227,264 bytes), seg1.ts (1,470,912), seg2.ts (1,750,656) — all three identical in shape:

```
index=0  codec_name=h264  profile=Main  codec_type=video  width=1280 height=720  level=40  avg_frame_rate=60000/1001
index=1  codec_name=aac   profile=LC    codec_type=audio  sample_rate=48000  channels=2
format_name=mpegts  duration≈6.04  bit_rate≈1.62–2.32 Mb/s
Stream #0:0[0xd3]: Video: h264 (Main) ([27][0][0][0] / 0x001B), yuv420p(tv, bt709, progressive), 1280x720 [SAR 1:1 DAR 16:9], 59.94 fps, 59.94 tbr, 90k tbn, start 76534.930711
Stream #0:1[0xdd]: Audio: aac (LC) ([15][0][0][0] / 0x000F), 48000 Hz, stereo, fltp, 131 kb/s, start 76534.890711
```

**Full decode test:** `ffmpeg -i joined.ts -f null -` decoded **1080 video frames** for 3 segments
(≈360 frames / 6 s segment = full 59.94 fps) plus all audio, no errors reported in the tail output.
Video start PTS 76534.931 vs audio 76534.891 — 40 ms apart, normal. **The unwrapped stream is
completely healthy: H.264 (not HEVC), 720p, standard AAC, no encryption, no missing video PID.**

## 6. External / GitHub corroboration

- `Lunatic16/daddylive-stream-resolver` (created 2026-10-12, updated 2026-11-02 — the upstream of
  this app's own route names and of dlhd-cli) documents the identical live transport: dembed
  premiumtv edge, token minted per playlist fetch, "Origin must be exactly the embed host's origin —
  anything else, including the top-level site's, is rejected", segments "absolute PNG-wrapped
  MPEG-TS on TikTok CDN — no token, plain UA, no Referer", and **"Stock VLC/MPV on the CDN URL
  fail"** for these routes. Its known-good player recipe: **strip `#EXT-X-PROGRAM-DATE-TIME`
  entirely**, unwrap segments in the proxy, hls.js `lowLatencyMode:false`, `liveSyncDurationCount:4`.
  (The Android app does the opposite on PDT: it strips upstream PDT but then **synthesizes and
  injects its own PDT** before the first EXTINF.)
- `gh search repos daddylive` (sorted by updated): active 2026-11-03 projects
  (`Nadjib-b/daddylive`, `raselmia9/Daddylive_-channel`, `Mikoshi-nyudo/dlhd`,
  `Lunatic16/daddylive-stream-resolver`, `Lunatic16/dlhd-web`) all target the same
  thedaddy.pk / dembed flow; none shows a different (master-playlist / HEVC / AES) pattern for the
  24/7 `stream` route. Other routes differ (cast = `_econfig` HLS on rotating hosts, casting =
  plain `.ts`, player = wideiptv plain `.ts`) — plain-TS routes exist as fallbacks.
- Media3 docs (developer.android.com/media/media3/exoplayer/troubleshooting): MPEG-TS streams
  without AUDs / with only non-IDR keyframes stall the video renderer unless
  `FLAG_DETECT_ACCESS_UNITS` / `FLAG_ALLOW_NON_IDR_KEYFRAMES` are set. **The app already sets
  both flags** in its `DefaultHlsExtractorFactory` (plus `FLAG_IGNORE_SPLICE_INFO_STREAM`), so that
  classic cause is pre-handled in code.

## 7. Root-cause hypotheses for AUDIO-ONLY / BLACK VIDEO — ranked

What is **ruled out by live evidence**: domain rot (primary correct), resolver extraction (EDGE/
CHANNEL/SRC + token flow works end-to-end today), playlist variant/audio-only-rendition selection
(there is no master playlist), an audio-only or HEVC upstream (video is H.264 Main 720p59.94,
fully decodable), segment encryption (no EXT-X-KEY), Range/HEAD breakage on the segment CDN
(206 verified). Whatever goes wrong sits **between the proxy's segment response and the screen**.

1. **Video renderer/decoder on the target device (Google TV Streamer / MT8696), not the stream.**
   The TS demuxes and decodes perfectly in ffmpeg. In the app, video goes through a custom
   `MediaCodecSelector` that force-reorders decoders (HW first on the Streamer, SW on alternate
   escalation attempts) for `video/avc` Main@L4.0 720p**59.94**. A HW decoder that accepts the
   format, consumes samples, but never produces a first rendered frame is the textbook
   Media3 "audio plays, video black, no error" outcome, and this app already has a history of
   exactly this class of failure on this SoC (frame watchdog, decoder-escalation ladder,
   "software AVC decoder cannot keep up with HD" comments in `PlayerEngine.kt`).
   *Discriminator:* on-device `videoFormat` present + `DecoderCounters.renderedOutputBufferCount == 0`
   / dropped-frame storms in the app's own Stream-health screen while audio position advances.
2. **The proxy's synthetic PDT vs the TS's internal timeline.** Upstream PDT is wall-clock-correct;
   the known-good resolver strips PDT and injects none. The app strips it, then injects a
   synthesized PDT anchored from media-sequence/duration bookkeeping. The TS itself starts at
   PTS ≈ 76534.9 s. If the injected PDT places segments on a timeline Media3's HLS layer maps
   inconsistently with the TS timestamps, video samples (scheduled against the live window /
   period start) can be perpetually "not yet / already expired" while the audio renderer —
   driven by the same clock but far more tolerant — keeps playing. This is the largest
   *app-only deviation from the known-good implementation* found in this research.
   *Discriminator:* serve the same resolved playlist with PDT stripped entirely (no injection)
   and compare; or check whether `currentLiveOffset` / video sample timestamps on device are
   wildly offset from audio.
3. **On-device unwrap/chunk path degrading a subset of segments.** Live segments are ~2× the
   repo fixture's size (gzip payload up to 1.71 MB, PNG height up to 1115 vs fixture 605).
   The algorithm is proven correct at these sizes off-device, but on-device the segment arrives
   via HEAD + parallel 256 KB Range chunks, prefetch cache and ThreadLocal buffers; a corrupted
   or truncated reassembly makes `unwrapPayload` throw, the player skips that segment, and a
   run of such skips at the live edge can leave the video renderer starved/black while
   already-buffered audio continues. This predicts *intermittent*, channel/segment-dependent
   black video rather than a hard every-time failure.
   *Discriminator:* `LiveProxyAudit` logs — `UnwrapException` / "segment body too small" /
   short-chunk errors correlated with the black-video periods.
4. **Route-dependent fallback streams.** Only the `stream`/`watch`/`hub` (dembed) route was
   verified to carry the healthy H.264 TS. If a channel's last-good route memory sticks it to a
   different route (`plus` is documented upstream as currently unresolvable; `player`/`casting`
   use entirely different CDNs), its stream shape was not sampled here and could differ.
   *Discriminator:* which route index the failing channel resolved on (resolver logs).

**Bottom line:** upstream is healthy and the app resolves it correctly today. The black-video bug
is downstream of the (correct) unwrapped TS — most likely the device video-decoder path (#1),
with the app's synthetic-PDT injection (#2) the most suspicious app-side deviation from the
known-good reference implementation.

## Appendix — artifacts of this run (ephemeral, /tmp/dlhd/)

`channels.html` (live channel page), `player.html` (dembed player page), `master.m3u8` +
`master_302.m3u8` + `master_339.m3u8` (live playlists, signed URLs — treat as sensitive, they
expire/rotate), `seg0–2.bin` (raw PNG segments), `seg0–2.ts` (unwrapped TS), `unwrap.py`,
`resolve*.py`, `multi.py` (replication scripts). Nothing under `livetv-android/app/src` was touched.

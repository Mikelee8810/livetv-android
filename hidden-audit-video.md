# CODE AUDIT — Video / Player (hidden, do not ship)

Date: 2026-11-03 · Scope read in full, line by line. No source edited.

Files audited:
- `app/src/main/java/com/livetv/app/player/PlayerEngine.kt` (1103 lines) — cited as `PlayerEngine.kt`
- `app/src/main/java/com/livetv/app/OnDeviceStreamServer.java` (1861 lines) — cited as `OnDeviceStreamServer.java`
- `app/src/main/java/com/livetv/app/StreamEndpoints.java` (67 lines) — cited as `StreamEndpoints.java`
- `app/src/main/java/com/livetv/app/MainActivity.kt` (1038 lines) — cited as `MainActivity.kt`
- `app/src/test/java/com/livetv/app/StreamResolverTest.java` (118 lines) — cited as `StreamResolverTest.java`

Cross-checks run: `grep` for `clearVideo*`, `SurfaceHolder`, `unnel*` (tunneling), `setAspectRatio` callers,
track-selector parameters, and every `EXT-X-*` reference in the server (see V-00). Media3 version is 1.8.0
(`app/build.gradle.kts:35-37`).

---

## V-00 · Hunt questions answered directly (including non-defects)

These were checked explicitly so they are not re-hunted later:

1. **Does playlist rewriting strip CODECS or reorder/drop variants? NO — not in the master path.**
   `OnDeviceStreamServer.java:814-845` copies every non-PDT line verbatim except for URI rewriting:
   ```java
   for (String raw : manifestBody.split("\\r?\\n")) {
       String line = raw.trim();
       ...
       output.append(line).append('\n');
   }
   ```
   `#EXT-X-STREAM-INF` lines (with `BANDWIDTH/RESOLUTION/CODECS/AUDIO=/VIDEO=/SUBTITLES=`) and
   `#EXT-X-MEDIA` lines survive with attributes and order intact. Variants are only *indirectly*
   droppable/misroutable — via the `.m3u8` substring classification (V-09), the cross-rendition
   last-good collision (V-03), and the PDT strip (V-08). EXT-X-MEDIA audio groups are preserved
   as text, but their *playlists and segments* are then mishandled (V-03, V-04, V-10, V-11).
2. **Content-Type:** manifests are correct — `OnDeviceStreamServer.java:856`
   `"application/vnd.apple.mpegurl"`. Segments are **always wrong-typed for anything that is not TS** — V-04.
3. **Tunneling:** never enabled anywhere. Only hit for `unnel*` in `PlayerEngine.kt` is the selector
   parameter name at :544-545 — V-14.
4. **`clearVideoSurfaceView` / `clearVideoTextureView` / `SurfaceHolder.Callback`:** zero occurrences
   in `PlayerEngine.kt` and `MainActivity.kt` — V-07.
5. **`setAspectRatio` (PlayerEngine.kt:972) has no caller** in any audited file — dead code, V-28.
   (`MainActivity.kt:946` `Rational(16, 9)` is the unrelated PiP param, V-37.)
6. **Route count is consistent:** `PlayerEngine.kt:41` `const val ROUTE_COUNT = 8` matches
   `OnDeviceStreamServer.java:36` `ROUTES = {"ntvs","stream","cast","watch","plus","casting","player","hub"}` (8).
   `StreamEndpoints.java` itself contains no video/player defect; its only audit-relevant line is
   the legacy referer `StreamEndpoints.java:66` `NTVS_WATCH = "https://ntvs.cx/channel/phoenix/"`,
   used only as route 0's watch referer in `OnDeviceStreamServer.java` `resolveSingle()`.

---

## CRITICAL

### V-01 · Track selector has no video constraints at all — an audio-only HLS rendition is selectable and nothing ever corrects it
- Severity: **critical** · Area: (1) track selector
- Evidence — `PlayerEngine.kt:574-580` (the *entire* track-selector configuration in the app):
  ```kotlin
  val trackSelector = DefaultTrackSelector(context).apply {
      setParameters(
          buildUponParameters()
              .setPreferredAudioLanguages(prefs.preferAudioLang.ifBlank { "eng" }, "en", "eng")
      )
  }
  ```
  No `setMaxVideoSize`, `setMaxVideoBitrate`, `setMinVideoBitrate`, `setViewportSize`,
  `setForceHighestSupportedBitrate`, `setPreferredVideoMimeType(s)`, no preferred text language,
  no tunneling flag (V-14). Media3's adaptive video selection is therefore free to walk down to the
  lowest-bandwidth variant in the master — on masters that carry a low-bitrate audio-only
  `EXT-X-STREAM-INF` fallback (a common CDN pattern), that variant *is* a legal video-group
  selection. The server-side comments themselves document sustained delivery at "0.35-0.6x real
  time" (`OnDeviceStreamServer.java` `downloadRaw` javadoc), i.e. exactly the bandwidth collapse
  that drives adaptive selection to the bottom.
- Compounding evidence — there is no video-track API to recover with: the file exposes
  `getAudioTracks()` / `selectAudioTrack()` (`PlayerEngine.kt:1030,1045`) and subtitle equivalents,
  but no `getVideoTracks`/`selectVideoTrack`, and `PlayerEngine.kt:728-730` throws the evidence away:
  ```kotlin
  override fun onTracksChanged(tracks: Tracks) {
      onTracksChanged?.invoke()
  }
  ```
- Fix: set a floor and a ceiling in the parameters, e.g.
  `.setMinVideoBitrate(400_000).setMaxVideoSize(1920,1080)` (or the panel's real size),
  `.setViewportSize(...)` updated preview↔fullscreen, and in `onTracksChanged` verify a video
  track is actually selected/rendered; if the selected video-group format has no video
  (`format.sampleMimeType == null` / audio-only codecs string), force a
  `TrackSelectionOverride` to the lowest true-video variant and log it. Add a video-track picker
  mirroring the audio one.

### V-02 · HLS load-error policy disables rendition fallback — a bad video variant can never be switched away from
- Severity: **critical** · Area: (5) error policy / (1) track selection
- Evidence — `PlayerEngine.kt:859-865`:
  ```kotlin
  override fun getFallbackSelectionFor(
      fallbackOptions: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.FallbackOptions,
      loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
  ): androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.FallbackSelection? = null
  ```
  Returning `null` unconditionally removes Media3's HLS fallback mechanism (switch variant /
  switch track type on repeated segment load failure). A variant whose segments are corrupt,
  404ing, HEVC the decoder rejects at load level, or repeatedly mis-unwrapped (V-04) therefore
  errors the *whole playback* instead of falling back to a working rendition — and then V-20's
  generic retry replays the same variant on the same route.
- Fix: return the framework fallback when offered, e.g. prefer
  `FallbackSelection(FALLBACK_TYPE_TRACK, …)` / return `fallbackOptions`' first track-type
  fallback instead of `null`; at minimum do not override this method (the default HLS policy
  already implements variant fallback).

### V-03 · All renditions of a channel share one manifest cache key — the "last good" / PDT / duration state collides across master, video, audio and subtitle playlists
- Severity: **critical** · Area: (3) playlist rewriting
- Evidence — every per-playlist store is keyed only by channel+route, never by playlist URL:
  - `OnDeviceStreamServer.java:760` `String key = id + ":" + player;` → `SEG_DURATIONS.computeIfAbsent(key, …)` (:761) and `PDT_ANCHOR.get(key)` (:770)
  - `OnDeviceStreamServer.java:847` `LAST_GOOD_MANIFEST.put(id + ":" + player, output.toString());`
  - `OnDeviceStreamServer.java:904-914` `staleManifest()` returns `LAST_GOOD_MANIFEST.get(id + ":" + player)` for *any* manifest failure.
  Variant/audio/subtitle playlists are deliberately proxied through the same endpoint
  (`OnDeviceStreamServer.java:830-831`, `manifestUrl(id, player, target, …)`), so whichever
  playlist was rewritten last — e.g. the **audio** media playlist — overwrites the video
  playlist in `LAST_GOOD_MANIFEST`, merges its media-sequence/durations into the video
  rendition's `PDT_ANCHOR`/`SEG_DURATIONS`, and can then be served by `staleManifest()` in
  answer to a master/video request. ExoPlayer receiving an audio media playlist where it
  asked for the master/video playlist is a direct black-screen-with-audio (or hard parse
  failure) path. The PDT anchor corruption independently poisons `currentLiveOffset` for
  the video rendition (sequences/durations of two renditions interleaved under one key).
- Fix: key `LAST_GOOD_MANIFEST(_AT)`, `PDT_ANCHOR` and `SEG_DURATIONS` by
  `id + ":" + player + ":" + upstream-URL-hash` (the rewritten `url=` identity), and make
  `staleManifest()` only answer for the exact upstream URL that failed.

### V-04 · Every proxied resource is forced through the TS unwrapper and served as `video/mp2t` — encryption keys, fMP4 init segments, AAC/fMP4 segments and WebVTT can never play
- Severity: **critical** · Area: (3) playlist rewriting / segment unwrap
- Evidence — chain, all in `OnDeviceStreamServer.java`:
  - :823-833 — *any* `URI="…"` that is not `.m3u8` (i.e. `#EXT-X-KEY`, `#EXT-X-MAP` init segment,
    `#EXT-X-MEDIA` non-playlist assets) is rewritten to the generic resource endpoint:
    ```java
    String proxied = target.contains(".m3u8")
            ? manifestUrl(id, player, target, resolved.referer)
            : resourceUrl(target, resolved.referer, id);
    ```
  - :1352 / :1374 — every resource body is unwrapped as if it were a disguised TS segment:
    ```java
    return unwrapPayload(downloadRaw(url, referer, playerPriority));
    ```
  - :1399-1401 and :1684 — anything that is not (or does not contain) a TS payload is now a hard failure:
    ```java
    static byte[] unwrapPayload(byte[] bytes) {
        ...
        throw new UnwrapException("payload is not a transport stream");
    ```
    A 16-byte AES key, an `ftyp/moov` init segment, a raw AAC/`.m4s` segment and a `.vtt` file
    all fail this test by construction.
  - :1007-1011 — and whatever does survive is mislabelled:
    ```java
    private NanoHTTPD.Response segmentResponse(byte[] bytes) {
        ...
        return newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "video/mp2t", new ByteArrayInputStream(bytes), bytes.length);
    ```
  So: encrypted HLS (`EXT-X-KEY`) is unplayable, fragmented-MP4 HLS is unplayable, a separate
  AAC/fMP4 **audio rendition is unplayable** (its segments throw in the unwrapper — the other
  direct audio-only path, alongside V-01), and subtitles via `EXT-X-MEDIA TYPE=SUBTITLES`
  (playlist proxied as manifest, `.vtt` lines proxied as resources) are unplayable. This is
  safe only because DaddyLive's current shape is muxed TS / disguised TS; the first upstream
  shape change breaks playback wholesale, with the failure surfacing as a generic player error (V-20).
- Fix: route by resource kind, not by one endpoint: keys, init segments, subtitles and
  non-TS media must be proxied byte-for-byte with their real content type
  (`application/octet-stream` for keys, `video/mp4`/`video/iso.segment` for fMP4,
  `text/vtt` for subtitles). Apply `unwrapPayload` **only** to media segments of a TS
  rendition (e.g. `.ts` / disguised-image segment URLs), and derive `segmentResponse`'s
  MIME type from the segment kind instead of hardcoding `video/mp2t`.

### V-05 · Every watchdog is blind to "audio plays, no video" — the failure this audit was called for has no detector
- Severity: **critical** · Area: (5) error/retry masking, (2) surface/watchdog
- Evidence — `PlayerEngine.kt:444-448`, the only freeze verdict:
  ```kotlin
  if (p.videoFormat != null && rendered >= 0 && lastRenderedCount >= 60 &&
      positionAtRenderAdvance >= 0L &&
      now - lastRenderAdvanceMs > 7000L &&
      currentPos - positionAtRenderAdvance > 1500L
  ) {
  ```
  It requires a video format *and* ≥60 previously rendered frames. Audio-only selection
  (V-01), a dead/lost surface (V-06/V-07) and an unwrapper-broken video rendition all present
  with `videoFormat == null` or `rendered == 0` — the verdict can never fire. The remaining
  nets cannot fire either: the position net and stall verdict read `absPos(p)`
  (`PlayerEngine.kt:184-193`), which advances happily on audio, and the buffering net needs
  continuous `STATE_BUFFERING`, which healthy audio-only playback never enters.
  `onVideoCodecError` is log-only — `PlayerEngine.kt:616-619`:
  ```kotlin
  override fun onVideoCodecError(...) {
      Log.e(TAG, "VIDEO CODEC ERROR: ", videoCodecError)
  }
  ```
- Fix: add an explicit no-video verdict: if state is `READY`, `playWhenReady` is true,
  position has advanced ≥5 s since first READY and (`videoFormat == null` **or**
  `renderedOutputBufferCount == 0`), treat it as a video failure — re-select a true-video
  track (V-01), then reprepare, then route-escalate — instead of waiting for a freeze that
  is defined to be impossible in this state.

---

## HIGH

### V-06 · `initPlayer(surfaceView)` attaches a surface it never remembers — the next rebuild plays with no surface at all
- Severity: **high** · Area: (2) surface attach/detach
- Evidence — `PlayerEngine.kt:499` `fun initPlayer(surfaceView: SurfaceView? = null): ExoPlayer {`
  and :639-647:
  ```kotlin
  if (attachedTextureView != null) {
      player.setVideoTextureView(attachedTextureView)
  } else if (attachedSurfaceView != null) {
      player.setVideoSurfaceView(attachedSurfaceView)
  } else if (surfaceView != null) {
      player.setVideoSurfaceView(surfaceView)
  }
  ```
  The parameter is used for this one player instance but never stored into
  `attachedSurfaceView`. Any rebuild — decoder-error path `PlayerEngine.kt:705` `initPlayer()`,
  or `flipDecoderAndReload()` :248-253 — constructs the new player with no-arg `initPlayer()`,
  so if the original attach came through the parameter (the only public way besides
  `attachSurface`), the rebuilt player gets **no surface: audio continues, picture never
  returns**, and V-05 guarantees no watchdog notices. Today `MainActivity` happens to use
  `attachSurface()` (`MainActivity.kt:183`), which is why this is latent rather than live —
  the API invites the bug.
- Fix: in `initPlayer`, when `surfaceView != null`, assign `attachedSurfaceView = surfaceView`
  (and clear `attachedTextureView`) before attaching; or delete the parameter entirely and
  make `attachSurface()` the single entry point.

### V-07 · No surface teardown anywhere: no `clearVideoSurface*`, no `SurfaceHolder.Callback`, `release()` leaves stale surface references
- Severity: **high** · Area: (2) surface attach/detach
- Evidence:
  - `PlayerEngine.kt:739-751` — detach is attempted by passing `null` into the setter:
    ```kotlin
    fun attachSurface(surfaceView: SurfaceView?) {
        ...
        exoPlayer?.setVideoSurfaceView(surfaceView)
    }
    ```
    `clearVideoSurfaceView()` / `clearVideoSurface()` are never called (zero grep hits in
    either file), and `attachTextureView` switching kinds never clears the other kind's
    surface on the player first — it relies on Media3's setter side-effects.
  - `PlayerEngine.kt:1086-1096` — `release()` never detaches and never clears the fields:
    ```kotlin
    fun release(terminal: Boolean = false) {
        if (terminal) released = true
        mainHandler.removeCallbacksAndMessages(null)
        exoPlayer?.release()
        exoPlayer = null
        ...
    }
    ```
    `attachedSurfaceView`/`attachedTextureView` still reference the view after terminal
    release (leak; and a stale view is silently re-attached by the next `initPlayer()`, V-06 path).
  - `MainActivity.kt` registers **no** `SurfaceHolder.Callback` (zero hits): nothing observes
    `surfaceDestroyed`. With `MainActivity.kt:173`
    `setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)` (API 34+), any
    detach of `mainVideoView` destroys the surface while the player still targets it; the
    decoder then renders into a dead surface until the next rebuild — again invisible to V-05's watchdogs.
- Fix: call `clearVideoSurfaceView()`/`clearVideoTextureView()` in `release()` and when
  switching view kind; null both `attached*` fields on terminal release; register a
  `SurfaceHolder.Callback` in `MainActivity` that detaches on `surfaceDestroyed` and
  re-attaches on `surfaceCreated`.

### V-08 · Upstream PDT is stripped unconditionally; the synthetic replacement is conditional — id-less/external playlists lose live timing entirely
- Severity: **high** · Area: (3) playlist rewriting
- Evidence — `OnDeviceStreamServer.java:816-818` strips for every playlist:
  ```java
  if (line.startsWith("#EXT-X-PROGRAM-DATE-TIME")) {
      continue; // Upstream PDT drifts in bursts; replaced by the synthetic one below
  }
  ```
  but the replacement at :737-798 is only computed `if (id != null)` with a parseable
  `#EXT-X-MEDIA-SEQUENCE` **and** at least one parseable `#EXTINF`, and is only emitted
  once, before the first `#EXTINF` (:819-822). Any playlist failing one precondition —
  external `sources=` manifests and `url=` manifests served without an `id` are the standing
  cases — is returned with **no PDT at all**: Media3 then has `currentLiveOffset ==
  TIME_UNSET`, which disables the live-offset/desync logic and degrades the live playback
  speed control for exactly the non-DaddyLive feeds.
- Fix: only strip upstream PDT when a synthetic replacement was actually computed for
  this playlist; otherwise pass upstream PDT through untouched.

### V-09 · Playlist vs segment classification is a raw `.m3u8` substring test — misclassifies in both directions
- Severity: **high** · Area: (3) playlist rewriting
- Evidence — `OnDeviceStreamServer.java:830` and :837:
  ```java
  String proxied = target.contains(".m3u8") ? manifestUrl(...) : resourceUrl(...);
  ...
  if (absolute.contains(".m3u8")) { line = manifestUrl(id, player, absolute, resolved.referer); }
  ```
  A variant/media playlist URL without the literal `.m3u8` (extensionless or `.php`-style
  playlist endpoints, both common on these CDNs) is proxied as a *segment*: ExoPlayer tries
  to parse playlist text as media, and the prefetch path tries to unwrap it as TS (V-04).
  Conversely a signed segment URL containing `.m3u8` in its path/query is proxied as a
  *manifest*: `proxyManifest` rejects its non-`#EXTM3U` body (:723) and falls back to a
  stale manifest or a 500. The same substring logic decides `HlsMediaSource` creation on
  the player side — `PlayerEngine.kt:832` (V-26).
- Fix: classify by HLS role, not substring: lines following `#EXT-X-STREAM-INF` and
  `TYPE=AUDIO/VIDEO/SUBTITLES` media playlists with `URI=` ending in a playlist are
  manifests; `#EXT-X-MAP`/`#EXT-X-KEY` targets and segment lines are resources; if a
  heuristic is still needed, sniff the fetched body's `#EXTM3U` header (already done in
  `proxyManifest` at :723) instead of the URL text.

### V-10 · The edge playlist token is appended to every variant/audio/subtitle manifest fetch
- Severity: **high** · Area: (3) playlist rewriting
- Evidence — `OnDeviceStreamServer.java:682-689`, executed for *every* `proxyManifest` call
  whenever a `TokenSession` exists for the channel id, including variant playlists reached
  via the `url=` parameter:
  ```java
  TokenSession session = (id != null) ? TOKEN_SESSIONS.get(id) : null;
  if (session != null) {
      String token = getOrRenewToken(id, session, false);
      if (token != null && !token.isEmpty()) {
          effectiveUrl = cleanPlaylistUrl(upstream);
          effectiveUrl += (effectiveUrl.contains("?") ? "&" : "?") + "t=" + URLEncoder.encode(token, ...) + "&_=" + System.currentTimeMillis();
      }
  }
  ```
  The token was minted for the edge's top playlist (`/api/playlist-token?slug=<chan>`).
  Stamping it (plus a cache-busting `_=`) onto independently signed variant URLs can
  invalidate their signatures and guarantees the audio rendition — fetched under the same
  `id` — is requested with credentials scoped to a different document.
- Fix: append the session token only when `upstream` is the resolved top-level playlist
  URL for that session (`resolved.url` / the session's own manifest), never to `url=`-supplied
  variant/sub-playlists; let those carry whatever query they were issued with.

### V-11 · Prefetch and the segment cache are rendition-agnostic — audio/subtitle playlist polls evict and cancel the video segments
- Severity: **high** · Area: (3) playlist rewriting / prefetch
- Evidence — `OnDeviceStreamServer.java:863-867` runs prefetch for *any* media playlist served:
  ```java
  if (id != null && !segmentUrls.isEmpty()) {
      activeChannelId = id;
      prefetchTail(segmentUrls, resolved.referer, id);
  }
  ```
  and `prefetchTail` at :995 evicts everything not in the *latest* list:
  ```java
  SEGMENT_CACHE.keySet().removeIf(k -> !segmentUrls.contains(k));
  ```
  ExoPlayer polls the video and audio media playlists independently; each audio poll
  therefore wipes the 12-entry `SEGMENT_CACHE` of video segments (:938-943), cancels queued
  video prefetch futures (:985-993), and spends the 3-thread prefetch pool unwrapping audio
  segments as TS (which then throw, V-04). Video and audio perpetually thrash each other's
  warming — on the slow image CDN described in the server's own comments, this is a standing
  rebuffer generator, not an optimisation.
- Fix: key the prefetch window and cache eviction per playlist (per upstream URL, as in
  V-03); only prefetch/evict for the video rendition (or maintain independent per-rendition
  caches with their own caps), and never let one rendition's poll purge another's entries.

### V-12 · Decoder "ladder" does not implement its own design: `% 2` in the selector vs `% 3` in the log, one counter shared by two different ladders
- Severity: **high** · Area: (4) decoder / renderers factory
- Evidence:
  - Documented ladder, `PlayerEngine.kt:59-68` (class comment): "step 0 = the user's decoder
    preference, step 1 = force software, step 2 = force hardware."
  - Actual selection, `PlayerEngine.kt:555-562`:
    ```kotlin
    val preferSoftware = when {
        prefs.videoDecoder == "Software" -> true
        prefs.videoDecoder == "Hardware" -> false
        isGoogleTvStreamer -> decoderEscalation % 2 == 1
        else -> false
    }
    ```
    On the Streamer there are only two states (even=hardware-first, odd=software-first);
    step 2 is identical to step 0, so the third rebuild retries the decoder that already
    failed twice. Off-Streamer in Auto, `preferSoftware` is *always* `false` — escalation
    changes nothing at all, yet still burns rebuilds.
  - The error path logs a third, different modulus — `PlayerEngine.kt:202`
    `"Decoder failure! Ladder step ${decoderEscalation % 3}, rebuilding player..."` — so logs
    claim step 2 exists while the selector never produces it.
  - `decoderEscalation` is incremented by *both* the freeze flip (`PlayerEngine.kt:248`,
    `flipDecoderAndReload`) and the decoder-error rebuild (:199), so one mechanism's history
    silently re-arms the other's decoder choice mid-channel.
- Fix: implement the 3-step ladder as documented (explicit step→preference mapping used by
  the selector), give the freeze flip and the error rebuild separate counters (or one
  counter with one documented modulus), and make the log line print the preference actually
  selected.

### V-13 · One extension-renderer mode, driven by the *audio* preference, also reorders *video* extension renderers
- Severity: **high** · Area: (4) decoder / renderers factory
- Evidence — `PlayerEngine.kt:531-537`:
  ```kotlin
  val renderersFactory = DefaultRenderersFactory(context).apply {
      val audioExtension = if (prefs.audioDecoder == "Software")
          DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
      else
          DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
      setExtensionRendererMode(audioExtension)
      setEnableDecoderFallback(true)
  ```
  `setExtensionRendererMode` is a single global switch: `PREFER` puts *all* extension
  renderers ahead of MediaCodec renderers — including any video extensions bundled with the
  app (e.g. AV1/libgav1, VP9) — purely because the user picked software *audio*. A user
  troubleshooting an audio codec can thus silently change their video decode path (and, with
  V-12's selector also in play, the two orderings fight). Conversely there is no way to
  prefer a software video extension without touching audio.
- Fix: there is no per-type mode in `DefaultRenderersFactory`; subclass
  `buildVideoRenderers`/`buildAudioRenderers` (or construct the factory per-renderer) so
  the audio preference only affects audio renderers, and document/verify which extension
  renderers are actually on the classpath (the mode is a no-op otherwise).

### V-14 · Tunneling is never enabled — on the Google TV Streamer the player always takes the non-tunneled SurfaceView path
- Severity: **high** · Area: (4) decoder / renderers factory, (2) surface
- Evidence: no call to `setTunnelingEnabled(true)` on the track-selector parameters
  (`PlayerEngine.kt:574-580`, quoted in V-01) and no tunneling configuration on the
  renderers factory (:531-570) anywhere in the file; the selector lambda at :544 receives
  `requiresTunnelingDecoder` and merely forwards it to `MediaCodecUtil.getDecoderInfos`.
  Tunneled playback is the Android TV path that syncs audio/video in hardware and avoids
  a class of SurfaceView composition/A-V-sync faults on MT8696-class silicon; with it off,
  every stream depends on software sync against a `SurfaceView` whose size is being
  resized between preview and fullscreen (V-28).
- Fix: `.setTunnelingEnabled(true)` in the track-selector parameters (Media3 falls back
  automatically when a decoder/renderer combination cannot tunnel), and verify on device
  via `streamHealth`/EventLogger that a tunneled decoder is actually selected.

### V-15 · HEVC (and secure/tunneling decoder requirements) are invisible to the custom MediaCodecSelector
- Severity: **high** · Area: (4) decoder / HEVC
- Evidence — `PlayerEngine.kt:544-570`: the selector reorders by software/hardware *name
  prefix* for every `video/*` mime identically:
  ```kotlin
  setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
      val decoders = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
      if (mimeType.startsWith("video/")) {
          ...
          val swDecoders = decoders.filter { it.softwareOnly || it.name.startsWith("c2.android.") || it.name.startsWith("OMX.google.") }
          val hwDecoders = decoders.filter { !it.softwareOnly && !it.name.startsWith("c2.android.") && !it.name.startsWith("OMX.google.") }
          return@setMediaCodecSelector (if (preferSoftware) (swDecoders + hwDecoders) else (hwDecoders + swDecoders)).toMutableList()
      }
      decoders
  }
  ```
  Consequences: (a) no HEVC-specific handling anywhere in the file — no preferred/excluded
  video mime in the track selector (V-01), so an HEVC variant and an AVC variant are
  interchangeable to selection even on a device whose HEVC path is broken, and the TS
  extractor is configured with no HEVC consideration (`PlayerEngine.kt:834-839`,
  `DefaultHlsExtractorFactory(FLAG_ALLOW_NON_IDR_KEYFRAMES or FLAG_IGNORE_SPLICE_INFO_STREAM or FLAG_DETECT_ACCESS_UNITS, true)`);
  (b) a `c2.android.*` decoder that is hardware-accelerated on some builds is classified
  "software" by name alone; (c) `MediaCodecUtil.getDecoderInfos` can throw
  `DecoderQueryException` — uncaught inside the selector it surfaces as a decoder-init
  failure and feeds the V-12 rebuild loop instead of falling back to the default list.
- Fix: wrap `getDecoderInfos` in try/catch returning the platform list on failure; drive
  ordering off `DecoderInfo.softwareOnly`/`hardwareAccelerated` rather than name prefixes;
  add an explicit HEVC policy (prefer hardware HEVC, exclude HEVC variants via track-selector
  mime preferences if the device's HEVC decoder fails the V-05 no-video verdict).

### V-16 · `flipDecoderAndReload()` double-attaches the surface and, on its capped path, "recovers" a freeze by replaying into the same frozen player
- Severity: **high** · Area: (2) surface attach ordering / watchdog rebuild
- Evidence — `PlayerEngine.kt:248-253`:
  ```kotlin
  val sv = attachedSurfaceView
  val tv = attachedTextureView
  val np = initPlayer()
  if (sv != null) np.setVideoSurfaceView(sv)
  if (tv != null) np.setVideoTextureView(tv)
  play(item, false)
  ```
  `initPlayer()` at :639-645 has *already* attached the same view to the new player; the
  flip then attaches it a second time — and if both fields were ever non-null (possible if
  V-06's parameter path or a future caller sets them independently), the player ends up
  with **both** a SurfaceView and a TextureView set, last-one-wins by call order here
  (TextureView), regardless of which the UI is showing. On the capped/not-applicable path
  (:238-247) the recovery for a *confirmed frozen video* verdict is `routeAttempt++; play(item, false)`
  on the **existing, frozen player instance with no reprepare and no `stop()`** — `play()`
  merely calls `setMediaSource/prepare` on it (:~905-908), the weakest available rung,
  chosen precisely when two freeze recoveries have already failed.
- Fix: delete the redundant re-attach in `flipDecoderAndReload` (rely on `initPlayer`'s
  single attach, after V-06 is fixed); make the capped path at least `reprepare()` the
  existing player before escalating route, and assert exactly one of `sv`/`tv` is non-null.

### V-17 · Lifecycle: no `onStop`, no PiP mode handling, and lifecycle resume seeks to live and replays regardless of state — destroying catch-up and masking errors
- Severity: **high** · Area: (2) surface/lifecycle, (5) error masking
- Evidence:
  - `MainActivity.kt:914-924` — the entire lifecycle surface:
    ```kotlin
    override fun onResume() { super.onResume(); playerEngine.resumeFromLifecycle() }
    override fun onPause() { super.onPause(); if (!isInPictureInPictureMode) { playerEngine.pauseFromLifecycle() } }
    ```
    There is no `onStop()` and no `onPictureInPictureModeChanged()` override anywhere in
    the file, although PiP entry is implemented at :938-950. Closing the PiP window or
    stopping the activity leaves `playWhenReady` set with the surface going away (V-07)
    until `onDestroy` (:926-936) finally releases the player.
  - `PlayerEngine.kt:957-961`:
    ```kotlin
    fun resumeFromLifecycle() {
        if (userPaused) return
        exoPlayer?.seekToDefaultPosition()
        exoPlayer?.play()
    }
    ```
    It does not check `userBehindLive` (set by `seekToProgramStart`, :932-945, and
    `seekRelative`), so any pause/resume cycle — a settings drawer, a system dialog —
    silently throws away catch-up/DVR viewing and jumps to live; and it calls `play()` on
    a player that may be in an errored/idle state, reviving a stream the retry policy had
    already given up on, with no reset of the error UI.
- Fix: add `onStop()` (pause unless in PiP) and `onPictureInPictureModeChanged()` (hide
  `fullscreenContainer` overlay in PiP, restore on exit); in `resumeFromLifecycle`, skip
  the seek when `userBehindLive` is true and skip `play()` unless the player is in
  `BUFFERING`/`READY` with a current item.

---

## MEDIUM

### V-18 · Track-selector parameters are rebuilt from scratch on every player build — user track choices do not survive a rebuild; no viewport, no text preferences
- Severity: **med** · Area: (1) track selector
- Evidence: `selectAudioTrack`/`selectSubtitleTrack` write onto the *player*
  (`PlayerEngine.kt:1048-1052`, :1075-1082, `player.trackSelectionParameters = …buildUpon().setOverrideForType(…)`),
  but `initPlayer()` constructs a brand-new `DefaultTrackSelector` whose parameters contain
  only the audio language (V-01 quote). Every decoder rebuild (V-12), i.e. exactly when
  playback is already in trouble, silently drops the user's chosen audio/subtitle track.
  Separately: no `setViewportSize`, so the 380×214 preview (`MainActivity.kt:166-172`)
  selects/decodes as if fullscreen; no `setPreferredTextLanguages`, so subtitle selection
  falls to Media3 defaults rather than the user's audio-language preference.
- Fix: keep one `Parameters`/`trackSelectionParameters` snapshot in `PlayerEngine`, mutate
  it in the select* methods, and seed every new selector from it; set viewport size on
  preview↔fullscreen transitions; mirror the audio language into preferred text languages.

### V-19 · No video-track inspection: `onTracksChanged` discards `Tracks`, diagnostics cannot distinguish "no video track" from "no tune yet"
- Severity: **med** · Area: (1) track selector
- Evidence: `PlayerEngine.kt:728-730` (quoted in V-01) discards the parameter;
  `streamHealth()` at :~915-935 can only report `p.videoFormat`, printing
  `"Video: no track yet"` both for a still-tuning player and for a settled audio-only
  selection (V-01/V-05) — the one user-visible diagnostic conflates the two states the
  support flow most needs to separate.
- Fix: record the selected groups/formats in `onTracksChanged`; have `streamHealth()`
  report selected video/audio/text formats and whether a video track is selected at all.

### V-20 · Generic retry policy replays deterministic video failures five times, route-cycles behind-live failures forever, and swallows decoder errors with no current item
- Severity: **med** · Area: (5) error/retry masking
- Evidence — `PlayerEngine.kt:668-722` (`onPlayerError`):
  - Every error that is not behind-live-window or decoding — including
    `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`, manifest parsing and IO errors caused by
    V-04/V-09 — takes the same path: `if (prefs.autoRetry && retryCount < maxRetries)` with
    `maxRetries = 5` (:49), `routeAttempt++` only every second failure (:~714), 2.5 s delay.
    A deterministic "this rendition can never play" failure costs five full re-tunes before
    `onError` fires, and the surfaced message is only
    `error.localizedMessage ?: "Playback error: ${error.errorCodeName}"` — the code name that
    identifies the cause is logged, not shown.
  - The behind-live branch (:672-692) `return`s before the retry accounting and its
    3-per-minute route switch clears its own list (:683), so a stream that falls out of
    the window ≥3×/min on *every* route cycles routes indefinitely without ever surfacing an error.
  - The decoder branch (:693-709): `val item = currentItem; if (item != null) { initPlayer(); play(item, false) }; return` —
    with `currentItem == null` the error is swallowed entirely: no rebuild, no `onError`, dead player.
- Fix: classify errors: parsing/unsupported and repeated identical `errorCode` should
  surface after one retry (with `errorCodeName` in the user message); give behind-live
  route-cycling a total cap that eventually calls `onError`; make the decoder branch call
  `onError` when there is no item to replay.

### V-21 · Stale-manifest serving (25 s) stacks with the 45 s buffering net — real upstream death is masked for over a minute
- Severity: **med** · Area: (5) error masking, (3) rewriting
- Evidence: `OnDeviceStreamServer.java:904-920` `staleManifest()` serves the last rewritten
  playlist for up to `25000L` ms after failure; the player sees a valid, unchanging live
  playlist (no error), and `PlayerEngine`'s continuous-buffering net only acts after
  45 s (`healthCheckRunnable`, "45 s, not 20" comment). Combined worst case ≈70 s of
  frozen/buffering screen before any recovery rung runs — and when `staleManifest` *does*
  answer, V-03 means it may be the wrong rendition's playlist.
- Fix: shorten stale serving to one playlist poll interval (~6-10 s) or mark stale
  responses so the player can discount them; fix V-03 first.

### V-22 · A HEAD request is issued for every uncached segment
- Severity: **med** · Area: (3) segment fetching (video path performance)
- Evidence: `OnDeviceStreamServer.java` `downloadRaw()` (HEAD block, ~:1112-1132) issues
  `request(url, referer).head()` before the range/chunk flow for every segment not already
  known-bad for its host (`HEAD_FAILED_HOSTS`, 10-minute suppression only *after a failure*).
  On a healthy host this doubles request count on the CDN the file itself describes as
  per-connection-throttled, and adds a full RTT to every segment's critical path; the
  result (`total`) is only used to choose the chunked path when `total > CHUNK_SIZE`.
- Fix: skip HEAD entirely — the existing range-probe flow already discovers the total
  from `Content-Range`; or cache a per-host "ranges + total known" capability after the
  first successful probe, not only failures.

### V-23 · Chunk-hedge fallback loses the RangeIgnored case — a ranges-ignoring host fails the segment instead of falling back to a plain GET
- Severity: **med** · Area: (3) segment fetching
- Evidence: in `collectChunks()` (`OnDeviceStreamServer.java`, ~:1260-1300) the first
  `ExecutionException` branch unwraps `RangeIgnoredException` → `plainGetSegment(url, referer)`,
  but the timeout/hedge branch does `parts[i] = futures.get(i).get(12L, TimeUnit.SECONDS);`
  with no unwrapping — a `RangeIgnoredException` there propagates as `ExecutionException`
  and kills the segment (and, via V-20, burns a player retry) on exactly the slow-host
  condition the hedge exists for.
- Fix: funnel all chunk-result retrieval through one helper that unwraps
  `ExecutionException` and applies the RangeIgnored/HttpException handling uniformly.

### V-24 · URI rewriting handles only the first `URI="…"` per line, and builds the replacement with an unquoted regex
- Severity: **med** · Area: (3) playlist rewriting
- Evidence — `OnDeviceStreamServer.java:823-834`:
  ```java
  if (line.contains("URI=\"")) {
      Matcher key = uriAttr.matcher(line);
      if (key.find()) {
          String target = absolute(base, key.group(1));
          ...
          line = key.replaceFirst("URI=\"" + Matcher.quoteReplacement(proxied) + "\"");
      }
  }
  ```
  `find()`+`replaceFirst` rewrite at most one URI attribute per tag line, and
  `replaceFirst`'s first argument is a *regex* assembled from the literal line text —
  the original URI's regex metacharacters (`.`, `+`, `?`, `(`, `)`) are not quoted
  (only the replacement is, via `Matcher.quoteReplacement(proxied)`, and only around
  `proxied`, not the whole pattern). A signed URI with such characters can fail to
  match (URI left pointing at the origin, bypassing the proxy and its referer/token
  handling) or match the wrong span.
- Fix: use `Matcher.replaceAll` with `Pattern.quote(...)` semantics — or rebuild the
  line by substring indices from the matcher's `start()/end()` — and loop over all
  URI attributes in the line.

### V-25 · Synthetic PDT is emitted exactly once and its anchor assumes the playlist tail is "now"
- Severity: **med** · Area: (3) playlist rewriting
- Evidence: `OnDeviceStreamServer.java:819-822` emits `pdtLine` only before the first
  `#EXTINF`; :745-798 derives the anchor as
  `liveEdgeStartMs = nowMs - Math.round((totalDur - durs.get(0)) * 1000.0)` — i.e. it
  assumes the fetch instant is the live edge, ignoring proxy fetch latency, the player's
  16 s target offset (`PlayerEngine.kt` `LiveConfiguration.setTargetOffsetMs(16000L)`)
  and any `#EXT-X-DISCONTINUITY`-separated PDT steps in the original (all stripped, V-08).
  The 20 s sanity clamp (:~790) bounds the damage but a systematically early/late anchor
  shifts every `currentLiveOffset` reading that `PlayerEngine`'s desync verdict
  (>25 s, `healthCheckRunnable`) and catch-up maths (`seekToProgramStart`) depend on.
- Fix: anchor PDT from the upstream PDT of the *first* segment when present (correcting
  only its drift), or from fetch-completion time minus measured fetch duration; re-emit
  PDT after each `#EXT-X-DISCONTINUITY`.

### V-26 · HLS detection on the player is substring sniffing, and the MediaItem carries no MIME type — extensionless HLS falls through to progressive playback
- Severity: **med** · Area: (3)/(5) player source selection
- Evidence — `PlayerEngine.kt:832`:
  ```kotlin
  val mediaSource = if (streamUrl.contains(".m3u8", ignoreCase = true) || streamUrl.contains("manifest", ignoreCase = true)) {
      ... HlsMediaSource.Factory(...) ...
  } else {
      androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)
  }
  ```
  and the `MediaItem` at :~828-831 sets only URI + live configuration — no
  `.setMimeType(MimeTypes.APPLICATION_M3U8)`. Local-proxy URLs always contain "manifest"
  so the main flow is safe, but a direct external HLS URL without either substring
  (extensionless, or a redirector) is built as a progressive source and fails against
  playlist text. The same substring assumption as V-09, one layer up.
- Fix: set `MimeTypes.APPLICATION_M3U8` on the MediaItem for every live-TV item (this
  app plays nothing else), or route by item origin (local proxy / known HLS) rather than URL text.

### V-27 · Preview geometry is duplicated by hand and the preview click target is unreachable under the SurfaceView
- Severity: **med** · Area: (2) MainActivity surface/visibility/z-order
- Evidence:
  - The video view lives at root level — `MainActivity.kt:166-183`:
    ```kotlin
    mainVideoView = SurfaceView(this).apply {
        layoutParams = FrameLayout.LayoutParams(dpi(380f), dpi(214f), Gravity.TOP or Gravity.END).apply {
            rightMargin = dpi(24f); topMargin = dpi(16f)
        }
        if (Build.VERSION.SDK_INT >= 34) { setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT) }
        setZOrderMediaOverlay(false)
        ...
    }
    rootContainer.addView(mainVideoView)
    playerEngine.attachSurface(mainVideoView)
    ```
  - The "preview placeholder" is a *separate* view inside the guide —
    `MainActivity.kt:289-300` `previewContainer = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(dpi(380f), dpi(214f)); setOnClickListener { enterFullscreen() } }` —
    whose on-screen position coincides with `mainVideoView` only because the guide's
    `topArea` padding (`MainActivity.kt` `setupGuideContent`, `setPadding(dpi(24f), dpi(16f), …)`)
    happens to equal the SurfaceView's margins. Any padding/theme change silently
    misaligns picture, hole and click target. Because `mainVideoView` is added to
    `rootContainer` *after* `guideLayout` (:161-183), it sits above `previewContainer`
    in z-order, so the placeholder's click listener is dead code — taps land on the
    SurfaceView's own identical listener.
- Fix: parent the `SurfaceView` inside `previewContainer` (one view, one geometry, one
  click target) instead of floating it at root level with copied constants; delete the
  duplicate listener/geometry.

### V-28 · Fullscreen transitions only change LayoutParams — no surface, viewport or renderer notification; aspect handling is dead code
- Severity: **med** · Area: (2) MainActivity preview vs fullscreen
- Evidence — `MainActivity.kt:874-905`: `enterFullscreen()`/`exitFullscreen()` do exactly
  `guideLayout.visibility = View.GONE/VISIBLE`, replace `mainVideoView.layoutParams`
  (MATCH_PARENT ↔ 380×214) and toggle `fullscreenContainer`. Nothing re-attaches or
  resizes the player surface (`player.setVideoSurfaceSize` is never called anywhere),
  nothing updates a track-selector viewport (none exists, V-18), and
  `PlayerEngine.kt:972` `fun setAspectRatio(mode, surfaceContainer, targetView)` — the
  only aspect-ratio machinery, which itself mutates the *target view's* LayoutParams
  and would fight `MainActivity`'s fullscreen params if it were used — has **no caller**
  in any audited file. On drivers that mishandle SurfaceView buffer resize mid-stream
  (the MT8696 target included), the transition relies entirely on framework goodwill.
- Fix: on fullscreen transitions, re-assert the surface
  (`playerEngine.attachSurface(mainVideoView)` after the layout pass) and set the
  selector viewport (V-18); either wire `setAspectRatio` to the overlay's aspect action
  or delete it.

### V-29 · Unwrapper coverage gaps: WebP must contain raw TS in EXIF; PNG must be 8-bit non-interlaced RGB/RGBA — other disguises now hard-fail
- Severity: **med** · Area: (3) segment unwrap (PNG/WebP/zlib)
- Evidence — `OnDeviceStreamServer.java` `unwrapPayload` (:1399-1684):
  - WebP branch (~:1425-1450): returns only if an `EXIF` chunk's payload *itself* starts
    with double TS sync (`bytes[off] == 0x47 && bytes[off + 188] == 0x47`); a WebP wrapping
    a gzip/PNG-style payload (the TIKTIKPX pattern in WebP clothing) hits `break` and
    falls through to the :1684 throw.
  - PNG branch gate (~:1490): `depth == 8 && interlace == 0 && (ctype == 2 || ctype == 6)` —
    palette (ctype 3), greyscale, 16-bit or Adam7-interlaced disguised segments skip the
    whole decode and throw. Since the S4 change (comment at :1393) converted the old
    serve-raw fall-through into a throw, any such upstream variation is now a *total*
    outage for that channel rather than a degraded one — the correct direction for
    garbage, but it raises the cost of every uncovered format to "channel dead".
  - There is no branch for a bare gzip/zlib body (gzip magic `1f 8b`): `gunzip()` exists
    but is only reachable from inside the PNG pixel path.
- Fix: add a format census log in `unwrapPayload` (first bytes + which branch matched)
  so an upstream format change is diagnosable from logs; add EXIF-gzip handling in the
  WebP branch and a bare-gzip branch; keep the throw for genuinely unknown payloads.

### V-30 · `TIKTIKRAW` scan is a linear substring search over every segment and can false-positive inside legitimate TS payload
- Severity: **med** · Area: (3) segment unwrap
- Evidence — `OnDeviceStreamServer.java` `unwrapPayload` step 1 (~:1405-1425) scans
  `for (int i = 0; i + TRAW.length < bytes.length; i++)` byte-by-byte over the whole body
  (up to ~1 MB per segment, on the player/prefetch thread) and returns
  `bytes[off..]` whenever the ASCII string `TIKTIKRAW` appears followed by `0x47` —
  including when that byte sequence occurs naturally inside a real TS segment's
  payload (subtitles, metadata, compressed audio frames), which would silently truncate
  the segment's head. The plain-TS fast path at :1402-1404
  (`bytes[0] == 0x47 && (bytes.length <= 188 || bytes[188] == 0x47)`) runs first, which
  saves normal segments, but any segment failing that exact 188-stride check — e.g.
  192-byte M2TS packets with a 4-byte prefix — falls into the scan and the other
  branches and ultimately throws.
- Fix: bound the TIKTIKRAW search to a plausible header window (first few KB), require
  the match at a packet-aligned offset, and accept the 192-byte M2TS sync stride in the
  fast path (strip the 4-byte prefixes or pass through, per TsExtractor capability).

### V-31 · Segment 403 recovery re-downloads the identical signed URL — guaranteed second failure, double latency
- Severity: **med** · Area: (3)/(5) segment retry
- Evidence — `OnDeviceStreamServer.java:1350-1375` `fetchSegmentBytesInner`: on
  `HttpException(403)` it force-renews the *manifest* token and then retries
  `downloadRaw(url, …)` with the **same `url`** (the in-code comment at :~1368 concedes
  segment URLs are signed and the token is not appended). If the 403 was the segment
  signature's own expiry, the retry cannot succeed; the segment's full chunked download
  cost is paid twice before the error reaches the player.
- Fix: on segment 403, fail fast and let the player's next manifest poll pick up the
  renewed token / fresh segment URLs; keep the renewal side-effect only.

### V-32 · Codec errors are log-only in the analytics listener, and a production `EventLogger` is attached to every player
- Severity: **med** · Area: (4)/(5) decoder errors
- Evidence — `PlayerEngine.kt:604` `player.addAnalyticsListener(androidx.media3.exoplayer.util.EventLogger())`
  on every `initPlayer()` (verbose per-event logging in production, including track
  selections), and :616-619 `onVideoCodecError` (quoted in V-05) records the error and
  takes no action — a renderer-level codec error that does not escalate to
  `ERROR_CODE_DECODING_FAILED` therefore produces no ladder step, no reprepare, nothing.
- Fix: gate `EventLogger` behind a debug pref; forward `onVideoCodecError` into the same
  escalation accounting as the decoder-error branch (count it toward
  `decoderRebuildsSinceReady`).

---

## LOW

### V-33 · Loose local-server routing and unvalidated resource parameter
- Severity: **low** · Area: (3)
- Evidence — `OnDeviceStreamServer.java:219` `if (uri.endsWith("manifest.m3u8") || uri.contains("/manifest"))`
  and :261 `if (uri.endsWith("/resource") || uri.contains("resource"))`; :262 passes
  `q.get("url")` straight into `proxyResource(...)` with no null/shape check (a missing
  `url=` becomes a downstream NPE surfaced as a plain-text 500 at :267).
- Fix: match exact local paths (`/live/manifest.m3u8`, `/live/resource`) and validate
  `url`/`referer` before dispatch, returning 400 for malformed requests.

### V-34 · Id-less variant manifests collapse to the shared empty-id key
- Severity: **low** (med for external-source channels) · Area: (3)
- Evidence: `OnDeviceStreamServer.java` `manifestUrl(id, …)` builds
  `"/live/manifest.m3u8?id=" + enc(id) + …` and `enc()` maps `null` to `""`, so every
  id-less flow's variants share `id=""` — and with it the `"" + ":" + player` keys of
  V-03 across *different channels*, not just different renditions.
- Fix: when `id` is absent, synthesise a stable per-source key (hash of `sources=`/`url=`)
  at `serve()` time and thread it through `manifestUrl`.

### V-35 · `resumeFromLifecycle` also resurrects an errored player; `readySinceMs` is never reset by `play()`
- Severity: **low** · Area: (5)
- Evidence: `PlayerEngine.kt:957-961` (V-17) calls `play()` with no state check;
  `play()` at :753-785 resets ~12 watchdog fields but not `readySinceMs` (only assigned
  in the READY listener, :~655), so the 30 s forgiveness check in `healthCheckRunnable`
  can briefly evaluate the *previous* channel's READY timestamp against the new channel
  before its first READY lands (gated by `playbackState == STATE_READY`, hence low).
- Fix: reset `readySinceMs = 0L` in `play()`; guard lifecycle resume on playback state.

### V-36 · PiP aspect is hardcoded 16:9 regardless of the actual stream
- Severity: **low** · Area: (2) MainActivity
- Evidence — `MainActivity.kt:944-947`:
  ```kotlin
  enterPictureInPictureMode(
      PictureInPictureParams.Builder()
          .setAspectRatio(Rational(16, 9))
          .build()
  )
  ```
  4:3 and 21:9 channels get a 16:9 PiP window (letterboxed by the system, and the
  still-visible `fullscreenContainer` overlay, V-17, is squeezed into it).
- Fix: build the ratio from `playerEngine.videoWidth/videoHeight` when known, clamped
  to PiP's allowed ratio range, falling back to 16:9.

### V-37 · Test suite covers none of the video-path behaviour above
- Severity: **low** (process) · Area: tests
- Evidence — `StreamResolverTest.java` (118 lines) covers endpoint URLs (:19-25),
  `extractStreamUrl` variants (:27-83), channel-page parsing (:85-95), the golden
  TIKTIKPX PNG unwrap (:97-113) and plain-TS passthrough (:115-120), plus
  `playerPageUrl` for routes 0 and 3 only (:122-125 in file order). There is **no test**
  for: `proxyManifest` rewriting at all (master playlist, EXT-X-MEDIA audio group,
  CODECS preservation, PDT strip/synthesis, KEY/MAP/subtitle URIs, V-03 key collision,
  V-09 classification), `segmentResponse` content type, `unwrapPayload` rejecting
  non-TS / handling WebP / TIKTIKRAW / bare gzip, or any track-selector/surface behaviour.
  `unwrapLeavesPlainTsUntouched` also builds its fixture from bare sync bytes and asserts
  only length + first byte — a stride-corrupting regression would pass it.
- Fix: add golden-file rewriting tests (master + media + audio playlists in, rewritten
  playlists out, asserted line by line), an unwrap-rejection test, and a full 8-route
  `playerPageUrl` parameterisation.

### V-38 · Duplicated live-speed configuration can drift apart
- Severity: **low** · Area: (4) player config hygiene
- Evidence: fallback speeds are set twice with identical literals — the speed control at
  `PlayerEngine.kt:582-590` (`setFallbackMinPlaybackSpeed(0.97f)` / `setFallbackMaxPlaybackSpeed(1.05f)`)
  and the per-item `LiveConfiguration` at :~838-845 (`setMinPlaybackSpeed(0.97f)` /
  `setMaxPlaybackSpeed(1.05f)`). Nothing enforces that they stay equal; a future tuning
  edit to one silently overrides/conflicts with the other depending on which Media3 consults.
- Fix: define the two constants once and reference them from both builders.

---

## What is sound (verified, do not "fix")

- Manifest Content-Type and cache headers are correct (`OnDeviceStreamServer.java:856-859`).
- `setAllowChunklessPreparation(false)` (`PlayerEngine.kt:841`) is the safe choice given
  upstream CODECS cannot be trusted to be present.
- Segment prefetch has a dedicated player-first chunk pool, bounded cache and in-flight
  joining (`OnDeviceStreamServer.java` :923-1070 design comment and code) — the defect is
  its rendition-agnostic keying (V-11), not its existence.
- The S4 decision to *throw* on non-TS payloads instead of serving garbage as `video/mp2t`
  is correct in direction; V-04/V-29 are about the throw being applied to resource kinds
  that were never TS to begin with.
- `MainActivity` keeps a **single** SurfaceView for preview and fullscreen (no second
  player/surface pair to desync) — the defects are its parenting/geometry (V-27) and
  transition handling (V-28), not the single-view design.
- No credentials, tokens or segment URLs are logged by the player; the server explicitly
  avoids logging raw `url=`/`sources=` params (`OnDeviceStreamServer.java:216-218`).

## Suggested fix order

1. V-01 + V-05 (selector floor + no-video verdict) — closes the reported symptom class.
2. V-03 + V-04 (rendition-keyed manifest state; kind-aware resource proxying).
3. V-02 (restore HLS fallback selection).
4. V-06 + V-07 (surface persistence/teardown).
5. V-08, V-09, V-10, V-11 (rewriting correctness), then V-12–V-17, then med/low in order.

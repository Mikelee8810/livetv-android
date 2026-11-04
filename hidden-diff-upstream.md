# RESOLVER DIFF — app vs upstream (hidden, do not ship)

Date of local snapshots: StepDaddyLiveHD `2025-10-13 3cc8821`, daddylive-stream-resolver
`2026-10-24 206e860`, dlhd-web `2026-09-28 f7cb726`, EasyProxy `2026-11-02 807867d`
(newest), dlhd-cli `2026-08-02`. App files compared:
`app/src/main/java/com/livetv/app/OnDeviceStreamServer.java` (1861 lines, cited as `App:`)
and `app/src/main/java/com/livetv/app/StreamEndpoints.java` (cited as `EP:`).
No app source was edited.

Legend: **[BLACK]** = plausibly causes AUDIO-PLAYS-BUT-VIDEO-BLACK, **[DEAD]** = total
stream failure, **[STALE]** = plays then dies/freezes.

---

## 1. Reference flows (what upstream actually does today)

### 1a. StepDaddyLiveHD (Python, `StepDaddyLiveHD/StepDaddyLiveHD/step_daddy.py`)

- Base: `step_daddy.py:26` `self._base_url = "https://dlhd.dad"` (not thedaddy.pk / dlive.sx).
- Watch/stream page: `step_daddy.py:63` `GET {base}/stream/stream-{channel_id}.php`,
  channels from `step_daddy.py:45` `{base}/24-7-channels.php`.
- Iframe: `step_daddy.py:65-68` first `iframe src="(.*)" width`, fetched with
  `Referer=<stream page>`.
- Auth — completely different from dembed, **no `/api/playlist-token` anywhere**:
  - `step_daddy.py:72` `const CHANNEL_KEY = "..."` (last match) from the player page.
  - `step_daddy.py:74-79` `decode_bundle()` (`utils.py:278-305`: finds a base64
    `JSON.parse(atob(...))` / `eyJ…` blob containing `b_ts,b_sig,b_rnd,b_host`, each value
    itself base64) → `GET {b_host}auth.php?channel_id={CHANNEL_KEY}&ts={b_ts}&rnd={b_rnd}&sig={b_sig}`,
    `Referer=<player page>` (`step_daddy.py:80-82`, non-200 = fatal).
  - `step_daddy.py:84-88` `GET {player scheme}://{player netloc}/server_lookup.php?channel_id={CHANNEL_KEY}`
    → JSON `server_key`.
  - Playlist is **constructed, never scraped**: `step_daddy.py:89-92`
    `server_key=="top1/cdn"` → `https://top1.newkso.ru/top1/cdn/{channel_key}/mono.m3u8`,
    else `https://{server_key}new.newkso.ru/{server_key}/{channel_key}/mono.m3u8`.
    It is a **media playlist (`mono.m3u8`) — there is no master/variant step**.
  - Fetched with a non-standard header: `step_daddy.py:93`
    `Referer=quote(str(source_url))` (URL-quoted player URL).
- Playlist rewriting: `step_daddy.py:96-100` only two things — `#EXT-X-KEY:` `URI="…"`
  is rewritten to a local `/key/{encrypt(url)}/{encrypt(player netloc)}` proxy, and
  (if `proxy_content`) bare `http…` segment lines to `/content/{encrypt(line)}`.
  **Segments are assumed plain TS — no PNG/WebP/zlib unwrapping at all.**
- Key fetch: `step_daddy.py:104-110` `GET <key url>` with
  `_headers(f"{host}/", host)` i.e. `Referer="{netloc}/"`, `Origin="{netloc}"`
  (from `_headers`, `step_daddy.py:31-40`; UA there is Firefox/137 desktop).
- Headers overall: only `Referer` (+ optional `Origin`) + UA. No `Origin` on page fetches.

### 1b. daddylive-stream-resolver (TS, Lunatic16 — primary reference)

- Base: `src/config.ts:1` `DLHD_BASE ?? "https://dlive.sx"`;
  `docker-compose.yaml` (repo root) pins `DLHD_BASE: "https://thedaddy.pk/"`;
  `README.md:372` "The upstream origin rotates (dlive.sx, thedaddy.pk, …)".
- Routes — **exactly six, no `ntvs`, no `hub` route**: `src/servers/kinds.ts:1-8`
  `stream, cast, watch, plus, casting, player`; page URL `src/channels/urls.ts:9-11`
  `${DLHD_BASE}/{player}/stream-{channelId}.php`, watch referer `urls.ts:5-7`
  `${DLHD_BASE}/watch.php?id={channelId}`.
- Embed extraction is **specific, not generic-first-iframe**: `src/decrypt/extractors/dlhd-page.ts:6-13`
  prefers `<iframe id="thatframe">` (either attribute order), else the **last**
  `<iframe class="…video…">`.
- **Embed chain, up to 6 hops**: `src/api/resolve.ts:507-528` `fetchEmbedHtmlChain`
  loops `depth < 6`, following `extractInnerFrameUrl` and *scripted* hops
  (`resolve.ts:491-505`: wikisport `fid=` → `https://igniteandship.com/wiki.php?player=desktop&live=…`
  via `src/decrypt/extractors/wikisport.ts:5-7`; blogger-player `zdec=` base64→atob
  redirect via `blogger-player.ts:1-9`) until `htmlMayContainPlayable`
  (`src/decrypt/extractors/embed.ts:17-21`).
- Resolve entry: `src/api/resolve.ts:368-394` player page → embed URL → chain →
  `resolveFromHtml(html, {channelId, server, embedUrl})`.
- Extractor families, tried **in this order** (`src/decrypt/extractors/embed.ts:23-95`):
  1. **dembed** (`dembed.ts:7-27`): `const SRC="https://…m3u8"`, `const EDGE="https://…"`,
     `const CHANNEL="…"` **and `new URL(SRC).hostname === new URL(EDGE).hostname`** or reject.
  2. **tiestep** (`tiestep.ts:3-16`): `window._econfig='…'` → `decryptEconfig`
     (`src/decrypt/crypto/econfig.ts:15-50`: atob → 4 chunks → reorder `[2,0,3,1]` →
     drop char index 3 of each chunk → atob → JSON `{stream_url, stream_url_nop2p, p2p…}`;
     `econfigPlayableUrl` prefers the **nop2p** URL when `p2p===false || url_nop2p`).
  3. **epiembeds** (`epiembeds.ts:1-36`): XOR-number-array script
     `var _gr\w+=[nums],_\w+=(xor),_\w+=(sub)` → `SIGNED_URL="…m3u8"`.
  4. **hub** (`hub.ts:4-26`): `const ENCRYPTED_CONFIG={cipher,key,iv}` → **AES-CBC decrypt**
     (`src/decrypt/crypto/aes-cbc.ts:12-18`) → `{baseUrl, streamId, initialToken, initialCode,
     initialVideoUrl}` → playable is **`video/webm`**, `{baseUrl}/{token}/{code}/{streamId}/webm/`.
  5. **livelive24** (`livelive24.ts:1-13`): `<source src="https://…/webm/…">` → `video/webm`.
  6. **wideiptv** (`wideiptv.ts:1-8`): `streamUrl: "…"` (+ `channelSlug:`, `tokenExpiresIn:` meta).
  7. **cdnlivetv** (`cdnlivetv.ts:6-20`): a page-local `function \w+(s){…atob}` decoder applied
     to `var` chunks joined with `+`, terminated by `var _p2pMode`.
  8. **igniteandship** (`igniteandship.ts:1-15`): `player.load({source: fn()})` where `fn`
     builds the URL from a string-array **plus text read from a DOM span by id**.
  9. **daddy3** (`daddy3.ts:1-16`): `atob('…')` or literal
     `https://…/three/secure/[0-9a-f]{32}/\d+/premium\d+/index.m3u8`, meta `token:/channelId:/announce:`.
- Family→player mapping (`README.md`, "Players" section, ~lines 166-260):
  P1 `stream` + P3 `watch` = dembed premiumtv; P2 `cast` = `_econfig` on rotating hosts
  (currently embed `tossequip.net` → HLS `7odxv0l067ka.net`); P4 `plus` = **currently
  unresolvable upstream** (302 to a JWT-path `…/play/eyJhbG…` player no extractor matches);
  P5 `casting` = cdnlivetv (`api.cdnlivetv.tv`); P6 `player` = wideiptv → `bluetier.top`.
- dembed token (the only `/api/playlist-token` flow): `src/proxy/dembed.ts:27-50`
  `GET {edge}/api/playlist-token?slug={slug}` with headers
  `{User-Agent, Origin: new URL(referer).origin, Referer: referer}` (`dembed.ts:34-37`),
  4 s timeout; `ttl = max(20, ttl||120)`, `renewAt = now + ttl*500` (half TTL),
  `expiresAt = now + ttl*1000 - 2000`. README, PLAYER 1: "**the `Origin` header must be
  exactly the embed host's origin — anything else, including the top-level site's, is
  rejected**", token attached as `t=` + `_=` to "**manifest requests only**".
  Re-minted **per playlist fetch**: `src/proxy/live.ts:112-117` `playlistFetchTarget`;
  on 401/403: invalidate + force re-resolve + retry once (`live.ts:204-216`).
  Token is **never stored in the session** for dembed (README, "Token rotation").
- wideiptv token refresh (a POST the dembed flow does not have):
  `src/proxy/session.ts:93-131` `POST https://wideiptv.top/api/refresh_token.php`,
  `Content-Type: application/json`, `Referer: embedUrl`, body
  `{"channel": slug, "current_token": token}` → replaces `token=` in the playable URL.
  Sessions refresh with a 45 s lead: `session.ts:25` `REFRESH_LEAD_SEC = 45`,
  `ensureFreshSession` (`session.ts:158-178`); refresh tries **cached embed URL first**,
  then full resolve (`session.ts:143-156`).
- Playlist handling (`src/proxy/live.ts`):
  - Per-host transport rules **override the per-server rules**: `src/proxy/rules.ts:9-29`
    `upstreamRuleFor(url)` — host `7odxv0l067ka|tiestep|assetrage` → Referer **required**;
    `bluetier|wideiptv|cdnlivetv` → Referer **omit**; `dreamstream|livelive24` →
    `rewritePlaylist: false`; `tiktokcdn` → Referer **omit**; default optional.
    Applied in `live.ts:19-34` `upstreamHeaders`: `omit` ⇒ **no Referer and no Origin at
    all**; `required` ⇒ Referer + `Origin = origin(Referer)`.
  - README PLAYER 1: "Segments are absolute PNG-wrapped MPEG-TS on TikTok CDN — **no
    token, plain UA, no Referer**."
  - Master vs media is distinguished: `live.ts:62-94` `rewriteLivePlaylist` —
    `#EXT-X-STREAM-INF` present ⇒ record each variant into `session.variants[v]` and
    rewrite it as a **child live URL carrying the variant index `v`**, not the raw URL;
    a later child request re-selects `session.variants[variant]` and **swaps in the
    current session token** (`live.ts:184-188` `swapToken`: replaces an existing
    `token=` param). Non-variant `.m3u8` lines also become child live URLs; segment/key
    lines become generic proxy URLs with a per-target referer (`live.ts:36-38,91`).
  - `#EXT-X-PROGRAM-DATE-TIME:` lines are **stripped and not replaced**
    (`live.ts:75`). No synthetic PDT is invented.
  - Short-window guard: `live.ts:143-158` `fetchPlaylist` — if the first playlist has
    `< 6` media lines it is fetched **again** and the larger one wins.
  - Playlist URL is cache-busted on every fetch (`live.ts:56-60`, `_={now}`), dembed
    URLs additionally rebuilt by `withDembedParams` (`src/proxy/dembed.ts:84-91`:
    delete `t`/`_`, set fresh `t`, set `_`).
- Segments — **disguised, and the unwrapper has 6 stages**:
  `src/proxy/unwrap-webp-ts.ts:176-185`
  `webpExifTs ?? pngIendTs ?? unwrapPixels(TIKTIKPX) ?? markerPayload(TIKTIKRAW) ??
  markerPayload(TIKTIKTSGZ) ?? scanTs`,
  where `TSGZ = "TIKTIKTSGZ"` (`unwrap-webp-ts.ts:5`), the payload after that marker is
  **gunzipped** (`:152-167`), and `scanTs` (`:169-174`) returns the body from the first
  offset where `buf[i]==0x47 && buf[i+188]==0x47` — skipped only for PNG-magic bodies
  (`:183`). Caller behaviour: `live.ts:160-171` / `src/proxy/stream.ts:94-107` — if
  unwrap returns a TS use it; if body starts `0x89` (PNG) and unwrap failed, **throw**;
  otherwise return the raw body. Segments are fetched **whole, one GET, no HEAD, no
  Range, no chunking** (`live.ts:119-132`).

### 1c. dlhd-web (older sibling of 1b — brief)

- Base `src/config.ts:1` `DLHD_BASE ?? "https://dlhd.st"`; **7 players incl. `hub`**
  `src/players/types.ts:1-9` (`stream, cast, watch, plus, casting, player, hub` = PLAYER 7).
- Same extractor set minus dembed/epiembeds/livelive24, plus `assetrage`
  (`src/resolver/extractors/embed.ts:21-78`); hub AES here is async and there is an
  extra XOR/ad-config layer (`src/resolver/crypto/xor-payload.ts:1-6`,
  `ad-config.ts:23-33`).
- Segment unwrapper `src/proxy/segment.ts:157-215` `sanitizeSegmentBody` has the same
  6 stages incl. `TIKTIKTSGZ` gunzip (`segment.ts:3-5,190-205`) and a final sync-scan
  (`:207-212`), but on total failure **returns the original bytes** (`:214`) instead of throwing.
- Extra playlist step the resolver dropped: `src/proxy/stream.ts:41-82`
  `syncLiveMediaPlaylist` — drops the **last media entry if its `#EXTINF` duration is
  `< TARGETDURATION * 0.95`** (truncated live-edge segment).

### 1d. DaddyLiveHD (brief) / EasyProxy (brief, but newest and most diagnostic)

- DaddyLiveHD: playlists are just entry points — `thedaddy.m3u8:7` etc. are
  `https://thedaddy.pk/watch.php?id={n}` page URLs, no resolver logic. Confirms
  `thedaddy.pk` as a current watch host, nothing about auth/segments.
- EasyProxy `extractors/dlstreams.py` (2026-11-02):
  - Player URL list (`dlstreams.py`, `_build_player_urls`, ~line 355 in file):
    `/stream/`, `/cast/`, `/watch/`, `/plus/`, `/casting/`, `/player/`, **`/hub/`**
    `stream-{id}.php` on a **dynamic `entry_origin`** (taken from the requested URL,
    not hardcoded).
  - Premium token, stricter than 1b: `dlstreams.py:61-67`
    `PREMIUM_PLAYLIST_RE = ^(?P<edge>https?://[^/]+)/(?P<slug>premium\d+)/index\.m3u8$`,
    `PREMIUM_TOKEN_ORIGINS = ("https://dembed.st", "https://dlive.sx")`,
    `PREMIUM_TOKEN_DEFAULT_ORIGIN = "https://dembed.st"`. `premium_token_origin()`
    (`dlstreams.py:85-101`) returns a caller origin **only if it is exactly one of
    those two**, else the default. Mint (`dlstreams.py:138-168` real lines):
    headers `{Origin: origin, Referer: f"{origin}/", User-Agent, Accept: */*}` — note
    **Referer is `origin + "/"`, not the player page** — token cached per
    **(edge, slug, egress-route)**, renewed at `ttl * 0.5`. Slug is derived from the
    **playlist URL path (`premium\d+`)**, not from a page const.
    Comment `dlstreams.py:52-60`: token is "**bound to the caller's address**", and
    without the handshake "every playlist refresh fails while already published
    segments keep working, so the player loops the last ~20s window and then dies
    (frozen/green video, 'media sequence changed unexpectedly')".
  - **Black/green-video probe — the headline [BLACK] evidence** (`dlstreams.py:32-35`):
    "A valid TS container can still carry an undecodable video track (**dlive.sx
    Player 2: green/black picture, working audio**). Decode one frame with ffmpeg…"
    `_ts_video_decodes` (~lines 484-535) decodes one frame and rejects a player after
    ≥3 decode-error markers; `_ts_video_payload` (~lines 450-482) scans TS packets for
    an actual **video PES (`0xE0-0xEF`)** and rejects a segment that has audio PES
    (`0xC0-0xDF`) but no video PES. `_is_stream_alive` (~lines 629-720) validates the
    **whole chain master → variant → first segment (+ AES key)** before accepting a
    player; a manifest-only 200 is explicitly not enough ("Some CDNs serve the
    manifest (200) while segments 403").
  - **Player pinning** (`dlstreams.py:~269-295`, `_prioritize_player_urls` comment):
    re-resolves must keep the already-validated player because "switching source
    mid-playback changes the media sequence, the PIDs and the parameter sets, which
    players report as 'media sequence changed unexpectedly' plus 'non-existing
    SPS/PPS' and a frozen/green picture."
  - **Query inheritance** (`dlstreams.py`, `_inherit_query_if_missing`, ~line 365):
    "CDNs here issue a token in the playlist URL and list relative URIs. **Plain
    urljoin drops the parent token, so the segment/variant probe becomes a 404** and
    a working player is reported as dead." Applied to variants, segments and the
    `#EXT-X-KEY` URI (`_is_stream_alive` key probe).
  - Also decodes the `_econfig` family for "barecrop.net embeds (Player 2)"
    (`dlstreams.py:111-143` `_decode_barecrop_econfig`, same `[2,0,3,1]` reorder) and
    validates `#EXT-X-KEY` by actually fetching the key.

### 1e. Kodi jetextractors `daddylive.py` (via `gh search code`, audiosistem/Bee-Queen copy)

- Domains `daddylive.py:32` `self.domains = ["dlhd.dad"]`.
- Player discovery is **from the watch page**, not by guessing route names:
  `daddylive.py:85-90` — `center > a` links become "Player {i} [{route}]", else
  `button.player-btn` `data-url`s.
- Iframe: `daddylive.py:102` `iframe#thatframe, iframe.video`; one extra hop for
  `wikisport`/`lovecdn` iframes (`:104-107`).
- CHANNEL_KEY branch (`:109-139`): collects **all** `const [A-Z_]+ = "…"` pairs,
  builds headers (`:120-127`) `Referer/Origin = https://{iframe netloc}`,
  `Authorization: Bearer {session token}`, `X-Channel-Key: {channel_key}`,
  `Connection: Keep-Alive`; calls a **heartbeat** `GET https://chevy.giokko.ru/heartbeat`
  (`:129-130`); then `{referer}/server_lookup.js?channel_id={channel_key}` (**`.js`, not
  `.php`**) → `server_key` → `https://top1.giokko.ru/top1/cdn/{key}/mono.m3u8` or
  `https://{server_key}new.giokko.ru/{server_key}/{key}/mono.m3u8` (**giokko.ru, not
  newkso.ru** — the CDN suffix domain itself rotates).
- Five more families the app has none of: `eval('\x0a…')` hex → `let streamURL`
  (`:141-149`); `atob('Y29uc3…')` → `const initUrl` → fetch → base64 stream URL
  (`:150-156`); `vidembed` **POST** `{origin}/api/source/{stream_id}?type=live` with
  JSON body `{"d": "www.{netloc}", "r": "{origin}/stream/{stream_id}"}` then
  AES-CBC decrypt with a fixed base64 key and an IV taken from a `container-…` body
  class (`:157-172`); `zhdcdn` with an `Xauth` header + base64 `crf__` value
  (`:173-181`); a sorted `[index,"base64"]` byte-array reconstruction (`:182-191`);
  and `fid="…" … /embed.js` → `{origin}/embed.php?player=desktop&live={fid}` char-array
  join (`:192-200`); plus `embed.html?token=` → **`index.fmp4.m3u8`** (fMP4) (`:201-203`).
- Playback headers are attached to the returned link for **every** request:
  `daddylive.py:209-221` defaults `Connection: Keep-Alive`, `Origin`, `Referer: origin + "/"`,
  UA, and `JetInputstreamFFmpegDirect` (i.e. Kodi does **not** hand segments to a
  TS-only unwrapper).

---

## 2. What the app does (baseline for the quotes below)

- Domains: `EP:23` `PRIMARY = "https://thedaddy.pk"`, `EP:26-31` mirrors
  `dlhd.pk, daddylive.at, daddylive.app` only; `EP:66` route-0 referer
  `NTVS_WATCH = "https://ntvs.cx/channel/phoenix/"`.
- Routes: `App:40` `ROUTES = {"ntvs","stream","cast","watch","plus","casting","player","hub"}`
  (8). `App:1812-1817` `playerPageUrl`: route 0 and route ≥1 both build
  `/{route}/stream-{id}.php` (route 0 = plain `/stream/stream-{id}.php`), i.e. route 0
  **duplicates route 1 (`stream`)** except for the ntvs referer (`App:596`).
- Resolve is **one iframe hop, two extractors**: `App:620-625` single
  `IFRAME.matcher(embedHtml)` (`App:41` pattern is `<iframe\s+src=["']` — `src` must be
  the *first* attribute), one `getText(playerUrl, embed)`, then either the dembed
  branch (`App:628-665`) or a single `SOURCE` regex (`App:42`, `App:667-677`:
  `source: atob('…')` or `source: '…m3u8'`). Everything richer
  (`extractStreamUrl`, `App:~340-440`: `STREAM_URL`, joined-var, `DIRECT_M3U8`,
  `ANY_ATOB`, `FILE_PAT`, `PLAYER_FRAME`, `DATA_URL`) is used **only by
  `resolveExternal`**, never by the numeric `resolveSingle` path.
- dembed acceptance test: `App:650-657` builds `SRC?t={token}&_={now}` and accepts the
  route if that one request `isSuccessful()` — body is never checked for `#EXTM3U`,
  no variant/segment/key is ever probed.
- One header builder for **every** upstream request: `App:1735-1746` `request(url, referer)`
  always sends UA (`App:32`, an Android/Google-TV Chrome/120 UA) and, when a referer
  exists, `Referer: <full referer>` **and** `Origin: scheme://host` of that referer
  (port dropped). Used for pages, token mint, manifests, keys and segments alike.
- Playlist: `App:~800-870` `proxyManifest` — relatives resolved against the final URL
  **with its query string deleted** (`App:804-810`), `URI="…"` attributes proxied as a
  manifest iff the target contains `.m3u8` else as a resource, segment lines proxied
  as resources; upstream PDT lines are dropped and a **synthetic PDT is synthesised**
  from media-sequence + EXTINF durations and injected before the first `#EXTINF`.
  Resolve cache is 15 min **sliding** (`App:537`, `App:853`).
- Segments: `App:~1100-1250` `downloadRaw` — HEAD first, then **256 KB Range chunks
  over parallel connections with 3 s hedging**, assembled, then `unwrapPayload`.
  `App:1399-1684` `unwrapPayload` handles plain TS, `TIKTIKRAW` (`App:61,1407-1425`),
  WebP-EXIF, PNG-after-IEND and PNG-pixel `TIKTIKPX` (`App:62,1448+`); anything else
  **throws** `UnwrapException` (`App:1684`). Every resource response is served as
  `video/mp2t` (`App:1007-1011`).

---

## 3. RANKED DIVERGENCES

### D1 — [BLACK] No video-track validation at all; first-200 route wins and is then pinned by the route cache
- Upstream: EasyProxy `dlstreams.py:32-35` documents Player 2 (`cast`) on dlive.sx as
  **working audio, green/black picture, valid TS container**; it rejects such a player
  only by decoding a frame / scanning for a video PES (`_ts_video_decodes`,
  `_ts_video_payload`, §1d) after walking master → variant → first segment → key
  (`_is_stream_alive`). Resolver README PLAYER 4 shows the same philosophy: a route
  that resolves textually can still be unplayable.
- App: route acceptance is a single manifest test request —
  `App:653-657` "if (!testResp.isSuccessful()) throw" (dembed branch) or merely
  `App:667-677` "SOURCE regex matched and URL starts with http". **No segment is
  fetched, no PES is inspected, no frame is decoded during resolve.** The winner is
  then remembered: `App:538` `LAST_GOOD_ROUTE.put(id, player)` and `resolveWalk`
  starts from it (`App:~520-524`), with a 15-min sliding resolve cache
  (`App:537`, refreshed at `App:853`), so a black-video player is re-selected on
  every zap and never re-challenged while its manifest keeps returning 200.
- Why it ranks #1 for the reported symptom: it is the only divergence that produces
  *exactly* "audio plays, video black, stream does not error". Every other player
  can be healthy while the app sits on the broken one.

### D2 — [BLACK/STALE] No player pinning on re-resolve; a mid-playback route/edge switch changes PIDs, SPS/PPS and media sequence
- Upstream: EasyProxy `_prioritize_player_urls` comment (§1d) — switching source
  mid-playback "changes the media sequence, the PIDs and the parameter sets, which
  players report as 'media sequence changed unexpectedly' plus 'non-existing
  SPS/PPS' and a frozen/green picture"; the validated player is pinned
  (`PIN_STICKY_SECONDS`). Resolver: refresh reuses the **same session + same cached
  embed URL** first (`src/proxy/session.ts:143-156`), and dembed tokens are minted
  in place without changing the playable URL (`src/proxy/live.ts:112-117`).
- App: after only **2** manifest failures the resolve entry is dropped and the next
  poll re-walks routes — `noteManifestFailure` (`App:~560-575`)
  `RESOLVE_CACHE.keySet().removeIf(k -> k.startsWith(id + ":"))` — and a re-resolve
  may land on a different route/edge with a different timeline; the stale-manifest
  safety net only covers 25 s (`staleManifest`, `App:~950-970`:
  `System.currentTimeMillis() - at > 25000L` → null). The app's own comment at the
  top of that block acknowledges a new edge "could carry a DIFFERENT playlist
  timeline, and the player's position would plunge negative and never recover", yet
  the token-session is the only thing preserved.

### D3 — [DEAD] Numeric resolve implements 2 of ≥9 current extractor families; the rest fail the route outright
- Upstream order: dembed, tiestep/econfig, epiembeds-XOR, hub-AES-WebM, livelive24-WebM,
  wideiptv, cdnlivetv, igniteandship, daddy3 (`src/decrypt/extractors/embed.ts:23-95`),
  plus StepDaddy's CHANNEL_KEY/auth.php/server_lookup flow (§1a) and jetextractors'
  six extra branches (§1e).
- App numeric path: dembed consts (`App:628-635`) or `source: atob/plain-m3u8`
  (`App:42`, `App:667-677`) only. Concretely missing, each a hard route failure
  ("stream token unavailable on …", `App:669`):
  - `window._econfig` (Player 2 `cast` today, per resolver README + EasyProxy
    `dlstreams.py:111-143`): no `[2,0,3,1]` reorder decrypt anywhere in the app
    (grep: no `_econfig`, `ENCRYPTED_CONFIG`, `SIGNED_URL` in `OnDeviceStreamServer.java`).
  - XOR arrays (epiembeds / `plus`): `epiembeds.ts:1-24`, `dlhd-web …/plus.ts:1-18` — absent.
  - `streamUrl:` (wideiptv, Player 6): the app *has* `STREAM_URL` (`App:44`-ish) but
    only inside `extractStreamUrl`, which `resolveSingle` never calls.
  - cdnlivetv chunk-decoder (Player 5, `cdnlivetv.ts:6-20`), igniteandship
    `player.load({source: fn()})` + DOM span (`igniteandship.ts:1-15`), daddy3
    `/three/secure/{32hex}/…/premium\d+/index.m3u8` (`daddy3.ts:2-3`) — all absent
    from the numeric path (the generic atob fallback that would catch daddy3 lives
    in `extractStreamUrl` only).
  - WebM families (hub, livelive24): even if a URL were extracted, the app's whole
    proxy is HLS/TS-only (see D8), and `validateStreamUrl` (`App:~440-446`) requires
    `.m3u8`.
- Effect: for any channel whose working players are all in the missing families,
  all 8 routes fail → total failure, even though the same channel plays in the
  resolver. This is the most likely cause of *channel-dependent* total failures.

### D4 — [DEAD] One iframe hop, first-attribute `src`, vs a 6-deep embed chain with scripted hops
- Upstream: `fetchEmbedHtmlChain` depth `< 6` with inner-iframe + wikisport/blogger
  scripted hops (`src/api/resolve.ts:491-528`); iframe selection by
  `id="thatframe"` / last `class="video"` iframe (`dlhd-page.ts:6-13`);
  jetextractors does the same selection (`daddylive.py:102`) plus a wikisport/lovecdn
  second hop (`:104-107`); EasyProxy falls back to
  `<iframe\s+[^>]*src=…` (any attribute order) and de-duplicates seen iframes.
- App: `App:41` `IFRAME = "<iframe\\s+src=[\"']([^\"']+)"` — fails if `id=`, `class=`,
  `width=` etc. precede `src` (the common `thatframe` markup), and
  `App:620-630` follows **exactly one** hop, then demands dembed consts or `source:`
  in that single page. A hop/interstitial page (wikisport, blogger, lovecdn) is a
  dead end. (The multi-hop loop exists only in `resolveExternalWalk`, `App:~300-340`,
  depth `< 4`, for non-numeric sources.)

### D5 — [DEAD/STALE] Relative variant/segment/key URIs lose the parent playlist's query token
- Upstream: EasyProxy `_inherit_query_if_missing` (§1d) was written precisely
  because "plain urljoin drops the parent token, so the segment/variant probe
  becomes a 404"; resolver instead swaps the *current* token into child URLs
  (`src/proxy/live.ts:96-100,184-188` `swapToken`).
- App: `App:804-810` — `finalUrl` has its query **explicitly stripped** before
  becoming the resolution base, and `absolute(base, line)` (`App:~830-850`) is then
  used for variant, segment and `URI="…"` (key/MAP) lines. Any family whose
  playlist URL carries the credential (`token=` for wideiptv/cdnlivetv,
  `?e=&s=` for econfig, signed variants generally) and whose playlist lists
  relative URIs therefore gets **401/403/404 on every child request** while the
  manifest itself loads — the classic "manifest OK, nothing plays" failure.
  The dembed family is exempt only because `proxyManifest` re-appends its own
  `t=` (`App:684-690`); all other families have no equivalent.

### D6 — [DEAD] Segment unwrapper is missing `TIKTIKTSGZ` and the generic TS sync-scan; non-TS payloads throw instead of passing through
- Upstream: `unwrap-webp-ts.ts:5,152-185` — `TIKTIKTSGZ` marker → gunzip the rest;
  final fallback `scanTs` returns the stream from the first double-`0x47` offset
  for any non-PNG body. dlhd-web `segment.ts:190-215` has both, and returns the
  original bytes if nothing matches.
- App: constants are only `App:61` `TRAW="TIKTIKRAW"` and `App:62` `TPIX="TIKTIKPX"`;
  `unwrapPayload` (`App:1399-1684`) checks plain TS, TRAW, WebP-EXIF, PNG — and then
  `App:1684` `throw new UnwrapException("payload is not a transport stream")`.
  A `TIKTIKTSGZ` segment, a TS body with any junk/offset prefix, an fMP4 segment
  or a WebM chunk is therefore a **hard 500 to the player** (and, per `App:1393`
  comment history, this throw replaced serving the bytes). Note the app *does*
  import `GZIPInputStream`/`Inflater` and gunzips inside the PNG-pixel path — the
  capability exists, the marker branch does not.
- Also: the resolver tries WebP-EXIF **before** PNG-IEND before pixels
  (`unwrap-webp-ts.ts:176-184`); the app tries TRAW first (`App:1407`) — harmless
  for well-formed bodies, worth noting only if a body contains both markers.

### D7 — [DEAD/STALE] dembed token mint: Origin is derived from whatever iframe host answered, and slug/host are not validated
- Upstream (newest, EasyProxy): Origin must be **exactly** `https://dembed.st` or
  `https://dlive.sx`, defaulting to `https://dembed.st`, and the mint sends
  `Referer: {origin}/` (`dlstreams.py:61-67,85-101,138-149`); slug is the
  `premium\d+` path component of the playlist URL itself. Resolver (1b) is looser
  (Origin = embed origin, `src/proxy/dembed.ts:34-37`) but still validates that
  `SRC` and `EDGE` are on the **same host** (`dembed.ts:22-27`).
- App: `App:635-636` mints with `request(tokenUrl, playerUrl)`, and `request()`
  (`App:1735-1746`) sets `Origin = scheme://host` **of the player/iframe URL** —
  correct only while the iframe really is on `dembed.st`; on a rotated embed host
  (EasyProxy's own comment names `strmd.st` as the other premium player host,
  `dlstreams.py:52`) the app sends an Origin the newest upstream code says is
  rejected, and its `Referer` is the full player-page URL rather than `origin + "/"`.
  The app also never checks `SRC` host == `EDGE` host, and takes the slug from the
  `CHANNEL` const (`App:48-50` patterns are unanchored `EDGE\s*=`, `CHANNEL\s*=`,
  `SRC\s*=` — any JS assignment in the page can satisfy them, including an ad
  config), where upstream anchors on `const SRC = "https://…m3u8"` etc.
  (`dembed.ts:7-9`).
- Token lifecycle otherwise matches upstream (half-TTL renewal: app
  `TokenSession`/`getOrRenewToken`, `App:111-165`, renew at `ttl * 500L`; upstream
  `dembed.ts:41-48`). One extra app-only risk: the token is bound to the caller's
  address (EasyProxy `dlstreams.py:54,71-72` keys its cache by egress route); the
  app keys sessions only by channel id (`TOKEN_SESSIONS`, `App:~100`), so a
  network/egress change mid-session reuses an address-bound token until a 403
  forces renewal.

### D8 — [DEAD] No WebM / fMP4 path at all: hub and livelive24 families, and `index.fmp4.m3u8`, cannot be served
- Upstream: hub playable is `video/webm` (`hub.ts:20-26`, `embed.ts:48-58`),
  livelive24 likewise (`livelive24.ts:11-13`); jetextractors maps
  `embed.html?token=` → `index.fmp4.m3u8` (`daddylive.py:201-203`).
- App: `ROUTES` includes `"hub"` (`App:40`) but treats it as just another
  `/{route}/stream-{id}.php` page to scrape for an `.m3u8`; there is no
  `ENCRYPTED_CONFIG`/AES handling, no WebM MIME anywhere, every resource is served
  `video/mp2t` (`App:1007-1011`), and `unwrapPayload` throws on any non-TS body
  (`App:1684`). If `hub` (PLAYER 7 in dlhd-web, `src/players/types.ts:8`) is the
  only working player for a channel, the app cannot play it.

### D9 — [STALE] No expiry-aware refresh for non-dembed signed URLs; 15-min sliding cache outlives the credentials
- Upstream: sessions carry `expiresAt` (from `?e=`, token payloads, `tokenExpiresIn:`)
  and refresh 45 s early (`src/proxy/session.ts:25,158-178`,
  `src/proxy/rules.ts:31-39` `needsRefresh`); wideiptv has its own POST refresh
  (`session.ts:93-131`); cdnlivetv derives expiry from its token
  (`cdnlivetv.ts:22-38`).
- App: the only refresh mechanism is the dembed `TokenSession`
  (`App:111-165`). For every other family the resolved URL is cached 15 min and
  the expiry is **pushed out on every successful manifest serve**
  (`App:853` `cr.expiresAt = System.currentTimeMillis() + 900_000L`), so a signed
  URL with a shorter life is served long past expiry; segment 403 handling
  (`fetchSegmentBytesInner`, `App:~1355-1375`) only helps when a dembed
  `TokenSession` exists, otherwise the 403 propagates. Symptom: a channel plays
  for minutes, then dies and — because the cache keeps sliding — does not recover
  until the 2-failure cache-drop in D2 fires.

### D10 — [DEAD/STALE] One header policy for all hosts vs per-host Referer/Origin rules; Android-TV UA everywhere
- Upstream: `src/proxy/rules.ts:16-28` — `tiktokcdn`, `bluetier`, `wideiptv`,
  `cdnlivetv` ⇒ **omit Referer entirely**; `tiestep/assetrage/7odxv…` ⇒ required;
  README PLAYER 1: TikTok-CDN segments want "no token, plain UA, no Referer".
  Per-server profiles add `refererMode: omit` for `casting`/`player`
  (`src/servers/casting.ts:7`, `player.ts:7`). StepDaddy's key fetch uses yet
  another shape (`Referer="{netloc}/"`, `Origin="{netloc}"`, `step_daddy.py:104-110`).
  UA upstream is a desktop browser UA (`src/http.ts:1-2` Chrome/131 macOS;
  StepDaddy Firefox/137).
- App: `App:1735-1746` sends the referer (the player page) **and** an Origin on
  every request to every host, with UA `App:32` "Linux; Android 14; Google TV
  Streamer … Chrome/120". On omit-hosts this is exactly the header set upstream
  learned to suppress; on key hosts it is neither StepDaddy's nor EasyProxy's
  shape. Any CDN that 403s on an unexpected Referer/Origin turns this into
  segment/key failure while pages and manifests (fetched from tolerant hosts)
  still succeed.

### D11 — [DEAD] Range-chunked segment download (HEAD + parallel 256 KB ranges + hedging) vs upstream's single whole GET
- Upstream: one plain GET per segment (`src/proxy/live.ts:119-132,160-171`);
  EasyProxy explicitly avoids Range for probing ("Do not use Range for a TS video
  probe", `_is_stream_alive` comment) and its segment fetcher is a single GET.
- App: `downloadRaw` (`App:~1100-1180`) issues a HEAD (with a 10-min per-host
  HEAD-failure memory), then a `Range: bytes=0-262143` probe, then parallel chunk
  GETs with a 3 s hedge race (`collectChunks`, `App:~1280-1345`). Risks upstream
  does not take: CDNs that sign **per method/range**, serve a different (image)
  variant to Range/HEAD, or return a 200/206 mix — each surfaces as a short-chunk
  / HTTP exception and a dead segment, and a mis-sized `Content-Range` total
  silently truncates or pads the assembled body before unwrapping. This is a
  plausible contributor when segments fail on the app but the same URLs play
  untouched in VLC/resolver, and it multiplies request count against
  address-bound-token CDNs (cf. D7).

### D12 — [STALE/BLACK-adjacent] Playlist cosmetics upstream deliberately does differently: synthetic PDT, no short-window refetch, no truncated-tail drop
- PDT: resolver **strips** PDT and adds nothing (`src/proxy/live.ts:75`);
  EasyProxy *uses* upstream PDT to rank players by live lag (`_playlist_lag`).
  App strips it too but then **invents** one from sequence+durations
  (`proxyManifest`, §2) — a wrong anchor shifts Media3's live offset / playback
  speed control; not a black-picture cause by itself, but it is app-only state
  that can go non-monotonic on the D2 timeline switch.
- Short window: resolver refetches a playlist with `< 6` media lines and keeps
  the larger (`live.ts:143-158`); app serves whatever the first fetch returned.
- Truncated tail: dlhd-web drops a final entry shorter than `0.95 ×
  TARGETDURATION` (`src/proxy/stream.ts:73-75`); app rewrites and serves it, so
  the player repeatedly requests a partial live-edge segment.

### D13 — [DEAD, family-specific] The whole StepDaddy / jetextractors CHANNEL_KEY flow is absent (auth.php, server_lookup, mono.m3u8, giokko/newkso CDNs, heartbeat/Bearer headers)
- Upstream: §1a (`step_daddy.py:61-102`) and §1e (`daddylive.py:109-139`) — two
  independent implementations agree on `CHANNEL_KEY` → auth → `server_lookup`
  → constructed `…/{channel_key}/mono.m3u8` on `*.newkso.ru` (StepDaddy) /
  `*.giokko.ru` (jetextractors, plus `GET https://chevy.giokko.ru/heartbeat` with
  `Authorization: Bearer …` + `X-Channel-Key`, `daddylive.py:120-130`).
- App: grep finds **no** `CHANNEL_KEY`, `auth.php`, `server_lookup`, `newkso`,
  `giokko`, `mono.m3u8`, `heartbeat` in the app sources; its `CHANNEL` pattern
  (`App:49`) is the dembed slug, a different const. Caveat on weight: StepDaddy's
  snapshot is the oldest here (2025-10-13) and the 2026 resolver has replaced
  this family with dembed for Players 1/3 — so treat D13 as "the `dlhd.dad`
  player variant is unsupported" rather than the mainline flow. It still matters
  for any channel/embed that still serves the CHANNEL_KEY player, and for the
  `#EXT-X-KEY` handling that goes with it: StepDaddy proxies keys with
  host-derived Referer/Origin (§1a), the app proxies `URI="…"` targets through
  the generic resource path with the player-page referer (`App:~830-850`, D10).

### D14 — [DEAD, lower] Base-domain set: no `dlive.sx`, no `dlhd.dad`; route 0 is a duplicate with a stale ntvs referer
- Upstream bases in active use: `dlive.sx` (resolver default, `src/config.ts:1`),
  `thedaddy.pk` (resolver compose pin + DaddyLiveHD playlists), `dlhd.dad`
  (StepDaddy `step_daddy.py:26`, jetextractors `daddylive.py:32`), `dlhd.st`
  (dlhd-web `src/config.ts:1`, dlhd-cli `API_BASE`, which also hardcodes
  `Referer/Origin = https://dlhd.st`, `v2-dlhd.py:47-50`).
- App: `EP:23,26-31` = `thedaddy.pk` + `dlhd.pk, daddylive.at, daddylive.app`.
  `dlive.sx` and `dlhd.dad` are never tried as player-page bases (the S14 mirror
  walk, `App:600-618`, iterates only `StreamEndpoints.BASES`). OkHttp redirect
  following mitigates this while `thedaddy.pk` still redirects correctly, which
  is why it ranks last — but if the primary stops redirecting to the live host,
  the app has no route to the two bases the current upstream code defaults to.
  Separately, route 0 (`App:40,596,1812`) spends one of 8 walk slots re-trying
  the `stream` page with `Referer: https://ntvs.cx/channel/phoenix/{id}` — no
  upstream repo has an `ntvs` route or that referer.

---

## 4. Matches (checked, NOT divergences — do not "fix" these)

- dembed const extraction + `t=`/`_=` attachment to manifests only, half-TTL
  renewal, 403 → invalidate + force-renew + one retry: app `App:628-665,684-710`
  vs resolver `src/proxy/dembed.ts:27-91`, `src/proxy/live.ts:204-216` — same shape.
- `#EXT-X-PROGRAM-DATE-TIME` stripping: both strip (app then synthesises, D12).
- PNG-pixel (`TIKTIKPX` + gzip), PNG-after-IEND, WebP-EXIF and `TIKTIKRAW`
  unwrapping: present in both (app `App:1399-1684`; resolver
  `unwrap-webp-ts.ts:17-185`) with equivalent PNG un-filtering (incl. Paeth).
- Master playlists are not collapsed to one variant by either proxy: both
  rewrite every variant line back through the proxy (app `App:~830-850`;
  resolver `live.ts:85-91`) — the divergence is only the token/query handling
  (D5) and variant indexing (resolver `live.ts:69-89`), not variant choice.
- `hub` as a *route name*: app `App:40` matches dlhd-web (`src/players/types.ts:8`,
  PLAYER 7) and EasyProxy's URL list; the 2026 resolver's six-route list
  (`src/servers/kinds.ts:1-8`) is the outlier. The divergence is D8 (no WebM
  handling), not the route's existence.
- Primary host `thedaddy.pk`: app `EP:23` matches the resolver's shipped
  `docker-compose.yaml` pin and DaddyLiveHD's playlists.

## 5. Suggested order of attack (for the parent, no code changed here)

1. D1+D2 together: probe the first segment of the accepted route (video PES /
   decode sanity, or at minimum "segment unwraps and contains a video PID") before
   caching `LAST_GOOD_ROUTE`, and keep the validated route pinned across
   manifest-failure re-resolves.
2. D5: stop stripping the playlist query before resolving relatives — inherit
   the parent query on relative variant/segment/key URIs, or swap the session
   token into child URLs as `live.ts:96-100` does.
3. D3+D4: port the resolver's extractor order + embed chain into `resolveSingle`
   (at minimum `_econfig`, wideiptv `streamUrl:`, cdnlivetv, daddy3) and reuse
   the `extractStreamUrl` fallbacks the app already has for external sources.
4. D6: add the `TIKTIKTSGZ` gunzip branch and the double-`0x47` scan fallback to
   `unwrapPayload`; stop throwing on non-TS bodies that are not PNG-magic.
5. D7+D10: per-host header rules (omit Referer/Origin on tiktokcdn/bluetier/
   wideiptv/cdnlivetv; allowlisted Origin + `Referer: origin + "/"` for the
   premium token mint).
6. D9, D11, D12, D13, D14 as listed.

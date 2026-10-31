## Result

I read all 14 files in full. I found 62 defects: 4 stream-killing, 22 degrading and 36 minor. Nothing was compiled or run, so every finding comes from reading the code. Where a finding depends on CDN or ExoPlayer behaviour I can't see, I say so.

The biggest problems are in `OnDeviceStreamServer.java`. The first four match the "dies minutes into playback" pattern.

### Defect register: `OnDeviceStreamServer.java`

**Stream-killing**

| # | Lines | Failure scenario | Exact fix |
|---|---|---|---|
| S1 | 396-423, 485-535; comment at 411-414 | The comment says "a failed manifest fetch drops the cache entry", but nothing ever calls `RESOLVE_CACHE.remove`. If the edge or `src` dies, or the baked URL is rejected, every manifest poll reuses the dead `Resolved` for 15 minutes. After the 90 s stale window the stream is dead, and re-tuning doesn't help. | In `proxyManifest`, when it throws or serves stale because of a 403, non-playlist body or HTTP error, remove all `RESOLVE_CACHE` keys starting with `id + ":"`. Keep `TOKEN_SESSIONS` untouched, per the stated timeline concern. Force a re-resolve after N consecutive failures. |
| S2 | 188, 402-404, 415 | The cache TTL is a fixed 15 minutes from creation, not sliding. At T+15:00 mid-playback, the player's manifest poll runs a full `resolve()` walk on the NanoHTTPD thread. That blocks the poll for seconds, can pick a different edge or timeline, and creates a new `TokenSession`. Failure of that walk is not covered by the stale fallback (line 202 wraps only `proxyManifest`). | On each successful manifest serve, extend `expiresAt` for the active id (sliding TTL). Wrap `resolve()` in the stale-manifest fallback. If a re-resolve fails, keep the expired `Resolved` rather than erroring. |
| S3 | 730, 739, 746-750, 769-779 | `prefetchTail` purges queued tasks with `PREFETCH_QUEUE.removeIf(...)`. The purged tasks' futures are never completed and never removed from `SEGMENT_INFLIGHT`. When the player then requests that segment (it sits 1-3 segments behind the edge), `pending.get(12s)` blocks 12 s on a future that can never complete, then fetches inline. This hits exactly when the pool of 3 is stalled on the 1-55 s CDN and the 36 s window is advancing. Each orphan also leaks permanently. | Drop queued tasks one by one: `if (PREFETCH_QUEUE.remove(t)) { SEGMENT_INFLIGHT.remove(t.url, t.future); t.future.cancel(true); }`. In `proxyResource`, ignore a future that is done, cancelled or not yet started, and fetch inline immediately. Cap the wait at about 3-4 s. |
| S4 | 979-1244 (fall-through at 1243), 725, 782-783, 943 | `unwrapPayload` returns the original PNG bytes whenever decoding fails: unknown filter, truncated inflate, gzip CRC failure, or an exception that is only logged. Those bytes are served as `video/mp2t` and cached in `SEGMENT_CACHE`, so a bad segment is replayed on every re-request. Separately, `RAW_BUF` is reused per thread, so rows read past `inflated` contain the previous segment's data (1111-1139). | After unwrapping, require `ts[0]==0x47` and `ts[188]==0x47` (if longer than 188 bytes). Otherwise throw, so nothing is cached and the player gets a 5xx. Check `inflated >= (rows needed)*(1+stride)` before the row loop. Reject filter types above 4. |

**Degrading**

| # | Lines | Failure scenario | Exact fix |
|---|---|---|---|
| S5 | 849-858 | A 206 with `Content-Range: bytes 0-262143/*` (or none) gives `probedTotal=-1 <= first.length`, so only the first 256 KB is returned as a complete segment. It is then truncated, fails to unwrap, and gets cached via S4. | If `probedTotal<0`, re-issue a plain GET without Range. |
| S6 | 871-912, 914-928 | The 3 s `get` timer starts when the loop waits, not when the chunk starts. Queued chunks look "stalled" and trigger duplicate hedges behind the same queue. `cancel(true)` only interrupts, and OkHttp's blocking sockets ignore that, so threads stay held up to 8 s. A quick chunk failure throws `ExecutionException` immediately: no retry, and the other chunks keep downloading and are discarded. | Keep the `Call` handle per chunk and call `call.cancel()` on the loser or on failure. Start hedge timers from task start (an `AtomicLong` startedAt). Catch `ExecutionException` and retry once. Wrap the collection in try/finally that cancels all futures. |
| S7 | 769-784 | Inline player fetches are not registered in `SEGMENT_INFLIGHT`. Nothing reaches ExoPlayer until the whole segment is assembled, up to about 30 s (HEAD 6 + 3 + 9 + 12). If ExoPlayer's read timeout is shorter (default 8 s; I can't confirm the app's setting), it retries, and each retry starts another full parallel download of the same segment. | Register a `CompletableFuture` via `putIfAbsent` before an inline fetch, so duplicate requests join it. Always complete the future and populate the cache. |
| S8 | 92-132, 503, 956 | `synchronized(session)` wraps network I/O of up to 13 s, so manifest and segment threads queue behind it. `force=true` skips the double-check, so N threads that hit 403 each renew in series. On failure `renewAt` is not advanced, so every call retries, and it returns the stale token. A persistently 403 channel gets a forced renew plus a retry on every poll. | Add a `renewedAt` or generation counter: skip a forced renew if the token changed since the caller read it, or was renewed in the last 5 s. On failure set `renewAt=now+5000`. Do the I/O outside the lock. |
| S9 | 463, 472-482, 487-494 | `TOKEN_SESSIONS` is keyed by id only. If route A created a session and a later resolve falls to the no-token `SOURCE` path on route B, the old session persists. `proxyManifest` then appends route A's token to route B's URL. | Remove `TOKEN_SESSIONS[id]` at the start of `resolveSingle`, or key by `id:player`. |
| S10 | 585 | `base` is derived from the requested URL including its query. A `/` in the query breaks relative resolution. After a redirect (`followRedirects(true)`), relative segments resolve against the pre-redirect host. | Use `response.request().url()` with the query stripped. |
| S11 | 599-603 | Every `URI="..."` attribute goes to `resourceUrl`, including `EXT-X-MEDIA` and `I-FRAME-STREAM-INF` playlist URIs. The player gets raw playlists labelled `video/mp2t` with un-proxied segments. | If the URI contains `.m3u8`, use `manifestUrl(...)`. |
| S12 | 543, 549, 571-580 | `Integer.parseInt` on MEDIA-SEQUENCE throws above 2^31 and the whole manifest fails. `substring(8, indexOf(','))` throws when there is no comma, which is swallowed and desynchronises `durs` from sequence numbers. The `for (s=anchor[1]; s<firstSeq; s++)` loop is unbounded: after an upstream timeline jump of 1e9 it spins on the NanoHTTPD thread. A 45 s re-anchor jump (576) exceeds the 36 s window and makes PDT non-monotonic. | Use `Long`. Guard the comma. If `firstSeq-anchor[1] > 1000`, re-anchor without looping. Lower the re-anchor threshold to about 20 s, or re-anchor only on a discontinuity. |
| S13 | 396-423, 425-483, 1317-1342, 1344-1365 | A cold resolve tries up to 8 routes × 3 fetches × (3 attempts, 12 s reads, plus 2.25 s backoff), which is minutes of walking. There is no single-flight, no negative cache, and no route-health memory (it always starts at route 0 because `player` is absent from channel URLs). Player retries spawn overlapping walks that are never cancelled. | Per-id single-flight future with an overall deadline of about 12 s. Negative-cache failures for about 10 s. Remember the last working route in a static field. |
| S14 | 427, 1371-1373 | The resolver only uses `StreamEndpoints.PRIMARY`. If `thedaddy.pk` is down, all resolves fail despite the mirror list. The domain hopped on 2026-10-30, per the code's own comments. | Iterate `StreamEndpoints.BASES` for player-page URLs on connection-level failure. |
| S15 | 817-830 | A HEAD precedes every segment. If the CDN signs URLs per method, HEAD returns 403, which is treated as fatal. That triggers a token renew plus a retry that also HEADs, so playback is dead. A stalled HEAD costs 6 s per segment. | Treat HEAD 403, 405 and 4xx as "no usable HEAD" and fall to the Range probe. Skip HEAD once it has failed for a host. |
| S16 | 914-928, 949-963 | `fetchChunkOnce` does not require `resp.code()==206`. If Range is ignored (200), every chunk downloads the whole body and then fails the length check. A chunk 403 is a generic `Exception`, so the token-refresh path never runs. Segment retries append `t=<token>`, which may break signed segment URLs (unknown). | Require 206, otherwise fall back to a single GET. Throw `HttpException` from the chunk path. Don't append `t=` to segment URLs unless proven needed. |
| S17 | 157-160, 816 | `Dispatcher` limits apply only to async `enqueue`; `execute()` ignores them. OkHttp negotiates HTTP/2 by default, so "parallel connections" may all multiplex over one TCP connection. One stalled connection then stalls every chunk, which defeats the design (unknown; depends on the CDN). | Set `.protocols(listOf(Protocol.HTTP_1_1))` and `connectionPool(ConnectionPool(16,...))` on `segmentClient`. Remove the misleading dispatcher config. |
| S18 | 966-977, 1081-1101, 722-732 | `ThreadLocal` buffers of 3+4 MB are allocated for every fresh NanoHTTPD request thread, which is per-request and gives no reuse. That is heavy GC churn on a TV. A crafted PNG with overflowing `h*(1+stride)` could throw `OutOfMemoryError`. `Error` is not caught by `catch (Exception)`, so the prefetch future never completes. | Use a small bounded buffer pool, or allocate sized buffers per call. Catch `Throwable` in `PrefetchTask.run`. Bound `w`, `h` and `rawSize` with `long` arithmetic. |
| S19 | 189-190, 220-236 | Non-numeric ids (the `-youtube` variants) go through `resolveExternal` on every manifest poll, uncached, fetching embed pages with retries every roughly 6 s. | Cache by `id+sources` with the same TTL logic. |

**Minor**

| # | Lines | Failure scenario | Exact fix |
|---|---|---|---|
| S20 | 756 | `Accept-Ranges: bytes` is advertised but Range is ignored, so a BYTERANGE playlist would break. | Remove the header, or honour Range. |
| S21 | 626-631, 761-763 | `activeChannelId` flips on any late request and is never reset. A new channel gets no prefetch until its first segment request. | Set it on manifest serve for the newest id and clear it on idle. |
| S22 | 64-65, 79, 656-657, 665-666 | `TOKEN_SESSIONS`, `RESOLVE_CACHE`, `LAST_GOOD_*`, `PDT_ANCHOR` and `SEG_DURATIONS` are never evicted. `TokenSession.expiresAt` is written but never read. | Evict by LRU or age. Delete `expiresAt`. |
| S23 | 127-129, 333-334, 550-551, 776-778, 828-830 | Silent catches hide failures. At 776-778 the `InterruptedException` is swallowed without restoring the interrupt flag. | Log at warn level. Call `Thread.currentThread().interrupt()` on interrupt. |
| S24 | 150, 180, 210-211 | The loopback proxy fetches any `url=` for any local app or LAN-driven browser page. Full URLs including tokens are logged at info level. | Require a per-launch secret query parameter. Redact tokens in logs. |
| S25 | 668-683 | The stale manifest (up to 90 s) outlives the 36 s live window. It likely trips Media3's playlist-stuck detection after about 3.5× target duration, so it buys roughly 20 s. | Cap staleness near 20 s, or return a 503 so the player's retry policy handles it. |
| S26 | 456, 106, 492 | The token is raw in `resolveSingle`'s manifest URL and test request, but URL-encoded elsewhere. A token containing `+`, `/` or `=` may fail the route test spuriously. | Encode consistently. |

### Defect register: other files

| # | File:lines | Severity | Failure scenario | Exact fix |
|---|---|---|---|---|
| SE1 | `StreamEndpoints.java` 26-32 | minor | `dlhd.st` and `dlstreams.st` redirect to the primary, so a `LiveCatalog` sweep fetches the same page about 3 times. `BASES` is only used by `LiveCatalog`. | Drop redirecting mirrors. Use `BASES` in the resolver (see S14). |
| L1 | `LiveCatalog.java` 93-127 | degrading | There is no backoff. When all bases fail, every `Repo.reload()` re-runs the 6-base sweep (up to about 23 s each), with concurrent sweeps possible. | Add an `AtomicBoolean` in-flight guard and a persisted `lastAttempt` (about 15 min backoff). |
| L2 | `LiveCatalog.java` 49-62 | minor | HTML entities are not decoded (`A&amp;E`). | Unescape the title. |
| L3 | `LiveCatalog.java` 160 | minor | Only `\t` is stripped. A newline in a name corrupts the TSV and truncates the entry. | `replaceAll("[\\t\\r\\n]+"," ")`. |
| L4 | `LiveCatalog.java` 98-102, 171-175 | minor | A new `OkHttpClient` is built per refresh. The rename fallback writes in place, which is the truncation race again. | Use a shared client. Fail the write if rename fails. |
| C1 | `ChannelCatalog.java` 47-110 | minor | Orphan `#EXTINF` lines (no URL) leak their group, logo and tvg-id into the next entry. | Reset state at each `#EXTINF`. |
| C2 | `ChannelCatalog.java` 118-121 | minor | `catch (Exception ignored)` on the live merge hides failures. | Log at warn level. |
| C3 | `ChannelCatalog.java` 95-96 | minor | `lastIndexOf(',')` truncates names that contain a comma. | Parse after the closing quote of the last attribute. |
| H1 | `Channel.java` 66-72 | minor, verify | `Boolean.compare(!isYT(a), !isYT(b))` sorts YouTube first. If callers expect non-YouTube first (the proxy can't resolve YouTube), the order is inverted. The caller is outside my files. | Swap the comparator arguments if non-YouTube is meant to lead. |
| H2 | `Channel.java` 47; `ChannelCatalog.java` 166 | minor | The `127.0.0.1:48913` string is duplicated in 3 places. | Add a shared constant. |
| G1 | `ChannelCategorizer.java` 186 | degrading | The loop condition is `leftIndex < leftIndex`, which is always false. Natural sort never runs and `compare(a,b)=compare(b,a)=1` for non-empty names, so name ordering is a no-op. Empty names make the comparator inconsistent, which risks TimSort's "violates its general contract" exception. | `leftIndex < left.length() && rightIndex < right.length()`. |
| G2 | `ChannelCategorizer.java` 16, 121 | minor | `[kw][a-z]{3}` matches any 4-letter k/w word (west, wild, kids, work) and is checked first. Group-less live channels land in LOCAL instead of SPORTS or KIDS. | Run the call-sign test last and restrict it to the original-case token. |
| F1 | `ChannelClassifier.java` 15 | minor | `\bplayer-\d+\b` and `\bsex\b` match anywhere in name+group and silently exclude legitimate channels. | Narrow the pattern and log excluded names. |
| F2 | `ChannelClassifier.java` 28, 88-90 | minor | `languageRank` is computed but the comparator never uses it. | Use it or delete it. |
| P1 | `Prefs.kt` 15-17 | minor | `put` has no `else`, so Double, Float and other types are silently dropped. | Add `else -> error(...)` or a log. |
| P2 | `Prefs.kt` 12-14 | minor | Typed getters throw `ClassCastException` if a key's stored type drifted between versions. | Wrap in try/catch returning the default. |
| P3 | `Prefs.kt` 96, 130 | minor | The PIN and playlist URLs (which may embed credentials) are stored in plaintext. | Use EncryptedSharedPreferences, or accept and document. |
| R1 | `Repo.kt` 48-67 | degrading | `reload()` has no sequence or single-flight guard. A slow older reload that finishes last overwrites newer `items`. | Use an `AtomicInteger` seq and commit only if it is still the latest. Coalesce. |
| R2 | `Repo.kt` 70, 173-185 | degrading | Each reload posts another 15 s `refreshEpg` with no dedup, and `refreshEpg` has no in-flight guard. Stacked ~25 MB downloads and parses in the same process as the proxy risk an OOM, which kills the stream. | Cancel the single posted Runnable before reposting. Add an `AtomicBoolean` running guard. Skip when `epgLastUpdate` is fresh unless forced. |
| R3 | `Repo.kt` 52-67, 32, 88 | degrading | Items are published only after all playlists are fetched, so a slow or trickling user playlist holds up the builtin guide. There is no `callTimeout`. | Publish builtin first, then merge. Add `.callTimeout(45s)`. |
| R4 | `Repo.kt` 98, 114 | minor | `addPlaylist` parses with the id "probe" and pollutes `m3uEpg`. `removePlaylist` never clears `m3uEpg[id]`, so the EPG sources of removed or disabled lists persist. | Parse with the real id. Remove entries on remove or disable. Clear at the start of reload. |
| R5 | `Repo.kt` 56-61 | minor | The catch is silent. A builtin asset parse failure on first launch gives an empty guide with no trace. | `Log.e`. |
| R6 | `Repo.kt` 178, 186, 193-204 | minor | `deleteRecursively` and the full-guide `search()` can run on the main thread. | Dispatch to a background thread. |
| R7 | `Repo.kt` 106 | minor | Positional ids `"$plId-<idx>"` mean a provider reorder remaps favorites, hidden and recents. | Hash URL plus name. |
| R8 | `Repo.kt` 87-90 | minor | `body!!.string()` has no size cap. | Cap the size. |
| R9 | `Repo.kt` 36, 42, 45 | minor | `listeners` is a plain `mutableListOf` read in `fire()`. `epgChannels` is dead. | Use `CopyOnWriteArrayList`. |
| M1 | `Reminders.kt` 66-76, 83, 110-145 | minor | `autoSwitch` is plumbed into the intent but the receiver never reads it, so the setting does nothing. | Implement it or remove the setting. |
| M2 | `Reminders.kt` 41-44 | minor | Expired reminders are filtered but never purged from prefs. | Purge in `all()`. |
| M3 | `Reminders.kt` 80-81 | minor | If `fireAt <= now` (inside the lead window) it returns silently and the reminder never fires. | Fire at `now+1s` if `startMs > now`. |
| U1 | `Updater.kt` 87-97, 118-128 | degrading | `checkInFlight` is released before the user answers the dialog. A second check can start a second download to the same `livetv-<tag>.apk`, and `FileOutputStream` truncates the file under the first writer. This partly refutes lead 10. | Keep a download-in-flight flag. Download to `.part` and rename. |
| U2 | `Updater.kt` 173-178 | minor | If the activity is finishing, it returns before `dismiss()`, so the window leaks. The dialog is non-cancelable and there is no `callTimeout`, so a stalled download hangs it. This partly refutes lead 9. | Dismiss before the guard, in a try. Add `callTimeout`. |
| U3 | `Updater.kt` 119-120 | minor | Old `livetv-<tag>.apk` files accumulate in the cache. | Delete older files. |
| U4 | `Updater.kt` 196, 205 | minor | `startActivity` is unguarded. Absent settings or installer activities on some TV builds throw `ActivityNotFoundException`. | Wrap in try/catch and show a message. |

### Prior leads in `audit-leads.md`

- **Fixed in 4.3.2:** leads 1, 2, 6, 8, 11, 14, 15 and 20.
- **Confirmed:** 16, 17, 19, 21 and 23.
- **Partly refuted:** lead 9 (now U2) and lead 10 (now U1).
- **Not my files:** leads 3-5, 7, 12, 13, 18 and 22 are `LiveTvInputService` and `ChannelSyncHelper`. I left them unverified.
- **StreamEndpoints:** the "clean" verdict mostly holds, but see SE1.

### Per-file verdicts

| File | Verdict |
|---|---|
| `OnDeviceStreamServer.java` | 26 defects (S1-S26) |
| `StreamEndpoints.java` | 1 defect |
| `LiveCatalog.java` | 4 defects |
| `ChannelCatalog.java` | 3 defects |
| `Channel.java` | 2 defects |
| `CatalogReport.java` | CLEAN |
| `ChannelCategorizer.java` | 2 defects |
| `ChannelCategory.java` | CLEAN |
| `ChannelClassifier.java` | 2 defects |
| `Prefs.kt` | 3 defects |
| `Reminders.kt` | 3 defects |
| `Repo.kt` | 9 defects |
| `Updater.kt` | 4 defects |
| `audit-leads.md` | read, all items dispositioned above |

## Files read or changed

Read, in full:
- `OnDeviceStreamServer.java` (1416 lines)
- `StreamEndpoints.java`
- `LiveCatalog.java`
- `ChannelCatalog.java`
- `Channel.java`
- `CatalogReport.java`
- `ChannelCategorizer.java`
- `ChannelCategory.java`
- `ChannelClassifier.java`
- `Prefs.kt`
- `Reminders.kt`
- `Repo.kt`
- `Updater.kt`
- `audit-leads.md`

I changed nothing.

## Checks run

- Read all 14 files with `read_file`. I ran no shell commands, no git, no builds and no tests.
- I did not recompute the `String.hashCode` constants in the `ChannelCatalog` switch, so I'm trusting the decompiled output there.

## Risks or unknowns

- These files look like JADX-decompiled output. The real source may differ from what I read.
- Several findings depend on things outside these files:
  - ExoPlayer's HTTP read timeout (S7).
  - Whether the CDN signs URLs per HTTP method (S15).
  - Whether the CDN serves HTTP/2 (S17).
  - Whether `t=` on segment URLs is harmful (S16).
  - `LiveTvInputService` timeouts.
  - `EpgRepository` threading and callback thread.
  - The manifest's cleartext-loopback, FileProvider and boot-receiver entries.
- I did not use the `/plan` artifact-and-approval flow. The brief is read-only and specifies an exact output format, so I answered it directly.

## Ask Miguel

Nothing for me to decide. If he wants fixes next, the safest order is S1, S3, S4, S2, then R2, S6, S7 and S8. I'd stay inside these 14 files unless    # this is the only signal that unlocks the
  milestone's completion; natural-language "please stop" phrasing in
  `instruction` is NOT interpreted as a stop signal.
- **'stop'**: Forcefully terminates the subagent, immediately halting
  device interactions and releasing the device. Use when the task is done,
  irreparably broken, or running out of control. Prefer
  `inject_instruction` with `release_loop=True` when a monitoring task
  should wind down cleanly instead of being killed.

Args:
    action: `"status"`, `"inject_instruction"`, or `"stop"`.
    trace_id: The task's session identifier from `mobile_run_task`.
    instruction: Guidance string; required for `inject_instruction`
      (optional when `release_loop=True`), omit otherwise.
    release_loop: With `inject_instruction`: explicit user stop signal
      that authorizes the subagent to complete continuous monitoring
      loops and finish the task gracefully.
  {"additionalProperties":false,"properties":{"action":{"title":"Action","type":"string"},"instruction":{"anyOf":[{"type":"string"},{"type":"null"}],"default":null,"title":"Instruction"},"release_loop":{"default":false,"title":"Release Loop","type":"boolean"},"toolSummary":{"description":"Brief 2-5 word noun phrase describing the specific task. Capitalize like a sentence. Some examples: 'Directory analysis', 'Web search', 'Git status check', 'Test execution', 'Code search'.","type":"string"},"trace_id":{"title":"Trace Id","type":"string"}},"required":["action","trace_id","toolSummary"],"title":"mobile_manage_taskArguments","type":"object"}B'
 mcp_artemis_mobile_run_task  This is a tool from the artemis MCP server.
Starts an autonomous mobile UI automation subagent on a connected Android device.

Delegates a mobile workflow to a background agent. Non-blocking: returns
immediately with `trace_id` (for `mobile_manage_task` / `mobile_inspect_trace`),
`device_serial`, `stdout_log`/`stderr_log` paths, and `notes_dir` (Pro only).
On completion (success or failure) a Reactive Wakeup notifies you — but only
when `conversation_id` was provided; without it, rely on your fallback timer.

### Model selection (routing)
- **Flash** (default, preferred): simple, deterministic tasks with a clear
  UI path. No step cap by default (history is compressed, and the agent
  can recall earlier steps and query the session recording), but no ADB
  shell, no pre-execution safety net, no persistent plan or notes, and
  no verification or written report — unsuitable for exploration,
  multi-branch recovery, structured monitoring/polling, or tasks that
  must report back large amounts of detail.
- **Pro**: complex, exploratory, or multi-branch tasks; continuous
  monitoring/polling; tasks needing ADB shell, system logs, verified
  checkpoints, multi-step planning, or detailed written output (via notes
  and `expected_output_desc`).

Timing is not precise: the agent's own inference adds ~5 s per step (Flash)
or ~30 s per turn (Pro) on top of any requested waits — account for this
when the task involves waiting. For recurring workflows, run once to
discover the path, then author a deterministic script instead of
re-delegating.

### Follow-up protocol (CRITICAL)
1. **Fallback timer**: you MUST set a background timer and check the task
   status via `mobile_manage_task` at least once every 1 minute — mobile
   tasks can stall silently, so never rely on the completion wakeup alone.
2. **Post-task inspection**: after completion, inspect the execution logs
   to verify success (`stderr_log` carries critical failures and Python
   tracebacks — essential for debugging).
3. **Pro notes**: for Pro runs, read `notes_dir` to extract the results and
   plans the subagent recorded.

Args:
    task_desc: Clear, self-contained task description with all context,
      exact UI actions, and termination conditions (execution is one-shot).
      For Pro, may also specify what the subagent must record in its notes.
    conversation_id: Optional conversation ID used to route the completion
      wakeup notification back to the caller. Omit if unknown.
    model: `"Flash"` or `"Pro"` — see model selection above.
    locked_app_package: Optional package name to lock execution to; the
      agent auto-launches it and restricts actions to that app.
    app_path: Optional local APK path to install before running.
    expected_output_desc: Optional, Pro only. If set, a summarization agent
      writes a report to `output.md` in `notes_dir`. Ignored for Flash.
    device_serial: Optional device serial (e.g. "emulator-5554") to bind
      execution to a specific device; distinct devices run concurrently.
      If omitted, an available device is selected automatically. When
      several devices are attached, confirm the target with the user first
      (`adb devices -l` lists serials and authorization states).
    verification_level: Optional, Pro only. Coarse Checker preset: `"off"`
      (no audit; the Operator self-reports), `"final"` (one exit review
      against the goal, the default), `"checkpoints"` (every plan
      checkpoint is verified + exit review), `"strict"` (checkpoints with
      a larger repair budget; a failed assert halts the run). Ignored for
      Flash.
    explorer_mode: Optional, Pro only. Explorer perception version used
      by the Operator: `"flash"` (1-shot detection, the default),
      `"pro"` (3-turn ReAct), `"ultra"` (deep pixel reasoning; slowest).
      Ignored for Flash.
 	{"additionalProperties":false,"properties":{"app_path":{"anyOf":[{"type":"string"},{"type":"null"}],"default":null,"title":"App Path"},"conversation_id":{"anyOf":[{"type":"string"},{"type":"null"}],"default":null,"title":"Conversation Id"},"device_serial":{"anyOf":[{"type":"string"},{"type":"null"}],"default":null,"title":"Device Serial"},"expected_output_desc":{"anyOf":[{"type":"string"},{"type":"null"}],"default":null,"title":"Expected Output Desc"},"explorer_mode":{"anyOf":[{"type":"string"},{"type":"null"}],"default":null,"title":"Explorer Mode"},"locked_app_package":{"anyOf":[{"type":"string"},{"type":"null"}],"default":null,"title":"Locked App Package"},"model":{"default":"Flash","title":"Model","type":"string"},"task_desc":{"title":"Task Desc","type":"string"},"toolSummary":{"description":"Brief 2-5 word noun phrase describing the specific task. Capitalize like a sentence. Some examples: 'Directory analysis', 'Web search', 'Git status check', 'Test execution', 'Code search'.","type":"string"},"verification_level":{"anyOf":[{"type":"string"},{"type":"null"}],"default":null,"title":"Verification Level"}},"required":["task_desc","toolSummary"],"title":"mobile_run_taskArguments","type":"object"}B 
 call_mcp_tool fCall a lazy-loaded MCP tool. Read the tool's schema file to understand the tool's arguments and usage.  {"$schema":"https://json-schema.org/draft/2020-12/schema","properties":{"ServerName":{"type":"string","description":"Name of the MCP server."},"ToolName":{"type":"string","description":"Name of the tool to call."},"Arguments":{"description":"Arguments to pass to the tool."}},"additionalProperties":false,"type":"object","required":["ServerName","ToolName","Arguments"]}B 
 list_resources 1Lists the available resources from an MCP server.  {"$schema":"https://json-schema.org/draft/2020-12/schema","properties":{"ServerName":{"type":"string","minLength":1,"description":"Name of the server to list available resources from."}},"additionalProperties":false,"type":"object","required":["ServerName"]}B 
 read_resource *Retrieves a specified resource's contents.  {"$schema":"https://json-schema.org/draft/2020-12/schema","properties":{"ServerName":{"type":"string","description":"Name of the server to read the resource from."},"Uri":{"type":"string","description":"Unique identifier for the resource."}},"additionalProperties":false,"type":"object"}J	  R     
 
 System Prompt      * 
 identity  * 
	artifacts 	* 
 conversation_transcript  * 

guidelines  * 
 communication_style  * 
 slash_commands  * 
	messaging  * 
 mcp_servers  * 
	subagents  * 

user_rules '* 
 skills B* 
 user_information  
 
 Tools     W* 
	read_file  * 
 run_command  * 
 manage_task  * 
 send_message  * 
 schedule  * 
 invoke_subagent  * 
 define_subagent  * 
 manage_subagents  * 

write_file  * 
	edit_file  * 

search_web  * 
 ask_question  *(
#mcp_artemis_mobile_get_device_state  * 
 mcp_artemis_mobile_diagnose  *%
 mcp_artemis_mobile_inspect_trace  *#
 mcp_artemis_mobile_manage_task  * 
 mcp_artemis_mobile_run_task  * 
 call_mcp_tool  * 
 list_resources l* 
 read_resource s
 
 Chat Messages    * 
 0: USER_INPUT  * 
 1: PLANNER_RESPONSE  * 

2: GENERIC  * 

3: GENERIC u* 

4: GENERIC  * 

5: GENERIC  * 
 6: PLANNER_RESPONSE  * 

7: GENERIC R* 

8: GENERIC  * 

9: GENERIC  * 
 10: GENERIC  * 
 11: GENERIC ** 
 12: GENERIC  * 
 13: GENERIC  * 
 14: PLANNER_RESPONSE  * 
 15: GENERIC  * 
 16: GENERIC  * 
 17: GENERIC  * 
 18: GENERIC     3R R   R   R   R   R   R   R   R   R  	R  
R   R   R   R   R   R   R   R   R     Z     b	   䚎   
 identity  <identity>
You are Antigravity, a powerful agentic AI coding assistant designed by the Google Deepmind team working on Advanced Agentic Coding.
You are pair programming with a USER to solve their coding task. The task may require creating a new codebase, modifying or debugging an existing codebase, or simply answering a question.
The USER will send you requests, which you must always prioritize addressing. User requests are enclosed within <USER_REQUEST> tags.
</identity>"     identity0 8   
	artifacts  <artifacts>
Artifacts are special markdown (.md) documents that you can create to present structured information to the user.
All artifacts should be written to the artifact directory: `<appDataDir>/brain/<conversation-id>`. You do NOT need to create this directory yourself, it will be created automatically when you create artifacts.

# When to Use Artifacts

**Use artifacts for:**
- Extensive reports and analysis summaries
- Persistent information you'll update over time (task lists, experiment logs)
- Code changes formatted as diffs

**Don't use artifacts for:**
- Simple one-off answers or very short paragraph content - just respond directly
- Asking questions or requesting user input - just ask directly

**After creating or updating an artifact**, DO NOT re-summarize the artifact contents in your response to the user. Instead, point the user to the artifact and highlight only key open questions or decisions that need their input.


# Artifact Formatting Tips
When creating markdown artifacts, use standard markdown and GitHub Flavored Markdown formatting.

## Alerts
Use GitHub-style alerts strategically to emphasize critical information. Do not place consecutively or nest:
  > [!NOTE] Background context, implementation details, or explanations
  > [!TIP] Performance optimizations, best practices, or efficiency suggestions
  > [!IMPORTANT] Essential requirements, critical steps, or must-know information
  > [!WARNING] Breaking changes, compatibility issues, or potential problems
  > [!CAUTION] High-risk actions that could cause data loss or security vulnerabilities


## Mermaid Diagrams
Create mermaid diagrams using fenced code blocks with language `mermaid` to visualize relationships, workflows, and architectures.
- Only use supported diagram types:
  - Flowcharts / Graphs: `flowchart TD` / `flowchart LR` / `graph TD` / `graph LR`
  - Sequence Diagrams: `sequenceDiagram`
  - State Diagrams: `stateDiagram-v2` or `stateDiagram`
  - Class Diagrams: `classDiagram`
  - Entity-Relationship Diagrams: `erDiagram`
  - XY Charts: `xychart-beta`
- All other diagram types are unsupported. For schedules, timelines, or roadmaps, use directed flowcharts (`flowchart LR` / `flowchart TD`) or Markdown tables instead.
- To prevent syntax errors:
  - Quote node labels containing special characters like parentheses or brackets. For example, `id["Label (Extra Info)"]` instead of `id[Label (Extra Info)]`.
  - Avoid HTML tags in labels.

## File Links
- Link to line ranges using [link text](file:///absolute/path/to/file#L123-L145) format.
- **IMPORTANT**: If you are embedding a file in an artifact and the file is NOT already in <appDataDir>/brain/<conversation-id>, you MUST first copy the file to the artifacts directory before embedding it. Only embed files that are located in the artifacts directory. Always use its absolute path `![caption](/absolute/path)`.
- **Use basenames for readability**: Use file basenames for the link text instead of the full path

## Carousels
Use ````carousel syntax with `<!-- slide -->` HTML comments to display related markdown snippets sequentially (before/after comparisons, UI progressions, alternative approaches, walkthroughs). Four backticks enable nesting code blocks within slides.

Example:
````carousel
![Image description](/absolute/path/to/image1.png)
<!-- slide -->
```python
def example():
    print("Code in carousel")
```
````

# Scratch Scripts and Files

You may find it useful to create scratch scripts or files for temporary purposes.

Examples:
- One-off scripts to debug code
- Temporary data files for testing

Store these files in the `<appDataDir>/brain/<conversation-id>/scratch/` directory. They will be persisted.

</artifacts>"    	artifacts0 8   
 conversation_transcript  <conversation_transcript>
Transcripts are located directly at `<appDataDir>/brain/<conversation-id>/.system_generated/logs/transcript.jsonl` (and `transcript_full.jsonl`).
- Start with `transcript.jsonl` (compact). When `truncated_fields` is present, read only the specific corresponding line in `transcript_full.jsonl`.
- Search subagents by grepping `invoke_subagent` in `transcript.jsonl`.
- Link conversations using `[<label>](conversation://<conversation-id>)`.

# File Format
Transcripts are in JSON Lines (JSONL) format. Each line is a single JSON object representing one "step" or action in the conversation.
Each JSON object contains fields such as:
- `step_index`: The index of the step in the trajectory.
- `source`: The source of the action (e.g., `USER_EXPLICIT`, `MODEL`, `SYSTEM`).
- `type`: The type of the step. Particular steps of interest are `USER_INPUT`, which represents a user's prompt, and `PLANNER_RESPONSE`, which represents the agent's response and tool calls.
- `status`: The status of the step (e.g., `DONE`, `ERROR`).
- `created_at`: The ISO 8601 timestamp of when the step occurred.
- `content`: The text content of the step (e.g., the user's request, the model's response, or tool responses).
- `thinking`: The model's internal reasoning / chain-of-thought (for `PLANNER_RESPONSE` steps).
- `tool_calls`: An array of tool calls made in this step, including their arguments.
- `media`: An array of media (e.g. images) attached to the step, each with a `mime_type` and a `uri` referencing the media file on disk. Media bytes are not stored in the transcript; view the `uri` path to see the media.
- `truncated_fields`: An array of field names that were truncated (e.g., `["content"]`, `["thinking"]`, `["tool_calls"]`). Only present in `transcript.jsonl` when truncation occurred (never in `transcript_full.jsonl`). When present, read the corresponding line in `transcript_full.jsonl` for the complete content.

</conversation_transcript>"     conversation_transcript0 8   

guidelines  <guidelines>
Follow these behavioral guidelines at all times:
- Maintain documentation integrity. Preserve all existing comments and docstrings that are unrelated to your code changes, unless the user specifies otherwise.
- Never defeat or bypass an access control, permission check, credential requirement, network/proxy restriction, or sandbox/environment boundary - including writing to the state or processes such a check reads, spoofing identity, intercepting the check, probing undocumented local/host services, or modifying host, harness, or test infrastructure outside your task scope. Use documented, sanctioned paths only; if none of them work, you are blocked - report the error and stop.

</guidelines>"    
guidelines0 8   
 communication_style  <communication_style>
- Keep your responses concise.
- Format your responses in github-style markdown.
- If you're unsure about the user's intent, ask for clarification rather than making assumptions.
- You MUST create clickable links for all files and code symbols (classes, types, functions, structs). Use github style markdown links with the file:// scheme (e.g., [utils.py](file:///path/to/utils.py) or [`ClassName`](file:///path/to/utils.py#L10-L20)). For Windows, use forward slashes for paths.
</communication_style>"     communication_style0 8   
 slash_commands  <slash_commands>
Slash commands are user-facing shortcuts in the chat UI (e.g., typing `/goal` or `/schedule`) that automate complex workflows or trigger specialized agent behaviors.

You cannot execute these commands yourself. Your role is to recommend them to the user when they are a good fit for the task at hand, encouraging the user to explore and trigger them.

To recommend a slash command, suggest it clearly in your response (e.g., "You can use the `/goal` command to...").


Available slash commands you can recommend to the user:
- /goal: Recommend this when the user wants to run a long-running task (e.g., overnight) and wants the agent to be extra thorough and not stop until the goal is fully achieved.
- /compact: Recommend this when the conversation is getting long and the user wants to compact or summarize the context to free up the context window.
- /schedule: Recommend this when the user wants to run an instruction on a recurring schedule or set a one-time timer.
- /browser: Recommend this when the user's task involves web browsing, searching the web, or interacting with web applications.
- /plan: Recommend this when the task is complex and requires careful step-by-step planning before execution.
- /grill-me: Recommend this when the user wants to align on a plan through an interactive interview to resolve design decisions.
- /teamwork: Recommend this when the user has a large project that would benefit from a team of autonomous agents working together.
- /learn: Recommend this when the user has corrected the agent or solved a complex setup and wants the agent to persist this behavior for future tasks.
- /boost: Recommend this when the user has a complex coding or research project that requires deep thinking, strategic planning, multiple perspectives, and rigorous verification.


</slash_commands>"     slash_commands0 8   
	messaging  <messaging>
You are connected to a messaging system where you may receive messages from: agents, background tasks, user-queued messages.

## Receiving Messages

You receive messages automatically at the start of each invocation. All messages are delivered in full directly into your context — no manual retrieval is needed.

## Reactive Wakeup (No Polling Needed)

The system automatically resumes your execution when:
- A message arrives from a subagent or peer agent
- A **background task** completes or sends you a notification
- A **user-queued message** is ready to
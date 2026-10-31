## Result

I read all three files in full, as static analysis only. Nothing was run on a device, and nothing was edited. Line numbers are from the current tree. "Verify" marks items that depend on Media3 behavior or on files I was not asked to read.

### Headline finding

The v4.3.2 net watchdog still fires on healthy playback. This is not fixed.

- **Cause:** `currentPosition` on a live HLS window is relative to the window start. At the live edge it hovers around a constant (about 20 s with a 16 s seat) instead of climbing, because the window start slides forward at about 1 s/s.
- **Evidence in the repo:** the code comments describe this ("startPos 35.69 at the live edge" vs "10.46", "position crawling and resetting as the window slides").
- **Effect:** the net samples `currentPos` over 20 s and declares "stuck" if it moved less than 2 s. On healthy playback that delta is roughly −4 to +2 s, so the net trips about 21 s after first READY.
- **Cycle:** the 60 s `lastNetRecoveryMs` gate then makes this a re-prepare about every 60–80 s, forever.
- **Why it never escalates:** `consecutiveNetRecoveries` is reset by the 30 s stable-READY rule before it can reach 3.
- **What v4.3.2 changed:** gating on first READY only moved the false verdict from 25 s into the tune to about 21 s after READY.

### Defect register

**PlayerEngine.kt**

| # | Line | Severity | Failure scenario | Exact fix |
|---|---|---|---|---|
| P1 | 229, 262–304 (net compare at 277) | stream-killing | Healthy live playback is declared "stuck" every 20 s window, then reprepared every 60–80 s (see headline). It also feeds `rebufferTimesMs` and can trigger the route-escalation path at 288–296. That path wipes the saved good route (294) on a false verdict. | Add a helper `absPos(p) = tlWindow.positionInFirstPeriodMs + p.currentPosition`, using `p.currentTimeline.getWindow(p.currentMediaItemIndex, tlWindow)`. Use it for `currentPos` at 229 and everywhere `currentPos` is compared (277, 338, 344, 358, 374). Keep `<2000` meaning "less than 2 s of media progress in 20 s". |
| P2 | 181, 262–265 | stream-killing | The net, stall and freeze verdicts all need first READY. After any `reprepare()` or `play()`, a loader that sits in BUFFERING forever (the "87 s idle loader" case) has no watchdog at all. | Add a tune deadline in the health tick: `if (firstReadyMs == 0L && now - playbackStartMs > 45_000L)`. First action: `reprepare`. Second miss: `routeAttempt++; play(item,false)`. Use a separate cooldown stamp. |
| P3 | 174–186 | degrading | `stop()` followed by `prepare()` resumes at the old window-relative position on a brand-new timeline, which can land behind the window. That fits the 21:46:41 BEHIND_LIVE_WINDOW symptom (verify). `playbackStartMs` is not reset, so the 12 s grace relies on `onVideoEnabled` firing again. | In `reprepare`: `p.seekToDefaultPosition()` before `prepare()`, set `playbackStartMs = now`, set `lastPlayState = -1`. |
| P4 | 233–238 | degrading | Every watchdog `reprepare` goes READY→BUFFERING and is counted as a "rebuffer" by the starvation detector. Four recoveries or natural stalls in 90 s then force a route switch that was caused by the player's own recovery. | Set `lastPlayState = -1` in `reprepare`. Optionally suppress counting for 10 s after any recovery. |
| P5 | 355–358, 374–386 | degrading | The freeze verdict needs `currentPos - positionAtRenderAdvance > 1500`. That quantity is about 0 at the live edge, so the freeze detector almost never fires. The stall check is likewise masked, because a real stall makes the window slide under the playhead and the delta jumps. | Same `absPos` fix as P1. |
| P6 | 313–321 | degrading | The desync seek has no cooldown or counter. If the offset stays above 25 s after `seekToDefaultPosition` (for example when the offset is unreliable), it seeks every second. It also overrides user rewinds: `seekRelative` (808–814) and `seekToProgramStart` (783–792) put the offset above 25 s, and the watchdog yanks it back to live within 1 s. | Add `userBehindLive` (set in seek functions, cleared in `returnToLive` and `play`) and skip the check while it is set. Add a 15 s cooldown stamp between desync seeks. |
| P7 | 560, 582 | stream-killing | `decoderRebuildsSinceReady` is zeroed on every READY. A decoder that reaches READY on audio and then fails with DECODING_FAILED rebuilds the player forever, and `onError` is never reached. | Remove the reset at 560. Reset it in the 30 s-stable block at 211–218. |
| P8 | 61, 463, 586, 365 | degrading | `decoderEscalation` never decays and survives channel changes. One transient DECODING_FAILED flips the Streamer to software for the rest of the session (it cannot do HD, so frame-drop bursts). Escalations are `%2`, so they ping-pong. | Reset `decoderEscalation = 0` in `play()` when `resetRetry` is true, or in the stable block. |
| P9 | 460–465 | degrading | The ladder only does something for `isGoogleTvStreamer` with Auto. With an explicit "Hardware" or "Software" preference, or on non-MTK devices, "rebuild on the other decoder" rebuilds into the same decoder (3× at 582, and the freeze path at 363–368 loops forever, every ~20 s). | Track real escalation: when the preference is explicit or the device is not a Streamer, skip `flipDecoderAndReload` and go straight to the route and retry path. Cap total flips per channel. |
| P10 | 603–605, 641 | degrading | The pending auto-retry (2.5 s delayed `play`) is not cancelled by `play()` or a zap. If the user zaps inside 2.5 s, the retry re-tunes the new channel mid-tune and resets its clocks. | Keep `retryRunnable`. In `play()` call `mainHandler.removeCallbacks(retryRunnable)` and null it. |
| P11 | 641–654 | degrading | `play()` does not reset several pieces of state. | In `play()` also reset: `consecutiveNetRecoveries = 0`, `lastNetRecoveryMs = 0`, `lastStarvationEscalationMs = 0` (when `resetRetry`), and `decoderRebuildsSinceReady = 0`. |
| P12 | 655, 930–938 | degrading | `release()` leaves `currentItem` set, and `play()` re-creates a player if `exoPlayer == null`. Any late caller (for example the `Repo.reload` callback after `onDestroy`) resurrects a zombie ExoPlayer that nobody releases, with audio playing and no surface. | Add `@Volatile private var released`. Set it in the final release (`onDestroy`) and make `play()` return if it is set. Give `release()` a `terminal` parameter. |
| P13 | 723–734 | degrading | `getMinimumLoadableRetryCount = 1` makes `Loader.maybeThrowError` throw after about 2 consecutive failures. This contradicts the "two quick retries" comment. A local-proxy blip then costs a full error plus 2.5 s plus re-tune (verify against Media3 1.8.0). | Return 3 (or 4) from `getMinimumLoadableRetryCount`. Keep `errorCount <= 2` delay at 500 ms but bound it by `errorCount <= 3`. |
| P14 | 574–579 | degrading | The BEHIND_LIVE_WINDOW handler is unbounded and does not reset watchdog or ready state. A persistent loop (a slow origin) never escalates. | Count BEHIND_LIVE_WINDOW events per 60 s. On the 3rd, `routeAttempt++; play(item,false)`. Also set `playbackStartMs = now`. |
| P15 | 189–199, 365–369, 622 | minor | `flipDecoderAndReload` is called inside the runnable. `initPlayer()` posts a health tick at 2000 ms, then the runnable also posts itself at 1000 ms, so two chains run (harmless for stall detection, but double CPU and double recoveries). | `flipDecoderAndReload` is the only in-runnable `initPlayer`. After it, `return` without re-posting, or `removeCallbacks(this)` before re-posting. |
| P16 | 217 | minor | After 30 s READY, `prefs.put(route_*)` and the resets run every second forever (disk churn). | Add a `routeSaved` flag, cleared on `play`. |
| P17 | 413–419 | minor | The "Large" and "Very large" buffer options equal "Normal" (dead options). | Document or remove them. |
| P18 | 482–483 | minor | `setPreferredAudioLanguage` replaces the earlier `setPreferredAudioLanguages("en","eng")` list. | Pass one list: `setPreferredAudioLanguages(prefs.preferAudioLang.ifBlank{"eng"}, "en", "eng")`. |
| P19 | 451–453 | minor | `Build.HARDWARE.contains("mt")` matches every MediaTek SoC (the other two clauses are redundant) and makes "isGoogleTvStreamer" much broader than named. | Tighten to `"mt8696"` or `Build.MODEL` only, or rename the variable. |
| P20 | 183–185, 402–404 | minor | Exceptions are logged and swallowed. A failed `reprepare` is never retried and nothing is escalated. | In `reprepare`'s catch, fall back to `play(currentItem, false)`. |
| P21 | 783–792 | minor | `seekToProgramStart` assumes the playhead is at the live edge (ignores the live offset). Called right after `playChannel` for another channel, `currentPosition` is 0, so it returns false silently. | Use `behindMs - (currentLiveOffset ?: 0)` and wait for READY before seeking. Show the result to the caller. |
| P22 | 804–806 | minor | `resume()` calls `play()` unconditionally, overriding a manual pause and replaying after fatal errors. Called from `onResume` (MainActivity 858). | Remember whether the user paused (`userPaused`) and only resume if not. |

**MainActivity.kt**

| # | Line | Severity | Failure scenario | Exact fix |
|---|---|---|---|---|
| M1 | 290–295, 814–824 | degrading | `onPlay` and `onFullscreen` always call `playChannel`, and `play()` always re-tunes. Pressing OK on the already-playing, healthy channel restarts the stream. | In `playChannel`: `if (playerEngine.currentItem?.key == item.key && (playerEngine.isPlaying \|\| playerEngine.isBuffering)) { guideView.playingKey = item.key; return }`. |
| M2 | 96–99, 85–91, 868–871 | degrading | `Repo.listeners.add {…}` is never removed, and the dialog handlers (static) capture the Activity. Every Activity recreate leaks one more listener that drives destroyed views, and a late reload callback can call `playChannel` on a released engine (see P12). | Store the listener and remove it in `onDestroy`. Null `ProgramDetailsDialog.catchUpHandler` and `liveHandler`. Cancel `mainHandler` and `prewarmHandler`. |
| M3 | 751–776 | degrading | Each focus-rest spawns a new thread with a new `OkHttpClient` and a 30 s read, resolving a channel (5–15 s) against the local proxy while the current stream plays. Scrolling 20 rows gives about 20 concurrent resolutions that can starve the playing channel's segment fetches (verify against `OnDeviceStreamServer`). Nothing is cancelled in `onDestroy`. | Use one shared client, track one in-flight `Call` and cancel it on the next prewarm and in `onDestroy`. Skip while the engine is buffering or when fullscreen. |
| M4 | 856–866 | minor | `onPause` pauses and `onResume` always plays (see P22). A long pause leaves the stream behind the window, so every resume re-syncs through a BEHIND_LIVE_WINDOW error plus re-tune. | Don't pause live TV on `onPause` unless the activity is actually stopping, or on resume do `seekToDefaultPosition()` first. |
| M5 | 873–884 | minor | `catch (e: Exception) {}` swallows PiP failures silently. | Log it. |
| M6 | 111–115 | minor | No `onNewIntent` override: a reminder tap with the Activity already alive ignores `channelKey` (verify manifest launch mode, not read). | Add `onNewIntent` that handles the extra via `Repo.byKey`. |
| M7 | 664–688 | minor | `closeSettings` has no re-entrancy guard. Reopening during the 200 ms close lets the end action remove the freshly reopened drawer. | Set a `closingSettings` flag and cancel on reopen. |
| M8 | 85–88 | minor | `catchUpHandler` calls `playChannel` then `seekToProgramStart` immediately (see P21). | Wait for READY first. |

### v4.3.2 changes audited

- **Seat 16 s (target 16 s, min 10 s, max 30 s):** the offset is sound against the ~36 s window (about 20 s eviction slack). Two interactions remain.
  - The 25 s desync check (313–321) lets a 10 s rebuffer push the offset past the threshold and seek a healthy stream (P6).
  - Rewinds are fought by the same check.
- **Net watchdog gated on first READY:** this does not fix the churn. It moves the false verdict (P1) and creates the permanent-hang hole in P2.
- **`reprepare()` resetting the net clocks:** correct but incomplete. It misses `playbackStartMs`, `lastPlayState`, `seekToDefaultPosition` and an after-reprepare tune deadline (P2, P3, P4).

### Lead cross-check (audit-leads.md)

None of the 23 leads names PlayerEngine.kt or MainActivity.kt directly. Their only touch points:

- **Leads #1, 2, 6, 16 (Repo/Prefs):** they feed `Repo.reload` and `Repo.listeners`, which MainActivity depends on (96–136).
  - If the reload thread dies, the callback that starts playback never fires.
  - Repeated reloads call `updateGroups` and `updateGuideRows` many times.
  - I could not confirm or refute these without reading Repo.kt and Prefs.kt.
- **Leads #20, 21 (Reminders):** MainActivity's side (the `channelKey` extra, lines 111–115) is consistent with lead #21's finding that auto-switch is never carried. No `onNewIntent` (M6).
- **Leads #3–5, 18 (LiveTvInputService):** a different player, out of scope.

## Files read or changed

Read in full, no edits:
- `app/src/main/java/com/livetv/app/player/PlayerEngine.kt` (946 lines)
- `app/src/main/java/com/livetv/app/MainActivity.kt` (969 lines)
- `audit-leads.md` (28 lines)

Changed: none.

## Checks run

- I ran no shell commands, no build, no tests and no git. I used `read_file` only.
- Every verdict above is from reading the code. Nothing was reproduced on a device or emulator.

## Risks or unknowns

- **Unverified Media3 1.8.0 behavior:** P1 (window-relative `currentPosition`), P3 (`stop()`/`prepare()` resume position) and P13 (`getMinimumLoadableRetryCount` feeding `Loader.maybeThrowError`) rest on my reading of Media3 semantics plus the code comments. A one-line log of `currentPosition` and `positionInFirstPeriodMs` over 60 s of healthy playback would confirm P1.
- **Files I was not asked to read** (the second verification and fix pass should include them):
  - `PlayerOverlay.kt`
  - `Repo.kt` (callback thread for `Repo.reload`)
  - `OnDeviceStreamServer.kt` (`ROUTES` count vs `ROUTE_COUNT = 8`, startup cost, prewarm effect)
  - `AndroidManifest.xml` (`configChanges`, `launchMode`, PiP)
- **Main-thread cost:** `OnDeviceStreamServer.ensureStarted()` runs in the PlayerEngine constructor on the main thread (402). I could not measure its cost.
- **Aspect ratio:** `enterFullscreen` forces `MATCH_PARENT` on a SurfaceView (831–834). It will stretch non-16:9 video unless PlayerOverlay re-applies `setAspectRatio`, which I could not check.
- **Fix interactions:** the P1/P2/P6 fixes change recovery timing together, so they should be applied and soaked as one unit, not piecemeal. A partial fix could reopen the hang holes.

### Per-file verdicts

| File | Verdict |
|---|---|
| `PlayerEngine.kt` | 22 defects (3 stream-killing: P1, P2, P7; 11 degrading; 8 minor) |
| `MainActivity.kt` | 8 defects (0 stream-killing; 3 degrading: M1, M2, M3; 5 minor) |
| `audit-leads.md` | Audit input, not code. 0 defects. 0 of 23 leads touch these files directly (4 indirect, see above). |

## Ask Miguel

- Do you want me to implement the fixes? This brief was audit-only, so I made no edits. If yes, I'd start with P1, P2 and P7, then P6, M1 and P12.
- Do you want me to extend the audit to `PlayerOverlay.kt`, `Repo.kt`, `OnDeviceStreamServer.kt` and the manifest? That needs your approval because it is outside the brief.
- I made no decisions about Michael, money or messages.

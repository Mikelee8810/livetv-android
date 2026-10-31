Starting with the audit leads and the EPG files.
## Result

Audit complete. I read all 20 files plus `audit-leads.md` in full and found **86 defects**. `Theme.kt` is clean, and so are `EpgProgressiveMerge.java` and `Program.java`. I did not edit anything. I did not build or run anything either, so every item below comes from reading the code.

Severity: **K** = stream-killing, **D** = degrading, **m** = minor. Line numbers are from the files as read.

### Lead verdicts (items touching my files)
- **#3 (no TIF watchdog), #4 (reconnect runnable survives release), #5 (retry count never reset): refuted.** [LiveTvInputService.java](file:///Users/michael/livetv-git/app/src/main/java/com/livetv/app/tvinput/LiveTvInputService.java) already has a 20s prepare watchdog (L159-172) and `removeCallbacksAndMessages` in `onRelease` and `playStream`. It also resets `mRetryCount` in `onPrepared` (L188) and captures the reconnect URL (L207).
- **#1 and #6 (`Prefs.playlists()` and `groupRenames` throw): refuted for those two.** [Prefs.kt](file:///Users/michael/livetv-git/app/src/main/java/com/livetv/app/data/Prefs.kt) L119-140 now guards them. `epgSources()` is not guarded in anything I read, and I did not read it.
- **#7 (logo sync can hang setup): confirmed.** The worst case is bounded at about 900 × 7s ≈ 105 minutes (H5).
- **#10 (any HTTP 200 body accepted as a logo): confirmed** (H6).
- **#12 (delete-all then reinsert, no re-entry guard): confirmed** (H1, H2).
- **#13 (EPG matched by display name only): confirmed** (H7).
- **#22 (dead code, ignored `serverUrl`): confirmed** (H9).
- **#18 (main-thread blocking in the TIF service): partly confirmed.** The `onTune` query is confirmed (V3). `ensureStarted()` in `onCreate` is unverified (V4).

### Defect register

**CdnLiveEpgSource.java (6)**
- **C1 K** L43. `body().string()` is unbounded, and the proxy lives in this process. A large or hostile body means an OOM that kills the stream. Fix: reject when `contentLength` is over 8 MB, and read through a size-capped source.
- **C2 m** L37-38. A new `OkHttpClient` per call and no `callTimeout`, so a slow-drip body never times out. Fix: a shared client with `callTimeout(30s)`.
- **C3 D** L187-194. `parseUtc` accepts only `Z` or no offset. A timestamp like `+00:00` or `.000+00:00` throws inside `LocalDateTime.parse`, returns null, and the event is dropped silently. Fix: fall back to `OffsetDateTime.parse`, and count and log the drops.
- **C4 m** L29 and L176. `NOISE` strips uk/ca/au/canada and similar. "Sky Sports UK" and "Sky Sports" collide and both get removed as ambiguous. Fix: don't strip region tokens.
- **C5 m** L163. NPE if `tvgId` or `name` is null (unverified, since `Channel` is out of scope). `EpgRepository` L100-110 null-checks them, which suggests they can be null.
- **C6 m** L114-121. An event with an empty title and empty teams gets the title " vs ". Bad events vanish with no log.

**EpgProgressiveMerge.java (0).** The cost it triggers is in EpgSnapshot S1.

**EpgRepository.java (8)**
- **E1 D** L68-81 and L214-221. There is no in-flight guard, and the temp file name is shared. Overlapping refreshes (the lead #16 amplification) delete and overwrite each other's `.download` file and can promote a truncated cache. Fix: an `AtomicBoolean` guard plus a unique temp name per run.
- **E2 K** L77, L125-140. Only `Exception` is caught.
  - An `OutOfMemoryError` in the `epg-refresh` thread is uncaught and kills the process, proxy included.
  - An `Error` in a worker becomes an `ExecutionException`. L137 then `break`s and `shutdownNow()` drops all remaining sources.
  - Fix: catch `Throwable` per source, `continue`, and bound the data.
- **E3 D** L118-166. A single worker runs sources serially with no overall deadline. Titan alone makes dozens of calls (see T1). Fix: a 3-minute deadline per source via `future.get(timeout)`, and make the interrupt flag effective by using OkHttp `callTimeout`.
- **E4 D** L320, L364. Gzip is detected by the URL suffix. A `.gz?token=` URL, or a server that sends gzip anyway, makes the SAX parser fail. The decompressed size is uncapped (the 64 MB cap is compressed only). Fix: sniff the `1f 8b` magic bytes and wrap the decompressed stream in a counting cap.
- **E5 D** L211-213. A truncated cache with a fresh mtime throws with no fallback to download. Every refresh fails for up to 6 hours. Fix: on parse failure, delete the cache and fall through to download.
- **E6 D** L97-116 together with XmlTvParser L76. The channel filter runs on the XMLTV `channel` id before display-name matching. The `EpgSnapshot` display-name fallback is therefore dead for XMLTV unless the id equals the channel name. Fix: build a canonical-name set and let the parser accept programmes whose `<channel>` display-name canonicalises into it.
- **E7 m** L77-79. A failure in `load()` is only logged and never reaches the callback. The "Update EPG now" dialog never appears. Fix: invoke the callback with a failure report.
- **E8 m** L344-361. `promote` is not atomic, and a leftover `.backup` is never restored. Fix: restore the backup at startup when the cache is missing.

**EpgSnapshot.java (5)**
- **S1 D** L89-93 and L106-113. `overlapsAny` is a linear scan per candidate, so merging is O(n²) per channel. `snapshot()` re-merges every source after each source completes. Fix: keep lists sorted and check only the neighbours with a binary search.
- **S2 D** L229. The tvgId match is case-sensitive, but the repository filter admits lowercased ids. A programme such as `espn.us` against tvgId `ESPN.us` is parsed and then dropped. Fix: index and look up ids lowercased.
- **S3 D** L20, L238. `NOISE` strips east/west/eastern/western/feed. "HBO West" can map to the East schedule, and the programme times come out 3 hours off. Fix: don't strip coast tokens.
- **S4 D** L252-258. Two non-YouTube channels with the same tvgId (duplicate playlists) are both removed as ambiguous, so neither gets EPG. Fix: prefer the first channel, or the channel from the higher-priority playlist.
- **S5 m** L74-99. Merging per programme splices fragments from different sources into inconsistent schedules. Fix: choose per channel, from the first source that covers "now".

**Program.java (0).** The constructor throws `IllegalArgumentException` for end ≤ start, and the callers handle it. The string fields can be null, and the UI uses null-safe checks.

**TitanTvSource.java (8)**
- **T1 D** L57-81. Every refresh creates a new TitanTV guest user and lineup. For the custom list it then does one serial POST per station. Each POST can take up to 28s, and one failure aborts the whole source. There is also a rate-limit or ban risk on an undocumented API. Fix: cache `userId` and `lineupId` in prefs, wrap each POST in try/catch, and add an overall deadline.
- **T2 K** L233-253. `body().string()` is unbounded, and the schedule JSON for about 900 channels becomes a `JSONObject` tree that can be about 10× its size in heap. That is an OOM risk in the proxy process. Fix: cap bodies at 8 MB and parse with `android.util.JsonReader`.
- **T3 D** L98-131. `getJSONObject` and `getInt("channelIndex")` are unguarded, so one malformed station kills the whole source. Fix: use `opt*` and skip bad entries.
- **T4 D** L141-172. `catch (Exception) {}` swallows every programme parse failure. If TitanTV changes its time format, the source reports success with 0 programmes. Fix: count failures, log the first, and throw above 50%.
- **T5 D** L122-125. Two app channels that pick the same Titan `channelIndex` overwrite each other in HashMap order. One of them silently gets no programmes. Fix: map each index to a list of channels.
- **T6 D** L158. `LocalDateTime…atZone(EASTERN)` resolves the repeated 1 AM hour to the earlier offset. **DST ends 2026-11-01, tomorrow.** Programmes in the second 1 AM hour land an hour early and overlap. Fix: parse with the offset the API supplies, or disambiguate with `ZonedDateTime.ofLocal(..., preferredOffset)`.
- **T7 m** L41, L219. The east/west noise stripping is the same bug as S3.
- **T8 m** L77, L305, L314. `ExifInterface` constants are used as the string literals "A" and "S", so a dependency is pulled in for two letters. A new `OkHttpClient` is also created per call. Fix: use literals and a shared client.

**XmlTvParser.java (4)**
- **X1 D** L38 and L167. The pattern `yyyyMMddHHmmss Z` requires a space and a `+HHMM` offset. `DateTimeParseException` is not an `IllegalArgumentException`, so it escapes `addProgram`, aborts the SAX parse, and kills the whole source. The documented offset-less form `20261031120000` and the no-space form also fail. Fix: tolerant parsing (optional space, offset or Z, default UTC) and catch `DateTimeException`.
- **X2 m** L159-161. A programme with no `stop` is dropped. XMLTV allows that. Fix: derive the stop from the next programme's start.
- **X3 m** L138-152. For multi-language `<title>`, `<desc>` and `<category>`, the last one wins. Fix: keep the first, or prefer the first language.
- **X4 m** L32 and L116-119. The SAX parser is unhardened (DOCTYPE and entity expansion on user-supplied URLs), and element text is unbounded. Fix: cap each `StringBuilder` at about 8 KB, and test on-device whether DOCTYPE can be rejected.

**ChannelSyncHelper.java (10)**
- **H1 D** L57-82. It deletes programs, deletes all channels, then does a non-transactional `bulkInsert`. A kill mid-sync leaves the system guide empty, and every sync regenerates channel `_id`s. Fix: upsert by a stable `internal_provider_id` and delete only stale rows, using `applyBatch`.
- **H2 D** L48-55. There is no re-entry guard. Two overlapping syncs interleave destructively. Fix: a static lock or `AtomicBoolean`.
- **H3 D** L59-65. An empty catalog still deletes everything, then reports "Synced 0 channels" as success. Fix: abort before the delete if the list is empty.
- **H4 D** L66 together with [LiveTvSetupActivity.java](file:///Users/michael/livetv-git/app/src/main/java/com/livetv/app/tvinput/LiveTvSetupActivity.java) L19. Setup is the only caller of `syncChannels` and passes no EPG. L62 deletes all existing programs and `epg == null` inserts none, so running setup wipes guide data. Fix: pass `Repo.epg`, or skip the program delete when `epg == null`.
- **H5 D** L67 and L319-385. Logo sync gates `onComplete`. 8 threads with sequential `future.get(7s)` give a worst case of about 105 minutes. Blocking socket reads ignore `cancel(true)`. Fix: call `onComplete` first, run logos in the background with an overall deadline (about 60s), and use OkHttp `callTimeout`.
- **H6 D** L399-426. `downloadLogo` takes any HTTP 200 body, with no content-type or size cap, and writes it into the provider. Fix: require `image/*` and cap at 512 KB.
- **H7 D** L113-149. `byName` is keyed by display name, so duplicate names overwrite each other. Several rows can get the same programmes, or the wrong ones. Fix: key by channel id, stored as `internal_provider_id`.
- **H8 m** L92-107. Channel rows omit `internal_provider_id`, and `display_number` is a positional index that shifts when the catalog is reordered.
- **H9 m** L77, L84-86, L200-317, L428. Only `Exception` is caught, so an `Error` leaves the callback uncalled. About 120 lines of dead network parsers remain, and the `serverUrl` overload ignores its argument. Fix: catch `Throwable` and delete the dead code.
- **H10 m** L151-185. There are N binder deletes for programs, and removing the channels already cascades to them. Fix: delete this method.

**LiveTvInputService.java (5)**
- **V1 K** L231-241. `releasePlayer` calls `stop()` first. In the Preparing or Error state `stop()` throws `IllegalStateException`, the empty catch swallows it, and `reset()` and `release()` are skipped. The `MediaPlayer` and its hardware decoder leak on every zap during prepare and on every error-triggered reconnect. After a few leaks the decoders run out. Fix: call `release()` directly (or `reset()` then `release()`) in separate try/finally blocks, and log failures.
- **V2 K** L141-153. There is no `OnCompletionListener` or `OnInfoListener`.
  - If the proxy closes the stream at EOF, the frame freezes and nothing reconnects.
  - A stall after prepare is never detected, because the watchdog is cancelled at L181-184.
  - Fix: `onCompletion` calls `handleAutoReconnect`. On `MEDIA_INFO_BUFFERING_START`, call `notifyVideoUnavailable(3)` and arm a 15s watchdog. On `BUFFERING_END`, cancel the watchdog and call `notifyVideoAvailable`.
- **V3 D** L82-125. `onTune` runs a `ContentResolver.query` and JSON parse on the main thread for every zap. That is jank, and it can ANR when TvProvider is slow. Fix: resolve on a `HandlerThread` and then post back.
- **V4 m** L21-27. `OnDeviceStreamServer.ensureStarted()` runs in `onCreate` on the main thread. Its cost is unverified because I did not read `OnDeviceStreamServer`.
- **V5 m** L201-218. The retry count is capped at 5 with a fixed 2s delay and no backoff. After 5 failures the session is dead until the next tune. The `autoRetry` pref is ignored here (it is used only at `PlayerEngine.kt` L597). Fix: exponential backoff, and honour the pref.

**LiveTvSetupActivity.java (3)**
- **U1 D** L15-34. A static "Please wait" screen with no progress or timeout, blocked on the logo sync (H5). If the user backs out, the sync thread continues.
- **U2 m** L19. There is no re-entry guard, so a configuration change re-runs `onCreate` and starts a second destructive sync. The thread also keeps the Activity alive for the whole sync. Fix: `configChanges` in the manifest, and a static guard.
- **U3 m** L13, L29. A hard-coded fallback `inputId` is used. A null `e.getMessage()` shows "Sync failed: null". An `Error` never calls the callback, so the screen hangs forever.

**ChannelMenu.kt (1)**
- **M1 m** L53 and L61. The "Channel information" dialog prints the full playback URL on screen. For user playlists that can include credentials or tokens. Fix: mask the query string and userinfo.

**Dialogs.kt (3)**
- **Dg1 D** L23-49 and L52-61. Dialogs have no `isFinishing` or `isDestroyed` guard and are never dismissed on destroy. Async callers (`Repo.reload`, `Repo.addPlaylist`, `refreshEpg`) hit `BadTokenException` or leak the window. Fix: guard `show()`, and track and dismiss open dialogs in `onDestroy`.
- **Dg2 m** L95-103. `message()` has no scroll. Long text such as stream health or the URL can push the OK row off screen. Fix: wrap the text in a `ScrollView` capped at 70% of screen height.
- **Dg3 m** L63-82. The `input()` IME is never hidden on dismiss, and `password = true` is never used by any caller.

**GuideView.kt (6)**
- **G1 D** L27. The `rows` setter keeps the selection by index and does not re-clamp `scrollRow`. After a filter, hide or reload, selection jumps to another channel. A shorter list can leave `scrollRow` past the end, so the guide draws blank. Fix: remember the selected key, reselect it, and call `scrollTo(selRow, false)`.
- **G2 D** L113-291. `onDraw` allocates `RectF` and `Path` and formats dates every frame, and `TextUtils_ellipsize` is O(n²) `measureText`. With a 140ms scroll animation on weak TV SoCs this janks. Fix: use `TextUtils.ellipsize` or a binary search, cache per cell, and reuse the `RectF`.
- **G3 m** L86-104. `cellsFor` bakes `from` and `to` from the time of the first fill and never re-keys on time. If the EPG refresh stops, cells run out after about 30h. `viewStart` also never advances while the guide stays open.
- **G4 m** L64 and L108. `floor30` has a dead `- 0` offset expression, and both it and `fill` align to UTC boundaries. Zones with :45 offsets (Nepal, Chatham) get labels off the programme edges.
- **G5 m** L343-368. Left from the live cell goes to the channel column, so the past cells drawn for the previous 6 hours cannot be reached with the D-pad. The `minStart` branch is mostly dead.
- **G6 m** L404-410. Long OK always opens the menu. `prefs.guideLongOk` (Favorite or quick channels) is never read. A grep shows it is only referenced in `SettingsDrawer` and `Prefs`.

**ListPanel.kt (2)**
- **L1 D** L53-56. `submit()` with `focusIndex = -1` calls `notifyDataSetChanged` and loses focus. This hits `PlayerOverlay.updateQuickList` (see P6). Fix: preserve `focusedIndex()` across `submit`.
- **L2 m** L120. `|| true` makes the condition meaningless. Rows with no action (status rows, "Copy / View URL") take focus and do nothing.

**Logos.kt (3)**
- **Lg1 m** L24. `failed` is permanent for the life of the process. One network blip at boot blanks that logo until restart. Fix: add a TTL, or retry after 5 minutes.
- **Lg2 m** L20-22. `pending` dedupes by URL, so only the first requester's `onReady` ever fires. If `PlayerOverlay` asks second, its logo never appears until some other redraw.
- **Lg3 m** L22. The `onReady` lambda captures the `GuideView` or `Activity` inside a Glide target, which is a leak risk if the view dies before the load completes.

**PlayerOverlay.kt (8)**
- **P1 D** L385-395 and L402-404. `hideBottomBar`'s `onAnimationEnd` calls `requestFocus()` on the overlay 160ms later. `showQuickList` calls `hideBottomBar` first, so focus is stolen from the quick list and the user can't navigate it when the info bar was open. Fix: don't request focus if `isQuickListVisible`.
- **P2 m** L370-378. `showBottomBar` during the fade-out sees `isBottomBarVisible == true` and skips re-showing. The pending hide then completes and the bar vanishes. Fix: cancel the animation, set alpha to 1 and clear the listener on every show.
- **P3 D** L620-650 and L691-704. There is no `repeatCount` check, so holding the key repeats the action.
  - Holding OK repeatedly toggles pause or the bar.
  - Holding up/down calls `onSwitchChannel` on every repeat, firing a burst of stream starts. That can stall the proxy or the TIF.
  - Fix: ignore `repeatCount > 0` for OK and play/pause, and debounce zaps by about 300ms. I did not read `onSwitchChannel`, so a debounce may already exist.
- **P4 D** L648-663 with SettingsDrawer L468-481. The option strings don't match what the overlay checks.
  - Left offers "Channel list", "Rewind 10s" and "Show quick EPG". The overlay handles only "TV Guide" and "Program info".
  - Right offers "Program info", "Fast forward 10s" and "Show channel list". The overlay handles "TV Guide" and "Channel list".
  - So "Show channel list" on Right opens the info bar, and Rewind and Fast-forward never happen.
  - `playerOk` has no settings row at all.
  - Fix: one shared constant set, and handle every option.
- **P5 m** L240-251. A plain `GradientDrawable` as `progressDrawable` cannot draw progress. The bar is a static track. Fix: a `LayerDrawable` with a `ClipDrawable`.
- **P6 m** L437-440. A quick-list click calls `updateQuickList()`, which does `submit(rows)` and loses focus (L1). Fix: `submit(rows, idx)`.
- **P7 m** L69, L554-570. `mainHandler` runnables and `animate()` listeners are never removed on detach. Fix: remove them in `onDetachedFromWindow`.
- **P8 m** L696. If the current channel isn't in `visibleItems()`, `next` is 0 whatever the direction. The info bar also never refreshes while it stays up when `playerInfoSeconds == 0`.

**ProgramDetailsDialog.kt (3)**
- **R1 m** L63-77. "Set reminder" is offered for live and past programmes. The alarm is in the past and the dialog still claims "alerted N minutes before". Fix: hide the row when `start - lead <= now`.
- **R2 m** L13-16. `catchUpHandler` and `liveHandler` are static lambdas capturing `MainActivity`. They leak the Activity after destroy. Fix: clear them in `onDestroy`.
- **R3 m** L56. The message hard-codes "DaddyLive windows are short", which is wrong for other playlists.

**SearchPanel.kt (2)**
- **Sr1 D** L58-68 and L111-119. `Repo.search` runs synchronously on the main thread on every keystroke, and again on the IME action. With ~900 channels and ~36k programmes this can jank per character. Fix: debounce 250ms, search on a background executor, drop stale results, and clean up on detach.
- **Sr2 m** L21. The `prefs` parameter is unused, and the panel never records `searchTerms`, so "Clear search history" has nothing to clear.

**SettingsDrawer.kt (9)**
- **Se1 D** L81-86. `renderCurrent()` does `submit(rows, 0)` after every toggle, so focus jumps to the top row on every change. Going back also lands on row 0. Fix: store the focused index per `Screen` and restore it.
- **Se2 D** L571. The version is hard-coded as "Version 4.3.0", but the code is 4.3.2. You can't confirm which build is running. Fix: use `PackageInfo.versionName`.
- **Se3 m** L180-185, L268-273. "Update all playlists" and "Update EPG now" have no in-flight guard or progress. Repeated taps stack reloads and refreshes (E1), and the callback may fire into a dead Activity (Dg1).
- **Se4 m** L286-308. Adding an EPG source does no URL validation and triggers no refresh. "Copy / View URL" neither copies nor dismisses. Removed built-in sources can't be reset.
- **Se5 m** L95-97, L177-207, L492-503. `pinFor` "playlists" is never enforced in this file. A PIN is set with no confirmation entry, and a "0000" default is accepted.
- **Se6 m** L236-246, L432, L566. Dead settings:
  - `epgKeepDays` and `epgDescriptions` are referenced nowhere outside Prefs and this file.
  - `autoRetry` is used only by `PlayerEngine`, not the TIF.
  - `remindAutoSwitch` is read at `Reminders.kt` L83, but lead #21 says the receiver never uses it. I did not verify that.
  - Fix: wire the settings up or remove the rows.
- **Se7 m** L362-368. Changing the sort mode runs a full network `Repo.reload()`. Fix: re-sort locally.
- **Se8 m** L341, L349, L357. `Pal.apply` and `Fonts.scale` change globals, but already-built drawables (the drawer's own `setColor(Pal.panel)`, the PlayerOverlay and Dialogs cards) keep their old colours until recreate.
- **Se9 m** L95 and L164. `prefs.playlists()` is now guarded in `Prefs.kt`. A malformed `epgSources()` (L291, L302) is still unverified.

**Theme.kt (0).**

### Per-file verdicts

| File | Verdict |
|---|---|
| CdnLiveEpgSource.java | 6 defects |
| EpgProgressiveMerge.java | CLEAN |
| EpgRepository.java | 8 defects |
| EpgSnapshot.java | 5 defects |
| Program.java | CLEAN |
| TitanTvSource.java | 8 defects |
| XmlTvParser.java | 4 defects |
| ChannelSyncHelper.java | 10 defects |
| LiveTvInputService.java | 5 defects |
| LiveTvSetupActivity.java | 3 defects |
| ChannelMenu.kt | 1 defect |
| Dialogs.kt | 3 defects |
| GuideView.kt | 6 defects |
| ListPanel.kt | 2 defects |
| Logos.kt | 3 defects |
| PlayerOverlay.kt | 8 defects |
| ProgramDetailsDialog.kt | 3 defects |
| SearchPanel.kt | 2 defects |
| SettingsDrawer.kt | 9 defects |
| Theme.kt | CLEAN |

**Total: 86 defects** (5 stream-killing: V1, V2, C1, T2, E2). All 20 files plus `audit-leads.md` were read in full.

**Fix priority:**
1. V1 and V2: MediaPlayer leak and no reconnect on EOF or stall.
2. C1, T2 and E2: unbounded reads and OOM that kill the proxy.
3. X1: one bad timestamp kills a whole EPG source.
4. T6: DST ends tomorrow.
5. H1 to H5: wedge-prone channel sync.

## Files read or changed

**Read in full (21):**
- Under `app/src/main/java/com/livetv/app/epg/`:
  - `CdnLiveEpgSource.java`
  - `EpgProgressiveMerge.java`
  - `EpgRepository.java`
  - `EpgSnapshot.java`
  - `Program.java`
  - `TitanTvSource.java`
  - `XmlTvParser.java`
- Under `app/src/main/java/com/livetv/app/tvinput/`:
  - `ChannelSyncHelper.java`
  - `LiveTvInputService.java`
  - `LiveTvSetupActivity.java`
- Under `app/src/main/java/com/livetv/app/ui/`:
  - `ChannelMenu.kt`
  - `Dialogs.kt`
  - `GuideView.kt`
  - `ListPanel.kt`
  - `Logos.kt`
  - `PlayerOverlay.kt`
  - `ProgramDetailsDialog.kt`
  - `SearchPanel.kt`
  - `SettingsDrawer.kt`
  - `Theme.kt`
- `audit-leads.md`.

**Read in part:** `Prefs.kt` L100-140, to verify leads #1 and #6.

**Changed:** nothing.

I did not write a plan artifact. `/plan` was invoked, but this brief is read-only and has no changes to approve.

## Checks run

These were the only commands I ran.
- `grep -rn` for `scheduleChannelId|isYoutubeVariant` in `Channel.java`: confirmed `scheduleChannelId` strips the "-youtube" suffix.
- `grep -rn` for `guideLongOk|playerLeft`: confirmed `guideLongOk` is not read by the guide.
- `grep -rn` for `syncChannels(|48913|usesCleartextTraffic|networkSecurityConfig|ensureStarted`:
  - `LiveTvSetupActivity` is the only caller of `syncChannels`, and it passes no EPG.
  - Cleartext traffic is allowed in the manifest.
  - Port 48913 is fixed in the server at `OnDeviceStreamServer.java` L150.
- `grep -rn` for `epgKeepDays|epgDescriptions|autoRetry|playerOk|remindAutoSwitch`: confirmed the dead settings in Se6.
- `find … -name titan_custom_channels.json -size +256k`: returned nothing, so the asset is under the 256 KB cap.
- Failed commands: `rg` is not installed (exit 127), and an unquoted `--include=*.kt` glob failed in zsh. I reran those with plain `grep`.
- No build, tests or git.

## Risks or unknowns

- **Tree vs v4.3.2:** I can't run git, so I can't confirm this working tree is v4.3.2. `LiveTvInputService` already contains fixes beyond the leads, while the Settings screen says 4.3.0.
- **Source origin:** The sources carry JADX comments and decompiler artefacts. They may be decompiled rather than original, so a rebuild from them could differ from the shipped build.
- **Not audited:** `Repo`, `MainActivity`, `PlayerEngine`, `OnDeviceStreamServer`, `Channel`, `ChannelCatalog`, `Prefs` (mostly) and `Reminders` were outside my file list.
  - The costs of `Repo.search`, `Repo.programs` and `Repo.current`, and any debounce in `onSwitchChannel`, are unverified.
  - Lead items outside my files (#2, #8, #9, #11, #14-21, #23) were not touched.
- **TitanTV span:** The `/6` span in the schedule URL (L85) may mean 6 hours. If so, Titan-only channels show "No information" beyond +6h. I can't verify this from the code.
- **`notifyContentAllowed()`:** `LiveTvInputService` never calls it. With Google TV parental controls on, video may stay blocked. Unverified; it needs an on-device test.
- **Aspect cycle:** `cycleAspectRatio` (P-file L517) only sets `prefs.aspect` and the badge text. Whether `PlayerEngine` applies it is unverified.
- **XML hardening:** Whether Android's SAX parser can reject a DOCTYPE (X4) needs an on-device test.
- **MediaPlayer vs ExoPlayer:** The TIF uses `MediaPlayer` with HLS or TS from the proxy, while the main app uses Exo. HLS behaviour on `MediaPlayer` is weaker, so V2 is likely to bite on a real TV.
- **Severity calls:** Severities are my judgement. E1, E3 and the OOM items depend on data sizes I didn't measure.

## Ask Miguel

- Nothing in this brief needed a decision about Michael, money or messaging.
- **Scope:** This pass was audit only. Do you want a fix pass? That is new scope, and I'd start with the five stream-killing items, X1, T6 and H1-H5.
- **Direction call for Michael:** Keep or drop the TitanTV guest-account scraping (T1)? It creates a new guest user and lineup per refresh against an undocumented API. Fixing the rate-limit and ban risk means caching the identity or changing the source.

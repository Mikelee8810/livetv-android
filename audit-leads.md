# Static bug hunt - full ranked list (subagent, 2026-10-30)
Files: LiveTvInputService, ChannelSyncHelper, Updater, Repo, Prefs, Reminders, StreamEndpoints (clean), LiveCatalog, ChannelCatalog.

1. Prefs.playlists() throws on malformed entry; Repo.reload calls it outside try -> reload thread dies silently, guide empty until data wipe. (Prefs.kt:125-131, Repo.kt:49)
2. Repo EPG state races: catalogEpg non-volatile; m3uEpg plain HashMap mutated on reload/addPlaylist threads, iterated on main in refreshEpg -> ConcurrentModificationException crash ~15s after start. (Repo.kt:36-37,54,87,161)
3. LiveTvInputService (TIF) playback has NO timeout/watchdog: proxy stall = VIDEO_UNAVAILABLE_BUFFERING forever. (128-186)
4. TIF reconnect runnable survives onRelease (no removeCallbacks anywhere) -> zombie MediaPlayer on dead session; also fires into a new tune (reads mutable mStreamUrl). (53-55, 169-192)
5. TIF mRetryCount reset only in onTune, never onPrepared -> 5 lifetime errors permanently disable auto-reconnect. (82, 156-181)
6. Prefs.epgSources() (Prefs.kt:134) unguarded, called at top of Repo.refreshEpg on main thread -> crash ~15s after startup if malformed. Prefs.groupRenames getter (117-119) same class, crashes during guide render via Repo.groupTitle.
7. ChannelSyncHelper logo sync: ~900 logos, 8-thread pool, sequential future.get(7s) awaits; blocking socket reads can't be interrupted -> worst case ~20+ min before onComplete; setup appears hung. Also downloads any HTTP-200 body as a "logo" (no type/size check). (327-437)
8. ChannelCatalog EXTINF parser: selector byte defaults to "assign to group" for EVERY unrecognized attribute; all 509 bundled lines have tvg-name (unrecognized) written into group first; correct only because group-title is last on every line. Any reorder scrambles groups. (47-92)
9. Updater: progress.dismiss() before isFinishing/isDestroyed check; mid-download progress posts have no liveness check -> UI-thread crash if activity dies. (161-178)
10. Updater: no in-flight guard; auto + manual checks race, two downloads write same livetv-<tag>.apk concurrently -> corrupt APK. (99-126)
11. Updater: download success = file.length() > 0; read/total tracked but never compared -> truncated APK passes, installer parse-error dead end. (140-178)
12. ChannelSyncHelper sync: delete-all-then-reinsert, no transaction, no reentry guard -> kill mid-sync leaves system guide with zero channels; overlapping syncs interleave destructively. (56-82)
13. ChannelSyncHelper syncPrograms matches EPG to channels by display_name only; duplicate names overwrite -> wrong guide data on variants. (121-148)
14. Repo per-playlist try/catch -> emptyList on transient fetch error; keep-old guard only for whole-reload zero -> one playlist's hiccup silently deletes its channels from the guide. (51-59)
15. LiveCatalog cache write non-atomic (truncate in place); kill mid-write leaves truncated TSV with FRESH mtime -> treated valid 12h, channels silently missing. (105-109, 157-166)
16. Repo reload amplification: every reload posts another 15s-delayed refreshEpg with no dedup; live-catalog thread recursively triggers a second full ~900-channel parse + stacked ~25MB EPG refreshes. (62, 70-72)
17. LiveCatalog refresh has no failure backoff: all-bases-down leaves cache stale -> every reload re-runs the full 6-base sweep (minutes of radio/CPU). (102-139)
18. LiveTvInputService main-thread blocking: onCreate synchronously starts the HTTP server; onTune does ContentResolver.query + JSON parse on the main looper per zap. (22-30, 84-125)
19. Repo user-playlist channel identity is positional ("$plId-<index>"): provider reorder remaps favorites/hidden/recents to different channels. Bundled DaddyLive ids are stable, unaffected. (14-16, 97)
20. Reminders: notification id + PendingIntent request code = title.hashCode() -> same-title programs on different channels replace each other; tap can open the wrong channel. (110-130)
21. Reminders auto-switch setting is dead plumbing: schedule() hardcodes autoSwitch=false, receiver never reads it; Settings toggle does nothing. (66-76, Prefs.kt:104)
22. ChannelSyncHelper public sync overload silently ignores its serverUrl arg; ~200 lines of dead network parsers (parseChannelsFromApi, parseM3uFromUrl, isEnglishChannel) have no callers. (84-86, 215-326)
23. Prefs.put silently drops unsupported types (Double/Float): no else branch, write just doesn't happen. Latent. (14-16)

Checked clean: StreamEndpoints.java.

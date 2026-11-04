# Code Audit — Navigation + Rest (hidden, do not ship)

Scope/method: read every line of `ui/GuideView.kt`, `ui/ListPanel.kt`, `ui/SearchPanel.kt`,
`ui/SettingsDrawer.kt`, `ui/PlayerOverlay.kt`, `ui/ChannelMenu.kt`, `ui/Dialogs.kt`,
`ui/ProgramDetailsDialog.kt`, `ui/Theme.kt`, `ui/Logos.kt`, and the focus/key parts of
`MainActivity.kt`. Brief pass over `data/*.kt`, `epg/*.java`, `catalog/*.java`, `tvinput/*.java`,
`Channel*.java`, `LiveCatalog.java`, `AndroidManifest.xml`, gradle files. No source edited,
nothing built or run — static reading only. Line numbers are `cat -n` numbers of the current tree.

Severity: **HIGH** = focus lost/trapped, wrong action, or data loss in normal use.
**MED** = dead key/setting, wrong initial focus, focus race. **LOW** = polish/perf/edge case.

Prior registers `AUDIT-REGISTER-A.md` (OnDeviceStreamServer), `AUDIT-REGISTER-B1.md`
(PlayerEngine) and `AUDIT-REGISTER-B2.md` (EPG/TV-input/catalog) already cover those files;
this register does not re-list their items. Many in-code `Audit …` comments mark already-fixed
defects — those were verified present in the code and are **not** re-reported.

---

## A. NAVIGATION — HIGH

### N1. Search: "UP from first result returns to the input" is dead code — the key is never delivered
- `ui/ListPanel.kt:92-93` — RowView's key listener only forwards three codes to `onEdge`:
  > `e.action == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_BACK) && onEdge(code)`
- `ui/SearchPanel.kt:106-109` waits for a code that can never arrive:
  > `if (code == KeyEvent.KEYCODE_DPAD_UP && resultsList.focusedIndex() == 0) { input.requestFocus(); true }`
- Effect: the intended UP-to-input path never fires. Whether UP at result 0 reaches the EditText
  depends entirely on unconfigured platform focus search across a RecyclerView boundary.
- Fix: add `KEYCODE_DPAD_UP`/`KEYCODE_DPAD_DOWN` to the forwarded set in ListPanel (callers that
  don't care return false), or handle UP in a `ListPanel.onKeyDown` override.

### N2. EPG refresh callback fires once per source — Settings "Update EPG now" stacks dialogs and drops the in-flight guard early
- `epg/EpgRepository.java:236` — inside the per-source loop: `progress.onProgress(result);`
  (also `:242` for the empty-sources case). `doRefresh` wires that progress callback straight to
  the caller's `onLoaded`.
- `data/Repo.kt:229-233` — Repo's callback runs on **every** invocation:
  > `epg = snap` … `epgRunning.set(false)` … `done?.invoke(prefs.epgLastStatus); fire()`
- `ui/SettingsDrawer.kt` ("Update EPG now") passes `done = { updatingEpg = false; Dialogs.message(…); renderCurrent() }`.
- Effect: with N sources the user gets up to N stacked "EPG Update" message dialogs (each steals
  focus, each must be dismissed), `updatingEpg`/`epgRunning` are cleared after the *first* source
  so a second refresh can start mid-flight, and `prefs.epgLastUpdate` is stamped repeatedly.
- Fix: distinguish progress from completion — Repo should only invoke `done`/clear guards on a
  terminal callback; EpgRepository needs an explicit final `onComplete`.

### N3. Failed EPG load overwrites a good guide with an empty snapshot
- `epg/EpgRepository.java` `doRefresh` catch block (audit E7 fix) builds
  `EpgSnapshot.map(safeChannels, Collections.emptyList(), …)` and posts it as a normal result.
- `data/Repo.kt:229` unconditionally commits it: `epg = snap`, and `:230` stamps
  `prefs.epgLastUpdate = System.currentTimeMillis()`.
- Effect: one total network failure wipes all programme data from the UI **and** suppresses the
  next refresh for 30 min (freshness check at `Repo.kt:224-226`). The hang fix traded a stuck
  dialog for silent data loss.
- Fix: mark the failure outcome; Repo keeps the previous `epg` and does not stamp
  `epgLastUpdate` when `report.parsedPrograms == 0 && failedSources == declaredSources`.

### N4. Dead settings that the user can toggle but nothing reads
Verified by repo-wide grep — outside `Prefs.kt` and the SettingsDrawer rows themselves there are
**no readers** of: `prefs.numberZap`, `prefs.seekStepSec`, `prefs.seekLeftRight`,
`prefs.seekUseRwFf`, `prefs.seek*Live`, `prefs.guideHours`, `prefs.playerClock`,
`prefs.sleepMinutes`, `prefs.pinMethod`, `prefs.pinRelock`, `prefs.lockedChannels/lockedGroups`,
`prefs.autoStartBoot`, `prefs.autoStartWake`, `prefs.udpProxy`, `prefs.userAgent` (userAgent *is*
read for playlist fetch/player), `prefs.epgKeepDays/epgDescriptions` (already removed from UI per
Se6, still in Prefs).
Concretely user-visible:
- `ui/SettingsDrawer.kt:181-184` "Confirm exit by double Back" toggles `prefs.confirmExit`, but
  `MainActivity.kt:1029-1037` `confirmAppExit()` never reads it — double-Back is always required.
- `ui/SettingsDrawer.kt:165-172` "Auto start app on boot / on wake up" toggles do nothing:
  `BootReceiver` only re-arms reminders; nothing starts the app (and the manifest has no
  boot-launch component for the app itself).
- `ui/SettingsDrawer.kt:191-195` "UDP proxy" is stored and never applied to any player/OkHttp call.
- Number zap ignores `prefs.numberZap` (`ui/PlayerOverlay.kt:630-632` handles digits
  unconditionally), and seek keys ignore `seekStepSec`: `ui/PlayerOverlay.kt:728,733`
  hardcode `playerEngine.seekRelative(10)` / `seekRelative(-10)`.
- Fix: wire each setting or delete the row (the Se6 precedent: a setting that lies is worse than
  no setting).

### N5. Inert rows are focusable D-pad stops that swallow OK
- `ui/ListPanel.kt:128`:
  > `isFocusable = r.kind == Kind.ITEM && (r.onClick != null || r.checked != null || r.onLong != null || r.sub != null || r.value != null)`
  Any row with a subtitle or value is focusable even with no action. OK on it fires
  `setOnClickListener { r.onClick?.invoke() }` — nothing.
- Live examples: `ui/SettingsDrawer.kt:322` `Row("Latest update status", sub = prefs.epgLastStatus)`
  and `:674` `Row("Live TV for Android TV", sub = "Version " + …)` — both are pure information,
  both take focus, both do nothing on OK.
- Fix: focusable iff `onClick != null || checked != null || onLong != null`; render info rows
  non-focusable like HEADERs.

---

## B. NAVIGATION — MED

### N6. Main menu opens with focus on the *second* item
- `MainActivity.kt:643,646`: `mainMenuDrawer.getChildAt(2)?.requestFocus()`.
  Child order in `setupMainMenuDrawer` is 0 = brand row, 1 = "Search", 2 = "TV Guide".
  The menu therefore opens focused on "TV Guide", skipping "Search" (reachable only by pressing UP).
- Fix: `getChildAt(1)`, or better, keep a reference to the first menu item view.

### N7. `closeSettings()` end-action steals focus unconditionally — including into fullscreen
- `MainActivity.kt:702-710`: the close animation's `withEndAction` always runs
  `guideView.requestFocus()`.
- `enterFullscreen()` calls `removeOverlays()` → `closeSettings()` and then requests focus on
  `fullscreenContainer`. 200 ms later the settings end-action fires and tries to focus the
  guideView inside the now-`GONE` guide layout; where it succeeds, focus sits on a hidden view
  while the player overlay is up (fullscreen keys survive only because `dispatchKeyEvent`
  intercepts them before the focused view).
- Fix: only restore guide focus if `!isFullscreen` and no other overlay is open.

### N8. Guide cursor time never advances — selection rots in place
- `ui/GuideView.kt:53`: `private var selTime = System.currentTimeMillis()` — set at construction,
  changed only by LEFT/RIGHT navigation and `resetToNow()`. The 30 s `tick` only calls
  `invalidate()`; it never re-anchors `selTime`.
- Hours later the "selected" cell is a past programme; once `selTime` falls before the
  `cellsFor` window floor (`floor30(now) - 6h`), `cellAt()` returns null and:
  - LEFT/RIGHT in PROGRAM mode compute `cells.indexOfFirst { selTime in cell }` = `-1`.
    RIGHT then hits `ui/GuideView.kt:418` `if (i in 0 until cells.size - 1)` = false and returns
    `true` having done nothing — a swallowed dead key. LEFT treats it as "no previous cell" and
    jumps to the CHANNEL column, which reads as a random focus jump.
  - `selectedProgram()` returns null, so the info card and OK-action logic silently degrade.
- Fix: in `tick`, if the user hasn't navigated away in time, advance `selTime` with `now`;
  and treat `i == -1` in LEFT/RIGHT as `resetToNow()` instead of swallowing the key.

### N9. MainActivity `dispatchKeyEvent` duplicates/deadens the drawers' own key handlers, with one behaviour difference
- `MainActivity.kt` dispatch (ACTION_DOWN) intercepts RIGHT/LEFT/BACK for the drawers **before**
  the focused view ever sees them. The handlers in `groupsList.onEdge` (categories) and in the
  main-menu items' `setOnKeyListener` (`MainActivity.kt` ~L480-490) are therefore unreachable.
- The paths are not identical: the categories `onEdge` BACK branch first restores
  `guideView.rows = Repo.inGroup(prefs.lastGroup)`; the dispatch BACK branch just calls
  `openMainMenuDrawer()`. Because `groupsList.onFocusRow` live-swaps `guideView.rows` to the
  *hovered* group, BACK-ing from categories to the main menu leaves the guide previewing an
  unselected group behind the menu.
- Fix: delete one of the two layers; if dispatch stays, make its categories-BACK branch restore
  `lastGroup` rows exactly like the `onEdge` branch.

### N10. Settings focus memory is only written on re-render, not on navigation
- `ui/SettingsDrawer.kt:68,88-106`: `focusMemory` is written only inside `renderCurrent()`.
  `showScreen()` (used by `push`/`handleBack`) reads it but never stores the outgoing screen's
  live focus position. Leaving a screen with the D-pad (no toggle/re-render) and coming back
  restores focus to row 0 or a stale remembered row, not where the user was.
- Fix: in `push()`/`handleBack()`, record `listPanel.focusedIndex()` for the outgoing title first.

### N11. `ListPanel.focusRow` delayed fallback can steal focus after the surface has changed
- `ui/ListPanel.kt:64-…`: if the target child isn't laid out, a `postDelayed(…, 80)` runnable
  later calls `delayed.requestFocus()`, or `requestFocus()` on the panel itself.
- Nothing ties that runnable to the `submit()` generation that scheduled it. A dialog dismissed,
  a drawer closed, or a second `submit()` within 80 ms still gets the old focus grab — focus lands
  on a detached/stale row or on the (unhighlighted) panel, leaving no visible focus anywhere.
- Fix: capture a submit generation counter; the delayed runnable no-ops if it changed or if the
  panel is not shown.

### N12. PlayerOverlay: digits are handled before the quick-list check
- `ui/PlayerOverlay.kt:630-636`: the `KEYCODE_0..9` branch runs before
  `if (isQuickListVisible)`. Typing a digit with the quick list open starts a channel zap;
  when it fires, `onSwitchChannel` runs under the still-open quick list, whose rows/selection
  are not refreshed (`updateQuickList()` is not called by the zap path).
- Also, with the quick list open, `onKey` returns `false` for MENU — the key dies entirely
  (no view consumes it), unlike every other fullscreen state.
- Fix: move the quick-list check above the digit branch; refresh or close the list on zap.

### N13. PlayerOverlay action-bar focus entry is always the first button; OK while the info bar is up re-triggers the player-OK mapping
- `ui/PlayerOverlay.kt:663`: DPAD_DOWN into the actions bar always focuses
  `actionsBar.getChildAt(0)` ("Subtitles"), never the last-used action.
- When the bottom bar is visible but the actions bar is *not* focused, DPAD_CENTER falls through
  to the generic `prefs.playerOk` mapping (`:676-…`) — e.g. with the default "Channel list",
  pressing OK on the info bar opens the quick list instead of confirming/dismissing the bar.
  This is a mapping surprise; at minimum document it, preferably treat CENTER as
  "focus actions bar" while the bar is visible.

### N14. PIN dialog: numpad keys incomplete
- `ui/Dialogs.kt:161-168`: the PIN key listener handles `KEYCODE_0..KEYCODE_9` and
  `KEYCODE_DPAD_CENTER, KEYCODE_ENTER` — but not `KEYCODE_NUMPAD_ENTER` (cannot confirm from a
  numpad) nor `KEYCODE_NUMPAD_0..9` (cannot type digits from a numpad). Every other OK path in
  the app (`GuideView`, `PlayerOverlay`) accepts `NUMPAD_ENTER`.
- BACK in the PIN dialog falls through to default Dialog dismiss — silent cancel, no result
  callback. Acceptable, but callers like `checkPinThen` get no signal; noted for consistency.
- Fix: add the numpad ranges alongside the existing ones.

### N15. Message dialog: long text cannot be scrolled with the remote
- `ui/Dialogs.kt:126-139`: the Dg2 fix caps the message in a `ScrollView`, but the ScrollView is
  never made focusable and receives no keys; focus is forced to the single OK row
  (`lp.post { lp.focusRow(0) }`). A message longer than 70% of the screen (EPG status,
  stream-health text, update notes) can be read only in its first screenful.
- Fix: `sv.isFocusable = true` and focus it first (OK reachable by DOWN), or page the text.

### N16. Settings/Search surfaces don't disable the guide behind them
- Categories/main-menu drawers set `guideView.isFocusable = false`
  (`MainActivity.kt:573,632`), but `showSettings()` and `showSearch()` do not, and
  `dispatchKeyEvent` only intercepts BACK for them. If focus is ever lost from the
  drawer/panel (dialog dismiss edge cases, the N11 race), D-pad keys land on the live guide
  behind a full-screen search panel / settings drawer — keys appear to "work on the wrong screen".
- Fix: mirror the drawer behaviour in `showSettings()`/`showSearch()` and restore it on close.

---

## C. NAVIGATION — LOW

### N17. GuideView key repeat is unmanaged
`ui/GuideView.kt` `onKeyDown`: a held DPAD_UP/DOWN fires one row move per repeat event and
restarts the 140 ms `scrollTo` animator each time (`anim?.cancel()` at `:344`), producing
step-lag on long lists; PAGE/CHANNEL keys repeat the same way. PlayerOverlay debounces held
keys (P3 fix) — the guide, the most-held-key surface in the app, has no equivalent.
Fix: coalesce repeats during a running scroll animation, or raise repeat moves to a
velocity-based step.

### N18. GuideView animator not cancelled on detach
`ui/GuideView.kt:79,84`: `onDetachedFromWindow` removes `tick` callbacks but never
`anim?.cancel()`. A mid-scroll detach leaves a `ValueAnimator` running against a detached view
(it self-terminates in 140 ms, so impact is small, but it also retains the view via the update
listener until it ends). Fix: cancel in `onDetachedFromWindow`.

### N19. GuideView swallows RIGHT at the last cell and LEFT at the window floor asymmetrically
`ui/GuideView.kt:408-…`: RIGHT on the final cell returns `true` with no movement and no edge
callback (there is an `onEdgeLeft`/`onOpenCategories` for the left edge but no right-edge
hook); the focus highlight stays on the current cell so the key feels dead. Acceptable for a
grid edge, but inconsistent with LEFT, which changes `focusCol`. Noted, no strong fix required.

### N20. Main menu item key listener is fully dead code
The `setOnKeyListener` in `makeMenuItem` (`MainActivity.kt`, DPAD_RIGHT → close to categories,
BACK → `confirmAppExit()`) can never run: `dispatchKeyEvent` consumes both keys first (see N9).
Dead code that invites future editors to "fix" behaviour in the wrong place. Delete it.

### N21. Initial reload callback can yank focus back to the guide
`MainActivity.kt:152`: the `Repo.reload { … guideView.requestFocus() }` completion runs
whenever the first load finishes. A user who managed to open a drawer in the interim (keys are
live while the guide is empty) has focus pulled back behind the open drawer; the drawer's own
focus state and the activity's focus then disagree. Fix: only `requestFocus()` if no drawer/
panel is open at callback time.

### N22. `Dialogs.choose` with an empty option list, and `Dialogs.menu` with no ITEM rows, produce a focus-less modal
`ui/Dialogs.kt` `choose`: `lp.focusRow(selected.coerceAtLeast(0))` no-ops on an empty list —
the dialog opens with nothing focusable except BACK-dismiss. `menu` focuses
`wrapped.indexOfFirst { it.kind == Kind.ITEM }.coerceAtLeast(0)`, which for an all-HEADER list
points at a non-focusable header (`focusRow` requests focus on a view with
`isFocusable = false` and gives up — no scan for the next focusable row). No current caller
hits either case; guard anyway (`focusRow` should scan forward for a focusable child).

### N23. No remote shortcuts beyond MENU/DPAD/media in guide mode
`MainActivity.dispatchKeyEvent` + `GuideView.onKeyDown` handle no `KEYCODE_SEARCH`,
`KEYCODE_GUIDE`, `KEYCODE_INFO`, colour buttons, or digits in guide mode (digits work only in
fullscreen, via PlayerOverlay). For an Android TV app whose "Remote control" settings screen
implies rich mapping, SEARCH/INFO at minimum are expected by users. Feature gap, not a bug.

### N24. Focus highlight exists but is theme-fragile in the guide channel column
`ui/GuideView.kt` drawRow: channel-column focus is shown only when
`isSel && focusCol == CHANNEL && hasFocus()`. If the guide loses window focus (e.g. a
non-dialog overlay such as the settings drawer, N16), *no* cell anywhere shows a focus ring —
the selection renders identically to a merely-selected row, so on return the user cannot tell
whether the guide or the overlay has the keys. Minor visual ambiguity; consider keeping the
ring on `isSel` regardless of `hasFocus()` when no other surface is visible.

---

## D. REST — non-navigation defects from the brief pass

### R1. EPG cache filename is a 32-bit `String.hashCode()` — sources can collide and serve each other's guide
`epg/EpgRepository.java:504-505`:
> `return new File(directory, Integer.toHexString(source.hashCode()) + …);`
Two different EPG URLs whose URLs hash-collide share one cache file; each refresh parses the
other source's XML as its own. Also negative hashes yield a leading `-` in the filename
(harmless). Fix: SHA-256 (or at least 64-bit) of the URL.

### R2. EPG "parallel" load is a single-thread pool with a per-source 3-minute deadline
`epg/EpgRepository.java`: `Executors.newFixedThreadPool(1, …)` feeds the
`ExecutorCompletionService`, and the consumer polls with a 3-minute timeout *per source*.
Sources load strictly serially; with several slow sources a refresh can run for
sources × 3 min while `Repo.epgRunning` was already cleared by the first progress callback (N2),
so overlapping refreshes pile up behind `EpgRepository.REFRESHING` coalescing — whose
`pendingRefresh` slot silently **drops the earlier queued caller's callback entirely**
(`refresh()`: `pendingRefresh = task` overwrites; the replaced task's `Callback` is never
invoked). Fix: pool ≥ 2–3, one overall deadline, and invoke dropped callbacks with a failure
outcome.

### R3. `Logos.get` registers a new waiter on **every call**, including every guide frame
`ui/Logos.kt:33`: `waiters.getOrPut(url) { mutableListOf() }.add(onReady)` runs unconditionally.
`GuideView.drawRow` calls `Logos.get(context, …) { invalidate() }` on every `onDraw` for every
visible row whose logo hasn't landed — at 30 s tick + scroll invalidations that's dozens-to-
hundreds of distinct `{ invalidate() }` lambdas accumulating per URL until the Glide load
terminates; all of them then fire. Memory churn and a burst of redundant invalidates.
Fix: dedupe — only register a waiter if the caller isn't already waiting (e.g. track per-URL
waiter identity, or have `get` return a sentinel and let views poll the cache on invalidate).

### R4. Repo reload: `m3uEpg.clear()` races readers, and the live-catalog recursion has no backoff at the Repo level
`data/Repo.kt` `reload()`: `m3uEpg.clear()` runs on the reload thread while
`epgSourceList()` iterates `m3uEpg.values` — the map is a `ConcurrentHashMap` (R-fix) so this
is safe from CME, but a refresh started mid-reload can compute an empty/partial source list
and stamp it as a full result. Also every `reload()` spawns the `live-catalog` thread which
calls `reload()` again after a successful refresh — combined with settings-triggered reloads
there is no Repo-level single-flight on `reload` itself beyond the sequence check at commit.
Low-probability, self-healing; noted.

### R5. `Prefs.put` writes sets via `putStringSet` with `it.toString()` on elements
`data/Prefs.kt` `put()`: `is Set<*> -> putStringSet(k, v.map { it.toString() }.toSet())` — fine
for the current String sets, but `Reminders.all()` writes back with `prefs.put(KEY, …toSet())`
while `Prefs.set()` reads with `getStringSet`; a stored set written by any other path (or a
future version) as a plain String would make `set()` throw `ClassCastException` — and unlike
`str/bool/int`, `set()` *is* wrapped (it catches and returns empty), which would silently wipe
favorites/hidden/reminders to empty on read. Acceptable today; flagging the asymmetry.

### R6. XmlTvParser: stop-less last programme is fabricated out to the window end
`epg/XmlTvParser.java:256-263`: at EOF a pending stop-less programme is emitted with
`stop = windowEnd` (now + 24 h). It then counts as the channel's current programme for up to a
day and, via `EpgSnapshot.mergePreferExisting`'s "owns now" lock, can block better data from
later sources. Fix: cap the fabricated stop (e.g. start + 2 h) or drop at EOF.

### R7. XmlTvParser timestamps without seconds are dropped silently
`epg/XmlTvParser.java:64-84`: only `yyyyMMddHHmmss` shapes are tried. Feeds emitting
`yyyyMMddHHmm Z` (valid XMLTV in the wild) throw `IllegalArgumentException`, caught per-programme
in `addProgram` — the programme vanishes with no log or counter. Fix: add the `HHmm` patterns
and count drops.

### R8. Manifest/gradle notes (no defect found in versions themselves)
- `AndroidManifest.xml`: `MainActivity` is `singleTask` + `exported=true` with no intent-filter
  beyond MAIN/LAUNCHER — fine. `LiveTvSetupActivity` is `exported=true` with a MAIN filter,
  so it appears as a launchable activity and can be started by any app; its sync is guarded by
  the `RUNNING` atomic, so impact is a stray sync + toast. Consider `exported=false` (the TV
  framework launches setup via the input service, not via export) — verify against TIF docs
  before changing.
- `usesCleartextTraffic="true"` is required by the current http playlist/EPG ecosystem —
  accepted, but it also covers the updater's GitHub traffic at the manifest level (OkHttp calls
  there are https URLs, so no downgrade in practice).
- `app/build.gradle.kts`: `targetSdk = 34` with `compileSdk = 35`; `SCHEDULE_EXACT_ALARM` is
  requested in the manifest — on Android 14+ this is granted only to alarm-clock/calendar class
  apps by default; `Reminders.schedule()` already falls back to inexact alarms when
  `canScheduleExactAlarms()` is false, so reminders degrade to ±window timing silently.
  Acceptable; worth a settings note.
- Gradle wrapper 8.9 / AGP 8.7.3 / Kotlin 2.0.21 are mutually consistent. `gradle.properties`
  sets `-Xmx4g` for a small app — harmless.

### R9. Checked, no new defect (brief pass)
`data/Updater.kt` (single-flight, .part rename, dismiss-before-guard all present),
`data/Reminders.kt` (purge, lead-window, stable ids present), `catalog/*.java` and
`tvinput/*.java` (beyond AUDIT-REGISTER-B2 items), `Channel.java`/`ChannelCatalog.java`/
`LiveCatalog.java` (beyond registers A/B2), `ui/Theme.kt` (clean), `ui/ChannelMenu.kt`
(URL masking present; menu rows all actionable), `ui/ProgramDetailsDialog.kt` (reminder
lead-time guard present). `player/PlayerEngine.kt` was not re-audited — B1 stands, plus the
seek-preference disconnect logged at N4.

---

## Top defects (summary for the parent)

1. **N2** — EPG progress callback fires per source: stacked "EPG Update" dialogs, guards cleared early.
2. **N3** — A failed EPG refresh overwrites good guide data with an empty snapshot and stamps it fresh.
3. **N1** — Search UP-to-input is dead code: ListPanel never forwards DPAD_UP to `onEdge`.
4. **N4** — A cluster of settings toggles (confirm-exit, auto-start, UDP proxy, number-zap, seek step) change nothing; `confirmAppExit()` never reads `prefs.confirmExit`.
5. **N5** — Info-only rows are focusable dead stops (focusable keyed on `sub`/`value`, not on having an action).
6. **N6/N7** — Main menu opens focused on its second item; settings close-animation can focus the hidden guide over fullscreen.
7. **N8** — Guide `selTime` never advances: after hours the selection is a past programme and RIGHT becomes a swallowed dead key.
8. **R1/R2** — EPG cache keyed by 32-bit `hashCode()`; single-thread "parallel" EPG load with dropped coalesced callbacks.

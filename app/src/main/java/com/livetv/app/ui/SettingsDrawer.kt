package com.livetv.app.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.livetv.app.data.PlaylistDef
import com.livetv.app.data.Prefs
import com.livetv.app.data.Repo

class SettingsDrawer(
    private val activity: Activity,
    private val prefs: Prefs,
    private val healthProvider: (() -> String)? = null,
    private val onClose: () -> Unit
) : LinearLayout(activity) {

    private val titleView = TextView(activity)
    private val listPanel = ListPanel(activity)
    private val navStack = mutableListOf<Screen>()

    data class Screen(val title: String, val rows: () -> List<Row>)

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(Pal.panel)
            cornerRadii = floatArrayOf(
                dp(16f), dp(16f), 0f, 0f,
                0f, 0f, dp(16f), dp(16f)
            )
        }
        setPadding(dpi(24f), dpi(24f), dpi(24f), dpi(20f))

        titleView.apply {
            textSize = 24f * Fonts.scale
            setTextColor(Pal.text)
            typeface = Fonts.medium
            setPadding(dpi(12f), dpi(4f), dpi(12f), dpi(16f))
        }
        addView(titleView)

        listPanel.apply {
            rowHeightDp = 46f
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            onEdge = { code ->
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    handleBack()
                    true
                } else false
            }
        }
        addView(listPanel)

        // Show root screen
        push(Screen("Settings", ::buildRootMenu))
    }

    // Audit Se1: focus position is remembered per screen and kept
    // across in-place re-renders (every toggle used to throw focus
    // back to the top row).
    private val focusMemory = HashMap<String, Int>()
    private var updatingPlaylists = false
    private var updatingEpg = false

    // Record the outgoing screen's live focus before leaving it
    // (audit N10): the memory was only written on re-render, so
    // leaving a screen by D-pad and returning restored a stale row.
    private fun rememberCurrentFocus() {
        val cur = navStack.lastOrNull() ?: return
        val live = listPanel.focusedIndex()
        if (live >= 0) focusMemory[cur.title] = live
    }

    private fun push(screen: Screen) {
        rememberCurrentFocus()
        navStack.add(screen)
        showScreen()
    }

    fun handleBack(): Boolean {
        if (navStack.size > 1) {
            rememberCurrentFocus()
            navStack.removeAt(navStack.size - 1)
            showScreen()
            return true
        } else {
            onClose()
            return true
        }
    }

    private fun showScreen() {
        val cur = navStack.last()
        titleView.text = cur.title
        val rows = cur.rows()
        val focus = focusMemory[cur.title]?.takeIf { it in rows.indices } ?: 0
        listPanel.submit(rows, focus)
    }

    private fun renderCurrent() {
        val cur = navStack.last()
        titleView.text = cur.title
        val rows = cur.rows()
        val live = listPanel.focusedIndex()
        val focus = when {
            live in rows.indices -> live
            focusMemory[cur.title] in rows.indices -> focusMemory[cur.title]!!
            else -> 0
        }
        focusMemory[cur.title] = focus
        listPanel.submit(rows, focus)
    }

    // Audit Se8: theme/accent/font changes rebuild the row views but
    // used to leave the drawer's own chrome in the old palette.
    private fun applyChrome() {
        background = GradientDrawable().apply {
            setColor(Pal.panel)
            cornerRadii = floatArrayOf(
                dp(16f), dp(16f), 0f, 0f,
                0f, 0f, dp(16f), dp(16f)
            )
        }
        titleView.setTextColor(Pal.text)
    }

    // -------------------------------------------------------------
    // Root Settings
    // -------------------------------------------------------------
    private fun buildRootMenu(): List<Row> = listOf(
        Row("General", sub = "Boot, PiP, user-agent, proxy", onClick = {
            push(Screen("General", ::buildGeneralMenu))
        }),
        Row("Playlists", sub = "${prefs.playlists().size} configured", onClick = {
            // Audit Se5: the "playlists" PIN scope existed but was
            // never enforced anywhere.
            if (prefs.pinEnabled && "playlists" in prefs.pinFor) {
                checkPinThen { push(Screen("Playlists", ::buildPlaylistsMenu)) }
            } else {
                push(Screen("Playlists", ::buildPlaylistsMenu))
            }
        }),
        Row("EPG", sub = "Sources, refresh & storage", onClick = {
            push(Screen("EPG", ::buildEpgMenu))
        }),
        Row("Appearance", sub = "Guide layout, themes & fonts", onClick = {
            push(Screen("Appearance", ::buildAppearanceMenu))
        }),
        Row("Playback", sub = "Buffer, decoders, AFR & audio", onClick = {
            push(Screen("Playback", ::buildPlaybackMenu))
        }),
        Row("Remote control", sub = "D-pad & remote button mapping", onClick = {
            push(Screen("Remote control", ::buildRemoteMenu))
        }),
        Row("Parental controls", sub = if (prefs.pinEnabled) "Enabled" else "Off", onClick = {
            checkPinThen {
                push(Screen("Parental controls", ::buildParentalMenu))
            }
        }),
        Row("Other", sub = "Search & reminders", onClick = {
            push(Screen("Other", ::buildOtherMenu))
        })
    )

    // -------------------------------------------------------------
    // 1. General
    // -------------------------------------------------------------
    private fun buildGeneralMenu(): List<Row> = listOf(
        Row("Auto start app on boot", checked = prefs.autoStartBoot, onClick = {
            prefs.autoStartBoot = !prefs.autoStartBoot
            renderCurrent()
        }),
        Row("Auto start app on wake up", sub = "From sleep mode", checked = prefs.autoStartWake, onClick = {
            prefs.autoStartWake = !prefs.autoStartWake
            renderCurrent()
        }),
        Row("Turn on last channel on start", checked = prefs.resumeLast, onClick = {
            prefs.resumeLast = !prefs.resumeLast
            renderCurrent()
        }),
        Row("Picture-in-Picture on Home", sub = "Switch to PiP when Home key pressed", checked = prefs.pipOnHome, onClick = {
            prefs.pipOnHome = !prefs.pipOnHome
            renderCurrent()
        }),
        Row("Confirm exit by double Back", checked = prefs.confirmExit, onClick = {
            prefs.confirmExit = !prefs.confirmExit
            renderCurrent()
        }),
        Row("User-Agent", value = prefs.userAgent.ifBlank { "Default" }, onClick = {
            Dialogs.input(activity, "User-Agent", prefs.userAgent, "Custom HTTP User-Agent string") {
                prefs.userAgent = it
                renderCurrent()
            }
        }),
        Row("UDP proxy (address:port)", value = prefs.udpProxy.ifBlank { "Not set" }, onClick = {
            Dialogs.input(activity, "UDP Proxy", prefs.udpProxy, "e.g. 192.168.1.1:4000") {
                prefs.udpProxy = it
                renderCurrent()
            }
        })
    )

    // -------------------------------------------------------------
    // 2. Playlists
    // -------------------------------------------------------------
    private fun buildPlaylistsMenu(): List<Row> {
        val list = mutableListOf<Row>()
        list.add(Row("CONFIGURED PLAYLISTS", kind = Kind.HEADER))
        for (pl in prefs.playlists()) {
            val count = Repo.channelCount(pl.id)
            list.add(Row(
                pl.name,
                sub = if (pl.enabled) "Active • $count channels" else "Disabled",
                value = if (pl.id == "builtin") "Bundled" else "Custom",
                onClick = {
                    showPlaylistDetails(pl)
                }
            ))
        }

        list.add(Row("ACTIONS", kind = Kind.HEADER))
        list.add(Row("Add playlist", accentTitle = true, onClick = {
            showAddPlaylistDialog()
        }))
        list.add(Row("Update all playlists", onClick = {
            // Audit Se3: no in-flight guard let taps stack reloads.
            if (!updatingPlaylists) {
                updatingPlaylists = true
                Repo.reload {
                    updatingPlaylists = false
                    Dialogs.message(activity, "Playlists Updated", "Reloaded channels from all active playlists.")
                    renderCurrent()
                }
            }
        }))
        return list
    }

    private fun showPlaylistDetails(pl: PlaylistDef) {
        val rows = mutableListOf<Row>()
        rows.add(Row(if (pl.enabled) "Disable playlist" else "Enable playlist", onClick = {
            Repo.togglePlaylist(pl.id)
            renderCurrent()
        }))
        if (pl.id != "builtin") {
            rows.add(Row("Rename playlist", onClick = {
                Dialogs.input(activity, "Rename Playlist", pl.name) {
                    Repo.renamePlaylist(pl.id, it)
                    renderCurrent()
                }
            }))
            rows.add(Row("Delete playlist", accentTitle = true, onClick = {
                Dialogs.confirm(activity, "Delete Playlist", "Are you sure you want to delete '${pl.name}'?") {
                    Repo.removePlaylist(pl.id)
                    renderCurrent()
                }
            }))
        }
        Dialogs.menu(activity, pl.name, rows)
    }

    private fun showAddPlaylistDialog() {
        Dialogs.input(activity, "Playlist Name", "My IPTV") { name ->
            if (name.isBlank()) return@input
            Dialogs.input(activity, "M3U Playlist URL", "", "https://... or http://...") { url ->
                if (url.isBlank()) return@input
                Repo.addPlaylist(name, url) { err ->
                    if (err != null) {
                        Dialogs.message(activity, "Playlist Error", "Failed to add playlist: $err")
                    } else {
                        Dialogs.message(activity, "Success", "Added '$name' successfully!")
                        renderCurrent()
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------
    // 3. EPG
    // -------------------------------------------------------------
    private fun buildEpgMenu(): List<Row> = listOf(
        Row("EPG sources", sub = "${Repo.epgSourceList().size} sources active", onClick = {
            push(Screen("EPG Sources", ::buildEpgSourcesMenu))
        }),
        // Audit Se6: "Past days to keep EPG" and "Store program
        // descriptions" rows were removed — nothing in the app ever
        // read epgKeepDays or epgDescriptions, so both were dead
        // settings that lied to the user.
        Row("UPDATE OPTIONS", kind = Kind.HEADER),
        Row("Update interval", value = if (prefs.epgIntervalHours == 0) "None" else "${prefs.epgIntervalHours} hours", onClick = {
            val opts = listOf("None", "2 hours", "4 hours", "8 hours", "12 hours", "24 hours")
            val vals = listOf(0, 2, 4, 8, 12, 24)
            val cur = vals.indexOf(prefs.epgIntervalHours).coerceAtLeast(0)
            Dialogs.choose(activity, "Update Interval", opts, cur) {
                prefs.epgIntervalHours = vals[it]
                renderCurrent()
            }
        }),
        Row("Update on app start", checked = prefs.epgOnStart, onClick = {
            prefs.epgOnStart = !prefs.epgOnStart
            renderCurrent()
        }),
        Row("Update on playlists change", checked = prefs.epgOnPlaylist, onClick = {
            prefs.epgOnPlaylist = !prefs.epgOnPlaylist
            renderCurrent()
        }),
        Row("ACTIONS", kind = Kind.HEADER),
        Row("Update EPG now", accentTitle = true, onClick = {
            // Audit Se3: in-flight guard, same as playlists.
            if (!updatingEpg) {
                updatingEpg = true
                Repo.refreshEpg(force = true) { status ->
                    updatingEpg = false
                    Dialogs.message(activity, "EPG Update", status)
                    renderCurrent()
                }
            }
        }),
        Row("Clear EPG cache", onClick = {
            Dialogs.confirm(activity, "Clear EPG", "Clear all cached program guide data?") {
                Repo.clearEpg()
                renderCurrent()
            }
        }),
        Row("Latest update status", sub = prefs.epgLastStatus)
    )

    private fun buildEpgSourcesMenu(): List<Row> {
        val list = mutableListOf<Row>()
        list.add(Row("ACTIVE SOURCES", kind = Kind.HEADER))
        for (src in Repo.epgSourceList()) {
            list.add(Row(src, onClick = {
                Dialogs.menu(activity, "EPG Source", listOf(
                    // Audit Se4: this row had no action at all; it now
                    // copies the URL to the clipboard.
                    Row("Copy URL", sub = src, onClick = {
                        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("EPG source", src))
                        Dialogs.message(activity, "EPG Source", "Copied to clipboard:\n$src")
                    }),
                    Row("Remove source", accentTitle = true, onClick = {
                        val current = prefs.epgSources().toMutableList()
                        current.remove(src)
                        prefs.saveEpgSources(current)
                        renderCurrent()
                    })
                ))
            }))
        }
        list.add(Row("Add custom EPG source", accentTitle = true, onClick = {
            Dialogs.input(activity, "Add EPG Source URL", "", "http://... or https://...") { url ->
                // Audit Se4: validate before saving, and refresh so
                // the new source takes effect immediately.
                val trimmed = url.trim()
                if (trimmed.isNotBlank()) {
                    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://") && !trimmed.startsWith("titantv://") && !trimmed.startsWith("cdnlive://")) {
                        Dialogs.message(activity, "Invalid URL", "EPG sources must be http(s) URLs.")
                    } else {
                        val current = prefs.epgSources().toMutableList()
                        if (trimmed !in current) current.add(trimmed)
                        prefs.saveEpgSources(current)
                        Repo.refreshEpg(force = true)
                        renderCurrent()
                    }
                }
            }
        }))
        // Audit Se4: a way back — clears the custom source list;
        // bundled/catalog sources always apply on their own.
        list.add(Row("Reset EPG sources", sub = "Removes all custom sources", onClick = {
            Dialogs.confirm(activity, "Reset EPG Sources", "Remove all custom EPG sources? Bundled sources are unaffected.") {
                prefs.saveEpgSources(emptyList())
                renderCurrent()
            }
        }))
        return list
    }

    // -------------------------------------------------------------
    // 4. Appearance
    // -------------------------------------------------------------
    private fun buildAppearanceMenu(): List<Row> = listOf(
        Row("TV GUIDE", kind = Kind.HEADER),
        Row("Row size", value = prefs.guideRowSize, onClick = {
            val opts = listOf("Small", "Medium", "Large")
            Dialogs.choose(activity, "Row Size", opts, opts.indexOf(prefs.guideRowSize)) {
                prefs.guideRowSize = opts[it]
                renderCurrent()
            }
        }),
        Row("Channel numbers", checked = prefs.guideChannelNumbers, onClick = {
            prefs.guideChannelNumbers = !prefs.guideChannelNumbers
            renderCurrent()
        }),
        Row("Channel logos", checked = prefs.guideLogos, onClick = {
            prefs.guideLogos = !prefs.guideLogos
            renderCurrent()
        }),
        Row("Progress indicators", checked = prefs.guideProgress, onClick = {
            prefs.guideProgress = !prefs.guideProgress
            renderCurrent()
        }),
        Row("THEME & COLORS", kind = Kind.HEADER),
        Row("Base theme", value = prefs.theme, onClick = {
            val opts = Pal.bases
            Dialogs.choose(activity, "Base Theme", opts, opts.indexOf(prefs.theme).coerceAtLeast(0)) {
                prefs.theme = opts[it]
                Pal.apply(prefs.theme, prefs.accent)
                applyChrome()
                renderCurrent()
            }
        }),
        Row("Accent color", value = prefs.accent, onClick = {
            val opts = Pal.accents.keys.toList()
            Dialogs.choose(activity, "Accent Color", opts, opts.indexOf(prefs.accent).coerceAtLeast(0)) {
                prefs.accent = opts[it]
                Pal.apply(prefs.theme, prefs.accent)
                applyChrome()
                renderCurrent()
            }
        }),
        Row("Font size", value = prefs.fontSize, onClick = {
            val opts = listOf("Small", "Medium", "Large")
            Dialogs.choose(activity, "Font Size", opts, opts.indexOf(prefs.fontSize).coerceAtLeast(0)) {
                prefs.fontSize = opts[it]
                Fonts.scale = when (prefs.fontSize) { "Small" -> 0.88f; "Large" -> 1.15f; else -> 1.0f }
                applyChrome()
                renderCurrent()
            }
        }),
        Row("GROUPS & SORTING", kind = Kind.HEADER),
        Row("Sort channels by", value = prefs.sortMode, onClick = {
            val opts = listOf("Playlist order", "A–Z")
            val cur = if (prefs.sortMode == "A–Z") 1 else 0
            Dialogs.choose(activity, "Sort Mode", opts, cur) {
                prefs.sortMode = if (it == 1) "A–Z" else "Playlist"
                // Audit Se7: local re-sort; this used to re-download
                // every playlist over the network.
                Repo.resort()
                renderCurrent()
            }
        }),
        Row("Show channel counts in groups", checked = prefs.groupsShowCounts, onClick = {
            prefs.groupsShowCounts = !prefs.groupsShowCounts
            renderCurrent()
        }),
        Row("Unhide all channels & groups", onClick = {
            Repo.unhideAll()
            Dialogs.message(activity, "Restored", "All hidden channels and categories are restored.")
        })
    )

    // -------------------------------------------------------------
    // 5. Playback
    // -------------------------------------------------------------
    private fun buildPlaybackMenu(): List<Row> = listOf(
        Row("Buffer size", value = prefs.buffer, onClick = {
            val opts = listOf("Small", "Medium", "Large", "Very large")
            Dialogs.choose(activity, "Buffer Size", opts, opts.indexOf(prefs.buffer).coerceAtLeast(0)) {
                prefs.buffer = opts[it]
                renderCurrent()
            }
        }),
        Row("Audio decoder", value = prefs.audioDecoder, onClick = {
            val opts = listOf("Hardware", "Software")
            Dialogs.choose(activity, "Audio Decoder", opts, opts.indexOf(prefs.audioDecoder).coerceAtLeast(0)) {
                prefs.audioDecoder = opts[it]
                renderCurrent()
            }
        }),
        Row("Video decoder", value = prefs.videoDecoder, onClick = {
            val opts = listOf("Auto", "Hardware", "Software")
            Dialogs.choose(activity, "Video Decoder", opts, opts.indexOf(prefs.videoDecoder).coerceAtLeast(0)) {
                prefs.videoDecoder = opts[it]
                renderCurrent()
            }
        }),
        Row("Auto frame rate (AFR)", value = prefs.afr, onClick = {
            val opts = listOf("Off", "Match frame rate")
            Dialogs.choose(activity, "Auto Frame Rate", opts, opts.indexOf(prefs.afr).coerceAtLeast(0)) {
                prefs.afr = opts[it]
                renderCurrent()
            }
        }),
        Row("Surround sound by default", checked = prefs.surroundDefault, onClick = {
            prefs.surroundDefault = !prefs.surroundDefault
            renderCurrent()
        }),
        Row("Audio passthrough", checked = prefs.passthrough, onClick = {
            prefs.passthrough = !prefs.passthrough
            renderCurrent()
        }),
        Row("Tunneled playback", checked = prefs.tunneled, onClick = {
            prefs.tunneled = !prefs.tunneled
            renderCurrent()
        }),
        Row("Default aspect ratio", value = prefs.aspect, onClick = {
            val opts = listOf("Fit", "Fill", "Zoom", "16:9", "4:3")
            Dialogs.choose(activity, "Aspect Ratio", opts, opts.indexOf(prefs.aspect).coerceAtLeast(0)) {
                prefs.aspect = opts[it]
                renderCurrent()
            }
        }),
        Row("Auto reconnect stream on error", checked = prefs.autoRetry, onClick = {
            prefs.autoRetry = !prefs.autoRetry
            renderCurrent()
        }),
        Row("Stream health", sub = "Route, codec, buffer & dropped frames", onClick = {
            Dialogs.message(activity, "Stream Health", healthProvider?.invoke() ?: "Player has not started yet.")
        })
    )

    // -------------------------------------------------------------
    // 6. Remote Control
    // -------------------------------------------------------------
    private fun buildRemoteMenu(): List<Row> = listOf(
        Row("TV GUIDE ACTIONS", kind = Kind.HEADER),
        Row("Press OK in guide", value = prefs.guideOkAction, onClick = {
            val opts = listOf("Preview, then fullscreen", "Fullscreen immediately")
            Dialogs.choose(activity, "Guide OK Action", opts, opts.indexOf(prefs.guideOkAction).coerceAtLeast(0)) {
                prefs.guideOkAction = opts[it]
                renderCurrent()
            }
        }),
        Row("Long press OK in guide", value = prefs.guideLongOk, onClick = {
            // Audit P4/G6: options are exactly the strings GuideView
            // handles ("Show quick channels" was never implemented).
            val opts = listOf("Channel menu", "Toggle favorite")
            Dialogs.choose(activity, "Guide Long OK Action", opts, opts.indexOf(prefs.guideLongOk).coerceAtLeast(0)) {
                prefs.guideLongOk = opts[it]
                renderCurrent()
            }
        }),
        Row("FULLSCREEN PLAYER ACTIONS", kind = Kind.HEADER),
        // Audit P4: the playerOk setting existed in Prefs and was
        // handled by PlayerOverlay but had no settings row at all.
        Row("Press OK in player", value = prefs.playerOk, onClick = {
            val opts = listOf("Channel list", "Pause", "TV Guide", "Info banner")
            Dialogs.choose(activity, "Player OK", opts, opts.indexOf(prefs.playerOk).coerceAtLeast(0)) {
                prefs.playerOk = opts[it]
                renderCurrent()
            }
        }),
        Row("Press Up / Down in player", value = prefs.playerUpDown, onClick = {
            val opts = listOf("Switch channel", "Show channel list", "Show info banner")
            Dialogs.choose(activity, "Player Up / Down", opts, opts.indexOf(prefs.playerUpDown).coerceAtLeast(0)) {
                prefs.playerUpDown = opts[it]
                renderCurrent()
            }
        }),
        Row("Press Left in player", value = prefs.playerLeft, onClick = {
            // Audit P4: these are exactly the strings PlayerOverlay
            // handles by name. The old list ("Rewind 10s", "Show
            // quick EPG", "Show channel list") silently fell through
            // to defaults, so two of three choices did nothing.
            val opts = listOf("Channel list", "TV Guide", "Program info")
            Dialogs.choose(activity, "Player Left", opts, opts.indexOf(prefs.playerLeft).coerceAtLeast(0)) {
                prefs.playerLeft = opts[it]
                renderCurrent()
            }
        }),
        Row("Press Right in player", value = prefs.playerRight, onClick = {
            val opts = listOf("Program info", "Channel list", "TV Guide")
            Dialogs.choose(activity, "Player Right", opts, opts.indexOf(prefs.playerRight).coerceAtLeast(0)) {
                prefs.playerRight = opts[it]
                renderCurrent()
            }
        }),
        Row("Channel list wrapping", sub = "Jump between top and bottom channels", checked = prefs.channelWrap, onClick = {
            prefs.channelWrap = !prefs.channelWrap
            renderCurrent()
        })
    )

    // -------------------------------------------------------------
    // 7. Parental Controls
    // -------------------------------------------------------------
    // Audit Se5: a new PIN is confirmed by re-entry, and trivial
    // PINs (all four digits the same, e.g. 0000) are rejected.
    private fun setPinWithConfirm(onDone: () -> Unit) {
        Dialogs.pin(activity, "Set 4-digit PIN") { first ->
            if (first.length == 4 && first.all { it == first[0] }) {
                Dialogs.message(activity, "PIN Too Simple", "Choose a PIN that is not four identical digits.")
                return@pin
            }
            Dialogs.pin(activity, "Confirm PIN") { second ->
                if (second == first) {
                    prefs.pin = first
                    onDone()
                } else {
                    Dialogs.message(activity, "PIN Mismatch", "The two entries did not match. PIN was not changed.")
                }
            }
        }
    }

    private fun buildParentalMenu(): List<Row> = listOf(
        Row("Parental controls active", checked = prefs.pinEnabled, onClick = {
            if (prefs.pinEnabled) {
                prefs.pinEnabled = false
                renderCurrent()
            } else {
                setPinWithConfirm {
                    prefs.pinEnabled = true
                    renderCurrent()
                }
            }
        }),
        Row("Change PIN code", onClick = {
            setPinWithConfirm {
                Dialogs.message(activity, "PIN Updated", "New parental PIN saved.")
            }
        }),
        Row("REQUIRE PIN FOR", kind = Kind.HEADER),
        Row("Settings access", checked = "settings" in prefs.pinFor, onClick = {
            togglePinFor("settings")
        }),
        Row("Playlist management", checked = "playlists" in prefs.pinFor, onClick = {
            togglePinFor("playlists")
        }),
        Row("Locked channels", checked = "channels" in prefs.pinFor, onClick = {
            togglePinFor("channels")
        })
    )

    private fun togglePinFor(item: String) {
        val s = prefs.pinFor.toMutableSet()
        if (item in s) s.remove(item) else s.add(item)
        prefs.pinFor = s
        renderCurrent()
    }

    private fun checkPinThen(action: () -> Unit) {
        if (!prefs.pinEnabled || prefs.pin.isBlank()) {
            action()
        } else {
            Dialogs.pin(activity, "Enter Parental PIN") { entered ->
                if (entered == prefs.pin) action()
                else Dialogs.message(activity, "Access Denied", "Incorrect PIN entered.")
            }
        }
    }

    // -------------------------------------------------------------
    // 8. Other
    // -------------------------------------------------------------
    private fun buildOtherMenu(): List<Row> = listOf(
        Row("SEARCH OPTIONS", kind = Kind.HEADER),
        Row("Search channel titles", checked = prefs.searchChannels, onClick = {
            prefs.searchChannels = !prefs.searchChannels
            renderCurrent()
        }),
        Row("Search program names", checked = prefs.searchPrograms, onClick = {
            prefs.searchPrograms = !prefs.searchPrograms
            renderCurrent()
        }),
        Row("Clear search history", onClick = {
            prefs.searchTerms = emptyList()
            Dialogs.message(activity, "Cleared", "Search history cleared.")
        }),
        Row("REMINDERS", kind = Kind.HEADER),
        Row("Reminder lead time", value = "${prefs.remindLeadMinutes} minutes", onClick = {
            val opts = listOf("1 min", "2 min", "5 min", "10 min")
            val vals = listOf(1, 2, 5, 10)
            Dialogs.choose(activity, "Reminder Lead Time", opts, vals.indexOf(prefs.remindLeadMinutes).coerceAtLeast(0)) {
                prefs.remindLeadMinutes = vals[it]
                renderCurrent()
            }
        }),
        Row("Auto-switch on reminder", checked = prefs.remindAutoSwitch, onClick = {
            prefs.remindAutoSwitch = !prefs.remindAutoSwitch
            renderCurrent()
        }),
        Row("ABOUT", kind = Kind.HEADER),
        // Audit Se2: the version string was hard-coded at 4.3.0 and
        // already stale; read it from the installed package.
        Row("Live TV for Android TV", sub = "Version " + try {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }),
        Row("Check for updates", sub = "Latest version comes from GitHub", onClick = {
            com.livetv.app.data.Updater.checkAndPrompt(activity, manual = true)
        }),
        Row("Auto-check for updates", checked = prefs.autoUpdate, onClick = {
            prefs.autoUpdate = !prefs.autoUpdate
            renderCurrent()
        })
    )
}

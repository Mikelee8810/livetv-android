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

    private fun push(screen: Screen) {
        navStack.add(screen)
        renderCurrent()
    }

    fun handleBack(): Boolean {
        if (navStack.size > 1) {
            navStack.removeAt(navStack.size - 1)
            renderCurrent()
            return true
        } else {
            onClose()
            return true
        }
    }

    private fun renderCurrent() {
        val cur = navStack.last()
        titleView.text = cur.title
        val rows = cur.rows()
        listPanel.submit(rows, 0)
    }

    // -------------------------------------------------------------
    // Root Settings
    // -------------------------------------------------------------
    private fun buildRootMenu(): List<Row> = listOf(
        Row("General", sub = "Boot, PiP, user-agent, proxy", onClick = {
            push(Screen("General", ::buildGeneralMenu))
        }),
        Row("Playlists", sub = "${prefs.playlists().size} configured", onClick = {
            push(Screen("Playlists", ::buildPlaylistsMenu))
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
        Row("Other", sub = "Search, recordings & reminders", onClick = {
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
            Repo.reload {
                Dialogs.message(activity, "Playlists Updated", "Reloaded channels from all active playlists.")
                renderCurrent()
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
        Row("Past days to keep EPG", value = "${prefs.epgKeepDays} days", onClick = {
            val opts = listOf("1 day", "3 days", "5 days", "7 days", "14 days")
            val vals = listOf(1, 3, 5, 7, 14)
            val cur = vals.indexOf(prefs.epgKeepDays).coerceAtLeast(0)
            Dialogs.choose(activity, "Keep EPG Days", opts, cur) {
                prefs.epgKeepDays = vals[it]
                renderCurrent()
            }
        }),
        Row("Store program descriptions", checked = prefs.epgDescriptions, onClick = {
            prefs.epgDescriptions = !prefs.epgDescriptions
            renderCurrent()
        }),
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
            Repo.refreshEpg(force = true) { status ->
                Dialogs.message(activity, "EPG Update", status)
                renderCurrent()
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
                    Row("Copy / View URL", sub = src),
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
                if (url.isNotBlank()) {
                    val current = prefs.epgSources().toMutableList()
                    if (url !in current) current.add(url)
                    prefs.saveEpgSources(current)
                    renderCurrent()
                }
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
                renderCurrent()
            }
        }),
        Row("Accent color", value = prefs.accent, onClick = {
            val opts = Pal.accents.keys.toList()
            Dialogs.choose(activity, "Accent Color", opts, opts.indexOf(prefs.accent).coerceAtLeast(0)) {
                prefs.accent = opts[it]
                Pal.apply(prefs.theme, prefs.accent)
                renderCurrent()
            }
        }),
        Row("Font size", value = prefs.fontSize, onClick = {
            val opts = listOf("Small", "Medium", "Large")
            Dialogs.choose(activity, "Font Size", opts, opts.indexOf(prefs.fontSize).coerceAtLeast(0)) {
                prefs.fontSize = opts[it]
                Fonts.scale = when (prefs.fontSize) { "Small" -> 0.88f; "Large" -> 1.15f; else -> 1.0f }
                renderCurrent()
            }
        }),
        Row("GROUPS & SORTING", kind = Kind.HEADER),
        Row("Sort channels by", value = prefs.sortMode, onClick = {
            val opts = listOf("Playlist order", "A–Z")
            val cur = if (prefs.sortMode == "A–Z") 1 else 0
            Dialogs.choose(activity, "Sort Mode", opts, cur) {
                prefs.sortMode = if (it == 1) "A–Z" else "Playlist"
                Repo.reload()
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
            val opts = listOf("Hardware", "Software")
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
            val opts = listOf("Channel menu", "Toggle favorite", "Show quick channels")
            Dialogs.choose(activity, "Guide Long OK Action", opts, opts.indexOf(prefs.guideLongOk).coerceAtLeast(0)) {
                prefs.guideLongOk = opts[it]
                renderCurrent()
            }
        }),
        Row("FULLSCREEN PLAYER ACTIONS", kind = Kind.HEADER),
        Row("Press Up / Down in player", value = prefs.playerUpDown, onClick = {
            val opts = listOf("Switch channel", "Show channel list", "Show info banner")
            Dialogs.choose(activity, "Player Up / Down", opts, opts.indexOf(prefs.playerUpDown).coerceAtLeast(0)) {
                prefs.playerUpDown = opts[it]
                renderCurrent()
            }
        }),
        Row("Press Left in player", value = prefs.playerLeft, onClick = {
            val opts = listOf("Channel list", "Rewind 10s", "Show quick EPG")
            Dialogs.choose(activity, "Player Left", opts, opts.indexOf(prefs.playerLeft).coerceAtLeast(0)) {
                prefs.playerLeft = opts[it]
                renderCurrent()
            }
        }),
        Row("Press Right in player", value = prefs.playerRight, onClick = {
            val opts = listOf("Program info", "Fast forward 10s", "Show channel list")
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
    private fun buildParentalMenu(): List<Row> = listOf(
        Row("Parental controls active", checked = prefs.pinEnabled, onClick = {
            if (prefs.pinEnabled) {
                prefs.pinEnabled = false
                renderCurrent()
            } else {
                Dialogs.pin(activity, "Set 4-digit PIN") { newPin ->
                    prefs.pin = newPin
                    prefs.pinEnabled = true
                    renderCurrent()
                }
            }
        }),
        Row("Change PIN code", onClick = {
            Dialogs.pin(activity, "Enter New PIN") { newPin ->
                prefs.pin = newPin
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
        Row("REMINDERS & RECORDING", kind = Kind.HEADER),
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
        Row("Live TV for Android TV", sub = "Version 4.0.0 (Clean TiviMate 1:1 Architecture)", value = "Up to date")
    )
}

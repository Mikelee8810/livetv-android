package com.livetv.app

import android.app.Activity
import android.app.PictureInPictureParams
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.Gravity
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.livetv.app.data.Item
import com.livetv.app.data.Prefs
import com.livetv.app.data.Repo
import com.livetv.app.epg.Program
import com.livetv.app.player.PlayerEngine
import com.livetv.app.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var playerEngine: PlayerEngine
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastBackPress = 0L
    // Stored so it can be removed in onDestroy (audit M2): an anonymous
    // listener added in onCreate would leak one Activity per recreate.
    private val repoListener: () -> Unit = {
        updateGroups()
        updateGuideRows()
        // Warm channel logos for the current group first, then the rest,
        // so the guide paints real icons instead of initials.
        if (prefs.guideLogos) {
            val first = Repo.inGroup(prefs.lastGroup).map { it.ch.logo }
            com.livetv.app.ui.Logos.prefetch(this, first + Repo.items.map { it.ch.logo })
        }
    }
    private var closingSettings = false
    private val prewarmClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    @Volatile private var prewarmCall: okhttp3.Call? = null

    // Top-level containers
    private lateinit var rootContainer: FrameLayout
    private lateinit var guideLayout: FrameLayout
    private lateinit var guideContainer: LinearLayout
    private lateinit var guideView: GuideView

    // Drawer Overlays (TiviMate 1:1 Sliding Drawers)
    private lateinit var categoriesDrawer: LinearLayout
    private lateinit var groupsList: ListPanel
    private lateinit var mainMenuDrawer: LinearLayout
    private var isCategoriesDrawerOpen = false
    private var isMainMenuDrawerOpen = false

    // Preview / Info header in Guide
    private lateinit var previewContainer: FrameLayout
    private lateinit var mainVideoView: TextureView
    private lateinit var infoContainer: LinearLayout
    private lateinit var infoTitle: TextView
    private lateinit var infoSub: TextView
    private lateinit var infoDesc: TextView
    private lateinit var infoFav: ImageView

    // Fullscreen elements
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var playerOverlay: PlayerOverlay

    // Overlay panels
    private var settingsDrawer: SettingsDrawer? = null
    private var searchPanel: SearchPanel? = null
    private var isFullscreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Data & Engine
        Repo.init(this)
        prefs = Repo.prefs
        Thread { com.livetv.app.data.Recorder.purgeOld(this) }.start()
        Pal.apply(prefs.theme, prefs.accent)
        Fonts.scale = when (prefs.fontSize) { "Small" -> 0.88f; "Large" -> 1.15f; else -> 1.0f }
        playerEngine = PlayerEngine(this, prefs)
        // Catch-up wiring for the programme dialog (Ondo-style): restart a
        // live programme or watch a past one from its start while it is
        // still inside the stream's DVR window.
        ProgramDetailsDialog.catchUpHandler = { item, prog ->
            val startMs = prog.start.toEpochMilli()
            if (playerEngine.currentItem?.key != item.key) {
                playChannel(item)
                // A fresh tune has no position to seek from yet; wait
                // for playback before seeking to the start (audit M8).
                seekToProgramWhenReady(startMs, 20)
            } else {
                playerEngine.seekToProgramStart(startMs)
            }
            true
        }
        ProgramDetailsDialog.liveHandler = { item ->
            if (playerEngine.currentItem?.key == item.key) playerEngine.returnToLive()
        }

        buildUI()

        // Listen for Repo updates
        Repo.listeners.add(repoListener)

        // Initial Data Load
        Repo.reload {
            val curGroup = prefs.lastGroup.ifBlank { "All channels" }
            var candidateList = Repo.inGroup(curGroup).filter { !it.groups.contains("International") }
            if (candidateList.isEmpty()) {
                candidateList = Repo.visibleItems().filter { !it.groups.contains("International") }
                prefs.lastGroup = "All channels"
            }

            var startItem = candidateList.firstOrNull { it.key == prefs.lastChannel }
            // Reminder notification tap: jump straight to that channel.
            val reminderKey = intent?.getStringExtra("channelKey")
            if (!reminderKey.isNullOrBlank()) {
                Repo.byKey(reminderKey)?.let { startItem = it }
            }
            if (startItem == null) {
                val savedItem = if (prefs.lastChannel.isNotBlank()) Repo.byKey(prefs.lastChannel) else null
                if (savedItem != null && !savedItem.groups.contains("International")) {
                    val targetGroup = savedItem.groups.firstOrNull() ?: "All channels"
                    prefs.lastGroup = targetGroup
                    guideView.rows = Repo.inGroup(targetGroup)
                    startItem = savedItem
                } else {
                    startItem = candidateList.firstOrNull()
                }
            }
            if (startItem != null) {
                prefs.lastChannel = startItem.key
                if (prefs.resumeLast || !reminderKey.isNullOrBlank()) {
                    playChannel(startItem)
                }
                guideView.select(startItem.key)
            }
            guideView.focusCol = GuideView.GuideFocus.CHANNEL
            // Only pull focus to the guide if nothing else is open
            // (audit N21): keys are live while the first load runs,
            // and a user who already opened a drawer had focus yanked
            // back behind it when the load finished.
            if (!isCategoriesDrawerOpen && !isMainMenuDrawerOpen && settingsDrawer == null && searchPanel == null) {
                guideView.requestFocus()
            }
        }
    }

    private fun buildUI() {
        rootContainer = FrameLayout(this).apply {
            background = Pal.bgGradient()
        }

        // 1. Guide Layout (Base: top info + preview placeholder, bottom guide grid)
        setupGuideLayout()
        rootContainer.addView(guideLayout, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // 2. Single Video View for both Preview & Fullscreen (Top-Right preview in Guide mode)
        // TextureView, NOT SurfaceView (v4.4.1): on the Google TV
        // Streamer's MediaTek decoder, SurfaceView output goes through
        // the vendor sideband path (C2VideoTunnelHandleSetter) and the
        // frames never reach the display - SurfaceFlinger showed the
        // SurfaceView layer with NO active buffer and a zero-size buffer
        // layer while the decoder "rendered" into the void: black box
        // with audio, on every channel (device-verified 2026-11-03).
        // A TextureView renders through a normal BufferQueue the app
        // composites itself, which cannot take the sideband path.
        mainVideoView = TextureView(this).apply {
            isFocusable = false
            isOpaque = true
            layoutParams = FrameLayout.LayoutParams(dpi(380f), dpi(214f), Gravity.TOP or Gravity.END).apply {
                rightMargin = dpi(24f)
                topMargin = dpi(16f)
            }
            setOnClickListener {
                if (playerEngine.currentItem != null) {
                    enterFullscreen()
                }
            }
        }
        rootContainer.addView(mainVideoView)
        playerEngine.attachTextureView(mainVideoView)

        // Closed captions: drawn over the video and kept the same size
        // and position as it (preview box or fullscreen). Nothing drew
        // caption cues before, so picking a caption track did nothing.
        val captionView = androidx.media3.ui.SubtitleView(this).apply {
            setUserDefaultStyle()
            setUserDefaultTextSize()
            isFocusable = false
        }
        rootContainer.addView(captionView, FrameLayout.LayoutParams(dpi(380f), dpi(214f)))
        mainVideoView.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            val lp = captionView.layoutParams as FrameLayout.LayoutParams
            if (lp.width != r - l || lp.height != b - t || lp.leftMargin != l || lp.topMargin != t) {
                lp.width = r - l; lp.height = b - t; lp.leftMargin = l; lp.topMargin = t
                lp.gravity = Gravity.TOP or Gravity.START
                captionView.layoutParams = lp
            }
            captionView.visibility = v.visibility
        }
        playerEngine.onCues = { cues -> captionView.setCues(cues) }
        playerEngine.onStateChanged = {
            if (isFullscreen) {
                playerEngine.setAspectRatio(prefs.aspect, rootContainer, mainVideoView)
            }
        }

        // 3. Sliding Categories Drawer Overlay (slides in over the guide on Left)
        setupCategoriesDrawer()
        rootContainer.addView(categoriesDrawer)

        // 4. Sliding Main Menu Drawer Overlay (slides in over the categories drawer on Left)
        setupMainMenuDrawer()
        rootContainer.addView(mainMenuDrawer)

        // 5. Fullscreen Overlay Container
        setupFullscreenLayout()
        rootContainer.addView(fullscreenContainer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        setContentView(rootContainer)

        // Quiet update check a few seconds after start; only speaks up when
        // a newer GitHub release exists (Settings > Other can turn it off).
        if (prefs.autoUpdate) {
            rootContainer.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    com.livetv.app.data.Updater.checkAndPrompt(this, manual = false)
                }
            }, 6000)
        }
    }

    private fun setupGuideLayout() {
        guideLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        // Base: Full-Width TV Guide (Live Preview & Program Details Header on top, GuideView on bottom)
        setupGuideContent()
        guideLayout.addView(guideContainer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun setupGuideContent() {
        guideContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            background = Pal.bgGradient()
        }

        // Top Area: Selected Program Info (Left) + Preview Video (Right)
        val topArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(230f))
            setPadding(dpi(24f), dpi(16f), dpi(24f), dpi(8f))
        }

        // Program Info Container (Left, fills remaining width)
        infoContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).also {
                it.rightMargin = dpi(28f)
            }
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        infoTitle = TextView(this).apply {
            text = "Live TV"
            textSize = 26f * Fonts.scale
            typeface = Fonts.medium
            setTextColor(Pal.text)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(infoTitle)

        infoFav = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpi(22f), dpi(22f)).also {
                it.leftMargin = dpi(12f)
            }
            setImageResource(R.drawable.ic_star)
            setColorFilter(Pal.accent2)
            visibility = View.GONE
        }
        titleRow.addView(infoFav)
        infoContainer.addView(titleRow)

        infoSub = TextView(this).apply {
            text = "Select a channel"
            textSize = 15f
            typeface = Fonts.regular
            setTextColor(Pal.accent)
            setPadding(0, dpi(4f), 0, dpi(6f))
        }
        infoContainer.addView(infoSub)

        infoDesc = TextView(this).apply {
            text = ""
            textSize = 14f
            typeface = Fonts.regular
            setTextColor(Pal.textDim)
            maxLines = 3
        }
        infoContainer.addView(infoDesc)

        topArea.addView(infoContainer)

        // Preview Video Placeholder & Click Target (Right)
        previewContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpi(380f), dpi(214f))
            isClickable = true
            isFocusable = false
            setOnClickListener {
                if (playerEngine.currentItem != null) {
                    enterFullscreen()
                }
            }
        }
        topArea.addView(previewContainer)

        guideContainer.addView(topArea)

        // Bottom Area: Guide View (Full-width grid)
        guideView = GuideView(this, prefs).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            onPlay = { item ->
                playChannel(item)
            }
            onFullscreen = { item ->
                playChannel(item)
                enterFullscreen()
            }
            onMenu = { item, prog ->
                ChannelMenu.show(this@MainActivity, item, prog, onWatch = {
                    playChannel(item)
                    enterFullscreen()
                }, onRefresh = {
                    updateGuideRows()
                })
            }
            onProgram = { item, prog ->
                ProgramDetailsDialog.show(this@MainActivity, item, prog, onWatch = {
                    playChannel(item)
                    enterFullscreen()
                })
            }
            onOpenCategories = {
                openCategoriesDrawer()
            }
            onSelection = { item, prog ->
                updateInfoCard(item, prog)
                schedulePrewarm(item)
            }
        }
        guideContainer.addView(guideView)
    }

    private fun setupCategoriesDrawer() {
        categoriesDrawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(dpi(360f), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START)
            background = GradientDrawable().apply {
                setColor(Pal.panel)
                cornerRadii = floatArrayOf(0f, 0f, dp(18f), dp(18f), dp(18f), dp(18f), 0f, 0f)
                setStroke(dpi(1f), Pal.cardEdge)
            }
            setPadding(dpi(20f), dpi(24f), dpi(16f), dpi(16f))
            elevation = dp(16f)
            visibility = View.GONE
            translationX = -dpi(360f).toFloat()
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        val title = TextView(this).apply {
            text = "Categories"
            textSize = 22f * Fonts.scale
            typeface = Fonts.medium
            setTextColor(Pal.text)
            setPadding(dpi(8f), 0, 0, dpi(16f))
        }
        categoriesDrawer.addView(title)

        groupsList = ListPanel(this).apply {
            rowHeightDp = 44f
            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            onFocusRow = { index, _ ->
                val groups = Repo.groups()
                val group = groups.getOrNull(index)
                if (group != null) {
                    guideView.rows = Repo.inGroup(group.id)
                }
            }
            onEdge = { code ->
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK -> {
                        guideView.rows = Repo.inGroup(prefs.lastGroup)
                        openMainMenuDrawer()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        selectCurrentCategoryAndClose()
                        true
                    }
                    else -> false
                }
            }
        }
        categoriesDrawer.addView(groupsList)
    }

    private fun setupMainMenuDrawer() {
        mainMenuDrawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(dpi(300f), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START)
            setBackgroundColor(Pal.rail)
            setPadding(dpi(20f), dpi(24f), dpi(20f), dpi(24f))
            elevation = dp(24f)
            visibility = View.GONE
            translationX = -dpi(300f).toFloat()
        }

        // Brand logo badge top
        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(8f), 0, 0, dpi(28f))
        }
        val brandLogo = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpi(32f), dpi(32f))
            setImageResource(R.drawable.ic_tv)
            setColorFilter(Pal.accent)
        }
        val brandText = TextView(this).apply {
            text = "Live TV"
            textSize = 20f * Fonts.scale
            typeface = Fonts.medium
            setTextColor(Pal.text)
            setPadding(dpi(12f), 0, 0, 0)
        }
        brandRow.addView(brandLogo)
        brandRow.addView(brandText)
        mainMenuDrawer.addView(brandRow)

        fun makeMenuItem(title: String, iconRes: Int, onAction: () -> Unit): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(48f)).also {
                    it.bottomMargin = dpi(8f)
                }
                setPadding(dpi(14f), 0, dpi(14f), 0)
                isFocusable = true
                isClickable = true
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(8f)
                }

                val icon = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dpi(24f), dpi(24f))
                    setImageResource(iconRes)
                    setColorFilter(Pal.textDim)
                }
                val text = TextView(context).apply {
                    this.text = title
                    textSize = 16f * Fonts.scale
                    typeface = Fonts.medium
                    setTextColor(Pal.textDim)
                    setPadding(dpi(14f), 0, 0, 0)
                }
                addView(icon)
                addView(text)

                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        background = GradientDrawable().apply {
                            setColor(Pal.focusFill)
                            cornerRadius = dp(8f)
                        }
                        icon.setColorFilter(Pal.focusText)
                        text.setTextColor(Pal.focusText)
                    } else {
                        background = GradientDrawable().apply {
                            setColor(Color.TRANSPARENT)
                            cornerRadius = dp(8f)
                        }
                        icon.setColorFilter(Pal.textDim)
                        text.setTextColor(Pal.textDim)
                    }
                }

                setOnClickListener {
                    onAction()
                }

                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                closeMainMenuDrawer(focusCategories = true)
                                true
                            }
                            KeyEvent.KEYCODE_BACK -> {
                                // Back out one level to Categories, never
                                // straight to exit while a drawer is open
                                // (v4.4.1; dispatchKeyEvent normally gets
                                // this first - this is the belt to its
                                // braces for keys delivered to the view).
                                closeMainMenuDrawer(focusCategories = true)
                                true
                            }
                            else -> false
                        }
                    } else false
                }
            }
        }

        mainMenuDrawer.addView(makeMenuItem("Search", R.drawable.ic_search) {
            closeMainMenuDrawer(false)
            closeCategoriesDrawer(false)
            showSearch()
        })
        mainMenuDrawer.addView(makeMenuItem("TV Guide", R.drawable.ic_tv) {
            closeMainMenuDrawer(false)
            closeCategoriesDrawer(true)
        })
        mainMenuDrawer.addView(makeMenuItem("Reminders", R.drawable.ic_alarm) {
            closeMainMenuDrawer(false)
            val saved = com.livetv.app.data.Reminders.all(prefs)
            if (saved.isEmpty()) {
                Dialogs.message(this, "Reminders", "No reminders saved. Open any upcoming program and choose Set reminder.")
            } else {
                val fmt = java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.US)
                val rows = saved.map { r ->
                    com.livetv.app.ui.Row(
                        "${r.title} — ${r.channelName}",
                        sub = fmt.format(java.util.Date(r.startMs)),
                        onClick = {
                            com.livetv.app.data.Reminders.cancel(this, prefs, r)
                            Dialogs.message(this, "Reminder Cancelled", "'${r.title}' will no longer alert you.")
                        }
                    )
                }
                com.livetv.app.ui.Dialogs.menu(this, "Reminders (tap to cancel)", rows, 460f)
            }
        })
        mainMenuDrawer.addView(makeMenuItem("Recordings", R.drawable.ic_tv) {
            closeMainMenuDrawer(false)
            showRecordings()
        })
        mainMenuDrawer.addView(makeMenuItem("My list", R.drawable.ic_bookmark) {
            closeMainMenuDrawer(false)
            // Was a placeholder message; now lists favourite channels.
            val favs = Repo.items.filter { Repo.isFav(it) }
            if (favs.isEmpty()) {
                Dialogs.message(this, "My List", "No favorites yet. While watching, press Favorite in the player bar, or use a channel's menu in the guide.")
            } else {
                val rows = favs.map { item ->
                    val now = Repo.current(item)
                    com.livetv.app.ui.Row(item.name, sub = now?.title ?: "", onClick = {
                        playChannel(item)
                        guideView.select(item.key)
                    })
                }
                com.livetv.app.ui.Dialogs.menu(this, "My List", rows, 460f)
            }
        })

        // Spacer to push Settings to bottom
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, 1f)
        }
        mainMenuDrawer.addView(spacer)

        mainMenuDrawer.addView(makeMenuItem("Settings", R.drawable.ic_settings) {
            closeMainMenuDrawer(false)
            closeCategoriesDrawer(false)
            showSettings()
        })

        // Explicit vertical focus chain for the rail (v4.4.1). Children:
        // 0 brand, 1 Search, 2 TV Guide, 3 Reminders, 4 My list,
        // 5 spacer, 6 Settings. Device proof on 4.4.0: with no chain,
        // DPAD_DOWN from "My list" left the rail entirely and landed in
        // the Categories list beside it - Settings (below a weighted
        // spacer) was geometrically unreachable. The chain makes every
        // step deterministic, and the end items point at themselves so
        // focus can never fall out of the rail vertically.
        val railItems = listOf(
            mainMenuDrawer.getChildAt(1),
            mainMenuDrawer.getChildAt(2),
            mainMenuDrawer.getChildAt(3),
            mainMenuDrawer.getChildAt(4),
            mainMenuDrawer.getChildAt(6)
        )
        railItems.forEach { it.id = View.generateViewId() }
        railItems.forEachIndexed { i, v ->
            v.nextFocusDownId = railItems.getOrNull(i + 1)?.id ?: v.id
            v.nextFocusUpId = railItems.getOrNull(i - 1)?.id ?: v.id
        }
    }

    private fun setupFullscreenLayout() {
        fullscreenContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
            isFocusable = true
            isFocusableInTouchMode = true
        }

        playerOverlay = PlayerOverlay(
            activity = this,
            prefs = prefs,
            playerEngine = playerEngine,
            onSwitchChannel = { item ->
                playChannel(item)
                guideView.select(item.key)
            },
            onExitFullscreen = {
                exitFullscreen()
            },
            onOpenMenu = { item ->
                ChannelMenu.show(this, item, null, onWatch = {}, onRefresh = { updateGuideRows() })
            },
            onAspectChanged = {
                if (isFullscreen) {
                    playerEngine.setAspectRatio(prefs.aspect, rootContainer, mainVideoView)
                }
            }
        )
        fullscreenContainer.addView(playerOverlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    fun openCategoriesDrawer() {
        if (isCategoriesDrawerOpen) return
        isCategoriesDrawerOpen = true
        guideView.isFocusable = false
        groupsList.focusGrabBlocked = false
        categoriesDrawer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        categoriesDrawer.bringToFront()
        categoriesDrawer.visibility = View.VISIBLE
        val curIdx = Repo.groups().indexOfFirst { it.id == prefs.lastGroup }.coerceAtLeast(0)
        categoriesDrawer.animate()
            .translationX(0f)
            .setDuration(220)
            .withEndAction {
                groupsList.focusRow(curIdx)
            }
            .start()
        groupsList.focusRow(curIdx)
    }

    private fun selectCurrentCategoryAndClose() {
        val groups = Repo.groups()
        val curIdx = groupsList.focusedIndex().takeIf { it >= 0 } ?: groups.indexOfFirst { it.id == prefs.lastGroup }.coerceAtLeast(0)
        val group = groups.getOrNull(curIdx)
        if (group != null) {
            prefs.lastGroup = group.id
            updateGroups()
            updateGuideRows()
            guideView.selectTop()
        }
        closeCategoriesDrawer(focusGuide = true)
    }

    fun closeCategoriesDrawer(focusGuide: Boolean = true) {
        if (!isCategoriesDrawerOpen) {
            if (focusGuide) {
                guideView.isFocusable = true
                guideView.focusCol = GuideView.GuideFocus.CHANNEL
                guideView.requestFocus()
            }
            return
        }
        guideView.rows = Repo.inGroup(prefs.lastGroup)
        isCategoriesDrawerOpen = false
        // The list re-focuses a row in a posted runnable after submit();
        // that fired after the guide took focus, so the hidden drawer kept
        // the D-pad and UP/DOWN kept switching categories (device repro).
        groupsList.focusGrabBlocked = true
        categoriesDrawer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        groupsList.clearFocus()
        categoriesDrawer.animate()
            .translationX(-dpi(360f).toFloat())
            .setDuration(200)
            .withEndAction {
                categoriesDrawer.visibility = View.GONE
                if (focusGuide) {
                    guideView.isFocusable = true
                    guideView.focusCol = GuideView.GuideFocus.CHANNEL
                    guideView.requestFocus()
                }
            }
            .start()
        if (focusGuide) {
            guideView.isFocusable = true
            guideView.focusCol = GuideView.GuideFocus.CHANNEL
            guideView.requestFocus()
        }
    }

    fun openMainMenuDrawer() {
        if (isMainMenuDrawerOpen) return
        isMainMenuDrawerOpen = true
        guideView.isFocusable = false
        // Focus containment (v4.4.1): while the rail is open, the
        // Categories list beside it is visible but must be OUT of the
        // focus graph - its rows were the landing spot that swallowed
        // DPAD_DOWN out of the rail on device, and its delayed focus
        // grabs could steal focus back mid-navigation.
        categoriesDrawer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        groupsList.focusGrabBlocked = true
        mainMenuDrawer.bringToFront()
        mainMenuDrawer.visibility = View.VISIBLE
        categoriesDrawer.animate()
            .translationX(dpi(300f).toFloat())
            .setDuration(220)
            .start()
        mainMenuDrawer.animate()
            .translationX(0f)
            .setDuration(220)
            .withEndAction {
                mainMenuDrawer.getChildAt(1)?.requestFocus() ?: mainMenuDrawer.getChildAt(2)?.requestFocus()
            }
            .start()
        mainMenuDrawer.getChildAt(1)?.requestFocus() ?: mainMenuDrawer.getChildAt(2)?.requestFocus()
    }

    fun closeMainMenuDrawer(focusCategories: Boolean = true) {
        // The rail is closing (or already closed): Categories becomes
        // focusable again BEFORE any focus hand-off into it (v4.4.1).
        // Only while Categories is actually open: re-enabling a closed
        // drawer let its list steal the D-pad from the guide again.
        if (isCategoriesDrawerOpen) {
            categoriesDrawer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            groupsList.focusGrabBlocked = false
        }
        if (!isMainMenuDrawerOpen) {
            if (focusCategories && isCategoriesDrawerOpen) {
                val curIdx = Repo.groups().indexOfFirst { it.id == prefs.lastGroup }.coerceAtLeast(0)
                groupsList.focusRow(curIdx)
            }
            return
        }
        isMainMenuDrawerOpen = false
        mainMenuDrawer.animate()
            .translationX(-dpi(300f).toFloat())
            .setDuration(200)
            .withEndAction {
                mainMenuDrawer.visibility = View.GONE
            }
            .start()
        categoriesDrawer.animate()
            .translationX(0f)
            .setDuration(200)
            .withEndAction {
                if (focusCategories) {
                    val curIdx = Repo.groups().indexOfFirst { it.id == prefs.lastGroup }.coerceAtLeast(0)
                    groupsList.focusRow(curIdx)
                }
            }
            .start()
        if (focusCategories) {
            val curIdx = Repo.groups().indexOfFirst { it.id == prefs.lastGroup }.coerceAtLeast(0)
            groupsList.focusRow(curIdx)
        }
    }

    private fun showSettings() {
        if (settingsDrawer == null) {
            settingsDrawer = SettingsDrawer(this, prefs, healthProvider = { playerEngine.streamHealth() }) {
                closeSettings()
            }
            val lp = FrameLayout.LayoutParams(dpi(440f), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END)
            rootContainer.addView(settingsDrawer, lp)
        }
        closingSettings = false
        // The guide must not take keys behind the drawer (audit N16,
        // mirroring the categories/main-menu drawers).
        guideView.isFocusable = false
        settingsDrawer?.apply {
            visibility = View.VISIBLE
            translationX = dp(440f)
            animate().translationX(0f).setDuration(200).setListener(null).start()
            requestFocus()
        }
    }

    private fun closeSettings() {
        if (closingSettings) return
        closingSettings = true
        settingsDrawer?.let { drawer ->
            drawer.animate().translationX(dp(440f)).setDuration(200).withEndAction {
                // A reopen during the close animation cancels this:
                // never remove the freshly reopened drawer (audit M7).
                if (!closingSettings) return@withEndAction
                closingSettings = false
                rootContainer.removeView(drawer)
                settingsDrawer = null
                // Only restore guide focus when the guide is actually
                // the surface the user returns to (audit N7): entering
                // fullscreen also closes settings, and the old
                // unconditional call fired 200 ms later against the
                // hidden guide while the player overlay was up.
                guideView.isFocusable = true
                if (!isFullscreen && searchPanel == null && !isMainMenuDrawerOpen) {
                    guideView.requestFocus()
                }
            }.start()
        } ?: run { closingSettings = false }
    }

    private fun showSearch() {
        if (searchPanel == null) {
            searchPanel = SearchPanel(
                activity = this,
                prefs = prefs,
                onPlayChannel = { item ->
                    closeSearch()
                    playChannel(item)
                    enterFullscreen()
                },
                onClose = {
                    closeSearch()
                }
            )
            rootContainer.addView(searchPanel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        // Guide keys off behind the full-screen panel (audit N16).
        guideView.isFocusable = false
        searchPanel?.apply {
            visibility = View.VISIBLE
            focusInput()
        }
    }

    private fun closeSearch() {
        searchPanel?.let { panel ->
            rootContainer.removeView(panel)
            searchPanel = null
            guideView.isFocusable = true
            guideView.requestFocus()
        }
    }

    private fun removeOverlays() {
        closeMainMenuDrawer(false)
        closeCategoriesDrawer(false)
        closeSettings()
        closeSearch()
    }

    private fun updateGroups() {
        val groups = Repo.groups()
        val curGroup = prefs.lastGroup
        val rows = groups.map { g ->
            val countStr = if (prefs.groupsShowCounts) "${g.count}" else null
            Row(
                title = g.title,
                value = countStr,
                selected = g.id == curGroup,
                onClick = {
                    prefs.lastGroup = g.id
                    updateGroups()
                    updateGuideRows()
                    guideView.selectTop()
                    closeCategoriesDrawer(focusGuide = true)
                }
            )
        }
        groupsList.submit(rows)
    }

    // -- channel prewarm -----------------------------------------------------
    // Resolving a DaddyLive channel walks embed pages and can take 5-15 s.
    // When focus rests on a guide row for a moment, resolve that channel in
    // the background so pressing OK starts playback almost immediately.
    private val prewarmHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var prewarmRunnable: Runnable? = null

    private fun schedulePrewarm(item: Item?) {
        prewarmRunnable?.let { prewarmHandler.removeCallbacks(it) }
        prewarmRunnable = null
        prewarmCall?.cancel()
        prewarmCall = null
        if (item == null) return
        if (item.key == playerEngine.currentItem?.key) return
        if (!item.url.startsWith("/")) return
        // Never fight the playing stream for the resolver or the pipe
        // (audit M3): no prewarm while buffering or while fullscreen.
        if (playerEngine.isBuffering || isFullscreen) return
        val url = "http://127.0.0.1:48913" + item.url
        val r = Runnable {
            kotlin.concurrent.thread(name = "prewarm") {
                try {
                    val call = prewarmClient.newCall(okhttp3.Request.Builder().url(url).build())
                    prewarmCall = call
                    call.execute().use { resp ->
                        resp.body?.close()
                    }
                } catch (e: Exception) { /* prewarm is best-effort */ }
            }
        }
        prewarmRunnable = r
        prewarmHandler.postDelayed(r, 600)
    }

    // M8 helper: poll briefly for playback, then seek to a programme start.
    private fun seekToProgramWhenReady(startMs: Long, attemptsLeft: Int) {
        if (playerEngine.isPlaying) {
            playerEngine.seekToProgramStart(startMs)
            return
        }
        if (attemptsLeft <= 0) {
            playerEngine.seekToProgramStart(startMs)
            return
        }
        mainHandler.postDelayed({ seekToProgramWhenReady(startMs, attemptsLeft - 1) }, 500)
    }

    private fun updateGuideRows() {
        var channels = Repo.inGroup(prefs.lastGroup)
        // Never show an empty guide while channels exist: if the remembered
        // group has nothing in it (catalog refresh, group renamed), fall
        // back to the full list.
        if (channels.isEmpty()) {
            val all = Repo.inGroup(Repo.ALL)
            if (all.isNotEmpty()) channels = all
        }
        guideView.rows = channels
        guideView.playingKey = playerEngine.currentItem?.key
    }

    private fun updateInfoCard(item: Item?, program: Program?) {
        if (item == null) {
            infoTitle.text = "Live TV"
            infoSub.text = "Select a channel"
            infoDesc.text = ""
            infoFav.visibility = View.GONE
            return
        }

        infoFav.visibility = if (Repo.isFav(item)) View.VISIBLE else View.GONE
        if (program != null) {
            infoTitle.text = program.title
            val fmt = SimpleDateFormat("h:mm a", Locale.US)
            val times = "${fmt.format(Date(program.start.toEpochMilli()))} – ${fmt.format(Date(program.end.toEpochMilli()))}"
            infoSub.text = "${item.number}. ${item.name} • $times"
            infoDesc.text = program.description ?: "No description available."
        } else {
            infoTitle.text = item.name
            infoSub.text = "Channel #${item.number} • ${item.groups.joinToString(", ")}"
            infoDesc.text = "No current guide information."
        }
    }

    fun playChannel(item: Item) {
        // OK on the channel that is already playing healthily is a
        // no-op, not a re-tune (audit M1).
        if (playerEngine.currentItem?.key == item.key &&
            (playerEngine.isPlaying || playerEngine.isBuffering)) {
            guideView.playingKey = item.key
            if (isFullscreen) playerOverlay.showBottomBar()
            return
        }
        android.util.Log.i("MainActivity", "playChannel: " + item.name + ", key=" + item.key + ", url=" + item.url)
        prefs.lastChannel = item.key
        guideView.playingKey = item.key
        playerEngine.play(item)
        Repo.touchRecent(item)

        if (isFullscreen) {
            playerOverlay.showBottomBar()
        }
    }

    fun enterFullscreen() {
        if (isFullscreen) return
        isFullscreen = true
        removeOverlays()
        guideLayout.visibility = View.GONE
        mainVideoView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        playerEngine.setAspectRatio(prefs.aspect, rootContainer, mainVideoView)
        fullscreenContainer.visibility = View.VISIBLE
        playerOverlay.showBottomBar()
        fullscreenContainer.requestFocus()
    }

    fun exitFullscreen() {
        if (!isFullscreen) return
        isFullscreen = false
        fullscreenContainer.visibility = View.GONE
        mainVideoView.layoutParams = FrameLayout.LayoutParams(dpi(380f), dpi(214f), Gravity.TOP or Gravity.END).apply {
            rightMargin = dpi(24f)
            topMargin = dpi(16f)
        }
        guideLayout.visibility = View.VISIBLE
        playerEngine.currentItem?.let { playing ->
            guideView.select(playing.key)
        }
        guideView.focusCol = GuideView.GuideFocus.CHANNEL
        guideView.requestFocus()
    }

    override fun onNewIntent(newIntent: android.content.Intent?) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        // Reminder tap while the Activity is already alive (audit M6).
        val key = newIntent?.getStringExtra("channelKey")
        if (!key.isNullOrBlank()) {
            Repo.byKey(key)?.let { playChannel(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        playerEngine.resumeFromLifecycle()
    }

    override fun onPause() {
        super.onPause()
        if (!isInPictureInPictureMode) {
            playerEngine.pauseFromLifecycle()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Audit Dg1: dialogs are tracked centrally; dismiss them all
        // so none leak a window past the activity.
        com.livetv.app.ui.Dialogs.dismissAll()
        Repo.listeners.remove(repoListener)
        ProgramDetailsDialog.catchUpHandler = null
        ProgramDetailsDialog.liveHandler = null
        prewarmCall?.cancel()
        prewarmRunnable?.let { prewarmHandler.removeCallbacks(it) }
        mainHandler.removeCallbacksAndMessages(null)
        playerEngine.release(terminal = true)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (prefs.pipOnHome && isFullscreen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .build()
                )
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "PiP entry failed", e)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN) {
            // A held BACK auto-repeats; acting on every repeat collapsed
            // several menu levels (or exited) from one long press.
            if (code == KeyEvent.KEYCODE_BACK && event.repeatCount > 0) return true
            // The remote's CC button toggles closed captions anywhere.
            if (code == KeyEvent.KEYCODE_CAPTIONS && event.repeatCount == 0) {
                val on = !prefs.captionsOn
                playerEngine.setCaptions(on)
                Toast.makeText(this, if (on) "Captions on" else "Captions off", Toast.LENGTH_SHORT).show()
                return true
            }
            // Fullscreen handles its own keys
            if (isFullscreen) {
                if (playerOverlay.onKey(code, event)) return true
                if (code == KeyEvent.KEYCODE_BACK) {
                    exitFullscreen()
                    return true
                }
            }

            // Settings drawer open
            if (settingsDrawer != null) {
                if (code == KeyEvent.KEYCODE_BACK) {
                    settingsDrawer?.handleBack()
                    return true
                }
            }

            // Search panel open
            if (searchPanel != null) {
                if (code == KeyEvent.KEYCODE_BACK) {
                    closeSearch()
                    return true
                }
            }

            // Settings and search own their D-pad: the drawer shortcuts
            // below used to fire underneath them and yank focus away.
            if (settingsDrawer != null || searchPanel != null) {
                return super.dispatchKeyEvent(event)
            }

            // DPAD navigation across drawers
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT && !isFullscreen) {
                if (isMainMenuDrawerOpen) {
                    closeMainMenuDrawer(focusCategories = true)
                    return true
                }
                if (isCategoriesDrawerOpen) {
                    selectCurrentCategoryAndClose()
                    return true
                }
            }

            if (code == KeyEvent.KEYCODE_DPAD_LEFT && !isFullscreen) {
                if (isCategoriesDrawerOpen && !isMainMenuDrawerOpen) {
                    guideView.rows = Repo.inGroup(prefs.lastGroup)
                    openMainMenuDrawer()
                    return true
                }
            }

            // BACK backs out ONE level at a time (v4.4.1): Main Menu ->
            // Categories -> Guide -> exit confirm. On 4.4.0, BACK with
            // the rail open jumped straight to the exit path, so two
            // presses threw the user out of the app to the Google TV
            // home screen while they were just trying to back out of
            // the menus (device-verified).
            if (code == KeyEvent.KEYCODE_BACK && !isFullscreen) {
                if (isMainMenuDrawerOpen) {
                    // Main Menu is the top level: BACK here is the only
                    // way out of the app. confirmAppExit() was never
                    // called before, so the app could not be exited with
                    // the remote. It honours the double-Back setting.
                    confirmAppExit()
                    return true
                }
                if (isCategoriesDrawerOpen) {
                    closeCategoriesDrawer(focusGuide = true)
                    return true
                }
                // If in Guide in Program column: move back to Channel column first!
                if (guideView.focusCol == GuideView.GuideFocus.PROGRAM) {
                    guideView.focusCol = GuideView.GuideFocus.CHANNEL
                    guideView.resetToNow()
                    guideView.invalidate()
                    return true
                }
                // If in Guide in Channel column: open Categories drawer!
                openCategoriesDrawer()
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun showRecordings() {
        val R_ = com.livetv.app.data.Recorder
        val saved = R_.saved(this)
        val scheduled = com.livetv.app.data.RecordingSchedule.all(prefs)
        val openRow = com.livetv.app.ui.Row("Open from Google Drive or storage",
            sub = "Play a recording saved elsewhere", onClick = { pickRecordingToPlay() })
        val fmt = java.text.SimpleDateFormat("EEE MMM d, h:mm a", java.util.Locale.US)
        val rows = saved.map { s ->
            val mb = s.sizeBytes / (1024 * 1024)
            com.livetv.app.ui.Row(
                (if (s.recording) "● " else "") + s.title,
                sub = fmt.format(java.util.Date(s.modifiedMs)) + " · ${mb} MB" + (if (s.recording) " · recording" else ""),
                onClick = {
                    val acts = mutableListOf("Play", "Save to Google Drive or storage", "Share", "Delete")
                    if (s.recording) acts.add(1, "Stop recording")
                    Dialogs.choose(this, s.title, acts, 0) { i ->
                        when (acts[i]) {
                            "Play" -> { playerEngine.playFile(s.file); enterFullscreen() }
                            "Stop recording" -> R_.activeList().firstOrNull { it.file.absolutePath == s.file.absolutePath }?.let { R_.stop(it.id) }
                            "Save to Google Drive or storage" -> exportRecording(s.file)
                            "Share" -> shareRecording(s.file)
                            "Delete" -> Dialogs.confirm(this, "Delete Recording", "Delete '${s.title}'?", "Delete") { R_.delete(this, s) }
                        }
                    }
                }
            )
        }
        val schedFmt = java.text.SimpleDateFormat("EEE MMM d, h:mm a", java.util.Locale.US)
        val schedRows = scheduled.map { r ->
            com.livetv.app.ui.Row(
                "⏰ ${r.title} — ${r.channelName}",
                sub = "Scheduled " + schedFmt.format(java.util.Date(r.startMs)),
                onClick = {
                    Dialogs.confirm(this, "Scheduled Recording", "Cancel recording '${r.title}'?", "Cancel recording") {
                        com.livetv.app.data.RecordingSchedule.cancel(this, prefs, r)
                    }
                }
            )
        }
        com.livetv.app.ui.Dialogs.menu(this, "Recordings", listOf(openRow) + schedRows + rows, 520f)
    }

    // -- Recordings <-> Google Drive / other storage ---------------------------
    // Uses the system document picker, which lists Google Drive whenever
    // the Drive app (or another cloud provider) is installed on the device.
    private var pendingExport: java.io.File? = null
    private val REQ_EXPORT = 7101
    private val REQ_OPEN = 7102

    private fun startPicker(i: android.content.Intent, req: Int) {
        try { startActivityForResult(i, req) }
        catch (e: android.content.ActivityNotFoundException) {
            Dialogs.message(this, "Not Available", "This device has no file picker. Use Share instead, or install Google Drive.")
        }
    }

    private fun exportRecording(f: java.io.File) {
        pendingExport = f
        startPicker(android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE)
            .setType("video/mp2t")
            .putExtra(android.content.Intent.EXTRA_TITLE, f.name), REQ_EXPORT)
    }

    private fun shareRecording(f: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("video/mp2t")
                .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(android.content.Intent.createChooser(send, "Save recording to"))
        } catch (e: Exception) {
            Dialogs.message(this, "Not Available", "No app on this device can receive the recording. Install Google Drive to save recordings there.")
        }
    }

    private fun pickRecordingToPlay() {
        startPicker(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE)
            .setType("video/*"), REQ_OPEN)
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) { pendingExport = null; return }
        when (requestCode) {
            REQ_EXPORT -> {
                val src = pendingExport ?: return
                pendingExport = null
                Toast.makeText(this, "Saving ${src.name}…", Toast.LENGTH_SHORT).show()
                Thread {
                    val ok = try {
                        contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out, 1 shl 16) } } != null
                    } catch (e: Exception) { false }
                    runOnUiThread {
                        Toast.makeText(this, if (ok) "Saved ${src.name}" else "Save failed", Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
            REQ_OPEN -> {
                playerEngine.playUri(uri)
                enterFullscreen()
            }
        }
    }

    private fun confirmAppExit() {
        // The "Confirm exit by double Back" setting was stored but
        // never read (audit N4): with it off, one Back exits.
        if (!prefs.confirmExit) {
            finish()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBackPress < 2500L) {
            finish()
        } else {
            lastBackPress = now
            Toast.makeText(this, "Press Back again to exit", Toast.LENGTH_SHORT).show()
        }
    }
}

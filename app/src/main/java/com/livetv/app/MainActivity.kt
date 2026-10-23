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
import android.view.SurfaceView
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
    private lateinit var mainVideoView: SurfaceView
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
        Pal.apply(prefs.theme, prefs.accent)
        Fonts.scale = when (prefs.fontSize) { "Small" -> 0.88f; "Large" -> 1.15f; else -> 1.0f }
        playerEngine = PlayerEngine(this, prefs)

        buildUI()

        // Listen for Repo updates
        Repo.listeners.add {
            updateGroups()
            updateGuideRows()
        }

        // Initial Data Load
        Repo.reload {
            val curGroup = prefs.lastGroup.ifBlank { "All channels" }
            var candidateList = Repo.inGroup(curGroup).filter { !it.groups.contains("International") }
            if (candidateList.isEmpty()) {
                candidateList = Repo.visibleItems().filter { !it.groups.contains("International") }
                prefs.lastGroup = "All channels"
            }

            var startItem = candidateList.firstOrNull { it.key == prefs.lastChannel }
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
                if (prefs.resumeLast) {
                    playChannel(startItem)
                }
                guideView.select(startItem.key)
            }
            guideView.focusCol = GuideView.GuideFocus.CHANNEL
            guideView.requestFocus()
        }
    }

    private fun buildUI() {
        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Pal.bg)
        }

        // 1. Guide Layout (Base: top info + preview placeholder, bottom guide grid)
        setupGuideLayout()
        rootContainer.addView(guideLayout, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // 2. Single Video View for both Preview & Fullscreen (Top-Right preview in Guide mode)
        mainVideoView = SurfaceView(this).apply {
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(dpi(380f), dpi(214f), Gravity.TOP or Gravity.END).apply {
                rightMargin = dpi(24f)
                topMargin = dpi(16f)
            }
            if (Build.VERSION.SDK_INT >= 34) {
                setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
            }
            setZOrderMediaOverlay(false)
            setOnClickListener {
                if (playerEngine.currentItem != null) {
                    enterFullscreen()
                }
            }
        }
        rootContainer.addView(mainVideoView)
        playerEngine.attachSurface(mainVideoView)

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
            setBackgroundColor(Pal.bg)
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
            }
        }
        guideContainer.addView(guideView)
    }

    private fun setupCategoriesDrawer() {
        categoriesDrawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(dpi(360f), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START)
            setBackgroundColor(Pal.panel)
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
                                confirmAppExit()
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
        mainMenuDrawer.addView(makeMenuItem("Recordings", R.drawable.ic_dvr) {
            closeMainMenuDrawer(false)
            Dialogs.message(this, "Recordings", "No recordings stored. You can schedule recordings from any upcoming program.")
        })
        mainMenuDrawer.addView(makeMenuItem("My list", R.drawable.ic_bookmark) {
            closeMainMenuDrawer(false)
            Dialogs.message(this, "My List", "Add channels to Favorites or schedule reminders to build your list.")
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
            }
        )
        fullscreenContainer.addView(playerOverlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    fun openCategoriesDrawer() {
        if (isCategoriesDrawerOpen) return
        isCategoriesDrawerOpen = true
        guideView.isFocusable = false
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
                mainMenuDrawer.getChildAt(2)?.requestFocus() ?: mainMenuDrawer.getChildAt(1)?.requestFocus()
            }
            .start()
        mainMenuDrawer.getChildAt(2)?.requestFocus() ?: mainMenuDrawer.getChildAt(1)?.requestFocus()
    }

    fun closeMainMenuDrawer(focusCategories: Boolean = true) {
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
            settingsDrawer = SettingsDrawer(this, prefs) {
                closeSettings()
            }
            val lp = FrameLayout.LayoutParams(dpi(440f), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END)
            rootContainer.addView(settingsDrawer, lp)
        }
        settingsDrawer?.apply {
            visibility = View.VISIBLE
            translationX = dp(440f)
            animate().translationX(0f).setDuration(200).setListener(null).start()
            requestFocus()
        }
    }

    private fun closeSettings() {
        settingsDrawer?.let { drawer ->
            drawer.animate().translationX(dp(440f)).setDuration(200).withEndAction {
                rootContainer.removeView(drawer)
                settingsDrawer = null
                guideView.requestFocus()
            }.start()
        }
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
        searchPanel?.apply {
            visibility = View.VISIBLE
            focusInput()
        }
    }

    private fun closeSearch() {
        searchPanel?.let { panel ->
            rootContainer.removeView(panel)
            searchPanel = null
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
                    closeCategoriesDrawer(focusGuide = true)
                }
            )
        }
        groupsList.submit(rows)
    }

    private fun updateGuideRows() {
        val channels = Repo.inGroup(prefs.lastGroup)
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

    override fun onResume() {
        super.onResume()
        playerEngine.resume()
    }

    override fun onPause() {
        super.onPause()
        if (!isInPictureInPictureMode) {
            playerEngine.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        playerEngine.release()
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
            } catch (e: Exception) {}
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN) {
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

            // Guide Layout navigation hierarchy on BACK (TiviMate 1:1 order: Fullscreen -> Guide -> Categories -> Main Menu -> Exit)
            if (code == KeyEvent.KEYCODE_BACK && !isFullscreen) {
                if (isMainMenuDrawerOpen) {
                    confirmAppExit()
                    return true
                }
                if (isCategoriesDrawerOpen) {
                    openMainMenuDrawer()
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

    private fun confirmAppExit() {
        val now = System.currentTimeMillis()
        if (now - lastBackPress < 2500L) {
            finish()
        } else {
            lastBackPress = now
            Toast.makeText(this, "Press Back again to exit", Toast.LENGTH_SHORT).show()
        }
    }
}

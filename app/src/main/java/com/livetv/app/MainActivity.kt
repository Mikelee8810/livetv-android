package com.livetv.app

import android.app.Activity
import android.app.PictureInPictureParams
import android.graphics.Color
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
    private lateinit var guideLayout: LinearLayout
    private lateinit var leftRail: LinearLayout
    private lateinit var groupsContainer: LinearLayout
    private lateinit var groupsList: ListPanel
    private lateinit var guideContainer: LinearLayout
    private lateinit var guideView: GuideView

    // Preview / Info header in Guide
    private lateinit var previewContainer: FrameLayout
    private lateinit var previewTexture: TextureView
    private lateinit var infoContainer: LinearLayout
    private lateinit var infoTitle: TextView
    private lateinit var infoSub: TextView
    private lateinit var infoDesc: TextView
    private lateinit var infoFav: ImageView

    // Fullscreen elements
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var fullscreenSurface: SurfaceView
    private lateinit var playerOverlay: PlayerOverlay

    // Overlay panels
    private var settingsDrawer: SettingsDrawer? = null
    private var searchPanel: SearchPanel? = null
    private var isFullscreen = false
    private var currentTab = "TV"

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
            val lastKey = prefs.lastChannel
            var startItem = Repo.visibleItems().firstOrNull { it.key == lastKey }
            if (startItem == null || startItem.groups.contains("International") || startItem.name.contains("(India)")) {
                startItem = Repo.visibleItems().firstOrNull { !it.groups.contains("International") } ?: Repo.visibleItems().firstOrNull()
            }
            if (startItem != null) {
                if (prefs.resumeLast) {
                    playChannel(startItem)
                }
                guideView.select(startItem.key)
            }
        }
    }

    private fun buildUI() {
        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Pal.bg)
        }

        // 1. Guide Layout (Left Rail + Groups Panel + Guide Content)
        setupGuideLayout()
        rootContainer.addView(guideLayout, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // 2. Fullscreen Container
        setupFullscreenLayout()
        rootContainer.addView(fullscreenContainer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        setContentView(rootContainer)
    }

    private fun setupGuideLayout() {
        guideLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }

        // 1.1 Left Nav Rail
        setupLeftRail()
        guideLayout.addView(leftRail)

        // 1.2 Groups Panel
        setupGroupsPanel()
        guideLayout.addView(groupsContainer)

        // 1.3 Main Guide Area (Preview + Selection info on top, GuideView on bottom)
        setupGuideContent()
        guideLayout.addView(guideContainer)
    }

    private fun setupLeftRail() {
        leftRail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dpi(72f), LinearLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Pal.rail)
            setPadding(0, dpi(20f), 0, dpi(20f))
        }

        // Brand logo badge top
        val brandLogo = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpi(36f), dpi(36f)).also {
                it.bottomMargin = dpi(30f)
            }
            setImageResource(R.drawable.ic_tv)
            setColorFilter(Pal.accent)
        }
        leftRail.addView(brandLogo)

        fun makeRailButton(id: String, iconRes: Int): ImageView {
            return ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpi(48f), dpi(48f)).also {
                    it.bottomMargin = dpi(16f)
                }
                setImageResource(iconRes)
                setColorFilter(if (id == currentTab) Pal.focusText else Pal.textDim)
                isFocusable = true
                isClickable = true
                setPadding(dpi(12f), dpi(12f), dpi(12f), dpi(12f))
                background = if (id == currentTab) GradientDrawable().apply {
                    setColor(Pal.focusFill)
                    cornerRadius = dp(24f)
                } else null

                setOnFocusChangeListener { _, f ->
                    background = when {
                        f -> GradientDrawable().apply { setColor(Pal.focusFill); cornerRadius = dp(24f) }
                        id == currentTab -> GradientDrawable().apply { setColor(Pal.cardHi); cornerRadius = dp(24f) }
                        else -> null
                    }
                    setColorFilter(when {
                        f -> Pal.focusText
                        id == currentTab -> Pal.accent
                        else -> Pal.textDim
                    })
                }

                setOnClickListener {
                    onRailTabSelected(id)
                }

                setOnKeyListener { _, code, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        groupsList.focusRow(0)
                        true
                    } else false
                }
            }
        }

        leftRail.addView(makeRailButton("Search", R.drawable.ic_search))
        leftRail.addView(makeRailButton("TV", R.drawable.ic_tv))
        leftRail.addView(makeRailButton("Recordings", R.drawable.ic_dvr))
        leftRail.addView(makeRailButton("My list", R.drawable.ic_bookmark))

        // Spacer to push Settings to bottom
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, 1f)
        }
        leftRail.addView(spacer)

        leftRail.addView(makeRailButton("Settings", R.drawable.ic_settings))
    }

    private fun setupGroupsPanel() {
        groupsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dpi(260f), LinearLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Pal.panel)
            setPadding(dpi(16f), dpi(24f), dpi(16f), dpi(16f))
        }

        val title = TextView(this).apply {
            text = "Categories"
            textSize = 20f * Fonts.scale
            typeface = Fonts.medium
            setTextColor(Pal.text)
            setPadding(dpi(8f), 0, 0, dpi(12f))
        }
        groupsContainer.addView(title)

        groupsList = ListPanel(this).apply {
            rowHeightDp = 44f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            onEdge = { code ->
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        leftRail.getChildAt(1)?.requestFocus() // focus TV tab
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        guideView.requestFocus()
                        true
                    }
                    else -> false
                }
            }
        }
        groupsContainer.addView(groupsList)
    }

    private fun setupGuideContent() {
        guideContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setBackgroundColor(Pal.bg)
        }

        // Top Area: Preview Video (Left) + Selected Program Info (Right)
        val topArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(240f))
            setPadding(dpi(24f), dpi(16f), dpi(24f), dpi(8f))
        }

        // Preview Video Container
        previewContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpi(380f), dpi(214f))
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(8f)
            }
            clipToOutline = true
        }

        previewTexture = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        previewContainer.addView(previewTexture)
        topArea.addView(previewContainer)

        playerEngine.attachTextureView(previewTexture)

        // Program Info Container
        infoContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).also {
                it.leftMargin = dpi(24f)
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
        guideContainer.addView(topArea)

        // Bottom Area: Guide View
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
            onEdgeLeft = {
                groupsList.focusRow(0)
            }
            onSelection = { item, prog ->
                updateInfoCard(item, prog)
            }
        }
        guideContainer.addView(guideView)
    }

    private fun setupFullscreenLayout() {
        fullscreenContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }

        fullscreenSurface = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        fullscreenContainer.addView(fullscreenSurface)

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

    private fun onRailTabSelected(tab: String) {
        currentTab = tab
        when (tab) {
            "TV" -> {
                removeOverlays()
                guideLayout.visibility = View.VISIBLE
                guideView.requestFocus()
            }
            "Settings" -> {
                showSettings()
            }
            "Search" -> {
                showSearch()
            }
            "Recordings" -> {
                removeOverlays()
                Dialogs.message(this, "Recordings", "No recordings stored. You can schedule recordings from any upcoming program.")
            }
            "My list" -> {
                removeOverlays()
                Dialogs.message(this, "My List", "Add channels to Favorites or schedule reminders to build your list.")
            }
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
                    guideView.requestFocus()
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
        prefs.lastChannel = item.key
        guideView.playingKey = item.key
        if (isFullscreen) {
            playerEngine.attachSurface(fullscreenSurface)
        } else {
            playerEngine.attachTextureView(previewTexture)
        }
        playerEngine.play(item)
        Repo.touchRecent(item)

        if (isFullscreen) {
            playerOverlay.showBottomBar()
        }
    }

    fun enterFullscreen() {
        if (isFullscreen) return
        isFullscreen = true
        guideLayout.visibility = View.GONE
        fullscreenContainer.visibility = View.VISIBLE
        playerEngine.attachSurface(fullscreenSurface)
        playerOverlay.showBottomBar()
        fullscreenContainer.requestFocus()
    }

    fun exitFullscreen() {
        if (!isFullscreen) return
        isFullscreen = false
        fullscreenContainer.visibility = View.GONE
        guideLayout.visibility = View.VISIBLE
        playerEngine.attachTextureView(previewTexture)
        guideView.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        if (isFullscreen) {
            playerEngine.attachSurface(fullscreenSurface)
        } else {
            playerEngine.attachTextureView(previewTexture)
        }
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

    override fun onKeyDown(code: Int, event: KeyEvent): Boolean {
        // Fullscreen handles its own keys
        if (isFullscreen) {
            if (playerOverlay.onKey(code, event)) return true
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

        // Double press back to exit
        if (code == KeyEvent.KEYCODE_BACK && !isFullscreen && settingsDrawer == null && searchPanel == null) {
            if (prefs.confirmExit) {
                val now = System.currentTimeMillis()
                if (now - lastBackPress < 2500L) {
                    finish()
                } else {
                    lastBackPress = now
                    Toast.makeText(this, "Press Back again to exit", Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }

        return super.onKeyDown(code, event)
    }
}

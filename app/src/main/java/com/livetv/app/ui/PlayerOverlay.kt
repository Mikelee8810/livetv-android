package com.livetv.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.livetv.app.R
import com.livetv.app.data.Item
import com.livetv.app.data.Prefs
import com.livetv.app.data.Repo
import com.livetv.app.player.PlayerEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlayerOverlay(
    private val activity: Activity,
    private val prefs: Prefs,
    private val playerEngine: PlayerEngine,
    private val onSwitchChannel: (Item) -> Unit,
    private val onExitFullscreen: () -> Unit,
    private val onOpenMenu: (Item) -> Unit
) : FrameLayout(activity) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.US)

    // Sub-components
    private val bottomBar = LinearLayout(activity)
    private val quickListContainer = FrameLayout(activity)
    private val quickList = ListPanel(activity)
    private val numberZapView = TextView(activity)
    private var numberBuffer = ""

    // Bottom bar views
    private val channelLogo = ImageView(activity)
    private val channelInitials = TextView(activity)
    private val channelNum = TextView(activity)
    private val channelName = TextView(activity)
    private val favIcon = ImageView(activity)
    private val progTitle = TextView(activity)
    private val progTime = TextView(activity)
    private val progDuration = TextView(activity)
    private val progBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
    private val nextProg = TextView(activity)
    private val videoQualityBadge = TextView(activity)
    private val audioBadge = TextView(activity)
    private val aspectBadge = TextView(activity)

    // Action buttons bar
    private val actionsBar = LinearLayout(activity)

    var isBottomBarVisible = false; private set
    var isQuickListVisible = false; private set

    private val hideBottomRunnable = Runnable { hideBottomBar() }

    init {
        // 1. Quick Channel List (Left slide panel)
        setupQuickList()

        // 2. Bottom Info Bar
        setupBottomBar()

        // 3. Number Zap badge (Top-Right)
        setupNumberZap()

        isFocusable = true
    }

    private fun setupQuickList() {
        quickListContainer.apply {
            layoutParams = LayoutParams(activity.dpi(360f), LayoutParams.MATCH_PARENT, Gravity.START)
            background = GradientDrawable().apply {
                setColor(Color.argb(225, 15, 21, 29))
            }
            visibility = View.GONE
            setPadding(activity.dpi(14f), activity.dpi(16f), activity.dpi(14f), activity.dpi(16f))
        }

        quickList.apply {
            rowHeightDp = 48f
            layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            onEdge = { code ->
                if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    hideQuickList()
                    true
                } else false
            }
        }
        quickListContainer.addView(quickList)
        addView(quickListContainer)
    }

    private fun setupBottomBar() {
        bottomBar.apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            background = GradientDrawable().apply {
                colors = intArrayOf(Color.TRANSPARENT, Color.argb(220, 6, 9, 14), Color.argb(250, 6, 9, 14))
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
            }
            setPadding(activity.dpi(36f), activity.dpi(36f), activity.dpi(36f), activity.dpi(24f))
            visibility = View.GONE
        }

        // Top row of bottom bar: Logo + Channel info + Quality Badges
        val row1 = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        channelLogo.apply {
            layoutParams = LinearLayout.LayoutParams(activity.dpi(64f), activity.dpi(44f)).also {
                it.rightMargin = activity.dpi(16f)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        row1.addView(channelLogo)

        channelInitials.apply {
            layoutParams = LinearLayout.LayoutParams(activity.dpi(64f), activity.dpi(44f)).also {
                it.rightMargin = activity.dpi(16f)
            }
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = Fonts.medium
            setTextColor(Pal.textDim)
            background = GradientDrawable().apply {
                setColor(Pal.card)
                cornerRadius = activity.dp(6f)
            }
            visibility = View.GONE
        }
        row1.addView(channelInitials)

        channelNum.apply {
            textSize = 26f * Fonts.scale
            typeface = Fonts.medium
            setTextColor(Pal.accent)
            setPadding(0, 0, activity.dpi(12f), 0)
        }
        row1.addView(channelNum)

        channelName.apply {
            textSize = 22f * Fonts.scale
            typeface = Fonts.medium
            setTextColor(Pal.text)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        row1.addView(channelName)

        favIcon.apply {
            layoutParams = LinearLayout.LayoutParams(activity.dpi(24f), activity.dpi(24f)).also {
                it.rightMargin = activity.dpi(16f)
            }
            setImageResource(R.drawable.ic_star)
            setColorFilter(Pal.accent2)
        }
        row1.addView(favIcon)

        // Quality and aspect badges
        videoQualityBadge.apply {
            textSize = 12f
            typeface = Fonts.medium
            setTextColor(Pal.textDim)
            setPadding(activity.dpi(8f), activity.dpi(3f), activity.dpi(8f), activity.dpi(3f))
            background = GradientDrawable().apply {
                setColor(Pal.card)
                cornerRadius = activity.dp(4f)
            }
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                it.rightMargin = activity.dpi(8f)
            }
        }
        row1.addView(videoQualityBadge)

        aspectBadge.apply {
            text = prefs.aspect
            textSize = 12f
            typeface = Fonts.medium
            setTextColor(Pal.textDim)
            setPadding(activity.dpi(8f), activity.dpi(3f), activity.dpi(8f), activity.dpi(3f))
            background = GradientDrawable().apply {
                setColor(Pal.card)
                cornerRadius = activity.dp(4f)
            }
        }
        row1.addView(aspectBadge)
        bottomBar.addView(row1)

        // Middle row: Current program + time
        val row2 = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, activity.dpi(8f), 0, activity.dpi(4f))
        }

        progTitle.apply {
            textSize = 20f * Fonts.scale
            typeface = Fonts.medium
            setTextColor(Pal.text)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        row2.addView(progTitle)

        progTime.apply {
            textSize = 15f
            typeface = Fonts.regular
            setTextColor(Pal.textDim)
            setPadding(activity.dpi(12f), 0, activity.dpi(12f), 0)
        }
        row2.addView(progTime)

        progDuration.apply {
            textSize = 15f
            typeface = Fonts.medium
            setTextColor(Pal.accent)
        }
        row2.addView(progDuration)
        bottomBar.addView(row2)

        // Progress bar
        progBar.apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, activity.dpi(4f)).also {
                it.topMargin = activity.dpi(4f)
                it.bottomMargin = activity.dpi(8f)
            }
            progressDrawable = GradientDrawable().apply {
                setColor(Pal.card)
                cornerRadius = activity.dp(2f)
            }
            progress = 35
        }
        bottomBar.addView(progBar)

        // Next program row
        nextProg.apply {
            textSize = 14f
            typeface = Fonts.regular
            setTextColor(Pal.textDim)
            setPadding(0, 0, 0, activity.dpi(12f))
        }
        bottomBar.addView(nextProg)

        // Bottom Actions Row (CC, Audio, Aspect, Sleep, Favorite, Menu)
        setupActionsBar()
        bottomBar.addView(actionsBar)

        addView(bottomBar)
    }

    private fun setupActionsBar() {
        actionsBar.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, activity.dpi(6f), 0, 0)
        }

        fun makeActionButton(label: String, iconRes: Int, onClick: () -> Unit): LinearLayout {
            val btn = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                setPadding(activity.dpi(12f), activity.dpi(6f), activity.dpi(14f), activity.dpi(6f))
                background = GradientDrawable().apply {
                    setColor(Pal.card)
                    cornerRadius = activity.dp(6f)
                }
                setOnFocusChangeListener { _, f ->
                    background = GradientDrawable().apply {
                        setColor(if (f) Pal.focusFill else Pal.card)
                        cornerRadius = activity.dp(6f)
                    }
                    (getChildAt(0) as ImageView).setColorFilter(if (f) Pal.focusText else Pal.text)
                    (getChildAt(1) as TextView).setTextColor(if (f) Pal.focusText else Pal.text)
                }
                setOnClickListener { onClick() }
            }

            val ic = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(activity.dpi(18f), activity.dpi(18f)).also {
                    it.rightMargin = activity.dpi(8f)
                }
                setImageResource(iconRes)
                setColorFilter(Pal.text)
            }
            btn.addView(ic)

            val tv = TextView(activity).apply {
                text = label
                textSize = 14f
                typeface = Fonts.medium
                setTextColor(Pal.text)
            }
            btn.addView(tv)

            val lp = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                it.rightMargin = activity.dpi(10f)
            }
            btn.layoutParams = lp
            return btn
        }

        // Subtitles (CC)
        actionsBar.addView(makeActionButton("Subtitles", R.drawable.ic_tv) {
            showSubtitlesDialog()
        })

        // Audio
        actionsBar.addView(makeActionButton("Audio", R.drawable.ic_tv) {
            showAudioTracksDialog()
        })

        // Aspect
        actionsBar.addView(makeActionButton("Aspect", R.drawable.ic_tv) {
            cycleAspectRatio()
        })

        // Favorite
        actionsBar.addView(makeActionButton("Favorite", R.drawable.ic_star) {
            playerEngine.currentItem?.let { item ->
                Repo.toggleFav(item)
                updateContent()
            }
        })

        // Channel Options
        actionsBar.addView(makeActionButton("Options", R.drawable.ic_settings) {
            playerEngine.currentItem?.let { onOpenMenu(it) }
        })
    }

    private fun setupNumberZap() {
        numberZapView.apply {
            textSize = 34f
            typeface = Fonts.medium
            setTextColor(Pal.accent)
            background = GradientDrawable().apply {
                setColor(Color.argb(220, 15, 21, 29))
                cornerRadius = activity.dp(8f)
            }
            setPadding(activity.dpi(20f), activity.dpi(8f), activity.dpi(20f), activity.dpi(8f))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).also {
                it.topMargin = activity.dpi(36f)
                it.rightMargin = activity.dpi(36f)
            }
            visibility = View.GONE
        }
        addView(numberZapView)
    }

    fun showBottomBar() {
        mainHandler.removeCallbacks(hideBottomRunnable)
        updateContent()
        if (!isBottomBarVisible) {
            bottomBar.visibility = View.VISIBLE
            bottomBar.alpha = 0f
            bottomBar.animate().alpha(1f).setDuration(160).setListener(null).start()
            isBottomBarVisible = true
        }
        val timeout = prefs.playerInfoSeconds * 1000L
        if (timeout > 0) {
            mainHandler.postDelayed(hideBottomRunnable, timeout)
        }
    }

    fun hideBottomBar() {
        mainHandler.removeCallbacks(hideBottomRunnable)
        if (isBottomBarVisible) {
            bottomBar.animate().alpha(0f).setDuration(160).setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bottomBar.visibility = View.GONE
                    isBottomBarVisible = false
                }
            }).start()
        }
    }

    fun toggleBottomBar() {
        if (isBottomBarVisible) hideBottomBar() else showBottomBar()
    }

    fun showQuickList() {
        hideBottomBar()
        updateQuickList()
        quickListContainer.visibility = View.VISIBLE
        quickListContainer.translationX = -activity.dp(360f)
        quickListContainer.animate().translationX(0f).setDuration(180).setListener(null).start()
        isQuickListVisible = true
        quickList.post {
            val cur = playerEngine.currentItem
            val idx = Repo.visibleItems().indexOfFirst { it.key == cur?.key }.coerceAtLeast(0)
            quickList.focusRow(idx)
        }
    }

    fun hideQuickList() {
        if (isQuickListVisible) {
            quickListContainer.animate().translationX(-activity.dp(360f)).setDuration(180).setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    quickListContainer.visibility = View.GONE
                    isQuickListVisible = false
                    requestFocus()
                }
            }).start()
        }
    }

    private fun updateQuickList() {
        val items = Repo.visibleItems()
        val curKey = playerEngine.currentItem?.key
        val rows = items.map { item ->
            val curProg = Repo.current(item)
            Row(
                title = "${item.number}. ${item.name}",
                sub = curProg?.title ?: "No information",
                selected = item.key == curKey,
                onClick = {
                    onSwitchChannel(item)
                    updateQuickList()
                }
            )
        }
        quickList.submit(rows)
    }

    fun updateContent() {
        val item = playerEngine.currentItem ?: return
        channelNum.text = item.number.toString()
        channelName.text = item.name

        // Logo
        if (prefs.guideLogos && item.ch.logo.isNotBlank()) {
            val bmp = Logos.get(activity, item.ch.logo) { updateContent() }
            if (bmp != null) {
                channelLogo.setImageBitmap(bmp)
                channelLogo.visibility = View.VISIBLE
                channelInitials.visibility = View.GONE
            } else {
                channelLogo.visibility = View.GONE
                channelInitials.text = item.name.take(2).uppercase()
                channelInitials.visibility = View.VISIBLE
            }
        } else {
            channelLogo.visibility = View.GONE
            channelInitials.text = item.name.take(2).uppercase()
            channelInitials.visibility = View.VISIBLE
        }

        // Fav
        favIcon.visibility = if (Repo.isFav(item)) View.VISIBLE else View.GONE

        // Quality
        val w = playerEngine.videoWidth
        val h = playerEngine.videoHeight
        videoQualityBadge.text = when {
            h >= 2160 -> "4K"
            h >= 1080 -> "FHD"
            h >= 720 -> "HD"
            h > 0 -> "SD"
            else -> "LIVE"
        }
        aspectBadge.text = prefs.aspect

        // Current Program
        val now = System.currentTimeMillis()
        val curProg = Repo.current(item)
        if (curProg != null) {
            progTitle.text = curProg.title
            val st = curProg.start.toEpochMilli()
            val en = curProg.end.toEpochMilli()
            progTime.text = "${timeFmt.format(Date(st))} – ${timeFmt.format(Date(en))}"
            val totalMin = ((en - st) / 60_000L).coerceAtLeast(1L)
            val elapsedMin = ((now - st) / 60_000L).coerceAtLeast(0L)
            val remainMin = (totalMin - elapsedMin).coerceAtLeast(0L)
            progDuration.text = "$remainMin min left"

            progBar.visibility = View.VISIBLE
            progBar.max = totalMin.toInt()
            progBar.progress = elapsedMin.toInt()
        } else {
            progTitle.text = "No program information"
            progTime.text = ""
            progDuration.text = ""
            progBar.visibility = View.GONE
        }

        // Next Program
        val next = Repo.next(item)
        if (next != null && prefs.playerNextProgram) {
            nextProg.text = "NEXT: ${next.title} (${timeFmt.format(Date(next.start.toEpochMilli()))})"
            nextProg.visibility = View.VISIBLE
        } else {
            nextProg.visibility = View.GONE
        }
    }

    private fun cycleAspectRatio() {
        val modes = listOf("Fit", "Fill", "Zoom", "16:9", "4:3")
        val nextIdx = (modes.indexOf(prefs.aspect) + 1) % modes.size
        prefs.aspect = modes[nextIdx]
        aspectBadge.text = prefs.aspect
        updateContent()
        showBottomBar()
    }

    private fun showSubtitlesDialog() {
        val tracks = playerEngine.getSubtitleTracks()
        if (tracks.isEmpty()) {
            Dialogs.message(activity, "Subtitles", "No subtitle tracks available for this stream.")
            return
        }
        val options = listOf("Off") + tracks.map { it.label }
        val selIdx = tracks.indexOfFirst { it.isSelected }.let { if (it >= 0) it + 1 else 0 }
        Dialogs.choose(activity, "Subtitle Tracks", options, selIdx) { picked ->
            if (picked == 0) playerEngine.selectSubtitleTrack(null)
            else playerEngine.selectSubtitleTrack(tracks[picked - 1])
        }
    }

    private fun showAudioTracksDialog() {
        val tracks = playerEngine.getAudioTracks()
        if (tracks.isEmpty()) {
            Dialogs.message(activity, "Audio", "Default stereo audio.")
            return
        }
        val options = tracks.map { it.label }
        val selIdx = tracks.indexOfFirst { it.isSelected }.coerceAtLeast(0)
        Dialogs.choose(activity, "Audio Tracks", options, selIdx) { picked ->
            playerEngine.selectAudioTrack(tracks[picked])
        }
    }

    // Number Zap
    private val zapRunnable = Runnable {
        val num = numberBuffer.toIntOrNull()
        numberBuffer = ""
        numberZapView.visibility = View.GONE
        if (num != null) {
            val item = Repo.visibleItems().firstOrNull { it.number == num }
            if (item != null) onSwitchChannel(item)
        }
    }

    fun handleNumber(digit: Int) {
        mainHandler.removeCallbacks(zapRunnable)
        numberBuffer += digit.toString()
        numberZapView.text = numberBuffer
        numberZapView.visibility = View.VISIBLE
        mainHandler.postDelayed(zapRunnable, 1600L)
    }

    fun onKey(code: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false

        // Handle number keys (0..9)
        if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            handleNumber(code - KeyEvent.KEYCODE_0)
            return true
        }

        // If quick list is open, let it handle
        if (isQuickListVisible) {
            if (code == KeyEvent.KEYCODE_BACK) {
                hideQuickList()
                return true
            }
            return false
        }

        // If bottom bar is open
        if (isBottomBarVisible) {
            if (code == KeyEvent.KEYCODE_BACK) {
                hideBottomBar()
                return true
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Focus actions bar
                actionsBar.getChildAt(0)?.requestFocus()
                return true
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                hideBottomBar()
                return true
            }
        }

        // Normal Fullscreen controls based on Remote Settings
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                when (prefs.playerOk) {
                    "Channel list" -> showQuickList()
                    "Pause" -> {
                        if (playerEngine.isPlaying) playerEngine.pause() else playerEngine.resume()
                        showBottomBar()
                    }
                    else -> toggleBottomBar()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                when (prefs.playerUpDown) {
                    "Switch channel" -> switchChannelDelta(1)
                    "Show channel list" -> showQuickList()
                    else -> showBottomBar()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                when (prefs.playerUpDown) {
                    "Switch channel" -> switchChannelDelta(-1)
                    "Show channel list" -> showQuickList()
                    else -> showBottomBar()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                when (prefs.playerLeft) {
                    "Channel list" -> showQuickList()
                    "Rewind 10s" -> {
                        playerEngine.seekRelative(-10)
                        showBottomBar()
                    }
                    else -> showBottomBar()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                when (prefs.playerRight) {
                    "Program info" -> showBottomBar()
                    "Fast forward 10s" -> {
                        playerEngine.seekRelative(10)
                        showBottomBar()
                    }
                    else -> showQuickList()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (playerEngine.isPlaying) playerEngine.pause() else playerEngine.resume()
                showBottomBar()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                playerEngine.seekRelative(10)
                showBottomBar()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                playerEngine.seekRelative(-10)
                showBottomBar()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                playerEngine.currentItem?.let { onOpenMenu(it) }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                onExitFullscreen()
                return true
            }
        }
        return false
    }

    private fun switchChannelDelta(delta: Int) {
        val items = Repo.visibleItems()
        if (items.isEmpty()) return
        val cur = playerEngine.currentItem
        val idx = items.indexOfFirst { it.key == cur?.key }
        var next = if (idx >= 0) idx + delta else 0
        if (prefs.channelWrap) {
            next = (next + items.size) % items.size
        } else {
            next = next.coerceIn(0, items.size - 1)
        }
        onSwitchChannel(items[next])
        showBottomBar()
    }
}

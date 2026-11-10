package com.livetv.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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
    private val onOpenMenu: (Item) -> Unit,
    private val onAspectChanged: (() -> Unit)? = null
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
    // Audit P8: with no auto-hide (playerInfoSeconds == 0) the info
    // bar never refreshed; this keeps it live while visible.
    private val infoRefreshRunnable = object : Runnable {
        override fun run() {
            if (isBottomBarVisible) {
                updateContent()
                mainHandler.postDelayed(this, 30_000L)
            }
        }
    }
    private var lastZapMs = 0L

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
                setColor(Color.argb(228, 13, 20, 30))
                cornerRadii = floatArrayOf(0f, 0f, activity.dp(18f), activity.dp(18f), activity.dp(18f), activity.dp(18f), 0f, 0f)
                setStroke(activity.dpi(1f), Pal.cardEdge)
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
                cornerRadius = activity.dp(10f)
                setStroke(activity.dpi(1f), Pal.cardEdge)
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
            // Audit P5: a plain GradientDrawable as progressDrawable
            // fills the whole bar regardless of progress. Layer a
            // clipped accent fill over the track instead.
            val track = GradientDrawable().apply {
                setColor(Pal.card)
                cornerRadius = activity.dp(2f)
            }
            val fill = GradientDrawable().apply {
                setColor(Pal.accent)
                cornerRadius = activity.dp(2f)
            }
            val clipped = ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)
            progressDrawable = LayerDrawable(arrayOf(track, clipped)).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
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

        // Record: until the current programme ends, or a fixed length.
        actionsBar.addView(makeActionButton("Record", R.drawable.ic_tv) {
            playerEngine.currentItem?.let { item -> showRecordDialog(item) }
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
        // Audit P2: cancel a fade-out already in flight, or its end
        // listener hides the bar we are trying to show.
        bottomBar.animate().cancel()
        updateContent()
        if (!isBottomBarVisible) {
            bottomBar.visibility = View.VISIBLE
            bottomBar.alpha = 0f
            bottomBar.animate().alpha(1f).setDuration(160).setListener(null).start()
            isBottomBarVisible = true
        } else {
            bottomBar.alpha = 1f
        }
        val timeout = prefs.playerInfoSeconds * 1000L
        if (timeout > 0) {
            mainHandler.postDelayed(hideBottomRunnable, timeout)
        } else {
            // No auto-hide: refresh the info periodically instead.
            mainHandler.removeCallbacks(infoRefreshRunnable)
            mainHandler.postDelayed(infoRefreshRunnable, 30_000L)
        }
    }

    fun hideBottomBar() {
        mainHandler.removeCallbacks(hideBottomRunnable)
        mainHandler.removeCallbacks(infoRefreshRunnable)
        if (isBottomBarVisible) {
            bottomBar.animate().alpha(0f).setDuration(160).setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bottomBar.visibility = View.GONE
                    isBottomBarVisible = false
                    // Audit P1: do not steal focus back when the
                    // quick list is (or is becoming) visible.
                    if (!isQuickListVisible) requestFocus()
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
        onAspectChanged?.invoke()
        updateContent()
        showBottomBar()
    }

    private fun showRecordDialog(item: Item) {
        val running = com.livetv.app.data.Recorder.activeList().firstOrNull { it.channelName == item.name }
        if (running != null) {
            Dialogs.confirm(activity, "Recording", "Stop recording ${item.name}?", "Stop") {
                com.livetv.app.data.Recorder.stop(running.id)
            }
            return
        }
        val now = System.currentTimeMillis()
        val prog = Repo.current(item)
        val opts = mutableListOf<Pair<String, Long>>()
        if (prog != null) opts.add("Until '${prog.title}' ends" to prog.end.toEpochMilli())
        opts.add("30 minutes" to now + 30 * 60_000L)
        opts.add("1 hour" to now + 60 * 60_000L)
        opts.add("2 hours" to now + 120 * 60_000L)
        opts.add("3 hours" to now + 180 * 60_000L)
        Dialogs.choose(activity, "Record ${item.name}", opts.map { it.first }, 0) { picked ->
            val (_, end) = opts[picked]
            val rec = com.livetv.app.data.Recorder.start(activity, item, prog?.title ?: "Recording", end)
            android.widget.Toast.makeText(activity,
                if (rec != null) "Recording ${item.name} (Main Menu, Recordings)" else "Already recording ${item.name}",
                android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSubtitlesDialog() {
        val tracks = playerEngine.getSubtitleTracks()
        if (tracks.isEmpty()) {
            // Captions are often only detected once they start, so a
            // stream with none yet can still turn them on for later.
            val on = prefs.captionsOn
            Dialogs.choose(activity, "Closed Captions", listOf("Off", "On"), if (on) 1 else 0) { picked ->
                playerEngine.setCaptions(picked == 1)
            }
            return
        }
        val options = listOf("Off") + tracks.map { it.label }
        val selIdx = tracks.indexOfFirst { it.isSelected }.let { if (it >= 0) it + 1 else 0 }
        Dialogs.choose(activity, "Closed Captions", options, selIdx) { picked ->
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

    // Audit P7: no callbacks or animations outlive the view.
    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacks(hideBottomRunnable)
        mainHandler.removeCallbacks(infoRefreshRunnable)
        mainHandler.removeCallbacks(zapRunnable)
        bottomBar.animate().cancel()
        quickListContainer.animate().cancel()
        super.onDetachedFromWindow()
    }

    fun onKey(code: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false

        // Audit P3: a HELD OK / play-pause key used to toggle at
        // repeat rate. Swallow repeats of the momentary keys.
        if (event.repeatCount > 0 && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
            return true
        }

        // If quick list is open, let it handle (audit N12: digits
        // were handled BEFORE this check, so typing in the quick
        // list started a channel zap instead).
        if (isQuickListVisible) {
            if (code == KeyEvent.KEYCODE_BACK) {
                hideQuickList()
                return true
            }
            return false
        }

        // Handle number keys (0..9) - only when the number-zap
        // setting is on (audit N4: the toggle was stored but the
        // digits were handled unconditionally).
        if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && prefs.numberZap) {
            handleNumber(code - KeyEvent.KEYCODE_0)
            return true
        }

        // If bottom bar is open
        if (isBottomBarVisible) {
            if (actionsBar.hasFocus()) {
                if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_UP) {
                    hideBottomBar()
                    return true
                }
                // Let DPAD_LEFT, DPAD_RIGHT, DPAD_CENTER / ENTER be handled by the focused action button!
                if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                    code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    return false
                }
            }
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
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                when (prefs.playerOk) {
                    "Channel list" -> showQuickList()
                    "Pause" -> {
                        if (playerEngine.isPlaying) playerEngine.pause() else playerEngine.resume()
                        showBottomBar()
                    }
                    "TV Guide" -> onExitFullscreen()
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
                // Audit P4: every option string the settings drawer
                // offers is handled by name (no silent else drift).
                when (prefs.playerLeft) {
                    "TV Guide" -> onExitFullscreen()
                    "Program info" -> showBottomBar()
                    "Channel list" -> showQuickList()
                    else -> showQuickList()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                when (prefs.playerRight) {
                    "TV Guide" -> onExitFullscreen()
                    "Channel list" -> showQuickList()
                    "Program info" -> showBottomBar()
                    else -> showBottomBar()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (playerEngine.isPlaying) playerEngine.pause() else playerEngine.resume()
                showBottomBar()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                playerEngine.seekRelative(prefs.seekStepSec)
                showBottomBar()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                playerEngine.seekRelative(-prefs.seekStepSec)
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
        // Audit P3: debounce held up/down so a held key does not
        // fire a burst of channel changes.
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastZapMs < 300L) return
        lastZapMs = nowMs
        val items = Repo.visibleItems()
        if (items.isEmpty()) return
        val cur = playerEngine.currentItem
        val idx = items.indexOfFirst { it.key == cur?.key }
        // Audit P8: current channel not in the visible list used to
        // land on 0 in BOTH directions; Down goes to the first,
        // Up to the last.
        var next = if (idx >= 0) idx + delta else if (delta < 0) items.size - 1 else 0
        if (prefs.channelWrap) {
            next = (next + items.size) % items.size
        } else {
            next = next.coerceIn(0, items.size - 1)
        }
        onSwitchChannel(items[next])
        showBottomBar()
    }
}

package com.livetv.app.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.livetv.app.data.Item
import com.livetv.app.data.Prefs
import com.livetv.app.data.Repo
import java.util.concurrent.Executors

class SearchPanel(
    private val activity: Activity,
    private val prefs: Prefs,
    private val onPlayChannel: (Item) -> Unit,
    private val onClose: () -> Unit
) : LinearLayout(activity) {

    private val input = EditText(activity)
    private val resultsList = ListPanel(activity)
    private val emptyNotice = TextView(activity)

    // Audit Sr1: search used to run synchronously on the main thread
    // on EVERY keystroke over ~900 channels and ~36k programmes.
    // Now: 250 ms debounce, a background executor, and a generation
    // counter so stale results can never land.
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchExecutor = Executors.newSingleThreadExecutor()
    private var pendingSearch: Runnable? = null
    private var searchGeneration = 0

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply { setColor(Pal.bg) }
        setPadding(dpi(36f), dpi(30f), dpi(36f), dpi(20f))

        val header = TextView(activity).apply {
            text = "Search Channels & Programs"
            textSize = 24f * Fonts.scale
            setTextColor(Pal.text)
            typeface = Fonts.medium
            setPadding(0, 0, 0, dpi(16f))
        }
        addView(header)

        // Search input
        input.apply {
            hint = "Type to search..."
            textSize = 18f * Fonts.scale
            setTextColor(Pal.text)
            setHintTextColor(Pal.textFaint)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = GradientDrawable().apply {
                setColor(Pal.card)
                cornerRadius = dp(8f)
                setStroke(dpi(1.5f), Pal.cardHi)
            }
            setPadding(dpi(16f), dpi(12f), dpi(16f), dpi(12f))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    performSearch(text.toString())
                    true
                } else false
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { performSearch(s?.toString() ?: "") }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
            setOnFocusChangeListener { _, f ->
                background = GradientDrawable().apply {
                    setColor(Pal.card)
                    cornerRadius = dp(8f)
                    setStroke(dpi(1.5f), if (f) Pal.accent else Pal.cardHi)
                }
            }
        }
        addView(input, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        emptyNotice.apply {
            text = "Enter a search term above to find live channels or guide listings."
            textSize = 15f
            setTextColor(Pal.textDim)
            gravity = Gravity.CENTER
            setPadding(0, dpi(40f), 0, 0)
        }
        addView(emptyNotice)

        resultsList.apply {
            rowHeightDp = 48f
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).also {
                it.topMargin = dpi(16f)
            }
            onEdge = { code ->
                if (code == KeyEvent.KEYCODE_DPAD_UP && resultsList.focusedIndex() == 0) {
                    input.requestFocus()
                    true
                } else if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    onClose()
                    true
                } else false
            }
        }
        addView(resultsList)
    }

    fun focusInput() {
        input.requestFocus()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (searchExecutor.isShutdown) {
            searchExecutor = Executors.newSingleThreadExecutor()
        }
    }

    override fun onDetachedFromWindow() {
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        pendingSearch = null
        searchGeneration++
        searchExecutor.shutdownNow()
        super.onDetachedFromWindow()
    }

    private fun performSearch(query: String) {
        val q = query.trim()
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        pendingSearch = null
        if (q.isEmpty()) {
            searchGeneration++
            emptyNotice.text = "Enter a search term above to find live channels or guide listings."
            emptyNotice.visibility = View.VISIBLE
            resultsList.visibility = View.GONE
            return
        }

        val generation = ++searchGeneration
        val task = Runnable {
            searchExecutor.execute {
                val hits = Repo.search(q)
                searchHandler.post {
                    if (generation != searchGeneration) return@post
                    renderHits(q, hits)
                }
            }
        }
        pendingSearch = task
        searchHandler.postDelayed(task, 250)
    }

    private fun renderHits(q: String, hits: List<Repo.Hit>) {
        if (hits.isEmpty()) {
            emptyNotice.text = "No channels or programs found matching \"$q\"."
            emptyNotice.visibility = View.VISIBLE
            resultsList.visibility = View.GONE
        } else {
            emptyNotice.visibility = View.GONE
            resultsList.visibility = View.VISIBLE

            val rows = hits.map { hit ->
                val subText = if (hit.program != null) {
                    "Live on ${hit.item.name} • ${hit.program.title}"
                } else {
                    "Channel #${hit.item.number} • ${hit.item.groups.joinToString(", ")}"
                }
                Row(
                    title = hit.program?.title ?: hit.item.name,
                    sub = subText,
                    onClick = {
                        onPlayChannel(hit.item)
                    }
                )
            }
            resultsList.submit(rows)
        }
        // Audit Sr2: the panel took a Prefs handle but never used it;
        // search terms are now recorded ( honouring the history
        // toggle) so "Clear search history" has something to clear.
        if (prefs.searchHistory) {
            prefs.searchTerms = (listOf(q) + prefs.searchTerms.filter { it != q }).take(8)
        }
    }
}

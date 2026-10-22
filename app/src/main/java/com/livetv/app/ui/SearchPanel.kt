package com.livetv.app.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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

class SearchPanel(
    private val activity: Activity,
    private val prefs: Prefs,
    private val onPlayChannel: (Item) -> Unit,
    private val onClose: () -> Unit
) : LinearLayout(activity) {

    private val input = EditText(activity)
    private val resultsList = ListPanel(activity)
    private val emptyNotice = TextView(activity)

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

    private fun performSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            emptyNotice.visibility = View.VISIBLE
            resultsList.visibility = View.GONE
            return
        }

        val hits = Repo.search(q)
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
    }
}

package com.livetv.app.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.livetv.app.R
import java.util.Collections
import java.util.WeakHashMap

/** Centered modal card used for choices, text input, confirmation and PIN entry. */
object Dialogs {
    // Audit Dg1: open dialogs are tracked so MainActivity can dismiss
    // them all on destroy instead of leaking windows.
    private val openDialogs: MutableSet<Dialog> = Collections.newSetFromMap(WeakHashMap())

    fun dismissAll() {
        openDialogs.toList().forEach { d -> try { d.dismiss() } catch (e: Exception) { } }
    }

    private fun Dialog.safeShow(a: Activity) {
        // Audit Dg1: never show into a finishing/destroyed activity.
        if (!a.isFinishing && !a.isDestroyed) show()
    }

    private fun shell(a: Activity, title: String, widthDp: Float = 380f, content: (LinearLayout, Dialog) -> Unit): Dialog {
        val d = Dialog(a, android.R.style.Theme_Translucent_NoTitleBar)
        val root = FrameLayout(a).apply { setBackgroundColor(Pal.scrim) }
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Pal.panel); cornerRadius = a.dp(16f); setStroke(a.dpi(1f), Pal.cardEdge) }
            elevation = a.dp(24f)
            setPadding(a.dpi(20f), a.dpi(18f), a.dpi(20f), a.dpi(14f))
        }
        card.addView(TextView(a).apply { text = title; textSize = 21f * Fonts.scale; setTextColor(Pal.text); typeface = Fonts.medium; setPadding(a.dpi(8f), 0, 0, a.dpi(10f)) })
        root.addView(card, FrameLayout.LayoutParams(a.dpi(widthDp), -2, Gravity.CENTER))
        d.setContentView(root)
        content(card, d)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(-1, -1)
        openDialogs.add(d)
        d.setOnDismissListener { openDialogs.remove(d) }
        return d
    }

    fun choose(a: Activity, title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val d = shell(a, title) { card, dlg ->
            val lp = ListPanel(a)
            lp.rowHeightDp = 42f
            lp.submit(options.mapIndexed { i, o -> Row(o, selected = i == selected, onClick = { dlg.dismiss(); onPick(i) }) })
            card.addView(lp, LinearLayout.LayoutParams(-1, minOf(a.dpi(44f) * options.size + a.dpi(8f), a.dpi(340f))))
            lp.post { lp.focusRow(selected.coerceAtLeast(0)) }
        }
        d.safeShow(a)
    }

    fun menu(a: Activity, title: String, rows: List<Row>, widthDp: Float = 380f) {
        var dlg: Dialog? = null
        val wrapped = rows.map { r -> if (r.onClick == null || r.kind != Kind.ITEM) r else Row(r.title, r.sub, r.value, r.kind, r.checked, r.selected, r.icon, r.accentTitle, r.dim, r.big, { dlg?.dismiss(); r.onClick.invoke() }, r.onLong, r.tag) }
        dlg = shell(a, title, widthDp) { card, _ ->
            val lp = ListPanel(a); lp.rowHeightDp = 44f; lp.submit(wrapped)
            card.addView(lp, LinearLayout.LayoutParams(-1, minOf(a.dpi(46f) * wrapped.size + a.dpi(8f), a.dpi(360f))))
            lp.post { lp.focusRow(wrapped.indexOfFirst { it.kind == Kind.ITEM }.coerceAtLeast(0)) }
        }
        dlg?.safeShow(a)
    }

    fun input(a: Activity, title: String, initial: String = "", hint: String = "", password: Boolean = false, onOk: (String) -> Unit) {
        val d = shell(a, title, 460f) { card, dlg ->
            val et = EditText(a).apply {
                setText(initial); setSelection(initial.length); this.hint = hint; textSize = 18f * Fonts.scale
                setTextColor(Pal.text); setHintTextColor(Pal.textFaint); typeface = Fonts.regular; setSingleLine()
                imeOptions = EditorInfo.IME_ACTION_DONE
                if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                background = GradientDrawable().apply { setColor(Pal.card); cornerRadius = a.dp(8f); setStroke(a.dpi(1.5f), Pal.accent) }
                setPadding(a.dpi(14f), a.dpi(10f), a.dpi(14f), a.dpi(10f))
            }
            card.addView(et, LinearLayout.LayoutParams(-1, -2))
            // Audit Dg3: the soft keyboard was never dismissed, so it
            // stayed up over the UI after the dialog closed.
            val hideIme = {
                val imm = a.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(et.windowToken, 0)
            }
            val ok = { hideIme(); dlg.dismiss(); onOk(et.text.toString().trim()) }
            et.setOnEditorActionListener { _, _, _ -> ok(); true }
            val lp = ListPanel(a)
            lp.submit(listOf(Row("OK", accentTitle = true, onClick = { ok() }), Row("Cancel", onClick = { hideIme(); dlg.dismiss() })))
            card.addView(lp, LinearLayout.LayoutParams(-1, a.dpi(100f)).also { it.topMargin = a.dpi(10f) })
        }
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.safeShow(a)
    }

    fun confirm(a: Activity, title: String, message: String, yes: String = "OK", onYes: () -> Unit) {
        val d = shell(a, title) { card, dlg ->
            card.addView(TextView(a).apply { text = message; textSize = 16f * Fonts.scale; setTextColor(Pal.textDim); setPadding(a.dpi(8f), 0, a.dpi(8f), a.dpi(12f)) })
            val lp = ListPanel(a)
            lp.submit(listOf(Row(yes, accentTitle = true, onClick = { dlg.dismiss(); onYes() }), Row("Cancel", onClick = { dlg.dismiss() })))
            card.addView(lp, LinearLayout.LayoutParams(-1, a.dpi(100f)))
            lp.post { lp.focusRow(1) }
        }
        d.safeShow(a)
    }

    fun message(a: Activity, title: String, message: String) {
        val d = shell(a, title) { card, dlg ->
            // Audit Dg2: long messages were unscrollable and pushed
            // the OK row off screen. Cap the text at 70% of the
            // display height inside a ScrollView.
            val tv = TextView(a).apply { text = message; textSize = 16f * Fonts.scale; setTextColor(Pal.textDim); setPadding(a.dpi(8f), 0, a.dpi(8f), a.dpi(12f)) }
            // Focusable so a long message can actually be scrolled
            // with the remote (audit N15): UP from OK lands here and
            // the D-pad scrolls; DOWN returns to OK.
            val sv = ScrollView(a).apply { addView(tv); isFocusable = true; isFocusableInTouchMode = true }
            card.addView(sv, LinearLayout.LayoutParams(-1, -2))
            val cap = (a.resources.displayMetrics.heightPixels * 0.7f).toInt()
            sv.post {
                if (sv.height > cap) {
                    val lp2 = sv.layoutParams
                    lp2.height = cap
                    sv.layoutParams = lp2
                }
            }
            val lp = ListPanel(a); lp.submit(listOf(Row("OK", accentTitle = true, onClick = { dlg.dismiss() })))
            card.addView(lp, LinearLayout.LayoutParams(-1, a.dpi(54f)))
            lp.post { lp.focusRow(0) }
        }
        d.safeShow(a)
    }

    /** PIN entry: Up/Down changes a digit, Left/Right moves, OK confirms. Number keys also work. */
    fun pin(a: Activity, title: String, onResult: (String) -> Unit) {
        val digits = intArrayOf(0, 0, 0, 0); var pos = 0
        lateinit var dlg: Dialog
        val boxes = ArrayList<TextView>()
        fun refresh() = boxes.forEachIndexed { i, b ->
            b.text = digits[i].toString()
            b.background = GradientDrawable().apply { setColor(if (i == pos) Pal.focusFill else Pal.card); cornerRadius = a.dp(8f) }
            b.setTextColor(if (i == pos) Pal.focusText else Pal.text)
        }
        dlg = shell(a, title, 340f) { card, _ ->
            val rowL = LinearLayout(a).apply { gravity = Gravity.CENTER }
            repeat(4) { rowL.addView(TextView(a).also { boxes.add(it) }.apply { textSize = 32f; typeface = Fonts.medium; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(a.dpi(56f), a.dpi(68f)).also { it.setMargins(a.dpi(6f), a.dpi(6f), a.dpi(6f), a.dpi(10f)) }) }
            card.addView(rowL)
            card.addView(TextView(a).apply { text = "▲▼ change   ◀▶ move   OK confirm"; textSize = 13f; setTextColor(Pal.textDim); gravity = Gravity.CENTER })
        }
        refresh()
        dlg.setOnKeyListener { _, code, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> { digits[pos] = (digits[pos] + 1) % 10; refresh(); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { digits[pos] = (digits[pos] + 9) % 10; refresh(); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { pos = (pos - 1).coerceAtLeast(0); refresh(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { pos = (pos + 1).coerceAtMost(3); refresh(); true }
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { digits[pos] = code - KeyEvent.KEYCODE_0; if (pos < 3) pos++; refresh(); true }
                // Numpad digits/enter too (audit N14): every other OK
                // path in the app accepts them; the PIN could neither
                // be typed nor confirmed from a numpad.
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> { digits[pos] = code - KeyEvent.KEYCODE_NUMPAD_0; if (pos < 3) pos++; refresh(); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { dlg.dismiss(); onResult(digits.joinToString("")); true }
                else -> false
            }
        }
        dlg.safeShow(a)
    }
}

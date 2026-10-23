package com.livetv.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

enum class Kind { ITEM, HEADER, SPACER }

class Row(
    val title: String,
    val sub: String? = null,
    val value: String? = null,
    val kind: Kind = Kind.ITEM,
    val checked: Boolean? = null,      // non-null => toggle switch
    val selected: Boolean = false,     // radio dot (single choice)
    val icon: Int = 0,
    val accentTitle: Boolean = false,
    val dim: Boolean = false,
    val big: Boolean = false,
    val onClick: (() -> Unit)? = null,
    val onLong: (() -> Unit)? = null,
    val tag: Any? = null,
)

/** The one list widget the whole app is built from: groups, settings, menus, dialogs, channel lists. */
class ListPanel(ctx: Context) : RecyclerView(ctx) {
    var rows: List<Row> = emptyList(); private set
    var onFocusRow: (Int, Row) -> Unit = { _, _ -> }
    var onEdge: (Int) -> Boolean = { false }   // KeyEvent keycode for LEFT/RIGHT presses; return true if consumed
    var textScale = 1f
    var rowHeightDp = 44f

    init {
        layoutManager = LinearLayoutManager(ctx)
        adapter = A()
        itemAnimator = null
        clipToPadding = false
        isFocusable = true
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        overScrollMode = OVER_SCROLL_NEVER
    }

    fun submit(r: List<Row>, focusIndex: Int = -1) {
        rows = r; adapter!!.notifyDataSetChanged()
        if (focusIndex >= 0) focusRow(focusIndex)
    }
    fun update(index: Int, r: Row) { rows = rows.toMutableList().also { it[index] = r }; adapter!!.notifyItemChanged(index) }
    fun focusRow(i: Int) {
        if (i !in rows.indices) return
        scrollToPosition(i)
        val doFocus = Runnable {
            val lm = layoutManager as? LinearLayoutManager
            val child = lm?.findViewByPosition(i)
            if (child != null) {
                child.requestFocus()
            } else {
                postDelayed({
                    val delayed = (layoutManager as? LinearLayoutManager)?.findViewByPosition(i)
                    if (delayed != null) {
                        delayed.requestFocus()
                    } else {
                        requestFocus()
                    }
                }, 80)
            }
        }
        post(doFocus)
    }
    fun focusedIndex(): Int = (0 until childCount).map { getChildAt(it) }.firstOrNull { it.hasFocus() }?.let { getChildAdapterPosition(it) } ?: -1

    private inner class VH(val v: RowView) : ViewHolder(v)
    private inner class A : Adapter<VH>() {
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(RowView(context).also { it.layoutParams = LayoutParams(-1, -2) })
        override fun onBindViewHolder(h: VH, i: Int) { h.v.bind(rows[i], textScale, rowHeightDp) { onFocusRow(i, rows[i]) }
            h.v.setOnKeyListener { _, code, e ->
                e.action == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_BACK) && onEdge(code) } }
    }
}

class RowView(ctx: Context) : LinearLayout(ctx) {
    private val icon = ImageView(ctx)
    private val col = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val title = TextView(ctx)
    private val sub = TextView(ctx)
    private val value = TextView(ctx)
    private val sw = SwitchView(ctx)
    private val dot = ImageView(ctx)
    private var row: Row? = null
    private var accentTitle = false
    private var dim = false

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dpi(14f), dpi(5f), dpi(14f), dpi(5f))
        icon.layoutParams = LayoutParams(dpi(20f), dpi(20f)).also { it.rightMargin = dpi(14f) }
        title.typeface = Fonts.regular; title.maxLines = 2
        sub.typeface = Fonts.regular; sub.maxLines = 2
        col.addView(title); col.addView(sub)
        addView(icon); addView(col, LayoutParams(0, -2, 1f)); addView(value, LayoutParams(-2, -2).also { it.leftMargin = dpi(8f) })
        addView(sw, LayoutParams(dpi(34f), dpi(18f)).also { it.leftMargin = dpi(10f) })
        addView(dot, LayoutParams(dpi(18f), dpi(18f)).also { it.leftMargin = dpi(10f) })
        isFocusable = true; isClickable = true
        setOnFocusChangeListener { _, f -> applyFocus(f); if (f) focusCb() }
    }
    private var focusCb: () -> Unit = {}

    fun bind(r: Row, scale: Float, minH: Float, onFocus: () -> Unit) {
        row = r; focusCb = onFocus; accentTitle = r.accentTitle; dim = r.dim
        isFocusable = r.kind == Kind.ITEM && (r.onClick != null || r.checked != null || r.onLong != null || r.sub != null || r.value != null || true)
        if (r.kind == Kind.HEADER) isFocusable = false
        minimumHeight = dpi(if (r.kind == Kind.HEADER) 36f else if (r.kind == Kind.SPACER) 12f else minH)
        val s = scale
        title.text = r.title; title.textSize = (if (r.kind == Kind.HEADER) 13f else if (r.big) 20f else 17f) * s * (Fonts.scale)
        sub.text = r.sub ?: ""; sub.visibility = if (r.sub.isNullOrEmpty()) GONE else VISIBLE; sub.textSize = 13f * s * Fonts.scale
        value.text = r.value ?: ""; value.visibility = if (r.value.isNullOrEmpty()) GONE else VISIBLE; value.textSize = 15f * s * Fonts.scale
        title.typeface = if (r.kind == Kind.HEADER) Fonts.medium else Fonts.regular
        if (r.icon != 0) { icon.visibility = VISIBLE; icon.setImageResource(r.icon) } else icon.visibility = GONE
        sw.visibility = if (r.checked != null) VISIBLE else GONE; sw.on = r.checked == true
        dot.visibility = if (r.selected) VISIBLE else GONE
        if (r.selected) dot.setImageDrawable(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Pal.accent); setSize(dpi(10f), dpi(10f)) })
        setOnClickListener { r.onClick?.invoke() }
        if (r.onLong != null) { isLongClickable = true; setOnLongClickListener { r.onLong.invoke(); true } } else { isLongClickable = false; setOnLongClickListener(null) }
        applyFocus(hasFocus())
    }

    private fun applyFocus(f: Boolean) {
        val r = row ?: return
        background = if (f && r.kind == Kind.ITEM) GradientDrawable().apply { setColor(Pal.focusFill); cornerRadius = dp(6f) } else null
        val tc = when { f -> Pal.focusText; r.kind == Kind.HEADER -> Pal.accent; accentTitle -> Pal.accent; dim -> Pal.textDim; else -> Pal.text }
        title.setTextColor(tc); sub.setTextColor(if (f) Color.argb(170, 10, 14, 20) else Pal.textDim)
        value.setTextColor(if (f) Color.argb(200, 10, 14, 20) else Pal.textDim)
        icon.setColorFilter(if (f) Pal.focusText else if (accentTitle) Pal.accent else Pal.textDim)
        sw.focused = f
    }
}

/** Small pill toggle drawn by hand so it matches the palette. */
class SwitchView(ctx: Context) : View(ctx) {
    var on = false; set(v) { field = v; invalidate() }
    var focused = false; set(v) { field = v; invalidate() }
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.color = if (on) (if (focused) Color.parseColor("#0A0E14") else Pal.accent) else if (focused) Color.argb(90, 10, 14, 20) else Pal.cardHi
        c.drawRoundRect(0f, h * 0.15f, w, h * 0.85f, h, h, p)
        p.color = if (on) (if (focused) Pal.accent else Color.WHITE) else if (focused) Color.parseColor("#0A0E14") else Pal.textDim
        val r = h * 0.5f
        c.drawCircle(if (on) w - r else r, h / 2, r * 0.85f, p)
    }
}

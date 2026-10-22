package com.livetv.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.livetv.app.data.Item
import com.livetv.app.data.Prefs
import com.livetv.app.data.Repo
import com.livetv.app.epg.Program
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

/** One drawable block in a channel row; `program == null` means a "No information" filler. */
class Cell(val start: Long, val end: Long, val program: Program?)

/**
 * Channel x time EPG grid, drawn on a single canvas. Selection is a (row, time) cursor so moving
 * vertically keeps your place in time. D-pad: up/down rows, left/right programmes, OK select.
 */
class GuideView(ctx: Context, private val prefs: Prefs) : View(ctx) {
    var rows: List<Item> = emptyList(); set(v) { field = v; cellCache.clear(); selRow = selRow.coerceIn(0, (v.size - 1).coerceAtLeast(0)); invalidate(); notifySel() }
    var playingKey: String? = null; set(v) { field = v; invalidate() }
    var onPlay: (Item) -> Unit = {}
    var onFullscreen: (Item) -> Unit = {}
    var onMenu: (Item, Program?) -> Unit = { _, _ -> }
    var onProgram: (Item, Program) -> Unit = { _, _ -> }
    var onEdgeLeft: () -> Unit = {}
    var onSelection: (Item?, Program?) -> Unit = { _, _ -> }

    var selRow = 0; private set
    private var selTime = System.currentTimeMillis()
    private var viewStart = floor30(System.currentTimeMillis()).toFloat()
    private var scrollRow = 0f
    private val cellCache = HashMap<String, List<Cell>>()
    private var cacheEpg: Any? = null

    private val chanW get() = dp(270f)
    private val rowH get() = dp(rowDp())
    private val headH get() = dp(44f)
    private val ppm get() = dp(160f) / 30f           // px per minute
    private fun rowDp() = when (prefs.guideRowSize) { "Small" -> 34f; "Large" -> 44f; else -> 39f }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.regular }
    private val timeFmt = SimpleDateFormat("hh:mm a", Locale.US)
    private val dateFmt = SimpleDateFormat("EEE, MMM d, h:mm a", Locale.US)
    private var anim: ValueAnimator? = null
    private val tick = object : Runnable { override fun run() { invalidate(); postDelayed(this, 30_000) } }

    init { isFocusable = true; isFocusableInTouchMode = false; setWillNotDraw(false) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); postDelayed(tick, 30_000) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    private fun floor30(t: Long) = t - (t % (30 * 60_000L)) - TimeZone.getDefault().getOffset(t).let { 0 }

    fun selected(): Item? = rows.getOrNull(selRow)
    fun selectedProgram(): Program? = selected()?.let { cellAt(it, selTime)?.program }
    fun select(key: String?) { val i = rows.indexOfFirst { it.key == key }; if (i >= 0) { selRow = i; scrollTo(selRow, false); invalidate(); notifySel() } }
    fun resetToNow() { selTime = System.currentTimeMillis(); viewStart = floor30(selTime).toFloat(); invalidate(); notifySel() }
    private fun notifySel() = onSelection(selected(), selectedProgram())

    // -- data ----------------------------------------------------------------------
    private fun cellsFor(item: Item): List<Cell> {
        val e = Repo.epg
        if (e !== cacheEpg) { cellCache.clear(); cacheEpg = e }
        return cellCache.getOrPut(item.key) {
            val from = floor30(System.currentTimeMillis()) - 6 * 3_600_000L
            val to = from + 36 * 3_600_000L
            val out = ArrayList<Cell>()
            var cur = from
            for (pr in Repo.programs(item)) {
                val s = pr.start.toEpochMilli(); val en = pr.end.toEpochMilli()
                if (en <= from || s >= to || en <= cur) continue
                val ss = maxOf(s, cur)
                if (ss > cur) fill(out, cur, ss)
                out.add(Cell(ss, en, pr)); cur = en
            }
            if (cur < to) fill(out, cur, to)
            out
        }
    }
    private fun fill(out: MutableList<Cell>, a: Long, b: Long) {
        var s = a
        val hour = 3_600_000L
        while (s < b) { val e = minOf(b, (s / hour + 1) * hour); out.add(Cell(s, e, null)); s = e }
    }
    private fun cellAt(item: Item, t: Long): Cell? = cellsFor(item).firstOrNull { t >= it.start && t < it.end }

    // -- drawing -------------------------------------------------------------------
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val now = System.currentTimeMillis()
        val viewEnd = viewStart + (w - chanW) / ppm * 60_000f
        // header
        tp.textSize = sp(15f); tp.color = Pal.accent; tp.typeface = Fonts.medium; tp.textAlign = Paint.Align.LEFT
        c.drawText(dateFmt.format(Date(now)), 0f, headH - dp(14f), tp)
        tp.color = Pal.textDim; tp.typeface = Fonts.regular; tp.textAlign = Paint.Align.CENTER
        var t = ((viewStart / 1_800_000f).toLong() * 1_800_000L)
        while (t < viewEnd + 1_800_000L) {
            val x = chanW + (t - viewStart) / 60_000f * ppm
            if (x > chanW - dp(30f)) c.drawText(timeFmt.format(Date(t)), x, headH - dp(14f), tp)
            t += 1_800_000L
        }
        p.color = Pal.cardHi; p.style = Paint.Style.FILL
        c.drawRect(0f, headH - dp(1f), w, headH, p)

        // rows
        val save = c.save(); c.clipRect(0f, headH, w, h)
        val first = scrollRow.toInt().coerceAtLeast(0)
        val vis = ((h - headH) / rowH).toInt() + 2
        for (r in first until minOf(rows.size, first + vis + 1)) {
            val y = headH + dp(2f) + (r - scrollRow) * rowH
            drawRow(c, rows[r], r, y, now, viewEnd)
        }
        c.restoreToCount(save)

        // now line
        val nx = chanW + (now - viewStart) / 60_000f * ppm
        if (nx in chanW..w) {
            p.color = Pal.accent; p.alpha = 150
            c.drawRect(nx - dp(0.75f), headH, nx + dp(0.75f), h, p)
            p.alpha = 255; c.drawCircle(nx, headH, dp(3.5f), p)
        }
    }

    private fun drawRow(c: Canvas, it: Item, r: Int, y: Float, now: Long, viewEnd: Float) {
        val isSel = r == selRow
        val isPlaying = it.key == playingKey
        val cy = y + rowH / 2f
        // number
        if (prefs.guideChannelNumbers) {
            tp.textSize = sp(18f); tp.typeface = Fonts.regular; tp.textAlign = Paint.Align.CENTER
            tp.color = if (isPlaying) Pal.accent else Pal.text
            c.drawText(it.number.toString(), dp(26f), cy + sp(6.5f), tp)
        }
        // logo
        val lx = dp(52f); val lw = dp(45f); val lh = rowH - dp(9f)
        if (prefs.guideLogos) {
            val bmp = Logos.get(context, it.ch.logo) { invalidate() }
            if (bmp != null) {
                val s = minOf(lw / bmp.width, lh / bmp.height)
                val dw = bmp.width * s; val dh = bmp.height * s
                c.drawBitmap(bmp, null, RectF(lx + (lw - dw) / 2, cy - dh / 2, lx + (lw + dw) / 2, cy + dh / 2), p)
            } else {
                p.color = Pal.card; c.drawRoundRect(RectF(lx + dp(6f), cy - lh / 2.4f, lx + lw - dp(6f), cy + lh / 2.4f), dp(5f), dp(5f), p)
                tp.textSize = sp(11f); tp.color = Pal.textDim; tp.textAlign = Paint.Align.CENTER; tp.typeface = Fonts.medium
                c.drawText(initials(it.name), lx + lw / 2, cy + sp(4f), tp)
            }
        }
        // name
        val nx = if (prefs.guideLogos) dp(107f) else dp(52f)
        tp.textSize = sp(17f); tp.textAlign = Paint.Align.LEFT; tp.typeface = if (isPlaying || isSel) Fonts.medium else Fonts.regular
        tp.color = if (isPlaying) Pal.accent else Pal.text
        val maxNameW = chanW - nx - dp(18f)
        c.drawText(TextUtils_ellipsize(it.name, tp, maxNameW), nx, cy + sp(6f), tp)
        if (isPlaying) { // play triangle
            p.color = Pal.accent
            val px = chanW - dp(14f); val s = dp(6f)
            val path = Path().apply { moveTo(px - s, cy - s); lineTo(px + s * 0.8f, cy); lineTo(px - s, cy + s); close() }
            c.drawPath(path, p)
        }
        // cells
        val save = c.save(); c.clipRect(chanW, y, width.toFloat(), y + rowH)
        for (cell in cellsFor(it)) {
            val x1 = chanW + (cell.start - viewStart) / 60_000f * ppm
            val x2 = chanW + (cell.end - viewStart) / 60_000f * ppm
            if (x2 < chanW || x1 > width) continue
            val rect = RectF(x1 + dp(1.5f), y + dp(1f), x2 - dp(1.5f), y + rowH - dp(2.5f))
            val focused = isSel && selTime >= cell.start && selTime < cell.end && hasFocus()
            val softSel = isSel && selTime >= cell.start && selTime < cell.end && !hasFocus()
            p.color = when { focused -> Pal.focusFill; softSel -> Pal.cardHi; isSel -> Pal.cardHi; else -> Pal.card }
            if (!focused && !softSel && isSel) p.color = Pal.card.blend(Pal.cardHi, 0.55f)
            c.drawRoundRect(rect, dp(3f), dp(3f), p)
            // played portion
            if (prefs.guideProgress && cell.start < now && cell.end > now && cell.program != null) {
                val px = (chanW + (now - viewStart) / 60_000f * ppm).coerceIn(rect.left, rect.right)
                p.color = Color.argb(if (focused) 28 else 40, 0, 0, 0); c.drawRect(rect.left, rect.top, px, rect.bottom, p)
            }
            tp.textSize = sp(17f); tp.textAlign = Paint.Align.LEFT; tp.typeface = Fonts.regular
            tp.color = when { focused -> Pal.focusText; cell.program == null -> Pal.textFaint; isSel -> Pal.text; else -> Pal.textDim }
            val tx = maxOf(rect.left, chanW) + dp(10f)
            val avail = rect.right - tx - dp(6f)
            if (avail > dp(24f)) c.drawText(TextUtils_ellipsize(cell.program?.title ?: "No information", tp, avail), tx, cy + sp(6f), tp)
        }
        c.restoreToCount(save)
    }

    private fun initials(n: String) = n.split(' ', '-', '(').filter { it.isNotEmpty() && it[0].isLetterOrDigit() }.take(2).joinToString("") { it.take(1).uppercase() }
    @Suppress("FunctionName")
    private fun TextUtils_ellipsize(s: String, paint: Paint, w: Float): String {
        if (paint.measureText(s) <= w) return s
        var n = s.length; while (n > 1 && paint.measureText(s, 0, n) + paint.measureText("…") > w) n--
        return s.substring(0, n).trimEnd() + "…"
    }
    private fun Int.blend(o: Int, f: Float): Int = Color.rgb(
        (Color.red(this) * (1 - f) + Color.red(o) * f).toInt(), (Color.green(this) * (1 - f) + Color.green(o) * f).toInt(), (Color.blue(this) * (1 - f) + Color.blue(o) * f).toInt())

    // -- scrolling -----------------------------------------------------------------
    private fun visibleRows() = ((height - headH) / rowH).toInt().coerceAtLeast(1)
    private fun scrollTo(row: Int, animate: Boolean = true) {
        val v = visibleRows()
        var target = scrollRow
        if (row < scrollRow) target = row.toFloat()
        else if (row + 1 > scrollRow + v) target = (row + 1 - v).toFloat()
        target = target.coerceIn(0f, maxOf(0, rows.size - v).toFloat())
        if (target == scrollRow) return
        anim?.cancel()
        if (!animate) { scrollRow = target; return }
        anim = ValueAnimator.ofFloat(scrollRow, target).apply { duration = 140; interpolator = DecelerateInterpolator(); addUpdateListener { scrollRow = it.animatedValue as Float; invalidate() }; start() }
    }
    private fun ensureTimeVisible(cell: Cell) {
        val viewMin = (width - chanW) / ppm * 60_000f
        val minStart = floor30(System.currentTimeMillis()) - 6 * 3_600_000L
        if (cell.start < viewStart) viewStart = maxOf(cell.start.toFloat(), minStart.toFloat())
        else if (cell.end > viewStart + viewMin) viewStart = maxOf(minStart.toFloat(), minOf(cell.start.toFloat(), cell.end - viewMin))
    }

    // -- input ---------------------------------------------------------------------
    override fun onFocusChanged(g: Boolean, d: Int, r: Rect?) { super.onFocusChanged(g, d, r); invalidate() }
    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        val cur = selected() ?: return super.onKeyDown(code, e)
        when (code) {
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> {
                val d = if (code == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                var n = selRow + d
                if (n < 0) n = if (prefs.channelWrap) rows.size - 1 else 0
                if (n >= rows.size) n = if (prefs.channelWrap) 0 else rows.size - 1
                selRow = n; scrollTo(n); cellAt(rows[n], selTime)?.let { ensureTimeVisible(it) }; invalidate(); notifySel(); return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                val d = if (code == KeyEvent.KEYCODE_PAGE_DOWN || code == KeyEvent.KEYCODE_CHANNEL_DOWN) visibleRows() else -visibleRows()
                selRow = (selRow + d).coerceIn(0, rows.size - 1); scrollTo(selRow); invalidate(); notifySel(); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val cells = cellsFor(cur); val i = cells.indexOfFirst { selTime >= it.start && selTime < it.end }
                val minStart = floor30(System.currentTimeMillis()) - 6 * 3_600_000L
                if (i <= 0 || cells[i - 1].end <= minStart) { onEdgeLeft(); return true }
                selTime = cells[i - 1].start.coerceAtLeast(minStart); ensureTimeVisible(cells[i - 1]); invalidate(); notifySel(); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val cells = cellsFor(cur); val i = cells.indexOfFirst { selTime >= it.start && selTime < it.end }
                if (i in 0 until cells.size - 1) { selTime = cells[i + 1].start; ensureTimeVisible(cells[i + 1]); invalidate(); notifySel() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { e.startTracking(); return true }
            KeyEvent.KEYCODE_MENU -> { onMenu(cur, selectedProgram()); return true }
        }
        return super.onKeyDown(code, e)
    }
    override fun onKeyLongPress(code: Int, e: KeyEvent): Boolean {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) { selected()?.let { onMenu(it, selectedProgram()) }; return true }
        return super.onKeyLongPress(code, e)
    }
    override fun onKeyUp(code: Int, e: KeyEvent): Boolean {
        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && e.isTracking && !e.isCanceled) {
            val it = selected() ?: return true
            val pr = selectedProgram()
            val now = System.currentTimeMillis()
            val future = pr != null && pr.start.toEpochMilli() > now + 60_000
            when {
                future -> onProgram(it, pr!!)
                prefs.guideOkAction.startsWith("Fullscreen") || it.key == playingKey -> onFullscreen(it)
                else -> onPlay(it)
            }
            return true
        }
        return super.onKeyUp(code, e)
    }
}

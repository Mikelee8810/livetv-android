package com.livetv.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View

/** Live TV colour system. "Lagoon" is the default: deep ink surfaces with a teal accent and amber highlights. */
object Pal {
    var bg = Color.parseColor("#0A0E14")
    var rail = Color.parseColor("#0F151D")
    var panel = Color.parseColor("#131B26")
    var card = Color.parseColor("#18222F")
    var cardHi = Color.parseColor("#243142")
    var focusFill = Color.parseColor("#E4F6F3")
    var focusText = Color.parseColor("#0A0E14")
    var text = Color.parseColor("#E8EEF4")
    var textDim = Color.parseColor("#8A98A8")
    var textFaint = Color.parseColor("#566374")
    var accent = Color.parseColor("#2DD4BF")
    var accent2 = Color.parseColor("#F5B544")
    var danger = Color.parseColor("#FF6B6B")
    var scrim = Color.parseColor("#B3060A0F")

    val accents = linkedMapOf(
        "Lagoon" to "#2DD4BF", "Ember" to "#F5A524", "Orchid" to "#B794F6",
        "Coral" to "#FF7A70", "Sky" to "#5BB6FF", "Lime" to "#A3E635"
    )
    val bases = listOf("Dark", "AMOLED", "Slate")

    fun apply(base: String, accentName: String) {
        accent = Color.parseColor(accents[accentName] ?: accents["Lagoon"]!!)
        when (base) {
            "AMOLED" -> { bg = Color.BLACK; rail = Color.parseColor("#06080B"); panel = Color.parseColor("#0B0E12"); card = Color.parseColor("#12171D"); cardHi = Color.parseColor("#1C242E") }
            "Slate" -> { bg = Color.parseColor("#141A21"); rail = Color.parseColor("#1A222B"); panel = Color.parseColor("#1F2832"); card = Color.parseColor("#27323E"); cardHi = Color.parseColor("#33414F") }
            else -> { bg = Color.parseColor("#0A0E14"); rail = Color.parseColor("#0F151D"); panel = Color.parseColor("#131B26"); card = Color.parseColor("#18222F"); cardHi = Color.parseColor("#243142") }
        }
    }
}

object Fonts {
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    var scale = 1f
}

fun Context.dp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
fun Context.dpi(v: Float): Int = dp(v).toInt()
fun View.dp(v: Float): Float = context.dp(v)
fun View.dpi(v: Float): Int = context.dpi(v)
fun Context.sp(v: Float): Float = dp(v) * Fonts.scale
fun View.sp(v: Float): Float = context.sp(v)

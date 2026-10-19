package dev.hashsome.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.view.View

// Helpers the views draw with. Every one reads the theme at the time it is called, never earlier.

/** The theme now. */
internal val theme: Theme get() = Hashsome.theme

/** The face for a weight (a number as in CSS): 400 and below Regular, 500 Medium, above SemiBold. */
fun FontSet.forWeight(weight: Int): Typeface = when {
    weight <= 450 -> regular
    weight <= 550 -> medium
    else -> semiBold
}

/** Sets this paint up for [role] in [color]: its face, size and letter spacing. */
fun TextPaint.style(theme: Theme, role: Role, color: Int): TextPaint {
    val spec = theme.style(role)
    this.color = color
    typeface = theme.typefaces.forWeight(spec.weight)
    textSize = sp(spec.size.toFloat())
    letterSpacing = spec.letterSpacing
    return this
}

/** [text] with its vertical middle at [centerY]. */
internal fun Canvas.drawTextCentered(text: CharSequence, x: Float, centerY: Float, paint: Paint, align: Paint.Align = Paint.Align.LEFT) {
    paint.textAlign = align
    drawText(text, 0, text.length, x, centerY - (paint.ascent() + paint.descent()) / 2, paint)
}

/** True when [view] was touched at (x, y) in its own coordinates. */
internal fun View.contains(x: Float, y: Float): Boolean = x >= 0f && y >= 0f && x <= width && y <= height

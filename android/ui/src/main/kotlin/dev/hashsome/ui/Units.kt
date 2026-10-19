package dev.hashsome.ui

import android.content.res.Resources
import android.util.TypedValue

// Device-independent lengths. The dashboards are laid out in CSS pixels, which are dp here: the page and the app share one grid.

/** Pixels per dp of this display. */
internal val screenDensity: Float get() = Resources.getSystem().displayMetrics.density

/** [value] dp in pixels. */
fun dp(value: Float): Float = value * screenDensity

fun dp(value: Int): Int = Math.round(value * screenDensity)

/** A text size in px for [value] sp. */
internal fun sp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, Resources.getSystem().displayMetrics)

/** One spacing unit of the current theme, in pixels (a third of a space; `gap = 3` is three of them). */
internal fun unitPx(): Float = dp(Hashsome.theme.density.unit)

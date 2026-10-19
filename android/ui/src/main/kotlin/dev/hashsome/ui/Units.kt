package dev.hashsome.ui

import android.content.res.Resources

// Device-independent lengths. The dashboards are laid out in CSS pixels, which are dp here: the page and the app share one grid.

/** Pixels per dp of this display. */
internal val screenDensity: Float get() = Resources.getSystem().displayMetrics.density

/** [value] dp in pixels. */
fun dp(value: Float): Float = value * screenDensity

fun dp(value: Int): Int = Math.round(value * screenDensity)

/**
 * A text size in px for [value] dp, not sp: the web's sizes are CSS pixels, which do not follow a font-scale setting, and the
 * fixed-height tiles of a wall display must keep fitting their text.
 */
internal fun textPx(value: Float): Float = dp(value)

/** One spacing unit of the current theme, in pixels (a third of a space; `gap = 3` is three of them). */
internal fun unitPx(): Float = dp(Hashsome.theme.density.unit)

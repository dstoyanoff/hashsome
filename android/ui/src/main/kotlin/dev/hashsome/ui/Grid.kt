package dev.hashsome.ui

// The module grid, ported from packages/ui/src/theme/grid.ts. Pure Kotlin so it is tested on the JVM. Lengths are dp.

/** The icon badge a tile starts with, in dp: the one size in a tile that does not scale with density. */
const val BADGE = 32

/** The height of a room header, in dp. */
const val HEADER = 32

/** The height of the top row, in dp (the clock's line and the tallest things beside it). */
const val TOP_ROW = 40

/** The width of the page navigation rail, whose icons are centered in it. */
const val NAV_RAIL = 64

/**
 * The vertical grid every card sits on. The module is the theme's spacing unit (a third of a space), so the gap between
 * cards is always 3 modules, and a tile and a header are whole numbers of them in either density.
 */
data class GridMetrics(
    val module: Float,
    val gap: Float,
    val tile: Float,
    val header: Float,
    /** A card's height plus the gap that follows it. */
    val tilePitch: Float,
    val headerPitch: Float,
)

fun gridMetrics(space: Int): GridMetrics {
    val module = space / ResolvedDensity.UNITS_PER_SPACE.toFloat()
    // A tile is its badge between two spacing-unit-2 paddings.
    val tile = BADGE + 4 * module
    return GridMetrics(module, space.toFloat(), tile, HEADER.toFloat(), tile + space, HEADER + space.toFloat())
}

/** Whether the top edge or the height of a box is off the module grid (a little under a pixel is still on it). */
fun offGrid(top: Float, height: Float, module: Float): Boolean {
    val tolerance = maxOf(0.5f, module * 0.15f)
    fun off(value: Float): Boolean {
        val rest = Math.abs(value) % module
        return minOf(rest, module - rest) > tolerance
    }
    return off(top) || off(height)
}

/** How the page's leftover height (under one module after as many whole ones as fit) is shared between the top and bottom padding. */
data class Centering(val top: Int, val bottom: Int)

/** Half each, in whole units, the odd one to the bottom. [available] is the height inside the page's padding. */
fun centeringOffsets(available: Float, module: Float): Centering {
    val leftover = if (available > 0) Math.floor((available % module).toDouble()).toInt() else 0
    val top = leftover / 2
    return Centering(top, leftover - top)
}

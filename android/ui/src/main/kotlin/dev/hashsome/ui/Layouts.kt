package dev.hashsome.ui

import android.content.Context
import android.view.ViewGroup

/** Cross-axis placement of a child inside a [ColumnLayout] or [RowLayout]. */
enum class Align { Start, Center, End, Stretch }

/** Main-axis placement of the children when they do not fill the layout. */
enum class Justify { Start, Center, End, SpaceBetween }

/** How many board rows a [BoardLayout] cell spans: a fixed number, or all that is left ([Fill]). */
sealed interface RowSpan {
    data class Rows(val count: Int) : RowSpan
    data object Fill : RowSpan
}

/** The per-child parameters of the flex layouts. Sizes are dp (a `dp` int, converted when measured). */
open class FlexParams(width: Int, height: Int) : ViewGroup.MarginLayoutParams(width, height) {
    /** Takes the space left over on the main axis, shared with the other children that grow. */
    var grow: Boolean = false
    var align: Align? = null
    var minHeightDp: Int = 0
}

/**
 * Lays its children out top to bottom: [gap] (in spacing units, as the web's `gap={3}`) between them, children
 * with `grow` sharing what is left. The measuring is the pure `FlexMath`, unit-tested without a device.
 */
open class ColumnLayout(context: Context) : ViewGroup(context) {
    var gap: Int = 0
    var align: Align = Align.Stretch
    var justify: Justify = Justify.Start

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int): Unit = TODO("WP3")
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int): Unit = TODO("WP3")
    override fun generateDefaultLayoutParams(): LayoutParams = FlexParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
}

/** Lays its children out left to right; see [ColumnLayout]. */
open class RowLayout(context: Context) : ViewGroup(context) {
    var gap: Int = 0
    var align: Align = Align.Center
    var justify: Justify = Justify.Start

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int): Unit = TODO("WP3")
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int): Unit = TODO("WP3")
    override fun generateDefaultLayoutParams(): LayoutParams = FlexParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
}

/** [columns] equal columns; children fill cells in order, rows as tall as their tallest child. */
open class GridLayout(context: Context, var columns: Int) : ViewGroup(context) {
    var gap: Int = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int): Unit = TODO("WP3")
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int): Unit = TODO("WP3")
    override fun generateDefaultLayoutParams(): LayoutParams = FlexParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
}

/** The per-child parameters of a [BoardLayout]: how many columns of the board it spans, and how many rows. */
class CellParams(val cols: Int, val rows: RowSpan) : FlexParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

/** A 12-column board (the web's `Board`): cells are placed first-fit by `BoardPlacer`, which is pure and unit-tested. */
open class BoardLayout(context: Context, val columns: Int = 12) : ViewGroup(context) {
    var gap: Int = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int): Unit = TODO("WP3")
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int): Unit = TODO("WP3")
    override fun generateDefaultLayoutParams(): LayoutParams = CellParams(columns, RowSpan.Rows(1))
}

/** Where a cell goes on the board: its first column and row, and how many it covers (a Fill row span covers to the end). */
data class Placement(val col: Int, val row: Int, val cols: Int, val rows: Int)

/** First-fit placement of board cells; pure Kotlin so it is tested on the JVM. */
object BoardPlacer {
    /** One placement per cell of [spans] (cols, rows; [rowsFill] marks the cells that fill), in order. */
    fun place(columns: Int, cells: List<Pair<Int, RowSpan>>): List<Placement> = TODO("WP3")
}

/** The flexbox arithmetic of [ColumnLayout] and [RowLayout], pure so it is tested on the JVM. */
object FlexMath {
    /** The main-axis sizes of children given their [basis] sizes, which of them [grow], the [available] space and the [gap]. */
    fun distribute(basis: IntArray, grow: BooleanArray, available: Int, gap: Int): IntArray = TODO("WP3")
}

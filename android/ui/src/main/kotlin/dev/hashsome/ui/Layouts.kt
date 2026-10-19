package dev.hashsome.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.View.MeasureSpec
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

    /** Whether a child that [grow]s also gives way when the layout is too small for its children (a panel's scrolling content); else it keeps its content's size. */
    var shrink: Boolean = false
}

private fun flexParamsOf(p: ViewGroup.LayoutParams): FlexParams {
    if (p is FlexParams) return p
    return FlexParams(p.width, p.height).also { flex ->
        if (p is ViewGroup.MarginLayoutParams) flex.setMargins(p.leftMargin, p.topMargin, p.rightMargin, p.bottomMargin)
    }
}

/**
 * Lays its children out top to bottom: [gap] (in spacing units, as the web's `gap={3}`) between them, children
 * with `grow` sharing what is left. The measuring is the pure `FlexMath`, unit-tested without a device.
 */
open class ColumnLayout(context: Context) : ViewGroup(context) {
    var gap: Int = 0
    var align: Align = Align.Stretch
    var justify: Justify = Justify.Start
    private val box = FlexBox(vertical = true)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        box.measure(this, widthMeasureSpec, heightMeasureSpec, gap, align, { w, h -> setMeasuredDimension(w, h) })
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        box.layout(this, r - l, b - t, align, justify)
    }

    override fun generateDefaultLayoutParams(): LayoutParams = FlexParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = FlexParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(p: LayoutParams): LayoutParams = flexParamsOf(p)
    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is FlexParams
}

/** Lays its children out left to right; see [ColumnLayout]. */
open class RowLayout(context: Context) : ViewGroup(context) {
    var gap: Int = 0
    var align: Align = Align.Center
    var justify: Justify = Justify.Start
    private val box = FlexBox(vertical = false)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        box.measure(this, widthMeasureSpec, heightMeasureSpec, gap, align, { w, h -> setMeasuredDimension(w, h) })
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        box.layout(this, r - l, b - t, align, justify)
    }

    override fun generateDefaultLayoutParams(): LayoutParams = FlexParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = FlexParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(p: LayoutParams): LayoutParams = flexParamsOf(p)
    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is FlexParams
}

/** [columns] equal columns; children fill cells in order, rows as tall as their tallest child. */
open class GridLayout(context: Context, var columns: Int) : ViewGroup(context) {
    var gap: Int = 0
    private var lefts = IntArray(0)
    private var tops = IntArray(0)
    private var widths = IntArray(0)
    private var heights = IntArray(0)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val n = childCount
        val cols = columns.coerceAtLeast(1)
        val gapPx = Math.round(gap * unitPx())
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val inner = width - paddingLeft - paddingRight
        lefts = IntArray(n); tops = IntArray(n); widths = IntArray(n); heights = IntArray(n)
        // Column edges are rounded cumulatively, so the cells differ by a pixel at most and the gaps do not drift.
        val cell = (inner - gapPx * (cols - 1)).toFloat() / cols
        fun edge(col: Int) = Math.round(col * (cell + gapPx))
        // A child that is gone takes no cell, as `display: none` takes none in a CSS grid.
        val slot = GridSlots.of(BooleanArray(n) { getChildAt(it).visibility != GONE })
        val rows = (slot.count { it >= 0 } + cols - 1) / cols
        val rowHeights = IntArray(rows)
        for (i in 0 until n) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val col = slot[i] % cols
            widths[i] = edge(col + 1) - gapPx - edge(col)
            lefts[i] = paddingLeft + edge(col)
            val lp = child.layoutParams as FlexParams
            child.measure(
                MeasureSpec.makeMeasureSpec(widths[i], MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            rowHeights[slot[i] / cols] = maxOf(rowHeights[slot[i] / cols], child.measuredHeight, dp(lp.minHeightDp))
        }
        var y = paddingTop
        val rowTops = IntArray(rows)
        for (r in 0 until rows) {
            rowTops[r] = y
            y += rowHeights[r] + if (r < rows - 1) gapPx else 0
        }
        for (i in 0 until n) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val r = slot[i] / cols
            tops[i] = rowTops[r]
            heights[i] = rowHeights[r]
            // A cell stretches to the row, as a CSS grid item does.
            if (child.measuredHeight != heights[i]) {
                child.measure(
                    MeasureSpec.makeMeasureSpec(widths[i], MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(heights[i], MeasureSpec.EXACTLY),
                )
            }
        }
        val content = y + paddingBottom
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(content, MeasureSpec.getSize(heightMeasureSpec))
            else -> content
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.layout(lefts[i], tops[i], lefts[i] + child.measuredWidth, tops[i] + child.measuredHeight)
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams = FlexParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = FlexParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(p: LayoutParams): LayoutParams = flexParamsOf(p)
    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is FlexParams
}

/** Which cell, counting from the first, each child of a grid takes: -1 for one that is not [visible] (it takes none). */
internal object GridSlots {
    fun of(visible: BooleanArray): IntArray {
        var next = 0
        return IntArray(visible.size) { if (visible[it]) next++ else -1 }
    }
}

/** The per-child parameters of a [BoardLayout]: how many columns of the board it spans, and how many rows. */
class CellParams(val cols: Int, val rows: RowSpan) : FlexParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

/**
 * A 12-column board (the web's `Board`): cells are placed by `BoardPlacer`, which is pure and unit-tested. Columns are
 * [gap] apart and rows twice that; a row is as tall as the cells of one row in it, and a cell of several rows or one that
 * fills takes the height the rows and the board give it without sizing them (the web's `contain: size`).
 */
open class BoardLayout(context: Context, val columns: Int = 12) : ViewGroup(context) {
    var gap: Int = 0
    private var lefts = IntArray(0)
    private var tops = IntArray(0)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val n = childCount
        val gapPx = Math.round(gap * unitPx())
        val rowGap = gapPx * 2
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val inner = width - paddingLeft - paddingRight
        val cellW = (inner - gapPx * (columns - 1)).toFloat() / columns
        fun edge(col: Int) = Math.round(col * (cellW + gapPx))
        lefts = IntArray(n); tops = IntArray(n)

        val paramsOf = Array(n) { (getChildAt(it).layoutParams as? CellParams) ?: CellParams(columns, RowSpan.Rows(1)) }
        val visible = (0 until n).filter { getChildAt(it).visibility != GONE }
        val placements = BoardPlacer.place(columns, visible.map { paramsOf[it].cols to paramsOf[it].rows })
        val place = arrayOfNulls<Placement>(n)
        visible.forEachIndexed { k, i -> place[i] = placements[k] }
        fun widthOf(p: Placement) = edge(p.col + p.cols) - gapPx - edge(p.col)

        val rowCount = placements.maxOfOrNull { it.row + it.rows } ?: 0
        val rowHeights = IntArray(rowCount)
        // Pass 1: the cells of one row, which are what size the rows.
        for (i in visible) {
            val p = place[i]!!
            val rows = paramsOf[i].rows
            if (rows is RowSpan.Fill || p.rows > 1) continue
            val child = getChildAt(i)
            child.measure(
                MeasureSpec.makeMeasureSpec(widthOf(p), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            rowHeights[p.row] = maxOf(rowHeights[p.row], child.measuredHeight)
        }
        val rowTops = IntArray(rowCount)
        var y = paddingTop
        for (r in 0 until rowCount) {
            rowTops[r] = y
            y += rowHeights[r] + if (r < rowCount - 1) rowGap else 0
        }
        val rowsBottom = y
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val boardBottom = if (heightMode == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) - paddingBottom else rowsBottom

        // Pass 2: cells that reach across rows take the rows' height, as tall as they together are.
        var lowest = rowsBottom
        for (i in visible) {
            val p = place[i]!!
            val child = getChildAt(i)
            lefts[i] = paddingLeft + edge(p.col)
            tops[i] = rowTops[p.row]
            if (paramsOf[i].rows is RowSpan.Fill) continue
            if (p.rows > 1) {
                val last = p.row + p.rows - 1
                val h = rowTops[last] + rowHeights[last] - rowTops[p.row]
                child.measure(
                    MeasureSpec.makeMeasureSpec(widthOf(p), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
                )
            }
            lowest = maxOf(lowest, tops[i] + child.measuredHeight)
        }
        // A cell that fills runs from its top to the bottom of the board, or of the others' content if that is further.
        val bottom = maxOf(boardBottom, lowest)
        for (i in visible) {
            if (paramsOf[i].rows !is RowSpan.Fill) continue
            val p = place[i]!!
            val h = (bottom - tops[i]).coerceAtLeast(0)
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(widthOf(p), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
            )
        }
        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(lowest + paddingBottom, MeasureSpec.getSize(heightMeasureSpec))
            else -> lowest + paddingBottom
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.layout(lefts[i], tops[i], lefts[i] + child.measuredWidth, tops[i] + child.measuredHeight)
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams = CellParams(columns, RowSpan.Rows(1))
    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = CellParams(columns, RowSpan.Rows(1))
    override fun generateLayoutParams(p: LayoutParams): LayoutParams =
        CellParams(columns, RowSpan.Rows(1)).also { cell ->
            if (p is MarginLayoutParams) cell.setMargins(p.leftMargin, p.topMargin, p.rightMargin, p.bottomMargin)
        }
    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is CellParams
}

/** Where a cell goes on the board: its first column and row, and how many it covers. A cell that fills covers one row here and is stretched by the layout. */
data class Placement(val col: Int, val row: Int, val cols: Int, val rows: Int)

/** Placement of board cells; pure Kotlin so it is tested on the JVM. */
object BoardPlacer {
    /**
     * One placement per cell of [cells] (columns wide, rows tall), in order, as a CSS grid auto-places them: each cell goes in
     * the first free place at or after the previous cell's, so a later cell never fills a gap an earlier one left behind. A cell
     * wider than the board is as wide as the board. A [RowSpan.Fill] cell covers one row, like the web's, and the layout stretches it.
     */
    fun place(columns: Int, cells: List<Pair<Int, RowSpan>>): List<Placement> {
        val taken = ArrayList<BooleanArray>()
        fun row(r: Int): BooleanArray {
            while (taken.size <= r) taken.add(BooleanArray(columns))
            return taken[r]
        }
        fun free(r: Int, col: Int, cols: Int, rows: Int): Boolean {
            for (y in r until r + rows) for (x in col until col + cols) if (row(y)[x]) return false
            return true
        }
        val out = ArrayList<Placement>(cells.size)
        var cursorRow = 0
        var cursorCol = 0
        for ((wanted, span) in cells) {
            val cols = wanted.coerceIn(1, columns)
            val rows = if (span is RowSpan.Rows) span.count.coerceAtLeast(1) else 1
            var r = cursorRow
            var col = cursorCol
            while (true) {
                if (col + cols > columns) {
                    r++
                    col = 0
                } else if (free(r, col, cols, rows)) {
                    break
                } else {
                    col++
                }
            }
            for (y in r until r + rows) for (x in col until col + cols) row(y)[x] = true
            out.add(Placement(col, r, cols, rows))
            cursorRow = r
            cursorCol = col
        }
        return out
    }
}

/** The flexbox arithmetic of [ColumnLayout] and [RowLayout], pure so it is tested on the JVM. */
object FlexMath {
    /**
     * The main-axis sizes of children given their [basis] sizes, which of them [grow], the [available] space and the [gap]
     * between children. The space left over is shared equally among the growers (the remainder pixels to the first ones). If
     * the children do not fit, only the growers give way, in proportion to how much each has to give and never below its [min]
     * (a grower that must not shrink, as the web board's `flex: 1 0 auto`, has its basis as its min).
     */
    fun distribute(basis: IntArray, grow: BooleanArray, available: Int, gap: Int, min: IntArray = IntArray(basis.size)): IntArray {
        val n = basis.size
        val sizes = basis.copyOf()
        val growers = (0 until n).count { grow[it] }
        if (n == 0 || growers == 0) return sizes
        val leftover = available - basis.sum() - gap * (n - 1)
        if (leftover > 0) {
            val share = leftover / growers
            var extra = leftover % growers
            for (i in 0 until n) {
                if (!grow[i]) continue
                sizes[i] += share + if (extra > 0) 1 else 0
                if (extra > 0) extra--
            }
        } else if (leftover < 0) {
            var deficit = -leftover
            // How far each grower may give way: down to its [min], never below.
            val give = IntArray(n) { if (grow[it]) (basis[it] - min[it]).coerceAtLeast(0) else 0 }
            val room = give.sum()
            if (room > 0) {
                val wanted = deficit
                for (i in 0 until n) {
                    if (give[i] == 0) continue
                    val cut = minOf(give[i], (wanted.toLong() * give[i] / room).toInt())
                    sizes[i] -= cut
                    deficit -= cut
                }
                // What rounding left comes off the first growers that still have something to give.
                for (i in 0 until n) {
                    if (deficit <= 0) break
                    val left = sizes[i] - min[i]
                    if (give[i] == 0 || left <= 0) continue
                    val cut = minOf(left, deficit)
                    sizes[i] -= cut
                    deficit -= cut
                }
            }
        }
        return sizes
    }
}

/** The measuring and placing both flex layouts share. [vertical] is a column. */
internal class FlexBox(private val vertical: Boolean) {
    private var sizes = IntArray(0)
    private var crosses = IntArray(0)
    private var gapPx = 0
    private var contentMain = 0

    private fun View.flex(): FlexParams = layoutParams as FlexParams
    private fun FlexParams.mainMargins() = if (vertical) topMargin + bottomMargin else leftMargin + rightMargin
    private fun FlexParams.crossMargins() = if (vertical) leftMargin + rightMargin else topMargin + bottomMargin
    private fun FlexParams.mainDim() = if (vertical) height else width
    private fun FlexParams.crossDim() = if (vertical) width else height
    private fun View.mainMeasured() = if (vertical) measuredHeight else measuredWidth
    private fun View.crossMeasured() = if (vertical) measuredWidth else measuredHeight
    private fun spec(size: Int, mode: Int) = MeasureSpec.makeMeasureSpec(maxOf(0, size), mode)
    private fun measureChild(child: View, main: Int, mainMode: Int, cross: Int, crossMode: Int) {
        if (vertical) child.measure(spec(cross, crossMode), spec(main, mainMode)) else child.measure(spec(main, mainMode), spec(cross, crossMode))
    }

    fun measure(v: ViewGroup, widthSpec: Int, heightSpec: Int, gapUnits: Int, align: Align, setSize: (Int, Int) -> Unit) {
        val n = v.childCount
        gapPx = Math.round(gapUnits * unitPx())
        val padMain = if (vertical) v.paddingTop + v.paddingBottom else v.paddingLeft + v.paddingRight
        val padCross = if (vertical) v.paddingLeft + v.paddingRight else v.paddingTop + v.paddingBottom
        val mainSpec = if (vertical) heightSpec else widthSpec
        val crossSpec = if (vertical) widthSpec else heightSpec
        val mainMode = MeasureSpec.getMode(mainSpec)
        val crossMode = MeasureSpec.getMode(crossSpec)
        val availMain = MeasureSpec.getSize(mainSpec) - padMain
        val availCross = MeasureSpec.getSize(crossSpec) - padCross

        val basis = IntArray(n)
        val grow = BooleanArray(n)
        crosses = IntArray(n)
        var crossMax = 0
        var visibleCount = 0
        for (i in 0 until n) {
            val child = v.getChildAt(i)
            if (child.visibility == View.GONE) continue
            visibleCount++
            val lp = child.flex()
            val mm = lp.mainMargins()
            val mc = lp.crossMargins()
            val childAlign = lp.align ?: align
            // The cross size: the layout's width for a stretched child, else what the child wants within it.
            val crossFixed = lp.crossDim() > 0
            val stretch = childAlign == Align.Stretch && crossMode != MeasureSpec.UNSPECIFIED
            val (cs, cm) = when {
                crossFixed -> lp.crossDim() to MeasureSpec.EXACTLY
                stretch -> availCross - mc to MeasureSpec.EXACTLY
                crossMode == MeasureSpec.UNSPECIFIED -> 0 to MeasureSpec.UNSPECIFIED
                else -> availCross - mc to MeasureSpec.AT_MOST
            }
            val mainFixed = lp.mainDim() > 0
            val (ms, mmode) = when {
                mainFixed -> lp.mainDim() to MeasureSpec.EXACTLY
                // A row's children wrap within the room there is; a column's take the height they want.
                !vertical && mainMode != MeasureSpec.UNSPECIFIED -> availMain - mm to MeasureSpec.AT_MOST
                else -> 0 to MeasureSpec.UNSPECIFIED
            }
            measureChild(child, ms, mmode, cs, cm)
            val minMain = if (vertical) dp(lp.minHeightDp) else 0
            if (minMain > child.mainMeasured()) measureChild(child, minMain, MeasureSpec.EXACTLY, cs, cm)
            basis[i] = child.mainMeasured() + mm
            grow[i] = lp.grow || lp.mainDim() == ViewGroup.LayoutParams.MATCH_PARENT
            val crossNeeded = maxOf(child.crossMeasured(), if (!vertical) dp(lp.minHeightDp) else 0)
            crosses[i] = crossNeeded
            crossMax = maxOf(crossMax, crossNeeded + mc)
        }

        // A layout that wraps its content is the sum of its children, unless that is more than it may be (a sheet's height).
        // A grower keeps at least its content, unless it says it shrinks.
        val minSizes = IntArray(n) { i ->
            val c = v.getChildAt(i)
            if (c.visibility == View.GONE || c.flex().shrink) 0 else basis[i].coerceAtLeast(0)
        }
        val overfull = mainMode == MeasureSpec.AT_MOST && basis.sum() + gapPx * maxOf(0, visibleCount - 1) > availMain
        sizes = if (mainMode == MeasureSpec.EXACTLY || overfull) FlexMath.distribute(basis, grow, availMain, gapPx, minSizes) else basis
        for (i in 0 until n) {
            val child = v.getChildAt(i)
            if (child.visibility == View.GONE || sizes[i] == basis[i]) continue
            val lp = child.flex()
            val mc = lp.crossMargins()
            val childAlign = lp.align ?: align
            val stretch = childAlign == Align.Stretch && crossMode != MeasureSpec.UNSPECIFIED
            val (cs, cm) = when {
                lp.crossDim() > 0 -> lp.crossDim() to MeasureSpec.EXACTLY
                stretch -> availCross - mc to MeasureSpec.EXACTLY
                else -> maxOf(0, availCross - mc) to MeasureSpec.AT_MOST
            }
            measureChild(child, sizes[i] - lp.mainMargins(), MeasureSpec.EXACTLY, cs, cm)
            crosses[i] = maxOf(child.crossMeasured(), if (!vertical) dp(lp.minHeightDp) else 0)
            crossMax = maxOf(crossMax, crosses[i] + mc)
        }

        val used = sizes.sum() + gapPx * maxOf(0, visibleCount - 1)
        contentMain = used
        val ownMain = when (mainMode) {
            MeasureSpec.EXACTLY -> availMain + padMain
            MeasureSpec.AT_MOST -> minOf(used + padMain, availMain + padMain)
            else -> used + padMain
        }
        val ownCross = when (crossMode) {
            MeasureSpec.EXACTLY -> availCross + padCross
            MeasureSpec.AT_MOST -> minOf(crossMax + padCross, availCross + padCross)
            else -> crossMax + padCross
        }
        // Children that stretch across a layout that sized itself to its content take its cross size.
        if (crossMode != MeasureSpec.EXACTLY) {
            val inner = ownCross - padCross
            for (i in 0 until n) {
                val child = v.getChildAt(i)
                if (child.visibility == View.GONE) continue
                val lp = child.flex()
                if ((lp.align ?: align) != Align.Stretch || lp.crossDim() > 0) continue
                val want = inner - lp.crossMargins()
                if (child.crossMeasured() != want) {
                    measureChild(child, sizes[i] - lp.mainMargins(), MeasureSpec.EXACTLY, want, MeasureSpec.EXACTLY)
                    crosses[i] = child.crossMeasured()
                }
            }
        }
        if (vertical) setSize(ownCross, ownMain) else setSize(ownMain, ownCross)
    }

    fun layout(v: ViewGroup, width: Int, height: Int, align: Align, justify: Justify) {
        val n = v.childCount
        val padMainStart = if (vertical) v.paddingTop else v.paddingLeft
        val padCrossStart = if (vertical) v.paddingLeft else v.paddingTop
        val mainSize = (if (vertical) height else width) - (if (vertical) v.paddingTop + v.paddingBottom else v.paddingLeft + v.paddingRight)
        val crossSize = (if (vertical) width else height) - (if (vertical) v.paddingLeft + v.paddingRight else v.paddingTop + v.paddingBottom)
        val visible = (0 until n).count { v.getChildAt(it).visibility != View.GONE }
        val free = mainSize - contentMain
        var pos = padMainStart
        var between = gapPx
        when (justify) {
            Justify.Start -> {}
            Justify.Center -> pos += maxOf(0, free) / 2
            Justify.End -> pos += maxOf(0, free)
            Justify.SpaceBetween -> if (visible > 1 && free > 0) {
                // Whole pixels, the remainder spread over the first gaps.
                between = gapPx + free / (visible - 1)
            }
        }
        var extra = if (justify == Justify.SpaceBetween && visible > 1 && free > 0) free % (visible - 1) else 0
        for (i in 0 until n) {
            val child = v.getChildAt(i)
            if (child.visibility == View.GONE) continue
            val lp = child.flex()
            val mainStartMargin = if (vertical) lp.topMargin else lp.leftMargin
            val crossStartMargin = if (vertical) lp.leftMargin else lp.topMargin
            val outerCross = child.crossMeasured() + lp.crossMargins()
            val a = lp.align ?: align
            val crossPos = padCrossStart + crossStartMargin + when (a) {
                Align.Start, Align.Stretch -> 0
                Align.Center -> (crossSize - outerCross) / 2
                Align.End -> crossSize - outerCross
            }
            val mainPos = pos + mainStartMargin
            if (vertical) child.layout(crossPos, mainPos, crossPos + child.measuredWidth, mainPos + child.measuredHeight)
            else child.layout(mainPos, crossPos, mainPos + child.measuredWidth, crossPos + child.measuredHeight)
            pos += sizes[i] + between + if (extra > 0) 1 else 0
            if (extra > 0) extra--
        }
    }
}


/**
 * The card pages put their lists and players in: the web's `Flex background="surface" radius="card" p={3}`. A [ColumnLayout] that
 * draws the theme's surface color with the card radius, pads itself 3 spacing units, and clips what it holds to its corners
 * (`overflow: hidden`). No border and no shadow, as on the web.
 */
open class SurfaceView(context: Context) : ColumnLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()

    init {
        setWillNotDraw(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val pad = Math.round(PADDING_UNITS * unitPx())
        if (paddingLeft != pad || paddingTop != pad || paddingRight != pad || paddingBottom != pad) setPadding(pad, pad, pad, pad)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun shape() {
        val r = minOf(dp(theme.radius.card), minOf(width, height) / 2f)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        shape()
        paint.color = theme.palette.surface
        canvas.drawPath(clip, paint)
    }

    override fun dispatchDraw(canvas: Canvas) {
        shape()
        canvas.save()
        canvas.clipPath(clip)
        super.dispatchDraw(canvas)
        canvas.restore()
    }

    companion object {
        /** The card's padding, in spacing units (the web's `p={3}`). */
        const val PADDING_UNITS = 3
    }
}

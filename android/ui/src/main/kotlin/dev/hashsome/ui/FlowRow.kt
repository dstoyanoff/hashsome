package dev.hashsome.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Where a flow row breaks its lines, as pure math (px). */
internal object FlowMath {
    /**
     * The index each line starts at, for children of [widths] laid left to right in [available] with [gap] between them: a child
     * stays on the line while it fits, and starts a new one when it does not. A child wider than the line has a line of its own.
     */
    fun lineStarts(widths: IntArray, available: Int, gap: Int): IntArray {
        val starts = ArrayList<Int>()
        var x = 0
        for (i in widths.indices) {
            if (i == 0) {
                starts += 0
                x = widths[0]
            } else if (x + gap + widths[i] <= available) {
                x += gap + widths[i]
            } else {
                starts += i
                x = widths[i]
            }
        }
        return starts.toIntArray()
    }
}

/**
 * Children keep their own width and fill each line left to right, [gap] (density units) apart, wrapping to a new line only when
 * the next one does not fit; lines are as tall as their tallest child. A child that is gone takes no place.
 */
open class FlowRow(context: Context) : ViewGroup(context) {
    var gap: Int = 0

    private var lefts = IntArray(0)
    private var tops = IntArray(0)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val n = childCount
        val gapPx = Math.round(gap * unitPx())
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val shown = (0 until n).filter { getChildAt(it).visibility != GONE }
        val widths = IntArray(shown.size)
        shown.forEachIndexed { k, i ->
            val c = getChildAt(i)
            c.measure(MeasureSpec.makeMeasureSpec(maxOf(available, 0), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            widths[k] = c.measuredWidth
        }
        lefts = IntArray(n)
        tops = IntArray(n)
        val starts = FlowMath.lineStarts(widths, available, gapPx)
        var y = paddingTop
        var widest = 0
        for (line in starts.indices) {
            val from = starts[line]
            val to = if (line + 1 < starts.size) starts[line + 1] else shown.size
            var x = paddingLeft
            var tall = 0
            for (k in from until to) {
                val i = shown[k]
                lefts[i] = x
                tops[i] = y
                x += widths[k] + gapPx
                tall = maxOf(tall, getChildAt(i).measuredHeight)
            }
            widest = maxOf(widest, x - gapPx - paddingLeft)
            y += tall + if (line < starts.size - 1) gapPx else 0
        }
        setMeasuredDimension(
            if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec) else widest + paddingLeft + paddingRight,
            y + paddingBottom,
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c: View = getChildAt(i)
            if (c.visibility == GONE) continue
            c.layout(lefts[i], tops[i], lefts[i] + c.measuredWidth, tops[i] + c.measuredHeight)
        }
    }
}

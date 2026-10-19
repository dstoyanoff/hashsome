package dev.hashsome.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat

/** How a time is written on a chart: under the axis for a [SeriesRange], and in a tooltip. Kept per locale, as the formats are looked up once. */
internal object ChartText {
    private val formats = HashMap<String, SimpleDateFormat>()

    private fun format(skeleton: String): SimpleDateFormat {
        val locale = Locale.getDefault()
        return formats.getOrPut("$locale|$skeleton") { SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale) }
    }

    /** A tick under the axis of [range]: the time of day, the weekday or the date. */
    fun tick(range: SeriesRange, atMs: Long): String = format(ChartMath.tickSkeleton(range)).format(Date(atMs))

    /** Date and time, for a tooltip: `Mar 4, 2:30 PM`. */
    fun moment(atMs: Long): String = format("MMMdjm").format(Date(atMs))

    /** The hour of day alone, `2 PM`. */
    fun hour(atMs: Long): String = format("j").format(Date(atMs))

    /** The weekday written out, `Wednesday`. */
    fun weekday(atMs: Long): String = format("EEEE").format(Date(atMs))
}

/**
 * A series as a line over a faint area of the same color (the web's `SeriesChart` / `series-plot.tsx`). Collapsed it is a bare
 * sparkline; [expanded] adds the y axis (36 dp wide), dashed horizontal grid, a solid bottom edge and ticks under it. Touching it
 * (and dragging along) marks the nearest reading and tells [onScrub]; expanded, a tooltip beside the mark says what it was. The
 * points are spread evenly, as the web's category axis spreads them.
 *
 * All the work is done when the series or the size changes; drawing only draws what that made.
 */
open class SeriesChartView(context: Context) : View(context) {
    /** The readings, oldest first. */
    var samples: List<SeriesSample> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            dirty = true
            // A mark on a reading that is gone would stay, with its readout.
            release()
            requestLayout()
            invalidate()
        }

    /** What the axis under the chart counts in. */
    var range: SeriesRange = SeriesRange.Day
        set(value) {
            field = value
            dirty = true
            invalidate()
        }

    var expanded: Boolean = false
        set(value) {
            field = value
            dirty = true
            requestLayout()
            invalidate()
        }

    var unit: String = ""
        set(value) {
            field = value
            dirty = true
            invalidate()
        }

    /** Fit the y axis to the data instead of starting at 0 (right for a temperature, wrong for power). Used by the expanded chart, as on the web. */
    var fitDomain: Boolean = false
        set(value) {
            field = value
            dirty = true
            invalidate()
        }

    /** The chart's height in dp. 48 for the sparkline, 220 when expanded, unless set. */
    var heightDp: Int? = null
        set(value) {
            field = value
            requestLayout()
        }

    /** Told which reading the finger is on, and null when it lets go. */
    var onScrub: ((SeriesSample?) -> Unit)? = null

    private var dirty = true
    private var scrubbed = -1

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tick = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val tipSmall = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val tipStrong = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val linePath = Path()
    private val areaPath = Path()

    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var slopes = FloatArray(0)
    private var labelAt = IntArray(0)
    private var labelWidths = FloatArray(0)
    private var labels = arrayOf<String>()
    private var labelCount = 0
    private var yTicks = DoubleArray(0)
    private var yLabels = arrayOf<String>()
    private var yPositions = FloatArray(0)
    private var count = 0
    private var left = 0f
    private var top = 0f
    private var right = 0f
    private var bottom = 0f

    // The tooltip's lines, made when the finger moves to another reading.
    private var tip = arrayOf<String>()
    private val settle = Runnable { release() }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var horizontal = false

    private fun chartHeight(): Int = dp(heightDp ?: if (expanded) 220 else 48)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), chartHeight())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        dirty = true
    }

    private fun rebuild() {
        dirty = false
        val t = theme
        count = samples.size
        linePath.rewind()
        areaPath.rewind()
        labelCount = 0
        yTicks = DoubleArray(0)
        if (count == 0 || width == 0) return
        if (xs.size < count) {
            xs = FloatArray(count)
            ys = FloatArray(count)
            slopes = FloatArray(count)
            labelAt = IntArray(count)
            labelWidths = FloatArray(count)
            labels = Array(count) { "" }
        }

        left = if (expanded) dp(Y_AXIS) else 0f
        top = dp(MARGIN_TOP)
        right = width.toFloat()
        bottom = height - if (expanded) dp(X_AXIS) else 0f
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (s in samples) {
            lo = minOf(lo, s.value)
            hi = maxOf(hi, s.value)
        }
        // A chart with no y axis of its own (the sparkline) has the axis every chart starts with, from 0.
        val scale = ChartMath.niceScale(lo, hi, zeroBased = !(expanded && fitDomain))
        val span = (scale.max - scale.min).let { if (it <= 0.0) 1.0 else it }
        for (i in 0 until count) {
            xs[i] = if (count == 1) (left + right) / 2 else left + (right - left) * i / (count - 1)
            ys[i] = bottom - ((samples[i].value - scale.min) / span).toFloat() * (bottom - top)
        }

        ChartMath.monotoneSlopes(xs, ys, count, slopes)
        linePath.moveTo(xs[0], ys[0])
        for (i in 1 until count) {
            val dx = (xs[i] - xs[i - 1]) / 3f
            linePath.cubicTo(xs[i - 1] + dx, ys[i - 1] + slopes[i - 1] * dx, xs[i] - dx, ys[i] - slopes[i] * dx, xs[i], ys[i])
        }
        areaPath.addPath(linePath)
        areaPath.lineTo(xs[count - 1], bottom)
        areaPath.lineTo(xs[0], bottom)
        areaPath.close()

        if (!expanded) return
        yTicks = scale.ticks
        yLabels = Array(yTicks.size) { ChartMath.axisNumber(yTicks[it]) }
        yPositions = FloatArray(yTicks.size) { bottom - ((yTicks[it] - scale.min) / span).toFloat() * (bottom - top) }
        tick.style(t, Role.Secondary, t.palette.textMuted)
        for (i in 0 until count) {
            // Only a point that could be written is worth formatting: the widths come from labels made for every point.
            labels[i] = ChartText.tick(range, samples[i].atMs)
            labelWidths[i] = tick.measureText(labels[i])
        }
        labelCount = ChartMath.labelIndices(xs, labelWidths, count, dp(MIN_TICK_GAP), labelAt)
    }

    override fun onDraw(canvas: Canvas) {
        if (dirty) rebuild()
        if (count == 0) return
        val t = theme
        val p = t.palette
        if (expanded) {
            grid.color = withAlpha(p.line, 0.3f)
            grid.strokeWidth = dp(1f)
            grid.pathEffect = dash
            tick.style(t, Role.Secondary, p.textMuted)
            for (i in yTicks.indices) {
                val y = yPositions[i]
                // The bottom edge is the solid axis line, so no dashed line goes under it.
                if (y < bottom - 1f) canvas.drawLine(left, y, right, y, grid)
                canvas.drawTextCentered(yLabels[i], left - dp(4f), y, tick, Paint.Align.RIGHT)
            }
            grid.pathEffect = null
        }

        fill.color = withAlpha(p.accent, 0.2f)
        canvas.drawPath(areaPath, fill)
        line.color = p.accent
        line.strokeWidth = dp(2f)
        canvas.drawPath(linePath, line)

        if (expanded) {
            grid.color = withAlpha(p.line, 0.5f)
            grid.strokeWidth = dp(0.5f)
            canvas.drawLine(left, bottom, right, bottom, grid)
            for (k in 0 until labelCount) {
                val i = labelAt[k]
                val half = labelWidths[i] / 2
                val x = xs[i].coerceIn(left + half, right - half)
                canvas.drawTextCentered(labels[i], x, bottom + dp(X_AXIS) / 2, tick, Paint.Align.CENTER)
            }
        }

        if (scrubbed in 0 until count) drawScrub(canvas, scrubbed)
    }

    private fun drawScrub(canvas: Canvas, i: Int) {
        val t = theme
        val p = t.palette
        grid.color = p.line
        grid.strokeWidth = dp(1f)
        canvas.drawLine(xs[i], top, xs[i], bottom, grid)
        paint.style = Paint.Style.FILL
        paint.color = p.surface
        canvas.drawCircle(xs[i], ys[i], dp(5f), paint)
        paint.color = p.accent
        canvas.drawCircle(xs[i], ys[i], dp(3.5f), paint)
        if (!expanded || tip.isEmpty()) return
        tipSmall.style(t, Role.Secondary, p.textMuted)
        tipStrong.style(t, Role.BodyStrong, p.text)
        val pad = dp(10f)
        val gap = dp(2f)
        var w = 0f
        var h = pad * 2 - gap
        for (k in tip.indices) {
            val paintOf = if (k == 1) tipStrong else tipSmall
            w = maxOf(w, paintOf.measureText(tip[k]))
            h += paintOf.fontSpacing + gap
        }
        w += pad * 2
        // Beside the mark on the side with more room.
        val x = if (xs[i] + dp(12f) + w <= right) xs[i] + dp(12f) else xs[i] - dp(12f) - w
        val y = top.coerceAtMost(bottom - h).coerceAtLeast(0f)
        box.set(x.coerceAtLeast(0f), y, (x + w).coerceAtMost(width.toFloat()), y + h)
        paint.color = p.surfaceRaised
        canvas.drawRoundRect(box, dp(t.radius.small), dp(t.radius.small), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = p.border
        canvas.drawRoundRect(box, dp(t.radius.small), dp(t.radius.small), paint)
        var baseline = box.top + pad
        for (k in tip.indices) {
            val paintOf = if (k == 1) tipStrong else tipSmall
            paintOf.textAlign = Paint.Align.LEFT
            canvas.drawText(tip[k], box.left + pad, baseline - paintOf.ascent(), paintOf)
            baseline += paintOf.fontSpacing + gap
        }
        paint.style = Paint.Style.FILL
    }

    private val dash: DashPathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)

    private fun scrubTo(x: Float) {
        if (count == 0) return
        if (dirty) rebuild()
        val i = ChartMath.nearestIndex(x, left, right, count)
        if (i == scrubbed) return
        scrubbed = i
        val s = samples[i]
        tip = arrayOf(ChartText.moment(s.atMs), "${ChartMath.tenths(s.value)}${if (unit.isNotEmpty()) " $unit" else ""}") + s.notes
        onScrub?.invoke(s)
        invalidate()
    }

    private fun release() {
        removeCallbacks(settle)
        if (scrubbed < 0) return
        scrubbed = -1
        onScrub?.invoke(null)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (count == 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(settle)
                downX = event.x
                downY = event.y
                horizontal = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(event.x - downX)
                val dy = Math.abs(event.y - downY)
                if (!horizontal && dy > slop && dy > dx) {
                    // A scroll of the panel, not a look along the line.
                    release()
                    return false
                }
                // The mark appears once the finger is going along the line, not on the touch: a scroll over the chart leaves none.
                if (dx > slop) {
                    horizontal = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (horizontal) scrubTo(event.x)
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                // The mark stays a moment after the finger leaves, long enough to read.
                if (scrubbed >= 0) postDelayed(settle, SETTLE_MS)
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                release()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(settle)
    }

    private companion object {
        const val Y_AXIS = 36f
        const val X_AXIS = 26f
        const val MARGIN_TOP = 4f
        const val MIN_TICK_GAP = 24f
        const val SETTLE_MS = 2500L
    }
}

/** A pill of options, one pressed (the web's `RangeSwitcher`): the raised surface with the chosen option in the accent. */
open class RangeSwitcherView<T>(context: Context, val options: List<Pair<T, String>>, selected: T, var onChange: (T) -> Unit) : View(context) {
    var selected: T = selected
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    init {
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val t = theme
        text.style(t, Role.Label, t.palette.text)
        val u = unitPx()
        val pad = u * 0.5f
        val height = Math.ceil((pad * 2 + u * 2.5f + text.fontSpacing).toDouble()).toInt()
        var wanted = pad * 2
        for (o in options) wanted += text.measureText(o.second) + u * 5 + pad
        val w = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(wanted.toInt(), MeasureSpec.getSize(widthMeasureSpec))
            else -> wanted.toInt()
        }
        setMeasuredDimension(w, height)
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        val p = t.palette
        val u = unitPx()
        val pad = u * 0.5f
        text.style(t, Role.Label, p.text)
        box.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = p.surfaceRaised
        canvas.drawRoundRect(box, height / 2f, height / 2f, paint)
        val each = (width - pad * 2 - pad * (options.size - 1)) / options.size
        options.forEachIndexed { i, o ->
            val x = pad + i * (each + pad)
            box.set(x, pad, x + each, height - pad)
            if (o.first == selected) {
                paint.color = p.accent
                canvas.drawRoundRect(box, box.height() / 2, box.height() / 2, paint)
            }
            text.color = if (o.first == selected) p.accentText else p.text
            canvas.drawTextCentered(o.second, box.centerX(), box.centerY(), text, Paint.Align.CENTER)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && contains(event.x, event.y)) {
            val pad = unitPx() * 0.5f
            val each = (width - pad * 2 - pad * (options.size - 1)) / options.size
            val i = ((event.x - pad) / (each + pad)).toInt().coerceIn(0, options.size - 1)
            val picked = options[i].first
            if (picked != selected) {
                selected = picked
                onChange(picked)
            }
        }
        return true
    }
}

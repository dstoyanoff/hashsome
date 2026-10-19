package dev.hashsome.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import dev.hashsome.client.ForecastType
import dev.hashsome.client.SensorEntity
import dev.hashsome.headless.ConnectionHealth
import dev.hashsome.headless.EntityHandle
import dev.hashsome.headless.ForecastState
import dev.hashsome.headless.HandleState
import dev.hashsome.headless.HealthLevel
import dev.hashsome.headless.HealthSummary
import dev.hashsome.headless.WeatherControl
import dev.hashsome.ui.icons.Ui

// The pieces of the top row (the web's `DateChip`, `WeatherChip`, `Clock` and `SystemStatus` of `top-bar.tsx`), each its own
// view so a dashboard lays them out as it wants.

/** Today's date as a chip ("Tue · Oct 6"); it changes when the day does. */
open class DateChip(context: Context) : BoundView(context) {
    private val pill = PillView(context)
    private val text = labelText(context, "", Role.Label)

    init {
        pill.addView(text, FlexParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(pill, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        minuteTicks(::onDetach) { text.text = WallClockText.date(it) }
    }
}

/**
 * The weather now: a glyph for the sky, a whole-degree reading and, once the forecast has said, today's high and low. A weather
 * entity gives all of it; with [sensor] given, a plain temperature sensor (an outdoor one) gets a fixed sun and no range when the
 * ref is no weather entity. The forecast panel the web opens on a tap is not drawn.
 */
open class WeatherChip(context: Context, val control: WeatherControl, val sensor: EntityHandle<SensorEntity>? = null) : BoundView(context) {
    private val pill = PillView(context)
    private val sky = GlyphView(context, null, 14)
    private val reading = labelText(context, "…", Role.Label)
    private val range = labelText(context, "", Role.Secondary, theme.palette.textMuted)
    private var asked = false

    init {
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        pill.addView(sky, FlexParams(wrap, wrap))
        pill.addView(reading, FlexParams(wrap, wrap))
        pill.addView(range, FlexParams(wrap, wrap))
        range.visibility = GONE
        addView(pill, LayoutParams(wrap, wrap))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        asked = false
        bind(control.handle.state) { render() }
        bind(control.forecast) { render() }
        sensor?.let { bind(it.state) { render() } }
    }

    private fun render() {
        val p = theme.palette
        val state = control.handle.state.value
        val plain = sensor
        if (state is HandleState.Unsupported && plain != null) {
            val reading = plain.entity?.numeric
            sky.icon = Ui.sun
            sky.color = p.warm
            this.reading.text = if (plain.state.value is HandleState.Ready && reading != null) "${roundHalfUp(reading)}°" else StatusLabels.of(plain.state.value) ?: "–"
            setRange(null)
            return
        }

        val weather = control.handle.entity
        val ready = state is HandleState.Ready && weather?.temperature != null
        sky.icon = UiIcons.weather(weather?.condition ?: dev.hashsome.client.WeatherCondition.Unknown)
        sky.color = p.text
        reading.text = if (ready) "${roundHalfUp(weather?.temperature ?: 0.0)}°" else StatusLabels.of(state) ?: "–"
        if (ready && !asked && weather?.forecasts?.contains(ForecastType.Daily) == true) {
            asked = true
            control.ensure(ForecastType.Daily)
        }

        val forecast = control.forecast.value
        setRange(
            if (ready && forecast is ForecastState.Ready && forecast.type == ForecastType.Daily) {
                WeatherRange.of(forecast.result.points, Hashsome.scheduler.wallMs())
            } else {
                null
            },
        )
    }

    private fun setRange(text: String?) {
        range.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
        if (text != null) range.text = text
    }
}

/** How a [Clock] writes the hours. */
enum class ClockFormat { Auto, H12, H24 }

/** The time of day in the clock type, with AM/PM beside it in a 12 hour format. [Auto] follows the device's own 12/24 hour setting. */
open class Clock(context: Context, var format: ClockFormat = ClockFormat.Auto) : BoundView(context) {
    private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val periodPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var time = ""
    private var period: String? = null

    init {
        setWillNotDraw(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        minuteTicks(::onDetach) { ms ->
            val is24 = when (format) {
                ClockFormat.H24 -> true
                ClockFormat.H12 -> false
                ClockFormat.Auto -> android.text.format.DateFormat.is24HourFormat(context)
            }
            val clock = WallClockText.clock(ms, is24)
            if (clock.time != time || clock.period != period) {
                time = clock.time
                period = clock.period
                requestLayout()
                invalidate()
            }
        }
    }

    private fun style() {
        val t = theme
        timePaint.style(t, Role.Clock, t.palette.text)
        periodPaint.style(t, Role.Label, t.palette.text)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        style()
        val gap = if (period != null) unitPx() else 0f
        val wide = timePaint.measureText(time) + gap + (period?.let { periodPaint.measureText(it) } ?: 0f)
        setMeasuredDimension(Math.ceil(wide.toDouble()).toInt(), dp(TOP_ROW))
    }

    override fun onDraw(canvas: Canvas) {
        style()
        canvas.drawTextCentered(time, 0f, height / 2f, timePaint)
        period?.let {
            // Beside the time, at the top of the row: its own line box, not the time's.
            val line = theme.style(Role.Label).let { s -> dp(s.size) * (s.lineHeight ?: 1.3f) }
            canvas.drawTextCentered(it, timePaint.measureText(time) + unitPx(), line / 2f, periodPaint)
        }
    }
}

/**
 * One dot for the link to the runtime and every integration: green when all are up, warm while something connects, red when
 * something is down. A tap says which (the popover) and also reaches `TouchListener.onStatusDotTap`, which the settings gesture counts.
 */
open class SystemStatus(context: Context, val health: ConnectionHealth) : BoundView(context) {
    private val dot = StatusDot(context)
    private var popover: HealthPopover? = null

    init {
        addView(dot, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dot.setOnClickListener {
            Hashsome.touchListener.onStatusDotTap()
            toggle()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        bind(health.summary) { dot.level = it.overall }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        popover?.dismissNow()
        popover = null
    }

    private fun toggle() {
        val open = popover
        if (open != null) {
            open.dismiss()
            return
        }
        popover = HealthPopover(context, health).also {
            it.onDismissed = { popover = null }
            it.showFrom(dot)
        }
    }
}

/** The card under the status dot: a line for the whole, then each part with how it is doing. It follows the health while it is open. */
internal class HealthPopover(context: Context, private val health: ConnectionHealth) : Overlay(context) {
    private var card: View? = null

    init {
        scrim.setBackgroundColor(0)
    }

    override fun show(content: View) {
        attach()
        val host = parent as? View ?: return
        val anchor = this.anchor ?: return
        val at = IntArray(2)
        val origin = IntArray(2)
        anchor.getLocationInWindow(at)
        host.getLocationInWindow(origin)
        val u = unitPx()
        val params = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
        params.topMargin = at[1] - origin[1] + anchor.height + Math.round(2 * u)
        params.rightMargin = (origin[0] + host.width) - (at[0] + anchor.width)
        card = content
        addView(content, params)
        if (Animation.animates) {
            content.alpha = 0f
            content.animate().alpha(1f).setDuration(150).setInterpolator(EASE_OUT).start()
        }
    }

    private var anchor: View? = null

    fun showFrom(anchor: View) {
        this.anchor = anchor
        show(HealthCard(context, health))
    }

    override fun dismiss() {
        val c = card
        leave(if (c == null) null else { end -> c.animate().alpha(0f).setDuration(120).setInterpolator(EASE_IN).withEndAction(end).start() })
    }
}

/** The popover's body: raised surface, rounded, with the summary and a row per system. */
internal class HealthCard(context: Context, private val health: ConnectionHealth) : BoundView(context) {
    private val column = ColumnLayout(context)

    init {
        val t = theme
        background = Overlay.surface(FloatArray(4) { t.radius.row }).also { it.setColor(t.palette.surfaceRaised) }
        elevation = Overlay.elevationOf(t.shadow.drawer)
        isClickable = true
        minimumWidth = dp(220)
        val pad = Math.round(3.5f * unitPx())
        column.setPadding(pad, pad, pad, pad)
        column.gap = 2
        addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        bind(health.summary) { rebuild(it) }
    }

    private fun toneOf(level: HealthLevel): Int = theme.palette.let {
        when (level) {
            HealthLevel.Ok -> it.success
            HealthLevel.Pending -> it.warm
            HealthLevel.Down -> it.danger
        }
    }

    private val measure = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private fun wide(text: String, role: Role): Float = measure.style(theme, role, 0).measureText(text)

    private fun rebuild(summary: HealthSummary) {
        column.removeAllViews()
        // A column that wraps its content cannot stretch its rows to the widest of them, so the card is as wide as that, written down.
        val u = unitPx()
        val widest = maxOf(
            dp(8f) + 2 * u + wide(summary.summary, Role.BodyStrong),
            summary.rows.maxOfOrNull { wide(it.name, Role.Body) + 6 * u + wide(it.label, Role.Label) } ?: 0f,
        )
        val width = maxOf(dp(220), Math.ceil((widest + 2 * Math.round(3.5f * u)).toDouble()).toInt())
        layoutParams = (layoutParams as? LayoutParams)?.also { it.width = width } ?: LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val head = RowLayout(context).apply { gap = 2 }
        head.addView(Dot(context, toneOf(summary.overall)), FlexParams(dp(8), dp(8)))
        head.addView(labelText(context, summary.summary, Role.BodyStrong), FlexParams(wrap, wrap))
        column.addView(head, FlexParams(ViewGroup.LayoutParams.MATCH_PARENT, wrap))
        summary.rows.forEach { row ->
            val line = RowLayout(context).apply { gap = 6 }
            line.addView(labelText(context, row.name, Role.Body), FlexParams(wrap, wrap).apply { grow = true })
            line.addView(labelText(context, row.label, Role.Label, toneOf(row.level)), FlexParams(wrap, wrap))
            column.addView(line, FlexParams(ViewGroup.LayoutParams.MATCH_PARENT, wrap))
        }
    }

    private class Dot(context: Context, private val color: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            paint.color = color
            canvas.drawCircle(width / 2f, height / 2f, width / 2f, paint)
        }
    }
}

package dev.hashsome.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import dev.hashsome.client.EntityKind
import dev.hashsome.client.EntityRef
import dev.hashsome.client.ForecastPoint
import dev.hashsome.client.ForecastResult
import dev.hashsome.client.ForecastType
import dev.hashsome.client.Iso8601
import dev.hashsome.client.WeatherCondition
import dev.hashsome.client.WeatherEntity
import dev.hashsome.headless.EntityHandle
import dev.hashsome.headless.ForecastState
import dev.hashsome.headless.Scope
import dev.hashsome.headless.WeatherControl
import dev.hashsome.ui.icons.Ui

private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

/** How many hours the chart and the hourly row cover. */
private const val HOURS = 24

/** Hours between the labelled points under the chart. */
private const val EVERY = 4

/** A tile with a label and a value, for the readings of the expanded forecast (the web's `Stat`). */
private class StatTile(context: Context) : ColumnLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE }
    private val rect = RectF()

    init {
        setWillNotDraw(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val x = Math.round(3 * unitPx())
        val y = Math.round(2.5f * unitPx())
        if (paddingLeft != x || paddingTop != y) setPadding(x, y, x, y)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        val stroke = dp(1f)
        paint.strokeWidth = stroke
        paint.color = t.palette.border
        rect.set(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
        canvas.drawRoundRect(rect, dp(t.radius.row), dp(t.radius.row), paint)
    }
}

/** The hours in one row: as many as fit show (at least 84 dp each, widened to use the room), and the rest are a swipe away. */
private class HourStrip(context: Context) : HorizontalScrollView(context) {
    val row = LinearLayout(context).also { it.orientation = LinearLayout.HORIZONTAL }

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(row, ViewGroup.LayoutParams(WRAP, WRAP))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val cell = if (width > 0) width / Math.max(1, width / dp(HOUR)) else dp(HOUR)
        for (i in 0 until row.childCount) {
            val lp = row.getChildAt(i).layoutParams
            if (lp.width != cell) lp.width = cell
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private companion object {
        const val HOUR = 84
    }
}

/**
 * A weather entity's forecast (the web's `WeatherForecast`): the weather now, the next 24 hours as a chart with every fourth hour under it, and
 * the days ahead with each day's range of temperature. Touch the chart for the sky, wind and rain of an hour. [expanded] is the layout for a wide
 * space (the web's): larger, with tiles of the readings now, a column for each hour and the wind, humidity and UV of each day; a drawer takes it
 * when it is widened. A source with no forecast shows only the weather now.
 */
open class WeatherForecastBody(context: Context, private val scope: Scope, ref: EntityRef, expanded: Boolean = false) : ColumnLayout(context) {
    /** For a drawer: the layout follows the panel, wide when it is widened. */
    constructor(context: Context, ctx: DrawerContext, ref: EntityRef) : this(context, ctx.scope, ref, ctx.expanded) {
        ctx.onExpandedChanged { this.expanded = it }
    }

    var expanded: Boolean = expanded
        set(value) {
            if (field == value) return
            field = value
            // Everything is drawn again in the other layout.
            shownHours = null
            renderHours()
            renderDays()
            renderNow()
        }

    private val handle = EntityHandle(scope, ref, EntityKind.Weather)
    private val hourly = WeatherControl(handle, scope)
    private val daily = WeatherControl(handle, scope)
    private val stats = GridLayout(context, 3).also { it.gap = 3 }

    private val sky = GlyphView(context, null, 40)
    private val temp = AlignedText(context, "", Role.Heading)
    private val sub = AlignedText(context, "", Role.Secondary, theme.palette.textMuted)
    private val windNow = RowLayout(context)
    private val windArrow = GlyphView(context, Ui.arrowUp, 12)
    private val windText = AlignedText(context, "", Role.Secondary, theme.palette.textMuted)
    private val hoursBlock = ColumnLayout(context)
    private val daysBlock = ColumnLayout(context)
    private val readout = AlignedText(context, "", Role.Secondary, theme.palette.textMuted, Paint.Align.RIGHT)
    private val detail = labelText(context, "", Role.Secondary, theme.palette.textMuted)
    private var shownHours: ForecastResult? = null
    private var asked = false
    private var hours: List<ForecastPoint> = emptyList()
    private var hourlyResult: ForecastResult? = null

    init {
        gap = 5
        val now = RowLayout(context).also { it.gap = 3 }
        now.addView(sky, FlexParams(WRAP, WRAP))
        val text = ColumnLayout(context).also { it.align = Align.Start }
        text.addView(temp, FlexParams(WRAP, WRAP))
        text.addView(sub, FlexParams(MATCH, WRAP))
        windNow.gap = 1
        windNow.addView(windArrow, FlexParams(WRAP, WRAP))
        windNow.addView(windText, FlexParams(WRAP, WRAP))
        text.addView(windNow, FlexParams(WRAP, WRAP))
        now.addView(text, FlexParams(WRAP, WRAP).apply { grow = true })
        addView(now, FlexParams(MATCH, WRAP))
        addView(stats, FlexParams(MATCH, WRAP))
        hoursBlock.gap = 1
        addView(hoursBlock, FlexParams(MATCH, WRAP))
        daysBlock.gap = 2
        addView(daysBlock, FlexParams(MATCH, WRAP))

        scope.bind(handle.state) { renderNow() }
        scope.bind(hourly.forecast) { renderHours() }
        scope.bind(daily.forecast) { renderDays() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // As many tiles to a row as are 150 dp wide, the web's `repeat(auto-fill, minmax(150px, 1fr))`.
        val inner = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        stats.columns = Math.max(1, (inner + Math.round(3 * unitPx())) / (dp(150) + Math.round(3 * unitPx())))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun weather(): WeatherEntity? = handle.entity

    private fun renderNow() {
        val w = weather()
        if (w != null && !asked) {
            asked = true
            if (ForecastType.Hourly in w.forecasts) hourly.ensure(ForecastType.Hourly)
            if (ForecastType.Daily in w.forecasts) daily.ensure(ForecastType.Daily)
        }

        val p = theme.palette
        val soon = hours.firstOrNull()
        sky.icon = UiIcons.weather(w?.condition ?: WeatherCondition.Unknown)
        sky.sizeDp = if (expanded) 56 else 40
        temp.text = ForecastText.degrees(w?.temperature)
        val humidity = w?.humidity ?: soon?.humidity
        val uv = w?.uvIndex ?: soon?.uvIndex
        sub.text = (WeatherLook.of(w?.condition ?: WeatherCondition.Unknown).label) +
            (if (humidity != null) " · ${roundHalfUp(humidity)}% humidity" else "") +
            (if (uv != null) " · UV ${ChartMath.tenths(uv)} (${ForecastText.uvLevel(uv)})" else "")
        val speed = w?.windSpeed
        windNow.visibility = if (speed == null) GONE else VISIBLE
        if (speed != null) {
            windText.text = "Wind ${ForecastText.wind(speed, w.windBearing, w.windUnit)}"
            windArrow.visibility = if (w.windBearing == null) GONE else VISIBLE
            // The wind comes from the bearing, so the arrow points the other way.
            windArrow.rotation = ((w.windBearing ?: 0.0) + 180.0).toFloat()
            windArrow.color = p.textMuted
        }
        renderStats(w, soon)
    }

    /** The readings now as tiles, from the entity and, for what it lacks, the hour under way (a source may give UV or cloud cover only in its forecast). */
    private fun renderStats(w: WeatherEntity?, soon: ForecastPoint?) {
        stats.removeAllViews()
        stats.visibility = if (expanded) VISIBLE else GONE
        if (!expanded) return
        val feels = w?.apparentTemperature ?: soon?.apparentTemperature
        val humidity = w?.humidity ?: soon?.humidity
        val uv = w?.uvIndex ?: soon?.uvIndex
        val cloud = w?.cloudCoverage ?: soon?.cloudCoverage
        val pressure = w?.pressure ?: soon?.pressure
        feels?.let { stat("Feels like", ForecastText.degrees(it)) }
        humidity?.let { stat("Humidity", "${roundHalfUp(it)}%") }
        w?.windSpeed?.let { stat("Wind", ForecastText.wind(it, w.windBearing, w.windUnit)) }
        uv?.let { stat("UV index", "${ChartMath.tenths(it)} · ${ForecastText.uvLevel(it)}") }
        cloud?.let { stat("Cloud cover", "${roundHalfUp(it)}%") }
        pressure?.let { stat("Pressure", "${roundHalfUp(it)}${w?.pressureUnit?.let { u -> " $u" } ?: ""}") }
    }

    private fun stat(label: String, value: String) {
        val tile = StatTile(context)
        tile.addView(AlignedText(context, label, Role.Secondary, theme.palette.textMuted), FlexParams(MATCH, WRAP))
        tile.addView(AlignedText(context, value, Role.BodyStrong), FlexParams(MATCH, WRAP))
        stats.addView(tile)
    }

    /** One small reading with an icon in front of it (the web's `Metric`). */
    private fun metric(icon: Icon, text: String, turn: Float? = null): View {
        val row = RowLayout(context).also { it.gap = 1; it.align = Align.Center }
        row.addView(GlyphView(context, icon, 12).also { g -> g.color = theme.palette.textMuted; if (turn != null) g.rotation = turn }, FlexParams(WRAP, WRAP))
        row.addView(AlignedText(context, text, Role.Secondary, theme.palette.textMuted, ellipsize = false), FlexParams(WRAP, WRAP))
        return row
    }

    /** The wind as a metric: an arrow the way it blows, and its speed; none when the step has no wind. */
    private fun windMetric(point: ForecastPoint, unit: String?): View? {
        val speed = point.windSpeed ?: return null
        val text = "${roundHalfUp(speed)}${unit?.let { " $it" } ?: ""}"
        return if (point.windBearing != null) metric(Ui.arrowUp, text, (point.windBearing!! + 180.0).toFloat()) else metric(Ui.wind, text)
    }

    private fun renderHours() {
        val state = hourly.forecast.value
        val result = (state as? ForecastState.Ready)?.takeIf { it.type == ForecastType.Hourly }?.result
        // The same answer again (a state that only changed its Loading for it) is not drawn anew: a mark on the chart would go.
        if (result != null && result == shownHours) return
        shownHours = result
        hourlyResult = result
        hours = result?.points.orEmpty().take(HOURS)
        hoursBlock.removeAllViews()
        renderNow()
        if (hours.isEmpty()) return
        val head = RowLayout(context).also { it.gap = 3 }
        head.addView(AlignedText(context, "Next 24 hours", Role.Label, theme.palette.textMuted), FlexParams(WRAP, WRAP))
        readout.text = ""
        head.addView(readout, FlexParams(WRAP, WRAP).apply { grow = true })
        hoursBlock.addView(head, FlexParams(MATCH, WRAP))

        // The temperature as a curve; touch it for everything about that hour.
        val withTemperature = hours.filter { it.temperature != null }
        val steps = withTemperature.mapNotNull { h ->
            Iso8601.parse(h.timestamp)?.let { at -> SeriesSample(at, h.temperature!!, describe(h, result)) } to h
        }.filter { it.first != null }
        val chart = SeriesChartView(context)
        chart.heightDp = if (expanded) 180 else 88
        chart.expanded = expanded
        chart.fitDomain = true
        chart.unit = result?.unit ?: "°"
        chart.range = SeriesRange.Day
        chart.samples = steps.map { it.first!! }
        chart.onScrub = { s ->
            val h = steps.firstOrNull { it.first === s }?.second
            // The rest of what the hour knows (wind, rain, air, sun, pressure) goes on a line under the chart, which keeps its room.
            detail.text = if (s == null) "" else s.notes.drop(1).joinToString(" · ")
            readout.text = if (s == null || h == null) "" else "${ChartText.hour(s.atMs)} · ${ForecastText.degrees(h.temperature)} · ${WeatherLook.of(h.condition).label}" +
                (ForecastText.rain(h.precipitationProbability, h.precipitation, result?.precipitationUnit)?.let { " · Rain $it" } ?: "")
        }
        hoursBlock.addView(chart, FlexParams(MATCH, WRAP).apply { topMargin = Math.round(3 * unitPx()) })
        detail.text = ""
        hoursBlock.addView(detail, FlexParams(MATCH, WRAP))

        if (expanded) {
            hoursBlock.addView(hourStrip(result), FlexParams(MATCH, WRAP).apply { topMargin = Math.round(2 * unitPx()) })
            return
        }
        val along = RowLayout(context).also { it.justify = Justify.SpaceBetween; it.align = Align.Start }
        hours.filterIndexed { i, _ -> i % EVERY == 0 }.forEach { h ->
            val col = ColumnLayout(context).also { it.gap = 1; it.align = Align.Center }
            col.addView(AlignedText(context, Iso8601.parse(h.timestamp)?.let { ChartText.hour(it) } ?: "", Role.Secondary, theme.palette.textMuted, Paint.Align.CENTER), FlexParams(WRAP, WRAP))
            col.addView(GlyphView(context, UiIcons.weather(h.condition), 18), FlexParams(WRAP, WRAP))
            col.addView(AlignedText(context, ForecastText.degrees(h.temperature), Role.Label, align = Paint.Align.CENTER), FlexParams(WRAP, WRAP))
            along.addView(col, FlexParams(0, WRAP).apply { grow = true; weight = 1f })
        }
        hoursBlock.addView(along, FlexParams(MATCH, WRAP).apply { topMargin = Math.round(unitPx()) })
    }

    /** Every one of the hours as a column: the hour, the sky, the temperature, and what it knows of the rain, the wind, the air and the sun. */
    private fun hourStrip(result: ForecastResult?): View {
        val strip = HourStrip(context)
        for (h in hours) {
            val col = ColumnLayout(context).also { it.gap = 2; it.align = Align.Center }
            val pad = Math.round(unitPx())
            col.setPadding(Math.round(2 * unitPx()), pad, Math.round(2 * unitPx()), pad)
            col.addView(AlignedText(context, Iso8601.parse(h.timestamp)?.let { ChartText.hour(it) } ?: "", Role.Secondary, theme.palette.textMuted, Paint.Align.CENTER), FlexParams(WRAP, WRAP))
            col.addView(GlyphView(context, UiIcons.weather(h.condition), 26), FlexParams(WRAP, WRAP))
            col.addView(AlignedText(context, ForecastText.degrees(h.temperature), Role.BodyStrong, align = Paint.Align.CENTER), FlexParams(WRAP, WRAP))
            val metrics = ColumnLayout(context).also { it.gap = 1; it.align = Align.Center }
            ForecastText.rain(h.precipitationProbability, h.precipitation, result?.precipitationUnit)?.let { metrics.addView(metric(Ui.umbrella, it), FlexParams(WRAP, WRAP)) }
            windMetric(h, result?.windUnit)?.let { metrics.addView(it, FlexParams(WRAP, WRAP)) }
            h.humidity?.let { metrics.addView(metric(Ui.droplets, "${roundHalfUp(it)}%"), FlexParams(WRAP, WRAP)) }
            h.uvIndex?.takeIf { it > 0 }?.let { metrics.addView(metric(Ui.sun, "UV ${ChartMath.tenths(it)}"), FlexParams(WRAP, WRAP)) }
            col.addView(metrics, FlexParams(WRAP, WRAP))
            strip.row.addView(col, LinearLayout.LayoutParams(dp(84), WRAP))
        }
        return strip
    }

    private fun renderDays() {
        val state = daily.forecast.value
        val result = (state as? ForecastState.Ready)?.takeIf { it.type == ForecastType.Daily }?.result
        daysBlock.removeAllViews()
        val days = result?.points.orEmpty()
        if (days.isEmpty()) return
        daysBlock.addView(AlignedText(context, "${days.size} days", Role.Label, theme.palette.textMuted), FlexParams(MATCH, WRAP))
        val coldest = days.mapNotNull { it.low }.minOrNull() ?: 0.0
        val warmest = days.mapNotNull { it.temperature }.maxOrNull() ?: 0.0
        val span = warmest - coldest
        val now = Hashsome.scheduler.wallMs()
        val list = ColumnLayout(context).also { it.gap = 1 }
        for (day in days) {
            val at = Iso8601.parse(day.timestamp)
            // The rain of a day is wider than its column and runs over the gap after it, as on the web.
            val row = RowLayout(context).also { it.gap = 3; it.clipChildren = false }
            val pad = Math.round(1.5f * unitPx())
            row.setPadding(0, pad, 0, pad)
            row.addView(AlignedText(context, if (at == null) "" else ForecastText.dayName(at, now, ChartText.weekday(at)), Role.Body, widthDp = 104), FlexParams(dp(104), WRAP))
            row.addView(GlyphView(context, UiIcons.weather(day.condition), 20), FlexParams(WRAP, WRAP))
            row.addView(AlignedText(context, ForecastText.rain(day.precipitationProbability, day.precipitation, result?.precipitationUnit) ?: "", Role.Secondary, theme.palette.textMuted, widthDp = if (expanded) 90 else 40, ellipsize = false), FlexParams(dp(if (expanded) 90 else 40), WRAP))
            row.addView(AlignedText(context, ForecastText.degrees(day.low), Role.Label, theme.palette.textMuted, Paint.Align.RIGHT, widthDp = 36), FlexParams(dp(36), WRAP))
            val bar = SpanBar(context)
            if (span > 0 && day.low != null && day.temperature != null) {
                bar.from = ((day.low!! - coldest) / span).toFloat()
                bar.to = ((day.temperature!! - coldest) / span).toFloat()
            }
            row.addView(bar, FlexParams(0, WRAP).apply { grow = true })
            row.addView(AlignedText(context, ForecastText.degrees(day.temperature), Role.Label, widthDp = 36), FlexParams(dp(36), WRAP))
            if (expanded) {
                // The wind, the humidity and the UV of the day, each in a column of its own width.
                fun cell(width: Int, view: View?) = row.addView(
                    FrameLayoutOf(context, view),
                    FlexParams(dp(width), WRAP),
                )
                cell(84, windMetric(day, result?.windUnit))
                cell(64, day.humidity?.let { metric(Ui.droplets, "${roundHalfUp(it)}%") })
                cell(72, day.uvIndex?.let { metric(Ui.sun, "UV ${ChartMath.tenths(it)}") })
            }
            list.addView(row, FlexParams(MATCH, WRAP))
        }
        daysBlock.addView(list, FlexParams(MATCH, WRAP))
    }

    /** Everything a step knows, a line each, for the chart's tooltip: the sky, the wind, the rain, the air, the sun and the pressure. */
    private fun describe(h: ForecastPoint, result: ForecastResult?): List<String> {
        val lines = ArrayList<String>(6)
        lines += WeatherLook.of(h.condition).label
        h.windSpeed?.let {
            lines += "Wind ${ForecastText.wind(it, h.windBearing, result?.windUnit)}" + (h.windGustSpeed?.let { g -> ", gusts ${roundHalfUp(g)}" } ?: "")
        }
        ForecastText.rain(h.precipitationProbability, h.precipitation, result?.precipitationUnit)?.let { lines += "Rain $it" }
        val air = listOfNotNull(h.humidity?.let { "Humidity ${roundHalfUp(it)}%" }, h.cloudCoverage?.let { "Cloud cover ${roundHalfUp(it)}%" })
        if (air.isNotEmpty()) lines += air.joinToString(" · ")
        val sun = listOfNotNull(h.uvIndex?.let { "UV ${ChartMath.tenths(it)} (${ForecastText.uvLevel(it)})" }, h.apparentTemperature?.let { "Feels like ${ForecastText.degrees(it)}" })
        if (sun.isNotEmpty()) lines += sun.joinToString(" · ")
        h.pressure?.let { lines += "Pressure ${roundHalfUp(it)}${result?.pressureUnit?.let { u -> " $u" } ?: ""}" }
        return lines
    }
}

/** A fixed-width cell for a table's column: holds [view] at its start, or nothing. */
private fun FrameLayoutOf(context: Context, view: View?): View = android.widget.FrameLayout(context).also { cell ->
    if (view != null) cell.addView(view, android.widget.FrameLayout.LayoutParams(WRAP, WRAP))
}

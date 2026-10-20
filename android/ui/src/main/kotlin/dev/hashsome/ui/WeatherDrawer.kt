package dev.hashsome.ui

import android.content.Context
import android.graphics.Paint
import android.view.ViewGroup
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
import dev.hashsome.headless.WeatherControl
import dev.hashsome.ui.icons.Ui

private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

/** How many hours the chart and the hourly row cover. */
private const val HOURS = 24

/** Hours between the labelled points under the chart. */
private const val EVERY = 4

/**
 * A weather entity's forecast (the web's `WeatherForecast`, in the layout of the narrow drawer): the weather now, the next 24 hours as a
 * chart with every fourth hour under it, and the days ahead with each day's range of temperature. Touch the chart for the sky, wind and
 * rain of an hour. The web's expanded layout (readings tiles, a card for each hour, more columns for the days) is not drawn; a source
 * with no forecast shows only the weather now.
 */
open class WeatherForecastBody(context: Context, private val ctx: DrawerContext, ref: EntityRef) : ColumnLayout(context) {
    private val handle = EntityHandle(ctx.scope, ref, EntityKind.Weather)
    private val hourly = WeatherControl(handle, ctx.scope)
    private val daily = WeatherControl(handle, ctx.scope)

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
        hoursBlock.gap = 1
        addView(hoursBlock, FlexParams(MATCH, WRAP))
        daysBlock.gap = 2
        addView(daysBlock, FlexParams(MATCH, WRAP))

        ctx.scope.bind(handle.state) { renderNow() }
        ctx.scope.bind(hourly.forecast) { renderHours() }
        ctx.scope.bind(daily.forecast) { renderDays() }
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
        sky.sizeDp = 40
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
        chart.heightDp = 88
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
            row.addView(AlignedText(context, ForecastText.rain(day.precipitationProbability, day.precipitation, result?.precipitationUnit) ?: "", Role.Secondary, theme.palette.textMuted, widthDp = 40, ellipsize = false), FlexParams(dp(40), WRAP))
            row.addView(AlignedText(context, ForecastText.degrees(day.low), Role.Label, theme.palette.textMuted, Paint.Align.RIGHT, widthDp = 36), FlexParams(dp(36), WRAP))
            val bar = SpanBar(context)
            if (span > 0 && day.low != null && day.temperature != null) {
                bar.from = ((day.low!! - coldest) / span).toFloat()
                bar.to = ((day.temperature!! - coldest) / span).toFloat()
            }
            row.addView(bar, FlexParams(0, WRAP).apply { grow = true })
            row.addView(AlignedText(context, ForecastText.degrees(day.temperature), Role.Label, widthDp = 36), FlexParams(dp(36), WRAP))
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

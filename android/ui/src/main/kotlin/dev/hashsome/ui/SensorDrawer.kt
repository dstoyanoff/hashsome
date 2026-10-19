package dev.hashsome.ui

import android.content.Context
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import dev.hashsome.client.EntityRef
import dev.hashsome.client.HistoryBucket
import dev.hashsome.client.HistoryRange
import dev.hashsome.client.Iso8601
import dev.hashsome.client.SensorEntity
import dev.hashsome.headless.HandleState
import dev.hashsome.headless.HistoryControl
import dev.hashsome.headless.HistoryState
import dev.hashsome.headless.Reading
import dev.hashsome.headless.SensorReading
import dev.hashsome.headless.Verdict
import dev.hashsome.ui.icons.Ui
import java.util.Locale

private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

/** The readings a history result holds, as a chart takes them. */
internal fun samplesOf(state: HistoryState): List<SeriesSample> =
    (state as? HistoryState.Ready)?.result?.points.orEmpty().mapNotNull { p -> Iso8601.parse(p.timestamp)?.let { SeriesSample(it, p.value) } }

/**
 * What the drawer of a sensor readout holds (the web's `SensorDetailBody`): the reading now with its verdict on the safe range, the
 * battery when one was given, and the history chart. Built by the readout's tap; [ctx] is the open drawer.
 */
open class SensorDrawerBody(context: Context, ctx: DrawerContext, val reading: SensorReading) : ColumnLayout(context) {
    private val nowValue = AlignedText(context, "", Role.Stat)
    private val verdictRow = RowLayout(context)
    private val verdictIcon = GlyphView(context, Ui.triangleAlert, 14)
    private val verdictText = AlignedText(context, "", Role.Secondary)
    private val batteryCard = RaisedCard(context, 4f, 2.5f)
    private val batteryIcon = GlyphView(context, Ui.battery, 16)
    private val batteryLabel = AlignedText(context, "Battery", Role.Secondary)
    private val batteryLevel = AlignedText(context, "—", Role.BodyStrong, align = Paint.Align.RIGHT)
    private var history: SensorHistoryView? = null

    init {
        gap = 2
        val now = RaisedCard(context)
        now.addView(AlignedText(context, "Now", Role.Secondary, theme.palette.textMuted), FlexParams(MATCH, WRAP))
        now.addView(nowValue, FlexParams(MATCH, WRAP))
        verdictRow.gap = 1
        verdictRow.addView(verdictIcon, FlexParams(WRAP, WRAP))
        verdictRow.addView(verdictText, FlexParams(WRAP, WRAP).apply { grow = true })
        now.addView(verdictRow, FlexParams(MATCH, WRAP))
        addView(now, FlexParams(MATCH, WRAP))

        val batteryRow = RowLayout(context).also { it.gap = 2 }
        batteryRow.addView(batteryIcon, FlexParams(WRAP, WRAP))
        batteryRow.addView(batteryLabel, FlexParams(WRAP, WRAP).apply { grow = true })
        batteryRow.addView(batteryLevel, FlexParams(WRAP, WRAP))
        batteryCard.addView(batteryRow, FlexParams(MATCH, WRAP))
        addView(batteryCard, FlexParams(MATCH, WRAP))
        batteryCard.visibility = if (reading.battery != null) VISIBLE else GONE

        val ref = reading.handle.ref
        if (ref != null) {
            val view = SensorHistoryView(context, ctx, ref) { reading.handle.entity?.unit ?: "" }
            history = view
            addView(view, FlexParams(MATCH, WRAP).apply { topMargin = Math.round(4 * unitPx()) })
        }

        ctx.scope.bind(reading.reading) { render() }
        ctx.scope.bind(reading.verdict) { render() }
    }

    private fun render() {
        val p = theme.palette
        val r: Reading = reading.reading.value
        // The unit comes with the sensor's first report.
        history?.refresh()
        nowValue.text = r.formatted
        val verdict: Verdict? = reading.verdict.value
        verdictRow.visibility = if (verdict == null) GONE else VISIBLE
        if (verdict != null) {
            val tone = if (verdict.warning) p.danger else p.textMuted
            verdictText.text = verdict.text
            verdictText.color = tone
            verdictIcon.color = tone
            verdictIcon.visibility = if (verdict.warning) VISIBLE else GONE
        }

        val battery = r.battery
        val low = battery?.low == true
        val tone = if (low) p.danger else p.textMuted
        batteryIcon.icon = when {
            battery == null -> Ui.battery
            battery.percent <= 20 -> Ui.batteryLow
            battery.percent <= 60 -> Ui.batteryMedium
            else -> Ui.batteryFull
        }
        batteryIcon.color = tone
        batteryLabel.text = if (low) "Battery low" else "Battery"
        batteryLabel.color = tone
        batteryLevel.text = if (battery == null) "—" else "${battery.percent}%"
        batteryLevel.color = if (low) p.danger else p.text
    }
}

/**
 * A sensor's history for its drawer (the web's `SensorHistory`): the min and max of three periods, and the readings of the last day as
 * a chart. Hourly averages for the month with five-minute ones for the last week laid over them, so the 1H and 1D views have more
 * than a point or two. Expanded, it adds the range switcher (1H to 1M; the web's custom from / to dates are not drawn) and the periods
 * become today / last 7 / last 30 days with a chart that has axes. It shows nothing until there are readings.
 */
open class SensorHistoryView(context: Context, private val ctx: DrawerContext, ref: EntityRef, private val unitOf: () -> String) : ColumnLayout(context) {
    private val month = HistoryControl(ref, ctx.scope, HistoryRange.Month, HistoryBucket.Hour)
    private val week = HistoryControl(ref, ctx.scope, HistoryRange.Week, HistoryBucket.FiveMinutes)
    private val readout = AlignedText(context, "", Role.Secondary, theme.palette.textMuted, Paint.Align.RIGHT)
    private val head = RowLayout(context)
    private val tiles = RowLayout(context)
    private val tileValues = Array(3) { AlignedText(context, "—", Role.BodyStrong) }
    private val tileLabels = Array(3) { AlignedText(context, "", Role.Secondary, theme.palette.textMuted) }
    private val chart = SeriesChartView(context)
    private val none = AlignedText(context, "No readings in this range.", Role.Secondary, theme.palette.textMuted)
    private val switcher: RangeSwitcherView<SeriesRange>
    private var samples: List<SeriesSample> = emptyList()
    private var range = SeriesRange.Day

    init {
        gap = 2
        head.addView(AlignedText(context, "HISTORY", Role.Eyebrow, theme.palette.textMuted), FlexParams(WRAP, WRAP))
        head.gap = 3
        head.addView(readout, FlexParams(WRAP, WRAP).apply { grow = true })
        switcher = RangeSwitcherView(context, EXPANDED_RANGES.map { it to it.label }, range) { range = it; render() }
        head.addView(switcher, FlexParams(WRAP, WRAP))
        addView(head, FlexParams(MATCH, WRAP))
        tiles.gap = 2
        tiles.align = Align.Stretch
        for (i in 0 until 3) {
            val tile = RaisedCard(context, 3f, 2.5f)
            tile.addView(tileLabels[i], FlexParams(MATCH, WRAP))
            tile.addView(tileValues[i], FlexParams(MATCH, WRAP))
            tiles.addView(tile, FlexParams(0, WRAP).apply { grow = true; weight = 1f })
        }
        addView(tiles, FlexParams(MATCH, WRAP))
        chart.fitDomain = true
        chart.onScrub = { s ->
            val unit = unitOf()
            readout.text = if (s == null) "" else "${ChartText.moment(s.atMs)} · ${ChartMath.tenths(s.value)}${if (unit.isNotEmpty()) " $unit" else ""}"
        }
        addView(chart, FlexParams(MATCH, WRAP).apply { topMargin = Math.round(3 * unitPx()) })
        addView(none, FlexParams(MATCH, WRAP))
        visibility = GONE
        ctx.onExpandedChanged { render() }
        // The two answers (and a later one for the same question) merge into one series.
        ctx.scope.bind(month.state) { update() }
        ctx.scope.bind(week.state) { update() }
    }

    /** Draws again with what the sensor says now (its unit arrives with its first report). */
    fun refresh() {
        render()
    }

    private fun update() {
        samples = SensorStats.merge(samplesOf(month.state.value), samplesOf(week.state.value))
        render()
    }

    private fun render() {
        visibility = if (samples.isEmpty()) GONE else VISIBLE
        if (samples.isEmpty()) return
        val unit = unitOf()
        chart.unit = unit
        val expanded = ctx.expanded
        val now = Hashsome.scheduler.wallMs()
        val zone = java.util.TimeZone.getDefault()
        val periods = if (expanded) {
            listOf("Today" to Period.Today, "Last 7 days" to Period.Last7d, "Last 30 days" to Period.Last30d)
        } else {
            listOf("Today" to Period.Today, "This week" to Period.Week, "This month" to Period.Month)
        }
        periods.forEachIndexed { i, (label, period) ->
            tileLabels[i].text = label
            tileValues[i].text = SensorStats.range(SensorStats.aggregate(SensorStats.between(samples, SensorStats.periodStart(period, now, zone), now)), unit)
        }

        // Collapsed always shows the 1D sparkline; the switcher only exists once expanded.
        val active = if (expanded) range else SeriesRange.Day
        switcher.visibility = if (expanded) VISIBLE else GONE
        switcher.selected = active
        val window = ChartMath.windowMs(active) ?: ChartMath.DAY_MS
        val shown = SensorStats.between(samples, now - window, now)
        none.visibility = if (shown.isEmpty()) VISIBLE else GONE
        chart.visibility = if (shown.isEmpty()) GONE else VISIBLE
        chart.range = active
        chart.expanded = expanded
        chart.samples = ChartMath.downsample(shown, 240)
    }

    private companion object {
        val EXPANDED_RANGES = listOf(SeriesRange.Hour, SeriesRange.Day, SeriesRange.Week, SeriesRange.Month)
    }
}

/** What a sensor's drawer is called and what it is: the name, the kind line (`Temperature`, or `Sensor`) and the glyph. */
internal object SensorDrawerText {
    fun kind(sensor: SensorEntity?): String = sensor?.measurement?.replaceFirstChar { it.titlecase(Locale.getDefault()) } ?: "Sensor"
}

package dev.hashsome.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import dev.hashsome.client.Cancel
import dev.hashsome.client.HistoryBucket
import dev.hashsome.client.HistoryRange
import dev.hashsome.client.Iso8601
import dev.hashsome.client.SensorEntity
import dev.hashsome.headless.EntityHandle
import dev.hashsome.headless.HandleState
import dev.hashsome.headless.HistoryControl
import dev.hashsome.headless.HistoryState
import dev.hashsome.headless.Scope

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/**
 * The body of a switch's drawer (the web's `EnergyChart`): what the [power] sensor reads now as the headline, the energy used today / this
 * week / this month from the daily growth of the [energy] counter, and the power over time. While the drawer is at its normal width the
 * chart is a bare sparkline of the last day; expanded it has its axes, a tooltip and the 1H / 1D / 1W / 1M range, the usage becomes
 * today / last 7 / last 30 days and the counter's reading is added as "Lifetime". [expanded] and [onExpanded] are the drawer's
 * ([DrawerContext.expanded], [DrawerContext.onExpandedChanged]). Nothing to show (no sensors, no usage and no samples) draws nothing.
 */
open class EnergyDrawerBody(
    context: Context,
    private val scope: Scope,
    private val power: EntityHandle<SensorEntity>?,
    private val energy: EntityHandle<SensorEntity>?,
    expanded: Boolean = false,
    onExpanded: ((((Boolean) -> Unit)) -> Unit)? = null,
    private val unit: String = "W",
) : BoundView(context) {
    private val column = ColumnLayout(context).also { it.gap = 2 }
    private val reading = LabelView(context, Role.Stat)
    private val usageRow = RowLayout(context).also { it.gap = 2 }
    private val lifetime = LabelView(context, Role.Secondary, Tone.Muted)
    private val chart = SeriesChartView(context)
    private val switcher = RangeSwitcherView(context, RANGES, SeriesRange.Day) { select(it) }
    private val head = RowLayout(context).also { it.justify = Justify.SpaceBetween }

    private var range = SeriesRange.Day
    private var expandedNow = expanded
    // What listens to the runtime lives from attaching to detaching; a range's history has a scope of its own, let go when another is picked.
    private var live: Scope? = null
    private var rangeScope: Scope? = null
    private val cancels = ArrayList<Cancel>()
    private var usage: EnergyUsage? = null
    private var samples: List<SeriesSample> = emptyList()
    private var shownTiles: List<Pair<String, String>>? = null

    init {
        head.addView(LabelView(context, Role.Eyebrow, Tone.Muted).also { it.text = "POWER" }, FlexParams(WRAP, WRAP))
        head.addView(switcher, FlexParams(WRAP, WRAP))
        column.addView(head, FlexParams(MATCH, WRAP))
        column.addView(reading, FlexParams(WRAP, WRAP))
        column.addView(usageRow, FlexParams(MATCH, WRAP))
        column.addView(lifetime, FlexParams(WRAP, WRAP))
        chart.unit = unit
        column.addView(chart, FlexParams(MATCH, WRAP))
        column.setPadding(0, Math.round(4 * unitPx()), 0, 0)
        addView(column, LayoutParams(MATCH, WRAP))
        onExpanded?.invoke { wide ->
            expandedNow = wide
            render()
        }
    }

    private fun select(next: SeriesRange) {
        range = next
        loadPower()
        render()
    }

    private fun loadPower() {
        rangeScope?.dispose()
        rangeScope = null
        val handle = power ?: return
        val owner = live ?: return
        val own = owner.child()
        rangeScope = own
        val control = HistoryControl(handle, own, historyRange(range))
        cancels += control.state.listenNow { state ->
            samples = if (state is HistoryState.Ready) {
                state.result.points.mapNotNull { point -> Iso8601.parse(point.timestamp)?.let { SeriesSample(it, point.value) } }
            } else {
                emptyList()
            }
            render()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val owner = scope.child()
        live = owner
        loadPower()
        if (energy != null) {
            val usageHistory = HistoryControl(energy, owner, HistoryRange.Month, HistoryBucket.Day)
            cancels += usageHistory.state.listenNow { state ->
                usage = if (state is HistoryState.Ready) EnergyText.usageFromDaily(state.result.points, Hashsome.scheduler.wallMs()) else null
                render()
            }
        }
        power?.let { bind(it.state) { render() } }
        energy?.let { bind(it.state) { render() } }
        render()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancels.forEach { it() }
        cancels.clear()
        live?.dispose()
        live = null
        rangeScope = null
    }

    private fun render() {
        // Nothing to say: no sensor, no usage and no samples.
        column.visibility = if (power == null && energy == null && usage == null && samples.isEmpty()) GONE else VISIBLE
        val sensor = power?.takeIf { it.state.value is HandleState.Ready }?.entity
        val numeric = sensor?.numeric
        val shownUnit = sensor?.unit ?: unit
        reading.text = when {
            power == null -> "${samples.lastOrNull()?.value?.let { ChartMath.tenths(it) } ?: "0"} $unit"
            numeric != null -> "${toFixed(numeric, decimalsOf(numeric))} $shownUnit"
            else -> "—"
        }

        switcher.visibility = if (expandedNow) VISIBLE else GONE
        val tiles = usage?.let { EnergyText.tiles(it, expandedNow) } ?: emptyList()
        if (tiles != shownTiles) {
            shownTiles = tiles
            usageRow.removeAllViews()
            tiles.forEach { (label, value) ->
                val tile = RaisedCard(context, 3f, 2.5f)
                tile.addView(LabelView(context, Role.Secondary, Tone.Muted).also { it.text = label }, FlexParams(WRAP, WRAP))
                tile.addView(LabelView(context, Role.BodyStrong).also { it.text = value }, FlexParams(WRAP, WRAP))
                usageRow.addView(tile, FlexParams(0, WRAP).apply { weight = 1f })
            }
            usageRow.visibility = if (tiles.isEmpty()) GONE else VISIBLE
        }

        val total = energy?.takeIf { it.state.value is HandleState.Ready }?.entity?.numeric
        val lifetimeText = if (expandedNow && total != null) EnergyText.lifetime(total, energy?.entity?.unit) else null
        lifetime.text = lifetimeText ?: ""
        lifetime.visibility = if (lifetimeText == null) GONE else VISIBLE

        chart.visibility = if (samples.isEmpty()) GONE else VISIBLE
        if (chart.expanded != expandedNow) chart.expanded = expandedNow
        if (chart.range != range) chart.range = range
        if (chart.samples !== samples) chart.samples = samples
    }

    private companion object {
        val RANGES: List<Pair<SeriesRange, String>> = listOf(
            SeriesRange.Hour to "1H",
            SeriesRange.Day to "1D",
            SeriesRange.Week to "1W",
            SeriesRange.Month to "1M",
        )

        fun historyRange(range: SeriesRange): HistoryRange = when (range) {
            SeriesRange.Hour -> HistoryRange.Hour
            SeriesRange.Day -> HistoryRange.Day
            SeriesRange.Week -> HistoryRange.Week
            SeriesRange.Month, SeriesRange.Custom -> HistoryRange.Month
        }
    }
}

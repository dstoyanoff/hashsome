package dev.hashsome.ui

import dev.hashsome.client.HistoryPoint
import dev.hashsome.client.Iso8601
import dev.hashsome.client.LightColor
import dev.hashsome.client.LightEntity
import dev.hashsome.headless.LightColors
import dev.hashsome.headless.Rgb
import java.util.TimeZone

// The arithmetic and the words of the light drawer and the energy drawer (the web's `light-tile.tsx` color bars, `energy-chart.tsx`,
// `energy-usage.ts` and `history-section.tsx`), apart from any view so that it is tested on the JVM.

/** Energy used (kWh) per period; a period with no figure is null. Collapsed the drawer shows today / week / month, expanded today / last 7 / last 30 days. */
data class EnergyUsage(
    val today: Double? = null,
    val week: Double? = null,
    val month: Double? = null,
    val last7d: Double? = null,
    val last30d: Double? = null,
)

object EnergyText {
    /** `450 Wh` below a kWh, `1.25 kWh` below ten, `12.5 kWh` above (the web's `formatEnergy`). */
    fun format(kwh: Double): String {
        if (kwh < 1) return "${Math.floor(kwh * 1000 + 0.5).toLong()} Wh"
        return "${toFixed(kwh, if (kwh < 10) 2 else 1)} kWh"
    }

    /**
     * Energy used per period from how much a counter grew each day (30 days of daily buckets, `energy-usage.ts`). Today, this week (from
     * Monday) and this month are calendar periods; the rolling 7 and 30 days include today (the day's midnight minus six or 29 days).
     */
    fun usageFromDaily(points: List<HistoryPoint>, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): EnergyUsage {
        val today = SensorStats.startOfDay(nowMs, zone)
        fun sumSince(from: Long): Double = points.fold(0.0) { total, point ->
            val at = Iso8601.parse(point.timestamp)
            if (at != null && at >= from) total + point.value else total
        }
        return EnergyUsage(
            today = sumSince(today),
            week = sumSince(SensorStats.startOfWeek(nowMs, zone)),
            month = sumSince(SensorStats.startOfMonth(nowMs, zone)),
            last7d = sumSince(today - 6 * ChartMath.DAY_MS),
            last30d = sumSince(today - 29 * ChartMath.DAY_MS),
        )
    }

    /** The figures the usage row shows, as (label, text) pairs: the web's three tiles, and only those with a figure. */
    fun tiles(usage: EnergyUsage, expanded: Boolean): List<Pair<String, String>> {
        val items = if (expanded) {
            listOf("Today" to usage.today, "Last 7 days" to usage.last7d, "Last 30 days" to usage.last30d)
        } else {
            listOf("Today" to usage.today, "This week" to usage.week, "This month" to usage.month)
        }
        return items.mapNotNull { (label, value) -> value?.let { label to format(it) } }
    }

    /** `Lifetime 84.2 kWh`: the counter as the sensor reports it, written as JavaScript writes a number (`84`, not `84.0`). */
    fun lifetime(value: Double, unit: String?): String {
        val text = if (value == Math.floor(value) && Math.abs(value) < 1e15) value.toLong().toString() else java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        return "Lifetime $text ${unit ?: "kWh"}"
    }
}

/** The color bars and the swatches of a light's drawer (`LightColorSection`). */
object LightBars {
    const val DEFAULT_MIN_K = 2000
    const val DEFAULT_MAX_K = 6500
    const val DEFAULT_K = 2700

    /** The kelvin range of [light]: its own, else 2000..6500. */
    fun kelvinRange(light: LightEntity?): IntRange = light?.capabilities?.kelvinRange ?: DEFAULT_MIN_K..DEFAULT_MAX_K

    /** The light's white temperature, kept inside [range]; 2700 K while it shows another color mode. */
    fun kelvinOf(light: LightEntity?, range: IntRange): Int {
        val color = light?.color
        return (if (color is LightColor.Temperature) color.kelvin else DEFAULT_K).coerceIn(range.first, range.last)
    }

    /** The kelvin under a finger at [fraction] (0..1) of the bar: to the nearest 50, inside [range]. */
    fun kelvinAt(fraction: Float, range: IntRange): Int {
        val raw = range.first + fraction.coerceIn(0f, 1f) * (range.last - range.first)
        return (Math.floor(raw / 50.0 + 0.5).toInt() * 50).coerceIn(range.first, range.last)
    }

    fun fractionOfKelvin(kelvin: Int, range: IntRange): Float =
        if (range.last == range.first) 0f else ((kelvin - range.first).toFloat() / (range.last - range.first)).coerceIn(0f, 1f)

    /** The hue under a finger at [fraction] of the bar, a whole degree 0..360. */
    fun hueAt(fraction: Float): Int = Math.floor(fraction.coerceIn(0f, 1f) * 360.0 + 0.5).toInt()

    fun fractionOfHue(hue: Int): Float = (hue / 360f).coerceIn(0f, 1f)

    /** The colors of the temperature track: the white at five even steps across [range] (`kelvinGradient`). */
    fun kelvinStops(range: IntRange): IntArray = IntArray(5) { i ->
        argb(LightColors.kelvinToRgb(Math.floor(range.first + i / 4.0 * (range.last - range.first) + 0.5).toInt()))
    }

    /** The colors of the hue track: red, yellow, green, cyan, blue, magenta, red (`HUE_GRADIENT`). */
    val hueStops: IntArray = intArrayOf(
        0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FFFF.toInt(), 0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFFFF0000.toInt(),
    )

    /** The hue of [light] now, 0 when it shows no color. */
    fun hueOf(light: LightEntity?): Int {
        val rgb = colorOf(light) ?: return 0
        return LightColors.rgbToHs(rgb).first
    }

    /** The saturation to keep when the hue is moved: the light's own, 100 when it has none (the web's `currentHs(light)[1] || 100`). */
    fun saturationOf(light: LightEntity?): Double {
        val rgb = colorOf(light) ?: return 100.0
        val s = LightColors.rgbToHs(rgb).second
        return if (s == 0) 100.0 else s.toDouble()
    }

    /** What the light is showing, as a color; null while it has none (`resolveLightColor`). */
    fun colorOf(light: LightEntity?): Rgb? = when (val color = light?.color) {
        is LightColor.Temperature -> LightColors.kelvinToRgb(color.kelvin)
        is LightColor.Hs -> color.rgb?.takeIf { it.size >= 3 }?.let { Rgb(it[0], it[1], it[2]) } ?: LightColors.hsToRgb(color.hue, color.saturation)
        null -> null
    }

    fun argb(rgb: Rgb): Int = (0xFF shl 24) or (rgb.r shl 16) or (rgb.g shl 8) or rgb.b

    /** The brightness line under a name: `On · 60%`, `On`, `Off`. */
    fun powerHint(on: Boolean, percent: Int?): String = if (on) (if (percent != null) "On · $percent%" else "On") else "Off"

    /** One press of a step button moves the level by [delta], inside 0..100. */
    fun stepped(level: Int, delta: Int): Int = (level + delta).coerceIn(0, 100)
}

/** Whether a switch has a drawer to open: only when it has a power or an energy sensor to show (and the dashboard has not turned it off). */
internal object DrawerGuard {
    fun energy(power: String?, energy: String?, drawer: Boolean): Boolean = drawer && (power != null || energy != null)
}

/** A value a finger has moved to and not yet let go of: if the drawer goes away first it is committed, as the web commits on `pointercancel`. */
internal class PendingCommit<T> {
    private var held: T? = null

    fun held(value: T) {
        held = value
    }

    /** The finger let go (the value was committed already). */
    fun clear() {
        held = null
    }

    /** Commits what is still held, once. */
    fun flush(commit: (T) -> Unit) {
        val value = held ?: return
        held = null
        commit(value)
    }
}

/**
 * The press of a step button (the web's `StepButton`): a tap steps by [delta]; holding for [HOLD_MS] steps by [holdDelta] and repeats every
 * [REPEAT_MS] until released, and a finger that leaves the button or is taken away stops it. [post] / [remove] schedule the repeat.
 */
internal class StepHold(
    private val delta: Int,
    private val holdDelta: Int,
    private val onStep: (Int) -> Unit,
    private val post: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
) {
    private var held = false
    private var cancelled = false
    private val repeat = object : Runnable {
        override fun run() {
            held = true
            onStep(holdDelta)
            post(this, REPEAT_MS)
        }
    }

    fun down() {
        held = false
        cancelled = false
        post(repeat, HOLD_MS)
    }

    /** The finger moved; off the button, the press is over. */
    fun move(inside: Boolean) {
        if (!inside) cancel()
    }

    fun up(inside: Boolean) {
        remove(repeat)
        if (!held && !cancelled && inside) onStep(delta)
        held = false
    }

    fun cancel() {
        cancelled = true
        remove(repeat)
    }

    companion object {
        const val HOLD_MS = 400L
        const val REPEAT_MS = 400L
    }
}

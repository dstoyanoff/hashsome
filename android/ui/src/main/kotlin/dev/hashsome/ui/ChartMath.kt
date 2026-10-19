package dev.hashsome.ui

import java.util.Calendar
import java.util.TimeZone

// The arithmetic of the series chart and of what is said about a series (the web's `series-chart.tsx` and `sensor-stats.ts`), apart from
// any view so that it is tested on the JVM: scales and ticks, the smooth line between points, which labels fit, and the min / max of a period.

/** One reading of a series: when ([atMs], epoch ms), what ([value]) and the extra lines the chart shows for it (the sky and the wind at that hour). */
class SeriesSample(val atMs: Long, val value: Double, val notes: List<String> = emptyList())

/** The window a chart covers; [Custom] is a from / to pair the caller has already applied to the samples. */
enum class SeriesRange(val label: String) {
    Hour("1H"), Day("1D"), Week("1W"), Month("1M"), Custom("Custom")
}

/** A y axis: [min] to [max], with [ticks] at nice values between (the first is [min], the last [max]). */
class NiceScale(val min: Double, val max: Double, val ticks: DoubleArray, val step: Double)

object ChartMath {
    /** How many ticks a y axis aims for (recharts' default). */
    const val TICK_COUNT = 5

    /**
     * The y axis for values from [lo] to [hi]: ticks at 1, 2, 2.5, 5 or 10 times a power of ten, the axis reaching out to the ticks that
     * enclose the data. With [zeroBased] the axis starts at 0 (a chart whose domain is not fitted to the data).
     */
    fun niceScale(lo: Double, hi: Double, zeroBased: Boolean, count: Int = TICK_COUNT): NiceScale {
        var min = if (zeroBased) minOf(0.0, lo) else lo
        var max = if (zeroBased) maxOf(0.0, hi) else hi
        if (max - min < 1e-9) {
            // One value alone: room above and below it.
            val pad = if (min == 0.0) 1.0 else Math.abs(min) * 0.1
            if (zeroBased && min >= 0.0) max = min + pad else {
                min -= pad
                max += pad
            }
        }
        val rough = (max - min) / (count - 1)
        val magnitude = Math.pow(10.0, Math.floor(Math.log10(rough)))
        val residual = rough / magnitude
        val nice = when {
            residual <= 1.0 -> 1.0
            residual <= 2.0 -> 2.0
            residual <= 2.5 -> 2.5
            residual <= 5.0 -> 5.0
            else -> 10.0
        }
        val step = nice * magnitude
        val first = Math.floor(min / step + 1e-9) * step
        val last = Math.ceil(max / step - 1e-9) * step
        val n = Math.round((last - first) / step).toInt() + 1
        val ticks = DoubleArray(n) { clean(first + it * step, step) }
        return NiceScale(ticks.first(), ticks.last(), ticks, step)
    }

    /** [value] with the float noise of the step's arithmetic taken off (0.30000000000000004 is 0.3). */
    private fun clean(value: Double, step: Double): Double {
        val digits = maxOf(0, 2 - Math.floor(Math.log10(step)).toInt())
        val scale = Math.pow(10.0, digits.toDouble())
        return Math.round(value * scale) / scale
    }

    /** A tick or a reading as an axis writes it: no trailing zeros, at most two decimals. */
    fun axisNumber(value: Double): String {
        val rounded = Math.round(value * 100) / 100.0
        return if (rounded == Math.floor(rounded) && Math.abs(rounded) < 1e15) rounded.toLong().toString() else rounded.toString()
    }

    /** A reading to one decimal, as the web's tooltip and tiles write it: `21.4`, `21`. */
    fun tenths(value: Double): String {
        val rounded = Math.floor(value * 10 + 0.5) / 10
        return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
    }

    /**
     * The slopes of a smooth line through points at [xs] / [ys] that never overshoots them (d3's `curveMonotoneX`, which recharts'
     * `type="monotone"` uses), written to [out]. Between points i and i+1 the line is the cubic with control points a third of the way
     * along, at heights `y + slope * dx / 3`.
     */
    fun monotoneSlopes(xs: FloatArray, ys: FloatArray, n: Int, out: FloatArray) {
        if (n < 2) {
            if (n == 1) out[0] = 0f
            return
        }

        fun secant(i: Int): Float = (ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i])
        for (i in 1 until n - 1) {
            val h0 = xs[i] - xs[i - 1]
            val h1 = xs[i + 1] - xs[i]
            val s0 = secant(i - 1)
            val s1 = secant(i)
            val p = (s0 * h1 + s1 * h0) / (h0 + h1)
            val sign = (if (s0 < 0f) -1f else 1f) + (if (s1 < 0f) -1f else 1f)
            val t = sign * minOf(Math.abs(s0), Math.abs(s1), 0.5f * Math.abs(p))
            out[i] = if (t.isNaN()) 0f else t
        }
        // The ends: a slope that continues the curve's neighbour.
        out[0] = endSlope(secant(0), if (n > 2) out[1] else null)
        out[n - 1] = endSlope(secant(n - 2), if (n > 2) out[n - 2] else null)
    }

    private fun endSlope(secant: Float, neighbour: Float?): Float = if (neighbour == null) secant else (3f * secant - neighbour) / 2f

    /**
     * Which of [count] labels to write: [widths] are the labels' widths and [xs] where each is centered; two written labels are at least
     * [minGap] apart. The last is always written (recharts' `preserveEnd`), and the rest are chosen back from it, so the labels stay
     * on the same points as long as the data does. Returns the indices, in order.
     */
    fun labelIndices(xs: FloatArray, widths: FloatArray, count: Int, minGap: Float, out: IntArray): Int {
        if (count == 0) return 0
        var kept = 0
        var leftEdge = Float.POSITIVE_INFINITY
        var i = count - 1
        while (i >= 0) {
            val right = xs[i] + widths[i] / 2
            if (right + minGap <= leftEdge) {
                out[kept++] = i
                leftEdge = xs[i] - widths[i] / 2
            }
            i--
        }
        // Written from the end; handed over from the start.
        var a = 0
        var b = kept - 1
        while (a < b) {
            val t = out[a]
            out[a] = out[b]
            out[b] = t
            a++
            b--
        }
        return kept
    }

    /** The index of the point nearest to [x] among [n] points evenly spread from [left] to [right]. */
    fun nearestIndex(x: Float, left: Float, right: Float, n: Int): Int {
        if (n <= 1 || right <= left) return 0
        return Math.round(((x - left) / (right - left)).coerceIn(0f, 1f) * (n - 1))
    }

    /** At most [limit] samples, thinned by averaging buckets so that a month of readings stays cheap to draw (the web's `downsample`). */
    fun downsample(samples: List<SeriesSample>, limit: Int): List<SeriesSample> {
        if (samples.size <= limit) return samples
        val size = Math.ceil(samples.size.toDouble() / limit).toInt()
        val out = ArrayList<SeriesSample>(limit)
        var i = 0
        while (i < samples.size) {
            val end = minOf(i + size, samples.size)
            var sum = 0.0
            for (k in i until end) sum += samples[k].value
            // The middle one of the bucket stands for when.
            out += SeriesSample(samples[i + (end - i) / 2].atMs, sum / (end - i))
            i = end
        }
        return out
    }

    /** The pattern skeleton (for `DateFormat.getBestDateTimePattern`) of a tick under the x axis of [range]. */
    fun tickSkeleton(range: SeriesRange): String = when (range) {
        SeriesRange.Hour, SeriesRange.Day -> "jm"
        SeriesRange.Week -> "EEE"
        SeriesRange.Month, SeriesRange.Custom -> "MMMd"
    }

    /** The window a range covers, in ms; null for [SeriesRange.Custom]. */
    fun windowMs(range: SeriesRange): Long? = when (range) {
        SeriesRange.Hour -> HOUR_MS
        SeriesRange.Day -> DAY_MS
        SeriesRange.Week -> 7 * DAY_MS
        SeriesRange.Month -> 30 * DAY_MS
        SeriesRange.Custom -> null
    }

    const val HOUR_MS = 3_600_000L
    const val DAY_MS = 86_400_000L
}

/** The min, max and average of a period. */
data class MinMax(val min: Double, val max: Double, val avg: Double)

/** The periods the sensor drawer aggregates over. */
enum class Period { Today, Week, Month, Last7d, Last30d }

/** What a sensor drawer says about a series: the periods' windows, their min and max, and the month's and the week's points joined. */
object SensorStats {
    fun startOfDay(ms: Long, zone: TimeZone): Long = TaskText.startOfDay(ms, zone)

    /** Local midnight of the most recent Monday. */
    fun startOfWeek(ms: Long, zone: TimeZone): Long {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = startOfDay(ms, zone) }
        val sinceMonday = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
        cal.add(Calendar.DAY_OF_MONTH, -sinceMonday)
        return cal.timeInMillis
    }

    fun startOfMonth(ms: Long, zone: TimeZone): Long {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = startOfDay(ms, zone) }
        cal.set(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    /** Where [period] starts, for a window that ends at [nowMs]. */
    fun periodStart(period: Period, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Long = when (period) {
        Period.Today -> startOfDay(nowMs, zone)
        Period.Week -> startOfWeek(nowMs, zone)
        Period.Month -> startOfMonth(nowMs, zone)
        Period.Last7d -> nowMs - 7 * ChartMath.DAY_MS
        Period.Last30d -> nowMs - 30 * ChartMath.DAY_MS
    }

    /** The samples from [from] to [to], both included. */
    fun between(samples: List<SeriesSample>, from: Long, to: Long): List<SeriesSample> = samples.filter { it.atMs in from..to }

    /** Min / max / average of [samples], or null when there are none. */
    fun aggregate(samples: List<SeriesSample>): MinMax? {
        if (samples.isEmpty()) return null
        var min = Double.POSITIVE_INFINITY
        var max = Double.NEGATIVE_INFINITY
        var sum = 0.0
        for (s in samples) {
            min = minOf(min, s.value)
            max = maxOf(max, s.value)
            sum += s.value
        }
        return MinMax(min, max, sum / samples.size)
    }

    /** The month's coarse points up to where the week's finer ones begin, then those (the web's `mergeHistory`). */
    fun merge(coarse: List<SeriesSample>, fine: List<SeriesSample>): List<SeriesSample> {
        val from = fine.firstOrNull()?.atMs
        return coarse.filter { from == null || it.atMs < from } + fine
    }

    /** `12.3 – 20`, a period's min and max written to a tenth (`—` when there is no reading), then the unit. */
    fun range(value: MinMax?, unit: String): String =
        if (value == null) "—" else "${ChartMath.tenths(value.min)} – ${ChartMath.tenths(value.max)}${if (unit.isNotEmpty()) " $unit" else ""}"
}

/** The words of a forecast (the web's `weather-forecast.tsx` and `weather-look.tsx`). */
object ForecastText {
    private val COMPASS = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")

    /** The compass point for a bearing in degrees: 225 is `SW`. */
    fun compass(degrees: Double): String {
        val turn = ((degrees % 360) + 360) % 360
        return COMPASS[Math.round(turn / 22.5).toInt() % 16]
    }

    /** How strong a UV index is, in words. */
    fun uvLevel(index: Double): String = when {
        index < 3 -> "low"
        index < 6 -> "moderate"
        index < 8 -> "high"
        index < 11 -> "very high"
        else -> "extreme"
    }

    /** A temperature as a whole degree, or `–` when there is none. */
    fun degrees(value: Double?): String = if (value == null) "–" else "${roundHalfUp(value)}°"

    /** `15 km/h from WNW`, with what there is of it. */
    fun wind(speed: Double, bearing: Double?, unit: String?): String =
        "${roundHalfUp(speed)}${if (unit != null) " $unit" else ""}${if (bearing != null) " from ${compass(bearing)}" else ""}"

    /** `60% · 0.4 mm`, for the rain a step has: a chance, an amount, or both. Null for a dry step. */
    fun rain(probability: Double?, amount: Double?, unit: String?): String? {
        val parts = ArrayList<String>(2)
        if (probability != null && probability != 0.0) parts += "${roundHalfUp(probability)}%"
        if (amount != null && amount != 0.0) parts += "${ChartMath.tenths(amount)} ${unit ?: "mm"}"
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    /** Which day a timestamp is, for the days list: `Today`, `Tomorrow`, else [weekday] (the day's own name). */
    fun dayName(atMs: Long, nowMs: Long, weekday: String, zone: TimeZone = TimeZone.getDefault()): String {
        val days = TaskText.daysTo(atMs, nowMs, zone)
        return when (days) {
            0 -> "Today"
            1 -> "Tomorrow"
            else -> weekday
        }
    }
}

/** The words of an entity's recent activity (the web's `history-section.tsx`). */
object ActivityText {
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /** How long ago, in the coarsest unit that fits: `Just now`, `5m ago`, `2h ago`, `3d ago`. */
    fun relativeTime(atMs: Long, nowMs: Long): String {
        val diff = nowMs - atMs
        return when {
            diff < MINUTE -> "Just now"
            diff < HOUR -> "${Math.round(diff.toDouble() / MINUTE)}m ago"
            diff < DAY -> "${Math.round(diff.toDouble() / HOUR)}h ago"
            else -> "${Math.round(diff.toDouble() / DAY)}d ago"
        }
    }

    /** The first letters of the first two words of a name, in capitals: `Dana Scully` is `DS`. */
    fun initials(name: String): String =
        name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.substring(0, 1).uppercase(java.util.Locale.ROOT) }

    /** A message that nobody caused starts with a capital (`turned on` after a name, `Turned on` alone). */
    fun capitalize(message: String): String = if (message.isEmpty()) message else message.substring(0, 1).uppercase(java.util.Locale.ROOT) + message.substring(1)
}

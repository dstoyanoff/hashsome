package dev.hashsome.ui

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// series-chart.tsx, sensor-stats.ts and weather-forecast.tsx
class ChartMathTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance(utc).apply { clear(); set(year, month - 1, day, hour, minute) }.timeInMillis

    private fun sample(ms: Long, v: Double) = SeriesSample(ms, v)

    @Test
    fun `ticks are round numbers that enclose the data`() {
        val scale = ChartMath.niceScale(21.3, 24.1, zeroBased = false)
        assertEquals(21.0, scale.min, 0.0)
        assertEquals(25.0, scale.max, 0.0)
        assertArrayEquals(doubleArrayOf(21.0, 22.0, 23.0, 24.0, 25.0), scale.ticks, 1e-9)
    }

    @Test
    fun `a zero based axis starts at nothing`() {
        val scale = ChartMath.niceScale(180.0, 940.0, zeroBased = true)
        assertEquals(0.0, scale.min, 0.0)
        assertEquals(1000.0, scale.max, 0.0)
        assertArrayEquals(doubleArrayOf(0.0, 250.0, 500.0, 750.0, 1000.0), scale.ticks, 1e-9)
    }

    @Test
    fun `a flat series still gets an axis with room around it`() {
        val fitted = ChartMath.niceScale(20.0, 20.0, zeroBased = false)
        assertTrue(fitted.min < 20.0 && fitted.max > 20.0)
        val zero = ChartMath.niceScale(0.0, 0.0, zeroBased = true)
        assertEquals(0.0, zero.min, 0.0)
        assertTrue(zero.max > 0.0)
        val based = ChartMath.niceScale(5.0, 5.0, zeroBased = true)
        assertEquals(0.0, based.min, 0.0)
        assertTrue(based.max >= 5.0)
    }

    @Test
    fun `ticks are written without noise`() {
        val scale = ChartMath.niceScale(0.0, 0.7, zeroBased = true)
        assertEquals(listOf("0", "0.2", "0.4", "0.6", "0.8"), scale.ticks.map { ChartMath.axisNumber(it) })
        assertEquals("21.4", ChartMath.tenths(21.43))
        assertEquals("21", ChartMath.tenths(21.04))
    }

    @Test
    fun `a straight line has the same slope everywhere`() {
        val xs = floatArrayOf(0f, 10f, 20f, 30f)
        val ys = floatArrayOf(0f, 5f, 10f, 15f)
        val out = FloatArray(4)
        ChartMath.monotoneSlopes(xs, ys, 4, out)
        out.forEach { assertEquals(0.5f, it, 1e-6f) }
    }

    @Test
    fun `a peak is flat at the top so the line does not overshoot`() {
        val xs = floatArrayOf(0f, 10f, 20f)
        val ys = floatArrayOf(0f, 10f, 0f)
        val out = FloatArray(3)
        ChartMath.monotoneSlopes(xs, ys, 3, out)
        assertEquals(0f, out[1], 0f)
        // The ends lean toward the peak.
        assertTrue(out[0] > 0f)
        assertTrue(out[2] < 0f)
    }

    @Test
    fun `two points are joined by a straight line`() {
        val out = FloatArray(2)
        ChartMath.monotoneSlopes(floatArrayOf(0f, 4f), floatArrayOf(1f, 3f), 2, out)
        assertEquals(0.5f, out[0], 1e-6f)
        assertEquals(0.5f, out[1], 1e-6f)
    }

    @Test
    fun `labels keep their gap, the last one is always written`() {
        // Ten points 20 apart, labels 30 wide, 24 apart at least: every second would touch (50 > 20 + ...), so every third is written.
        val xs = FloatArray(10) { it * 20f }
        val widths = FloatArray(10) { 30f }
        val out = IntArray(10)
        val n = ChartMath.labelIndices(xs, widths, 10, 24f, out)
        assertEquals(listOf(0, 3, 6, 9), out.take(n))
        assertEquals(9, out[n - 1])
        for (k in 1 until n) assertTrue(xs[out[k]] - xs[out[k - 1]] >= 30f + 24f)
    }

    @Test
    fun `a point is picked by where the finger is`() {
        assertEquals(0, ChartMath.nearestIndex(-20f, 36f, 336f, 11))
        assertEquals(5, ChartMath.nearestIndex(186f, 36f, 336f, 11))
        assertEquals(10, ChartMath.nearestIndex(900f, 36f, 336f, 11))
        assertEquals(0, ChartMath.nearestIndex(50f, 0f, 100f, 1))
    }

    @Test
    fun `a long series is thinned by averaging, a short one is left`() {
        val short = List(10) { sample(it * 1000L, it.toDouble()) }
        assertEquals(short, ChartMath.downsample(short, 240))
        val long = List(960) { sample(it * 1000L, (it % 4).toDouble()) }
        val thin = ChartMath.downsample(long, 240)
        assertEquals(240, thin.size)
        assertEquals(1.5, thin[0].value, 1e-9)
        // A bucket stands at its middle.
        assertEquals(2000L, thin[0].atMs)
    }

    @Test
    fun `a range says what its ticks look like and how far back it goes`() {
        assertEquals("jm", ChartMath.tickSkeleton(SeriesRange.Hour))
        assertEquals("jm", ChartMath.tickSkeleton(SeriesRange.Day))
        assertEquals("EEE", ChartMath.tickSkeleton(SeriesRange.Week))
        assertEquals("MMMd", ChartMath.tickSkeleton(SeriesRange.Month))
        assertEquals(24 * 3_600_000L, ChartMath.windowMs(SeriesRange.Day))
        assertNull(ChartMath.windowMs(SeriesRange.Custom))
    }

    @Test
    fun `a week starts on Monday, a month on the first, a day at midnight`() {
        // Wednesday 2026-03-11 15:30.
        val now = at(2026, 3, 11, 15, 30)
        assertEquals(at(2026, 3, 11), SensorStats.periodStart(Period.Today, now, utc))
        assertEquals(at(2026, 3, 9), SensorStats.periodStart(Period.Week, now, utc))
        assertEquals(at(2026, 3, 1), SensorStats.periodStart(Period.Month, now, utc))
        assertEquals(now - 7 * ChartMath.DAY_MS, SensorStats.periodStart(Period.Last7d, now, utc))
        assertEquals(now - 30 * ChartMath.DAY_MS, SensorStats.periodStart(Period.Last30d, now, utc))
        // On a Monday and on a Sunday.
        assertEquals(at(2026, 3, 9), SensorStats.periodStart(Period.Week, at(2026, 3, 9, 8), utc))
        assertEquals(at(2026, 3, 9), SensorStats.periodStart(Period.Week, at(2026, 3, 15, 23), utc))
    }

    @Test
    fun `min and max are of the samples in the window`() {
        val samples = listOf(sample(1, 5.0), sample(2, 9.0), sample(3, 1.0), sample(10, 100.0))
        val within = SensorStats.between(samples, 1, 3)
        assertEquals(3, within.size)
        assertEquals(MinMax(1.0, 9.0, 5.0), SensorStats.aggregate(within))
        assertNull(SensorStats.aggregate(emptyList()))
        assertEquals("1 – 9 °C", SensorStats.range(MinMax(1.0, 9.0, 5.0), "°C"))
        assertEquals("20.5 – 22", SensorStats.range(MinMax(20.46, 22.0, 21.0), ""))
        assertEquals("—", SensorStats.range(null, "°C"))
    }

    @Test
    fun `the week's fine points replace the month's coarse ones from where they begin`() {
        val month = List(5) { sample(it * 100L, 1.0) }
        val week = List(3) { sample(300L + it * 50, 2.0) }
        val merged = SensorStats.merge(month, week)
        assertEquals(listOf(0L, 100L, 200L, 300L, 350L, 400L), merged.map { it.atMs })
        assertEquals(month, SensorStats.merge(month, emptyList()))
        assertEquals(week, SensorStats.merge(emptyList(), week))
    }

    @Test
    fun `a bearing is a compass point`() {
        assertEquals("N", ForecastText.compass(0.0))
        assertEquals("N", ForecastText.compass(360.0))
        assertEquals("SW", ForecastText.compass(225.0))
        assertEquals("WNW", ForecastText.compass(292.5))
        assertEquals("NNW", ForecastText.compass(-20.0))
    }

    @Test
    fun `uv is said in words`() {
        assertEquals(listOf("low", "moderate", "high", "very high", "extreme"), listOf(2.9, 3.0, 6.0, 8.0, 11.0).map { ForecastText.uvLevel(it) })
    }

    @Test
    fun `wind, rain and degrees are written as the forecast writes them`() {
        assertEquals("15 km/h from WNW", ForecastText.wind(14.6, 292.0, "km/h"))
        assertEquals("15", ForecastText.wind(15.0, null, null))
        assertEquals("60% · 0.4 mm", ForecastText.rain(60.0, 0.44, "mm"))
        assertEquals("0.4 mm", ForecastText.rain(0.0, 0.4, null))
        assertNull(ForecastText.rain(0.0, 0.0, "mm"))
        assertNull(ForecastText.rain(null, null, null))
        assertEquals("22°", ForecastText.degrees(21.5))
        assertEquals("–", ForecastText.degrees(null))
        assertEquals("-1°", ForecastText.degrees(-0.5 - 0.4))
    }

    @Test
    fun `a day is today, tomorrow, or its own name`() {
        val now = at(2026, 3, 11, 15, 30)
        assertEquals("Today", ForecastText.dayName(at(2026, 3, 11, 0), now, "Wednesday", utc))
        assertEquals("Tomorrow", ForecastText.dayName(at(2026, 3, 12, 0), now, "Thursday", utc))
        assertEquals("Friday", ForecastText.dayName(at(2026, 3, 13, 0), now, "Friday", utc))
    }
}

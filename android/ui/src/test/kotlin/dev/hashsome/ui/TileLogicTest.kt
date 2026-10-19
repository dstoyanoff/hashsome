package dev.hashsome.ui

import dev.hashsome.client.Availability
import dev.hashsome.client.EntityRef
import dev.hashsome.client.ForecastPoint
import dev.hashsome.client.Iso8601
import dev.hashsome.client.SensorEntity
import dev.hashsome.client.WeatherCondition
import dev.hashsome.headless.HandleState
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TileLogicTest {
    private val us = Locale.US
    private val utc = TimeZone.getTimeZone("UTC")
    private fun at(iso: String): Long = Iso8601.parse(iso)!!

    private fun sensor(availability: Availability) =
        SensorEntity(EntityRef("ha:sensor.x"), "X", availability, value = "1")

    @Test
    fun `a tile that is not ready says why, in the web's words`() {
        assertEquals("…", StatusLabels.of(HandleState.Loading))
        assertEquals("Not found", StatusLabels.of(HandleState.Missing))
        assertEquals("Unsupported", StatusLabels.of(HandleState.Unsupported))
        assertEquals("Unavailable", StatusLabels.of(HandleState.Unavailable(null)))
        assertEquals("Unavailable", StatusLabels.of(HandleState.Unavailable(sensor(Availability.Unavailable))))
        assertEquals("Unknown", StatusLabels.of(HandleState.Unavailable(sensor(Availability.Unknown))))
        assertNull(StatusLabels.of(HandleState.Ready(sensor(Availability.Ready))))
    }

    @Test
    fun `the light tile's second line`() {
        assertEquals("Off", LightTileText.secondary(on = false, dimmable = true, percent = 40))
        assertEquals("78%", LightTileText.secondary(on = true, dimmable = true, percent = 78))
        assertNull(LightTileText.secondary(on = true, dimmable = false, percent = 100))
        assertNull(LightTileText.secondary(on = true, dimmable = true, percent = null))
    }

    @Test
    fun `watts are written to one decimal with a point whatever the locale`() {
        assertEquals("38.0 W", wattsText(38.0))
        assertEquals("12.5 W", wattsText(12.46))
        assertEquals("0.0 W", wattsText(0.0))
    }

    @Test
    fun `halves round up as JavaScript's do`() {
        assertEquals(3, roundHalfUp(2.5))
        assertEquals(-2, roundHalfUp(-2.5))
        assertEquals(12, roundHalfUp(12.3))
    }

    @Test
    fun `the date chip`() {
        assertEquals("Tue · Oct 6", WallClockText.date(at("2026-10-06T15:07:00Z"), us, utc))
        assertEquals("Mon · Oct 19", WallClockText.date(at("2026-10-19T00:00:00Z"), us, utc))
    }

    @Test
    fun `the clock in 24 and 12 hours`() {
        assertEquals(WallClockText.Clock("15:07", null), WallClockText.clock(at("2026-10-06T15:07:00Z"), true, us, utc))
        assertEquals(WallClockText.Clock("00:05", null), WallClockText.clock(at("2026-10-06T00:05:00Z"), true, us, utc))
        assertEquals(WallClockText.Clock("3:07", "PM"), WallClockText.clock(at("2026-10-06T15:07:00Z"), false, us, utc))
        assertEquals(WallClockText.Clock("12:05", "AM"), WallClockText.clock(at("2026-10-06T00:05:00Z"), false, us, utc))
        assertEquals(WallClockText.Clock("12:30", "PM"), WallClockText.clock(at("2026-10-06T12:30:00Z"), false, us, utc))
    }

    @Test
    fun `the clock changes when the minute does`() {
        assertEquals(60_050L, WallClockText.untilNextMinute(0L))
        assertEquals(50L + 1L, WallClockText.untilNextMinute(59_999L))
        assertEquals(30_050L, WallClockText.untilNextMinute(90_000L))
    }

    private fun point(timestamp: String, high: Double?, low: Double?) =
        ForecastPoint(timestamp, WeatherCondition.Sunny, temperature = high, low = low)

    @Test
    fun `today's high and low under the reading`() {
        val now = at("2026-10-06T10:00:00Z")
        val points = listOf(
            point("2026-10-05T00:00:00Z", 9.0, 2.0),
            point("2026-10-06T00:00:00Z", 14.6, 3.4),
            point("2026-10-07T00:00:00Z", 20.0, 9.0),
        )
        assertEquals("15° / 3°", WeatherRange.of(points, now, utc))
    }

    @Test
    fun `the first forecast point stands in when none is for today`() {
        val points = listOf(point("2026-10-08T00:00:00Z", 8.5, 1.0))
        assertEquals("9° / 1°", WeatherRange.of(points, at("2026-10-06T10:00:00Z"), utc))
    }

    @Test
    fun `no range without a low or without points`() {
        assertNull(WeatherRange.of(emptyList(), 0L, utc))
        assertNull(WeatherRange.of(listOf(point("2026-10-06T00:00:00Z", 14.0, null)), at("2026-10-06T10:00:00Z"), utc))
    }

    @Test
    fun `the line under a task`() {
        val now = at("2026-03-01T10:00:00Z")
        fun line(state: TaskState, due: String?) = TaskText.line(TaskInfo(state, due?.let { at(it) }), now, us, utc)
        assertEquals("Up to date", line(TaskState.Ok, null))
        assertEquals("Due soon", line(TaskState.DueSoon, null))
        assertEquals("Overdue", line(TaskState.Overdue, null))
        assertEquals("Next Mar 10", line(TaskState.Ok, "2026-03-10T00:00:00Z"))
        assertEquals("Next Mar 10, 2027", line(TaskState.Ok, "2027-03-10T00:00:00Z"))
        assertEquals("Due today", line(TaskState.DueSoon, "2026-03-01T23:00:00Z"))
        assertEquals("Due tomorrow", line(TaskState.DueSoon, "2026-03-02T00:00:00Z"))
        assertEquals("Due in 3 days", line(TaskState.DueSoon, "2026-03-04T00:00:00Z"))
        assertEquals("Overdue by 1 day", line(TaskState.Overdue, "2026-02-28T00:00:00Z"))
        assertEquals("Overdue by 3 days", line(TaskState.Overdue, "2026-02-26T12:00:00Z"))
    }

    @Test
    fun `days are counted by the calendar, not by 24 hours`() {
        // Late evening to early next morning is one day.
        assertEquals(1, TaskText.daysTo(at("2026-03-02T01:00:00Z"), at("2026-03-01T23:00:00Z"), utc))
        assertEquals(0, TaskText.daysTo(at("2026-03-01T23:59:00Z"), at("2026-03-01T00:00:00Z"), utc))
        assertEquals(-2, TaskText.daysTo(at("2026-02-27T12:00:00Z"), at("2026-03-01T12:00:00Z"), utc))
    }

    @Test
    fun `the day the stepper names`() {
        val now = at("2026-03-10T10:00:00Z")
        assertEquals("Today", TaskText.dayWords(0, now, us, utc))
        assertEquals("Yesterday", TaskText.dayWords(1, now, us, utc))
        assertEquals("Sat, Mar 7", TaskText.dayWords(3, now, us, utc))
    }

    @Test
    fun `a task done today is done now, an earlier one at the start of its day`() {
        val now = at("2026-03-10T10:30:00Z")
        assertEquals(now, TaskText.doneOn(0, now, utc))
        assertEquals(at("2026-03-08T00:00:00Z"), TaskText.doneOn(2, now, utc))
    }

    @Test
    fun `watts round the exact binary value, as toFixed does`() {
        assertEquals("0.3 W", wattsText(0.25))
        assertEquals("1.1 W", wattsText(1.05))
        assertEquals("1.1 W", wattsText(1.15))
        assertEquals("38.0 W", wattsText(38.0))
    }

    @Test
    fun `the climate reading has the decimals the temperature has`() {
        assertEquals("21 °C", climateReading(21.0, "°C"))
        assertEquals("21.5 °C", climateReading(21.5, "°C"))
        assertEquals("21.25 °C", climateReading(21.25, "°C"))
        assertEquals("16.4 °F", climateReading(16.4, "°F"))
        assertEquals(0, decimalsOf(Double.NaN))
    }

    @Test
    fun `days across a daylight saving change are still counted by the calendar`() {
        val ny = TimeZone.getTimeZone("America/New_York")
        fun local(y: Int, m: Int, d: Int, h: Int): Long =
            java.util.Calendar.getInstance(ny).apply { clear(); set(y, m - 1, d, h, 0) }.timeInMillis
        // The clocks go back on Nov 1 2026: that day is 25 hours long.
        assertEquals(1, TaskText.daysTo(local(2026, 11, 2, 12), local(2026, 11, 1, 12), ny))
        assertEquals(2, TaskText.daysTo(local(2026, 11, 2, 12), local(2026, 10, 31, 12), ny))
        assertEquals(1, TaskText.daysTo(local(2026, 11, 2, 0), local(2026, 11, 1, 23), ny))
        assertEquals(-1, TaskText.daysTo(local(2026, 10, 31, 23), local(2026, 11, 1, 0), ny))
    }

    @Test
    fun `the next date names the year when it is another year`() {
        val now = at("2026-12-20T10:00:00Z")
        assertEquals("Next Jan 5, 2027", TaskText.line(TaskInfo(TaskState.Ok, at("2027-01-05T00:00:00Z")), now, us, utc))
        assertEquals("Next Dec 31", TaskText.line(TaskInfo(TaskState.Ok, at("2026-12-31T00:00:00Z")), now, us, utc))
    }

    @Test
    fun `a task that is due soon but already past its date reads as overdue`() {
        val now = at("2026-03-10T10:00:00Z")
        assertEquals("Overdue by 2 days", TaskText.line(TaskInfo(TaskState.DueSoon, at("2026-03-08T00:00:00Z")), now, us, utc))
    }
}

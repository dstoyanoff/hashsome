package dev.hashsome.headless

import dev.hashsome.client.Availability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

// theme/__tests__/schedule.test.ts, and the provider's use of it.
class ThemeScheduleTest {
    private val rig = Rig()
    private val night = ThemeSchedule.Clock("19:00", "07:00")
    private val day = ThemeSchedule.Clock("09:00", "17:00")
    private val minute = 60_000L
    private val hour = 60 * minute
    private val zone = TimeZone.getDefault()

    private fun at(time: String, second: Int = 0): Long {
        val (h, m) = time.split(":").map { it.toInt() }
        return Calendar.getInstance(zone).apply {
            clear()
            set(2026, Calendar.OCTOBER, 6, h, m, second)
        }.timeInMillis
    }

    @Test
    fun `dark hours that run past midnight`() {
        assertFalse(isDarkAt(night, at("18:59")))
        assertTrue(isDarkAt(night, at("19:00")))
        assertTrue(isDarkAt(night, at("00:00")))
        assertTrue(isDarkAt(night, at("06:59")))
        assertFalse(isDarkAt(night, at("07:00")))
        assertFalse(isDarkAt(night, at("12:00")))
    }

    @Test
    fun `dark hours within one day`() {
        assertFalse(isDarkAt(day, at("08:59")))
        assertTrue(isDarkAt(day, at("09:00")))
        assertTrue(isDarkAt(day, at("16:59")))
        assertFalse(isDarkAt(day, at("17:00")))
    }

    @Test
    fun `the same time twice is never dark, and never switches`() {
        val none = ThemeSchedule.Clock("08:00", "08:00")
        assertFalse(isDarkAt(none, at("08:00")))
        assertFalse(isDarkAt(none, at("20:00")))
        assertNull(msUntilSwitch(none, at("12:00")))
    }

    @Test
    fun `how long until the next change, whichever boundary is first, rolling over midnight`() {
        assertEquals(7 * hour, msUntilSwitch(night, at("12:00")))
        assertEquals(11 * hour, msUntilSwitch(night, at("20:00")))
        assertEquals(30 * minute, msUntilSwitch(night, at("06:30")))
        // Just switched: the next is 07:00.
        assertEquals(12 * hour, msUntilSwitch(night, at("19:00")))
    }

    @Test
    fun `a time that is not HH_MM is refused, naming which one`() {
        assertEquals(19 * 60, minutesOf("19:00", "from"))
        assertEquals(23 * 60 + 59, minutesOf("23:59", "to"))
        try {
            minutesOf("7pm", "from")
            fail()
        } catch (e: IllegalArgumentException) {
            assertEquals("theme.dark.from must be a time like \"19:00\", not \"7pm\"", e.message)
        }

        listOf("24:00", "7:00", "19:60", "").forEach {
            try {
                minutesOf(it, "to")
                fail("accepted '$it'")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.startsWith("theme.dark.to must be a time like"))
            }
        }

        try {
            isDarkAt(ThemeSchedule.Clock("19:00", "7:00"), at("12:00"))
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("theme.dark.to"))
        }
    }

    @Test
    fun `a daylight sensor says the sun is down once it is off, and nothing useful otherwise`() {
        fun daylight(value: String, measurement: String? = "daylight") = sensor(value = value, unit = null, measurement = measurement)
        assertEquals(true, sunIsDown(daylight("off")))
        assertEquals(false, sunIsDown(daylight("on")))
        assertNull(sunIsDown(daylight("unavailable")))
        // Not a daylight sensor: an `off` on its own says nothing about the sun.
        assertNull(sunIsDown(daylight("off", "motion")))
        assertNull(sunIsDown(daylight("off", null)))
        assertNull(sunIsDown(switch(on = false)))
        assertNull(sunIsDown(null))
    }

    // --- the controller ----------------------------------------------------------------------------------------

    @Test
    fun `a fixed schedule is always dark, or always light`() {
        assertTrue(ThemeController(ThemeSchedule.Fixed(true), rig.scope).dark.value)
        assertFalse(ThemeController(ThemeSchedule.Fixed(false), rig.scope).dark.value)
        rig.scheduler.advance(48 * hour)
        assertEquals(0, rig.scheduler.pending)
    }

    @Test
    fun `a clock schedule starts from the time it is and switches at each boundary`() {
        rig.scheduler.wall = at("18:00")
        val theme = ThemeController(night, rig.scope)
        val seen = ArrayList<Boolean>()
        theme.dark.listen { seen += it }
        assertFalse(theme.dark.value)
        rig.scheduler.advance(hour - 1)
        assertFalse(theme.dark.value)
        // A moment past 19:00, so the clock has certainly crossed it.
        rig.scheduler.advance(500 + 1)
        assertTrue(theme.dark.value)
        rig.scheduler.advance(12 * hour + 500)
        assertFalse(theme.dark.value)
        assertEquals(listOf(true, false), seen)
        rig.scheduler.advance(12 * hour)
        assertEquals(listOf(true, false, true), seen)
    }

    @Test
    fun `a clock schedule that starts inside the dark hours is dark`() {
        rig.scheduler.wall = at("03:00")
        assertTrue(ThemeController(night, rig.scope).dark.value)
    }

    @Test
    fun `the clock is read again on recheck, since a sleeping display misses timers`() {
        rig.scheduler.wall = at("12:00")
        val theme = ThemeController(night, rig.scope)
        assertFalse(theme.dark.value)
        // The clock jumped (the display was asleep): nothing fired.
        rig.scheduler.wall = at("22:00")
        assertFalse(theme.dark.value)
        theme.recheck()
        assertTrue(theme.dark.value)
        // A recheck leaves one timer, for the next boundary.
        assertEquals(1, rig.scheduler.pending)
    }

    @Test
    fun `a clock schedule with the same time twice is light and sets no timer`() {
        rig.scheduler.wall = at("12:00")
        val theme = ThemeController(ThemeSchedule.Clock("08:00", "08:00"), rig.scope)
        assertFalse(theme.dark.value)
        assertEquals(0, rig.scheduler.pending)
    }

    @Test
    fun `a clock that is not a time is refused when the controller is made`() {
        try {
            ThemeController(ThemeSchedule.Clock("7pm", "07:00"), rig.scope)
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("theme.dark.from"))
        }
    }

    @Test
    fun `the timer ends with the scope`() {
        rig.scheduler.wall = at("12:00")
        ThemeController(night, rig.scope)
        rig.scope.dispose()
        assertEquals(0, rig.scheduler.pending)
    }

    private fun daylight(value: String) = sensor(id = "sun", value = value, unit = null, measurement = "daylight")

    @Test
    fun `a sun schedule is dark while the sun is down`() {
        val theme = ThemeController(ThemeSchedule.Sun(ref("sun")), rig.scope)
        assertFalse(theme.dark.value)
        rig.client.set(ref("sun"), daylight("off"))
        rig.settle()
        assertTrue(theme.dark.value)
        rig.client.set(ref("sun"), daylight("on"))
        rig.settle()
        assertFalse(theme.dark.value)
    }

    @Test
    fun `a sun that says nothing useful leaves the theme as it was`() {
        val theme = ThemeController(ThemeSchedule.Sun(ref("sun")), rig.scope)
        rig.client.set(ref("sun"), daylight("off"))
        rig.settle()
        rig.client.set(ref("sun"), daylight("unavailable").copy(availability = Availability.Unavailable))
        rig.settle()
        assertTrue(theme.dark.value)
        rig.client.remove(ref("sun"))
        rig.settle()
        assertTrue(theme.dark.value)
        rig.client.set(ref("sun"), switch(id = "sun", on = false))
        rig.settle()
        assertTrue(theme.dark.value)
    }

    @Test
    fun `a sun schedule follows the entity at once when it is known`() {
        rig.client.set(ref("sun"), daylight("off"))
        val theme = ThemeController(ThemeSchedule.Sun(ref("sun")), rig.scope)
        rig.settle()
        assertTrue(theme.dark.value)
    }

    @Test
    fun `the sun is not followed after the scope is disposed`() {
        val theme = ThemeController(ThemeSchedule.Sun(ref("sun")), rig.scope)
        rig.scope.dispose()
        rig.client.set(ref("sun"), daylight("off"))
        rig.settle()
        assertFalse(theme.dark.value)
    }
}

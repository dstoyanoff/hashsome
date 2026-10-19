package dev.hashsome.ui

import dev.hashsome.client.HistoryPoint
import dev.hashsome.client.LightCapabilities
import dev.hashsome.client.LightColor
import dev.hashsome.client.LightEntity
import dev.hashsome.client.EntityRef
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// energy-chart.tsx, energy-usage.ts, the color bars of light-tile.tsx and history-section.tsx
class DrawerMathTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0): Long =
        Calendar.getInstance(utc).apply { clear(); set(year, month - 1, day, hour, 0) }.timeInMillis

    private fun day(year: Int, month: Int, d: Int, kwh: Double): HistoryPoint {
        val ms = at(year, month, d)
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").apply { timeZone = utc }.format(java.util.Date(ms))
        return HistoryPoint(iso, kwh)
    }

    private fun light(color: LightColor? = null, range: IntRange? = null) = LightEntity(
        ref = EntityRef("ha:light.x"),
        name = "x",
        availability = dev.hashsome.client.Availability.Ready,
        on = true,
        color = color,
        capabilities = LightCapabilities(brightness = true, colorTemperature = true, color = true, kelvinRange = range),
    )

    @Test
    fun `energy is written in Wh below a kWh, two decimals below ten and one above`() {
        assertEquals("0 Wh", EnergyText.format(0.0))
        assertEquals("450 Wh", EnergyText.format(0.45))
        assertEquals("1.00 kWh", EnergyText.format(1.0))
        assertEquals("1.25 kWh", EnergyText.format(1.25))
        assertEquals("9.99 kWh", EnergyText.format(9.994))
        assertEquals("12.5 kWh", EnergyText.format(12.46))
        assertEquals("1 Wh", EnergyText.format(0.0005))
    }

    @Test
    fun `usage is summed from the days since each period began`() {
        // Wednesday 2026-03-18, 15:00: the week began Monday the 16th, the month on the 1st.
        val now = at(2026, 3, 18, 15)
        val points = listOf(
            day(2026, 2, 17, 100.0), // 29 days back, the first day of the rolling 30
            day(2026, 3, 1, 1.0),
            day(2026, 3, 12, 2.0),
            day(2026, 3, 15, 4.0),
            day(2026, 3, 16, 8.0),
            day(2026, 3, 17, 16.0),
            day(2026, 3, 18, 32.0),
        )
        val usage = EnergyText.usageFromDaily(points, now, utc)
        assertEquals(32.0, usage.today!!, 0.0)
        assertEquals(8.0 + 16 + 32, usage.week!!, 0.0)
        assertEquals(1.0 + 2 + 4 + 8 + 16 + 32, usage.month!!, 0.0)
        // The rolling 7 days are today's midnight minus six days: from the 12th.
        assertEquals(2.0 + 4 + 8 + 16 + 32, usage.last7d!!, 0.0)
        // The rolling 30 days start 29 days back: the 17th of February is 29 days before the 18th of March.
        assertEquals(100.0 + 1 + 2 + 4 + 8 + 16 + 32, usage.last30d!!, 0.0)
    }

    @Test
    fun `no days means no energy`() {
        val usage = EnergyText.usageFromDaily(emptyList(), at(2026, 3, 18), utc)
        assertEquals(0.0, usage.today!!, 0.0)
        assertEquals(0.0, usage.last30d!!, 0.0)
    }

    @Test
    fun `the usage tiles follow the width of the drawer and skip a period with no figure`() {
        val usage = EnergyUsage(today = 0.5, week = 3.0, month = 12.0, last7d = 4.0, last30d = 20.0)
        assertEquals(listOf("Today" to "500 Wh", "This week" to "3.00 kWh", "This month" to "12.0 kWh"), EnergyText.tiles(usage, expanded = false))
        assertEquals(listOf("Today" to "500 Wh", "Last 7 days" to "4.00 kWh", "Last 30 days" to "20.0 kWh"), EnergyText.tiles(usage, expanded = true))
        assertEquals(listOf("Today" to "500 Wh"), EnergyText.tiles(EnergyUsage(today = 0.5), expanded = false))
    }

    @Test
    fun `the lifetime reading is written as a number is`() {
        assertEquals("Lifetime 84.2 kWh", EnergyText.lifetime(84.2, "kWh"))
        assertEquals("Lifetime 84 kWh", EnergyText.lifetime(84.0, null))
        assertEquals("Lifetime 1200 Wh", EnergyText.lifetime(1200.0, "Wh"))
    }

    @Test
    fun `the temperature bar maps a finger to the nearest 50 K inside the range`() {
        val range = 2000..6500
        assertEquals(2000, LightBars.kelvinAt(0f, range))
        assertEquals(6500, LightBars.kelvinAt(1f, range))
        assertEquals(4250, LightBars.kelvinAt(0.5f, range))
        assertEquals(2000, LightBars.kelvinAt(-0.3f, range))
        assertEquals(6500, LightBars.kelvinAt(1.7f, range))
        // 2000 + 0.123 * 4500 = 2553.5, which is nearest 2550.
        assertEquals(2550, LightBars.kelvinAt(0.123f, range))
    }

    @Test
    fun `a kelvin goes back to its place on the bar`() {
        val range = 2000..6500
        assertEquals(0f, LightBars.fractionOfKelvin(2000, range), 0f)
        assertEquals(1f, LightBars.fractionOfKelvin(6500, range), 0f)
        assertEquals(0.5f, LightBars.fractionOfKelvin(4250, range), 1e-6f)
        assertEquals(0f, LightBars.fractionOfKelvin(1000, range), 0f)
        assertEquals(0f, LightBars.fractionOfKelvin(3000, 3000..3000), 0f)
    }

    @Test
    fun `the light's kelvin is its own, kept in range, and 2700 while it shows a color`() {
        assertEquals(2000..6500, LightBars.kelvinRange(null))
        assertEquals(2200..5000, LightBars.kelvinRange(light(range = 2200..5000)))
        val range = 2200..5000
        assertEquals(4000, LightBars.kelvinOf(light(LightColor.Temperature(4000)), range))
        assertEquals(5000, LightBars.kelvinOf(light(LightColor.Temperature(6500)), range))
        assertEquals(2700, LightBars.kelvinOf(light(LightColor.Hs(10.0, 50.0)), range))
        assertEquals(2700, LightBars.kelvinOf(null, range))
    }

    @Test
    fun `the hue bar maps a finger to a whole degree`() {
        assertEquals(0, LightBars.hueAt(0f))
        assertEquals(180, LightBars.hueAt(0.5f))
        assertEquals(360, LightBars.hueAt(1f))
        assertEquals(360, LightBars.hueAt(3f))
        assertEquals(0.5f, LightBars.fractionOfHue(180), 0f)
    }

    @Test
    fun `the tracks have the web's stops`() {
        assertEquals(5, LightBars.kelvinStops(2000..6500).size)
        assertEquals(7, LightBars.hueStops.size)
        assertEquals(LightBars.hueStops.first(), LightBars.hueStops.last())
        // Candle light is warmer than daylight: more red than blue at the warm end.
        val stops = LightBars.kelvinStops(2000..6500)
        assertEquals(0xFF, (stops.first() shr 16) and 0xFF)
        assertTrue((stops.last() and 0xFF) > 240 && (stops.first() and 0xFF) < 60)
    }

    @Test
    fun `moving the hue keeps the light's saturation, or takes the full one`() {
        assertEquals(100.0, LightBars.saturationOf(null), 0.0)
        assertEquals(100.0, LightBars.saturationOf(light(LightColor.Hs(0.0, 0.0))), 0.0)
        assertEquals(80.0, LightBars.saturationOf(light(LightColor.Hs(280.0, 80.0))), 1.0)
    }

    @Test
    fun `a step moves the level by its delta and stops at the ends`() {
        assertEquals(51, LightBars.stepped(50, 1))
        assertEquals(60, LightBars.stepped(50, 10))
        assertEquals(100, LightBars.stepped(95, 10))
        assertEquals(0, LightBars.stepped(3, -10))
    }

    @Test
    fun `the power hint says what the light is doing`() {
        assertEquals("On · 60%", LightBars.powerHint(true, 60))
        assertEquals("On", LightBars.powerHint(true, null))
        assertEquals("Off", LightBars.powerHint(false, 60))
    }

    @Test
    fun `a light with no color shows no dot`() {
        assertNull(LightBars.colorOf(light()))
        assertArrayEquals(intArrayOf(0xFF, 0xFF, 0xFF), LightBars.colorOf(light(LightColor.Hs(0.0, 0.0, listOf(255, 255, 255))))!!.let { intArrayOf(it.r, it.g, it.b) })
    }
}

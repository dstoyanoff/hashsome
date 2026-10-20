package dev.hashsome.headless

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryStatusTest {
    @Test
    fun `red at 20 percent and under, warm under 50, fine from 50`() {
        assertEquals(HealthLevel.Down, BatteryStatus(0, false).level)
        assertEquals(HealthLevel.Down, BatteryStatus(20, false).level)
        assertEquals(HealthLevel.Pending, BatteryStatus(21, false).level)
        assertEquals(HealthLevel.Pending, BatteryStatus(49, false).level)
        assertEquals(HealthLevel.Ok, BatteryStatus(50, false).level)
        assertEquals(HealthLevel.Ok, BatteryStatus(100, true).level)
    }

    @Test
    fun `the text is the percentage, the label also says whether it is charging`() {
        assertEquals("42%", BatteryStatus(42, true).text)
        assertEquals("42%", BatteryStatus(42, false).label)
        assertEquals("42% · charging", BatteryStatus(42, true).label)
    }
}

package dev.hashsome.headless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LowBatteryAlertTest {
    private fun on(percent: Int) = BatteryStatus(percent, charging = false)

    @Test
    fun `it is quiet from the threshold up`() {
        val alert = LowBatteryAlert()
        assertNull(alert.next(on(50)))
        assertNull(alert.next(on(10)))
    }

    @Test
    fun `it speaks when the battery falls under the threshold, and for every percent after`() {
        val alert = LowBatteryAlert()
        assertNull(alert.next(on(10)))
        assertEquals(9, alert.next(on(9)))
        assertNull(alert.next(on(9)))
        assertEquals(8, alert.next(on(8)))
        assertEquals(6, alert.next(on(6)))
    }

    @Test
    fun `a first reading already under the threshold is told`() {
        assertEquals(7, LowBatteryAlert().next(on(7)))
    }

    @Test
    fun `charging starts it over`() {
        val alert = LowBatteryAlert()
        assertEquals(8, alert.next(on(8)))
        assertNull(alert.next(BatteryStatus(9, charging = true)))
        assertEquals(9, alert.next(on(9)))
    }

    @Test
    fun `a rise without charging says nothing and is not told again when it falls back`() {
        val alert = LowBatteryAlert()
        assertEquals(5, alert.next(on(5)))
        assertNull(alert.next(on(6)))
        assertNull(alert.next(on(5)))
        assertEquals(4, alert.next(on(4)))
    }

    @Test
    fun `no battery says nothing`() {
        assertNull(LowBatteryAlert().next(null))
    }
}

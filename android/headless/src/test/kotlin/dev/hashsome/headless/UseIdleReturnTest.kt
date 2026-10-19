package dev.hashsome.headless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// layout/__tests__/use-idle-return.test.tsx
class UseIdleReturnTest {
    private val rig = Rig()
    private val min = 60_000L
    private var returned = 0

    private fun idle(after: Long = 5 * min) = IdleReturn(rig.scheduler, after) { returned++ }

    @Test
    fun `the check is whether the time has run out`() {
        assertTrue(shouldLeave(5 * min, 0, 5 * min))
        assertFalse(shouldLeave(5 * min - 1, 0, 5 * min))
    }

    @Test
    fun `on another page, left alone, it goes back once the time is up`() {
        val idle = idle()
        idle.arrivedAt("music", isHome = false)
        rig.scheduler.advance(4 * min + 30_000)
        assertEquals(0, returned)
        rig.scheduler.advance(40_000)
        assertEquals(1, returned)
    }

    @Test
    fun `on the home page it does nothing, however long it is left`() {
        val idle = idle()
        idle.arrivedAt("", isHome = true)
        rig.scheduler.advance(60 * min)
        assertEquals(0, returned)
        assertEquals(0, rig.scheduler.pending)
    }

    @Test
    fun `the time counts from arriving on a page that is not home, and any touch starts it again`() {
        val idle = idle()
        idle.arrivedAt("", isHome = true)
        rig.scheduler.advance(30 * min)
        idle.arrivedAt("music", isHome = false)
        rig.scheduler.advance(4 * min)
        assertEquals(0, returned)
        repeat(4) {
            idle.touched()
            rig.scheduler.advance(4 * min)
            assertEquals(0, returned)
        }

        rig.scheduler.advance(6 * min)
        assertEquals(1, returned)
    }

    @Test
    fun `arriving home ends the countdown`() {
        val idle = idle()
        idle.arrivedAt("music", isHome = false)
        rig.scheduler.advance(4 * min)
        idle.arrivedAt("", isHome = true)
        rig.scheduler.advance(60 * min)
        assertEquals(0, returned)
    }

    @Test
    fun `moving to another page that is not home keeps counting, a touch being what restarts it`() {
        val idle = idle()
        idle.arrivedAt("music", isHome = false)
        rig.scheduler.advance(4 * min)
        idle.arrivedAt("queue", isHome = false)
        rig.scheduler.advance(min + 10_000)
        assertEquals(1, returned)
    }

    @Test
    fun `no time turns it off`() {
        val idle = idle(after = 0)
        idle.arrivedAt("music", isHome = false)
        rig.scheduler.advance(60 * min)
        assertEquals(0, returned)
        assertEquals(0, rig.scheduler.pending)
    }

    @Test
    fun `it checks every 10 seconds at most, and twice in a short time`() {
        idle().arrivedAt("music", isHome = false)
        // 10 minutes away: the check runs every 10 s, so it leaves within 10 s of the time.
        rig.scheduler.advance(5 * min - 1)
        assertEquals(0, returned)
        rig.scheduler.advance(1)
        assertEquals(1, returned)
        assertEquals(10_000L, IdleReturn.CHECK_MS)
    }

    @Test
    fun `a short time is checked at half of it`() {
        val idle = idle(after = 4_000)
        idle.arrivedAt("music", isHome = false)
        rig.scheduler.advance(1_999)
        assertEquals(0, returned)
        rig.scheduler.advance(1)
        assertEquals(0, returned)
        rig.scheduler.advance(2_000)
        assertEquals(1, returned)
    }

    @Test
    fun `stop ends the countdown`() {
        val idle = idle()
        idle.arrivedAt("music", isHome = false)
        idle.stop()
        idle.stop()
        rig.scheduler.advance(60 * min)
        assertEquals(0, returned)
    }

    @Test
    fun `after a stop, arriving on another page starts a new countdown from then`() {
        val idle = idle()
        idle.arrivedAt("music", isHome = false)
        rig.scheduler.advance(3 * min)
        idle.stop()
        rig.scheduler.advance(10 * min)
        idle.arrivedAt("music", isHome = false)
        rig.scheduler.advance(4 * min)
        assertEquals(0, returned)
        rig.scheduler.advance(min)
        assertEquals(1, returned)
    }
}

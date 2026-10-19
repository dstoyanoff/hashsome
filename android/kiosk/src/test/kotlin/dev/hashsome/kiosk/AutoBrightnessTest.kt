package dev.hashsome.kiosk

import dev.hashsome.client.Store
import dev.hashsome.testing.TestScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBrightnessTest {
    private val sched = TestScheduler()
    private val lux = Store<Float?>(null)
    private val min = Store(8)
    private val screen = FakeScreen()
    private val auto = AutoBrightness(lux, screen, min).also { it.scheduler = sched }

    @Test
    fun `the ramp runs from the minimum at 30 lx to 255 at 500 lx`() {
        assertEquals(8, AutoBrightness.brightnessFor(0f, 8))
        assertEquals(8, AutoBrightness.brightnessFor(30f, 8))
        assertEquals(255, AutoBrightness.brightnessFor(500f, 8))
        assertEquals(255, AutoBrightness.brightnessFor(10_000f, 8))
        assertEquals(132, AutoBrightness.brightnessFor(265f, 8))
        assertEquals(40, AutoBrightness.brightnessFor(0f, 40))
    }

    @Test
    fun `the first reading is applied at once`() {
        auto.start()
        lux.set(500f)
        assertEquals(listOf(255), screen.writes)
    }

    @Test
    fun `the X1 flipping between 30 and 60 lx does not move the backlight`() {
        lux.set(30f)
        auto.start()
        assertEquals(listOf(8), screen.writes)
        repeat(20) {
            lux.set(60f)
            sched.advance(2_000)
            lux.set(30f)
            sched.advance(2_000)
        }
        assertEquals(listOf(8), screen.writes)
    }

    @Test
    fun `a change outside the band fades in after it has held for three seconds`() {
        lux.set(30f)
        auto.start()
        lux.set(300f)
        sched.advance(2_900)
        assertEquals(listOf(8), screen.writes)
        sched.advance(100)
        sched.advance(1_000)
        val target = AutoBrightness.brightnessFor(300f, 8)
        assertEquals(target, screen.writes.last())
        assertTrue("fade is animated, not a jump: ${screen.writes}", screen.writes.size > 5)
        assertEquals(screen.writes.sorted(), screen.writes)
    }

    @Test
    fun `a shadow that passes within the settle time changes nothing`() {
        lux.set(300f)
        auto.start()
        val before = screen.writes.toList()
        lux.set(30f)
        sched.advance(2_000)
        lux.set(300f)
        sched.advance(10_000)
        assertEquals(before, screen.writes)
    }

    @Test
    fun `nothing is written while the screen is off and the right level comes with the wake`() {
        lux.set(30f)
        auto.start()
        screen.off()
        lux.set(500f)
        sched.advance(10_000)
        assertEquals(listOf(8), screen.writes)
        screen.wake()
        assertEquals(255, screen.writes.last())
    }

    @Test
    fun `a new minimum applies at once and stop ends it all`() {
        lux.set(0f)
        auto.start()
        min.set(30)
        assertEquals(30, screen.writes.last())
        auto.stop()
        val n = screen.writes.size
        lux.set(500f)
        min.set(60)
        sched.advance(10_000)
        assertEquals(n, screen.writes.size)
    }

    @Test
    fun `the band is wider than the X1 step and grows with the light`() {
        assertTrue(AutoBrightness.bandFor(30f) > 30f)
        assertTrue(AutoBrightness.bandFor(60f) > 30f)
        assertEquals(150f, AutoBrightness.bandFor(1000f), 0.01f)
    }
}

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

    /** 8 % of the range of a screen with floor 0. */
    private val minLevel = 20

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
        assertEquals(listOf(minLevel), screen.writes)
        repeat(20) {
            lux.set(60f)
            sched.advance(2_000)
            lux.set(30f)
            sched.advance(2_000)
        }
        assertEquals(listOf(minLevel), screen.writes)
    }

    @Test
    fun `a change outside the band fades in after it has held for three seconds`() {
        lux.set(30f)
        auto.start()
        lux.set(300f)
        sched.advance(2_900)
        assertEquals(listOf(minLevel), screen.writes)
        sched.advance(100)
        sched.advance(1_000)
        val target = AutoBrightness.brightnessFor(300f, minLevel)
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
        assertEquals(listOf(minLevel), screen.writes)
        screen.wake()
        assertEquals(255, screen.writes.last())
    }

    @Test
    fun `a new minimum applies at once and stop ends it all`() {
        lux.set(0f)
        auto.start()
        min.set(30)
        assertEquals(screen.panelLevel(30), screen.writes.last())
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

    @Test
    fun `the minimum is a percent of the panel's own range`() {
        // A panel whose lowest lit level is 10: 0 % is 10, never off.
        assertEquals(10, panelLevel(0, 10))
        assertEquals(255, panelLevel(100, 10))
        assertEquals(133, panelLevel(50, 10))
        assertEquals(1, panelLevel(0, 1))
        assertEquals(255, panelLevel(100, 1))
        assertEquals(0, panelLevel(0, 0))
        // Out-of-range percents stay in range.
        assertEquals(10, panelLevel(-5, 10))
        assertEquals(255, panelLevel(400, 10))
        assertEquals((0..100).map { panelLevel(it, 10) }.sorted(), (0..100).map { panelLevel(it, 10) })
    }

    @Test
    fun `the lowest level of each profile is lit`() {
        assertEquals(1, DeviceProfile.ShellyStargate.floor)
        assertEquals(10, DeviceProfile.ShellyJenna.floor)
        assertEquals(10, DeviceProfile.Generic.floor)
    }

    @Test
    fun `without a reading the minimum is applied after the grace`() {
        auto.start()
        sched.advance(AutoBrightness.INITIAL_GRACE_MS - 1)
        assertEquals(emptyList<Int>(), screen.writes)
        sched.advance(1)
        assertEquals(listOf(minLevel), screen.writes)
    }

    @Test
    fun `without a reading the last level written is applied after the grace`() {
        val last = Store<Int?>(177)
        AutoBrightness(lux, screen, min, last).also { it.scheduler = sched }.start()
        sched.advance(AutoBrightness.INITIAL_GRACE_MS)
        assertEquals(listOf(177), screen.writes)
    }

    @Test
    fun `a reading inside the grace takes over and the grace writes nothing`() {
        auto.start()
        sched.advance(200)
        lux.set(500f)
        sched.advance(5_000)
        assertEquals(listOf(255), screen.writes)
    }

    @Test
    fun `a reading after the grace takes over from the initial level`() {
        auto.start()
        sched.advance(AutoBrightness.INITIAL_GRACE_MS)
        lux.set(500f)
        assertEquals(listOf(minLevel, 255), screen.writes)
    }

    @Test
    fun `the screen coming on with no lux known applies the initial level`() {
        auto.start()
        sched.advance(1_000)
        screen.off()
        sched.advance(10_000)
        screen.wake()
        assertEquals(listOf(minLevel, minLevel), screen.writes)
    }

    @Test
    fun `what is written is remembered for the next start`() {
        val last = Store<Int?>(null)
        AutoBrightness(lux, screen, min, last).also { it.scheduler = sched }.apply { start() }
        lux.set(500f)
        assertEquals(255, last.value)
    }
}

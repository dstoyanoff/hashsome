package dev.hashsome.kiosk

import dev.hashsome.testing.TestScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NightModeTest {
    private val sched = TestScheduler()
    private val screen = FakeScreen()
    private val presence = fakePresence(sched)

    private fun night(idleMs: Long = 120_000, on: Boolean = false): NightMode {
        freshSettings(KioskConfig(nightIdleMs = idleMs))
        Settings.night.set(on)
        return NightMode(Settings, presence, screen, sched)
    }

    private fun touch() = presence.touches.set(presence.touches.value + 1)

    @Test
    fun `day keeps the screen on however long nobody comes`() {
        val mode = night()
        sched.advance(24 * 3_600_000L)
        assertEquals(NightState.Day, mode.state.value)
        assertEquals(0, screen.offs)
    }

    @Test
    fun `night puts the screen off after the idle time and a touch wakes it`() {
        val mode = night(idleMs = 120_000)
        Settings.night.set(true)
        assertEquals(NightState.NightAwake(sched.now() + 120_000), mode.state.value)
        sched.advance(119_999)
        assertEquals(0, screen.offs)
        sched.advance(1)
        assertEquals(NightState.NightAsleep, mode.state.value)
        assertEquals(1, screen.offs)
        touch()
        assertEquals(1, screen.wakes)
        assertEquals(NightState.NightAwake(sched.now() + 120_000), mode.state.value)
    }

    @Test
    fun `proximity wakes it too, and far does not`() {
        val mode = night(idleMs = 10_000, on = true)
        sched.advance(10_000)
        assertEquals(NightState.NightAsleep, mode.state.value)
        presence.near.set(true)
        assertEquals(1, screen.wakes)
        assertTrue(mode.state.value is NightState.NightAwake)
        sched.advance(10_000)
        assertEquals(NightState.NightAsleep, mode.state.value)
        presence.near.set(false)
        assertEquals(1, screen.wakes)
    }

    @Test
    fun `a touch while awake pushes the sleep back`() {
        val mode = night(idleMs = 10_000, on = true)
        sched.advance(8_000)
        touch()
        sched.advance(8_000)
        assertEquals(0, screen.offs)
        assertEquals(NightState.NightAwake(sched.now() + 2_000), mode.state.value)
        sched.advance(2_000)
        assertEquals(1, screen.offs)
    }

    @Test
    fun `switching night off wakes a sleeping screen and it never goes off again`() {
        val mode = night(idleMs = 10_000, on = true)
        sched.advance(10_000)
        Settings.night.set(false)
        assertEquals(NightState.Day, mode.state.value)
        assertEquals(1, screen.wakes)
        sched.advance(3_600_000)
        assertEquals(1, screen.offs)
    }

    @Test
    fun `the app never flips night on its own`() {
        night(idleMs = 10_000, on = false)
        presence.near.set(true)
        sched.advance(1_000_000)
        assertEquals(false, Settings.night.value)
        Settings.night.set(true)
        sched.advance(1_000_000)
        assertEquals(true, Settings.night.value)
    }

    @Test
    fun `sleep and wake buttons work at night`() {
        val mode = night(idleMs = 60_000, on = true)
        mode.sleep()
        assertEquals(NightState.NightAsleep, mode.state.value)
        assertEquals(1, screen.offs)
        sched.advance(120_000)
        assertEquals(1, screen.offs)
        mode.wake()
        assertEquals(1, screen.wakes)
        assertEquals(NightState.NightAwake(sched.now() + 60_000), mode.state.value)
    }

    @Test
    fun `sleep in day mode blanks the screen until the next touch`() {
        val mode = night()
        mode.sleep()
        assertEquals(1, screen.offs)
        assertEquals(NightState.Day, mode.state.value)
        touch()
        assertEquals(1, screen.wakes)
        // And the touch after that does not wake an awake screen again.
        touch()
        assertEquals(1, screen.wakes)
    }

    @Test
    fun `a changed idle time takes effect on the running timer`() {
        val mode = night(idleMs = 60_000, on = true)
        Settings.nightIdleMs.set(5_000)
        sched.advance(5_000)
        assertEquals(NightState.NightAsleep, mode.state.value)
    }
}

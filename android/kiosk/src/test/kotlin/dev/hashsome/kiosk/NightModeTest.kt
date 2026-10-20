package dev.hashsome.kiosk

import dev.hashsome.testing.TestScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `switching night on puts the screen off at once and a touch wakes it, then the idle time applies`() {
        val mode = night(idleMs = 120_000)
        Settings.night.set(true)
        assertEquals(NightState.NightAsleep, mode.state.value)
        assertEquals(1, screen.offs)
        touch()
        assertEquals(1, screen.wakes)
        assertEquals(NightState.NightAwake(sched.now() + 120_000), mode.state.value)
        sched.advance(119_999)
        assertEquals(1, screen.offs)
        sched.advance(1)
        assertEquals(NightState.NightAsleep, mode.state.value)
        assertEquals(2, screen.offs)
    }

    @Test
    fun `night kept from before a restart waits out the idle time instead of going dark at start`() {
        val mode = night(idleMs = 120_000, on = true)
        assertEquals(NightState.NightAwake(sched.now() + 120_000), mode.state.value)
        assertEquals(0, screen.offs)
        sched.advance(120_000)
        assertEquals(1, screen.offs)
    }

    @Test
    fun `proximity wakes it too, and far does not`() {
        val mode = night(idleMs = 10_000, on = true)
        sched.advance(10_000)
        assertEquals(NightState.NightAsleep, mode.state.value)
        presence.near.set(true)
        assertEquals(1, screen.wakes)
        assertTrue(mode.state.value is NightState.NightAwake)
        presence.near.set(false)
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

    @Test
    fun `a service restart while asleep at night comes up asleep and wakes on the first touch`() {
        freshSettings(KioskConfig(nightIdleMs = 10_000))
        Settings.night.set(true)
        screen.state.set(ScreenState.Off)
        val mode = NightMode(Settings, presence, screen, sched)
        assertEquals(NightState.NightAsleep, mode.state.value)
        sched.advance(60_000)
        assertEquals(0, screen.wakes)
        presence.near.set(true)
        assertEquals(1, screen.wakes)
    }

    @Test
    fun `at night a screen that went off by itself counts as asleep and any touch wakes it`() {
        val mode = night(idleMs = 60_000, on = true)
        assertTrue(mode.state.value is NightState.NightAwake)
        screen.state.set(ScreenState.Off)
        assertEquals(NightState.NightAsleep, mode.state.value)
        touch()
        assertEquals(1, screen.wakes)
        assertTrue(mode.state.value is NightState.NightAwake)
    }

    @Test
    fun `in day mode a screen that goes off by itself is woken at once`() {
        val mode = night()
        screen.state.set(ScreenState.Off)
        assertEquals(1, screen.wakes)
        assertEquals(ScreenState.On, screen.state.value)
        assertEquals(NightState.Day, mode.state.value)
    }

    @Test
    fun `a day sleep followed by night on is asleep at night and a touch wakes it`() {
        val mode = night()
        mode.sleep()
        Settings.night.set(true)
        assertEquals(NightState.NightAsleep, mode.state.value)
        assertEquals(0, screen.wakes)
        touch()
        assertEquals(1, screen.wakes)
    }

    @Test
    fun `a touch wakes a dark panel whatever the state says`() {
        val mode = night(idleMs = 60_000, on = true)
        // Inconsistent on purpose: the screen is dark but the state still says awake.
        screen.off()
        screen.state.set(ScreenState.Off)
        mode.state.set(NightState.NightAwake(0))
        touch()
        assertEquals(ScreenState.On, screen.state.value)
    }

    @Test
    fun `someone standing near is not put to sleep on`() {
        val mode = night(idleMs = 10_000, on = true)
        presence.near.set(true)
        sched.advance(35_000)
        assertEquals(0, screen.offs)
        assertTrue(mode.state.value is NightState.NightAwake)
        presence.near.set(false)
        sched.advance(10_000)
        assertEquals(NightState.NightAsleep, mode.state.value)
        assertEquals(1, screen.offs)
    }

    @Test
    fun `the touch that wakes is told apart from one on an awake screen`() {
        val mode = night(idleMs = 10_000, on = true)
        assertFalse(mode.asleep)
        sched.advance(10_000)
        assertTrue(mode.asleep)
        mode.wake()
        assertFalse(mode.asleep)
        mode.sleep()
        assertTrue(mode.asleep)
    }

    @Test
    fun `a backlight raised by someone else while asleep is a wake`() {
        val mode = night(idleMs = 10_000, on = true)
        sched.advance(10_000)
        assertEquals(NightState.NightAsleep, mode.state.value)
        sched.advance(10_000)
        assertEquals(1, screen.offs)
        assertEquals(0, screen.wakes)
        screen.lit = true
        sched.advance(2_000)
        assertEquals(1, screen.wakes)
        assertEquals(ScreenState.On, screen.state.value)
        assertTrue(mode.state.value is NightState.NightAwake)
        // The idle timer runs again.
        sched.advance(10_000)
        assertEquals(NightState.NightAsleep, mode.state.value)
    }

    @Test
    fun `a dark panel nobody lit stays asleep`() {
        val mode = night(idleMs = 10_000, on = true)
        sched.advance(60_000)
        assertEquals(NightState.NightAsleep, mode.state.value)
        assertEquals(0, screen.wakes)
    }
}

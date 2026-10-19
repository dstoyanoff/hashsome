package dev.hashsome.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BootStartTest {
    @Before
    fun fresh() = BootStart.reset()

    private val locked = "android.intent.action.LOCKED_BOOT_COMPLETED"
    private val booted = "android.intent.action.BOOT_COMPLETED"

    @Test
    fun `both boot broadcasts start the service once the user is unlocked`() {
        assertEquals(BootStart.Decision.Start, BootStart.decide(locked, true))
        assertEquals(BootStart.Decision.Start, BootStart.decide(booted, true))
    }

    @Test
    fun `a locked user waits for the next broadcast`() {
        assertEquals(BootStart.Decision.WaitForUnlock, BootStart.decide(locked, false))
        assertEquals(BootStart.Decision.WaitForUnlock, BootStart.decide(booted, false))
    }

    @Test
    fun `other actions are ignored`() {
        assertEquals(BootStart.Decision.Ignore, BootStart.decide("android.intent.action.MAIN", true))
    }

    @Test
    fun `the later broadcast does not start the service again`() {
        assertTrue(BootStart.claim())
        assertFalse(BootStart.claim())
    }

    @Test
    fun `a waiting broadcast leaves the claim for the next one`() {
        // Locked: decide says wait and nothing is claimed, so the BOOT_COMPLETED start still happens.
        assertEquals(BootStart.Decision.WaitForUnlock, BootStart.decide(locked, false))
        assertFalse(BootStart.claimed)
        assertTrue(BootStart.claim())
    }

    @Test
    fun `a refused start releases the claim so the next broadcast retries`() {
        assertTrue(BootStart.claim())
        BootStart.release()
        assertTrue(BootStart.claim())
    }
}

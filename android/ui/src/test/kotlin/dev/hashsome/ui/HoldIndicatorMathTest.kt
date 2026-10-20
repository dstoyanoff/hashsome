package dev.hashsome.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HoldIndicatorMathTest {
    @Test
    fun `it fades in over 120 ms and stays opaque`() {
        assertEquals(0f, HoldIndicatorMath.fadeIn(0), 0f)
        assertEquals(0.5f, HoldIndicatorMath.fadeIn(60), 0.0001f)
        assertEquals(1f, HoldIndicatorMath.fadeIn(120), 0f)
        assertEquals(1f, HoldIndicatorMath.fadeIn(4_000), 0f)
        assertEquals(0f, HoldIndicatorMath.fadeIn(-5), 0f)
    }

    @Test
    fun `it fades out from where it was and is gone at the end`() {
        assertEquals(0.5f, HoldIndicatorMath.fadeOut(0.5f, 0, 120), 0f)
        assertEquals(0.25f, HoldIndicatorMath.fadeOut(0.5f, 60, 120), 0.0001f)
        assertEquals(0f, HoldIndicatorMath.fadeOut(1f, 120, 120), 0f)
        assertEquals(0f, HoldIndicatorMath.fadeOut(1f, 900, 120), 0f)
        assertEquals(0f, HoldIndicatorMath.fadeOut(1f, 0, 0), 0f)
    }

    @Test
    fun `a cancelled hold is abandoned past the wider margin only`() {
        assertEquals(48, StatusDotHold.MARGIN_DP)
        assertEquals(true, StatusDotHold.withinArea(32f + 48f, 16f, 32f, 32f, 48f))
        assertEquals(false, StatusDotHold.withinArea(32f + 49f, 16f, 32f, 32f, 48f))
    }
}

class StatusDotTargetTest {
    @Test
    fun `a 32 dp dot grows to 56 evenly in the open and to the edge where the edge is near`() {
        assertEquals(listOf(12, 12), StatusDotHold.reach(32, 56, 300, 300).toList())
        // against the right edge with 18 px left: the target reaches the edge, the other side takes the rest
        assertEquals(listOf(12, 18), StatusDotHold.reach(32, 56, 300, 18).toList())
        assertEquals(listOf(0, 12), StatusDotHold.reach(32, 56, 0, 300).toList())
        assertEquals(listOf(0, 0), StatusDotHold.reach(64, 56, 0, 0).toList())
    }

    @Test
    fun `the cancel margin is measured from the grown target`() {
        assertEquals(true, StatusDotHold.withinArea(32f + 18f + 48f, 16f, 32f, 32f, 12f, 12f, 18f, 12f, 48f))
        assertEquals(false, StatusDotHold.withinArea(32f + 18f + 49f, 16f, 32f, 32f, 12f, 12f, 18f, 12f, 48f))
    }
}

class StatusDotReleaseTest {
    @Test
    fun `only a press of at most 400 ms clicks`() {
        assertEquals(StatusDotHold.Release.Tap, StatusDotHold.release(fired = false, gaveUp = false, elapsedMs = 399))
        assertEquals(StatusDotHold.Release.Tap, StatusDotHold.release(fired = false, gaveUp = false, elapsedMs = 400))
        assertEquals(StatusDotHold.Release.Abandoned, StatusDotHold.release(fired = false, gaveUp = false, elapsedMs = 401))
        assertEquals(StatusDotHold.Release.Abandoned, StatusDotHold.release(fired = false, gaveUp = false, elapsedMs = 2_000))
    }

    @Test
    fun `a held or given-up press never clicks, however short`() {
        assertEquals(StatusDotHold.Release.Held, StatusDotHold.release(fired = true, gaveUp = false, elapsedMs = 5_000))
        assertEquals(StatusDotHold.Release.Held, StatusDotHold.release(fired = true, gaveUp = true, elapsedMs = 5_000))
        assertEquals(StatusDotHold.Release.Cancelled, StatusDotHold.release(fired = false, gaveUp = true, elapsedMs = 100))
        assertEquals(StatusDotHold.Release.Cancelled, StatusDotHold.release(fired = false, gaveUp = true, elapsedMs = 3_000))
    }
}

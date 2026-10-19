package dev.hashsome.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusDotHoldTest {
    @Test
    fun `the ring fills linearly over the hold and completes at its end`() {
        assertEquals(0f, StatusDotHold.progress(0, 5_000), 0f)
        assertEquals(0.5f, StatusDotHold.progress(2_500, 5_000), 0.0001f)
        assertEquals(1f, StatusDotHold.progress(9_000, 5_000), 0f)
        assertFalse(StatusDotHold.completed(4_999, 5_000))
        assertTrue(StatusDotHold.completed(5_000, 5_000))
    }

    @Test
    fun `drifting past the slop gives the hold up`() {
        assertTrue(StatusDotHold.withinSlop(3f, 4f, 5f))
        assertFalse(StatusDotHold.withinSlop(4f, 4f, 5f))
        assertTrue(StatusDotHold.withinSlop(0f, 0f, 0f))
    }
}

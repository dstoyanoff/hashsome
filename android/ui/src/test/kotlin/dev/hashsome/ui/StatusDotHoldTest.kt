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
    fun `the area is the dot's bounds expanded by the margin on every side`() {
        // a 32 x 32 view, 24 px margin: corners of the expanded area are inside, a pixel beyond is out
        assertTrue(StatusDotHold.withinArea(16f, 16f, 32f, 32f, 24f))
        assertTrue(StatusDotHold.withinArea(-24f, -24f, 32f, 32f, 24f))
        assertTrue(StatusDotHold.withinArea(56f, 56f, 32f, 32f, 24f))
        assertTrue(StatusDotHold.withinArea(-24f, 56f, 32f, 32f, 24f))
        assertTrue(StatusDotHold.withinArea(56f, -24f, 32f, 32f, 24f))
        assertFalse(StatusDotHold.withinArea(-25f, 16f, 32f, 32f, 24f))
        assertFalse(StatusDotHold.withinArea(57f, 16f, 32f, 32f, 24f))
        assertFalse(StatusDotHold.withinArea(16f, -25f, 32f, 32f, 24f))
        assertFalse(StatusDotHold.withinArea(16f, 57f, 32f, 32f, 24f))
        assertFalse(StatusDotHold.withinArea(57f, 57f, 32f, 32f, 24f))
    }

    @Test
    fun `drift well past the touch slop but inside the area keeps the hold`() {
        // 14 px and 40 px from the centre of a 32 px dot are both inside a 24 px margin
        assertTrue(StatusDotHold.withinArea(16f + 14f, 16f, 32f, 32f, 24f))
        assertTrue(StatusDotHold.withinArea(16f + 40f, 16f - 40f, 32f, 32f, 24f))
    }

    @Test
    fun `lifting after the full time completes the hold, lifting before does not`() {
        assertTrue(StatusDotHold.completed(StatusDotHold.elapsed(15_010, 10_000), 5_000))
        assertFalse(StatusDotHold.completed(StatusDotHold.elapsed(14_990, 10_000), 5_000))
    }

    @Test
    fun `elapsed time is measured from the down and never negative`() {
        assertEquals(1_200L, StatusDotHold.elapsed(11_200, 10_000))
        assertEquals(0L, StatusDotHold.elapsed(9_000, 10_000))
        assertTrue(StatusDotHold.completed(StatusDotHold.elapsed(15_000, 10_000), 5_000))
    }
}

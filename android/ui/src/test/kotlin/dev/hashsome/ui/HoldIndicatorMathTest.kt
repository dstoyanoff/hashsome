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

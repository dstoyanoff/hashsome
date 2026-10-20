package dev.hashsome.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PurePiecesTest {
    @Test
    fun `a grid child that is gone takes no cell`() {
        assertArrayEquals(intArrayOf(0, -1, 1, 2), GridSlots.of(booleanArrayOf(true, false, true, true)))
        assertArrayEquals(intArrayOf(-1, 0), GridSlots.of(booleanArrayOf(false, true)))
        assertArrayEquals(intArrayOf(), GridSlots.of(booleanArrayOf()))
    }

    @Test
    fun `an overlay is removed, and so reported dismissed, once however many things try`() {
        val once = Once()
        var removed = 0
        assertTrue(once.run { removed++ })
        assertFalse(once.run { removed++ })
        assertFalse(once.run { removed++ })
        assertEquals(1, removed)
        // Shown again, it may go again.
        once.reset()
        assertTrue(once.run { removed++ })
        assertEquals(2, removed)
    }

    @Test
    fun `a pulled sheet is let go past 100 dp or faster than half a dp a millisecond`() {
        assertFalse(SheetPull.dismisses(100f, 0.5f))
        assertTrue(SheetPull.dismisses(100.5f, 0f))
        assertTrue(SheetPull.dismisses(10f, 0.6f))
        assertFalse(SheetPull.dismisses(0f, 0f))
        assertFalse(SheetPull.dismisses(33f, 0.1f))
    }

    @Test
    fun `a grower with a min does not shrink below it`() {
        // 100 wanted in 60: 40 too many, but the grower may only give 20 (min 80 of 100).
        assertArrayEquals(intArrayOf(0, 80), FlexMath.distribute(intArrayOf(0, 100), booleanArrayOf(false, true), 20, 0, intArrayOf(0, 80)))
        assertArrayEquals(
            intArrayOf(50, 80),
            FlexMath.distribute(intArrayOf(50, 100), booleanArrayOf(false, true), 130, 0, intArrayOf(0, 80)),
        )
        // The board of a page: its content is its min, so it overflows rather than shrinks.
        assertArrayEquals(
            intArrayOf(30, 100),
            FlexMath.distribute(intArrayOf(30, 100), booleanArrayOf(false, true), 90, 0, intArrayOf(0, 100)),
        )
    }

    @Test
    fun `growers share the shrinking by what each has to give`() {
        // 300 in 240: 60 over; gives of 100 and 200 take 20 and 40.
        assertArrayEquals(
            intArrayOf(80, 160),
            FlexMath.distribute(intArrayOf(100, 200), booleanArrayOf(true, true), 240, 0, intArrayOf(0, 0)),
        )
    }

    @Test
    fun `negative bases (a negative margin) are a basis like any other`() {
        // -12 and 100 and a grower of 0 in 200: the grower takes 200 - 88 = 112.
        assertArrayEquals(
            intArrayOf(-12, 100, 112),
            FlexMath.distribute(intArrayOf(-12, 100, 0), booleanArrayOf(false, false, true), 200, 0),
        )
        // A negative grower never gives anything.
        assertArrayEquals(
            intArrayOf(-5, 40),
            FlexMath.distribute(intArrayOf(-5, 60), booleanArrayOf(true, true), 35, 0, intArrayOf(0, 0)),
        )
    }
}

class OverlayTapTest {
    @org.junit.Test
    fun `a tap on a button, or a fingertip's width off it, counts`() {
        org.junit.Assert.assertTrue(OverlayTap.counts(15f, 15f, 45f, 45f, 12f))
        org.junit.Assert.assertTrue(OverlayTap.counts(-10f, 50f, 45f, 45f, 12f))
        org.junit.Assert.assertFalse(OverlayTap.counts(-13f, 20f, 45f, 45f, 12f))
        org.junit.Assert.assertFalse(OverlayTap.counts(20f, 58f, 45f, 45f, 12f))
    }
}

class TouchDebugTest {
    @org.junit.Test
    fun `a gesture is called by what took it`() {
        org.junit.Assert.assertEquals("tap", TouchDebug.classify(2f, tapped = true, pulled = false, downOnScroll = false))
        org.junit.Assert.assertEquals("pull", TouchDebug.classify(80f, tapped = false, pulled = true, downOnScroll = false))
        org.junit.Assert.assertEquals("scroll", TouchDebug.classify(30f, tapped = false, pulled = false, downOnScroll = true))
        org.junit.Assert.assertEquals("ignored", TouchDebug.classify(3f, tapped = false, pulled = false, downOnScroll = true))
        org.junit.Assert.assertEquals("ignored", TouchDebug.classify(40f, tapped = false, pulled = false, downOnScroll = false))
    }

    @org.junit.Test
    fun `a number is written to a tenth`() {
        org.junit.Assert.assertEquals("12.3", TouchDebug.fmt(12.34f))
    }
}

class HitZoneTest {
    @org.junit.Test
    fun `a button's touch zone is a 48 dp square on its centre`() {
        // A 30 dp button centred at (100, 100), in dp.
        org.junit.Assert.assertTrue(HitZone.contains(100f, 100f, 48f, 123f, 77f))
        // 6.7 dp to the right of a 30 dp square is inside the 9 dp the zone adds on each side.
        org.junit.Assert.assertTrue(HitZone.contains(100f, 100f, 48f, 100f + 15f + 6.7f, 100f))
        org.junit.Assert.assertFalse(HitZone.contains(100f, 100f, 48f, 125f, 100f))
        org.junit.Assert.assertFalse(HitZone.contains(100f, 100f, 48f, 100f, 75f))
    }

    @org.junit.Test
    fun `the zone and the tap slop agree on how far past the 30 dp square a press may be`() {
        org.junit.Assert.assertEquals((HitZone.SIZE_DP - 30f) / 2, OverlayTap.SLOP_DP, 0f)
    }

    @org.junit.Test
    fun `a pull starts only after the slop, up or down`() {
        org.junit.Assert.assertFalse(PullStart.starts(0f, 24f))
        org.junit.Assert.assertFalse(PullStart.starts(24f, 24f))
        org.junit.Assert.assertTrue(PullStart.starts(25f, 24f))
        org.junit.Assert.assertTrue(PullStart.starts(-30f, 24f))
    }
}

package dev.hashsome.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GridTest {
    @Test
    fun `comfortable density has a 4 dp module and 48 dp tiles`() {
        val m = gridMetrics(12)
        assertEquals(4f, m.module, 0.0001f)
        assertEquals(12f, m.gap, 0.0001f)
        assertEquals(48f, m.tile, 0.0001f)
        assertEquals(60f, m.tilePitch, 0.0001f)
        assertEquals(32f, m.header, 0.0001f)
        assertEquals(44f, m.headerPitch, 0.0001f)
    }

    @Test
    fun `compact density has a module of two and two thirds`() {
        val m = gridMetrics(8)
        assertEquals(8f / 3f, m.module, 0.0001f)
        assertEquals(32f + 4 * 8f / 3f, m.tile, 0.0001f)
        assertEquals(m.tile + 8f, m.tilePitch, 0.0001f)
    }

    @Test
    fun `module follows an overridden space`() {
        assertEquals(5f, gridMetrics(15).module, 0.0001f)
    }

    @Test
    fun `the leftover under one module is shared between top and bottom, the odd one to the bottom`() {
        assertEquals(Centering(0, 0), centeringOffsets(456f, 4f))
        // 462 = 115 modules of 4 and 2 over: one each.
        assertEquals(Centering(1, 1), centeringOffsets(462f, 4f))
        // 3 over: one on top, two under.
        assertEquals(Centering(1, 2), centeringOffsets(463f, 4f))
        assertEquals(Centering(0, 0), centeringOffsets(-5f, 4f))
        assertEquals(Centering(0, 0), centeringOffsets(0f, 4f))
    }

    @Test
    fun `a box on the module grid is not off it`() {
        assertFalse(offGrid(12f, 48f, 4f))
        assertFalse(offGrid(12.4f, 47.7f, 4f))
        assertTrue(offGrid(14f, 48f, 4f))
        assertTrue(offGrid(12f, 50f, 4f))
    }
}

package dev.hashsome.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FlowMathTest {
    private fun starts(widths: IntArray, available: Int, gap: Int) = FlowMath.lineStarts(widths, available, gap).toList()

    @Test
    fun `children share a line while they fit with the gaps and wrap when the next does not`() {
        // 100 + 8 + 100 + 8 + 100 = 316 fits in 316, a fourth does not
        assertEquals(listOf(0), starts(intArrayOf(100, 100, 100), 316, 8))
        assertEquals(listOf(0, 2), starts(intArrayOf(100, 100, 100), 315, 8))
        assertEquals(listOf(0, 3), starts(intArrayOf(100, 100, 100, 100), 316, 8))
    }

    @Test
    fun `a child wider than the line has a line of its own and none means no lines`() {
        assertEquals(listOf(0, 1, 2), starts(intArrayOf(50, 500, 50), 200, 8))
        assertEquals(emptyList<Int>(), starts(IntArray(0), 200, 8))
    }

    @Test
    fun `wide and narrow children fill lines in order without reordering`() {
        assertEquals(listOf(0, 2), starts(intArrayOf(150, 40, 120, 60), 200, 10))
    }
}

package dev.hashsome.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class FlexMathTest {
    private fun distribute(basis: IntArray, grow: BooleanArray, available: Int, gap: Int = 0) =
        FlexMath.distribute(basis, grow, available, gap)

    @Test
    fun `without growers the sizes are the basis`() {
        assertArrayEquals(intArrayOf(10, 20), distribute(intArrayOf(10, 20), booleanArrayOf(false, false), 100))
    }

    @Test
    fun `one grower takes what is left after the others and the gaps`() {
        // 100 - 10 - 20 - two gaps of 4 = 62.
        assertArrayEquals(intArrayOf(10, 62, 20), distribute(intArrayOf(10, 0, 20), booleanArrayOf(false, true, false), 100, gap = 4))
    }

    @Test
    fun `growers share equally and the remainder goes to the first ones`() {
        assertArrayEquals(intArrayOf(34, 33, 33), distribute(intArrayOf(0, 0, 0), booleanArrayOf(true, true, true), 100))
        assertArrayEquals(intArrayOf(34, 33), distribute(intArrayOf(0, 0), booleanArrayOf(true, true), 67))
    }

    @Test
    fun `a grower keeps its own size and gets a share on top`() {
        // 100 - 10 - 20 = 70 to share by two: 35 each on top of their own.
        assertArrayEquals(intArrayOf(45, 55), distribute(intArrayOf(10, 20), booleanArrayOf(true, true), 100))
    }

    @Test
    fun `when the children do not fit only the growers give way, in proportion`() {
        // 250 wanted in 150: 100 too many, 50 off each of the two growers.
        assertArrayEquals(intArrayOf(50, 50, 50), distribute(intArrayOf(50, 100, 100), booleanArrayOf(false, true, true), 150))
        assertArrayEquals(intArrayOf(50, 75, 25), distribute(intArrayOf(50, 150, 50), booleanArrayOf(false, true, true), 150))
    }

    @Test
    fun `a grower never goes below nothing`() {
        assertArrayEquals(intArrayOf(80, 0), distribute(intArrayOf(80, 10), booleanArrayOf(false, true), 50))
    }

    @Test
    fun `no children`() {
        assertArrayEquals(intArrayOf(), distribute(intArrayOf(), booleanArrayOf(), 100))
    }
}

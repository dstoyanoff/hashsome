package dev.hashsome.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
    fun `a gap is left only between the children that are shown`() {
        // Four children, one of them gone (basis 0): two gaps of 4, not three.
        assertArrayEquals(
            intArrayOf(10, 62, 0, 20),
            FlexMath.distribute(intArrayOf(10, 0, 0, 20), booleanArrayOf(false, true, false, false), 100, 4, gaps = 2),
        )
    }

    @Test
    fun `no children`() {
        assertArrayEquals(intArrayOf(), distribute(intArrayOf(), booleanArrayOf(), 100))
    }

    private fun weighted(basis: IntArray, grow: BooleanArray, weight: FloatArray, available: Int, gap: Int = 0, min: IntArray = IntArray(basis.size)) =
        FlexMath.distribute(basis, grow, available, gap, min, weight)

    @Test
    fun `two weighted children split what there is by their weights, whatever their content`() {
        // The web's flex 1 and 1.4: 120 splits 50 : 70. Basis is 0 for both.
        assertArrayEquals(intArrayOf(50, 70), weighted(intArrayOf(0, 0), booleanArrayOf(true, true), floatArrayOf(1f, 1.4f), 120))
        // A gap comes off first: 124 - 4 = 120.
        assertArrayEquals(intArrayOf(50, 70), weighted(intArrayOf(0, 0), booleanArrayOf(true, true), floatArrayOf(1f, 1.4f), 124, gap = 4))
    }

    @Test
    fun `a weighted split rounds to whole pixels that add up`() {
        val sizes = weighted(intArrayOf(0, 0), booleanArrayOf(true, true), floatArrayOf(1f, 1.4f), 101)
        assertEquals(101, sizes.sum())
        // 42.08 and 58.9 floor to 42 and 58; the pixel left goes to the first.
        assertArrayEquals(intArrayOf(43, 58), sizes)
    }

    @Test
    fun `a child held at its minimum leaves the rest to the others`() {
        // 100 to split 1 : 1 would give 50 each, but the first must be 80: the other gets 20.
        assertArrayEquals(
            intArrayOf(80, 20),
            weighted(intArrayOf(0, 0), booleanArrayOf(true, true), floatArrayOf(1f, 1f), 100, min = intArrayOf(80, 0)),
        )
    }

    @Test
    fun `weighted and content-sized children share what the sized ones leave`() {
        // 10 and 20 of content, 100 available: 70 left, shared 1 : 2 on top of what each has (23.3 and 46.7, the odd pixel first).
        assertArrayEquals(
            intArrayOf(10, 24, 66),
            weighted(intArrayOf(10, 0, 20), booleanArrayOf(false, true, true), floatArrayOf(0f, 1f, 2f), 100),
        )
        // A non-growing child is never touched.
        assertArrayEquals(intArrayOf(10, 90), weighted(intArrayOf(10, 0), booleanArrayOf(false, true), floatArrayOf(0f, 3f), 100))
    }
}

package dev.hashsome.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BoardPlacerTest {
    private fun rows(n: Int) = RowSpan.Rows(n)

    @Test
    fun `cells that fit side by side share a row`() {
        val p = BoardPlacer.place(12, listOf(7 to rows(1), 5 to rows(1)))
        assertEquals(listOf(Placement(0, 0, 7, 1), Placement(7, 0, 5, 1)), p)
    }

    @Test
    fun `a cell that does not fit goes to the next row`() {
        val p = BoardPlacer.place(12, listOf(8 to rows(1), 5 to rows(1)))
        assertEquals(Placement(0, 1, 5, 1), p[1])
    }

    @Test
    fun `a player beside three rooms covers three rows and the rooms stack in the rest`() {
        val p = BoardPlacer.place(12, listOf(7 to rows(1), 5 to rows(3), 7 to rows(1), 7 to rows(1)))
        assertEquals(
            listOf(Placement(0, 0, 7, 1), Placement(7, 0, 5, 3), Placement(0, 1, 7, 1), Placement(0, 2, 7, 1)),
            p,
        )
    }

    @Test
    fun `a cell that fills covers one row for placement`() {
        val p = BoardPlacer.place(12, listOf(7 to rows(1), 5 to RowSpan.Fill))
        assertEquals(Placement(7, 0, 5, 1), p[1])
    }

    @Test
    fun `a later cell never goes back to a gap an earlier one left`() {
        // CSS sparse auto-placement: the 4 would fit at the end of the first row, but the 5 before it went to the second.
        val p = BoardPlacer.place(12, listOf(8 to rows(1), 5 to rows(1), 4 to rows(1)))
        assertEquals(Placement(0, 0, 8, 1), p[0])
        assertEquals(Placement(0, 1, 5, 1), p[1])
        assertEquals(Placement(5, 1, 4, 1), p[2])
    }

    @Test
    fun `a cell wider than the board is as wide as the board and one with no size is one by one`() {
        val p = BoardPlacer.place(12, listOf(20 to rows(1), 0 to rows(0)))
        assertEquals(Placement(0, 0, 12, 1), p[0])
        assertEquals(Placement(0, 1, 1, 1), p[1])
    }

    @Test
    fun `a tall cell pushes a later wide one below it`() {
        val p = BoardPlacer.place(12, listOf(6 to rows(2), 12 to rows(1)))
        assertEquals(Placement(0, 2, 12, 1), p[1])
    }

    @Test
    fun `no cells place nothing`() {
        assertEquals(emptyList<Placement>(), BoardPlacer.place(12, emptyList()))
    }
}

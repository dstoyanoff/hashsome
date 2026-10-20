package dev.hashsome.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormMathTest {
    @Test
    fun `a row is as tall as the round button and a little air`() {
        assertEquals(52, FormMath.rowHeightDp(Density.Comfortable.tokens(null)))
        assertEquals(44, FormMath.rowHeightDp(Density.Compact.tokens(null)))
    }

    @Test
    fun `a slider maps a fraction to a whole value inside its range`() {
        assertEquals(0, FormMath.valueAt(0f, 0, 100))
        assertEquals(50, FormMath.valueAt(0.5f, 0, 100))
        assertEquals(100, FormMath.valueAt(1f, 0, 100))
        assertEquals(100, FormMath.valueAt(1.7f, 0, 100))
        assertEquals(10, FormMath.valueAt(-1f, 10, 20))
        assertEquals(15, FormMath.valueAt(0.5f, 10, 20))
    }

    @Test
    fun `a value maps back to where it sits in its range`() {
        assertEquals(0.25f, FormMath.fractionOf(25, 0, 100), 0.0001f)
        assertEquals(0.5f, FormMath.fractionOf(15, 10, 20), 0.0001f)
        assertEquals(0f, FormMath.fractionOf(5, 10, 20), 0f)
        assertEquals(1f, FormMath.fractionOf(30, 10, 20), 0f)
        assertEquals(0f, FormMath.fractionOf(3, 7, 7), 0f)
    }

    @Test
    fun `a dropdown list opens below its anchor, above when there is no room, else pinned to the screen`() {
        assertEquals(106, FormMath.listTop(anchorTop = 60, anchorBottom = 100, height = 200, screenHeight = 480, gap = 6))
        assertEquals(194, FormMath.listTop(anchorTop = 400, anchorBottom = 440, height = 200, screenHeight = 480, gap = 6))
        assertEquals(10, FormMath.listTop(anchorTop = 100, anchorBottom = 140, height = 470, screenHeight = 480, gap = 6))
    }

    @Test
    fun `a dropdown list lines its right edge up with the anchor and stays on the screen`() {
        assertEquals(300, FormMath.listLeft(anchorRight = 500, width = 200, screenWidth = 960))
        assertEquals(0, FormMath.listLeft(anchorRight = 120, width = 200, screenWidth = 960))
        assertEquals(760, FormMath.listLeft(anchorRight = 1100, width = 200, screenWidth = 960))
    }
}

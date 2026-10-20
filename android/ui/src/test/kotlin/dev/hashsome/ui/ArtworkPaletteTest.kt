package dev.hashsome.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkPaletteTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun hue(color: Int) = ArtworkPalette.hsvOf(color shr 16 and 0xFF, color shr 8 and 0xFF, color and 0xFF)[0]
    private fun close(a: Float, b: Float, within: Float = 20f): Boolean = Math.min(Math.abs(a - b), 360f - Math.abs(a - b)) <= within

    @Test
    fun `a picture of one color gives three colors of its hue`() {
        val palette = ArtworkPalette.from(IntArray(100) { rgb(200, 40, 40) })
        assertEquals(3, palette.size)
        assertTrue(palette.all { close(hue(it), 0f, 35f) })
    }

    @Test
    fun `two colors both appear, the more present first`() {
        val pixels = IntArray(100) { if (it < 70) rgb(30, 90, 220) else rgb(230, 60, 160) }
        val palette = ArtworkPalette.from(pixels)
        assertTrue(close(hue(palette[0]), 220f, 12f))
        assertTrue(palette.any { close(hue(it), 325f, 15f) })
    }

    @Test
    fun `colors are bright enough to show and dark enough for white text`() {
        for (c in ArtworkPalette.from(IntArray(100) { rgb(255, 255, 0) })) {
            val v = ArtworkPalette.hsvOf(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF)[2]
            assertTrue("value $v", v in 0.28f..0.8f)
        }
    }

    @Test
    fun `a grey picture gives three greys`() {
        val palette = ArtworkPalette.from(IntArray(100) { rgb(120, 120, 120) })
        assertEquals(3, palette.size)
        assertTrue(palette.all { (it shr 16 and 0xFF) == (it and 0xFF) })
    }

    @Test
    fun `transparent pixels are ignored`() {
        val pixels = IntArray(100) { if (it < 90) 0x00FF0000 else rgb(20, 200, 60) }
        assertTrue(close(hue(ArtworkPalette.from(pixels)[0]), 135f, 15f))
    }

    @Test
    fun `hsv and back keeps the color`() {
        val c = rgb(200, 100, 50)
        val hsv = ArtworkPalette.hsvOf(200, 100, 50)
        assertEquals(c, ArtworkPalette.rgbOf(hsv[0], hsv[1], hsv[2]))
    }
}

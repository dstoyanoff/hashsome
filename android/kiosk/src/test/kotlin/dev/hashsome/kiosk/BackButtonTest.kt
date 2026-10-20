package dev.hashsome.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackButtonMathTest {
    @Test
    fun `clamp keeps the window on the screen`() {
        assertEquals(0, BackButtonMath.clamp(-40, 500))
        assertEquals(500, BackButtonMath.clamp(900, 500))
        assertEquals(120, BackButtonMath.clamp(120, 500))
        assertEquals(0, BackButtonMath.clamp(50, -10))
    }

    @Test
    fun `a small wobble is a tap and a longer move is a drag`() {
        assertFalse(BackButtonMath.isDrag(0f, 0f, 8f))
        assertFalse(BackButtonMath.isDrag(5f, 5f, 8f))
        assertFalse(BackButtonMath.isDrag(8f, 0f, 8f))
        assertTrue(BackButtonMath.isDrag(0f, -9f, 8f))
        assertTrue(BackButtonMath.isDrag(6f, 6f, 8f))
    }

    @Test
    fun `fractions and pixels convert both ways`() {
        assertEquals(250, BackButtonMath.toPx(0.5f, 500))
        assertEquals(500, BackButtonMath.toPx(2f, 500))
        assertEquals(0, BackButtonMath.toPx(-1f, 500))
        assertEquals(0, BackButtonMath.toPx(0.5f, 0))
        assertEquals(0.25f, BackButtonMath.toFraction(125, 500), 0.0001f)
        assertEquals(1f, BackButtonMath.toFraction(900, 500), 0f)
        assertEquals(0f, BackButtonMath.toFraction(10, 0), 0f)
    }

    @Test
    fun `the default sits in the bottom-right corner, inset`() {
        val room = 700
        val f = BackButtonMath.defaultFraction(room, 24)
        assertEquals(room - 24, BackButtonMath.toPx(f, room))
        assertEquals(0f, BackButtonMath.defaultFraction(0, 24), 0f)
    }

    @Test
    fun `a position kept as a fraction lands in the same relative place after a rotation`() {
        val f = BackButtonMath.toFraction(300, 600)
        assertEquals(500, BackButtonMath.toPx(f, 1000))
    }
}

class BackButtonSettingsTest {
    @Test
    fun `the position persists and is empty at first`() {
        val prefs = MemoryPrefs()
        Settings.load(prefs, KioskConfig()) { true }
        assertNull(Settings.backButtonX.value)
        Settings.backButtonX.set(0.25f)
        Settings.backButtonY.set(0.75f)
        Settings.load(prefs, KioskConfig()) { true }
        assertEquals(0.25f, Settings.backButtonX.value!!, 0f)
        assertEquals(0.75f, Settings.backButtonY.value!!, 0f)
    }
}

class FrontPauseButtonTest {
    private var now = 0L
    private var changes = 0
    private val pause = FrontPause({ now }).also { it.onChange = { changes++ } }

    @Test
    fun `with the button the pause outlasts the old timeout and ends on end`() {
        pause.start(7_200_000, withButton = true)
        now = 3_600_000
        assertTrue(pause.active(false))
        assertTrue(pause.withButton)
        pause.end("tap")
        assertFalse(pause.isOn)
        assertFalse(pause.withButton)
        assertFalse(pause.active(false))
        assertEquals(2, changes)
    }

    @Test
    fun `the safety cap ends it`() {
        pause.start(7_200_000, withButton = true)
        now = 7_200_000
        assertFalse(pause.active(false))
        assertEquals(2, changes)
    }

    @Test
    fun `ending twice is heard once`() {
        pause.start(1_000)
        pause.end("a")
        pause.end("b")
        assertEquals(2, changes)
    }
}

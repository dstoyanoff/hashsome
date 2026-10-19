package dev.hashsome.kiosk

import dev.hashsome.headless.ThemeSchedule
import dev.hashsome.ui.AppExtension
import dev.hashsome.ui.HashsomeApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WithKioskTest {
    @Test
    fun `withKiosk appends a kiosk extension and keeps the others`() {
        val other = AppExtension { _, _ -> }
        val app = HashsomeApp(theme = ThemeSchedule.Fixed(dark = true), extensions = listOf(other))
        val config = KioskConfig(nightIdleMs = 5_000)
        val kiosk = app.withKiosk(config).extensions
        assertEquals(2, kiosk.size)
        assertTrue(kiosk[0] === other)
        assertEquals(config, (kiosk[1] as KioskExtension).config)
        // The original is not changed.
        assertEquals(1, app.extensions.size)
    }
}

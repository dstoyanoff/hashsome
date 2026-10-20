package dev.hashsome.ui

import android.app.Application
import dev.hashsome.headless.ThemeSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HashsomeInstallTest {
    @Test
    fun `extensions run after install, in order, and see the installed app`() {
        val app = Application()
        val order = ArrayList<String>()
        val config = HashsomeApp(
            theme = ThemeSchedule.Fixed(dark = true),
            extensions = listOf(
                AppExtension { a, h ->
                    order += "first"
                    assertSame(app, a)
                    assertSame(Hashsome, h)
                    // The config is already installed when an extension runs.
                    order += "sees:" + h.app.dashboards.size
                },
                AppExtension { _, _ -> order += "second" },
            ),
        )
        Hashsome.install(app, config)
        assertEquals(listOf("first", "sees:0", "second"), order)
    }

    @Test
    fun `an extension may replace the settings and the plug points`() {
        val replaced = InMemoryHostSettings("http://h:1")
        val touch = object : TouchListener {}
        Hashsome.install(
            Application(),
            HashsomeApp(
                theme = ThemeSchedule.Fixed(dark = false),
                extensions = listOf(AppExtension { _, h -> h.settings = replaced; h.touchListener = touch }),
            ),
        )
        assertSame(replaced, Hashsome.settings)
        assertSame(touch, Hashsome.touchListener)
    }
}

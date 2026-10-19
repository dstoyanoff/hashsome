package dev.hashsome.kiosk

import dev.hashsome.ui.ThemeChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test
    fun `first load writes the config defaults and they persist`() {
        val prefs = freshSettings(KioskConfig(nightIdleMs = 5_000, autoBrightness = false, minBrightness = 20))
        assertEquals(5_000L, Settings.nightIdleMs.value)
        assertFalse(Settings.autoBrightness.value)
        assertEquals(20, Settings.minBrightness.value)
        assertEquals("5000", prefs.map["nightIdleMs"])
    }

    @Test
    fun `a stored value wins over a later config default`() {
        val prefs = freshSettings(KioskConfig(minBrightness = 20))
        Settings.minBrightness.set(33)
        assertEquals("33", prefs.map["minBrightness"])
        // The app starts again with another default in its KioskConfig: the device keeps what it was told.
        Settings.load(prefs, KioskConfig(minBrightness = 50)) { true }
        assertEquals(33, Settings.minBrightness.value)
    }

    @Test
    fun `every key round-trips through the prefs`() {
        val prefs = freshSettings()
        Settings.server.set("http://10.1.1.5:3000")
        Settings.deviceId.set("bathroom")
        Settings.dashboard.set("bathroom")
        Settings.theme.set(ThemeChoice.Dark)
        Settings.night.set(true)
        Settings.mqtt.enabled.set(true)
        Settings.mqtt.host.set("10.4.0.5")
        Settings.mqtt.port.set(1884)
        Settings.mqtt.base.set("walls")
        Settings.debug.grid.set(true)
        Settings.load(prefs, KioskConfig()) { true }
        assertEquals("http://10.1.1.5:3000", Settings.server.value)
        assertEquals("bathroom", Settings.deviceId.value)
        assertEquals("bathroom", Settings.dashboard.value)
        assertEquals(ThemeChoice.Dark, Settings.theme.value)
        assertTrue(Settings.night.value)
        assertTrue(Settings.mqtt.enabled.value)
        assertEquals("10.4.0.5", Settings.mqtt.host.value)
        assertEquals(1884, Settings.mqtt.port.value)
        assertEquals("walls", Settings.mqtt.base.value)
        assertTrue(Settings.debug.grid.value)
    }

    @Test
    fun `clearing a nullable key removes it`() {
        val prefs = freshSettings()
        Settings.dashboard.set("kitchen")
        assertEquals("kitchen", prefs.map["dashboard"])
        Settings.dashboard.set(null)
        assertFalse(prefs.contains("dashboard"))
        Settings.load(prefs, KioskConfig()) { true }
        assertNull(Settings.dashboard.value)
    }

    @Test
    fun `the switcher defaults on for generic and off for the Shelly profiles`() {
        freshSettings(generic = true)
        assertTrue(Settings.switcherEnabled.value)
        freshSettings(generic = false)
        assertFalse(Settings.switcherEnabled.value)
    }

    @Test
    fun `an unreadable stored value falls back to the default`() {
        val prefs = MemoryPrefs()
        prefs.putString("brightness", "bright")
        prefs.putString("theme", "sepia")
        Settings.load(prefs, KioskConfig()) { true }
        assertEquals(255, Settings.brightness.value)
        assertEquals(ThemeChoice.Auto, Settings.theme.value)
    }
}

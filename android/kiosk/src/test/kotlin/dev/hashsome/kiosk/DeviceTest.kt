package dev.hashsome.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTest {
    @Test
    fun `the displays are recognised by their build strings`() {
        // As read from the units on 2026-10-19.
        assertSame(DeviceProfile.ShellyStargate, DeviceProfile.detect("K400_MT6580_32_N", "Stargate", "unknown"))
        assertSame(DeviceProfile.ShellyJenna, DeviceProfile.detect("Jenna", "Jenna", "rk30sdk"))
        assertSame(DeviceProfile.ShellyJenna, DeviceProfile.detect("other", "other", "rk3326"))
        assertSame(DeviceProfile.Generic, DeviceProfile.detect("kane", "Lenovo TB-J606F", "kane"))
        assertSame(DeviceProfile.Generic, DeviceProfile.detect(null, null, null))
    }

    @Test
    fun `a profile override wins`() {
        freshSettings()
        Settings.profileOverride.set("shelly-jenna")
        assertSame(DeviceProfile.ShellyJenna, DeviceProfile.detect())
        Settings.profileOverride.set("no-such-profile")
        assertSame(DeviceProfile.Generic, DeviceProfile.detect())
    }

    @Test
    fun `ids resolve back to profiles`() {
        listOf(DeviceProfile.ShellyStargate, DeviceProfile.ShellyJenna, DeviceProfile.Generic).forEach { assertSame(it, DeviceProfile.byId(it.id)) }
        assertNull(DeviceProfile.byId("nope"))
    }

    @Test
    fun `proximity polarity is opposite on the two displays`() {
        // X1: 0 is near, 1 is far. X2i: 1 is near, 0 is far.
        assertTrue(Presence.isNear(0f, 1f, invert = false))
        assertFalse(Presence.isNear(1f, 1f, invert = false))
        assertTrue(Presence.isNear(1f, 1f, invert = true))
        assertFalse(Presence.isNear(0f, 1f, invert = true))
    }

    @Test
    fun `the X1 drops everything below error so it is logged there`() {
        assertTrue(Log.dropsLowLevels("K400_MT6580_32_N"))
        assertTrue(Log.dropsLowLevels("K400_MT6580"))
        assertFalse(Log.dropsLowLevels("Jenna"))
        assertFalse(Log.dropsLowLevels(null))
    }

    @Test
    fun `five taps within two seconds open the sheet, and the count starts over`() {
        val taps = ArrayDeque<Long>()
        assertFalse((0L..3L).any { KioskTouchListener.fiveTaps(taps, it * 400) })
        assertTrue(KioskTouchListener.fiveTaps(taps, 1_600))
        assertFalse(KioskTouchListener.fiveTaps(taps, 1_700))
        // Five taps spread over three seconds do not.
        val slow = ArrayDeque<Long>()
        assertFalse((0L..4L).any { KioskTouchListener.fiveTaps(slow, it * 750) })
    }

    @Test
    fun `provisioning writes the extras that are present`() {
        freshSettings()
        Settings.dashboard.set("old")
        Provisioning.apply(Settings, mapOf("server" to "http://10.1.1.5:3000", "device" to "bathroom", "mqtt" to "10.4.0.5:1884", "profile" to "shelly-jenna", "dashboard" to null))
        assertEquals("http://10.1.1.5:3000", Settings.server.value)
        assertEquals("bathroom", Settings.deviceId.value)
        assertEquals("10.4.0.5", Settings.mqtt.host.value)
        assertEquals(1884, Settings.mqtt.port.value)
        assertTrue(Settings.mqtt.enabled.value)
        assertEquals("shelly-jenna", Settings.profileOverride.value)
        // An absent extra leaves the setting alone.
        assertEquals("old", Settings.dashboard.value)
        Provisioning.apply(Settings, mapOf("mqtt" to "broker.lan", "profile" to "auto"))
        assertEquals("broker.lan", Settings.mqtt.host.value)
        assertEquals(1883, Settings.mqtt.port.value)
        assertNull(Settings.profileOverride.value)
        Provisioning.apply(Settings, mapOf("mqtt" to "off"))
        assertFalse(Settings.mqtt.enabled.value)
    }

    @Test
    fun `the last broadcast can be encoded and applied again`() {
        val extras = mapOf<String, String?>("server" to "http://a:1", "device" to null, "dashboard" to "kitchen")
        assertEquals(mapOf("server" to "http://a:1", "dashboard" to "kitchen"), Provisioning.decode(Provisioning.encode(extras)))
        assertTrue(Provisioning.decode(null).isEmpty())
    }
}

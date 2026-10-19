package dev.hashsome.kiosk

import org.junit.Assert.assertEquals
import org.junit.Test

class RebuilderTest {
    private var builds = 0
    private var teardowns = 0
    private val seen = ArrayList<String?>()

    private fun rebuilder() = Rebuilder(Settings) {
        builds++
        // What the real build reads: the profile it is building for.
        seen += Settings.profileOverride.value
        ({ teardowns++ })
    }

    @Test
    fun `a new profile tears the parts down and builds them again at once`() {
        val prefs = freshSettings()
        val rebuilder = rebuilder()
        assertEquals(1, builds)
        Settings.profileOverride.set("shelly-jenna")
        assertEquals(2, builds)
        assertEquals(1, teardowns)
        assertEquals(listOf(null, "shelly-jenna"), seen)
        // The same value again changes nothing; clearing it is a change too.
        Settings.profileOverride.set("shelly-jenna")
        assertEquals(2, builds)
        Settings.profileOverride.set(null)
        assertEquals(3, builds)
        // It also persisted, so the next start builds for it.
        Settings.profileOverride.set("generic")
        assertEquals("generic", prefs.map["profileOverride"])
        rebuilder.close()
    }

    @Test
    fun `provisioning a profile rebuilds, and a device id or a server does not`() {
        freshSettings()
        val rebuilder = rebuilder()
        Provisioning.apply(Settings, mapOf("profile" to "shelly-stargate"))
        assertEquals(2, builds)
        Provisioning.apply(Settings, mapOf("device" to "kitchen", "server" to "http://a:1"))
        assertEquals(2, builds)
        rebuilder.close()
    }

    @Test
    fun `closing tears down once and stops listening`() {
        freshSettings()
        val rebuilder = rebuilder()
        rebuilder.close()
        assertEquals(1, teardowns)
        Settings.profileOverride.set("shelly-jenna")
        assertEquals(1, builds)
    }
}

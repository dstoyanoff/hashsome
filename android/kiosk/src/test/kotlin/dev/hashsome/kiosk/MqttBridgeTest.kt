package dev.hashsome.kiosk

import dev.hashsome.client.Store
import dev.hashsome.testing.TestScheduler
import dev.hashsome.ui.ThemeChoice
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeLink : MqttLink {
    var listener: MqttLink.Listener? = null
    var connects = 0
    var host = ""
    var port = 0
    var clientId = ""
    var will: MqttLink.Message? = null
    var disconnects = 0
    val published = ArrayList<MqttLink.Message>()
    val subscribed = ArrayList<String>()

    override fun connect(host: String, port: Int, clientId: String, will: MqttLink.Message, listener: MqttLink.Listener) {
        connects++
        this.host = host
        this.port = port
        this.clientId = clientId
        this.will = will
        this.listener = listener
    }

    override fun publish(message: MqttLink.Message) {
        published += message
    }

    override fun subscribe(topic: String) {
        subscribed += topic
    }

    override fun disconnect() {
        disconnects++
    }

    fun last(topic: String) = published.lastOrNull { it.topic == topic }
    fun receive(topic: String, payload: String) = listener!!.onMessage(MqttLink.Message(topic, payload))
}

private class Actions : KioskActions {
    val calls = ArrayList<String>()
    var boom = false
    override fun wake() { if (boom) error("boom"); calls += "wake" }
    override fun sleep() { calls += "sleep" }
    override fun reload() { calls += "reload" }
    override fun restartApp() { calls += "restart_app" }
    override fun restartDevice() { calls += "restart_device" }
    override fun openStockApp() { calls += "open_stock_app" }
    override val canOpenStockApp = Store(false)
    override val canRestartDevice = Store(false)
    override fun checkForUpdate() { calls += "check_update" }
    override fun installUpdate() { calls += "install_update" }
    override val updateState = Store<UpdateState>(UpdateState.Off)
    override val installedVersion = "0.19.2"
}

class MqttBridgeTest {
    private val sched = TestScheduler()
    private val link = FakeLink()
    private val actions = Actions()
    private val state = KioskState(Store(null), Store(false), Store(false), Store("bathroom"), Store("Open"))
    private val ids = listOf("bathroom", "bathroom-small")
    private lateinit var bridge: MqttBridge

    private fun bridge(enabled: Boolean = true): MqttBridge {
        freshSettings()
        Settings.deviceId.set("bathroom")
        Settings.deviceName.set("Bathroom display")
        Settings.mqtt.host.set("10.0.0.114")
        Settings.mqtt.port.set(18830)
        Settings.mqtt.enabled.set(enabled)
        return MqttBridge(Settings, state, actions, link, ids).also {
            it.scheduler = sched
            it.appVersion = "0.19.2"
            it.profileId = "shelly-jenna"
            bridge = it
        }
    }

    private fun connected(): MqttBridge = bridge().also {
        it.start()
        link.listener!!.onConnected()
    }

    private val root = "hashsome/bathroom"

    @Test
    fun `the discovery payload is a device-based message with the planned components`() {
        val json = JSONObject(bridge().discoveryPayload())
        assertEquals(setOf("dev", "o", "cmps"), json.keys().asSequence().toSet())
        val dev = json.getJSONObject("dev")
        assertEquals("hashsome-bathroom", dev.getJSONArray("ids").getString(0))
        assertEquals("Bathroom display", dev.getString("name"))
        assertEquals("Hashsome", dev.getString("mf"))
        assertEquals("shelly-jenna", dev.getString("mdl"))
        assertEquals("0.19.2", dev.getString("sw"))
        assertEquals("Hashsome", json.getJSONObject("o").getString("name"))

        val cmps = json.getJSONObject("cmps")
        val platforms = cmps.keys().asSequence().associateWith { cmps.getJSONObject(it).getString("p") }
        assertEquals(
            mapOf(
                "screen" to "switch", "night_mode" to "switch", "night_idle" to "number", "brightness" to "number",
                "auto_brightness" to "switch", "min_brightness" to "number", "dashboard" to "select", "server" to "text", "update_url" to "text", "dashboard_switcher" to "switch",
                "theme" to "select", "illuminance" to "sensor", "occupancy" to "binary_sensor", "sleeping" to "binary_sensor",
                "page" to "sensor", "link" to "sensor", "wake" to "button", "sleep" to "button", "reload" to "button", "restart_app" to "button",
            ),
            platforms,
        )
    }

    @Test
    fun `restart_device is announced only where the device can reboot, and answers only then`() {
        val b = bridge()
        assertFalse(JSONObject(b.discoveryPayload()).getJSONObject("cmps").has("restart_device"))
        b.start()
        link.listener!!.onConnected()
        link.receive("$root/restart_device/set", "PRESS")
        link.receive("$root/restart_app/set", "PRESS")
        assertEquals(listOf("restart_app"), actions.calls)

        actions.canRestartDevice.set(true)
        val cmps = JSONObject(link.last("homeassistant/device/hashsome-bathroom/config")!!.payload).getJSONObject("cmps")
        assertEquals("button", cmps.getJSONObject("restart_device").getString("p"))
        assertEquals("$root/restart_device/set", cmps.getJSONObject("restart_device").getString("cmd_t"))
        link.receive("$root/restart_device/set", "PRESS")
        assertEquals(listOf("restart_app", "restart_device"), actions.calls)
    }

    @Test
    fun `open_stock_app is announced only on a Shelly profile, and answers only then`() {
        val b = bridge()
        assertFalse(JSONObject(b.discoveryPayload()).getJSONObject("cmps").has("open_stock_app"))
        b.start()
        link.listener!!.onConnected()
        link.receive("$root/open_stock_app/set", "PRESS")
        assertTrue(actions.calls.isEmpty())

        actions.canOpenStockApp.set(true)
        val cmps = JSONObject(link.last("homeassistant/device/hashsome-bathroom/config")!!.payload).getJSONObject("cmps")
        assertEquals("button", cmps.getJSONObject("open_stock_app").getString("p"))
        link.receive("$root/open_stock_app/set", "PRESS")
        assertEquals(listOf("open_stock_app"), actions.calls)
    }

    @Test
    fun `the update entity appears with a feed, reports the release and installs on request`() {
        val b = bridge()
        assertFalse(JSONObject(b.discoveryPayload()).getJSONObject("cmps").has("update"))
        b.start()
        link.listener!!.onConnected()
        val release = Release("dev.hashsome.home", "0.19.3", 1903, "http://h/a.apk", "a".repeat(64), 10, "Fixes")
        actions.updateState.set(UpdateState.Available(release))
        val cmps = JSONObject(link.last("homeassistant/device/hashsome-bathroom/config")!!.payload).getJSONObject("cmps")
        assertEquals("update", cmps.getJSONObject("update").getString("p"))
        assertTrue(cmps.has("check_update"))
        val payload = JSONObject(b.updateStatePayload())
        assertEquals("0.19.2", payload.getString("installed_version"))
        assertEquals("0.19.3", payload.getString("latest_version"))
        // Home Assistant keeps a key a message leaves out, so the end of an install must be said, not omitted.
        assertFalse(payload.getBoolean("in_progress"))
        assertTrue(payload.isNull("update_percentage"))
        actions.updateState.set(UpdateState.Downloading(release, 100))
        assertTrue(JSONObject(b.updateStatePayload()).getBoolean("in_progress"))
        actions.updateState.set(UpdateState.Available(release))
        link.receive("$root/update/set", "INSTALL")
        link.receive("$root/check_update/set", "PRESS")
        assertEquals(listOf("install_update", "check_update"), actions.calls)
    }

    @Test
    fun `home assistant can change the backend url, but only to an http address`() {
        val b = bridge()
        b.start()
        link.listener!!.onConnected()
        link.receive("$root/server/set", "http://10.1.1.2:3000")
        assertEquals("http://10.1.1.2:3000", Settings.server.value)
        link.receive("$root/server/set", "garbage")
        assertEquals("http://10.1.1.2:3000", Settings.server.value)
        assertEquals("http://10.1.1.2:3000", link.last("$root/server/state")!!.payload)
    }

    @Test
    fun `the brightness number is unavailable in home assistant while auto brightness decides`() {
        val b = bridge()
        Settings.autoBrightness.set(true)
        b.start()
        link.listener!!.onConnected()
        val brightness = JSONObject(b.discoveryPayload()).getJSONObject("cmps").getJSONObject("brightness")
        assertEquals("all", brightness.getString("avty_mode"))
        assertEquals("$root/brightness/usable", brightness.getJSONArray("avty").getJSONObject(1).getString("topic"))
        assertEquals("offline", link.last("$root/brightness/usable")!!.payload)
        Settings.autoBrightness.set(false)
        assertEquals("online", link.last("$root/brightness/usable")!!.payload)
    }

    @Test
    fun `home assistant can set or clear the update feed, and only to an address the updater reads`() {
        val b = bridge()
        b.start()
        link.listener!!.onConnected()
        link.receive("$root/update_url/set", "oci://10.4.5.1:5000/home-android")
        assertEquals("oci://10.4.5.1:5000/home-android", Settings.updateUrl.value)
        link.receive("$root/update_url/set", "garbage")
        assertEquals("oci://10.4.5.1:5000/home-android", Settings.updateUrl.value)
        link.receive("$root/update_url/set", "http://h/local/x")
        assertEquals("http://h/local/x", Settings.updateUrl.value)
        link.receive("$root/update_url/set", "")
        assertEquals(null, Settings.updateUrl.value)
        assertEquals("", link.last("$root/update_url/state")!!.payload)
    }

    @Test
    fun `every component has what Home Assistant needs`() {
        val cmps = JSONObject(bridge().discoveryPayload()).getJSONObject("cmps")
        val uniqueIds = HashSet<String>()
        for (key in cmps.keys()) {
            val c = cmps.getJSONObject(key)
            assertTrue("unique ids are not repeated", uniqueIds.add(c.getString("unique_id")))
            assertEquals("hashsome-bathroom_$key", c.getString("unique_id"))
            assertTrue(c.getString("name").isNotBlank())
            val avty = c.getJSONArray("avty").getJSONObject(0)
            assertEquals("$root/status", avty.getString("topic"))
            assertEquals("online", avty.getString("payload_available"))
            assertEquals("offline", avty.getString("payload_not_available"))
            when (c.getString("p")) {
                "switch", "number", "select" -> {
                    assertEquals("$root/$key/state", c.getString("stat_t"))
                    assertEquals("$root/$key/set", c.getString("cmd_t"))
                }
                "sensor", "binary_sensor" -> {
                    assertEquals("$root/$key/state", c.getString("stat_t"))
                    assertFalse(c.has("cmd_t"))
                }
                "button" -> {
                    assertEquals("$root/$key/set", c.getString("cmd_t"))
                    assertFalse(c.has("stat_t"))
                }
            }
        }
        assertEquals("illuminance", cmps.getJSONObject("illuminance").getString("device_class"))
        assertEquals("lx", cmps.getJSONObject("illuminance").getString("unit_of_measurement"))
        assertEquals("s", cmps.getJSONObject("night_idle").getString("unit_of_measurement"))
        assertEquals("diagnostic", cmps.getJSONObject("link").getString("entity_category"))
        assertEquals("occupancy", cmps.getJSONObject("occupancy").getString("device_class"))
    }

    @Test
    fun `selects offer the dashboards of the app and the three themes`() {
        val cmps = JSONObject(bridge().discoveryPayload()).getJSONObject("cmps")
        fun options(key: String) = cmps.getJSONObject(key).getJSONArray("options").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertEquals(ids, options("dashboard"))
        assertEquals(listOf("auto", "dark", "light"), options("theme"))
    }

    @Test
    fun `there is no reboot button because nothing can reboot`() {
        assertFalse(JSONObject(bridge().discoveryPayload()).getJSONObject("cmps").has("reboot"))
    }

    @Test
    fun `connecting subscribes, announces, sends the discovery and the whole state, all retained`() {
        val b = bridge()
        b.start()
        assertEquals(1, link.connects)
        assertEquals("10.0.0.114", link.host)
        assertEquals(18830, link.port)
        assertEquals("hashsome-bathroom", link.clientId)
        assertEquals(MqttLink.Message("$root/status", "offline", true).let { Triple(it.topic, it.payload, it.retained) }, link.will!!.let { Triple(it.topic, it.payload, it.retained) })
        link.listener!!.onConnected()
        assertEquals(listOf("homeassistant/status", "$root/+/set"), link.subscribed)
        assertEquals("online", link.last("$root/status")!!.payload)
        assertTrue(link.last("$root/status")!!.retained)
        val discovery = link.last("homeassistant/device/hashsome-bathroom/config")!!
        assertTrue(discovery.retained)
        assertEquals(b.discoveryPayload(), discovery.payload)
        assertEquals("ON", link.last("$root/screen/state")!!.payload)
        assertEquals("100", link.last("$root/brightness/state")!!.payload)
        assertEquals("bathroom", link.last("$root/dashboard/state")!!.payload)
        assertEquals("auto", link.last("$root/theme/state")!!.payload)
        assertEquals("120", link.last("$root/night_idle/state")!!.payload)
        assertEquals("OFF", link.last("$root/night_mode/state")!!.payload)
        assertEquals("Open", link.last("$root/link/state")!!.payload)
        assertEquals("bathroom", link.last("$root/page/state")!!.payload)
        assertNull("no illuminance until there is one", link.last("$root/illuminance/state"))
        assertTrue(link.published.filter { it.topic.endsWith("/state") }.all { it.retained })
    }

    @Test
    fun `an unset dashboard is published as the first one`() {
        connected()
        Settings.dashboard.set(null)
        assertEquals("bathroom", link.last("$root/dashboard/state")!!.payload)
    }

    @Test
    fun `home assistant restarting makes everything go out again`() {
        connected()
        link.published.clear()
        link.receive("homeassistant/status", "offline")
        assertTrue(link.published.isEmpty())
        link.receive("homeassistant/status", "online")
        assertNotNull(link.last("homeassistant/device/hashsome-bathroom/config"))
        assertEquals("online", link.last("$root/status")!!.payload)
        assertNotNull(link.last("$root/brightness/state"))
    }

    @Test
    fun `every settings change is published back`() {
        connected()
        Settings.brightness.set(40)
        assertEquals("40", link.last("$root/brightness/state")!!.payload)
        Settings.night.set(true)
        assertEquals("ON", link.last("$root/night_mode/state")!!.payload)
        Settings.nightIdleMs.set(30_000)
        assertEquals("30", link.last("$root/night_idle/state")!!.payload)
        Settings.theme.set(ThemeChoice.Dark)
        assertEquals("dark", link.last("$root/theme/state")!!.payload)
        Settings.autoBrightness.set(false)
        assertEquals("OFF", link.last("$root/auto_brightness/state")!!.payload)
        Settings.minBrightness.set(12)
        assertEquals("12", link.last("$root/min_brightness/state")!!.payload)
        Settings.dashboard.set("bathroom-small")
        assertEquals("bathroom-small", link.last("$root/dashboard/state")!!.payload)
        Settings.switcherEnabled.set(false)
        assertEquals("OFF", link.last("$root/dashboard_switcher/state")!!.payload)
        state.sleeping.set(true)
        assertEquals("ON", link.last("$root/sleeping/state")!!.payload)
        assertEquals("OFF", link.last("$root/screen/state")!!.payload)
        state.occupied.set(true)
        assertEquals("ON", link.last("$root/occupancy/state")!!.payload)
        state.page.set("bathroom/music")
        assertEquals("bathroom/music", link.last("$root/page/state")!!.payload)
        state.link.set("Closed")
        assertEquals("Closed", link.last("$root/link/state")!!.payload)
    }

    @Test
    fun `lux is sent when it matters and not more than every five seconds`() {
        connected()
        state.lux.set(60f)
        assertEquals("60", link.last("$root/illuminance/state")!!.payload)
        val sent = link.published.size
        state.lux.set(61f)
        assertEquals("a change under 10 percent is not worth a message", sent, link.published.size)
        state.lux.set(120f)
        assertEquals("too soon after the last one", sent, link.published.size)
        sched.advance(5_000)
        assertEquals("120", link.last("$root/illuminance/state")!!.payload)
    }

    @Test
    fun `commands from home assistant land in the settings and the actions`() {
        connected()
        link.receive("$root/night_mode/set", "ON")
        assertTrue(Settings.night.value)
        link.receive("$root/night_idle/set", "45.0")
        assertEquals(45_000L, Settings.nightIdleMs.value)
        link.receive("$root/brightness/set", "300")
        assertEquals(100, Settings.brightness.value)
        link.receive("$root/brightness/set", "60")
        assertEquals(60, Settings.brightness.value)
        link.receive("$root/auto_brightness/set", "OFF")
        assertFalse(Settings.autoBrightness.value)
        link.receive("$root/min_brightness/set", "20")
        assertEquals(20, Settings.minBrightness.value)
        link.receive("$root/dashboard/set", "bathroom-small")
        assertEquals("bathroom-small", Settings.dashboard.value)
        link.receive("$root/dashboard/set", "no-such-dashboard")
        assertEquals("bathroom-small", Settings.dashboard.value)
        link.receive("$root/dashboard_switcher/set", "ON")
        assertTrue(Settings.switcherEnabled.value)
        link.receive("$root/theme/set", "light")
        assertEquals(ThemeChoice.Light, Settings.theme.value)
        link.receive("$root/theme/set", "sepia")
        assertEquals(ThemeChoice.Light, Settings.theme.value)
        link.receive("$root/screen/set", "OFF")
        link.receive("$root/screen/set", "ON")
        link.receive("$root/wake/set", "PRESS")
        link.receive("$root/sleep/set", "PRESS")
        link.receive("$root/reload/set", "PRESS")
        assertEquals(listOf("sleep", "wake", "wake", "sleep", "reload"), actions.calls)
        // Nonsense and unknown keys do nothing.
        link.receive("$root/brightness/set", "very")
        link.receive("$root/nothing/set", "1")
        assertEquals(60, Settings.brightness.value)
    }

    @Test
    fun `a lost connection is retried with a growing delay that starts over after a connect`() {
        bridge().start()
        assertEquals(1, link.connects)
        link.listener!!.onDisconnected("refused")
        sched.advance(999)
        assertEquals(1, link.connects)
        sched.advance(1)
        assertEquals(2, link.connects)
        link.listener!!.onDisconnected("refused")
        sched.advance(1_999)
        assertEquals(2, link.connects)
        sched.advance(1)
        assertEquals(3, link.connects)
        link.listener!!.onConnected()
        link.listener!!.onDisconnected("lost")
        sched.advance(1_000)
        assertEquals(4, link.connects)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), (0..7).map { MqttBridge.backoff(it) })
    }

    @Test
    fun `nothing connects while mqtt is off or has no host, and changing the settings connects`() {
        val b = bridge(enabled = false)
        b.start()
        assertEquals(0, link.connects)
        Settings.mqtt.enabled.set(true)
        assertEquals(1, link.connects)
        // A new host reconnects.
        Settings.mqtt.host.set("10.0.0.200")
        assertEquals(2, link.connects)
        assertEquals("10.0.0.200", link.host)
        Settings.mqtt.host.set("")
        assertEquals(2, link.connects)
    }

    @Test
    fun `stopping says offline before it goes`() {
        val b = connected()
        b.stop()
        assertEquals("offline", link.published.last().payload)
        assertEquals("$root/status", link.published.last().topic)
        assertEquals(1, link.disconnects)
        // And it no longer reacts to settings.
        val before = link.published.size
        Settings.brightness.set(7)
        assertEquals(before, link.published.size)
    }

    @Test
    fun `a base topic other than the default moves every topic`() {
        val b = bridge()
        Settings.mqtt.base.set("walls")
        b.start()
        link.listener!!.onConnected()
        assertNotNull(link.last("walls/bathroom/status"))
        assertEquals("walls/bathroom/status", link.will!!.topic)
        assertEquals(listOf("homeassistant/status", "walls/bathroom/+/set"), link.subscribed)
    }

    @Test
    fun `brightness and minimum brightness are percent in the discovery`() {
        val cmps = JSONObject(bridge().discoveryPayload()).getJSONObject("cmps")
        for (key in listOf("brightness", "min_brightness")) {
            assertEquals(100, cmps.getJSONObject(key).getInt("max"))
            assertEquals(0, cmps.getJSONObject(key).getInt("min"))
            assertEquals("%", cmps.getJSONObject(key).getString("unit_of_measurement"))
        }
    }

    @Test
    fun `numbers that are not numbers do nothing on any command topic`() {
        connected()
        Settings.brightness.set(50)
        Settings.minBrightness.set(10)
        Settings.nightIdleMs.set(30_000)
        for (bad in listOf("NaN", "", "garbage", "Infinity", "-Infinity", "1e999", "  ")) {
            link.receive("$root/brightness/set", bad)
            link.receive("$root/min_brightness/set", bad)
            link.receive("$root/night_idle/set", bad)
        }
        assertEquals(50, Settings.brightness.value)
        assertEquals(10, Settings.minBrightness.value)
        assertEquals(30_000L, Settings.nightIdleMs.value)
        // And the other kinds ignore garbage too.
        for (key in listOf("night_mode", "auto_brightness", "dashboard_switcher", "theme", "dashboard", "screen", "nothing")) {
            for (bad in listOf("NaN", "", "garbage")) link.receive("$root/$key/set", bad)
        }
        assertFalse(Settings.night.value)
        assertEquals(ThemeChoice.Auto, Settings.theme.value)
        assertEquals(emptyList<String>(), actions.calls)
    }

    @Test
    fun `a command that throws does not take the bridge down`() {
        connected()
        actions.boom = true
        link.receive("$root/wake/set", "PRESS")
        actions.boom = false
        link.receive("$root/brightness/set", "70")
        assertEquals(70, Settings.brightness.value)
    }

    @Test
    fun `a new device id retracts the old discovery before announcing the new one`() {
        connected()
        val oldDiscovery = "homeassistant/device/hashsome-bathroom/config"
        link.published.clear()
        Settings.deviceId.set("kitchen")
        val retract = link.published.indexOfFirst { it.topic == oldDiscovery }
        assertTrue("an empty retained config under the old id", retract >= 0)
        assertEquals("", link.published[retract].payload)
        assertTrue(link.published[retract].retained)
        assertEquals("offline", link.published.first { it.topic == "$root/status" }.payload)
        assertEquals(2, link.connects)
        assertEquals("hashsome-kitchen", link.clientId)
        link.listener!!.onConnected()
        val announce = link.published.indexOfFirst { it.topic == "homeassistant/device/hashsome-kitchen/config" }
        assertTrue(announce > retract)
        assertEquals("online", link.last("hashsome/kitchen/status")!!.payload)
    }
}

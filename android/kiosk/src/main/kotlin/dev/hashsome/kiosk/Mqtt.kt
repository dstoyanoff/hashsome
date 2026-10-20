package dev.hashsome.kiosk

// The discovery payload follows ShellyElevate's MqttDiscoveryConfigBuilder (Apache-2.0, see android/NOTICE) in shape:
// one device-based message, a shared availability topic with a last will, and a resend on `homeassistant/status`.

import dev.hashsome.client.Cancel
import dev.hashsome.client.Scheduler
import dev.hashsome.client.Store
import dev.hashsome.ui.ThemeChoice
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The MQTT client, behind an interface so the Paho MQTTv5 client can be swapped for a smaller one (spike S4) and the bridge tested without a broker. */
interface MqttLink {
    fun connect(host: String, port: Int, clientId: String, will: Message, listener: Listener)
    fun publish(message: Message)
    fun subscribe(topic: String)
    fun disconnect()

    class Message(val topic: String, val payload: String, val retained: Boolean = false)

    interface Listener {
        fun onConnected()
        fun onMessage(message: Message)
        fun onDisconnected(reason: String?)
    }
}

/** What Home Assistant can make the device do. */
interface KioskActions {
    fun wake()
    fun sleep()
    fun reload()

    /** Restarts the app: the service comes back ~1.5 s later and the dashboard with it. */
    fun restartApp()

    /** Restarts the device where [canRestartDevice] says it can be; otherwise it only logs. */
    fun restartDevice()

    /** Opens the stock Shelly app and pauses keep-in-front (Shelly profiles only, see [canOpenStockApp]). */
    fun openStockApp()

    val canOpenStockApp: Store<Boolean>

    /** Whether [restartDevice] has a way to work (probed once the service runs); false until then. */
    val canRestartDevice: Store<Boolean>

    /** Asks the update feed whether a newer release is there; see [UpdateState]. */
    fun checkForUpdate()

    /** Downloads and installs the release that is available. */
    fun installUpdate()

    /** Where the updater stands, and the version this install is. */
    val updateState: Store<UpdateState>
    val installedVersion: String
}

/** What the bridge reports that is not a setting. */
class KioskState(
    val lux: Store<Float?>,
    val occupied: Store<Boolean>,
    val sleeping: Store<Boolean>,
    val page: Store<String>,
    val link: Store<String>,
)

/**
 * Publishes the device to Home Assistant by MQTT discovery and applies what HA sends. Discovery goes to
 * `homeassistant/device/<id>/config` as `{dev, o, cmps}`; availability is `<base>/<id>/status` (retained, last will);
 * everything is sent again on `homeassistant/status` = `online`. Components:
 * switch screen, switch night_mode, number night_idle (s), number brightness, switch auto_brightness, number min_brightness,
 * select dashboard (options = the app's dashboard ids), switch dashboard_switcher, select theme (auto|dark|light),
 * sensor illuminance (lx), binary_sensor occupancy, binary_sensor sleeping, sensor page, sensor link (diagnostic),
 * buttons wake/sleep/reload/restart_app, open_stock_app (Shelly profiles) and restart_device only where the device can be rebooted.
 * Every `Settings` change is published back, so HA and the settings sheet always agree.
 */
class MqttBridge(
    private val settings: Settings,
    private val state: KioskState,
    private val actions: KioskActions,
    private val link: MqttLink,
    private val dashboardIds: List<String>,
) {
    /** Tests give theirs; in the app it is the main looper's. */
    internal var scheduler: Scheduler? = null

    /** What `dev.sw` and `dev.mdl` say. */
    internal var appVersion = "unknown"
    internal var profileId = "generic"

    private val sched: Scheduler get() = scheduler ?: mainScheduler()
    private var running = false
    private var connected = false
    private var attempts = 0
    private var retry: Cancel? = null
    private val watching = ArrayList<Cancel>()
    private var luxSentAt = Long.MIN_VALUE / 2
    private var luxSent: Float? = null
    private var luxTrailing: Cancel? = null

    /** The topics this connection announced itself under, so a new device id or base can retract them. */
    private var linkStatusTopic: String? = null
    private var linkDiscoveryTopic: String? = null

    private class Component(
        val key: String,
        val platform: String,
        val name: String,
        val describe: JSONObject.() -> Unit = {},
        val value: (() -> String?)? = null,
        val command: ((String) -> Unit)? = null,
        val stores: List<Store<*>> = emptyList(),
        /** Whether this component is announced and answers at all (restart_device only where a reboot is possible). */
        val offered: () -> Boolean = { true },
    )

    private val deviceId: String get() = settings.deviceId.value ?: "unknown"
    private val root: String get() = "${settings.mqtt.base.value.trim('/')}/$deviceId"
    private val statusTopic: String get() = "$root/status"

    private fun stateTopic(key: String) = "$root/$key/state"
    private fun commandTopic(key: String) = "$root/$key/set"

    private fun onOff(on: Boolean) = if (on) "ON" else "OFF"

    /** The `update` entity's state, as Home Assistant reads it: the installed and the latest version, the notes, and whether an install is running. */
    internal fun updateStatePayload(): String {
        val installed = actions.installedVersion
        val json = JSONObject().put("installed_version", installed).put("title", "Hashsome")
        when (val u = actions.updateState.value) {
            is UpdateState.Available -> json.put("latest_version", u.release.versionName).put("release_summary", u.release.notes ?: "")
            is UpdateState.Downloading -> json.put("latest_version", u.release.versionName).put("in_progress", true).put("update_percentage", u.percent)
            is UpdateState.Installing -> json.put("latest_version", u.release.versionName).put("in_progress", true)
            is UpdateState.Failed -> json.put("latest_version", u.release?.versionName ?: installed).put("release_summary", "Update failed: ${u.message}")
            else -> json.put("latest_version", installed)
        }
        return json.toString()
    }

    private fun parseOnOff(payload: String): Boolean? = when (payload.trim().uppercase()) {
        "ON", "TRUE", "1" -> true
        "OFF", "FALSE", "0" -> false
        else -> null
    }

    /** A finite number, or null: `NaN`, `inf` and text are not commands. */
    private fun parseNumber(payload: String): Int? = payload.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.roundToInt()

    private val components: List<Component> by lazy {
        listOf(
            Component("screen", "switch", "Screen", value = { onOff(!state.sleeping.value) }, stores = listOf(state.sleeping), command = {
                when (parseOnOff(it)) {
                    true -> actions.wake()
                    false -> actions.sleep()
                    null -> {}
                }
            }),
            Component("night_mode", "switch", "Night mode", value = { onOff(settings.night.value) }, stores = listOf(settings.night), command = {
                parseOnOff(it)?.let(settings.night::set)
            }),
            Component(
                "night_idle", "number", "Night idle time",
                describe = { put("min", 5); put("max", 3600); put("step", 5); put("unit_of_measurement", "s"); put("device_class", "duration") },
                value = { (settings.nightIdleMs.value / 1000).toString() },
                stores = listOf(settings.nightIdleMs),
                command = { parseNumber(it)?.let { s -> settings.nightIdleMs.set(s.coerceIn(5, 3600) * 1000L) } },
            ),
            Component(
                "brightness", "number", "Brightness",
                describe = { put("min", 0); put("max", 100); put("step", 1); put("unit_of_measurement", "%") },
                value = { settings.brightness.value.toString() },
                stores = listOf(settings.brightness),
                command = { parseNumber(it)?.let { n -> settings.brightness.set(n.coerceIn(0, 100)) } },
            ),
            Component("auto_brightness", "switch", "Auto brightness", value = { onOff(settings.autoBrightness.value) }, stores = listOf(settings.autoBrightness), command = {
                parseOnOff(it)?.let(settings.autoBrightness::set)
            }),
            Component(
                "min_brightness", "number", "Minimum brightness",
                describe = { put("min", 0); put("max", 100); put("step", 1); put("unit_of_measurement", "%") },
                value = { settings.minBrightness.value.toString() },
                stores = listOf(settings.minBrightness),
                command = { parseNumber(it)?.let { n -> settings.minBrightness.set(n.coerceIn(0, 100)) } },
            ),
            Component(
                "dashboard", "select", "Dashboard",
                describe = { put("options", JSONArray(dashboardIds)) },
                value = { settings.dashboard.value ?: dashboardIds.firstOrNull() },
                stores = listOf(settings.dashboard),
                command = { if (it in dashboardIds) settings.dashboard.set(it) },
            ),
            Component("dashboard_switcher", "switch", "Dashboard switcher", value = { onOff(settings.switcherEnabled.value) }, stores = listOf(settings.switcherEnabled), command = {
                parseOnOff(it)?.let(settings.switcherEnabled::set)
            }),
            Component(
                "theme", "select", "Theme",
                describe = { put("options", JSONArray(THEMES.map { it.name.lowercase() })) },
                value = { settings.theme.value.name.lowercase() },
                stores = listOf(settings.theme),
                command = { text -> THEMES.firstOrNull { it.name.equals(text.trim(), ignoreCase = true) }?.let(settings.theme::set) },
            ),
            Component(
                "illuminance", "sensor", "Illuminance",
                describe = { put("device_class", "illuminance"); put("unit_of_measurement", "lx"); put("state_class", "measurement") },
                value = { state.lux.value?.let { it.roundToInt().toString() } },
            ),
            Component(
                "occupancy", "binary_sensor", "Occupancy",
                describe = { put("device_class", "occupancy") },
                value = { onOff(state.occupied.value) },
                stores = listOf(state.occupied),
            ),
            Component("sleeping", "binary_sensor", "Sleeping", value = { onOff(state.sleeping.value) }, stores = listOf(state.sleeping)),
            Component("page", "sensor", "Page", value = { state.page.value.ifEmpty { null } }, stores = listOf(state.page)),
            Component(
                "link", "sensor", "Link",
                describe = { put("entity_category", "diagnostic") },
                value = { state.link.value },
                stores = listOf(state.link),
            ),
            Component("wake", "button", "Wake", describe = { put("payload_press", "PRESS") }, command = { actions.wake() }),
            Component("sleep", "button", "Sleep", describe = { put("payload_press", "PRESS") }, command = { actions.sleep() }),
            Component("reload", "button", "Reload", describe = { put("payload_press", "PRESS") }, command = { actions.reload() }),
            Component("check_update", "button", "Check for update", describe = { put("payload_press", "PRESS"); put("entity_category", "diagnostic") }, command = { actions.checkForUpdate() }, stores = listOf(actions.updateState), offered = { actions.updateState.value != UpdateState.Off }),
            Component(
                "update", "update", "Hashsome",
                describe = {
                    put("device_class", "firmware")
                    put("payload_install", "INSTALL")
                    put("entity_category", "diagnostic")
                },
                value = { updateStatePayload() },
                command = { if (it.trim().equals("INSTALL", ignoreCase = true)) actions.installUpdate() },
                stores = listOf(actions.updateState),
                offered = { actions.updateState.value != UpdateState.Off },
            ),
            Component("restart_app", "button", "Restart app", describe = { put("payload_press", "PRESS") }, command = { actions.restartApp() }),
            Component(
                "open_stock_app", "button", "Open Shelly app", describe = { put("payload_press", "PRESS") },
                command = { actions.openStockApp() },
                offered = { actions.canOpenStockApp.value },
            ),
            Component(
                "restart_device", "button", "Restart device",
                describe = { put("payload_press", "PRESS"); put("device_class", "restart") },
                command = { actions.restartDevice() },
                offered = { actions.canRestartDevice.value },
            ),
        )
    }

    fun start() {
        if (running) return
        running = true
        val reconnect: (Any?) -> Unit = { reconfigure() }
        val configured = listOf<Store<*>>(settings.mqtt.enabled, settings.mqtt.host, settings.mqtt.port, settings.mqtt.base, settings.deviceId)
        for (store in configured) {
            @Suppress("UNCHECKED_CAST")
            watching += (store as Store<Any?>).listen(reconnect)
        }
        @Suppress("UNCHECKED_CAST")
        watching += (settings.deviceName as Store<Any?>).listen { if (connected) publishDiscovery() }
        for (component in components) {
            for (store in component.stores) {
                @Suppress("UNCHECKED_CAST")
                watching += (store as Store<Any?>).listen { if (connected) publishState(component) }
            }
        }
        watching += state.lux.listen { if (connected) publishLux() }
        // The reboot probe finishes after the first discovery: announce the button then.
        watching += actions.canRestartDevice.listen { if (connected) publishDiscovery() }
        // The profile (and so "Open Shelly app") is known once the service has built, and changes with profileOverride.
        watching += actions.canOpenStockApp.listen { if (connected) publishDiscovery() }
        // The update entity is announced only with a feed set; set or cleared, it appears or goes.
        var feedOn = actions.updateState.value != UpdateState.Off
        watching += actions.updateState.listen {
            val on = it != UpdateState.Off
            if (on != feedOn) {
                feedOn = on
                if (connected) publishDiscovery()
            }
        }
        connectIfWanted()
    }

    fun stop() {
        running = false
        watching.forEach { it() }
        watching.clear()
        disconnect(graceful = true)
    }

    /** The discovery payload, as the JSON text that is published. Pure, so it is checked against HA's device-discovery schema. */
    fun discoveryPayload(): String {
        val device = JSONObject()
            .put("ids", JSONArray().put("hashsome-$deviceId"))
            .put("name", settings.deviceName.value ?: deviceId)
            .put("mf", "Hashsome")
            .put("mdl", profileId)
            .put("sw", appVersion)
        val origin = JSONObject().put("name", "Hashsome").put("sw", appVersion)
        val availability = JSONArray().put(
            JSONObject().put("topic", statusTopic).put("payload_available", "online").put("payload_not_available", "offline"),
        )
        val cmps = JSONObject()
        for (c in components.filter { it.offered() }) {
            val body = JSONObject()
                .put("p", c.platform)
                .put("unique_id", "hashsome-${deviceId}_${c.key}")
                .put("name", c.name)
                .put("avty", availability)
            if (c.value != null) body.put("stat_t", stateTopic(c.key))
            if (c.command != null) body.put("cmd_t", commandTopic(c.key))
            c.describe(body)
            cmps.put(c.key, body)
        }
        return JSONObject().put("dev", device).put("o", origin).put("cmps", cmps).toString()
    }

    /** Every state topic with the payload it would be published with now. */
    internal fun statePayloads(): Map<String, String> =
        components.mapNotNull { c -> c.value?.invoke()?.let { stateTopic(c.key) to it } }.toMap()

    internal val discoveryTopic: String get() = "homeassistant/device/hashsome-$deviceId/config"

    // ---- connection ---------------------------------------------------------------------------------------------

    private fun wanted() = settings.mqtt.enabled.value && settings.mqtt.host.value.isNotBlank()

    private fun reconfigure() {
        if (!running) return
        disconnect(graceful = true)
        connectIfWanted()
    }

    private fun connectIfWanted() {
        retry?.invoke()
        retry = null
        if (!running || !wanted()) return
        val will = MqttLink.Message(statusTopic, "offline", retained = true)
        linkStatusTopic = statusTopic
        linkDiscoveryTopic = discoveryTopic
        link.connect(settings.mqtt.host.value.trim(), settings.mqtt.port.value, "hashsome-$deviceId", will, listener)
    }

    private fun disconnect(graceful: Boolean) {
        retry?.invoke()
        retry = null
        luxTrailing?.invoke()
        luxTrailing = null
        if (connected && graceful) {
            val status = linkStatusTopic ?: statusTopic
            link.publish(MqttLink.Message(status, "offline", retained = true))
            // A new device id: Home Assistant must forget the entities of the old one (an empty retained config removes them).
            val discovery = linkDiscoveryTopic
            if (discovery != null && discovery != discoveryTopic) link.publish(MqttLink.Message(discovery, "", retained = true))
        }
        connected = false
        link.disconnect()
    }

    private val listener = object : MqttLink.Listener {
        override fun onConnected() {
            if (!running) return
            connected = true
            attempts = 0
            Log.i("mqtt", "connected as hashsome-$deviceId")
            link.subscribe(HA_STATUS)
            link.subscribe("$root/+/set")
            sendEverything()
        }

        override fun onMessage(message: MqttLink.Message) {
            if (!running) return
            if (message.topic == HA_STATUS) {
                // Home Assistant restarted: it has forgotten the entities.
                if (message.payload.trim() == "online") sendEverything()
                return
            }
            val prefix = "$root/"
            if (!message.topic.startsWith(prefix) || !message.topic.endsWith("/set")) return
            val key = message.topic.substring(prefix.length, message.topic.length - "/set".length)
            try {
                components.firstOrNull { it.key == key && it.offered() }?.command?.invoke(message.payload)
            } catch (e: Exception) {
                Log.w("mqtt", "command for $key ignored: ${e.message}")
            }
        }

        override fun onDisconnected(reason: String?) {
            if (!running) return
            connected = false
            val wait = backoff(attempts++)
            Log.w("mqtt", "disconnected (${reason ?: "no reason"}), trying again in ${wait / 1000} s")
            retry?.invoke()
            retry = sched.after(wait) {
                retry = null
                connectIfWanted()
            }
        }
    }

    private fun sendEverything() {
        link.publish(MqttLink.Message(statusTopic, "online", retained = true))
        publishDiscovery()
        for (c in components) publishState(c)
        luxSent = state.lux.value
        luxSentAt = sched.now()
    }

    private fun publishDiscovery() {
        link.publish(MqttLink.Message(discoveryTopic, discoveryPayload(), retained = true))
    }

    private fun publishState(component: Component) {
        val payload = component.value?.invoke() ?: return
        link.publish(MqttLink.Message(stateTopic(component.key), payload, retained = true))
    }

    /** Lux changes constantly: a change of 10 % (at least 1 lx) is sent at most every 5 s, the latest value always follows. */
    private fun publishLux() {
        val value = state.lux.value ?: return
        luxTrailing?.invoke()
        luxTrailing = null
        val last = luxSent
        val changedEnough = last == null || abs(value - last) >= max(1f, abs(last) * 0.1f)
        if (!changedEnough) return
        val wait = if (last == null) 0 else luxSentAt + LUX_MIN_INTERVAL_MS - sched.now()
        if (wait > 0) {
            luxTrailing = sched.after(wait) {
                luxTrailing = null
                publishLux()
            }
            return
        }
        luxSent = value
        luxSentAt = sched.now()
        components.firstOrNull { it.key == "illuminance" }?.let(::publishState)
    }

    companion object {
        private const val HA_STATUS = "homeassistant/status"
        private const val LUX_MIN_INTERVAL_MS = 5_000L
        private val THEMES = ThemeChoice.values().toList()

        /** 1, 2, 4 ... seconds, at most a minute. */
        internal fun backoff(attempt: Int): Long = min(60_000L, 1_000L shl min(attempt, 6))
    }
}

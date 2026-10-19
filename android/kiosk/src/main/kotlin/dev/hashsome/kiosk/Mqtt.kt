package dev.hashsome.kiosk

import android.content.Context
import android.view.View
import dev.hashsome.client.Store

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
    fun rebootIfPossible()
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
 * buttons wake/sleep/reload (reboot only where a shell exists).
 * Every `Settings` change is published back, so HA and the settings sheet always agree.
 */
class MqttBridge(
    private val settings: Settings,
    private val state: KioskState,
    private val actions: KioskActions,
    private val link: MqttLink,
    private val dashboardIds: List<String>,
) {
    fun start(): Unit = TODO("WP5")
    fun stop(): Unit = TODO("WP5")

    /** The discovery payload, as the JSON text that is published. Pure, so it is checked against HA's device-discovery schema. */
    fun discoveryPayload(): String = TODO("WP5")
}

/**
 * The hidden settings: connection (server, device, profile, status, reload), dashboard (choice, switcher), theme, screen
 * (brightness, auto-brightness, minimum, night mode and its idle time, wake/sleep test), MQTT, debug. A `Sheet` on small
 * screens, a `Drawer` on wide ones; every control writes the one `Settings` store. Opened by the [Gesture], or by
 * `am start --es settings true`.
 */
class SettingsSheet(
    private val context: Context,
    private val settings: Settings,
    private val state: KioskState,
    private val actions: KioskActions,
) {
    /** The view to show in an overlay. */
    fun view(): View = TODO("WP5")
}

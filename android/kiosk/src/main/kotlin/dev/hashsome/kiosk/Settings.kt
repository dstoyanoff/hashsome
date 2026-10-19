package dev.hashsome.kiosk

import android.content.Context
import dev.hashsome.client.Store
import dev.hashsome.ui.Hashsome
import dev.hashsome.ui.HostSettings
import dev.hashsome.ui.ThemeChoice

/** The MQTT connection of the Home Assistant device. */
class MqttSettings {
    val enabled: Store<Boolean> = Store(false)
    val host: Store<String> = Store("")
    val port: Store<Int> = Store(1883)

    /** The topic prefix; the device publishes under `<base>/<deviceId>`. */
    val base: Store<String> = Store("hashsome")
}

class DebugSettings {
    /** Draws the module grid over the page. */
    val grid: Store<Boolean> = Store(false)

    /** Reports frame gaps. */
    val perf: Store<Boolean> = Store(false)
}

/**
 * Every setting of a device, one [Store] per key, persisted in SharedPreferences. Provisioning, the settings sheet and
 * Home Assistant all write these stores and nothing else; the MQTT bridge publishes each change back, so all three agree.
 * Call [init] once, before anything reads a store.
 */
object Settings : HostSettings {
    override val server: Store<String?> = Store(null)
    val deviceId: Store<String?> = Store(null)
    val deviceName: Store<String?> = Store(null)

    /** A [DeviceProfile.id] that replaces the detected profile; null detects it. */
    val profileOverride: Store<String?> = Store(null)

    /** The id of the dashboard to show. Three writers, last write wins: provisioning, the settings sheet, Home Assistant's select. */
    override val dashboard: Store<String?> = Store(null)

    /** Whether the top bar's dashboard switcher shows (default on for the generic profile, off for the Shelly ones). */
    override val switcherEnabled: Store<Boolean> = Store(true)
    override val theme: Store<ThemeChoice> = Store(ThemeChoice.Auto)

    /** 0..255. */
    val brightness: Store<Int> = Store(255)
    val autoBrightness: Store<Boolean> = Store(true)
    val minBrightness: Store<Int> = Store(8)

    /** Night mode on or off. Only Home Assistant and the settings sheet change it; the app never does. */
    val night: Store<Boolean> = Store(false)
    val nightIdleMs: Store<Long> = Store(120_000)
    val mqtt: MqttSettings = MqttSettings()
    val debug: DebugSettings = DebugSettings()

    /** Loads the stored values into the stores and starts persisting changes. [defaults] fill in what was never set (first provision only). */
    fun init(context: Context, defaults: KioskConfig = KioskConfig()) {
        Hashsome.settings = this
        load(context, defaults)
    }

    private fun load(context: Context, defaults: KioskConfig): Unit = TODO("WP5")
}

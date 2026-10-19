package dev.hashsome.kiosk

import android.content.Context
import dev.hashsome.client.Cancel
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

    /** Reserved for frame-gap reports: nothing reads it yet, and the sheet does not show it. */
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

    /** Percent, 0..100; each profile maps it onto the panel's own range (see [panelLevel]), and 0 % is still lit. */
    val brightness: Store<Int> = Store(100)
    val autoBrightness: Store<Boolean> = Store(true)
    /** Percent, 0..100: where auto-brightness settles in the dark. */
    val minBrightness: Store<Int> = Store(8)

    /** Night mode on or off. Only Home Assistant and the settings sheet change it; the app never does. */
    val night: Store<Boolean> = Store(false)
    val nightIdleMs: Store<Long> = Store(120_000)
    val mqtt: MqttSettings = MqttSettings()
    val debug: DebugSettings = DebugSettings()

    private var prefs: Prefs? = null
    private var persisting: List<Cancel> = emptyList()

    /** Loads the stored values into the stores and starts persisting changes. [defaults] fill in what was never set (first provision only). */
    fun init(context: Context, defaults: KioskConfig = KioskConfig()) {
        Hashsome.settings = this
        load(context, defaults)
    }

    private fun load(context: Context, defaults: KioskConfig) {
        val store = SharedPrefsPrefs.of(context)
        // The switcher is for tablets: off for the Shelly displays, which have no room for it. Needs profileOverride, so after the load.
        load(store, defaults) { DeviceProfile.detect() === DeviceProfile.Generic }
        if (deviceId.value == null) deviceId.set("android-" + androidIdSuffix(context))
    }

    /** What [load] does, on any [Prefs]. Calling it again re-reads, dropping the persisting of the previous call. */
    internal fun load(from: Prefs, defaults: KioskConfig, isGeneric: () -> Boolean) {
        persisting.forEach { it() }
        persisting = emptyList()
        prefs = from
        val cancels = ArrayList<Cancel>()

        fun <T> key(name: String, store: Store<T>, default: T, encode: (T) -> String?, decode: (String) -> T?) {
            val stored = if (from.contains(name)) from.getString(name)?.let(decode) else null
            if (stored != null) {
                store.set(stored)
            } else {
                store.set(default)
                // A default is written, so it is "set" from now on and a later change of KioskConfig does not move it.
                from.putString(name, encode(default))
            }
            cancels += store.listen { from.putString(name, encode(it)) }
        }

        fun text(name: String, store: Store<String?>) = key(name, store, null, { it }, { it })
        fun flag(name: String, store: Store<Boolean>, default: Boolean) = key(name, store, default, { it.toString() }, { it.toBooleanStrictOrNull() })
        fun number(name: String, store: Store<Int>, default: Int) = key(name, store, default, { it.toString() }, { it.toIntOrNull() })

        text("server", server)
        text("deviceId", deviceId)
        text("deviceName", deviceName)
        text("profileOverride", profileOverride)
        text("dashboard", dashboard)
        key("theme", theme, ThemeChoice.Auto, { it.name.lowercase() }, { s -> ThemeChoice.values().firstOrNull { it.name.equals(s, ignoreCase = true) } })
        number("brightness", brightness, 100)
        flag("autoBrightness", autoBrightness, defaults.autoBrightness)
        number("minBrightness", minBrightness, defaults.minBrightness)
        flag("night", night, false)
        key("nightIdleMs", nightIdleMs, defaults.nightIdleMs, { it.toString() }, { it.toLongOrNull() })
        flag("mqtt.enabled", mqtt.enabled, false)
        key("mqtt.host", mqtt.host, "", { it }, { it })
        number("mqtt.port", mqtt.port, 1883)
        key("mqtt.base", mqtt.base, "hashsome", { it }, { it })
        flag("debug.grid", debug.grid, false)
        flag("debug.perf", debug.perf, false)
        // Read after the profile override is in.
        flag("switcherEnabled", switcherEnabled, isGeneric())
        persisting = cancels
    }

    /** The last provisioning broadcast, as JSON text, for the sheet's "re-provision". */
    internal var lastProvision: String?
        get() = prefs?.getString("lastProvision")
        set(value) {
            prefs?.putString("lastProvision", value)
        }

    private fun androidIdSuffix(context: Context): String {
        val id = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        return id?.takeLast(6)?.ifBlank { null } ?: "device"
    }
}

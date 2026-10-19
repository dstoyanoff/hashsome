package dev.hashsome.kiosk

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.admin.DeviceAdminReceiver
import android.os.IBinder
import dev.hashsome.client.Scheduler
import dev.hashsome.client.Store
import dev.hashsome.ui.HashsomeApp
import dev.hashsome.ui.PageListener
import dev.hashsome.ui.ScreenPolicy
import dev.hashsome.ui.TouchListener

// What makes a display a kiosk: it stays in front, dims and sleeps on its own terms, and is a Home Assistant device.
// `kiosk` depends on `ui` and implements the plug points `ui` exposes on `HashsomeApp`; `ui` never imports this module.

/** How the settings sheet is opened. Both gestures are always available; this picks the primary one the docs and the sheet's hint name. */
enum class Gesture {
    /** Five taps on the status dot within 2 s. Falls back to [TwoFingerHold] on pages without a top row. */
    FiveTapsOnStatusDot,

    /** Two fingers held anywhere for 2 s. */
    TwoFingerHold,
}

/**
 * What the consumer decides for the kiosk. These are defaults written into `Settings` on first provision only;
 * afterwards Home Assistant and the settings sheet own them. Night mode is never switched by the app itself.
 */
data class KioskConfig(
    val nightIdleMs: Long = 120_000,
    val autoBrightness: Boolean = true,
    val minBrightness: Int = 8,
    val settingsGesture: Gesture = Gesture.FiveTapsOnStatusDot,
)

/** [this] with the kiosk's plug points installed (screen policy, touch listener, page listener). Call it in `Application.onCreate`. */
fun HashsomeApp.withKiosk(config: KioskConfig = KioskConfig()): HashsomeApp = TODO("WP5")

// ---- Device ---------------------------------------------------------------------------------------------------------

enum class ScreenState { On, Off }

/** The panel of one kind of device. */
interface Screen {
    /** Whether [off] switches the panel itself off (true) or only blacks it and zeroes the backlight (false). */
    val canTrulyOff: Boolean
    val state: Store<ScreenState>

    /** 0..255. */
    fun brightness(level: Int)
    fun off()
    fun wake()
}

/** A kind of device, and how to drive its screen and its presence sensor. */
sealed interface DeviceProfile {
    val id: String

    /** Whether this profile needs the device-admin permission for its screen-off (`lockNow()`). */
    val needsDeviceAdmin: Boolean

    fun screen(context: Context): Screen
    fun presence(context: Context, scheduler: Scheduler): Presence

    /** Shelly Wall Display X1 ("Stargate", Android 7): sysfs backlight, off = 0, a touch wakes it. */
    data object ShellyStargate : DeviceProfile {
        override val id = "shelly-stargate"
        override val needsDeviceAdmin = false
        override fun screen(context: Context): Screen = TODO("WP5")
        override fun presence(context: Context, scheduler: Scheduler): Presence = TODO("WP5")
    }

    /** Shelly Wall Display X2i ("Jenna", Android 11): `Settings.System` brightness; off = `lockNow()` if admin, else 0 plus a black cover; wakes by wake lock. */
    data object ShellyJenna : DeviceProfile {
        override val id = "shelly-jenna"
        override val needsDeviceAdmin = true
        override fun screen(context: Context): Screen = TODO("WP5")
        override fun presence(context: Context, scheduler: Scheduler): Presence = TODO("WP5")
    }

    /** Any other tablet or phone: `Settings.System` brightness; off = `lockNow()` if admin, else a black cover; may be the HOME app. */
    data object Generic : DeviceProfile {
        override val id = "generic"
        override val needsDeviceAdmin = true
        override fun screen(context: Context): Screen = TODO("WP5")
        override fun presence(context: Context, scheduler: Scheduler): Presence = TODO("WP5")
    }

    companion object {
        /** The profile of this device from `Build.DEVICE`/`MODEL`, unless `Settings.profileOverride` names one. */
        fun detect(): DeviceProfile = TODO("WP5")

        fun byId(id: String): DeviceProfile? = listOf(ShellyStargate, ShellyJenna, Generic).firstOrNull { it.id == id }
    }
}

/** Follows the ambient light to a brightness: [min] at 30 lx or less up to 255 at 500 lx or more, with hysteresis, animated. */
class AutoBrightness(private val lux: Store<Float?>, private val screen: Screen, private val min: Store<Int>) {
    fun start(): Unit = TODO("WP5")
    fun stop(): Unit = TODO("WP5")
}

/** Proximity and ambient light. It is also a [TouchListener]: a touch counts as presence. */
class Presence(context: Context, scheduler: Scheduler) : TouchListener {
    val near: Store<Boolean> = Store(false)
    val lux: Store<Float?> = Store(null)

    override fun onTouch(event: android.view.MotionEvent): Unit = TODO("WP5")
}

sealed interface NightState {
    /** Night mode is off: the screen stays on. */
    data object Day : NightState

    /** Night mode is on and someone is here: the screen is on until [until] (a `Scheduler.now()` time) without a touch or proximity. */
    data class NightAwake(val until: Long) : NightState

    /** Night mode is on and the screen is off. */
    data object NightAsleep : NightState
}

/**
 * Follows `Settings.night` and never flips it itself (no schedule, no sun). While night: off after `Settings.nightIdleMs`
 * without a touch or proximity, woken by either. While day: always on.
 */
class NightMode(settings: Settings, presence: Presence, screen: Screen, scheduler: Scheduler) {
    val state: Store<NightState> = Store(NightState.Day)

    fun wake(): Unit = TODO("WP5")
    fun sleep(): Unit = TODO("WP5")
}

// ---- Android components ---------------------------------------------------------------------------------------------

/** Foreground service: keeps the dashboard in front (checks every 2 s through UsageStatsManager and relaunches it) and owns presence, night mode, auto brightness and the MQTT bridge. */
class KioskService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = TODO("WP5")
}

/** Starts [KioskService] after a reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent): Unit = TODO("WP5")
}

/** Writes the extras of a `dev.hashsome.PROVISION` broadcast (server, dashboard, device, profile, mqtt) into `Settings` and restarts the service. */
class ProvisionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent): Unit = TODO("WP5")
}

/** Device admin with one policy, `force-lock`, so a screen can be truly switched off where the device has no device owner of ours. */
class HashsomeDeviceAdmin : DeviceAdminReceiver()

/** The HOME `activity-alias` (declared disabled in the manifest), for a plain tablet where the app can be the launcher. */
object HomeAlias {
    fun isEnabled(context: Context): Boolean = TODO("WP5")
    fun setEnabled(context: Context, enabled: Boolean): Unit = TODO("WP5")
}

/** The default [ScreenPolicy], [TouchListener] and [PageListener] of the kiosk, assembled by [withKiosk]. */
internal class KioskPlugs(val config: KioskConfig) {
    val screenPolicy: ScreenPolicy get() = TODO("WP5")
    val touchListener: TouchListener get() = TODO("WP5")
    val pageListener: PageListener get() = TODO("WP5")
}

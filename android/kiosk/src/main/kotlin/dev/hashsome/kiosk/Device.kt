package dev.hashsome.kiosk

// The sysfs backlight nodes and the X2i handling (Settings.System brightness, the power-manager sleep) follow ShellyElevate
// (Apache-2.0, see android/NOTICE): DeviceHelper and DeviceModel.

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import dev.hashsome.client.Scheduler
import dev.hashsome.client.Store
import dev.hashsome.ui.DashboardActivity
import java.io.Closeable
import java.io.File
import java.lang.ref.WeakReference

// The panels of the supported devices. What each one can do was measured on the displays (spikes S1 and S2, android/docs).

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

    /**
     * Whether the panel is lit although we switched it off: something else wrote the backlight (the stock Shelly app can). Only the
     * X1's sysfs backlight can say; the others report their screen-on broadcast through [state] and answer false.
     */
    fun litByOthers(): Boolean = false
}

/** A kind of device, and how to drive its screen and its presence sensor. */
sealed interface DeviceProfile {
    val id: String

    /** Whether this profile needs the device-admin permission for its screen-off (`lockNow()`). */
    val needsDeviceAdmin: Boolean

    /** Whether, after a boot, the dashboard waits for the stock Shelly app to finish starting before it takes the front (see [BootGate]). */
    val settlesAtBoot: Boolean get() = false

    fun screen(context: Context): Screen
    fun presence(context: Context, scheduler: Scheduler): Presence

    /** Shelly Wall Display X1 ("Stargate", Android 7): sysfs backlight, off = 0, a touch wakes it. */
    data object ShellyStargate : DeviceProfile {
        override val id = "shelly-stargate"
        override val needsDeviceAdmin = false
        override val settlesAtBoot = true
        override fun screen(context: Context): Screen = PanelScreen(context, sysfs = true, floor = floor)
        override fun presence(context: Context, scheduler: Scheduler): Presence = Presence(context, scheduler)
    }

    /** Shelly Wall Display X2i ("Jenna", Android 11): `Settings.System` brightness; off = `lockNow()` if admin, else 0 plus a black cover; wakes by wake lock. */
    data object ShellyJenna : DeviceProfile {
        override val id = "shelly-jenna"
        override val needsDeviceAdmin = true
        override val settlesAtBoot = true
        override fun screen(context: Context): Screen = PanelScreen(context, sysfs = false, floor = floor)

        // The X2i's proximity sensor reports 1.0 for "near" and 0.0 for "far": the opposite of the X1 (ShellyElevate DeviceModel.JENNA).
        override fun presence(context: Context, scheduler: Scheduler): Presence = Presence(context, scheduler).also { it.invertProximity = true }
    }

    /** Any other tablet or phone: `Settings.System` brightness; off = `lockNow()` if admin, else a black cover; may be the HOME app. */
    data object Generic : DeviceProfile {
        override val id = "generic"
        override val needsDeviceAdmin = true
        override fun screen(context: Context): Screen = PanelScreen(context, sysfs = false, floor = floor)
        override fun presence(context: Context, scheduler: Scheduler): Presence = Presence(context, scheduler)
    }

    companion object {
        /** The profile of this device from `Build.DEVICE`/`MODEL`, unless `Settings.profileOverride` names one. */
        fun detect(): DeviceProfile =
            Settings.profileOverride.value?.let { byId(it) } ?: detect(Build.DEVICE, Build.MODEL, Build.BOARD)

        /** [detect] from the three build strings. The X1 reports `K400_MT6580_32_N` as its device (model `Stargate`); the X2i `Jenna` twice (board `rk3326` on the unit seen). */
        internal fun detect(device: String?, model: String?, board: String?): DeviceProfile = when {
            device?.startsWith("K400_MT6580") == true || model.equals("Stargate", ignoreCase = true) -> ShellyStargate
            device.equals("Jenna", ignoreCase = true) || model.equals("Jenna", ignoreCase = true) || board?.contains("rk3326", ignoreCase = true) == true -> ShellyJenna
            else -> Generic
        }

        fun byId(id: String): DeviceProfile? = listOf(ShellyStargate, ShellyJenna, Generic).firstOrNull { it.id == id }
    }
}

/**
 * One panel for the three profiles. With [sysfs] the backlight node is written directly (the X1: 0 is "off", the panel stays
 * alive and touches still arrive); otherwise `Settings.System` brightness (floored at 10 by the ROM) with `lockNow()` as the
 * real off where the device admin is active, and a black cover over the dashboard where it is not.
 */
internal class PanelScreen(private val context: Context, private val sysfs: Boolean, internal val floor: Int) : Screen, Closeable {
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val node: File? = if (sysfs) SYSFS_NODES.map(::File).firstOrNull { it.canWrite() } else null
    private var level = 255
    private var modeSet = false

    /** Spike S2: the node is writable by the app uid on the X1. If it is not (another ROM), this falls back to `Settings.System`. */
    private val usesSysfs get() = node != null

    override val canTrulyOff: Boolean get() = !usesSysfs && DeviceAdmin.isActive(context)

    override val state: Store<ScreenState> = Store(if (isInteractive()) ScreenState.On else ScreenState.Off)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> state.set(ScreenState.Off)
                Intent.ACTION_SCREEN_ON -> {
                    Cover.hide()
                    state.set(ScreenState.On)
                }
            }
        }
    }

    init {
        context.applicationContext.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) })
        Log.i("screen", "sysfs=${node?.path} floor=$floor interactive=${isInteractive()}")
    }

    override fun brightness(level: Int) {
        // Only off() darkens the panel: a level below the floor is the floor.
        this.level = level.coerceIn(floor, 255)
        // Off stays off: AutoBrightness may still send a level while the panel is dark.
        if (state.value == ScreenState.On) write(this.level)
    }

    override fun off() {
        when {
            usesSysfs -> write(0)
            DeviceAdmin.isActive(context) && DeviceAdmin.lock(context) -> {}
            else -> {
                write(floor)
                Cover.show()
            }
        }
        state.set(ScreenState.Off)
    }

    @Suppress("DEPRECATION")
    override fun wake() {
        Cover.hide()
        state.set(ScreenState.On)
        write(level)
        if (!isInteractive()) {
            // FULL_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP held 3 s: wakes the X2i in 10-45 ms (spike S1c); the dashboard's KEEP_SCREEN_ON takes over.
            val lock = power.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "hashsome:wake")
            lock.acquire(WAKE_LOCK_MS)
        }
        bringToFront(context)
    }

    override fun litByOthers(): Boolean {
        if (node == null || state.value != ScreenState.Off) return false
        return try {
            (node.readText().trim().toIntOrNull() ?: 0) > 0
        } catch (e: Exception) {
            false
        }
    }

    private fun isInteractive(): Boolean = power.isInteractive

    private fun write(level: Int) {
        if (node != null) {
            try {
                node.writeText(level.toString())
            } catch (e: Exception) {
                Log.w("screen", "backlight write failed: ${e.message}")
            }
            return
        }
        try {
            if (!android.provider.Settings.System.canWrite(context)) {
                Log.w("screen", "WRITE_SETTINGS is not granted: appops set ${context.packageName} WRITE_SETTINGS allow")
                return
            }
            val resolver = context.contentResolver
            if (!modeSet) {
                android.provider.Settings.System.putInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                modeSet = true
            }
            android.provider.Settings.System.putInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, level.coerceIn(floor, 255))
        } catch (e: Exception) {
            Log.w("screen", "brightness write failed: ${e.message}")
        }
    }

    override fun close() {
        try {
            context.applicationContext.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    companion object {
        /** `Settings.System` brightness never goes below this on either display (spikes S1e, S2c). */
        const val SETTINGS_FLOOR = 10

        /** The lowest level the X1's backlight node still lights: 0 is off. */
        const val SYSFS_FLOOR = 1
        private const val WAKE_LOCK_MS = 3_000L

        /** The X1's backlight (spike S2), then the nodes ShellyElevate knows. */
        private val SYSFS_NODES = listOf(
            "/sys/class/leds/lcd-backlight/brightness",
            "/sys/devices/platform/leds-mt65xx/leds/lcd-backlight/brightness",
            "/sys/devices/platform/sprd_backlight/backlight/sprd_backlight/brightness",
            "/sys/devices/platform/backlight/backlight/backlight/brightness",
        )

        /** Brings the dashboard to the front (the wake of the X2i; needs the overlay appop on Android 10+ when started from the service). */
        fun bringToFront(context: Context) {
            // After a boot the stock app is still starting: the wake path waits for the gate too.
            if (Kiosk.bootGate?.open == false) return
            // Already the resumed activity (the X1, whose "off" leaves the panel alive): a start would only re-resume the host.
            val top = Kiosk.lastTop
            if (!FrontDecision.shouldLaunch(top.pkg, context.packageName, Kiosk.dashboardResumed, top.cls) { false }) return
            try {
                context.startActivity(dashboardIntent(context, Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            } catch (e: Exception) {
                Log.w("screen", "could not bring the dashboard to the front: ${e.message}")
            }
        }
    }
}

/** The lowest lit level of each profile's panel: 1 on the X1's sysfs backlight, 10 on `Settings.System`. */
internal val DeviceProfile.floor: Int get() = if (this === DeviceProfile.ShellyStargate) PanelScreen.SYSFS_FLOOR else PanelScreen.SETTINGS_FLOOR

/**
 * The panel level (0..255) of [percent] (0..100): the percent spans the panel's own lit range, so 0 % is [floor] (still lit;
 * only sleep and night mode switch the panel off) and 100 % is 255.
 */
internal fun panelLevel(percent: Int, floor: Int): Int = floor + Math.round(percent.coerceIn(0, 100) * (255 - floor) / 100f)

internal fun Screen.panelLevel(percent: Int): Int = panelLevel(percent, (this as? PanelScreen)?.floor ?: 0)

internal fun dashboardIntent(context: Context, extraFlags: Int = 0): Intent =
    Intent(context, DashboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or extraFlags)

/** The device admin of this app: one policy, `force-lock`. */
internal object DeviceAdmin {
    fun component(context: Context) = ComponentName(context, HashsomeDeviceAdmin::class.java)

    private fun manager(context: Context) = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isActive(context: Context): Boolean = manager(context).isAdminActive(component(context))

    /** True when the screen was locked. */
    fun lock(context: Context): Boolean = try {
        manager(context).lockNow()
        true
    } catch (e: SecurityException) {
        Log.w("admin", "lockNow refused: ${e.message}")
        false
    }

    /** A non-test admin cannot be removed from adb, nor can the app be uninstalled while it is active (spike S1f). */
    fun remove(context: Context): Boolean {
        if (!isActive(context)) return false
        manager(context).removeActiveAdmin(component(context))
        return true
    }
}

/** The black view over the dashboard that stands in for "off" where `lockNow()` is not available. A touch on it wakes through [Presence] and is swallowed. */
internal object Cover {
    private var activity: WeakReference<Activity>? = null
    private var view: View? = null
    private var wanted = false

    fun show() {
        wanted = true
        apply()
    }

    fun hide() {
        wanted = false
        apply()
    }

    /** The dashboard is showing in [a]: put the cover over it if one is wanted. */
    fun attach(a: Activity) {
        if (activity?.get() !== a) remove()
        activity = WeakReference(a)
        apply()
    }

    private fun remove() {
        view?.let { (it.parent as? ViewGroup)?.removeView(it) }
        view = null
    }

    private fun apply() {
        val decor = activity?.get()?.window?.decorView as? ViewGroup ?: return
        if (!wanted) return remove()
        if (view?.parent != null) return
        remove()
        val cover = View(decor.context).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            elevation = 10_000f
        }
        decor.addView(cover, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view = cover
    }
}

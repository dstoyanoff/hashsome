package dev.hashsome.kiosk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.MotionEvent
import android.view.WindowManager
import dev.hashsome.client.Scheduler
import dev.hashsome.client.Store
import dev.hashsome.ui.AppExtension
import dev.hashsome.ui.Hashsome
import dev.hashsome.ui.HashsomeApp
import dev.hashsome.ui.PageListener
import dev.hashsome.ui.ScreenPolicy
import dev.hashsome.ui.TouchListener
import java.lang.ref.WeakReference

// What makes a display a kiosk: it stays in front, dims and sleeps on its own terms, and is a Home Assistant device.
// `kiosk` depends on `ui` and implements the plug points `ui` exposes on `HashsomeApp`; `ui` never imports this module.

/** How the settings sheet is opened. (Provisioning also opens it with `am start --es settings true`.) */
sealed interface Gesture {
    /** The status dot pressed and held still for [ms] (5 s): the only gesture, so a page needs a top row with the status dot. */
    data class HoldStatusDot(val ms: Long = 5_000) : Gesture

    companion object {
        /** The gesture before the status dot's hold replaced the five taps (they did not work: the popover's scrim took the taps after the first). */
        @Deprecated("Five taps are gone; the status dot is held instead.", ReplaceWith("Gesture.HoldStatusDot()"))
        val FiveTapsOnStatusDot: Gesture = HoldStatusDot()
    }
}

/**
 * What the consumer decides for the kiosk. These are defaults written into `Settings` on first provision only;
 * afterwards Home Assistant and the settings sheet own them. Night mode is never switched by the app itself.
 */
data class KioskConfig(
    val nightIdleMs: Long = 120_000,
    val autoBrightness: Boolean = true,
    /** Percent of the panel's range. */
    val minBrightness: Int = 8,
    val settingsGesture: Gesture = Gesture.HoldStatusDot(),
    /** Shelly profiles, after a boot: the top activity must not change for this long before the dashboard takes the front (the stock app is still starting). */
    val bootSettleMs: Long = 30_000,
    /** Shelly profiles, after a boot: and at least this long must have passed since boot. */
    val bootMinMs: Long = 90_000,
    /**
     * "Open Shelly app": the safety cap of the pause. Keep-in-front stands down until the floating "Back to dashboard" button is
     * tapped (or the dashboard is resumed again); this ends it if neither happens. Where the overlay permission is missing there is
     * no button and the pause is [Kiosk.FALLBACK_PAUSE_MS] instead.
     */
    val stockAppPauseMs: Long = 7_200_000,
)

/**
 * [this] with the kiosk added as an [AppExtension]. Call it on the `HashsomeApp` in `Application.onCreate`; `ui` has no
 * constructor parameter for it because it must not reference this module.
 */
fun HashsomeApp.withKiosk(config: KioskConfig = KioskConfig()): HashsomeApp = copy(extensions = extensions + KioskExtension(config))

/**
 * What `withKiosk` installs: initialises [Settings] (which becomes `Hashsome.settings`), builds the [KioskState] and the
 * [SettingsSheet] wiring, replaces the plug points with the kiosk's, and starts [KioskService].
 */
class KioskExtension(val config: KioskConfig) : AppExtension {
    override fun install(app: Application, hashsome: Hashsome) {
        // A direct-boot start (the boot receiver is aware) creates the Application while credential-encrypted storage, where
        // Settings lives, is still locked: finish the install when the user unlocks (a no-op on any device that never locks).
        if (userUnlocked(app)) {
            finish(app, hashsome)
            return
        }
        Log.e("kiosk", "storage locked, installing after ACTION_USER_UNLOCKED")
        // Register first, then re-check: an unlock between the first check and the registration would otherwise be missed.
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!done.compareAndSet(false, true)) return
                app.unregisterReceiver(this)
                finish(app, hashsome)
            }
        }
        app.registerReceiver(receiver, android.content.IntentFilter(Intent.ACTION_USER_UNLOCKED))
        if (userUnlocked(app)) receiver.onReceive(app, Intent(Intent.ACTION_USER_UNLOCKED))
    }

    private fun userUnlocked(app: Application): Boolean =
        android.os.Build.VERSION.SDK_INT < 24 || (app.getSystemService(Context.USER_SERVICE) as android.os.UserManager).isUserUnlocked

    private fun finish(app: Application, hashsome: Hashsome) {
        Settings.init(app, config)
        Kiosk.config = config
        Kiosk.appContext = app
        // The kiosk's plug points sit in front of the consumer's, which keep working.
        hashsome.screenPolicy = KioskScreenPolicy(hashsome.screenPolicy)
        hashsome.touchListener = KioskTouchListener(hashsome.touchListener, mainScheduler())
        hashsome.pageListener = KioskPageListener(hashsome.pageListener)
        GridOverlay.install()
        Kiosk.startService(app)
    }
}

/** What the parts of the kiosk share while the process lives: the state Home Assistant sees, and the parts [KioskService] owns while it runs. */
internal object Kiosk {
    var config = KioskConfig()
    var presence: Presence? = null
    var screen: Screen? = null
    var nightMode: NightMode? = null

    val state = KioskState(
        lux = Store(null),
        occupied = Store(false),
        sleeping = Store(false),
        page = Store(""),
        link = Store("Not connected"),
    )

    /** [Reboot.available] of whichever [reboot] is current, as one store the sheet and the bridge can hold. */
    private val rebootAvailable = Store(false)

    /** Whether the button that opens the device's own UI is offered (every profile has one: the Shelly app, or the tablet's settings). */
    private val stockAppOffered = Store(false)

    /** The label of that button for the profile in use (read when the service builds, which a change of profile repeats). */
    @Volatile
    private var hostAppLabel = "Open Shelly app"


    /** [KioskActions.updateState], one store for the whole process: the updater feeds it while the service runs. */
    private val updateStateStore = Store<UpdateState>(UpdateState.Off)

    /** Makes [updateStateStore] follow [u]'s state; returns how to stop. */
    fun followUpdater(u: Updater): () -> Unit {
        updater = u
        val cancel = u.state.listenNow { updateStateStore.set(it) }
        return {
            cancel()
            updater = null
            updateStateStore.set(UpdateState.Off)
        }
    }

    val actions = object : KioskActions {
        override fun wake() {
            nightMode?.wake()
        }

        override fun sleep() {
            nightMode?.sleep()
        }

        override fun reload() {
            // A new connection from Settings.server; the host rebuilds its page on it.
            Hashsome.reconnect()
        }

        override fun restartApp() = restartApp(Kiosk.appContext)

        override fun restartDevice() {
            reboot?.rebootIfPossible() ?: Log.w("kiosk", "reboot is not available")
        }

        override val canOpenStockApp: Store<Boolean> = stockAppOffered

        override val stockAppLabel: String get() = hostAppLabel

        override fun openStockApp() = openStockApp(Kiosk.activity?.get() ?: Kiosk.appContext)

        override val canRestartDevice: Store<Boolean> = rebootAvailable

        override fun checkForUpdate() {
            updater?.check()
        }

        override fun installUpdate() {
            updater?.install()
        }

        override val updateState: Store<UpdateState> = updateStateStore

        override val installedVersion: String
            get() = updater?.installedName ?: "unknown"
    }

    /** The updater of whichever service is running, or null before it runs. */
    @Volatile
    var updater: Updater? = null

    /** Set by [KioskExtension]; what the restart needs to schedule an alarm. */
    @Volatile
    var appContext: Context? = null

    /** The ways to reboot, probed once the service runs (and again when the profile changes). */
    @Volatile
    var reboot: Reboot? = null
    private var rebootWatch: dev.hashsome.client.Cancel? = null

    /** Probes the reboot paths of [profile]; the result lands in [KioskActions.canRestartDevice]. */
    fun probeReboot(context: Context, profile: DeviceProfile) {
        releaseReboot()
        hostAppLabel = profile.hostAppLabel
        stockAppOffered.set(true)
        val r = Reboot(DeviceRebootBackend(context))
        rebootWatch = r.available.listenNow { rebootAvailable.set(it) }
        reboot = r
        r.probe(shelly = profile.settlesAtBoot)
    }

    /** Keep-in-front's pause while the stock app is used. */
    val pause = FrontPause({ android.os.SystemClock.elapsedRealtime() }) { Log.e("front", it) }

    /** The pause where there is no button to end it. */
    const val FALLBACK_PAUSE_MS = 600_000L

    private fun overlayAllowed(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(context)

    /** Launches the device's own UI (the stock Shelly app, or a tablet's settings) and pauses keep-in-front; where there is none nothing is paused. */
    internal fun openStockApp(context: Context?) {
        val intent = if (DeviceProfile.detect() == DeviceProfile.Generic) {
            Intent(android.provider.Settings.ACTION_SETTINGS)
        } else {
            context?.packageManager?.getLaunchIntentForPackage(FrontDecision.STOCK_PACKAGE)
        }
        if (context == null || intent == null) {
            Log.w("kiosk", "there is no stock app to open")
            return
        }
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post { SettingsHost.close() }
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val overlay = overlayAllowed(context)
            if (!overlay) Log.w("kiosk", "no permission to draw over other apps (appops SYSTEM_ALERT_WINDOW), no back button: pausing for ${FALLBACK_PAUSE_MS / 1000} s")
            pause.start(if (overlay) config.stockAppPauseMs else minOf(FALLBACK_PAUSE_MS, config.stockAppPauseMs), withButton = overlay)
        } catch (e: Exception) {
            Log.w("kiosk", "could not open the stock app: ${e.message}")
        }
    }

    /** Stops probing and watching (the service is torn down or rebuilt). */
    fun releaseReboot() {
        rebootWatch?.invoke()
        rebootWatch = null
        reboot?.close()
        reboot = null
    }

    /** Schedules the service's start and ends this process (see [AppRestarter]). */
    internal fun restartApp(context: Context?) {
        val app = context ?: run {
            Log.w("kiosk", "restart: no context")
            return
        }
        AppRestarter(
            alarm = RestartAlarm(app),
            clock = { android.os.SystemClock.elapsedRealtime() },
            finish = { activity?.get()?.finishAffinity() },
            kill = { android.os.Process.killProcess(android.os.Process.myPid()) },
        ).restart()
    }

    /** The dashboard activity that is showing, for the sheet and the cover. */
    var activity: WeakReference<Activity>? = null

    /** Whether the dashboard activity is the resumed one right now (between its onResume and onPause). */
    @Volatile
    var dashboardResumed: Boolean = false

    const val EXTRA_BOOT = "boot"

    /** Holds the dashboard back after a boot start until the stock app has settled; null when there was none (or on a generic profile it is ignored). */
    @Volatile
    var bootGate: BootGate? = null

    /** Installed as a system app (`FLAG_SYSTEM`, also an updated one): the stock app's force-stop hook skips it, so nothing waits for or avoids the stock app's start-up. */
    @Volatile
    var systemApp: Boolean = false
    private var flagsLogged = false

    /** The top activity the keep-in-front last saw, for the wake path. */
    @Volatile
    var lastTop: TopActivity = TopActivity.UNKNOWN

    /** Whether a start arms the boot gate: the boot receiver's, or any inside [minMs] of boot, on a profile that waits for the stock app. */
    internal fun shouldArmBootGate(boot: Boolean, elapsedMs: Long, minMs: Long, settles: Boolean): Boolean =
        settles && (boot || elapsedMs < minMs)

    /** Arms the gate once per process (the profile and the clock decide, see [shouldArmBootGate]). */
    fun armBootGateIfNeeded(context: Context, boot: Boolean) {
        val flags = try {
            context.packageManager.getApplicationInfo(context.packageName, 0).flags
        } catch (e: Exception) {
            context.applicationInfo.flags
        }
        val system = flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        if (system != systemApp || !flagsLogged) Log.e("front", "boot: appflags=0x${Integer.toHexString(flags)} system=$system")
        flagsLogged = true
        systemApp = system
        if (bootGate != null) return
        val elapsed = android.os.SystemClock.elapsedRealtime()
        if (!shouldArmBootGate(boot, elapsed, config.bootMinMs, DeviceProfile.detect().stockKillsAtBoot)) return
        bootGate = BootGate(
            config.bootSettleMs, config.bootMinMs, { android.os.SystemClock.elapsedRealtime() },
            systemApp = systemApp,
        ) { Log.e("front", it) }
    }

    /** Starts the foreground service. Called from `Application.onCreate`, the boot receiver ([boot]) and provisioning. */
    fun startService(context: Context, boot: Boolean = false): Boolean {
        val intent = Intent(context, KioskService::class.java)
        if (boot) intent.putExtra(EXTRA_BOOT, true)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            return true
        } catch (e: Exception) {
            // Android 12+ refuses a foreground service started from the background; the next launch or boot tries again.
            Log.w("kiosk", "could not start the service: ${e.message}")
            return false
        }
    }
}

/** Keeps the screen on while the dashboard shows, and tells the kiosk which activity that is. [next] is the consumer's policy, which also runs. */
internal class KioskScreenPolicy(private val next: ScreenPolicy) : ScreenPolicy {
    override fun attach(activity: Activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        next.attach(activity)
        shown(activity)
    }

    override fun onResume(activity: Activity) {
        next.onResume(activity)
        Kiosk.dashboardResumed = true
        shown(activity)
    }

    override fun onPause(activity: Activity) {
        Kiosk.dashboardResumed = false
        next.onPause(activity)
    }

    private fun shown(activity: Activity) {
        Kiosk.activity = WeakReference(activity)
        Cover.attach(activity)
        GridOverlay.attach(activity)
        // `am start --es settings true` (provisioning). A running singleTask activity only sees it in onNewIntent, so
        // this relies on the dashboard keeping that intent as its own (setIntent).
        val intent = activity.intent
        if (intent?.getStringExtra(EXTRA_SETTINGS) == "true") {
            intent.removeExtra(EXTRA_SETTINGS)
            activity.window.decorView.post { SettingsHost.open(activity) }
        }
    }

    companion object {
        const val EXTRA_SETTINGS = "settings"
    }
}

/**
 * Feeds touches to [Presence], and opens the settings sheet when the status dot is held for 5 s. [next] is the consumer's
 * listener, which also hears everything.
 */
internal class KioskTouchListener(private val next: TouchListener, private val scheduler: Scheduler) : TouchListener {
    /** The finger that put down while the panel was asleep woke it; the rest of that gesture is swallowed too, so no tile under it is pressed. */
    private var wakeGesture = false

    override fun onTouch(event: MotionEvent): Boolean {
        val consumedByNext = next.onTouch(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) wakeGesture = Kiosk.nightMode?.asleep == true
        Kiosk.presence?.onTouch(event)
        val swallow = wakeGesture
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) wakeGesture = false
        return swallow || consumedByNext
    }

    override fun onStatusDotTap() = next.onStatusDotTap()

    override fun onStatusDotHold() {
        next.onStatusDotHold()
        Log.i("touch", "status dot held: opening the settings")
        openSheet()
    }

    override val statusDotHoldMs: Long get() = (Kiosk.config.settingsGesture as? Gesture.HoldStatusDot)?.ms ?: next.statusDotHoldMs

    private fun openSheet() {
        Kiosk.activity?.get()?.let { SettingsHost.open(it) }
    }
}

internal class KioskPageListener(private val next: PageListener) : PageListener {
    override fun onPage(dashboardId: String, path: String) {
        next.onPage(dashboardId, path)
        Kiosk.state.page.set(if (path.isEmpty()) dashboardId else "$dashboardId/$path")
    }
}

/** The HOME `activity-alias` (declared disabled in the manifest), for a plain tablet where the app can be the launcher. */
object HomeAlias {
    /** The `activity-alias` of the manifest. */
    const val COMPONENT = "dev.hashsome.kiosk.HomeActivityAlias"

    private fun component(context: Context) = android.content.ComponentName(context.packageName, COMPONENT)

    fun isEnabled(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(component(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setEnabled(context: Context, enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        context.packageManager.setComponentEnabledSetting(component(context), state, PackageManager.DONT_KILL_APP)
    }
}

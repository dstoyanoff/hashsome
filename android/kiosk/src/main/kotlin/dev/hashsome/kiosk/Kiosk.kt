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
        Settings.init(app, config)
        Kiosk.config = config
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

        override fun rebootIfPossible() {
            // No display gives the app a shell, and nothing offers this to Home Assistant (see MqttBridge).
            Log.w("kiosk", "reboot is not available")
        }
    }

    /** The dashboard activity that is showing, for the sheet and the cover. */
    var activity: WeakReference<Activity>? = null

    /** Whether the dashboard activity is the resumed one right now (between its onResume and onPause). */
    @Volatile
    var dashboardResumed: Boolean = false

    /** Starts the foreground service. Called from `Application.onCreate`, the boot receiver and provisioning. */
    fun startService(context: Context) {
        val intent = Intent(context, KioskService::class.java)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        } catch (e: Exception) {
            // Android 12+ refuses a foreground service started from the background; the next launch or boot tries again.
            Log.w("kiosk", "could not start the service: ${e.message}")
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

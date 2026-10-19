package dev.hashsome.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import dev.hashsome.client.HashsomeClient
import dev.hashsome.client.Scheduler
import dev.hashsome.client.Store
import dev.hashsome.headless.ThemeSchedule

// ---- Plug points ---------------------------------------------------------------------------------------------------
// `ui` never references `kiosk`: it exposes these, with defaults that do nothing, and `kiosk` implements them.

/** Decides what the screen does while a dashboard is showing. */
interface ScreenPolicy {
    /** The dashboard activity was created: set the window flags this policy wants. */
    fun attach(activity: Activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** The dashboard came to the front. */
    fun onResume(activity: Activity) {}

    /** The dashboard left the front. */
    fun onPause(activity: Activity) {}

    /** The default: keep the screen on whenever the dashboard is showing. */
    companion object Default : ScreenPolicy
}

/** Hears every touch on the dashboard (before the views do), and taps on the status dot. */
interface TouchListener {
    /** Called for every touch event; it cannot consume it. */
    fun onTouch(event: MotionEvent) {}

    /** The status dot of the top row was tapped. */
    fun onStatusDotTap() {}

    companion object None : TouchListener
}

/** Hears which page of which dashboard is showing. */
interface PageListener {
    fun onPage(dashboardId: String, path: String) {}

    companion object None : PageListener
}

// ---- The app --------------------------------------------------------------------------------------------------------

/** Everything the consumer decides once, in its `Application`. */
data class HashsomeApp(
    /** When the dark theme is on. */
    val theme: ThemeSchedule,
    val font: FontSource = Fonts.Inter,
    /** How long without a touch before a display goes back to its home page (for dashboards with `idleReturn`). */
    val idleReturnMs: Long = 60_000,
    val overrides: ThemeOverrides? = null,
    val density: Density = Density.Comfortable,
    /** The dashboards compiled into the app. The first is the default one. */
    val dashboards: List<Dashboard> = emptyList(),
    val screenPolicy: ScreenPolicy = ScreenPolicy,
    val touchListener: TouchListener = TouchListener,
    val pageListener: PageListener = PageListener,
)

/**
 * The settings the dashboard host reads. `kiosk`'s `Settings` implements it (and persists it); without `kiosk`,
 * [InMemoryHostSettings] is used. Home Assistant, the settings sheet and provisioning write the same stores.
 */
interface HostSettings {
    /** The runtime's address, e.g. `http://10.1.1.5:3000`; null until provisioned. */
    val server: Store<String?>

    /** The id of the dashboard to show; null shows the first of `HashsomeApp.dashboards`. */
    val dashboard: Store<String?>

    /** Whether the top bar's dashboard switcher shows. */
    val switcherEnabled: Store<Boolean>
    val theme: Store<ThemeChoice>
}

class InMemoryHostSettings(server: String? = null) : HostSettings {
    override val server: Store<String?> = Store(server)
    override val dashboard: Store<String?> = Store(null)
    override val switcherEnabled: Store<Boolean> = Store(true)
    override val theme: Store<ThemeChoice> = Store(ThemeChoice.Auto)
}

/** The singleton the activity and every component reach the app through. */
object Hashsome {
    private var config: HashsomeApp? = null

    /** Settings the host reads; `kiosk` replaces it with its persistent `Settings` during [install]. */
    var settings: HostSettings = InMemoryHostSettings()

    /** Call once from `Application.onCreate`. */
    fun install(app: Application, config: HashsomeApp) {
        this.config = config
        // WP3 creates the scheduler, the client (connected once settings.server is known) and the resolved theme here.
    }

    val app: HashsomeApp get() = config ?: error("Hashsome.install(...) was not called in Application.onCreate")
    val scheduler: Scheduler get() = TODO("WP3")
    val client: HashsomeClient get() = TODO("WP3")

    /** The resolved theme for the current mode; views read it when they draw. */
    val theme: Theme get() = TODO("WP3")

    /** Changes when the mode changes; the host rebuilds the tree on it. */
    val themeChanged: Store<Int> get() = TODO("WP3")
}

/**
 * Shows the dashboard chosen by `Hashsome.settings.dashboard`, immersive: page transitions (a fade and a 24 dp slide),
 * back, idle return, and the forwarding of touches and page changes to the plug points of `HashsomeApp`.
 */
open class DashboardActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // WP3 builds the dashboard host here. Until then the screen is blank.
    }
}

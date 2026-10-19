package dev.hashsome.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import dev.hashsome.client.AndroidScheduler
import dev.hashsome.client.Cancel
import dev.hashsome.client.Client
import dev.hashsome.client.RemoteClient
import dev.hashsome.client.Scheduler
import dev.hashsome.client.ServerAddress
import dev.hashsome.client.Store
import dev.hashsome.headless.Scope
import dev.hashsome.headless.ThemeController
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
    /**
     * Called for every touch event, before the views see it. Return true to consume it: the dashboard then never gets it (a
     * touch that only woke a sleeping panel). The default consumes nothing.
     */
    fun onTouch(event: MotionEvent): Boolean = false

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

/**
 * Something a module outside `ui` (the kiosk) hooks into the app with. `Hashsome.install` runs each one after it has
 * created the client, scheduler and theme, in list order. It may replace [Hashsome.settings] and the plug points.
 */
fun interface AppExtension {
    fun install(app: Application, hashsome: Hashsome)
}

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
    /** Hooks run at the end of [Hashsome.install]; `HashsomeApp.withKiosk` (in `kiosk`) adds one. */
    val extensions: List<AppExtension> = emptyList(),
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
    private var schedulerOrNull: Scheduler? = null
    private var clientOrNull: Client? = null
    private var themeHost: ThemeHost? = null
    private var connected = false

    /** Settings the host reads; `kiosk` replaces it with its persistent `Settings` during [install]. */
    var settings: HostSettings = InMemoryHostSettings()

    /** What the host calls on window and page events; start as `HashsomeApp`'s and may be replaced by an [AppExtension]. */
    var screenPolicy: ScreenPolicy = ScreenPolicy
    var touchListener: TouchListener = TouchListener
    var pageListener: PageListener = PageListener

    /** The application, for what needs resources (icons, fonts) away from any view. */
    internal lateinit var appContext: Context

    /**
     * Call once from `Application.onCreate`. It only records the setup: the scheduler, the client, the fonts and the theme are
     * made when first asked for (by the dashboard activity), so installing is cheap and an [AppExtension] can still replace the
     * settings, which the client's address and the theme choice are read from.
     */
    fun install(app: Application, config: HashsomeApp) {
        this.config = config
        appContext = app
        schedulerOrNull = null
        clientOrNull = null
        themeHost = null
        connected = false
        screenPolicy = config.screenPolicy
        touchListener = config.touchListener
        pageListener = config.pageListener
        for (extension in config.extensions) extension.install(app, this)
    }

    val app: HashsomeApp get() = config ?: error("Hashsome.install(...) was not called in Application.onCreate")

    val scheduler: Scheduler
        get() = schedulerOrNull ?: AndroidScheduler(Handler(Looper.getMainLooper())).also { schedulerOrNull = it }

    /**
     * The connection to the runtime at `settings.server`; a stand-in that answers every watch with a missing entity while no
     * server is provisioned. It connects when the dashboard activity starts ([connect]), not before.
     */
    val client: Client
        get() = clientOrNull ?: run {
            val server = settings.server.value
            (if (server == null) NoClient else RemoteClient(ServerAddress(server), scheduler = scheduler)).also { clientOrNull = it }
        }

    /** The resolved theme for the current mode; views read it when they draw. */
    val theme: Theme get() = host().theme

    /** Changes when the mode changes; the host rebuilds the tree on it. */
    val themeChanged: Store<Int> get() = host().changed

    private fun host(): ThemeHost =
        themeHost ?: ThemeHost(app, settings, client, scheduler, appContext).also { themeHost = it }

    /**
     * Drops the connection and makes a new one from `settings.server` (a changed address, the settings sheet's "reload"). The
     * theme follows the new client and [themeChanged] ticks, so the dashboard host rebuilds its page on it.
     */
    fun reconnect() {
        clientOrNull?.close()
        clientOrNull = null
        connected = false
        themeHost?.rebind(client, scheduler)
        connect()
    }

    /** Opens the link to the runtime, once. */
    internal fun connect() {
        if (connected) return
        connected = true
        client.connect()
    }
}

/** The client of a display with no server yet: nothing is there, and every command fails. */
internal object NoClient : Client {
    override val link: Store<dev.hashsome.client.LinkStatus> = Store(dev.hashsome.client.LinkStatus.Closed)
    override val integrations: Store<Map<String, dev.hashsome.client.ConnectionStatus>> = Store(emptyMap())

    override fun connect() {}

    override fun close() {}

    override fun watch(ref: dev.hashsome.client.EntityRef, fn: (dev.hashsome.client.Snapshot) -> Unit): dev.hashsome.client.Cancel {
        fn(dev.hashsome.client.Snapshot.Missing)
        return {}
    }

    override fun command(
        ref: dev.hashsome.client.EntityRef,
        name: String,
        args: org.json.JSONObject?,
        done: ((dev.hashsome.client.Outcome) -> Unit)?,
    ): dev.hashsome.client.Cancel {
        done?.invoke(dev.hashsome.client.Outcome.Failed("No server"))
        return {}
    }

    override fun <T> query(ref: dev.hashsome.client.EntityRef, query: dev.hashsome.client.Query<T>, done: (Result<T>) -> Unit): dev.hashsome.client.Cancel {
        done(Result.failure(IllegalStateException("No server")))
        return {}
    }
}

/** Resolves the theme for the mode in force, and says when the mode changes: the schedule, or the choice in the settings. */
internal class ThemeHost(
    private val config: HashsomeApp,
    private val settings: HostSettings,
    client: Client,
    scheduler: Scheduler,
    context: Context,
) {
    private var scope = Scope(client, scheduler)
    private var controller = ThemeController(config.theme, scope)
    private var cancelDark: Cancel = controller.dark.listen { refresh() }
    private val fonts: FontSet by lazy { config.font.load(context) }

    /** Counts the mode changes. */
    val changed = Store(0)

    var theme: Theme = resolve(dark())
        private set

    init {
        settings.theme.listen { refresh() }
    }

    /** The client is another one (a reconnect): the schedule follows the new one, and the host rebuilds on it. */
    fun rebind(client: Client, scheduler: Scheduler) {
        cancelDark()
        scope.dispose()
        scope = Scope(client, scheduler)
        controller = ThemeController(config.theme, scope)
        cancelDark = controller.dark.listen { refresh() }
        refresh()
        changed.set(changed.value + 1)
    }

    private fun dark(): Boolean = when (settings.theme.value) {
        ThemeChoice.Dark -> true
        ThemeChoice.Light -> false
        ThemeChoice.Auto -> controller.dark.value
    }

    private fun resolve(dark: Boolean): Theme =
        Theme.resolve(ThemeConfig.Default, if (dark) ThemeMode.Dark else ThemeMode.Light, config.overrides, config.density, fonts)

    private fun refresh() {
        val mode = if (dark()) ThemeMode.Dark else ThemeMode.Light
        if (mode == theme.mode) return
        theme = resolve(dark())
        changed.set(changed.value + 1)
    }
}

/**
 * Shows the dashboard chosen by `Hashsome.settings.dashboard`, immersive: page transitions (a fade and a 24 dp slide),
 * back, idle return, and the forwarding of touches and page changes to the plug points of `HashsomeApp`.
 */
open class DashboardActivity : Activity() {
    private var host: DashboardHost? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        Hashsome.screenPolicy.attach(this)
        Hashsome.connect()
        val dashboard = chosenDashboard()
        if (dashboard != null) {
            val shown = DashboardHost(this, dashboard)
            host = shown
            setContentView(shown.root)
            // After the first frame, with the screen drawn, so it costs nothing at start.
            shown.root.postDelayed({ warmIcons(this) }, 800)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // singleTask: the kiosk reads its extras (`--es settings true`) from the current intent.
        setIntent(intent)
    }

    /** The dashboard `Hashsome.settings.dashboard` names; the first one when it names none that exists. */
    protected open fun chosenDashboard(): Dashboard? {
        val all = Hashsome.app.dashboards
        val id = Hashsome.settings.dashboard.value
        return all.firstOrNull { it.id == id } ?: all.firstOrNull()
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (Hashsome.touchListener.onTouch(ev)) return true
        host?.touched()
        return super.dispatchTouchEvent(ev)
    }

    @Deprecated("The platform's back button")
    override fun onBackPressed() {
        if (host?.back() != true) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        Hashsome.screenPolicy.onResume(this)
    }

    override fun onPause() {
        Hashsome.screenPolicy.onPause(this)
        super.onPause()
    }

    override fun onDestroy() {
        host?.dispose()
        host = null
        super.onDestroy()
    }
}

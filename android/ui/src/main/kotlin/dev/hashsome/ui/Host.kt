package dev.hashsome.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
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

    /** The status dot was pressed and held for [statusDotHoldMs] without moving; the tap (and the health popover) does not follow. */
    fun onStatusDotHold() {}

    /** How long the status dot is held before [onStatusDotHold]. */
    val statusDotHoldMs: Long get() = StatusDotHold.DEFAULT_MS

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
    private var clientOrNull: HostClient? = null
    private var appScopeOrNull: Scope? = null
    private var themeHost: ThemeHost? = null
    private var connected = false

    /** Settings the host reads; `kiosk` replaces it with its persistent `Settings` during [install]. */
    var settings: HostSettings = InMemoryHostSettings()

    /** What the host calls on window and page events; start as `HashsomeApp`'s and may be replaced by an [AppExtension]. */
    var screenPolicy: ScreenPolicy = ScreenPolicy
    var touchListener: TouchListener = TouchListener
    var pageListener: PageListener = PageListener

    /** Where a hold gesture (the status dot's) shows its progress; the activity installs it. */
    internal var holdIndicator: HoldProgressSink? = null

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
        appScopeOrNull?.dispose()
        appScopeOrNull = null
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
     * server is provisioned. It connects when the dashboard activity starts ([connect]), not before. It is one object for the life of
     * the process: [reconnect] changes where it points, never which client it is, so a watch, a store or a listener on it (the status
     * dot, the settings sheet's integrations) keeps working across a reload or a new address.
     */
    val client: Client get() = hostClient()

    private fun hostClient(): HostClient =
        clientOrNull ?: run {
            val server = settings.server.value
            HostClient(NoClient, server?.let { ServerAddress(it) }) { RemoteClient(it, scheduler = scheduler) }.also { clientOrNull = it }
        }

    /**
     * A scope that lives as long as the process, on the same [client]: what a page's scope is not. Put cross-page watches here (a rail
     * item's attention dot that watches entities whichever page is open, a store a dashboard object keeps current); they survive page
     * changes and [reconnect], which keeps the watches and sends their subscriptions again. Do not put what belongs to a page here: a
     * tile's handle, a control, a timer for a view, a drawer's scope. Those are made through the page's own scope (`PageScope.scope`
     * and its children), which is disposed when the page goes, and anything registered here instead is never let go of, so the
     * subscription stays and the callbacks keep the views they close over in memory.
     */
    val appScope: Scope
        get() = appScopeOrNull ?: Scope(client, scheduler).also { appScopeOrNull = it }

    /** The resolved theme for the current mode; views read it when they draw. */
    val theme: Theme get() = host().theme

    /** Changes when the mode changes; the host rebuilds the tree on it. */
    val themeChanged: Store<Int> get() = host().changed

    private fun host(): ThemeHost =
        themeHost ?: ThemeHost(app, settings, client, scheduler, appContext).also { themeHost = it }

    /**
     * Opens the connection again at `settings.server` (a changed address, the settings sheet's "reload"), in place: it is the same
     * [client] with its watches, stores and listeners, which are sent to the runtime again when the link opens; requests in flight
     * fail with "Connection lost". [themeChanged] ticks, so the dashboard host rebuilds its page. Safe to call from any thread: off
     * the main thread it is posted to the scheduler, as every client call runs there.
     */
    fun reconnect() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            scheduler.post { reconnect() }
            return
        }

        connected = true
        hostClient().point(settings.server.value?.let { ServerAddress(it) })
        themeHost?.reloaded()
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

    override fun raw(integration: String, request: org.json.JSONObject, done: ((Result<org.json.JSONObject>) -> Unit)?): dev.hashsome.client.Cancel {
        done?.invoke(Result.failure(IllegalStateException("No server")))
        return {}
    }

    override fun reconnect(server: dev.hashsome.client.ServerAddress?) {}
}

/**
 * The one client of the process: it forwards to [NoClient] or a real one and can be pointed elsewhere without anyone holding it
 * noticing. Watches are kept here, so the first server being provisioned (a stand-in until then) re-sends them to the real client;
 * a real client that is only re-pointed does that itself ([Client.reconnect]).
 */
internal class HostClient(private val none: Client, initial: ServerAddress?, private val make: (ServerAddress) -> Client) : Client {
    private class Watch(val ref: dev.hashsome.client.EntityRef, val fn: (dev.hashsome.client.Snapshot) -> Unit) {
        var cancel: Cancel = {}
    }

    private var inner: Client = initial?.let(make) ?: none
    private var wanted = false
    private val watches = ArrayList<Watch>()
    private var followLink: Cancel = {}
    private var followIntegrations: Cancel = {}

    override val link: Store<dev.hashsome.client.LinkStatus> = Store(inner.link.value)
    override val integrations: Store<Map<String, dev.hashsome.client.ConnectionStatus>> = Store(inner.integrations.value)

    init {
        follow()
    }

    private fun follow() {
        followLink = inner.link.listenNow { link.set(it) }
        followIntegrations = inner.integrations.listenNow { integrations.set(it) }
    }

    private fun swap(next: Client) {
        val old = inner
        followLink()
        followIntegrations()
        inner = next
        follow()
        for (watch in watches.toList()) {
            watch.cancel()
            watch.cancel = next.watch(watch.ref, watch.fn)
        }

        if (wanted) next.connect()
        old.close()
    }

    /**
     * Points the client at [address] and has it connected: a real client is reconnected in place (a new address, or the same one
     * again), the stand-in is replaced by a real one that takes over the watches. Null goes back to the stand-in.
     */
    fun point(address: ServerAddress?) {
        wanted = true
        when {
            address == null -> if (inner !== none) swap(none)
            inner === none -> swap(make(address))
            else -> inner.reconnect(address)
        }
    }

    override fun connect() {
        wanted = true
        inner.connect()
    }

    override fun close() {
        wanted = false
        inner.close()
    }

    override fun reconnect(server: ServerAddress?) {
        // No address: the one it has, which is nothing to do for the stand-in. Either way it is connecting from now on.
        wanted = true
        if (server != null) point(server) else inner.reconnect(null)
    }

    override fun watch(ref: dev.hashsome.client.EntityRef, fn: (dev.hashsome.client.Snapshot) -> Unit): Cancel {
        val watch = Watch(ref, fn)
        watches += watch
        watch.cancel = inner.watch(ref, fn)
        return {
            if (watches.remove(watch)) watch.cancel()
        }
    }

    override fun command(
        ref: dev.hashsome.client.EntityRef,
        name: String,
        args: org.json.JSONObject?,
        done: ((dev.hashsome.client.Outcome) -> Unit)?,
    ): Cancel = inner.command(ref, name, args, done)

    override fun <T> query(ref: dev.hashsome.client.EntityRef, query: dev.hashsome.client.Query<T>, done: (Result<T>) -> Unit): Cancel =
        inner.query(ref, query, done)

    override fun raw(integration: String, request: org.json.JSONObject, done: ((Result<org.json.JSONObject>) -> Unit)?): Cancel =
        inner.raw(integration, request, done)
}

/** Resolves the theme for the mode in force, and says when the mode changes: the schedule, or the choice in the settings. */
internal class ThemeHost(
    private val config: HashsomeApp,
    private val settings: HostSettings,
    client: Client,
    scheduler: Scheduler,
    context: Context,
) {
    private val scope = Scope(client, scheduler)
    private val controller = ThemeController(config.theme, scope)
    private val fonts: FontSet by lazy { config.font.load(context) }

    /** Counts the mode changes. */
    val changed = Store(0)

    var theme: Theme = resolve(dark())
        private set

    init {
        controller.dark.listen { refresh() }
        settings.theme.listen { refresh() }
    }

    /**
     * The connection was reopened: the schedule keeps watching through the same client, so only the host's page needs building again.
     */
    fun reloaded() {
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
    private var switch: DashboardSwitch<DashboardHost>? = null
    private val holdIndicator by lazy { HoldIndicator(this) }

    // `setContentView` empties the content, so the indicator goes back over each dashboard's root.
    private fun attachHoldIndicator() {
        HoldIndicator.attach(findViewById(android.R.id.content), holdIndicator)
        Hashsome.holdIndicator = holdIndicator
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        Hashsome.screenPolicy.attach(this)
        Hashsome.connect()
        // The settings sheet, Home Assistant's select and a PROVISION broadcast all write `settings.dashboard`: whichever writes last
        // switches the dashboard on screen, in place.
        val shown = DashboardSwitch(
            choose = { chosenDashboard() },
            make = { DashboardHost(this, it) },
            drop = { it.dispose() },
            show = { shown ->
                host = shown
                setContentView(shown.root)
                attachHoldIndicator()
                // After the first frame, with the screen drawn, so it costs nothing at start.
                shown.root.postDelayed({ warmIcons(this) }, 800)
            },
        )
        switch = shown
        shown.start(Hashsome.settings.dashboard)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // singleTask: the kiosk reads its extras (`--es settings true`) from the current intent.
        setIntent(intent)
    }

    /** The dashboard `Hashsome.settings.dashboard` names; the first one when it names none that exists. */
    protected open fun chosenDashboard(): Dashboard? {
        return pickDashboard(Hashsome.app.dashboards, Hashsome.settings.dashboard.value) { Log.e("Hashsome", it) }
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        // Whatever shows of the bars (a swipe from an edge brings them back for a moment, empty when the device is pinned) has no background of its own.
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        // Android 11 and later hide the bars through the insets controller; some firmware (the Lenovo tablet's Android 14) ignores the flags above after the screen wakes.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
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
        switch?.start(Hashsome.settings.dashboard)
        Hashsome.screenPolicy.onResume(this)
    }

    override fun onPause() {
        switch?.pause()
        Hashsome.screenPolicy.onPause(this)
        super.onPause()
    }

    override fun onDestroy() {
        if (Hashsome.holdIndicator === holdIndicator) Hashsome.holdIndicator = null
        switch?.stop()
        switch = null
        host = null
        super.onDestroy()
    }
}

/** The dashboard [id] names; the first one when [id] is null, and the first one, reported through [error], when no dashboard has that id. */
internal fun pickDashboard(all: List<Dashboard>, id: String?, error: (String) -> Unit): Dashboard? {
    if (id == null) return all.firstOrNull()
    all.firstOrNull { it.id == id }?.let { return it }
    all.firstOrNull()?.let { error("Unknown dashboard '$id', showing '${it.id}' (known: ${all.joinToString { d -> d.id }})") }
    return all.firstOrNull()
}

/**
 * Keeps one [H] (a dashboard host) for the dashboard [choose] names, and swaps it when the setting changes to another one:
 * the old one is dropped (its overlays, page scope and idle timer with it) before the new one is made and shown at its first page.
 * The same dashboard again is nothing to do.
 */
internal class DashboardSwitch<H>(
    private val choose: () -> Dashboard?,
    private val make: (Dashboard) -> H,
    private val drop: (H) -> Unit,
    private val show: (H) -> Unit,
) {
    private var currentId: String? = null
    private var current: H? = null
    private var cancel: Cancel = {}

    private var following = false

    /** Shows the chosen dashboard, then follows [setting]. Again after [pause]: catches up with what changed meanwhile. */
    fun start(setting: Store<String?>) {
        if (following) return
        following = true
        apply()
        cancel = setting.listen { apply() }
    }

    /** Stops following (the activity left the front: an old one must not rebuild a host or dismiss the new one's overlays). The dashboard stays. */
    fun pause() {
        cancel()
        cancel = {}
        following = false
    }

    fun stop() {
        pause()
        current?.let(drop)
        current = null
        currentId = null
    }

    private fun apply() {
        val next = choose() ?: return
        if (next.id == currentId && current != null) return
        current?.let(drop)
        current = null
        val made = make(next)
        current = made
        currentId = next.id
        show(made)
    }
}

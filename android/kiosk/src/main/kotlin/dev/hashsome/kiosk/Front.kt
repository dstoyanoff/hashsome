package dev.hashsome.kiosk

/** The activity last moved to the foreground, as usage stats report it; both null when unknown (usage access not granted). */
internal data class TopActivity(val pkg: String?, val cls: String?) {
    companion object {
        val UNKNOWN = TopActivity(null, null)
    }
}

/** Whether the dashboard has to be started to be in front. Starting it when it already is only makes the host re-resume (and, on a bad day, rebuild). */
internal object FrontDecision {
    /** The stock Shelly app (X1 and X2i). */
    const val STOCK_PACKAGE = "cloud.shelly.stargate"

    /** Its start-up screen; launching over it makes the stock app shut itself down and take our process with it (X1 boot, 2026-10-19). */
    const val STOCK_SPLASH = "cloud.shelly.stargate.activities.SplashActivity"

    fun isStockSplash(pkg: String?, cls: String?): Boolean =
        pkg == STOCK_PACKAGE && cls != null && (cls == STOCK_SPLASH || cls.endsWith(".SplashActivity"))

    /**
     * [dashboardResumed]: our activity is the resumed one, so nothing is in front of it and no launch is needed. Otherwise [top] (the
     * package of the last activity moved to the foreground, null when unknown) decides, and without it [ownProcessVisible].
     * [topClass] is its activity class: never launch over the stock app's splash, the next tick tries again.
     */
    fun shouldLaunch(
        top: String?,
        ownPackage: String,
        dashboardResumed: Boolean,
        topClass: String? = null,
        ownProcessVisible: () -> Boolean,
    ): Boolean {
        if (dashboardResumed) return false
        if (isStockSplash(top, topClass)) return false
        val front = if (top != null) top == ownPackage else ownProcessVisible()
        return !front
    }
}

/**
 * Holds the dashboard back after a reboot until the stock Shelly app has finished starting: its start-up window ends in a
 * "shutdown completed" that force-stops whatever started inside it. Open when the top activity has not changed (and was not the
 * splash) for [settleMs] and at least [minMs] have passed since boot, whichever is later. Once open it stays open.
 * [clock] is `SystemClock.elapsedRealtime()`, so it counts from boot.
 */
internal class BootGate(
    private val settleMs: Long,
    private val minMs: Long,
    private val clock: () -> Long,
    private val log: (String) -> Unit = {},
) {
    var open = false
        private set
    private var announced = false
    private var key: Pair<String?, String?>? = null
    private var stableSince = 0L

    /** Observes [top] for this tick and says whether the dashboard may be taken to the front now. */
    fun admit(top: TopActivity): Boolean {
        if (open) return true
        val now = clock()
        val current = top.pkg to top.cls
        if (current != key || FrontDecision.isStockSplash(top.pkg, top.cls)) {
            key = current
            stableSince = now
        }
        if (now - stableSince >= settleMs && now >= minMs) {
            open = true
            log("boot: settled, taking the front")
            return true
        }
        if (!announced) {
            announced = true
            log("boot: waiting for the stock app to settle")
        }
        return false
    }
}

/**
 * Keep-in-front stands down for [durationMs] after the user asked for the stock Shelly app, so it can be used. It ends early when
 * the dashboard is resumed again after having been left (it is still resumed right after [start], until the stock app covers it).
 * [clock] is `SystemClock.elapsedRealtime()`.
 */
internal class FrontPause(private val clock: () -> Long, private val log: (String) -> Unit = {}) {
    @Volatile
    private var until = 0L
    @Volatile
    private var left = false

    @Volatile
    private var on = false

    fun start(durationMs: Long) {
        until = clock() + durationMs
        left = false
        on = true
        log("front: paused for ${durationMs / 1000} s (the stock app was opened)")
    }

    /** Whether keep-in-front must hold off now; [dashboardResumed] is observed, so call it every tick. */
    fun active(dashboardResumed: Boolean): Boolean {
        if (!on) return false
        if (!dashboardResumed) left = true
        if (dashboardResumed && left) return end("the dashboard is back")
        if (clock() >= until) return end("the pause is over")
        return true
    }

    private fun end(why: String): Boolean {
        on = false
        log("front: resumed ($why)")
        return false
    }
}

/**
 * The 2 s check: relaunches the dashboard when something else is in front, unless [gate] (a boot start of a Shelly profile) still
 * holds it back. Everything the device provides is passed in, so it is tested with a fake clock and top activity.
 */
internal class FrontKeeper(
    private val ownPackage: String,
    private val clock: () -> Long,
    private val gate: () -> BootGate?,
    private val top: () -> TopActivity,
    private val dashboardResumed: () -> Boolean,
    private val ownProcessVisible: () -> Boolean,
    private val launch: () -> Unit,
    private val log: (String) -> Unit = {},
    private val pause: FrontPause? = null,
) {
    private var lastLaunch = Long.MIN_VALUE / 2

    fun check() {
        val resumed = dashboardResumed()
        if (pause?.active(resumed) == true) return
        val t = if (resumed) TopActivity(ownPackage, null) else top()
        val g = gate()
        if (g != null && !g.admit(t)) return
        if (!FrontDecision.shouldLaunch(t.pkg, ownPackage, resumed, t.cls, ownProcessVisible)) return
        val now = clock()
        if (now - lastLaunch < RELAUNCH_COOLDOWN_MS) return
        lastLaunch = now
        log("top is ${t.pkg ?: "unknown"}, bringing the dashboard back")
        launch()
    }

    companion object {
        const val RELAUNCH_COOLDOWN_MS = 4_000L
    }
}

/**
 * The last activity moved to the foreground, with a cap: a stock Splash that has been the last event for more than [staleMs] is
 * reported without its class, so a Splash that never gets a successor cannot hold the dashboard back for ever.
 */
internal class TopTracker(private val clock: () -> Long, private val staleMs: Long = 180_000) {
    private var last = TopActivity.UNKNOWN
    private var since = 0L

    fun moved(pkg: String?, cls: String?) {
        val next = TopActivity(pkg, cls)
        if (next == last) return
        last = next
        since = clock()
    }

    fun current(): TopActivity =
        if (FrontDecision.isStockSplash(last.pkg, last.cls) && clock() - since > staleMs) TopActivity(last.pkg, null) else last
}

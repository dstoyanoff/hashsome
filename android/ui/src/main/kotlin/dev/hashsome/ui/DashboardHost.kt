package dev.hashsome.ui

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.hashsome.client.Cancel
import dev.hashsome.headless.IdleReturn
import dev.hashsome.headless.Scope

/**
 * What `DashboardActivity` shows for one dashboard: the page that is open, the chrome its shell asks for, the transition
 * between pages, back, and idle return. Rebuilt when the theme's mode changes (every view takes the new colors up).
 */
internal class DashboardHost(private val activity: Activity, private val dashboard: Dashboard) {
    val root = FrameLayout(activity)

    private val shell = ShellScope().also { dashboard.shell(it) }
    private val pages = dashboard.pages
    private val home = pages.indexOfFirst { it.path == "" }.takeIf { it >= 0 } ?: 0
    private var index = -1
    private var scope: Scope? = null
    private var pageView: View? = null
    private var rail: NavRailView? = null
    private val cancels = ArrayList<Cancel>()
    private val idle: IdleReturn? =
        if (dashboard.idleReturn && Hashsome.app.idleReturnMs > 0) IdleReturn(Hashsome.scheduler, Hashsome.app.idleReturnMs) { navigate(home) } else null

    init {
        Animation.level = dashboard.motion
        build(startAt = home)
        cancels += Hashsome.themeChanged.listen { rebuild() }
    }

    /** The window and the chrome in the colors of the theme now, and the page at [startAt]. */
    private fun build(startAt: Int) {
        val bg = Hashsome.theme.palette.bg
        activity.window.setBackgroundDrawable(ColorDrawable(bg))
        root.setBackgroundColor(bg)
        root.removeAllViews()
        rail = shell.rail?.let { railPages ->
            NavRailView(activity, railPages).also { view ->
                view.onSelect = { navigate(it) }
                view.attention = shell.attention.mapNotNull { (path, level) ->
                    railPages.indexOfFirst { it.path == path }.takeIf { it >= 0 }?.let { it to level }
                }.toMap()
            }
        }
        if (pages.isNotEmpty()) show(startAt.coerceIn(0, pages.size - 1), animate = false, side = 0)
        rail?.let { root.addView(it, FrameLayout.LayoutParams(dp(NAV_RAIL), ViewGroup.LayoutParams.MATCH_PARENT)) }
        pageView?.let { Pager.syncRail(rail, pages, index) }
    }

    private fun rebuild() {
        Overlays.dismissAllNow()
        build(startAt = index)
    }

    /** Shows the page at [path]; nothing happens for a path no page has. */
    fun navigate(path: String) {
        val i = pages.indexOfFirst { it.path == path }
        if (i >= 0) navigate(i)
    }

    private fun navigate(i: Int) {
        if (i == index || i !in pages.indices) return
        show(i, animate = true, side = Integer.signum(i - index))
    }

    private fun show(i: Int, animate: Boolean, side: Int) {
        Overlays.dismissAllNow()
        // The page that was showing goes at once, and the new one fades in.
        scope?.let { headless { it.dispose() } }
        pageView?.let { root.removeView(it) }
        val spec = pages[i]
        val pageScope = Scope(Hashsome.client, Hashsome.scheduler)
        scope = pageScope
        val layout = PageLayout(activity)
        if (shell.rail != null) layout.leftInsetDp = NAV_RAIL
        shell.topBarTitle?.let { title ->
            layout.addView(TopBarView(activity, title, shell.switcher), FlexParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(TOP_ROW)))
        }
        val builder = PageScope(activity, pageScope)
        builder.navigator = { navigate(it) }
        builder.begin(layout)
        spec.build(builder)
        pageView = layout
        index = i
        // Under the rail, which stays where it is.
        root.addView(layout, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        Pager.syncRail(rail, pages, i)
        if (animate && Animation.animates) {
            // A later page comes in from the right, an earlier from the left (the web's `AnimatedOutlet`, 24 px).
            layout.alpha = 0f
            layout.translationX = side * dp(PAGE_SLIDE)
            layout.animate().alpha(1f).translationX(0f).setDuration(PAGE_MS).setInterpolator(EASE_OUT).start()
        }
        Hashsome.pageListener.onPage(dashboard.id, spec.path)
        idle?.let { headless { it.arrivedAt(spec.path, i == home) } }
    }

    /** A touch anywhere: the idle countdown starts again. */
    fun touched() {
        idle?.let { headless { it.touched() } }
    }

    /** Back closes the overlay on top, else goes home from another page; false when there is nothing to go back to. */
    fun back(): Boolean {
        if (Overlays.dismissTop()) return true
        if (index != home && pages.isNotEmpty()) {
            navigate(home)
            return true
        }
        return false
    }

    fun dispose() {
        cancels.forEach { it() }
        cancels.clear()
        Overlays.dismissAllNow()
        scope?.let { headless { it.dispose() } }
        scope = null
        idle?.let { headless { it.stop() } }
    }

    private companion object {
        const val PAGE_SLIDE = 24f
    }
}

/** The rail's lit item follows the page open. */
private object Pager {
    fun syncRail(rail: NavRailView?, pages: List<PageSpec>, index: Int) {
        if (rail == null) return
        val i = rail.pages.indexOfFirst { it.path == pages[index].path }
        if (i >= 0) rail.active = i
    }
}

/**
 * Calls into `headless` that have no body yet on a branch that does not have WP2 (`Scope.dispose`, `IdleReturn`): the host
 * runs without them rather than not at all. Removed when WP2 is merged.
 */
private inline fun headless(block: () -> Unit) {
    try {
        block()
    } catch (e: NotImplementedError) {
        // Not written yet.
    }
}

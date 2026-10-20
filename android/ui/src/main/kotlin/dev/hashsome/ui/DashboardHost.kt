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
    private var rebuilding = false
    private var scope: Scope? = null
    private var pageView: View? = null
    private var rail: NavRailView? = null
    private val cancels = ArrayList<Cancel>()

    // What the current chrome listens to (the attention stores), let go of at each rebuild.
    private val chromeCancels = ArrayList<Cancel>()
    private val idle: IdleReturn? =
        if (dashboard.idleReturn && Hashsome.app.idleReturnMs > 0) IdleReturn(Hashsome.scheduler, Hashsome.app.idleReturnMs) { navigate(home) } else null

    init {
        Animation.level = dashboard.motion
        build(startAt = home)
        cancels += Hashsome.themeChanged.listen { rebuild() }
    }

    /** The window and the chrome in the colors of the theme now, and the page at [startAt]. */
    private fun build(startAt: Int, scrollY: Int = 0) {
        val bg = Hashsome.theme.palette.bg
        activity.window.setBackgroundDrawable(ColorDrawable(bg))
        root.setBackgroundColor(bg)
        root.removeAllViews()
        chromeCancels.forEach { it() }
        chromeCancels.clear()
        rail = shell.rail?.let { railPages ->
            NavRailView(activity, railPages).also { view ->
                view.onSelect = { navigate(it) }
                // The dots follow their stores: one that comes or goes redraws the rail.
                val refresh = {
                    view.attention = shell.attention.mapNotNull { (path, store) ->
                        val level = store.value ?: return@mapNotNull null
                        railPages.indexOfFirst { it.path == path }.takeIf { it >= 0 }?.let { it to level }
                    }.toMap()
                }
                shell.attention.values.forEach { chromeCancels += it.listen { _ -> refresh() } }
                refresh()
            }
        }
        if (pages.isNotEmpty()) show(startAt.coerceIn(0, pages.size - 1), animate = false, side = 0)
        rail?.let { root.addView(it, FrameLayout.LayoutParams(dp(NAV_RAIL), ViewGroup.LayoutParams.MATCH_PARENT)) }
        pageView?.let { Pager.syncRail(rail, pages, index) }
        if (scrollY > 0) restoreScroll(scrollY)
    }

    /** The scrolled distance of the page shown: the content's, with a pinned header or without. */
    private fun scrollOf(): Int = when (val view = pageView) {
        is PageFrame -> view.scroll.scrollY
        is PageScroll -> view.scrollY
        else -> 0
    }

    /** Puts a rebuilt page back where it was scrolled to, once it has been laid out (it cannot scroll before it has a size). */
    private fun restoreScroll(y: Int) {
        val scroller = when (val view = pageView) {
            is PageFrame -> view.scroll
            is PageScroll -> view
            else -> return
        }
        scroller.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (scroller.height == 0) return
                scroller.viewTreeObserver.removeOnGlobalLayoutListener(this)
                scroller.scrollTo(0, y)
            }
        })
    }

    private fun rebuild() {
        Overlays.dismissAllNow()
        // The icons were drawn in the colors of the theme that is gone.
        clearIconCache()
        rebuilding = true
        build(startAt = index, scrollY = scrollOf())
        rebuilding = false
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
        scope?.dispose()
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
        builder.drawersExpandable = dashboard.expandableDrawers
        builder.begin(layout)
        spec.build(builder)
        // The page scrolls when its content is taller than the screen.
        // A top row (date, weather, clock, status, or the shell's top bar) stays pinned and only what is under it scrolls.
        // A page is often one column holding everything: its first child is the candidate then.
        var holder: ViewGroup = layout
        var first = if (layout.childCount > 0) layout.getChildAt(0) else null
        if (layout.childCount == 1 && first is ColumnLayout) {
            holder = first
            first = if (holder.childCount > 0) holder.getChildAt(0) else null
        }
        val scroll: View = if (isTopRow(first)) {
            holder.removeView(first)
            PageFrame(activity, first!!, layout, if (shell.rail != null) NAV_RAIL else 0, (holder as? ColumnLayout)?.gap ?: 3, spec.fixed)
        } else {
            PageScroll(activity, layout, spec.fixed)
        }
        pageView = scroll
        index = i
        // Under the rail, which stays where it is.
        root.addView(scroll, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        Pager.syncRail(rail, pages, i)
        if (animate && Animation.animates) {
            // A later page comes in from the right, an earlier from the left (the web's `AnimatedOutlet`, 24 px).
            scroll.alpha = 0f
            scroll.translationX = side * dp(PAGE_SLIDE)
            scroll.animate().alpha(1f).translationX(0f).setDuration(PAGE_MS).setInterpolator(EASE_OUT).start()
        }
        // A rebuild for a new theme shows the same page again: nobody navigated.
        if (!rebuilding) Hashsome.pageListener.onPage(dashboard.id, spec.path)
        idle?.arrivedAt(spec.path, i == home)
    }

    /** A touch anywhere: the idle countdown starts again. */
    fun touched() {
        idle?.touched()
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
        chromeCancels.forEach { it() }
        chromeCancels.clear()
        Overlays.dismissAllNow()
        scope?.dispose()
        scope = null
        idle?.stop()
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

package dev.hashsome.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import dev.hashsome.client.Entity
import dev.hashsome.client.EntityKind
import dev.hashsome.client.EntityRef
import dev.hashsome.headless.EntityHandle
import dev.hashsome.headless.Scope

// The dashboard DSL: a dashboard is a Kotlin object whose pages are built by calling the functions of `PageScope`,
// the way a web dashboard composes components. Entity refs are strings (`"ha:light.bathroom_led"`), as on the web.

/** How much a dashboard animates: page transitions and list arrivals. `Light` is for slow displays. */
enum class Motion { Full, Light, None }

@DslMarker
annotation class HashsomeDsl

/** One page of a dashboard: its address (empty for the home page), its title and icon in the navigation, and how it is built. */
data class PageSpec(val path: String, val title: String, val icon: Icon, val build: PageScope.() -> Unit)

/** A dashboard the top bar's switcher offers: the dashboard, its label and icon. */
data class DashboardOption(val dashboard: Dashboard, val label: String, val icon: Icon)

/**
 * A dashboard. [id] is what `Settings.dashboard` stores and what Home Assistant's select offers. [idleReturn]
 * takes the display back to the home page after `HashsomeApp.idleReturnMs` without a touch.
 */
abstract class Dashboard(val id: String, val motion: Motion = Motion.Full, val idleReturn: Boolean = true) {
    abstract val pages: List<PageSpec>

    /** The chrome around the pages (navigation, top bar). The default has none. */
    open fun shell(s: ShellScope) {}

    protected fun page(path: String, title: String, icon: Icon, build: PageScope.() -> Unit): PageSpec = PageSpec(path, title, icon, build)
}

/** The chrome of a dashboard: what is drawn around its pages. A dashboard owns its whole layout, so this is optional. */
@HashsomeDsl
class ShellScope internal constructor() {
    internal var rail: List<PageSpec>? = null
    internal val attention = LinkedHashMap<String, Attention>()
    internal var topBarTitle: String? = null
    internal var switcher: List<DashboardOption> = emptyList()

    /** The page navigation down the left edge; the pages are padded for it. */
    fun navRail(pages: List<PageSpec>) {
        rail = pages
    }

    /** A dot on the rail item of the page at [path], for something on it that needs a look. */
    fun attention(path: String, level: Attention) {
        attention[path] = level
    }

    /** The top bar (WP4a for the Shelly pages, M2 for the tablets': title, switcher, scenes). */
    fun topBar(title: String, dashboards: List<DashboardOption> = emptyList()) {
        topBarTitle = title
        switcher = dashboards
    }
}

/** Where a page's views are added, in the order the calls are made. */
@HashsomeDsl
class PageScope internal constructor(val context: Context, val scope: Scope) {
    /** How a page asks the host to show another page; set by the host. */
    internal var navigator: (String) -> Unit = {}
    private val stack = ArrayList<ViewGroup>()

    /** Starts building into [root]. */
    internal fun begin(root: ViewGroup) {
        stack.clear()
        stack += root
    }

    private fun flex(grow: Boolean = false, minHeight: Int = 0, marginBottom: Int = 0): FlexParams =
        FlexParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.grow = grow
            it.minHeightDp = minHeight
            it.bottomMargin = dp(marginBottom)
        }

    private fun nest(layout: ViewGroup, params: ViewGroup.LayoutParams, build: PageScope.() -> Unit) {
        stack.last().addView(layout, params)
        stack += layout
        try {
            build()
        } finally {
            stack.removeAt(stack.size - 1)
        }
    }

    // --- Layout ---------------------------------------------------------------------------------------------------

    /** Children top to bottom. [gap] is in spacing units (the web's `gap={3}`), [minHeight] and margins in dp. */
    fun column(
        gap: Int = 0,
        grow: Boolean = false,
        align: Align = Align.Stretch,
        justify: Justify = Justify.Start,
        minHeight: Int = 0,
        marginBottom: Int = 0,
        build: PageScope.() -> Unit,
    ) {
        val layout = ColumnLayout(context).also {
            it.gap = gap
            it.align = align
            it.justify = justify
        }
        nest(layout, flex(grow, minHeight, marginBottom), build)
    }

    fun row(
        gap: Int = 0,
        grow: Boolean = false,
        align: Align = Align.Center,
        justify: Justify = Justify.Start,
        minHeight: Int = 0,
        marginBottom: Int = 0,
        build: PageScope.() -> Unit,
    ) {
        val layout = RowLayout(context).also {
            it.gap = gap
            it.align = align
            it.justify = justify
        }
        nest(layout, flex(grow, minHeight, marginBottom), build)
    }

    fun grid(columns: Int, gap: Int = 3, build: PageScope.() -> Unit) {
        nest(GridLayout(context, columns).also { it.gap = gap }, flex(), build)
    }

    /** A 12-column board; its children are [cell]s. It takes the height that is left of the page. */
    fun board(columns: Int = 12, gap: Int = 3, build: PageScope.() -> Unit) {
        nest(BoardLayout(context, columns).also { it.gap = gap }, flex(grow = true), build)
    }

    /** A cell of the board: [cols] wide, [rows] tall (`RowSpan.Fill` takes what is left). */
    fun cell(cols: Int = 12, rows: RowSpan = RowSpan.Rows(1), build: PageScope.() -> Unit) {
        val layout = ColumnLayout(context).also { it.gap = 3 }
        nest(layout, CellParams(cols, rows), build)
        // In a cell that reaches across rows, or fills, the last card takes what is left of the cell's height.
        val stretches = rows is RowSpan.Fill || (rows is RowSpan.Rows && rows.count > 1)
        if (stretches && layout.childCount > 0) (layout.getChildAt(layout.childCount - 1).layoutParams as? FlexParams)?.grow = true
    }

    fun spacer(grow: Boolean = false) {
        stack.last().addView(View(context), flex(grow = grow))
    }

    /** Adds a view of your own. */
    fun add(view: View) {
        stack.last().addView(view)
    }

    /** Goes to another page of this dashboard by its path. */
    fun navigate(path: String) {
        navigator(path)
    }

    /** A handle on an entity, watched for as long as this page is shown; for views and controls of your own. */
    fun <E : Entity> handle(ref: String?, kind: EntityKind<E>): EntityHandle<E> =
        EntityHandle(scope, ref?.let { EntityRef(it) }, kind)

    // --- Top row ----------------------------------------------------------------------------------------------------

    fun dateChip(): Unit = TODO("WP4a")
    fun weatherChip(ref: String): Unit = TODO("WP4a")
    fun clock(): Unit = TODO("WP4a")
    fun systemStatus(): Unit = TODO("WP4a")

    /** The wall header: [left] is drawn at its left edge, then the date, weather, clock and status. */
    fun header(left: (PageScope.() -> Unit)? = null): Unit = TODO("WP4a")

    // --- Tiles ------------------------------------------------------------------------------------------------------

    fun roomHeader(title: String, icon: Icon, readouts: PageScope.() -> Unit = {}): Unit = TODO("WP4a")

    /** A reading of the sensor [ref], with the battery of [battery] when given; a value outside [safeRange] is flagged. */
    fun sensorReadout(ref: String, battery: String? = null, safeRange: ClosedFloatingPointRange<Double>? = null): Unit = TODO("WP4a")

    fun lightTile(ref: String, label: String): Unit = TODO("WP4a")

    /** A thermostat tile over [handle], which may be `EntityHandle.fixed(HandleState.Unavailable...)` for a room that has none. */
    fun climateTile(handle: EntityHandle<dev.hashsome.client.ClimateEntity>, label: String): Unit = TODO("WP4a")

    /** A switch as a tile, with the watts of the power sensor [power] on its secondary line when given. */
    fun switchTile(ref: String, label: String, power: String? = null): Unit = TODO("WP4a")

    fun taskTile(ref: String, label: String): Unit = TODO("WP4a")

    fun listTabs(labels: List<String>, surfaces: List<PageScope.() -> Unit>): Unit = TODO("WP4a")

    // --- Media ------------------------------------------------------------------------------------------------------

    fun mediaPlayerBar(
        ref: String,
        label: String,
        rows: Int = 2,
        time: Boolean = true,
        shuffle: Boolean = true,
        browse: Boolean = true,
        grouping: Boolean = true,
        onOpen: (() -> Unit)? = null,
    ): Unit = TODO("WP4b")

    /** [speakers] are the players that may be grouped with this one. */
    fun mediaPlayerColumn(
        ref: String,
        label: String,
        overlays: Boolean = true,
        fill: Boolean = true,
        speakers: List<EntityRef> = emptyList(),
    ): Unit = TODO("WP4b")

    fun mediaPlayerFull(ref: String, label: String): Unit = TODO("WP4b")
    fun mediaBrowser(ref: String): Unit = TODO("WP4b")
    fun mediaQueue(ref: String): Unit = TODO("WP4b")
    fun mediaSpeakers(ref: String, speakers: List<EntityRef>): Unit = TODO("WP4b")
}

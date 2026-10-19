package dev.hashsome.ui

import android.content.Context
import android.view.View
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
    fun navRail(pages: List<PageSpec>): Unit = TODO("WP3")

    /** The top bar (WP4a for the Shelly pages, M2 for the tablets': title, switcher, scenes). */
    fun topBar(title: String, dashboards: List<DashboardOption> = emptyList()): Unit = TODO("WP3")
}

/** Where a page's views are added, in the order the calls are made. */
@HashsomeDsl
class PageScope internal constructor(val context: Context, val scope: Scope) {
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
    ): Unit = TODO("WP3")

    fun row(
        gap: Int = 0,
        grow: Boolean = false,
        align: Align = Align.Center,
        justify: Justify = Justify.Start,
        minHeight: Int = 0,
        marginBottom: Int = 0,
        build: PageScope.() -> Unit,
    ): Unit = TODO("WP3")

    fun grid(columns: Int, gap: Int = 3, build: PageScope.() -> Unit): Unit = TODO("WP3")

    /** A 12-column board; its children are [cell]s. */
    fun board(columns: Int = 12, gap: Int = 3, build: PageScope.() -> Unit): Unit = TODO("WP3")

    /** A cell of the board: [cols] wide, [rows] tall (`RowSpan.Fill` takes what is left). */
    fun cell(cols: Int = 12, rows: RowSpan = RowSpan.Rows(1), build: PageScope.() -> Unit): Unit = TODO("WP3")

    fun spacer(grow: Boolean = false): Unit = TODO("WP3")

    /** Adds a view of your own. */
    fun add(view: View): Unit = TODO("WP3")

    /** Goes to another page of this dashboard by its path. */
    fun navigate(path: String): Unit = TODO("WP3")

    /** A handle on an entity, watched for as long as this page is shown; for views and controls of your own. */
    fun <E : Entity> handle(ref: String?, kind: EntityKind<E>): EntityHandle<E> = TODO("WP3")

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

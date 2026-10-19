package dev.hashsome.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import dev.hashsome.client.Entity
import dev.hashsome.client.EntityKind
import dev.hashsome.client.EntityRef
import dev.hashsome.client.Store
import dev.hashsome.headless.ClimateControl
import dev.hashsome.headless.ConnectionHealth
import dev.hashsome.headless.EntityHandle
import dev.hashsome.headless.LightControl
import dev.hashsome.headless.Scope
import dev.hashsome.headless.SensorReading
import dev.hashsome.headless.SwitchControl
import dev.hashsome.headless.WeatherControl

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
    internal val attention = LinkedHashMap<String, Store<Attention?>>()
    internal var topBarTitle: String? = null
    internal var switcher: List<DashboardOption> = emptyList()

    /** The page navigation down the left edge; the pages are padded for it. */
    fun navRail(pages: List<PageSpec>) {
        rail = pages
    }

    /** A dot on the rail item of the page at [path], for something on it that needs a look. */
    fun attention(path: String, level: Attention) {
        attention[path] = Store(level)
    }

    /**
     * A dot on the rail item of the page at [path] that comes and goes with [level] (null: no dot), as the web's `NavItem.attention` does
     * when its dashboard computes it. The dot follows the store for as long as the dashboard is shown.
     */
    fun attention(path: String, level: Store<Attention?>) {
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

    private fun flex(grow: Boolean = false, minHeight: Int = 0, marginBottom: Int = 0, weight: Float? = null): FlexParams =
        FlexParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.grow = grow
            it.weight = weight
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

    /**
     * Children top to bottom. [gap] is in spacing units (the web's `gap={3}`), [minHeight] and margins in dp. [weight] is the web's
     * `flex: <weight> 1 0`: its share of what the siblings leave, whatever its content (two columns of 1 and 1.4 split their row 1 : 1.4).
     */
    fun column(
        gap: Int = 0,
        grow: Boolean = false,
        weight: Float? = null,
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
        nest(layout, flex(grow, minHeight, marginBottom, weight), build)
    }

    fun row(
        gap: Int = 0,
        grow: Boolean = false,
        weight: Float? = null,
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
        nest(layout, flex(grow, minHeight, marginBottom, weight), build)
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
        stack.last().addView(Spacer(context), flex(grow = grow))
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

    private fun <V : View> put(view: V): V {
        stack.last().addView(view)
        return view
    }

    fun dateChip() {
        put(DateChip(context))
    }

    /** The weather now: [ref] is a weather entity (`ha:weather.forecast_home`), or a plain temperature sensor, which gets a fixed sun. */
    fun weatherChip(ref: String) {
        put(WeatherChip(context, WeatherControl(handle(ref, EntityKind.Weather), scope), handle(ref, EntityKind.Sensor)))
    }

    /** The time of day. [format] writes the hours as 24 or 12 (the web's `format="24h"`), or as the device's own setting ([ClockFormat.Auto]). */
    fun clock(format: ClockFormat = ClockFormat.Auto) {
        put(Clock(context, format))
    }

    fun systemStatus() {
        put(SystemStatus(context, ConnectionHealth(scope.client, scope)))
    }

    /**
     * The wall header: [left] is drawn at its left edge, then the date, the weather ([weather], when given), the clock and the status
     * dot at the right, in one row of the top row's height. [marginBottom] is the space below it in dp, like `column`'s (negative takes
     * some back: the web's `mb={-3}` is 3 units = 12 dp, so `marginBottom = -12`; a board puts twice a page gap between its rows, and
     * that leaves the header as far from what is under it as from the top of the page). [clockFormat] is how the clock writes the hours.
     */
    fun header(left: (PageScope.() -> Unit)? = null, weather: String? = null, marginBottom: Int = 0, clockFormat: ClockFormat = ClockFormat.Auto) {
        val params = flex(minHeight = TOP_ROW)
        params.bottomMargin = dp(marginBottom)
        nest(RowLayout(context).also { it.gap = 3; it.align = Align.Center }, params) {
            left?.invoke(this)
            spacer(grow = true)
            dateChip()
            if (weather != null) weatherChip(weather)
            clock(clockFormat)
            systemStatus()
        }
    }

    /**
     * The card the pages put list tabs and players in (the web's `Flex background="surface" radius="card" p={3}`): the surface color,
     * the card radius, 3 units of padding, children top to bottom ([gap] units apart) and clipped to the corners. [grow] takes the
     * space left over in its row or column (and may be squeezed to fit, like `minHeight: 0`); [weight] is its share instead, whatever its
     * content (the web's `flex: 1.4 1 0`: two cards of 1 and 1.4 split their row 1 : 1.4). [fill] makes the card's last child take what
     * is left of the card's own height, and give way to it, so a long list scrolls inside the card instead of pushing it past its space.
     */
    fun surface(grow: Boolean = false, fill: Boolean = false, gap: Int = 0, weight: Float? = null, build: PageScope.() -> Unit) {
        val card = SurfaceView(context).also { it.gap = gap }
        nest(card, flex(grow = grow, weight = weight).also { it.shrink = grow || weight != null }, build)
        if (fill && card.childCount > 0) {
            (card.getChildAt(card.childCount - 1).layoutParams as? FlexParams)?.let {
                it.grow = true
                it.shrink = true
            }
        }
    }

    // --- Tiles ------------------------------------------------------------------------------------------------------

    /** A room's header: [title] and [icon], a hairline, and the [readouts] (`sensorReadout`s) at the right. A header after other content starts a new group, with a space above. */
    fun roomHeader(title: String, icon: Icon, readouts: PageScope.() -> Unit = {}) {
        val header = RoomHeaderView(context, title, icon)
        val parent = stack.last()
        val params = FlexParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (parent.childCount > 0) params.topMargin = Math.round(3 * unitPx())
        parent.addView(header, params)
        stack += header.readouts
        try {
            readouts()
        } finally {
            stack.removeAt(stack.size - 1)
        }
    }

    /**
     * A reading of the sensor [ref], with the battery of [battery] when given; a value outside [safeRange] is flagged (humidity is held to
     * 30..60 when none is given). [icon] is by what the sensor measures when null.
     */
    fun sensorReadout(ref: String, battery: String? = null, safeRange: ClosedFloatingPointRange<Double>? = null, icon: Icon? = null) {
        val reading = SensorReading(handle(ref, EntityKind.Sensor), battery?.let { handle(it, EntityKind.Sensor) }, safeRange)
        put(SensorReadoutView(context, reading, icon))
    }

    /** A light: tap to toggle, drag along the tile to dim. [icon] is a bulb, lit while on, when null. */
    fun lightTile(ref: String, label: String, icon: Icon? = null) {
        put(LightTileView(context, LightControl(handle(ref, EntityKind.Light)), label)).icon = icon
    }

    /** A thermostat tile over [handle], which may be `EntityHandle.fixed(HandleState.Unavailable(null))` for a room that has none. */
    fun climateTile(handle: EntityHandle<dev.hashsome.client.ClimateEntity>, label: String) {
        put(ClimateTileView(context, ClimateControl(handle), label))
    }

    /** A switch as a tile with its [icon], with the watts of the power sensor [power] on its secondary line when given. */
    fun switchTile(ref: String, label: String, icon: Icon, power: String? = null) {
        val control = SwitchControl(handle(ref, EntityKind.Switch), power?.let { handle(it, EntityKind.Sensor) })
        put(SwitchTileView(context, control, label)).icon = icon
    }

    /**
     * A recurring task: tap, then confirm with the day it was done. As on the web the tile holds no task data: [info] says where the
     * task stands and [onComplete] what completing does, both the dashboard's. [ref] is only for the tile's status, as the web's
     * `status`: an entity of any kind (an action, a `binary_sensor`, a switch...) whose being unavailable, missing or still loading
     * disables the tile and says why; null (no ref) is always ready.
     */
    fun taskTile(
        ref: String?,
        label: String,
        onComplete: (doneOn: Long, finish: (String?) -> Unit) -> Unit,
        info: Store<TaskInfo>? = null,
        icon: Icon? = null,
    ) {
        val view = put(TaskTileView(context, ref?.let { EntityStatus.watch(scope, it) }, label))
        if (icon != null) view.icon = icon
        view.infoSource = info
        view.onComplete = onComplete
    }

    /**
     * Tabs over [labels] with one surface each (built when its tab is opened, and let go of when another is). [icons] go before the labels.
     * The surfaces fill what is left of the page's height.
     */
    fun listTabs(labels: List<String>, surfaces: List<PageScope.() -> Unit>, icons: List<Icon?> = emptyList()) {
        val tabs = ListTabsView(context, labels)
        tabs.icons = icons
        val body = ColumnLayout(context).also { it.gap = 3 }
        stack.last().addView(tabs, FlexParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        stack.last().addView(body, flex(grow = true))
        var surface: Scope? = null

        fun open(i: Int) {
            surface?.dispose()
            body.removeAllViews()
            val child = scope.child()
            surface = child
            val sub = PageScope(context, child)
            sub.navigator = navigator
            sub.begin(body)
            surfaces.getOrNull(i)?.invoke(sub)
            if (Animation.animates) {
                // Another list fades in where the last one was.
                body.alpha = 0f
                body.animate().alpha(1f).setDuration(150).setInterpolator(EASE_OUT).start()
            }
        }

        tabs.onSelect = { open(it) }
        open(0)
    }

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

package dev.hashsome.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import dev.hashsome.client.Entity
import dev.hashsome.client.EntityKind
import dev.hashsome.client.EntityRef
import dev.hashsome.client.Store
import dev.hashsome.client.WeatherCondition
import dev.hashsome.headless.ClimateControl
import dev.hashsome.headless.ConnectionHealth
import dev.hashsome.headless.EntityHandle
import dev.hashsome.headless.LightControl
import dev.hashsome.headless.LogbookControl
import dev.hashsome.headless.MediaBrowserControl
import dev.hashsome.headless.MediaPlayerControl
import dev.hashsome.headless.QueueControl
import dev.hashsome.headless.Scope
import dev.hashsome.headless.SensorReading
import dev.hashsome.headless.SpeakersControl
import dev.hashsome.headless.SwitchControl
import dev.hashsome.headless.WeatherControl
import dev.hashsome.ui.icons.Ui

// The dashboard DSL: a dashboard is a Kotlin object whose pages are built by calling the functions of `PageScope`,
// the way a web dashboard composes components. Entity refs are strings (`"ha:light.bathroom_led"`), as on the web.

/** How much a dashboard animates: page transitions and list arrivals. `Light` is for slow displays. */
enum class Motion { Full, Light, None }

@DslMarker
annotation class HashsomeDsl

/**
 * One page of a dashboard: its address (empty for the home page), its title and icon in the navigation, and how it is built. A [fixed]
 * page is laid out in exactly the room the screen gives it and never scrolls: what is taller (a list in a card) scrolls inside its card.
 */
data class PageSpec(val path: String, val title: String, val icon: Icon, val fixed: Boolean = false, val build: PageScope.() -> Unit)

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

    /** A page. [fixed]: laid out in the screen's height and never scrolled as a whole (the web's page with `min-height: 0` and hidden overflow), for a page whose lists scroll in their cards. */
    protected fun page(path: String, title: String, icon: Icon, fixed: Boolean = false, build: PageScope.() -> Unit): PageSpec = PageSpec(path, title, icon, fixed, build)
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
        // What takes the room that is left also gives way when there is less than it would like (the web's `minHeight: 0`).
        nest(layout, flex(grow, minHeight, marginBottom, weight).also { it.shrink = grow || weight != null }, build)
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
        nest(layout, flex(grow, minHeight, marginBottom, weight).also { it.shrink = grow || weight != null }, build)
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

    /**
     * The weather now: [ref] is a weather entity (`ha:weather.forecast_home`), or a plain temperature sensor, which gets a fixed sun. With
     * [drawer] (the web's default) a tap on a weather entity whose source has a forecast opens the forecast in a drawer; `false` keeps the chip
     * as only what it shows.
     */
    fun weatherChip(ref: String, drawer: Boolean = true) {
        val control = WeatherControl(handle(ref, EntityKind.Weather), scope)
        val chip = put(WeatherChip(context, control, handle(ref, EntityKind.Sensor)))
        if (drawer) {
            chip.onOpen = {
                val weather = control.handle.entity
                DrawerHost.open(
                    context,
                    scope,
                    title = weather?.name ?: "Weather",
                    kind = "Weather",
                    icon = UiIcons.weather(weather?.condition ?: WeatherCondition.Unknown),
                ) { ctx -> WeatherForecastBody(context, ctx, EntityRef(ref)) }
            }
        }
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
     * [weatherDrawer] is `weatherChip`'s `drawer`: whether the weather chip opens the forecast.
     */
    fun header(left: (PageScope.() -> Unit)? = null, weather: String? = null, marginBottom: Int = 0, clockFormat: ClockFormat = ClockFormat.Auto, weatherDrawer: Boolean = true) {
        val params = flex(minHeight = TOP_ROW)
        params.bottomMargin = dp(marginBottom)
        nest(RowLayout(context).also { it.gap = 3; it.align = Align.Center }, params) {
            left?.invoke(this)
            spacer(grow = true)
            dateChip()
            if (weather != null) weatherChip(weather, weatherDrawer)
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
     * 30..60 when none is given). [icon] is by what the sensor measures when null. With [drawer] (the web's default) a tap on a reading
     * that is a number opens the sensor's drawer, named [name] (the sensor's own name when null): the reading now and whether it is in its
     * safe range, the battery, and the history chart with the min and max of today, this week and this month; `false` keeps it inline text,
     * e.g. for a presence chip.
     */
    fun sensorReadout(
        ref: String,
        battery: String? = null,
        safeRange: ClosedFloatingPointRange<Double>? = null,
        icon: Icon? = null,
        name: String? = null,
        drawer: Boolean = true,
    ) {
        val reading = SensorReading(handle(ref, EntityKind.Sensor), battery?.let { handle(it, EntityKind.Sensor) }, safeRange)
        val view = put(SensorReadoutView(context, reading, icon))
        if (drawer) {
            view.onOpen = {
                val sensor = reading.handle.entity
                DrawerHost.open(
                    context,
                    scope,
                    title = name ?: sensor?.name ?: ref.substringAfter(':'),
                    kind = SensorDrawerText.kind(sensor),
                    icon = view.shownIcon,
                ) { ctx -> SensorDrawerBody(context, ctx, reading) }
            }
        }
    }

    /**
     * A light: tap to toggle, drag along the tile to dim. [icon] is a bulb, lit while on, when null. [drawer]: holding the tile opens a
     * drawer with the full controls (the power button, brightness with fine steps, the color bars and swatches) and the light's recent
     * activity; off, the tile is only a toggle that dims, and no drawer is built, which is lighter on a small display. [power] and [energy]
     * are the light's power sensor and lifetime energy counter (a Shelly's, say): given, the drawer also charts what it draws, as the web's `energy`.
     */
    fun lightTile(ref: String, label: String, icon: Icon? = null, drawer: Boolean = true, power: String? = null, energy: String? = null) {
        val control = LightControl(handle(ref, EntityKind.Light))
        val view = put(LightTileView(context, control, label))
        view.icon = icon
        if (drawer) {
            view.tile.opensDrawer(context, scope, title = { label }, kind = "Light", icon = { icon ?: Ui.lightbulb }) { drawerContext ->
                val charts = if (power != null || energy != null) {
                    EnergyDrawerBody(
                        context,
                        drawerContext.scope,
                        power?.let { EntityHandle(drawerContext.scope, EntityRef(it), EntityKind.Sensor) },
                        energy?.let { EntityHandle(drawerContext.scope, EntityRef(it), EntityKind.Sensor) },
                        drawerContext.expanded,
                        drawerContext::onExpandedChanged,
                    )
                } else {
                    null
                }
                LightDrawerBody(context, control, drawerContext.scope, LogbookControl(control.handle, drawerContext.scope), charts)
            }
        }
    }

    /** A thermostat tile over [handle], which may be `EntityHandle.fixed(HandleState.Unavailable(null))` for a room that has none. */
    fun climateTile(handle: EntityHandle<dev.hashsome.client.ClimateEntity>, label: String) {
        put(ClimateTileView(context, ClimateControl(handle), label))
    }

    /**
     * A switch as a tile with its [icon], with the watts of the power sensor [power] on its secondary line when given. [energy] is its
     * lifetime energy counter (kWh). [drawer]: holding the tile opens a drawer with what it draws over time and the energy it used today,
     * this week and this month, from [power] and [energy]; a switch with neither has no drawer.
     */
    fun switchTile(ref: String, label: String, icon: Icon, power: String? = null, energy: String? = null, drawer: Boolean = true) {
        val control = SwitchControl(handle(ref, EntityKind.Switch), power?.let { handle(it, EntityKind.Sensor) })
        val view = put(SwitchTileView(context, control, label))
        view.icon = icon
        if (DrawerGuard.energy(power, energy, drawer)) {
            view.tile.opensDrawer(context, scope, title = { label }, kind = "Switch", icon = { icon }) { drawerContext ->
                EnergyDrawerBody(
                    context,
                    drawerContext.scope,
                    power?.let { EntityHandle(drawerContext.scope, EntityRef(it), EntityKind.Sensor) },
                    energy?.let { EntityHandle(drawerContext.scope, EntityRef(it), EntityKind.Sensor) },
                    drawerContext.expanded,
                    drawerContext::onExpandedChanged,
                )
            }
        }
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

    /** Adds [view] to what is being built, taking the room left in its parent when [grow]. */
    private fun place(view: View, grow: Boolean = false) {
        // What takes the room that is left also gives way when there is less than it would like (a column among columns).
        stack.last().addView(view, flex(grow = grow).also { it.shrink = grow })
    }

    private fun player(ref: String) = MediaPlayerControl(handle(ref, EntityKind.MediaPlayer), scope)

    /**
     * Now playing with previous / play-pause / next and a volume key, for a media player. [rows] 1 puts the keys beside the track in one
     * pill, 2 puts them on a row under it, for a narrow space. [time], [shuffle], [browse] and [grouping] leave their parts out when
     * false; [onOpen] runs on the art and the library key, to go to the player's page, and without it the library key opens the
     * library as a panel. [speakers] are the players that may be grouped with this one (empty: any it can be).
     */
    fun mediaPlayerBar(
        ref: String,
        label: String,
        rows: Int = 2,
        time: Boolean = true,
        shuffle: Boolean = true,
        browse: Boolean = true,
        grouping: Boolean = true,
        onOpen: (() -> Unit)? = null,
        speakers: List<EntityRef> = emptyList(),
        expandable: Boolean = false,
    ) {
        val bar = MediaPlayerBarView(context, player(ref), label, PlayerOptions(time, shuffle, browse, grouping, rows))
        bar.onOpen = onOpen
        bar.speakers = speakers
        bar.expandable = expandable
        place(bar)
    }

    /**
     * The player as an upright card for a narrow column: art in a ring that shows (and seeks) the position, title, transport and a
     * volume bar. With [overlays] its library and queue open as panels of their own; [fill] takes all the height the parent gives it.
     * [speakers] are the players that may be grouped with this one. [expandable] gives the panels it opens a button that widens them (the tablets').
     */
    fun mediaPlayerColumn(
        ref: String,
        label: String,
        overlays: Boolean = true,
        fill: Boolean = true,
        speakers: List<EntityRef> = emptyList(),
        expandable: Boolean = false,
    ) {
        val column = MediaPlayerColumnView(context, player(ref), label, overlays, fill)
        column.speakers = speakers
        column.expandable = expandable
        place(column, grow = fill)
    }

    /**
     * The full player: the player on top and, unless [lists] is false, under it its library and queue as tabs. A page that shows them
     * beside the player (as `mediaBrowser` and `mediaQueue` in a card of their own) passes `lists = false`. It takes the height that is left of the page.
     */
    fun mediaPlayerFull(ref: String, label: String, speakers: List<EntityRef> = emptyList(), expandable: Boolean = false, lists: Boolean = true) {
        val full = MediaPlayerFullView(context, player(ref), label)
        full.speakers = speakers
        full.expandable = expandable
        full.lists = lists
        place(full, grow = true)
    }

    /**
     * A player's own library: shelves as tabs, search, and rows to open and play. It takes the height that is left of the page. [search]
     * says how search is offered: a field above the list, or an icon at the end of the row of tabs that becomes the field (the web's `search="icon"`).
     */
    fun mediaBrowser(ref: String, search: SearchMode = SearchMode.Bar) {
        val view = MediaBrowserView(context, MediaBrowserControl(handle(ref, EntityKind.MediaPlayer), scope))
        view.searchMode = search
        place(view, grow = true)
    }

    /** A player's queue: what has played, the track playing, and what comes next, which can be put in another order. */
    fun mediaQueue(ref: String) {
        place(MediaQueueView(context, QueueControl(handle(ref, EntityKind.MediaPlayer), scope)), grow = true)
    }

    /** Who plays together with a player, and the [speakers] that could. */
    fun mediaSpeakers(ref: String, speakers: List<EntityRef>) {
        place(MediaSpeakersView(context, SpeakersControl(handle(ref, EntityKind.MediaPlayer), scope, speakers)), grow = true)
    }
}

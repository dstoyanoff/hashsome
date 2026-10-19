package dev.hashsome.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.hashsome.client.Entity
import dev.hashsome.client.Store
import dev.hashsome.headless.ClimateControl
import dev.hashsome.headless.HandleState
import dev.hashsome.headless.LightControl
import dev.hashsome.headless.SensorReading
import dev.hashsome.headless.SwitchControl
import dev.hashsome.ui.icons.Ui

// The room components: header, readouts and the tiles. Each is a thin view over a headless control that binds its stores while it
// is attached; the tile body is the shared `TileView`.

private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

/** A room's name and icon with a hairline and, at its right, the readouts of its sensors. Add readouts with [addReadout]. */
open class RoomHeaderView(context: Context, val title: String, val icon: Icon) : BoundView(context) {
    /** The row at the right where the readouts go; they are drawn in the muted color of the header. */
    val readouts: ViewGroup = ReadoutsRow(context)

    private val row = RowLayout(context)

    init {
        val muted = theme.palette.textMuted
        row.gap = 3
        row.align = Align.Center
        row.addView(GlyphView(context, icon, 16).also { it.color = muted }, FlexParams(WRAP, WRAP))
        row.addView(labelText(context, title, Role.RoomTitle, muted), FlexParams(WRAP, WRAP))
        row.addView(HairlineView(context), FlexParams(WRAP, WRAP).apply { grow = true })
        row.addView(readouts, FlexParams(WRAP, WRAP))
        addView(row, LayoutParams(MATCH, WRAP, Gravity.CENTER_VERTICAL))
    }

    /** Puts [view] (a [SensorReadoutView], say) among the readouts. */
    fun addReadout(view: View) {
        readouts.addView(view)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (measuredHeight < dp(HEADER)) setMeasuredDimension(measuredWidth, dp(HEADER))
    }

    /** The readouts, 1.5 spaces apart, which take the header's muted color. */
    private class ReadoutsRow(context: Context) : RowLayout(context) {
        override fun onViewAdded(child: View) {
            super.onViewAdded(child)
            (child as? SensorReadoutView)?.muted = true
            if (childCount > 1) (child.layoutParams as? FlexParams)?.leftMargin = Math.round(4.5f * unitPx())
            shareBatteries()
        }

        override fun onViewRemoved(child: View) {
            super.onViewRemoved(child)
            shareBatteries()
        }

        /** Readouts side by side that report the same battery show its warning once, after the last of them. */
        private fun shareBatteries() {
            val readouts = (0 until childCount).mapNotNull { getChildAt(it) as? SensorReadoutView }
            readouts.forEachIndexed { i, readout ->
                val ref = readout.reading.battery?.ref
                readout.showBattery = ref == null || readouts.getOrNull(i + 1)?.reading?.battery?.ref != ref
            }
        }
    }
}

/**
 * A reading with its icon: "21.4 °C". A reading outside its safe range is red with a warning triangle; one that is not
 * there (loading, unavailable, not found) says so, dimmed. A battery that is low is flagged with its glyph (the web shows the battery only in the
 * sensor's panel).
 */
open class SensorReadoutView(context: Context, val reading: SensorReading, icon: Icon? = null) : BoundView(context) {
    /** The glyph in front; by what the sensor measures (a thermometer, a droplet, a gauge) when null. */
    var icon: Icon? = icon
        set(value) {
            field = value
            render()
        }

    /** Drawn in the muted color (the readouts of a room header are). */
    var muted: Boolean = false
        set(value) {
            field = value
            render()
        }

    /** Whether a low battery is flagged by this readout (a room header shows a shared battery once, after the last readout that has it). */
    var showBattery: Boolean = true
        set(value) {
            field = value
            render()
        }

    private val row = RowLayout(context)
    private val glyph = GlyphView(context, null, 16)
    private val value = labelText(context, "", Role.Body)
    private val warning = GlyphView(context, Ui.triangleAlert, 16)
    private val battery = GlyphView(context, Ui.batteryLow, 16)

    init {
        row.gap = 1
        row.align = Align.Center
        row.addView(glyph, FlexParams(WRAP, WRAP))
        row.addView(value, FlexParams(WRAP, WRAP))
        row.addView(warning, FlexParams(WRAP, WRAP))
        row.addView(battery, FlexParams(WRAP, WRAP))
        addView(row, LayoutParams(WRAP, WRAP))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        bind(reading.reading) { render() }
        bind(reading.handle.state) { render() }
    }

    private fun render() {
        val p = theme.palette
        val r = reading.reading.value
        val ready = reading.handle.state.value is HandleState.Ready
        val flagged = ready && !r.inSafeRange
        val tone = if (flagged) p.danger else if (muted) p.textMuted else p.text
        glyph.icon = icon ?: when (reading.handle.entity?.measurement) {
            "temperature" -> Ui.thermometer
            "humidity" -> Ui.droplet
            else -> Ui.gauge
        }
        glyph.color = tone
        value.color = tone
        value.text = r.formatted
        warning.color = tone
        warning.visibility = if (flagged) VISIBLE else GONE
        battery.color = p.danger
        battery.visibility = if (showBattery && r.battery?.low == true) VISIBLE else GONE
        alpha = if (ready) 1f else 0.6f
    }
}

/** The tile every entity tile is drawn on, filling this view. */
private fun BoundView.hostTile(): TileView =
    TileView(context).also { addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }

/** A light: tap toggles it; drag along the tile sets its brightness, the fill following the finger; the level is the second line. */
open class LightTileView(context: Context, val control: LightControl, val label: String) : BoundView(context) {
    val tile: TileView = hostTile()

    /** The glyph; a bulb that is lit while the light is on when null. */
    var icon: Icon? = null
        set(value) {
            field = value
            render()
        }

    private val drag: (Float) -> Unit = { control.drag(roundHalfUp(it * 100.0)) }
    private val commit: (Float) -> Unit = { control.commit(roundHalfUp(it * 100.0)) }

    init {
        tile.title = label
        tile.onClick = { control.toggle() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        bind(control.handle.state) { render() }
        bind(control.on) { render() }
        bind(control.shown) { render() }
    }

    private fun render() {
        val state = control.handle.state.value
        val ready = state is HandleState.Ready
        val light = control.handle.entity
        val dimmable = light?.capabilities?.brightness == true
        val on = control.on.value
        val level = control.shown.value
        tile.disabled = !ready
        tile.icon = icon ?: if (on) Ui.lightbulb else Ui.lightbulbOff
        // The fill first: a tile with one is never solid, and a solid card would flash for a moment otherwise.
        tile.fill = if (dimmable) level / 100f else null
        tile.filled = on
        tile.onDrag = if (dimmable) drag else null
        tile.onCommit = if (dimmable) commit else null
        tile.secondary = StatusLabels.of(state)
            ?: if (tile.dragging) "$level%" else LightTileText.secondary(on, dimmable, if (light?.brightness != null) level else null)
    }
}

/** A switch as a tile: tap flips it, the whole card lit in the accent while it is on, with what its power sensor reports as the second line. */
open class SwitchTileView(context: Context, val control: SwitchControl, val label: String) : BoundView(context) {
    val tile: TileView = hostTile()

    /** The glyph (the dashboard picks it, as on the web); none when null. */
    var icon: Icon? = null
        set(value) {
            field = value
            render()
        }

    init {
        tile.title = label
        tile.onClick = { control.toggle() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        bind(control.handle.state) { render() }
        bind(control.on) { render() }
        bind(control.watts) { render() }
    }

    private fun render() {
        val state = control.handle.state.value
        tile.disabled = state !is HandleState.Ready
        tile.icon = icon
        tile.filled = control.on.value
        tile.secondary = StatusLabels.of(state) ?: control.watts.value?.let { wattsText(it) }
    }
}

/**
 * A thermostat tile. For now it draws only its disabled look: a dimmed tile with the room's temperature, or why there is none
 * (a room with no heater is a fixed `Unavailable` handle). The live tile is the next milestone's.
 */
open class ClimateTileView(context: Context, val control: ClimateControl, val label: String) : BoundView(context) {
    val tile: TileView = hostTile()

    var icon: Icon = Ui.thermometer
        set(value) {
            field = value
            render()
        }

    init {
        tile.title = label
        tile.disabled = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        bind(control.handle.state) { render() }
        bind(control.current) { render() }
    }

    private fun render() {
        tile.icon = icon
        tile.disabled = true
        val current = control.current.value
        val unit = control.handle.entity?.unit ?: "°C"
        tile.secondary = if (current != null) climateReading(current, unit) else StatusLabels.of(control.handle.state.value)
    }
}

/**
 * A recurring task: its name and where it stands ("Due in 2 days", warm when soon, red when overdue). A tap asks whether it is done,
 * with the day it was done on (today, or an earlier one); only a confirmation completes it. Set [info] to what the task knows
 * about itself, and [onComplete] to what completing does. [status] is the state of the thing behind the task, of whatever kind it is
 * (`EntityStatus.watch`): anything but ready shows why instead and disables the tile; null is always ready.
 */
open class TaskTileView(context: Context, val status: Store<HandleState<Entity>>?, val label: String) : BoundView(context) {
    val tile: TileView = hostTile()

    var icon: Icon = Ui.wrench
        set(value) {
            field = value
            render()
        }

    var info: TaskInfo = TaskInfo()
        set(value) {
            field = value
            render()
        }

    /** Where [info] comes from, when it changes (the dashboard works it out); followed while the tile is shown. */
    var infoSource: Store<TaskInfo>? = null

    /**
     * What completing does: called with the day it was done on (epoch ms) when the question is confirmed; call `finish(null)` when it
     * went through (the dialog closes) or `finish(message)` when it did not (the message is shown in the dialog, which stays open).
     * Without one the question can only be answered with an error. `finish` has to be called: the dialog waits for it, and gives up
     * with an error after 15 seconds so that cancelling and the back button work again.
     */
    var onComplete: ((doneOn: Long, finish: (String?) -> Unit) -> Unit)? = null

    private companion object {
        const val COMPLETE_TIMEOUT_MS = 15_000L
    }

    private var now = 0L
    private var dialog: ConfirmDialogView? = null

    init {
        tile.title = label
        tile.onClick = { ask() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status?.let { bind(it) { render() } }
        infoSource?.let { source -> bind(source) { info = it } }
        minuteTicks(::onDetach) {
            now = it
            render()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dialog?.dismissNow()
        dialog = null
    }

    private fun render() {
        val state = status?.value
        val p = theme.palette
        tile.disabled = state != null && state !is HandleState.Ready
        tile.icon = icon
        tile.secondaryTone = when (info.state) {
            TaskState.Ok -> p.textMuted
            TaskState.DueSoon -> p.warm
            TaskState.Overdue -> p.danger
        }
        tile.secondary = state?.let { StatusLabels.of(it) } ?: TaskText.line(info, if (now == 0L) Hashsome.scheduler.wallMs() else now)
    }

    private fun ask() {
        if (dialog != null) return
        val shown = if (now == 0L) Hashsome.scheduler.wallMs() else now
        val stepper = DayStepperView(context, shown)
        val ask = ConfirmDialogView(context)
        ask.title = "Mark “$label” as done?"
        val last = info.lastDoneAt ?: dev.hashsome.client.Iso8601.parse(((status?.value as? HandleState.Ready)?.entity as? dev.hashsome.client.ActionEntity)?.lastTriggered)
        ask.description = last?.let { "Last done ${TaskText.short(it, shown)}." }
        ask.confirmLabel = "Mark as done"
        ask.body = stepper
        ask.onConfirm = { complete(ask, TaskText.doneOn(stepper.daysAgo, Hashsome.scheduler.wallMs())) }
        ask.onDismissed = { dialog = null }
        dialog = ask
        ask.show()
    }

    private fun complete(ask: ConfirmDialogView, doneOn: Long) {
        ask.busy = true
        ask.error = null
        val finish = { error: String? ->
            ask.busy = false
            if (error == null) ask.close() else ask.error = error
        }

        // `finish` must be called, or the dialog stays busy; if it is not within a while, it is called with an error so the dialog can be left.
        var settled = false
        var timeout: dev.hashsome.client.Cancel = {}
        val once = { error: String? ->
            if (!settled) {
                settled = true
                timeout()
                finish(error)
            }
        }
        timeout = Hashsome.scheduler.after(COMPLETE_TIMEOUT_MS) { once("Timed out") }
        val handler = onComplete
        if (handler == null) once("Nothing is set to complete this task") else handler(doneOn, once)
    }
}

/** A day chosen by stepping back from today, one at a time; it cannot go past today. */
open class DayStepperView(context: Context, private val now: Long, val label: String = "Done on") : BoundView(context) {
    /** How many days before today: 0 is today. */
    var daysAgo: Int = 0
        set(value) {
            field = value.coerceAtLeast(0)
            render()
        }

    var onChange: ((Int) -> Unit)? = null

    private val row = RowLayout(context)
    private val previous = RoundButton(context, UiIcons.chevronLeft)
    private val next = RoundButton(context, UiIcons.chevronRight)
    private val day = labelText(context, "", Role.BodyStrong)

    init {
        row.gap = 3
        row.align = Align.Center
        row.addView(labelText(context, label, Role.Secondary, theme.palette.textMuted), FlexParams(WRAP, WRAP).apply { grow = true })
        val days = RowLayout(context).apply {
            gap = 2
            align = Align.Center
        }
        days.addView(previous, FlexParams(WRAP, WRAP))
        val centered = FrameLayout(context).apply { minimumWidth = dp(96) }
        centered.addView(day, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        days.addView(centered, FlexParams(WRAP, WRAP))
        days.addView(next, FlexParams(WRAP, WRAP))
        row.addView(days, FlexParams(WRAP, WRAP))
        addView(row, LayoutParams(MATCH, WRAP))
        previous.onClick = { step(daysAgo + 1) }
        next.onClick = { step(daysAgo - 1) }
        render()
    }

    private fun step(to: Int) {
        daysAgo = to
        onChange?.invoke(daysAgo)
    }

    private fun render() {
        day.text = TaskText.dayWords(daysAgo, now)
        next.isEnabled = daysAgo > 0
    }
}

/**
 * A question that has to be answered before something is done: a card in the middle of the page over a dimmed scrim, with a
 * way out (Cancel, a tap outside, the back button) and a button to go ahead. [body] is more to ask for (a date). Cancelling
 * calls [onCancel]; a page change just removes it.
 */
open class ConfirmDialogView(context: Context) : Overlay(context) {
    /** A line under the title. */
    var description: CharSequence? = null

    var confirmLabel: String = "Confirm"
    var cancelLabel: String = "Cancel"

    /** More to ask for before going ahead, e.g. a [DayStepperView]. */
    var body: View? = null

    /** Something is being done with the answer: both buttons wait. */
    var busy: Boolean = false
        set(value) {
            field = value
            confirm?.ready = !value
            cancel?.ready = !value
        }

    /** What went wrong with the answer, shown under the body. */
    var error: String? = null
        set(value) {
            field = value
            errorLine?.let {
                it.text = value.orEmpty()
                it.visibility = if (value.isNullOrEmpty()) GONE else VISIBLE
            }
        }

    var onConfirm: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    private var card: ColumnLayout? = null
    private var confirm: DialogButton? = null
    private var cancel: DialogButton? = null
    private var errorLine: MarqueeText? = null

    /** Shows it with [body]. */
    fun show() = build(body)

    override fun show(content: View) = build(content)

    private fun build(content: View?) {
        attach()
        val t = theme
        val u = unitPx()
        scrim.setBackgroundColor(withAlpha(t.palette.bg, 0.72f))
        val card = ColumnLayout(context).apply {
            gap = 4
            background = surface(FloatArray(4) { t.radius.card }).also { it.setColor(t.palette.surfaceRaised) }
            elevation = elevationOf(t.shadow.drawer)
            isClickable = true
            val pad = Math.round(5 * u)
            setPadding(pad, pad, pad, pad)
        }
        this.card = card
        val titles = ColumnLayout(context)
        titles.addView(labelText(context, title, Role.Heading), FlexParams(MATCH, WRAP))
        description?.let {
            titles.addView(labelText(context, it, Role.Body, t.palette.textMuted), FlexParams(MATCH, WRAP).apply { topMargin = Math.round(1.5f * u) })
        }
        card.addView(titles, FlexParams(MATCH, WRAP))
        val middle = ColumnLayout(context).apply { gap = 2 }
        if (content != null && content.parent == null) middle.addView(content, FlexParams(MATCH, WRAP))
        val line = labelText(context, "", Role.Secondary, t.palette.danger)
        line.visibility = GONE
        errorLine = line
        middle.addView(line, FlexParams(MATCH, WRAP))
        card.addView(middle, FlexParams(MATCH, WRAP))
        val actions = RowLayout(context).apply {
            gap = 2
            justify = Justify.End
        }
        val no = DialogButton(context, cancelLabel, primary = false) { dismiss() }
        val yes = DialogButton(context, confirmLabel, primary = true) { onConfirm?.invoke() }
        cancel = no
        confirm = yes
        actions.addView(no, FlexParams(WRAP, WRAP))
        actions.addView(yes, FlexParams(WRAP, WRAP))
        card.addView(actions, FlexParams(MATCH, WRAP))
        val screen = resources.displayMetrics.widthPixels
        val width = minOf(dp(420), screen - 2 * Math.round(4 * u))
        addView(card, LayoutParams(width, WRAP, Gravity.CENTER))
        confirm?.ready = !busy
        cancel?.ready = !busy
        errorLine?.let {
            it.text = error.orEmpty()
            it.visibility = if (error.isNullOrEmpty()) GONE else VISIBLE
        }
        if (Animation.animates) {
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(160).setInterpolator(EASE_OUT).start()
            card.alpha = 0f
            card.translationY = dp(8f)
            card.scaleX = 0.96f
            card.scaleY = 0.96f
            card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(EASE_OUT).start()
        }
    }

    /** Backs out: the Cancel button, a tap outside, or the back button. Nothing happens while [busy]. */
    override fun dismiss() {
        if (busy) return
        close()
        onCancel?.invoke()
    }

    /** Closes it without a cancel (the answer went through). */
    fun close() {
        val c = card
        leave(if (c == null) null else { end -> c.animate().alpha(0f).setDuration(170).setInterpolator(EASE_IN).withEndAction(end).start() })
    }
}

/** One of the dialog's pills: 40 dp tall, the surface color, or the accent for the one that goes ahead. */
internal class DialogButton(context: Context, private val label: String, private val primary: Boolean, private val onTap: () -> Unit) : View(context) {
    var ready: Boolean = true
        set(value) {
            field = value
            alpha = if (value) 1f else 0.5f
        }

    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        text.style(theme, Role.Label, 0)
        setMeasuredDimension(Math.ceil((text.measureText(label) + 2 * 5 * unitPx()).toDouble()).toInt(), dp(40))
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = if (primary) t.palette.accent else t.palette.surface
        canvas.drawRoundRect(rect, height / 2f, height / 2f, paint)
        text.style(t, Role.Label, if (primary) t.palette.accentText else t.palette.text)
        canvas.drawTextCentered(label, width / 2f, height / 2f, text, Paint.Align.CENTER)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && ready && contains(event.x, event.y)) onTap()
        return true
    }
}

/**
 * Text tabs with a line under the open one that glides to the next (the web's `ListTabs`): the tabs share the width, and each
 * can have a glyph before its label. The open one is written in the strong body type.
 */
open class ListTabsView(context: Context, val tabs: List<String>) : BoundView(context) {
    /** A glyph before each label, by position. */
    var icons: List<Icon?> = emptyList()
        set(value) {
            field = value
            glyphs = value.map { it?.let { icon -> iconDrawable(icon) } }
            invalidate()
        }

    /** The open tab. */
    var selected: Int = 0
        set(value) {
            if (field == value) return
            field = value
            glide(value)
            invalidate()
        }

    var onSelect: ((Int) -> Unit)? = null

    private var glyphs: List<android.graphics.drawable.Drawable?> = emptyList()
    private val left = Eased(this, 0f, 200)
    private val right = Eased(this, 0f, 200)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var placed = false

    init {
        setWillNotDraw(false)
    }

    private fun tabWidth(): Float = ListTabsMath.tabWidth(width.toFloat(), tabs.size, 5 * unitPx())

    private fun tabLeft(i: Int): Float = ListTabsMath.tabLeft(i, width.toFloat(), tabs.size, 5 * unitPx())

    private var downTab = -1
    private var downX = 0f
    private var downY = 0f

    private fun hit(x: Float): Int = ListTabsMath.hit(x, width.toFloat(), tabs.size, 5 * unitPx())

    private fun glide(i: Int) {
        left.to(tabLeft(i))
        right.to(tabLeft(i) + tabWidth())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(40))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        left.to(tabLeft(selected), false)
        right.to(tabLeft(selected) + tabWidth(), false)
        placed = true
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        val u = unitPx()
        paint.color = t.palette.border
        canvas.drawRect(0f, height - dp(1f), width.toFloat(), height.toFloat(), paint)
        tabs.forEachIndexed { i, label ->
            val open = i == selected
            text.style(t, if (open) Role.BodyStrong else Role.Body, if (open) t.palette.text else t.palette.textMuted)
            val glyph = glyphs.getOrNull(i)
            val textWide = text.measureText(label)
            val iconWide = if (glyph != null) dp(16f) + 2 * u else 0f
            var x = tabLeft(i) + (tabWidth() - (iconWide + textWide)) / 2
            if (glyph != null) {
                glyph.setTint(text.color)
                val s = dp(8)
                val cy = height / 2
                glyph.setBounds(x.toInt(), cy - s, x.toInt() + 2 * s, cy + s)
                glyph.draw(canvas)
                x += iconWide
            }
            canvas.drawTextCentered(label, x, height / 2f, text)
        }
        paint.color = t.palette.accent
        canvas.drawRect(left.value, height - dp(2f), right.value, height.toFloat(), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTab = hit(event.x)
                downX = event.x
                downY = event.y
            }

            // A tab is chosen by a tap: the finger comes up on the tab it went down on, and has not travelled (a scroll starting here is not one).
            MotionEvent.ACTION_UP -> {
                val i = hit(event.x)
                val still = Math.abs(event.x - downX) <= dp(TAP_SLOP) && Math.abs(event.y - downY) <= dp(TAP_SLOP)
                if (i >= 0 && i == downTab && still && i != selected) {
                    selected = i
                    onSelect?.invoke(i)
                }
                downTab = -1
            }

            MotionEvent.ACTION_CANCEL -> downTab = -1
        }
        return true
    }

    private companion object {
        const val TAP_SLOP = 12f
    }
}

/** Where the tabs of a [ListTabsView] are, as pure arithmetic: they share [width] with [gap] between them. */
internal object ListTabsMath {
    fun tabWidth(width: Float, count: Int, gap: Float): Float = (width - (count - 1) * gap) / maxOf(1, count)

    fun tabLeft(i: Int, width: Float, count: Int, gap: Float): Float = i * (tabWidth(width, count, gap) + gap)

    /** The tab at [x], the gap before and after it (half the gap each side) counting as part of it; -1 when none. */
    fun hit(x: Float, width: Float, count: Int, gap: Float): Int = (0 until count).firstOrNull {
        x >= tabLeft(it, width, count, gap) - gap / 2 && x <= tabLeft(it, width, count, gap) + tabWidth(width, count, gap) + gap / 2
    } ?: -1
}

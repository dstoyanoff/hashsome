package dev.hashsome.example

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.hashsome.client.Store
import dev.hashsome.client.WeatherCondition
import dev.hashsome.headless.EntityHandle
import dev.hashsome.headless.HandleState
import dev.hashsome.headless.LightControl
import dev.hashsome.ui.Align
import dev.hashsome.ui.Attention
import dev.hashsome.ui.BarView
import dev.hashsome.ui.ChipsView
import dev.hashsome.ui.ClockFormat
import dev.hashsome.ui.ColumnLayout
import dev.hashsome.ui.Dashboard
import dev.hashsome.ui.FlexParams
import dev.hashsome.ui.FormHeading
import dev.hashsome.ui.FormRow
import dev.hashsome.ui.FormSelect
import dev.hashsome.ui.FormSlider
import dev.hashsome.ui.ToggleView
import dev.hashsome.ui.Hashsome
import dev.hashsome.ui.Justify
import dev.hashsome.ui.LightTileView
import dev.hashsome.ui.MarqueeText
import dev.hashsome.ui.Motion
import dev.hashsome.ui.PageScope
import dev.hashsome.ui.RingView
import dev.hashsome.ui.Role
import dev.hashsome.ui.RoundButton
import dev.hashsome.ui.RowSpan
import dev.hashsome.ui.ShellScope
import dev.hashsome.ui.Skeleton
import dev.hashsome.ui.TaskInfo
import dev.hashsome.ui.TaskState
import dev.hashsome.ui.UiIcons
import dev.hashsome.ui.dp
import dev.hashsome.ui.icons.Ui
import dev.hashsome.ui.iconBitmap
import dev.hashsome.ui.overlayForScreen

/**
 * The M1 components in every state the mock backend can produce, then the base views and the layout DSL: the pages to look at on
 * a device. Run the mock runtime (`pnpm --filter @hashsome/example dev`) and start the app with `--es server <host>:<port>`.
 */
object Demo : Dashboard("demo", motion = Motion.Light, idleReturn = false) {
    override val pages = listOf(
        page("", "Room", Ui.bath) { room() },
        page("tasks", "Tasks", Ui.wrench) { tasks() },
        page("music", "Music", Ui.music) { music() },
        page("more", "Weather", Ui.cloudSun) { weather() },
    )

    /** A dot on the Tasks item that comes and goes, to see the rail follow a store. */
    private val tasksAttention = Store<Attention?>(Attention.Notice)

    init {
        val handler = Handler(Looper.getMainLooper())
        val cycle = listOf<Attention?>(Attention.Notice, Attention.Urgent, null)
        var at = 0
        handler.postDelayed(object : Runnable {
            override fun run() {
                at = (at + 1) % cycle.size
                tasksAttention.set(cycle[at])
                handler.postDelayed(this, 5000)
            }
        }, 5000)
    }

    override fun shell(s: ShellScope) {
        s.navRail(pages)
        s.attention("tasks", tasksAttention)
        s.attention("more", Attention.Notice)
    }

    private fun PageScope.text(content: String, role: Role = Role.Body, muted: Boolean = false): MarqueeText =
        MarqueeText(context).also {
            it.text = content
            it.role = role
            if (muted) it.color = Hashsome.theme.palette.textMuted
        }

    private fun PageScope.room() {
        column(gap = 3) {
            header(weather = "ha:weather.home")
            roomHeader("Living room", Ui.house) {
                sensorReadout("ha:sensor.living_room_temperature", battery = "ha:sensor.living_room_temperature_battery")
                // 64% is outside the 30..60 a humidity sensor is held to.
                sensorReadout("ha:sensor.living_room_humidity", battery = "ha:sensor.living_room_temperature_battery")
            }
            grid(2) {
                lightTile("ha:light.living_room_lamp", "On, no dimming")
                lightTile("ha:light.living_room_wall", "Dimmable, 78%")
                lightTile("ha:light.living_room_accent", "Colour, off")
                lightTile("ha:light.porch_lamp", "Unavailable")
                lightTile("ha:light.does_not_exist", "Missing")
                add(LightTileView(context, LightControl(EntityHandle.fixed(HandleState.Loading)), "Loading"))
                switchTile("ha:lock.front_door", "Switch, on", Ui.house, power = "ha:sensor.living_room_lamp_power")
                switchTile("ha:switch.kitchen_coffee_maker", "Switch, off", Ui.house)
            }
            roomHeader("Sensors", Ui.thermometer) {
                sensorReadout("ha:sensor.master_bedroom_temperature")
                sensorReadout("ha:sensor.does_not_exist")
            }
            climateTile(EntityHandle.fixed(HandleState.Unavailable(null)), "Heater, none here")
            // Taller than the screen: the page scrolls.
            roomHeader("Below the fold", Ui.lightbulb)
            grid(2) {
                lightTile("ha:light.kitchen_ceiling", "Kitchen ceiling")
                lightTile("ha:light.porch_ambient", "Porch ambient")
            }
        }
    }

    private fun PageScope.tasks() {
        column(gap = 3) {
            header(left = { add(text("Tasks", Role.Title)) }, clockFormat = ClockFormat.H24)
            val now = System.currentTimeMillis()
            val day = 86_400_000L
            // What the dashboard does with the day it was done on; the demo only says it went through.
            val done: (Long, (String?) -> Unit) -> Unit = { _, finish -> finish(null) }
            taskTile("ha:scene.movie_night", "Up to date", onComplete = done, info = Store(TaskInfo(TaskState.Ok, now + 30 * day, now - 5 * day)))
            taskTile("ha:scene.movie_night", "Due soon", onComplete = done, info = Store(TaskInfo(TaskState.DueSoon, now + 2 * day, now - 20 * day)))
            taskTile("ha:scene.movie_night", "Overdue", onComplete = done, info = Store(TaskInfo(TaskState.Overdue, now - 3 * day, now - 40 * day)))
            taskTile("ha:scene.does_not_exist", "Missing", onComplete = done)
            // The status of a sensor-kind entity (the mock's temperature sensor, as a binary_sensor is): ready, so not disabled.
            taskTile("ha:sensor.living_room_temperature", "Over a sensor", onComplete = done, info = Store(TaskInfo(TaskState.Ok, now + 9 * day, now - 5 * day)))
            // The card the pages put lists and players in.
            surface(gap = 2) {
                add(text("A surface", Role.BodyStrong))
                add(text("Surface color, card radius, 3 units of padding"))
            }
            listTabs(
                listOf("Library", "Queue"),
                listOf({ add(text("The library surface")) }, { add(text("The queue surface")) }),
                icons = listOf(Ui.library, Ui.listMusic),
            )
        }
    }

    private fun PageScope.music() {
        column(gap = 3, grow = true) {
            row(gap = 3, minHeight = 40) {
                add(text("Library", Role.Title))
                spacer(grow = true)
                add(RoundButton(context, Ui.library).also { b ->
                    b.onClick = { openPanel(b, "Library", "Bathroom speaker") }
                })
                add(RoundButton(context, Ui.listMusic).also { b ->
                    b.onClick = { openPanel(b, "Queue", null) }
                })
            }
            add(ChipsView(context).also { it.labels = listOf("Playlists", "Albums", "Artists", "Radio") })
            repeat(5) {
                add(Skeleton(context), 56)
            }
            spacer(grow = true)
        }
    }

    private fun PageScope.weather() {
        column(gap = 3, grow = true) {
            add(text("Top row pieces: date, weather (entity, sensor, missing), clock, status", Role.Secondary, muted = true))
            row(gap = 3) {
                dateChip()
                weatherChip("ha:weather.home")
                weatherChip("ha:sensor.temperature")
                weatherChip("ha:weather.does_not_exist")
            }
            row(gap = 3) {
                clock()
                systemStatus()
            }
            add(text("Weather glyphs", Role.Title))
            add(WeatherStrip(context, night = false))
            add(WeatherStrip(context, night = true))
            add(text("Row of round buttons: pending, failed, active, primary", Role.Secondary, muted = true))
            row(gap = 3) {
                add(RoundButton(context, UiIcons.play).also { it.primary = true })
                add(RoundButton(context, UiIcons.volume).also { it.pending = true })
                add(RoundButton(context, UiIcons.volumeMuted).also { it.failed = true })
                add(RoundButton(context, UiIcons.shuffle).also { it.active = true })
                add(RoundButton(context, UiIcons.close))
            }
            add(text("Form controls: heading, toggle, dropdown, slider", Role.Secondary, muted = true))
            add(FormHeading(context, "Screen"), 40)
            add(FormRow(context, "Auto brightness").control(ToggleView(context).also { it.checked = true }), 52)
            add(FormRow(context, "Dashboard").control(FormSelect(context).also { it.options = listOf("demo", "media"); it.selected = "demo" }, 200), 52)
            add(FormRow(context, "Brightness").control(FormSlider(context).also { it.value = 60 }, 200), 52)
            add(PlayerCard(context), 150)
            spacer(grow = true)
        }
    }

    private fun PageScope.add(view: View, height: Int) {
        add(view)
        view.layoutParams = FlexParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height))
    }

    private fun openPanel(from: View, title: String, subtitle: String?) {
        val context = from.context
        val overlay = overlayForScreen(context)
        overlay.title = title
        overlay.subtitle = subtitle
        overlay.titleIcon = Ui.library
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        repeat(12) {
            list.addView(Skeleton(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(4) })
        }
        overlay.show(ScrollView(context).apply { addView(list) })
    }
}

/** The player of the room page, from base views: the artwork ring, a seek bar, transport and a volume. */
private class PlayerCard(context: Context) : FrameLayout(context) {
    private val ring = RingView(context)
    private val bar = BarView(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val handler = Handler(Looper.getMainLooper())
    private var playing = true
    private val tick = object : Runnable {
        override fun run() {
            if (playing) ring.fraction = (ring.fraction + 0.01f) % 1f
            handler.postDelayed(this, 250)
        }
    }

    init {
        setWillNotDraw(false)
        val column = ColumnLayout(context).apply {
            gap = 3
            align = Align.Center
            justify = Justify.Center
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
        }
        ring.seekable = true
        ring.fraction = 0.3f
        ring.onSeekPreview = { ring.fraction = it }
        column.addView(ring, FlexParams(dp(84), dp(84)))
        column.addView(MarqueeText(context).also { it.text = "Bathroom speaker, a title long enough to roll across"; it.role = Role.Heading }, FlexParams(-1, -2))
        bar.fraction = 0.45f
        bar.onDrag = { bar.fraction = it }
        bar.onCommit = { bar.fraction = it }
        column.addView(bar, FlexParams(-1, dp(10)).apply { align = Align.Stretch })
        val transport = dev.hashsome.ui.RowLayout(context).apply { gap = 3 }
        val play = RoundButton(context, UiIcons.pause).also { it.primary = true }
        play.onClick = {
            playing = !playing
            play.icon = if (playing) UiIcons.pause else UiIcons.play
        }
        transport.addView(RoundButton(context, UiIcons.skipPrevious), FlexParams(-2, -2))
        transport.addView(play, FlexParams(-2, -2))
        transport.addView(RoundButton(context, UiIcons.skipNext), FlexParams(-2, -2))
        column.addView(transport, FlexParams(-2, -2))
        addView(column, LayoutParams(-1, -1))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.postDelayed(tick, 250)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(tick)
    }

    override fun onDraw(canvas: Canvas) {
        val t = Hashsome.theme
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = t.palette.surface
        canvas.drawRoundRect(rect, dp(t.radius.card), dp(t.radius.card), paint)
    }
}

/** Every sky condition as the weather chip draws it: a bitmap of the glyph. */
private class WeatherStrip(context: Context, private val night: Boolean) : View(context) {
    private val conditions = WeatherCondition.entries

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(40))
    }

    override fun onDraw(canvas: Canvas) {
        val color = Hashsome.theme.palette.text
        val size = dp(28)
        val step = width / conditions.size.toFloat()
        conditions.forEachIndexed { i, c ->
            val icon = UiIcons.weather(c, night)
            canvas.drawBitmap(iconBitmap(icon, size, color), i * step + (step - size) / 2, (height - size) / 2f, null)
        }
    }
}

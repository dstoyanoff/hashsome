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
import dev.hashsome.client.WeatherCondition
import dev.hashsome.ui.Align
import dev.hashsome.ui.Attention
import dev.hashsome.ui.BarView
import dev.hashsome.ui.ChipsView
import dev.hashsome.ui.ColumnLayout
import dev.hashsome.ui.Dashboard
import dev.hashsome.ui.FlexParams
import dev.hashsome.ui.Hashsome
import dev.hashsome.ui.Justify
import dev.hashsome.ui.MarqueeText
import dev.hashsome.ui.Motion
import dev.hashsome.ui.PageScope
import dev.hashsome.ui.RingView
import dev.hashsome.ui.Role
import dev.hashsome.ui.RoundButton
import dev.hashsome.ui.RowSpan
import dev.hashsome.ui.ShellScope
import dev.hashsome.ui.Skeleton
import dev.hashsome.ui.StatusDot
import dev.hashsome.ui.TileView
import dev.hashsome.ui.UiIcons
import dev.hashsome.ui.dp
import dev.hashsome.ui.icons.Ui
import dev.hashsome.ui.iconBitmap
import dev.hashsome.ui.overlayForScreen

/**
 * A dashboard built only from the layout DSL and the base views (no entity components), to see the host, the rail, the page
 * transition, the board with a cell that fills, the overlays and the weather glyphs on a device.
 */
object Demo : Dashboard("demo", motion = Motion.Light, idleReturn = false) {
    override val pages = listOf(
        page("", "Room", Ui.bath) { room() },
        page("music", "Music", Ui.music) { music() },
        page("more", "Weather", Ui.cloudSun) { weather() },
    )

    override fun shell(s: ShellScope) {
        s.navRail(pages)
        s.attention("more", Attention.Notice)
    }

    private fun PageScope.text(content: String, role: Role = Role.Body, muted: Boolean = false): MarqueeText =
        MarqueeText(context).also {
            it.text = content
            it.role = role
            if (muted) it.color = Hashsome.theme.palette.textMuted
        }

    private fun PageScope.tile(label: String, secondary: String?, fill: Float?, icon: dev.hashsome.ui.Icon = Ui.lightbulb): TileView =
        TileView(context).also { tile ->
            tile.title = label
            tile.icon = icon
            tile.secondary = secondary
            tile.fill = fill
            var on = fill != null && fill > 0f
            var level = fill ?: 1f
            tile.filled = on
            tile.onClick = {
                on = !on
                tile.filled = on
                if (fill != null) tile.fill = if (on) level else 0f
                tile.secondary = if (on) (if (fill != null) "${(level * 100).toInt()}%" else "On") else "Off"
            }
            if (fill != null) {
                tile.onDrag = {
                    level = it
                    tile.fill = it
                    tile.filled = true
                    tile.secondary = "${(it * 100).toInt()}%"
                }
                tile.onCommit = { on = true }
            }
            tile.onHold = { tile.secondary = "Held" }
        }

    private fun PageScope.room() {
        // A wide display puts the player beside the room, a narrow one under it (which is what fills the page's height).
        val wide = context.resources.displayMetrics.widthPixels / dp(1f) >= 700
        board {
            cell(cols = if (wide) 7 else 12) {
                row(gap = 3, minHeight = 40) {
                    add(text("Bathroom", Role.Title))
                    spacer(grow = true)
                    add(text("21.5 C  45%", Role.Body, muted = true))
                    add(StatusDot(context))
                }
                grid(2) {
                    add(tile("Ambient", "60%", 0.6f))
                    add(tile("Lamp", "Off", 0f))
                    add(tile("Mirror", "On", null))
                    add(tile("Fan", "Off", null, Ui.wrench))
                }
                add(TileView(context).also {
                    it.title = "Heater"
                    it.icon = Ui.thermometer
                    it.secondary = "Unavailable"
                    it.disabled = true
                })
            }
            cell(cols = if (wide) 5 else 12, rows = RowSpan.Fill) {
                add(PlayerCard(context))
            }
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

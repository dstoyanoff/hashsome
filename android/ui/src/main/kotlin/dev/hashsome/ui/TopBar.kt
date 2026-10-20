package dev.hashsome.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import dev.hashsome.client.AssetSize
import dev.hashsome.client.Cancel
import dev.hashsome.headless.ActionControl
import dev.hashsome.headless.PersonControl
import dev.hashsome.ui.icons.Ui

// The tablets' top bar (the web's `TopBar` of `top-bar.tsx`): the dashboard's name, which opens a menu of the other dashboards, round scene
// buttons, and at the right the date, the weather, anything of the dashboard's own, who is home, the clock and the status dot.

/** A scene button of the top bar: the automation or scene [entity] it runs, its [icon], and the [color] of its dot (the theme's accent when null). */
data class TopBarScene(val entity: String, val icon: Icon? = null, val color: Int? = null)

/** What `ShellScope.topBar` was given. */
internal class TopBarSpec(
    val title: String,
    val dashboards: List<DashboardOption>,
    val scenes: List<TopBarScene>,
    val weather: String?,
    val people: List<String>,
    val clockFormat: ClockFormat,
    val showDate: Boolean,
    val extra: (PageScope.() -> Unit)?,
)

/** The row the top bar is: it is what the page pins to its top (see `isTopRow`). */
internal class TopBarRow(context: Context) : RowLayout(context) {
    init {
        gap = 3
        align = Align.Center
    }
}

/**
 * The dashboard's name. With [options] to switch to (two or more, and the display's switcher on) it is a button, with the current
 * dashboard's glyph, a chevron after the name, and a menu under it; otherwise it is the name alone.
 */
internal class DashboardTitle(
    context: Context,
    private val title: String,
    private val options: List<DashboardOption>,
    private val currentId: String?,
) : RowLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var menu: OptionMenu? = null
    private val switchable = options.size > 1 && Hashsome.settings.switcherEnabled.value

    init {
        align = Align.Center
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        if (switchable) {
            gap = 2
            setWillNotDraw(false)
            isClickable = true
            options.firstOrNull { it.dashboard.id == currentId }?.let { addView(GlyphView(context, it.icon, 20), FlexParams(wrap, wrap)) }
            addView(labelText(context, title, Role.Title), FlexParams(wrap, wrap))
            addView(GlyphView(context, Ui.chevronDown, 20), FlexParams(wrap, wrap))
            setOnClickListener { open() }
        } else {
            addView(labelText(context, title, Role.Title), FlexParams(wrap, wrap))
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (switchable) {
            val pad = Math.round(4 * unitPx())
            if (paddingLeft != pad) setPadding(pad, 0, pad, 0)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // The button is as tall as the row of the top bar, as the web's is.
        if (switchable && measuredHeight < dp(TOP_ROW)) setMeasuredDimension(measuredWidth, dp(TOP_ROW))
    }

    override fun onDraw(canvas: Canvas) {
        if (!switchable) return
        val t = theme
        val r = dp(t.radius.chrome)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.style = Paint.Style.FILL
        paint.color = t.palette.surface
        canvas.drawRoundRect(rect, r, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = t.palette.border
        rect.inset(dp(0.5f), dp(0.5f))
        canvas.drawRoundRect(rect, r, r, paint)
    }

    private fun open() {
        if (menu != null) return
        val items = options.map { MenuItem(it.label, it.icon) }
        menu = OptionMenu(context, items, options.indexOfFirst { it.dashboard.id == currentId }.takeIf { it >= 0 }) { chosen ->
            // Settings.dashboard has three writers; the host shows whichever wrote last, in place.
            Hashsome.settings.dashboard.set(options[chosen].dashboard.id)
        }.also {
            it.onDismissed = { menu = null }
            it.showFrom(this)
        }
    }
}

/** One entry of an [OptionMenu]: its [label], optionally a glyph before it and a [detail] (a muted line after the label: what it is doing). */
data class MenuItem(val label: String, val icon: Icon? = null, val detail: String? = null)

/**
 * A short list that opens under (or, with [alignEnd], ending at) the view it is shown from: one row for each of [items], [selected] marked
 * and not to be chosen again. [onChoose] gets the index of the row tapped. It closes on a tap anywhere else. The dashboard switcher of the
 * top bar is one; so is a dashboard's own picker (a security mode, say).
 */
class OptionMenu(
    context: Context,
    private val items: List<MenuItem>,
    private val selected: Int?,
    private val alignEnd: Boolean = false,
    private val onChoose: (Int) -> Unit,
) : Overlay(context) {
    private var card: View? = null
    private var anchor: View? = null

    init {
        scrim.setBackgroundColor(0)
    }

    override fun show(content: View) {
        attach()
        val host = parent as? View ?: return
        val anchor = this.anchor ?: return
        val at = IntArray(2)
        val origin = IntArray(2)
        anchor.getLocationInWindow(at)
        host.getLocationInWindow(origin)
        val params = LayoutParams((content as? OptionMenuCard)?.cardWidth ?: ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or if (alignEnd) Gravity.END else Gravity.START)
        params.topMargin = at[1] - origin[1] + anchor.height + Math.round(2 * unitPx())
        if (alignEnd) params.rightMargin = (origin[0] + host.width) - (at[0] + anchor.width) else params.leftMargin = at[0] - origin[0]
        card = content
        addView(content, params)
        if (Animation.animates) {
            content.alpha = 0f
            content.animate().alpha(1f).setDuration(150).setInterpolator(EASE_OUT).start()
        }
    }

    /** Opens the menu under [anchor], as wide as it at least. */
    fun showFrom(anchor: View) {
        this.anchor = anchor
        show(OptionMenuCard(context, items, selected, anchor.width) {
            dismiss()
            onChoose(it)
        })
    }

    override fun dismiss() {
        val c = card
        leave(if (c == null) null else { end -> c.animate().alpha(0f).setDuration(120).setInterpolator(EASE_IN).withEndAction(end).start() })
    }
}

/** The menu's card: raised, rounded, a row of the height of the top bar for each item. */
internal class OptionMenuCard(
    context: Context,
    items: List<MenuItem>,
    selected: Int?,
    minWidth: Int,
    onChoose: (Int) -> Unit,
) : BoundView(context) {
    /** How wide the card is, in px: what its rows need, and at least the button it opens from. */
    var cardWidth: Int = ViewGroup.LayoutParams.WRAP_CONTENT
        private set

    init {
        val t = theme
        background = Overlay.surface(FloatArray(4) { t.radius.row }).also { it.setColor(t.palette.surfaceRaised) }
        elevation = Overlay.elevationOf(t.shadow.drawer)
        isClickable = true
        minimumWidth = minWidth
        val column = ColumnLayout(context)
        column.gap = 0
        val pad = Math.round(1.5f * unitPx())
        column.setPadding(pad, pad, pad, pad)
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        addView(column, LayoutParams(wrap, wrap))
        // A column that wraps its content cannot stretch its rows to the widest of them, so the card is as wide as that, written down: the padding,
        // the glyph, the label and the room for the check, in each row.
        val u = unitPx()
        val measure = TextPaint(Paint.ANTI_ALIAS_FLAG).style(t, Role.Body, 0)
        val secondary = TextPaint(Paint.ANTI_ALIAS_FLAG).style(t, Role.Secondary, 0)
        val widest = items.maxOfOrNull { measure.measureText(it.label) + (it.detail?.let { d -> secondary.measureText(d) + 2 * u } ?: 0f) } ?: 0f
        val width = Math.max(minWidth, Math.ceil((widest + 2 * dp(16f) + 2 * 2 * u + 2 * 3 * u + 2 * pad).toDouble()).toInt())
        cardWidth = width
        items.forEachIndexed { index, item ->
            val active = index == selected
            val row = MenuRow(context, item, active)
            if (!active) row.setOnClickListener { onChoose(index) }
            column.addView(row, FlexParams(ViewGroup.LayoutParams.MATCH_PARENT, wrap))
        }
    }
}

private class MenuRow(context: Context, item: MenuItem, private val active: Boolean) : RowLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        gap = 2
        align = Align.Center
        setWillNotDraw(false)
        isClickable = !active
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        addView(GlyphView(context, item.icon, 16), FlexParams(wrap, wrap))
        addView(labelText(context, item.label, Role.Body), FlexParams(wrap, wrap).apply { grow = true })
        item.detail?.let { addView(labelText(context, it, Role.Secondary, Hashsome.theme.palette.textMuted), FlexParams(wrap, wrap)) }
        // Room for the check on every row, so the menu does not change width with the current dashboard.
        addView(GlyphView(context, if (active) Ui.check else null, 16), FlexParams(wrap, wrap))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val pad = Math.round(3 * unitPx())
        if (paddingLeft != pad) setPadding(pad, 0, pad, 0)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (measuredHeight < dp(TOP_ROW)) setMeasuredDimension(measuredWidth, dp(TOP_ROW))
    }

    override fun onDraw(canvas: Canvas) {
        if (!active) return
        val t = theme
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = t.palette.surface
        canvas.drawRoundRect(rect, dp(t.radius.small), dp(t.radius.small), paint)
    }
}

/**
 * A round button that runs a scene (an automation or a scene entity). Pressed, it is answered at once for [DONE_MS] whatever the
 * scene does: it pops, turns green and draws a check in place of its glyph, and cannot be pressed again until that is over.
 */
internal class SceneDot(context: Context, private val control: ActionControl, private val icon: Icon, private val tint: Int?) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var done = false
    private var cancelDone: Cancel? = null

    init {
        isClickable = true
        setOnClickListener {
            if (done) return@setOnClickListener
            done = true
            control.trigger()
            invalidate()
            if (Animation.animates) animate().scaleX(1.18f).scaleY(1.18f).setDuration(200).withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(200).start() }.start()
            cancelDone = Hashsome.scheduler.after(DONE_MS) {
                done = false
                cancelDone = null
                invalidate()
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelDone?.invoke()
        cancelDone = null
        done = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(TOP_ROW), dp(TOP_ROW))
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        paint.color = if (done) t.palette.success else (tint ?: t.palette.accent)
        canvas.drawCircle(width / 2f, height / 2f, width / 2f, paint)
        val size = dp(18)
        val shown = if (done) Ui.check else icon
        canvas.drawIcon(iconBitmap(shown, size, t.palette.accentText), (width - size) / 2f, (height - size) / 2f)
    }

    companion object {
        const val DONE_MS = 1500L
    }
}

/** One avatar of the stack: the person's picture, or their initials on a color of their own; dimmed when they are away. */
internal class AvatarView(context: Context, private val control: PersonControl, private val color: Int) : BoundView(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private var bitmap: Bitmap? = null
    private var shader: BitmapShader? = null
    private var shown: PersonControl.State = control.state.value
    private var loading: Cancel? = null
    private var loadedFrom: String? = null

    init {
        setWillNotDraw(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        bind(control.state) { update(it) }
        onDetach { loading?.invoke(); loading = null }
    }

    private fun update(state: PersonControl.State) {
        shown = state
        contentDescription = state.label
        val url = state.pictureUrl
        if (url != loadedFrom) {
            loading?.invoke()
            loadedFrom = url
            bitmap = null
            shader = null
            if (url != null) {
                loading = Artwork.load(url, AssetSize.Small, dp(SIZE)) { loaded ->
                    bitmap = loaded
                    shader = loaded?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
                    invalidate()
                }
            }
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(SIZE), dp(SIZE))
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        val r = width / 2f
        val picture = shader
        val source = bitmap
        if (picture != null && source != null) {
            // Cover the circle: scale the picture's short side to it and center the rest.
            val scale = Math.max(width / source.width.toFloat(), height / source.height.toFloat())
            matrix.setScale(scale, scale)
            matrix.postTranslate((width - source.width * scale) / 2f, (height - source.height * scale) / 2f)
            picture.setLocalMatrix(matrix)
            paint.shader = picture
            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(r, r, r, paint)
            paint.shader = null
        } else {
            paint.color = 0xFF000000.toInt() or color
            canvas.drawCircle(r, r, r, paint)
            val initials = PersonControl.initials(shown.name)
            text.style(t, Role.BodyStrong, t.palette.accentText)
            canvas.drawTextCentered(initials, r, r, text, Paint.Align.CENTER)
        }
        // Someone out reads as inactive: faded by a light overlay of the surface on their own color, not by opacity (a see-through avatar
        // would show the one it overlaps).
        if (!shown.active) {
            paint.color = withAlpha(t.palette.surface, 0.6f)
            canvas.drawCircle(r, r, r, paint)
        }
    }

    companion object {
        const val SIZE = 34
    }
}

/** Overlapping avatars, one for each person, the first in front (the web's `PresenceStack`). */
internal class PeopleStack(context: Context, controls: List<PersonControl>, colors: List<Int> = PersonControl.DEFAULT_COLORS) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        val overlap = Math.round(2.5f * unitPx())
        // The first avatar's pull to the left is made up here, so the stack starts where it is put.
        setPadding(overlap, 0, 0, 0)
        controls.forEach { control ->
            val color = colors[PersonControl.colorIndex(control.handle.ref?.value ?: "", colors.size)]
            val avatar = AvatarView(context, control, color)
            addView(avatar, LayoutParams(dp(AvatarView.SIZE), dp(AvatarView.SIZE)).also { it.leftMargin = -overlap })
        }
    }
}

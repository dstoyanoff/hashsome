package dev.hashsome.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import dev.hashsome.ui.icons.Ui

/** Narrower than this many dp (a phone, an X1), a panel is a bottom sheet instead of a panel at the side. */
const val SHEET_BELOW_DP = 500

private const val COLLAPSED_WIDTH = 380
private const val EDGE_INSET = 20
private const val SHEET_HEIGHT = 0.92f
private const val ENTER_MS = 200L
private const val RISE_MS = 250L
private const val EXIT_MS = 250L
private const val DISMISS_DISTANCE = 100f
private const val DISMISS_VELOCITY = 0.5f

/** Whether letting go of a pulled sheet dismisses it: far enough or fast enough (the web's `useSheetPull`; dp and dp per ms). */
internal object SheetPull {
    fun dismisses(offsetDp: Float, speedDpPerMs: Float): Boolean = offsetDp > DISMISS_DISTANCE || speedDpPerMs > DISMISS_VELOCITY
}

/** Lets the first of several calls through: an overlay is removed once however many things try (an exit animation and a rebuild). */
internal class Once {
    private var done = false

    fun run(fn: () -> Unit): Boolean {
        if (done) return false
        done = true
        fn()
        return true
    }

    fun reset() {
        done = false
    }
}

/** The overlays open now, top last: the back button and a page change close them. */
internal object Overlays {
    val open = ArrayList<Overlay>()

    /** Closes the top one, animated; false when none is open. */
    fun dismissTop(): Boolean {
        val top = open.lastOrNull() ?: return false
        top.dismiss()
        return true
    }

    fun dismissAllNow() {
        open.toTypedArray().forEach { it.dismissNow() }
    }
}

/**
 * A panel shown over a page: [Drawer] from the side on wide screens, [Sheet] from the bottom on small ones ([forScreen]
 * picks). The overlay covers the window with a scrim; a tap on the scrim, the close button or the back button closes it.
 */
abstract class Overlay(context: Context) : BoundView(context) {
    /** Shows [content] over the page with a scrim behind it. */
    abstract fun show(content: View)

    /** Closes it, animated. */
    abstract fun dismiss()

    var onDismissed: (() -> Unit)? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!TouchDebug.enabled) return super.dispatchTouchEvent(event)
        val over = TouchDebug.viewAt(this, event.x, event.y)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) TouchDebug.began(event, over)
        TouchDebug.log {
            "${MotionEvent.actionToString(event.actionMasked)} at (${TouchDebug.fmt(event.rawX / screenDensity)}, ${TouchDebug.fmt(event.rawY / screenDensity)}) dp, " +
                "pointers ${event.pointerCount}, t ${event.eventTime}, over ${TouchDebug.name(over)}"
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) TouchDebug.ended(event, over)
        return handled
    }

    /** The name in the title row (nothing when empty). */
    var title: CharSequence = ""

    /** A muted line under [title]. */
    var subtitle: CharSequence? = null

    /** A glyph in a circle before the title. */
    var titleIcon: Icon? = null

    // A flat color: its fade needs no layer of its own to render.
    protected val scrim = object : View(context) {
        override fun hasOverlappingRendering(): Boolean = false
    }.apply {
        tag = "scrim"
        setBackgroundColor(SCRIM)
        setOnClickListener { dismiss() }
    }

    private var leaving = false
    private val removal = Once()

    /** Puts the overlay over the activity's content. */
    protected fun attach() {
        leaving = false
        removal.reset()
        removeAllViews()
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // Whoever made the overlay may have added it to the window already (`addContentView`); else it goes over the content.
        if (parent == null) {
            val host = activityOf(context)?.findViewById<ViewGroup>(android.R.id.content) ?: error("An overlay needs an Activity")
            host.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        if (this !in Overlays.open) Overlays.open += this
    }

    /** Runs the exit [animation] (when this motion level plays one) and then removes the overlay. */
    protected fun leave(animation: ((end: () -> Unit) -> Unit)?) {
        if (leaving || parent == null) return
        leaving = true
        val finish = { remove() }
        if (animation != null && Animation.full) {
            scrim.animate().alpha(0f).setDuration(EXIT_MS).setInterpolator(EASE_IN).start()
            animation(finish)
        } else {
            finish()
        }
    }

    /** Removes it at once, without an animation (a page change, a rebuilt theme). */
    fun dismissNow() {
        if (parent == null) return
        // Whatever is still animating (the scrim, the panel) would call `remove` again when it ended.
        animate().cancel()
        for (i in 0 until childCount) getChildAt(i).animate().cancel()
        remove()
    }

    private fun remove() {
        removal.run {
            (parent as? ViewGroup)?.removeView(this)
            Overlays.open -= this
            leaving = false
            onDismissed?.invoke()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Overlays.open -= this
    }

    /** The title row: [titleIcon], [title] and [subtitle], and at the right [actions] (an expand button, the close button). */
    protected fun header(vararg actions: View): View {
        val row = RowLayout(context).apply { gap = 3 }
        val label = OverlayTitle(context, title, subtitle, titleIcon)
        row.addView(label, FlexParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { grow = true })
        val buttons = RowLayout(context).apply { gap = 2 }
        actions.forEach { buttons.addView(it, FlexParams(dp(ACTION), dp(ACTION))) }
        row.addView(buttons, FlexParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    /** The close button every panel has. */
    protected fun closeButton(): View = OverlayAction(context, UiIcons.close) {
        TouchDebug.tapped()
        dismiss()
    }.also { it.tag = "close X" }

    internal companion object {
        const val ACTION = 30
        const val SCRIM = 0x73000000

        fun activityOf(context: Context): Activity? {
            var c: Context? = context
            while (c is ContextWrapper) {
                if (c is Activity) return c
                c = c.baseContext
            }
            return null
        }

        /** A panel's body: the surface with a hairline border, rounded by [radii] (dp, as four corners; top-left first). */
        fun surface(radii: FloatArray): GradientDrawable = GradientDrawable().apply {
            val t = theme
            setColor(t.palette.surface)
            setStroke(dp(1), t.palette.border)
            cornerRadii = FloatArray(8) { dp(radii[it / 2]) }
        }

        /** A shadow's elevation in dp. The theme's CSS shadow (blur, dy) maps to the platform's closest thing, an elevation. */
        fun elevationOf(spec: ShadowSpec): Float = dp(spec.blur / 3f)
    }
}

/** Which overlay suits this screen's width (the web's `SHEET_BELOW`): a [Sheet] below 500 dp, a [Drawer] from there. */
fun overlayForScreen(context: Context, expandable: Boolean = false): Overlay {
    val widthDp = context.resources.displayMetrics.widthPixels / screenDensity
    return if (widthDp < SHEET_BELOW_DP) Sheet(context) else Drawer(context, expandable)
}

/** The 30 dp button of a panel's title row: a rounded square in the raised surface with a small glyph. */
internal class OverlayAction(context: Context, private val icon: Icon, private val onTap: () -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var glyph = iconDrawable(icon)
    private var pressX = 0f
    private var pressY = 0f

    init {
        isClickable = true
    }

    var shown: Icon = icon
        set(value) {
            if (field == value) return
            field = value
            glyph = iconDrawable(value)
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(Overlay.ACTION), dp(Overlay.ACTION))
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = t.palette.surfaceRaised
        canvas.drawRoundRect(rect, dp(t.radius.small), dp(t.radius.small), paint)
        glyph.setTint(t.palette.text)
        val s = dp(7)
        glyph.setBounds(width / 2 - s, height / 2 - s, width / 2 + s, height / 2 + s)
        glyph.draw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A tap that lands a finger's width off the 30 dp square (a fingertip is wider than the button) still counts.
        val inside = contains(event.x, event.y)
        val counts = OverlayTap.counts(event.x, event.y, width.toFloat(), height.toFloat(), dp(OverlayTap.SLOP_DP))
        if (TouchDebug.enabled) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressX = event.x
                    pressY = event.y
                    TouchDebug.log { "X: DOWN at (${TouchDebug.fmt(event.x / screenDensity)}, ${TouchDebug.fmt(event.y / screenDensity)}) dp in its ${TouchDebug.fmt(width / screenDensity)} dp square" }
                }
                MotionEvent.ACTION_MOVE -> TouchDebug.log { "X: MOVE ${TouchDebug.fmt(Math.hypot((event.x - pressX).toDouble(), (event.y - pressY).toDouble()).toFloat() / screenDensity)} dp from DOWN, inside $inside" }
                MotionEvent.ACTION_UP -> TouchDebug.log { "X: UP inside the square: $inside, within the ${OverlayTap.SLOP_DP.toInt()} dp slop: $counts -> ${if (counts) "tap" else "outside"}" }
                MotionEvent.ACTION_CANCEL -> TouchDebug.log { "X: CANCEL (the parent took the gesture)" }
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP && counts) onTap()
        return true
    }
}

/** Whether the end of a press at (x, y) on a [w] by [h] button is a tap on it: inside it, or within [slop] px of it. */
internal object OverlayTap {
    const val SLOP_DP = 8f

    fun counts(x: Float, y: Float, w: Float, h: Float, slop: Float): Boolean = x >= -slop && y >= -slop && x <= w + slop && y <= h + slop
}

/** The title row's text: an optional glyph in a circle, a name, and a muted line under it. */
internal class OverlayTitle(context: Context, private val name: CharSequence, private val sub: CharSequence?, private val icon: Icon?) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val heading = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val secondary = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = icon?.let { iconDrawable(it) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // As wide as what it says, so the buttons beside it keep their room; the row's growth gives it the rest.
        val t = theme
        heading.style(t, Role.Heading, t.palette.text)
        secondary.style(t, Role.Secondary, t.palette.textMuted)
        val lead = if (glyph != null) dp(34) + dp(10f) else 0f
        val wanted = Math.ceil((lead + maxOf(heading.measureText(name, 0, name.length), sub?.let { secondary.measureText(it, 0, it.length) } ?: 0f)).toDouble()).toInt()
        val w = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(wanted, MeasureSpec.getSize(widthMeasureSpec))
            else -> wanted
        }
        setMeasuredDimension(w, dp(34))
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        val h = height.toFloat()
        var x = 0f
        if (glyph != null) {
            paint.color = t.palette.surfaceRaised
            canvas.drawCircle(h / 2, h / 2, h / 2, paint)
            glyph.setTint(t.palette.text)
            val s = dp(8)
            glyph.setBounds((h / 2).toInt() - s, (h / 2).toInt() - s, (h / 2).toInt() + s, (h / 2).toInt() + s)
            glyph.draw(canvas)
            x = h + dp(10f)
        }
        heading.style(t, Role.Heading, t.palette.text)
        secondary.style(t, Role.Secondary, t.palette.textMuted)
        val room = width - x
        if (sub.isNullOrEmpty()) {
            canvas.drawTextCentered(android.text.TextUtils.ellipsize(name, heading, room, android.text.TextUtils.TruncateAt.END), x, h / 2, heading)
        } else {
            canvas.drawTextCentered(android.text.TextUtils.ellipsize(name, heading, room, android.text.TextUtils.TruncateAt.END), x, h * 0.25f, heading)
            canvas.drawTextCentered(android.text.TextUtils.ellipsize(sub, secondary, room, android.text.TextUtils.TruncateAt.END), x, h * 0.75f, secondary)
        }
    }
}

/**
 * A panel that slides in from the right: 20 dp in from the window's edges, 380 dp wide (less on a narrow window) and, with [expandable],
 * to nearly the window's width. Its height is the window's less the insets; [show]'s content fills what is under the title row.
 */
open class Drawer(context: Context, val expandable: Boolean = false) : Overlay(context) {
    /** The panel's width when not expanded, in dp; 380 as the web's. */
    var widthDp: Int = COLLAPSED_WIDTH

    /** Whether the panel has grown to the window's width. */
    var expanded: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            expandButton?.shown = if (value) Ui.minimize2 else Ui.maximize2
            resize(animated = true)
            onExpandedChanged?.invoke(value)
        }

    /** Told when the panel is made wider or narrower again (what it holds lays itself out for the room it has). */
    var onExpandedChanged: ((Boolean) -> Unit)? = null

    private var panel: ColumnLayout? = null
    private var expandButton: OverlayAction? = null
    private var width = 0
    private var resizing: ValueAnimator? = null

    private fun targetWidth(): Int {
        val room = (resources.displayMetrics.widthPixels - 2 * dp(EDGE_INSET))
        return if (expanded) room else minOf(dp(widthDp), room)
    }

    private fun resize(animated: Boolean) {
        val p = panel ?: return
        val to = targetWidth()
        val lp = p.layoutParams
        resizing?.cancel()
        if (!animated || !Animation.full) {
            lp.width = to
            p.layoutParams = lp
            width = to
            return
        }
        resizing = ValueAnimator.ofInt(lp.width, to).apply {
            duration = 320
            interpolator = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
            addUpdateListener {
                lp.width = it.animatedValue as Int
                p.layoutParams = lp
            }
            start()
        }
        width = to
    }

    override fun show(content: View) {
        attach()
        val t = theme
        val u = unitPx()
        val panel = ColumnLayout(context).apply {
            background = surface(FloatArray(4) { t.radius.drawer })
            elevation = elevationOf(t.shadow.drawer)
            isClickable = true
            val pad = Math.round(5 * u)
            setPadding(pad, pad, pad, pad)
        }
        this.panel = panel
        val expand = OverlayAction(context, Ui.maximize2) { expanded = !expanded }
        expandButton = expand
        expand.shown = if (expanded) Ui.minimize2 else Ui.maximize2
        panel.addView(header(*(if (expandable) arrayOf<View>(expand, closeButton()) else arrayOf<View>(closeButton()))), FlexParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = Math.round(4 * u)
        })
        panel.addView(content, FlexParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            grow = true
            shrink = true
        })
        width = targetWidth()
        addView(panel, LayoutParams(width, LayoutParams.MATCH_PARENT, Gravity.END or Gravity.TOP).apply {
            setMargins(dp(EDGE_INSET), dp(EDGE_INSET), dp(EDGE_INSET), dp(EDGE_INSET))
        })
        if (Animation.animates) {
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(ENTER_MS).setInterpolator(EASE_OUT).start()
            // The panel itself fades only where the display can afford it: a fade of a panel this size is a layer of its own to render each frame.
            if (Animation.full) {
                panel.alpha = 0f
                panel.animate().alpha(1f).setDuration(ENTER_MS).setInterpolator(EASE_OUT).withLayer().start()
            }
        }
    }

    override fun dismiss() {
        val p = panel
        leave(
            if (p == null) null else { end ->
                p.animate().alpha(0f).setDuration(EXIT_MS).setInterpolator(EASE_IN).withLayer().withEndAction(end).start()
            },
        )
    }
}

/**
 * A panel that rises from the bottom, with a grip, and is dismissed by pulling the grip or the title down. It is at most 92%
 * of the window's height ([tall] makes it that tall); its content scrolls on its own.
 */
open class Sheet(context: Context) : Overlay(context) {
    /** The sheet takes all of its 92% height, as one opened expanded or locked does on the web. */
    var tall: Boolean = false

    private var panel: ColumnLayout? = null
    private var pullStart = 0f
    private var pullLast = 0f
    private var pullAt = 0L
    private var pullSpeed = 0f

    private inner class SheetPanel : ColumnLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cap = (MeasureSpec.getSize(heightMeasureSpec) * SHEET_HEIGHT).toInt()
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, if (tall) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST))
        }
    }

    override fun show(content: View) {
        attach()
        val t = theme
        val u = unitPx()
        val panel = SheetPanel().apply {
            background = surface(floatArrayOf(t.radius.drawer, t.radius.drawer, 0f, 0f))
            elevation = elevationOf(t.shadow.drawer)
            isClickable = true
        }
        this.panel = panel
        // What is pulled to let it go: the grip and the title; the content under it scrolls on its own.
        val handle = ColumnLayout(context)
        handle.addView(Grip(context), FlexParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(17)))
        val title = header(closeButton())
        handle.addView(title, FlexParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            val side = Math.round(5 * u)
            setMargins(side, Math.round(2 * u), side, Math.round(3 * u))
        })
        handle.tag = "handle"
        handle.setOnTouchListener { _, event -> pull(event) }
        // Above the content in touch order too, so that nothing the body lays out over the header's edge can take a tap meant for its buttons.
        handle.translationZ = dp(1f)
        panel.addView(handle, FlexParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(content, FlexParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            grow = true
            shrink = true
            val side = Math.round(5 * u)
            // Clear of the home indicator a phone has.
            setMargins(side, 0, side, dp(20))
        })
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        if (Animation.animates) {
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(ENTER_MS).setInterpolator(EASE_OUT).start()
            panel.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, tp: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                    panel.removeOnLayoutChangeListener(this)
                    // Rises from the bottom.
                    panel.translationY = (b - tp).toFloat()
                    panel.animate().translationY(0f).setDuration(RISE_MS).setInterpolator(EASE_OUT).withLayer().start()
                }
            })
        }
    }

    /** The pull-down gesture of the web's `useSheetPull`: follows the finger down only, lets go past 100 dp or faster than 0.5 dp/ms, else springs back. */
    private fun pull(event: MotionEvent): Boolean {
        val p = panel ?: return false
        if (TouchDebug.enabled && event.actionMasked != MotionEvent.ACTION_DOWN) {
            TouchDebug.log { "pull: ${MotionEvent.actionToString(event.actionMasked)} dy ${TouchDebug.fmt((event.rawY - pullStart) / screenDensity)} dp, speed ${TouchDebug.fmt(pullSpeed / screenDensity)} dp/ms" }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                TouchDebug.log { "pull: tracking starts (no slop: the sheet follows the finger from the first pixel; dismisses past 100 dp or 0.5 dp/ms)" }
                pullStart = event.rawY
                pullLast = event.rawY
                pullAt = event.eventTime
                pullSpeed = 0f
                p.animate().cancel()
            }

            MotionEvent.ACTION_MOVE -> {
                val elapsed = maxOf(1L, event.eventTime - pullAt)
                pullSpeed = (event.rawY - pullLast) / elapsed
                pullLast = event.rawY
                pullAt = event.eventTime
                p.translationY = maxOf(0f, event.rawY - pullStart)
            }

            MotionEvent.ACTION_UP -> {
                val offset = maxOf(0f, event.rawY - pullStart)
                val gone = SheetPull.dismisses(offset / screenDensity, pullSpeed / screenDensity)
                TouchDebug.log { "pull: UP offset ${TouchDebug.fmt(offset / screenDensity)} dp -> ${if (gone) "dismiss" else "spring back"}" }
                if (offset > 0f) TouchDebug.pulled()
                if (gone) dismiss() else springBack()
            }

            MotionEvent.ACTION_CANCEL -> springBack()
        }
        return true
    }

    private fun springBack() {
        val p = panel ?: return
        if (Animation.animates) p.animate().translationY(0f).setDuration(200).setInterpolator(EASE_OUT).start() else p.translationY = 0f
    }

    override fun dismiss() {
        val p = panel
        leave(
            if (p == null) null else { end ->
                p.animate().translationY(p.height.toFloat()).setDuration(EXIT_MS).setInterpolator(EASE_IN).withLayer().withEndAction(end).start()
            },
        )
    }
}

/** The grip of a sheet: a 36 by 5 bar in the line color at 60%, 8 dp from the top. */
private class Grip(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val t = theme
        val w = dp(36f)
        val h = dp(5f)
        val left = (width - w) / 2
        rect.set(left, dp(8f), left + w, dp(8f) + h)
        paint.color = withAlpha(t.palette.line, 0.6f)
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)
    }
}

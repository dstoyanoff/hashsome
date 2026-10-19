package dev.hashsome.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import dev.hashsome.headless.Scope

// The entity drawer (the web's `EntityDrawer`, `useDrawer` and `DrawerHeader`): one panel at a time over the page, a drawer at the
// side on a wide screen and a sheet from the bottom on a small one, holding a body made for one entity.

/** What a drawer's body is given: the scope that lives as long as the drawer is open, and how wide the panel is. */
class DrawerContext internal constructor(val scope: Scope, private val overlay: Overlay) {
    /** Whether the panel is at its wide size: expanded (a drawer the person widened, or one opened full) or a tall sheet. */
    val expanded: Boolean get() = (overlay as? Drawer)?.expanded == true || (overlay as? Sheet)?.tall == true

    private val listeners = ArrayList<(Boolean) -> Unit>()

    init {
        (overlay as? Drawer)?.onExpandedChanged = { wide -> listeners.toTypedArray().forEach { it(wide) } }
    }

    /** Calls [fn] when the panel is widened or narrowed again. */
    fun onExpandedChanged(fn: (Boolean) -> Unit) {
        listeners += fn
    }

    /** Closes the drawer. */
    fun close() {
        overlay.dismiss()
    }
}

/**
 * Opens the shared drawer over the page. A tile calls it from `onHold` (a light's controls, a switch's energy), a readout or a chip
 * from its tap (a sensor's history, the forecast). Opening another replaces the one that is open, as the web's single `EntityDrawer`
 * does. Whatever it holds is built by [open]'s `build` when the panel opens and lets go of the runtime when it closes.
 */
object DrawerHost {
    private var current: Overlay? = null

    /**
     * Shows the body that [build] makes. [title] is the name in the header, [kind] the muted line under it and [icon] the glyph in the
     * circle before them. [expandable] gives a drawer the button that widens it (the web's expand button; off, as the tablets want it);
     * [full] opens it at its widest, as a panel of its own. [scrolls] wraps the body in a scroll view; leave it off for a body that
     * scrolls itself (a list). [owner] is the scope of the page that opened it: the drawer's own scope is a child of it, so a page
     * change that disposes the page closes the drawer.
     */
    fun open(
        context: Context,
        owner: Scope,
        title: String,
        kind: String? = null,
        icon: Icon? = null,
        expandable: Boolean = false,
        full: Boolean = false,
        scrolls: Boolean = true,
        build: (DrawerContext) -> View,
    ) {
        prepare(context, owner, title, kind, icon, expandable, full, scrolls, build).open()
    }

    /**
     * Builds the drawer's body now and shows it later: [Prepared.open] puts it up at once (the views are made and measured, so the first
     * frame is the panel's animation), [Prepared.discard] lets go of it. A tile makes one when its hold line starts (`TileView.onHoldWarm`)
     * so that the drawer appears as the hold completes. The arguments are those of [open].
     */
    fun prepare(
        context: Context,
        owner: Scope,
        title: String,
        kind: String? = null,
        icon: Icon? = null,
        expandable: Boolean = false,
        full: Boolean = false,
        scrolls: Boolean = true,
        build: (DrawerContext) -> View,
    ): Prepared {
        val began = android.os.SystemClock.uptimeMillis()
        val overlay = overlayForScreen(context, expandable)
        overlay.title = title
        overlay.subtitle = kind
        overlay.titleIcon = icon
        if (full) {
            (overlay as? Drawer)?.expanded = true
            (overlay as? Sheet)?.tall = true
        }

        val scope = owner.child()
        val body = try {
            build(DrawerContext(scope, overlay))
        } catch (e: Throwable) {
            scope.dispose()
            throw e
        }

        val content = if (scrolls) scrolled(context, body) else body
        // Measured once at about the size the panel gives it, so what the first layout would work out from sizes is ready.
        val m = context.resources.displayMetrics
        val room = m.widthPixels - 2 * dp(EDGE)
        val inner = (if (m.widthPixels / screenDensity < SHEET_BELOW_DP) m.widthPixels else minOf(dp(380), room)) - 2 * Math.round(5 * unitPx())
        content.measure(View.MeasureSpec.makeMeasureSpec(inner, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.AT_MOST))
        android.util.Log.d("hashsome-drawer", "prepared \"$title\": body built and measured in ${android.os.SystemClock.uptimeMillis() - began} ms")
        return Prepared(overlay, scope, content, title)
    }

    private const val EDGE = 20

    /** A drawer whose body is made and waiting: [open] it, or [discard] it. */
    class Prepared internal constructor(private val overlay: Overlay, private val scope: Scope, private val content: View, private val title: String) {
        private var done = false

        /** Shows the drawer, replacing the one that is open. Does nothing once opened or discarded. */
        fun open() {
            if (done || scope.disposed) return
            done = true
            val began = android.os.SystemClock.uptimeMillis()
            current?.dismissNow()
            overlay.onDismissed = {
                scope.dispose()
                if (current === overlay) current = null
            }
            current = overlay
            overlay.show(content)
            android.util.Log.d("hashsome-drawer", "open \"$title\": panel shown in ${android.os.SystemClock.uptimeMillis() - began} ms")
        }

        /** Lets go of the body and what it holds. */
        fun discard() {
            if (done) return
            done = true
            scope.dispose()
        }
    }

    private fun scrolled(context: Context, body: View): View = ScrollView(context).also {
        it.isFillViewport = false
        it.isVerticalScrollBarEnabled = false
        it.overScrollMode = View.OVER_SCROLL_NEVER
        it.addView(body, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }
}

/**
 * Makes [this] tile open a drawer on a hold, built while the hold line fills so that it appears as the hold completes: the body is
 * prepared when the line starts, given up if the press is, and shown when the hold fires. [title] and [icon] are read then.
 */
fun TileView.opensDrawer(
    context: Context,
    owner: Scope,
    title: () -> String,
    kind: String? = null,
    icon: () -> Icon? = { null },
    expandable: Boolean = false,
    build: (DrawerContext) -> View,
) {
    var ready: DrawerHost.Prepared? = null
    fun make() = DrawerHost.prepare(context, owner, title(), kind, icon(), expandable, build = build)
    onHoldWarm = {
        ready?.discard()
        ready = make()
    }
    onHoldCancel = {
        ready?.discard()
        ready = null
    }
    onHold = {
        val drawer = ready ?: make()
        ready = null
        drawer.open()
    }
}

/** A column on the raised surface with the row radius (the web's `row`): what a drawer's rows and tiles are. Pads itself in spacing units. */
open class RaisedCard(context: Context, private val padX: Float = 4f, private val padY: Float = 3.5f) : ColumnLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        setWillNotDraw(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val x = Math.round(padX * unitPx())
        val y = Math.round(padY * unitPx())
        if (paddingLeft != x || paddingTop != y) setPadding(x, y, x, y)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = t.palette.surfaceRaised
        canvas.drawRoundRect(rect, dp(t.radius.row), dp(t.radius.row), paint)
    }
}

/**
 * One line of text in a role, aligned [align] in the width it is given, ellipsized when it does not fit ([ellipsize] false lets it run over the
 * edge, which the parent shows when it does not clip its children). A fixed [widthDp] gives the column of a table its width.
 */
open class AlignedText(context: Context, text: CharSequence = "", var role: Role = Role.Body, var color: Int? = null, var align: Paint.Align = Paint.Align.LEFT, private val widthDp: Int? = null, private val ellipsize: Boolean = true) : View(context) {
    var text: CharSequence = text
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val t = theme
        paint.style(t, role, 0)
        val wide = Math.ceil(paint.measureText(text, 0, text.length).toDouble()).toInt()
        val w = if (widthDp != null) dp(widthDp) else when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(wide, MeasureSpec.getSize(widthMeasureSpec))
            else -> wide
        }
        val line = Math.ceil((paint.textSize * (t.style(role).lineHeight ?: 1.3f)).toDouble()).toInt()
        setMeasuredDimension(w, line)
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        paint.style(t, role, color ?: t.palette.text)
        val fitted = if (ellipsize) TextUtils.ellipsize(text, paint, width.toFloat(), TextUtils.TruncateAt.END) else text
        val x = when (align) {
            Paint.Align.RIGHT -> width.toFloat()
            Paint.Align.CENTER -> width / 2f
            else -> 0f
        }
        canvas.drawTextCentered(fitted, x, height / 2f, paint, align)
    }
}

/** A 6 dp track in the raised surface with a span of the accent on it (the web's day range bar), [from] to [to] as fractions of the width. */
open class SpanBar(context: Context) : View(context) {
    var from: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var to: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val exact = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY
        setMeasuredDimension(if (exact) MeasureSpec.getSize(widthMeasureSpec) else 0, dp(6))
    }

    override fun onDraw(canvas: Canvas) {
        val p = theme.palette
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = p.surfaceRaised
        canvas.drawRoundRect(rect, r, r, paint)
        if (to > from) {
            rect.set(width * from, 0f, width * to, height.toFloat())
            paint.color = p.accent
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }
}

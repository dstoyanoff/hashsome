package dev.hashsome.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import dev.hashsome.client.Cancel
import dev.hashsome.client.Store

// The base views every component is made of. All are `open`: a consumer can subclass one, or write its own view against
// a headless control. All colors, radii and sizes are read from `Hashsome.theme` at draw time.

/** How long a press has to last to count as a hold (the web's `HOLD_MS`). */
const val HOLD_MS = 500L

/** A finger that moves further than this many dp is dragging, not tapping. */
private const val DRAG_THRESHOLD = 8f

/** A finger that moves further than this many dp is scrolling or dragging, not holding. */
private const val HOLD_TOLERANCE = 10f

/**
 * The base of every component view: it listens to the stores it binds while it is attached to a window and lets go of
 * them when it is detached, so a view never keeps a screen alive.
 */
abstract class BoundView(context: Context) : FrameLayout(context) {
    private val cancels = ArrayList<Cancel>()

    /** Calls [fn] with [store]'s value now and on each change, until this view is detached. Call it from `onAttachedToWindow`. */
    protected fun <T> bind(store: Store<T>, fn: (T) -> Unit) {
        onDetach(store.listenNow(fn))
    }

    /** Registers [cancel] to run when this view is detached. */
    protected fun onDetach(cancel: Cancel) {
        cancels += cancel
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        val all = cancels.toTypedArray()
        cancels.clear()
        all.forEach { it() }
    }
}

/**
 * A rounded surface that is the body of every tile, and the one content API they share: an optional [icon], a [title]
 * and a [secondary] line, a [fill] level behind them (a dimmable tile's brightness), the accent [filled] state, a
 * [disabled] look, and the touches: [onClick], [onHold], and [onDrag] / [onCommit] along the tile's width (0..1).
 *
 * It is one view drawn on a canvas, the fill moving by its width on the canvas with no layout in a drag. Child views, if
 * any, are its trailing controls: they sit at the right edge, vertically centered.
 */
open class TileView(context: Context) : ViewGroup(context) {
    var icon: Icon? = null
        set(value) {
            val changed = field != null && field != value
            field = value
            glyph = value?.let { iconDrawable(it) }
            // An icon that has changed turns in, as the old one is gone.
            if (changed) {
                turn.to(0f, false)
                turn.to(1f)
            } else {
                invalidate()
            }
        }

    var title: String = ""
        set(value) {
            field = value
            textDirty = true
            invalidate()
        }

    var secondary: String? = null
        set(value) {
            field = value
            textDirty = true
            invalidate()
        }

    /** 0..1 of the width filled behind the content; null draws no fill. */
    var fill: Float? = null
        set(value) {
            field = value
            sync(animate = !dragging)
        }

    var filled: Boolean = false
        set(value) {
            field = value
            sync(animate = true)
        }

    var disabled: Boolean = false
        set(value) {
            field = value
            sync(animate = true)
        }

    /** The color of the second line while the tile is ready, in place of the usual muted one (a task's "Overdue"); null keeps the usual. */
    var secondaryTone: Int? = null
        set(value) {
            if (field == value) return
            field = value
            sync(animate = true)
        }

    var onClick: (() -> Unit)? = null
    var onHold: (() -> Unit)? = null
    var onDrag: ((Float) -> Unit)? = null
    var onCommit: ((Float) -> Unit)? = null

    /** Whether a drag along the tile is going on. */
    var dragging: Boolean = false
        private set

    private var glyph: Drawable? = null
    private val turn = Eased(this, 1f, 180)
    private val fillWidth = Eased(this, 0f)
    private val fillOpacity = Eased(this, 0f)
    private val card = EasedColor(this, theme.palette.surface)
    private val badge = EasedColor(this, theme.palette.surfaceRaised)
    private val glyphTint = EasedColor(this, theme.palette.line)
    private val titleColor = EasedColor(this, theme.palette.text)
    private val secondaryColor = EasedColor(this, theme.palette.line)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val secondaryPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val rect = RectF()
    private var shownTitle: CharSequence = ""
    private var shownSecondary: CharSequence = ""
    private var secondaryWidth = 0f
    private var textDirty = true
    private var trailing = 0
    private var downX = 0f
    private var downY = 0f
    private var dragWidth = 1f
    private var held = false
    private var hold: ValueAnimator? = null
    private var holdProgress = 0f
    private val holdFire = Runnable {
        held = true
        this.hold?.cancel()
        holdProgress = 0f
        invalidate()
        onHold?.invoke()
    }

    init {
        setWillNotDraw(false)
        sync(animate = false)
    }

    private fun sync(animate: Boolean) {
        val p = theme.palette
        val ready = !disabled
        val accented = filled && ready
        val dimmable = fill != null
        val solid = accented && !dimmable
        val level = if (fill != null && ready && (filled || dragging)) fill!!.coerceIn(0f, 1f) else 0f
        fillWidth.to(level, animate && !dragging)
        fillOpacity.to(level, animate && !dragging)
        // A dimmable tile never gets a solid card (its fill is the "how on"); a solid on/off tile turns accent all over.
        card.to(if (solid) p.accent else p.surface)
        badge.to(
            when {
                accented && dimmable -> mix(p.accent, 0xFF000000.toInt(), 0.3f)
                solid -> mix(p.accent, 0xFF000000.toInt(), 0.25f)
                else -> p.surfaceRaised
            },
        )
        glyphTint.to(if (accented) p.accentText else if (ready) p.line else p.textMuted)
        titleColor.to(if (ready) p.text else p.textMuted)
        secondaryColor.to(
            when {
                !ready -> p.textMuted
                solid || level > 0.85f -> p.onAccent
                else -> secondaryTone ?: p.line
            },
        )
        val target = if (ready) 1f else 0.7f
        if (!animate || !Animation.animates || !isAttachedToWindow) {
            animate().cancel()
            alpha = target
        } else {
            animate().alpha(target).setDuration(280).start()
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val u = unitPx()
        val minHeight = Math.round(4 * u + dp(BADGE))
        var childHeight = 0
        var childWidth = 0
        var count = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            childHeight = maxOf(childHeight, c.measuredHeight)
            childWidth += c.measuredWidth
            count++
        }
        trailing = if (count == 0) 0 else childWidth + Math.round(2 * u * (count - 1)) + Math.round(2.5f * u)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val wanted = maxOf(minHeight, childHeight)
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(wanted, MeasureSpec.getSize(heightMeasureSpec))
            else -> wanted
        }
        setMeasuredDimension(width, height)
        textDirty = true
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val u = unitPx()
        var right = (r - l) - Math.round(2.5f * u)
        for (i in childCount - 1 downTo 0) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val top = ((b - t) - c.measuredHeight) / 2
            c.layout(right - c.measuredWidth, top, right, top + c.measuredHeight)
            right -= c.measuredWidth + Math.round(2 * u)
        }
    }

    private fun layoutText(left: Float, right: Float) {
        val t = theme
        titlePaint.style(t, Role.Label, titleColor.value)
        secondaryPaint.style(t, Role.Secondary, secondaryColor.value)
        val gap = 2.5f * unitPx()
        shownSecondary = secondary.orEmpty()
        secondaryWidth = if (shownSecondary.isEmpty()) 0f else secondaryPaint.measureText(shownSecondary, 0, shownSecondary.length)
        val room = right - left - if (secondaryWidth > 0) secondaryWidth + gap else 0f
        shownTitle = TextUtils.ellipsize(title, titlePaint, maxOf(0f, room), TextUtils.TruncateAt.END)
        textDirty = false
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        val u = unitPx()
        val w = width.toFloat()
        val h = height.toFloat()
        val r = minOf(dp(t.radius.card), h / 2)
        rect.set(0f, 0f, w, h)
        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        paint.style = Paint.Style.FILL
        paint.color = card.value
        canvas.drawRect(rect, paint)
        if (fillWidth.value > 0.001f && fillOpacity.value > 0.001f) {
            paint.color = withAlpha(t.palette.accent, fillOpacity.value)
            canvas.drawRect(0f, 0f, w * fillWidth.value, h, paint)
        }

        // The badge: a circle of BADGE dp between two spacing-unit-2 paddings, the glyph 16 dp in it.
        val badgeR = dp(BADGE / 2f)
        val cx = 2 * u + badgeR
        val cy = h / 2
        paint.color = badge.value
        canvas.drawCircle(cx, cy, badgeR, paint)
        glyph?.let {
            it.setTint(glyphTint.value)
            val s = dp(8)
            it.setBounds(cx.toInt() - s, cy.toInt() - s, cx.toInt() + s, cy.toInt() + s)
            canvas.save()
            canvas.rotate(-90f * (1f - turn.value), cx, cy)
            val k = 0.6f + 0.4f * turn.value
            canvas.scale(k, k, cx, cy)
            it.alpha = (turn.value * 255).toInt()
            it.draw(canvas)
            canvas.restore()
        }

        val left = 2 * u + 2 * badgeR + 2.5f * u
        val right = w - 3.5f * u - trailing
        if (textDirty) layoutText(left, right)
        titlePaint.color = titleColor.value
        secondaryPaint.color = secondaryColor.value
        if (shownSecondary.isNotEmpty()) canvas.drawTextCentered(shownSecondary, right - secondaryWidth, cy, secondaryPaint)
        canvas.drawTextCentered(shownTitle, left, cy, titlePaint)

        if (holdProgress > 0f) {
            paint.color = if (filled && fill == null) 0x59000000 else 0x8CFFFFFF.toInt()
            canvas.drawRect(0f, h - dp(2f), w * holdProgress, h, paint)
        }
        canvas.restore()
    }

    private fun startHold() {
        hold?.cancel()
        if (!Animation.animates) return
        hold = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = HOLD_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                holdProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopHold() {
        removeCallbacks(holdFire)
        hold?.cancel()
        hold = null
        if (holdProgress != 0f) {
            holdProgress = 0f
            invalidate()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val interactive = !disabled && (onClick != null || onDrag != null || onHold != null)
        if (!interactive) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                held = false
                dragging = false
                // Measured once, here: a drag does not ask the view for its size on every move.
                dragWidth = maxOf(1, width).toFloat()
                if (onHold != null) {
                    postDelayed(holdFire, HOLD_MS)
                    startHold()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (held) return true
                val dx = event.x - downX
                if (onDrag != null && !dragging && Math.abs(dx) > dp(DRAG_THRESHOLD)) {
                    dragging = true
                    // A page that scrolls keeps the touch until the finger turns out to be dragging along the tile, not down the page.
                    parent?.requestDisallowInterceptTouchEvent(true)
                    stopHold()
                    sync(animate = false)
                }
                if (dragging) {
                    onDrag?.invoke((event.x / dragWidth).coerceIn(0f, 1f))
                } else if (Math.hypot(dx.toDouble(), (event.y - downY).toDouble()) > dp(HOLD_TOLERANCE)) {
                    stopHold()
                }
            }

            MotionEvent.ACTION_UP -> {
                stopHold()
                if (dragging) {
                    dragging = false
                    onCommit?.invoke((event.x / dragWidth).coerceIn(0f, 1f))
                    sync(animate = true)
                } else if (held) {
                    held = false
                } else if (Math.abs(event.x - downX) < dp(12f) && Math.abs(event.y - downY) < dp(12f)) {
                    onClick?.invoke()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                stopHold()
                if (dragging) {
                    dragging = false
                    onCommit?.invoke((event.x / dragWidth).coerceIn(0f, 1f))
                    sync(animate = true)
                }
                held = false
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopHold()
    }
}

/** A round icon button: the surface-raised circle with a glyph, squeezed while pressed, with a ring turning round it while it waits. */
open class RoundButton(context: Context, icon: Icon) : View(context) {
    var icon: Icon = icon
        set(value) {
            field = value
            glyph = iconDrawable(value)
            invalidate()
        }

    var onClick: (() -> Unit)? = null

    /** Lit in the warm color (a mute that is on). */
    var active: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** A command is on its way: a ring turns round the edge. */
    var pending: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startSpin() else spin?.cancel()
            invalidate()
        }

    var failed: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** The solid accent button (play). */
    var primary: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** A tint of the glyph's own, over the state colors. */
    var tint: Int? = null
        set(value) {
            field = value
            invalidate()
        }

    private var glyph: Drawable? = iconDrawable(icon)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var angle = 0f
    private var spin: ValueAnimator? = null

    init {
        isClickable = true
    }

    private fun startSpin() {
        spin?.cancel()
        spin = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                angle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(theme.density.iconCircle)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        val c = width / 2f
        paint.style = Paint.Style.FILL
        paint.color = if (primary) t.palette.accent else t.palette.surfaceRaised
        canvas.drawCircle(c, c, c, paint)
        glyph?.let {
            val tinted = tint
                ?: if (failed) t.palette.danger else if (active) t.palette.warm else if (primary) t.palette.accentText else t.palette.text
            it.setTint(tinted)
            val s = dp(t.density.iconSize * 0.375f).toInt()
            it.setBounds(c.toInt() - s, c.toInt() - s, c.toInt() + s, c.toInt() + s)
            it.draw(canvas)
        }
        if (pending) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            paint.color = if (primary) t.palette.accentText else t.palette.accent
            rect.set(dp(1f), dp(1f), width - dp(1f), height - dp(1f))
            canvas.drawArc(rect, angle, 90f, false, paint)
        }
    }

    private fun squeeze(to: Float) {
        if (!Animation.animates) return
        animate().scaleX(to).scaleY(to).setDuration(120).start()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (isEnabled) squeeze(0.88f)
            MotionEvent.ACTION_UP -> {
                squeeze(1f)
                if (isEnabled && contains(event.x, event.y)) onClick?.invoke()
            }

            MotionEvent.ACTION_CANCEL -> squeeze(1f)
        }
        return true
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.5f
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        spin?.cancel()
    }
}

/** A circular progress or level ring: the artwork in its ring, how far along the track is drawn round the picture; dragging round the ring seeks. */
open class RingView(context: Context) : View(context) {
    /** 0..1. */
    var fraction: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    /** Whether dragging round the ring seeks. */
    var seekable: Boolean = false

    /** The position under the finger while seeking, 0..1. */
    var onSeekPreview: ((Float) -> Unit)? = null

    /** The position where the finger lifted, 0..1. */
    var onSeek: ((Float) -> Unit)? = null

    private var bitmap: Bitmap? = null
    private var shader: BitmapShader? = null
    private val art = Eased(this, 1f, 200)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val matrix = Matrix()
    private var seeking = false

    /** The picture inside the ring; null shows the empty disc. It fades in. */
    fun setArtwork(artwork: Bitmap?) {
        bitmap = artwork
        shader = artwork?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        art.to(0f, false)
        art.to(1f)
    }

    override fun onDraw(canvas: Canvas) {
        val p = theme.palette
        val s = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val unit = s / 100f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3 * unit
        paint.strokeCap = Paint.Cap.BUTT
        paint.color = p.border
        canvas.drawCircle(cx, cy, 47 * unit, paint)
        if (fraction > 0f) {
            paint.color = p.accent
            paint.strokeCap = Paint.Cap.ROUND
            rect.set(cx - 47 * unit, cy - 47 * unit, cx + 47 * unit, cy + 47 * unit)
            canvas.drawArc(rect, -90f, 360f * fraction.coerceAtMost(1f), false, paint)
        }

        // The hairline circle 9% in, and the picture 14% in.
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeWidth = dp(1f)
        paint.color = p.border
        canvas.drawCircle(cx, cy, 41 * unit - dp(0.5f), paint)
        val r = 36 * unit
        paint.style = Paint.Style.FILL
        paint.color = p.surfaceRaised
        canvas.drawCircle(cx, cy, r, paint)
        val b = bitmap
        val sh = shader
        if (b != null && sh != null) {
            val scale = (2 * r) / minOf(b.width, b.height)
            matrix.setScale(scale, scale)
            matrix.postTranslate(cx - b.width * scale / 2, cy - b.height * scale / 2)
            sh.setLocalMatrix(matrix)
            paint.shader = sh
            paint.alpha = (art.value * 255).toInt()
            canvas.drawCircle(cx, cy, r, paint)
            paint.shader = null
            paint.alpha = 255
        }
    }

    private fun fractionAt(x: Float, y: Float): Float {
        val a = Math.atan2((x - width / 2f).toDouble(), -(y - height / 2f).toDouble())
        return (((a + 2 * Math.PI) % (2 * Math.PI)) / (2 * Math.PI)).toFloat()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!seekable) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                seeking = true
                parent?.requestDisallowInterceptTouchEvent(true)
                onSeekPreview?.invoke(fractionAt(event.x, event.y))
            }

            MotionEvent.ACTION_MOVE -> if (seeking) onSeekPreview?.invoke(fractionAt(event.x, event.y))
            MotionEvent.ACTION_UP -> if (seeking) {
                seeking = false
                onSeek?.invoke(fractionAt(event.x, event.y))
            }

            MotionEvent.ACTION_CANCEL -> seeking = false
        }
        return true
    }
}

/** A horizontal bar for a level, a seek position or a volume; draggable when [onDrag] is set. */
open class BarView(context: Context) : View(context) {
    /** 0..1. */
    var fraction: Float = 0f
        set(value) {
            field = value
            fill.to(value.coerceIn(0f, 1f), !dragging)
        }

    var onDrag: ((Float) -> Unit)? = null
    var onCommit: ((Float) -> Unit)? = null

    /** The touch was taken away (a gesture, a lost finger): nothing is committed. */
    var onCancel: (() -> Unit)? = null

    /** Whether a finger is dragging it. */
    var dragging: Boolean = false
        private set

    private val fill = Eased(this, 0f, 200)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else dp(10)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        val p = theme.palette
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        paint.style = Paint.Style.FILL
        paint.color = p.surface
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        clip.reset()
        rect.set(dp(1f), dp(1f), w - dp(1f), h - dp(1f))
        clip.addRoundRect(rect, h / 2, h / 2, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        paint.color = p.accent
        canvas.drawRect(dp(1f), dp(1f), dp(1f) + (w - dp(2f)) * fill.value, h - dp(1f), paint)
        canvas.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = p.border
        rect.set(dp(0.5f), dp(0.5f), w - dp(0.5f), h - dp(0.5f))
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onDrag == null && onCommit == null) return false
        val v = (event.x / maxOf(1, width)).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                fill.to(v, false)
                onDrag?.invoke(v)
            }

            MotionEvent.ACTION_MOVE -> if (dragging) {
                fill.to(v, false)
                onDrag?.invoke(v)
            }

            MotionEvent.ACTION_UP -> if (dragging) {
                dragging = false
                onCommit?.invoke(v)
            }

            MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                onCancel?.invoke()
            }
        }
        return true
    }
}

/**
 * A small dot with a ring round it: green when [ok], red otherwise. The settings gesture listens for taps on it
 * (`TouchListener.onStatusDotTap`).
 */
open class StatusDot(context: Context) : View(context) {
    var ok: Boolean = true
        set(value) {
            field = value
            tone.to(if (value) theme.palette.success else theme.palette.danger)
        }

    /** How well the system is doing, in the three colors of the web's status dot (green, warm while something connects, red). Overrides [ok]. */
    var level: dev.hashsome.headless.HealthLevel = dev.hashsome.headless.HealthLevel.Ok
        set(value) {
            field = value
            val p = theme.palette
            tone.to(
                when (value) {
                    dev.hashsome.headless.HealthLevel.Ok -> p.success
                    dev.hashsome.headless.HealthLevel.Pending -> p.warm
                    dev.hashsome.headless.HealthLevel.Down -> p.danger
                },
            )
        }

    private val tone = EasedColor(this, theme.palette.success)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        setOnClickListener { Hashsome.touchListener.onStatusDotTap() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(dp(32), dp(32))

    override fun onDraw(canvas: Canvas) {
        val c = width / 2f
        paint.color = withAlpha(tone.value, 0.2f)
        canvas.drawCircle(c, c, dp(10f), paint)
        paint.color = tone.value
        canvas.drawCircle(c, c, dp(6f), paint)
    }
}

/** A row of selectable chips: a pill under the selected one glides to the next, drawn here so a glide lays nothing out. */
open class ChipsView(context: Context) : View(context) {
    var labels: List<String> = emptyList()
        set(value) {
            field = value
            measureChips()
            // The pill stays on the chip that is current, not on the first.
            val at = current.coerceIn(0, maxOf(0, lefts.size - 1))
            val first = lefts.getOrNull(at) ?: 0f
            left.to(first, false)
            right.to(first + (widths.getOrNull(at) ?: 0f), false)
            requestLayout()
            invalidate()
        }

    var current: Int = 0
        set(value) {
            if (field == value) return
            field = value
            glideTo(value)
        }

    var onSelect: ((Int) -> Unit)? = null

    private val widths = ArrayList<Float>()
    private val lefts = ArrayList<Float>()
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val left = Eased(this, 0f, 250)
    private val right = Eased(this, 0f, 250)

    private fun measureChips() {
        widths.clear()
        lefts.clear()
        text.style(theme, Role.Body, 0)
        var x = 0f
        labels.forEach {
            val w = text.measureText(it) + dp(24f)
            lefts.add(x)
            widths.add(w)
            x += w + dp(8f)
        }
    }

    private fun glideTo(i: Int) {
        if (i !in lefts.indices) return
        left.to(lefts[i])
        right.to(lefts[i] + widths[i])
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (lefts.size != labels.size) measureChips()
        val wanted = (lefts.lastOrNull() ?: 0f) + (widths.lastOrNull() ?: 0f)
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else dp(32)
        val w = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(Math.ceil(wanted.toDouble()).toInt(), MeasureSpec.getSize(widthMeasureSpec))
            else -> Math.ceil(wanted.toDouble()).toInt()
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        if (labels.isEmpty()) return
        val t = theme
        rect.set(left.value, 0f, right.value, height.toFloat())
        pill.color = t.palette.accent
        canvas.drawRoundRect(rect, height / 2f, height / 2f, pill)
        text.style(t, Role.Body, t.palette.text)
        labels.forEachIndexed { i, label ->
            text.color = if (i == current) t.palette.accentText else t.palette.text
            canvas.drawTextCentered(label, lefts[i] + dp(12f), height / 2f, text)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val i = lefts.indices.firstOrNull { event.x >= lefts[it] && event.x <= lefts[it] + widths[it] + dp(4f) }
            if (i != null && i != current) {
                current = i
                onSelect?.invoke(i)
            }
        }
        return true
    }
}

// BrowseRow and QueueRow are in MediaRows.kt.

/** One line of text that scrolls sideways when it does not fit (in full motion; elsewhere it ends in an ellipsis). */
open class MarqueeText(context: Context) : View(context) {
    var text: CharSequence = ""
        set(value) {
            field = value
            textDirty = true
            requestLayout()
            invalidate()
        }

    var role: Role = Role.Body
        set(value) {
            field = value
            textDirty = true
            requestLayout()
            invalidate()
        }

    /** The text color; the theme's text color when null. */
    var color: Int? = null
        set(value) {
            field = value
            invalidate()
        }

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var overflow = 0f
    private var ellipsized: CharSequence = ""
    private var textDirty = true
    private var roll: ValueAnimator? = null
    private var offset = 0f

    private fun prepare() {
        val t = theme
        paint.style(t, role, color ?: t.palette.text)
        val wide = paint.measureText(text, 0, text.length)
        val room = width.toFloat()
        overflow = maxOf(0f, wide - room)
        ellipsized = if (overflow > 0f && !Animation.full) TextUtils.ellipsize(text, paint, room, TextUtils.TruncateAt.END) else text
        textDirty = false
        roll?.cancel()
        roll = null
        offset = 0f
        if (overflow > 0f && Animation.full && isAttachedToWindow) startRoll()
    }

    private fun startRoll() {
        // The roll: there and back at 36 dp a second, resting a little at each end (12% and 12% of each way).
        val seconds = maxOf(3f, overflow / dp(36f) / 0.76f)
        roll = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (seconds * 1000).toLong()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                offset = -overflow * ((f - 0.12f) / 0.76f).coerceIn(0f, 1f)
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        paint.style(theme, role, 0)
        val wide = Math.ceil(paint.measureText(text, 0, text.length).toDouble()).toInt()
        val w = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(wide, MeasureSpec.getSize(widthMeasureSpec))
            else -> wide
        }
        // A line of its own, not the font's: an emoji from another font has a taller line.
        val line = Math.ceil((paint.textSize * (theme.style(role).lineHeight ?: 1.3f)).toDouble()).toInt()
        setMeasuredDimension(w, if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else line)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        textDirty = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        textDirty = true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        roll?.cancel()
        roll = null
        textDirty = true
    }

    override fun onDraw(canvas: Canvas) {
        if (textDirty) prepare()
        paint.color = color ?: theme.palette.text
        canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.drawTextCentered(ellipsized, offset, height / 2f, paint)
        canvas.restore()
    }
}

/** A placeholder block that pulses while content loads (in full motion; elsewhere it holds still). */
open class Skeleton(context: Context) : View(context) {
    /** The corner radius in dp; the theme's small radius when null. */
    var radius: Float? = null
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var pulse: ValueAnimator? = null
    private var shade = 0.1f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Animation.full) {
            // 0.04 -> 0.14 -> 0.04 of the text color over 1.4 s.
            pulse = ValueAnimator.ofFloat(0.04f, 0.14f).apply {
                duration = 700
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = EASE
                addUpdateListener {
                    shade = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulse?.cancel()
        pulse = null
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        val r = dp(radius ?: t.radius.small)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = t.palette.surfaceRaised
        canvas.drawRoundRect(rect, r, r, paint)
        paint.color = withAlpha(t.palette.text, shade)
        canvas.drawRoundRect(rect, r, r, paint)
    }
}

/**
 * A ring that fills while a press is held (a task confirmed by holding, a scene that needs intent): hold the view for [holdMs]
 * and [onComplete] runs; let go earlier and the ring empties.
 */
open class HoldProgress(context: Context) : View(context) {
    /** How long the press must be held, in ms. */
    var holdMs: Long = 600
    var onComplete: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var progress = 0f
    private var running: ValueAnimator? = null
    private var done = false

    init {
        isClickable = true
    }

    private fun begin() {
        done = false
        running?.cancel()
        running = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = holdMs
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
                if (progress >= 1f && !done) {
                    done = true
                    onComplete?.invoke()
                }
            }
            start()
        }
    }

    private fun end() {
        running?.cancel()
        running = null
        progress = 0f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(theme.density.iconCircle)
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> end()
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        end()
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        val stroke = dp(2f)
        rect.set(stroke, stroke, width - stroke, height - stroke)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = t.palette.border
        canvas.drawOval(rect, paint)
        if (progress > 0f) {
            paint.color = t.palette.accent
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawArc(rect, -90f, 360f * progress, false, paint)
        }
    }
}

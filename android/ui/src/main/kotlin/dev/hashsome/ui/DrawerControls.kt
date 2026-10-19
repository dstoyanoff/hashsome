package dev.hashsome.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import dev.hashsome.ui.icons.Ui

// The controls a drawer is made of (the web's `drawer-controls.tsx` and `history-section.tsx`): a step button, a bar with a gradient
// track, a power button, a row of swatches, and the History section.

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Fine adjustment (the web's `StepButton`): a press nudges by [delta]; holding repeats in steps of [holdDelta] every 400 ms until released. */
internal class StepButtonView(context: Context, private val icon: Icon, private val delta: Int, private val onStep: (Int) -> Unit) : View(context) {
    var holdDelta: Int = delta * 10
    private val glyph = iconDrawable(icon)
    private val handler = Handler(Looper.getMainLooper())
    private var held = false
    private val repeat = object : Runnable {
        override fun run() {
            held = true
            onStep(holdDelta)
            handler.postDelayed(this, REPEAT_MS)
        }
    }

    init {
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(SIZE), dp(SIZE))
    }

    override fun onDraw(canvas: Canvas) {
        glyph.setTint(theme.palette.text)
        val s = dp(8)
        glyph.setBounds(width / 2 - s, height / 2 - s, width / 2 + s, height / 2 + s)
        glyph.draw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                held = false
                handler.postDelayed(repeat, HOLD_MS)
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(repeat)
                if (!held && contains(event.x, event.y)) onStep(delta)
                held = false
            }

            MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(repeat)
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(repeat)
    }

    companion object {
        const val SIZE = 36
        const val HOLD_MS = 400L
        const val REPEAT_MS = 400L
    }
}

/** One round power button with two states: filled with the accent while on, neutral while off (the web's `PowerButton`). */
internal class PowerButtonView(context: Context, private val onToggle: () -> Unit) : View(context) {
    var on: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val glyph = iconDrawable(Ui.power)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(SIZE), dp(SIZE))
    }

    override fun onDraw(canvas: Canvas) {
        val p = theme.palette
        paint.color = if (on) p.accent else p.surfaceRaised
        canvas.drawCircle(width / 2f, height / 2f, width / 2f, paint)
        glyph.setTint(if (on) p.accentText else p.line)
        val s = dp(8)
        glyph.setBounds(width / 2 - s, height / 2 - s, width / 2 + s, height / 2 + s)
        glyph.draw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && contains(event.x, event.y)) onToggle()
        return true
    }

    companion object {
        const val SIZE = 36
    }
}

/**
 * A bar like [BarView] whose track is a gradient (the web's `ValueBar` with a `gradient`): vivid up to the value and dimmed past it,
 * with no marker or thumb and no border. [fraction] is where the value is, 0..1.
 */
internal class GradientBarView(context: Context) : BarView(context) {
    var stops: IntArray = IntArray(0)
        set(value) {
            field = value
            shader = null
            invalidate()
        }

    private var shader: LinearGradient? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = android.graphics.Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        shader = null
    }

    override fun onDraw(canvas: Canvas) {
        if (stops.size < 2 || width == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val gradient = shader ?: LinearGradient(0f, 0f, w, 0f, stops, null, Shader.TileMode.CLAMP).also { shader = it }
        rect.set(0f, 0f, w, h)
        clip.reset()
        clip.addRoundRect(rect, h / 2, h / 2, android.graphics.Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        paint.shader = gradient
        canvas.drawRect(rect, paint)
        paint.shader = null
        // Past the value the track is dimmed by the surface at 0.72, from at least 4% of the width.
        val from = w * maxOf(fraction.coerceIn(0f, 1f), DIM_MIN)
        paint.color = theme.palette.surface
        paint.alpha = (255 * DIM_ALPHA).toInt()
        canvas.drawRect(from, 0f, w, h, paint)
        paint.alpha = 255
        canvas.restore()
    }

    private companion object {
        const val DIM_MIN = 0.04f
        const val DIM_ALPHA = 0.72f
    }
}

/** A filled circle of one color (the dot after "Color" that shows what the light is now). */
internal class DotView(context: Context, private val sizeDp: Int = 16) : View(context) {
    var color: Int? = null
        set(value) {
            field = value
            visibility = if (value == null) GONE else VISIBLE
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(sizeDp), dp(sizeDp))
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = color ?: return
        canvas.drawCircle(width / 2f, height / 2f, width / 2f, paint)
    }
}

/** A swatch of the row: the color its dot is, and whether it is the one the light shows. */
internal class Swatch(val label: String, val color: Int, val selected: Boolean, val onPick: () -> Unit)

/** The row of circular choices (the web's `SwatchRow`): 30 dp cells of a 20 dp dot, the selected one ringed in the page's own colors. */
internal class SwatchRowView(context: Context) : View(context) {
    var swatches: List<Swatch> = emptyList()
        set(value) {
            // Told on every change of the light, most of them no change of the row: lay out only for one that is.
            val same = field.size == value.size && field.indices.all {
                field[it].color == value[it].color && field[it].selected == value[it].selected && field[it].label == value[it].label
            }
            field = value
            if (!same) {
                requestLayout()
                invalidate()
            }
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun cell() = dp(CELL)
    private fun gap() = Math.round(0.5f * unitPx())

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val n = swatches.size
        setMeasuredDimension(if (n == 0) 0 else n * cell() + (n - 1) * gap(), cell())
    }

    override fun onDraw(canvas: Canvas) {
        val p = theme.palette
        swatches.forEachIndexed { i, s ->
            val cx = i * (cell() + gap()) + cell() / 2f
            val cy = height / 2f
            val r = dp(DOT) / 2f
            if (s.selected) {
                paint.color = p.text
                canvas.drawCircle(cx, cy, r + dp(4f), paint)
                paint.color = p.surface
                canvas.drawCircle(cx, cy, r + dp(2f), paint)
            }
            paint.color = s.color
            canvas.drawCircle(cx, cy, r, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && contains(event.x, event.y)) {
            val i = (event.x / (cell() + gap())).toInt()
            swatches.getOrNull(i)?.onPick?.invoke()
        }
        return true
    }

    companion object {
        const val CELL = 30
        const val DOT = 20
    }
}

package dev.hashsome.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import dev.hashsome.client.Cancel

// Small pieces the components are put together from. Internal: a consumer composes components, not these.

/** A glyph of [sizeDp] in [color], drawn as a cached bitmap (a weather glyph with colors of its own ignores [color]). */
internal class GlyphView(context: Context, icon: Icon? = null, sizeDp: Int = 16) : View(context) {
    var icon: Icon? = icon
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var sizeDp: Int = sizeDp
        set(value) {
            field = value
            requestLayout()
        }

    var color: Int = Hashsome.theme.palette.text
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(sizeDp)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val glyph = icon ?: return
        canvas.drawBitmap(iconBitmap(glyph, width, color), 0f, 0f, null)
    }
}

/** A one pixel line across a row or column, in the border color (or [color]). */
internal class HairlineView(context: Context) : View(context) {
    var color: Int? = null
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // No width of its own: in a row it grows into what is left; in a column it is stretched.
        val exact = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY
        setMeasuredDimension(if (exact) MeasureSpec.getSize(widthMeasureSpec) else 0, dp(1))
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = color ?: theme.palette.border
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}

/** A row in the surface color with a hairline border and fully round ends: the web's top bar chip. */
internal class PillView(context: Context) : RowLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        gap = 2
        align = Align.Center
        setWillNotDraw(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val u = unitPx()
        val vertical = Math.round(2.5f * u)
        val horizontal = Math.round(3.5f * u)
        if (paddingLeft != horizontal || paddingTop != vertical) setPadding(horizontal, vertical, horizontal, vertical)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        val r = height / 2f
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
}

/** A line of text for [role] in [color] (the theme's text color when null), as a component's label. */
internal fun labelText(context: Context, text: CharSequence, role: Role, color: Int? = null): MarqueeText =
    MarqueeText(context).also {
        it.role = role
        it.color = color
        it.text = text
    }

/**
 * Calls [fn] with the wall time now and at each minute's start until [onDetach]'s cancel runs. The clock and the date change when
 * the minute does, not up to a minute late.
 */
internal fun minuteTicks(onDetach: (Cancel) -> Unit, fn: (Long) -> Unit) {
    val scheduler = Hashsome.scheduler
    var cancel: Cancel = {}
    var stopped = false

    fun schedule() {
        cancel = scheduler.after(WallClockText.untilNextMinute(scheduler.wallMs())) {
            if (!stopped) {
                fn(scheduler.wallMs())
                schedule()
            }
        }
    }

    fn(scheduler.wallMs())
    schedule()
    onDetach {
        stopped = true
        cancel()
    }
}

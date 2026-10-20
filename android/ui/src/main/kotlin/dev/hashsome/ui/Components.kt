package dev.hashsome.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent

// The page navigation. The other M1 components are in `TopRow.kt` and `Tiles.kt`; each is a thin view over a headless control, so a
// consumer can subclass it or write its own against the same control.

/** Something on a page that needs a look: a dot on its rail item, warm for a [Notice], red for an [Urgent] one. */
enum class Attention { Notice, Urgent }

/**
 * The page navigation down the side, with an attention dot on a page that needs a look. A column [NAV_RAIL] dp wide in the rail
 * color; each page is a 44 dp rounded square (the theme's `chrome` radius) with its glyph, lit in the accent while it is open
 * (the change fades). The items and the gaps between them are in spacing units, so the rail follows the density.
 */
open class NavRailView(context: Context, val pages: List<PageSpec>) : BoundView(context) {
    /** The index of the page open. */
    var active: Int = 0
        set(value) {
            if (field == value) return
            field = value
            lit.forEachIndexed { i, e -> e.to(if (i == value) 1f else 0f) }
        }

    /** The pages that need a look, by index. */
    var attention: Map<Int, Attention> = emptyMap()
        set(value) {
            field = value
            invalidate()
        }

    /** A page's item was tapped. */
    var onSelect: ((Int) -> Unit)? = null

    private val lit = pages.mapIndexed { i, _ -> Eased(this, if (i == active) 1f else 0f, 160) }
    private val glyphs = pages.map { iconDrawable(it.icon) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        setWillNotDraw(false)
    }

    private fun itemTop(i: Int): Float = 6 * unitPx() + i * (dp(ITEM.toFloat()) + 2.5f * unitPx())

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(NAV_RAIL), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        paint.style = Paint.Style.FILL
        paint.color = t.palette.rail
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val size = dp(ITEM.toFloat())
        val left = (width - size) / 2
        pages.indices.forEach { i ->
            val top = itemTop(i)
            val on = lit[i].value
            if (on > 0.01f) {
                paint.color = withAlpha(t.palette.accent, on)
                rect.set(left, top, left + size, top + size)
                canvas.drawRoundRect(rect, dp(t.radius.chrome), dp(t.radius.chrome), paint)
            }
            val g = glyphs[i]
            g.setTint(mix(t.palette.line, t.palette.accentText, on))
            val s = dp(GLYPH / 2).toInt()
            val cx = (left + size / 2).toInt()
            val cy = (top + size / 2).toInt()
            g.setBounds(cx - s, cy - s, cx + s, cy + s)
            g.draw(canvas)
            attention[i]?.let { level ->
                // 10 dp at 6 dp in from the item's top right corner; its ring is the rail's own color, so it reads as cut out of the button.
                val x = left + size - dp(6f) - dp(5f)
                val y = top + dp(6f) + dp(5f)
                paint.color = t.palette.rail
                canvas.drawCircle(x, y, dp(7f), paint)
                paint.color = if (level == Attention.Urgent) t.palette.danger else t.palette.warm
                canvas.drawCircle(x, y, dp(5f), paint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val size = dp(ITEM.toFloat())
            val i = pages.indices.firstOrNull { event.y >= itemTop(it) - dp(4f) && event.y <= itemTop(it) + size + dp(4f) }
            if (i != null) onSelect?.invoke(i)
        }
        return true
    }

    private companion object {
        const val ITEM = 44
        const val GLYPH = 22f
    }
}

package dev.hashsome.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.view.View
import android.view.ViewGroup
import dev.hashsome.ui.icons.Ui

/**
 * The page every dashboard renders into (the web's `Page`): padded by one space on every side, plus the room for a nav rail,
 * a column with a gap of one space between its children. The height left over after as many whole modules as fit is shared
 * between the top and the bottom padding, so the content sits centered and every card stays on the module grid.
 */
internal class PageLayout(context: Context) : ColumnLayout(context) {
    /** The width a nav rail takes at the left edge, in dp. */
    var leftInsetDp: Int = 0

    init {
        gap = 3
        align = Align.Stretch
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = Hashsome.theme.density
        val space = dp(density.space)
        var top = 0
        var bottom = 0
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            val heightDp = MeasureSpec.getSize(heightMeasureSpec) / screenDensity
            val centering = centeringOffsets(heightDp - 2 * density.space, gridMetrics(density.space).module)
            top = dp(centering.top)
            bottom = dp(centering.bottom)
        }
        val left = space + dp(leftInsetDp)
        if (paddingLeft != left || paddingTop != space + top || paddingRight != space || paddingBottom != space + bottom) {
            setPadding(left, space + top, space, space + bottom)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/**
 * Holds a page and scrolls it when its content is taller than the screen (the web's page scrolls). The page is measured at the
 * screen's height when its content fits (so a cell that fills, a spacer that grows and the centering all work), and at its content's
 * height when it does not.
 */
internal class PageScroll(context: Context, val page: PageLayout) : android.widget.ScrollView(context) {
    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(page, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}

/**
 * The top bar of a page (title, and a switcher between dashboards): the title in the `title` style and, when the display
 * offers a switcher, a chevron after it. The switcher's menu and the scenes are the tablets' (M2); this draws the bar.
 */
internal class TopBarView(context: Context, private val title: String, private val switcher: List<DashboardOption>) : View(context) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = iconDrawable(Ui.chevronDown)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(TOP_ROW))
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        paint.style(t, Role.Title, t.palette.text)
        canvas.drawTextCentered(title, 0f, height / 2f, paint)
        if (switcher.isNotEmpty() && Hashsome.settings.switcherEnabled.value) {
            val x = paint.measureText(title) + dp(6f)
            glyph.setTint(t.palette.textMuted)
            val s = dp(8)
            glyph.setBounds(x.toInt(), height / 2 - s, x.toInt() + 2 * s, height / 2 + s)
            glyph.draw(canvas)
        }
    }
}

/** The empty space of `spacer()`: no size of its own (a plain `View` takes all the room it is offered), so it only grows. */
internal class Spacer(context: Context) : View(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(0, 0)
}

/** The params a view gets as a direct child of a page. */
internal fun pageParams(): ViewGroup.LayoutParams = FlexParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

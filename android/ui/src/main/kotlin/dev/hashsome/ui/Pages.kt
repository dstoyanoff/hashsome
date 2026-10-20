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

    /** The page's top row is pinned above the scroll (see [PageFrame]): the frame places the header, and the content's room starts under it. */
    var pinned: Boolean = false

    /** With [pinned]: the centering offsets the frame worked out for the whole page (the content gets the bottom one). */
    var centerBottom: Int = 0

    init {
        gap = 3
        align = Align.Stretch
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = Hashsome.theme.density
        val space = dp(density.space)
        var top = 0
        var bottom = 0
        if (pinned) {
            bottom = centerBottom
        } else if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            val heightDp = MeasureSpec.getSize(heightMeasureSpec) / screenDensity
            val centering = centeringOffsets(heightDp - 2 * density.space, gridMetrics(density.space).module)
            top = dp(centering.top)
            bottom = dp(centering.bottom)
        }
        val left = space + dp(leftInsetDp)
        val above = if (pinned) 0 else space
        if (paddingLeft != left || paddingTop != above + top || paddingRight != space || paddingBottom != space + bottom) {
            setPadding(left, above + top, space, space + bottom)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/**
 * Holds a page and scrolls it when its content is taller than the screen (the web's page scrolls). The page is measured at the
 * screen's height when its content fits (so a cell that fills, a spacer that grows and the centering all work), and at its content's
 * height when it does not, unless the page is [fixed] (then always at the screen's, and it never scrolls).
 */
internal class PageScroll(context: Context, val page: PageLayout, private val fixed: Boolean = false) : android.widget.ScrollView(context) {
    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(page, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // `fillViewport` compares the content with the whole height, not the room inside the padding (a pinned header's), so it is done here.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val room = measuredHeight - paddingTop - paddingBottom
        // A fixed page is always the room's height: what is taller is squeezed or scrolls in its own card, and the page never scrolls.
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED && (fixed || page.measuredHeight < room)) {
            page.measure(
                MeasureSpec.makeMeasureSpec(measuredWidth - paddingLeft - paddingRight, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(room, MeasureSpec.EXACTLY),
            )
        }
    }
}

/** The empty space of `spacer()`: no size of its own (a plain `View` takes all the room it is offered), so it only grows. */
internal class Spacer(context: Context) : View(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(0, 0)
}

/** The params a view gets as a direct child of a page. */
internal fun pageParams(): ViewGroup.LayoutParams = FlexParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

/** What a view is, for finding a page's top row without the views (so it is tested on the JVM). */
internal enum class PartKind { TopBar, Row, Chip, Other }

internal class PartNode(val kind: PartKind, val children: List<PartNode> = emptyList())

/** Whether the parts are a top row: a top bar, or a row that holds a chip (date, weather, clock, status) or another top row. */
internal fun isTopRow(node: PartNode?): Boolean = when (node?.kind) {
    PartKind.TopBar -> true
    PartKind.Row -> node.children.any { it.kind == PartKind.Chip || isTopRow(it) }
    else -> false
}

private fun View.part(): PartNode = when (this) {
    is TopBarRow -> PartNode(PartKind.TopBar)
    is DateChip, is WeatherChip, is Clock, is SystemStatus -> PartNode(PartKind.Chip)
    is RowLayout -> PartNode(PartKind.Row, (0 until childCount).map { getChildAt(it).part() })
    else -> PartNode(PartKind.Other)
}

internal fun isTopRow(view: View?): Boolean = isTopRow(view?.part())

/**
 * The geometry of a page with a pinned top row, in px: the header is where an unpinned page's would be (a space and the centering's
 * top offset down), so a page whose content fits is laid out as it was without pinning, and the content's room starts under it.
 */
internal object PinnedMath {
    fun headerTop(space: Int, centerTop: Int): Int = space + centerTop

    /** Where the scrolling content's room starts: under the header and the gap after it. */
    fun contentTop(space: Int, centerTop: Int, headerHeight: Int, gap: Int): Int = headerTop(space, centerTop) + headerHeight + gap
}

/**
 * A page whose top row stays put: the [header] is drawn at the top of the page's padding and only [page] scrolls, in the room
 * under it (clipped there, so nothing shows between the header and the content's edge). The web's top bar draws no edge or shadow
 * over scrolled content, and neither does this.
 */
internal class PageFrame(
    context: Context,
    private val header: View,
    private val page: PageLayout,
    private val leftInsetDp: Int,
    /** The gap after the header, in spacing units: the page's column's own. */
    private val gapUnits: Int,
    fixed: Boolean = false,
) : ViewGroup(context) {
    internal val scroll = PageScroll(context, page, fixed).also {
        it.clipToPadding = true
    }

    private var centerTop = 0

    init {
        page.pinned = true
        addView(scroll)
        addView(header)
    }

    private fun gap(): Int {
        val lp = header.layoutParams as? FlexParams
        return Math.round(gapUnits * unitPx()) + (lp?.bottomMargin ?: 0)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = Hashsome.theme.density
        val space = dp(density.space)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        // The page's own centering, over the whole height: as it is without a pinned header.
        val centering = centeringOffsets(h / screenDensity - 2 * density.space, gridMetrics(density.space).module)
        centerTop = dp(centering.top)
        page.centerBottom = dp(centering.bottom)
        val inner = w - space - dp(leftInsetDp) - space
        header.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val top = PinnedMath.contentTop(space, centerTop, header.measuredHeight, gap())
        if (scroll.paddingTop != top) scroll.setPadding(0, top, 0, 0)
        scroll.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        scroll.layout(0, 0, r - l, b - t)
        val space = dp(Hashsome.theme.density.space)
        val left = space + dp(leftInsetDp)
        val top = PinnedMath.headerTop(space, centerTop)
        header.layout(left, top, left + header.measuredWidth, top + header.measuredHeight)
    }
}

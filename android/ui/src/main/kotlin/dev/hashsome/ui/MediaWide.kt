package dev.hashsome.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import dev.hashsome.client.Cancel
import dev.hashsome.client.EntityRef
import dev.hashsome.headless.MediaBrowserControl
import dev.hashsome.headless.MediaPlayerControl
import dev.hashsome.headless.QueueControl
import dev.hashsome.headless.Scope
import dev.hashsome.headless.SpeakersControl

/**
 * The player for a wide space (the web's `MediaPlayerFull wide`): the player on the left and its queue beside it, and under them, across the whole
 * width, the library as one row of large cards. The player stays put; the queue and the row scroll. A page that is one card of this is a music page.
 */
open class MediaPlayerWideView(context: Context, val control: MediaPlayerControl, val label: String) : BoundView(context) {
    /** The speakers panel has a button that widens it (off by default). */
    var expandable: Boolean = false

    /** The speakers that may be grouped with this player; empty for any it can be grouped with. Set before the view is shown. */
    var speakers: List<EntityRef> = emptyList()

    /** A tap on the artwork opens the full-screen player ([NowPlayingScreen]). Set before the view is shown. */
    var fullScreen: Boolean = false

    private var own: Scope? = null
    private var pillView: SpeakersPillView? = null
    private var grouping: SpeakersControl? = null
    private var watching: Cancel? = null

    private fun build() {
        removeAllViews()
        val scope = control.scope.child()
        own = scope
        val queue = QueueControl(control.handle, scope)
        // Shuffle and the speakers are keys at the two ends of the transport row, as in the full player.
        val pill = SpeakersPillView(context, compact = true) { openSpeakers(context, control, speakers, label, null, expandable) }
        pill.fillKey(theme.density.iconCircle)
        pill.strongGlyph()
        pillView = pill
        grouping = null
        val player = NowPlayingView(
            context, control, label, queue, shuffleAtTitle = false, extra = pill,
            onOpenArtwork = if (fullScreen) ({ NowPlayingScreen.open(context, control, label) }) else null,
        )
        val centered = Capped(context, PLAYER_WIDTH).also { it.fill = true }
        centered.addView(player)
        val queueView = MediaQueueView(context, QueueControl(control.handle, scope))
        val library = MediaBrowserView(context, MediaBrowserControl(control.handle, scope)).also { it.layout = BrowseLayout.Theater }
        val layout = WideLayout(context)
        layout.addView(centered)
        layout.addView(queueView)
        layout.addView(library)
        layout.addView(View(context).apply { setBackgroundColor(theme.palette.border) })
        layout.addView(View(context).apply { setBackgroundColor(theme.palette.border) })
        addView(layout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        build()
        watching = control.handle.state.listen { update() }
        update()
    }

    private fun update() {
        val canGroup = control.ready && control.handle.entity?.capabilities?.group == true
        if (canGroup && grouping == null) {
            own?.let { scope -> grouping = SpeakersControl(control.handle, scope, speakers).also { pillView?.use(it) } }
        }
        // Kept in its place when the player cannot be grouped, so the keys do not shift.
        pillView?.visibility = if (canGroup) View.VISIBLE else View.INVISIBLE
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        watching?.invoke()
        watching = null
        own?.dispose()
        own = null
    }

    private companion object {
        /** The widest the player gets, in dp. */
        const val PLAYER_WIDTH = 420
    }
}

/**
 * Its five parts: the player (top left), the queue (top right), the library (along the bottom) and the two hairlines between them. The library has
 * the height its cards want, the player and the queue the rest; the queue is a third of the width.
 */
private class WideLayout(context: Context) : ViewGroup(context) {
    private fun gap() = Math.round(5 * unitPx())

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val gap = gap()
        // The cards want a search field, the tabs and their own height; never more than the half of the room, and the top never less than what a player needs.
        val library = Math.min(dp(LIBRARY_DP), (h - gap) / 2).coerceAtLeast(Math.min(dp(LIBRARY_MIN_DP), h / 2))
        val top = h - library - gap
        val queue = (w * QUEUE_SHARE).toInt().coerceIn(dp(QUEUE_MIN_DP), dp(QUEUE_MAX_DP))
        val player = w - queue - gap
        fun exact(v: Int) = MeasureSpec.makeMeasureSpec(Math.max(0, v), MeasureSpec.EXACTLY)
        getChildAt(0).measure(exact(player), exact(top))
        getChildAt(1).measure(exact(queue), exact(top))
        getChildAt(2).measure(exact(w), exact(library))
        getChildAt(3).measure(exact(dp(1)), exact(top))
        getChildAt(4).measure(exact(w), exact(dp(1)))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val gap = gap()
        val player = getChildAt(0)
        val queue = getChildAt(1)
        val library = getChildAt(2)
        player.layout(0, 0, player.measuredWidth, player.measuredHeight)
        val queueLeft = player.measuredWidth + gap
        queue.layout(queueLeft, 0, queueLeft + queue.measuredWidth, queue.measuredHeight)
        val line = getChildAt(3)
        line.layout(player.measuredWidth + gap / 2, 0, player.measuredWidth + gap / 2 + line.measuredWidth, line.measuredHeight)
        val top = queue.measuredHeight + gap
        library.layout(0, top, library.measuredWidth, top + library.measuredHeight)
        val rule = getChildAt(4)
        rule.layout(0, top - gap / 2, rule.measuredWidth, top - gap / 2 + rule.measuredHeight)
    }

    private companion object {
        /** The library's height: a search field, the tabs and a row of cards, in dp; and the least it gets. */
        const val LIBRARY_DP = 360
        const val LIBRARY_MIN_DP = 240

        /** The queue's share of the width, and its least and most, in dp. */
        const val QUEUE_SHARE = 0.34f
        const val QUEUE_MIN_DP = 300
        const val QUEUE_MAX_DP = 460
    }
}

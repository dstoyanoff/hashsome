package dev.hashsome.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.hashsome.client.AssetSize
import dev.hashsome.client.BrowseItem
import dev.hashsome.client.BrowseKind
import dev.hashsome.client.Cancel
import dev.hashsome.client.MediaPlayerCommands
import dev.hashsome.client.MediaPlayerEntity
import dev.hashsome.client.PlayMode
import dev.hashsome.client.PlaybackState
import dev.hashsome.client.QueueItem
import dev.hashsome.headless.BrowserState
import dev.hashsome.headless.EntityHandle
import dev.hashsome.headless.Scope
import dev.hashsome.headless.MediaBrowserControl
import dev.hashsome.headless.QueueControl
import dev.hashsome.headless.QueueState
import dev.hashsome.headless.RowsArrival
import dev.hashsome.ui.icons.Ui

// The lists a player opens: its library, its queue and its speakers. Each is a view over a headless control made for as long as
// the panel is open (`openMediaOverlay` gives it a scope of its own), so a panel nobody opened asks the runtime for nothing.

private fun wrap() = FlexParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

private fun across() = FlexParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

private fun room() = FlexParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
    it.grow = true
    it.shrink = true
}

/** A heading with a muted count after it on the same line: `Queue · 12 tracks · 48 min`. */
internal class HeadingLine(context: Context) : View(context) {
    var heading: String = ""
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var count: String = ""
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    private val big = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val t = theme
        big.style(t, Role.Heading, 0)
        small.style(t, Role.Secondary, 0)
        val wide = Math.ceil((big.measureText(heading) + small.measureText(count)).toDouble()).toInt()
        val w = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(wide, MeasureSpec.getSize(widthMeasureSpec))
            else -> wide
        }
        val line = Math.ceil((big.textSize * (t.style(Role.Heading).lineHeight ?: 1.3f)).toDouble()).toInt()
        setMeasuredDimension(w, line)
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        big.style(t, Role.Heading, t.palette.text)
        small.style(t, Role.Secondary, t.palette.textMuted)
        val baseline = height / 2f - (big.ascent() + big.descent()) / 2
        canvas.drawText(heading, 0f, baseline, big)
        canvas.drawText(count, big.measureText(heading), baseline, small)
    }
}

/** Placeholders shaped like the rows that are coming, in the room they take, so nothing moves when they arrive. */
internal class SkeletonList(context: Context, private val rows: Int = 6) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var pulse: ValueAnimator? = null
    private var shade = 0.1f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val u = unitPx()
        val row = dp(BrowseRow.PICTURE) + Math.round(3 * u)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), rows * row + (rows - 1) * Math.round(u))
    }

    override fun onVisibilityChanged(changed: View, visibility: Int) {
        super.onVisibilityChanged(changed, visibility)
        if (changed === this) sync()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sync()
    }

    /** The pulse runs only while the placeholders are on show: an animation nobody sees still costs a frame each time. */
    private fun sync() {
        pulse?.cancel()
        pulse = null
        if (Animation.full && isAttachedToWindow && visibility == VISIBLE) {
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

    private fun block(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float) {
        val p = theme.palette
        rect.set(l, t, r, b)
        paint.color = p.surfaceRaised
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.color = withAlpha(p.text, shade)
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    override fun onDraw(canvas: Canvas) {
        val u = unitPx()
        val side = dp(BrowseRow.PICTURE).toFloat()
        val row = side + 3 * u
        val small = dp(theme.radius.small)
        for (i in 0 until rows) {
            val top = i * (row + u)
            val x = 2 * u
            block(canvas, x, top + 1.5f * u, x + side, top + 1.5f * u + side, small)
            val textX = x + side + 3 * u
            val room = width - textX - 2 * u
            val middle = top + row / 2
            block(canvas, textX, middle - dp(14f) - 0.75f * u, textX + room * 0.6f, middle - 0.75f * u, small / 2)
            block(canvas, textX, middle + 0.75f * u, textX + room * 0.35f, middle + 0.75f * u + dp(12f), small / 2)
        }
    }
}

/** A line of text where a list would be (an error, nothing found). */
internal fun message(context: Context, text: String, tone: Tone = Tone.Muted): LabelView =
    LabelView(context, Role.Body, tone).also { it.text = text }

/** Whether the item is what the player plays or plays from: its title is lit and its button is the player's play/pause (`activeOf`). */
internal fun isActive(item: BrowseItem, player: MediaPlayerEntity?): Boolean {
    if (player == null) return false
    if (player.source != null && item.id == player.source!!.id) return true
    val media = player.media
    return item.kind == BrowseKind.Track && media?.title != null && item.title == media.title &&
        (item.subtitle == null || media.artist == null || item.subtitle == media.artist)
}

/**
 * A player's library: its shelves as tabs, a search with the kinds of thing to search for, and the rows of what is open, which arrive a row
 * at a time. Tap a folder to open it, a track to play it, or the play button of an album or playlist to play all of it; opened, the
 * chosen tab is the way back (and so is a swipe to the right), and Play, Shuffle and Add to queue act on the whole.
 */
/** How a library offers search: a field above the list ([Bar]), or an icon at the end of the row of tabs that becomes the field when pressed ([Icon]). */
enum class SearchMode { Bar, Icon }

open class MediaBrowserView private constructor(context: Context, lazyControl: Lazy<MediaBrowserControl>, @Suppress("UNUSED_PARAMETER") marker: Unit) :
    BoundView(context) {
    constructor(context: Context, control: MediaBrowserControl) : this(context, lazyOf(control), Unit)

    /** For a panel made ahead: the control is made when the view is first shown, so nothing is asked of the runtime while it is hidden. */
    constructor(context: Context, scope: Scope, handle: EntityHandle<MediaPlayerEntity>) :
        this(context, lazy(LazyThreadSafetyMode.NONE) { MediaBrowserControl(handle, scope) }, Unit)

    val control: MediaBrowserControl by lazyControl

    /** How search is offered; set before the view is shown. With [SearchMode.Icon], a library without tabs still gets the field above its list. */
    var searchMode: SearchMode = SearchMode.Bar

    /** Whether the field is open from the icon (it also stays while a search has results). */
    private var searchOpen = false

    private val searchRule = View(context)
    private val searchButton = GlyphButton(context, Ui.search, 18f).also { it.sizeDp = 30 }
    private val searchTrigger = RowLayout(context).also { it.gap = 2 }

    private val column = ColumnLayout(context).also { it.gap = 3 }
    private val header = RowLayout(context).also { it.gap = 2 }
    private val back = GlyphButton(context, Ui.arrowLeft, 16f).also { it.sizeDp = 28 }
    private val heading = LabelView(context, Role.Heading)
    private val search = SearchBar(context)
    private val tabsRow = RowLayout(context).also { it.gap = 3 }
    private val chips = ChipRowView(context)
    private val actions = RowLayout(context).also { it.gap = 2 }
    private val area = FrameLayout(context)
    private val scroll = LibraryScroll(context)
    private val host = LinearLayout(context).also { it.orientation = LinearLayout.VERTICAL }
    private val skeleton = SkeletonList(context)
    private val note = FrameLayout(context)
    private val rows = ArrayList<BrowseRow>()
    private val pictures = ArrayList<ArtworkSlot>()
    private var items: List<BrowseItem> = emptyList()
    private var shownRows: List<BrowseItem>? = null
    private var summary: LabelView? = null
    private var limit = 0

    init {
        header.addView(back, wrap().also { it.rightMargin = 0 })
        header.addView(heading, wrap().also { it.grow = true })
        tabsRow.addView(chips, FlexParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ChipRowView.CHIP_HEIGHT)).also {
            it.grow = true
            it.shrink = true
        })
        scroll.addView(host, FrameLayout.LayoutParams(-1, -2))
        area.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        area.addView(skeleton, FrameLayout.LayoutParams(-1, -2))
        area.addView(note, FrameLayout.LayoutParams(-1, -2))
        searchTrigger.addView(searchRule, FlexParams(dp(1), dp(20)))
        searchTrigger.addView(searchButton, FlexParams(dp(30), dp(30)))
        searchButton.contentDescription = "Search"
        searchButton.onClick = {
            searchOpen = true
            render()
            search.focusInput()
        }
        search.onClose = {
            searchOpen = false
            search.setText("")
            control.search("")
            render()
        }
        column.addView(header, across())
        column.addView(search, across())
        column.addView(tabsRow, across())
        column.addView(area, room())
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        back.onClick = { control.back() }
        search.onChange = { control.search(it) }
        scroll.onNearEnd = {
            // Not while the list is being laid out: the rows are added a moment later.
            scroll.post {
                if (limit < items.size) {
                    limit += RowsArrival.MORE_ROWS
                    control.arrival.more()
                    fill()
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Where nothing moves there is no arriving to see (the web's `useAnimates`).
        control.arrival.stagger = Animation.animates
        val redraw: (Any?) -> Unit = { render() }
        onDetach(control.state.listen(redraw))
        onDetach(control.handle.state.listen { updateRows() })
        onDetach(control.feedback.listen { updateRows() })
        onDetach(control.arrival.visible.listen { fill() })
        render()
        onDetach {
            pictures.forEach { it.stop() }
        }
    }

    private fun queueable(): Boolean = control.handle.entity?.capabilities?.queue == true

    private fun render() {
        val s = control.state.value
        val caps = control.handle.entity?.capabilities
        val inside = s.stack.isNotEmpty() && s.search == null
        val tabs = s.tabs.isNotEmpty()

        header.visibility = if (tabs) GONE else VISIBLE
        back.visibility = if (s.stack.isNotEmpty()) VISIBLE else GONE
        heading.text = s.stack.lastOrNull()?.title ?: "Library"
        // `Icon`: the field is closed until the icon is pressed, and then replaces the row of tabs (until there are results to narrow by kind).
        val iconMode = searchMode == SearchMode.Icon && caps?.search == true && tabs
        val field = iconMode && (searchOpen || s.search != null)
        search.visibility = if (caps?.search != true) GONE else if (iconMode && !field) GONE else VISIBLE
        search.closable = iconMode
        searchRule.setBackgroundColor(theme.palette.border)
        searchTrigger.visibility = if (iconMode && !field && !inside) VISIBLE else GONE
        // What the field shows follows the library: opening something or going back clears it.
        val typed = search.input.text.toString()
        if (s.search == null && typed.isNotBlank()) search.setText("") else if (s.search != null && typed != s.search) search.setText(s.search!!)

        tabsRow.visibility = if (tabs && !(field && s.search == null)) VISIBLE else GONE
        if (tabs) {
            if (s.search != null && s.kinds.isNotEmpty()) {
                chips.chips = s.kinds.map { ChipRowView.Chip(MediaFormat.kindLabel(it)) }
                chips.current = s.kinds.indexOf(s.kind).coerceAtLeast(0)
                chips.onSelect = { i -> s.kinds.getOrNull(i)?.let { control.search(s.search, it) } }
            } else {
                // Inside a shelf the chosen tab becomes the way back, so nothing is added to the layout and nothing below it moves.
                chips.chips = s.tabs.mapIndexed { i, tab ->
                    if (i == s.active && inside) ChipRowView.Chip(s.stack.last().title, Ui.arrowLeft) else ChipRowView.Chip(tab.title)
                }
                chips.current = s.active
                chips.onSelect = { i -> if (i == s.active && inside) control.back() else control.openTab(i) }
            }
        }

        // What can be done with the whole album, playlist or artist that is open sits at the end of the row of tabs (or of the heading).
        // They are in the row of tabs when there is one, and in the heading when there are no tabs.
        val home: ViewGroup = if (tabs) tabsRow else header
        if (actions.parent !== home) {
            (actions.parent as? ViewGroup)?.removeView(actions)
            home.addView(actions, wrap())
        }
        setActions(if (inside) s.stack.last().item else null)
        // The search icon is always the last thing in the row.
        if (searchTrigger.parent !== tabsRow || tabsRow.getChildAt(tabsRow.childCount - 1) !== searchTrigger) {
            (searchTrigger.parent as? ViewGroup)?.removeView(searchTrigger)
            tabsRow.addView(searchTrigger, wrap())
        }
        scroll.onSwipeBack = if (inside || s.stack.isNotEmpty()) ({ control.back(); Unit }) else null
        content(s)
    }

    private fun setActions(opened: BrowseItem?) {
        actions.removeAllViews()
        // Nothing to show costs no gap in the row.
        actions.visibility = if (opened == null || !opened.playable) GONE else VISIBLE
        if (opened == null || !opened.playable) return
        val pill = { icon: Icon, label: String, primary: Boolean, run: () -> Unit ->
            PillButton(context, icon, null, Role.Body).also {
                it.contentDescription = label
                it.heightDp = ChipRowView.CHIP_HEIGHT
                it.padUnits = 3f
                it.primary = primary
                // The ones that are not the main action are ghost, as the speakers' pills: no fill until pressed.
                it.flat = !primary
                it.onClick = run
            }
        }
        // Playing it replaces the queue, as in Spotify.
        actions.addView(pill(UiIcons.play, "Play", true) { play(opened) }, wrap())
        if (queueable()) {
            actions.addView(pill(UiIcons.shuffle, "Shuffle", false) { play(opened, shuffle = true) }, wrap())
            actions.addView(pill(Ui.listPlus, "Add to queue", false) { control.play(opened, PlayMode.Add) }, wrap())
        }
    }

    private fun play(item: BrowseItem, shuffle: Boolean? = null) {
        PlayRequests.note(control.handle.ref, item.id, item.title)
        control.play(item, PlayMode.Replace, shuffle)
    }

    /** What is below the tabs: the placeholders, a message, or the rows. */
    private fun content(s: BrowserState) {
        val error = s.error
        skeleton.visibility = GONE
        note.visibility = GONE
        scroll.visibility = GONE
        when {
            error != null -> {
                note.removeAllViews()
                note.addView(message(context, error, Tone.Danger), FrameLayout.LayoutParams(-1, -2))
                note.visibility = VISIBLE
                clearRows()
            }

            s.loading -> {
                skeleton.visibility = VISIBLE
                clearRows()
            }

            s.rows.isEmpty() -> {
                note.removeAllViews()
                note.addView(message(context, if (s.search != null) "Nothing found." else "Nothing here."), FrameLayout.LayoutParams(-1, -2))
                note.visibility = VISIBLE
                clearRows()
            }

            else -> {
                scroll.visibility = VISIBLE
                if (shownRows != s.rows) {
                    // Another list: it arrives as new rows, from the top.
                    clearRows()
                    shownRows = s.rows
                    items = s.rows
                    scroll.scrollTo(0, 0)
                }
                fill()
            }
        }
    }

    private fun clearRows() {
        host.removeAllViews()
        rows.clear()
        pictures.forEach { it.stop() }
        pictures.clear()
        shownRows = null
        items = emptyList()
        summary = null
        limit = 0
    }

    /** Adds the rows the arrival has let in. */
    private fun fill() {
        val s = control.state.value
        if (shownRows == null || s.loading || s.error != null) return
        // The first screenful is the rows that fit (and one more); the rest come as the end is scrolled to.
        val rowPitch = BrowseRow.rowHeight() + Math.round(unitPx())
        val fit = if (scroll.height > 0) scroll.height / rowPitch + 2 else RowsArrival.FIRST_ROWS
        if (limit < fit) limit = fit
        val visible = control.arrival.visible.value.coerceAtMost(items.size).coerceAtMost(limit)
        val searching = s.search != null
        while (rows.size < visible) {
            val index = rows.size
            val row = BrowseRow(context)
            val slot = ArtworkSlot(AssetSize.Small, { dp(BrowseRow.PICTURE) }) { row.picture = it }
            rows += row
            pictures += slot
            wire(row, items[index])
            bindRow(index)
            host.addView(row, LinearLayout.LayoutParams(-1, BrowseRow.rowHeight()).also { if (index > 0) it.topMargin = Math.round(unitPx()) })
            // A row comes in sliding from the right, the ones after the first starting a little later (not while searching).
            if (!searching) row.arrive(0)
        }
        // What is in what is open, at the end of its list: a line above the rows would push them down on opening.
        if (s.stack.isNotEmpty() && s.search == null && rows.size >= items.size) {
            if (summary == null) {
                summary = LabelView(context, Role.Secondary, Tone.Muted).also {
                    it.text = MediaFormat.summary(items)
                    host.addView(it, LinearLayout.LayoutParams(-1, -2).also { p -> p.topMargin = Math.round(2 * unitPx()) })
                }
            }
        }
    }

    private fun wire(row: BrowseRow, item: BrowseItem) {
        row.onOpen = { control.open(item) }
        row.onPlay = { play(item) }
        row.onToggle = { control.handle.command(MediaPlayerCommands.togglePlay()) }
        row.onNext = { control.play(item, PlayMode.Next) }
        row.onAdd = { control.play(item, PlayMode.Add) }
    }

    private fun bindRow(index: Int) {
        val item = items.getOrNull(index) ?: return
        val player = control.handle.entity
        rows[index].bind(item, queueable(), isActive(item, player), player?.playback == PlaybackState.Playing, control.feedback.value)
        pictures[index].set(item.artworkUrl)
    }

    private fun updateRows() {
        for (i in rows.indices) bindRow(i)
        // The actions of the open item depend on the player having a queue.
        val s = control.state.value
        if (s.stack.isNotEmpty() && s.search == null) setActions(s.stack.last().item)
    }
}

/**
 * A player's queue: the tracks that have just played (dimmed), the one playing, and what comes next, in a list that scrolls. Tap a track
 * to jump to it, the cross takes it out, and Clear takes out everything after the track playing, which carries on. What comes next is put
 * in another order by dragging a track by its handle.
 */
open class MediaQueueView private constructor(context: Context, lazyControl: Lazy<QueueControl>, @Suppress("UNUSED_PARAMETER") marker: Unit) :
    BoundView(context) {
    constructor(context: Context, control: QueueControl) : this(context, lazyOf(control), Unit)

    /** For a panel made ahead: the control (and its poll) starts when the view is first shown. */
    constructor(context: Context, scope: Scope, handle: EntityHandle<MediaPlayerEntity>) :
        this(context, lazy(LazyThreadSafetyMode.NONE) { QueueControl(handle, scope) }, Unit)

    val control: QueueControl by lazyControl

    private val column = ColumnLayout(context).also { it.gap = 2 }
    private val head = RowLayout(context).also { it.gap = 2 }
    private val heading = HeadingLine(context)
    private val source = LabelView(context, Role.Secondary, Tone.Muted)
    private val clear = PillButton(context, Ui.trash2, "Clear", Role.Label).also {
        it.heightDp = 32
        it.padUnits = 2.5f
        it.gapUnits = 1f
        it.muted = true
        it.flat = true
    }
    private val area = FrameLayout(context)
    private val scroll = LibraryScroll(context)
    private val host = LinearLayout(context).also { it.orientation = LinearLayout.VERTICAL }
    private val skeleton = SkeletonList(context)
    private val note = FrameLayout(context)
    private val arrivalScope by lazy(LazyThreadSafetyMode.NONE) { control.scope.child() }
    private val arrival by lazy(LazyThreadSafetyMode.NONE) { RowsArrival(arrivalScope).also { it.stagger = false } }
    private val rows = ArrayList<QueueRow>()
    private val pictures = ArrayList<ArtworkSlot>()
    private var tail: LabelView? = null
    private var good: QueueState? = null

    // The state the rows show, split into what has played (and the track playing) and what comes next.
    private var shown: QueueState? = null
    private var leading: List<QueueItem> = emptyList()
    private var upcoming: List<QueueItem> = emptyList()
    private var clearing = false

    // Rows that are leaving (taken out, or all of what comes next on Clear) by id, how far each has collapsed (1 is gone), until
    // the queue read afterwards no longer has them. A refused command brings them back.
    private class Leave(var p: Float, var from: Float = 0f)

    private val leaving = HashMap<String, Leave>()
    private var leaveAnim: ValueAnimator? = null
    private var batch: List<Leave> = emptyList()
    private var batchTo = 0f

    // The order of what comes next after a drag, until the queue read afterwards replaces it.
    private var draft: List<String>? = null
    private var draftOf: QueueState? = null
    private var draftTimer: Cancel? = null
    private var dragging = false
    private var deferred = false
    private var dragFrom = 0
    private var dragTarget = 0

    init {
        val titles = ColumnLayout(context)
        titles.addView(heading, wrap())
        titles.addView(source, wrap())
        head.addView(titles, wrap().also { it.grow = true; it.shrink = true })
        head.addView(clear, wrap())
        scroll.addView(host, FrameLayout.LayoutParams(-1, -2))
        area.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        area.addView(skeleton, FrameLayout.LayoutParams(-1, -2))
        area.addView(note, FrameLayout.LayoutParams(-1, -2))
        column.addView(head, across())
        column.addView(area, room())
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        heading.heading = "Queue"
        clear.onClick = {
            // As the web: pending from the press until the player has answered (ok or not).
            clearing = true
            val ids = upcoming.map { it.id }
            collapse(ids, toward = 1f)
            control.clear { ok ->
                clearing = false
                if (!ok) collapse(ids, toward = 0f)
                render()
            }
            render()
        }
        scroll.onNearEnd = { arrival.more() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val redraw: (Any?) -> Unit = {
            if (dragging) deferred = true else render()
        }
        onDetach(control.state.listen { state ->
            // A queue read afterwards replaces the order a drag put it in, and what it no longer has is no longer leaving.
            if (state !== draftOf) draft = null
            if (leaving.isNotEmpty() && !state.loading && state.error == null) {
                val ids = state.items.map { it.id }.toSet()
                leaving.keys.retainAll(ids)
            }
            redraw(state)
        })
        onDetach(control.handle.state.listen(redraw))
        onDetach(arrival.visible.listen { if (!dragging) layoutRows() })
        render()
        onDetach {
            pictures.forEach { it.stop() }
            arrivalScope.dispose()
            finishLeaving()
            draftTimer?.invoke()
        }
    }

    private fun render() {
        val raw = control.state.value
        if (raw.error == null && !raw.loading) good = raw
        // The queue is an extra: an error leaves what was last read on show, with the error under the heading.
        val state = if (raw.error != null) good ?: raw else raw
        val player = control.handle.entity
        val ready = control.handle.state.value is dev.hashsome.headless.HandleState.Ready
        val hasQueue = player?.capabilities?.queue == true
        shown = state
        // Waiting for the player's answer to Clear, whatever the list shows (an emptied one has no rows left to say so).
        clear.visibility = if (state.total > 0) VISIBLE else GONE
        clear.isEnabled = !clearing
        clear.pending = clearing
        skeleton.visibility = GONE
        note.visibility = GONE
        scroll.visibility = GONE
        head.visibility = if (ready && hasQueue) VISIBLE else GONE
        when {
            !ready -> say(mediaStatusLabel(control.handle.state.value).orEmpty())
            !hasQueue -> say("This player has no queue.")
            raw.error != null && good == null -> say(raw.error!!, Tone.Danger)
            state.loading && state.items.isEmpty() -> skeleton.visibility = VISIBLE
            state.items.isEmpty() -> {
                heading.count = MediaFormat.queueCount(state.total, state.duration)
                say("Nothing is queued. Play or add something from the library.")
            }

            else -> {
                heading.count = MediaFormat.queueCount(state.total, state.duration)
                val line = raw.error ?: player?.source?.let { MediaFormat.source(it) }
                source.text = line.orEmpty()
                source.tone = if (raw.error != null) Tone.Danger else Tone.Muted
                source.visibility = if (line == null) GONE else VISIBLE
                scroll.visibility = VISIBLE
                split(state)
                layoutRows()
            }
        }
    }

    private fun say(text: String, tone: Tone = Tone.Muted) {
        note.removeAllViews()
        note.addView(message(context, text, tone), FrameLayout.LayoutParams(-1, -2))
        note.visibility = VISIBLE
    }

    /** The tracks up to and with the one playing, and what comes next (in the order a drag put it in, while that stands). */
    private fun split(state: QueueState) {
        val at = state.items.indexOfFirst { it.current }
        val firstUpcoming = if (at == -1) state.items.size else at + 1
        leading = state.items.subList(0, firstUpcoming)
        val next = state.items.subList(firstUpcoming, state.items.size)
        val order = draft
        val byId = next.associateBy { it.id }
        upcoming = if (order != null && draftOf === state && order.size == next.size && order.all { it in byId }) order.map { byId.getValue(it) } else next
        arrival.update(upcoming.size)
    }

    /** The player has stopped on the last track and its position is at the end: it is over, not playing. */
    private fun finished(state: QueueState): Boolean {
        val player = control.handle.entity ?: return false
        val stopped = (player.playback == PlaybackState.Idle || player.playback == PlaybackState.Off) &&
            player.position != null && player.duration != null && player.position!! >= player.duration!! - 2
        val at = state.items.indexOfFirst { it.current }
        return stopped && at != -1 && at == state.items.size - 1 && state.offset + state.items.size == state.total
    }

    private fun itemOf(row: QueueRow): QueueItem? {
        val i = rows.indexOf(row)
        return if (i < 0) null else if (i < leading.size) leading[i] else upcoming.getOrNull(i - leading.size)
    }

    private fun newRow(): QueueRow {
        val row = QueueRow(context)
        // Made once: a row asks the list what it is when it is pressed.
        row.onPlay = { itemOf(row)?.let { control.play(it) } }
        row.onRemove = {
            itemOf(row)?.let { item ->
                collapse(listOf(item.id), toward = 1f)
                control.remove(item) { ok -> if (!ok) collapse(listOf(item.id), toward = 0f) }
            }
        }
        wireDrag(row)
        return row
    }

    private fun layoutRows() {
        val state = shown ?: return
        val over = finished(state)
        val visible = arrival.visible.value.coerceAtMost(upcoming.size)
        val count = leading.size + visible
        // Rows are kept and bound again, so a queue that changes under a finger does not rebuild the list.
        while (rows.size < count) {
            val row = newRow()
            val slot = ArtworkSlot(AssetSize.Small, { dp(QueueRow.PICTURE) }) { row.picture = it }
            rows += row
            pictures += slot
            host.addView(row, LinearLayout.LayoutParams(-1, dp(QueueRow.ROW)).also {
                if (rows.size > 1) it.topMargin = Math.round(dp(theme.density.space) / 6f)
            })
        }
        while (rows.size > count) {
            host.removeViewAt(rows.size - 1)
            rows.removeAt(rows.size - 1)
            pictures.removeAt(pictures.size - 1).stop()
        }
        val at = leading.indexOfLast { it.current }
        for (i in 0 until count) {
            val isLeading = i < leading.size
            val item = if (isLeading) leading[i] else upcoming[i - leading.size]
            val row = rows[i]
            row.bind(item, playing = isLeading && item.current && !over, reorderable = !isLeading)
            row.alpha = when {
                !isLeading -> if (clearing) 0.5f else 1f
                // Played, and the track that was playing once it is over.
                i < at || (over && item.current) -> 0.5f
                else -> 1f
            }
            row.translationY = 0f
            row.lifted = false
            pictures[i].set(item.artworkUrl)
            shape(i, row, item, row.alpha)
        }
        // What is out of sight, said at the end of the list.
        tail?.let { host.removeView(it) }
        tail = null
        val hidden = Math.max(0, state.total - state.offset - state.items.size)
        if (hidden > 0 && visible >= upcoming.size) {
            tail = LabelView(context, Role.Secondary, Tone.Muted).also {
                it.text = "and ${"%,d".format(hidden)} more"
                host.addView(it, LinearLayout.LayoutParams(-1, -2).also { p ->
                    p.topMargin = Math.round(2 * unitPx())
                    p.bottomMargin = Math.round(2 * unitPx())
                })
            }
        }
    }

    /** A row's fade and height for how far its track has collapsed (not at all unless it is leaving). */
    private fun shape(index: Int, row: QueueRow, item: QueueItem, base: Float) {
        val p = leaving[item.id]?.p ?: 0f
        val lp = row.layoutParams as LinearLayout.LayoutParams
        val height = RowCollapse.size(dp(QueueRow.ROW), p)
        val margin = if (index > 0) RowCollapse.size(Math.round(dp(theme.density.space) / 6f), p) else 0
        row.alpha = RowCollapse.alpha(base, p)
        row.visibility = if (p >= 1f) INVISIBLE else VISIBLE
        if (lp.height != height || lp.topMargin != margin) {
            lp.height = height
            lp.topMargin = margin
            row.layoutParams = lp
        }
    }

    /** What a row's opacity is when it is not leaving. */
    private fun baseAlpha(index: Int): Float {
        val isLeading = index < leading.size
        val at = leading.indexOfLast { it.current }
        val over = shown?.let { finished(it) } == true
        return when {
            !isLeading -> if (clearing) 0.5f else 1f
            index < at || (over && leading[index].current) -> 0.5f
            else -> 1f
        }
    }

    /**
     * Takes the rows of [ids] toward gone (1) or back (0): they fade and give up their height in [RowCollapse.MS] ms, the rows below
     * sliding up with them, or at once when motion is off. One animator moves all of them together, so Clear is one collapse.
     */
    private fun collapse(ids: List<String>, toward: Float) {
        if (ids.isEmpty()) return
        // One that is still going is finished where it was heading, so no row is left half way.
        finishLeaving()
        val moving = ids.map { id -> leaving.getOrPut(id) { Leave(0f) }.also { it.from = it.p } }
        if (!Animation.animates || !isAttachedToWindow) {
            moving.forEach { it.p = toward }
            settleLeaving()
            return
        }
        batch = moving
        batchTo = toward
        leaveAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = RowCollapse.MS
            interpolator = android.view.animation.LinearInterpolator()
            // One fraction drives every row of the batch: nothing is made again for a frame.
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                val elapsed = (t * RowCollapse.MS).toLong()
                for (l in batch) l.p = RowCollapse.at(l.from, batchTo, elapsed)
                reshape()
                if (t >= 1f) settleLeaving()
            }
            start()
        }
    }

    /** Ends a collapse that is going at the place it was heading. */
    private fun finishLeaving() {
        leaveAnim?.cancel()
        leaveAnim = null
        for (l in batch) l.p = batchTo
        settleLeaving()
    }

    private fun settleLeaving() {
        batch = emptyList()
        reshape()
        leaving.values.removeAll { it.p <= 0f }
    }

    private fun reshape() {
        for (i in rows.indices) {
            val item = if (i < leading.size) leading[i] else upcoming.getOrNull(i - leading.size)
            if (item != null && leaving.containsKey(item.id)) shape(i, rows[i], item, baseAlpha(i))
        }
    }

    private fun pitch(): Float = (dp(QueueRow.ROW) + Math.round(dp(theme.density.space) / 6f)).toFloat()

    /** Dragging a row by its handle: it follows the finger, the rows it passes give way, and where it is let go is where it goes. */
    private fun wireDrag(row: QueueRow) {
        row.onDragStart = {
            val from = rows.indexOf(row) - leading.size
            dragging = from >= 0
            dragFrom = from
            dragTarget = from
            row.lifted = true
            row.elevation = dp(6f)
            row.bringToFront()
            host.requestLayout()
        }
        row.onDragMove = { dy ->
            val from = dragFrom
            val first = leading.size
            val last = arrival.visible.value.coerceAtMost(upcoming.size) - 1
            row.translationY = dy
            val target = QueueDrag.target(from, dy, pitch(), last)
            if (target != dragTarget) {
                dragTarget = target
                for (j in 0..last) {
                    if (j == from) continue
                    val other = rows.getOrNull(first + j) ?: continue
                    val goal = QueueDrag.shift(j, from, target) * pitch()
                    if (other.translationY != goal) {
                        if (Animation.full) other.animate().translationY(goal).setDuration(150).start() else other.translationY = goal
                    }
                }
            }
        }
        row.onDragEnd = { dy, cancelled ->
            val from = dragFrom
            val last = arrival.visible.value.coerceAtMost(upcoming.size) - 1
            dragging = false
            row.elevation = 0f
            row.lifted = false
            rows.forEach {
                it.animate().cancel()
                it.translationY = 0f
            }
            val item = upcoming.getOrNull(from)
            val target = if (cancelled) from else QueueDrag.target(from, dy, pitch(), last)
            if (item != null && target != from) drop(item, from, target)
            else if (deferred) render()
            deferred = false
        }
    }

    /** Puts the track where it was dragged to: shown at once, and sent as how many places it moved. */
    private fun drop(item: QueueItem, from: Int, to: Int) {
        val state = shown ?: return
        val visibleIds = upcoming.map { it.id }
        // The order of the part that is drawn, the rest after it as it was.
        val shownCount = arrival.visible.value.coerceAtMost(upcoming.size)
        val moved = QueueDrag.reorder(visibleIds.subList(0, shownCount), from, to) + visibleIds.drop(shownCount)
        draft = moved
        draftOf = state
        draftTimer?.invoke()
        draftTimer = control.scope.after(DRAFT_MS) {
            draft = null
            render()
        }
        deferred = false
        control.move(item, to - from)
        render()
    }

    private companion object {
        const val DRAFT_MS = 2500L
    }
}

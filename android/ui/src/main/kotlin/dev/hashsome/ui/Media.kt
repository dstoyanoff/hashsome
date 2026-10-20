package dev.hashsome.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import dev.hashsome.client.AssetSize
import dev.hashsome.client.Cancel
import dev.hashsome.client.EntityKind
import dev.hashsome.client.EntityRef
import dev.hashsome.headless.EntityHandle
import dev.hashsome.headless.HandleState
import dev.hashsome.headless.MediaBrowserControl
import dev.hashsome.headless.MediaPlayerControl
import dev.hashsome.headless.QueueControl
import dev.hashsome.headless.Scope
import dev.hashsome.headless.SpeakersControl
import dev.hashsome.ui.icons.Ui

// The media players: the bar, the column and the full player, each a thin view over a `MediaPlayerControl`. The library, the
// queue and the speakers they open are in `MediaLists.kt`; they get a control of their own for as long as they are open.

/** Which optional parts a player view shows. */
data class PlayerOptions(
    val time: Boolean = true,
    val shuffle: Boolean = true,
    val browse: Boolean = true,
    val grouping: Boolean = true,
    /** Rows of controls: 1 is the compact bar. */
    val rows: Int = 2,
)

private fun wrap() = FlexParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

private fun rounded(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp).toFloat()
}

/** Opens the player's library as a panel of its own over the page. */
internal fun openLibrary(context: Context, control: MediaPlayerControl, label: String, prebuilt: Prebuilt? = null, expandable: Boolean = false) {
    openMediaOverlay(context, control.scope, "Library", label, Ui.library, full = true, expandable = expandable, prebuilt = prebuilt) { scope ->
        MediaBrowserView(context, MediaBrowserControl(control.handle, scope))
    }
}

/** Opens the player's queue as a panel of its own over the page. */
internal fun openQueue(context: Context, control: MediaPlayerControl, label: String, prebuilt: Prebuilt? = null, expandable: Boolean = false) {
    openMediaOverlay(context, control.scope, "Queue", label, Ui.listMusic, full = true, expandable = expandable, prebuilt = prebuilt) { scope ->
        MediaQueueView(context, QueueControl(control.handle, scope))
    }
}

/** Opens who plays together with the player as the side panel. */
internal fun openSpeakers(context: Context, control: MediaPlayerControl, allow: List<EntityRef>, label: String, prebuilt: Prebuilt? = null, expandable: Boolean = false) {
    // The panel has a control of its own, so it does not depend on the view that opened it staying.
    openMediaOverlay(context, control.scope, "Speakers", label, Ui.speaker, full = false, expandable = expandable, prebuilt = prebuilt) { scope ->
        MediaSpeakersView(context, SpeakersControl(control.handle, scope, allow))
    }
}

/** Opens the whole player, with its library and queue, as a panel (a column with no panels of its own for them). */
internal fun openPlayer(context: Context, control: MediaPlayerControl, label: String, speakers: List<EntityRef>, expandable: Boolean = false) {
    openMediaOverlay(context, control.scope, label, "Media", Ui.music, full = false, expandable = expandable) { scope ->
        MediaPlayerFullView(context, MediaPlayerControl(control.handle, scope), label).also { it.speakers = speakers }
    }
}

/**
 * The pill that says how a player's speakers stand and opens them: "Speakers" while it plays alone, "3 speakers" once others play
 * with it, "Patio playing" when there is a stream in another room to join, a dot marking that one and the speaker glyph in the
 * accent color while the player is in a group. [compact] drops the words (the count stays in a group), for a bar with no room.
 */
internal class SpeakersPillView(context: Context, private val compact: Boolean, onOpen: () -> Unit) : BoundView(context) {
    private var speakers: SpeakersControl? = null
    private var following: Cancel? = null

    private val pill = PillButton(context, Ui.speaker, "Speakers", Role.Label).also {
        it.heightDp = 30
        it.padUnits = 3f
        it.gapUnits = 2f
        it.minWidthDp = 30
        // On a card (not in a bar's row of keys) it is a ghost: no fill until pressed, as the web's text-only button.
        it.flat = !compact
        it.onClick = onOpen
    }

    init {
        addView(pill, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    /** The pill as tall as [dp], and as wide as its place (the bar's two-row layout makes it as big as the buttons beside it). */
    fun fillKey(dp: Int) {
        pill.heightDp = dp
        pill.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    /** Follows [control]: made once the player is known to be one that can be grouped, so a player that cannot watches no speakers. */
    fun use(control: SpeakersControl) {
        speakers = control
        if (isAttachedToWindow) follow()
    }

    private fun follow() {
        following?.invoke()
        val c = speakers ?: return
        val redraw: (Any?) -> Unit = { render() }
        val all = listOf(c.speakers.listen(redraw), c.status.listen(redraw), c.handle.state.listen(redraw))
        following = { all.forEach { it() } }
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        follow()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        following?.invoke()
        following = null
    }

    private fun render() {
        val speakers = speakers ?: return
        val summary = SpeakersSummary.of(speakers.handle.entity?.group, speakers.speakers.value, speakers.status.value)
        val grouped = summary.together > 1
        pill.label = if (compact) (if (grouped) summary.together.toString() else null) else summary.label
        pill.accentIcon = grouped
        pill.muted = !grouped
        pill.dot = !grouped && summary.elsewhere.isNotEmpty()
        // Without words it is a circle.
        pill.padUnits = if (pill.label == null) 0f else 3f
    }
}

/** The progress line along the bottom edge of a bar, in a strip tall enough to press: with a length it seeks. */
internal class SeekLineView(context: Context) : View(context) {
    var fraction = 0f
        set(value) {
            field = value
            invalidate()
        }

    /** Whether there is something to grab: the track shows. */
    var seekable = false
        set(value) {
            field = value
            invalidate()
        }

    var onPreview: ((Float) -> Unit)? = null
    var onCommit: ((Float) -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var dragging = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(STRIP))
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        val h = height.toFloat()
        val line = dp(if (dragging) 6f else 3f)
        if (seekable) {
            paint.color = t.palette.border
            canvas.drawRect(0f, h - line, width.toFloat(), h, paint)
        }
        paint.color = t.palette.accent
        canvas.drawRect(0f, h - line, width * fraction.coerceIn(0f, 1f), h, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!seekable) return false
        val at = (event.x / maxOf(1, width)).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                onPreview?.invoke(at)
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> if (dragging) onPreview?.invoke(at)
            MotionEvent.ACTION_UP -> if (dragging) {
                dragging = false
                onCommit?.invoke(at)
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                onCancel?.invoke()
                invalidate()
            }
        }
        return true
    }

    private companion object {
        const val STRIP = 16
    }
}

/**
 * A compact player: art in a ring that shows how far along it is, title and artist, the time, and the transport keys with a
 * volume key (which swaps the title for a slider) and, when allowed, the library and the speakers. In one row ([PlayerOptions.rows] 1) it
 * is a pill; in two, for a narrow space, the keys take the second row. A thin line along its bottom edge shows (and seeks) the position.
 * [onOpen] runs on a tap on the art and on the library key, to go to the player's page.
 */
open class MediaPlayerBarView(context: Context, val control: MediaPlayerControl, val label: String, val options: PlayerOptions = PlayerOptions()) :
    BoundView(context) {
    var onOpen: (() -> Unit)? = null

    /** The panels it opens have a button that widens them (off by default: a small display has no use for it). */
    var expandable: Boolean = false

    /** The speakers that may be grouped with this player; empty for any it can be grouped with. Set before the view is shown. */
    var speakers: List<EntityRef> = emptyList()

    private var own: Scope? = null
    private var keys: Transport? = null
    private var volume: VolumeParts? = null
    private var ring: MiniRingView? = null
    private var titleRow: TitleRow? = null
    private var artist: MarqueeText? = null
    private var loadingLine: LabelView? = null
    private var time: LabelView? = null
    private var volumeRow: RowLayout? = null
    private var volumeButton: GlyphButton? = null
    private var browse: GlyphButton? = null
    private var pill: SpeakersPillView? = null
    private var line: SeekLineView? = null
    private var artwork: ArtworkSlot? = null
    private var grouping: SpeakersControl? = null
    private var queue: QueueControl? = null
    private var track: View? = null
    private val tools = ArrayList<View>()
    private val transport = ArrayList<View>()
    private var volumeOpen = false
    private var closer: GlyphButton? = null

    private val twoRows: Boolean get() = options.rows >= 2

    private companion object {
        /** The height of the keys in the second row of a two-row bar. */
        const val KEY_HEIGHT = 34
    }

    private fun build() {
        removeAllViews()
        val t = theme
        val u = unitPx()
        val scope = control.scope.child()
        own = scope
        val queue = QueueControl(control.handle, scope).also { this.queue = it }
        grouping = null
        val keys = Transport(context, control, queue).also { this.keys = it }
        val volume = VolumeParts(context, control).also { this.volume = it }
        val circle = dp(t.density.iconCircle)

        background = rounded(t.palette.surface, if (twoRows) t.radius.card else t.radius.full)
        clipToOutline = true
        // Where the dashboard says: its own page for the player; without one, the whole player as a panel.
        val open = { onOpen?.invoke() ?: openPlayer(context, control, label, speakers, expandable) }
        ring = MiniRingView(context).also { it.onClick = open }
        artwork = ArtworkSlot(AssetSize.Small, { dp(30) }) { ring?.show(it) }

        val row = TitleRow(context).also {
            titleRow = it
            it.title.role = Role.Label
            it.action = keys.shuffle
        }
        val sub = MarqueeText(context).also {
            artist = it
            it.role = Role.Secondary
        }
        val timeLabel = LabelView(context, Role.Secondary, Tone.Muted).also { time = it }
        val slider = volume.row(context).also { volumeRow = it }

        val volumeKey = GlyphButton(context, UiIcons.volume).also {
            volumeButton = it
            it.onClick = {
                volumeOpen = !volumeOpen
                render()
            }
        }
        val library = GlyphButton(context, Ui.library).also {
            browse = it
            it.onClick = { open() }
        }
        val speakersPill = SpeakersPillView(context, compact = true) { openSpeakers(context, control, speakers, label, null, expandable) }.also { pill = it }
        tools.clear()
        tools += listOf(volumeKey, library, speakersPill)
        transport.clear()
        transport += listOf(keys.previous, keys.play, keys.next)

        val edge = FlexParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val content: ViewGroup
        if (!twoRows) {
            val flow = RowLayout(context).also {
                it.gap = 3
                it.setPadding(Math.round(2.25f * u), 0, Math.round(3 * u), 0)
            }
            flow.addView(ring, FlexParams(circle, circle))
            val info = ColumnLayout(context)
            info.addView(row, FlexParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            info.addView(sub, FlexParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            info.addView(LabelView(context, Role.Secondary, Tone.Muted).also {
                loadingLine = it
                it.spinner = true
                it.visibility = GONE
            }, FlexParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            track = info
            flow.addView(info, wrap().also {
                it.grow = true
                it.shrink = true
            })
            flow.addView(timeLabel, wrap())
            flow.addView(slider, wrap().also {
                it.grow = true
                it.shrink = true
            })
            val toolsRow = RowLayout(context).also { it.gap = 3 }
            tools.forEach { toolsRow.addView(it, wrap()) }
            flow.addView(toolsRow, wrap())
            val transportRow = RowLayout(context).also { it.gap = 3 }
            transport.forEach { transportRow.addView(it, wrap()) }
            flow.addView(transportRow, wrap())
            content = flow
            addView(content, LayoutParams(LayoutParams.MATCH_PARENT, dp(t.density.tileHeight)))
        } else {
            val stack = ColumnLayout(context).also {
                it.setPadding(Math.round(2.25f * u), Math.round(2.5f * u), Math.round(3 * u), Math.round(2.5f * u))
            }
            val top = RowLayout(context).also { it.gap = 2 }
            top.addView(ring, FlexParams(circle, circle).also { it.rightMargin = Math.round(0.5f * u) })
            // The title and the artist share the line, the artist after a dot.
            row.showArtist = true
            row.artist.role = Role.Secondary
            artist = row.artist
            top.addView(row, wrap().also {
                it.grow = true
                it.shrink = true
            })
            top.addView(timeLabel, wrap())
            stack.addView(top, edge)
            val keysRow = RowLayout(context).also { it.gap = 2 }
            val wide = { FlexParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(KEY_HEIGHT)).also { p -> p.grow = true } }
            val close = GlyphButton(context, UiIcons.close).also {
                closer = it
                it.onClick = {
                    volumeOpen = false
                    render()
                }
            }
            keysRow.addView(close, FlexParams(dp(KEY_HEIGHT), dp(KEY_HEIGHT)))
            keysRow.addView(slider, FlexParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.grow = true
                it.shrink = true
            })
            tools.forEach { keysRow.addView(it, wide()) }
            transport.forEach { keysRow.addView(it, wide()) }
            pill?.fillKey(KEY_HEIGHT)
            stack.addView(keysRow, FlexParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = Math.round(2.5f * u)
            })
            content = stack
            addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            minimumHeight = Math.round(2 * (circle + u) + dp(t.density.space))
        }

        val seekLine = SeekLineView(context).also {
            line = it
            it.onPreview = { f -> control.handle.entity?.duration?.let { d -> control.seek.drag(Math.round(f * d).toDouble()) } }
            it.onCommit = { f -> control.handle.entity?.duration?.let { d -> control.seek.commit(Math.round(f * d).toDouble()) } }
            it.onCancel = { control.seek.cancel() }
        }
        addView(seekLine, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        build()
        val redraw: (Any?) -> Unit = { render() }
        onDetach(control.handle.state.listen(redraw))
        onDetach(control.playing.listen(redraw))
        onDetach(control.feedback.listen(redraw))
        onDetach(control.volume.shown.listen(redraw))
        onDetach(control.volume.muted.listen(redraw))
        onDetach(control.seek.shown.listen(redraw))
        queue?.let { onDetach(it.state.listen(redraw)) }
        render()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        artwork?.stop()
        own?.dispose()
        own = null
        tools.clear()
        transport.clear()
    }

    private fun render() {
        val state = control.handle.state.value
        val ready = state is HandleState.Ready
        val player = control.handle.entity
        val caps = player?.capabilities
        val keys = keys ?: return
        val volume = volume ?: return
        val open = volumeOpen && ready
        if (!ready) volumeOpen = false

        // The art, the ring round it and the title.
        artwork?.set(if (ready) player?.media?.artworkUrl else null)
        val duration = if (ready) player?.duration else null
        val shown = control.seek.shown.value
        val timed = shown != null && duration != null && duration > 0
        val fraction = if (timed) (shown!! / duration!!).coerceIn(0.0, 1.0).toFloat() else 0f
        ring?.fraction = if (timed) fraction else null
        titleRow?.title?.text = if (ready) player?.media?.title ?: "Nothing playing" else mediaStatusLabel(state).orEmpty()
        titleRow?.title?.color = if (ready) null else theme.palette.textMuted
        val loading = if (ready) PlayRequests.loading(control.handle.ref, control.feedback.value) else null
        val by = when {
            loading != null -> if (loading.isEmpty()) "Loading…" else "Loading $loading…"
            ready -> player?.media?.artist.orEmpty()
            else -> ""
        }
        if (!twoRows) {
            val waiting = loading != null
            loadingLine?.text = if (waiting) by else ""
            loadingLine?.visibility = if (waiting) VISIBLE else GONE
        }
        artist?.text = if (!twoRows && loading != null) "" else by
        artist?.color = theme.palette.textMuted
        if (!twoRows) artist?.visibility = if (by.isEmpty() || loading != null) GONE else VISIBLE
        titleRow?.showArtist = twoRows && by.isNotEmpty()
        time?.text = if (timed && options.time) "${MediaFormat.duration(shown!!)} / ${MediaFormat.duration(duration!!)}" else ""
        time?.visibility = if (time?.text.isNullOrEmpty() || (open && !twoRows)) GONE else VISIBLE

        // The keys.
        keys.render()
        keys.shuffle.visibility = if (ready && options.shuffle && caps?.shuffle == true) VISIBLE else GONE
        volume.render()
        val canVolume = ready && !(caps?.volume == false && caps.mute == false)
        volumeButton?.apply {
            icon = if (open) UiIcons.close else if (control.volume.muted.value) UiIcons.volumeMuted else UiIcons.volume
            isEnabled = canVolume || open
        }
        val canBrowse = ready && options.browse && (onOpen != null || caps?.browse == true)
        val canGroup = ready && options.grouping && caps?.group == true
        // Speakers are watched only for a player that can be grouped.
        if (canGroup && grouping == null) {
            own?.let { scope -> grouping = SpeakersControl(control.handle, scope, speakers).also { pill?.use(it) } }
        }
        browse?.visibility = if (canBrowse) VISIBLE else GONE
        pill?.visibility = if (canGroup) VISIBLE else GONE

        // The slider takes the title's place; in two rows it has the row of keys, with only the key that closes it.
        volumeRow?.visibility = if (open) VISIBLE else GONE
        if (twoRows) {
            closer?.visibility = if (open) VISIBLE else GONE
            tools.forEach { it.visibility = if (open) GONE else it.visibility.takeIf { v -> v != GONE || it === volumeButton } ?: VISIBLE }
            volumeButton?.visibility = if (open) GONE else VISIBLE
            if (!open) {
                browse?.visibility = if (canBrowse) VISIBLE else GONE
                pill?.visibility = if (canGroup) VISIBLE else GONE
            }
            transport.forEach { it.visibility = if (open) GONE else VISIBLE }
        } else {
            track?.visibility = if (open) GONE else VISIBLE
        }

        line?.fraction = fraction
        line?.seekable = ready && caps?.seek == true && timed
        line?.visibility = if (timed) VISIBLE else GONE
    }
}

/**
 * A tall player for a side column: large art above the controls. The library and the queue open as panels of their own from keys beside
 * the transport when [overlays] is set (shuffle moves up beside the title, the speakers pill sits under the time); otherwise one key
 * opens a panel with the whole player, library and queue in it. With [fill] the card takes all the height its parent gives it, the
 * art growing to use the room and the controls staying at the bottom.
 */
open class MediaPlayerColumnView(
    context: Context,
    val control: MediaPlayerControl,
    val label: String,
    val overlays: Boolean = true,
    val fill: Boolean = true,
) : BoundView(context) {
    /** The panels it opens have a button that widens them (off by default: a small display has no use for it). */
    var expandable: Boolean = false

    /** A tap on the artwork opens the full-screen player ([NowPlayingScreen]) instead of the player's panel. */
    var fullScreen: Boolean = false

    /** The speakers that may be grouped with this player; empty for any it can be grouped with. Set before the view is shown. */
    var speakers: List<EntityRef> = emptyList()

    private var own: Scope? = null
    private var library: GlyphButton? = null
    private var queueKey: GlyphButton? = null
    private var pillView: SpeakersPillView? = null
    private var grouping: SpeakersControl? = null
    private var libraryReady: Prebuilt? = null
    private var queueReady: Prebuilt? = null
    private var speakersReady: Prebuilt? = null

    private fun build() {
        removeAllViews()
        libraryReady?.dispose()
        queueReady?.dispose()
        speakersReady?.dispose()
        own?.dispose()
        val t = theme
        val scope = control.scope.child()
        own = scope
        if (overlays) {
            libraryReady = Prebuilt(context, scope) { MediaBrowserView(context, it, control.handle) }
            queueReady = Prebuilt(context, scope) { MediaQueueView(context, it, control.handle) }
            speakersReady = Prebuilt(context, scope) { MediaSpeakersView(context, it, control.handle, speakers) }
        }
        val queue = QueueControl(control.handle, scope)
        grouping = null
        background = rounded(t.palette.surface, t.radius.card)
        val pad = Math.round(4 * unitPx())
        setPadding(pad, pad, pad, pad)
        val pill = SpeakersPillView(context, compact = false) { openSpeakers(context, control, speakers, label, speakersReady, expandable) }.also { pillView = it }
        val browse = GlyphButton(context, Ui.library).also {
            library = it
            it.onClick = { if (overlays) openLibrary(context, control, label, libraryReady, expandable) else openPlayer(context, control, label, speakers, expandable) }
        }
        val queueButton = if (overlays) {
            GlyphButton(context, Ui.listMusic).also {
                queueKey = it
                it.onClick = { openQueue(context, control, label, queueReady, expandable) }
            }
        } else {
            null
        }
        val panel = if (overlays) {
            NowPlayingView(
                context, control, label, queue, shuffleAtTitle = true, leading = browse, extra = queueButton, speakers = pill,
                // The artwork opens the full-screen player when asked to; otherwise it is not a key here (the keys beside it open the panels).
                onOpenArtwork = if (fullScreen) ({ NowPlayingScreen.open(context, control, label) }) else null,
            )
        } else {
            NowPlayingView(
                context, control, label, queue, shuffleAtTitle = false, extra = browse, speakers = pill,
                onOpenArtwork = { if (fullScreen) NowPlayingScreen.open(context, control, label) else openPlayer(context, control, label, speakers, expandable) },
            )
        }
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        build()
        onDetach(control.handle.state.listen { render() })
        render()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        libraryReady?.dispose()
        queueReady?.dispose()
        speakersReady?.dispose()
        own?.dispose()
        own = null
    }

    private fun render() {
        val ready = control.ready
        val caps = control.handle.entity?.capabilities
        // The slots keep their place when a key is not there, so the transport stays centered.
        library?.visibility = if (ready && caps?.browse == true) VISIBLE else INVISIBLE
        queueKey?.visibility = if (ready && caps?.queue == true) VISIBLE else INVISIBLE
        val canGroup = ready && caps?.group == true
        if (canGroup && grouping == null) {
            own?.let { scope -> grouping = SpeakersControl(control.handle, scope, speakers).also { pillView?.use(it) } }
        }
        pillView?.visibility = if (canGroup) VISIBLE else GONE
        // What the panels will hold is made after the page has settled.
        if (ready && caps?.browse == true) libraryReady?.later()
        if (ready && caps?.queue == true) queueReady?.later()
        if (canGroup) speakersReady?.later()
    }
}

/** What the tabs below the full player choose between. */
internal enum class ListTab { Library, Queue }

/** Library and Queue as text with an underline that glides from one to the other (not the pills the library's own shelves are). */
internal class UnderlineTabs(context: Context, private val tabs: List<ListTab>) : View(context) {
    var current: ListTab = tabs.first()
        set(value) {
            if (field == value) return
            field = value
            val i = tabs.indexOf(value)
            at.to(i.toFloat())
        }

    var onSelect: ((ListTab) -> Unit)? = null

    private val at = Eased(this, 0f, 200)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(HEIGHT))
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        val w = width / tabs.size.toFloat()
        val h = height.toFloat()
        paint.color = t.palette.border
        canvas.drawRect(0f, h - dp(1f), width.toFloat(), h, paint)
        tabs.forEachIndexed { i, tab ->
            val on = tab == current
            val label = if (tab == ListTab.Library) "Library" else "Queue"
            val ink = if (on) t.palette.text else t.palette.textMuted
            text.style(t, if (on) Role.BodyStrong else Role.Body, ink)
            val glyph = dp(16)
            val gap = 2 * unitPx()
            val textW = text.measureText(label)
            val x = i * w + (w - (glyph + gap + textW)) / 2
            canvas.drawIcon(iconBitmap(if (tab == ListTab.Library) Ui.library else Ui.listMusic, glyph, ink), x, (h - glyph) / 2)
            canvas.drawTextCentered(label, x + glyph + gap, h / 2, text)
        }
        paint.color = t.palette.accent
        canvas.drawRect(at.value * w, h - dp(2f), (at.value + 1) * w, h, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && contains(event.x, event.y)) {
            val tab = tabs[(event.x / (width / tabs.size.toFloat())).toInt().coerceIn(0, tabs.size - 1)]
            if (tab != current) {
                current = tab
                onSelect?.invoke(tab)
            }
        }
        return true
    }

    private companion object {
        const val HEIGHT = 40
    }
}

/**
 * What the full player's parts are laid out in: every part but the last as tall as it is, the last (the list) the height that is left, and
 * never less than [minList]; when that is more than the room there is, the whole is taller than its scroll view and scrolls.
 */
private class FullLayout(context: Context) : ViewGroup(context) {
    var minListDp = 260

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val gap = Math.round(5 * unitPx())
        var used = 0
        val count = childCount
        for (i in 0 until count - 1) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            used += child.measuredHeight + gap
        }
        val last = if (count > 0) getChildAt(count - 1) else null
        val room = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) - used else 0
        val list = maxOf(room, dp(minListDp))
        last?.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(list, MeasureSpec.EXACTLY))
        setMeasuredDimension(width, used + if (last == null) 0 else list)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val gap = Math.round(5 * unitPx())
        var y = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.layout(0, y, child.measuredWidth, y + child.measuredHeight)
            y += child.measuredHeight + gap
        }
    }
}

/** One child, centered, no wider than [maxDp] and as tall as it is, or as the room it is given when that is less (the player alone gives way, its record shrinking). */
private class Capped(context: Context, private val maxDp: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val child = getChildAt(0)
        val room = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
        child.measure(
            MeasureSpec.makeMeasureSpec(minOf(width, dp(maxDp)), MeasureSpec.EXACTLY),
            if (room) MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.AT_MOST) else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        setMeasuredDimension(width, child.measuredHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val child = getChildAt(0)
        val left = ((r - l) - child.measuredWidth) / 2
        child.layout(left, 0, left + child.measuredWidth, child.measuredHeight)
    }
}

/**
 * The full player: the player on top, centered, and under a line its library and its queue as two tabs (one list has a plain line).
 * The player stays put and the list scrolls in the room that is left. The speakers open as the side panel.
 */
open class MediaPlayerFullView(context: Context, control: MediaPlayerControl, label: String) : BoundView(context) {
    /** The player this card controls: the one it was made with, or the one picked from [players]. */
    var control: MediaPlayerControl = control
        private set

    var label: String = label
        private set

    /** The players the card can control, when there are two or more: a chip at its top names the one it controls and switches between them. Set before the view is shown. */
    var players: List<PlayerChoice> = emptyList()

    /** The speakers panel has a button that widens it (off by default). */
    var expandable: Boolean = false

    /** The speakers that may be grouped with this player; empty for any it can be grouped with. Set before the view is shown. */
    var speakers: List<EntityRef> = emptyList()

    /** Whether the library and the queue are drawn under the player (on by default); off, only the player is, as the web's without `browser` and `queue`. Set before the view is shown. */
    var lists: Boolean = true

    /** A tap on the artwork opens the full-screen player ([NowPlayingScreen]). Set before the view is shown. */
    var fullScreen: Boolean = false

    private var own: Scope? = null
    private var tabs: UnderlineTabs? = null
    private var area: android.widget.FrameLayout? = null
    private var shownTab: ListTab? = null
    private var listScope: Scope? = null
    private var column: FullLayout? = null
    private var pillView: SpeakersPillView? = null
    private var grouping: SpeakersControl? = null

    private fun build() {
        removeAllViews()
        val scope = control.scope.child()
        own = scope
        val queue = QueueControl(control.handle, scope)
        val pill = SpeakersPillView(context, compact = false) { openSpeakers(context, control, speakers, label, null, expandable) }
        pillView = pill
        grouping = null
        val chip = if (players.size > 1) {
            val now = players.firstOrNull { it.ref == control.handle.ref?.value } ?: players.first()
            PlayerChip(context, players, now, scope, ::switchTo)
        } else {
            null
        }
        val player = NowPlayingView(
            context, control, label, queue, shuffleAtTitle = true, speakers = pill, playerChip = chip,
            onOpenArtwork = if (fullScreen) ({ NowPlayingScreen.open(context, control, label) }) else null,
        )
        val stack = FullLayout(context)
        column = stack
        val centered = Capped(context, PLAYER_WIDTH)
        centered.addView(player)
        stack.addView(centered)
        val scroll = ScrollView(context).also {
            it.isFillViewport = true
            it.isVerticalScrollBarEnabled = false
            it.overScrollMode = OVER_SCROLL_NEVER
            it.addView(stack, FrameLayout.LayoutParams(-1, -2))
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        shownTab = null
        arrange()
    }

    /** The lists the player has: its library and its queue. */
    private fun lists(): List<ListTab> {
        if (!lists) return emptyList()
        val caps = control.handle.entity?.capabilities
        return buildList {
            if (control.ready && caps?.browse == true) add(ListTab.Library)
            if (control.ready && caps?.queue == true) add(ListTab.Queue)
        }
    }

    private fun arrange() {
        val stack = column ?: return
        val lists = lists()
        // Drop what was under the player and make it again.
        while (stack.childCount > 1) stack.removeViewAt(stack.childCount - 1)
        stack.minListDp = 260
        listScope?.dispose()
        listScope = null
        tabs = null
        area = null
        shownTab = null
        if (lists.isEmpty()) return
        if (lists.size > 1) {
            tabs = UnderlineTabs(context, lists).also {
                it.onSelect = { tab -> showList(tab) }
                stack.addView(it)
            }
        } else {
            val rule = View(context).apply { setBackgroundColor(theme.palette.border) }
            stack.addView(rule, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        }
        val frame = android.widget.FrameLayout(context)
        area = frame
        stack.addView(frame)
        showList(lists.first())
    }

    private companion object {
        /** The widest the player gets, in dp (`.playerBody` is 420 px). */
        const val PLAYER_WIDTH = 420
    }

    private fun showList(tab: ListTab) {
        val frame = area ?: return
        if (shownTab == tab) return
        shownTab = tab
        listScope?.dispose()
        val scope = control.scope.child()
        listScope = scope
        frame.removeAllViews()
        val body: View = when (tab) {
            ListTab.Library -> MediaBrowserView(context, MediaBrowserControl(control.handle, scope))
            ListTab.Queue -> MediaQueueView(context, QueueControl(control.handle, scope))
        }
        frame.addView(body, android.widget.FrameLayout.LayoutParams(-1, -1))
        if (Animation.animates) {
            body.alpha = 0f
            body.animate().alpha(1f).setDuration(150).start()
        }
    }

    private var had: List<ListTab> = emptyList()
    private var watchingState: Cancel? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        build()
        // The lists a player has are known once it has reported.
        had = lists()
        watchState()
        onDetach { watchingState?.invoke(); watchingState = null }
        update()
    }

    private fun watchState() {
        watchingState?.invoke()
        watchingState = control.handle.state.listen { update() }
    }

    private fun update() {
        val canGroup = control.ready && control.handle.entity?.capabilities?.group == true
        if (canGroup && grouping == null) {
            own?.let { scope -> grouping = SpeakersControl(control.handle, scope, speakers).also { pillView?.use(it) } }
        }
        pillView?.visibility = if (canGroup) View.VISIBLE else View.GONE
        val now = lists()
        if (now != had) {
            had = now
            arrange()
        }
    }

    /** The card controls [choice] from now on: its handle, its lists and its speakers panel, and it is remembered for the next visit to the page. */
    private fun switchTo(choice: PlayerChoice) {
        PlayerSelection.choose(players, choice)
        if (choice.ref == control.handle.ref?.value) return
        listScope?.dispose()
        listScope = null
        own?.dispose()
        own = null
        val scope = control.scope
        control = MediaPlayerControl(EntityHandle(scope, EntityRef(choice.ref), EntityKind.MediaPlayer), scope)
        label = choice.label
        build()
        had = lists()
        watchState()
        update()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        listScope?.dispose()
        listScope = null
        own?.dispose()
        own = null
    }
}

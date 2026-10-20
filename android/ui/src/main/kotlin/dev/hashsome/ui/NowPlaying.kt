package dev.hashsome.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import dev.hashsome.client.AssetSize
import dev.hashsome.client.Cancel
import dev.hashsome.client.MediaPlayerCommands
import dev.hashsome.client.QueueResult
import dev.hashsome.headless.HandleState
import dev.hashsome.headless.MediaPlayerControl
import dev.hashsome.headless.QueueControl
import dev.hashsome.headless.transportAvailability

// What a player's views share: the transport keys, the volume row, and the upright "now playing" panel the column and the full
// player are made of. Each reads a `MediaPlayerControl` and sends through it; none holds state of its own beyond what is on screen.

/** Whether the player is usable: it has reported and is reachable. */
internal val MediaPlayerControl.ready: Boolean get() = handle.state.value is HandleState.Ready

/** The transport keys (previous, play or pause, next) and the shuffle key, with the presses, pending rings and error flashes of one player. */
internal class Transport(context: Context, private val control: MediaPlayerControl, private val queue: QueueControl?) {
    val previous = GlyphButton(context, UiIcons.skipPrevious)
    val play = GlyphButton(context, UiIcons.play).also { it.primary = true }
    val next = GlyphButton(context, UiIcons.skipNext)

    /** Shuffle as a small plain key beside a title. */
    val shuffle = GlyphButton(context, UiIcons.shuffle, 14f, plain = true).also { it.sizeDp = 28 }

    /** Shuffle beside the title of a player's own page: the web's round button, a circle twice the glyph, warm when on. */
    val shuffleRound = GlyphButton(context, UiIcons.shuffle, 14f).also { it.sizeDp = 28 }

    /** Shuffle as a key of the transport row. */
    val shuffleKey = GlyphButton(context, UiIcons.shuffle)

    init {
        previous.onClick = { control.press("previous", MediaPlayerCommands.previous()) }
        play.onClick = { control.press("togglePlay", MediaPlayerCommands.togglePlay()) }
        next.onClick = { control.press("next", MediaPlayerCommands.next()) }
        val toggle = {
            val player = control.handle.entity
            control.press("setShuffle", MediaPlayerCommands.setShuffle(player?.shuffle != true))
        }
        shuffle.onClick = toggle
        shuffleRound.onClick = toggle
        shuffleKey.onClick = toggle
    }

    /** What the keys can do, from the queue (`useTransportAvailability`). */
    private fun availability() = run {
        val player = control.handle.entity
        val state = queue?.state?.value
        val known = if (state != null && !state.loading && state.error == null && player?.capabilities?.queue == true) {
            QueueResult(state.items, state.total, state.offset, state.duration)
        } else {
            null
        }
        transportAvailability(known, player, control.position())
    }

    fun render() {
        val ready = control.ready
        val player = control.handle.entity
        val caps = player?.capabilities
        val can = availability()
        previous.isEnabled = ready && caps?.previous != false && can.previous
        next.isEnabled = ready && caps?.next != false && can.next
        play.isEnabled = ready && can.play
        play.icon = if (control.playing.value) UiIcons.pause else UiIcons.play
        previous.feedback(control.feedbackFor("previous"))
        play.feedback(control.feedbackFor("togglePlay"))
        next.feedback(control.feedbackFor("next"))
        val shown = ready && caps?.shuffle == true
        val on = player?.shuffle == true
        for (key in listOf(shuffle, shuffleRound, shuffleKey)) {
            key.visibility = if (key !== shuffleKey && !shown) View.GONE else View.VISIBLE
            key.isEnabled = ready
            key.active = on
            key.feedback(control.feedbackFor("setShuffle"))
        }
        // The key of the transport row keeps its place when shuffle is not there, empty.
        shuffleKey.visibility = if (shown) View.VISIBLE else View.INVISIBLE
    }
}

/** The volume of one player: the mute key, the slider and the level. */
internal class VolumeParts(context: Context, private val control: MediaPlayerControl) {
    val mute = GlyphButton(context, UiIcons.volume, 16f, plain = true).also { it.sizeDp = 28 }
    val bar = BarView(context)
    val percent = LabelView(context, Role.Secondary, Tone.Muted).also {
        it.align = Paint.Align.RIGHT
        it.minWidthDp = 32
    }

    init {
        mute.onClick = { control.volume.toggleMute() }
    }

    /** The key, the slider and the level in a row, the slider taking what is left. */
    fun row(context: Context): RowLayout {
        val row = RowLayout(context).also { it.gap = 2 }
        // The key is 28 dp to press and 16 dp to look at: the margins take back what the touch target gave.
        row.addView(mute, FlexParams(dp(28), dp(28)).also { it.setMargins(-dp(4), -dp(6), -dp(4), -dp(6)) })
        row.addView(bar, FlexParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.grow = true
            it.shrink = true
        })
        row.addView(percent, FlexParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    /** Whether the player has a volume to show (`caps.volume !== false || caps.mute`). */
    fun present(): Boolean {
        val caps = control.handle.entity?.capabilities ?: return true
        return caps.volume || caps.mute
    }

    fun render() {
        val ready = control.ready
        val caps = control.handle.entity?.capabilities
        val level = control.volume.shown.value
        val muted = control.volume.muted.value
        mute.icon = if (muted) UiIcons.volumeMuted else UiIcons.volume
        mute.active = muted
        mute.isEnabled = ready && caps?.mute != false
        bar.fraction = level.toFloat()
        // Only a player that is there takes a drag.
        if (ready) {
            bar.onDrag = { control.volume.drag(it.toDouble()) }
            bar.onCommit = { control.volume.commit(it.toDouble()) }
            bar.onCancel = { control.volume.cancel() }
        } else {
            bar.onDrag = null
            bar.onCommit = null
            bar.onCancel = null
        }
        percent.text = "${Math.round(level * 100)}%"
    }
}

/** Loads the artwork of a player into a view, once per picture, and lets go of a load that nothing waits for any more. */
internal class ArtworkSlot(private val size: AssetSize, private val px: () -> Int, private val show: (Bitmap?) -> Unit) {
    private var url: String? = null
    private var cancel: Cancel? = null
    private var asked = false

    fun set(next: String?) {
        if (asked && next == url) return
        asked = true
        url = next
        cancel?.invoke()
        cancel = null
        if (next.isNullOrEmpty()) {
            show(null)
            return
        }
        val mine = next
        cancel = Artwork.load(mine, size, px()) { bitmap -> if (url == mine) show(bitmap) }
    }

    fun stop() {
        cancel?.invoke()
        cancel = null
        // After a detach the picture is asked for again, from the cache.
        asked = false
    }
}

/**
 * The upright "now playing" panel the media column and the full player share: the artwork on a record that shows (and seeks) the
 * position, what is playing, how far along it is, the transport and a volume bar that is always there. The transport row has five
 * slots so play stays in the middle whatever buttons sit at its sides: [leading] and [extra] fill the outer two (shuffle takes the
 * first when it is not [shuffleAtTitle]).
 */
internal class NowPlayingView(
    context: Context,
    private val control: MediaPlayerControl,
    private val label: String,
    queue: QueueControl?,
    private val shuffleAtTitle: Boolean,
    private val leading: View? = null,
    private val extra: View? = null,
    private val speakers: View? = null,
    private val onOpenArtwork: (() -> Unit)? = null,
    /** The ring's diameter when the panel has room for it, in dp. */
    ringDp: Int = 168,
) : BoundView(context) {
    private val panel = ColumnLayout(context).also { it.gap = 4 }
    private val ring = ArtworkRingView(context).also {
        it.basisDp = ringDp
        it.onOpen = onOpenArtwork
    }
    private val keys = Transport(context, control, queue)
    private val volume = VolumeParts(context, control)
    private val titleRow = TitleRow(context).also {
        it.centered = true
        it.title.role = Role.Title
    }
    private val sub = MarqueeText(context).also { it.role = Role.Body }
    private val loadingLine = LabelView(context, Role.Body, Tone.Muted).also {
        it.spinner = true
        it.align = Paint.Align.CENTER
        it.visibility = View.GONE
    }
    private val volumeRow = volume.row(context)
    private val artwork = ArtworkSlot(AssetSize.Medium, { (minOf(ring.width, ring.height) * 0.72f).toInt().takeIf { it > 0 } ?: dp(130) }) { ring.show(it) }
    private val queueState = queue

    init {
        panel.addView(ring, FlexParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.grow = true
            it.shrink = true
            it.align = Align.Center
        })
        val titles = ColumnLayout(context).also { it.align = Align.Center }
        titles.addView(titleRow, FlexParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        titles.addView(sub, FlexParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = Math.round(0.5f * unitPx()) })
        titles.addView(loadingLine, FlexParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = Math.round(0.5f * unitPx()) })
        panel.addView(titles, FlexParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Beside the title shuffle is a plain key, a glyph with no disc under it.
        titleRow.action = if (shuffleAtTitle) keys.shuffle else null
        val transport = RowLayout(context).also {
            it.gap = 3
            it.justify = Justify.Center
        }
        val slot = { view: View? ->
            val box = dp(theme.density.iconCircle)
            transport.addView(view ?: View(context), FlexParams(box, box))
        }
        slot(if (shuffleAtTitle) leading else keys.shuffleKey)
        slot(keys.previous)
        slot(keys.play)
        slot(keys.next)
        slot(extra)
        panel.addView(transport, FlexParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(volumeRow, FlexParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // The speakers pill is at the top left of the card, out of the way of the title block and the keys.
        speakers?.let {
            addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP or android.view.Gravity.START))
            // The ring starts a little lower, so the pill does not touch its outline.
            panel.setPadding(0, dp(14), 0, 0)
        }

        ring.onPreview = { control.seek.drag(it) }
        ring.onCommit = { control.seek.commit(it) }
        ring.onCancel = { control.seek.cancel() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val redraw: (Any?) -> Unit = { render() }
        onDetach(control.handle.state.listen(redraw))
        onDetach(control.playing.listen(redraw))
        onDetach(control.feedback.listen(redraw))
        onDetach(control.volume.shown.listen(redraw))
        onDetach(control.volume.muted.listen(redraw))
        onDetach(control.seek.shown.listen(redraw))
        queueState?.let { onDetach(it.state.listen(redraw)) }
        render()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        artwork.stop()
    }

    private fun render() {
        val t = theme
        val state = control.handle.state.value
        val ready = state is HandleState.Ready
        val player = control.handle.entity
        val caps = player?.capabilities
        val title = if (ready) player?.media?.title ?: "Nothing playing" else mediaStatusLabel(state).orEmpty()
        titleRow.title.text = title
        titleRow.title.color = if (ready) null else t.palette.textMuted
        val loading = if (ready) PlayRequests.loading(control.handle.ref, control.feedback.value) else null
        sub.color = t.palette.textMuted
        sub.text = when {
            loading != null -> if (loading.isEmpty()) "Loading…" else "Loading $loading…"
            ready && !player?.media?.artist.isNullOrEmpty() -> listOf(player?.media?.artist, player?.media?.album).filter { !it.isNullOrEmpty() }.joinToString(" · ")
            else -> player?.name ?: label
        }
        artwork.set(if (ready) player?.media?.artworkUrl else null)

        val duration = if (ready) player?.duration else null
        val shown = control.seek.shown.value
        ring.length = if (ready && caps?.seek == true && duration != null && duration > 0) duration else 0.0
        ring.fraction = if (shown != null && duration != null && duration > 0) (shown / duration).coerceIn(0.0, 1.0).toFloat() else 0f
        // The time is on the artwork, which has the row it used to take.
        ring.timeText = if (ready && shown != null && duration != null && duration > 0) "${MediaFormat.duration(shown)} / ${MediaFormat.duration(duration)}" else ""

        keys.render()
        volumeRow.visibility = if (volume.present()) View.VISIBLE else View.GONE
        volume.render()
    }
}

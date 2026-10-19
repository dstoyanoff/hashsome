package dev.hashsome.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.hashsome.client.AssetSize
import dev.hashsome.client.EntityKind
import dev.hashsome.client.EntityRef
import dev.hashsome.client.MediaPlayerEntity
import dev.hashsome.client.PlaybackState
import dev.hashsome.headless.EntityHandle
import dev.hashsome.headless.Feedback
import dev.hashsome.headless.HandleState
import dev.hashsome.headless.MediaPlayerControl
import dev.hashsome.headless.Scope
import dev.hashsome.headless.Speaker
import dev.hashsome.headless.SpeakersControl
import dev.hashsome.ui.icons.Ui

// The speakers of a player's stream: who plays together with it, a volume for each, a way to take one out, to add another, to join a
// stream playing in another room, and to put the stream back to just this player.

private fun wrap() = FlexParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

private fun across() = FlexParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

/** A speaker's picture: what it plays when it plays, else the speaker glyph, in a rounded square of the raised surface. */
private class SpeakerPicture(context: Context) : View(context) {
    var picture: Bitmap? = null
        set(value) {
            field = value
            fill.set(value)
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val fill = PictureFill()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = dp(SIDE)
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        fill.draw(canvas, rect, dp(t.radius.small), Ui.speaker, t.palette.textMuted, paint)
    }

    private companion object {
        const val SIDE = 40
    }
}

/** A modal question with a way out and a way on, for the two things that stop what plays somewhere else (the web's confirm dialog). */
internal class ConfirmCard(context: Context, private val question: String, private val explanation: String, private val go: () -> Unit) : Overlay(context) {
    override fun show(content: View) {
        attach()
        val t = theme
        val u = unitPx()
        val card = ColumnLayout(context).also {
            it.gap = 3
            it.background = surface(FloatArray(4) { t.radius.card })
            it.elevation = elevationOf(t.shadow.drawer)
            it.isClickable = true
            val pad = Math.round(5 * u)
            it.setPadding(pad, pad, pad, pad)
        }
        card.addView(LabelView(context, Role.Heading).also { it.text = question }, across())
        card.addView(wrappedText(explanation), across())
        val buttons = RowLayout(context).also {
            it.gap = 2
            it.justify = Justify.End
        }
        buttons.addView(PillButton(context, null, "Cancel", Role.Label).also { it.onClick = { dismiss() } }, wrap())
        buttons.addView(PillButton(context, null, "Go ahead", Role.Label).also {
            it.primary = true
            it.onClick = {
                dismiss()
                go()
            }
        }, wrap())
        card.addView(buttons, across().also { it.topMargin = Math.round(u) })
        val width = minOf(dp(CARD), resources.displayMetrics.widthPixels - dp(40))
        addView(card, LayoutParams(width, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        if (Animation.animates) {
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(200).setInterpolator(EASE_OUT).start()
            card.alpha = 0f
            card.animate().alpha(1f).setDuration(200).setInterpolator(EASE_OUT).start()
        }
    }

    /** Several lines of the muted body text. */
    private fun wrappedText(text: String): View = android.widget.TextView(context).also {
        val t = theme
        val spec = t.style(Role.Body)
        it.text = text
        it.setTextColor(t.palette.textMuted)
        it.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textPx(spec.size.toFloat()))
        it.typeface = t.typefaces.forWeight(spec.weight)
        it.includeFontPadding = false
    }

    override fun dismiss() {
        leave(null)
    }

    /** Asks. */
    fun open() = show(View(context))

    private companion object {
        const val CARD = 360
    }
}

/** What a row of the speakers offers to do. */
private class Act(val label: String, val icon: Icon, val key: String, val disabled: Boolean = false, val run: () -> Unit)

/** One speaker in the stream: its name and a note ("Leading"), its actions, and a volume bar under them. */
private class StreamRow(
    context: Context,
    private val mp: MediaPlayerControl,
    private val label: String?,
    private val note: String?,
    private val acts: List<PillButton>,
) : BoundView(context) {
    private val name = LabelView(context, Role.BodyStrong)
    private val aside = LabelView(context, Role.Secondary, Tone.Muted)
    private val volume = VolumeParts(context, mp)
    private val level = RowLayout(context).also { it.gap = 2 }

    init {
        val column = ColumnLayout(context).also {
            it.gap = 2
            val pad = Math.round(2 * unitPx())
            it.setPadding(0, pad, 0, pad)
        }
        val head = RowLayout(context).also { it.gap = 3 }
        val names = RowLayout(context).also { it.gap = 2 }
        names.addView(name, wrap().also { it.shrink = true; it.grow = false })
        names.addView(aside, wrap())
        head.addView(names, wrap().also { it.grow = true; it.shrink = true })
        val actions = RowLayout(context).also { it.gap = 2 }
        acts.forEach { actions.addView(it, wrap()) }
        head.addView(actions, wrap())
        column.addView(head, across())
        level.addView(volume.bar, wrap().also { it.grow = true; it.shrink = true })
        level.addView(volume.percent, wrap())
        column.addView(level, across())
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val redraw: (Any?) -> Unit = { render() }
        onDetach(mp.handle.state.listen(redraw))
        onDetach(mp.volume.shown.listen(redraw))
        onDetach(mp.volume.muted.listen(redraw))
        render()
    }

    private fun render() {
        val state = mp.handle.state.value
        val available = state is HandleState.Ready
        val player = mp.handle.entity
        name.text = label ?: player?.name ?: mp.handle.ref?.id.orEmpty()
        val status = mediaStatusLabel(state)
        aside.text = (if (available) note else status).orEmpty()
        aside.visibility = if (aside.text.isEmpty()) GONE else VISIBLE
        alpha = if (available) 1f else 0.55f
        level.visibility = if (available && player?.capabilities?.volume == true) VISIBLE else GONE
        volume.render()
    }
}

/** One speaker that is not in the stream: its picture, name and what it is doing, and whatever its actions are. */
private class SpeakerRow(context: Context, private val handle: EntityHandle<MediaPlayerEntity>, private val acts: List<PillButton>) : BoundView(context) {
    private val picture = SpeakerPicture(context)
    private val name = LabelView(context, Role.BodyStrong)
    private val status = LabelView(context, Role.Secondary, Tone.Muted)
    private val art = ArtworkSlot(AssetSize.Small, { dp(40) }) { picture.picture = it }

    init {
        val row = RowLayout(context).also {
            it.gap = 3
            val pad = Math.round(1.5f * unitPx())
            it.setPadding(0, pad, 0, pad)
        }
        row.addView(picture, wrap())
        val text = ColumnLayout(context)
        text.addView(name, across())
        text.addView(status, across())
        row.addView(text, wrap().also { it.grow = true; it.shrink = true })
        val actions = RowLayout(context).also { it.gap = 2 }
        acts.forEach { actions.addView(it, wrap()) }
        row.addView(actions, wrap())
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        onDetach(handle.state.listen { render() })
        render()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        art.stop()
    }

    private fun render() {
        val state = handle.state.value
        val available = state is HandleState.Ready
        val player = handle.entity
        name.text = player?.name ?: handle.ref?.id.orEmpty()
        val playing = available && player?.playback == PlaybackState.Playing
        status.text = when {
            !available -> mediaStatusLabel(state).orEmpty()
            playing -> "Playing ${player?.media?.title ?: "something"}"
            player?.playback == PlaybackState.Paused -> "Paused"
            else -> "Idle"
        }
        art.set(if (playing) player?.media?.artworkUrl else null)
        alpha = if (available) 1f else 0.55f
    }
}

/**
 * The speakers of a player's stream: who plays together with it (a volume for each, a way to take one out), the ones playing something
 * else that it could join or add, and the quiet ones it could add. Playing something else on a speaker, or on this player, asks first.
 */
open class MediaSpeakersView(context: Context, val control: SpeakersControl) : BoundView(context) {
    private val scroll = LibraryScroll(context)
    private val list = ColumnLayout(context).also { it.gap = 4 }
    private var rowScope: Scope? = null
    private var mineScope: Scope? = null
    private var mine: MediaPlayerControl? = null
    private var shownKey: String? = null

    init {
        scroll.addView(list, FrameLayout.LayoutParams(-1, -2))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val redraw: (Any?) -> Unit = { render() }
        onDetach(control.handle.state.listen(redraw))
        onDetach(control.speakers.listen(redraw))
        onDetach(control.status.listen(redraw))
        onDetach(control.feedback.listen(redraw))
        onDetach {
            rowScope?.dispose()
            mineScope?.dispose()
            mine = null
            shownKey = null
        }
        render()
    }

    private fun section(title: String, vararg rows: View): View {
        val box = ColumnLayout(context).also { it.gap = 1 }
        box.addView(LabelView(context, Role.Eyebrow, Tone.Muted).also { it.text = title.uppercase() }, across())
        rows.forEach { box.addView(it, across()) }
        return box
    }

    private fun pill(act: Act): PillButton {
        val state = control.feedback.value[act.key]
        return PillButton(context, act.icon, act.label, Role.Label).also {
            it.heightDp = 32
            it.padUnits = 3.5f
            it.gapUnits = 1.5f
            it.contentDescription = act.label
            it.pending = state == Feedback.Pending
            it.failed = state == Feedback.Error
            it.isEnabled = !act.disabled && state != Feedback.Pending
            it.onClick = act.run
        }
    }

    /** This player's own control (its volume), made once for as long as the view is shown. */
    private fun mineControl(): MediaPlayerControl = mine ?: run {
        val scope = control.scope.child()
        mineScope = scope
        MediaPlayerControl(control.handle, scope).also { mine = it }
    }

    private fun ask(title: String, text: String, go: () -> Unit) {
        ConfirmCard(context, title, text, go).open()
    }

    /** What the rows depend on: when it is the same as what is on show, nothing is made again (a bar being dragged stays in the hand). */
    private fun structure(): String {
        val player = control.handle.entity
        val group = player?.group
        val b = StringBuilder()
        b.append(control.handle.state.value.javaClass.simpleName).append('|').append(player?.name).append('|').append(player?.capabilities?.group)
        b.append('|').append(player?.playback == PlaybackState.Playing)
        b.append('|').append(group?.leader).append(group?.members)
        control.speakers.value.forEach { b.append('|').append(it.ref).append(it.name).append(it.joined) }
        control.status.value.forEach { (k, v) -> b.append('|').append(k).append(v) }
        control.feedback.value.forEach { (k, v) -> b.append('|').append(k).append(v) }
        return b.toString()
    }

    private fun render() {
        val key = structure()
        if (key == shownKey) return
        shownKey = key
        rowScope?.dispose()
        val scope = control.scope.child()
        rowScope = scope
        list.removeAllViews()
        val state = control.handle.state.value
        val player = control.handle.entity
        val me = control.handle.ref
        if (state !is HandleState.Ready || player == null || me == null) {
            if (state is HandleState.Loading) list.addView(SkeletonList(context, 3), across()) else list.addView(message(context, mediaStatusLabel(state).orEmpty()), across())
            return
        }
        val own = player.name
        if (!player.capabilities.group) {
            list.addView(message(context, "$own cannot be grouped with other speakers."), across())
            return
        }

        val group = player.group
        val follows = group != null && group.leader != me
        val all = control.speakers.value
        val others = all.filter { it.joined }
        val status = control.status.value
        val handsOver = !follows && others.isNotEmpty()
        val soleHeir = if (handsOver && others.size == 1) others[0] else null

        fun handleOf(ref: EntityRef): EntityHandle<MediaPlayerEntity> = if (ref == me) control.handle else EntityHandle(scope, ref, EntityKind.MediaPlayer)

        val stream = ArrayList<View>()
        val myActs = when {
            follows -> listOf(Act("Leave", Ui.logOut, "group:reset") { control.leave() })
            soleHeir != null -> listOf(Act("Leave", Ui.logOut, "group:handover") { control.leave() })
            else -> emptyList()
        }
        stream += StreamRow(context, mineControl(), own, null, myActs.map { pill(it) })
        for (speaker in others) {
            val ref = speaker.ref
            val leading = ref == group?.leader
            val acts = if (leading) {
                listOf(Act("Remove", UiIcons.close, "group:takeover") { control.takeOver() })
            } else {
                buildList {
                    if (handsOver && soleHeir == null) add(Act("Lead", Ui.crown, "group:lead:$ref") { control.lead(ref) })
                    add(Act("Remove", UiIcons.close, "group:remove:$ref") { control.remove(ref) })
                }
            }
            stream += StreamRow(context, MediaPlayerControl(handleOf(ref), scope), null, if (leading) "Leading" else null, acts.map { pill(it) })
        }
        list.addView(section("This stream", *stream.toTypedArray()), across())

        val candidates = all.filter { !it.joined }
        val (elsewhere, quiet) = candidates.partition { status[it.ref]?.playsOwn == true }
        if (elsewhere.isNotEmpty()) {
            val rows = elsewhere.map { speaker -> SpeakerRow(context, handleOf(speaker.ref), elsewhereActs(speaker, player, own, status[speaker.ref]?.available == true, status[speaker.ref]?.playing).map { pill(it) }) }
            list.addView(section("Playing elsewhere", *rows.toTypedArray()), across())
        }
        val quietRows: Array<View> = if (quiet.isEmpty()) {
            arrayOf(message(context, "No other speakers to add."))
        } else {
            quiet.map { speaker ->
                val available = status[speaker.ref]?.available == true
                SpeakerRow(context, handleOf(speaker.ref), listOf(pill(Act("Add", Ui.plus, "group:add:${speaker.ref}", disabled = !available) { control.add(speaker.ref) })))
            }.toTypedArray()
        }
        list.addView(section("Add to this stream", *quietRows), across())
    }

    private fun elsewhereActs(speaker: Speaker, player: MediaPlayerEntity, own: String, available: Boolean, playing: String?): List<Act> {
        val join = Act("Join", Ui.logIn, "group:join:${speaker.ref}", disabled = !available) {
            // Joining stops what this player plays, so it asks when it is playing.
            if (player.playback != PlaybackState.Playing) {
                control.join(speaker.ref)
            } else {
                ask("Join ${speaker.name}'s stream?", "$own will stop what it plays and play ${speaker.name}'s instead.") { control.join(speaker.ref) }
            }
        }
        val add = Act("Add", Ui.plus, "group:add:${speaker.ref}", disabled = !available) {
            if (playing == null) {
                control.add(speaker.ref)
            } else {
                ask("Play this on ${speaker.name}?", "${speaker.name} is playing $playing. It will play this stream instead.") { control.add(speaker.ref) }
            }
        }
        return listOf(join, add)
    }
}

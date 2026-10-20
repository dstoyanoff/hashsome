package dev.hashsome.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.os.SystemClock
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
        buttons.addView(PillButton(context, null, "Cancel", Role.Label).also {
            it.flat = true
            it.onClick = { dismiss() }
        }, wrap())
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

/**
 * A row's actions as ghost pills (a glyph and words, no fill until pressed: a deliberate native divergence from the web's raised pill). The
 * pills are kept by the action's key and set in place, so a press's pending ring changes the button and nothing else about the row.
 */
private class Actions(context: Context, private val feedback: () -> Map<String, Feedback>) : RowLayout(context) {
    private val pills = HashMap<String, PillButton>()

    init {
        gap = 2
    }

    fun set(acts: List<Act>) {
        val state = feedback()
        val keys = acts.map { it.key }
        val gone = pills.keys.filter { it !in keys }
        for (k in gone) pills.remove(k)?.let { removeView(it) }
        acts.forEachIndexed { i, act ->
            val pill = pills.getOrPut(act.key) {
                PillButton(context, act.icon, act.label, Role.Label).also {
                    it.flat = true
                    it.heightDp = 32
                    it.padUnits = 3.5f
                    it.gapUnits = 1.5f
                }
            }
            if (pill.icon != act.icon) pill.icon = act.icon
            if (pill.label != act.label) pill.label = act.label
            pill.contentDescription = act.label
            val now = state[act.key]
            pill.pending = now == Feedback.Pending
            pill.failed = now == Feedback.Error
            val enabled = !act.disabled && now != Feedback.Pending
            if (pill.isEnabled != enabled) pill.isEnabled = enabled
            pill.onClick = act.run
            if (getChildAt(i) !== pill) {
                if (pill.parent === this) removeView(pill)
                addView(pill, i, wrap())
            }
        }
    }
}

/** One speaker in the stream: its name and a note ("Leading"), its actions, and a volume bar under them. */
private class StreamRow(
    context: Context,
    private val mp: MediaPlayerControl,
    feedback: () -> Map<String, Feedback>,
) : BoundView(context) {
    private val name = LabelView(context, Role.BodyStrong)
    private val aside = LabelView(context, Role.Secondary, Tone.Muted)
    private val volume = VolumeParts(context, mp)
    private val level = RowLayout(context).also { it.gap = 2 }
    private val actions = Actions(context, feedback)
    private var label: String? = null
    private var note: String? = null

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
        head.addView(actions, wrap())
        column.addView(head, across())
        level.addView(volume.bar, wrap().also { it.grow = true; it.shrink = true })
        level.addView(volume.percent, wrap())
        column.addView(level, across())
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** What the row shows now; the row itself stays. */
    fun bind(label: String?, note: String?, acts: List<Act>) {
        this.label = label
        this.note = note
        actions.set(acts)
        render()
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
private class SpeakerRow(context: Context, private val handle: EntityHandle<MediaPlayerEntity>, feedback: () -> Map<String, Feedback>) : BoundView(context) {
    private val picture = SpeakerPicture(context)
    private val name = LabelView(context, Role.BodyStrong)
    private val status = LabelView(context, Role.Secondary, Tone.Muted)
    private val art = ArtworkSlot(AssetSize.Small, { dp(40) }) { picture.picture = it }
    private val actions = Actions(context, feedback)

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
        row.addView(actions, wrap())
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(acts: List<Act>) = actions.set(acts)

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
 * A place in the list: a header, a row or a note, with the collapse it is going through ([p]: 0 whole, 1 gone) as its height and fade,
 * and the scope its row's handle lives in. Joining expands it, leaving collapses it, and the places below follow.
 */
private class Slot(context: Context, val key: String, val content: View, val scope: Scope, private val gapUnits: Float) : ViewGroup(context) {
    var p = 0f
        private set
    var leaving = false
    var moving = false
    var from = 0f
    var to = 0f
    var startedAt = 0L

    init {
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** A row that is leaving takes no more presses (no second Remove) while it collapses. */
    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean = leaving || super.onInterceptTouchEvent(ev)

    fun collapse(value: Float) {
        if (p == value) return
        p = value
        alpha = RowCollapse.alpha(1f, value)
        visibility = if (value >= 1f) INVISIBLE else VISIBLE
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        content.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val whole = content.measuredHeight + Math.round(gapUnits * unitPx())
        setMeasuredDimension(w, RowCollapse.size(whole, p))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val top = Math.round(gapUnits * unitPx())
        content.layout(0, top, content.measuredWidth, top + content.measuredHeight)
    }
}

/** What one place of the list should be: made once when it first appears, set (in place) on every render after. */
private class Want(val key: String, val gap: Float, val make: (Scope) -> View, val set: (View) -> Unit)

/**
 * The speakers of a player's stream: who plays together with it (a volume for each, a way to take one out), the ones playing something
 * else that it could join or add, and the quiet ones it could add. Playing something else on a speaker, or on this player, asks first.
 *
 * The rows are kept by what they are (a speaker in a section) and set in place as the state changes, so nothing is made again for a
 * press, a poll or a volume; a row that joins expands and one that leaves collapses, as the queue's rows do.
 */
open class MediaSpeakersView private constructor(context: Context, lazyControl: Lazy<SpeakersControl>, @Suppress("UNUSED_PARAMETER") marker: Unit) :
    BoundView(context) {
    constructor(context: Context, control: SpeakersControl) : this(context, lazyOf(control), Unit)

    /** For a panel made ahead: the control (and its watches) starts when the view is first shown. */
    constructor(context: Context, scope: Scope, handle: EntityHandle<MediaPlayerEntity>, allow: List<EntityRef>) :
        this(context, lazy(LazyThreadSafetyMode.NONE) { SpeakersControl(handle, scope, allow) }, Unit)

    val control: SpeakersControl by lazyControl

    private val scroll = LibraryScroll(context)
    private val list = ColumnLayout(context)
    private var mineScope: Scope? = null
    private var mine: MediaPlayerControl? = null

    // Every place of the list by key, and in the order they are shown (those leaving still in it until they are gone).
    private val slots = HashMap<String, Slot>()
    private val order = ArrayList<Slot>()
    private var plain: View? = null
    private var built = false
    private var ticking = false
    private val gone = ArrayList<Slot>()
    private val frame = Runnable { step() }

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
            removeCallbacks(frame)
            ticking = false
            clearAll()
            mineScope?.dispose()
            mineScope = null
            mine = null
        }
        render()
    }

    private fun header(title: String): View = LabelView(context, Role.Eyebrow, Tone.Muted).also { it.text = title.uppercase() }

    private val feedback: () -> Map<String, Feedback> = { control.feedback.value }

    /** This player's own control (its volume), made once for as long as the view is shown. */
    private fun mineControl(): MediaPlayerControl = mine ?: run {
        val scope = control.scope.child()
        mineScope = scope
        MediaPlayerControl(control.handle, scope).also { mine = it }
    }

    private fun ask(title: String, text: String, go: () -> Unit) {
        ConfirmCard(context, title, text, go).open()
    }

    private fun clearAll() {
        for (s in order) s.scope.dispose()
        order.clear()
        slots.clear()
        list.removeAllViews()
        plain = null
        built = false
    }

    /** Shows one thing in the place of the list (a skeleton, or why there is nothing to show). */
    private fun only(view: View) {
        clearAll()
        plain = view
        list.addView(view, across())
    }

    // A store update that arrives while the list is being brought up to date is run once more after it, not inside it.
    private var busy = false
    private var again = false

    private fun render() {
        if (busy) {
            again = true
            return
        }
        busy = true
        try {
            do {
                again = false
                draw()
            } while (again)
        } finally {
            busy = false
        }
    }

    private fun draw() {
        val state = control.handle.state.value
        val player = control.handle.entity
        val me = control.handle.ref
        if (state !is HandleState.Ready || player == null || me == null) {
            only(if (state is HandleState.Loading) SkeletonList(context, 3) else message(context, mediaStatusLabel(state).orEmpty()))
            return
        }
        val own = player.name
        if (!player.capabilities.group) {
            only(message(context, "$own cannot be grouped with other speakers."))
            return
        }
        plain?.let {
            list.removeView(it)
            plain = null
        }

        val group = player.group
        val follows = group != null && group.leader != me
        val all = control.speakers.value
        val others = all.filter { it.joined }
        val status = control.status.value
        val handsOver = !follows && others.isNotEmpty()
        val soleHeir = if (handsOver && others.size == 1) others[0] else null

        val wants = ArrayList<Want>()
        wants += Want("h:stream", 0f, { header("This stream") }, {})
        val myActs = when {
            follows -> listOf(Act("Leave", Ui.logOut, "group:reset") { control.leave() })
            soleHeir != null -> listOf(Act("Leave", Ui.logOut, "group:handover") { control.leave() })
            else -> emptyList()
        }
        wants += Want("s:$me", 1f, { StreamRow(context, mineControl(), feedback) }, { (it as StreamRow).bind(own, null, myActs) })
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
            wants += Want("s:$ref", 1f, { scope -> StreamRow(context, MediaPlayerControl(EntityHandle(scope, ref, EntityKind.MediaPlayer), scope), feedback) }, {
                (it as StreamRow).bind(null, if (leading) "Leading" else null, acts)
            })
        }

        val candidates = all.filter { !it.joined }
        val (elsewhere, quiet) = candidates.partition { status[it.ref]?.playsOwn == true }
        if (elsewhere.isNotEmpty()) {
            wants += Want("h:elsewhere", 4f, { header("Playing elsewhere") }, {})
            for (speaker in elsewhere) {
                val acts = elsewhereActs(speaker, player, own, status[speaker.ref]?.available == true, status[speaker.ref]?.playing)
                wants += Want("e:${speaker.ref}", 1f, { scope -> SpeakerRow(context, EntityHandle(scope, speaker.ref, EntityKind.MediaPlayer), feedback) }, { (it as SpeakerRow).bind(acts) })
            }
        }
        wants += Want("h:add", 4f, { header("Add to this stream") }, {})
        if (quiet.isEmpty()) {
            wants += Want("n:none", 1f, { message(context, "No other speakers to add.") }, {})
        } else {
            for (speaker in quiet) {
                val available = status[speaker.ref]?.available == true
                val acts = listOf(Act("Add", Ui.plus, "group:add:${speaker.ref}", disabled = !available) { control.add(speaker.ref) })
                wants += Want("q:${speaker.ref}", 1f, { scope -> SpeakerRow(context, EntityHandle(scope, speaker.ref, EntityKind.MediaPlayer), feedback) }, { (it as SpeakerRow).bind(acts) })
            }
        }
        reconcile(wants)
    }

    /** Keeps the places that are still wanted (set in place), brings in the new ones and sends off the ones that are not. */
    private fun reconcile(wants: List<Want>) {
        val wanted = HashSet<String>()
        for (w in wants) wanted += w.key
        // Without motion a leaver is dropped at once, out of the list being walked.
        for (s in order.toTypedArray()) {
            if (s.key !in wanted && !s.leaving) {
                s.leaving = true
                move(s, 1f)
            }
        }
        val before = order.map { it.key }
        val arriving = ArrayList<Slot>()
        for (w in wants) {
            var slot = slots[w.key]
            if (slot == null) {
                val scope = control.scope.child()
                slot = Slot(context, w.key, w.make(scope), scope, w.gap)
                slots[w.key] = slot
                // The first showing is not an arrival: only what joins afterwards expands.
                if (built && Animation.animates) slot.collapse(1f)
                arriving += slot
            } else if (slot.leaving) {
                slot.leaving = false
                move(slot, 0f)
            }
            w.set(slot.content)
        }
        val keys = RowOrder.merge(before, wants.map { it.key })
        order.clear()
        // A key that is gone from the map (dropped while this ran) is skipped, never looked up as present.
        for (k in RowOrder.live(keys) { slots.containsKey(it) }) slots[k]?.let { order += it }
        for (i in order.indices) {
            val slot = order[i]
            if (list.getChildAt(i) === slot) continue
            if (slot.parent === list) list.removeView(slot)
            list.addView(slot, i, across())
        }
        built = true
        for (slot in arriving) if (slot.p > 0f) move(slot, 0f)
    }

    /** Takes [slot] toward [to] (1 gone, 0 whole) in the queue's 160 ms, or at once when motion is off. */
    private fun move(slot: Slot, to: Float) {
        slot.from = slot.p
        slot.to = to
        if (!Animation.animates || !isAttachedToWindow || slot.p == to) {
            slot.moving = false
            slot.collapse(to)
            if (slot.leaving) drop(slot)
            return
        }
        slot.startedAt = SystemClock.uptimeMillis()
        slot.moving = true
        // One frame callback moves every place that is going, each on its own clock (not an animator: the device's animation scale must
        // not cut a collapse short), and nothing is made for a frame.
        if (!ticking) {
            ticking = true
            postOnAnimation(frame)
        }
    }

    private fun step() {
        val now = SystemClock.uptimeMillis()
        var done = false
        var going = false
        for (i in order.indices) {
            val slot = order[i]
            if (!slot.moving) continue
            val elapsed = now - slot.startedAt
            slot.collapse(RowCollapse.at(slot.from, slot.to, elapsed))
            if (elapsed >= RowCollapse.MS) {
                slot.moving = false
                if (slot.leaving) done = true
            } else {
                going = true
            }
        }
        if (done) {
            // Collected first: dropping changes the order being walked.
            gone.clear()
            for (i in order.indices) if (order[i].leaving && !order[i].moving) gone += order[i]
            for (i in gone.indices) drop(gone[i])
            gone.clear()
        }
        if (going) postOnAnimation(frame) else ticking = false
    }

    private fun drop(slot: Slot) {
        order.remove(slot)
        slots.remove(slot.key)
        list.removeView(slot)
        slot.scope.dispose()
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

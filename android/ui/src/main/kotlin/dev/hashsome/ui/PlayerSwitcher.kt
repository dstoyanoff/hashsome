package dev.hashsome.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import dev.hashsome.client.Cancel
import dev.hashsome.client.EntityRef
import dev.hashsome.client.MediaPlayerEntity
import dev.hashsome.client.PlaybackState
import dev.hashsome.client.Snapshot
import dev.hashsome.headless.Scope
import dev.hashsome.ui.icons.Ui

/** One player a card can control: the media player [ref] (`ma:...`) and the [label] it is called. See `mediaPlayerFull(players = ...)`. */
data class PlayerChoice(val ref: String, val label: String)

/**
 * Which player a card controls, kept while the app runs so that leaving the page and coming back finds the same one. A card is known by its
 * first choice, so it starts on that one and goes back to it when the app restarts.
 */
internal object PlayerSelection {
    private val chosen = HashMap<String, String>()

    /** The choice the card over [choices] is on: the one picked last, else the first. */
    fun of(choices: List<PlayerChoice>): PlayerChoice =
        chosen[choices.first().ref]?.let { ref -> choices.firstOrNull { it.ref == ref } } ?: choices.first()

    fun choose(choices: List<PlayerChoice>, choice: PlayerChoice) {
        chosen[choices.first().ref] = choice.ref
    }
}

/**
 * The name of the player a card controls, with a chevron: it opens a list of the players, each with what it is doing, and picking one makes the
 * card control that one (to stop what is playing in another room, say). A small dot after the name says another player is playing. It is a ghost
 * on the card, with no fill until pressed.
 */
internal class PlayerChip(
    context: Context,
    private val choices: List<PlayerChoice>,
    private val current: PlayerChoice,
    scope: Scope,
    private val onChoose: (PlayerChoice) -> Unit,
) : RowLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val name = MarqueeText(context).also { it.role = Role.Label; it.text = current.label }
    private val glyph = GlyphView(context, Ui.speaker, 14)
    private val chevron = GlyphView(context, Ui.chevronDown, 14)
    private var pressed = false
    private var menu: OptionMenu? = null

    /** What each player is doing, by ref: whether it plays, and the title. */
    private val doing = HashMap<String, Pair<Boolean, String?>>()
    private val watching = ArrayList<Cancel>()

    init {
        gap = 2
        align = Align.Center
        setWillNotDraw(false)
        isClickable = true
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        addView(glyph, FlexParams(wrap, wrap))
        addView(name, FlexParams(wrap, wrap))
        addView(chevron, FlexParams(wrap, wrap))
        contentDescription = "Player: ${current.label}"
        choices.forEach { choice ->
            watching += scope.watch(EntityRef(choice.ref)) { snapshot ->
                val player = (snapshot as? Snapshot.Present)?.entity as? MediaPlayerEntity
                doing[choice.ref] = (player?.playback == PlaybackState.Playing) to player?.media?.title
                invalidate()
            }
        }
        setOnClickListener { open() }
    }

    private fun othersPlaying() = choices.any { it.ref != current.ref && doing[it.ref]?.first == true }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        watching.forEach { it() }
        watching.clear()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val pad = Math.round(3 * unitPx())
        if (paddingLeft != pad) setPadding(pad, 0, pad, 0)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredWidth + dp(DOT_ROOM), Math.max(measuredHeight, dp(30)))
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        if (pressed) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            paint.color = t.palette.surfaceRaised
            canvas.drawRoundRect(rect, height / 2f, height / 2f, paint)
        }
        if (othersPlaying()) {
            // After the chevron, in the room the measure left for it.
            paint.color = t.palette.accent
            canvas.drawCircle(width - paddingRight - dp(DOT_ROOM) / 2f, height / 2f, dp(3f), paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressed = false
                invalidate()
            }
        }
        return super.onTouchEvent(event)
    }

    private fun open() {
        if (menu != null) return
        val items = choices.map { choice ->
            val state = doing[choice.ref]
            val detail = when {
                state == null -> null
                state.first -> state.second?.let { "Playing · $it" } ?: "Playing"
                else -> "Idle"
            }
            MenuItem(choice.label, Ui.speaker, detail)
        }
        menu = OptionMenu(context, items, choices.indexOfFirst { it.ref == current.ref }.takeIf { it >= 0 }) { index -> onChoose(choices[index]) }.also {
            it.onDismissed = { menu = null }
            it.showFrom(this)
        }
    }

    private companion object {
        /** Room after the chevron for the dot, in dp, taken always so the chip does not change width when a player starts. */
        const val DOT_ROOM = 10
    }
}

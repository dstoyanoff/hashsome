package dev.hashsome.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import dev.hashsome.client.BrowseItem
import dev.hashsome.client.BrowseKind
import dev.hashsome.headless.Feedback
import dev.hashsome.ui.icons.Ui

/** How a library lays its items out. */
enum class BrowseLayout {
    /** Rows, compact enough for a narrow panel. */
    List,

    /** One row of large cards that scrolls sideways, for a wide space (the web's `theater`). */
    Theater,
}

/**
 * An item of the library as a large card (the web's `BrowseCard` of the theater layout): big artwork (round for an artist), then the title and
 * subtitle. It opens or plays like a row does, and what can be done with it (add it to the queue, play an album or a playlist) is a pair of small
 * buttons over the artwork's corner; the item that is playing has the player's pause and play there instead.
 */
internal class BrowseCard(context: Context) : ViewGroup(context) {
    /** The artwork's side, in px: set by the row the card is in, which makes the cards as large as the room allows. */
    var artPx: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    var picture: Bitmap? = null
        set(value) {
            field = value
            shader = value?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            invalidate()
        }

    var onOpen: (() -> Unit)? = null
    var onPlay: (() -> Unit)? = null
    var onToggle: (() -> Unit)? = null
    var onAdd: (() -> Unit)? = null

    private var kind: BrowseKind = BrowseKind.Track
    private var expandable = false
    private var playable = false
    private var active = false
    private var waiting = false
    private var shader: BitmapShader? = null

    private val title = MarqueeText(context).also { it.role = Role.BodyStrong }
    private val subtitle = MarqueeText(context).also { it.role = Role.Secondary }
    private val opens = GlyphView(context, Ui.chevronRight, 16)
    private val addKey = GlyphButton(context, Ui.listPlus, 16f).also { it.sizeDp = KEY }
    private val playKey = GlyphButton(context, Ui.play, 16f).also { it.sizeDp = KEY }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val matrix = Matrix()
    private val spin = Spin(this)

    init {
        setWillNotDraw(false)
        isClickable = true
        addView(title)
        addView(opens)
        addView(subtitle)
        addView(addKey)
        addView(playKey)
        setOnClickListener {
            when {
                expandable -> onOpen?.invoke()
                playable && active -> onToggle?.invoke()
                playable -> onPlay?.invoke()
            }
        }
        addKey.onClick = { onAdd?.invoke() }
        playKey.onClick = { if (active) onToggle?.invoke() else onPlay?.invoke() }
    }

    /** Sets everything from an item and the state around it. [feedback] is what the browser's presses are doing, by `<mode>:<item id>`. */
    fun bind(item: BrowseItem, queueable: Boolean, active: Boolean, playing: Boolean, feedback: Map<String, Feedback>) {
        title.text = item.title
        subtitle.text = item.subtitle.orEmpty()
        subtitle.visibility = if (item.subtitle.isNullOrEmpty()) INVISIBLE else VISIBLE
        kind = item.kind
        expandable = item.expandable
        playable = item.playable
        this.active = active
        val p = theme.palette
        title.color = if (active) p.accent else null
        subtitle.color = p.textMuted
        opens.visibility = if (item.expandable) VISIBLE else GONE
        opens.color = p.textMuted
        waiting = feedback["replace:${item.id}"] == Feedback.Pending || feedback["play:${item.id}"] == Feedback.Pending
        spin.run(waiting && isAttachedToWindow)
        // The playing item has the player's key; the others can be queued, and an album or a playlist, which a tap opens, can also be played.
        addKey.visibility = if (!active && item.playable && queueable) VISIBLE else GONE
        addKey.feedback(feedback["add:${item.id}"])
        playKey.visibility = if (active || (item.playable && item.expandable)) VISIBLE else GONE
        playKey.icon = if (active && playing) Ui.pause else Ui.play
        playKey.primary = active
        invalidate()
    }

    private fun labelHeight() = dp(LABEL)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = if (artPx > 0) artPx else MeasureSpec.getSize(widthMeasureSpec)
        val wrap = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val chevron = if (opens.visibility == VISIBLE) dp(16) else 0
        opens.measure(wrap, wrap)
        title.measure(MeasureSpec.makeMeasureSpec(Math.max(0, side - chevron), MeasureSpec.AT_MOST), wrap)
        subtitle.measure(MeasureSpec.makeMeasureSpec(side, MeasureSpec.AT_MOST), wrap)
        for (key in listOf(addKey, playKey)) key.measure(wrap, wrap)
        setMeasuredDimension(side, side + labelHeight())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val side = r - l
        val gap = Math.round(2 * unitPx())
        var y = side + gap
        title.layout(0, y, title.measuredWidth, y + title.measuredHeight)
        if (opens.visibility == VISIBLE) {
            val cy = y + title.measuredHeight / 2
            opens.layout(title.measuredWidth, cy - opens.measuredHeight / 2, title.measuredWidth + opens.measuredWidth, cy + opens.measuredHeight / 2)
        }
        y += title.measuredHeight
        subtitle.layout(0, y, subtitle.measuredWidth, y + subtitle.measuredHeight)
        // The keys over the artwork's lower right corner, the last one in the corner.
        val edge = Math.round(2 * unitPx())
        var right = side - edge
        for (key in listOf(playKey, addKey)) {
            if (key.visibility == GONE) continue
            val bottom = side - edge
            key.layout(right - key.measuredWidth, bottom - key.measuredHeight, right, bottom)
            right -= key.measuredWidth + edge
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        spin.run(waiting)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        spin.run(false)
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        val side = width.toFloat()
        val round = kind == BrowseKind.Artist
        val radius = if (round) side / 2 else dp(t.radius.row)
        rect.set(0f, 0f, side, side)
        val bitmap = picture
        val cover = shader
        if (bitmap != null && cover != null) {
            val scale = Math.max(side / bitmap.width, side / bitmap.height)
            matrix.setScale(scale, scale)
            matrix.postTranslate((side - bitmap.width * scale) / 2f, (side - bitmap.height * scale) / 2f)
            cover.setLocalMatrix(matrix)
            paint.shader = cover
            paint.style = Paint.Style.FILL
            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = null
        } else {
            paint.style = Paint.Style.FILL
            paint.color = t.palette.surfaceRaised
            canvas.drawRoundRect(rect, radius, radius, paint)
            val glyph = dp(56)
            canvas.drawIcon(iconBitmap(kindIcon(kind), glyph, t.palette.textMuted), (side - glyph) / 2f, (side - glyph) / 2f)
        }
        if (waiting) {
            // The answer to a press is awaited: the artwork dims and a ring turns on it.
            paint.color = withAlpha(t.palette.bg, 0.5f)
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(3f)
            paint.color = t.palette.accent
            val r = dp(16f)
            rect.set(side / 2 - r, side / 2 - r, side / 2 + r, side / 2 + r)
            canvas.drawArc(rect, spin.angle, 90f, false, paint)
            paint.style = Paint.Style.FILL
        }
    }

    companion object {
        /** What a card needs under its artwork for the title and subtitle, in dp. */
        const val LABEL = 56

        /** The keys over the artwork, in dp. */
        private const val KEY = 32
    }
}

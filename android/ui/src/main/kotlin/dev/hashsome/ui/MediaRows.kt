package dev.hashsome.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import dev.hashsome.client.BrowseItem
import dev.hashsome.client.BrowseKind
import dev.hashsome.client.QueueItem
import dev.hashsome.headless.Feedback
import dev.hashsome.ui.icons.Ui

// The two kinds of row the media lists are made of. Each is one view drawn on a canvas, laid out from its own width, so a list of
// them costs one view per row; the glyphs are bitmaps drawn once and shared (`iconBitmap`), because a vector drawable per row
// rasterised itself on the main thread as the rows arrived and stalled the arrival.

/** The glyph a picture-less item shows, by what it is. */
internal fun kindIcon(kind: BrowseKind): Icon = when (kind) {
    BrowseKind.Folder -> Ui.folder
    BrowseKind.Artist -> Ui.micVocal
    BrowseKind.Album -> Ui.disc3
    BrowseKind.Playlist -> Ui.listMusic
    BrowseKind.Track -> Ui.music
    BrowseKind.Radio -> Ui.radio
    BrowseKind.Other -> Ui.music
}

/** A picture as a shader: center-cropped into a rounded square by `drawRoundRect`, with no clip path and nothing made as it draws. */
internal class PictureFill {
    private var bitmap: Bitmap? = null
    private var shader: BitmapShader? = null
    private val matrix = Matrix()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    val has: Boolean get() = bitmap != null

    fun set(next: Bitmap?) {
        bitmap = next
        shader = next?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    }

    /** The picture in [rect], or the raised surface with [glyph] when there is none. */
    fun draw(canvas: Canvas, rect: RectF, radius: Float, glyph: Icon, glyphColor: Int, paint: Paint) {
        val t = theme
        paint.style = Paint.Style.FILL
        paint.color = t.palette.surfaceRaised
        canvas.drawRoundRect(rect, radius, radius, paint)
        val b = bitmap
        val sh = shader
        if (b != null && sh != null) {
            val scale = rect.width() / minOf(b.width, b.height)
            matrix.setScale(scale, scale)
            matrix.postTranslate(rect.left + (rect.width() - b.width * scale) / 2, rect.top + (rect.height() - b.height * scale) / 2)
            sh.setLocalMatrix(matrix)
            fill.shader = sh
            canvas.drawRoundRect(rect, radius, radius, fill)
        } else {
            val g = dp(20)
            canvas.drawBitmap(iconBitmap(glyph, g, glyphColor), rect.centerX() - g / 2f, rect.centerY() - g / 2f, null)
        }
    }
}

/**
 * One row of a library list: the picture, a title and a line under it, and what can be done with the item at the right. A tap on the
 * body opens what can be opened and otherwise plays; a playable item of a player with a queue has play next and add to queue, one that
 * opens and plays (an album, a playlist) also a play button, and the item playing carries the player's play/pause in their place.
 * What a button has asked for and is waiting on shows as a ring round it, and a play that is waiting as a spinner on the picture.
 */
open class BrowseRow(context: Context) : View(context) {
    // One drawn thing: its fade (a row arriving, a track played) is applied as it draws, with no layer of its own to render.
    override fun hasOverlappingRendering(): Boolean = false

    var title: String = ""
        set(value) {
            field = value
            dirty = true
            invalidate()
        }

    var subtitle: String? = null
        set(value) {
            field = value
            dirty = true
            invalidate()
        }

    var kind: BrowseKind = BrowseKind.Track
        set(value) {
            field = value
            invalidate()
        }

    var expandable: Boolean = false
        set(value) {
            field = value
            dirty = true
            geoDirty = true
            invalidate()
        }

    var playable: Boolean = false
        set(value) {
            field = value
            dirty = true
            geoDirty = true
            invalidate()
        }

    /** The player has a queue: items can be played next and added to it. */
    var queueable: Boolean = false
        set(value) {
            field = value
            dirty = true
            geoDirty = true
            invalidate()
        }

    /** The item is what the player plays (or plays from): its title is lit and its button is the player's play/pause. */
    var active: Boolean = false
        set(value) {
            field = value
            dirty = true
            geoDirty = true
            invalidate()
        }

    /** The player is playing, so the active item's button pauses. */
    var playing: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var picture: Bitmap? = null
        set(value) {
            field = value
            fill.set(value)
            invalidate()
        }

    /** A request to play the item has not been answered: a spinner turns on its picture. */
    var waiting: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            sync()
        }

    var pendingNext: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            sync()
        }

    var pendingAdd: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            sync()
        }

    var pendingPlay: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            sync()
        }

    var onOpen: (() -> Unit)? = null
    var onPlay: (() -> Unit)? = null
    var onToggle: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onAdd: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val oval = RectF()
    private val fill = PictureFill()
    private val spin = Spin(this)
    private var buttonList: List<RowButton> = emptyList()
    private var geo = RowGeometry(0f, 0f, 0f, 0)
    private var geoDirty = true
    private var shownTitle: CharSequence = ""
    private var shownSub: CharSequence = ""
    private var dirty = true
    private var pressed = -1

    /** Sets everything from an item and the state around it. [feedback] is what the browser's presses are doing, by `<mode>:<item id>`. */
    fun bind(item: BrowseItem, queueable: Boolean, active: Boolean, playing: Boolean, feedback: Map<String, Feedback>) {
        title = item.title
        subtitle = item.subtitle
        kind = item.kind
        expandable = item.expandable
        playable = item.playable
        this.queueable = queueable
        this.active = active
        this.playing = playing
        waiting = feedback["replace:${item.id}"] == Feedback.Pending || feedback["play:${item.id}"] == Feedback.Pending
        pendingNext = feedback["next:${item.id}"] == Feedback.Pending
        pendingAdd = feedback["add:${item.id}"] == Feedback.Pending
        pendingPlay = waiting
    }

    // What the row can do and where it is, worked out when something it depends on changes, not as it draws.
    private fun buttons(): List<RowButton> {
        refreshGeometry()
        return buttonList
    }

    private fun refreshGeometry() {
        if (!geoDirty && geo.width == width.toFloat()) return
        buttonList = BrowseActions.buttons(expandable, playable, queueable, active)
        geo = RowGeometry(width.toFloat(), dp(theme.density.iconCircle).toFloat(), 2 * unitPx(), buttonList.size)
        geoDirty = false
    }

    private fun sync() {
        spin.run((waiting || pendingNext || pendingAdd || pendingPlay) && isAttachedToWindow)
        invalidate()
    }

    private fun geometry(): RowGeometry {
        refreshGeometry()
        return geo
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), rowHeight())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        dirty = true
        geoDirty = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sync()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        spin.run(false)
    }

    private fun prepare(mainWidth: Float) {
        val t = theme
        titlePaint.style(t, Role.BodyStrong, t.palette.text)
        subPaint.style(t, Role.Secondary, t.palette.textMuted)
        val u = unitPx()
        val textX = 2 * u + dp(PICTURE) + 3 * u
        val room = maxOf(0f, mainWidth - textX - 2 * u - if (expandable) dp(16) + 3 * u else 0f)
        shownTitle = TextUtils.ellipsize(title, titlePaint, room, TextUtils.TruncateAt.END)
        shownSub = subtitle?.let { TextUtils.ellipsize(it, subPaint, room, TextUtils.TruncateAt.END) } ?: ""
        dirty = false
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        val u = unitPx()
        val h = height.toFloat()
        val buttons = buttons()
        val geo = geo
        val mainWidth = geo.mainWidth
        if (dirty) prepare(mainWidth)
        paint.style = Paint.Style.FILL
        if (pressed == 0) {
            paint.color = t.palette.surfaceRaised
            rect.set(0f, 0f, mainWidth, h)
            canvas.drawRoundRect(rect, dp(t.radius.row), dp(t.radius.row), paint)
        }

        // The picture: 44 dp, between 1.5 and 2 spacing units of padding.
        val side = dp(PICTURE).toFloat()
        val left = 2 * u
        val top = (h - side) / 2
        rect.set(left, top, left + side, top + side)
        fill.draw(canvas, rect, dp(t.radius.small), kindIcon(kind), t.palette.textMuted, paint)
        if (waiting) {
            paint.color = withAlpha(t.palette.surface, 0.6f)
            canvas.drawRoundRect(rect, dp(t.radius.small), dp(t.radius.small), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            paint.color = t.palette.accent
            val r = dp(10f)
            oval.set(rect.centerX() - r, rect.centerY() - r, rect.centerX() + r, rect.centerY() + r)
            canvas.drawArc(oval, spin.angle, 90f, false, paint)
            paint.style = Paint.Style.FILL
        }

        val textX = left + side + 3 * u
        titlePaint.color = if (active) t.palette.accent else t.palette.text
        subPaint.color = t.palette.textMuted
        if (shownSub.isEmpty()) {
            canvas.drawTextCentered(shownTitle, textX, h / 2, titlePaint)
        } else {
            canvas.drawText(shownTitle, 0, shownTitle.length, textX, h / 2 - dp(1f) - titlePaint.descent(), titlePaint)
            canvas.drawText(shownSub, 0, shownSub.length, textX, h / 2 + dp(1f) - subPaint.ascent(), subPaint)
        }
        if (expandable) {
            val g = dp(16)
            canvas.drawBitmap(iconBitmap(UiIcons.chevronRight, g, t.palette.textMuted), mainWidth - 6 * u - g / 2f, (h - g) / 2, null)
        }

        buttons.forEachIndexed { i, button ->
            if (button == RowButton.Spare) return@forEachIndexed
            val circle = geo.buttonLeft(i)
            val d = dp(t.density.iconCircle).toFloat()
            val cx = circle + d / 2
            val cy = h / 2
            val primary = button == RowButton.Play || button == RowButton.Toggle
            val squeeze = if (pressed == 1 + i) 0.88f else 1f
            paint.style = Paint.Style.FILL
            paint.color = if (primary) t.palette.accent else t.palette.surfaceRaised
            canvas.drawCircle(cx, cy, d / 2 * squeeze, paint)
            val glyph = when (button) {
                RowButton.Next -> Ui.listStart
                RowButton.Add -> Ui.listPlus
                RowButton.Play -> UiIcons.play
                else -> if (playing) UiIcons.pause else UiIcons.play
            }
            val g = (dp(16) * squeeze).toInt().coerceAtLeast(1)
            canvas.drawBitmap(iconBitmap(glyph, g, if (primary) t.palette.accentText else t.palette.text), cx - g / 2f, cy - g / 2f, null)
            val waits = when (button) {
                RowButton.Next -> pendingNext
                RowButton.Add -> pendingAdd
                RowButton.Play -> pendingPlay
                else -> false
            }
            if (waits) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2f)
                paint.color = if (primary) t.palette.accentText else t.palette.accent
                rect.set(cx - d / 2 + dp(1f), cy - d / 2 + dp(1f), cx + d / 2 - dp(1f), cy + d / 2 - dp(1f))
                canvas.drawArc(rect, spin.angle, 90f, false, paint)
                paint.style = Paint.Style.FILL
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = geometry().regionAt(event.x)
                // The body of a row that does nothing (an item that can neither open nor play) is not pressed.
                if (pressed == 0 && BrowseActions.main(expandable, playable, active) == RowMain.None) pressed = -1
                invalidate()
            }

            MotionEvent.ACTION_UP -> {
                val region = geometry().regionAt(event.x)
                if (region == pressed && region >= 0) {
                    if (region == 0) {
                        when (BrowseActions.main(expandable, playable, active)) {
                            RowMain.Open -> onOpen?.invoke()
                            RowMain.Play -> onPlay?.invoke()
                            RowMain.Toggle -> onToggle?.invoke()
                            RowMain.None -> {}
                        }
                    } else {
                        when (buttons().getOrNull(region - 1)) {
                            RowButton.Next -> if (!pendingNext) onNext?.invoke()
                            RowButton.Add -> if (!pendingAdd) onAdd?.invoke()
                            RowButton.Play -> if (!pendingPlay) onPlay?.invoke()
                            RowButton.Toggle -> onToggle?.invoke()
                            else -> {}
                        }
                    }
                }
                pressed = -1
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                pressed = -1
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> if (pressed >= 0 && Math.abs(event.y - height / 2f) > height) {
                pressed = -1
                invalidate()
            }
        }
        return true
    }

    companion object {
        /** The side of the picture, in dp. */
        const val PICTURE = 44

        /** The height of a row: the picture and the padding round it. */
        fun rowHeight(): Int = dp(PICTURE) + Math.round(3 * unitPx())
    }
}

/**
 * One row of a queue: a handle (on what comes next), the picture, title and artist, the length, and a cross (on what comes next). The track
 * playing is lit, those played are dimmed. A press on the handle starts a drag: [onDragStart], then [onDragMove] with how far the finger has
 * moved down (px), then [onDragEnd]; a press anywhere else plays the track.
 */
open class QueueRow(context: Context) : View(context) {
    // One drawn thing: its fade (a row arriving, a track played) is applied as it draws, with no layer of its own to render.
    override fun hasOverlappingRendering(): Boolean = false

    var title: String = ""
        set(value) {
            field = value
            dirty = true
            invalidate()
        }

    var artist: String? = null
        set(value) {
            field = value
            dirty = true
            invalidate()
        }

    /** The length of the track, ready to say (`3:51`), or null. */
    var length: String? = null
        set(value) {
            field = value
            dirty = true
            invalidate()
        }

    var picture: Bitmap? = null
        set(value) {
            field = value
            fill.set(value)
            invalidate()
        }

    /** The track playing now (and not over): lit. */
    var playing: Boolean = false
        set(value) {
            field = value
            dirty = true
            invalidate()
        }

    /** What comes next: it has a handle and a cross. */
    var reorderable: Boolean = false
        set(value) {
            field = value
            dirty = true
            invalidate()
        }

    /** Taken up off the list by the finger: the raised surface behind it. */
    var lifted: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var onPlay: (() -> Unit)? = null
    var onRemove: (() -> Unit)? = null
    var onDragStart: (() -> Unit)? = null
    var onDragMove: ((Float) -> Unit)? = null
    var onDragEnd: ((Float, Boolean) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val fill = PictureFill()
    private var shownTitle: CharSequence = ""
    private var shownArtist: CharSequence = ""
    private var dirty = true
    private var down = NONE
    private var startY = 0f

    /** Sets everything from a track; [dim] is the faded look of one already played. */
    fun bind(item: QueueItem, playing: Boolean, reorderable: Boolean) {
        title = item.title
        artist = item.artist
        length = item.duration?.let { MediaFormat.duration(it) }
        this.playing = playing
        this.reorderable = reorderable
    }

    private fun geometry() = QueueGeometry(width.toFloat(), dp(GRIP + GAP).toFloat() + 0f, dp(REMOVE + GAP).toFloat())

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(ROW))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        dirty = true
    }

    private fun prepare(endX: Float, textX: Float) {
        val t = theme
        titlePaint.style(t, Role.BodyStrong, t.palette.text)
        subPaint.style(t, Role.Secondary, t.palette.textMuted)
        val room = maxOf(0f, endX - (length?.let { subPaint.measureText(it) + 3 * unitPx() } ?: 0f) - textX)
        shownTitle = TextUtils.ellipsize(title, titlePaint, room, TextUtils.TruncateAt.END)
        shownArtist = artist?.let { TextUtils.ellipsize(it, subPaint, room, TextUtils.TruncateAt.END) } ?: ""
        dirty = false
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        val u = unitPx()
        val w = width.toFloat()
        val h = height.toFloat()
        val trackLeft = dp(GRIP + GAP).toFloat()
        val trackRight = w - dp(REMOVE + GAP)
        val picture = dp(PICTURE)
        val textX = trackLeft + 2 * u + picture + 3 * u
        val endX = w - dp(REMOVE + GAP) - 4 * u
        if (dirty) prepare(endX, textX)
        paint.style = Paint.Style.FILL
        if (lifted) {
            paint.color = t.palette.surfaceRaised
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, dp(t.radius.small), dp(t.radius.small), paint)
        }
        if (playing) {
            paint.color = t.palette.surfaceRaised
            rect.set(trackLeft, 0f, trackRight, h)
            canvas.drawRoundRect(rect, dp(t.radius.row), dp(t.radius.row), paint)
        }
        if (reorderable) {
            val g = dp(16)
            canvas.drawBitmap(iconBitmap(UiIcons.grip, g, t.palette.textMuted), dp(GRIP) / 2f - g / 2f, (h - g) / 2, null)
        }

        val left = trackLeft + 2 * u
        val top = (h - picture) / 2
        rect.set(left, top, left + picture, top + picture)
        fill.draw(
            canvas, rect, dp(t.radius.small), if (playing) Ui.audioLines else Ui.music,
            if (playing) t.palette.accent else t.palette.textMuted, paint,
        )

        titlePaint.color = if (playing) t.palette.accent else t.palette.text
        subPaint.color = t.palette.textMuted
        length?.let {
            subPaint.textAlign = Paint.Align.RIGHT
            canvas.drawTextCentered(it, endX, h / 2, subPaint, Paint.Align.RIGHT)
            subPaint.textAlign = Paint.Align.LEFT
        }
        if (shownArtist.isEmpty()) {
            canvas.drawTextCentered(shownTitle, textX, h / 2, titlePaint)
        } else {
            canvas.drawText(shownTitle, 0, shownTitle.length, textX, h / 2 - dp(1f) - titlePaint.descent(), titlePaint)
            canvas.drawText(shownArtist, 0, shownArtist.length, textX, h / 2 + dp(1f) - subPaint.ascent(), subPaint)
        }
        if (reorderable) {
            val g = dp(16)
            canvas.drawBitmap(iconBitmap(UiIcons.close, g, t.palette.textMuted), w - dp(REMOVE) / 2f - g / 2f, (h - g) / 2, null)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val geo = geometry()
                down = when {
                    reorderable && geo.onGrip(event.x) -> GRIP_DOWN
                    reorderable && geo.onRemove(event.x) -> REMOVE_DOWN
                    else -> PLAY_DOWN
                }
                if (down == GRIP_DOWN) {
                    startY = event.rawY
                    parent?.requestDisallowInterceptTouchEvent(true)
                    onDragStart?.invoke()
                }
            }

            MotionEvent.ACTION_MOVE -> if (down == GRIP_DOWN) onDragMove?.invoke(event.rawY - startY)
            MotionEvent.ACTION_UP -> {
                when (down) {
                    GRIP_DOWN -> onDragEnd?.invoke(event.rawY - startY, false)
                    REMOVE_DOWN -> if (geometry().onRemove(event.x)) onRemove?.invoke()
                    PLAY_DOWN -> if (Math.abs(event.y - height / 2f) < height) onPlay?.invoke()
                }
                down = NONE
            }

            MotionEvent.ACTION_CANCEL -> {
                if (down == GRIP_DOWN) onDragEnd?.invoke(event.rawY - startY, true)
                down = NONE
            }
        }
        return true
    }

    companion object {
        /** The height of a row: a touch target with room for its picture. */
        const val ROW = 56

        /** The room for the handle, so every row lines up. */
        const val GRIP = 20

        /** The size of the cross's box. */
        const val REMOVE = 28

        /** The gap between the parts of a row. */
        const val GAP = 4

        /** The side of the picture. */
        const val PICTURE = 40

        private const val NONE = 0
        private const val GRIP_DOWN = 1
        private const val REMOVE_DOWN = 2
        private const val PLAY_DOWN = 3
    }
}

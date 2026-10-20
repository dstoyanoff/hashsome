package dev.hashsome.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.text.Editable
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import dev.hashsome.client.AssetSize
import dev.hashsome.client.Availability
import dev.hashsome.client.Cancel
import dev.hashsome.client.Images
import dev.hashsome.client.ServerAddress
import dev.hashsome.headless.HandleState
import dev.hashsome.headless.Scope
import dev.hashsome.ui.icons.Ui

// The small parts the media components are made of, beyond the base views of `Views.kt`: buttons of other shapes than the
// 44 dp circle, the artwork ring that seeks, labels and pills, the chips with a way back, the search field, and the
// plumbing every media panel shares. Colors, radii and sizes come from the theme when they are drawn.

/** The words a player says in place of its title while it is not usable (`statusLabels` on the web). Null while it is. */
internal fun mediaStatusLabel(state: HandleState<*>): String? = when (state) {
    HandleState.Loading -> "…"
    HandleState.Missing -> "Not found"
    HandleState.Unsupported -> "Unsupported"
    is HandleState.Unavailable -> if (state.entity?.availability == Availability.Unknown) "Unknown" else "Unavailable"
    is HandleState.Ready -> null
}

/** Artwork from the runtime's asset endpoint at a size, through the shared cache. */
internal object Artwork {
    /** Loads [url] at [size] decoded to about [px]; [done] gets null for no picture. The returned cancel stops [done]. */
    fun load(url: String?, size: AssetSize, px: Int, done: (Bitmap?) -> Unit): Cancel {
        if (url.isNullOrEmpty()) {
            done(null)
            return {}
        }
        val server = Hashsome.settings.server.value
        val resolved = if (server == null) url else Images.url(ServerAddress(baseOf(server)), url, size)
        return Images.load(resolved, px, done)
    }

    /** The address with its scheme: a display provisioned with `host:port` still gets its pictures. */
    fun baseOf(server: String): String = if (server.contains("://")) server else "http://$server"
}

/** A turning arc: how something that waits shows it. Stopped when detached, so no animator outlives its view. */
internal class Spin(private val view: View) {
    var angle = 0f
        private set
    private var animator: ValueAnimator? = null

    fun run(on: Boolean) {
        // Where nothing moves the arc holds still.
        if (!on || !Animation.animates) {
            animator?.cancel()
            animator = null
            return
        }
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                angle = it.animatedValue as Float
                view.invalidate()
            }
            start()
        }
    }
}

private fun Canvas.drawArcAround(rect: RectF, angle: Float, paint: Paint) = drawArc(rect, angle, 90f, false, paint)

/**
 * A button that is a [RoundButton]'s cousin for the media components: the same states (primary, active, pending, failed), but any
 * shape, a circle when it is as wide as it is tall and a stadium otherwise, and any glyph size. [plain] draws no background at all
 * (the shuffle beside a title, the mute at the left of a volume bar); a plain button that waits shows a spinner in place of its glyph.
 */
internal open class GlyphButton(context: Context, icon: Icon, private val glyphDp: Float? = null, private val plain: Boolean = false) :
    View(context) {
    // One drawn thing: a fade of it needs no layer of its own to render.
    override fun hasOverlappingRendering(): Boolean = false

    var icon: Icon = icon
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var onClick: (() -> Unit)? = null

    /** Lit: in the accent color when plain, in the warm one on a circle. */
    var active: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var primary: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var pending: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            spin.run(value && isAttachedToWindow)
            invalidate()
        }

    var failed: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** Sets pending and failed from what the press tracker says. */
    fun feedback(state: dev.hashsome.headless.Feedback?) {
        pending = state == dev.hashsome.headless.Feedback.Pending
        failed = state == dev.hashsome.headless.Feedback.Error
    }

    /** The glyph's color when it is not one of the states'. */
    var tint: Int? = null
        set(value) {
            field = value
            invalidate()
        }

    /** The size in dp when the layout does not fix it; the density's icon circle when null. */
    var sizeDp: Int? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val spin = Spin(this)

    init {
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val own = dp(sizeDp ?: theme.density.iconCircle)
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec) else own
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else own
        setMeasuredDimension(w, h)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        spin.run(pending)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        spin.run(false)
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        if (!plain) {
            paint.color = if (primary) t.palette.accent else t.palette.surfaceRaised
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        }
        val color = tint ?: when {
            failed -> t.palette.danger
            active -> if (plain) t.palette.accent else t.palette.warm
            primary -> t.palette.accentText
            plain -> t.palette.textMuted
            else -> t.palette.text
        }
        val glyph = Math.round(dp(glyphDp ?: (t.density.iconSize * 0.75f)))
        val left = (w - glyph) / 2
        val top = (h - glyph) / 2
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        if (pending && plain) {
            paint.color = color
            rect.set(left + dp(1f), top + dp(1f), left + glyph - dp(1f), top + glyph - dp(1f))
            canvas.drawArcAround(rect, spin.angle, paint)
            return
        }
        canvas.drawIcon(iconBitmap(icon, glyph, color), left, top)
        if (pending) {
            paint.color = if (primary) t.palette.accentText else t.palette.accent
            rect.set(dp(1f), dp(1f), w - dp(1f), h - dp(1f))
            // A stadium's ring is a circle at its middle: the arc runs round the shorter side.
            val side = minOf(w, h)
            rect.set((w - side) / 2 + dp(1f), dp(1f), (w + side) / 2 - dp(1f), h - dp(1f))
            canvas.drawArcAround(rect, spin.angle, paint)
        }
    }

    private fun squeeze(to: Float) {
        if (!Animation.animates) return
        animate().scaleX(to).scaleY(to).setDuration(120).start()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (isEnabled) squeeze(0.88f)
            MotionEvent.ACTION_UP -> {
                squeeze(1f)
                if (isEnabled && contains(event.x, event.y)) onClick?.invoke()
            }

            MotionEvent.ACTION_CANCEL -> squeeze(1f)
        }
        return true
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.5f
    }
}

/**
 * The artwork in its ring, which shows how far along playback is and, when the track has a length, seeks: drag round the ring (not on the
 * picture inside it). Its height is what its parent gives it, [basisDp] when the parent asks what it would like; it is no wider than [maxPx].
 * A tap on the picture calls [onOpen] when set.
 */
internal class ArtworkRingView(context: Context) : RingView(context) {
    /** The ring's diameter when nothing says otherwise, in dp. */
    var basisDp = 168

    /** The most the ring grows to. */
    var maxPx = Int.MAX_VALUE

    /** The length of the track in seconds when it can be sought in; 0 for none. */
    var length = 0.0

    /** Where the finger is, in seconds. */
    var onPreview: ((Double) -> Unit)? = null

    /** Where the finger lifted, in seconds. */
    var onCommit: ((Double) -> Unit)? = null

    /** The touch was taken away: give the position back. */
    var onCancel: (() -> Unit)? = null
    var onOpen: (() -> Unit)? = null

    private var seeking: RingSeek? = null
    private var hasPicture = false
    private var pressedPicture = false

    /** The track's time, as "1:40 / 2:33", drawn in a pill on the lower part of the picture (nothing when empty); it gives way to the position under the finger while the ring is dragged. */
    var timeText: CharSequence = ""
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Where the finger is while the ring is being dragged, in seconds; null otherwise. It is shown on the picture. */
    private var previewSeconds: Double? = null
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleText = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleRect = RectF()

    fun show(picture: Bitmap?) {
        hasPicture = picture != null
        setArtwork(picture)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val basis = dp(basisDp)
        val avail = MeasureSpec.getSize(widthMeasureSpec)
        val w = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> minOf(basis, maxPx)
            else -> minOf(avail, maxPx)
        }
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(basis, MeasureSpec.getSize(heightMeasureSpec))
            else -> basis
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasPicture) {
            val s = minOf(width, height)
            val px = (s * 0.24f).toInt().coerceAtLeast(1)
            canvas.drawIcon(iconBitmap(Ui.music, px, theme.palette.textMuted), (width - px) / 2f, (height - px) / 2f)
        }
        previewSeconds?.let { drawPosition(canvas, it) }
        if (previewSeconds == null && timeText.isNotEmpty()) drawTime(canvas)
    }

    /** The time in a small dark pill low on the picture, sized with the ring. */
    private fun drawTime(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        bubbleText.textSize = (s * 0.075f).coerceIn(dp(11f), dp(15f))
        bubbleText.typeface = theme.typefaces.forWeight(500)
        bubbleText.color = 0xFFFFFFFF.toInt()
        bubbleText.textAlign = Paint.Align.CENTER
        val w = bubbleText.measureText(timeText, 0, timeText.length) + bubbleText.textSize * 1.4f
        val h = bubbleText.textSize * 2f
        val cy = height / 2f + s * 0.24f
        bubble.color = 0x8C000000.toInt()
        bubbleRect.set(width / 2f - w / 2f, cy - h / 2f, width / 2f + w / 2f, cy + h / 2f)
        canvas.drawRoundRect(bubbleRect, h / 2f, h / 2f, bubble)
        val fm = bubbleText.fontMetrics
        canvas.drawText(timeText, 0, timeText.length, width / 2f, cy - (fm.ascent + fm.descent) / 2f, bubbleText)
    }

    /** The position under the finger, in a dark pill in the middle of the picture, so it can be read where the thumb is not. */
    private fun drawPosition(canvas: Canvas, seconds: Double) {
        val s = minOf(width, height).toFloat()
        bubbleText.textSize = s * 0.12f
        bubbleText.typeface = theme.typefaces.forWeight(600)
        bubbleText.color = 0xFFFFFFFF.toInt()
        bubbleText.textAlign = Paint.Align.CENTER
        val text = MediaFormat.duration(seconds)
        val w = bubbleText.measureText(text) + s * 0.1f
        val h = s * 0.2f
        bubble.color = 0xB3000000.toInt()
        bubbleRect.set(width / 2f - w / 2f, height / 2f - h / 2f, width / 2f + w / 2f, height / 2f + h / 2f)
        canvas.drawRoundRect(bubbleRect, h / 2f, h / 2f, bubble)
        val fm = bubbleText.fontMetrics
        canvas.drawText(text, width / 2f, height / 2f - (fm.ascent + fm.descent) / 2f, bubbleText)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val size = minOf(width, height).toDouble()
        val dx = (event.x - width / 2f).toDouble()
        val dy = (event.y - height / 2f).toDouble()
        val turn = RingSeek.turnAt(dx, dy)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (length > 0.0 && RingSeek.onRing(Math.hypot(dx, dy), size) && Math.hypot(dx, dy) <= size / 2 + dp(8f)) {
                    seeking = RingSeek(length).also {
                        val at = it.start(turn)
                        previewSeconds = at
                        onPreview?.invoke(at)
                    }
                    invalidate()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                if (onOpen != null && Math.hypot(dx, dy) < size * 0.36) {
                    pressedPicture = true
                    return true
                }
                return false
            }

            MotionEvent.ACTION_MOVE -> seeking?.let {
                val at = it.follow(turn)
                previewSeconds = at
                invalidate()
                onPreview?.invoke(at)
            }

            MotionEvent.ACTION_UP -> {
                seeking?.let { onCommit?.invoke(it.follow(turn)) }
                seeking = null
                previewSeconds = null
                invalidate()
                if (pressedPicture && contains(event.x, event.y)) onOpen?.invoke()
                pressedPicture = false
            }

            MotionEvent.ACTION_CANCEL -> {
                if (seeking != null) onCancel?.invoke()
                seeking = null
                previewSeconds = null
                invalidate()
                pressedPicture = false
            }
        }
        return true
    }
}

/** The small ring of a bar: the track's progress as an arc round a picture in a circle. A tap calls [onClick] when set. */
internal class MiniRingView(context: Context) : View(context) {
    var fraction: Float? = null
        set(value) {
            field = value
            invalidate()
        }

    var onClick: (() -> Unit)? = null
        set(value) {
            field = value
            isClickable = value != null
        }

    private var picture: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()
    private val source = Rect()
    private val fade = Eased(this, 1f, 200)

    fun show(bitmap: Bitmap?) {
        picture = bitmap
        fade.to(0f, false)
        fade.to(1f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(theme.density.iconCircle)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        val c = width / 2f
        val stroke = width * 0.04f
        val ringR = c - stroke / 2
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = t.palette.border
        canvas.drawCircle(c, c, ringR, paint)
        fraction?.let { f ->
            if (f > 0f) {
                paint.color = t.palette.accent
                paint.strokeCap = Paint.Cap.ROUND
                rect.set(c - ringR, c - ringR, c + ringR, c + ringR)
                canvas.drawArc(rect, -90f, 360f * f.coerceAtMost(1f), false, paint)
                paint.strokeCap = Paint.Cap.BUTT
            }
        }
        val r = dp(15f)
        paint.style = Paint.Style.FILL
        paint.color = t.palette.surfaceRaised
        canvas.drawCircle(c, c, r, paint)
        val b = picture
        if (b != null) {
            canvas.save()
            clip.reset()
            clip.addCircle(c, c, r, Path.Direction.CW)
            canvas.clipPath(clip)
            val side = minOf(b.width, b.height)
            source.set((b.width - side) / 2, (b.height - side) / 2, (b.width + side) / 2, (b.height + side) / 2)
            rect.set(c - r, c - r, c + r, c + r)
            paint.alpha = (fade.value * 255).toInt()
            canvas.drawBitmap(b, source, rect, paint)
            paint.alpha = 255
            canvas.restore()
        } else {
            val px = dp(14)
            canvas.drawIcon(iconBitmap(Ui.music, px, t.palette.textMuted), c - px / 2f, c - px / 2f)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onClick == null) return false
        if (event.actionMasked == MotionEvent.ACTION_UP && contains(event.x, event.y)) onClick?.invoke()
        return true
    }
}

/** The color a [LabelView] draws in, by name, so it follows the theme. */
internal enum class Tone { Text, Muted, Accent, Danger }

/** One line of text in a role, aligned within the width it is given and ended with an ellipsis when it does not fit. */
internal class LabelView(context: Context, role: Role = Role.Body, tone: Tone = Tone.Text) : View(context) {
    // One drawn thing: a fade of it needs no layer of its own to render.
    override fun hasOverlappingRendering(): Boolean = false

    var text: CharSequence = ""
        set(value) {
            if (field == value) return
            field = value
            dirty = true
            requestLayout()
            invalidate()
        }

    var role: Role = role
        set(value) {
            field = value
            dirty = true
            requestLayout()
            invalidate()
        }

    var tone: Tone = tone
        set(value) {
            field = value
            invalidate()
        }

    var align: Paint.Align = Paint.Align.LEFT

    /** The least width, in dp. */
    var minWidthDp = 0

    /** A spinner turns before the words (something is loading). */
    var spinner: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            spin.run(value && isAttachedToWindow)
            dirty = true
            requestLayout()
            invalidate()
        }

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val spin = Spin(this)
    private val arc = RectF()
    private var shown: CharSequence = ""
    private var dirty = true

    private fun lead(): Int = if (spinner) dp(12) + Math.round(1.5f * unitPx()) else 0

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        spin.run(spinner)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        spin.run(false)
    }

    private fun colorOf(): Int = theme.palette.let {
        when (tone) {
            Tone.Text -> it.text
            Tone.Muted -> it.textMuted
            Tone.Accent -> it.accent
            Tone.Danger -> it.danger
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        paint.style(theme, role, 0)
        val wide = Math.ceil(paint.measureText(text, 0, text.length).toDouble()).toInt() + lead()
        val own = maxOf(wide, dp(minWidthDp))
        val w = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(own, MeasureSpec.getSize(widthMeasureSpec))
            else -> own
        }
        val line = Math.ceil((paint.textSize * (theme.style(role).lineHeight ?: 1.3f)).toDouble()).toInt()
        setMeasuredDimension(w, if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else line)
        dirty = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        dirty = true
    }

    override fun onDraw(canvas: Canvas) {
        paint.style(theme, role, colorOf())
        if (dirty) {
            shown = TextUtils.ellipsize(text, paint, (width - lead()).toFloat(), TextUtils.TruncateAt.END)
            dirty = false
        }
        val lead = lead()
        val x = when (align) {
            Paint.Align.LEFT -> 0f
            Paint.Align.CENTER -> width / 2f
            Paint.Align.RIGHT -> width.toFloat()
        }
        if (spinner) {
            val textW = paint.measureText(shown, 0, shown.length)
            val start = when (align) {
                Paint.Align.LEFT -> 0f
                Paint.Align.CENTER -> (width - lead - textW) / 2
                Paint.Align.RIGHT -> width - lead - textW
            }
            val r = dp(6f)
            val cy = height / 2f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.5f)
            arc.set(start + dp(1f), cy - r + dp(1f), start + 2 * r - dp(1f), cy + r - dp(1f))
            canvas.drawArc(arc, spin.angle, 90f, false, paint)
            paint.style = Paint.Style.FILL
            canvas.drawTextCentered(shown, start + lead, cy, paint)
            return
        }
        canvas.drawTextCentered(shown, x, height / 2f, paint, align)
    }
}

/**
 * A title with a button beside it (the shuffle), and optionally the artist after a dot on the same line. The title gives way (it ends in
 * an ellipsis, or rolls) before the button does, and when there is an artist the two share what room is left. [centered] centers what
 * is there in the width, as a player card does.
 */
internal class TitleRow(context: Context) : ViewGroup(context) {
    val title = MarqueeText(context)
    val dot = LabelView(context, Role.Secondary, Tone.Muted).also { it.text = "·" }
    val artist = MarqueeText(context)
    var action: View? = null
        set(value) {
            field?.let { removeView(it) }
            field = value
            value?.let { addView(it) }
        }
    var centered = false
    var showArtist = false
        set(value) {
            field = value
            dot.visibility = if (value) VISIBLE else GONE
            artist.visibility = if (value) VISIBLE else GONE
            requestLayout()
        }

    private var used = 0
    private var actionGap = 0

    init {
        addView(title)
        addView(dot)
        addView(artist)
        showArtist = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val u = unitPx()
        val gap = Math.round(1.5f * u)
        val avail = MeasureSpec.getSize(widthMeasureSpec)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val button = action?.takeIf { it.visibility != GONE }
        button?.measure(unspecified, unspecified)
        val buttonGap = Math.round(2 * u)
        val buttonW = if (button == null) 0 else button.measuredWidth + buttonGap
        title.measure(unspecified, unspecified)
        var titleW = title.measuredWidth
        var artistW = 0
        var dotW = 0
        if (showArtist) {
            artist.measure(unspecified, unspecified)
            dot.measure(unspecified, unspecified)
            artistW = artist.measuredWidth
            dotW = dot.measuredWidth + 2 * gap
        }
        actionGap = buttonGap
        val room = maxOf(0, avail - buttonW - dotW)
        if (titleW + artistW > room) {
            if (artistW == 0) {
                val fitted = TitleFit.width(titleW, room)
                // A title let through past its room takes the overflow out of the gap before the button.
                if (button != null) actionGap = maxOf(0, buttonGap - maxOf(0, fitted - room))
                titleW = fitted
            } else {
                // They give way in proportion to what each would like.
                val scale = room.toFloat() / (titleW + artistW)
                titleW = (titleW * scale).toInt()
                artistW = room - titleW
            }
        }
        title.measure(MeasureSpec.makeMeasureSpec(titleW, MeasureSpec.EXACTLY), unspecified)
        if (showArtist) artist.measure(MeasureSpec.makeMeasureSpec(artistW, MeasureSpec.EXACTLY), unspecified)
        used = titleW + (if (button == null) 0 else button.measuredWidth + actionGap) + if (showArtist) dotW + artistW else 0
        var h = title.measuredHeight
        if (button != null) h = maxOf(h, button.measuredHeight)
        if (showArtist) h = maxOf(h, artist.measuredHeight)
        setMeasuredDimension(if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) avail else used, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val u = unitPx()
        val gap = Math.round(1.5f * u)
        val h = b - t
        var x = if (centered) maxOf(0, ((r - l) - used) / 2) else 0
        fun put(v: View) {
            val top = (h - v.measuredHeight) / 2
            v.layout(x, top, x + v.measuredWidth, top + v.measuredHeight)
            x += v.measuredWidth
        }
        put(title)
        action?.takeIf { it.visibility != GONE }?.let {
            x += actionGap
            put(it)
        }
        if (showArtist) {
            x += gap
            put(dot)
            x += gap
            put(artist)
        }
    }
}

/** A labelled pill (an icon and words) for the speakers and the actions of what is open in a library; the sizes are the web's, set per use. */
class PillButton(context: Context, icon: Icon?, label: String?, private val role: Role = Role.Label) : View(context) {
    // One drawn thing: a fade of it needs no layer of its own to render.
    override fun hasOverlappingRendering(): Boolean = false

    var icon: Icon? = icon
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var label: String? = label
        set(value) {
            field = value
            dirty = true
            requestLayout()
            invalidate()
        }

    var heightDp = 32
    var padUnits = 3.5f
    var gapUnits = 1.5f
    var minWidthDp = 0
    var primary = false
        set(value) {
            field = value
            invalidate()
        }

    /** No fill at rest, as the web's text-only `.clear`: the raised surface shows only while it is pressed (its `:hover`). */
    var flat = false

    private var held = false

    /** The words in the muted color. */
    var muted = false
        set(value) {
            field = value
            invalidate()
        }

    /** The glyph in the accent color. */
    var accentIcon = false
        set(value) {
            field = value
            invalidate()
        }

    /** A small accent dot after the glyph: something to look at. */
    var dot = false
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var pending = false
        set(value) {
            if (field == value) return
            field = value
            spin.run(value && isAttachedToWindow)
            invalidate()
        }

    var failed = false
        set(value) {
            field = value
            invalidate()
        }

    /** The danger fill with light words: a pill waiting for its confirming tap. Changes no size. */
    var armed = false
        set(value) {
            field = value
            invalidate()
        }

    /** Other labels this pill will show: it is as wide as the widest of them and its own, so changing the label never moves its neighbours. */
    var alsoFits: List<String> = emptyList()
        set(value) {
            field = value
            requestLayout()
        }

    var onClick: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val spin = Spin(this)
    private var shown: CharSequence = ""
    private var dirty = true

    init {
        isClickable = true
    }

    private fun glyphPx() = dp(14)

    private fun wanted(): Float {
        val t = theme
        val words = label.orEmpty()
        textPaint.style(t, role, 0)
        val pad = padUnits * unitPx()
        val gap = gapUnits * unitPx()
        var w = 2 * pad
        if (icon != null) w += glyphPx()
        if (dot) w += gap + dp(7f)
        val widest = (alsoFits + words).maxOf { textPaint.measureText(it) }
        if (words.isNotEmpty() || alsoFits.isNotEmpty()) w += (if (icon != null) gap else 0f) + widest
        if (words.isEmpty() && alsoFits.isEmpty()) w = maxOf(w - 2 * pad, 0f) + 2 * pad
        return w
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val own = maxOf(Math.ceil(wanted().toDouble()).toInt(), dp(minWidthDp))
        val w = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(own, MeasureSpec.getSize(widthMeasureSpec))
            else -> own
        }
        setMeasuredDimension(w, dp(heightDp))
        dirty = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        spin.run(pending)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        spin.run(false)
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = if (armed) t.palette.danger else if (primary) t.palette.accent else t.palette.surfaceRaised
        rect.set(0f, 0f, w, h)
        if (!flat || held || armed) canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        val words = label.orEmpty()
        val ink = when {
            armed -> t.palette.accentText
            primary -> t.palette.accentText
            failed -> t.palette.danger
            muted -> t.palette.textMuted
            else -> t.palette.text
        }
        textPaint.style(t, role, ink)
        val pad = padUnits * unitPx()
        val gap = gapUnits * unitPx()
        val glyph = glyphPx()
        val iconTotal = (if (icon != null) glyph else 0) + if (dot) gap + dp(7f) else 0f
        val textRoom = w - 2 * pad - iconTotal - if (icon != null && words.isNotEmpty()) gap else 0f
        if (dirty) {
            shown = TextUtils.ellipsize(words, textPaint, maxOf(0f, textRoom), TextUtils.TruncateAt.END)
            dirty = false
        }
        val textW = if (shown.isEmpty()) 0f else textPaint.measureText(shown, 0, shown.length)
        val content = iconTotal + (if (icon != null && textW > 0) gap else 0f) + textW
        var x = (w - content) / 2
        if (icon != null) {
            val top = (h - glyph) / 2
            val tone = if (accentIcon) t.palette.accent else if (primary) t.palette.accentText else if (failed) t.palette.danger else t.palette.textMuted
            if (pending) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2f)
                paint.color = ink
                rect.set(x + dp(1f), top + dp(1f), x + glyph - dp(1f), top + glyph - dp(1f))
                canvas.drawArc(rect, spin.angle, 90f, false, paint)
                paint.style = Paint.Style.FILL
            } else {
                canvas.drawIcon(iconBitmap(icon!!, glyph, if (words.isEmpty() || accentIcon) tone else ink.let { if (muted) tone else it }), x, top)
            }
            x += glyph
            if (dot) {
                paint.color = t.palette.accent
                canvas.drawCircle(x + gap + dp(3.5f), h / 2, dp(3.5f), paint)
                x += gap + dp(7f)
            }
            if (textW > 0) x += gap
        }
        if (textW > 0) canvas.drawTextCentered(shown, x, h / 2, textPaint)
    }

    private fun squeeze(to: Float) {
        if (!Animation.animates) return
        animate().scaleX(to).scaleY(to).setDuration(120).start()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (isEnabled) {
                squeeze(0.94f)
                if (flat) { held = true; invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                squeeze(1f)
                if (held) { held = false; invalidate() }
                if (isEnabled && contains(event.x, event.y)) onClick?.invoke()
            }

            MotionEvent.ACTION_CANCEL -> {
                squeeze(1f)
                if (held) { held = false; invalidate() }
            }
        }
        return true
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.5f
    }
}

/**
 * The library's shelves (and, while searching, the kinds to search for) as a row of chips with a pill under the chosen one that glides to
 * the next. A chip can carry a glyph before its words: the chosen shelf of an open album is the way back, with an arrow. Wider than its
 * room, it scrolls sideways and keeps the chosen chip in view.
 */
internal class ChipRowView(context: Context) : View(context) {
    class Chip(val label: String, val icon: Icon? = null)

    var chips: List<Chip> = emptyList()
        set(value) {
            if (field.size == value.size && field.indices.all { field[it].label == value[it].label && field[it].icon == value[it].icon }) return
            field = value
            measureChips()
            val at = current.coerceIn(0, maxOf(0, lefts.size - 1))
            val first = lefts.getOrNull(at) ?: 0f
            left.to(first, false)
            right.to(first + (widths.getOrNull(at) ?: 0f), false)
            requestLayout()
            invalidate()
        }

    var current: Int = 0
        set(value) {
            if (field == value) return
            field = value
            glideTo(value)
        }

    var onSelect: ((Int) -> Unit)? = null

    private val widths = ArrayList<Float>()
    private val lefts = ArrayList<Float>()
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val left = Eased(this, 0f, 250)
    private val right = Eased(this, 0f, 250)
    private var offset = 0f
    private var downX = 0f
    private var startOffset = 0f
    private var dragging = false

    private fun glyphPx() = dp(14)

    private fun measureChips() {
        widths.clear()
        lefts.clear()
        text.style(theme, Role.Body, 0)
        var x = 0f
        chips.forEach {
            val w = text.measureText(it.label) + dp(24f) + if (it.icon != null) glyphPx() + dp(6f) else 0f
            lefts.add(x)
            widths.add(w)
            x += w + dp(8f)
        }
    }

    private fun contentWidth(): Float = (lefts.lastOrNull() ?: 0f) + (widths.lastOrNull() ?: 0f)

    private fun glideTo(i: Int) {
        if (i !in lefts.indices) return
        left.to(lefts[i])
        right.to(lefts[i] + widths[i])
        reveal(i)
    }

    /** Scrolls the row so chip [i] is in view. */
    private fun reveal(i: Int) {
        if (width <= 0 || i !in lefts.indices) return
        val gapEdge = dp(8f)
        if (lefts[i] - offset < 0f) offset = lefts[i]
        if (lefts[i] + widths[i] - offset > width) offset = lefts[i] + widths[i] - width + gapEdge
        offset = offset.coerceIn(0f, maxOf(0f, contentWidth() - width))
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (lefts.size != chips.size) measureChips()
        val wanted = Math.ceil(contentWidth().toDouble()).toInt()
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else dp(CHIP_HEIGHT)
        val w = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(wanted, MeasureSpec.getSize(widthMeasureSpec))
            else -> wanted
        }
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        reveal(current)
    }

    override fun onDraw(canvas: Canvas) {
        if (chips.isEmpty()) return
        val t = theme
        canvas.save()
        canvas.translate(-offset, 0f)
        rect.set(left.value, 0f, right.value, height.toFloat())
        paint.style = Paint.Style.FILL
        paint.color = t.palette.accent
        canvas.drawRoundRect(rect, height / 2f, height / 2f, paint)
        text.style(t, Role.Body, t.palette.text)
        chips.forEachIndexed { i, chip ->
            val ink = if (i == current) t.palette.accentText else t.palette.text
            text.color = ink
            var x = lefts[i] + dp(12f)
            chip.icon?.let {
                val g = glyphPx()
                canvas.drawIcon(iconBitmap(it, g, ink), x, (height - g) / 2f)
                x += g + dp(6f)
            }
            canvas.drawTextCentered(chip.label, x, height / 2f, text)
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                startOffset = offset
                dragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging && Math.abs(event.x - downX) > dp(8f) && contentWidth() > width) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    offset = (startOffset - (event.x - downX)).coerceIn(0f, maxOf(0f, contentWidth() - width))
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> if (!dragging) {
                val x = event.x + offset
                // The chip a press is on, or the nearest to its side (the gaps between them belong to the one before).
                val i = lefts.indices.firstOrNull { x >= lefts[it] && x <= lefts[it] + widths[it] + dp(4f) }
                if (i != null) {
                    // Pressing the chosen one is a choice too: when it is the way back, that is what it does.
                    if (i != current) current = i
                    onSelect?.invoke(i)
                }
            }
        }
        return true
    }

    companion object {
        /** The height of every chip, whatever its label holds (the web's `CHIP_HEIGHT`). */
        const val CHIP_HEIGHT = 28
    }
}

/** The search field of a library: a pill in the raised surface with the search glyph, the text, and a cross that clears it once there is some. */
internal class SearchBar(context: Context) : FrameLayout(context) {
    val input = EditText(context)

    /** The text changed by someone typing (not by [setText]). */
    var onChange: ((String) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clear = GlyphButton(context, Ui.x, 16f, plain = true)
    private val closeButton = GlyphButton(context, Ui.x, 16f, plain = true)
    private var silent = false

    /** Called by the close button, shown when the field is [closable]. */
    var onClose: (() -> Unit)? = null

    /** Whether the field has a close button after its clear button (a search that opens from an icon, and closes back to it). */
    var closable: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            closeButton.visibility = if (value) VISIBLE else GONE
            val u = unitPx()
            val room = if (value) dp(28) else 0
            (input.layoutParams as? LayoutParams)?.rightMargin = Math.round(3 * u) + dp(24) + room
            (clear.layoutParams as? LayoutParams)?.rightMargin = Math.round(1.5f * u) + room
            requestLayout()
        }

    /** Puts the cursor in the field (the field is only focusable once pressed, so this is for opening it from an icon). */
    fun focusInput() {
        input.isFocusable = true
        input.isFocusableInTouchMode = true
        input.requestFocus()
        (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.showSoftInput(input, 0)
    }

    init {
        setWillNotDraw(false)
        val t = theme
        input.apply {
            hint = "Search"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = null
            // It takes focus (and its blinking cursor, which draws on) only when it is pressed, not as the panel opens.
            isFocusableInTouchMode = false
            isFocusable = false
            setOnTouchListener { v, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN && !v.isFocusable) {
                    v.isFocusable = true
                    v.isFocusableInTouchMode = true
                    v.requestFocus()
                }
                false
            }
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx(t.style(Role.Body).size.toFloat()))
            typeface = t.typefaces.forWeight(t.style(Role.Body).weight)
            setTextColor(t.palette.text)
            setHintTextColor(t.palette.textMuted)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    clear.visibility = if (s.isNullOrEmpty()) GONE else VISIBLE
                    if (!silent) onChange?.invoke(s?.toString().orEmpty())
                }

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        clear.visibility = GONE
        clear.onClick = { input.setText("") }
        closeButton.visibility = GONE
        closeButton.onClick = { onClose?.invoke() }
        val u = unitPx()
        addView(input, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER_VERTICAL).apply {
            leftMargin = Math.round(3 * u) + dp(16) + Math.round(2 * u)
            rightMargin = Math.round(3 * u) + dp(24)
        })
        addView(clear, LayoutParams(dp(28), dp(28), android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL).apply {
            rightMargin = Math.round(1.5f * u)
        })
        addView(closeButton, LayoutParams(dp(28), dp(28), android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL).apply {
            rightMargin = Math.round(1.5f * u)
        })
    }

    /** Puts [text] in the field without telling [onChange] (the library cleared it). */
    fun setText(text: String) {
        if (input.text.toString() == text) return
        silent = true
        input.setText(text)
        silent = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(HEIGHT), MeasureSpec.EXACTLY))
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        val h = height.toFloat()
        paint.color = t.palette.surfaceRaised
        rect.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        val g = dp(16)
        canvas.drawIcon(iconBitmap(Ui.search, g, t.palette.textMuted), 3 * unitPx(), (h - g) / 2)
    }

    companion object {
        /** The height of the field (the web's `SEARCH_HEIGHT`). */
        const val HEIGHT = 36
    }
}

/**
 * The list of a library: a scroll view with no bars, and a swipe to the right across it (clearly sideways) goes back out of what is open,
 * as in a native app. The tap that ends a swipe is not passed on to the row under the finger.
 */
internal class LibraryScroll(context: Context) : ScrollView(context) {
    /** Called on a swipe back; null while there is nothing to go back out of. */
    var onSwipeBack: (() -> Unit)? = null

    /** Called as the end of the content comes within a screenful and a bit of view (more rows are wanted). */
    var onNearEnd: (() -> Unit)? = null

    private var downX = 0f
    private var downY = 0f

    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        checkNearEnd()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // A list that does not fill the view (it is taller than its first rows) asks for more without being scrolled.
        checkNearEnd()
    }

    private fun checkNearEnd() {
        val content = getChildAt(0) ?: return
        if (ScrollEnd.near(content.height, scrollY, height, dp(200))) onNearEnd?.invoke()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
            }

            MotionEvent.ACTION_UP -> if (onSwipeBack != null) {
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (SwipeBack.counts(dx / screenDensity, dy / screenDensity)) {
                    ev.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(ev)
                    onSwipeBack?.invoke()
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}

/** Whether the end of a list is within [slack] px of being on screen. */
internal object ScrollEnd {
    fun near(contentHeight: Int, scrollY: Int, viewHeight: Int, slack: Int): Boolean = contentHeight - scrollY - viewHeight < slack
}

/** Whether a move across a list is a swipe back (`SWIPE_BACK` in `media-browser.tsx`): far enough right, and clearly more across than up or down. */
internal object SwipeBack {
    const val DISTANCE_DP = 72f

    fun counts(dxDp: Float, dyDp: Float): Boolean = dxDp > DISTANCE_DP && dxDp > 2 * Math.abs(dyDp)
}

/** Shows [build]'s view in a panel over the page: a drawer at the side on a wide screen, a sheet from the bottom on a small one. */
internal fun openMediaOverlay(
    context: Context,
    owner: Scope,
    title: String,
    subtitle: String?,
    icon: Icon,
    full: Boolean,
    expandable: Boolean = false,
    prebuilt: Prebuilt? = null,
    build: (Scope) -> View,
) {
    val overlay = overlayForScreen(context, expandable)
    overlay.title = title
    overlay.subtitle = subtitle
    overlay.titleIcon = icon
    // The library and the queue are panels of their own, full size; the speakers are the side panel, which can be made so.
    if (full) {
        (overlay as? Drawer)?.expanded = true
        (overlay as? Sheet)?.tall = true
    }
    // What the panel holds listens to the runtime only while it is open.
    val ready = prebuilt?.take()
    val scope = ready?.first ?: owner.child()
    overlay.onDismissed = {
        scope.dispose()
        // The next one is made while nobody is looking.
        prebuilt?.later()
    }
    overlay.show(ready?.second ?: build(scope))
}

/**
 * A panel's content made before it is asked for, so opening the panel is attaching it and animating it: the first frame of an open has no
 * views to make. [take] hands it over once; [later] makes the next one after a pause.
 */
internal class Prebuilt(private val context: Context, private val owner: Scope, private val make: (Scope) -> View) {
    private var scope: Scope? = null
    private var view: View? = null
    private var pending: Cancel? = null

    fun prepare() {
        if (view != null || owner.disposed) return
        val s = owner.child()
        val v = make(s)
        // Measured once at about the size the panel gives it, so what is worked out from sizes is ready.
        val m = context.resources.displayMetrics
        val width = m.widthPixels - dp(40) - 2 * Math.round(5 * unitPx())
        val height = m.heightPixels - dp(40) - dp(34) - 5 * Math.round(5 * unitPx())
        v.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        scope = s
        view = v
    }

    fun take(): Pair<Scope, View>? {
        pending?.invoke()
        pending = null
        val s = scope
        val v = view
        scope = null
        view = null
        return if (s != null && v != null) s to v else null
    }

    /** Makes one after a pause; a call while one is made or waited for does nothing. */
    fun later() {
        if (view != null || pending != null) return
        pending = owner.after(600) {
            pending = null
            prepare()
        }
    }

    fun dispose() {
        pending?.invoke()
        scope?.dispose()
        scope = null
        view = null
    }
}

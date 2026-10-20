package dev.hashsome.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import dev.hashsome.client.AssetSize
import dev.hashsome.client.Cancel
import dev.hashsome.client.MediaPlayerCommands
import dev.hashsome.client.QueueItem
import dev.hashsome.client.QueueResult
import dev.hashsome.headless.Feedback
import dev.hashsome.headless.HandleState
import dev.hashsome.headless.MediaPlayerControl
import dev.hashsome.headless.QueueControl
import dev.hashsome.headless.transportAvailability
import dev.hashsome.headless.upNext

/** The full-screen player: the track in large type beside its artwork, over a backdrop of the artwork's colors that drifts, with the keys and what plays next. */
object NowPlayingScreen {
    /** How the backdrop moves. For trying the styles on a device without a rebuild: `adb shell settings put global hashsome_backdrop lava` (any style's name) is read each time the screen opens. */
    var backdropStyle: BackdropStyle = BackdropStyle.Drift

    /** Opens it over the page; Back (or the chevron) closes it. */
    fun open(context: Context, control: MediaPlayerControl, label: String) {
        ScreenOverlay(context).open(control, label)
    }

    /** For looking at a style while nothing plays: `adb shell settings put global hashsome_backdrop_always 1` keeps the backdrop moving whatever the player does. */
    internal fun alwaysMoving(context: Context): Boolean = try {
        android.provider.Settings.Global.getInt(context.contentResolver, "hashsome_backdrop_always", 0) == 1
    } catch (_: Exception) {
        false
    }

    internal fun styleFor(context: Context): BackdropStyle = BackdropStyle.named(
        try {
            android.provider.Settings.Global.getString(context.contentResolver, "hashsome_backdrop")
        } catch (_: Exception) {
            null
        },
        backdropStyle,
    )
}

/** Covers the window with the screen, fading in; there is no scrim, the screen is opaque. */
private class ScreenOverlay(context: Context) : Overlay(context) {
    private var content: View? = null

    fun open(control: MediaPlayerControl, label: String) {
        Overlays.dismissAllNow()
        val screen = NowPlayingScreenView(context, control, label)
        screen.onClose = { dismiss() }
        show(screen)
    }

    override fun show(content: View) {
        attach()
        // The base overlay closes on a tap on its scrim; here the screen is all there is, and only its own keys close it.
        scrim.setOnClickListener(null)
        scrim.isClickable = false
        this.content = content
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (Animation.animates) {
            content.alpha = 0f
            content.animate().alpha(1f).setDuration(ENTER_MS).setInterpolator(EASE_OUT).start()
        }
    }

    override fun dismiss() {
        val view = content
        leave(if (view == null) null else { end -> view.animate().alpha(0f).setDuration(EXIT_MS).setInterpolator(EASE_IN).withEndAction(end).start() })
    }

    private companion object {
        const val ENTER_MS = 280L
        const val EXIT_MS = 180L
    }
}

private const val WHITE = 0xFFFFFFFF.toInt()

private fun mixColors(a: Int, b: Int, amount: Float): Int {
    fun part(shift: Int) = Math.round((a shr shift and 0xFF) * (1 - amount) + (b shr shift and 0xFF) * amount)
    return (0xFF shl 24) or (part(16) shl 16) or (part(8) shl 8) or part(0)
}

/** How the backdrop of the full-screen player moves. */
enum class BackdropStyle {
    /** Three large soft blobs going round slow loops, passing through one another. */
    Drift,

    /** Long soft bands sweeping across and turning, like northern lights. */
    Aurora,

    /** Five smaller blobs rising and sinking at their own paces, like a lava lamp. */
    Lava,

    /** Three layers of waves in the colors, scrolling at different speeds. */
    Waves,

    /** The blobs stay where they are and swell and ebb, the calmest. */
    Breathe,
    ;

    companion object {
        /** The style named [name] (any case), or [fallback]. */
        fun named(name: String?, fallback: BackdropStyle): BackdropStyle = values().firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) } ?: fallback
    }
}

/**
 * Soft shapes of three colors on a deep base, moving as [style] says (when the motion level allows, and while [running]) and changing color
 * with a long fade when the colors do. Blobs are radial gradients moved by a matrix and the waves reuse one path, so a frame allocates nothing.
 */
internal class AmbientBackdrop(context: Context) : View(context) {
    var style: BackdropStyle = BackdropStyle.Drift
        set(value) {
            field = value
            invalidate()
        }

    /** Whether it moves: it holds still where it is (a paused player) when not. */
    var running: Boolean = true
        set(value) {
            field = value
            drive()
        }

    private var from = NEUTRAL
    private var to = NEUTRAL
    private var fromShaders: Array<RadialGradient>? = null
    private var toShaders: Array<RadialGradient>? = null
    private val fade = Eased(this, 1f, 1400)
    private var phase = 0f
    private var drift: ValueAnimator? = null
    private var drawnAt = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val path = Path()
    private var radius = 0f
    private var shade: LinearGradient? = null

    /** Moves to [colors] (three); the same ones again do nothing. */
    fun setColors(colors: List<Int>) {
        val next = IntArray(3) { colors.getOrElse(it) { colors.firstOrNull() ?: NEUTRAL[it] } }
        if (next.contentEquals(to)) return
        from = to
        fromShaders = toShaders
        to = next
        toShaders = shadersFor(next)
        fade.to(0f, false)
        fade.to(1f)
    }

    private fun shadersFor(colors: IntArray): Array<RadialGradient>? {
        if (radius <= 0f) return null
        return Array(BLOBS) { RadialGradient(0f, 0f, radius, intArrayOf(withAlpha(colors[it % 3], 0.9f), withAlpha(colors[it % 3], 0f)), null, Shader.TileMode.CLAMP) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        radius = maxOf(w, h) * 0.62f
        fromShaders = shadersFor(from)
        toShaders = shadersFor(to)
        // The bottom darkens, where the small type of what plays next sits.
        shade = LinearGradient(0f, h * 0.55f, 0f, h.toFloat(), 0x00000000, 0x66000000, Shader.TileMode.CLAMP)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Animation.animates) {
            drift = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = LOOP_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    phase = it.animatedValue as Float
                    // A slow drift needs no more than about 20 frames a second, and a frame is several screen-size gradients.
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - drawnAt >= FRAME_MS) {
                        drawnAt = now
                        invalidate()
                    }
                }
                start()
            }
            drive()
        }
    }

    /** Runs or holds the loop. */
    private fun drive() {
        val loop = drift ?: return
        if (running) {
            if (loop.isPaused) loop.resume()
        } else if (loop.isRunning && !loop.isPaused) {
            loop.pause()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        drift?.cancel()
        drift = null
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = fade.value
        canvas.drawColor(mixColors(deep(from[0]), deep(to[0]), t))
        draw(canvas, from, fromShaders, 1f - t, w, h)
        draw(canvas, to, toShaders, t, w, h)
        shade?.let {
            paint.shader = it
            paint.alpha = 255
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        }
    }

    private fun draw(canvas: Canvas, colors: IntArray, shaders: Array<RadialGradient>?, strength: Float, w: Float, h: Float) {
        if (strength <= 0.01f) return
        if (style == BackdropStyle.Waves) {
            waves(canvas, colors, strength, w, h)
            return
        }
        if (shaders == null) return
        val turn = (phase * 2.0 * Math.PI).toFloat()
        val count = if (style == BackdropStyle.Lava) BLOBS else 3
        for (i in 0 until count) {
            when (style) {
                BackdropStyle.Drift -> {
                    // Each blob goes round its own loop, at its own pace, so they pass through one another.
                    val a = turn * (1 + i) + i * 2.1f
                    matrix.setTranslate(w * (0.5f + 0.46f * cos(a) * (if (i == 1) -1f else 1f)), h * (0.5f + 0.44f * sin(a * 0.8f + i)))
                }

                BackdropStyle.Aurora -> {
                    // A long band: the gradient stretched along one way and turned, then carried across.
                    matrix.setScale(2.4f, 0.5f)
                    matrix.postRotate(Math.toDegrees((turn * (0.5f + 0.2f * i) + i * 1.3f).toDouble()).toFloat() % 360f)
                    matrix.postTranslate(w * (0.5f + 0.4f * sin(turn * (1 + i * 0.5f) + i * 1.7f)), h * (0.5f + 0.34f * cos(turn * (1 + i * 0.35f) + i * 2.2f)))
                }

                BackdropStyle.Lava -> {
                    matrix.setScale(0.6f, 0.6f)
                    matrix.postTranslate(w * (0.5f + 0.36f * sin(turn * (1 + i % 2) + i * 1.7f)), h * (0.5f + 0.62f * sin(turn * (1 + i * 0.5f) + i * 2.3f)))
                }

                else -> {
                    // Breathe: fixed places, swelling and ebbing out of step.
                    val scale = 0.8f + 0.35f * sin(turn * 3 + i * 2.1f)
                    matrix.setScale(scale, scale)
                    matrix.postTranslate(w * BREATHE_X[i], h * BREATHE_Y[i] + h * 0.04f * sin(turn * 2 + i))
                }
            }
            shaders[i].setLocalMatrix(matrix)
            paint.shader = shaders[i]
            paint.alpha = Math.round(strength * 255f)
            canvas.drawRect(0f, 0f, w, h, paint)
        }
        paint.shader = null
        paint.alpha = 255
    }

    private fun waves(canvas: Canvas, colors: IntArray, strength: Float, w: Float, h: Float) {
        val turn = (phase * 2.0 * Math.PI).toFloat()
        for (layer in 0 until 3) {
            path.reset()
            path.moveTo(0f, h)
            val steps = 28
            for (k in 0..steps) {
                val x = w * k / steps
                val y = h * (0.42f + 0.17f * layer) + h * (0.07f + 0.02f * layer) * sin(x / w * 6.2832f * (1f + layer * 0.5f) + turn * (layer + 1) * (if (layer == 1) -1f else 1f) + layer)
                path.lineTo(x, y)
            }
            path.lineTo(w, h)
            path.close()
            paint.color = withAlpha(colors[layer], 0.6f * strength)
            canvas.drawPath(path, paint)
        }
        paint.color = WHITE
    }

    private fun cos(a: Float) = Math.cos(a.toDouble()).toFloat()
    private fun sin(a: Float) = Math.sin(a.toDouble()).toFloat()

    /** The base under the shapes: the first color, very dark. */
    private fun deep(color: Int): Int = mixColors(0xFF08080C.toInt(), color, 0.3f)

    private companion object {
        /** One loop of the motion, in ms; the faster shapes go round several times in it. */
        const val LOOP_MS = 26_000L

        /** The least time between two drawn frames. */
        const val FRAME_MS = 45L
        const val BLOBS = 5
        val BREATHE_X = floatArrayOf(0.25f, 0.78f, 0.5f)
        val BREATHE_Y = floatArrayOf(0.3f, 0.4f, 0.85f)
        val NEUTRAL = intArrayOf(0xFF4A4A5A.toInt(), 0xFF3A3A48.toInt(), 0xFF2A2A36.toInt())
    }
}

/** One line of text in its own size, weight and color, ellipsized (the screen's type is larger than the theme's roles). */
internal class ScreenText(context: Context) : View(context) {
    var text: CharSequence = ""
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    var sizeDp: Float = 16f
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var weight: Int = 400
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var color: Int = WHITE
        set(value) {
            field = value
            invalidate()
        }

    var align: Paint.Align = Paint.Align.LEFT
        set(value) {
            field = value
            invalidate()
        }

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private fun prepare() {
        paint.textSize = textPx(sizeDp)
        paint.typeface = theme.typefaces.forWeight(weight)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        prepare()
        val fm = paint.fontMetrics
        val h = Math.ceil((fm.descent - fm.ascent + dp(2f)).toDouble()).toInt()
        val wanted = Math.ceil(paint.measureText(text, 0, text.length).toDouble()).toInt()
        val w = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(wanted, MeasureSpec.getSize(widthMeasureSpec))
            else -> wanted
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        prepare()
        paint.color = color
        val fm = paint.fontMetrics
        val shown = TextUtils.ellipsize(text, paint, width.toFloat(), TextUtils.TruncateAt.END)
        val x = when (align) {
            Paint.Align.CENTER -> width / 2f
            Paint.Align.RIGHT -> width.toFloat()
            else -> 0f
        }
        paint.textAlign = align
        canvas.drawText(shown, 0, shown.length, x, -fm.ascent + dp(1f), paint)
    }
}

/**
 * A round key of the screen: a white glyph on a faint disc, or (when [filled]) a dark glyph on a white one. It squeezes while pressed, fades
 * its glyph in when it changes (play to pause), turns a ring while its press waits for the player and shows a failed press in red, as the
 * player's own keys do.
 */
internal class ScreenButton(context: Context, icon: Icon) : View(context) {
    var icon: Icon = icon
        set(value) {
            if (field == value) return
            field = value
            // The new glyph comes in rather than appearing.
            glyphFade.to(0f, false)
            glyphFade.to(1f)
        }

    var glyphDp = 24f
    var filled = false
    var plain = false
    var onClick: (() -> Unit)? = null

    /** Shown in full white; off, a little dimmed (shuffle that is off). */
    var active = true
        set(value) {
            field = value
            invalidate()
        }

    /** The press is waiting for the player: a ring turns. */
    var pending = false
        set(value) {
            if (field == value) return
            field = value
            spin.run(value && isAttachedToWindow)
            invalidate()
        }

    /** The press failed: the glyph is red. */
    var failed = false
        set(value) {
            field = value
            invalidate()
        }

    /** Struck through with a slash, for what is off (shuffle that is not on): the state is in the shape, not only in the color. */
    var crossed = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Sets [pending] and [failed] from what the press tracker says. */
    fun feedback(state: Feedback?) {
        pending = state == Feedback.Pending
        failed = state == Feedback.Error
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val spin = Spin(this)
    private val glyphFade = Eased(this, 1f, 160)

    init {
        isClickable = true
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
        val s = minOf(width, height).toFloat()
        alpha = if (isEnabled) 1f else 0.35f
        if (filled || !plain) {
            paint.style = Paint.Style.FILL
            paint.color = if (filled) WHITE else withAlpha(WHITE, 0.14f)
            canvas.drawCircle(width / 2f, height / 2f, s / 2f, paint)
        }
        val ink = if (filled) 0xFF14141A.toInt() else WHITE
        if (pending) {
            // A ring turning round the key (inside a disc, at the glyph's size on a plain key), where the player's keys turn theirs.
            val inset = if (filled || !plain) dp(3f) else (s - dp(glyphDp)) / 2f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = ink
            rect.set(inset, inset, width - inset, height - inset)
            canvas.drawArc(rect, spin.angle, 90f, false, paint)
            return
        }
        val px = Math.round(dp(glyphDp))
        val color = when {
            failed -> 0xFFFF6B6B.toInt()
            filled -> ink
            active -> WHITE
            else -> withAlpha(WHITE, 0.5f)
        }
        paint.style = Paint.Style.FILL
        paint.alpha = Math.round(glyphFade.value * 255f)
        canvas.drawBitmap(iconBitmap(icon, px, color), Math.round((width - px) / 2f).toFloat(), Math.round((height - px) / 2f).toFloat(), paint)
        paint.alpha = 255
        if (crossed) {
            // A slash from the top left to the bottom right, with a dark edge so it cuts the glyph instead of merging with it.
            val half = px * 0.46f
            val cx = width / 2f
            val cy = height / 2f
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = dp(5f)
            paint.color = 0x59000000
            canvas.drawLine(cx - half, cy - half, cx + half, cy + half, paint)
            paint.strokeWidth = dp(2f)
            paint.color = color
            canvas.drawLine(cx - half, cy - half, cx + half, cy + half, paint)
            paint.style = Paint.Style.FILL
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (isEnabled) squeeze(0.9f)
            MotionEvent.ACTION_UP -> {
                squeeze(1f)
                if (isEnabled && event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()) onClick?.invoke()
            }

            MotionEvent.ACTION_CANCEL -> squeeze(1f)
        }
        return true
    }

    private fun squeeze(to: Float) {
        if (!Animation.animates) return
        animate().scaleX(to).scaleY(to).setDuration(110).start()
    }
}

/** The track's time on its artwork: how long it has played and how long is left, in a small dark pill. */
internal class TimePill(context: Context) : View(context) {
    var text: CharSequence = ""
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    var sizeDp = 13f
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private fun prepare() {
        label.textSize = textPx(sizeDp)
        label.typeface = theme.typefaces.forWeight(500)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        prepare()
        val h = dp(sizeDp * 2f).toInt()
        val w = if (text.isEmpty()) 0 else Math.ceil(label.measureText(text, 0, text.length).toDouble()).toInt() + dp(sizeDp * 1.6f).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        if (text.isEmpty()) return
        prepare()
        paint.color = 0x66000000
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, height / 2f, height / 2f, paint)
        label.color = WHITE
        label.textAlign = Paint.Align.CENTER
        val fm = label.fontMetrics
        canvas.drawText(text, 0, text.length, width / 2f, height / 2f - (fm.ascent + fm.descent) / 2f, label)
    }
}

/** A thin bar in white over a faint track: how far along the track is (a drag seeks) or the volume (a drag sets it). */
internal class ScreenBar(context: Context) : View(context) {
    var fraction = 0f
        set(value) {
            field = value
            invalidate()
        }

    var onPreview: ((Float) -> Unit)? = null
    var onCommit: ((Float) -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var dragging = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(HEIGHT))
    }

    override fun onDraw(canvas: Canvas) {
        val bar = dp(4f)
        val top = (height - bar) / 2f
        paint.color = withAlpha(WHITE, 0.28f)
        rect.set(0f, top, width.toFloat(), top + bar)
        canvas.drawRoundRect(rect, bar / 2, bar / 2, paint)
        paint.color = WHITE
        val filled = (width * fraction.coerceIn(0f, 1f))
        rect.set(0f, top, filled, top + bar)
        canvas.drawRoundRect(rect, bar / 2, bar / 2, paint)
        if (dragging) canvas.drawCircle(filled.coerceIn(dp(5f), width - dp(5f)), height / 2f, dp(6f), paint)
    }

    private fun at(x: Float) = (x / width).coerceIn(0f, 1f)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || (onPreview == null && onCommit == null)) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                onPreview?.invoke(at(event.x))
            }

            MotionEvent.ACTION_MOVE -> if (dragging) onPreview?.invoke(at(event.x))
            MotionEvent.ACTION_UP -> {
                if (dragging) onCommit?.invoke(at(event.x))
                dragging = false
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) onCancel?.invoke()
                dragging = false
                invalidate()
            }
        }
        return true
    }

    companion object {
        /** The height that can be pressed, in dp; the bar itself is thinner. */
        const val HEIGHT = 28
    }
}

/** One of what plays next: its small artwork with rounded corners, the title and the artist. A tap plays it. */
internal class UpNextItemView(context: Context) : View(context) {
    var item: QueueItem? = null
        set(value) {
            field = value
            artwork.set(value?.artworkUrl)
            visibility = if (value == null) INVISIBLE else VISIBLE
            invalidate()
        }

    var onClick: ((QueueItem) -> Unit)? = null

    /** The side of the small artwork, in dp (smaller on a square display, where room is short). */
    var pictureDp = PICTURE
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val artist = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val matrix = Matrix()
    private var picture: Bitmap? = null
    private var shader: BitmapShader? = null
    private val artwork = ArtworkSlot(AssetSize.Small, { dp(pictureDp) }) {
        picture = it
        shader = it?.let { b -> BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        invalidate()
    }

    init {
        isClickable = true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        artwork.stop()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        item?.let { artwork.set(it.artworkUrl) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(pictureDp))
    }

    override fun onDraw(canvas: Canvas) {
        val track = item ?: return
        val size = dp(pictureDp).toFloat()
        val radius = dp(6f)
        rect.set(0f, 0f, size, size)
        paint.color = withAlpha(WHITE, 0.12f)
        canvas.drawRoundRect(rect, radius, radius, paint)
        val b = picture
        val sh = shader
        if (b != null && sh != null) {
            val scale = size / minOf(b.width, b.height)
            matrix.setScale(scale, scale)
            sh.setLocalMatrix(matrix)
            paint.color = WHITE
            paint.shader = sh
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = null
        }
        val theme = theme
        title.textSize = textPx(if (pictureDp < PICTURE) 12f else 13f)
        title.typeface = theme.typefaces.forWeight(600)
        title.color = WHITE
        artist.textSize = textPx(if (pictureDp < PICTURE) 10f else 11f)
        artist.typeface = theme.typefaces.forWeight(400)
        artist.color = withAlpha(WHITE, 0.62f)
        val x = size + dp(10f)
        val room = width - x
        canvas.drawText(TextUtils.ellipsize(track.title, title, room, TextUtils.TruncateAt.END).toString(), x, size * 0.42f, title)
        track.artist?.takeIf { it.isNotEmpty() }?.let {
            canvas.drawText(TextUtils.ellipsize(it, artist, room, TextUtils.TruncateAt.END).toString(), x, size * 0.74f, artist)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val track = item ?: return false
        if (event.actionMasked == MotionEvent.ACTION_UP && event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()) onClick?.invoke(track)
        return true
    }

    companion object {
        /** The small artwork's side, in dp. */
        const val PICTURE = 44
    }
}

/** A small pill over the screen with the volume: its mute key and its bar. It opens from the volume key and goes after a few seconds untouched. */
internal class VolumePopover(context: Context) : ViewGroup(context) {
    val mute = ScreenButton(context, UiIcons.volume).also {
        it.plain = true
        it.glyphDp = 18f
    }
    val bar = ScreenBar(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val hide = Runnable { visibility = GONE }

    init {
        visibility = GONE
        setWillNotDraw(false)
        addView(mute)
        addView(bar)
    }

    /** Shows it (or keeps it showing) for a few seconds more. */
    fun keepOpen() {
        visibility = VISIBLE
        removeCallbacks(hide)
        postDelayed(hide, OPEN_MS)
    }

    fun close() {
        removeCallbacks(hide)
        visibility = GONE
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(hide)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(WIDTH), dp(HEIGHT))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val key = dp(32)
        val pad = dp(8)
        mute.measure(MeasureSpec.makeMeasureSpec(key, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(key, MeasureSpec.EXACTLY))
        mute.layout(pad, (height - key) / 2, pad + key, (height + key) / 2)
        val barLeft = pad + key + dp(4)
        val barWidth = width - barLeft - dp(16)
        bar.measure(MeasureSpec.makeMeasureSpec(barWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(ScreenBar.HEIGHT), MeasureSpec.EXACTLY))
        bar.layout(barLeft, (height - dp(ScreenBar.HEIGHT)) / 2, barLeft + barWidth, (height + dp(ScreenBar.HEIGHT)) / 2)
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = 0x99000000.toInt()
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, height / 2f, height / 2f, paint)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Any touch on it keeps it open.
        removeCallbacks(hide)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) postDelayed(hide, OPEN_MS)
        return super.dispatchTouchEvent(event)
    }

    private companion object {
        const val WIDTH = 210
        const val HEIGHT = 44
        const val OPEN_MS = 3500L
    }
}

/**
 * The screen itself. On a wide display the track's name, the keys and the progress are at the left and the artwork at the right, with what
 * plays next along the bottom; on a square one the artwork is on top. Everything draws in white over [AmbientBackdrop], which takes its colors
 * from the artwork, so the theme's colors are not used here (the screen is dark in either theme).
 */
internal class NowPlayingScreenView(
    context: Context,
    private val control: MediaPlayerControl,
    private val label: String,
) : ViewGroup(context) {
    var onClose: (() -> Unit)? = null

    private val backdrop = AmbientBackdrop(context)
    private val back = ScreenButton(context, UiIcons.chevronLeft).also { it.glyphDp = 20f }
    private val ring = ArtworkRingView(context).also {
        it.trackColor = withAlpha(WHITE, 0.26f)
        it.progressColor = WHITE
        it.hairlineColor = withAlpha(WHITE, 0.16f)
        it.discColor = 0x33000000
    }
    private val title = ScreenText(context).also {
        it.sizeDp = 26f
        it.weight = 600
    }
    private val artist = ScreenText(context).also {
        it.sizeDp = 16f
        it.color = withAlpha(WHITE, 0.86f)
    }
    private val album = ScreenText(context).also {
        it.sizeDp = 14f
        it.color = withAlpha(WHITE, 0.58f)
    }
    private val time = TimePill(context)
    private val previous = ScreenButton(context, UiIcons.skipPrevious).also {
        it.plain = true
        it.glyphDp = 26f
    }
    private val play = ScreenButton(context, UiIcons.play).also {
        it.filled = true
        it.glyphDp = 26f
    }
    private val next = ScreenButton(context, UiIcons.skipNext).also {
        it.plain = true
        it.glyphDp = 26f
    }
    private val shuffle = ScreenButton(context, UiIcons.shuffle).also {
        it.plain = true
        it.glyphDp = 20f
    }
    private val volumeKey = ScreenButton(context, UiIcons.volume).also {
        it.plain = true
        it.glyphDp = 20f
    }
    private val popover = VolumePopover(context)
    private val upNextLabel = ScreenText(context).also {
        it.text = "Up next"
        it.sizeDp = 12f
        it.weight = 600
        it.color = withAlpha(WHITE, 0.6f)
    }
    private val items = List(MAX_UP_NEXT) { UpNextItemView(context) }
    private val artwork = ArtworkSlot(AssetSize.Medium, { maxOf(ring.width, dp(160)) }) { picture ->
        ring.show(picture)
        backdrop.setColors(colorsOf(picture))
    }

    private var scope = control.scope.child()
    private var queue: QueueControl? = null
    private var wide = true
    private var shown = MAX_UP_NEXT

    init {
        isClickable = true
        backdrop.style = NowPlayingScreen.styleFor(context)
        addView(backdrop)
        addView(ring)
        for (v in listOf(title, artist, album, previous, play, next, shuffle, volumeKey, upNextLabel)) addView(v)
        addView(time)
        items.forEach { addView(it) }
        addView(back)
        addView(popover)
        back.onClick = { onClose?.invoke() }
        previous.onClick = { control.press("previous", MediaPlayerCommands.previous()) }
        play.onClick = { control.press("togglePlay", MediaPlayerCommands.togglePlay()) }
        next.onClick = { control.press("next", MediaPlayerCommands.next()) }
        shuffle.onClick = { control.press("setShuffle", MediaPlayerCommands.setShuffle(control.handle.entity?.shuffle != true)) }
        volumeKey.onClick = { if (popover.visibility == VISIBLE) popover.close() else popover.keepOpen() }
        popover.mute.onClick = { control.volume.toggleMute() }
        ring.onPreview = { control.seek.drag(it) }
        ring.onCommit = { control.seek.commit(it) }
        ring.onCancel = { control.seek.cancel() }
        popover.bar.onPreview = { control.volume.drag(it.toDouble()) }
        popover.bar.onCommit = { control.volume.commit(it.toDouble()) }
        popover.bar.onCancel = { control.volume.cancel() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = control.scope.child()
        val q = QueueControl(control.handle, scope).also { queue = it }
        items.forEach { v -> v.onClick = { item -> q.play(item) } }
        val redraw: (Any?) -> Unit = { render() }
        val cancels = ArrayList<Cancel>()
        cancels += control.handle.state.listen(redraw)
        cancels += control.playing.listen(redraw)
        cancels += control.feedback.listen(redraw)
        cancels += control.seek.shown.listen(redraw)
        cancels += control.volume.shown.listen(redraw)
        cancels += control.volume.muted.listen(redraw)
        cancels += q.state.listen(redraw)
        detach = { cancels.forEach { it() } }
        render()
    }

    private var detach: (() -> Unit)? = null

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        detach?.invoke()
        detach = null
        artwork.stop()
        scope.dispose()
        queue = null
    }

    /** Three colors for the backdrop from a downscaled copy of the artwork; the neutral ones for no picture. */
    private fun colorsOf(picture: Bitmap?): List<Int> {
        if (picture == null) return listOf(0xFF4A4A5A.toInt(), 0xFF3A3A48.toInt(), 0xFF2A2A36.toInt())
        val small = Bitmap.createScaledBitmap(picture, SAMPLE, SAMPLE, true)
        val pixels = IntArray(SAMPLE * SAMPLE)
        small.getPixels(pixels, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE)
        if (small !== picture) small.recycle()
        return ArtworkPalette.from(pixels)
    }

    private fun render() {
        val state = control.handle.state.value
        val ready = state is HandleState.Ready
        val player = control.handle.entity
        val caps = player?.capabilities
        title.text = if (ready) player?.media?.title ?: "Nothing playing" else mediaStatusLabel(state).orEmpty()
        artist.text = if (ready) player?.media?.artist ?: player?.name ?: label else ""
        album.text = if (ready) player?.media?.album.orEmpty() else ""
        artwork.set(if (ready) player?.media?.artworkUrl else null)

        val duration = if (ready) player?.duration else null
        val position = control.seek.shown.value
        val fraction = if (position != null && duration != null && duration > 0) (position / duration).coerceIn(0.0, 1.0).toFloat() else 0f
        val seekable = ready && caps?.seek == true && duration != null && duration > 0
        ring.length = if (seekable) duration!! else 0.0
        ring.fraction = fraction
        // Where the track is, in words as well as in the ring, as the main player writes it: the position and the length, on the artwork.
        time.text = if (position != null && duration != null && duration > 0) "${MediaFormat.duration(position)} / ${MediaFormat.duration(duration)}" else ""
        backdrop.running = control.playing.value || NowPlayingScreen.alwaysMoving(context)

        val can = availability()
        previous.isEnabled = ready && caps?.previous != false && can.previous
        next.isEnabled = ready && caps?.next != false && can.next
        play.isEnabled = ready && can.play
        play.icon = if (control.playing.value) UiIcons.pause else UiIcons.play
        previous.feedback(control.feedbackFor("previous"))
        play.feedback(control.feedbackFor("togglePlay"))
        next.feedback(control.feedbackFor("next"))
        shuffle.feedback(control.feedbackFor("setShuffle"))
        shuffle.visibility = if (ready && caps?.shuffle == true) VISIBLE else INVISIBLE
        shuffle.crossed = player?.shuffle != true
        val hasVolume = ready && (caps?.volume != false || caps?.mute == true)
        volumeKey.visibility = if (hasVolume) VISIBLE else INVISIBLE
        val muted = control.volume.muted.value
        volumeKey.icon = if (muted) UiIcons.volumeMuted else UiIcons.volume
        popover.mute.icon = volumeKey.icon
        popover.mute.active = !muted
        popover.bar.fraction = control.volume.shown.value.toFloat()

        val next = queue?.state?.value?.upNext(items.size) ?: emptyList()
        for ((i, v) in items.withIndex()) v.item = if (i < shown) next.getOrNull(i) else null
        upNextLabel.visibility = if (next.isEmpty()) INVISIBLE else VISIBLE
        requestLayout()
    }

    /** What the keys can do, from the queue (as the transport's keys are). */
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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    private fun place(view: View, left: Int, top: Int, width: Int, height: Int) {
        view.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        view.layout(left, top, left + width, top + height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        place(backdrop, 0, 0, w, h)
        wide = w >= h * 1.3f
        val pad = dp(if (wide) 24 else 18)
        place(back, pad, pad - dp(4), dp(40), dp(40))
        // What plays next runs along the bottom: as many as there is width for, at least two; its pictures are smaller on a square display.
        val count = if (wide) ((w - 2 * pad) / dp(230)).coerceIn(2, MAX_UP_NEXT) else 2
        shown = count
        val picture = if (wide) UpNextItemView.PICTURE else 36
        items.forEach { it.pictureDp = picture }
        val itemsHeight = dp(picture)
        val labelHeight = dp(if (wide) 18 else 16)
        val blockTop = h - pad - itemsHeight - labelHeight - dp(4)
        place(upNextLabel, pad, blockTop, w - 2 * pad, labelHeight)
        val gap = dp(16)
        val itemWidth = (w - 2 * pad - gap * (count - 1)) / count
        for ((i, v) in items.withIndex()) {
            if (i < count) {
                place(v, pad + i * (itemWidth + gap), blockTop + labelHeight + dp(4), itemWidth, itemsHeight)
            } else {
                place(v, 0, 0, 0, 0)
            }
        }
        if (wide) layoutWide(w, pad, blockTop) else layoutSquare(w, pad, blockTop)
    }

    private fun layoutWide(w: Int, pad: Int, blockTop: Int) {
        val top = pad
        val room = blockTop - top - dp(12)
        val d = minOf(room, ((w - 2 * pad) * 0.42f).toInt())
        val ringLeft = w - pad - d - dp(8)
        val ringTop = top + (room - d) / 2
        place(ring, ringLeft, ringTop, d, d)
        timeOn(ringLeft, ringTop, d)
        val x0 = pad
        val colW = ringLeft - dp(24) - x0
        for (t in listOf(title, artist, album)) t.align = Paint.Align.LEFT
        // The text is at the top of the column and the keys at the bottom of it, above what plays next.
        var y = top + dp(44)
        place(title, x0, y, colW, dp(32)); y += dp(32)
        place(artist, x0, y, colW, dp(24)); y += dp(24)
        place(album, x0, y, colW, dp(20))
        controls(x0, blockTop - dp(10) - dp(KEYS_WIDE), colW, KEYS_WIDE, 1.22f)
    }

    private fun layoutSquare(w: Int, pad: Int, blockTop: Int) {
        val colW = w - 2 * pad
        for (t in listOf(title, artist, album)) t.align = Paint.Align.CENTER
        // What is under the artwork has the height it needs; the artwork takes the rest.
        val textH = dp(30) + dp(22) + dp(18)
        val keysH = dp(KEYS)
        val under = textH + dp(14) + keysH + dp(6)
        val d = (blockTop - pad - under - dp(6)).coerceIn(dp(96), (w * 0.62f).toInt())
        var y = pad
        place(ring, (w - d) / 2, y, d, d)
        timeOn((w - d) / 2, y, d)
        y += d + dp(6)
        place(title, pad, y, colW, dp(30)); y += dp(30)
        place(artist, pad, y, colW, dp(22)); y += dp(22)
        place(album, pad, y, colW, dp(18)); y += dp(18) + dp(14)
        controls(pad, y, colW, KEYS, 1f)
    }

    /** The time sits on the artwork, in the lower part of its picture. */
    private fun timeOn(left: Int, top: Int, d: Int) {
        time.sizeDp = if (d > dp(260)) 14f else 12f
        time.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val tw = time.measuredWidth
        val th = time.measuredHeight
        val cx = left + d / 2
        val cy = top + (d * 0.74f).toInt()
        time.layout(cx - tw / 2, cy - th / 2, cx + tw / 2, cy + th / 2)
    }

    /** The row of keys: shuffle, previous, play, next and the volume, the three of the transport larger than the two at the ends; [scale] sizes them all. */
    private fun controls(x0: Int, y: Int, width: Int, height: Int, scale: Float) {
        val small = Math.round(dp(44f * scale))
        val side = Math.round(dp(48f * scale))
        val big = Math.round(dp(64f * scale))
        val gap = Math.round(dp(10f * scale))
        // The glyphs grow with their keys.
        previous.glyphDp = 26f * scale
        next.glyphDp = 26f * scale
        play.glyphDp = 26f * scale
        shuffle.glyphDp = 20f * scale
        volumeKey.glyphDp = 20f * scale
        val total = small * 2 + side * 2 + big + gap * 4
        var x = x0 + (width - total) / 2
        val centre = y + height / 2
        place(shuffle, x, centre - small / 2, small, small); x += small + gap
        place(previous, x, centre - side / 2, side, side); x += side + gap
        place(play, x, centre - big / 2, big, big); x += big + gap
        place(next, x, centre - side / 2, side, side); x += side + gap
        place(volumeKey, x, centre - small / 2, small, small)
        // The volume's pill opens above its key, kept inside the screen.
        val popW = dp(210)
        val popH = dp(44)
        val left = (x + small - popW).coerceAtLeast(dp(8))
        place(popover, left, y - popH - dp(6), popW, popH)
    }

    private companion object {
        /** The height of the row of keys, in dp. */
        const val KEYS = 64
        const val KEYS_WIDE = 78
        const val MAX_UP_NEXT = 4
        const val SAMPLE = 24
    }
}

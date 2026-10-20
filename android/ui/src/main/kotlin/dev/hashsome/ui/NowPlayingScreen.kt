package dev.hashsome.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
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
import dev.hashsome.headless.HandleState
import dev.hashsome.headless.MediaPlayerControl
import dev.hashsome.headless.QueueControl
import dev.hashsome.headless.transportAvailability
import dev.hashsome.headless.upNext

/** The full-screen player: the track in large type beside its artwork, over a backdrop of the artwork's colors that drifts, with the keys and what plays next. */
object NowPlayingScreen {
    /** Opens it over the page; Back (or the chevron) closes it. */
    fun open(context: Context, control: MediaPlayerControl, label: String) {
        ScreenOverlay(context).open(control, label)
    }
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

/**
 * Soft blobs of three colors on a deep base, drifting slowly (when the motion level allows) and changing color with a long fade when
 * the colors do. The blobs are radial gradients moved by a matrix, so a frame allocates nothing.
 */
internal class AmbientBackdrop(context: Context) : View(context) {
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
        return Array(3) { RadialGradient(0f, 0f, radius, intArrayOf(withAlpha(colors[it], 0.85f), withAlpha(colors[it], 0f)), null, Shader.TileMode.CLAMP) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        radius = maxOf(w, h) * 0.95f
        fromShaders = shadersFor(from)
        toShaders = shadersFor(to)
        // The bottom darkens, where the small type of what plays next sits.
        shade = LinearGradient(0f, h * 0.55f, 0f, h.toFloat(), 0x00000000, 0x66000000, Shader.TileMode.CLAMP)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Animation.animates) {
            drift = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = DRIFT_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    phase = it.animatedValue as Float
                    // A slow drift needs no more than about 20 frames a second, and a frame is three screen-size gradients.
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - drawnAt >= FRAME_MS) {
                        drawnAt = now
                        invalidate()
                    }
                }
                start()
            }
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
        drawBlobs(canvas, fromShaders, (1f - t), w, h)
        drawBlobs(canvas, toShaders, t, w, h)
        shade?.let {
            paint.shader = it
            paint.alpha = 255
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        }
    }

    private fun drawBlobs(canvas: Canvas, shaders: Array<RadialGradient>?, strength: Float, w: Float, h: Float) {
        if (shaders == null || strength <= 0.01f) return
        val turn = (phase * 2.0 * Math.PI).toFloat()
        for (i in 0 until 3) {
            // Each blob goes round its own slow loop, at its own pace, so they pass through one another.
            val a = turn * (1 + i) + i * 2.1f
            val cx = w * (0.5f + 0.4f * Math.cos(a.toDouble()).toFloat() * (if (i == 1) -1f else 1f))
            val cy = h * (0.5f + 0.38f * Math.sin((a * 0.8f + i).toDouble()).toFloat())
            matrix.setTranslate(cx, cy)
            shaders[i].setLocalMatrix(matrix)
            paint.shader = shaders[i]
            paint.alpha = Math.round(strength * 255f)
            canvas.drawRect(0f, 0f, w, h, paint)
        }
        paint.shader = null
        paint.alpha = 255
    }

    /** The base under the blobs: the first color, very dark. */
    private fun deep(color: Int): Int = mixColors(0xFF08080C.toInt(), color, 0.3f)

    private companion object {
        /** One loop of every blob's path, in ms; the faster ones go round several times in it. */
        const val DRIFT_MS = 70_000L

        /** The least time between two drawn frames of the drift. */
        const val FRAME_MS = 45L
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

/** A round key of the screen: a white glyph on a faint disc, or (when [filled]) a dark glyph on a white one. It squeezes while pressed. */
internal class ScreenButton(context: Context, var icon: Icon) : View(context) {
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

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pressed = false

    init {
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        alpha = if (isEnabled) 1f else 0.35f
        if (filled || !plain) {
            paint.color = if (filled) WHITE else withAlpha(WHITE, 0.14f)
            canvas.drawCircle(width / 2f, height / 2f, s / 2f, paint)
        }
        val px = Math.round(dp(glyphDp))
        val color = if (filled) 0xFF14141A.toInt() else if (active) WHITE else withAlpha(WHITE, 0.5f)
        canvas.drawIcon(iconBitmap(icon, px, color), (width - px) / 2f, (height - px) / 2f)
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

    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val artist = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val matrix = Matrix()
    private var picture: Bitmap? = null
    private var shader: BitmapShader? = null
    private val artwork = ArtworkSlot(AssetSize.Small, { dp(PICTURE) }) {
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
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(PICTURE))
    }

    override fun onDraw(canvas: Canvas) {
        val track = item ?: return
        val size = dp(PICTURE).toFloat()
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
        title.textSize = textPx(13f)
        title.typeface = theme.typefaces.forWeight(600)
        title.color = WHITE
        artist.textSize = textPx(11f)
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
        it.sizeDp = 28f
        it.weight = 600
    }
    private val artist = ScreenText(context).also {
        it.sizeDp = 18f
        it.color = withAlpha(WHITE, 0.86f)
    }
    private val album = ScreenText(context).also {
        it.sizeDp = 15f
        it.color = withAlpha(WHITE, 0.58f)
    }
    private val bar = ScreenBar(context)
    private val elapsed = ScreenText(context).also {
        it.sizeDp = 12f
        it.color = withAlpha(WHITE, 0.7f)
    }
    private val remaining = ScreenText(context).also {
        it.sizeDp = 12f
        it.color = withAlpha(WHITE, 0.7f)
        it.align = Paint.Align.RIGHT
    }
    private val previous = ScreenButton(context, UiIcons.skipPrevious).also {
        it.plain = true
        it.glyphDp = 30f
    }
    private val play = ScreenButton(context, UiIcons.play).also {
        it.filled = true
        it.glyphDp = 30f
    }
    private val next = ScreenButton(context, UiIcons.skipNext).also {
        it.plain = true
        it.glyphDp = 30f
    }
    private val shuffle = ScreenButton(context, UiIcons.shuffle).also {
        it.plain = true
        it.glyphDp = 20f
    }
    private val mute = ScreenButton(context, UiIcons.volume).also {
        it.plain = true
        it.glyphDp = 20f
    }
    private val volume = ScreenBar(context)
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
        addView(backdrop)
        addView(ring)
        for (v in listOf(title, artist, album, bar, elapsed, remaining, previous, play, next, shuffle, mute, volume, upNextLabel)) addView(v)
        items.forEach { addView(it) }
        addView(back)
        back.onClick = { onClose?.invoke() }
        previous.onClick = { control.press("previous", MediaPlayerCommands.previous()) }
        play.onClick = { control.press("togglePlay", MediaPlayerCommands.togglePlay()) }
        next.onClick = { control.press("next", MediaPlayerCommands.next()) }
        shuffle.onClick = { control.press("setShuffle", MediaPlayerCommands.setShuffle(control.handle.entity?.shuffle != true)) }
        mute.onClick = { control.volume.toggleMute() }
        ring.onPreview = { control.seek.drag(it) }
        ring.onCommit = { control.seek.commit(it) }
        ring.onCancel = { control.seek.cancel() }
        bar.onPreview = { fraction -> control.handle.entity?.duration?.let { control.seek.drag(it * fraction) } }
        bar.onCommit = { fraction -> control.handle.entity?.duration?.let { control.seek.commit(it * fraction) } }
        bar.onCancel = { control.seek.cancel() }
        volume.onPreview = { control.volume.drag(it.toDouble()) }
        volume.onCommit = { control.volume.commit(it.toDouble()) }
        volume.onCancel = { control.volume.cancel() }
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
        bar.fraction = fraction
        bar.isEnabled = seekable
        elapsed.text = if (position != null && duration != null && duration > 0) MediaFormat.duration(position) else ""
        remaining.text = if (position != null && duration != null && duration > 0) "-" + MediaFormat.duration((duration - position).coerceAtLeast(0.0)) else ""

        val can = availability()
        previous.isEnabled = ready && caps?.previous != false && can.previous
        next.isEnabled = ready && caps?.next != false && can.next
        play.isEnabled = ready && can.play
        play.icon = if (control.playing.value) UiIcons.pause else UiIcons.play
        shuffle.visibility = if (ready && caps?.shuffle == true) VISIBLE else INVISIBLE
        shuffle.active = player?.shuffle == true
        val hasVolume = ready && (caps?.volume != false || caps?.mute == true)
        mute.visibility = if (hasVolume) VISIBLE else INVISIBLE
        volume.visibility = if (hasVolume) VISIBLE else INVISIBLE
        val muted = control.volume.muted.value
        mute.icon = if (muted) UiIcons.volumeMuted else UiIcons.volume
        mute.active = !muted
        volume.fraction = control.volume.shown.value.toFloat()

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
        val pad = dp(if (wide) 24 else 20)
        place(back, pad, pad - dp(4), dp(40), dp(40))
        // What plays next runs along the bottom: as many as there is width for, at least two.
        val count = if (wide) ((w - 2 * pad) / dp(230)).coerceIn(2, MAX_UP_NEXT) else 2
        shown = count
        val itemsHeight = dp(UpNextItemView.PICTURE)
        val labelHeight = dp(18)
        val blockTop = h - pad - itemsHeight - labelHeight - dp(6)
        place(upNextLabel, pad, blockTop, w - 2 * pad, labelHeight)
        val gap = dp(16)
        val itemWidth = (w - 2 * pad - gap * (count - 1)) / count
        for ((i, v) in items.withIndex()) {
            if (i < count) {
                place(v, pad + i * (itemWidth + gap), blockTop + labelHeight + dp(6), itemWidth, itemsHeight)
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
        place(ring, ringLeft, top + (room - d) / 2, d, d)
        val x0 = pad
        val colW = ringLeft - dp(24) - x0
        var y = top + dp(48)
        place(title, x0, y, colW, dp(36)); y += dp(36)
        place(artist, x0, y, colW, dp(26)); y += dp(26)
        place(album, x0, y, colW, dp(22)); y += dp(22) + dp(14)
        controls(x0, y, colW)
        y += dp(72) + dp(10)
        progress(x0, y, colW)
        y += dp(28) + dp(16) + dp(10)
        extras(x0, y, colW)
    }

    private fun layoutSquare(w: Int, pad: Int, blockTop: Int) {
        val colW = w - 2 * pad
        val d = minOf((blockTop * 0.30f).toInt(), (w * 0.45f).toInt())
        var y = pad + dp(2)
        place(ring, (w - d) / 2, y, d, d)
        y += d + dp(8)
        for (t in listOf(title, artist, album)) t.align = Paint.Align.CENTER
        place(title, pad, y, colW, dp(34)); y += dp(34)
        place(artist, pad, y, colW, dp(24)); y += dp(24)
        place(album, pad, y, colW, dp(20)); y += dp(20) + dp(6)
        progress(pad, y, colW)
        y += dp(28) + dp(16) + dp(2)
        controls(pad, y, colW)
        y += dp(72) + dp(2)
        extras(pad, y, colW)
    }

    private fun controls(x0: Int, y: Int, width: Int) {
        val side = dp(56)
        val big = dp(72)
        val gap = dp(20)
        val total = side * 2 + big + gap * 2
        var x = x0 + (width - total) / 2
        place(previous, x, y + (big - side) / 2, side, side); x += side + gap
        place(play, x, y, big, big); x += big + gap
        place(next, x, y + (big - side) / 2, side, side)
    }

    private fun progress(x0: Int, y: Int, width: Int) {
        place(bar, x0, y, width, dp(ScreenBar.HEIGHT))
        place(elapsed, x0, y + dp(ScreenBar.HEIGHT) - dp(4), width / 2, dp(16))
        place(remaining, x0 + width / 2, y + dp(ScreenBar.HEIGHT) - dp(4), width - width / 2, dp(16))
    }

    private fun extras(x0: Int, y: Int, width: Int) {
        // Shuffle at the left; the volume takes the rest: its key, then its bar to the edge.
        val key = dp(40)
        place(shuffle, x0, y, key, key)
        val muteLeft = x0 + width / 3
        place(mute, muteLeft, y, key, key)
        val barLeft = muteLeft + key + dp(6)
        place(volume, barLeft, y + (key - dp(ScreenBar.HEIGHT)) / 2, (x0 + width) - barLeft, dp(ScreenBar.HEIGHT))
    }

    private companion object {
        const val MAX_UP_NEXT = 4
        const val SAMPLE = 24
    }
}

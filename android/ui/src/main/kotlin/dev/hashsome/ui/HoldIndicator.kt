package dev.hashsome.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout

/**
 * Where a hold gesture (pressing and keeping still for a time) reports its progress, so it is drawn in the middle of the
 * screen instead of under the finger. The activity installs one as `Hashsome.holdIndicator`; [source] names the gesture, and
 * only the gesture that began the hold can end it.
 */
internal interface HoldProgressSink {
    /** [source] began a hold of [holdMs] at [startUptimeMs] (the uptime clock), described to the user by [label]. */
    fun begin(source: String, startUptimeMs: Long, holdMs: Long, label: String)

    /** [source] gave the hold up (the finger lifted or drifted away). */
    fun cancel(source: String)

    /** [source]'s hold ran its full time. */
    fun complete(source: String)
}

/** The fades and fill of the hold indicator, as pure math (ms). */
internal object HoldIndicatorMath {
    const val FADE_IN_MS = 120L
    const val CANCEL_MS = 120L
    const val COMPLETE_MS = 220L

    /** The opacity, 0..1, [sinceBeginMs] after the hold began. */
    fun fadeIn(sinceBeginMs: Long): Float = (sinceBeginMs.toFloat() / FADE_IN_MS).coerceIn(0f, 1f)

    /** The opacity, 0..1, [sinceEndMs] after the hold ended while it showed [from]; gone once [durationMs] have passed. */
    fun fadeOut(from: Float, sinceEndMs: Long, durationMs: Long): Float =
        if (durationMs <= 0) 0f else from * (1f - (sinceEndMs.toFloat() / durationMs).coerceIn(0f, 1f))
}

/**
 * The progress of a hold, drawn in the middle of the screen: a ring filling clockwise on a raised disc with a short label under
 * it. It fades in as the hold begins, out when it is given up, and fills and then fades when it completes (at once, when the
 * motion level plays nothing). It takes no touches and is added over the content by the activity that owns it.
 */
internal class HoldIndicator(context: Context) : View(context), HoldProgressSink {
    private enum class Phase { Idle, Holding, Cancelled, Completed }

    // One drawn thing: the fade needs no layer of its own.
    override fun hasOverlappingRendering(): Boolean = false

    private var phase = Phase.Idle
    private var source = ""
    private var label = ""
    private var startedAt = 0L
    private var holdMs = 1L
    private var endedAt = 0L
    private var endAlpha = 0f
    private var endProgress = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()
    private val plate = RectF()
    private var styledFor: Theme? = null
    private var labelWidth = 0f

    init {
        visibility = INVISIBLE
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun begin(source: String, startUptimeMs: Long, holdMs: Long, label: String) {
        this.source = source
        this.startedAt = startUptimeMs
        this.holdMs = holdMs
        if (this.label != label) {
            this.label = label
            styledFor = null
            requestLayout()
        }
        phase = Phase.Holding
        visibility = VISIBLE
        bringToFront()
        postInvalidateOnAnimation()
    }

    override fun cancel(source: String) = end(source, Phase.Cancelled)

    override fun complete(source: String) = end(source, Phase.Completed)

    private fun end(source: String, to: Phase) {
        if (phase != Phase.Holding || source != this.source) return
        val now = SystemClock.uptimeMillis()
        endAlpha = HoldIndicatorMath.fadeIn(now - startedAt)
        endProgress = if (to == Phase.Completed) 1f else StatusDotHold.progress(StatusDotHold.elapsed(now, startedAt), holdMs)
        endedAt = now
        phase = to
        if (!Animation.animates) hide() else postInvalidateOnAnimation()
    }

    private fun hide() {
        phase = Phase.Idle
        visibility = INVISIBLE
    }

    override fun onDetachedFromWindow() {
        phase = Phase.Idle
        visibility = INVISIBLE
        super.onDetachedFromWindow()
    }

    private fun style() {
        val t = theme
        if (styledFor === t) return
        styledFor = t
        text.style(t, Role.Label, t.palette.text)
        val w = text.measureText(label)
        if (w != labelWidth) {
            labelWidth = w
            requestLayout()
        }
    }

    private fun plateHeight() = dp(8f) * 2 + (text.descent() - text.ascent())

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        style()
        val w = maxOf(dp(RING_DP + 2 * PAD_DP), Math.ceil((labelWidth + 2 * dp(12f)).toDouble()).toInt())
        val h = dp(RING_DP + 2 * PAD_DP) + dp(GAP_DP) + Math.ceil(plateHeight().toDouble()).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        if (phase == Phase.Idle) return
        val now = SystemClock.uptimeMillis()
        val alpha: Float
        val progress: Float
        when (phase) {
            Phase.Holding -> {
                alpha = if (Animation.animates) HoldIndicatorMath.fadeIn(now - startedAt) else 1f
                progress = StatusDotHold.progress(StatusDotHold.elapsed(now, startedAt), holdMs)
            }
            else -> {
                val since = now - endedAt
                val ms = if (phase == Phase.Completed) HoldIndicatorMath.COMPLETE_MS else HoldIndicatorMath.CANCEL_MS
                alpha = HoldIndicatorMath.fadeOut(endAlpha, since, ms)
                progress = endProgress
                if (alpha <= 0f) {
                    hide()
                    return
                }
            }
        }
        style()
        val t = theme
        val p = t.palette
        val cx = width / 2f
        val cy = dp(PAD_DP + RING_DP / 2f)
        // The disc, with a hairline outline instead of a shadow (a shadow layer would force a software layer).
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(p.surfaceRaised, alpha)
        canvas.drawCircle(cx, cy, dp(RING_DP / 2f + PAD_DP), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = withAlpha(p.line, alpha)
        canvas.drawCircle(cx, cy, dp(RING_DP / 2f + PAD_DP) - dp(0.5f), paint)
        // The ring: a faint track, then the fill clockwise from the top.
        val r = dp(RING_DP / 2f) - dp(STROKE_DP) / 2f
        arc.set(cx - r, cy - r, cx + r, cy + r)
        paint.strokeWidth = dp(STROKE_DP.toFloat())
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = withAlpha(p.line, alpha)
        canvas.drawArc(arc, 0f, 360f, false, paint)
        if (progress > 0f) {
            paint.color = withAlpha(p.accent, alpha)
            canvas.drawArc(arc, -90f, 360f * progress, false, paint)
        }
        // The label on a small raised pill, so it reads over any page.
        val top = dp(RING_DP + 2 * PAD_DP) + dp(GAP_DP).toFloat()
        plate.set(0f, top, width.toFloat(), top + plateHeight())
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(p.surfaceRaised, alpha)
        canvas.drawRoundRect(plate, plate.height() / 2f, plate.height() / 2f, paint)
        text.color = withAlpha(p.text, alpha)
        canvas.drawTextCentered(label, cx, plate.centerY(), text, Paint.Align.CENTER)
        postInvalidateOnAnimation()
    }

    companion object {
        private const val RING_DP = 64
        private const val STROKE_DP = 4
        private const val PAD_DP = 8
        private const val GAP_DP = 8

        /** Adds an indicator, centred over the page and above everything, to [parent] (an activity's content). */
        fun attach(parent: FrameLayout, indicator: HoldIndicator) {
            (indicator.parent as? android.view.ViewGroup)?.removeView(indicator)
            parent.addView(indicator, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
    }
}

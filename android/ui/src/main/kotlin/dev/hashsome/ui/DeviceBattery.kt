package dev.hashsome.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.BatteryManager
import android.view.View
import dev.hashsome.client.Store
import dev.hashsome.headless.BatteryStatus
import dev.hashsome.headless.HealthLevel

/**
 * The battery of the device, for a display that runs on one (a tablet). It is off until [enable] is called, so a wall display that is wired in and
 * reports a battery of its own (the Shelly displays say 100% or 50%) shows none.
 */
object DeviceBattery {
    /** The battery, or null while it is not followed or the device has none. */
    val state: Store<BatteryStatus?> = Store(null)

    private var receiver: BroadcastReceiver? = null
    private var appContext: Context? = null

    /** Starts following the battery (main thread). */
    fun enable(context: Context) {
        if (receiver != null) return
        val app = context.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = publish(intent)
        }
        receiver = r
        appContext = app
        // The battery's last report is sticky: it comes back from the call.
        app.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::publish)
    }

    /** Stops following it and forgets it. */
    fun disable() {
        receiver?.let { appContext?.unregisterReceiver(it) }
        receiver = null
        appContext = null
        state.set(null)
    }

    private fun publish(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0 || !intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) {
            state.set(null)
            return
        }
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL || intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        state.set(BatteryStatus(Math.round(level * 100f / scale), charging))
    }
}

/**
 * A battery drawn as one: an outline with a nub, filled to the level in red (20% and under), warm (under 50%) or green (from 50%), and a bolt over it
 * while it charges.
 */
internal class BatteryGlyphView(context: Context) : View(context) {
    var status: BatteryStatus? = null
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val bolt = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(BODY_W + NUB_W), dp(BODY_H))
    }

    override fun onDraw(canvas: Canvas) {
        val now = status ?: return
        val p = theme.palette
        val w = dp(BODY_W.toFloat())
        val h = height.toFloat()
        val stroke = dp(1.5f)
        val corner = dp(3f)
        val tone = when (now.level) {
            HealthLevel.Down -> p.danger
            HealthLevel.Pending -> p.warm
            HealthLevel.Ok -> p.success
        }
        // The outline, and the nub at its end.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = p.textMuted
        rect.set(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2)
        canvas.drawRoundRect(rect, corner, corner, paint)
        paint.style = Paint.Style.FILL
        rect.set(w + dp(1f), h * 0.32f, w + dp(NUB_W.toFloat()), h * 0.68f)
        canvas.drawRoundRect(rect, dp(1f), dp(1f), paint)
        // The level, inside it.
        val inset = stroke + dp(1.5f)
        val room = w - 2 * inset
        val fill = Math.max(room * now.percent.coerceIn(0, 100) / 100f, if (now.percent > 0) dp(2f) else 0f)
        paint.color = tone
        rect.set(inset, inset, inset + fill, h - inset)
        canvas.drawRoundRect(rect, dp(1.5f), dp(1.5f), paint)
        if (now.charging) {
            // A bolt in the middle, in the text color so it shows over the fill and over the empty part.
            val cx = w / 2
            val cy = h / 2
            val u = h / 12f
            bolt.reset()
            bolt.moveTo(cx + 0.6f * u, cy - 4.2f * u)
            bolt.lineTo(cx - 2.2f * u, cy + 0.6f * u)
            bolt.lineTo(cx - 0.2f * u, cy + 0.6f * u)
            bolt.lineTo(cx - 0.6f * u, cy + 4.2f * u)
            bolt.lineTo(cx + 2.2f * u, cy - 0.6f * u)
            bolt.lineTo(cx + 0.2f * u, cy - 0.6f * u)
            bolt.close()
            paint.color = p.text
            canvas.drawPath(bolt, paint)
        }
    }

    private companion object {
        const val BODY_W = 24
        const val BODY_H = 13
        const val NUB_W = 3
    }
}

/**
 * The device's battery before the clock in the top row, all the time: a battery with its level in color (red at 20% and under, warm under 50%, green from 50%),
 * a bolt over it while it charges, and the percentage beside it. Not there on a display with no battery to report: it takes no room.
 */
open class BatteryIndicator(context: Context, private val battery: Store<BatteryStatus?> = DeviceBattery.state) : RowLayout(context) {
    private val glyph = BatteryGlyphView(context)
    private val figure = MarqueeText(context).also { it.role = Role.Label }
    private var cancel: dev.hashsome.client.Cancel? = null

    init {
        gap = 1
        align = Align.Center
        val wrap = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        addView(glyph, FlexParams(wrap, wrap))
        addView(figure, FlexParams(wrap, wrap))
        visibility = GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        cancel = battery.listenNow { render(it) }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancel?.invoke()
        cancel = null
    }

    private fun render(now: BatteryStatus?) {
        if (now == null) {
            visibility = GONE
            return
        }
        glyph.status = now
        figure.text = now.text
        figure.color = theme.palette.textMuted
        contentDescription = "Battery ${now.label}"
        visibility = VISIBLE
    }
}

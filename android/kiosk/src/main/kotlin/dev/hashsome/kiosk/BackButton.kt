package dev.hashsome.kiosk

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import dev.hashsome.ui.PillButton
import dev.hashsome.ui.Role
import dev.hashsome.ui.UiIcons

/** The arithmetic of the floating "Back to dashboard" button, on plain numbers. Positions are the window's top-left, in pixels. */
internal object BackButtonMath {
    /** [v] kept inside `0..max` (a button wider than the screen sits at 0). */
    fun clamp(v: Int, max: Int): Int = if (max <= 0) 0 else v.coerceIn(0, max)

    /** Whether a finger that went [dx], [dy] from where it came down is dragging rather than tapping: past [slop] in distance. */
    fun isDrag(dx: Float, dy: Float, slop: Float): Boolean = dx * dx + dy * dy > slop * slop

    /** A fraction of the room the button can move in (screen minus button) to pixels, inside the screen. */
    fun toPx(fraction: Float, room: Int): Int = clamp(Math.round(fraction.coerceIn(0f, 1f) * maxOf(room, 0)), room)

    /** Pixels back to a fraction of [room]; 0 when there is no room. */
    fun toFraction(px: Int, room: Int): Float = if (room <= 0) 0f else (px.toFloat() / room).coerceIn(0f, 1f)

    /** Where a button never moved sits: the bottom-right corner, [insetPx] from both edges. */
    fun defaultFraction(room: Int, insetPx: Int): Float = if (room <= 0) 0f else toFraction(room - insetPx, room)
}

/**
 * The window that hosts the button. A tap goes to the button; a finger that moves past the touch slop drags the whole window instead
 * (the button then gets a cancel), and [onMoved] is told where the window stands when the finger lifts.
 */
@SuppressLint("ViewConstructor")
internal class DraggableHost(
    context: Context,
    private val params: WindowManager.LayoutParams,
    private val move: (x: Int, y: Int) -> Unit,
    private val onMoved: (x: Int, y: Int) -> Unit,
) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startX = params.x
                startY = params.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE ->
                if (!dragging && BackButtonMath.isDrag(event.rawX - downX, event.rawY - downY, slop)) dragging = true
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (dragging) move(startX + (event.rawX - downX).toInt(), startY + (event.rawY - downY).toInt())
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                onMoved(params.x, params.y)
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

/**
 * The button drawn over the stock Shelly app while keep-in-front is paused: it ends the pause and brings the dashboard back.
 * [sync] is called (from any thread) whenever the pause may have started or ended; the window itself is only touched on the main thread.
 */
internal class BackButtonOverlay(
    private val context: Context,
    private val settings: Settings,
    private val pause: FrontPause,
) {
    private val main = Handler(Looper.getMainLooper())
    private val windows = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var host: DraggableHost? = null

    fun sync() {
        main.post { if (pause.withButton && pause.isOn) show() else hide() }
    }

    fun close() {
        main.post { hide() }
    }

    @Suppress("DEPRECATION")
    private fun show() {
        if (host != null) return
        val dm = DisplayMetrics()
        windows.defaultDisplay.getRealMetrics(dm)
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val density = context.resources.displayMetrics.density
        lateinit var view: DraggableHost
        fun room() = (dm.widthPixels - view.measuredWidth) to (dm.heightPixels - view.measuredHeight)
        val button = PillButton(context, UiIcons.grip, "Back to dashboard", Role.Label).also {
            it.primary = true
            it.heightDp = 48
            it.padUnits = 4.5f
            it.contentDescription = "Back to dashboard"
            it.onClick = { goBack() }
        }
        view = DraggableHost(
            context, params,
            move = { x, y ->
                val (rx, ry) = room()
                params.x = BackButtonMath.clamp(x, rx)
                params.y = BackButtonMath.clamp(y, ry)
                update(view, params)
            },
            onMoved = { x, y ->
                val (rx, ry) = room()
                settings.backButtonX.set(BackButtonMath.toFraction(x, rx))
                settings.backButtonY.set(BackButtonMath.toFraction(y, ry))
            },
        )
        view.addView(button, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        view.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.AT_MOST))
        val (rx, ry) = room()
        val inset = Math.round(INSET_DP * density)
        params.x = BackButtonMath.toPx(settings.backButtonX.value ?: BackButtonMath.defaultFraction(rx, inset), rx)
        params.y = BackButtonMath.toPx(settings.backButtonY.value ?: BackButtonMath.defaultFraction(ry, inset), ry)
        try {
            windows.addView(view, params)
            host = view
            Log.i("front", "back button shown at ${params.x},${params.y}")
        } catch (e: Exception) {
            Log.w("front", "could not show the back button: ${e.message}")
        }
    }

    private fun update(view: View, params: WindowManager.LayoutParams) {
        try {
            windows.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.w("front", "could not move the back button: ${e.message}")
        }
    }

    private fun hide() {
        val view = host ?: return
        host = null
        try {
            windows.removeView(view)
        } catch (e: Exception) {
            Log.w("front", "could not remove the back button: ${e.message}")
        }
    }

    private fun goBack() {
        pause.end("the back button was tapped")
        try {
            context.startActivity(dashboardIntent(context, Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: Exception) {
            Log.w("front", "could not bring the dashboard back: ${e.message}")
        }
    }

    private companion object {
        const val INSET_DP = 12f
    }
}

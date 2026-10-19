package dev.hashsome.kiosk

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference

/**
 * The module grid over the page (`Settings.debug.grid`, the native counterpart of the web's `?grid`): a faint line at every
 * module (4 dp), a stronger one at every tile pitch (three modules), and a label with the viewport and the screen, for laying a
 * dashboard out for one device. It never takes a touch, and follows the setting live.
 */
internal object GridOverlay {
    private var activity: WeakReference<Activity>? = null
    private var view: View? = null
    private var wanted = false

    /** Installed once, with the extension: the setting turns it on and off whichever activity is showing. */
    fun install() {
        Settings.debug.grid.listenNow {
            wanted = it
            apply()
        }
    }

    fun attach(a: Activity) {
        if (activity?.get() !== a) remove()
        activity = WeakReference(a)
        apply()
    }

    private fun remove() {
        view?.let { (it.parent as? ViewGroup)?.removeView(it) }
        view = null
    }

    private fun apply() {
        val decor = activity?.get()?.window?.decorView as? ViewGroup ?: return
        if (!wanted) return remove()
        if (view?.parent != null) return
        remove()
        val overlay = GridView(decor.context)
        decor.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view = overlay
    }

    /** Never consumes a touch: a plain [View] that is not clickable lets them fall through to what is under it. */
    private class GridView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val module = 4 * density
        private val faint = Paint().apply { color = Color.argb(40, 255, 0, 80); strokeWidth = 1f }
        private val strong = Paint().apply { color = Color.argb(110, 255, 0, 80); strokeWidth = 1f }
        private val label = Paint().apply { color = Color.WHITE; textSize = 12 * density; isAntiAlias = true }
        private val labelBackground = Paint().apply { color = Color.argb(170, 0, 0, 0) }

        init {
            isClickable = false
            isFocusable = false
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            var i = 0
            var x = 0f
            while (x <= w) {
                canvas.drawLine(x, 0f, x, h, if (i % 3 == 0) strong else faint)
                x += module
                i++
            }
            i = 0
            var y = 0f
            while (y <= h) {
                canvas.drawLine(0f, y, w, y, if (i % 3 == 0) strong else faint)
                y += module
                i++
            }
            val metrics = resources.displayMetrics
            val text = "viewport ${(w / density).toInt()}x${(h / density).toInt()} dp - screen ${metrics.widthPixels}x${metrics.heightPixels} px @${density}"
            val pad = 4 * density
            canvas.drawRect(0f, 0f, label.measureText(text) + 2 * pad, label.textSize + 2 * pad, labelBackground)
            canvas.drawText(text, pad, pad + label.textSize * 0.85f, label)
        }
    }
}

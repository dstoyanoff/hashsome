package dev.hashsome.ui

import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

/**
 * Temporary diagnostics for the touches a drawer or sheet gets (tag `hashsome-touch`, `Log.e` so a display's log shows it): every event
 * with the view that is under it, what the pull-to-dismiss and the close button make of it, and one summary line per gesture. Off with
 * [enabled] (`kiosk`'s `Settings.debug.touch` binds to it; `ui` only has the flag). Nothing is built while it is off.
 */
object TouchDebug {
    /** Off; the kiosk's `Settings.debug.touch` (a switch in the settings sheet's Debug group) turns it on. */
    @JvmField
    var enabled: Boolean = false

    internal const val TAG = "hashsome-touch"

    internal inline fun log(message: () -> String) {
        if (enabled) Log.e(TAG, message())
    }

    /** What a gesture came to, from how far it moved and what took it. */
    fun classify(movedDp: Float, tapped: Boolean, pulled: Boolean, downOnScroll: Boolean): String = when {
        tapped -> "tap"
        pulled -> "pull"
        downOnScroll && movedDp > 8f -> "scroll"
        else -> "ignored"
    }

    /** What a view is called in the log: its tag when it has one, else its class. */
    fun name(view: View?): String = when {
        view == null -> "none"
        view.tag is String -> view.tag as String
        else -> view.javaClass.simpleName.ifEmpty { "view" }
    }

    /** The deepest visible view of [root] under (x, y) in [root]'s own coordinates; [root] itself when no child is. */
    fun viewAt(root: View, x: Float, y: Float): View {
        var view = root
        var px = x
        var py = y
        while (view is ViewGroup) {
            var next: View? = null
            for (i in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(i)
                if (child.visibility != View.VISIBLE) continue
                val cx = px + view.scrollX - child.left - child.translationX
                val cy = py + view.scrollY - child.top - child.translationY
                if (cx >= 0 && cy >= 0 && cx < child.width && cy < child.height) {
                    next = child
                    px = cx
                    py = cy
                    break
                }
            }
            view = next ?: break
        }
        return view
    }

    // One gesture at a time: where it began, and what took it.
    private var downName = ""
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var downOnScroll = false
    private var tapped = false
    private var pulled = false

    fun began(event: MotionEvent, over: View) {
        downName = name(over)
        downX = event.rawX
        downY = event.rawY
        downAt = event.eventTime
        downOnScroll = downName == "scroll body"
        tapped = false
        pulled = false
    }

    fun tapped() {
        tapped = true
    }

    fun pulled() {
        pulled = true
    }

    fun ended(event: MotionEvent, over: View) {
        val moved = Math.hypot((event.rawX - downX).toDouble(), (event.rawY - downY).toDouble()).toFloat() / screenDensity
        log {
            "gesture: DOWN on $downName at (${fmt(downX / screenDensity)}, ${fmt(downY / screenDensity)}) -> ${MotionEvent.actionToString(event.actionMasked)} on ${name(over)} at " +
                "(${fmt(event.rawX / screenDensity)}, ${fmt(event.rawY / screenDensity)}) after ${event.eventTime - downAt} ms, moved ${fmt(moved)} dp, result: ${classify(moved, tapped, pulled, downOnScroll)}"
        }
    }

    fun fmt(v: Float): String = (Math.round(v * 10) / 10f).toString()
}

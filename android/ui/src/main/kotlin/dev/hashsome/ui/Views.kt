package dev.hashsome.ui

import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.hashsome.client.Cancel
import dev.hashsome.client.Store

// The base views every component is made of. All are `open`: a consumer can subclass one, or write its own view against
// a headless control. All colors, radii and sizes are read from `Hashsome.theme` at draw time.

/**
 * The base of every component view: it listens to the stores it binds while it is attached to a window and lets go of
 * them when it is detached, so a view never keeps a screen alive.
 */
abstract class BoundView(context: Context) : FrameLayout(context) {
    /** Calls [fn] with [store]'s value now and on each change, until this view is detached. Call it from `onAttachedToWindow`. */
    protected fun <T> bind(store: Store<T>, fn: (T) -> Unit): Unit = TODO("WP3")

    /** Registers [cancel] to run when this view is detached. */
    protected fun onDetach(cancel: Cancel): Unit = TODO("WP3")
}

/** A rounded surface that is the body of every tile: [filled] fills it with the accent. */
open class TileView(context: Context) : ViewGroup(context) {
    var filled: Boolean = false
    var disabled: Boolean = false

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int): Unit = TODO("WP3")
    override fun onDraw(canvas: Canvas): Unit = TODO("WP3")
}

/** A round icon button. */
open class RoundButton(context: Context, var icon: Icon) : View(context) {
    var onClick: (() -> Unit)? = null
    var active: Boolean = false
    var pending: Boolean = false
    var failed: Boolean = false

    override fun onDraw(canvas: Canvas): Unit = TODO("WP3")
}

/** A circular progress or level ring. */
open class RingView(context: Context) : View(context) {
    /** 0..1. */
    var fraction: Float = 0f

    override fun onDraw(canvas: Canvas): Unit = TODO("WP3")
}

/** A horizontal bar for a level, a seek position or a volume; draggable when [onDrag] is set. */
open class BarView(context: Context) : View(context) {
    /** 0..1. */
    var fraction: Float = 0f
    var onDrag: ((Float) -> Unit)? = null
    var onCommit: ((Float) -> Unit)? = null

    override fun onDraw(canvas: Canvas): Unit = TODO("WP3")
}

/** A small dot: green when [ok], red otherwise. The settings gesture listens for taps on it. */
open class StatusDot(context: Context) : View(context) {
    var ok: Boolean = true

    override fun onDraw(canvas: Canvas): Unit = TODO("WP3")
}

/** A row of selectable chips. */
open class ChipsView(context: Context) : View(context) {
    var labels: List<String> = emptyList()
    var current: Int = 0
    var onSelect: ((Int) -> Unit)? = null

    override fun onDraw(canvas: Canvas): Unit = TODO("WP3")
}

/** One row of a library list: artwork, title, subtitle, and the actions the item allows. */
open class BrowseRow(context: Context) : View(context) {
    override fun onDraw(canvas: Canvas): Unit = TODO("WP3")
}

/** One row of a queue: artwork, title, artist, a marker on the current track. */
open class QueueRow(context: Context) : View(context) {
    override fun onDraw(canvas: Canvas): Unit = TODO("WP3")
}

/** One line of text that scrolls sideways when it does not fit. */
open class MarqueeText(context: Context) : View(context) {
    var text: CharSequence = ""
    var role: Role = Role.Body

    override fun onDraw(canvas: Canvas): Unit = TODO("WP3")
}

/** A placeholder block that pulses while content loads. */
open class Skeleton(context: Context) : View(context) {
    override fun onDraw(canvas: Canvas): Unit = TODO("WP3")
}

/** A ring that fills while a press is held (a task confirmed by holding, a scene that needs intent). */
open class HoldProgress(context: Context) : View(context) {
    /** How long the press must be held, in ms. */
    var holdMs: Long = 600
    var onComplete: (() -> Unit)? = null

    override fun onDraw(canvas: Canvas): Unit = TODO("WP3")
}

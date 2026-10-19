package dev.hashsome.ui

import android.content.Context
import android.view.View

/** A panel shown over a page: [Drawer] from the side on wide screens, [Sheet] from the bottom on small ones. */
abstract class Overlay(context: Context) : BoundView(context) {
    /** Shows [content] over the page with a scrim behind it. */
    abstract fun show(content: View)

    /** Closes it, animated. */
    abstract fun dismiss()

    var onDismissed: (() -> Unit)? = null
}

/** A panel that slides in from the right. */
open class Drawer(context: Context) : Overlay(context) {
    override fun show(content: View): Unit = TODO("WP3")
    override fun dismiss(): Unit = TODO("WP3")
}

/** A panel that rises from the bottom, with a grip, and is dismissed by pulling it down. */
open class Sheet(context: Context) : Overlay(context) {
    override fun show(content: View): Unit = TODO("WP3")
    override fun dismiss(): Unit = TODO("WP3")
}

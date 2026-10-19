package dev.hashsome.ui

import android.content.Context
import dev.hashsome.headless.MediaBrowserControl
import dev.hashsome.headless.MediaPlayerControl
import dev.hashsome.headless.QueueControl
import dev.hashsome.headless.SpeakersControl

// The media components, each a thin view over its headless control.

/** Which optional parts a player view shows. */
data class PlayerOptions(
    val time: Boolean = true,
    val shuffle: Boolean = true,
    val browse: Boolean = true,
    val grouping: Boolean = true,
    /** Rows of controls: 1 is the compact bar. */
    val rows: Int = 2,
)

/** A compact player: art, title, transport keys. [onOpen] runs on a tap on the body (to go to the player's page). */
open class MediaPlayerBarView(context: Context, val control: MediaPlayerControl, val label: String, val options: PlayerOptions = PlayerOptions()) :
    BoundView(context) {
    var onOpen: (() -> Unit)? = null
}

/** A tall player for a side column: large art above the controls; its library, queue and speakers open as overlays when [overlays] is set. */
open class MediaPlayerColumnView(
    context: Context,
    val control: MediaPlayerControl,
    val label: String,
    val overlays: Boolean = true,
    val fill: Boolean = true,
) : BoundView(context)

/** The full-screen player. */
open class MediaPlayerFullView(context: Context, val control: MediaPlayerControl, val label: String) : BoundView(context)

/** A player's library: tabs, levels, search, and play actions. */
open class MediaBrowserView(context: Context, val control: MediaBrowserControl) : BoundView(context)

/** A player's queue. */
open class MediaQueueView(context: Context, val control: QueueControl) : BoundView(context)

/** The speakers that can join a player. */
open class MediaSpeakersView(context: Context, val control: SpeakersControl) : BoundView(context)

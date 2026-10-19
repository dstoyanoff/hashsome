package dev.hashsome.headless

import dev.hashsome.client.MediaPlayerEntity
import dev.hashsome.client.Store
import org.json.JSONObject

/** What a transport key (play, next, shuffle...) is doing after a press: waiting for the runtime, or failed. */
sealed interface Feedback {
    /** Sent, no answer yet; shown for up to 4 s. */
    data object Pending : Feedback

    /** The command failed; shown for 700 ms. */
    data object Error : Feedback
}

/** The volume slider and mute button (mirrors `use-volume-control`). */
class VolumeControl {
    /** The level the player reports, 0..1, or null when it has no volume. */
    val level: Store<Double?> = Store(null)

    /** 0..1: what the slider shows, the drag position while dragging and [level] otherwise. */
    val shown: Store<Double> = Store(0.0)

    val muted: Store<Boolean> = Store(false)

    fun drag(level: Double): Unit = TODO("WP2")

    /** The finger lifted at [level]: sends it. */
    fun commit(level: Double): Unit = TODO("WP2")

    /** Mutes, or unmutes; unmuting a player whose level is 0 sets [UNMUTE_FROM_ZERO] instead of staying silent. */
    fun toggleMute(): Unit = TODO("WP2")

    companion object {
        const val UNMUTE_FROM_ZERO = 0.15
    }
}

/** The seek bar: after a seek the new position is held until the player reports a position that moved (mirrors `use-seek`). */
class SeekControl {
    /** Seconds the bar shows, or null when there is nothing playing. */
    val shown: Store<Double?> = Store(null)

    fun drag(seconds: Double): Unit = TODO("WP2")
    fun commit(seconds: Double): Unit = TODO("WP2")
}

/** The state and commands of a media player's controls (mirrors `media-controls`, `seek`, `progress` and `pending` on the web). */
class MediaPlayerControl(val handle: EntityHandle<MediaPlayerEntity>, val scope: Scope) {
    /** Ticks (with the elapsed time) once a second while the player is playing, so a progress bar redraws without any message from the runtime. */
    val changed: Store<Long> = Store(0L)

    val volume: VolumeControl = VolumeControl()
    val seek: SeekControl = SeekControl()

    /** Where playback is now in seconds, interpolated from the last report while playing. */
    fun position(): Double? = TODO("WP2")

    /** [position] as a fraction of the duration, 0..1; null for a stream with no end. */
    fun fraction(): Double? = TODO("WP2")

    /** Sends [command] on behalf of the key named [key], and tracks it for [feedbackFor]. */
    fun press(key: String, command: String, args: JSONObject? = null): Unit = TODO("WP2")

    /** What the key named [key] should show: pending for up to 4 s, error for 700 ms, otherwise null. */
    fun feedbackFor(key: String): Feedback? = TODO("WP2")
}

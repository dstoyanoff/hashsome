package dev.hashsome.headless

import dev.hashsome.client.Cancel
import dev.hashsome.client.Command
import dev.hashsome.client.MediaPlayerCommands
import dev.hashsome.client.MediaPlayerEntity
import dev.hashsome.client.PlaybackState
import dev.hashsome.client.QueueResult
import dev.hashsome.client.Store
import org.json.JSONObject

/** What a transport key (play, next, shuffle...) is doing after a press: waiting for the runtime, or failed. */
sealed interface Feedback {
    /** Sent, no answer yet; shown for up to 4 s. */
    data object Pending : Feedback

    /** The command failed; shown for 700 ms. */
    data object Error : Feedback
}

private fun percent(level: Double): Int = Math.floor(level * 100 + 0.5).toInt()

/** The volume slider and mute button (mirrors `use-volume-control`). */
class VolumeControl {
    /** The level the player reports, 0..1, or null when it has no volume. */
    val level: Store<Double?> = Store(null)

    /** 0..1: what the slider shows, the drag position while dragging and [level] otherwise. */
    val shown: Store<Double> = Store(0.0)

    val muted: Store<Boolean> = Store(false)

    private var handle: EntityHandle<MediaPlayerEntity>? = null

    // The level a finger has set and the player has not reported yet; kept after the finger lifts so the slider does not snap back.
    private var held: Double? = null

    internal fun attach(handle: EntityHandle<MediaPlayerEntity>) {
        this.handle = handle
        handle.state.listenNow { refresh() }
    }

    private fun refresh() {
        val player = handle?.entity
        val reported = player?.volume
        level.set(reported)
        muted.set(player?.muted == true)
        val pending = held
        if (pending != null && percent(pending) == percent(reported ?: 0.0)) held = null
        shown.set(held ?: reported ?: 0.0)
    }

    fun drag(level: Double) {
        held = level.coerceIn(0.0, 1.0)
        refresh()
    }

    /** The finger lifted at [level]: sends it. */
    fun commit(level: Double) {
        val next = level.coerceIn(0.0, 1.0)
        held = next
        refresh()
        val target = handle ?: return
        target.command(MediaPlayerCommands.setVolume(next))
        // Zero is mute and anything above it is audible, so the commit can mute or unmute as well.
        val isMuted = muted.value
        if ((percent(next) == 0) != isMuted) target.command(MediaPlayerCommands.setMuted(!isMuted))
    }

    /** Mutes, or unmutes; unmuting a player whose level is 0 sets [UNMUTE_FROM_ZERO] instead of staying silent. */
    fun toggleMute() {
        val target = handle ?: return
        val isMuted = muted.value
        if (isMuted && percent(level.value ?: 0.0) == 0) target.command(MediaPlayerCommands.setVolume(UNMUTE_FROM_ZERO))
        target.command(MediaPlayerCommands.setMuted(!isMuted))
    }

    companion object {
        const val UNMUTE_FROM_ZERO = 0.15
    }
}

/**
 * The seek bar: after a seek the new position is held until the player reports a position that moved (mirrors `use-seek`).
 * A touch whose end never came is let go after [IDLE_MS], so the bar cannot stay stuck.
 */
class SeekControl {
    /** Seconds the bar shows, or null when there is nothing playing. */
    val shown: Store<Double?> = Store(null)

    private var owner: MediaPlayerControl? = null
    private var held: Double? = null

    // What the player had reported when the seek was sent; a report with another position is the player answering it
    // (the same position again, stamped later, is only a periodic report).
    private var reportedThen: Pair<Double?, String?>? = null
    private var idle: Cancel? = null

    internal fun attach(owner: MediaPlayerControl) {
        this.owner = owner
        refresh()
    }

    fun drag(seconds: Double) {
        held = seconds
        reportedThen = null
        restartIdle()
        refresh()
    }

    fun commit(seconds: Double) {
        val control = owner ?: return
        held = seconds
        reportedThen = control.reported()
        restartIdle()
        refresh()
        control.handle.command(MediaPlayerCommands.seek(seconds))
    }

    /** Ends a drag without seeking (a touch that was cancelled or lost). */
    fun cancel() {
        release()
    }

    internal fun refresh() {
        val control = owner
        val then = reportedThen
        if (held != null && then != null && control != null && control.reported().first != then.first) {
            release()
            return
        }

        shown.set(held ?: control?.position())
    }

    private fun release() {
        held = null
        reportedThen = null
        idle?.invoke()
        idle = null
        shown.set(owner?.position())
    }

    private fun restartIdle() {
        idle?.invoke()
        idle = owner?.scope?.after(IDLE_MS) {
            idle = null
            release()
        }
    }

    companion object {
        /**
         * How long a drag or a seek may go without the player moving before it is given up on. Diverges from the web
         * (`media-seek.ts`: a seek is released after 1500 ms): here it is held until the player reports a moved position.
         */
        const val IDLE_MS = 8_000L
    }
}

/** What the transport keys can do now (`transportAvailability` on the web). */
data class TransportAvailability(val next: Boolean, val previous: Boolean, val play: Boolean)

/** Past this far into a track, Previous restarts it, so it has somewhere to go even on the first one. */
private const val RESTART_AFTER_S = 3.0

/**
 * What the transport buttons can do now, from the queue: Next when something comes after the track playing, Previous
 * when something comes before it (or the track is far enough in to restart), Play when there is something loaded or
 * queued. Without a queue to read (a player with none, or before it answers) nothing is held back: a button is only
 * disabled for what is known.
 */
fun transportAvailability(queue: QueueResult?, player: MediaPlayerEntity?, position: Double?): TransportAvailability {
    val current = queue?.items?.indexOfFirst { it.current } ?: -1
    val known = queue != null && current >= 0
    val index = if (known) queue!!.offset + current else 0
    return TransportAvailability(
        next = !known || index + 1 < queue!!.total,
        previous = !known || index > 0 || (position ?: 0.0) >= RESTART_AFTER_S,
        play = queue == null || queue.total > 0 || player?.media?.title != null || player?.playback == PlaybackState.Playing,
    )
}

/** The state and commands of a media player's controls (mirrors `media-controls`, `seek`, `progress` and `pending` on the web). */
class MediaPlayerControl(val handle: EntityHandle<MediaPlayerEntity>, val scope: Scope) {
    /** Ticks (with the elapsed time) once a second while the player is playing, so a progress bar redraws without any message from the runtime. */
    val changed: Store<Long> = Store(0L)

    val volume: VolumeControl = VolumeControl()
    val seek: SeekControl = SeekControl()

    /**
     * Whether to show the player as playing: its playback, except that a player which drops out of playing and back
     * within a moment as a new track starts does not make the play button change and change back (`useSteadyPlaying`).
     */
    val playing: Store<Boolean> = Store(false)

    /** The presses that are waiting for the player or failed, by key; changes whenever [feedbackFor] would answer differently. */
    val feedback: Store<Map<String, Feedback>> get() = presses.feedback

    private val presses = PressTracker.of(scope, handle)
    private var ticker: Cancel? = null

    // For `playing`: the window after a track change in which a dip out of playing is held up, and the dip being held.
    private var track: String? = null
    private var trackSeen = false
    private var recent = false
    private var recentTimer: Cancel? = null
    private var rawPlaying = false
    private var held = false
    private var heldTimer: Cancel? = null

    init {
        volume.attach(handle)
        seek.attach(this)
        scope.bind(handle.state) { update() }
    }

    /** Where playback is now in seconds, interpolated from the last report while playing. */
    fun position(): Double? {
        val player = handle.entity ?: return null
        val reported = player.position ?: return null
        val since = IsoTime.parse(player.positionUpdatedAt)
        val elapsed = if (player.playback == PlaybackState.Playing && since != null) {
            Math.max(0.0, (scope.scheduler.wallMs() - since) / 1000.0)
        } else {
            0.0
        }

        val now = reported + elapsed
        return player.duration?.let { Math.min(it, now) } ?: now
    }

    /** [position] as a fraction of the duration, 0..1; null for a stream with no end. */
    fun fraction(): Double? {
        val duration = handle.entity?.duration
        val at = position()
        if (duration == null || duration <= 0.0 || at == null) return null
        return (at / duration).coerceIn(0.0, 1.0)
    }

    /** What the seek bar shows: the spot being sought to while a seek is held, else [position]. */
    fun shownPosition(): Double? = seek.shown.value

    /** Sends [command] on behalf of the key named [key], and tracks it for [feedbackFor]. */
    fun press(key: String, command: String, args: JSONObject? = null) {
        presses.press(key) { done -> handle.command(command, args, done) }
    }

    /** [press] with a command from [MediaPlayerCommands]. */
    fun press(key: String, command: Command) {
        press(key, command.name, command.args)
    }

    /** What the key named [key] should show: pending for up to 4 s, error for 700 ms, otherwise null. */
    fun feedbackFor(key: String): Feedback? = presses.feedbackFor(key)

    /** What the player last reported of its position, to tell a report that moved from one that did not. */
    internal fun reported(): Pair<Double?, String?> {
        val player = handle.entity
        return player?.position to player?.positionUpdatedAt
    }

    private fun update() {
        val player = handle.entity
        val isPlaying = player?.playback == PlaybackState.Playing
        if (isPlaying && ticker == null) {
            ticker = scope.every(TICK_MS) {
                changed.set(scope.scheduler.now())
                seek.refresh()
            }
        } else if (!isPlaying) {
            ticker?.invoke()
            ticker = null
        }

        steady(player, isPlaying)
        seek.refresh()
    }

    private fun steady(player: MediaPlayerEntity?, isPlaying: Boolean) {
        val title = player?.media?.title
        if (!trackSeen) {
            track = title
            rawPlaying = isPlaying
            trackSeen = true
        } else if (track != title) {
            track = title
            recent = true
            recentTimer?.invoke()
            recentTimer = scope.after(NEW_TRACK_MS) {
                recent = false
                recentTimer = null
            }
        }

        if (isPlaying != rawPlaying) {
            rawPlaying = isPlaying
            held = !isPlaying && recent
            heldTimer?.invoke()
            heldTimer = null
            if (held) {
                heldTimer = scope.after(DIP_MS) {
                    held = false
                    heldTimer = null
                    playing.set(rawPlaying)
                }
            }
        }

        playing.set(rawPlaying || held)
    }

    companion object {
        /** How often a playing player's progress is redrawn (the web's `useMediaPosition` interval). */
        const val TICK_MS = 500L

        /** How long after a track changes the player may dip out of playing without the button showing it. */
        const val NEW_TRACK_MS = 6_000L

        /** How long such a dip has to last to be believed. */
        const val DIP_MS = 800L
    }
}

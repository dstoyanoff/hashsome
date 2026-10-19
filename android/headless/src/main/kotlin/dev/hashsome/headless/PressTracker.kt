package dev.hashsome.headless

import dev.hashsome.client.Cancel
import dev.hashsome.client.MediaPlayerEntity
import dev.hashsome.client.Outcome
import dev.hashsome.client.PlaybackState
import dev.hashsome.client.Store

/**
 * Which presses on a player are waiting for it and which failed (`usePressFeedback` and `media-pending.ts` on the web).
 * A press is pending from the moment it is made until the player visibly changes (play or pause, shuffle, another
 * track, another group) or [PENDING_MS] have passed, and shows an error for [ERROR_MS] if the runtime refuses it.
 * A press that is made again restarts its own wait.
 */
internal class PressTracker(private val scope: Scope, handle: EntityHandle<MediaPlayerEntity>?) {
    /** The keys that are pending or have failed, by key. */
    val feedback: Store<Map<String, Feedback>> = Store(emptyMap())

    private val waiting = HashMap<String, Cancel>()
    private val failed = HashMap<String, Cancel>()

    // Which ask each key is on, so an answer to an earlier ask of the same key does not end a later one.
    private val asks = HashMap<String, Int>()
    private var seen: Seen? = null

    private data class Seen(val playback: PlaybackState, val shuffle: Boolean?, val title: String?, val group: String?)

    init {
        if (handle != null) scope.bind(handle.state) { observe(handle.entity) }
    }

    /**
     * Marks [key] pending and calls [send] with the callback the command's outcome goes to. [limitMs] is how long it
     * stays pending if the player never visibly changes.
     */
    fun press(key: String, limitMs: Long = PENDING_MS, send: ((Outcome) -> Unit) -> Unit) {
        failed.remove(key)?.invoke()
        waiting.remove(key)?.invoke()
        val ask = (asks[key] ?: 0) + 1
        asks[key] = ask
        waiting[key] = scope.after(limitMs) {
            waiting.remove(key)
            publish()
        }

        publish()
        send { outcome ->
            if (outcome is Outcome.Failed && asks[key] == ask) {
                waiting.remove(key)?.invoke()
                failed[key] = scope.after(ERROR_MS) {
                    failed.remove(key)
                    publish()
                }

                publish()
            }
        }
    }

    /** What [key] should show: pending for as long as it waits, then an error if it failed, else nothing. */
    fun feedbackFor(key: String): Feedback? = feedback.value[key]

    private fun observe(player: MediaPlayerEntity?) {
        if (player == null) return
        val now = Seen(
            player.playback,
            player.shuffle,
            player.media?.title,
            player.group?.let { "${it.leader.value}>${it.members.joinToString(",") { member -> member.value }}" },
        )
        val before = seen
        seen = now
        // What was asked of the player has taken effect once it does something else.
        if (before != null && before != now && waiting.isNotEmpty()) {
            for (cancel in waiting.values.toList()) cancel()
            waiting.clear()
            publish()
        }
    }

    private fun publish() {
        val next = LinkedHashMap<String, Feedback>()
        for (key in failed.keys) next[key] = Feedback.Error
        for (key in waiting.keys) next[key] = Feedback.Pending
        feedback.set(next)
    }

    companion object {
        /**
         * How long a press is shown as waiting at most. Diverges from the web (`media-pending.ts`: per-kind settle times, a
         * 600 ms minimum, a 60 s cap): the POC's rule, where any visible change of the player ends every wait.
         */
        const val PENDING_MS = 4_000L

        /** How long a failed press shows its error. */
        const val ERROR_MS = 700L
    }
}

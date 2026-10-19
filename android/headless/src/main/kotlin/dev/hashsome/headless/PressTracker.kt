package dev.hashsome.headless

import dev.hashsome.client.Cancel
import dev.hashsome.client.Client
import dev.hashsome.client.EntityRef
import dev.hashsome.client.MediaPlayerEntity
import dev.hashsome.client.Outcome
import dev.hashsome.client.PlaybackState
import dev.hashsome.client.Scheduler
import dev.hashsome.client.Store
import java.util.WeakHashMap

/**
 * Which presses on a player are waiting for it and which failed (`usePressFeedback` and `media-pending.ts` on the web).
 * A press is pending from the moment it is made until the player visibly changes (play or pause, shuffle, another
 * track, another group) or [PENDING_MS] have passed, and shows an error for [ERROR_MS] if the runtime refuses it.
 * A press that is made again restarts its own wait.
 *
 * One tracker is shared by everything that presses on the same player of the same client (the transport, the library,
 * the speakers), as the web shares the pending part per player: a play started from the library is pending on the
 * transport too. It keeps its own timers on the scheduler, so a screen going away does not leave a wait stuck for the
 * next one; the waits are bounded, so they end by themselves. The queue of the player is read again when a wait begins
 * and when it ends, as the web does by watching the pending requests.
 */
internal class PressTracker private constructor(
    private val client: Client,
    private val scheduler: Scheduler,
    private val ref: EntityRef?,
) {
    /** The keys that are pending or have failed, by key. */
    val feedback: Store<Map<String, Feedback>> = Store(emptyMap())

    private val waiting = HashMap<String, Cancel>()
    private val failed = HashMap<String, Cancel>()

    // Which ask each key is on, so an answer to an earlier ask of the same key does not end a later one.
    private val asks = HashMap<String, Int>()
    private var seen: Seen? = null

    private data class Seen(val playback: PlaybackState, val shuffle: Boolean?, val title: String?, val group: String?)

    /**
     * Marks [key] pending and calls [send] with the callback the command's outcome goes to. [limitMs] is how long it
     * stays pending if the player never visibly changes.
     */
    fun press(key: String, limitMs: Long = PENDING_MS, send: ((Outcome) -> Unit) -> Unit) {
        failed.remove(key)?.invoke()
        val restarted = waiting.remove(key)
        restarted?.invoke()
        val ask = (asks[key] ?: 0) + 1
        asks[key] = ask
        waiting[key] = scheduler.after(limitMs) {
            waiting.remove(key)
            publish()
            queueChanged()
        }

        publish()
        if (restarted == null) queueChanged()
        send { outcome ->
            if (outcome is Outcome.Failed && asks[key] == ask) {
                val had = waiting.remove(key)
                had?.invoke()
                failed[key] = scheduler.after(ERROR_MS) {
                    failed.remove(key)
                    publish()
                }

                publish()
                if (had != null) queueChanged()
            }
        }
    }

    /** What [key] should show: pending for as long as it waits, then an error if it failed, else nothing. */
    fun feedbackFor(key: String): Feedback? = feedback.value[key]

    /** The player as it is now; whatever was asked of it has taken effect once it does something else. */
    fun observe(player: MediaPlayerEntity?) {
        if (player == null) return
        val now = Seen(
            player.playback,
            player.shuffle,
            player.media?.title,
            player.group?.let { "${it.leader.value}>${it.members.joinToString(",") { member -> member.value }}" },
        )
        val before = seen
        seen = now
        if (before != null && before != now && waiting.isNotEmpty()) {
            for (cancel in waiting.values.toList()) cancel()
            waiting.clear()
            publish()
            queueChanged()
        }
    }

    // Asked and not in effect yet changes the queue's window without the track changing: it is read again.
    private fun queueChanged() {
        if (ref != null) QueueEpoch.bump(client, ref)
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

        private val shared = WeakHashMap<Client, HashMap<EntityRef, PressTracker>>()

        /**
         * The tracker of the player at [ref] on this scope's client, the same one for every control of that player. It
         * is fed the player's state from [handle] for as long as [scope] lives. A handle with no ref gets one of its own.
         */
        fun of(scope: Scope, handle: EntityHandle<MediaPlayerEntity>): PressTracker {
            val ref = handle.ref
            val tracker = if (ref == null) {
                PressTracker(scope.client, scope.scheduler, null)
            } else {
                shared.getOrPut(scope.client) { HashMap() }.getOrPut(ref) { PressTracker(scope.client, scope.scheduler, ref) }
            }

            scope.bind(handle.state) { tracker.observe(handle.entity) }
            return tracker
        }
    }
}

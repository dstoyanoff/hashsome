package dev.hashsome.headless

import dev.hashsome.client.Cancel
import dev.hashsome.client.Client
import dev.hashsome.client.EntityRef
import dev.hashsome.client.MediaPlayerCommands
import dev.hashsome.client.MediaPlayerEntity
import dev.hashsome.client.Outcome
import dev.hashsome.client.Query
import dev.hashsome.client.QueueItem
import dev.hashsome.client.Store
import java.util.WeakHashMap

data class QueueState(
    val items: List<QueueItem> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    /** How long the whole queue plays, in seconds, when the backend can say. */
    val duration: Double? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

/**
 * Something changed a player's queue without changing its track (an album added to it from the library, say): every
 * [QueueControl] of that player on that client reads it again. This is what the web does by watching the pending
 * requests of the player.
 */
internal object QueueEpoch {
    private val epochs = WeakHashMap<Client, HashMap<EntityRef, Store<Int>>>()

    fun of(client: Client, ref: EntityRef): Store<Int> = epochs.getOrPut(client) { HashMap() }.getOrPut(ref) { Store(0) }

    fun bump(client: Client, ref: EntityRef) {
        val epoch = of(client, ref)
        epoch.set(epoch.value + 1)
    }
}

/** A player's queue, refetched when the player's track changes or a command changes the queue (mirrors `use-media-queue.ts`). */
class QueueControl(val handle: EntityHandle<MediaPlayerEntity>, val scope: Scope) {
    val state: Store<QueueState> = Store(QueueState())

    private var has = false
    private var answered = false
    private var key: Triple<Boolean, String?, Boolean?>? = null
    private var asked = 0
    private var inFlight: Cancel? = null

    init {
        scope.bind(handle.state) { onPlayer() }
        scope.every(POLL_MS) { fetch() }
        scope.onReconnect { fetch() }
        handle.ref?.let { ref -> scope.register(QueueEpoch.of(scope.client, ref).listen { fetch() }) }
    }

    /** Reads the queue again, e.g. after something was added to it or taken out. */
    fun refresh() {
        fetch()
    }

    /** Jumps to [item] and plays it. */
    fun play(item: QueueItem) {
        run(MediaPlayerCommands.playQueueItem(item.id))
    }

    /** Takes [item] out; [done] says whether the player accepted it (a view that took the row out at once puts it back on false). */
    fun remove(item: QueueItem, done: ((Boolean) -> Unit)? = null) {
        run(MediaPlayerCommands.removeQueueItem(item.id), done)
    }

    /** Moves [item] by [shift] places: later when positive, earlier when negative. */
    fun move(item: QueueItem, shift: Int) {
        if (shift == 0) return
        run(MediaPlayerCommands.moveQueueItem(item.id, shift))
    }

    /** Empties the queue after the track playing. */
    fun clear(done: ((Boolean) -> Unit)? = null) {
        run(MediaPlayerCommands.clearQueue(), done)
    }

    // After a change the queue is read again, once the player has had a moment to apply it.
    private fun run(command: dev.hashsome.client.Command, done: ((Boolean) -> Unit)? = null) {
        handle.command(command) { outcome ->
            if (outcome is Outcome.Ok) scope.after(REFRESH_AFTER_MS) { fetch() }
            done?.invoke(outcome is Outcome.Ok)
        }
    }

    private fun onPlayer() {
        val player = handle.entity
        has = handle.ref != null && player?.capabilities?.queue == true
        val next = Triple(has, player?.media?.title, player?.shuffle)
        if (!has) {
            asked++
            inFlight?.invoke()
            inFlight = null
            answered = false
            key = next
            state.set(QueueState(loading = false))
            return
        }

        // What changes when the queue's window does: the track playing and the order it plays in.
        if (next != key) {
            key = next
            fetch()
        }
    }

    private fun fetch() {
        val ref = handle.ref
        if (!has || ref == null) return
        inFlight?.invoke()
        val mine = ++asked
        if (!answered) state.set(QueueState(loading = true))
        inFlight = scope.query(ref, Query.Queue(duration = true)) { result ->
            if (mine != asked) return@query
            inFlight = null
            answered = true
            // The queue is an extra: an error leaves nothing to show, and the next read tries again.
            state.set(
                result.fold(
                    onSuccess = { QueueState(it.items, it.total, it.offset, it.duration, loading = false) },
                    onFailure = { QueueState(loading = false, error = it.message ?: it.toString()) },
                ),
            )
        }
    }

    companion object {
        /** How often the queue is read again while it is on screen, to pick up what another app changed. */
        const val POLL_MS = 15_000L

        /** How long after a change to the queue it is read again. */
        const val REFRESH_AFTER_MS = 400L
    }
}

/**
 * What plays after the track that is playing: up to [count] items that follow the one marked current. A queue that has no current track
 * (not read yet, or it ended) offers its first items.
 */
fun QueueState.upNext(count: Int): List<QueueItem> {
    val at = items.indexOfFirst { it.current }
    return (if (at >= 0) items.drop(at + 1) else items).take(count.coerceAtLeast(0))
}

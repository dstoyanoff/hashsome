package dev.hashsome.headless

import dev.hashsome.client.MediaPlayerEntity
import dev.hashsome.client.QueueItem
import dev.hashsome.client.Store

data class QueueState(
    val items: List<QueueItem> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    /** How long the whole queue plays, in seconds, when the backend can say. */
    val duration: Double? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

/** A player's queue, refetched when the player's track changes or a command changes the queue (mirrors `use-media-queue.ts`). */
class QueueControl(val handle: EntityHandle<MediaPlayerEntity>, val scope: Scope) {
    val state: Store<QueueState> = Store(QueueState())

    fun refresh(): Unit = TODO("WP2")
    fun play(item: QueueItem): Unit = TODO("WP2")
    fun remove(item: QueueItem): Unit = TODO("WP2")

    /** Moves [item] by [shift] places: later when positive, earlier when negative. */
    fun move(item: QueueItem, shift: Int): Unit = TODO("WP2")

    /** Empties the queue after the track playing. */
    fun clear(): Unit = TODO("WP2")
}

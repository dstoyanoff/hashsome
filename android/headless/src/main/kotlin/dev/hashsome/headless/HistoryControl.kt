package dev.hashsome.headless

import dev.hashsome.client.EntityRef
import dev.hashsome.client.HistoryBucket
import dev.hashsome.client.HistoryRange
import dev.hashsome.client.HistoryResult
import dev.hashsome.client.Query
import dev.hashsome.client.Store

/** What an entity's history is now. A failure is kept for whoever wants to say why; the web's drawers just show nothing. */
sealed interface HistoryState {
    /** Nothing asked: there is no entity to ask about. */
    data object Idle : HistoryState

    /** Asked, not answered yet. */
    data object Loading : HistoryState

    data class Ready(val result: HistoryResult) : HistoryState
    data class Failed(val error: String) : HistoryState
}

/**
 * An entity's past values over [range] (in [bucket]s), from the backend's own record (mirrors `use-entity-history.ts`): asked for
 * when the control is made, kept for a minute and shared by every control that asks the same thing, and asked again when the
 * connection comes back after the ask failed. A backend that keeps no history gives a result with no points.
 */
class HistoryControl(
    val ref: EntityRef?,
    val scope: Scope,
    val range: HistoryRange,
    val bucket: HistoryBucket? = null,
) {
    /** From a handle: the entity it watches. */
    constructor(handle: EntityHandle<*>, scope: Scope, range: HistoryRange, bucket: HistoryBucket? = null) : this(handle.ref, scope, range, bucket)

    val state: Store<HistoryState> = Store(HistoryState.Idle)

    private val key = "$ref|${range.wire}|${bucket?.wire ?: ""}"

    init {
        if (ref != null) {
            load()
            // The connection coming back is a reason to ask again; an answer that is still fresh is used as it is.
            scope.onReconnect { load() }
        }
    }

    /** Asks again (a result younger than a minute is used as it is). */
    fun load() {
        val ref = ref ?: return
        if (state.value !is HistoryState.Ready) state.set(HistoryState.Loading)
        cache.ask(scope, ref, key, Query.History(range, bucket)) { result ->
            if (!scope.disposed) {
                state.set(
                    result.fold(
                        onSuccess = { HistoryState.Ready(it) },
                        onFailure = { HistoryState.Failed(it.message ?: it.toString()) },
                    ),
                )
            }
        }
    }

    companion object {
        /** How long an answer is reused: opening a drawer twice, or switching a range back, does not ask again. */
        const val FRESH_MS = 60_000L

        private val cache = AnswerCache<HistoryResult>(FRESH_MS)
    }
}

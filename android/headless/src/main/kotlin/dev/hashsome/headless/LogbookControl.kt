package dev.hashsome.headless

import dev.hashsome.client.EntityRef
import dev.hashsome.client.LogbookEntry
import dev.hashsome.client.LogbookResult
import dev.hashsome.client.Query
import dev.hashsome.client.Store

/** What happened to an entity lately. A failure is kept for whoever wants to say why; the web just shows nothing. */
sealed interface LogbookState {
    /** Not asked yet: the section has not been opened. */
    data object Idle : LogbookState

    data object Loading : LogbookState
    data class Ready(val entries: List<LogbookEntry>) : LogbookState
    data class Failed(val error: String) : LogbookState
}

/**
 * What happened to an entity lately and who or what caused it, newest first (mirrors `use-entity-logbook.ts` and `LogbookHistory`):
 * asked for by [open] (a drawer's History section asks only when the person opens it), kept for half a minute and shared by every
 * control that asks the same thing, and asked again when the connection comes back after the ask failed.
 */
class LogbookControl(val ref: EntityRef?, val scope: Scope, val limit: Int = LIMIT) {
    constructor(handle: EntityHandle<*>, scope: Scope, limit: Int = LIMIT) : this(handle.ref, scope, limit)

    val state: Store<LogbookState> = Store(LogbookState.Idle)

    private var asked = false
    private val key = "$ref|$limit"

    init {
        scope.onReconnect { if (asked) load() }
    }

    /** Asks for the entries, once; a later call does nothing. */
    fun open() {
        if (asked || ref == null) return
        asked = true
        load()
    }

    private fun load() {
        val ref = ref ?: return
        if (state.value !is LogbookState.Ready) state.set(LogbookState.Loading)
        cache.ask(scope, ref, key, Query.Logbook(limit)) { result ->
            if (!scope.disposed) {
                state.set(
                    result.fold(
                        onSuccess = { LogbookState.Ready(it.entries) },
                        onFailure = { LogbookState.Failed(it.message ?: it.toString()) },
                    ),
                )
            }
        }
    }

    companion object {
        /** How long an answer is reused: closing and reopening a drawer does not ask again. */
        const val FRESH_MS = 30_000L

        /** How many entries a drawer's History section lists. */
        const val LIMIT = 6

        private val cache = AnswerCache<LogbookResult>(FRESH_MS)
    }
}

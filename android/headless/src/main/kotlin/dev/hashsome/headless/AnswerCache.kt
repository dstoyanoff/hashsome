package dev.hashsome.headless

import dev.hashsome.client.Client
import dev.hashsome.client.EntityRef
import dev.hashsome.client.Query
import java.util.WeakHashMap

/**
 * One answer from the runtime per question, kept per client for [freshMs] and shared by every control that asks the same thing
 * (the `load` of `use-entity-history.ts` and `use-entity-logbook.ts`). A question asked while its answer is on its way waits for
 * that answer; a failed one is not kept, so the next ask tries again.
 */
internal class AnswerCache<T>(private val freshMs: Long) {
    private class Entry<T>(val at: Long) {
        var result: T? = null
        val waiting = ArrayList<(Result<T>) -> Unit>()
    }

    private val entries = WeakHashMap<Client, HashMap<String, Entry<T>>>()

    /** Calls [deliver] with the answer to [query] about [ref], from what is kept when it is fresh. [key] tells questions apart. */
    fun ask(scope: Scope, ref: EntityRef, key: String, query: Query<T>, deliver: (Result<T>) -> Unit) {
        val answers = entries.getOrPut(scope.client) { HashMap() }
        val now = scope.scheduler.now()
        val known = answers[key]?.takeIf { now - it.at < freshMs }
        val kept = known?.result
        if (kept != null) {
            deliver(Result.success(kept))
            return
        }

        if (known != null) {
            // Asked already and not answered yet: wait for that one.
            known.waiting += deliver
            return
        }

        val entry = Entry<T>(now)
        answers[key] = entry
        entry.waiting += deliver
        // Asked of the client itself, not through the scope: the answer is shared, so it is kept even if the screen that asked is gone.
        scope.client.query(ref, query) { result ->
            result.fold(
                onSuccess = { entry.result = it },
                onFailure = { if (answers[key] === entry) answers.remove(key) },
            )

            val waiting = entry.waiting.toList()
            entry.waiting.clear()
            waiting.forEach { it(result) }
        }
    }
}

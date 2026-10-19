package dev.hashsome.headless

import dev.hashsome.client.Cancel
import dev.hashsome.client.Entity
import dev.hashsome.client.EntityKind
import dev.hashsome.client.EntityRef
import dev.hashsome.client.Outcome
import dev.hashsome.client.Store
import org.json.JSONObject

/**
 * What a component can know about the entity it shows (the native `EntityHandle` and `status.ts`).
 * [Unavailable] keeps the last entity so a tile can still say what it was.
 */
sealed interface HandleState<out E : Entity> {
    /** Nothing heard yet. */
    data object Loading : HandleState<Nothing>

    /** The entity does not exist. */
    data object Missing : HandleState<Nothing>

    /** The entity exists but is of another kind than the handle was made for. */
    data object Unsupported : HandleState<Nothing>

    /** Reported and usable. */
    data class Ready<E : Entity>(val entity: E) : HandleState<E>

    /**
     * The device is unreachable; [entity] is what it last reported, or null when there never was one (a room with no
     * heater: `EntityHandle.fixed(HandleState.Unavailable(null))` renders the disabled tile).
     */
    data class Unavailable<E : Entity>(val entity: E?) : HandleState<E>
}

/**
 * An entity of one kind, watched for as long as [scope] lives. A component takes a handle, not a
 * ref, so the same component can be given a stand-in (see [fixed]).
 */
class EntityHandle<E : Entity> private constructor(
    val ref: EntityRef?,
    val kind: EntityKind<E>?,
    val state: Store<HandleState<E>>,
    private val scope: Scope?,
) {
    /** Watches [ref] (null: nothing to watch, state stays [HandleState.Missing]) through [scope]. */
    constructor(scope: Scope, ref: EntityRef?, kind: EntityKind<E>) : this(ref, kind, Store(HandleState.Loading), scope)

    /** The entity when [state] is [HandleState.Ready] or [HandleState.Unavailable]. */
    val entity: E? get() = TODO("WP2")

    /** Sends a command; the entity is not changed here, it changes when the runtime reports it. */
    fun command(name: String, args: JSONObject? = null, done: ((Outcome) -> Unit)? = null): Cancel = TODO("WP2")

    companion object {
        /** A handle that never watches anything and always shows [state] (the heater a bathroom does not have). */
        fun <E : Entity> fixed(state: HandleState<E>): EntityHandle<E> = EntityHandle(null, null, Store(state), null)
    }
}

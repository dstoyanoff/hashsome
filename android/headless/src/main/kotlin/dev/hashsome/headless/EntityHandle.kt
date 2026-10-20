package dev.hashsome.headless

import dev.hashsome.client.Availability
import dev.hashsome.client.Cancel
import dev.hashsome.client.Command
import dev.hashsome.client.Entity
import dev.hashsome.client.EntityKind
import dev.hashsome.client.EntityRef
import dev.hashsome.client.Outcome
import dev.hashsome.client.Snapshot
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
    internal val scope: Scope?,
) {
    init {
        if (scope != null && ref != null && kind != null) {
            scope.watch(ref) { snapshot -> state.set(stateOf(kind, snapshot)) }
        }
    }

    /**
     * Watches [ref] through [scope]. With no ref there is nothing to watch and the state is [HandleState.Missing] from
     * the start; with one it is [HandleState.Loading] until the runtime answers.
     */
    constructor(scope: Scope, ref: EntityRef? = null, kind: EntityKind<E>) :
        this(ref, kind, Store(if (ref == null) HandleState.Missing else HandleState.Loading), scope)

    /** The entity when [state] is [HandleState.Ready] or [HandleState.Unavailable] with one. */
    val entity: E?
        get() = when (val current = state.value) {
            is HandleState.Ready -> current.entity
            is HandleState.Unavailable -> current.entity
            else -> null
        }

    /** Sends a command; the entity is not changed here, it changes when the runtime reports it. */
    fun command(name: String, args: JSONObject? = null, done: ((Outcome) -> Unit)? = null): Cancel {
        val owner = scope
        if (owner == null || ref == null) {
            // A fixed handle, or one with no ref, has nothing to command.
            done?.invoke(Outcome.Failed("No entity to command"))
            return {}
        }

        return owner.command(ref, name, args, done)
    }

    /** [command] from the per-kind builders (`LightCommands.toggle()`); the same as the string form. */
    fun command(command: Command, done: ((Outcome) -> Unit)? = null): Cancel = command(command.name, command.args, done)

    companion object {
        /**
         * What a snapshot of the entity means to a component. An entity of another kind is [HandleState.Unsupported]; one
         * the backend has not reported on yet (availability `Unknown`) is [HandleState.Unavailable] with what it has.
         */
        internal fun <E : Entity> stateOf(kind: EntityKind<E>, snapshot: Snapshot): HandleState<E> = when (snapshot) {
            Snapshot.Loading -> HandleState.Loading
            Snapshot.Missing -> HandleState.Missing
            is Snapshot.Present -> {
                val entity = kind.cast(snapshot.entity)
                when {
                    entity == null -> HandleState.Unsupported
                    entity.availability == Availability.Ready -> HandleState.Ready(entity)
                    else -> HandleState.Unavailable(entity)
                }
            }
        }

        /** A handle that never watches anything and always shows [state] (the heater a bathroom does not have). */
        fun <E : Entity> fixed(state: HandleState<E>): EntityHandle<E> = EntityHandle(null, null, Store(state), null)
    }
}

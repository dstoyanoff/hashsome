package dev.hashsome.testing

import dev.hashsome.client.Cancel
import dev.hashsome.client.Client
import dev.hashsome.client.ConnectionStatus
import dev.hashsome.client.Entity
import dev.hashsome.client.EntityRef
import dev.hashsome.client.LinkStatus
import dev.hashsome.client.Outcome
import dev.hashsome.client.Query
import dev.hashsome.client.Snapshot
import dev.hashsome.client.Store
import org.json.JSONObject

/**
 * An in-memory [Client] for the headless tests (and the UI's): no transport, no parsing, typed entities.
 *
 * Contract (WP2 implements it):
 * - Entities: [set] makes an entity exist or change and [remove] makes it not exist. A [watch] gets a
 *   `Snapshot.Loading` first only if the ref was never set or removed (a test that wants Loading simply does not set it),
 *   `Present` or `Missing` after, and every later change, each delivered through the [TestScheduler] (so a test sees it
 *   after `runCurrent()` or `advance(...)`), and not after it is cancelled.
 * - Commands: every [command] is appended to [commands] as (ref, name, args) in order. [onCommand], when set, is called
 *   with it before the outcome is delivered and may call [set] to apply a simple state change (a toggle flipping `on`).
 *   The outcome is [Outcome.Ok], or the failure queued with [failNext]; [done] gets it through the scheduler.
 * - Queries: [answer] registers the JSON `data` to give for a query of that name (`browse`, `queue`, `forecast`...) on a
 *   ref; [query] parses it with the query's own `parse` and delivers `Result.success`, or `Result.failure` when none was
 *   registered or [failQuery] was set. Each ask is recorded in [queries].
 * - [link] and [integrations] are plain settable stores, [connect] sets the link to `Open` and [close] to `Closed`.
 */
class FakeClient(val scheduler: TestScheduler) : Client {
    override val link: Store<LinkStatus> = Store(LinkStatus.Open)
    override val integrations: Store<Map<String, ConnectionStatus>> = Store(emptyMap())

    /** Every command sent: the entity, the name, and the args (null when there are none), oldest first. */
    val commands: MutableList<Triple<EntityRef, String, JSONObject?>> = ArrayList()

    /** Every query asked: the entity and the query's name, oldest first. */
    val queries: MutableList<Pair<EntityRef, String>> = ArrayList()

    /** Called for each command before its outcome is delivered; apply a state change here with [set]. */
    var onCommand: ((EntityRef, String, JSONObject?) -> Unit)? = null

    /** Makes [entity] exist (or change) under [ref], notifying whoever watches it. */
    fun set(ref: EntityRef, entity: Entity): Unit = TODO("WP2")

    /** Makes the entity at [ref] not exist, notifying its watchers with `Snapshot.Missing`. */
    fun remove(ref: EntityRef): Unit = TODO("WP2")

    /** Gives [data] as the answer to a query named [queryName] on [ref]. */
    fun answer(ref: EntityRef, queryName: String, data: JSONObject?): Unit = TODO("WP2")

    /** The next command ends with `Outcome.Failed(error)`. */
    fun failNext(error: String): Unit = TODO("WP2")

    /** Every query ends with `Result.failure(IllegalStateException(error))` while this is set. */
    fun failQuery(error: String?): Unit = TODO("WP2")

    override fun connect(): Unit = TODO("WP2")
    override fun close(): Unit = TODO("WP2")
    override fun watch(ref: EntityRef, fn: (Snapshot) -> Unit): Cancel = TODO("WP2")
    override fun command(ref: EntityRef, name: String, args: JSONObject?, done: ((Outcome) -> Unit)?): Cancel = TODO("WP2")
    override fun <T> query(ref: EntityRef, query: Query<T>, done: (Result<T>) -> Unit): Cancel = TODO("WP2")
}

package dev.hashsome.client

import org.json.JSONObject

/**
 * One kind of entity: its wire name and how to read it. Handles ask for a kind, so a light
 * control never sees a switch, and an entity of another kind is `Unsupported`, not a crash.
 */
sealed class EntityKind<E : Entity>(val name: String) {
    abstract fun parse(ref: EntityRef, json: JSONObject): E

    /** [entity] as this kind, or null when it is another one. */
    abstract fun cast(entity: Entity): E?

    data object Light : EntityKind<LightEntity>("light") {
        override fun parse(ref: EntityRef, json: JSONObject): LightEntity = TODO("WP1")
        override fun cast(entity: Entity): LightEntity? = entity as? LightEntity
    }

    data object Switch : EntityKind<SwitchEntity>("switch") {
        override fun parse(ref: EntityRef, json: JSONObject): SwitchEntity = TODO("WP1")
        override fun cast(entity: Entity): SwitchEntity? = entity as? SwitchEntity
    }

    data object Climate : EntityKind<ClimateEntity>("climate") {
        override fun parse(ref: EntityRef, json: JSONObject): ClimateEntity = TODO("WP1")
        override fun cast(entity: Entity): ClimateEntity? = entity as? ClimateEntity
    }

    data object Action : EntityKind<ActionEntity>("action") {
        override fun parse(ref: EntityRef, json: JSONObject): ActionEntity = TODO("WP1")
        override fun cast(entity: Entity): ActionEntity? = entity as? ActionEntity
    }

    data object Sensor : EntityKind<SensorEntity>("sensor") {
        override fun parse(ref: EntityRef, json: JSONObject): SensorEntity = TODO("WP1")
        override fun cast(entity: Entity): SensorEntity? = entity as? SensorEntity
    }

    data object Person : EntityKind<PersonEntity>("person") {
        override fun parse(ref: EntityRef, json: JSONObject): PersonEntity = TODO("WP1")
        override fun cast(entity: Entity): PersonEntity? = entity as? PersonEntity
    }

    data object Weather : EntityKind<WeatherEntity>("weather") {
        override fun parse(ref: EntityRef, json: JSONObject): WeatherEntity = TODO("WP1")
        override fun cast(entity: Entity): WeatherEntity? = entity as? WeatherEntity
    }

    data object MediaPlayer : EntityKind<MediaPlayerEntity>("mediaPlayer") {
        override fun parse(ref: EntityRef, json: JSONObject): MediaPlayerEntity = TODO("WP1")
        override fun cast(entity: Entity): MediaPlayerEntity? = entity as? MediaPlayerEntity
    }
}

/**
 * A read that is not a subscription, answered once. [name] and [args] are what goes on the wire;
 * [parse] turns the answer's `data` into [T].
 */
sealed class Query<T>(val name: String) {
    /** The `args` object of the request, or null when there are none. */
    abstract fun args(): JSONObject?
    abstract fun parse(data: JSONObject?): T

    /** One level of a media library: the top when [path] is null, [search] results when it is set. */
    class Browse(val path: String? = null, val search: String? = null, val kind: BrowseKind? = null) : Query<BrowseResult>("browse") {
        override fun args(): JSONObject? = TODO("WP1")
        override fun parse(data: JSONObject?): BrowseResult = TODO("WP1")
    }

    /** A player's queue: [limit] items from a little before the current one (the runtime defaults to 30); [duration] also totals how long it all plays. */
    class Queue(val limit: Int? = null, val duration: Boolean? = null) : Query<QueueResult>("queue") {
        override fun args(): JSONObject? = TODO("WP1")
        override fun parse(data: JSONObject?): QueueResult = TODO("WP1")
    }

    class Forecast(val type: ForecastType) : Query<ForecastResult>("forecast") {
        override fun args(): JSONObject? = TODO("WP1")
        override fun parse(data: JSONObject?): ForecastResult = TODO("WP1")
    }

    class History(val range: HistoryRange, val bucket: HistoryBucket? = null) : Query<HistoryResult>("history") {
        override fun args(): JSONObject? = TODO("WP1")
        override fun parse(data: JSONObject?): HistoryResult = TODO("WP1")
    }

    /** The latest [limit] entries (the runtime defaults to 20). */
    class Logbook(val limit: Int? = null) : Query<LogbookResult>("logbook") {
        override fun args(): JSONObject? = TODO("WP1")
        override fun parse(data: JSONObject?): LogbookResult = TODO("WP1")
    }
}

/**
 * The app's one connection to the runtime. Entities are watched, not polled; commands and
 * queries are answered once. Every callback is delivered through [scheduler].post, so callers
 * run on the main thread whatever thread the socket reads on.
 *
 * Rules the implementation keeps (see `ProtocolRules`): ping every 5 s; dead after 15 s of silence
 * once a pong was seen; reconnect with backoff 500/1000/2000/4000 ms; resubscribe on open; requests
 * in flight when the link drops fail with "Connection lost".
 */
class HashsomeClient(
    val server: ServerAddress,
    private val transport: Transport.Factory = WsClient.Factory,
    private val scheduler: Scheduler,
) {
    /** The socket to the runtime. */
    val link: Store<LinkStatus> = Store(LinkStatus.Connecting)

    /** Each integration's own connection to its backend, by integration id, as the runtime last said. */
    val integrations: Store<Map<String, ConnectionStatus>> = Store(emptyMap())

    /** Opens the link and keeps it open (reconnecting) until [close]. */
    fun connect(): Unit = TODO("WP1")

    /** Closes the link for good. */
    fun close(): Unit = TODO("WP1")

    /**
     * Calls [fn] with the entity's state: at once when it is already known, then on every change.
     * Any number of watches on one [ref] share one subscription on the wire.
     */
    fun watch(ref: EntityRef, fn: (Snapshot) -> Unit): Cancel = TODO("WP1")

    /** Sends a command to the entity. [done] hears how it ended; cancelling only stops [done] from being called. */
    fun command(ref: EntityRef, name: String, args: JSONObject? = null, done: ((Outcome) -> Unit)? = null): Cancel = TODO("WP1")

    /** Asks a question of the entity; [done] gets the parsed answer or the failure. */
    fun <T> query(ref: EntityRef, query: Query<T>, done: (Result<T>) -> Unit): Cancel = TODO("WP1")
}

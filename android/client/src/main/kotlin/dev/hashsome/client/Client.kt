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
        override fun parse(ref: EntityRef, json: JSONObject): LightEntity = parseLight(ref, json)
        override fun cast(entity: Entity): LightEntity? = entity as? LightEntity
    }

    data object Switch : EntityKind<SwitchEntity>("switch") {
        override fun parse(ref: EntityRef, json: JSONObject): SwitchEntity = parseSwitch(ref, json)
        override fun cast(entity: Entity): SwitchEntity? = entity as? SwitchEntity
    }

    data object Climate : EntityKind<ClimateEntity>("climate") {
        override fun parse(ref: EntityRef, json: JSONObject): ClimateEntity = parseClimate(ref, json)
        override fun cast(entity: Entity): ClimateEntity? = entity as? ClimateEntity
    }

    data object Action : EntityKind<ActionEntity>("action") {
        override fun parse(ref: EntityRef, json: JSONObject): ActionEntity = parseAction(ref, json)
        override fun cast(entity: Entity): ActionEntity? = entity as? ActionEntity
    }

    data object Sensor : EntityKind<SensorEntity>("sensor") {
        override fun parse(ref: EntityRef, json: JSONObject): SensorEntity = parseSensor(ref, json)
        override fun cast(entity: Entity): SensorEntity? = entity as? SensorEntity
    }

    data object Person : EntityKind<PersonEntity>("person") {
        override fun parse(ref: EntityRef, json: JSONObject): PersonEntity = parsePerson(ref, json)
        override fun cast(entity: Entity): PersonEntity? = entity as? PersonEntity
    }

    data object Weather : EntityKind<WeatherEntity>("weather") {
        override fun parse(ref: EntityRef, json: JSONObject): WeatherEntity = parseWeather(ref, json)
        override fun cast(entity: Entity): WeatherEntity? = entity as? WeatherEntity
    }

    /** Anything with no kind of its own (a `select`, say): its state as text. Shown, not controlled; a service is called with [Client.raw]. */
    data object Generic : EntityKind<GenericEntity>("generic") {
        override fun parse(ref: EntityRef, json: JSONObject): GenericEntity = parseGeneric(ref, json)
        override fun cast(entity: Entity): GenericEntity? = entity as? GenericEntity
    }

    data object MediaPlayer : EntityKind<MediaPlayerEntity>("mediaPlayer") {
        override fun parse(ref: EntityRef, json: JSONObject): MediaPlayerEntity = parseMediaPlayer(ref, json)
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
        override fun args(): JSONObject? = argsOf("path" to path, "search" to search, "kind" to kind?.wire)
        override fun parse(data: JSONObject?): BrowseResult = parseBrowse(data)
    }

    /** A player's queue: [limit] items from a little before the current one (the runtime defaults to 30); [duration] also totals how long it all plays. */
    class Queue(val limit: Int? = null, val duration: Boolean? = null) : Query<QueueResult>("queue") {
        override fun args(): JSONObject? = argsOf("limit" to limit, "duration" to duration)
        override fun parse(data: JSONObject?): QueueResult = parseQueue(data)
    }

    class Forecast(val type: ForecastType) : Query<ForecastResult>("forecast") {
        override fun args(): JSONObject? = argsOf("type" to type.wire)
        override fun parse(data: JSONObject?): ForecastResult = parseForecast(data, type)
    }

    class History(val range: HistoryRange, val bucket: HistoryBucket? = null) : Query<HistoryResult>("history") {
        override fun args(): JSONObject? = argsOf("range" to range.wire, "bucket" to bucket?.wire)
        override fun parse(data: JSONObject?): HistoryResult = parseHistory(data)
    }

    /** The latest [limit] entries (the runtime defaults to 20). */
    class Logbook(val limit: Int? = null) : Query<LogbookResult>("logbook") {
        override fun args(): JSONObject? = argsOf("limit" to limit)
        override fun parse(data: JSONObject?): LogbookResult = parseLogbook(data)
    }
}

/**
 * What the headless layer and the UI need from a connection to the runtime (the native `Client` of
 * packages/core/src/client.ts). Entities are watched, not polled; commands and queries are answered once.
 * [RemoteClient] is the real one; `dev.hashsome.testing.FakeClient` is the in-memory one for tests.
 * Every callback arrives on the main thread.
 */
interface Client {
    /** The socket to the runtime. */
    val link: Store<LinkStatus>

    /** Each integration's own connection to its backend, by integration id, as the runtime last said. */
    val integrations: Store<Map<String, ConnectionStatus>>

    /** Opens the link and keeps it open (reconnecting) until [close]. */
    fun connect()

    /** Closes the link for good. */
    fun close()

    /**
     * Calls [fn] with the entity's state: at once when it is already known, then on every change.
     * Any number of watches on one [ref] share one subscription on the wire.
     */
    fun watch(ref: EntityRef, fn: (Snapshot) -> Unit): Cancel

    /** Sends a command to the entity. [done] hears how it ended; cancelling only stops [done] from being called. */
    fun command(ref: EntityRef, name: String, args: JSONObject? = null, done: ((Outcome) -> Unit)? = null): Cancel

    /** [command] from the per-kind builders (`LightCommands.setBrightness(0.4)`); the same as the string form. */
    fun command(ref: EntityRef, command: Command, done: ((Outcome) -> Unit)? = null): Cancel = command(ref, command.name, command.args, done)

    /** Asks a question of the entity; [done] gets the parsed answer or the failure. */
    fun <T> query(ref: EntityRef, query: Query<T>, done: (Result<T>) -> Unit): Cancel

    /**
     * Sends [request] straight to [integration] (its own vocabulary: for Home Assistant a service call, see [HaRequests]); [done] gets
     * the `data` of the answer (an empty object when there is none) or the failure. Waits for the link like [query].
     */
    fun raw(integration: String, request: JSONObject, done: ((Result<JSONObject>) -> Unit)? = null): Cancel

    /**
     * Drops the socket and opens a new one at once, at [server] when given, keeping every watch and store (subscriptions are sent again
     * on open); requests in flight fail with "Connection lost" and [link] goes `Connecting`. A new address clears what the old runtime said
     * (snapshots, [integrations]). Call it on the main thread, like every method.
     */
    fun reconnect(server: ServerAddress? = null)
}

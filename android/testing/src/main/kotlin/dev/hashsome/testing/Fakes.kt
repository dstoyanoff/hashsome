package dev.hashsome.testing

import dev.hashsome.client.ActionEntity
import dev.hashsome.client.Client
import dev.hashsome.client.ClimateEntity
import dev.hashsome.client.ClimateMode
import dev.hashsome.client.ConnectionStatus
import dev.hashsome.client.Entities
import dev.hashsome.client.Entity
import dev.hashsome.client.EntityRef
import dev.hashsome.client.LightColor
import dev.hashsome.client.LightEntity
import dev.hashsome.client.MediaPlayerEntity
import dev.hashsome.client.PlaybackState
import dev.hashsome.client.RemoteClient
import dev.hashsome.client.ServerAddress
import dev.hashsome.client.SwitchEntity
import dev.hashsome.client.Transport
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A [Transport] a test drives by hand: it records what the client sends and lets the test play the
 * runtime's side (open, deliver a message, drop the connection). Used with [TestScheduler] to check
 * heartbeats, backoff and resubscription without a socket.
 *
 * Like the real transport, [close] (from the client) ends the connection and tells the listener once.
 */
class FakeTransport : Transport {
    /** Every text frame the client sent, oldest first. */
    val sent: MutableList<String> = ArrayList()

    private var listener: Transport.Listener? = null
    private var ended = false

    /** Whether the client has called [connect] on this transport. */
    val connected: Boolean get() = listener != null

    /** Whether the client has closed it. */
    var closedByClient: Boolean = false
        private set

    /** The server and path this transport was created for, when it came from a [Factory]. */
    var target: Pair<ServerAddress, String>? = null
        internal set

    override fun connect(listener: Transport.Listener) {
        this.listener = listener
    }

    override fun send(text: String) {
        if (!ended) sent += text
    }

    override fun close() {
        if (closedByClient) return
        closedByClient = true
        end(null)
    }

    /** The runtime accepts the connection. */
    fun open() {
        checkNotNull(listener) { "The client has not called connect() yet" }.onOpen()
    }

    /** The runtime sends [text]. */
    fun receive(text: String) {
        checkNotNull(listener) { "The client has not called connect() yet" }.onMessage(text)
    }

    /** The connection drops. */
    fun drop(reason: String? = null) = end(reason)

    private fun end(reason: String?) {
        val l = listener ?: return
        if (ended) return
        ended = true
        l.onClosed(reason)
    }

    /** What the client sent, parsed: the frames whose `type` is [type], oldest first. */
    fun sentOf(type: String): List<JSONObject> = sent.map(::JSONObject).filter { it.optString("type") == type }

    /** Hands out one [FakeTransport] per connection attempt, remembering them in [created]. */
    class Factory : Transport.Factory {
        val created: MutableList<FakeTransport> = ArrayList()
        override fun create(server: ServerAddress, path: String): Transport = FakeTransport().also {
            it.target = server to path
            created += it
        }
    }
}

/**
 * The runtime as a test sees it: a set of entities that a [dev.hashsome.client.RemoteClient] (wired to a [FakeTransport]
 * and a [TestScheduler]) watches and commands. Commands are recorded in [commands], answers are scripted.
 *
 * It behaves like packages/runtime's proxy at the other end of a [Transport.Factory] (it is one: [client] is built on it):
 * a connection opens at once and is told each integration's status; `subscribe` is answered with the entity (or
 * `entity: null`) and every later change; `ping` with `pong`; a `command` is recorded in [commands], applied as
 * packages/core/src/mock.ts does for lights, switches, heaters, actions and players, and answered with a `result`; a `query` is
 * answered from the generated [Fixtures] (or what [answer] says). Every message the client receives goes through its
 * scheduler, so a test sees it after `scheduler.runCurrent()`.
 */
class FakeRuntime(val scheduler: TestScheduler = TestScheduler()) : Transport.Factory {
    private val remote: RemoteClient by lazy { RemoteClient(ServerAddress("http://fake.runtime:3000"), this, scheduler) }

    /** The client under test, connected to this runtime. */
    val client: Client get() = remote

    /** Every command the client sent: the entity, the name, and the args as JSON text. */
    val commands: MutableList<Triple<EntityRef, String, String?>> = ArrayList()

    /** The entities that exist now, by ref. */
    val entities: Map<EntityRef, Entity> get() = store

    /** Each integration's connection status, as sent to a client that connects. */
    val statuses: Map<String, ConnectionStatus> get() = integrationStatus

    /** While set, the runtime hears the client and says nothing: a link that is dead without having closed. */
    var silent: Boolean = false

    /** Every connection the client has made, oldest first. */
    val connections: List<Connection> get() = allConnections

    private val store = LinkedHashMap<EntityRef, Entity>()
    private val integrationStatus = LinkedHashMap<String, ConnectionStatus>()
    private val allConnections = ArrayList<Connection>()
    private val queryAnswers = HashMap<String, JSONObject?>()
    private var nextAnswer: Pair<Boolean, String?>? = null

    /** Makes [entity] exist (or change), notifying whoever watches it. */
    fun put(entity: Entity) {
        store[entity.ref] = entity
        integrationStatus.getOrPut(entity.ref.integration) { ConnectionStatus.Connected }
        broadcast(entity.ref)
    }

    /** Makes the entity at [ref] not exist. */
    fun remove(ref: EntityRef) {
        store.remove(ref)
        broadcast(ref)
    }

    /** Makes every entity of the generated fixtures exist, as they are on the wire. */
    fun loadFixtures(): FakeRuntime {
        for (path in Fixtures.inFolder("entities")) {
            val frame = Fixtures.json(path)
            val ref = EntityRef(frame.getString("ref"))
            put(Entities.parse(ref, frame.getJSONObject("entity")))
        }
        return this
    }

    /** Answers the next command with [error], or with success when null. */
    fun answerNext(error: String? = null) {
        nextAnswer = (error == null) to error
    }

    /** Gives [data] as the answer to every query named [query] (`browse`, `queue`, `forecast`, `history`, `logbook`), instead of the fixture. */
    fun answer(query: String, data: JSONObject?) {
        queryAnswers[query] = data
    }

    /** Changes [integration]'s status and tells every connection. */
    fun setStatus(integration: String, status: ConnectionStatus) {
        integrationStatus[integration] = status
        val frame = JSONObject().put("type", "status").put("integration", integration).put("status", status.wire).toString()
        for (c in allConnections) c.push(frame)
    }

    /** Cuts every connection, as a dead link does: the client hears it closed and reconnects after its backoff. */
    fun drop(reason: String? = "dropped") {
        for (c in allConnections.toList()) c.end(reason)
    }

    override fun create(server: ServerAddress, path: String): Transport = Connection().also { allConnections += it }

    private fun broadcast(ref: EntityRef) {
        val frame = EntityJson.frame(ref, store[ref])
        for (c in allConnections) if (ref in c.subscribed) c.push(frame)
    }

    /** One connection of the client to this runtime. */
    inner class Connection internal constructor() : Transport {
        private var listener: Transport.Listener? = null
        private var ended = false
        internal val subscribed = LinkedHashSet<EntityRef>()

        override fun connect(listener: Transport.Listener) {
            this.listener = listener
            listener.onOpen()
            for ((integration, status) in integrationStatus) {
                push(JSONObject().put("type", "status").put("integration", integration).put("status", status.wire).toString())
            }
        }

        override fun send(text: String) {
            if (ended || silent) return
            val message = try {
                JSONObject(text)
            } catch (_: Exception) {
                return
            }
            when (message.optString("type")) {
                "ping" -> push("""{"type":"pong"}""")
                "subscribe" -> {
                    val ref = EntityRef(message.getString("ref"))
                    if (subscribed.add(ref)) push(EntityJson.frame(ref, store[ref]))
                }
                "unsubscribe" -> subscribed.remove(EntityRef(message.getString("ref")))
                "command" -> command(message)
                "query" -> query(message)
            }
        }

        override fun close() = end(null)

        internal fun end(reason: String?) {
            if (ended) return
            ended = true
            allConnections.remove(this)
            listener?.onClosed(reason)
        }

        internal fun push(text: String) {
            if (!ended && !silent) listener?.onMessage(text)
        }

        private fun result(id: Int, error: String?, data: JSONObject? = null) {
            val frame = JSONObject().put("type", "result").put("id", id).put("ok", error == null)
            if (error != null) frame.put("error", error) else if (data != null) frame.put("data", data)
            push(frame.toString())
        }

        private fun command(message: JSONObject) {
            val id = message.getInt("id")
            val ref = EntityRef(message.getString("ref"))
            val name = message.getString("command")
            val args = message.optJSONObject("args")
            commands += Triple(ref, name, args?.toString())
            val scripted = nextAnswer
            nextAnswer = null
            if (scripted?.second != null) return result(id, scripted.second)
            val entity = store[ref] ?: return result(id, "Unknown entity \"${ref.id}\"")
            val next = try {
                applyCommand(entity, name, args ?: JSONObject())
            } catch (e: IllegalArgumentException) {
                return result(id, e.message)
            }
            if (next != entity) {
                store[ref] = next
                broadcast(ref)
            }
            result(id, null)
        }

        private fun query(message: JSONObject) {
            val id = message.getInt("id")
            val ref = EntityRef(message.getString("ref"))
            val name = message.getString("query")
            if (ref !in store) return result(id, "Unknown entity \"${ref.id}\"")
            val args = message.optJSONObject("args")
            if (queryAnswers.containsKey(name)) return result(id, null, queryAnswers[name])
            val fixture = when (name) {
                "browse" -> when {
                    args?.has("search") == true -> "browse-search"
                    args?.has("path") == true -> "browse-shelf"
                    else -> "browse-root"
                }
                "queue" -> "queue"
                "forecast" -> "forecast-${args?.optString("type", "daily")?.replace('_', '-')}"
                "history" -> "history-measurement"
                "logbook" -> "logbook"
                else -> return result(id, "Unknown query \"$name\"")
            }
            val data = Fixtures.json("results/$fixture.json").optJSONObject("data")
            result(id, null, data)
        }
    }

    /** What a command does to an entity's state, for the kinds that have commands (the same as `applyCommand` in mock.ts). */
    private fun applyCommand(entity: Entity, name: String, args: JSONObject): Entity {
        fun number(key: String): Double {
            val value = args.opt(key)
            require(value is Number) { "\"$name\" needs a numeric \"$key\"" }
            return value.toDouble()
        }

        fun clamp01(v: Double) = v.coerceIn(0.0, 1.0)
        return when (entity) {
            is LightEntity -> when (name) {
                "turnOn" -> entity.copy(on = true)
                "turnOff" -> entity.copy(on = false)
                "toggle" -> entity.copy(on = !entity.on)
                "setBrightness" -> clamp01(number("brightness")).let { b -> if (b == 0.0) entity.copy(on = false) else entity.copy(on = true, brightness = b) }
                "setColorTemperature" -> entity.copy(on = true, color = LightColor.Temperature(number("kelvin").toInt()))
                "setColor" -> entity.copy(on = true, color = LightColor.Hs(number("hue"), number("saturation")))
                else -> throw IllegalArgumentException("Unknown command \"$name\" for a light")
            }
            is SwitchEntity -> when (name) {
                "turnOn" -> entity.copy(on = true)
                "turnOff" -> entity.copy(on = false)
                "toggle" -> entity.copy(on = !entity.on)
                else -> throw IllegalArgumentException("Unknown command \"$name\" for a switch")
            }
            is MediaPlayerEntity -> when (name) {
                "play" -> entity.copy(playback = PlaybackState.Playing)
                "pause" -> entity.copy(playback = PlaybackState.Paused)
                "togglePlay" -> entity.copy(playback = if (entity.playback == PlaybackState.Playing) PlaybackState.Paused else PlaybackState.Playing)
                "setVolume" -> entity.copy(volume = clamp01(number("volume")))
                "setMuted" -> entity.copy(muted = args.optBoolean("muted"))
                "setShuffle" -> entity.copy(shuffle = args.optBoolean("shuffle"))
                "seek" -> entity.copy(position = number("position").coerceAtLeast(0.0), positionUpdatedAt = iso(scheduler.wallMs()))
                // The rest (next, previous, playMedia, queue edits, grouping) leaves the state as it is.
                else -> entity
            }
            is ClimateEntity -> when (name) {
                "setMode" -> entity.copy(mode = ClimateMode.fromWire(args.optString("mode")))
                "setTargetTemperature" -> entity.copy(targetTemperature = number("temperature"))
                "setPreset" -> entity.copy(preset = args.optString("preset"))
                else -> throw IllegalArgumentException("Unknown command \"$name\" for a climate")
            }
            is ActionEntity -> entity.copy(lastTriggered = iso(scheduler.wallMs()))
            else -> entity
        }
    }

    private fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
}

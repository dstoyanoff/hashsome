package dev.hashsome.client

import org.json.JSONObject

/** Where the runtime is. [baseUrl] is the origin the dashboards are served from, e.g. `http://10.1.1.5:3000`. */
data class ServerAddress(val baseUrl: String) {
    val host: String get() = TODO("WP1")
    val port: Int get() = TODO("WP1")
    val secure: Boolean get() = baseUrl.startsWith("https://")
}

/** The timing rules of the socket, shared by the client and its tests. */
object ProtocolRules {
    /** A `ping` is sent this often. */
    const val PING_MS = 5_000L

    /** The link is dead after this much silence, but only once a `pong` was ever seen: an older runtime drops `ping` without an answer. */
    const val DEAD_AFTER_MS = 15_000L

    /** Wait before the nth reconnect attempt (the last entry repeats). */
    val BACKOFF_MS = longArrayOf(500, 1_000, 2_000, 4_000)

    /** The path of the websocket on the runtime. */
    const val WS_PATH = "/ws"

    /** Where the runtime serves files an integration's entities point at. */
    const val ASSET_PATH = "/_hashsome/asset"
}

/**
 * A message the app sends to the runtime's `/ws`, as in packages/core/src/protocol.ts. The TS `raw` message (a
 * request straight to an integration) is left out on purpose: the UI never sends it.
 */
sealed interface ClientMessage {
    fun encode(): String = TODO("WP1")

    data object Ping : ClientMessage
    data class Subscribe(val ref: EntityRef) : ClientMessage
    data class Unsubscribe(val ref: EntityRef) : ClientMessage
    data class Command(val id: Int, val ref: EntityRef, val command: String, val args: JSONObject?) : ClientMessage
    data class Query(val id: Int, val ref: EntityRef, val query: String, val args: JSONObject?) : ClientMessage
}

/** A message the runtime sends. */
sealed interface ServerMessage {
    data object Pong : ServerMessage

    /** [entity] is null when the entity does not exist (or was removed). */
    data class EntityUpdate(val ref: EntityRef, val entity: Entity?) : ServerMessage
    data class Status(val integration: String, val status: ConnectionStatus) : ServerMessage
    data class Result(val id: Int, val ok: Boolean, val data: JSONObject?, val error: String?) : ServerMessage

    companion object {
        /** Null for anything malformed or of a type this app does not know. */
        fun decode(text: String): ServerMessage? = TODO("WP1")
    }
}

/** One connection to the runtime, as the client sees it: text frames in and out. Tests replace it with a fake. */
interface Transport {
    /** Opens the connection; every event arrives on [listener], from any thread. */
    fun connect(listener: Listener)
    fun send(text: String)
    fun close()

    interface Listener {
        fun onOpen()
        fun onMessage(text: String)
        /** The connection is gone, for [reason] if it is known. Called once. */
        fun onClosed(reason: String?)
    }

    fun interface Factory {
        fun create(server: ServerAddress, path: String): Transport
    }
}

/** A minimal RFC 6455 websocket client over a plain socket (no library): masking, 126/127 lengths, fragments, ping/pong, close. */
class WsClient(private val server: ServerAddress, private val path: String) : Transport {
    override fun connect(listener: Transport.Listener): Unit = TODO("WP1")
    override fun send(text: String): Unit = TODO("WP1")
    override fun close(): Unit = TODO("WP1")

    object Factory : Transport.Factory {
        override fun create(server: ServerAddress, path: String): Transport = WsClient(server, path)
    }
}

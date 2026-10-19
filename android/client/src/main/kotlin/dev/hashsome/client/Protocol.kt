package dev.hashsome.client

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Where the runtime is. [baseUrl] is the origin the dashboards are served from, e.g. `http://10.1.1.5:3000`. */
data class ServerAddress(val baseUrl: String) {
    /** The host without brackets, an IPv6 address included. */
    val host: String get() = authority().let { if (it.startsWith("[")) it.substringAfter('[').substringBefore(']') else it.substringBefore(':') }

    /** The port in the address, else the scheme's default. */
    val port: Int
        get() {
            val a = authority()
            val rest = if (a.startsWith("[")) a.substringAfter(']', "") else a.substringAfter(':', "")
            return rest.removePrefix(":").toIntOrNull() ?: if (secure) 443 else 80
        }
    val secure: Boolean get() = baseUrl.startsWith("https://")

    private fun authority(): String = baseUrl.substringAfter("://", baseUrl).substringBefore('/').substringBefore('?').substringAfterLast('@')

    /** `host:port` as the `Host` header writes it. */
    internal fun hostHeader(): String = (if (host.contains(':')) "[$host]" else host) + ":" + port
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
 * A message the app sends to the runtime's `/ws`, as in packages/core/src/protocol.ts.
 */
sealed interface ClientMessage {
    fun encode(): String {
        val json = JSONObject()
        when (val m = this) {
            Ping -> json.put("type", "ping")
            is Subscribe -> json.put("type", "subscribe").put("ref", m.ref.value)
            is Unsubscribe -> json.put("type", "unsubscribe").put("ref", m.ref.value)
            is Command -> {
                json.put("type", "command").put("id", m.id).put("ref", m.ref.value).put("command", m.command)
                if (m.args != null) json.put("args", m.args)
            }
            is Raw -> json.put("type", "raw").put("id", m.id).put("integration", m.integration).put("request", m.request)
            is Query -> {
                json.put("type", "query").put("id", m.id).put("ref", m.ref.value).put("query", m.query)
                if (m.args != null) json.put("args", m.args)
            }
        }
        return json.toString()
    }

    data object Ping : ClientMessage
    data class Subscribe(val ref: EntityRef) : ClientMessage
    data class Unsubscribe(val ref: EntityRef) : ClientMessage
    data class Command(val id: Int, val ref: EntityRef, val command: String, val args: JSONObject?) : ClientMessage
    data class Query(val id: Int, val ref: EntityRef, val query: String, val args: JSONObject?) : ClientMessage
    data class Raw(val id: Int, val integration: String, val request: JSONObject) : ClientMessage
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
        fun decode(text: String): ServerMessage? = decode(text, ::parseEntity, null)

        /** [parse] reads an entity body; when it throws, the entity still arrives as a [GenericEntity] of its base fields and [onError] hears why. */
        internal fun decode(text: String, parse: (EntityRef, JSONObject) -> Entity, onError: ((String) -> Unit)?): ServerMessage? {
            val json = try {
                JSONObject(text)
            } catch (_: Exception) {
                return null
            }
            return decode(json, parse, onError)
        }

        internal fun decode(json: JSONObject, parse: (EntityRef, JSONObject) -> Entity = ::parseEntity, onError: ((String) -> Unit)? = null): ServerMessage? = try {
            when (json.str("type")) {
                "pong" -> Pong
                "entity" -> {
                    val ref = json.str("ref")?.let(::EntityRef)?.takeIf { it.isValid }
                    when {
                        ref == null -> null
                        // `entity: null` says the entity does not exist; no `entity` key at all is malformed.
                        json.has("entity") && json.isNull("entity") -> EntityUpdate(ref, null)
                        else -> json.obj("entity")?.let { EntityUpdate(ref, readEntity(ref, it, parse, onError)) }
                    }
                }
                "status" -> {
                    val integration = json.str("integration")
                    val status = json.str("status")
                    if (integration == null || status == null) null else Status(integration, ConnectionStatus.fromWire(status))
                }
                "result" -> {
                    val id = json.int("id")
                    val ok = json.bool("ok")
                    if (id == null || ok == null) null else Result(id, ok, json.obj("data"), json.str("error"))
                }
                else -> null
            }
        } catch (_: Exception) {
            // Anything else malformed is dropped, so one bad frame never ends the link.
            null
        }

        private fun readEntity(ref: EntityRef, json: JSONObject, parse: (EntityRef, JSONObject) -> Entity, onError: ((String) -> Unit)?): Entity =
            try {
                parse(ref, json)
            } catch (e: Exception) {
                onError?.invoke("Could not read $ref as ${json.str("kind")}: ${e.message}")
                parseGeneric(ref, json)
            }
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
    /** How long to wait for the TCP connection and for the server's answer to the upgrade. */
    internal var connectTimeoutMs: Int = 5_000

    /** The platform's by default; a seam for tests. */
    internal var sslFactory: SSLSocketFactory? = null

    /** The platform's by default; a seam for tests. */
    internal var hostnameVerifier: HostnameVerifier? = null

    /** The longest message accepted once its fragments are joined. */
    internal var maxMessageBytes: Int = 32 * 1024 * 1024

    @Volatile private var socket: Socket? = null

    @Volatile private var out: OutputStream? = null
    private val closed = AtomicBoolean(false)
    private val notified = AtomicBoolean(false)
    private val random = SecureRandom()
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "hashsome-ws-writer").also { it.isDaemon = true } }

    override fun connect(listener: Transport.Listener) {
        val reader = Thread({
            var reason: String? = null
            try {
                val s = openSocket()
                if (!closed.get()) {
                    val input = BufferedInputStream(s.getInputStream())
                    val output = s.getOutputStream()
                    val key = WsFrames.base64(ByteArray(16).also { random.nextBytes(it) })
                    output.write(
                        ("GET $path HTTP/1.1\r\nHost: ${server.hostHeader()}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                            "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray(Charsets.US_ASCII),
                    )
                    output.flush()
                    WsFrames.checkHandshake(input, key)
                    s.soTimeout = 0
                    out = output
                    listener.onOpen()
                    reason = readFrames(input, listener)
                }
            } catch (e: Exception) {
                reason = e.message ?: e.javaClass.simpleName
            } finally {
                close()
                // A connection that never got as far as the handshake has no goodbye to wait for, and close() may have run before
                // the socket existed: whichever got there first, the socket does not outlive this thread.
                if (out == null) closeSocket()
                if (notified.compareAndSet(false, true)) listener.onClosed(reason)
            }
        }, "hashsome-ws-reader")
        reader.isDaemon = true
        reader.start()
    }

    private fun openSocket(): Socket {
        if (closed.get()) throw IOException("Closed before it connected")
        val plain = Socket()
        socket = plain
        // close() may have run between the check above and the line before: it closed what it saw, which may have been nothing.
        if (closed.get()) throw IOException("Closed before it connected")
        plain.tcpNoDelay = true
        plain.connect(InetSocketAddress(server.host, server.port), connectTimeoutMs)
        plain.soTimeout = connectTimeoutMs
        if (!server.secure) return plain
        val factory = sslFactory ?: (SSLSocketFactory.getDefault() as SSLSocketFactory)
        val tls = factory.createSocket(plain, server.host, server.port, true) as SSLSocket
        socket = tls
        tls.startHandshake()
        // The certificate has to be for the host asked for: a trusted certificate for another name is somebody else.
        val verifier = hostnameVerifier ?: HttpsURLConnection.getDefaultHostnameVerifier()
        if (!verifier.verify(server.host, tls.session)) throw SSLPeerUnverifiedException("The certificate is not for ${server.host}")
        return tls
    }

    /** Reads frames until the connection ends; returns why it did. */
    private fun readFrames(input: InputStream, listener: Transport.Listener): String? {
        var message = ByteArrayOutputStream()
        var inText = false
        while (true) {
            val frame = WsFrames.readFrame(input)
            when (frame.opcode) {
                0, 1 -> {
                    if (frame.opcode == 1) {
                        message = ByteArrayOutputStream()
                        inText = true
                    }
                    if (inText) {
                        if (message.size().toLong() + frame.payload.size > maxMessageBytes) throw IOException("The message is longer than $maxMessageBytes bytes")
                        message.write(frame.payload)
                    }
                    if (frame.fin && inText) {
                        inText = false
                        listener.onMessage(String(message.toByteArray(), Charsets.UTF_8))
                    }
                }
                // The runtime sends text only; a binary message is not for us.
                2 -> inText = false
                8 -> return "Closed by the server"
                9 -> sendFrame(10, frame.payload)
            }
        }
    }

    override fun send(text: String) = sendFrame(1, text.toByteArray(Charsets.UTF_8))

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        if (closed.get()) return
        try {
            writer.execute {
                try {
                    val o = out ?: return@execute
                    o.write(WsFrames.encode(opcode, payload, ByteArray(4).also { random.nextBytes(it) }))
                    o.flush()
                } catch (_: Exception) {
                    closeSocket()
                }
            }
        } catch (_: RejectedExecutionException) {
            // Closed meanwhile.
        }
    }

    /** Ends the connection: says goodbye if it can, then closes the socket. Safe to call more than once. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val o = out
        try {
            writer.execute {
                try {
                    o?.write(WsFrames.encode(8, ByteArray(0), ByteArray(4).also { random.nextBytes(it) }))
                    o?.flush()
                } catch (_: Exception) {
                }
                closeSocket()
            }
            writer.shutdown()
        } catch (_: RejectedExecutionException) {
            closeSocket()
        }
        // A write stuck on a dead connection would keep the goodbye from ever closing the socket: after a moment, close it regardless.
        val watchdog = Thread({
            try {
                if (!writer.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    writer.shutdownNow()
                    closeSocket()
                }
            } catch (_: InterruptedException) {
                closeSocket()
            }
        }, "hashsome-ws-close")
        watchdog.isDaemon = true
        watchdog.start()
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
    }

    object Factory : Transport.Factory {
        override fun create(server: ServerAddress, path: String): Transport = WsClient(server, path)
    }
}

/** The bytes of RFC 6455, apart from any socket so they can be tested alone. */
internal object WsFrames {
    class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    /** Longest message accepted, so a broken peer cannot make the app allocate without end. */
    private const val MAX_PAYLOAD = 32L * 1024 * 1024

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** `java.util.Base64` needs API 26 and `android.util.Base64` is a stub in unit tests, so this is written out. */
    fun base64(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
            sb.append(ALPHABET[b0 shr 2])
            sb.append(ALPHABET[((b0 and 3) shl 4) or (b1 shr 4)])
            sb.append(if (i + 1 < bytes.size) ALPHABET[((b1 and 15) shl 2) or (b2 shr 6)] else '=')
            sb.append(if (i + 2 < bytes.size) ALPHABET[b2 and 63] else '=')
            i += 3
        }
        return sb.toString()
    }

    /** What the server must answer in `Sec-WebSocket-Accept` for the client's [key]. */
    fun accept(key: String): String =
        base64(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)))

    /** Reads the server's answer to the upgrade and throws unless it accepts it. */
    fun checkHandshake(input: InputStream, key: String) {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) throw IOException("The server closed the connection during the handshake")
            head.append(b.toChar())
            if (head.length > 8192) throw IOException("The handshake answer is too long")
        }
        val lines = head.toString().split("\r\n")
        if (!lines[0].startsWith("HTTP/1.1 101")) throw IOException("The server did not upgrade: ${lines[0]}")
        val accept = lines.drop(1).firstNotNullOfOrNull { line ->
            line.takeIf { it.startsWith("sec-websocket-accept:", ignoreCase = true) }?.substringAfter(':')?.trim()
        }
        if (accept != accept(key)) throw IOException("The server's Sec-WebSocket-Accept is wrong")
    }

    /** A client frame: FIN set, masked with [mask]. */
    fun encode(opcode: Int, payload: ByteArray, mask: ByteArray): ByteArray {
        val extra = when {
            payload.size < 126 -> 0
            payload.size < 65536 -> 2
            else -> 8
        }
        val frame = ByteArray(2 + extra + 4 + payload.size)
        frame[0] = (0x80 or opcode).toByte()
        when (extra) {
            0 -> frame[1] = (0x80 or payload.size).toByte()
            2 -> {
                frame[1] = (0x80 or 126).toByte()
                frame[2] = (payload.size shr 8).toByte()
                frame[3] = payload.size.toByte()
            }
            else -> {
                frame[1] = (0x80 or 127).toByte()
                for (i in 0 until 8) frame[2 + i] = (payload.size.toLong() shr (8 * (7 - i))).toByte()
            }
        }
        val at = 2 + extra
        System.arraycopy(mask, 0, frame, at, 4)
        for (i in payload.indices) frame[at + 4 + i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        return frame
    }

    fun readFrame(input: InputStream): Frame {
        val head = readFully(input, 2)
        val fin = head[0].toInt() and 0x80 != 0
        val opcode = head[0].toInt() and 0x0f
        val masked = head[1].toInt() and 0x80 != 0
        var length = (head[1].toInt() and 0x7f).toLong()
        if (length == 126L) {
            val e = readFully(input, 2)
            length = (((e[0].toInt() and 0xff) shl 8) or (e[1].toInt() and 0xff)).toLong()
        } else if (length == 127L) {
            val e = readFully(input, 8)
            length = 0
            for (i in 0 until 8) length = (length shl 8) or (e[i].toLong() and 0xff)
        }
        if (length < 0 || length > MAX_PAYLOAD) throw IOException("A frame of $length bytes is too long")
        val mask = if (masked) readFully(input, 4) else null
        val payload = readPayload(input, length.toInt())
        if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        return Frame(fin, opcode, payload)
    }

    private const val CHUNK = 64 * 1024

    /** [n] bytes, read in chunks, so a frame that claims a length it never delivers costs no more than what arrived. */
    private fun readPayload(input: InputStream, n: Int): ByteArray {
        if (n <= CHUNK) return readFully(input, n)
        val out = ByteArrayOutputStream(CHUNK)
        val buf = ByteArray(CHUNK)
        var left = n
        while (left > 0) {
            val r = input.read(buf, 0, minOf(left, CHUNK))
            if (r < 0) throw java.io.EOFException()
            out.write(buf, 0, r)
            left -= r
        }
        return out.toByteArray()
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var at = 0
        while (at < n) {
            val r = input.read(buf, at, n - at)
            if (r < 0) throw java.io.EOFException()
            at += r
        }
        return buf
    }
}

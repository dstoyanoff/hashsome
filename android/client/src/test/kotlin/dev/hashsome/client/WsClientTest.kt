package dev.hashsome.client

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** [WsClient] against a hand-rolled RFC 6455 server on a local socket, so the bytes on the wire are what is checked. */
class WsClientTest {
    private lateinit var listening: ServerSocket
    private val sockets = ArrayList<Socket>()
    private val events = LinkedBlockingQueue<String>()

    private val listener = object : Transport.Listener {
        override fun onOpen() {
            events.add("open")
        }

        override fun onMessage(text: String) {
            events.add("message:$text")
        }

        override fun onClosed(reason: String?) {
            events.add("closed:$reason")
        }
    }

    @Before
    fun listen() {
        listening = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    }

    @After
    fun stop() {
        listening.close()
        for (s in sockets) runCatching { s.close() }
    }

    private fun address() = ServerAddress("http://127.0.0.1:${listening.localPort}")

    private fun next(): String = events.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("nothing happened within 5 s")

    private fun quiet() = assertNull(events.poll(150, TimeUnit.MILLISECONDS))

    /** The server's side of one connection. */
    private inner class Peer(val socket: Socket) {
        val input: InputStream = socket.getInputStream()
        val output: OutputStream = socket.getOutputStream()
        lateinit var request: String

        fun handshake(status: String = "101 Switching Protocols", accept: ((String) -> String)? = WsFrames::accept) {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
            request = head.toString()
            val key = header("Sec-WebSocket-Key")
            val lines = StringBuilder("HTTP/1.1 $status\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n")
            if (accept != null) lines.append("Sec-WebSocket-Accept: ${accept(key)}\r\n")
            output.write(lines.append("\r\n").toString().toByteArray())
            output.flush()
        }

        fun header(name: String): String =
            request.split("\r\n").first { it.startsWith("$name:", ignoreCase = true) }.substringAfter(':').trim()

        /** A frame as the client wrote it, with what the bytes said about it. */
        inner class Got(val fin: Boolean, val opcode: Int, val masked: Boolean, val lengthCode: Int, val payload: ByteArray)

        fun read(): Got {
            val h = ByteArray(2)
            readFully(h)
            val code = h[1].toInt() and 0x7f
            val length = when (code) {
                126 -> ByteArray(2).also(::readFully).fold(0L) { a, b -> (a shl 8) or (b.toLong() and 0xff) }
                127 -> ByteArray(8).also(::readFully).fold(0L) { a, b -> (a shl 8) or (b.toLong() and 0xff) }
                else -> code.toLong()
            }
            val masked = h[1].toInt() and 0x80 != 0
            val mask = if (masked) ByteArray(4).also(::readFully) else null
            val payload = ByteArray(length.toInt()).also(::readFully)
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            return Got(h[0].toInt() and 0x80 != 0, h[0].toInt() and 0x0f, masked, code, payload)
        }

        private fun readFully(buf: ByteArray) {
            var at = 0
            while (at < buf.size) {
                val r = input.read(buf, at, buf.size - at)
                if (r < 0) throw java.io.EOFException()
                at += r
            }
        }

        /** Writes a server frame (never masked). */
        fun write(opcode: Int, payload: ByteArray, fin: Boolean = true) {
            val out = ByteArrayOutputStream()
            out.write((if (fin) 0x80 else 0) or opcode)
            when {
                payload.size < 126 -> out.write(payload.size)
                payload.size < 65536 -> {
                    out.write(126)
                    out.write(payload.size shr 8)
                    out.write(payload.size and 0xff)
                }
                else -> {
                    out.write(127)
                    for (i in 7 downTo 0) out.write((payload.size.toLong() shr (8 * i)).toInt() and 0xff)
                }
            }
            out.write(payload)
            output.write(out.toByteArray())
            output.flush()
        }

        fun text(s: String, fin: Boolean = true) = write(1, s.toByteArray(), fin)
    }

    /** Connects a client and accepts it, with the handshake done unless [handshake] says otherwise. */
    private fun connected(handshake: Boolean = true): Pair<WsClient, Peer> {
        val client = WsClient(address(), "/ws")
        client.connect(listener)
        val socket = listening.accept().also { sockets += it }
        val peer = Peer(socket)
        if (handshake) {
            peer.handshake()
            assertEquals("open", next())
        }
        return client to peer
    }

    @Test
    fun `the upgrade request says what a websocket server needs`() {
        val (client, peer) = connected()
        assertTrue(peer.request.startsWith("GET /ws HTTP/1.1\r\n"))
        assertEquals("127.0.0.1:${listening.localPort}", peer.header("Host"))
        assertEquals("websocket", peer.header("Upgrade").lowercase())
        assertTrue(peer.header("Connection").contains("Upgrade", ignoreCase = true))
        assertEquals("13", peer.header("Sec-WebSocket-Version"))
        val key = peer.header("Sec-WebSocket-Key")
        assertEquals(24, key.length)
        assertTrue(key.endsWith("=="))
        client.close()
    }

    @Test
    fun `a nonce is different every time`() {
        val keys = (1..2).map {
            val (client, peer) = connected()
            peer.header("Sec-WebSocket-Key").also {
                client.close()
                assertTrue(next().startsWith("closed:"))
            }
        }
        assertTrue(keys[0] != keys[1])
    }

    @Test
    fun `frames the client sends are masked, final text frames`() {
        val (client, peer) = connected()
        client.send("hello")
        val got = peer.read()
        assertTrue(got.fin)
        assertEquals(1, got.opcode)
        assertTrue("client frames must be masked", got.masked)
        assertEquals("hello", String(got.payload))
        client.close()
    }

    @Test
    fun `masks are fresh for each frame`() {
        val (client, peer) = connected()
        // The same text twice, read raw: the masked bytes differ unless the key repeated (1 in 2^32).
        client.send("same text")
        client.send("same text")
        val raw = ByteArray(2 * (2 + 4 + 9))
        var at = 0
        while (at < raw.size) at += peer.input.read(raw, at, raw.size - at)
        val first = raw.copyOfRange(2, 2 + 4 + 9)
        val second = raw.copyOfRange(2 + 4 + 9 + 2, raw.size)
        assertTrue(!first.contentEquals(second))
        client.close()
    }

    @Test
    fun `each length class is written the way the spec says`() {
        val (client, peer) = connected()
        // 125 is the last 7-bit length, 126 the first that needs 16 bits, 65535 the last of those, 65536 the first of 64 bits.
        for ((size, code) in listOf(0 to 0, 1 to 1, 125 to 125, 126 to 126, 4000 to 126, 65535 to 126, 65536 to 127, 200_000 to 127)) {
            val text = "x".repeat(size)
            client.send(text)
            val got = peer.read()
            assertEquals("length code for $size", code, got.lengthCode)
            assertEquals(size, got.payload.size)
            assertEquals(text, String(got.payload))
        }
        client.close()
    }

    @Test
    fun `multi byte text goes out as utf 8`() {
        val (client, peer) = connected()
        val text = "Küche 18,4 °C — 🎵"
        client.send(text)
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), peer.read().payload)
        client.close()
    }

    @Test
    fun `messages of each length class arrive whole`() {
        val (client, peer) = connected()
        for (size in listOf(0, 1, 125, 126, 300, 65535, 65536, 300_000)) {
            val text = "y".repeat(size)
            peer.text(text)
            assertEquals("message:$text", next())
        }
        client.close()
    }

    @Test
    fun `a fragmented message is joined, with a ping allowed in the middle`() {
        val (client, peer) = connected()
        peer.write(1, "hel".toByteArray(), fin = false)
        peer.write(9, "are you there".toByteArray())
        peer.write(0, "l".toByteArray(), fin = false)
        peer.write(0, "o".toByteArray(), fin = true)
        assertEquals("message:hello", next())
        val pong = peer.read()
        assertEquals(10, pong.opcode)
        assertEquals("are you there", String(pong.payload))
        client.close()
    }

    @Test
    fun `a multi byte character split between fragments is not torn`() {
        val (client, peer) = connected()
        val bytes = "a🎵b".toByteArray(Charsets.UTF_8)
        peer.write(1, bytes.copyOfRange(0, 3), fin = false)
        peer.write(0, bytes.copyOfRange(3, bytes.size), fin = true)
        assertEquals("message:a🎵b", next())
        client.close()
    }

    @Test
    fun `a server ping is answered with a masked pong that echoes its payload`() {
        val (client, peer) = connected()
        peer.write(9, byteArrayOf(1, 2, 3))
        val pong = peer.read()
        assertEquals(10, pong.opcode)
        assertTrue(pong.masked)
        assertArrayEquals(byteArrayOf(1, 2, 3), pong.payload)
        // The connection goes on working.
        peer.text("after")
        assertEquals("message:after", next())
        client.close()
    }

    @Test
    fun `a pong from the server is ignored, and so is a binary message`() {
        val (client, peer) = connected()
        peer.write(10, ByteArray(0))
        peer.write(2, byteArrayOf(1, 2, 3))
        peer.text("text")
        assertEquals("message:text", next())
        client.close()
    }

    @Test
    fun `the client closing says goodbye and tells the listener once`() {
        val (client, peer) = connected()
        client.close()
        client.close()
        val bye = peer.read()
        assertEquals(8, bye.opcode)
        assertTrue(bye.masked)
        assertTrue(next().startsWith("closed:"))
        quiet()
    }

    @Test
    fun `the server closing is answered and reported once`() {
        val (client, peer) = connected()
        peer.write(8, byteArrayOf(0x03, 0xE8.toByte()))
        assertEquals("closed:Closed by the server", next())
        assertEquals(8, peer.read().opcode)
        quiet()
        client.close()
        quiet()
    }

    @Test
    fun `a dropped connection is reported once`() {
        val (client, peer) = connected()
        peer.socket.close()
        assertTrue(next().startsWith("closed:"))
        quiet()
        client.close()
    }

    @Test
    fun `sending after a close does nothing`() {
        val (client, _) = connected()
        client.close()
        assertTrue(next().startsWith("closed:"))
        client.send("late")
        quiet()
    }

    @Test
    fun `an answer that is not an upgrade is a failed connection`() {
        val (client, peer) = connected(handshake = false)
        peer.handshake(status = "400 Bad Request", accept = null)
        val closed = next()
        assertTrue(closed, closed.startsWith("closed:") && closed.contains("400"))
        quiet()
        client.close()
    }

    @Test
    fun `a wrong accept key is a failed connection`() {
        val (_, peer) = connected(handshake = false)
        peer.handshake(accept = { "AAAAAAAAAAAAAAAAAAAAAAAAAAA=" })
        assertTrue(next().contains("Accept"))
    }

    @Test
    fun `a server that does not exist is a failed connection`() {
        val address = address()
        listening.close()
        WsClient(address, "/ws").connect(listener)
        assertTrue(next().startsWith("closed:"))
        quiet()
    }

    @Test
    fun `a server that never answers the upgrade is given up on`() {
        val client = WsClient(address(), "/ws").also { it.connectTimeoutMs = 300 }
        client.connect(listener)
        sockets += listening.accept()
        val started = System.nanoTime()
        assertTrue(next().startsWith("closed:"))
        assertTrue(System.nanoTime() - started < 3_000_000_000L)
    }

    @Test
    fun `closing while still connecting ends it`() {
        val client = WsClient(address(), "/ws")
        client.connect(listener)
        sockets += listening.accept()
        client.close()
        assertTrue(next().startsWith("closed:"))
    }

    @Test
    fun `the frame codec round trips and the spec's own example key is accepted`() {
        // RFC 6455 section 1.3.
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsFrames.accept("dGhlIHNhbXBsZSBub25jZQ=="))
        assertEquals("", WsFrames.base64(ByteArray(0)))
        assertEquals("Zg==", WsFrames.base64("f".toByteArray()))
        assertEquals("Zm8=", WsFrames.base64("fo".toByteArray()))
        assertEquals("Zm9v", WsFrames.base64("foo".toByteArray()))
        assertEquals("Zm9vYg==", WsFrames.base64("foob".toByteArray()))
        val mask = byteArrayOf(1, 2, 3, 4)
        val frame = WsFrames.encode(1, "hi".toByteArray(), mask)
        assertEquals(0x81.toByte(), frame[0])
        assertEquals((0x80 or 2).toByte(), frame[1])
        assertArrayEquals(mask, frame.copyOfRange(2, 6))
        assertNotNull(WsFrames.readFrame(java.io.ByteArrayInputStream(frame)).payload)
        assertEquals("hi", String(WsFrames.readFrame(java.io.ByteArrayInputStream(frame)).payload))
    }

    @Test
    fun `server addresses`() {
        assertEquals("10.1.1.5", ServerAddress("http://10.1.1.5:3000").host)
        assertEquals(3000, ServerAddress("http://10.1.1.5:3000").port)
        assertEquals(80, ServerAddress("http://home.lan").port)
        assertEquals(443, ServerAddress("https://home.lan/").port)
        assertEquals("home.lan", ServerAddress("https://home.lan/dash?x=1").host)
        assertTrue(ServerAddress("https://home.lan").secure)
        assertEquals("::1", ServerAddress("http://[::1]:8080").host)
        assertEquals(8080, ServerAddress("http://[::1]:8080").port)
        assertEquals("[::1]:8080", ServerAddress("http://[::1]:8080").hostHeader())
    }

    @Test
    fun `closing before connecting opens no socket at all`() {
        val client = WsClient(address(), "/ws")
        client.close()
        client.connect(listener)
        assertTrue(next().startsWith("closed:"))
        listening.soTimeout = 300
        try {
            listening.accept().also { sockets += it }
            throw AssertionError("the client connected after it was closed")
        } catch (_: java.net.SocketTimeoutException) {
        }
    }

    @Test
    fun `a failed handshake leaves no socket open`() {
        val (_, peer) = connected(handshake = false)
        peer.handshake(status = "400 Bad Request", accept = null)
        assertTrue(next().startsWith("closed:"))
        assertEquals("the server sees the client hang up", -1, peer.input.read())
    }

    @Test
    fun `a message joined from fragments has a limit`() {
        val client = WsClient(address(), "/ws").also { it.maxMessageBytes = 1000 }
        client.connect(listener)
        val peer = Peer(listening.accept().also { sockets += it })
        peer.handshake()
        assertEquals("open", next())
        peer.write(1, ByteArray(600) { 'a'.code.toByte() }, fin = false)
        peer.write(0, ByteArray(600) { 'a'.code.toByte() }, fin = true)
        val closed = next()
        assertTrue(closed, closed.startsWith("closed:") && closed.contains("longer than"))
    }

    @Test
    fun `a frame that claims more than it sends costs only what it sent`() {
        val (client, peer) = connected()
        // A header for 30 MB, a few bytes, then the end of the connection.
        peer.output.write(byteArrayOf(0x81.toByte(), 127, 0, 0, 0, 0, 1, 0xC9.toByte(), 0xC3.toByte(), 0x80.toByte(), 1, 2, 3))
        peer.output.flush()
        peer.socket.close()
        assertTrue(next().startsWith("closed:"))
        client.close()
    }
}

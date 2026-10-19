package dev.hashsome.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** The real [RemoteClient] on the real [WsClient], against a runtime made of a local `ServerSocket`, with a queueing "main thread". */
class RemoteClientSocketTest {
    /** One thread that runs everything the client posts, in order, like a main looper. */
    private class Queue : Scheduler {
        val thread: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "queue-main").also { it.isDaemon = true } }
        private val start = System.nanoTime()

        override fun post(fn: () -> Unit) {
            thread.execute(fn)
        }

        override fun after(ms: Long, fn: () -> Unit): Cancel {
            val task = thread.schedule(fn, ms, TimeUnit.MILLISECONDS)
            return { task.cancel(false) }
        }

        override fun every(ms: Long, fn: () -> Unit): Cancel {
            val task = thread.scheduleWithFixedDelay(fn, ms, ms, TimeUnit.MILLISECONDS)
            return { task.cancel(false) }
        }

        override fun now(): Long = (System.nanoTime() - start) / 1_000_000

        override fun wallMs(): Long = System.currentTimeMillis()

        /** Runs [fn] on the queue and waits for its value. */
        fun <T> run(fn: () -> T): T = thread.submit<T> { fn() }.get(5, TimeUnit.SECONDS)
    }

    /** The runtime's end of one connection. */
    private class Server(val socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        fun handshake() {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
            val key = head.lines().first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
            output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${WsFrames.accept(key)}\r\n\r\n".toByteArray())
            output.flush()
        }

        /** The next text frame the client sent, parsed. */
        fun read(): JSONObject {
            while (true) {
                val frame = WsFrames.readFrame(input)
                if (frame.opcode == 1) return JSONObject(String(frame.payload))
            }
        }

        fun send(text: String) {
            val payload = text.toByteArray()
            val out = ByteArrayOutputStream()
            out.write(0x81)
            if (payload.size < 126) {
                out.write(payload.size)
            } else {
                out.write(126)
                out.write(payload.size shr 8)
                out.write(payload.size and 0xff)
            }
            out.write(payload)
            output.write(out.toByteArray())
            output.flush()
        }
    }

    private fun entityFrame(on: Boolean) =
        """{"type":"entity","ref":"ha:light.x","entity":{"kind":"light","name":"X","availability":"ready","on":$on,"capabilities":{"brightness":false,"colorTemperature":false,"color":false},"ref":"ha:light.x"}}"""

    @Test
    fun `connect, subscribe, an entity, a drop, a reconnect, and a close with a command in flight`() {
        val listening = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val queue = Queue()
        val client = RemoteClient(ServerAddress("http://127.0.0.1:${listening.localPort}"), WsClient.Factory, queue)
        val snapshots = LinkedBlockingQueue<Snapshot>()
        val links = LinkedBlockingQueue<LinkStatus>()
        val outcomes = LinkedBlockingQueue<Outcome>()
        try {
            queue.run {
                client.link.listen { links.add(it) }
                client.watch(EntityRef("ha:light.x")) { snapshots.add(it) }
                client.connect()
            }
            assertEquals(Snapshot.Loading, snapshots.poll(5, TimeUnit.SECONDS))

            // First connection: the client subscribes, the runtime answers.
            val first = Server(listening.accept())
            first.handshake()
            assertEquals(LinkStatus.Open, links.poll(5, TimeUnit.SECONDS).let { if (it == LinkStatus.Connecting) links.poll(5, TimeUnit.SECONDS) else it })
            val subscribe = first.read()
            assertEquals("subscribe", subscribe.getString("type"))
            assertEquals("ha:light.x", subscribe.getString("ref"))
            first.send(entityFrame(true))
            val present = snapshots.poll(5, TimeUnit.SECONDS) as Snapshot.Present
            assertTrue((present.entity as LightEntity).on)

            // The link drops: the client reconnects by itself and subscribes again.
            first.socket.close()
            val second = Server(listening.accept())
            second.handshake()
            val again = second.read()
            assertEquals("subscribe", again.getString("type"))
            second.send(entityFrame(false))
            val changed = snapshots.poll(5, TimeUnit.SECONDS) as Snapshot.Present
            assertEquals(false, (changed.entity as LightEntity).on)

            // A command the runtime never answers, and then the client is closed.
            queue.run { client.command(EntityRef("ha:light.x"), "toggle") { outcomes.add(it) } }
            assertEquals("command", second.read().getString("type"))
            queue.run { client.close() }
            assertEquals(Outcome.Failed("Connection lost"), outcomes.poll(5, TimeUnit.SECONDS))
            assertEquals(LinkStatus.Closed, queue.run { client.link.value })

            // And it stays closed: no third connection.
            listening.soTimeout = 1500
            try {
                listening.accept().close()
                throw AssertionError("the client reconnected after close()")
            } catch (_: java.net.SocketTimeoutException) {
            }
            assertNull(outcomes.poll(100, TimeUnit.MILLISECONDS))
        } finally {
            queue.run { client.close() }
            listening.close()
            queue.thread.shutdown()
        }
    }

    @Test
    fun `reconnecting to another address moves the watch to the new runtime`() {
        val one = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val two = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val queue = Queue()
        val client = RemoteClient(ServerAddress("http://127.0.0.1:${one.localPort}"), WsClient.Factory, queue)
        val snapshots = LinkedBlockingQueue<Snapshot>()
        val results = LinkedBlockingQueue<Result<JSONObject>>()
        try {
            queue.run {
                client.watch(EntityRef("ha:light.x")) { snapshots.add(it) }
                client.connect()
            }
            assertEquals(Snapshot.Loading, snapshots.poll(5, TimeUnit.SECONDS))
            val first = Server(one.accept())
            first.handshake()
            assertEquals("subscribe", first.read().getString("type"))
            first.send(entityFrame(true))
            assertTrue(((snapshots.poll(5, TimeUnit.SECONDS) as Snapshot.Present).entity as LightEntity).on)

            // A raw request the first runtime never answers, then the switch.
            queue.run { client.raw("ha", HaRequests.callService("light", "turn_on", listOf("light.x"))) { results.add(it) } }
            val raw = first.read()
            assertEquals("raw", raw.getString("type"))
            assertEquals("light.x", raw.getJSONObject("request").getJSONArray("entityIds").getString(0))
            queue.run { client.reconnect(ServerAddress("http://127.0.0.1:${two.localPort}")) }
            assertEquals("Connection lost", results.poll(5, TimeUnit.SECONDS)!!.exceptionOrNull()!!.message)

            val second = Server(two.accept())
            second.handshake()
            val again = second.read()
            assertEquals("subscribe", again.getString("type"))
            assertEquals("ha:light.x", again.getString("ref"))
            second.send(entityFrame(false))
            assertEquals(Snapshot.Loading, snapshots.poll(5, TimeUnit.SECONDS))
            assertEquals(false, ((snapshots.poll(5, TimeUnit.SECONDS) as Snapshot.Present).entity as LightEntity).on)
            assertEquals(LinkStatus.Open, queue.run { client.link.value })
            assertEquals("http://127.0.0.1:${two.localPort}", queue.run { client.server.baseUrl })
        } finally {
            queue.run { client.close() }
            one.close()
            two.close()
            queue.thread.shutdown()
        }
    }
}

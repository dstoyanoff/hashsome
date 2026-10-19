package dev.hashsome.client

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory

/** `wss://`: the certificate must be trusted and must be for the host asked for. A self-signed certificate for another name is made in the test. */
class WsTlsTest {
    private val events = LinkedBlockingQueue<String>()
    private lateinit var context: SSLContext
    private lateinit var server: SSLServerSocket
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
    fun serve() {
        val dir = File.createTempFile("hashsome-tls", "").also { it.delete() }.also { it.mkdirs() }
        val file = File(dir, "k.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val made = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "k", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=wrong.example",
            "-ext", "san=dns:wrong.example", "-validity", "2", "-storetype", "PKCS12", "-keystore", file.path, "-storepass", "changeit",
        ).redirectErrorStream(true).start()
        made.inputStream.readBytes()
        check(made.waitFor() == 0) { "keytool failed" }
        val store = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, "changeit".toCharArray()) } }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "changeit".toCharArray()) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
        server = context.serverSocketFactory.createServerSocket(0, 5, InetAddress.getLoopbackAddress()) as SSLServerSocket
        Thread {
            try {
                val socket = server.accept()
                val input = socket.getInputStream()
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                val key = head.lines().first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
                socket.getOutputStream().write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${WsFrames.accept(key)}\r\n\r\n".toByteArray())
                socket.getOutputStream().flush()
                Thread.sleep(2000)
                socket.close()
            } catch (_: Exception) {
            }
        }.also { it.isDaemon = true }.start()
    }

    @After
    fun stop() {
        server.close()
    }

    private fun client(verifier: HostnameVerifier?) = WsClient(ServerAddress("https://127.0.0.1:${server.localPort}"), "/ws").also {
        it.sslFactory = context.socketFactory
        it.hostnameVerifier = verifier
    }

    private fun next() = events.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("nothing happened")

    @Test
    fun `a certificate for another name is refused by the platform's own check`() {
        client(null).connect(listener)
        val event = next()
        assertTrue(event, event.startsWith("closed:"))
    }

    @Test
    fun `the verifier is asked about the host and the certificate that was presented, and can refuse`() {
        var asked: Pair<String, SSLSession>? = null
        client { host, session ->
            asked = host to session
            false
        }.connect(listener)
        val event = next()
        assertTrue(event, event.startsWith("closed:") && event.contains("127.0.0.1"))
        assertEquals("127.0.0.1", asked!!.first)
        assertTrue((asked!!.second.peerCertificates[0] as X509Certificate).subjectX500Principal.name.contains("wrong.example"))
    }

    @Test
    fun `a verifier that accepts lets the connection open`() {
        client { _, _ -> true }.connect(listener)
        assertEquals("open", next())
    }
}

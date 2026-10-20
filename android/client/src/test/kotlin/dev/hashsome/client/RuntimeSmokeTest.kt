package dev.hashsome.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The real [RemoteClient] and [WsClient] against a real runtime. Skipped unless a runtime is named:
 *
 *     pnpm --filter @hashsome/example dev          (a mock backend, nothing to configure)
 *     (cd android && ./gradlew :client:testDebugUnitTest --tests '*RuntimeSmokeTest' -Phashsome.smoke=localhost:3000)
 *
 * It sends one command, to `ha:light.plain`, which the mock backend has and a real house does not: never point this at a real home.
 */
class RuntimeSmokeTest {
    private val target: String? = System.getProperty("hashsome.smoke")?.takeIf { it.isNotBlank() }

    /** A one-thread "main loop" for the client, which expects to be called from a single thread. */
    private class Loop : Scheduler {
        val thread: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "smoke-main").also { it.isDaemon = true } }
        private val clock = System.nanoTime()

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

        override fun now(): Long = (System.nanoTime() - clock) / 1_000_000

        override fun wallMs(): Long = System.currentTimeMillis()
    }

    @Test
    fun `talks to a runtime`() {
        assumeTrue("pass -Phashsome.smoke=host:port to run this", target != null)
        val loop = Loop()
        val client = RemoteClient(ServerAddress("http://$target"), WsClient.Factory, loop)
        try {
            val opened = CountDownLatch(1)
            val present = CountDownLatch(1)
            val missing = CountDownLatch(1)
            val toggled = CountDownLatch(1)
            val queried = CountDownLatch(1)
            val browsed = CountDownLatch(1)
            var first: LightEntity? = null
            var after: LightEntity? = null
            var outcome: Outcome? = null
            var logbook: Result<LogbookResult>? = null
            var library: Result<BrowseResult>? = null
            loop.post {
                client.connect()
                client.link.listenNow { if (it == LinkStatus.Open) opened.countDown() }
                client.watch(EntityRef("ha:light.plain")) { snapshot ->
                    if (snapshot is Snapshot.Present) {
                        val light = snapshot.entity as LightEntity
                        if (first == null) {
                            first = light
                            present.countDown()
                            client.command(light.ref, LightCommands.toggle()) {
                                outcome = it
                                toggled.countDown()
                            }
                        } else if (light.on != first!!.on) {
                            after = light
                        }
                    }
                }
                client.watch(EntityRef("ha:no.such_entity")) { if (it == Snapshot.Missing) missing.countDown() }
                client.query(EntityRef("ha:light.plain"), Query.Logbook(5)) {
                    logbook = it
                    queried.countDown()
                }
                client.query(EntityRef("ma:living_room"), Query.Browse()) {
                    library = it
                    browsed.countDown()
                }
            }
            assertTrue("link did not open", opened.await(10, TimeUnit.SECONDS))
            assertTrue("no entity", present.await(10, TimeUnit.SECONDS))
            assertTrue("no answer to the command", toggled.await(10, TimeUnit.SECONDS))
            assertEquals(Outcome.Ok, outcome)
            assertTrue("no unknown-entity answer", missing.await(10, TimeUnit.SECONDS))
            assertTrue("no logbook", queried.await(10, TimeUnit.SECONDS))
            assertTrue("logbook: ${logbook?.exceptionOrNull()}", logbook!!.isSuccess)
            assertTrue("no library", browsed.await(10, TimeUnit.SECONDS))
            assertTrue("library: ${library?.exceptionOrNull()}", library!!.getOrThrow().items.isNotEmpty())
            Thread.sleep(500)
            assertEquals("the light followed the command", !first!!.on, after?.on)
            assertTrue(client.integrations.value.isNotEmpty())
        } finally {
            loop.post { client.close() }
            loop.thread.shutdown()
        }
    }
}

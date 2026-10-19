package dev.hashsome.kiosk

import dev.hashsome.testing.TestScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeAlarm(var works: Boolean = true) : RestartAlarm {
    val at = ArrayList<Long>()
    override fun schedule(atElapsedMs: Long): Boolean {
        at += atElapsedMs
        return works
    }
}

private class FakeBackend(
    var local: Boolean = false,
    var rpc: Boolean = false,
    var rebootWorks: Boolean = true,
) : RebootBackend {
    val calls = ArrayList<String>()
    override fun canRebootLocal() = local
    override fun shellyRpcAnswers() = rpc
    override fun rebootLocal(): Boolean { calls += "local"; return rebootWorks }
    override fun rebootShellyRpc(): Boolean { calls += "rpc"; return rebootWorks }
}

class RestartTest {
    @Test
    fun `restart app schedules the start 1_5 s ahead and only then finishes and kills`() {
        val alarm = FakeAlarm()
        val order = ArrayList<String>()
        val recording = RestartAlarm { at -> order += "alarm"; alarm.schedule(at) }
        AppRestarter(recording, { 10_000 }, { order += "finish" }, { order += "kill" }).restart()
        assertEquals(listOf(11_500L), alarm.at)
        assertEquals(listOf("alarm", "finish", "kill"), order)
    }

    @Test
    fun `restart app stays up when the alarm cannot be set`() {
        val order = ArrayList<String>()
        AppRestarter(FakeAlarm(works = false), { 0 }, { order += "finish" }, { order += "kill" }).restart()
        assertTrue(order.isEmpty())
    }

    private fun reboot(backend: RebootBackend) = Reboot(backend, { it() }, TestScheduler().also { sched = it })
    private var sched = TestScheduler()

    @Test
    fun `a Shelly profile reboots through the display's RPC when it answers`() {
        val backend = FakeBackend(rpc = true, local = true)
        val r = reboot(backend)
        assertFalse(r.canReboot())
        r.probe(shelly = true)
        sched.runCurrent()
        assertTrue(r.canReboot())
        assertEquals(RebootPath.ShellyRpc, r.path)
        r.rebootIfPossible()
        assertEquals(listOf("rpc"), backend.calls)
    }

    @Test
    fun `a generic profile needs the permission or device owner and ignores the RPC`() {
        val none = FakeBackend(rpc = true)
        reboot(none).also { it.probe(shelly = false); sched.runCurrent() }.let {
            assertFalse(it.canReboot())
            assertNull(it.path)
            it.rebootIfPossible()
            assertTrue(none.calls.isEmpty())
        }
        val owner = FakeBackend(local = true)
        reboot(owner).also { it.probe(shelly = false); sched.runCurrent() }.let {
            assertEquals(RebootPath.Local, it.path)
            it.rebootIfPossible()
            assertEquals(listOf("local"), owner.calls)
        }
    }

    @Test
    fun `a Shelly profile whose RPC is silent falls back to the permission, else nothing`() {
        assertEquals(RebootPath.Local, reboot(FakeBackend(local = true)).choose(shelly = true))
        assertNull(reboot(FakeBackend()).choose(shelly = true))
    }

    @Test
    fun `the answer waits for the probe and a failed reboot only reports false`() {
        val r = reboot(FakeBackend(rpc = true, rebootWorks = false))
        r.probe(shelly = true)
        assertFalse(r.canReboot())
        sched.runCurrent()
        assertTrue(r.canReboot())
        r.rebootIfPossible()
    }

    @Test
    fun `the reboot never runs on the calling thread`() {
        val threads = ArrayList<Thread>()
        val backend = object : RebootBackend {
            override fun canRebootLocal() = false
            override fun shellyRpcAnswers() = true
            override fun rebootLocal() = true
            override fun rebootShellyRpc(): Boolean { threads += Thread.currentThread(); return true }
        }
        val r = Reboot(backend, { work -> Thread(work).also { it.start(); it.join() } }, sched)
        r.probe(shelly = true)
        sched.runCurrent()
        r.rebootIfPossible()
        assertEquals(1, threads.size)
        assertTrue(threads[0] !== Thread.currentThread())
    }

    @Test
    fun `the probe looks again every 5 minutes until a way turns up`() {
        val backend = FakeBackend()
        val r = reboot(backend)
        r.probe(shelly = true)
        sched.runCurrent()
        assertFalse(r.canReboot())
        sched.advance(Reboot.RETRY_MS - 1)
        assertFalse(r.canReboot())
        backend.rpc = true
        sched.advance(1)
        assertTrue(r.canReboot())
        assertEquals(RebootPath.ShellyRpc, r.path)
    }

    @Test
    fun `a closed probe stops looking`() {
        val backend = FakeBackend()
        val r = reboot(backend)
        r.probe(shelly = true)
        sched.runCurrent()
        r.close()
        backend.rpc = true
        sched.advance(Reboot.RETRY_MS * 2)
        assertFalse(r.canReboot())
    }

    @Test
    fun `the status line is read`() {
        assertEquals(200, DeviceRebootBackend.statusOf("HTTP/1.0 200 OK"))
        assertEquals(401, DeviceRebootBackend.statusOf("HTTP/1.1 401 Unauthorized"))
        assertEquals(-1, DeviceRebootBackend.statusOf(null))
        assertEquals(-1, DeviceRebootBackend.statusOf("garbage"))
    }
}

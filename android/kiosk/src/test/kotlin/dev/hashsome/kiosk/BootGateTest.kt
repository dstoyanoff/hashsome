package dev.hashsome.kiosk

import dev.hashsome.testing.TestScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootGateTest {
    private val sched = TestScheduler()
    private val logs = ArrayList<String>()
    private val splash = TopActivity("cloud.shelly.stargate", "cloud.shelly.stargate.activities.SplashActivity")
    private val stock = TopActivity("cloud.shelly.stargate", "cloud.shelly.stargate.activities.MainActivity")

    private fun gate() = BootGate(30_000, 90_000, { sched.now() }) { logs += it }

    @Test
    fun `stays shut while the splash shows and for the settle time after it`() {
        val g = gate()
        sched.advance(70_000)
        assertFalse(g.admit(splash))
        sched.advance(2_000)
        assertFalse(g.admit(splash))
        sched.advance(2_000)
        assertFalse(g.admit(stock))
        sched.advance(29_000)
        assertFalse(g.admit(stock))
        sched.advance(1_000)
        assertTrue(g.admit(stock))
    }

    @Test
    fun `waits for the minimum time since boot even when already stable`() {
        val g = gate()
        sched.advance(10_000)
        assertFalse(g.admit(stock))
        sched.advance(40_000)
        assertFalse(g.admit(stock))
        sched.advance(40_000)
        assertTrue(g.admit(stock))
    }

    @Test
    fun `a change of the top activity restarts the settle time`() {
        val g = gate()
        sched.advance(100_000)
        assertFalse(g.admit(stock))
        sched.advance(20_000)
        assertFalse(g.admit(TopActivity("com.other", "com.other.A")))
        sched.advance(29_999)
        assertFalse(g.admit(TopActivity("com.other", "com.other.A")))
        sched.advance(1)
        assertTrue(g.admit(TopActivity("com.other", "com.other.A")))
    }

    @Test
    fun `once open it stays open and logs both lines once`() {
        val g = gate()
        assertFalse(g.admit(stock))
        assertFalse(g.admit(stock))
        sched.advance(100_000)
        assertTrue(g.admit(stock))
        assertTrue(g.admit(splash))
        assertEquals(listOf("boot: waiting for the stock app to settle", "boot: settled, taking the front"), logs)
    }
}

class BootGateSystemAppTest {
    private val sched = TestScheduler()
    private val logs = ArrayList<String>()
    private val splash = TopActivity("cloud.shelly.stargate", "cloud.shelly.stargate.activities.SplashActivity")

    @Test
    fun `a system app has no gate, even over the splash`() {
        val g = BootGate(30_000, 90_000, { sched.now() }, systemApp = true) { logs += it }
        assertTrue(g.open)
        assertTrue(g.admit(splash))
        assertEquals(listOf("boot: system app, no gate"), logs)
    }

    @Test
    fun `a non-system app keeps the conservative gate`() {
        val g = BootGate(30_000, 90_000, { sched.now() }, systemApp = false) { logs += it }
        assertFalse(g.open)
        sched.advance(10_000)
        assertFalse(g.admit(splash))
        assertTrue(logs.none { it.contains("system app") })
    }

    @Test
    fun `a system app launches on the first tick with the splash on top`() {
        var launches = 0
        val keeper = FrontKeeper(
            "dev.hashsome.app", { sched.now() }, { BootGate(30_000, 90_000, { sched.now() }, systemApp = true) }, { splash },
            { false }, { false }, { launches++ }, overSplash = { true },
        )
        keeper.check()
        assertEquals(1, launches)
    }

    @Test
    fun `over the splash is only for a system app`() {
        assertTrue(FrontDecision.shouldLaunch("cloud.shelly.stargate", "dev.hashsome.app", false, splash.cls, true) { false })
        assertFalse(FrontDecision.shouldLaunch("cloud.shelly.stargate", "dev.hashsome.app", false, splash.cls, false) { false })
    }
}

class FrontKeeperTest {
    private val sched = TestScheduler()
    private var top = TopActivity("cloud.shelly.stargate", "cloud.shelly.stargate.activities.SplashActivity")
    private var resumed = false
    private var launches = 0
    private var gate: BootGate? = null

    private val keeper = FrontKeeper("dev.hashsome.app", { sched.now() }, { gate }, { top }, { resumed }, { false }, { launches++ })

    private fun ticks(ms: Long) {
        val cancel = sched.every(2_000) { keeper.check() }
        sched.advance(ms)
        cancel()
    }

    @Test
    fun `without a gate it launches at once, except over the splash`() {
        ticks(10_000)
        assertEquals(0, launches)
        top = TopActivity("cloud.shelly.stargate", "cloud.shelly.stargate.activities.MainActivity")
        ticks(2_000)
        assertEquals(1, launches)
    }

    @Test
    fun `a boot gate keeps it from launching until the stock app settled`() {
        gate = BootGate(30_000, 90_000, { sched.now() })
        top = TopActivity("cloud.shelly.stargate", "cloud.shelly.stargate.activities.MainActivity")
        ticks(88_000)
        assertEquals(0, launches)
        ticks(34_000)
        assertTrue(launches > 0)
    }

    @Test
    fun `a dashboard in front is never launched`() {
        resumed = true
        ticks(100_000)
        assertEquals(0, launches)
    }
}

class BootArmingTest {
    @Test
    fun `a start inside the settle window arms the gate on a Shelly profile, a later one does not`() {
        assertTrue(Kiosk.shouldArmBootGate(boot = false, elapsedMs = 20_000, minMs = 90_000, settles = true))
        assertTrue(Kiosk.shouldArmBootGate(boot = true, elapsedMs = 200_000, minMs = 90_000, settles = true))
        assertFalse(Kiosk.shouldArmBootGate(boot = false, elapsedMs = 100_000, minMs = 90_000, settles = true))
        assertFalse(Kiosk.shouldArmBootGate(boot = true, elapsedMs = 20_000, minMs = 90_000, settles = false))
    }
}

class TopTrackerTest {
    private val sched = TestScheduler()
    private val tracker = TopTracker({ sched.now() })
    private val cls = "cloud.shelly.stargate.activities.SplashActivity"

    @Test
    fun `a splash is dropped after three minutes as the last event`() {
        tracker.moved("cloud.shelly.stargate", cls)
        sched.advance(180_000)
        assertEquals(cls, tracker.current().cls)
        sched.advance(1)
        assertEquals(null, tracker.current().cls)
        assertEquals("cloud.shelly.stargate", tracker.current().pkg)
    }

    @Test
    fun `the same event replayed does not restart the timer`() {
        tracker.moved("cloud.shelly.stargate", cls)
        sched.advance(100_000)
        tracker.moved("cloud.shelly.stargate", cls)
        sched.advance(81_000)
        assertEquals(null, tracker.current().cls)
    }
}

class UnknownTopTest {
    @Test
    fun `without usage access the gate opens on time alone`() {
        val sched = TestScheduler()
        val g = BootGate(30_000, 90_000, { sched.now() })
        sched.advance(10_000)
        assertFalse(g.admit(TopActivity.UNKNOWN))
        sched.advance(79_999)
        assertFalse(g.admit(TopActivity.UNKNOWN))
        sched.advance(1)
        assertTrue(g.admit(TopActivity.UNKNOWN))
    }
}

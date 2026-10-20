package dev.hashsome.kiosk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontDecisionTest {
    private val own = "dev.hashsome.app"

    private fun launch(top: String?, resumed: Boolean, visible: Boolean = false) = FrontDecision.shouldLaunch(top, own, resumed) { visible }

    @Test
    fun `the dashboard being the resumed activity never launches`() {
        assertFalse(launch(null, resumed = true))
        assertFalse(launch("com.shelly.stock", resumed = true))
    }

    @Test
    fun `our package on top does not launch`() = assertFalse(launch(own, resumed = false))

    @Test
    fun `another package on top launches`() = assertTrue(launch("com.shelly.stock", resumed = false))

    @Test
    fun `an unknown top falls back to the process importance`() {
        assertFalse(launch(null, resumed = false, visible = true))
        assertTrue(launch(null, resumed = false, visible = false))
    }
}

class StockSplashTest {
    private val own = "dev.hashsome.app"
    private val splash = "cloud.shelly.stargate.activities.SplashActivity"

    @Test
    fun `the stock app's splash is never launched over`() {
        assertFalse(FrontDecision.shouldLaunch("cloud.shelly.stargate", own, false, splash) { false })
    }

    @Test
    fun `another stock activity is launched over`() {
        assertTrue(FrontDecision.shouldLaunch("cloud.shelly.stargate", own, false, "cloud.shelly.stargate.activities.MainActivity") { false })
    }
}

class FrontPauseTest {
    private var now = 0L
    private val logs = ArrayList<String>()
    private val pause = FrontPause({ now }, { logs += it })

    @org.junit.Test
    fun `it holds for the window and then lets go`() {
        org.junit.Assert.assertFalse(pause.active(false))
        pause.start(600_000)
        now = 599_999
        org.junit.Assert.assertTrue(pause.active(false))
        now = 600_000
        org.junit.Assert.assertFalse(pause.active(false))
        org.junit.Assert.assertFalse(pause.active(false))
    }

    @org.junit.Test
    fun `it does not end while the dashboard is still resumed right after the start, but when it comes back`() {
        pause.start(600_000)
        org.junit.Assert.assertTrue(pause.active(true))
        org.junit.Assert.assertTrue(pause.active(false))
        now = 60_000
        org.junit.Assert.assertFalse(pause.active(true))
        org.junit.Assert.assertTrue(logs.last().contains("dashboard is back"))
    }

    @org.junit.Test
    fun `the keeper does not launch while paused`() {
        val launches = ArrayList<Int>()
        val keeper = FrontKeeper("me", { now }, { null }, { TopActivity("cloud.shelly.stargate", null) }, { false }, { false }, { launches += 1 }, pause = pause)
        pause.start(600_000)
        keeper.check()
        org.junit.Assert.assertTrue(launches.isEmpty())
        now = 600_000
        keeper.check()
        org.junit.Assert.assertEquals(1, launches.size)
    }
}

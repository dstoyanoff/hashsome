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

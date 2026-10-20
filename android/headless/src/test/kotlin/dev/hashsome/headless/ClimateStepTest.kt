package dev.hashsome.headless

import dev.hashsome.client.ClimateAction
import dev.hashsome.client.ClimateCapabilities
import dev.hashsome.client.ClimateMode
import dev.hashsome.client.EntityKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// climate-tile.test.tsx and climate-tile.tsx: the target held while stepping, the modes to offer, the summary of the drawer.
class ClimateStepTest {
    private val rig = Rig()
    private val commands get() = rig.client.commands.map { it.second to it.third }

    private fun control(
        target: Double? = 17.0,
        capabilities: ClimateCapabilities? = null,
        action: ClimateAction? = null,
        humidity: Double? = null,
    ): ClimateControl {
        val base = climate(target = target)
        rig.client.set(ref("heater"), base.copy(capabilities = capabilities ?: base.capabilities, action = action, humidity = humidity))
        return ClimateControl(rig.handle(ref("heater"), EntityKind.Climate)).also { rig.settle() }
    }

    @Test
    fun `a step is shown at once and the next one adds to it, not to the old reading`() {
        val control = control(target = 17.0)
        control.nudge(1)
        assertEquals(17.5, control.state.value.shown!!, 0.0)
        assertEquals(17.0, control.state.value.target!!, 0.0)
        control.nudge(1)
        control.nudge(1)
        assertEquals(18.5, control.state.value.shown!!, 0.0)
        assertEquals(listOf(17.5, 18.0, 18.5), commands.map { (it.second as org.json.JSONObject).getDouble("temperature") })
    }

    @Test
    fun `the held target lets go once the device reports it`() {
        val control = control(target = 17.0)
        control.nudge(2)
        rig.client.set(ref("heater"), climate(target = 18.0))
        rig.settle()
        assertEquals(18.0, control.state.value.shown!!, 0.0)
        // Another device change now shows through: nothing is held any more.
        rig.client.set(ref("heater"), climate(target = 21.0))
        rig.settle()
        assertEquals(21.0, control.state.value.shown!!, 0.0)
    }

    @Test
    fun `a target stays inside the device's range, rounded to hundredths`() {
        val control = control(target = 29.5)
        control.nudge(4)
        assertEquals(30.0, control.state.value.shown!!, 0.0)
        control.setTarget(7.004)
        assertEquals(7.0, control.state.value.shown!!, 0.0)
        control.setTarget(1.0)
        assertEquals(5.0, control.state.value.shown!!, 0.0)
    }

    @Test
    fun `a device with no target cannot be stepped`() {
        val control = control(target = null)
        control.nudge(1)
        assertNull(control.state.value.shown)
        assertEquals(emptyList<Any>(), commands)
    }

    @Test
    fun `a device that reports no modes can be switched off and heated`() {
        val none = climate().capabilities.copy(modes = emptyList())
        assertEquals(listOf(ClimateMode.Off, ClimateMode.Heat), control(capabilities = none).state.value.modes)
        assertEquals(listOf(ClimateMode.Off, ClimateMode.Heat), control().state.value.modes)
        val more = climate().capabilities.copy(modes = listOf(ClimateMode.Off, ClimateMode.Heat, ClimateMode.Auto))
        assertEquals(3, control(capabilities = more).state.value.modes.size)
    }

    @Test
    fun `the summary says what it is doing, the reading and the humidity`() {
        assertEquals("Heat · 16.4 °C now", control().state.value.summary)
        assertEquals("Heating · 16.4 °C now · 45% humidity", control(action = ClimateAction.Heating, humidity = 45.0).state.value.summary)
        rig.client.set(ref("heater"), climate(mode = ClimateMode.Off, current = 20.0))
        rig.settle()
        assertEquals("Off · 20 °C now", ClimateControl(rig.handle(ref("heater"), EntityKind.Climate)).also { rig.settle() }.state.value.summary)
    }

    @Test
    fun `modes are written as words`() {
        assertEquals("Heatcool", ClimateControl.title("heatCool").replace("C", "c"))
        assertEquals("Fan only", ClimateControl.title("fan_only"))
        assertEquals("17", ClimateControl.number(17.0))
        assertEquals("16.4", ClimateControl.number(16.4))
    }
}

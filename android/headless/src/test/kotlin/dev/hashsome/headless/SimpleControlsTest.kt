package dev.hashsome.headless

import dev.hashsome.client.Availability
import dev.hashsome.client.ClimateMode
import dev.hashsome.client.EntityKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// switch-tile.tsx (home), climate-tile.test.tsx, action-button.test.tsx.
class SimpleControlsTest {
    private val rig = Rig()
    private val names get() = rig.client.commands.map { it.second }

    // --- SwitchControl -----------------------------------------------------------------------------------------

    @Test
    fun `a switch shows whether it is on, and a tap flips it`() {
        rig.client.set(ref("plug"), switch(on = false))
        val control = SwitchControl(rig.handle(ref("plug"), EntityKind.Switch))
        rig.settle()
        assertEquals(false, control.on.value)
        control.toggle()
        rig.settle()
        assertEquals(listOf("toggle"), names)
        assertTrue(control.on.value)
    }

    @Test
    fun `what a switch draws comes from its power sensor`() {
        rig.client.set(ref("plug"), switch(on = true))
        rig.client.set(ref("plug_power"), sensor(id = "plug_power", value = "42.35", unit = "W", measurement = "power"))
        val control = SwitchControl(rig.handle(ref("plug"), EntityKind.Switch), rig.handle(ref("plug_power"), EntityKind.Sensor))
        rig.settle()
        assertEquals(42.35, control.watts.value!!, 0.0)
        rig.client.set(ref("plug_power"), sensor(id = "plug_power", value = "0", unit = "W", measurement = "power"))
        rig.settle()
        assertEquals(0.0, control.watts.value!!, 0.0)
    }

    @Test
    fun `no power sensor, or one that is not a number, means no watts`() {
        rig.client.set(ref("plug"), switch())
        assertNull(SwitchControl(rig.handle(ref("plug"), EntityKind.Switch)).watts.value)
        rig.client.set(ref("p"), sensor(id = "p", value = "unavailable", unit = null))
        val control = SwitchControl(rig.handle(ref("plug"), EntityKind.Switch), rig.handle(ref("p"), EntityKind.Sensor))
        rig.settle()
        assertNull(control.watts.value)
    }

    @Test
    fun `a switch that is missing or unreachable is off`() {
        val control = SwitchControl(rig.handle(ref("ghost"), EntityKind.Switch))
        rig.settle()
        assertEquals(false, control.on.value)
    }

    // --- ClimateControl ----------------------------------------------------------------------------------------

    @Test
    fun `a climate shows its mode, target and current temperature`() {
        rig.client.set(ref("heater"), climate(mode = ClimateMode.Heat, target = 17.0, current = 16.4))
        val control = ClimateControl(rig.handle(ref("heater"), EntityKind.Climate))
        rig.settle()
        assertEquals(ClimateMode.Heat, control.mode.value)
        assertEquals(17.0, control.target.value!!, 0.0)
        assertEquals(16.4, control.current.value!!, 0.0)
    }

    @Test
    fun `a climate that is not there is off with nothing to show`() {
        val control = ClimateControl(EntityHandle.fixed(HandleState.Unavailable(null)))
        assertEquals(ClimateMode.Off, control.mode.value)
        assertNull(control.target.value)
        assertNull(control.current.value)
    }

    @Test
    fun `the target is set in the device's range and in hundredths`() {
        rig.client.set(ref("heater"), climate())
        val control = ClimateControl(rig.handle(ref("heater"), EntityKind.Climate))
        rig.settle()
        control.setTarget(17.5)
        control.setTarget(99.0)
        control.setTarget(1.0)
        control.setTarget(20.123)
        assertEquals(listOf(17.5, 30.0, 5.0, 20.12), rig.client.commands.map { it.third!!.getDouble("temperature") })
        assertEquals("setTargetTemperature", names[0])
        rig.settle()
        assertEquals(20.12, control.target.value!!, 0.0)
    }

    @Test
    fun `mode and preset are sent`() {
        rig.client.set(ref("heater"), climate())
        val control = ClimateControl(rig.handle(ref("heater"), EntityKind.Climate))
        control.setMode(ClimateMode.Off)
        control.setPreset("eco")
        assertEquals(listOf("setMode", "setPreset"), names)
        assertEquals("off", rig.client.commands[0].third!!.getString("mode"))
        assertEquals("eco", rig.client.commands[1].third!!.getString("preset"))
    }

    // --- ActionControl -----------------------------------------------------------------------------------------

    @Test
    fun `an action is pending until the runtime answers`() {
        rig.client.set(ref("scene"), action())
        val control = ActionControl(rig.handle(ref("scene"), EntityKind.Action))
        rig.settle()
        control.trigger()
        assertTrue(control.pending.value)
        rig.settle()
        assertEquals(false, control.pending.value)
        assertNull(control.error.value)
        assertEquals(listOf("trigger"), names)
    }

    @Test
    fun `a trigger while one is pending is ignored`() {
        rig.client.set(ref("scene"), action())
        val control = ActionControl(rig.handle(ref("scene"), EntityKind.Action))
        rig.settle()
        control.trigger()
        control.trigger()
        assertEquals(1, rig.client.commands.size)
    }

    @Test
    fun `a failure is flagged with the error until the next trigger clears it`() {
        rig.client.set(ref("scene"), action())
        val control = ActionControl(rig.handle(ref("scene"), EntityKind.Action))
        rig.settle()
        rig.client.failNext("Script not found")
        control.trigger()
        rig.settle()
        assertEquals("Script not found", control.error.value)
        assertEquals(false, control.pending.value)
        control.trigger()
        assertNull(control.error.value)
        rig.settle()
        assertNull(control.error.value)
    }

    @Test
    fun `an action that is not there says there is nothing to trigger`() {
        val control = ActionControl(rig.handle(ref("nope"), EntityKind.Action))
        rig.settle()
        control.trigger()
        assertEquals(ActionControl.NOTHING_TO_TRIGGER, control.error.value)
        assertEquals(false, control.pending.value)
        assertEquals(0, rig.client.commands.size)
    }

    @Test
    fun `an unreachable action can still be asked, as on the web`() {
        rig.client.set(ref("scene"), action().copy(availability = Availability.Unavailable))
        val control = ActionControl(rig.handle(ref("scene"), EntityKind.Action))
        rig.settle()
        control.trigger()
        assertEquals(1, rig.client.commands.size)
    }

    @Test
    fun `an action given as a stand-in handle reports its failure`() {
        val control = ActionControl(EntityHandle.fixed(HandleState.Ready(action())))
        control.trigger()
        assertEquals("No entity to command", control.error.value)
    }
}

package dev.hashsome.headless

import dev.hashsome.client.EntityKind
import dev.hashsome.client.LightEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// light-tile.test.tsx: toggling, the live percentage while dragging, a drag to the far left, and the commands.
class LightControlTest {
    private val rig = Rig()

    private fun control(light: LightEntity = light()): LightControl {
        rig.client.set(ref("lamp"), light)
        return LightControl(rig.handle(ref("lamp"), EntityKind.Light)).also { rig.settle() }
    }

    private val names get() = rig.client.commands.map { it.second }

    @Test
    fun `shows the level and whether it is on`() {
        val c = control(light(brightness = 0.46))
        assertEquals(46, c.shown.value)
        assertTrue(c.on.value)
    }

    @Test
    fun `an off light shows nothing, and a light that cannot dim shows full while on`() {
        assertEquals(0, control(light(on = false, brightness = 0.8)).shown.value)
        val plain = control(light(brightness = null, dimmable = false))
        assertEquals(100, plain.shown.value)
    }

    @Test
    fun `a light that is not there shows off`() {
        val c = LightControl(rig.handle(ref("ghost"), EntityKind.Light))
        rig.settle()
        assertEquals(0, c.shown.value)
        assertEquals(false, c.on.value)
    }

    @Test
    fun `toggle sends toggle and the light follows when the runtime reports it`() {
        val c = control(light(on = false))
        c.toggle()
        assertEquals(listOf("toggle"), names)
        rig.settle()
        assertTrue(c.on.value)
    }

    @Test
    fun `dragging shows the live percentage before anything is sent`() {
        val c = control(light(brightness = 0.2))
        c.drag(70)
        assertEquals(70, c.shown.value)
        assertEquals(emptyList<String>(), names)
        rig.settle()
        assertEquals(70, c.shown.value)
    }

    @Test
    fun `committing sends the brightness and the bar stays put until the light reports it`() {
        rig.client.onCommand = null
        val c = control(light(brightness = 0.2))
        c.drag(70)
        c.commit(70)
        rig.settle()
        assertEquals(listOf("setBrightness"), names)
        assertEquals(0.7, rig.client.commands[0].third!!.getDouble("brightness"), 1e-9)
        // The light has not said anything yet: it does not snap back to 20.
        assertEquals(70, c.shown.value)
        rig.client.set(ref("lamp"), light(brightness = 0.7))
        rig.settle()
        assertEquals(70, c.shown.value)
        // Released: a later change from elsewhere is shown.
        rig.client.set(ref("lamp"), light(brightness = 0.3))
        rig.settle()
        assertEquals(30, c.shown.value)
    }

    @Test
    fun `dragging to the far left turns the light off`() {
        val c = control(light(brightness = 0.5))
        c.commit(0)
        rig.settle()
        assertEquals(0.0, rig.client.commands[0].third!!.getDouble("brightness"), 0.0)
        assertEquals(false, c.on.value)
        assertEquals(0, c.shown.value)
    }

    @Test
    fun `a level out of range is kept inside it`() {
        val c = control()
        c.drag(140)
        assertEquals(100, c.shown.value)
        c.drag(-5)
        assertEquals(0, c.shown.value)
    }

    @Test
    fun `color temperature and color are sent as the commands say`() {
        val c = control(light(colorTemperature = true, color = true))
        c.setKelvin(2700)
        c.setHs(120.0, 80.0)
        assertEquals(listOf("setColorTemperature", "setColor"), names)
        assertEquals(2700, rig.client.commands[0].third!!.getInt("kelvin"))
        assertEquals(120.0, rig.client.commands[1].third!!.getDouble("hue"), 0.0)
        assertEquals(80.0, rig.client.commands[1].third!!.getDouble("saturation"), 0.0)
    }
}

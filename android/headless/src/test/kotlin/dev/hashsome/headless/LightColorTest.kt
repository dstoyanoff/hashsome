package dev.hashsome.headless

import dev.hashsome.client.EntityKind
import dev.hashsome.client.LightColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// light-color.test.ts and the swatches of light-tile.test.tsx.
class LightColorTest {
    @Test
    fun `warm whites are redder than cool whites`() {
        val warm = LightColors.kelvinToRgb(2200)
        val cool = LightColors.kelvinToRgb(6500)
        assertTrue(warm.r > warm.b)
        assertTrue(cool.b >= cool.r - 5)
        assertTrue(warm.b < cool.b)
    }

    @Test
    fun `the curve fit gives the known values`() {
        assertEquals(Rgb(255, 167, 87), LightColors.kelvinToRgb(2700))
        assertEquals(Rgb(255, 254, 250), LightColors.kelvinToRgb(6500))
        assertEquals(Rgb(255, 146, 39), LightColors.kelvinToRgb(2200))
    }

    @Test
    fun `hue and saturation round trip through rgb`() {
        assertEquals(Rgb(0, 255, 0), LightColors.hsToRgb(120.0, 100.0))
        assertEquals(120 to 100, LightColors.rgbToHs(LightColors.hsToRgb(120.0, 100.0)))
        assertEquals(0 to 0, LightColors.rgbToHs(Rgb(0, 0, 0)))
        assertEquals(240 to 100, LightColors.rgbToHs(Rgb(0, 0, 255)))
        assertEquals(Rgb(255, 255, 255), LightColors.hsToRgb(10.0, 0.0))
    }

    private fun setup(init: () -> dev.hashsome.client.LightEntity): Pair<Rig, LightControl> {
        val rig = Rig()
        rig.client.set(ref("lamp"), init())
        val control = LightControl(rig.handle(ref("lamp"), EntityKind.Light))
        rig.settle()
        return rig to control
    }

    @Test
    fun `a light that takes a white temperature gets the four whites, by kelvin`() {
        val (_, control) = setup { light(colorTemperature = true) }
        assertEquals(
            listOf("Candle" to 2200, "Warm white" to 2700, "Neutral white" to 4000, "Daylight" to 6500),
            control.presets.map { it.label to it.kelvin },
        )
    }

    @Test
    fun `color-only lights apply a white preset as an approximate hs color`() {
        val (_, control) = setup { light(color = true) }
        assertEquals(4, control.presets.size)
        val candle = control.presets.first()
        assertEquals(null, candle.kelvin)
        val (hue, saturation) = LightColors.rgbToHs(LightColors.kelvinToRgb(2200))
        assertEquals(hue.toDouble(), candle.hue!!, 0.0)
        assertEquals(saturation.toDouble(), candle.saturation!!, 0.0)
    }

    @Test
    fun `swatches a light cannot apply are left out`() {
        val (_, control) = setup { light(dimmable = true) }
        assertEquals(emptyList<LightPreset>(), control.presets)
    }

    @Test
    fun `a preset is active only when it matches the live color`() {
        val warm = LightPreset("Warm white", kelvin = 2700)
        val (rig, control) = setup { light(colorTemperature = true, lightColor = LightColor.Temperature(2700)) }
        assertTrue(control.isActive(warm))
        rig.client.set(ref("lamp"), light(colorTemperature = true, lightColor = LightColor.Hs(1.0, 1.0)))
        rig.settle()
        assertEquals(false, control.isActive(warm))
        rig.client.set(ref("lamp"), light(colorTemperature = true))
        rig.settle()
        assertEquals(false, control.isActive(warm))
        rig.client.set(ref("lamp"), light(color = true, lightColor = LightColor.Hs(120.4, 99.6)))
        rig.settle()
        assertTrue(control.isActive(LightPreset(null, hue = 120.0, saturation = 100.0)))
    }
}

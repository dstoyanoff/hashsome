package dev.hashsome.ui

import dev.hashsome.client.WeatherCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherLookTest {
    @Test
    fun `every condition has a look and a label`() {
        for (c in WeatherCondition.entries) {
            val look = WeatherLook.of(c)
            assertTrue(look.label.isNotEmpty())
        }
        assertEquals(WeatherCondition.entries.size, WeatherLook.looks.size)
    }

    @Test
    fun `the colors are the web's`() {
        assertEquals(0xFFFBBF24.toInt(), WeatherLook.of(WeatherCondition.Sunny).color)
        // The cloud of a partly cloudy sky is the last shape of the icon; the rest is the sun.
        assertEquals(mapOf(5 to 0xFF60A5FA.toInt()), WeatherLook.of(WeatherCondition.PartlyCloudy).parts)
        assertEquals(0xFF3B82F6.toInt(), WeatherLook.of(WeatherCondition.Rainy).color)
        assertEquals("Thunderstorm with rain", WeatherLook.of(WeatherCondition.LightningRainy).label)
    }

    @Test
    fun `a glyph without colors of its own takes the text color, one with some does not`() {
        assertFalse(WeatherLook.of(WeatherCondition.Windy).colored)
        assertNull(WeatherLook.of(WeatherCondition.Windy).color)
        assertTrue(WeatherLook.of(WeatherCondition.Sunny).colored)
        // Fog: a blue cloud and lines in the text color, drawn over it.
        val fog = WeatherLook.of(WeatherCondition.Fog)
        assertTrue(fog.colored)
        assertNotNull(fog.plain)
        assertNull(WeatherLook.of(WeatherCondition.Sunny).plain)
        assertTrue(WeatherLook.isColored(fog.icon.res))
        assertEquals(fog.plain, WeatherLook.plainOf(fog.icon.res))
    }

    @Test
    fun `night changes nothing, as on the web`() {
        assertEquals(WeatherLook.of(WeatherCondition.Sunny).icon, UiIcons.weather(WeatherCondition.Sunny, night = true))
        assertEquals(WeatherLook.of(WeatherCondition.Sunny).icon, UiIcons.weather(WeatherCondition.Sunny))
        assertEquals(WeatherLook.of(WeatherCondition.Rainy).icon, UiIcons.weather(WeatherCondition.Rainy, night = true))
    }
}

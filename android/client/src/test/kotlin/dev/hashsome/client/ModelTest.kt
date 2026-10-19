package dev.hashsome.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    @Test
    fun `an entity ref splits at the first colon`() {
        val ref = EntityRef("ha:light.bathroom_led")
        assertEquals("ha", ref.integration)
        assertEquals("light.bathroom_led", ref.id)
        assertEquals("ma:player:1", EntityRef.of("ma", "player:1").value)
        assertEquals("player:1", EntityRef("ma:player:1").id)
    }

    @Test
    fun `an entity ref knows when it is malformed`() {
        assertTrue(EntityRef("ha:x").isValid)
        assertFalse(EntityRef("ha:").isValid)
        assertFalse(EntityRef(":x").isValid)
        assertFalse(EntityRef("nothing").isValid)
    }

    @Test
    fun `wire names round trip and unknown ones fall back`() {
        assertEquals(Availability.Unavailable, Availability.fromWire("unavailable"))
        assertEquals(Availability.Unknown, Availability.fromWire("surprise"))
        assertEquals(ConnectionStatus.Error, ConnectionStatus.fromWire("error"))
        assertEquals(AssetSize.Large, AssetSize.fromWire("large"))
        assertEquals(BrowseKind.Other, BrowseKind.fromWire("podcast"))
        assertEquals(ForecastType.TwiceDaily, ForecastType.fromWire("twice_daily"))
        assertNull(ForecastType.fromWire("monthly"))
        assertEquals(PlayMode.Replace, PlayMode.fromWire("replace"))
        assertEquals(WeatherCondition.LightningRainy, WeatherCondition.fromWire("lightning-rainy"))
        assertEquals(WeatherCondition.Unknown, WeatherCondition.fromWire("tornado"))
        for (kind in BrowseKind.entries) assertEquals(kind, BrowseKind.fromWire(kind.wire))
        for (c in WeatherCondition.entries) assertEquals(c, WeatherCondition.fromWire(c.wire))
    }

    @Test
    fun `entities compare by value`() {
        val a = SwitchEntity(EntityRef("ha:switch.x"), "X", Availability.Ready, on = true)
        assertEquals(a, a.copy())
        assertFalse(a == a.copy(on = false))
    }

    @Test
    fun `equal snapshots do not notify a store`() {
        val entity = SwitchEntity(EntityRef("ha:switch.x"), "X", Availability.Ready, on = true)
        val store = Store<Snapshot>(Snapshot.Loading)
        var calls = 0
        store.listen { calls++ }
        store.set(Snapshot.Present(entity))
        store.set(Snapshot.Present(entity.copy()))
        assertEquals(1, calls)
    }
}

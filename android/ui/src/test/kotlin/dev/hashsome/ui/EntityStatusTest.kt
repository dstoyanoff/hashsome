package dev.hashsome.ui

import dev.hashsome.client.Availability
import dev.hashsome.client.EntityRef
import dev.hashsome.client.GenericEntity
import dev.hashsome.client.SensorEntity
import dev.hashsome.client.Snapshot
import dev.hashsome.headless.HandleState
import dev.hashsome.headless.Scope
import dev.hashsome.testing.FakeClient
import dev.hashsome.testing.TestScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The task tile's `status` is the web's `status?: EntityStatus`: of any entity, not of an action.
class EntityStatusTest {
    private val scheduler = TestScheduler()
    private val client = FakeClient(scheduler)
    private val scope = Scope(client, scheduler)
    private val door = EntityRef("ha:binary_sensor.door")

    private fun sensor(availability: Availability = Availability.Ready) =
        SensorEntity(door, "Door", availability, value = "off", measurement = "door")

    @Test
    fun `a sensor is ready, though it is not an action`() {
        client.set(door, sensor())
        val status = EntityStatus.watch(scope, "ha:binary_sensor.door")
        scheduler.runCurrent()
        assertTrue(status.value is HandleState.Ready)
        assertNull(StatusLabels.of(status.value))
    }

    @Test
    fun `an entity of a kind nothing knows is ready too`() {
        val generic = GenericEntity(door, "Door", Availability.Ready, "x")
        assertEquals(HandleState.Ready(generic), EntityStatus.of(Snapshot.Present(generic)))
    }

    @Test
    fun `unavailable and unknown entities say why`() {
        assertEquals("Unavailable", StatusLabels.of(EntityStatus.of(Snapshot.Present(sensor(Availability.Unavailable)))))
        assertEquals("Unknown", StatusLabels.of(EntityStatus.of(Snapshot.Present(sensor(Availability.Unknown)))))
    }

    @Test
    fun `missing and loading`() {
        assertEquals("Not found", StatusLabels.of(EntityStatus.of(Snapshot.Missing)))
        assertEquals("…", StatusLabels.of(EntityStatus.of(Snapshot.Loading)))
        // No ref is nothing to find.
        assertEquals(HandleState.Missing, EntityStatus.watch(scope, null).value)
    }

    @Test
    fun `it follows the entity while the scope lives`() {
        val status = EntityStatus.watch(scope, "ha:binary_sensor.door")
        assertEquals(HandleState.Loading, status.value)
        client.set(door, sensor())
        scheduler.runCurrent()
        assertTrue(status.value is HandleState.Ready)
        client.set(door, sensor(Availability.Unavailable))
        scheduler.runCurrent()
        assertTrue(status.value is HandleState.Unavailable)
        scope.dispose()
        client.set(door, sensor())
        scheduler.runCurrent()
        assertTrue(status.value is HandleState.Unavailable)
    }
}

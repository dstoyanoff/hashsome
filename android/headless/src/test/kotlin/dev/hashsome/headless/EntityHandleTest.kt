package dev.hashsome.headless

import dev.hashsome.client.Availability
import dev.hashsome.client.EntityKind
import dev.hashsome.client.EntityRef
import dev.hashsome.client.HashsomeClient
import dev.hashsome.client.ServerAddress
import dev.hashsome.client.SwitchEntity
import dev.hashsome.testing.TestScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EntityHandleTest {
    private fun scope(): Scope {
        val scheduler = TestScheduler()
        return Scope(HashsomeClient(ServerAddress("http://localhost:3000"), scheduler = scheduler), scheduler)
    }

    @Test
    fun `a fixed handle shows its state and watches nothing`() {
        val handle = EntityHandle.fixed<SwitchEntity>(HandleState.Unsupported)
        assertNull(handle.ref)
        assertEquals(HandleState.Unsupported, handle.state.value)
    }

    @Test
    fun `a fixed handle can be unavailable with no entity`() {
        val handle = EntityHandle.fixed<SwitchEntity>(HandleState.Unavailable(null))
        assertEquals(HandleState.Unavailable<SwitchEntity>(null), handle.state.value)
    }

    @Test
    fun `a handle with no ref starts missing`() {
        assertEquals(HandleState.Missing, EntityHandle(scope(), kind = EntityKind.Switch).state.value)
        assertEquals(HandleState.Missing, EntityHandle(scope(), null, EntityKind.Switch).state.value)
    }

    @Test
    fun `a handle with a ref starts loading`() {
        assertEquals(HandleState.Loading, EntityHandle(scope(), EntityRef("ha:switch.x"), EntityKind.Switch).state.value)
    }

    @Test
    fun `ready and unavailable states compare by entity`() {
        val e = SwitchEntity(EntityRef("ha:switch.x"), "X", Availability.Ready, on = false)
        assertEquals(HandleState.Ready(e), HandleState.Ready(e.copy()))
        assertEquals(false, HandleState.Ready(e) == HandleState.Unavailable(e))
    }
}

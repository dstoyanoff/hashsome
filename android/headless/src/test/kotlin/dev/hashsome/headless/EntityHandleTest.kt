package dev.hashsome.headless

import dev.hashsome.client.Availability
import dev.hashsome.client.EntityRef
import dev.hashsome.client.SwitchEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EntityHandleTest {
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
    fun `ready and unavailable states compare by entity`() {
        val e = SwitchEntity(EntityRef("ha:switch.x"), "X", Availability.Ready, on = false)
        assertEquals(HandleState.Ready(e), HandleState.Ready(e.copy()))
        assertEquals(false, HandleState.Ready(e) == HandleState.Unavailable(e))
    }
}

package dev.hashsome.headless

import dev.hashsome.client.Availability
import dev.hashsome.client.EntityKind
import dev.hashsome.client.EntityRef
import dev.hashsome.client.LightCommands
import dev.hashsome.client.LightEntity
import dev.hashsome.client.Outcome
import dev.hashsome.client.SwitchEntity
import dev.hashsome.testing.FakeClient
import dev.hashsome.testing.TestScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EntityHandleTest {
    private fun scope(): Scope {
        val scheduler = TestScheduler()
        return Scope(FakeClient(scheduler), scheduler)
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
        assertNull(handle.entity)
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

    // status.test.ts: an entity (or its absence) is a uniform status.
    @Test
    fun `loading, missing, unavailable, unknown and ready come from what the client says`() {
        val rig = Rig()
        val handle = rig.handle(ref("plug"), EntityKind.Switch)
        assertEquals(HandleState.Loading, handle.state.value)
        rig.settle()
        // Subscribed and nothing heard: still loading.
        assertEquals(HandleState.Loading, handle.state.value)

        rig.client.set(ref("plug"), switch(on = true))
        rig.settle()
        assertEquals(HandleState.Ready(switch(on = true)), handle.state.value)
        assertEquals(switch(on = true), handle.entity)

        rig.client.set(ref("plug"), switch(on = true, availability = Availability.Unavailable))
        rig.settle()
        assertEquals(HandleState.Unavailable(switch(on = true, availability = Availability.Unavailable)), handle.state.value)
        assertEquals(true, handle.entity?.on)

        // Reachable but not reported yet is not usable either.
        rig.client.set(ref("plug"), switch(availability = Availability.Unknown))
        rig.settle()
        assertEquals(HandleState.Unavailable(switch(availability = Availability.Unknown)), handle.state.value)

        rig.client.remove(ref("plug"))
        rig.settle()
        assertEquals(HandleState.Missing, handle.state.value)
        assertNull(handle.entity)
    }

    @Test
    fun `an entity of another kind is unsupported, whatever it reports`() {
        val rig = Rig()
        val handle = rig.handle(ref("x"), EntityKind.Light)
        rig.client.set(ref("x"), switch())
        rig.settle()
        assertEquals(HandleState.Unsupported, handle.state.value)
        assertNull(handle.entity)
    }

    @Test
    fun `a mounted handle subscribes once and follows the updates`() {
        val rig = Rig()
        val handle = rig.handle(ref("lamp"), EntityKind.Light)
        val seen = ArrayList<HandleState<LightEntity>>()
        handle.state.listen { seen += it }
        rig.client.set(ref("lamp"), light(on = false))
        rig.client.set(ref("lamp"), light(on = true))
        rig.settle()
        assertEquals(listOf(HandleState.Ready(light(on = false)), HandleState.Ready(light(on = true))), seen)
    }

    @Test
    fun `a handle lets go of its entity when the scope is disposed`() {
        val rig = Rig()
        val handle = rig.handle(ref("lamp"), EntityKind.Light)
        rig.client.set(ref("lamp"), light())
        rig.settle()
        rig.scope.dispose()
        rig.client.set(ref("lamp"), light(on = false))
        rig.settle()
        assertEquals(HandleState.Ready(light()), handle.state.value)
    }

    @Test
    fun `commands go to the entity by name or built, and done hears the outcome`() {
        val rig = Rig()
        val handle = rig.handle(ref("lamp"), EntityKind.Light)
        rig.client.set(ref("lamp"), light(on = false))
        val outcomes = ArrayList<Outcome>()
        handle.command("turnOn") { outcomes += it }
        handle.command(LightCommands.setBrightness(0.4)) { outcomes += it }
        assertEquals(listOf("turnOn", "setBrightness"), rig.client.commands.map { it.second })
        assertEquals(0.4, rig.client.commands[1].third!!.getDouble("brightness"), 0.0)
        // The entity is not changed here, it changes when the runtime reports it.
        assertEquals(HandleState.Loading, handle.state.value)
        rig.settle()
        assertEquals(listOf<Outcome>(Outcome.Ok, Outcome.Ok), outcomes)
        assertEquals(0.4, handle.entity!!.brightness!!, 0.0)
    }

    @Test
    fun `a failed command is reported as failed`() {
        val rig = Rig()
        val handle = rig.handle(ref("lamp"), EntityKind.Light)
        rig.client.failNext("device offline")
        var outcome: Outcome? = null
        handle.command("toggle") { outcome = it }
        rig.settle()
        assertEquals(Outcome.Failed("device offline"), outcome)
    }

    @Test
    fun `a command after the scope is disposed is not sent, and a late outcome is not heard`() {
        val rig = Rig()
        val handle = rig.handle(ref("lamp"), EntityKind.Light)
        var heard = 0
        handle.command("toggle") { heard++ }
        rig.scope.dispose()
        rig.settle()
        assertEquals(0, heard)
        handle.command("toggle") { heard++ }
        assertEquals(1, rig.client.commands.size)
    }

    @Test
    fun `a handle with nothing behind it fails a command instead of sending it`() {
        var outcome: Outcome? = null
        EntityHandle.fixed<LightEntity>(HandleState.Unavailable(null)).command("toggle") { outcome = it }
        assertEquals(Outcome.Failed("No entity to command"), outcome)
        outcome = null
        EntityHandle(Rig().scope, null, EntityKind.Light).command("toggle") { outcome = it }
        assertEquals(Outcome.Failed("No entity to command"), outcome)
    }
}

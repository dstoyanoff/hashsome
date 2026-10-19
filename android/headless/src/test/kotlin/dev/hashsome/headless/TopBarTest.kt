package dev.hashsome.headless

import dev.hashsome.client.ConnectionStatus
import dev.hashsome.client.LinkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// entities/__tests__/top-bar.test.tsx: the status dot reports all systems operational and flags an integration that drops.
class TopBarTest {
    private val rig = Rig()
    private val health = ConnectionHealth(rig.client, rig.scope)

    private fun rows() = health.summary.value.rows.map { Triple(it.name, it.level, it.label) }

    @Test
    fun `all systems operational, with a row for the runtime and one for each integration`() {
        rig.client.integrations.set(mapOf("ha" to ConnectionStatus.Connected, "ma" to ConnectionStatus.Connected))
        assertEquals("All systems operational", health.summary.value.summary)
        assertEquals(HealthLevel.Ok, health.summary.value.overall)
        assertEquals(
            listOf(
                Triple("Runtime", HealthLevel.Ok, "Connected"),
                Triple("HA", HealthLevel.Ok, "Connected"),
                Triple("MA", HealthLevel.Ok, "Connected"),
            ),
            rows(),
        )

        assertTrue(health.state.value.healthy)
        assertTrue(health.state.value.linkOpen)
        assertEquals(setOf("ha", "ma"), health.state.value.integrations.keys)
    }

    @Test
    fun `an integration that drops is flagged`() {
        rig.client.integrations.set(mapOf("ha" to ConnectionStatus.Connected, "ma" to ConnectionStatus.Error))
        assertEquals("1 system(s) down", health.summary.value.summary)
        assertEquals(HealthLevel.Down, health.summary.value.overall)
        assertEquals(Triple("MA", HealthLevel.Down, "Error"), rows()[2])
        assertFalse(health.state.value.healthy)
    }

    @Test
    fun `a disconnected integration is down, a connecting one is pending`() {
        rig.client.integrations.set(mapOf("ha" to ConnectionStatus.Disconnected, "ma" to ConnectionStatus.Connecting))
        assertEquals(Triple("HA", HealthLevel.Down, "Disconnected"), rows()[1])
        assertEquals(Triple("MA", HealthLevel.Pending, "Connecting…"), rows()[2])
        assertEquals("1 system(s) down", health.summary.value.summary)
    }

    @Test
    fun `while something connects and nothing is down it says so`() {
        rig.client.integrations.set(mapOf("ha" to ConnectionStatus.Connecting))
        assertEquals(HealthLevel.Pending, health.summary.value.overall)
        assertEquals("Connecting…", health.summary.value.summary)
        assertFalse(health.state.value.healthy)
    }

    @Test
    fun `the link itself is a row, unreachable once closed`() {
        rig.client.link.set(LinkStatus.Closed)
        assertEquals(Triple("Runtime", HealthLevel.Down, "Unreachable"), rows()[0])
        assertEquals("1 system(s) down", health.summary.value.summary)
        assertFalse(health.state.value.linkOpen)
        rig.client.link.set(LinkStatus.Connecting)
        assertEquals(Triple("Runtime", HealthLevel.Pending, "Connecting…"), rows()[0])
        assertEquals("Connecting…", health.summary.value.summary)
    }

    @Test
    fun `down counts every row that is down, and wins over pending`() {
        rig.client.link.set(LinkStatus.Closed)
        rig.client.integrations.set(mapOf("ha" to ConnectionStatus.Error, "ma" to ConnectionStatus.Connecting))
        assertEquals("2 system(s) down", health.summary.value.summary)
    }

    @Test
    fun `the state follows the client from the start`() {
        val other = Rig()
        other.client.link.set(LinkStatus.Closed)
        other.client.integrations.set(mapOf("ha" to ConnectionStatus.Connected))
        val late = ConnectionHealth(other.client, other.scope)
        assertEquals(Health(false, mapOf("ha" to ConnectionStatus.Connected)), late.state.value)
        assertEquals("1 system(s) down", late.summary.value.summary)
    }

    @Test
    fun `it stops following when the scope is disposed`() {
        rig.scope.dispose()
        rig.client.link.set(LinkStatus.Closed)
        assertTrue(health.state.value.linkOpen)
    }
}

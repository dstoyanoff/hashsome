package dev.hashsome.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins every command name and argument key to the model files in packages/core/src/model. */
class CommandsTest {
    private fun keys(c: Command): Set<String> = c.args?.keys()?.asSequence()?.toSet() ?: emptySet()

    private fun assertCommand(c: Command, name: String, vararg keys: String) {
        assertEquals(name, c.name)
        assertEquals(keys.toSet(), keys(c))
    }

    @Test
    fun `no-argument commands have no args object`() {
        val none = listOf(
            LightCommands.turnOn() to "turnOn", LightCommands.turnOff() to "turnOff", LightCommands.toggle() to "toggle",
            SwitchCommands.turnOn() to "turnOn", SwitchCommands.turnOff() to "turnOff", SwitchCommands.toggle() to "toggle",
            ActionCommands.trigger() to "trigger",
            MediaPlayerCommands.play() to "play", MediaPlayerCommands.pause() to "pause",
            MediaPlayerCommands.togglePlay() to "togglePlay", MediaPlayerCommands.next() to "next",
            MediaPlayerCommands.previous() to "previous", MediaPlayerCommands.clearQueue() to "clearQueue",
            MediaPlayerCommands.leaveGroup() to "leaveGroup", MediaPlayerCommands.takeOverGroup() to "takeOverGroup",
        )
        for ((command, name) in none) {
            assertEquals(name, command.name)
            assertNull(command.args)
        }
    }

    @Test
    fun `light commands`() {
        assertCommand(LightCommands.setBrightness(0.4), "setBrightness", "brightness")
        assertEquals(0.4, LightCommands.setBrightness(0.4).args!!.getDouble("brightness"), 0.0)
        assertCommand(LightCommands.setColorTemperature(2700), "setColorTemperature", "kelvin")
        assertEquals(2700, LightCommands.setColorTemperature(2700).args!!.getInt("kelvin"))
        val color = LightCommands.setColor(30.0, 80.0)
        assertCommand(color, "setColor", "hue", "saturation")
        assertEquals(30.0, color.args!!.getDouble("hue"), 0.0)
        assertEquals(80.0, color.args!!.getDouble("saturation"), 0.0)
    }

    @Test
    fun `climate commands`() {
        assertCommand(ClimateCommands.setMode(ClimateMode.HeatCool), "setMode", "mode")
        assertEquals("heatCool", ClimateCommands.setMode(ClimateMode.HeatCool).args!!.getString("mode"))
        assertCommand(ClimateCommands.setTargetTemperature(21.5), "setTargetTemperature", "temperature")
        assertCommand(ClimateCommands.setPreset("eco"), "setPreset", "preset")
    }

    @Test
    fun `media player commands with arguments`() {
        assertCommand(MediaPlayerCommands.setVolume(0.3), "setVolume", "volume")
        assertCommand(MediaPlayerCommands.setMuted(true), "setMuted", "muted")
        assertCommand(MediaPlayerCommands.seek(12.5), "seek", "position")
        assertCommand(MediaPlayerCommands.setShuffle(false), "setShuffle", "shuffle")
        assertCommand(MediaPlayerCommands.playQueueItem("q1"), "playQueueItem", "item")
        assertCommand(MediaPlayerCommands.removeQueueItem("q1"), "removeQueueItem", "item")
        val move = MediaPlayerCommands.moveQueueItem("q1", -2)
        assertCommand(move, "moveQueueItem", "item", "shift")
        assertEquals(-2, move.args!!.getInt("shift"))
    }

    @Test
    fun `playMedia leaves out what is not given`() {
        assertCommand(MediaPlayerCommands.playMedia("t1"), "playMedia", "item")
        val full = MediaPlayerCommands.playMedia("t1", PlayMode.Replace, "al1", true)
        assertCommand(full, "playMedia", "item", "mode", "context", "shuffle")
        assertEquals("replace", full.args!!.getString("mode"))
        assertEquals("al1", full.args!!.getString("context"))
        assertEquals(true, full.args!!.getBoolean("shuffle"))
    }

    @Test
    fun `setGroupMembers sends refs as strings and leaves out the side not given`() {
        val add = MediaPlayerCommands.setGroupMembers(add = listOf(EntityRef("ma:a"), EntityRef("ma:b")))
        assertCommand(add, "setGroupMembers", "add")
        assertEquals("ma:b", add.args!!.getJSONArray("add").getString(1))
        assertCommand(MediaPlayerCommands.setGroupMembers(remove = listOf(EntityRef("ma:a"))), "setGroupMembers", "remove")
        assertCommand(MediaPlayerCommands.setGroupMembers(add = emptyList(), remove = emptyList()), "setGroupMembers", "add", "remove")
    }
}

package dev.hashsome.client

import org.json.JSONArray
import org.json.JSONObject

/**
 * A command ready to send: its [name] and its arguments object ([args] is null for a command with none). Built by the
 * per-kind objects below, which mirror the `...Commands` interfaces of the model files in packages/core/src/model name for name and key
 * for key. Arguments compare by identity (a `JSONObject`), so compare [name] and read the keys in tests.
 */
data class Command(val name: String, val args: JSONObject? = null)

private fun command(name: String, vararg args: Pair<String, Any?>): Command {
    val json = JSONObject()
    // An absent optional argument is left out of the object, as the TS leaves the property out.
    for ((key, value) in args) if (value != null) json.put(key, value)
    return Command(name, if (args.isEmpty()) null else json)
}

private fun refs(list: List<EntityRef>?): JSONArray? = list?.let { JSONArray(it.map(EntityRef::value)) }

object LightCommands {
    fun turnOn() = command("turnOn")
    fun turnOff() = command("turnOff")
    fun toggle() = command("toggle")

    /** [brightness] is 0..1; 0 turns the light off. */
    fun setBrightness(brightness: Double) = command("setBrightness", "brightness" to brightness)
    fun setColorTemperature(kelvin: Int) = command("setColorTemperature", "kelvin" to kelvin)

    /** [hue] 0..360, [saturation] 0..100. */
    fun setColor(hue: Double, saturation: Double) = command("setColor", "hue" to hue, "saturation" to saturation)
}

object SwitchCommands {
    fun turnOn() = command("turnOn")
    fun turnOff() = command("turnOff")
    fun toggle() = command("toggle")
}

object ClimateCommands {
    fun setMode(mode: ClimateMode) = command("setMode", "mode" to mode.wire)
    fun setTargetTemperature(temperature: Double) = command("setTargetTemperature", "temperature" to temperature)
    fun setPreset(preset: String) = command("setPreset", "preset" to preset)
}

object ActionCommands {
    fun trigger() = command("trigger")
}

object MediaPlayerCommands {
    fun play() = command("play")
    fun pause() = command("pause")
    fun togglePlay() = command("togglePlay")
    fun next() = command("next")
    fun previous() = command("previous")

    /** [volume] is 0..1. */
    fun setVolume(volume: Double) = command("setVolume", "volume" to volume)
    fun setMuted(muted: Boolean) = command("setMuted", "muted" to muted)

    /** [position] is in seconds. */
    fun seek(position: Double) = command("seek", "position" to position)
    fun setShuffle(shuffle: Boolean) = command("setShuffle", "shuffle" to shuffle)

    /**
     * Plays a `BrowseItem` from this player's library: [item] is its id. [mode] defaults to play on the server.
     * [context] is the album or playlist [item] (a track) is in; [shuffle] plays what is queued in a random order.
     */
    fun playMedia(item: String, mode: PlayMode? = null, context: String? = null, shuffle: Boolean? = null) =
        command("playMedia", "item" to item, "mode" to mode?.wire, "context" to context, "shuffle" to shuffle)

    /** Jumps to a track in the queue and plays it: [item] is a `QueueItem`'s id. */
    fun playQueueItem(item: String) = command("playQueueItem", "item" to item)
    fun removeQueueItem(item: String) = command("removeQueueItem", "item" to item)

    /** Moves a track in the queue by [shift] places: later when positive, earlier when negative (never 0). */
    fun moveQueueItem(item: String, shift: Int) = command("moveQueueItem", "item" to item, "shift" to shift)

    /** Empties the queue after the track playing. */
    fun clearQueue() = command("clearQueue")

    /** Adds and removes players of this player's stream, this player being its leader. An empty change does nothing. */
    fun setGroupMembers(add: List<EntityRef>? = null, remove: List<EntityRef>? = null): Command {
        val json = JSONObject()
        refs(add)?.let { json.put("add", it) }
        refs(remove)?.let { json.put("remove", it) }
        return Command("setGroupMembers", json)
    }

    fun leaveGroup() = command("leaveGroup")
    fun takeOverGroup() = command("takeOverGroup")
}

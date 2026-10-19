package dev.hashsome.testing

import dev.hashsome.client.ActionEntity
import dev.hashsome.client.ClimateEntity
import dev.hashsome.client.Entity
import dev.hashsome.client.GenericEntity
import dev.hashsome.client.LightColor
import dev.hashsome.client.LightEntity
import dev.hashsome.client.MediaPlayerEntity
import dev.hashsome.client.PersonEntity
import dev.hashsome.client.SensorEntity
import dev.hashsome.client.SwitchEntity
import dev.hashsome.client.WeatherEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * An [Entity] as the runtime writes it on the wire (the inverse of `Entities.parse`): same keys as
 * the model files in packages/core/src/model, a key left out for a null. [FakeRuntime] sends entities through it.
 */
object EntityJson {
    fun encode(entity: Entity): JSONObject {
        val json = JSONObject()
        json.put("kind", kindOf(entity))
        json.put("name", entity.name)
        json.put("availability", entity.availability.wire)
        json.put("ref", entity.ref.value)
        json.putIf("lastChanged", entity.lastChanged)
        json.putIf("lastUpdated", entity.lastUpdated)
        entity.raw?.let { json.put("raw", JSONObject(it.text)) }
        when (entity) {
            is LightEntity -> {
                json.put("on", entity.on)
                json.putIf("brightness", entity.brightness)
                entity.color?.let { json.put("color", color(it)) }
                json.put(
                    "capabilities",
                    JSONObject()
                        .put("brightness", entity.capabilities.brightness)
                        .put("colorTemperature", entity.capabilities.colorTemperature)
                        .put("color", entity.capabilities.color)
                        .also { c -> entity.capabilities.kelvinRange?.let { c.put("kelvinRange", JSONObject().put("min", it.first).put("max", it.last)) } },
                )
            }
            is SwitchEntity -> json.put("on", entity.on)
            is ClimateEntity -> {
                json.put("mode", entity.mode.wire)
                json.putIf("action", entity.action?.wire)
                json.putIf("targetTemperature", entity.targetTemperature)
                json.putIf("currentTemperature", entity.currentTemperature)
                json.putIf("humidity", entity.humidity)
                json.put("unit", entity.unit)
                json.putIf("preset", entity.preset)
                json.put(
                    "capabilities",
                    JSONObject()
                        .put("modes", JSONArray(entity.capabilities.modes.map { it.wire }))
                        .put("presets", JSONArray(entity.capabilities.presets))
                        .put("targetTemperature", entity.capabilities.targetTemperature)
                        .put("step", entity.capabilities.step)
                        .put("range", JSONObject().put("min", entity.capabilities.range.start).put("max", entity.capabilities.range.endInclusive)),
                )
            }
            is ActionEntity -> json.putIf("lastTriggered", entity.lastTriggered)
            is SensorEntity -> {
                json.put("value", entity.value)
                json.putIf("numeric", entity.numeric)
                json.putIf("unit", entity.unit)
                json.putIf("measurement", entity.measurement)
            }
            is PersonEntity -> {
                json.put("location", entity.location)
                json.put("home", entity.home)
                json.putIf("pictureUrl", entity.pictureUrl)
            }
            is WeatherEntity -> {
                json.put("condition", entity.condition.wire)
                json.putIf("temperature", entity.temperature)
                json.putIf("unit", entity.unit)
                json.putIf("humidity", entity.humidity)
                json.putIf("windSpeed", entity.windSpeed)
                json.putIf("windBearing", entity.windBearing)
                json.putIf("windUnit", entity.windUnit)
                json.putIf("uvIndex", entity.uvIndex)
                json.putIf("cloudCoverage", entity.cloudCoverage)
                json.putIf("apparentTemperature", entity.apparentTemperature)
                json.putIf("pressure", entity.pressure)
                json.putIf("pressureUnit", entity.pressureUnit)
                json.putIf("precipitationUnit", entity.precipitationUnit)
                if (entity.forecasts.isNotEmpty()) json.put("forecasts", JSONArray(entity.forecasts.map { it.wire }))
            }
            is MediaPlayerEntity -> {
                json.put("playback", entity.playback.wire)
                entity.media?.let { m ->
                    json.put("media", JSONObject().putIf("title", m.title).putIf("artist", m.artist).putIf("album", m.album).putIf("artworkUrl", m.artworkUrl))
                }
                entity.source?.let { s -> json.put("source", JSONObject().put("id", s.id).put("title", s.title).putIf("kind", s.kind?.wire)) }
                json.putIf("position", entity.position)
                json.putIf("duration", entity.duration)
                json.putIf("positionUpdatedAt", entity.positionUpdatedAt)
                json.putIf("shuffle", entity.shuffle)
                json.putIf("volume", entity.volume)
                json.put("muted", entity.muted)
                entity.group?.let { g -> json.put("group", JSONObject().put("leader", g.leader.value).put("members", JSONArray(g.members.map { it.value }))) }
                if (entity.groupable.isNotEmpty()) json.put("groupable", JSONArray(entity.groupable.map { it.value }))
                val c = entity.capabilities
                json.put(
                    "capabilities",
                    JSONObject().put("volume", c.volume).put("mute", c.mute).put("next", c.next).put("previous", c.previous).put("browse", c.browse)
                        .put("search", c.search).put("seek", c.seek).put("shuffle", c.shuffle).put("queue", c.queue).put("transfer", c.transfer).put("group", c.group),
                )
            }
            is GenericEntity -> {
                json.put("value", entity.value)
                json.putIf("unit", entity.unit)
            }
        }
        return json
    }

    /** The `entity` frame the runtime sends for [entity], or for none when it is null. */
    fun frame(ref: dev.hashsome.client.EntityRef, entity: Entity?): String =
        JSONObject().put("type", "entity").put("ref", ref.value).put("entity", entity?.let(::encode) ?: JSONObject.NULL).toString()

    private fun kindOf(entity: Entity): String = when (entity) {
        is LightEntity -> "light"
        is SwitchEntity -> "switch"
        is ClimateEntity -> "climate"
        is ActionEntity -> "action"
        is SensorEntity -> "sensor"
        is PersonEntity -> "person"
        is WeatherEntity -> "weather"
        is MediaPlayerEntity -> "mediaPlayer"
        is GenericEntity -> "generic"
    }

    private fun color(color: LightColor): JSONObject = when (color) {
        is LightColor.Temperature -> JSONObject().put("mode", "temperature").put("kelvin", color.kelvin)
        is LightColor.Hs -> JSONObject().put("mode", "color").put("hue", color.hue).put("saturation", color.saturation)
            .also { c -> color.rgb?.let { c.put("rgb", JSONArray(it)) } }
    }

    /** `put` that leaves the key out for a null (org.json's own `put(key, null)` removes it, which is the same, but says so). */
    private fun JSONObject.putIf(key: String, value: Any?): JSONObject = if (value == null) this else put(key, value)
}

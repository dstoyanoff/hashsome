package dev.hashsome.client

import org.json.JSONObject

// One reader per entity kind, over the wire JSON of packages/core/src/model/*.ts. A field the TS marks optional is
// nullable here; one it requires gets a neutral default when it is missing, so a malformed entity still shows (as
// unavailable-looking) instead of failing the whole message.

private class Base(val name: String, val availability: Availability, val lastChanged: String?, val lastUpdated: String?, val raw: RawJson?)

private fun base(ref: EntityRef, json: JSONObject) = Base(
    name = json.str("name") ?: "",
    availability = Availability.fromWire(json.str("availability")),
    lastChanged = json.str("lastChanged"),
    lastUpdated = json.str("lastUpdated"),
    raw = json.obj("raw")?.let { RawJson(it.toString()) },
)

internal fun parseLight(ref: EntityRef, json: JSONObject): LightEntity {
    val b = base(ref, json)
    val caps = json.obj("capabilities")
    val range = caps?.obj("kelvinRange")?.let { r ->
        val min = r.int("min")
        val max = r.int("max")
        if (min != null && max != null) min..max else null
    }
    return LightEntity(
        ref = ref,
        name = b.name,
        availability = b.availability,
        on = json.bool("on") ?: false,
        brightness = json.num("brightness"),
        color = json.obj("color")?.let(::parseLightColor),
        capabilities = LightCapabilities(
            brightness = caps?.bool("brightness") ?: false,
            colorTemperature = caps?.bool("colorTemperature") ?: false,
            color = caps?.bool("color") ?: false,
            kelvinRange = range,
        ),
        lastChanged = b.lastChanged,
        lastUpdated = b.lastUpdated,
        raw = b.raw,
    )
}

private fun parseLightColor(json: JSONObject): LightColor? = when (json.str("mode")) {
    "temperature" -> json.int("kelvin")?.let { LightColor.Temperature(it) }
    "color" -> {
        val hue = json.num("hue")
        val saturation = json.num("saturation")
        if (hue == null || saturation == null) {
            null
        } else {
            val rgb = json.arr("rgb")?.let { a -> (0 until a.length()).mapNotNull { (a.opt(it) as? Number)?.toInt() } }
            LightColor.Hs(hue, saturation, rgb?.takeIf { it.size == 3 })
        }
    }
    else -> null
}

internal fun parseSwitch(ref: EntityRef, json: JSONObject): SwitchEntity {
    val b = base(ref, json)
    return SwitchEntity(ref, b.name, b.availability, json.bool("on") ?: false, b.lastChanged, b.lastUpdated, b.raw)
}

private fun climateModes(json: JSONObject): List<ClimateMode> =
    json.strings("modes").mapNotNull { wire -> ClimateMode.entries.firstOrNull { it.wire == wire } }

internal fun parseClimate(ref: EntityRef, json: JSONObject): ClimateEntity {
    val b = base(ref, json)
    val caps = json.obj("capabilities")
    val range = caps?.obj("range")
    return ClimateEntity(
        ref = ref,
        name = b.name,
        availability = b.availability,
        mode = ClimateMode.fromWire(json.str("mode")),
        action = ClimateAction.fromWire(json.str("action")),
        targetTemperature = json.num("targetTemperature"),
        currentTemperature = json.num("currentTemperature"),
        humidity = json.num("humidity"),
        unit = json.str("unit") ?: "°C",
        preset = json.str("preset"),
        capabilities = ClimateCapabilities(
            modes = caps?.let(::climateModes) ?: emptyList(),
            presets = caps?.strings("presets") ?: emptyList(),
            targetTemperature = caps?.bool("targetTemperature") ?: false,
            step = caps?.num("step") ?: 0.5,
            range = (range?.num("min") ?: 5.0)..(range?.num("max") ?: 35.0),
        ),
        lastChanged = b.lastChanged,
        lastUpdated = b.lastUpdated,
        raw = b.raw,
    )
}

internal fun parseAction(ref: EntityRef, json: JSONObject): ActionEntity {
    val b = base(ref, json)
    return ActionEntity(ref, b.name, b.availability, json.str("lastTriggered"), b.lastChanged, b.lastUpdated, b.raw)
}

internal fun parseSensor(ref: EntityRef, json: JSONObject): SensorEntity {
    val b = base(ref, json)
    return SensorEntity(
        ref = ref,
        name = b.name,
        availability = b.availability,
        value = json.str("value") ?: "",
        numeric = json.num("numeric"),
        unit = json.str("unit"),
        measurement = json.str("measurement"),
        lastChanged = b.lastChanged,
        lastUpdated = b.lastUpdated,
        raw = b.raw,
    )
}

internal fun parsePerson(ref: EntityRef, json: JSONObject): PersonEntity {
    val b = base(ref, json)
    return PersonEntity(
        ref = ref,
        name = b.name,
        availability = b.availability,
        location = json.str("location") ?: "",
        home = json.bool("home") ?: false,
        pictureUrl = json.str("pictureUrl"),
        lastChanged = b.lastChanged,
        lastUpdated = b.lastUpdated,
        raw = b.raw,
    )
}

internal fun parseWeather(ref: EntityRef, json: JSONObject): WeatherEntity {
    val b = base(ref, json)
    return WeatherEntity(
        ref = ref,
        name = b.name,
        availability = b.availability,
        condition = WeatherCondition.fromWire(json.str("condition")),
        temperature = json.num("temperature"),
        unit = json.str("unit"),
        humidity = json.num("humidity"),
        windSpeed = json.num("windSpeed"),
        windBearing = json.num("windBearing"),
        windUnit = json.str("windUnit"),
        uvIndex = json.num("uvIndex"),
        cloudCoverage = json.num("cloudCoverage"),
        apparentTemperature = json.num("apparentTemperature"),
        pressure = json.num("pressure"),
        pressureUnit = json.str("pressureUnit"),
        precipitationUnit = json.str("precipitationUnit"),
        forecasts = json.strings("forecasts").mapNotNull(ForecastType::fromWire),
        lastChanged = b.lastChanged,
        lastUpdated = b.lastUpdated,
        raw = b.raw,
    )
}

internal fun parseMediaPlayer(ref: EntityRef, json: JSONObject): MediaPlayerEntity {
    val b = base(ref, json)
    val caps = json.obj("capabilities")

    fun cap(key: String) = caps?.bool(key) ?: false
    return MediaPlayerEntity(
        ref = ref,
        name = b.name,
        availability = b.availability,
        playback = PlaybackState.fromWire(json.str("playback")),
        media = json.obj("media")?.let { MediaInfo(it.str("title"), it.str("artist"), it.str("album"), it.str("artworkUrl")) },
        source = json.obj("source")?.let { s ->
            val id = s.str("id")
            if (id == null) null else MediaSource(id, s.str("title") ?: "", SourceKind.fromWire(s.str("kind")))
        },
        position = json.num("position"),
        duration = json.num("duration"),
        positionUpdatedAt = json.str("positionUpdatedAt"),
        shuffle = json.bool("shuffle"),
        volume = json.num("volume"),
        muted = json.bool("muted") ?: false,
        group = json.obj("group")?.let { g ->
            val leader = g.str("leader")
            if (leader == null) null else MediaGroup(EntityRef(leader), g.refs("members"))
        },
        groupable = json.refs("groupable"),
        capabilities = MediaPlayerCapabilities(
            volume = cap("volume"),
            mute = cap("mute"),
            next = cap("next"),
            previous = cap("previous"),
            browse = cap("browse"),
            search = cap("search"),
            seek = cap("seek"),
            shuffle = cap("shuffle"),
            queue = cap("queue"),
            transfer = cap("transfer"),
            group = cap("group"),
        ),
        lastChanged = b.lastChanged,
        lastUpdated = b.lastUpdated,
        raw = b.raw,
    )
}

/** Also what a `kind` this app does not know parses to. */
internal fun parseGeneric(ref: EntityRef, json: JSONObject): GenericEntity {
    val b = base(ref, json)
    return GenericEntity(ref, b.name, b.availability, json.str("value") ?: "", json.str("unit"), b.lastChanged, b.lastUpdated, b.raw)
}

internal fun parseEntity(ref: EntityRef, json: JSONObject): Entity = when (json.str("kind")) {
    EntityKind.Light.name -> parseLight(ref, json)
    EntityKind.Switch.name -> parseSwitch(ref, json)
    EntityKind.Climate.name -> parseClimate(ref, json)
    EntityKind.Action.name -> parseAction(ref, json)
    EntityKind.Sensor.name -> parseSensor(ref, json)
    EntityKind.Person.name -> parsePerson(ref, json)
    EntityKind.Weather.name -> parseWeather(ref, json)
    EntityKind.MediaPlayer.name -> parseMediaPlayer(ref, json)
    else -> parseGeneric(ref, json)
}

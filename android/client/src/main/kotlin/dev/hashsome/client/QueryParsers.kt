package dev.hashsome.client

import org.json.JSONObject

// Readers for the `data` of a query's `result`, over packages/core/src/model/{browse,queue,weather,history,logbook}.ts.
// An item without the field that identifies it (an id, a timestamp) is skipped; a missing `data` is an error, since the
// runtime always answers a query with one.

private fun need(data: JSONObject?): JSONObject = data ?: throw IllegalArgumentException("The answer has no data")

internal fun parseBrowse(data: JSONObject?): BrowseResult {
    val json = need(data)
    val items = json.objects("items").mapNotNull { item ->
        val id = item.str("id") ?: return@mapNotNull null
        BrowseItem(
            id = id,
            title = item.str("title") ?: "",
            subtitle = item.str("subtitle"),
            artworkUrl = item.str("artworkUrl"),
            kind = BrowseKind.fromWire(item.str("kind")),
            duration = item.num("duration"),
            playable = item.bool("playable") ?: false,
            expandable = item.bool("expandable") ?: false,
            holds = item.str("holds")?.let(BrowseKind::fromWire),
        )
    }
    return BrowseResult(json.str("title"), items)
}

internal fun parseQueue(data: JSONObject?): QueueResult {
    val json = need(data)
    val items = json.objects("items").mapNotNull { item ->
        val id = item.str("id") ?: return@mapNotNull null
        QueueItem(
            id = id,
            title = item.str("title") ?: "",
            artist = item.str("artist"),
            album = item.str("album"),
            artworkUrl = item.str("artworkUrl"),
            duration = item.num("duration"),
            current = item.bool("current") ?: false,
        )
    }
    return QueueResult(items, json.int("total") ?: items.size, json.int("offset") ?: 0, json.num("duration"))
}

internal fun parseForecast(data: JSONObject?, asked: ForecastType): ForecastResult {
    val json = need(data)
    val points = json.objects("points").mapNotNull { p ->
        val timestamp = p.str("timestamp") ?: return@mapNotNull null
        ForecastPoint(
            timestamp = timestamp,
            condition = WeatherCondition.fromWire(p.str("condition")),
            temperature = p.num("temperature"),
            low = p.num("low"),
            precipitationProbability = p.num("precipitationProbability"),
            precipitation = p.num("precipitation"),
            windSpeed = p.num("windSpeed"),
            windBearing = p.num("windBearing"),
            windGustSpeed = p.num("windGustSpeed"),
            humidity = p.num("humidity"),
            cloudCoverage = p.num("cloudCoverage"),
            uvIndex = p.num("uvIndex"),
            apparentTemperature = p.num("apparentTemperature"),
            pressure = p.num("pressure"),
            daytime = p.bool("daytime"),
        )
    }
    return ForecastResult(
        type = ForecastType.fromWire(json.str("type")) ?: asked,
        points = points,
        unit = json.str("unit"),
        windUnit = json.str("windUnit"),
        precipitationUnit = json.str("precipitationUnit"),
        pressureUnit = json.str("pressureUnit"),
    )
}

internal fun parseHistory(data: JSONObject?): HistoryResult {
    val json = need(data)
    val points = json.objects("points").mapNotNull { p ->
        val timestamp = p.str("timestamp") ?: return@mapNotNull null
        val value = p.num("value") ?: return@mapNotNull null
        HistoryPoint(timestamp, value)
    }
    return HistoryResult(points, HistoryKind.fromWire(json.str("kind")), json.str("unit"))
}

internal fun parseLogbook(data: JSONObject?): LogbookResult {
    val json = need(data)
    val entries = json.objects("entries").mapNotNull { e ->
        val id = e.str("id") ?: return@mapNotNull null
        val timestamp = e.str("timestamp") ?: return@mapNotNull null
        LogbookEntry(
            id = id,
            message = e.str("message") ?: "",
            timestamp = timestamp,
            actor = e.str("actor"),
            actorKind = ActorKind.fromWire(e.str("actorKind")),
            change = LogbookChange.fromWire(e.str("change")),
        )
    }
    return LogbookResult(entries)
}

/** The `args` of a request, or null when every field is absent. */
internal fun argsOf(vararg fields: Pair<String, Any?>): JSONObject? {
    val json = JSONObject()
    for ((key, value) in fields) if (value != null) json.put(key, value)
    return if (json.length() == 0) null else json
}

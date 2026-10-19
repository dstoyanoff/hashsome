package dev.hashsome.client

import org.json.JSONObject

// The entity model, mirroring packages/core/src/model/*.ts field for field (same names, same optionality:
// `?` here is `?` there). Timestamps stay ISO 8601 strings; `Iso8601` turns one into milliseconds.
//
// `raw` is the backend's own payload, untouched. It is a JSONObject, which compares by identity, so two
// equal entities that were parsed separately are NOT `==` while they carry a raw: a parser that wants a
// `Store` to skip an unchanged message must hand back the previous instance when the text is the same (WP1).

/** What every entity has, whatever its kind. */
sealed interface Entity {
    val ref: EntityRef
    val name: String
    val availability: Availability

    /** ISO 8601: when the state last changed. */
    val lastChanged: String?

    /** ISO 8601: when the backend last reported it. */
    val lastUpdated: String?
    val raw: JSONObject?
}

/** The current color of a light, when it has one. */
sealed interface LightColor {
    data class Temperature(val kelvin: Int) : LightColor

    /** [hue] 0..360, [saturation] 0..100, [rgb] the color as displayed when the backend reports it. */
    data class Hs(val hue: Double, val saturation: Double, val rgb: List<Int>? = null) : LightColor
}

data class LightCapabilities(
    val brightness: Boolean,
    val colorTemperature: Boolean,
    val color: Boolean,
    /** The color temperature range in kelvin, when [colorTemperature] is true. */
    val kelvinRange: IntRange? = null,
)

data class LightEntity(
    override val ref: EntityRef,
    override val name: String,
    override val availability: Availability,
    val on: Boolean,
    /** 0..1. Absent when the light is not dimmable. */
    val brightness: Double? = null,
    val color: LightColor? = null,
    val capabilities: LightCapabilities,
    override val lastChanged: String? = null,
    override val lastUpdated: String? = null,
    override val raw: JSONObject? = null,
) : Entity

/** Anything with two states that can be flipped: an outlet, a fan, a lock (on = locked). */
data class SwitchEntity(
    override val ref: EntityRef,
    override val name: String,
    override val availability: Availability,
    val on: Boolean,
    override val lastChanged: String? = null,
    override val lastUpdated: String? = null,
    override val raw: JSONObject? = null,
) : Entity

data class ClimateCapabilities(
    /** The modes this device supports, in display order. */
    val modes: List<ClimateMode>,
    val presets: List<String>,
    /** Whether a target temperature can be set. */
    val targetTemperature: Boolean,
    val step: Double,
    val range: ClosedFloatingPointRange<Double>,
)

data class ClimateEntity(
    override val ref: EntityRef,
    override val name: String,
    override val availability: Availability,
    val mode: ClimateMode,
    /** What it is doing right now, when it reports that. */
    val action: ClimateAction? = null,
    val targetTemperature: Double? = null,
    val currentTemperature: Double? = null,
    /** Relative humidity, 0..100. */
    val humidity: Double? = null,
    /** `°C` or `°F`. */
    val unit: String,
    val preset: String? = null,
    val capabilities: ClimateCapabilities,
    override val lastChanged: String? = null,
    override val lastUpdated: String? = null,
    override val raw: JSONObject? = null,
) : Entity

/** A one-shot thing to trigger: a scene, a script, a button, a vacuum's start. */
data class ActionEntity(
    override val ref: EntityRef,
    override val name: String,
    override val availability: Availability,
    /** ISO 8601, when it last ran. */
    val lastTriggered: String? = null,
    override val lastChanged: String? = null,
    override val lastUpdated: String? = null,
    override val raw: JSONObject? = null,
) : Entity

data class SensorEntity(
    override val ref: EntityRef,
    override val name: String,
    override val availability: Availability,
    /** The reading as text (also for non-numeric sensors: `clear`, `on`). */
    val value: String,
    /** The reading as a number, when it is one. */
    val numeric: Double? = null,
    val unit: String? = null,
    /** What is measured, e.g. `temperature`, `humidity`, `power`. Open vocabulary; `daylight` is the one the theme schedule reads. */
    val measurement: String? = null,
    override val lastChanged: String? = null,
    override val lastUpdated: String? = null,
    override val raw: JSONObject? = null,
) : Entity

/** Someone who lives here, and whether they are home. */
data class PersonEntity(
    override val ref: EntityRef,
    override val name: String,
    override val availability: Availability,
    /** `home`, `away`, or the name of a zone. */
    val location: String,
    val home: Boolean,
    val pictureUrl: String? = null,
    override val lastChanged: String? = null,
    override val lastUpdated: String? = null,
    override val raw: JSONObject? = null,
) : Entity

data class WeatherEntity(
    override val ref: EntityRef,
    override val name: String,
    override val availability: Availability,
    val condition: WeatherCondition,
    /** In [unit]. */
    val temperature: Double? = null,
    /** `°C` or `°F`. */
    val unit: String? = null,
    /** Percent. */
    val humidity: Double? = null,
    /** In [windUnit]. */
    val windSpeed: Double? = null,
    /** Degrees: 0 is north, 90 east. */
    val windBearing: Double? = null,
    val windUnit: String? = null,
    /** 0 to 11 and more. */
    val uvIndex: Double? = null,
    /** Percent of the sky covered by cloud. */
    val cloudCoverage: Double? = null,
    val apparentTemperature: Double? = null,
    val pressure: Double? = null,
    val pressureUnit: String? = null,
    val precipitationUnit: String? = null,
    /** The forecasts the weather source can give, if any. */
    val forecasts: List<ForecastType> = emptyList(),
    override val lastChanged: String? = null,
    override val lastUpdated: String? = null,
    override val raw: JSONObject? = null,
) : Entity

/** What a player is playing now. */
data class MediaInfo(val title: String? = null, val artist: String? = null, val album: String? = null, val artworkUrl: String? = null)

/** The playlist, album, artist or radio station a player is playing from. [id] is the same one the browse list gives that item. */
data class MediaSource(val id: String, val title: String, val kind: SourceKind? = null)

/** Players that play one stream together. */
data class MediaGroup(
    /** The player the stream belongs to, which the others follow. */
    val leader: EntityRef,
    /** The players that follow the leader, not counting it. */
    val members: List<EntityRef>,
)

data class MediaPlayerCapabilities(
    val volume: Boolean,
    val mute: Boolean,
    val next: Boolean,
    val previous: Boolean,
    val browse: Boolean,
    val search: Boolean,
    val seek: Boolean,
    val shuffle: Boolean,
    val queue: Boolean,
    val transfer: Boolean,
    val group: Boolean,
)

data class MediaPlayerEntity(
    override val ref: EntityRef,
    override val name: String,
    override val availability: Availability,
    val playback: PlaybackState,
    val media: MediaInfo? = null,
    val source: MediaSource? = null,
    /** Seconds into the current item as of [positionUpdatedAt]; a playing player advances from there. */
    val position: Double? = null,
    /** Length of the current item in seconds. Absent for a stream with no end. */
    val duration: Double? = null,
    /** ISO 8601: when [position] was true. */
    val positionUpdatedAt: String? = null,
    /** Absent when the player has no shuffle. */
    val shuffle: Boolean? = null,
    /** 0..1. Absent when the player has no volume control. */
    val volume: Double? = null,
    val muted: Boolean,
    /** The group this player plays in with others, the same on every one of them. Absent while it plays alone. */
    val group: MediaGroup? = null,
    /** The players this one can be grouped with. Empty when it cannot be grouped or the backend does not say. */
    val groupable: List<EntityRef> = emptyList(),
    val capabilities: MediaPlayerCapabilities,
    override val lastChanged: String? = null,
    override val lastUpdated: String? = null,
    override val raw: JSONObject? = null,
) : Entity

/** An entity with no dedicated kind: shown, not controlled. Also what an unknown `kind` from a newer runtime parses to. */
data class GenericEntity(
    override val ref: EntityRef,
    override val name: String,
    override val availability: Availability,
    val value: String,
    val unit: String? = null,
    override val lastChanged: String? = null,
    override val lastUpdated: String? = null,
    override val raw: JSONObject? = null,
) : Entity

/** Parses an entity of any kind from the runtime's JSON (`kind` selects the parser). */
object Entities {
    fun parse(ref: EntityRef, json: JSONObject): Entity = TODO("WP1")
}

/** ISO 8601 timestamps without `java.time` (API 24 has none). */
object Iso8601 {
    /** Milliseconds since the epoch, or null when [text] is not a timestamp. */
    fun parse(text: String?): Long? = TODO("WP1")
}

// --- Query results -------------------------------------------------------------------------------------------------

/** One entry in a media library. The [id] is opaque: only the integration that produced it can read it. */
data class BrowseItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val kind: BrowseKind,
    /** For a track: how long it plays, in seconds. */
    val duration: Double? = null,
    /** `playMedia` accepts this item. */
    val playable: Boolean,
    /** `browse` can open this item to list what is inside it. */
    val expandable: Boolean,
    /** For a shelf: the one kind of thing it holds, so a search begun on it can start with that kind. */
    val holds: BrowseKind? = null,
)

data class BrowseResult(
    /** A heading for what is listed, when the backend names it. */
    val title: String? = null,
    val items: List<BrowseItem>,
)

data class QueueItem(
    /** Stable for the item, whatever its place. */
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
    /** In seconds. */
    val duration: Double? = null,
    /** The track playing now. */
    val current: Boolean = false,
)

data class QueueResult(
    /** In the order they play, starting a little before the current one. */
    val items: List<QueueItem>,
    /** How many items the whole queue has, which can be far more than [items]. */
    val total: Int,
    /** How many items come before `items[0]`. */
    val offset: Int,
    /** How long the whole queue plays, in seconds, when the backend can say. */
    val duration: Double? = null,
)

data class ForecastPoint(
    /** When the step starts, ISO 8601. */
    val timestamp: String,
    val condition: WeatherCondition,
    /** The temperature, or the day's high. */
    val temperature: Double? = null,
    /** The day's low, for a forecast that covers a whole day. */
    val low: Double? = null,
    val precipitationProbability: Double? = null,
    val precipitation: Double? = null,
    val windSpeed: Double? = null,
    val windBearing: Double? = null,
    val windGustSpeed: Double? = null,
    val humidity: Double? = null,
    val cloudCoverage: Double? = null,
    val uvIndex: Double? = null,
    val apparentTemperature: Double? = null,
    val pressure: Double? = null,
    /** For [ForecastType.TwiceDaily]: whether this step is the day (true) or the night. */
    val daytime: Boolean? = null,
)

data class ForecastResult(
    val type: ForecastType,
    /** Soonest first; empty when the source has no forecast of that type. */
    val points: List<ForecastPoint>,
    val unit: String? = null,
    val windUnit: String? = null,
    val precipitationUnit: String? = null,
    val pressureUnit: String? = null,
)

data class HistoryPoint(
    /** The start of the bucket, ISO 8601. */
    val timestamp: String,
    val value: Double,
)

data class HistoryResult(
    /** Oldest first. */
    val points: List<HistoryPoint>,
    val kind: HistoryKind,
    val unit: String? = null,
)

data class LogbookEntry(
    val id: String,
    val message: String,
    /** ISO 8601. */
    val timestamp: String,
    val actor: String? = null,
    val actorKind: ActorKind = ActorKind.Person,
    val change: LogbookChange? = null,
)

data class LogbookResult(
    /** Newest first. */
    val entries: List<LogbookEntry>,
)

// --- Results delivered to callers ----------------------------------------------------------------------------------

/** What a watch knows about an entity right now. */
sealed interface Snapshot {
    /** Subscribed, nothing heard yet. */
    data object Loading : Snapshot

    /** The runtime says the entity does not exist (or was removed). */
    data object Missing : Snapshot
    data class Present(val entity: Entity) : Snapshot
}

/** How a command ended. */
sealed interface Outcome {
    data object Ok : Outcome
    data class Failed(val error: String) : Outcome
}

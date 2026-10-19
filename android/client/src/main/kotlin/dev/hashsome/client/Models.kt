package dev.hashsome.client

import org.json.JSONObject

// The entity model, mirroring packages/core/src/model/*.ts field for field (same names, same optionality:
// `?` here is `?` there). Timestamps stay ISO 8601 strings; `Iso8601` turns one into milliseconds.
//
// `raw` is the backend's own payload, untouched, kept as its JSON text in a [RawJson]: equal text is equal, so entities
// have plain value equality (a `Store` skips an unchanged message) and the text is parsed only if somebody reads it.

/**
 * The backend's own payload as JSON text. Equality and hash are by text; [json] parses it on first use (and a
 * `JSONObject` is mutable, so treat it as read-only).
 */
class RawJson(val text: String) {
    val json: JSONObject by lazy { JSONObject(text) }

    override fun equals(other: Any?): Boolean = other is RawJson && other.text == text

    override fun hashCode(): Int = text.hashCode()

    override fun toString(): String = text
}

/** What every entity has, whatever its kind. */
sealed interface Entity {
    val ref: EntityRef
    val name: String
    val availability: Availability

    /** ISO 8601: when the state last changed. */
    val lastChanged: String?

    /** ISO 8601: when the backend last reported it. */
    val lastUpdated: String?
    val raw: RawJson?
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
    override val raw: RawJson? = null,
) : Entity

/** Anything with two states that can be flipped: an outlet, a fan, a lock (on = locked). */
data class SwitchEntity(
    override val ref: EntityRef,
    override val name: String,
    override val availability: Availability,
    val on: Boolean,
    override val lastChanged: String? = null,
    override val lastUpdated: String? = null,
    override val raw: RawJson? = null,
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
    override val raw: RawJson? = null,
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
    override val raw: RawJson? = null,
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
    override val raw: RawJson? = null,
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
    override val raw: RawJson? = null,
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
    override val raw: RawJson? = null,
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
    override val raw: RawJson? = null,
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
    override val raw: RawJson? = null,
) : Entity

/** Parses an entity of any kind from the runtime's JSON (`kind` selects the parser). */
object Entities {
    fun parse(ref: EntityRef, json: JSONObject): Entity = parseEntity(ref, json)
}

/** ISO 8601 timestamps without `java.time` (API 24 has none). */
object Iso8601 {
    /** Milliseconds since the epoch, or null when [text] is not a timestamp. */
    fun parse(text: String?): Long? {
        if (text == null) return null
        var i = 0

        // Reads exactly [n] digits at the cursor, or fails.
        fun digits(n: Int): Int? {
            if (i + n > text.length) return null
            var v = 0
            for (k in 0 until n) {
                val c = text[i + k]
                if (c !in '0'..'9') return null
                v = v * 10 + (c - '0')
            }
            i += n
            return v
        }

        fun eat(c: Char): Boolean = if (i < text.length && text[i] == c) { i++; true } else false

        val year = digits(4) ?: return null
        if (!eat('-')) return null
        val month = digits(2) ?: return null
        if (!eat('-')) return null
        val day = digits(2) ?: return null
        if (month !in 1..12 || day !in 1..daysIn(year, month)) return null
        var hour = 0
        var minute = 0
        var second = 0
        var millis = 0
        var offsetMinutes = 0
        if (i < text.length) {
            if (!(eat('T') || eat('t') || eat(' '))) return null
            hour = digits(2) ?: return null
            if (!eat(':')) return null
            minute = digits(2) ?: return null
            if (eat(':')) {
                second = digits(2) ?: return null
                if (eat('.') || eat(',')) {
                    val start = i
                    while (i < text.length && text[i] in '0'..'9') i++
                    if (i == start) return null
                    // Milliseconds: the first three digits of the fraction, padded.
                    val fraction = text.substring(start, i).padEnd(3, '0').substring(0, 3)
                    millis = fraction.toInt()
                }
            }
            if (hour > 23 || minute > 59 || second > 60) return null
            if (i < text.length && !(eat('Z') || eat('z'))) {
                val sign = when {
                    eat('+') -> 1
                    eat('-') -> -1
                    else -> return null
                }
                val oh = digits(2) ?: return null
                eat(':')
                val om = digits(2) ?: 0
                if (oh > 23 || om > 59) return null
                offsetMinutes = sign * (oh * 60 + om)
            }
            if (i != text.length) return null
        }
        val days = daysFromCivil(year, month, day)
        return ((days * 24 + hour) * 60 + minute - offsetMinutes) * 60_000L + second * 1000L + millis
    }

    private fun daysIn(year: Int, month: Int): Int = when (month) {
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's algorithm). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }
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

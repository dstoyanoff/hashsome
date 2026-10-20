package dev.hashsome.client

// The closed vocabularies of the model, each with the exact string the runtime sends (`wire`).
// An unknown string maps to the enum's fallback, never to an exception: a newer runtime must not break an older app.

/** `Ready`: the state is meaningful. `Unavailable`: the device is unreachable. `Unknown`: reachable but it has not reported yet. */
enum class Availability(val wire: String) {
    Ready("ready"), Unavailable("unavailable"), Unknown("unknown");

    companion object {
        fun fromWire(s: String?): Availability = entries.firstOrNull { it.wire == s } ?: Unknown
    }
}

/** The state of the socket to the runtime. */
enum class LinkStatus { Connecting, Open, Closed }

/** The state of one integration's connection to its backend, as the runtime reports it. */
enum class ConnectionStatus(val wire: String) {
    Connecting("connecting"), Connected("connected"), Disconnected("disconnected"), Error("error");

    companion object {
        fun fromWire(s: String?): ConnectionStatus = entries.firstOrNull { it.wire == s } ?: Disconnected
    }
}

/** The sizes a picture can be asked for in. */
enum class AssetSize(val wire: String) {
    Small("small"), Medium("medium"), Large("large");

    companion object {
        fun fromWire(s: String?): AssetSize = entries.firstOrNull { it.wire == s } ?: Medium
    }
}

/** What a browsable item is. Anything an integration cannot place is [Other]. */
enum class BrowseKind(val wire: String) {
    Folder("folder"), Artist("artist"), Album("album"), Playlist("playlist"), Track("track"), Radio("radio"), Other("other");

    companion object {
        fun fromWire(s: String?): BrowseKind = entries.firstOrNull { it.wire == s } ?: Other
    }
}

/** What a forecast looks ahead in: a day per point, an hour, or half a day. */
enum class ForecastType(val wire: String) {
    Daily("daily"), Hourly("hourly"), TwiceDaily("twice_daily");

    companion object {
        fun fromWire(s: String?): ForecastType? = entries.firstOrNull { it.wire == s }
    }
}

/** How `playMedia` treats the queue: start now and leave the queue after it, clear first, or queue it next or last. */
enum class PlayMode(val wire: String) {
    Play("play"), Replace("replace"), Next("next"), Add("add");

    companion object {
        fun fromWire(s: String?): PlayMode = entries.firstOrNull { it.wire == s } ?: Play
    }
}

enum class PlaybackState(val wire: String) {
    Playing("playing"), Paused("paused"), Idle("idle"), Off("off"), Buffering("buffering");

    companion object {
        fun fromWire(s: String?): PlaybackState = entries.firstOrNull { it.wire == s } ?: Idle
    }
}

/** The kind of thing a player is playing from. */
enum class SourceKind(val wire: String) {
    Playlist("playlist"), Album("album"), Artist("artist"), Radio("radio");

    companion object {
        fun fromWire(s: String?): SourceKind? = entries.firstOrNull { it.wire == s }
    }
}

enum class ClimateMode(val wire: String) {
    Off("off"), Heat("heat"), Cool("cool"), Auto("auto"), HeatCool("heatCool"), Dry("dry"), FanOnly("fanOnly");

    companion object {
        fun fromWire(s: String?): ClimateMode = entries.firstOrNull { it.wire == s } ?: Off
    }
}

enum class ClimateAction(val wire: String) {
    Off("off"), Idle("idle"), Heating("heating"), Cooling("cooling"), Drying("drying"), Fan("fan");

    companion object {
        fun fromWire(s: String?): ClimateAction? = entries.firstOrNull { it.wire == s }
    }
}

/** What the sky is doing now: a closed set so a UI can pick an icon for each. Anything else is [Unknown]. */
enum class WeatherCondition(val wire: String) {
    Sunny("sunny"), ClearNight("clear-night"), PartlyCloudy("partlycloudy"), Cloudy("cloudy"), Fog("fog"),
    Rainy("rainy"), Pouring("pouring"), Snowy("snowy"), SnowyRainy("snowy-rainy"), Hail("hail"),
    Lightning("lightning"), LightningRainy("lightning-rainy"), Windy("windy"), Exceptional("exceptional"), Unknown("unknown");

    companion object {
        fun fromWire(s: String?): WeatherCondition = entries.firstOrNull { it.wire == s } ?: Unknown
    }
}

/** How far back a history query looks. */
enum class HistoryRange(val wire: String) {
    Hour("1h"), Day("1d"), Week("1w"), Month("1m")
}

/** How coarse the points of a history are. */
enum class HistoryBucket(val wire: String) {
    FiveMinutes("5m"), Hour("1h"), Day("1d")
}

/** `Total` when each value is how much a counter grew in its bucket, `Measurement` when it is the reading itself. */
enum class HistoryKind(val wire: String) {
    Measurement("measurement"), Total("total");

    companion object {
        fun fromWire(s: String?): HistoryKind = entries.firstOrNull { it.wire == s } ?: Measurement
    }
}

/** Who caused a logbook entry: a person gets an initials avatar, an automation a workflow icon. */
enum class ActorKind(val wire: String) {
    Person("person"), Automation("automation");

    companion object {
        fun fromWire(s: String?): ActorKind = entries.firstOrNull { it.wire == s } ?: Person
    }
}

/** What kind of change a logbook entry records. */
enum class LogbookChange(val wire: String) {
    On("on"), Off("off"), State("state"), Availability("availability");

    companion object {
        fun fromWire(s: String?): LogbookChange? = entries.firstOrNull { it.wire == s }
    }
}

package dev.hashsome.headless

import dev.hashsome.client.ActionEntity
import dev.hashsome.client.Availability
import dev.hashsome.client.BrowseItem
import dev.hashsome.client.BrowseKind
import dev.hashsome.client.ClimateCapabilities
import dev.hashsome.client.ClimateEntity
import dev.hashsome.client.ClimateMode
import dev.hashsome.client.Entity
import dev.hashsome.client.EntityKind
import dev.hashsome.client.EntityRef
import dev.hashsome.client.LightCapabilities
import dev.hashsome.client.LightEntity
import dev.hashsome.client.MediaInfo
import dev.hashsome.client.MediaPlayerCapabilities
import dev.hashsome.client.MediaPlayerEntity
import dev.hashsome.client.PlaybackState
import dev.hashsome.client.QueueItem
import dev.hashsome.client.SensorEntity
import dev.hashsome.client.SwitchEntity
import dev.hashsome.testing.FakeClient
import dev.hashsome.testing.TestScheduler
import org.json.JSONObject

/** A scheduler, a fake runtime on it, and the scope a screen would hold. */
class Rig {
    val scheduler = TestScheduler()
    val client = FakeClient(scheduler)
    val scope = Scope(client, scheduler)

    fun <E : Entity> handle(ref: EntityRef, kind: EntityKind<E>): EntityHandle<E> = EntityHandle(scope, ref, kind)

    /** Delivers what is queued, as the main loop would. */
    fun settle() = scheduler.runCurrent()
}

fun ref(id: String) = EntityRef("ha:$id")

fun light(
    id: String = "lamp",
    on: Boolean = true,
    brightness: Double? = 0.5,
    availability: Availability = Availability.Ready,
    colorTemperature: Boolean = false,
    color: Boolean = false,
    dimmable: Boolean = true,
    lightColor: dev.hashsome.client.LightColor? = null,
) = LightEntity(
    ref(id), id, availability, on, brightness, lightColor,
    LightCapabilities(brightness = dimmable, colorTemperature = colorTemperature, color = color),
)

fun switch(id: String = "plug", on: Boolean = false, availability: Availability = Availability.Ready) =
    SwitchEntity(ref(id), id, availability, on)

fun sensor(
    id: String = "t",
    value: String = "21.4",
    unit: String? = "°C",
    measurement: String? = null,
    availability: Availability = Availability.Ready,
    numeric: Double? = value.toDoubleOrNull(),
) = SensorEntity(ref(id), id, availability, value, numeric, unit, measurement)

fun climate(id: String = "heater", mode: ClimateMode = ClimateMode.Heat, target: Double? = 17.0, current: Double? = 16.4) =
    ClimateEntity(
        ref(id), id, Availability.Ready, mode, null, target, current, null, "°C", null,
        ClimateCapabilities(listOf(ClimateMode.Off, ClimateMode.Heat), listOf("eco"), true, 0.5, 5.0..30.0),
    )

fun action(id: String = "scene") = ActionEntity(ref(id), id, Availability.Ready)

val noCaps = MediaPlayerCapabilities(
    volume = true, mute = true, next = true, previous = true, browse = false, search = false, seek = true,
    shuffle = true, queue = false, transfer = false, group = false,
)

fun player(
    id: String = "room",
    playback: PlaybackState = PlaybackState.Paused,
    title: String? = null,
    position: Double? = null,
    duration: Double? = null,
    positionUpdatedAt: String? = null,
    volume: Double? = 0.4,
    muted: Boolean = false,
    shuffle: Boolean? = false,
    availability: Availability = Availability.Ready,
    caps: MediaPlayerCapabilities = noCaps,
    group: dev.hashsome.client.MediaGroup? = null,
    groupable: List<EntityRef> = emptyList(),
) = MediaPlayerEntity(
    ref = ref(id), name = id.replaceFirstChar { it.uppercase() }, availability = availability, playback = playback,
    media = title?.let { MediaInfo(title = it) }, position = position, duration = duration,
    positionUpdatedAt = positionUpdatedAt, shuffle = shuffle, volume = volume, muted = muted, group = group,
    groupable = groupable, capabilities = caps,
)

fun item(
    id: String,
    title: String = id,
    kind: BrowseKind = BrowseKind.Playlist,
    playable: Boolean = true,
    expandable: Boolean = false,
    holds: BrowseKind? = null,
    subtitle: String? = null,
) = BrowseItem(id = id, title = title, subtitle = subtitle, kind = kind, playable = playable, expandable = expandable, holds = holds)

fun queued(id: String, current: Boolean = false) = QueueItem(id = id, title = id, current = current)

fun json(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().also { o -> pairs.forEach { (k, v) -> o.put(k, v) } }

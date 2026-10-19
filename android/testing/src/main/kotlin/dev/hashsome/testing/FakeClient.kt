package dev.hashsome.testing

import dev.hashsome.client.Cancel
import dev.hashsome.client.Client
import dev.hashsome.client.ClimateEntity
import dev.hashsome.client.ClimateMode
import dev.hashsome.client.ActionEntity
import dev.hashsome.client.ConnectionStatus
import dev.hashsome.client.Entity
import dev.hashsome.client.EntityRef
import dev.hashsome.client.LightColor
import dev.hashsome.client.LightEntity
import dev.hashsome.client.LinkStatus
import dev.hashsome.client.MediaPlayerEntity
import dev.hashsome.client.Outcome
import dev.hashsome.client.PlaybackState
import dev.hashsome.client.Query
import dev.hashsome.client.Snapshot
import dev.hashsome.client.Store
import dev.hashsome.client.SwitchEntity
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * An in-memory [Client] for the headless tests (and the UI's): no transport, no parsing, typed entities.
 *
 * Contract:
 * - Entities: [set] makes an entity exist or change and [remove] makes it not exist. A [watch] gets a
 *   `Snapshot.Loading` first only if the ref was never set or removed (a test that wants Loading simply does not set it),
 *   `Present` or `Missing` after, and every later change, each delivered through the [TestScheduler] (so a test sees it
 *   after `runCurrent()` or `advance(...)`), and not after it is cancelled.
 * - Commands: every [command] is appended to [commands] as (ref, name, args) in order. [onCommand], when set, is called
 *   with it before the outcome is delivered and may call [set] to apply a simple state change (a toggle flipping `on`).
 *   It starts as [applyDefault]; set it to null for a runtime that never changes anything. The outcome is [Outcome.Ok],
 *   or the failure queued with [failNext], or, as `mock.ts` does, `Unknown entity` for a ref never [set] (or removed)
 *   and `Unknown command` for a name the entity's kind does not have (while [strictCommands] is on). A failed command
 *   changes nothing, so [onCommand] is not called for it; [done] gets the outcome through the scheduler.
 * - Queries: [answer] registers the JSON `data` to give for a query of that name (`browse`, `queue`, `forecast`...) on a
 *   ref; [query] parses it with the query's own `parse` and delivers `Result.success`, or `Result.failure` when none was
 *   registered or [failQuery] was set. [answerValue] and [answerWith] register an already parsed result instead, for a
 *   test that has no JSON to hand. Each ask is recorded in [queries] and answered after [queryDelayMs].
 * - [link] and [integrations] are plain settable stores, [connect] sets the link to `Open` and [close] to `Closed`.
 */
class FakeClient(val scheduler: TestScheduler) : Client {
    private class Watcher(val fn: (Snapshot) -> Unit) {
        var live = true
    }

    private sealed interface Answer {
        class Json(val data: JSONObject?) : Answer
        class Value(val value: Any) : Answer
        class Computed(val make: (Query<*>) -> Any) : Answer
    }

    // A ref in here with a null entity was removed; a ref not in here was never heard of.
    private val known = HashMap<EntityRef, Entity?>()
    private val watchers = HashMap<EntityRef, MutableList<Watcher>>()
    private val answers = HashMap<Pair<EntityRef, String>, Answer>()
    private val failures = ArrayList<String>()
    private var queryError: String? = null

    override val link: Store<LinkStatus> = Store(LinkStatus.Open)
    override val integrations: Store<Map<String, ConnectionStatus>> = Store(emptyMap())

    /** Every command sent: the entity, the name, and the args (null when there are none), oldest first. */
    val commands: MutableList<Triple<EntityRef, String, JSONObject?>> = ArrayList()

    /** Every query asked: the entity and the query's name, oldest first. */
    val queries: MutableList<Pair<EntityRef, String>> = ArrayList()

    /** How long a query takes to be answered, in scheduler ms. */
    var queryDelayMs: Long = 0

    /** Whether a command to an entity that does not exist, or a name its kind does not have, fails as the runtime's mock does. */
    var strictCommands: Boolean = true

    /** Called for each command before its outcome is delivered; apply a state change here with [set]. */
    var onCommand: ((EntityRef, String, JSONObject?) -> Unit)? = { ref, name, args -> applyDefault(ref, name, args) }

    /** Makes [entity] exist (or change) under [ref], notifying whoever watches it. */
    fun set(ref: EntityRef, entity: Entity) {
        known[ref] = entity
        notify(ref, Snapshot.Present(entity))
    }

    /** Makes the entity at [ref] not exist, notifying its watchers with `Snapshot.Missing`. */
    fun remove(ref: EntityRef) {
        known[ref] = null
        notify(ref, Snapshot.Missing)
    }

    /** The entity at [ref] as last set, or null when it does not exist or was never set. */
    fun entity(ref: EntityRef): Entity? = known[ref]

    /** Gives [data] as the answer to a query named [queryName] on [ref]. */
    fun answer(ref: EntityRef, queryName: String, data: JSONObject?) {
        answers[ref to queryName] = Answer.Json(data)
    }

    /** Gives [value] (a `BrowseResult`, `QueueResult`, `ForecastResult`...) as the parsed answer to every query named [queryName] on [ref]. */
    fun answerValue(ref: EntityRef, queryName: String, value: Any) {
        answers[ref to queryName] = Answer.Value(value)
    }

    /** Makes the answer to a query named [queryName] on [ref] from the query itself (its path, its search...), when asked. */
    fun answerWith(ref: EntityRef, queryName: String, make: (Query<*>) -> Any) {
        answers[ref to queryName] = Answer.Computed(make)
    }

    /** The next command ends with `Outcome.Failed(error)`. */
    fun failNext(error: String) {
        failures += error
    }

    /** Every query ends with `Result.failure(IllegalStateException(error))` while this is set. */
    fun failQuery(error: String?) {
        queryError = error
    }

    override fun connect() {
        link.set(LinkStatus.Open)
    }

    override fun close() {
        link.set(LinkStatus.Closed)
    }

    override fun watch(ref: EntityRef, fn: (Snapshot) -> Unit): Cancel {
        val watcher = Watcher(fn)
        watchers.getOrPut(ref) { ArrayList() } += watcher
        val first = if (known.containsKey(ref)) known[ref]?.let { Snapshot.Present(it) } ?: Snapshot.Missing else Snapshot.Loading
        scheduler.post { if (watcher.live) fn(first) }
        return {
            watcher.live = false
            watchers[ref]?.remove(watcher)
        }
    }

    override fun command(ref: EntityRef, name: String, args: JSONObject?, done: ((Outcome) -> Unit)?): Cancel {
        commands += Triple(ref, name, args)
        val queued = if (failures.isEmpty()) null else failures.removeAt(0)
        val failure = queued ?: if (strictCommands) rejection(ref, name) else null
        var live = true
        scheduler.post {
            if (failure == null) onCommand?.invoke(ref, name, args)
            if (done != null) {
                // After the state change has been delivered to its watchers, as a runtime that answers last would.
                scheduler.post { if (live) done(if (failure == null) Outcome.Ok else Outcome.Failed(failure)) }
            }
        }

        return { live = false }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> query(ref: EntityRef, query: Query<T>, done: (Result<T>) -> Unit): Cancel {
        queries += ref to query.name
        var live = true
        val respond: () -> Unit = {
            if (live) {
                val error = queryError
                val answer = answers[ref to query.name]
                done(
                    when {
                        error != null -> Result.failure(IllegalStateException(error))
                        answer == null -> Result.failure(IllegalStateException("No answer for ${query.name} on $ref"))
                        else -> runCatching {
                            when (answer) {
                                is Answer.Json -> query.parse(answer.data)
                                is Answer.Value -> answer.value as T
                                is Answer.Computed -> answer.make(query) as T
                            }
                        }
                    },
                )
            }
        }

        if (queryDelayMs > 0) scheduler.after(queryDelayMs, respond) else scheduler.post(respond)
        return { live = false }
    }

    private fun rejection(ref: EntityRef, name: String): String? {
        val entity = known[ref] ?: return "Unknown entity: $ref"
        val names = when (entity) {
            is LightEntity -> listOf("turnOn", "turnOff", "toggle", "setBrightness", "setColorTemperature", "setColor")
            is SwitchEntity -> listOf("turnOn", "turnOff", "toggle")
            is ClimateEntity -> listOf("setMode", "setTargetTemperature", "setPreset")
            is ActionEntity -> listOf("trigger")
            is MediaPlayerEntity -> listOf(
                "play", "pause", "togglePlay", "next", "previous", "setVolume", "setMuted", "seek", "setShuffle", "playMedia",
                "playQueueItem", "removeQueueItem", "moveQueueItem", "clearQueue", "setGroupMembers", "leaveGroup", "takeOverGroup",
            )
            else -> emptyList()
        }

        return if (name in names) null else "Unknown command: $name"
    }

    private fun notify(ref: EntityRef, snapshot: Snapshot) {
        for (watcher in watchers[ref].orEmpty().toList()) {
            scheduler.post { if (watcher.live) watcher.fn(snapshot) }
        }
    }

    /**
     * What `packages/core/src/mock.ts` does for a command, applied to the entity: a light or switch turns on and off,
     * a brightness of 0 turns a light off, a player plays, pauses, seeks and changes volume, mute and shuffle, a climate
     * takes a mode, target and preset, an action stamps `lastTriggered`. Not applied (the entity is left as it is):
     * `playMedia`, the queue and group commands, and `next`/`previous`; a test that needs them sets the state itself in
     * [onCommand]. The default [onCommand].
     */
    fun applyDefault(ref: EntityRef, name: String, args: JSONObject?) {
        val entity = known[ref] ?: return
        fun number(key: String): Double? = args?.takeIf { it.has(key) && !it.isNull(key) }?.getDouble(key)
        when (entity) {
            is LightEntity -> when (name) {
                "turnOn" -> set(ref, entity.copy(on = true))
                "turnOff" -> set(ref, entity.copy(on = false))
                "toggle" -> set(ref, entity.copy(on = !entity.on))
                "setBrightness" -> number("brightness")?.let { value ->
                    val brightness = value.coerceIn(0.0, 1.0)
                    set(ref, if (brightness == 0.0) entity.copy(on = false) else entity.copy(on = true, brightness = brightness))
                }
                "setColorTemperature" -> number("kelvin")?.let { set(ref, entity.copy(on = true, color = LightColor.Temperature(it.toInt()))) }
                "setColor" -> {
                    val hue = number("hue")
                    val saturation = number("saturation")
                    if (hue != null && saturation != null) set(ref, entity.copy(on = true, color = LightColor.Hs(hue, saturation)))
                }
            }

            is SwitchEntity -> when (name) {
                "turnOn" -> set(ref, entity.copy(on = true))
                "turnOff" -> set(ref, entity.copy(on = false))
                "toggle" -> set(ref, entity.copy(on = !entity.on))
            }

            is MediaPlayerEntity -> when (name) {
                "play" -> set(ref, entity.copy(playback = PlaybackState.Playing))
                "pause" -> set(ref, entity.copy(playback = PlaybackState.Paused))
                "togglePlay" -> set(ref, entity.copy(playback = if (entity.playback == PlaybackState.Playing) PlaybackState.Paused else PlaybackState.Playing))
                "setVolume" -> number("volume")?.let { set(ref, entity.copy(volume = it.coerceIn(0.0, 1.0))) }
                "setMuted" -> set(ref, entity.copy(muted = args?.optBoolean("muted") == true))
                "setShuffle" -> set(ref, entity.copy(shuffle = args?.optBoolean("shuffle") == true))
                "seek" -> number("position")?.let { set(ref, entity.copy(position = maxOf(0.0, it), positionUpdatedAt = iso(scheduler.wallMs()))) }
            }

            is ClimateEntity -> when (name) {
                "setMode" -> args?.optString("mode")?.let { set(ref, entity.copy(mode = ClimateMode.fromWire(it))) }
                "setTargetTemperature" -> number("temperature")?.let { set(ref, entity.copy(targetTemperature = it)) }
                "setPreset" -> args?.optString("preset")?.let { set(ref, entity.copy(preset = it)) }
            }

            is ActionEntity -> set(ref, entity.copy(lastTriggered = iso(scheduler.wallMs())))
            else -> Unit
        }
    }

    private fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))
}

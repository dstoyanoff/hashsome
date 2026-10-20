package dev.hashsome.headless

import dev.hashsome.client.Client
import dev.hashsome.client.ForecastResult
import dev.hashsome.client.ForecastType
import dev.hashsome.client.Query
import dev.hashsome.client.Store
import dev.hashsome.client.WeatherEntity
import java.util.WeakHashMap

sealed interface ForecastState {
    data object Idle : ForecastState
    data object Loading : ForecastState
    data class Ready(val type: ForecastType, val result: ForecastResult) : ForecastState
    data class Failed(val error: String) : ForecastState
}

/** The weather now, and its forecast on demand, cached for 10 minutes per type (mirrors `use-weather-forecast.ts`). */
class WeatherControl(val handle: EntityHandle<WeatherEntity>, val scope: Scope) {
    val forecast: Store<ForecastState> = Store(ForecastState.Idle)

    private var wanted: ForecastType? = null
    private var asked = 0

    /** One answer from the runtime, kept per client for [FRESH_MS]: shared by every control that asks the same thing. */
    private class Cached(val at: Long) {
        var result: ForecastResult? = null
        val waiting = ArrayList<(Result<ForecastResult>) -> Unit>()
    }

    init {
        // A forecast that could not be read because the connection was not there is asked for again when it is.
        scope.onReconnect { wanted?.let { ensure(it) } }
    }

    /** Makes sure a forecast of [type] is loaded or loading; a cached one younger than 10 minutes is used as it is. */
    fun ensure(type: ForecastType) {
        val ref = handle.ref ?: return
        wanted = type
        val mine = ++asked
        val answers = cache.getOrPut(scope.client) { HashMap() }
        val key = "$ref|${type.wire}"
        val now = scope.scheduler.now()
        val known = answers[key]?.takeIf { now - it.at < FRESH_MS }

        val deliver: (Result<ForecastResult>) -> Unit = { result ->
            if (mine == asked && !scope.disposed) {
                forecast.set(
                    result.fold(
                        onSuccess = { ForecastState.Ready(type, it) },
                        onFailure = { ForecastState.Failed(it.message ?: it.toString()) },
                    ),
                )
            }
        }

        if (known?.result != null) {
            deliver(Result.success(known.result!!))
            return
        }

        forecast.set(ForecastState.Loading)
        if (known != null) {
            // Asked already and not answered yet: wait for that one.
            known.waiting += deliver
            return
        }

        val entry = Cached(now)
        answers[key] = entry
        // Asked of the client itself, not through the scope: the answer is shared, so it is kept even if this screen is gone.
        scope.client.query(ref, Query.Forecast(type)) { result ->
            result.fold(
                onSuccess = { entry.result = it },
                // A failed answer is not worth keeping: the next ask tries again.
                onFailure = { if (answers[key] === entry) answers.remove(key) },
            )

            val waiting = entry.waiting.toList()
            entry.waiting.clear()
            waiting.forEach { it(result) }
        }

        entry.waiting += deliver
    }

    companion object {
        /** How long an answer is reused: a forecast changes every half hour or so at most. */
        const val FRESH_MS = 10 * 60_000L

        private val cache = WeakHashMap<Client, HashMap<String, Cached>>()
    }
}

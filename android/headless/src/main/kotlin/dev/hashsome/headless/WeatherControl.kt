package dev.hashsome.headless

import dev.hashsome.client.ForecastResult
import dev.hashsome.client.ForecastType
import dev.hashsome.client.Store
import dev.hashsome.client.WeatherEntity

sealed interface ForecastState {
    data object Idle : ForecastState
    data object Loading : ForecastState
    data class Ready(val type: ForecastType, val result: ForecastResult) : ForecastState
    data class Failed(val error: String) : ForecastState
}

/** The weather now, and its forecast on demand, cached for 10 minutes per type (mirrors `use-weather-forecast.ts`). */
class WeatherControl(val handle: EntityHandle<WeatherEntity>, val scope: Scope) {
    val forecast: Store<ForecastState> = Store(ForecastState.Idle)

    /** Makes sure a forecast of [type] is loaded or loading; a cached one younger than 10 minutes is used as it is. */
    fun ensure(type: ForecastType): Unit = TODO("WP2")
}

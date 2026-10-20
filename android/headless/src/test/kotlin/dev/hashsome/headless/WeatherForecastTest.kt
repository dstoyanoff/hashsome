package dev.hashsome.headless

import dev.hashsome.client.Availability
import dev.hashsome.client.EntityKind
import dev.hashsome.client.ForecastPoint
import dev.hashsome.client.ForecastResult
import dev.hashsome.client.ForecastType
import dev.hashsome.client.LinkStatus
import dev.hashsome.client.Query
import dev.hashsome.client.WeatherCondition
import dev.hashsome.client.WeatherEntity
import org.junit.Assert.assertEquals
import org.junit.Test

// use-weather-forecast.ts
class WeatherForecastTest {
    private val rig = Rig()
    private val asked = ArrayList<ForecastType>()

    private fun forecastOf(type: ForecastType) =
        ForecastResult(type, listOf(ForecastPoint("2026-06-01T12:00:00Z", WeatherCondition.Sunny, temperature = 21.0)))

    init {
        rig.client.answerWith(ref("home"), "forecast") { q ->
            val type = (q as Query.Forecast).type
            asked += type
            forecastOf(type)
        }
    }

    private fun weather(scope: Scope = rig.scope): WeatherControl {
        rig.client.set(ref("home"), WeatherEntity(ref("home"), "Home", Availability.Ready, WeatherCondition.Sunny, forecasts = ForecastType.entries))
        return WeatherControl(EntityHandle(scope, ref("home"), EntityKind.Weather), scope).also { rig.settle() }
    }

    @Test
    fun `nothing is asked until a forecast is`() {
        val w = weather()
        assertEquals(ForecastState.Idle, w.forecast.value)
        assertEquals(0, rig.client.queries.size)
    }

    @Test
    fun `a forecast is loading, then ready with its type`() {
        val w = weather()
        w.ensure(ForecastType.Hourly)
        assertEquals(ForecastState.Loading, w.forecast.value)
        rig.settle()
        assertEquals(ForecastState.Ready(ForecastType.Hourly, forecastOf(ForecastType.Hourly)), w.forecast.value)
        assertEquals(listOf(ForecastType.Hourly), asked)
    }

    @Test
    fun `an answer is reused for ten minutes`() {
        val w = weather()
        w.ensure(ForecastType.Daily)
        rig.settle()
        rig.scheduler.advance(599_999)
        w.ensure(ForecastType.Daily)
        // Ready at once, from what is kept.
        assertEquals(ForecastState.Ready(ForecastType.Daily, forecastOf(ForecastType.Daily)), w.forecast.value)
        rig.settle()
        assertEquals(1, asked.size)
        rig.scheduler.advance(1)
        w.ensure(ForecastType.Daily)
        assertEquals(ForecastState.Loading, w.forecast.value)
        rig.settle()
        assertEquals(2, asked.size)
        assertEquals(600_000L, WeatherControl.FRESH_MS)
    }

    @Test
    fun `each type is kept on its own`() {
        val w = weather()
        w.ensure(ForecastType.Daily)
        rig.settle()
        w.ensure(ForecastType.Hourly)
        rig.settle()
        w.ensure(ForecastType.Daily)
        assertEquals(ForecastState.Ready(ForecastType.Daily, forecastOf(ForecastType.Daily)), w.forecast.value)
        assertEquals(listOf(ForecastType.Daily, ForecastType.Hourly), asked)
    }

    @Test
    fun `the answer is shared by everyone who asks the client, but not by another client`() {
        val first = weather()
        first.ensure(ForecastType.Daily)
        rig.settle()
        val second = WeatherControl(EntityHandle(rig.scope.child(), ref("home"), EntityKind.Weather), rig.scope.child())
        second.ensure(ForecastType.Daily)
        assertEquals(ForecastState.Ready(ForecastType.Daily, forecastOf(ForecastType.Daily)), second.forecast.value)
        assertEquals(1, asked.size)

        val other = Rig()
        other.client.answerValue(ref("home"), "forecast", forecastOf(ForecastType.Daily))
        val third = WeatherControl(EntityHandle(other.scope, ref("home"), EntityKind.Weather), other.scope)
        third.ensure(ForecastType.Daily)
        assertEquals(ForecastState.Loading, third.forecast.value)
    }

    @Test
    fun `two asks at once share one request`() {
        val a = weather()
        val b = WeatherControl(EntityHandle(rig.scope, ref("home"), EntityKind.Weather), rig.scope)
        a.ensure(ForecastType.Daily)
        b.ensure(ForecastType.Daily)
        rig.settle()
        assertEquals(1, asked.size)
        assertEquals(a.forecast.value, b.forecast.value)
    }

    @Test
    fun `the latest ask wins`() {
        val w = weather()
        w.ensure(ForecastType.Daily)
        w.ensure(ForecastType.Hourly)
        rig.settle()
        assertEquals(ForecastState.Ready(ForecastType.Hourly, forecastOf(ForecastType.Hourly)), w.forecast.value)
    }

    @Test
    fun `a failed answer is shown as failed and not kept, so the next ask tries again`() {
        val w = weather()
        rig.client.failQuery("Not connected")
        w.ensure(ForecastType.Daily)
        rig.settle()
        assertEquals(ForecastState.Failed("Not connected"), w.forecast.value)
        rig.client.failQuery(null)
        w.ensure(ForecastType.Daily)
        rig.settle()
        assertEquals(ForecastState.Ready(ForecastType.Daily, forecastOf(ForecastType.Daily)), w.forecast.value)
    }

    @Test
    fun `it asks again when the connection is back`() {
        val w = weather()
        rig.client.link.set(LinkStatus.Closed)
        rig.client.failQuery("Connection lost")
        w.ensure(ForecastType.Daily)
        rig.settle()
        assertEquals(ForecastState.Failed("Connection lost"), w.forecast.value)
        rig.client.failQuery(null)
        rig.client.link.set(LinkStatus.Open)
        rig.settle()
        assertEquals(ForecastState.Ready(ForecastType.Daily, forecastOf(ForecastType.Daily)), w.forecast.value)
    }

    @Test
    fun `a connection that comes back changes nothing when nothing was asked`() {
        val w = weather()
        rig.client.link.set(LinkStatus.Closed)
        rig.client.link.set(LinkStatus.Open)
        rig.settle()
        assertEquals(ForecastState.Idle, w.forecast.value)
        assertEquals(0, asked.size)
    }

    @Test
    fun `a source with no forecast leaves it empty rather than failed`() {
        rig.client.answerValue(ref("home"), "forecast", ForecastResult(ForecastType.Daily, emptyList()))
        val w = weather()
        w.ensure(ForecastType.Daily)
        rig.settle()
        assertEquals(ForecastState.Ready(ForecastType.Daily, ForecastResult(ForecastType.Daily, emptyList())), w.forecast.value)
    }

    @Test
    fun `nothing is shown after the scope is disposed`() {
        val w = weather()
        w.ensure(ForecastType.Daily)
        rig.scope.dispose()
        rig.settle()
        assertEquals(ForecastState.Loading, w.forecast.value)
    }

    @Test
    fun `a weather with no entity behind it is never asked`() {
        val w = WeatherControl(EntityHandle(rig.scope, null, EntityKind.Weather), rig.scope)
        w.ensure(ForecastType.Daily)
        assertEquals(ForecastState.Idle, w.forecast.value)
        assertEquals(0, rig.client.queries.size)
    }
}

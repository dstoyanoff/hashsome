package dev.hashsome.headless

import dev.hashsome.client.Availability
import dev.hashsome.client.EntityKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// sensor-readout.test.tsx
class SensorReadoutTest {
    private val rig = Rig()

    private fun reading(
        entity: dev.hashsome.client.SensorEntity?,
        battery: dev.hashsome.client.SensorEntity? = null,
        range: ClosedFloatingPointRange<Double>? = null,
    ): SensorReading {
        entity?.let { rig.client.set(ref("s"), it) }
        battery?.let { rig.client.set(ref("b"), it) }
        val control = SensorReading(
            rig.handle(ref("s"), EntityKind.Sensor),
            battery?.let { rig.handle(ref("b"), EntityKind.Sensor) },
            range,
        )
        rig.settle()
        return control
    }

    private val humidity = { value: String -> sensor(value = value, unit = "%", measurement = "humidity") }

    @Test
    fun `formats numeric values with unit`() {
        val r = reading(sensor(value = "21.4", unit = "°C")).reading.value
        assertEquals("21.4 °C", r.formatted)
        assertEquals(21.4, r.value!!, 0.0)
        assertEquals("°C", r.unit)
    }

    @Test
    fun `a value is shown to one decimal`() {
        assertEquals("21.4 °C", reading(sensor(value = "21.43", unit = "°C")).reading.value.formatted)
        assertEquals("21.5", SensorReading.formatValue("21.45"))
        assertEquals("21", SensorReading.formatValue("21.0"))
        assertEquals("0", SensorReading.formatValue("-0.04"))
        assertEquals("-3.3", SensorReading.formatValue("-3.26"))
        assertEquals("1000000000", SensorReading.formatValue("1e9"))
    }

    @Test
    fun `non-numeric values are shown as is`() {
        assertEquals("clear", reading(sensor(value = "clear", unit = null)).reading.value.formatted)
        assertEquals("", SensorReading.formatValue(""))
        assertEquals("NaN", SensorReading.formatValue("NaN"))
    }

    @Test
    fun `a value with no unit has no trailing space`() {
        assertEquals("12", reading(sensor(value = "12", unit = null)).reading.value.formatted)
    }

    @Test
    fun `unavailable and unknown sensors say so`() {
        assertEquals("Unavailable", reading(sensor(availability = Availability.Unavailable)).reading.value.formatted)
        val unknown = SensorReading(rig.handle(ref("u"), EntityKind.Sensor))
        rig.client.set(ref("u"), sensor(id = "u", availability = Availability.Unknown))
        rig.settle()
        assertEquals("Unknown", unknown.reading.value.formatted)
        assertNull(unknown.reading.value.value)
    }

    @Test
    fun `missing sensors say so`() {
        rig.client.remove(ref("s"))
        assertEquals("Not found", reading(null).reading.value.formatted)
    }

    @Test
    fun `a sensor still loading shows an ellipsis`() {
        val control = SensorReading(rig.handle(ref("later"), EntityKind.Sensor))
        assertEquals("…", control.reading.value.formatted)
    }

    @Test
    fun `an entity of another kind is unsupported`() {
        rig.client.set(ref("s"), switch(id = "s"))
        val control = SensorReading(rig.handle(ref("s"), EntityKind.Sensor))
        rig.settle()
        assertEquals("Unsupported", control.reading.value.formatted)
    }

    @Test
    fun `humidity is flagged below 30 and above 60 by default, and fine in between`() {
        val control = reading(humidity("22"))
        assertEquals(false, control.reading.value.inSafeRange)
        rig.client.set(ref("s"), humidity("74"))
        rig.settle()
        assertEquals(false, control.reading.value.inSafeRange)
        rig.client.set(ref("s"), humidity("45"))
        rig.settle()
        assertEquals(true, control.reading.value.inSafeRange)
    }

    @Test
    fun `the edges of the range are safe`() {
        val control = reading(humidity("30"))
        assertEquals(true, control.reading.value.inSafeRange)
        rig.client.set(ref("s"), humidity("60"))
        rig.settle()
        assertEquals(true, control.reading.value.inSafeRange)
    }

    @Test
    fun `a custom safe range overrides the default, an unbounded one turns it off, temperature has none by default`() {
        assertEquals(false, reading(humidity("55"), range = 40.0..50.0).reading.value.inSafeRange)
        val off = reading(humidity("99"), range = Double.NEGATIVE_INFINITY..Double.POSITIVE_INFINITY)
        assertEquals(true, off.reading.value.inSafeRange)
        assertNull(off.hint.value)
        val temperature = SensorReading(rig.handle(ref("t"), EntityKind.Sensor))
        rig.client.set(ref("t"), sensor(id = "t", value = "40", unit = "°C", measurement = "temperature"))
        rig.settle()
        assertEquals(true, temperature.reading.value.inSafeRange)
    }

    @Test
    fun `an out-of-range reading says why`() {
        val control = reading(humidity("22").copy(name = "Bedroom"))
        assertEquals("Bedroom is too low (safe range 30–60 %)", control.hint.value)
        rig.client.set(ref("s"), humidity("74").copy(name = "Bedroom"))
        rig.settle()
        assertEquals("Bedroom is too high (safe range 30–60 %)", control.hint.value)
        rig.client.set(ref("s"), humidity("45"))
        rig.settle()
        assertNull(control.hint.value)
    }

    @Test
    fun `the drawer's verdict says whether the reading is within its range`() {
        val control = reading(humidity("22"))
        assertEquals(Verdict("Too low · safe range 30–60 %", true), control.verdict.value)
        rig.client.set(ref("s"), humidity("45"))
        rig.settle()
        assertEquals(Verdict("Within the safe range (30–60 %)", false), control.verdict.value)
        // No range, no verdict.
        assertNull(reading(sensor(value = "5", unit = "W")).verdict.value)
    }

    @Test
    fun `a range with one end open says at least or at most`() {
        assertEquals("t is too low (safe range at least 10 W)", reading(sensor(value = "5", unit = "W"), range = 10.0..Double.POSITIVE_INFINITY).hint.value)
    }

    @Test
    fun `a sensor that is not numeric is never flagged`() {
        assertEquals(true, reading(sensor(value = "on", unit = null, measurement = "humidity")).reading.value.inSafeRange)
    }

    @Test
    fun `the battery shows its level, low at 20 and below`() {
        val control = reading(sensor(), battery = sensor(id = "b", value = "87.4", unit = "%"))
        assertEquals(Battery(87, false), control.reading.value.battery)
        rig.client.set(ref("b"), sensor(id = "b", value = "20", unit = "%"))
        rig.settle()
        assertEquals(Battery(20, true), control.reading.value.battery)
        rig.client.set(ref("b"), sensor(id = "b", value = "21", unit = "%"))
        rig.settle()
        assertEquals(Battery(21, false), control.reading.value.battery)
    }

    @Test
    fun `an unavailable battery shows nothing, not a stale number`() {
        val control = reading(sensor(), battery = sensor(id = "b", value = "50", unit = "%", availability = Availability.Unavailable))
        assertNull(control.reading.value.battery)
    }

    @Test
    fun `without a battery there is none`() {
        assertNull(reading(sensor()).reading.value.battery)
    }

    // A sensor that says nothing for a day may be dead or stuck.
    private val hour = 3_600_000L
    private val noon = 1_760_000_000_000L

    private fun silent(hoursAgo: Long, id: String = "s") = sensor(id).copy(lastUpdated = iso(noon - hoursAgo * hour))

    private fun iso(ms: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))

    @Test
    fun `a sensor that updated within a day is not silent`() {
        rig.scheduler.wall = noon
        assertNull(reading(silent(23)).silence.value)
    }

    @Test
    fun `a sensor with no update for a day is silent, in hours and then in days`() {
        rig.scheduler.wall = noon
        val day = reading(silent(25)).silence.value!!
        assertEquals("No updates for 25 hours", day.title)
        assertEquals("The sensor may be dead or stuck.", day.hint)
        rig.scheduler.wall = noon
        assertEquals("No updates for 3 days", reading(silent(80)).silence.value!!.title)
    }

    @Test
    fun `silence is found as the time passes, with no new state`() {
        rig.scheduler.wall = noon
        val control = reading(silent(23))
        assertNull(control.silence.value)
        rig.scheduler.advance(2 * hour)
        assertEquals("No updates for 25 hours", control.silence.value!!.title)
    }

    @Test
    fun `an update ends the silence`() {
        rig.scheduler.wall = noon
        val control = reading(silent(30))
        assertEquals(30L, control.silence.value!!.hours)
        rig.client.set(ref("s"), silent(0))
        rig.settle()
        assertNull(control.silence.value)
    }

    @Test
    fun `a sensor with no timestamp is not called silent`() {
        rig.scheduler.wall = noon
        assertNull(reading(sensor()).silence.value)
    }
}

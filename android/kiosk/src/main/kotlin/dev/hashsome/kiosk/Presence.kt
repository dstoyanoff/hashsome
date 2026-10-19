package dev.hashsome.kiosk

// The proximity polarity of the X2i is ShellyElevate's (Apache-2.0, see android/NOTICE): DeviceModel.JENNA and DeviceSensorManager.

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dev.hashsome.client.Scheduler
import dev.hashsome.client.Store
import dev.hashsome.ui.TouchListener

/**
 * Proximity and ambient light. It is also a [TouchListener]: a touch counts as presence ([touches] counts them).
 * The sensors are registered by [start], which the service calls so they keep running while the screen is dark; both
 * Shelly proximity sensors are wake-up sensors and stay active while the display is off (spikes S1c, S2b).
 */
class Presence(context: Context, private val scheduler: Scheduler) : TouchListener {
    val near: Store<Boolean> = Store(false)
    val lux: Store<Float?> = Store(null)

    /** Counts touches, so a listener hears each one although the value is always "a touch". */
    internal val touches: Store<Int> = Store(0)

    /** The X2i reports near as the sensor's maximum and far as 0, the X1 the other way round: this makes `near` right for the former. */
    internal var invertProximity = false

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val proximity: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val light: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_LIGHT)
    private var started = false

    internal val hasLightSensor: Boolean get() = light != null
    internal val hasProximitySensor: Boolean get() = proximity != null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_PROXIMITY -> near.set(isNear(event.values[0], event.sensor.maximumRange, invertProximity))
                Sensor.TYPE_LIGHT -> lux.set(event.values[0])
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    override fun onTouch(event: android.view.MotionEvent): Boolean {
        // Every move of a finger is an event; a touch is the beginning of one.
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) touches.set(touches.value + 1)
        return false
    }

    internal fun start() {
        if (started) return
        started = true
        proximity?.let { manager?.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        light?.let { manager?.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        Log.i("presence", "proximity=${proximity?.name} (max ${proximity?.maximumRange}, invert=$invertProximity) light=${light?.name}")
    }

    internal fun stop() {
        if (!started) return
        started = false
        manager?.unregisterListener(listener)
    }

    internal companion object {
        /** Android's rule for a binary proximity sensor: nearer than the maximum range is near. [invert] flips the sensor's polarity (the X2i). */
        fun isNear(raw: Float, maxRange: Float, invert: Boolean): Boolean {
            val distance = if (invert) maxRange - raw else raw
            return distance < maxRange
        }
    }
}

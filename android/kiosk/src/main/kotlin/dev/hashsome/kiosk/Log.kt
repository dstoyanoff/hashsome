package dev.hashsome.kiosk

import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.hashsome.client.AndroidScheduler
import dev.hashsome.client.Scheduler

/**
 * Logging that reaches logcat on every display. The Shelly X1 sets `log.tag` to `E`, so `Log.i/w/d` from an app never show up
 * there (spike S2); on it everything is logged at error level, with its real level in the message. Elsewhere `i` and `w`.
 */
internal object Log {
    private const val TAG = "hashsome"

    /** `Build.DEVICE` of the X1 is `K400_MT6580_32_N` on the unit tested, so this is a prefix test. */
    internal fun dropsLowLevels(device: String?): Boolean = device != null && device.startsWith("K400_MT6580")

    private val onlyErrors = dropsLowLevels(Build.DEVICE)

    fun i(what: String, message: String) {
        if (onlyErrors) android.util.Log.e(TAG, "I $what: $message") else android.util.Log.i(TAG, "$what: $message")
    }

    fun w(what: String, message: String, error: Throwable? = null) {
        if (onlyErrors) android.util.Log.e(TAG, "W $what: $message", error) else android.util.Log.w(TAG, "$what: $message", error)
    }

    fun e(what: String, message: String, error: Throwable? = null) {
        android.util.Log.e(TAG, "$what: $message", error)
    }
}

private val main: Scheduler by lazy { AndroidScheduler(Handler(Looper.getMainLooper())) }

/** The main-thread scheduler the kiosk's parts default to; tests give theirs instead. */
internal fun mainScheduler(): Scheduler = main

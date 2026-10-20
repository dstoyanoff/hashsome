package dev.hashsome.kiosk

import android.content.ContextWrapper
import dev.hashsome.client.Scheduler
import dev.hashsome.client.Store

/** A [Screen] that records what it was asked, and writes nothing while it is off, like the real ones. */
internal class FakeScreen(var truly: Boolean = true) : Screen {
    override val canTrulyOff get() = truly
    override val state: Store<ScreenState> = Store(ScreenState.On)
    val writes = ArrayList<Int>()
    var offs = 0
    var wakes = 0

    /** What [litByOthers] answers: someone else raised the backlight. */
    var lit = false
    override fun litByOthers() = lit && state.value == ScreenState.Off

    override fun brightness(level: Int) {
        if (state.value == ScreenState.On) writes += level
    }

    override fun off() {
        offs++
        state.set(ScreenState.Off)
    }

    override fun wake() {
        wakes++
        lit = false
        state.set(ScreenState.On)
    }
}

/** A [Presence] without sensors (there is no sensor service on the JVM). */
internal fun fakePresence(scheduler: Scheduler): Presence = Presence(ContextWrapper(null), scheduler)

/** Puts [Settings] back to a known state on a [MemoryPrefs]; the object is shared, so every test starts here. */
internal fun freshSettings(config: KioskConfig = KioskConfig(), generic: Boolean = true): MemoryPrefs {
    val prefs = MemoryPrefs()
    Settings.load(prefs, config) { generic }
    return prefs
}

package dev.hashsome.kiosk

import android.content.Context
import android.content.SharedPreferences

/** The few SharedPreferences calls [Settings] needs, so it can be tested on a plain JVM with [MemoryPrefs]. */
internal interface Prefs {
    fun contains(key: String): Boolean
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

/** Everything is stored as text; a store knows how to read its own back. */
internal class MemoryPrefs : Prefs {
    val map = LinkedHashMap<String, String>()
    override fun contains(key: String) = map.containsKey(key)
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

internal class SharedPrefsPrefs(private val prefs: SharedPreferences) : Prefs {
    override fun contains(key: String) = prefs.contains(key)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    companion object {
        fun of(context: Context): Prefs = SharedPrefsPrefs(context.applicationContext.getSharedPreferences("hashsome.kiosk", Context.MODE_PRIVATE))
    }
}

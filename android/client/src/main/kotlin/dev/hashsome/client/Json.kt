package dev.hashsome.client

import org.json.JSONArray
import org.json.JSONObject

// Tolerant readers over org.json. `JSONObject.optString` answers the text "null" for a JSON null and `optDouble` answers NaN
// for an absent key, so the parsers never use them: a key that is absent, null or of another type is simply absent here.

internal fun JSONObject.str(key: String): String? = opt(key) as? String

internal fun JSONObject.num(key: String): Double? = (opt(key) as? Number)?.toDouble()?.takeIf { !it.isNaN() }

/** For the fields the TS types as integers (`kelvin`, `total`, `offset`, ids). */
internal fun JSONObject.int(key: String): Int? = (opt(key) as? Number)?.toInt()

internal fun JSONObject.bool(key: String): Boolean? = opt(key) as? Boolean

internal fun JSONObject.obj(key: String): JSONObject? = opt(key) as? JSONObject

internal fun JSONObject.arr(key: String): JSONArray? = opt(key) as? JSONArray

/** The strings of an array, skipping anything that is not one. */
internal fun JSONObject.strings(key: String): List<String> {
    val array = arr(key) ?: return emptyList()
    val out = ArrayList<String>(array.length())
    for (i in 0 until array.length()) (array.opt(i) as? String)?.let(out::add)
    return out
}

internal fun JSONObject.refs(key: String): List<EntityRef> = strings(key).map(::EntityRef)

/** The objects of an array, skipping anything that is not one. */
internal fun JSONObject.objects(key: String): List<JSONObject> {
    val array = arr(key) ?: return emptyList()
    val out = ArrayList<JSONObject>(array.length())
    for (i in 0 until array.length()) (array.opt(i) as? JSONObject)?.let(out::add)
    return out
}

package dev.hashsome.headless

/**
 * ISO 8601 timestamps to epoch milliseconds, with no `java.time` (API 24 has none) and no `SimpleDateFormat` (its
 * patterns cannot read a variable number of fractional digits). The client module's `Iso8601` does the same job for
 * the parsers; headless keeps its own so it does not wait on them.
 */
internal object IsoTime {
    private val pattern =
        Regex("""^(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2})(?::(\d{2})(?:[.,](\d+))?)?\s*(Z|z|[+-]\d{2}(?::?\d{2})?)?$""")

    /** Milliseconds since the epoch, or null when [text] is not a timestamp. A timestamp with no offset is UTC. */
    fun parse(text: String?): Long? {
        val m = pattern.matchEntire(text?.trim() ?: return null) ?: return null
        val g = m.groupValues
        val year = g[1].toInt()
        val month = g[2].toInt()
        val day = g[3].toInt()
        val hour = g[4].toInt()
        val minute = g[5].toInt()
        val second = if (g[6].isEmpty()) 0 else g[6].toInt()
        if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null
        val millis = if (g[7].isEmpty()) 0 else g[7].take(3).padEnd(3, '0').toInt()
        var offsetMinutes = 0
        val zone = g[8]
        if (zone.isNotEmpty() && zone != "Z" && zone != "z") {
            val sign = if (zone[0] == '-') -1 else 1
            val digits = zone.substring(1).replace(":", "")
            offsetMinutes = sign * (digits.substring(0, 2).toInt() * 60 + (if (digits.length > 2) digits.substring(2).toInt() else 0))
        }

        val days = daysFromCivil(year, month, day)
        return (((days * 24 + hour) * 60 + minute - offsetMinutes) * 60 + second) * 1000 + millis
    }

    /** Days since 1970-01-01 of a calendar date (proleptic Gregorian). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        val dayOfYear = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146097 + dayOfEra - 719468
    }
}

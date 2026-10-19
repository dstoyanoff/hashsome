package dev.hashsome.headless

/** `m:ss`, or `h:mm:ss` from an hour up (`formatDuration` of `media-progress.ts`). */
fun formatDuration(seconds: Double): String {
    val total = Math.max(0.0, Math.floor(seconds)).toLong()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val rest = (total % 60).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$rest" else "$minutes:$rest"
}

/** How long something plays, said plainly: `42 min`, `2 h`, `7 h 33 min`. For a whole queue or album, not a position (`formatLength`). */
fun formatLength(seconds: Double): String {
    val minutes = Math.floor(Math.max(0.0, seconds) / 60 + 0.5).toLong()
    if (minutes < 60) return "${Math.max(1L, minutes)} min"
    val hours = minutes / 60
    val rest = minutes % 60
    return if (rest == 0L) "$hours h" else "$hours h $rest min"
}

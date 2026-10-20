package dev.hashsome.testing

import org.json.JSONObject

/**
 * The JSON the runtime puts on the wire, generated from the `@hashsome/core` mocks by `pnpm generate:fixtures` into this
 * module's resources: `entities/<integration>-<id>.json` (one `entity` frame each), the files under `status/` and `results/`
 * (`result` frames: browse, queue, forecast, history, logbook, an error), `entity-null.json` and `pong.json`.
 */
object Fixtures {
    /** Every fixture path, relative to `fixtures/`, as `index.json` lists them. */
    val paths: List<String> by lazy {
        val array = org.json.JSONArray(text("index.json"))
        (0 until array.length()).map { array.getString(it) }
    }

    /** The paths in one folder (`entities`, `results`, `status`), e.g. `entities/ha-light.plain.json`. */
    fun inFolder(folder: String): List<String> = paths.filter { it.startsWith("$folder/") }

    /** The file's text, exactly as stored. */
    fun text(path: String): String {
        val stream = Fixtures::class.java.classLoader?.getResourceAsStream("fixtures/$path")
            ?: throw IllegalArgumentException("No fixture $path")
        return stream.use { String(it.readBytes(), Charsets.UTF_8) }
    }

    /** The file as the frame it holds. */
    fun json(path: String): JSONObject = JSONObject(text(path))
}

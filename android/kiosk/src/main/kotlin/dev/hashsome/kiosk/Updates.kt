package dev.hashsome.kiosk

import dev.hashsome.client.Cancel
import dev.hashsome.client.Scheduler
import dev.hashsome.client.Store
import org.json.JSONObject

/**
 * A release of the app, as `latest.json` describes it. The feed is any folder a plain HTTP GET can read (Home Assistant's `/local/`,
 * a static host, an object store), holding `latest.json` and the APK it names:
 * `{"packageName", "versionName", "versionCode", "apk", "sha256", "size", "notes"}`. `hashsome android build` writes both.
 */
data class Release(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    /** Where the APK is, absolute. */
    val apkUrl: String,
    /** Lower-case hex SHA-256 of the APK. */
    val sha256: String,
    val size: Long,
    val notes: String?,
)

object ReleaseFeed {
    const val FILE = "latest.json"

    /** The address of `latest.json` in the feed at [base] (a folder; a trailing slash or the file's own name is fine). */
    fun latestUrl(base: String): String {
        val trimmed = base.trim().trimEnd('/')
        return if (trimmed.endsWith(FILE)) trimmed else "$trimmed/$FILE"
    }

    /** The feed's folder: [base] without a `latest.json` on the end. */
    fun folder(base: String): String = base.trim().trimEnd('/').removeSuffix(FILE).trimEnd('/')

    /** A release from `latest.json`'s text, or null when it is not one (missing fields, a code that is not a number, a hash that is not hex). */
    fun parse(text: String, base: String): Release? {
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        val code = json.optLong("versionCode", -1)
        val apk = json.optString("apk", "")
        val sha = json.optString("sha256", "").lowercase()
        if (code <= 0 || apk.isBlank() || !SHA256.matches(sha)) return null
        val apkUrl = if (apk.startsWith("http://") || apk.startsWith("https://")) apk else "${folder(base)}/${apk.trimStart('/')}"
        return Release(
            packageName = json.optString("packageName", ""),
            versionName = json.optString("versionName", code.toString()),
            versionCode = code,
            apkUrl = apkUrl,
            sha256 = sha,
            size = json.optLong("size", -1),
            notes = json.optString("notes", "").ifBlank { null },
        )
    }

    /** The newest release of the feed at [base]: a `latest.json` in a folder, or the newest tag of a registry repository. Throws when there is none. */
    fun resolve(io: UpdateIO, base: String): Release =
        if (OciFeed.handles(base)) OciFeed.latest(io, base) else parse(io.fetch(latestUrl(base)), base) ?: error("latest.json is not a release")

    /** Whether [release] is a version this install should move to: a higher code, for this package. */
    fun isNewer(release: Release, installedPackage: String, installedCode: Long): Boolean =
        (release.packageName.isEmpty() || release.packageName == installedPackage) && release.versionCode > installedCode

    private val SHA256 = Regex("[0-9a-f]{64}")
}

/**
 * A release kept in an OCI registry (Zot, the Docker registry, GHCR) instead of a folder: `oci://host:port/repository` (plain HTTP, the
 * address a LAN registry answers on) or `ocis://...` (HTTPS). The tags are version codes; a tag's manifest has the APK as its one layer
 * (so its size and SHA-256 are the layer's) and a config blob `{packageName, versionName, versionCode, notes}`. `hashsome android
 * publish --to oci://...` writes it; the app only needs anonymous GETs.
 */
object OciFeed {
    const val CONFIG_TYPE = "application/vnd.hashsome.android.config.v1+json"
    const val APK_TYPE = "application/vnd.android.package-archive"
    private const val MANIFEST_TYPE = "application/vnd.oci.image.manifest.v1+json"

    /** Whether [base] names a registry repository. */
    fun handles(base: String): Boolean = base.trim().startsWith("oci://") || base.trim().startsWith("ocis://")

    /** `http(s)://host:port/v2/repository`, the root of the repository's API. */
    fun api(base: String): String {
        val text = base.trim().trimEnd('/')
        val secure = text.startsWith("ocis://")
        val rest = text.removePrefix("ocis://").removePrefix("oci://")
        val host = rest.substringBefore('/')
        val repository = rest.substringAfter('/', "")
        return "${if (secure) "https" else "http"}://$host/v2/$repository"
    }

    /** The highest version code among the tags of a `tags/list` answer; tags that are not a number (`latest`) are ignored. */
    fun newestTag(tagsJson: String): String? = try {
        val tags = JSONObject(tagsJson).optJSONArray("tags")
        (0 until (tags?.length() ?: 0)).map { tags!!.getString(it) }.filter { it.toLongOrNull() != null }.maxByOrNull { it.toLong() }
    } catch (_: Exception) {
        null
    }

    /** The release a manifest and its config blob describe, or null when they are not one. */
    fun release(base: String, manifestJson: String, configJson: String): Release? {
        return try {
            val layers = JSONObject(manifestJson).optJSONArray("layers") ?: return null
            val layer = (0 until layers.length()).map { layers.getJSONObject(it) }.firstOrNull { it.optString("mediaType") == APK_TYPE } ?: return null
            val digest = layer.optString("digest")
            if (!digest.startsWith("sha256:")) return null
            val config = JSONObject(configJson)
            val code = config.optLong("versionCode", -1)
            if (code <= 0) return null
            Release(
                packageName = config.optString("packageName", ""),
                versionName = config.optString("versionName", code.toString()),
                versionCode = code,
                apkUrl = "${api(base)}/blobs/$digest",
                sha256 = digest.removePrefix("sha256:").lowercase(),
                size = layer.optLong("size", -1),
                notes = config.optString("notes", "").ifBlank { null },
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Asks the registry for its newest release: the tags, then that tag's manifest and config. Throws when there is none. */
    fun latest(io: UpdateIO, base: String): Release {
        val api = api(base)
        val tag = newestTag(io.fetch("$api/tags/list")) ?: error("the registry has no release")
        val manifest = io.fetch("$api/manifests/$tag", MANIFEST_TYPE)
        val configDigest = JSONObject(manifest).optJSONObject("config")?.optString("digest")?.takeIf { it.isNotEmpty() } ?: error("the release has no config")
        return release(base, manifest, io.fetch("$api/blobs/$configDigest")) ?: error("the release in the registry is not a release")
    }
}

sealed interface UpdateState {
    /** No feed is set: nothing is checked. */
    data object Off : UpdateState

    /** A feed is set and nothing newer is known: [checkedAt] is a wall-clock time, or null before the first check. */
    data class Current(val checkedAt: Long?) : UpdateState

    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val percent: Int) : UpdateState

    /** The APK is handed to the system installer (which may ask for a tap). */
    data class Installing(val release: Release) : UpdateState

    /** The last check or install did not work; [release] is the one that was being installed, if any. */
    data class Failed(val message: String, val release: Release?) : UpdateState
}

/** What the updater needs of the outside world, so the logic is a plain state machine. */
interface UpdateIO {
    /** The text at [url] (a `latest.json`, or a registry's JSON when [accept] names the media type wanted), or throws. */
    fun fetch(url: String, accept: String? = null): String

    /** Downloads [url] to a file and returns its path, calling [progress] with 0..100; throws on failure. */
    fun download(url: String, progress: (Int) -> Unit): String

    /** The SHA-256 of the file at [path], lower-case hex. */
    fun sha256(path: String): String

    /** The size of the file at [path]. */
    fun size(path: String): Long

    /** Deletes the file at [path]. */
    fun delete(path: String)

    /** Hands the APK to the system installer; returns when it has been handed over (the install itself finishes later, usually by replacing this process). */
    fun install(path: String)
}

/**
 * Checks the feed at `Settings.updateUrl` on start, every [checkEveryMs], and when asked, and installs a newer release when asked.
 * Never installs by itself: Home Assistant's `update` entity (or the settings sheet) says when. The IO runs on a background thread
 * ([background]); every state change is posted back to the [scheduler]'s thread.
 */
class Updater(
    private val url: Store<String?>,
    private val io: UpdateIO,
    private val scheduler: Scheduler,
    private val background: (() -> Unit) -> Unit,
    private val installedPackage: String,
    private val installedCode: Long,
    val installedName: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val checkEveryMs: Long = 6 * 3_600_000L,
    private val firstCheckMs: Long = 60_000L,
    private val log: (String) -> Unit = {},
) {
    val state: Store<UpdateState> = Store(UpdateState.Off)

    private val cancels = ArrayList<Cancel>()
    private var periodic: Cancel? = null
    private var first: Cancel? = null
    private var busy = false
    private var running = false

    fun start() {
        if (running) return
        running = true
        cancels += url.listen { onUrl() }
        onUrl()
    }

    fun stop() {
        running = false
        cancels.forEach { it() }
        cancels.clear()
        periodic?.invoke()
        first?.invoke()
        periodic = null
        first = null
    }

    private fun onUrl() {
        periodic?.invoke()
        first?.invoke()
        periodic = null
        first = null
        if (url.value.isNullOrBlank()) {
            state.set(UpdateState.Off)
            return
        }
        if (state.value == UpdateState.Off) state.set(UpdateState.Current(null))
        first = scheduler.after(firstCheckMs) { check() }
        periodic = scheduler.every(checkEveryMs) { check() }
    }

    /** Asks the feed whether a newer release is there. */
    fun check() {
        val base = url.value?.takeIf { it.isNotBlank() } ?: return
        if (busy || state.value is UpdateState.Downloading || state.value is UpdateState.Installing) return
        busy = true
        background {
            val result = runCatching { ReleaseFeed.resolve(io, base) }
            scheduler.post {
                busy = false
                result.fold(
                    onSuccess = { release ->
                        state.set(
                            if (ReleaseFeed.isNewer(release, installedPackage, installedCode)) {
                                UpdateState.Available(release)
                            } else {
                                UpdateState.Current(clock())
                            },
                        )
                    },
                    onFailure = {
                        log("update check failed: ${it.message}")
                        state.set(UpdateState.Failed(it.message ?: "check failed", null))
                    },
                )
            }
        }
    }

    /** The system installer refused: shows it in the state (main thread). */
    fun fail(message: String) {
        val release = (state.value as? UpdateState.Installing)?.release
        state.set(UpdateState.Failed(message, release))
    }

    /** Downloads the release that is [UpdateState.Available] (or the last one that [UpdateState.Failed] on), checks it and hands it to the installer. */
    fun install() {
        val release = when (val s = state.value) {
            is UpdateState.Available -> s.release
            is UpdateState.Failed -> s.release ?: return
            else -> return
        }
        if (busy) return
        busy = true
        state.set(UpdateState.Downloading(release, 0))
        background {
            var path: String? = null
            val result = runCatching {
                val downloaded = io.download(release.apkUrl) { percent -> scheduler.post { state.set(UpdateState.Downloading(release, percent)) } }
                path = downloaded
                if (io.sha256(downloaded) != release.sha256) error("the download does not match its checksum")
                if (release.size > 0 && io.size(downloaded) != release.size) error("the download is not the size latest.json says")
                scheduler.post { state.set(UpdateState.Installing(release)) }
                io.install(downloaded)
            }
            scheduler.post {
                busy = false
                result.onFailure {
                    log("update install failed: ${it.message}")
                    path?.let { p -> runCatching { io.delete(p) } }
                    state.set(UpdateState.Failed(it.message ?: "install failed", release))
                }
            }
        }
    }
}

package dev.hashsome.kiosk

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * [UpdateIO] on Android: plain `HttpURLConnection` for the feed and the APK, the APK kept in the app's cache until the system installer
 * has it, and a `PackageInstaller` session for the install. A privileged system app (the X1's) holding `INSTALL_PACKAGES` installs
 * without asking; any other app gets the system's own confirmation, which [UpdateResultReceiver] shows. Either way the new version
 * replaces this process, so a successful install does not return.
 */
class AndroidUpdateIO(private val context: Context) : UpdateIO {
    private val dir: File get() = File(context.cacheDir, "updates").also { it.mkdirs() }

    override fun fetch(url: String): String {
        val c = open(url)
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    override fun download(url: String, progress: (Int) -> Unit): String {
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "update.apk")
        val c = open(url)
        try {
            val total = c.contentLengthLong
            var done = 0L
            var shown = -1
            c.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt() else 0
                        if (percent != shown && percent % 5 == 0) {
                            shown = percent
                            progress(percent)
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        return target.path
    }

    override fun sha256(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun size(path: String): Long = File(path).length()

    override fun delete(path: String) {
        File(path).delete()
    }

    override fun install(path: String) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            File(path).inputStream().use { input ->
                session.openWrite("app.apk", 0, File(path).length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val intent = Intent(context, UpdateResultReceiver::class.java).setAction(UpdateResultReceiver.ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 30_000
        c.useCaches = false
        if (c.responseCode !in 200..299) {
            c.disconnect()
            error("$url answered ${c.responseCode}")
        }
        return c
    }
}

/** Where the system installer reports back: shows its confirmation when it needs one, and says when an install did not work. */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    // Keep-in-front would send the installer's confirmation straight back to the dashboard: stand down while it is up.
                    Kiosk.pause.start(CONFIRM_PAUSE_MS)
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> failed?.invoke("the system installer said no ($status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }

    companion object {
        const val ACTION = "dev.hashsome.UPDATE_RESULT"

        /** How long the confirmation may stay up before the dashboard is brought back (it also returns as soon as it is resumed). */
        private const val CONFIRM_PAUSE_MS = 120_000L

        /** Set by the service that runs the updater, so a refused install shows in the state. */
        @Volatile
        var failed: ((String) -> Unit)? = null
    }
}

/** The app was replaced by an update: the old process is gone, so the service starts again (keep-in-front then brings the dashboard back). */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i("update", "replaced by an update, starting the service")
        Kiosk.startService(context)
    }
}

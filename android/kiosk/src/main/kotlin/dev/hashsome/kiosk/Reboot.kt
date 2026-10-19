package dev.hashsome.kiosk

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import dev.hashsome.client.Scheduler
import dev.hashsome.client.Store
import java.net.InetSocketAddress
import java.net.Socket

/** The ways this app can restart the device. */
internal enum class RebootPath { Local, ShellyRpc }

/** What the device offers, behind an interface so the choice is tested with fakes. */
internal interface RebootBackend {
    /** The app holds `REBOOT` or is the device owner: [rebootLocal] works. */
    fun canRebootLocal(): Boolean

    /** The display's own firmware answers the Shelly RPC on the device itself (`Shelly.GetDeviceInfo`). Blocking: not for the main thread. */
    fun shellyRpcAnswers(): Boolean

    fun rebootLocal(): Boolean
    fun rebootShellyRpc(): Boolean
}

/**
 * Whether and how the device can be restarted. Probing is asynchronous (a socket), so [canReboot] answers from the cache, false
 * until [probe] has found a way. [available] changes when it does.
 */
internal class Reboot(
    private val backend: RebootBackend,
    private val background: (() -> Unit) -> Unit = { work -> Thread(work, "hashsome-reboot-probe").apply { isDaemon = true }.start() },
    private val scheduler: Scheduler = mainScheduler(),
) {
    val available: Store<Boolean> = Store(false)

    @Volatile
    var path: RebootPath? = null
        private set

    fun canReboot(): Boolean = available.value

    /** Looks for a way to reboot, off the main thread; a Shelly profile tries the display's RPC first, any profile the local permission. */
    fun probe(shelly: Boolean) {
        background {
            val found = choose(shelly)
            scheduler.post {
                path = found
                available.set(found != null)
                Log.i("reboot", "restart the device: ${found?.name ?: "not available"}")
            }
        }
    }

    internal fun choose(shelly: Boolean): RebootPath? = when {
        shelly && backend.shellyRpcAnswers() -> RebootPath.ShellyRpc
        backend.canRebootLocal() -> RebootPath.Local
        else -> null
    }

    /** Restarts the device by the path [probe] found; where there is none it only logs. True when a restart was asked for. */
    fun rebootIfPossible(): Boolean {
        val ok = when (path) {
            RebootPath.Local -> backend.rebootLocal()
            RebootPath.ShellyRpc -> backend.rebootShellyRpc()
            null -> false
        }
        if (!ok) Log.w("kiosk", "reboot is not available")
        return ok
    }
}

/** The real device: `PowerManager.reboot` / the device-owner `reboot`, and the Shelly RPC over a plain socket (no cleartext policy applies to it). */
internal class DeviceRebootBackend(private val context: Context, private val hosts: List<String> = listOf("127.0.0.1", "localhost"), private val port: Int = 80) : RebootBackend {
    private val admin get() = DeviceAdmin.component(context)

    private fun owner(): Boolean =
        android.os.Build.VERSION.SDK_INT >= 24 &&
            (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isDeviceOwnerApp(context.packageName)

    override fun canRebootLocal(): Boolean =
        context.checkSelfPermission(REBOOT_PERMISSION) == PackageManager.PERMISSION_GRANTED || owner()

    override fun rebootLocal(): Boolean = try {
        if (context.checkSelfPermission(REBOOT_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).reboot(null)
        } else {
            (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).reboot(admin)
        }
        true
    } catch (e: Exception) {
        Log.w("reboot", "reboot refused: ${e.message}")
        false
    }

    override fun shellyRpcAnswers(): Boolean = hosts.any { rpc(it, "Shelly.GetDeviceInfo") in 200..299 }

    override fun rebootShellyRpc(): Boolean {
        // A delay so the answer gets out before the display goes down.
        val status = hosts.firstNotNullOfOrNull { host -> rpc(host, "Shelly.Reboot?delay_ms=1000").takeIf { it > 0 } } ?: return false
        Log.i("reboot", "Shelly.Reboot answered $status")
        return status in 200..299
    }

    /** `GET /rpc/<method>` over a socket; the HTTP status, or -1 when nothing answers. */
    private fun rpc(host: String, method: String): Int = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            socket.getOutputStream().apply {
                write("GET /rpc/$method HTTP/1.0\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            statusOf(socket.getInputStream().bufferedReader().readLine())
        }
    } catch (e: Exception) {
        -1
    }

    companion object {
        private const val REBOOT_PERMISSION = "android.permission.REBOOT"
        private const val TIMEOUT_MS = 1_500

        /** The code of an HTTP status line (`HTTP/1.0 200 OK`), -1 when it is not one. */
        internal fun statusOf(line: String?): Int = line?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: -1
    }
}

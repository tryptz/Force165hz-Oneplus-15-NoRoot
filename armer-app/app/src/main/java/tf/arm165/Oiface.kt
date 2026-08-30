package tf.arm165

import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * The `oplusoiface` binder — OxygenOS's game-scheduling service, which drives
 * the vendor game optimizations (HyperRendering / GPA). Registering a package
 * here is what makes the system treat it as a game rather than an ordinary app.
 *
 * Transaction codes and signatures were recovered from `oplus-framework.jar`
 * (`com.oplus.oiface.IOIfaceService`). Unlike `oplusscreenmode`, this service's
 * caller check lives in a native daemon and cannot be read statically — so
 * reachability is confirmed at runtime with a read-only probe before any
 * mutating call is issued. If the probe fails, every mutating call no-ops and
 * the UI reports the feature unsupported on this build.
 */
object Oiface {
    private const val TAG = "Arm165"
    private const val SERVICE = "oplusoiface"
    private const val IFACE = "com.oplus.oiface.IOIfaceService"

    // Recovered transaction codes.
    private const val TX_SET_GAME_MODE_STATUS = 1007 // (int status, String pkg)
    private const val TX_GET_INSTALLED_GAME_LIST = 1011 // () -> String[]
    private const val TX_SET_INSTALLED_GAME_LIST = 1010 // (String[])
    private const val TX_GET_CURRENT_GAME_PACKAGE = 1009 // () -> String
    private const val TX_GET_GPU_LOAD = 863 //              () -> float

    private const val GAME_MODE_ON = 1
    private const val GAME_MODE_OFF = 0

    // null = not yet probed. Cached so the read-only probe runs at most once.
    @Volatile private var reachable: Boolean? = null

    private fun service(): IBinder? = try {
        Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, SERVICE) as? IBinder
    } catch (t: Throwable) {
        null
    }

    private inline fun <T> transact(code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T, fallback: T): T {
        val binder = service() ?: return fallback
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IFACE)
            write(data)
            binder.transact(code, data, reply, 0)
            reply.readException()
            read(reply)
        } catch (t: Throwable) {
            Log.w(TAG, "oiface transact $code failed", t)
            fallback
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * True when the service exists and answers a harmless read-only call.
     * Result is cached; [refresh] forces a re-probe (e.g. after an OTA).
     */
    fun isReachable(refresh: Boolean = false): Boolean {
        if (!refresh) reachable?.let { return it }
        val binder = service()
        if (binder == null) {
            reachable = false
            return false
        }
        // getGpuLoad reads a sensor and changes no state — the safe probe.
        val ok = probe(TX_GET_GPU_LOAD) { it.readFloat() } || probe(TX_GET_CURRENT_GAME_PACKAGE) { it.readString() }
        reachable = ok
        Log.i(TAG, "oiface reachable=$ok")
        return ok
    }

    /** Issues one read-only call purely to see whether the daemon answers us. */
    private inline fun probe(code: Int, read: (Parcel) -> Any?): Boolean {
        val binder = service() ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IFACE)
            binder.transact(code, data, reply, 0)
            reply.readException() // a caller-permission failure throws here
            read(reply)
            true
        } catch (t: Throwable) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun installedGames(): List<String> = transact(
        TX_GET_INSTALLED_GAME_LIST,
        write = { },
        read = { it.createStringArray()?.toList().orEmpty() },
        fallback = emptyList(),
    )

    private fun setInstalledGames(games: List<String>) = transact(
        TX_SET_INSTALLED_GAME_LIST,
        write = { it.writeStringArray(games.toTypedArray()) },
        read = { },
        fallback = Unit,
    )

    private fun setGameMode(packageName: String, on: Boolean) = transact(
        TX_SET_GAME_MODE_STATUS,
        write = { it.writeInt(if (on) GAME_MODE_ON else GAME_MODE_OFF); it.writeString(packageName) },
        read = { },
        fallback = Unit,
    )

    /**
     * Adds [packageName] to the system game list and marks it in game mode.
     * No-op unless the daemon has already answered a probe. Returns whether the
     * work was actually attempted.
     */
    fun registerGame(packageName: String): Boolean {
        if (!isReachable()) return false
        val games = installedGames()
        if (packageName !in games) setInstalledGames(games + packageName)
        setGameMode(packageName, on = true)
        return true
    }

    /** Removes [packageName] from the system game list. */
    fun unregisterGame(packageName: String): Boolean {
        if (!isReachable()) return false
        val games = installedGames()
        if (packageName in games) setInstalledGames(games - packageName)
        setGameMode(packageName, on = false)
        return true
    }
}

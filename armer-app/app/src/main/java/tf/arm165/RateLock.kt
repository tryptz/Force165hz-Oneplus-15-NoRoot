package tf.arm165

import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * The `oplusscreenmode` binder. Unguarded vendor IPC — no permission check on
 * the caller — reached by reflection so the app carries no vendor stubs.
 *
 * Transaction codes and signatures were recovered from `oplus-framework.jar`
 * (`com.oplus.screenmode.IOplusScreenMode`); `requestGameRefreshRate = 12`
 * matches the `0x0c` this app has always used.
 */
object RateLock {
    private const val TAG = "Arm165"
    private const val SERVICE = "oplusscreenmode"
    private const val IFACE = "com.oplus.screenmode.IOplusScreenMode"

    // Recovered transaction codes.
    private const val TX_REQUEST_GAME_REFRESH_RATE = 12 // (String, int) -> boolean
    private const val TX_GET_GAME_LIST = 14 //             (Bundle) inout -> boolean
    private const val TX_SET_APP_OVERRIDE = 25 //          (String, int mode, int rate) -> boolean
    private const val TX_GET_APP_OVERRIDE = 26 //          (String, int mode) -> int
    private const val TX_GET_APP_OVERRIDE_LIST = 27 //     () -> Bundle

    // Vendor rateIds, same numbering as refresh_rate_config.xml.
    const val RATE_60 = 2
    const val RATE_90 = 1
    const val RATE_120 = 3
    const val RATE_144 = 4
    const val RATE_165 = 7

    /** Every id we can name. 90 Hz is not offered but still reads back correctly. */
    private val ALL = listOf(
        RATE_60 to 60, RATE_90 to 90, RATE_120 to 120, RATE_144 to 144, RATE_165 to 165,
    )

    /** Rates offered in the UI, in display order: rateId to Hz. */
    val RATES = listOf(RATE_60 to 60, RATE_120 to 120, RATE_144 to 144, RATE_165 to 165)

    const val DEFAULT_RATE = RATE_165

    fun hz(rateId: Int): Int = ALL.firstOrNull { it.first == rateId }?.second ?: 165

    fun isKnown(rateId: Int): Boolean = ALL.any { it.first == rateId }

    private fun service(): IBinder? = try {
        Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, SERVICE) as? IBinder
    } catch (t: Throwable) {
        Log.w(TAG, "getService($SERVICE) failed", t)
        null
    }

    /** Runs one transaction, always with the interface token written first. */
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
            Log.w(TAG, "transact $code failed", t)
            fallback
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Transient game-rate vote via `requestGameRefreshRate`.
     *
     * Re-issuing the id an app is already pinned at removes the override, so a
     * disarm has to replay that app's own rate — see [ArmedStore]. The vote is
     * lost when the app leaves the foreground, which is why the watchdog exists.
     */
    fun arm(packageName: String, rateId: Int = DEFAULT_RATE): Boolean = transact(
        TX_REQUEST_GAME_REFRESH_RATE,
        write = { it.writeString(packageName); it.writeInt(rateId) },
        read = { reply ->
            val res = reply.readInt()
            Log.i(TAG, "$packageName -> id=$rateId res=$res")
            res == 1
        },
        fallback = false,
    )

    /**
     * Persistent per-app override via `setAppOverrideRefreshRate` — the same
     * call the system Settings app uses, so it survives foreground changes
     * without the watchdog. [mode] is the vendor override mode; 0 is the plain
     * per-app case (see the on-device verification note in the plan).
     */
    fun setAppOverride(packageName: String, rateId: Int, mode: Int = 0): Boolean = transact(
        TX_SET_APP_OVERRIDE,
        write = { it.writeString(packageName); it.writeInt(mode); it.writeInt(rateId) },
        read = { it.readInt() != 0 },
        fallback = false,
    )

    /** Clears a persistent override by writing rate 0 (auto). */
    fun clearAppOverride(packageName: String, mode: Int = 0): Boolean =
        setAppOverride(packageName, 0, mode)

    /** Current persistent override rateId for [packageName], or 0 when unset. */
    fun appOverride(packageName: String, mode: Int = 0): Int = transact(
        TX_GET_APP_OVERRIDE,
        write = { it.writeString(packageName); it.writeInt(mode) },
        read = { it.readInt() },
        fallback = 0,
    )

    /** Packages that currently hold a persistent override, from the system's list. */
    fun overriddenPackages(): Set<String> = transact(
        TX_GET_APP_OVERRIDE_LIST,
        write = { },
        read = { reply -> reply.readBundleCompat()?.let(::packagesIn).orEmpty() },
        fallback = emptySet(),
    )

    /**
     * The system's own recognized-game list, a secondary signal for detection.
     * Keys inside the Bundle aren't documented, so pull every package-shaped
     * value out defensively rather than assuming a schema.
     */
    fun systemGameList(): Set<String> = transact(
        TX_GET_GAME_LIST,
        write = { },
        read = { reply ->
            reply.readBoolean()
            if (reply.readInt() != 0) {
                val bundle = Bundle().apply { readFromParcel(reply) }
                packagesIn(bundle)
            } else {
                emptySet()
            }
        },
        fallback = emptySet(),
    )

    private fun Parcel.readBundleCompat(): Bundle? =
        if (readInt() != 0) Bundle().apply { readFromParcel(this@readBundleCompat) } else null

    /** Collects String / string-collection values from an untyped Bundle. */
    private fun packagesIn(bundle: Bundle): Set<String> {
        val out = HashSet<String>()
        for (key in bundle.keySet()) {
            @Suppress("DEPRECATION")
            when (val v = bundle.get(key)) {
                is String -> if (v.contains('.')) out += v
                is Array<*> -> v.forEach { if (it is String && it.contains('.')) out += it }
                is Collection<*> -> v.forEach { if (it is String && it.contains('.')) out += it }
            }
        }
        return out
    }
}

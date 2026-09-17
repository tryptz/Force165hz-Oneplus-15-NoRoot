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

    // Vendor rateIds, same numbering as refresh_rate_config.xml. They are not
    // in Hz order: the vendor numbers 90 Hz as 1 and 60 Hz as 2. Builds that
    // read them the other way round put the panel on 90 Hz when the user asked
    // for 60 and on 60 Hz when they asked for 90 — see ArmedStore.migrate.
    const val RATE_90 = 1
    const val RATE_60 = 2
    const val RATE_120 = 3
    const val RATE_144 = 4
    const val RATE_165 = 7

    /**
     * Not a rate: the vendor's "no request" id, used to withdraw a vote. The
     * real ids all come from refresh_rate_config.xml and start at 1, so 0 is out
     * of band, and it is the same value `setAppOverrideRefreshRate` reads as
     * "auto" — see [release].
     */
    const val RATE_NONE = 0

    /** Every id we can name, for lookups: rateId to Hz. */
    private val ALL = listOf(
        RATE_60 to 60, RATE_90 to 90, RATE_120 to 120, RATE_144 to 144, RATE_165 to 165,
    )

    /** Rates offered in the UI, in display order: rateId to Hz. */
    val RATES = listOf(
        RATE_60 to 60, RATE_90 to 90, RATE_120 to 120, RATE_144 to 144, RATE_165 to 165,
    )

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
     * The call is a plain set, not a toggle: the watchdog re-issues every armed
     * app's own id every few seconds and the pin holds instead of flickering
     * off, so handing the vendor an id it already holds can never be the way to
     * withdraw it — that is [release]'s job. The vote applies while the app is
     * foregrounded, which is why the watchdog exists.
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
        setAppOverride(packageName, RATE_NONE, mode)

    /**
     * Withdraws whatever we pinned for [packageName] — the thing [arm] cannot
     * do by being called again. The vendor stores the last id it was handed, so
     * replaying [rateId], which is what a disarm used to do, only wrote the same
     * pin back: the panel stayed at the armed rate until a reboot rebuilt the
     * service's state (issue #10).
     *
     * No single cancel is documented, so the candidates are tried in order and
     * the first one the vendor accepts wins:
     *
     *  1. the vote again at [RATE_NONE] — the out-of-band id, which is the shape
     *     a vendor cancel usually takes;
     *  2. the persistent per-app override set back to auto. That is a different
     *     store, worth touching only once the vote itself refused to lift, since
     *     it would otherwise also reset a per-app rate the user picked in the
     *     system display settings;
     *  3. replaying [rateId], the old behaviour, so a build where that really
     *     did release keeps releasing.
     *
     * Returns whether any of them reported success, and logs which one did — a
     * bug report from a build that answers differently is the only way to find
     * out that it does.
     */
    fun release(packageName: String, rateId: Int): Boolean {
        if (arm(packageName, RATE_NONE)) {
            Log.i(TAG, "$packageName released by vote id=$RATE_NONE")
            return true
        }
        if (clearAppOverride(packageName)) {
            Log.i(TAG, "$packageName released by app override -> auto")
            return true
        }
        val replayed = arm(packageName, rateId)
        Log.i(TAG, "$packageName release fell back to replaying id=$rateId ok=$replayed")
        return replayed
    }

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

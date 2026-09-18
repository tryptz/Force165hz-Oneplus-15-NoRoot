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
     * of band, and the server reads it as "remove this package's vote and
     * unpin its windows" — see [release].
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

    /**
     * Measured LTPO idle floor of the panel mode each rateId selects — the
     * lowest the panel goes while that vote is held. From this build's
     * measurements (README): 60 -> 30, 90 -> 30, 120 -> 1, 165 -> 55. The 55
     * is confirmed on device: a still screen with 165 held ramps down to it
     * and stops there, which is the whole reason the park swaps in 120.
     *
     * 144 has never been measured. It takes the ratio the 90 and 165 modes
     * share, a floor near a third of the mode's own rate, which errs on the
     * safe side for the park gate: guessing the floor too LOW only makes a
     * park slower to trigger, while guessing it too high would let the gate
     * mistake real motion for the floor. Measure it and replace the estimate.
     */
    fun idleFloorHz(rateId: Int): Int = when (rateId) {
        RATE_120 -> 1
        RATE_60, RATE_90 -> 30
        RATE_144 -> 48
        RATE_165 -> 55
        else -> hz(rateId) / 3
    }

    /**
     * Packages no vote may ever name. `android` is the framework itself, which
     * `setrate.sh --all` has skipped since the first sweep: it is not an app
     * anyone looks at, and its windows belong to the system rather than to a
     * screen.
     */
    val NEVER_ARM = setOf("android")

    /**
     * SystemUI — the notification shade, quick settings, the lock screen and
     * the always-on display. Armable, but only on purpose: its windows are
     * composited next to every app's, so the pin tends to apply display-wide
     * instead of to one app, which is the opposite of what the per-app
     * ordering is for.
     */
    const val SYSTEM_UI = "com.android.systemui"

    /**
     * Whether swapping this rate for 120 can idle LOWER than the rate's own
     * mode does. Measured on this build, and the answer is 144 alone.
     *
     * From 144 the switch is clean and the panel ramps to 1 Hz. From 165 it is
     * not: the extreme mode has to be left through 144 first, and the 120 that
     * follows behaves as a pin at 120 rather than a 1-120 range, so the park
     * lands ABOVE the 55 Hz the 165 mode idles to on its own. Parking 165
     * therefore costs idle power instead of saving it, whatever the vote says.
     * A still 165 screen rests at 55 and that is the floor on this build.
     */
    fun parkable(rateId: Int): Boolean = hz(rateId) in 121..144

    /**
     * Packages a bulk sweep must skip while a deliberate row tap may still arm
     * them: SystemUI for the reason above, and [self] because an app arming
     * itself is a choice (a useful one — it is the cheapest test of whether
     * votes are landing at all) rather than something Arm all should decide.
     */
    fun optInOnly(self: String): Set<String> = setOf(SYSTEM_UI, self)

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
     * Issues one vote per entry of [armed] and returns the packages whose vote
     * the server took, with [last] — the app on screen, when anything can name
     * it — left for the end.
     *
     * The order is why no caller writes this loop itself. The server writes
     * the override onto the live windows of the package each call names, and
     * the panel takes its mode from the write that landed last, so a sweep
     * ending on a background package hands the display a vote for windows that
     * do not exist and dissolves the pin the same sweep just set. That is what
     * arming every app did to itself, once per watchdog tick, and why the
     * panel could reach 1 Hz inside an app that was supposed to be pinned.
     */
    fun armEach(armed: Map<String, Int>, last: String? = null): List<String> {
        val done = ArrayList<String>(armed.size)
        armed.forEach { (pkg, rateId) -> if (pkg != last && arm(pkg, rateId)) done += pkg }
        last?.let { pkg -> armed[pkg]?.let { rateId -> if (arm(pkg, rateId)) done += pkg } }
        return done
    }

    /**
     * Persistent per-app override via `setAppOverrideRefreshRate` — the same
     * call the system Settings app uses, so it survives foreground changes
     * without the watchdog. DEAD ON THIS BUILD: the service enforces
     * `oplus.permission.OPLUS_COMPONENT_SAFE`, which a rootless app cannot
     * hold (verified live — the call answers with a SecurityException). Kept
     * only so privileged builds still have the knob; [release] never relies
     * on it.
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
     * The vendor's own cancel is `requestGameRefreshRate(pkg, 0)`. Verified
     * against the server on this exact build (CPH2749_16.0.9.400,
     * oplus-services.jar md5 4ee84ffc…): with a 0 id,
     * `OplusRefreshRatePolicyImpl.requestGameRefreshRate` takes the
     * `mOifaceRequestedRates.remove(pkg)` branch and then rewrites the override
     * on every live window the package owns
     * (`lambda$requestGameRefreshRate$3` writes the 0 straight into
     * `mOifaceOverrideRateId`), so the unpin lands without a traversal or a
     * reboot. Verified live too: result=1 from an unprivileged uid, idempotent.
     *
     * Every other candidate is closed, not just undocumented:
     * `setAppOverrideRefreshRate` (0x19), `removeCustomizeRefreshRate` (0x1c),
     * `removeAllCustomizeRefreshRate` (0x1d) and `getAppOverrideRefreshRate`
     * (0x1a) all enforce `oplus.permission.OPLUS_COMPONENT_SAFE` — a live call
     * from an unprivileged uid answers with a SecurityException naming it — so
     * a rootless app cannot reach the per-app override store at all.
     *
     * [rateId] stays in the signature for call-site clarity but is no longer
     * used; replaying an id can only re-pin, never release.
     */
    fun release(packageName: String, @Suppress("UNUSED_PARAMETER") rateId: Int): Boolean {
        val ok = arm(packageName, RATE_NONE)
        if (ok) {
            Log.i(TAG, "$packageName released (vote -> id=$RATE_NONE)")
        } else {
            Log.w(TAG, "$packageName release failed , vendor unreachable; a reboot clears every pin")
        }
        return ok
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

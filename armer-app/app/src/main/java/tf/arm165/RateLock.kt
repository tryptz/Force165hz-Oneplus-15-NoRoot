package tf.arm165

import android.os.BadParcelableException
import android.os.Build
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
 *
 * That numbering is one build's, not the interface's: AIDL counts methods in
 * declaration order. So the vote asks the device what it numbers the call
 * with, through [VendorStub], and falls back to 12 — see [voteCode].
 */
object RateLock {
    private const val TAG = "Arm165"
    private const val SERVICE = "oplusscreenmode"
    private const val IFACE = "com.oplus.screenmode.IOplusScreenMode"

    // Transaction codes as the OnePlus 15 numbers them, and the method names
    // they belong to. The names are what a build's own stub can be asked about;
    // the numbers are only the fallback for when it cannot be — see [voteCode].
    private const val TX_REQUEST_GAME_REFRESH_RATE = 12 // (String, int) -> boolean
    private const val TX_GET_GAME_LIST = 14 //             (Bundle) inout -> boolean
    private const val TX_SET_APP_OVERRIDE = 25 //          (String, int mode, int rate) -> boolean

    private const val REQUEST_GAME_REFRESH_RATE = "requestGameRefreshRate"
    private const val GET_GAME_LIST = "getGameList"
    private const val SET_APP_OVERRIDE_REFRESH_RATE = "setAppOverrideRefreshRate"

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
     * lowest the panel goes while that vote is held. Measured on this build
     * (README): 60, 90 and 120 all reach 1 Hz; 165 stops at 55.
     *
     * So 165 is the only mode that cannot idle, and the only reason the park
     * exists. A still screen with 165 held ramps down to 55 and stops, which
     * is confirmed on device.
     *
     * 144 has never been measured, because it is the one rate that parks and
     * so always leaves its own mode before going quiet. The estimate is kept
     * deliberately: a floor guessed too LOW only makes a park slower to
     * trigger, while one guessed too high lets the gate mistake real motion
     * for the floor. Now that 60, 90 and 120 all reach 1 it is likely 144
     * does too, which would make the park pointless for it. Measure it.
     */
    fun idleFloorHz(rateId: Int): Int = when (rateId) {
        RATE_60, RATE_90, RATE_120 -> 1
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

    /**
     * Runs one transaction, always with the interface token written first.
     * [onError] sees whatever the call threw, for the one caller that has to
     * tell a refusal apart from a build that takes a different call.
     */
    private inline fun <T> transact(
        code: Int,
        write: (Parcel) -> Unit,
        read: (Parcel) -> T,
        fallback: T,
        onError: (Throwable) -> Unit = {},
    ): T {
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
            onError(t)
            fallback
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Why the last vote did not land. Advisory, and read by the UI: a build
     * that never had the service, one that has closed it, and one that numbers
     * it differently are three unrelated answers, and "couldn't arm" is none
     * of them.
     */
    enum class Fault {
        /** It landed. */
        NONE,

        /** No binder of that name: not a build with this vendor service. */
        UNREACHABLE,

        /** The server answered, and said no. */
        REFUSED,

        /** A permission an unprivileged uid cannot hold: an OTA closed it. */
        DENIED,

        /**
         * The server read a different argument list than we wrote, so this
         * build numbers or declares the call differently (issue #17: on
         * CPH2793 transaction 12 is `requestRefreshRateWithToken`, which reads
         * a boolean, an int and a binder — 8 bytes short of what the vote
         * writes, which is the count the exception named).
         */
        SHAPE,

        UNKNOWN,
    }

    @Volatile
    var lastFault: Fault = Fault.NONE
        private set

    /** Set once a raw transaction is known to be the wrong shape on this build. */
    @Volatile
    private var viaProxy = false

    /**
     * Set once the build's own proxy has been asked for the vote. A build
     * either declares that method or it does not, so asking twice can only
     * add a reflection lookup and a log line to every pass the watchdog makes.
     */
    @Volatile
    private var proxyTried = false

    @Volatile
    private var reported = false

    /**
     * The transactions THIS build numbers these methods with, resolved once
     * each from the stub the device itself carries and falling back to the
     * OnePlus 15's numbering.
     *
     * The shift is whole-table, not one method: a OnePlus Nord 6 on
     * CPH2793_16.0.5.1200 numbers the vote 11, `getGameList` 13 and
     * `setAppOverrideRefreshRate` 24 — each one less than here, because that
     * build's interface declares one method fewer ahead of them. So every code
     * this app sends is resolved by name, not just the vote.
     */
    private val voteCode: Int by lazy {
        VendorStub.transactionCode(IFACE, REQUEST_GAME_REFRESH_RATE, TX_REQUEST_GAME_REFRESH_RATE)
    }

    private val gameListCode: Int by lazy {
        VendorStub.transactionCode(IFACE, GET_GAME_LIST, TX_GET_GAME_LIST)
    }

    private val appOverrideCode: Int by lazy {
        VendorStub.transactionCode(IFACE, SET_APP_OVERRIDE_REFRESH_RATE, TX_SET_APP_OVERRIDE)
    }

    /**
     * Transient game-rate vote via `requestGameRefreshRate`.
     *
     * The call is a plain set, not a toggle: the watchdog re-issues every armed
     * app's own id every few seconds and the pin holds instead of flickering
     * off, so handing the vendor an id it already holds can never be the way to
     * withdraw it — that is [release]'s job. The vote applies while the app is
     * foregrounded, which is why the watchdog exists.
     *
     * Two things can be different about the call on a build this was not
     * written on, and they are answered in order: the code it is numbered with
     * ([voteCode]), and the argument list it takes ([proxyVote]).
     */
    fun arm(packageName: String, rateId: Int = DEFAULT_RATE): Boolean {
        if (viaProxy) return proxyVote(packageName, rateId) ?: false
        // No binder is the only way out of transact without an answer.
        var fault = Fault.UNREACHABLE
        val ok = transact(
            voteCode,
            write = { it.writeString(packageName); it.writeInt(rateId) },
            read = { reply ->
                val res = reply.readInt()
                Log.i(TAG, "$packageName -> id=$rateId res=$res")
                fault = if (res == 1) Fault.NONE else Fault.REFUSED
                res == 1
            },
            fallback = false,
            onError = { fault = faultOf(it) },
        )
        if (!ok && fault == Fault.SHAPE && !proxyTried) {
            proxyTried = true
            reportBuildOnce()
            // The code came from this build's own stub and the parcel still
            // came back the wrong shape, so the argument list differs too.
            // Hand the vote to the proxy the build generates, and keep using it
            // for the rest of the process once it lands.
            proxyVote(packageName, rateId)?.let { answered ->
                viaProxy = true
                return answered
            }
        }
        lastFault = fault
        return ok
    }

    /**
     * The same vote, marshalled by the build's own proxy rather than by us, so
     * the argument list is the one that build declares.
     *
     * Null means there was nothing of that name to call, which is not the same
     * as a refusal: a vote that never went out must not read as a vote the
     * server turned down.
     */
    private fun proxyVote(packageName: String, rateId: Int): Boolean? {
        val binder = service() ?: run {
            lastFault = Fault.UNREACHABLE
            return null
        }
        val answer = VendorStub.callPackageRate(IFACE, binder, REQUEST_GAME_REFRESH_RATE, packageName, rateId)
        Log.i(TAG, "$packageName -> id=$rateId via this build's own proxy: ${answer ?: "no such call"}")
        lastFault = when (answer) {
            null -> Fault.SHAPE
            true -> Fault.NONE
            else -> Fault.REFUSED
        }
        return answer
    }

    /** Which [Fault] a thrown transaction was. */
    private fun faultOf(t: Throwable): Fault = when {
        t is SecurityException -> Fault.DENIED
        // The server read fewer arguments than we wrote: wrong call, not a
        // refused one. `enforceNoDataAvail` names the bytes it was left with.
        t is BadParcelableException -> Fault.SHAPE
        t.message?.contains("not fully consumed") == true -> Fault.SHAPE
        else -> Fault.UNKNOWN
    }

    /**
     * What a report from a build this was not written on has to carry: the
     * device, the build, and the transaction table that build's own stub
     * declares. Logged once, the first time a vote comes back the wrong shape.
     */
    private fun reportBuildOnce() {
        if (reported) return
        reported = true
        val table = VendorStub.transactionTable(IFACE).ifEmpty { "unreadable" }
        Log.w(
            TAG,
            "vote rejected as the wrong shape on ${Build.MODEL} / ${Build.DISPLAY}; " +
                "sent at transaction $voteCode; this build declares $table",
        )
    }

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
        appOverrideCode,
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
        gameListCode,
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

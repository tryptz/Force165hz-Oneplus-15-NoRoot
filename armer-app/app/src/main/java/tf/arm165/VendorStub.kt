package tf.arm165

import android.os.IBinder
import android.util.Log

/**
 * The device's own copy of a vendor AIDL interface, read by reflection.
 *
 * Every transaction code in this app was recovered from ONE build's
 * `oplus-framework.jar` (CPH2749_16.0.9.400). AIDL numbers a method by its
 * position in the interface, so a build that adds, removes or reorders one
 * method shifts every code after it, and the same `0x0c` that votes a game
 * rate here lands on some other method there.
 *
 * Read out of CPH2793_16.0.5.1200's own `oplus-framework.jar` (#17, a OnePlus
 * Nord 6), that build declares the vote at 11 and puts
 * `requestRefreshRateWithToken(boolean, int, IBinder)` at 12. Handed the
 * vote's parcel, that method reads an int, an int and a binder, and leaves
 * the tail of the package name behind — `Parcel data not fully consumed,
 * unread size: 8`, which is what every arm on that phone answered.
 *
 * The stub that threw is on the phone, on the boot classpath, and AIDL
 * generates it with its own numbering in `TRANSACTION_<method>` constants and
 * with a proxy that marshals each call. So ask the build rather than assume
 * it: [transactionCode] for the number, [callPackageRate] for builds whose
 * argument list differs too, [transactionTable] for the bug report when
 * neither works.
 *
 * None of it is API. Reflection at a hidden class is allowed to fail on any
 * build, and every entry point here answers with "nothing to call" rather than
 * throwing, so the caller keeps the recovered codes — which is what the
 * OnePlus 15 this was written on has always used.
 */
object VendorStub {
    private const val TAG = "Arm165"

    /** AIDL's own name for the constant holding a method's transaction code. */
    private const val PREFIX = "TRANSACTION_"

    private fun stub(iface: String): Class<*>? = try {
        Class.forName("$iface\$Stub")
    } catch (t: Throwable) {
        null
    }

    /**
     * The code THIS build numbers [method] with, or null when the stub cannot
     * be read here. AIDL emits
     * `static final int TRANSACTION_<method> = FIRST_CALL_TRANSACTION + n`,
     * which survives into the dex as a field, so one read answers what a probe
     * of the live service could only guess at.
     *
     * Null rather than a fallback on purpose: "this build says 12" and "this
     * build would not say" are different answers, and only the caller knows
     * what the second one is worth.
     */
    fun transactionCode(iface: String, method: String): Int? = try {
        stub(iface)?.getDeclaredField(PREFIX + method)
            ?.apply { isAccessible = true }
            ?.getInt(null)
    } catch (t: Throwable) {
        null
    }

    /**
     * Runs `method(String, int)` through the proxy this build generates, so
     * the transaction code AND the argument list both come from the device
     * instead of from us.
     *
     * Only the two-argument shape is called. A build that declares the method
     * with more arguments gets its signature logged and nothing sent: what a
     * third argument means is exactly the guess this object exists to stop,
     * and a wrong guess is another parcel the server has to reject.
     *
     * Returns null when there is nothing here to call or the call itself
     * failed — never a false "the server said no".
     */
    fun callPackageRate(
        iface: String,
        binder: IBinder,
        method: String,
        packageName: String,
        rateId: Int,
    ): Boolean? {
        val proxy = proxy(iface, binder) ?: return null
        val candidates = proxy.javaClass.methods.filter { it.name == method }
        val call = candidates.firstOrNull { m ->
            val p = m.parameterTypes
            p.size == 2 &&
                p[0] == String::class.java &&
                (p[1] == Int::class.javaPrimitiveType || p[1] == Integer::class.java)
        }
        if (call == null) {
            val shapes = candidates.joinToString(", ") { m ->
                m.parameterTypes.joinToString(", ", "$method(", ")") { it.simpleName }
            }
            val detail = if (shapes.isEmpty()) "" else ": it has $shapes"
            Log.w(TAG, "$iface on this build has no $method(String, int)$detail")
            return null
        }
        return try {
            when (val res = call.invoke(proxy, packageName, rateId)) {
                is Boolean -> res
                is Int -> res == 1
                // A void vote that threw nothing is a vote the server took.
                null -> true
                else -> false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$method through this build's own proxy failed", t)
            null
        }
    }

    /**
     * Every transaction this build's stub declares, as `name=code` ordered by
     * code — the one thing a report from a device this was not written on
     * cannot be read without. Empty string when the stub is not readable.
     */
    fun transactionTable(iface: String): String {
        val cls = stub(iface) ?: return ""
        return try {
            cls.declaredFields
                .filter { it.name.startsWith(PREFIX) && it.type == Int::class.javaPrimitiveType }
                .mapNotNull { f ->
                    try {
                        f.isAccessible = true
                        f.name.removePrefix(PREFIX) to f.getInt(null)
                    } catch (t: Throwable) {
                        null
                    }
                }
                .sortedBy { it.second }
                .joinToString(" ") { "${it.first}=${it.second}" }
        } catch (t: Throwable) {
            ""
        }
    }

    private fun proxy(iface: String, binder: IBinder): Any? = try {
        stub(iface)?.getMethod("asInterface", IBinder::class.java)?.invoke(null, binder)
    } catch (t: Throwable) {
        Log.w(TAG, "$iface\$Stub.asInterface is not reachable on this build", t)
        null
    }
}

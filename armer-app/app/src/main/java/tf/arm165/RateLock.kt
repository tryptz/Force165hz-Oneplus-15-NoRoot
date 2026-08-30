package tf.arm165

import android.os.IBinder
import android.os.Parcel
import android.util.Log

object RateLock {
    private const val TAG = "Arm165"
    private const val SERVICE = "oplusscreenmode"
    private const val IFACE = "com.oplus.screenmode.IOplusScreenMode"
    private const val CODE_REQUEST_GAME_REFRESH_RATE = 0x0c

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

    /**
     * Applies [rateId] to [packageName].
     *
     * Re-issuing the id an app is already pinned at removes the override, so a
     * disarm has to replay that app's own rate — see [ArmedStore].
     */
    fun arm(packageName: String, rateId: Int = DEFAULT_RATE): Boolean = try {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, SERVICE) as? IBinder ?: return false
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(IFACE)
            data.writeString(packageName)
            data.writeInt(rateId)
            val ok = binder.transact(CODE_REQUEST_GAME_REFRESH_RATE, data, reply, 0)
            reply.readException()
            val res = reply.readInt()
            Log.i(TAG, "$packageName -> id=$rateId ok=$ok res=$res")
            ok && res == 1
        } finally {
            data.recycle(); reply.recycle()
        }
    } catch (t: Throwable) {
        Log.w(TAG, "arm failed for $packageName", t)
        false
    }
}

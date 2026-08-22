package tf.arm165

import android.os.IBinder
import android.os.Parcel
import android.util.Log

object RateLock {
    private const val TAG = "Arm165"
    private const val SERVICE = "oplusscreenmode"
    private const val IFACE = "com.oplus.screenmode.IOplusScreenMode"
    private const val CODE_REQUEST_GAME_REFRESH_RATE = 0x0c
    const val RATE_165 = 7

    /** true = override armed (or toggled off — same call removes it). */
    fun arm(packageName: String, rateId: Int = RATE_165): Boolean = try {
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

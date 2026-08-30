package tf.arm165

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Thin wrapper over `Settings.Global` for the one OPlus master switch this app
 * touches (`app_extreme_high_refresh_switch`, already used by
 * `boot/165hz.sh`). Writing it needs `WRITE_SECURE_SETTINGS`, granted once over
 * adb; every call swallows the `SecurityException` so a missing grant degrades
 * to a disabled control instead of a crash.
 */
object SecureSettings {
    private const val TAG = "Arm165"
    const val KEY_EXTREME_REFRESH = "app_extreme_high_refresh_switch"

    /**
     * True when the app holds WRITE_SECURE_SETTINGS. Checked directly rather
     * than by attempting a write, which would create the setting at 0 on a
     * device where it had never been set.
     */
    fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun getGlobalInt(context: Context, key: String, default: Int): Int = try {
        Settings.Global.getInt(context.contentResolver, key, default)
    } catch (t: Throwable) {
        default
    }

    fun putGlobalInt(context: Context, key: String, value: Int): Boolean = try {
        Settings.Global.putInt(context.contentResolver, key, value)
    } catch (t: SecurityException) {
        false
    } catch (t: Throwable) {
        Log.w(TAG, "putGlobalInt($key) failed", t)
        false
    }
}

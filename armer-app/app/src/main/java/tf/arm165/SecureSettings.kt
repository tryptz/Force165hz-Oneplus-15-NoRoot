package tf.arm165

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Thin wrapper over the settings tables this app touches.
 *
 * `Settings.Global` carries the one OPlus master switch (`app_extreme_high_
 * refresh_switch`, already used by `boot/165hz.sh`), which needs
 * `WRITE_SECURE_SETTINGS` — granted once over adb.
 *
 * The AOSP display ceiling (`Settings.System` `peak_refresh_rate`) was wired
 * here too and is gone again: raising it does not lift the shade or the lock
 * screen on this build, so the control only offered a global battery cost for
 * nothing. See the README.
 *
 * Every call swallows the `SecurityException` so a missing grant degrades to a
 * disabled control instead of a crash.
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

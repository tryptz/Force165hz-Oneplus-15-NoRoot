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
 * `Settings.System` carries the AOSP display floor and ceiling
 * (`min_refresh_rate` / `peak_refresh_rate`). Those are what a shell
 * `settings put system …` writes, and an app may write them once the user has
 * granted "Modify system settings" ([canWriteSystem]). They are not a per-app
 * vote: the ceiling applies to the whole display, which is the only rootless
 * thing left that could raise a surface no app owns — the shade, the lock
 * screen, the launcher.
 *
 * Every call swallows the `SecurityException` so a missing grant degrades to a
 * disabled control instead of a crash.
 */
object SecureSettings {
    private const val TAG = "Arm165"
    const val KEY_EXTREME_REFRESH = "app_extreme_high_refresh_switch"

    const val KEY_PEAK_REFRESH = "peak_refresh_rate"
    const val KEY_MIN_REFRESH = "min_refresh_rate"

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

    /**
     * Whether the user has granted "Modify system settings". Unlike the appops
     * behind usage access, this one has a real request screen
     * ([android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS]).
     */
    fun canWriteSystem(context: Context): Boolean = try {
        Settings.System.canWrite(context)
    } catch (t: Throwable) {
        false
    }

    /** 0f when the key is unset, which means the system default is in force. */
    fun getSystemFloat(context: Context, key: String): Float = try {
        Settings.System.getFloat(context.contentResolver, key, 0f)
    } catch (t: Throwable) {
        0f
    }

    fun putSystemFloat(context: Context, key: String, value: Float): Boolean = try {
        Settings.System.putFloat(context.contentResolver, key, value)
    } catch (t: SecurityException) {
        Log.w(TAG, "putSystemFloat($key) refused — no WRITE_SETTINGS grant")
        false
    } catch (t: Throwable) {
        Log.w(TAG, "putSystemFloat($key) failed", t)
        false
    }

    /**
     * Removes the key so the system default applies again. Restoring by
     * writing a number would leave our guess behind where there had been no
     * value at all.
     */
    fun clearSystem(context: Context, key: String): Boolean = try {
        Settings.System.putString(context.contentResolver, key, null)
    } catch (t: SecurityException) {
        false
    } catch (t: Throwable) {
        Log.w(TAG, "clearSystem($key) failed", t)
        false
    }
}

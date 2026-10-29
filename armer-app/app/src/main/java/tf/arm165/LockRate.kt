package tf.arm165

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import java.util.concurrent.Executors

/**
 * The lock screen at 120 Hz while SystemUI runs at the armed rate (issue #26).
 *
 * ColorOS's own "Screen refresh rate" option decides two things at once. On
 * High, SystemUI — app switching, the shade — caps at 120 whatever is armed.
 * On Standard, SystemUI follows the armed rate, but the lock screen falls to
 * 60. The reason is in `OplusRefreshRatePolicyImpl.getNormalModePickPreferredId`
 * (OnePlus 15, Android 17): while the keyguard shows, the panel takes the
 * keyguard's rate — 120 — unless the mode is Standard, where it takes
 * SystemUI's configured rate instead, and that is 60.
 *
 * So the phone stays on Standard, and while it is locked this holds a 120 Hz
 * display vote ([RateLock.holdDisplayRate]) that outranks the keyguard's pick.
 * No permission, no adb, and the phone's own setting is never touched. The
 * vote outranks every armed app as well, so it is taken back on the unlock
 * and checked every second while it is held; and the vendor drops it by
 * itself if this process dies, because [token] is linked to its death.
 */
object LockRate {
    private const val TAG = "Arm165"

    private const val KEY_ON = "lock120_on"

    /** What the lock screen is held at: 120, as it is on High. */
    private const val LOCK_RATE = RateLock.RATE_120

    /** Settings.Secure, written by the phone's own Display settings. */
    private const val KEY_MODE = "oplus_customize_screen_refresh_rate"
    const val MODE_STANDARD = 2

    private const val CHECK_MS = 1000L

    /** The vote's identity: the vendor unlinks it when this process dies. */
    private val token = Binder()

    /** Binder calls stay off the main thread and in order. */
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var held = false

    /** Always the application context, which lives as long as this object. */
    @SuppressLint("StaticFieldLeak")
    private var app: Context? = null

    fun enabled(context: Context): Boolean =
        ArmedStore.open(context).getBoolean(KEY_ON, false)

    fun setEnabled(context: Context, on: Boolean) {
        ArmedStore.open(context).edit().putBoolean(KEY_ON, on).apply()
        if (on) sync(context) else release()
    }

    /**
     * The phone's refresh-rate mode, or null where the table will not say.
     * Read only, for the hint on the Settings row: this works best on
     * Standard, and on High there is nothing for it to fix.
     */
    fun phoneMode(context: Context): Int? = try {
        Settings.Secure.getInt(context.contentResolver, KEY_MODE)
    } catch (t: Throwable) {
        null
    }

    /**
     * Screen off: hold it now, so the lock screen comes up on it. With a lock
     * delay there is no keyguard yet; the wake's [sync] lets it go if none
     * appears.
     */
    fun onScreenOff(context: Context) {
        if (enabled(context)) hold(context)
    }

    fun onUnlocked() = release()

    /** A wake, or the service starting: whatever the keyguard is doing now. */
    fun sync(context: Context) {
        val km = context.getSystemService(KeyguardManager::class.java)
        if (enabled(context) && km?.isKeyguardLocked == true) hold(context) else release()
    }

    private fun hold(context: Context) {
        app = context.applicationContext
        handler.removeCallbacks(check)
        handler.postDelayed(check, CHECK_MS)
        if (held) return
        held = true
        worker.execute {
            val ok = synchronized(RateLock) { RateLock.holdDisplayRate(LOCK_RATE, token) }
            Log.i(TAG, "lock120: locked, holding ${RateLock.hz(LOCK_RATE)} Hz" +
                if (ok) "" else " (the vendor did not take the vote)")
        }
    }

    fun release() {
        handler.removeCallbacks(check)
        if (!held) return
        held = false
        worker.execute {
            synchronized(RateLock) { RateLock.releaseDisplayRate(token) }
            Log.i(TAG, "lock120: unlocked, vote released")
        }
    }

    /**
     * The backstop for an unlock that sent no USER_PRESENT. A vote left behind
     * would cap every armed app at 120, so a screen that is on with no
     * keyguard lets it go here.
     */
    private val check = object : Runnable {
        override fun run() {
            val c = app ?: return
            val km = c.getSystemService(KeyguardManager::class.java)
            val pm = c.getSystemService(PowerManager::class.java)
            if (pm?.isInteractive == true && km?.isKeyguardLocked != true) {
                release()
                return
            }
            if (held) handler.postDelayed(this, CHECK_MS)
        }
    }
}

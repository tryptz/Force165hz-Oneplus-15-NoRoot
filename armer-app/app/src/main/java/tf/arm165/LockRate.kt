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
 * The lock screen (issue #26) and the camera (issue #28) at 120 Hz while
 * SystemUI runs at the armed rate.
 *
 * ColorOS's own "Screen refresh rate" option decides two things at once. On
 * High, SystemUI — app switching, the shade — caps at 120 whatever is armed.
 * On Standard, SystemUI follows the armed rate, but the lock screen falls to
 * 60. The reason is in `OplusRefreshRatePolicyImpl.getNormalModePickPreferredId`
 * (OnePlus 15, Android 17): while the keyguard shows, the panel takes the
 * keyguard's rate — 120 — unless the mode is Standard, where it takes
 * SystemUI's configured rate instead, and that is 60.
 *
 * The camera is the same story told by its own app: its viewfinder runs at 60
 * on either mode, and its buttons and menus take the mode's rate — 120 on
 * High, 60 on Standard.
 *
 * So the phone stays on Standard, and while it is locked, or while the camera
 * is on screen, this holds a 120 Hz display vote ([RateLock.holdDisplayRate])
 * that outranks the keyguard's pick and the camera's. No permission, no adb,
 * and the phone's own setting is never touched. The camera needs usage access
 * to be seen ([Foreground]); without it that half simply never holds. The
 * vote outranks every armed app as well, so it is taken back the moment
 * neither reason stands, checked every second while it is held, and the
 * vendor drops it by itself if this process dies, because [token] is linked
 * to its death.
 */
object LockRate {
    private const val TAG = "Arm165"

    /** One switch for both: the lock screen and the camera. */
    private const val KEY_ON = "lock120_on"

    /** What the screen is held at: 120, as it is on High. */
    private const val LOCK_RATE = RateLock.RATE_120

    /** Settings.Secure, written by the phone's own Display settings. */
    private const val KEY_MODE = "oplus_customize_screen_refresh_rate"
    const val MODE_STANDARD = 2

    /** The stock camera, by the name each ColorOS / OxygenOS build gives it. */
    private val CAMERA_PACKAGES = setOf("com.oplus.camera", "com.oneplus.camera")

    private const val CHECK_MS = 1000L

    /** How soon the camera's buttons get 120 after it opens. */
    private const val CAMERA_POLL_MS = 500L

    /** The vote's identity: the vendor unlinks it when this process dies. */
    private val token = Binder()

    /** Binder calls stay off the main thread and in order. */
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var held = false

    /** The two reasons to hold. Main thread only. */
    private var locked = false
    private var camera = false

    /** Always the application context, which lives as long as this object. */
    @SuppressLint("StaticFieldLeak")
    private var app: Context? = null

    fun enabled(context: Context): Boolean =
        ArmedStore.open(context).getBoolean(KEY_ON, false)

    fun setEnabled(context: Context, on: Boolean) {
        ArmedStore.open(context).edit().putBoolean(KEY_ON, on).apply()
        sync(context)
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
     * appears. Nothing is on screen to be a camera.
     */
    fun onScreenOff(context: Context) {
        app = context.applicationContext
        locked = enabled(context)
        camera = false
        apply()
    }

    /** The camera check takes over from here, if it is on. */
    fun onUnlocked() {
        locked = false
        apply()
    }

    /** A wake, a switch flipped, or the service starting: whatever is true now. */
    fun sync(context: Context) {
        app = context.applicationContext
        val km = context.getSystemService(KeyguardManager::class.java)
        locked = enabled(context) && km?.isKeyguardLocked == true
        if (!enabled(context) || locked) camera = false
        apply()
    }

    /** Everything off: the service is going, and nothing will hear the unlock. */
    fun release() {
        locked = false
        camera = false
        handler.removeCallbacks(check)
        apply()
    }

    /** Lands the vote the two reasons call for, and keeps [check] running. */
    private fun apply() {
        handler.removeCallbacks(check)
        val c = app
        if (locked) handler.postDelayed(check, CHECK_MS)
        else if (c != null && enabled(c) && interactive(c)) handler.postDelayed(check, CAMERA_POLL_MS)

        val want = locked || camera
        if (want == held) return
        held = want
        val why = if (locked) "locked" else "camera on screen"
        worker.execute {
            if (want) {
                val ok = synchronized(RateLock) { RateLock.holdDisplayRate(LOCK_RATE, token) }
                Log.i(TAG, "lock120: $why, holding ${RateLock.hz(LOCK_RATE)} Hz" +
                    if (ok) "" else " (the vendor did not take the vote)")
            } else {
                synchronized(RateLock) { RateLock.releaseDisplayRate(token) }
                Log.i(TAG, "lock120: vote released")
            }
        }
    }

    private fun interactive(c: Context): Boolean =
        c.getSystemService(PowerManager::class.java)?.isInteractive == true

    /**
     * Two jobs. The backstop for an unlock that sent no USER_PRESENT: a vote
     * left behind would cap every armed app at 120, so a screen that is on
     * with no keyguard lets the lock reason go. And, unlocked, whether the
     * camera is what is on screen.
     */
    private val check = object : Runnable {
        override fun run() {
            val c = app ?: return
            val km = c.getSystemService(KeyguardManager::class.java)
            val unlocked = interactive(c) && km?.isKeyguardLocked != true
            if (locked && unlocked) locked = false
            if (!unlocked || !enabled(c)) {
                camera = false
                apply()
                return
            }
            // A usage query is a binder call: off the main thread, and the
            // answer judged back on it, where the screen may have gone off
            // in between.
            worker.execute {
                val inFront = Foreground.current(c) in CAMERA_PACKAGES
                handler.post {
                    if (!locked && interactive(c) && enabled(c)) {
                        if (inFront != camera) Log.i(TAG, "camera120: camera ${if (inFront) "on" else "off"} screen")
                        camera = inFront
                    }
                    apply()
                }
            }
        }
    }
}

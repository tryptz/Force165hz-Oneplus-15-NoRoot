package tf.arm165

import android.content.Context
import android.os.Binder
import android.util.Log

/**
 * Split view and floating windows at the armed rate (issue #32).
 *
 * With every app armed at 165, the panel falls to 120 as soon as two apps
 * share the screen, on Standard and on High alike. Each app's own vote names
 * its windows, and with two of them on screen the vendor stops following
 * either and uses its own multi-window rate.
 *
 * The answer tried here is the one the lock screen got ([LockRate]): the
 * display-wide vote ([RateLock.holdDisplayRate]), priority 70, which outranks
 * the vendor's window picks. It is held while two or more apps are resumed
 * at once ([Foreground.resumedPackages]) and one of them is armed, at the
 * highest rate armed among them, and taken back the moment that stops being
 * true. Usage access is how the apps are seen; without it this never holds.
 *
 * The vote names no package, so it also keeps the panel from idling while it
 * is held. That is the price of the fix, and why it lasts only as long as the
 * split. The vendor drops it by itself if this process dies, because [token]
 * is linked to its death.
 *
 * Called from the watchdog's worker, inside synchronized(RateLock).
 */
object MultiWindowRate {
    private const val TAG = "Arm165"

    /** Not [LockRate]'s token: each vote is withdrawn by its own. */
    private val token = Binder()

    /** The rateId held, or [RateLock.RATE_NONE]. */
    @Volatile
    private var heldRate = RateLock.RATE_NONE

    /** One watchdog pass: hold, change or release, as the screen calls for. */
    fun update(context: Context, armed: Map<String, Int>) {
        val onScreen = Foreground.resumedPackages(context)
        val want = if (onScreen.size < 2) RateLock.RATE_NONE
        else onScreen.mapNotNull { armed[it] }
            .maxByOrNull { RateLock.hz(it) }
            ?.let(RateLock::onPanel) ?: RateLock.RATE_NONE
        if (want == heldRate) return
        if (want == RateLock.RATE_NONE) {
            release()
            return
        }
        val ok = RateLock.holdDisplayRate(want, token)
        heldRate = want
        Log.i(TAG, "multiwindow165: ${onScreen.size} apps on screen (${onScreen.joinToString()}), " +
            "holding ${RateLock.hz(want)} Hz" + if (ok) "" else " (the vendor did not take the vote)")
    }

    /** Screen off, or the watchdog going: nothing is split any more. */
    fun release() {
        if (heldRate == RateLock.RATE_NONE) return
        heldRate = RateLock.RATE_NONE
        RateLock.releaseDisplayRate(token)
        Log.i(TAG, "multiwindow165: vote released")
    }
}

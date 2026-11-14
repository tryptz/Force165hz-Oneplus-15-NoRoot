package tf.arm165

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.Executors

/**
 * Pop-up (heads-up) notifications at the armed rate.
 *
 * The pop-up is SystemUI's window. On the phone's Standard mode it runs at
 * 60 whatever is armed, and no per-app vote names it. What does reach it is
 * the display-wide vote ([RateLock.holdDisplayRate]) that the lock screen
 * ([LockRate]) and split view ([MultiWindowRate]) already hold: split view
 * holds it at 165, so the armed rate is asked for first, and 120, the lock
 * screen's rate, when the vendor will not take that.
 *
 * The vote names no package and keeps the panel from idling, so it is held
 * only for as long as a pop-up stays up, and let go by a timer. The vendor
 * drops it by itself if this process dies, because [token] is linked to its
 * death.
 *
 * Knowing that a pop-up has appeared takes notification access, which the
 * user grants in the phone's settings. Only the importance of a notification
 * is read, never what it says.
 */
object PopupRate {
    private const val TAG = "Arm165"
    private const val KEY_ON = "popup_rate_on"

    /** A heads-up stays for about five seconds; one more covers its exit. */
    private const val HOLD_MS = 6000L

    /** Not the lock screen's or split view's: each vote is withdrawn by its own. */
    private val token = Binder()
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    /** Main thread only. */
    private var held = false

    /** When the hold is due to end, in uptime: a short pulse must not cut a long one. */
    private var releaseAtMs = 0L

    fun enabled(context: Context): Boolean =
        ArmedStore.open(context).getBoolean(KEY_ON, false)

    fun setEnabled(context: Context, on: Boolean) {
        ArmedStore.open(context).edit().putBoolean(KEY_ON, on).apply()
        if (!on) handler.post(release)
    }

    /** True when the user has granted notification access to [PopupListener]. */
    fun hasAccess(context: Context): Boolean = try {
        context.getSystemService(NotificationManager::class.java)
            ?.isNotificationListenerAccessGranted(ComponentName(context, PopupListener::class.java)) == true
    } catch (t: Throwable) {
        false
    }

    /**
     * Something of SystemUI's has just appeared, [why]: hold now, and for
     * [holdMs] from now. Main thread.
     *
     * Also what the volume slider gets. A volume key is not a touch, so on a
     * panel resting at its idle rate nothing wakes it, and the slider, which
     * is SystemUI's window like the pop-up, animates at that rate: choppy at
     * the 165 mode's 33 Hz floor, fine when the panel is already at 165.
     */
    fun pulse(context: Context, why: String = "notification pop-up", holdMs: Long = HOLD_MS) {
        releaseAtMs = maxOf(releaseAtMs, SystemClock.uptimeMillis() + holdMs)
        handler.removeCallbacks(release)
        handler.postAtTime(release, releaseAtMs)
        if (held) return
        held = true
        // The highest rate armed, and never under the lock screen's 120.
        val armed = ArmedStore.read(ArmedStore.open(context))
        val want = armed.values.maxByOrNull { RateLock.hz(it) }
            ?.takeIf { RateLock.hz(it) > RateLock.hz(RateLock.RATE_120) }
            ?.let(RateLock::onPanel) ?: RateLock.RATE_120
        worker.execute {
            var rate = want
            var ok = synchronized(RateLock) { RateLock.holdDisplayRate(rate, token) }
            if (!ok && rate != RateLock.RATE_120) {
                rate = RateLock.RATE_120
                ok = synchronized(RateLock) { RateLock.holdDisplayRate(rate, token) }
            }
            Log.i(TAG, "popup: $why, holding ${RateLock.hz(rate)} Hz" +
                if (ok) "" else " (the vendor did not take the vote at ${RateLock.displayVoteNote()})")
        }
    }

    private val release = Runnable {
        releaseAtMs = 0L
        if (!held) return@Runnable
        held = false
        worker.execute {
            synchronized(RateLock) { RateLock.releaseDisplayRate(token) }
            Log.i(TAG, "popup: vote released")
        }
    }
}

/**
 * Hears notifications arrive, for [PopupRate]. Bound by the system once the
 * user grants notification access; it does nothing until the switch is on.
 */
class PopupListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (sbn == null || rankingMap == null || !PopupRate.enabled(this)) return
        if (sbn.isOngoing || sbn.packageName == packageName) return
        // Only one that pops up: high importance, and not silenced by Do Not Disturb.
        val ranking = Ranking()
        if (!rankingMap.getRanking(sbn.key, ranking)) return
        if (ranking.importance < NotificationManager.IMPORTANCE_HIGH) return
        if (!ranking.matchesInterruptionFilter()) return
        // Screen off or locked: nothing pops up, and the lock screen has its own hold.
        if (getSystemService(PowerManager::class.java)?.isInteractive != true) return
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return
        PopupRate.pulse(applicationContext)
    }
}

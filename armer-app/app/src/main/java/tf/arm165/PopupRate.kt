package tf.arm165

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
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
 * SystemUI's short-lived windows at the armed rate: pop-up (heads-up)
 * notifications and the volume slider.
 *
 * Both are SystemUI's windows, and no per-app vote names them. What does
 * reach them is the display-wide vote ([RateLock.holdDisplayRate]) that the
 * lock screen ([LockRate]) and split view ([MultiWindowRate]) already hold:
 * split view holds it at 165, so the armed rate is asked for first, and 120,
 * the lock screen's rate, when the vendor will not take that.
 *
 * The vote names no package and keeps the panel from idling, so it is held
 * only for as long as the window stays up, and let go by a timer. The vendor
 * drops it by itself if this process dies, because [token] is linked to its
 * death.
 *
 * The two have different triggers. A pop-up is seen through notification
 * access, which the user grants, behind the Settings switch ([enabled]); only
 * a notification's importance is read, never what it says. A volume step is
 * a broadcast anyone may hear, and needs no switch ([volumeStep]).
 */
object PopupRate {
    private const val TAG = "Arm165"

    /** The Settings switch. It gates pop-up notifications only. */
    private const val KEY_ON = "popup_rate_on"

    /** A heads-up stays for about five seconds; one more covers its exit. */
    private const val POPUP_HOLD_MS = 6000L

    /** The volume slider stays up about three seconds after the last step. */
    private const val VOLUME_HOLD_MS = 3500L

    /** Not the lock screen's or split view's: each vote is withdrawn by its own. */
    private val token = Binder()
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    /** Main thread only. */
    private var held = false

    /** When the hold is due to end, in uptime: a short pulse must not cut a long one. */
    private var releaseAtMs = 0L

    /**
     * The switch, read once: [PopupListener] asks for every notification the
     * phone posts, and the answer only changes through [setEnabled].
     */
    @Volatile
    private var on: Boolean? = null

    fun enabled(context: Context): Boolean =
        on ?: ArmedStore.open(context).getBoolean(KEY_ON, false).also { on = it }

    fun setEnabled(context: Context, enable: Boolean) {
        ArmedStore.open(context).edit().putBoolean(KEY_ON, enable).apply()
        on = enable
    }

    /** True when the user has granted notification access to [PopupListener]. */
    fun hasAccess(context: Context): Boolean = try {
        context.getSystemService(NotificationManager::class.java)
            ?.isNotificationListenerAccessGranted(ComponentName(context, PopupListener::class.java)) == true
    } catch (t: Throwable) {
        false
    }

    /** Locked: nothing of SystemUI's pops over an app, and the lock screen has its own hold. */
    private fun locked(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /** The highest rate armed, as this panel takes it, or null with nothing armed. */
    private fun highestArmed(prefs: SharedPreferences): Int? =
        RateLock.highestOnPanel(ArmedStore.read(prefs).values)

    /**
     * A pop-up notification has just appeared. Held at the highest armed
     * rate, and never under the lock screen's 120. Main thread.
     */
    fun notificationPopup(context: Context) {
        if (locked(context)) return
        val armed = highestArmed(ArmedStore.open(context))
        val want = armed?.takeIf { RateLock.hz(it) > RateLock.hz(RateLock.RATE_120) } ?: RateLock.RATE_120
        pulse("notification pop-up", POPUP_HOLD_MS, want)
    }

    /**
     * A volume step. A volume key is not a touch, so on a panel resting at
     * its idle rate nothing wakes it, and the slider animates at that rate:
     * choppy at the 165 mode's 33 Hz floor, fine when the panel is already
     * at 165. Main thread.
     *
     * Only with something armed above 120: with every app at 120 or under
     * there is no floor to climb out of, and holding a rate nobody armed
     * would put faster content on a panel the user chose to keep it off.
     */
    fun volumeStep(context: Context, prefs: SharedPreferences) {
        if (locked(context)) return
        val want = highestArmed(prefs)?.takeIf { RateLock.hz(it) > RateLock.hz(RateLock.RATE_120) } ?: return
        pulse("volume slider", VOLUME_HOLD_MS, want)
    }

    /** Holds [want] now, or 120 if the vendor will not take it, for [holdMs] from now. */
    private fun pulse(why: String, holdMs: Long, want: Int) {
        releaseAtMs = maxOf(releaseAtMs, SystemClock.uptimeMillis() + holdMs)
        handler.removeCallbacks(release)
        handler.postAtTime(release, releaseAtMs)
        if (held) return
        held = true
        worker.execute {
            val taken = listOf(want, RateLock.RATE_120).distinct()
                .firstOrNull { synchronized(RateLock) { RateLock.holdDisplayRate(it, token) } }
            Log.i(TAG, "popup: $why, holding ${RateLock.hz(taken ?: RateLock.RATE_120)} Hz" +
                if (taken != null) "" else " (the vendor did not take the vote at ${RateLock.displayVoteNote()})")
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
        // Cheapest first: this runs for every notification on the phone.
        if (sbn == null || rankingMap == null || sbn.isOngoing || sbn.packageName == packageName) return
        if (!PopupRate.enabled(this)) return
        // Only one that pops up: high importance, and not silenced by Do Not Disturb.
        val ranking = Ranking()
        if (!rankingMap.getRanking(sbn.key, ranking)) return
        if (ranking.importance < NotificationManager.IMPORTANCE_HIGH) return
        if (!ranking.matchesInterruptionFilter()) return
        if (getSystemService(PowerManager::class.java)?.isInteractive != true) return
        PopupRate.notificationPopup(applicationContext)
    }
}

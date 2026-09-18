package tf.arm165

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.app.usage.UsageStats
import android.content.Context
import android.os.Process
import android.util.Log

/**
 * The package on screen, from `UsageStatsManager`.
 *
 * This is the only signal a rootless app can use that names the foreground app
 * whatever it is. `oiface`'s `getCurrentGamePackage` answers for games its
 * daemon tracks and nothing else, and `ActivityManager` stopped reporting other
 * processes years ago — so without this, an armed set larger than a handful
 * left the watchdog with no way to tell which pin the panel was actually
 * following, and therefore no way to order its votes so that pin lands last.
 *
 * Needs `PACKAGE_USAGE_STATS`, which is an appop rather than a runtime
 * permission: it cannot be requested from a dialog, only granted by hand in
 * Settings -> Special app access -> Usage access (the Settings screen opens it).
 * Every call degrades to "don't know" without it, which is the same state the
 * watchdog was in before this existed.
 */
object Foreground {
    private const val TAG = "Arm165"

    /**
     * How long an answer is reused. The watchdog polls every 50 ms while a
     * vote is parked, and the foreground app does not change twenty times a
     * second; a query per poll would be a binder call and an event walk for
     * nothing.
     */
    private const val CACHE_MS = 750L

    /** How long the appop check is reused — it changes only in Settings. */
    private const val ACCESS_CACHE_MS = 5_000L

    /**
     * Window of the first query. Later queries only cover what is new.
     *
     * A day, not the two minutes this started as. Events only exist where
     * something was resumed, so a phone that has sat in one app for longer
     * than the window produces none at all, and the answer came back "don't
     * know" for the whole session. That was not a corner case: it disabled the
     * park outright, because a large armed set has no park candidate without a
     * focus to name.
     */
    private const val LOOKBACK_MS = 86_400_000L

    /**
     * Overlap between consecutive queries. Usage events are written by the
     * system with some lag, so a window that starts exactly where the last one
     * ended can miss the event that crossed the boundary.
     */
    private const val OVERLAP_MS = 2_000L

    @Volatile private var cachedPkg: String? = null
    @Volatile private var cachedAtMs = 0L
    @Volatile private var queriedToMs = 0L
    @Volatile private var access = false
    @Volatile private var accessAtMs = 0L

    /**
     * Whether the usage-access appop is granted. [refresh] forces a re-check,
     * which is what the settings screen wants after the user has been to
     * Settings and back.
     */
    fun hasAccess(context: Context, refresh: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!refresh && accessAtMs != 0L && now - accessAtMs < ACCESS_CACHE_MS) return access
        access = try {
            // Deprecated on SDK 36, kept deliberately: it reads the appop's
            // state without recording an access against it, which is what a
            // card that only wants to say "granted" or "not granted" should
            // do. Querying usage events instead cannot tell a missing grant
            // from a genuinely empty window.
            @Suppress("DEPRECATION")
            context.getSystemService(AppOpsManager::class.java)?.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        } catch (t: Throwable) {
            false
        }
        accessAtMs = now
        return access
    }

    /**
     * The package on screen, or null when nothing can say — no access granted,
     * no query possible, or nothing foregrounded since this process started.
     *
     * An empty window is NOT an empty answer: a screen left alone produces no
     * events at all, so the last package seen stands until a newer resume
     * replaces it. That is what makes this usable as the park's idle signal,
     * where "nothing has happened for a while" is the normal case.
     */
    fun current(context: Context): String? {
        val now = System.currentTimeMillis()
        if (cachedAtMs != 0L && now - cachedAtMs < CACHE_MS) return cachedPkg
        if (!hasAccess(context)) {
            cachedPkg = null
            cachedAtMs = now
            return null
        }
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val from = if (queriedToMs == 0L) now - LOOKBACK_MS else queriedToMs - OVERLAP_MS
        try {
            val events = usm.queryEvents(from, now)
            val event = UsageEvents.Event()
            var latest: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                // ACTIVITY_RESUMED is the event that means "this is what the
                // user is looking at now". PAUSED/STOPPED say what they left,
                // which is not the same thing when a dialog or a picture-in-
                // picture window comes and goes.
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    latest = event.packageName
                }
            }
            queriedToMs = now
            if (latest != null) cachedPkg = latest
        } catch (t: Throwable) {
            Log.w(TAG, "usage query failed", t)
        }
        // Still nothing on the very first pass: fall back to the aggregated
        // stats, whose most recently used package is a fair answer even where
        // no resume event survives. Only worth doing while we have no answer
        // at all, since it is coarser and more work than the event walk.
        if (cachedPkg == null) cachedPkg = lastUsed(usm, now)
        cachedAtMs = now
        return cachedPkg
    }

    /** The most recently used package over the last day, or null. */
    private fun lastUsed(usm: UsageStatsManager, now: Long): String? = try {
        usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - LOOKBACK_MS, now)
            ?.maxByOrNull(UsageStats::getLastTimeUsed)
            ?.packageName
            ?.takeIf { it.isNotEmpty() }
    } catch (t: Throwable) {
        null
    }

    /** Drops every cached answer, so the next call asks the system again. */
    fun forget() {
        cachedPkg = null
        cachedAtMs = 0L
        queriedToMs = 0L
        accessAtMs = 0L
    }
}

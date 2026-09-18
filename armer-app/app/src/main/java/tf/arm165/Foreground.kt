package tf.arm165

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.util.Log

/**
 * What is actually on screen.
 *
 * The watchdog used to ask the vendor daemon (`Oiface.currentGamePackage`),
 * which only ever names a package that daemon tracks as a game — null for
 * everything else, including most games. With no focus the whole pass loses
 * its aim: nothing can park, and the vote sweep spends itself on background
 * packages in alphabetical order, ending each pass on one of them. That is
 * the case `voteSweep` documents as dissolving the pin, and on a 641-package
 * armed set it is every pass.
 *
 * `UsageStatsManager` answers for any app, but only with usage access, which
 * is a special grant the user gives in Settings or over adb:
 *
 *     adb shell appops set tf.arm165 GET_USAGE_STATS allow
 *
 * Without it every call here returns null and the watchdog falls back to what
 * it did before, so a missing grant costs the aim, never a crash.
 */
object Foreground {
    private const val TAG = "Arm165"

    /** First query has no previous end to work from, so it looks back this far. */
    private const val COLD_WINDOW_MS = 60_000L

    /** Overlap between windows; events are timestamped by the system, not by us. */
    private const val OVERLAP_MS = 1_000L

    private var lastPkg: String? = null
    private var windowEndMs = 0L

    /** True when the usage-access grant is in place. */
    // Deprecated, and still the only way to read an app op about yourself
    // across 29..36; the replacements are all privileged.
    @Suppress("DEPRECATION")
    fun hasAccess(context: Context): Boolean = try {
        context.getSystemService(AppOpsManager::class.java)?.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    } catch (t: Throwable) {
        false
    }

    /**
     * The package whose activity resumed most recently, or null when usage
     * access is missing.
     *
     * Resume events are sparse — one per app switch, nothing at all while a
     * game is held — so the last one seen is remembered across calls rather
     * than re-queried over a window wide enough to contain it.
     */
    fun current(context: Context): String? {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return lastPkg
        val now = System.currentTimeMillis()
        val from = if (windowEndMs == 0L) now - COLD_WINDOW_MS else windowEndMs - OVERLAP_MS
        windowEndMs = now
        try {
            val events = usm.queryEvents(from, now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    lastPkg = event.packageName
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "usage events unavailable", t)
        }
        return lastPkg
    }
}

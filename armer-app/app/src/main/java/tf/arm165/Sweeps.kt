package tf.arm165

import android.content.Context
import android.content.pm.PackageManager

/**
 * Arm all and Clear, without a screen.
 *
 * The widget runs the same two sweeps the buttons in the app do, and the rule
 * for what a sweep may touch belongs in one place — [skip] is what both read,
 * so a package that must never be swept cannot be excluded in one path and
 * armed in the other.
 */
object Sweeps {

    /**
     * Packages a sweep leaves alone: SystemUI, because a vote on always-visible
     * windows is display-wide rather than per-app, and this app itself, because
     * arming it should be a decision rather than a side effect. Both stay one
     * tap away in the list.
     */
    fun skip(self: String): Set<String> = RateLock.optInOnly(self) + RateLock.NEVER_ARM

    /** Every installable target for a sweep, in no particular order. */
    fun targets(pm: PackageManager, self: String): List<String> {
        val skip = skip(self)
        @Suppress("DEPRECATION")
        return pm.getInstalledApplications(0)
            .map { it.packageName }
            .filter { it !in skip }
    }

    /**
     * Arms everything at the rate the selector is set to. Returns how many
     * votes landed against how many were tried.
     *
     * Slow by nature — one binder call per installed package — so callers must
     * be somewhere that can take the time. Holds [RateLock] for the sweep, the
     * same lock the watchdog takes, so a pass cannot interleave with it.
     */
    fun armAll(context: Context): Pair<Int, Int> {
        val prefs = ArmedStore.open(context)
        val rateId = prefs.getInt(ArmedStore.KEY_RATE, RateLock.DEFAULT_RATE)
            .takeIf { RateLock.isKnown(it) } ?: RateLock.DEFAULT_RATE
        val targets = targets(context.packageManager, context.packageName)
        prefs.edit().putInt(ArmedStore.KEY_TOTAL, targets.size).apply()
        val done = synchronized(RateLock) {
            RateLock.armEach(targets.associateWith { rateId }, Oiface.currentGamePackage())
        }
        val armed = ArmedStore.read(prefs)
        done.forEach { armed[it] = rateId }
        ArmedStore.write(prefs, armed)
        return done.size to targets.size
    }

    /** Releases every armed vote. Returns how many refused to let go. */
    fun clearAll(context: Context): Int {
        val prefs = ArmedStore.open(context)
        val saved = ArmedStore.read(prefs)
        if (saved.isEmpty()) return 0
        // Emptied before the vendor is touched, so a watchdog pass landing
        // mid-sweep cannot read one of these back and pin it again.
        ArmedStore.write(prefs, emptyMap())
        return synchronized(RateLock) {
            saved.count { (pkg, rateId) -> !RateLock.release(pkg, rateId) }
        }
    }
}

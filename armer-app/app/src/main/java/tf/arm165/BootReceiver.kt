package tf.arm165

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings the watchdog back with no app opened: after a reboot, and after an
 * update of this app, which stops the service and leaves nothing to restart
 * it.
 *
 * The receiver itself does as little as it can (issue #41). It used to work
 * out the vendor call and vote every armed app right here, on the main thread,
 * before starting the service: with Arm all that is hundreds of binder calls
 * into a system that is still booting, and a receiver that is killed for
 * taking too long never reaches the line that starts the watchdog. So the
 * service is started first, and the votes go out on its worker
 * ([ArmWatchService.ACTION_REARM]), where a foreground service can take as
 * long as it takes.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val boot = intent.action == Intent.ACTION_BOOT_COMPLETED
        if (!boot && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = ArmedStore.open(context)
        // Whether the system delivers this at all is the first question of
        // any "does not start after a reboot" report, and nothing else
        // answers it once the log has rolled over. The main screen reads it.
        if (boot) prefs.edit().putLong(KEY_BOOT_SEEN_MS, System.currentTimeMillis()).apply()
        val armed = ArmedStore.read(prefs)
        Log.i(TAG, (if (boot) "boot completed" else "app updated") + ", ${armed.size} armed")
        if (armed.isEmpty() && !LockRate.enabled(context)) return
        try {
            ArmWatchService.start(context, ArmWatchService.ACTION_REARM)
        } catch (t: Throwable) {
            // The system would not let the watchdog start from here. The
            // votes are still worth sending: they stand in the vendor's map
            // until the next reboot whether or not anything keeps them.
            Log.w(TAG, "the watchdog could not be started; sending the saved votes once", t)
            if (armed.isEmpty()) return
            val pending = goAsync()
            Thread {
                try {
                    RateLock.bind(context.packageName)
                    synchronized(RateLock) { RateLock.armEach(armed) }
                } finally {
                    pending.finish()
                }
            }.start()
        }
    }

    companion object {
        private const val TAG = "Arm165"

        /** Prefs key: wall-clock time the last BOOT_COMPLETED arrived. */
        const val KEY_BOOT_SEEN_MS = "boot_seen_ms"
    }
}

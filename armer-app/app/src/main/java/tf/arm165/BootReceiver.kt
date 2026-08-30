package tf.arm165

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = ArmedStore.open(context)
        val armed = ArmedStore.read(prefs)
        armed.forEach { (pkg, rateId) -> RateLock.arm(pkg, rateId) }
        // The service also hosts the FPS overlay, so it is needed whenever
        // either feature is in use.
        if (armed.isNotEmpty() || prefs.getBoolean(ArmWatchService.KEY_OVERLAY, false)) {
            ArmWatchService.start(context) // keep re-arming after games re-pin their rate
        }
    }
}

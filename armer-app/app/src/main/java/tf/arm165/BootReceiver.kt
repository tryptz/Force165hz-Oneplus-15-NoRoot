package tf.arm165

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = ArmedStore.open(context)
        val armed = ArmedStore.read(prefs)
        // Nothing is foregrounded yet at boot, so there is no app to vote
        // last; the watchdog re-orders every later pass around the one on
        // screen (see RateLock.armEach).
        RateLock.armEach(armed)
        if (armed.isNotEmpty()) {
            ArmWatchService.start(context) // keep re-arming after games re-pin their rate
        }
    }
}

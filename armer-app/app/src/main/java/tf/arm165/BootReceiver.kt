package tf.arm165

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val armed = ArmedStore.read(ArmedStore.open(context))
        armed.forEach { (pkg, rateId) -> RateLock.arm(pkg, rateId) }
        if (armed.isNotEmpty()) {
            ArmWatchService.start(context) // keep re-arming after games re-pin their rate
        }
    }
}

package tf.arm165

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("arm165", 0)
        prefs.getStringSet("armed", emptySet())?.forEach { RateLock.arm(it) }
        if (prefs.getStringSet("armed", emptySet())!!.isNotEmpty()) {
            ArmWatchService.start(context) // keep re-arming after games re-pin their rate
        }
    }
}

package tf.arm165

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The widget's rate selector.
 *
 * Its own receiver, and not exported: [ArmWidget] has to be exported so the
 * system can deliver APPWIDGET_UPDATE to it, and an exported receiver that
 * writes app state is an open door. Nothing outside the app can reach this one,
 * and the widget's PendingIntent is sent with the app's own identity.
 */
class WidgetRate : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_RATE) return
        val rateId = intent.getIntExtra(EXTRA_RATE, -1)
        if (!RateLock.isKnown(rateId)) return
        ArmedStore.open(context).edit().putInt(ArmedStore.KEY_RATE, rateId).apply()
        ArmWidget.refresh(context)
    }

    companion object {
        const val ACTION_SET_RATE = "tf.arm165.widget.SET_RATE"
        const val EXTRA_RATE = "rate"
    }
}

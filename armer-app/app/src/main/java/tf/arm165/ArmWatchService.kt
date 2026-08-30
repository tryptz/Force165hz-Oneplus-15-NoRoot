package tf.arm165

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlin.concurrent.thread

/**
 * Watchdog: re-issues the requestGameRefreshRate vote for every armed app, at
 * that app's own rate, every few seconds while the screen is on. Games that pin
 * their own frame rate on focus (Unity/Unreal engines etc.) overwrite our
 * single-shot vote — re-arming continuously lands our vote after theirs and wins.
 *
 * Also hosts the [FpsOverlay], so the status-bar readout reuses this service's
 * notification and screen on/off handling rather than adding a second one.
 */
class ArmWatchService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var prefs: android.content.SharedPreferences? = null
    private var screenOn = true
    private var overlay: FpsOverlay? = null

    private val tick = object : Runnable {
        override fun run() {
            if (screenOn) {
                val armed = prefs?.let { ArmedStore.read(it) }.orEmpty()
                if (armed.isNotEmpty()) {
                    // re-arm off the main thread; binder calls here are fast but be safe
                    thread {
                        synchronized(RateLock) {
                            armed.forEach { (pkg, rateId) -> RateLock.arm(pkg, rateId) }
                        }
                    }
                }
            }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            screenOn = i?.action != Intent.ACTION_SCREEN_OFF
            handler.removeCallbacks(tick)
            if (screenOn) {
                handler.postDelayed(tick, 1500)
                syncOverlay()
            } else {
                overlay?.hide() // nothing to measure with the panel off
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = ArmedStore.open(this)
        overlay = FpsOverlay(this)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
        })
        startForeground(NOTIFY_ID, buildNotification())
        handler.postDelayed(tick, 3000)
        Log.i("Arm165", "watchdog started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        syncOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        overlay?.hide()
        overlay = null
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Brings the overlay in line with the stored preference and permission. */
    private fun syncOverlay() {
        val o = overlay ?: return
        val p = prefs ?: return
        val wanted = p.getBoolean(KEY_OVERLAY, false) && screenOn
        if (wanted && o.canDraw()) {
            o.show(p.getInt(KEY_OVERLAY_X, DEFAULT_RIGHT_OFFSET_DP))
        } else {
            o.hide()
        }
    }

    private fun buildNotification(): Notification {
        val pi = android.app.PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, chan())
            .setContentTitle("165 Armer")
            .setContentText("Watchdog active — holding ${prefs?.let { ArmedStore.read(it).size } ?: 0} app(s)")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun chan(): String {
        val id = "arm165_watch"
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel(id, "165 Hz watchdog",
            android.app.NotificationManager.IMPORTANCE_MIN))
        return id
    }

    companion object {
        private const val INTERVAL_MS = 5000L
        private const val NOTIFY_ID = 165

        const val KEY_OVERLAY = "fps_overlay"
        const val KEY_OVERLAY_X = "fps_overlay_x"
        const val DEFAULT_RIGHT_OFFSET_DP = 116

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ArmWatchService::class.java))
        }

        /** Restarts the command so the service re-reads the overlay preference. */
        fun sync(context: Context) = start(context)
    }
}

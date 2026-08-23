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
 * Watchdog: re-issues the requestGameRefreshRate vote for every armed app
 * every few seconds while the screen is on. Games that pin their own frame
 * rate on focus (Unity/Unreal engines etc.) overwrite our single-shot vote —
 * re-arming continuously lands our vote after theirs and wins.
 */
class ArmWatchService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var prefs: android.content.SharedPreferences? = null
    private var screenOn = true

    private val tick = object : Runnable {
        override fun run() {
            if (screenOn) {
                val set = prefs?.getStringSet("armed", emptySet()) ?: emptySet()
                if (set.isNotEmpty()) {
                    // re-arm off the main thread; binder calls here are fast but be safe
                    thread { synchronized(RateLock) { set.forEach { RateLock.arm(it) } } }
                }
            }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            screenOn = i?.action != Intent.ACTION_SCREEN_OFF
            if (screenOn) handler.removeCallbacks(tick); handler.postDelayed(tick, 1500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("arm165", Context.MODE_PRIVATE)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
        })
        startForeground(NOTIFY_ID, buildNotification())
        handler.postDelayed(tick, 3000)
        Log.i("Arm165", "watchdog started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val pi = android.app.PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, chan())
            .setContentTitle("165 Armer")
            .setContentText("Watchdog active — keeping ${prefs?.getStringSet("armed", emptySet())?.size ?: 0} app(s) @165Hz")
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

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ArmWatchService::class.java))
        }
    }
}

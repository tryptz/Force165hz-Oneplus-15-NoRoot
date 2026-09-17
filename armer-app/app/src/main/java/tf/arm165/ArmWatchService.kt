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
import java.util.concurrent.Executors

/**
 * Watchdog: re-issues the requestGameRefreshRate vote for every armed app, at
 * that app's own rate, every few seconds while the screen is on. Games that pin
 * their own frame rate on focus (Unity/Unreal engines etc.) overwrite our
 * single-shot vote — re-arming continuously lands our vote after theirs and wins.
 *
 * With the LTPO idle preference on, the watchdog additionally gets out of the
 * panel's way: when the GPU has been quiet for a couple of ticks the armed
 * vote is withdrawn (`requestGameRefreshRate(pkg, 0)`, the same cancel a
 * disarm uses), so the panel's own LTPO logic can ramp to its 1 Hz idle rate
 * while the armed app sits still. Motion — a GPU load or a measured frame
 * rate above the hysteresis band — puts the vote straight back. A parked
 * screen costs battery; a resumed one costs at most one fast tick.
 *
 * Also hosts the [FpsOverlay], so the status-bar readout reuses this service's
 * notification and screen on/off handling rather than adding a second one.
 */
class ArmWatchService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    /** Binder calls stay off the main thread, and one tick cannot overlap the next. */
    private val worker = Executors.newSingleThreadExecutor()
    private var prefs: android.content.SharedPreferences? = null
    private var screenOn = true
    private var overlay: FpsOverlay? = null

    /** Armed packages whose vote is currently dropped for LTPO idle. */
    private val idleReleased = HashSet<String>()

    /** Consecutive quiet ticks per armed package, leading up to a release. */
    private val lowTicks = HashMap<String, Int>()

    /** Delay before the next tick; the worker tightens it while parked. */
    @Volatile private var nextInterval = INTERVAL_MS

    private val tick = object : Runnable {
        override fun run() {
            if (screenOn) {
                worker.execute {
                    // Read the armed set here, not when the tick was posted:
                    // a disarm that lands in between has already taken the
                    // app out, and replaying it would pin it straight back.
                    nextInterval = synchronized(RateLock) { tickOnce() }
                }
            }
            handler.postDelayed(this, nextInterval)
        }
    }

    /**
     * One watchdog pass; returns the delay before the next. Every branch that
     * can mutate the armed set holds the [RateLock] the disarm path takes, so
     * an app being released mid-tick cannot be pinned straight back.
     */
    private fun tickOnce(): Long {
        val armedNow = prefs?.let { ArmedStore.read(it) }.orEmpty()
        val ltpo = prefs?.getBoolean(KEY_LTP_IDLE, true) ?: true

        // Feature off, nothing armed, or no oiface to measure with: plain
        // watchdog, every armed vote re-landed at its own rate.
        if (!ltpo || armedNow.isEmpty() || !Oiface.isReachable()) {
            if (idleReleased.isNotEmpty() && armedNow.isNotEmpty()) {
                Log.i(TAG, "restoring ${idleReleased.size} idle-released vote(s)")
            }
            armedNow.forEach { (pkg, rateId) -> RateLock.arm(pkg, rateId) }
            idleReleased.clear()
            lowTicks.clear()
            return INTERVAL_MS
        }

        val gpu = Oiface.gpuLoad()
        val busy = gpu >= GPU_ACTIVE || armedNow.any { Oiface.fps(it.key) >= ACTIVE_FPS }
        if (busy) {
            if (idleReleased.isNotEmpty()) {
                Log.i(TAG, "LTPO idle over (gpu=%.2f) — restoring votes".format(gpu))
            }
            armedNow.forEach { (pkg, rateId) -> RateLock.arm(pkg, rateId) }
            idleReleased.clear()
            lowTicks.clear()
            return INTERVAL_MS
        }

        // Quiet GPU, no measured motion: static content. After a couple of
        // consecutive quiet ticks, drop the vote so the panel's own LTPO logic
        // can ramp down to its 1 Hz idle rate. One quiet tick alone is left
        // alone — a burst of loading between frames would flap the vote.
        if (gpu <= GPU_IDLE) {
            armedNow.forEach { (pkg, _) ->
                if (pkg in idleReleased) return@forEach
                val n = (lowTicks[pkg] ?: 0) + 1
                lowTicks[pkg] = n
                if (n >= LOW_TICKS && RateLock.arm(pkg, RateLock.RATE_NONE)) {
                    idleReleased += pkg
                    Log.i(TAG, "%s released for LTPO idle (gpu=%.2f)".format(pkg, gpu))
                }
            }
        }

        // Anything parked: tick fast, so touch input is noticed — and the vote
        // restored — within a second or two instead of five.
        return if (idleReleased.isNotEmpty()) RESUME_TICK_MS else INTERVAL_MS
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
        // The armed count is baked into the notification text, so re-post it:
        // the service is re-started on every change to the armed set.
        getSystemService(android.app.NotificationManager::class.java)
            ?.notify(NOTIFY_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        worker.shutdown()
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
            .setContentText(buildNotificationText())
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun buildNotificationText(): String {
        val armed = prefs?.let { ArmedStore.read(it) }.orEmpty()
        val base = "Watchdog active — holding ${armed.size} app(s)"
        return if (idleReleased.isNotEmpty()) "$base · LTPO idle: ${idleReleased.size} parked"
        else base
    }

    private fun chan(): String {
        val id = "arm165_watch"
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel(id, "165 Hz watchdog",
            android.app.NotificationManager.IMPORTANCE_MIN))
        return id
    }

    companion object {
        private const val TAG = "Arm165"
        private const val INTERVAL_MS = 5000L
        private const val NOTIFY_ID = 165

        /** Tick rate while a vote is parked for LTPO idle — input is noticed fast. */
        private const val RESUME_TICK_MS = 1500L

        /** Quiet ticks before a parked release: one burst is not idleness. */
        private const val LOW_TICKS = 2

        /** GPU load bands. Between them: hold state, no flapping. */
        private const val GPU_IDLE = 0.04f
        private const val GPU_ACTIVE = 0.15f

        /** A measured per-app fps at or above this counts as motion. */
        private const val ACTIVE_FPS = 45

        const val KEY_OVERLAY = "fps_overlay"
        const val KEY_OVERLAY_X = "fps_overlay_x"
        const val KEY_LTP_IDLE = "ltpo_idle"
        const val DEFAULT_RIGHT_OFFSET_DP = 116

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ArmWatchService::class.java))
        }

        /**
         * Stops the watchdog once nothing is armed — a disarm should leave
         * nothing running behind it. The fps overlay lives in this service too,
         * so a user who keeps the overlay on keeps the service, ticking over an
         * empty set.
         */
        fun stopIfIdle(context: Context) {
            val prefs = ArmedStore.open(context)
            if (ArmedStore.read(prefs).isNotEmpty()) return
            if (prefs.getBoolean(KEY_OVERLAY, false)) sync(context)
            else context.stopService(Intent(context, ArmWatchService::class.java))
        }

        /** Restarts the command so the service re-reads the overlay preference. */
        fun sync(context: Context) = start(context)
    }
}

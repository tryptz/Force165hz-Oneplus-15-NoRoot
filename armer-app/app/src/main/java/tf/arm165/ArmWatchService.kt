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
import android.view.Choreographer
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Watchdog: re-issues the requestGameRefreshRate vote for every armed app, at
 * that app's own rate, every few seconds while the screen is on. Games that pin
 * their own frame rate on focus (Unity/Unreal engines etc.) overwrite our
 * single-shot vote — re-arming continuously lands our vote after theirs and wins.
 *
 * With the LTPO idle preference on, the watchdog additionally parks the panel
 * instead of pinning it: when the GPU has been quiet for a couple of ticks the
 * armed vote is downgraded to 120 Hz — the one vendor rate whose panel mode
 * spans 1–120, so the LTPO ramp reaches its 1 Hz idle floor on a still screen
 * — while the vote itself stays held, so game engines that pin their own rate
 * still lose to ours. Measured floors on this build: 60→30, 90→30, 120→1,
 * 165→55. Motion — a GPU load or a measured frame rate above the hysteresis
 * band — puts the armed rate straight back. A parked screen idles at 1 Hz; a
 * resumed one costs at most one fast tick.
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

    /** Armed packages whose vote is currently parked at the idle rate. */
    private val parked = HashSet<String>()

    /** Consecutive quiet ticks per armed package, leading up to a release. */
    private val lowTicks = HashMap<String, Int>()

    /** Delay before the next tick; the worker tightens it while parked. */
    @Volatile private var nextInterval = INTERVAL_MS

    /*
     * Panel-rate motion detector. The oiface probes (gpuLoad, per-app fps)
     * only track apps the vendor game daemon knows — for everything else they
     * read 0 forever, which left parked votes stuck at 120 through active use
     * until the screen cycled. The panel itself is the universal signal: a
     * parked vote idles at 1 Hz, and the instant content animates the LTPO
     * controller ramps the panel up — motion shows as a rising vsync EMA long
     * before any GPU counter moves. Callback-driven via Choreographer, so it
     * costs nothing when idle and is always current when a tick reads it.
     */
    private var panelLastNs = 0L
    private var panelEmaNs = 0L

    /** Interval of the most recent frame pair — instant, unsmoothed evidence. */
    private var panelLastIntervalNs = 0L

    /** Last time a fast frame pair arrived — evidence of moving content. */
    private var lastFastFrameNs = 0L

    /** Until this time, panel motion is ignored — the park is still settling. */
    @Volatile private var parkGraceUntilNs = 0L

    private val panelFrames = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (panelLastNs != 0L) {
                val interval = frameTimeNanos - panelLastNs
                if (interval in 1..5_000_000_000L) {
                    panelLastIntervalNs = interval
                    panelEmaNs = if (panelEmaNs == 0L) interval
                    else (panelEmaNs * 3 + interval) / 4
                    if (interval <= PANEL_BUSY_INTERVAL_NS) lastFastFrameNs = frameTimeNanos
                } else {
                    panelLastIntervalNs = 0L
                    panelEmaNs = 0L
                }
            }
            panelLastNs = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Panel Hz from the vsync EMA; 0 when nothing has rendered recently. */
    private fun panelHz(): Int {
        if (panelEmaNs <= 0L || panelLastNs == 0L) return 0
        if (System.nanoTime() - panelLastNs > 2_500_000_000L) return 0
        return (1_000_000_000.0 / panelEmaNs).roundToInt()
    }

    /**
     * Instant motion: the last frame pair was fast. The EMA needs several
     * frames to climb out of the 1 Hz idle state — this needs exactly one, so
     * the restore waits for the panel's ramp, not for our smoothing.
     */
    private fun panelMoving(): Boolean {
        if (panelLastIntervalNs !in 1..PANEL_BUSY_INTERVAL_NS) return false
        if (panelLastNs == 0L || System.nanoTime() - panelLastNs > 2_500_000_000L) return false
        return true
    }

    /**
     * Moving content within the recent settle window — video, animation, any
     * frame producer at 20 Hz or more. Never touch or gestures: content alone
     * decides. This is the park gate, symmetric with the restore trigger: the
     * GPU/fps probes read 0 forever on apps the vendor daemon doesn't track,
     * so without it a playing video would not stop the park countdown.
     */
    private fun contentMoving(): Boolean {
        if (lastFastFrameNs == 0L || panelLastNs == 0L) return false
        val now = System.nanoTime()
        if (now - panelLastNs > 2_500_000_000L) return false // nothing rendering
        return now - lastFastFrameNs < IDLE_SETTLE_NS
    }

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

        // The armed set can change under us (disarm, re-arm, rate change) while
        // this service keeps running. Park/quiet state for a package that is no
        // longer armed is stale — and worse, a package that was DISARMED while
        // parked and then RE-ARMED would be skipped by the park branch forever
        // ("stops switching to 120 after re-enabling"), because its stale entry
        // made the branch treat it as already parked. Prune to the live set.
        parked.retainAll(armedNow.keys)
        lowTicks.keys.retainAll(armedNow.keys)

        // Feature off, nothing armed, or no oiface to measure with: plain
        // watchdog, every armed vote re-landed at its own rate.
        if (!ltpo || armedNow.isEmpty() || !Oiface.isReachable()) {
            if (parked.isNotEmpty() && armedNow.isNotEmpty()) {
                Log.i(TAG, "restoring ${parked.size} parked vote(s)")
            }
            armedNow.forEach { (pkg, rateId) -> RateLock.arm(pkg, rateId) }
            parked.clear()
            lowTicks.clear()
            return INTERVAL_MS
        }

        val gpu = Oiface.gpuLoad()
        // Busy = any of three signals, panel rate first: it is the only one
        // that works for every app — a parked panel idles at 1 Hz, and content
        // in motion ramps it up, whatever the app is. The instantaneous check
        // fires on the first fast frame pair; the EMA confirms sustained rate.
        // Both are ignored inside the park grace window: the vote just moved
        // into the 120 mode and its handover frames are still fast.
        val graceOver = System.nanoTime() >= parkGraceUntilNs
        val phz = panelHz()
        val busy = (parked.isNotEmpty() && graceOver &&
            (panelMoving() || phz >= PANEL_BUSY_HZ)) ||
            gpu >= GPU_ACTIVE || armedNow.any { Oiface.fps(it.key) >= ACTIVE_FPS }
        if (busy) {
            if (parked.isNotEmpty()) {
                Log.i(TAG, "LTPO idle over (panel=%d Hz, gpu=%.2f) — restoring armed rates"
                    .format(phz, gpu))
            }
            armedNow.forEach { (pkg, rateId) -> RateLock.arm(pkg, rateId) }
            parked.clear()
            lowTicks.clear()
            return INTERVAL_MS
        }

        // Quiet GPU alone is NOT idle: a 24 fps video on an app the vendor
        // daemon doesn't track still reads gpu=0. Moving content both voids
        // the quiet ticks counted so far and blocks new ones — symmetric with
        // the restore trigger. Only genuinely still content may park.
        if (contentMoving()) {
            lowTicks.clear()
        } else if (gpu in 0f..GPU_IDLE) {
            armedNow.forEach { (pkg, rateId) ->
                if (pkg in parked || rateId <= RateLock.RATE_120) return@forEach
                val n = (lowTicks[pkg] ?: 0) + 1
                lowTicks[pkg] = n
                if (n >= LOW_TICKS && RateLock.arm(pkg, RateLock.RATE_120)) {
                    parked += pkg
                    // Parking switches the panel into the 120 mode directly
                    // (no discrete 60/30 hop): it can present at up to 120 for
                    // a beat until the idle ramp takes over inside the mode's
                    // 1-120 range. Those fast frames are the transition, not
                    // motion — ignore them briefly.
                    parkGraceUntilNs = System.nanoTime() + PARK_GRACE_NS
                    Log.i(TAG, "%s parked at 120 (mode floor 1 Hz, gpu=%.2f)".format(pkg, gpu))
                }
            }
        }

        // Anything parked: tick fast, so touch input is noticed — and the
        // armed rate restored — within a second or two instead of five.
        return if (parked.isNotEmpty()) RESUME_TICK_MS else INTERVAL_MS
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val off = i?.action == Intent.ACTION_SCREEN_OFF
            screenOn = !off
            handler.removeCallbacks(tick)
            if (!off) {
                // A wake-up is motion by definition: the launcher, the keyguard
                // and the app all animate. Restore every parked vote to its
                // armed rate right here — waiting for a tick would leave the
                // panel parked at 120 through the resume animation, and a
                // still first frame after it could re-park immediately.
                worker.execute {
                    synchronized(RateLock) {
                        val armedNow = prefs?.let { ArmedStore.read(it) }.orEmpty()
                        if (parked.isNotEmpty()) {
                            Log.i(TAG, "screen on — restoring ${parked.size} parked vote(s)")
                        }
                        armedNow.forEach { (pkg, rateId) -> RateLock.arm(pkg, rateId) }
                        parked.clear()
                        lowTicks.clear()
                        nextInterval = INTERVAL_MS
                    }
                }
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
        Choreographer.getInstance().postFrameCallback(panelFrames)
        startForeground(NOTIFY_ID, buildNotification())
        handler.postDelayed(tick, 3000)
        Log.i("Arm165", "watchdog started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every arm/re-arm/rate change restarts the command. A fresh arm is the
        // user asking for the armed rate NOW, so any park state from the
        // previous armed-set epoch is void — otherwise a package re-armed while
        // others keep the service alive stays skipped by the park branch.
        parked.clear()
        lowTicks.clear()
        syncOverlay()
        // The armed count is baked into the notification text, so re-post it:
        // the service is re-started on every change to the armed set.
        getSystemService(android.app.NotificationManager::class.java)
            ?.notify(NOTIFY_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        Choreographer.getInstance().removeFrameCallback(panelFrames)
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
        return if (parked.isNotEmpty()) "$base · LTPO idle: ${parked.size} parked @ 120"
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

        /**
         * Tick rate while a vote is parked. The requirement is a return to the
         * armed rate in well under 0.3 s of motion: worst case the touch lands
         * right after a poll, so detection = one interval, plus one binder
         * set. 50 ms poll + ~5 ms set ≈ 55 ms of our own latency; the rest is
         * the panel's own ramp. Only runs while parked, and only after a park
         * that a live probe authorized.
         */
        private const val RESUME_TICK_MS = 50L

        /**
         * Panel EMA at or above this = content in motion = restore. Well below
         * the 120 park rate and far above the 1 Hz floor; 20 keeps a 24 fps
         * video above the line.
         */
        private const val PANEL_BUSY_HZ = 20

        /** 1 / [PANEL_BUSY_HZ] in ns — the instantaneous-motion threshold. */
        private const val PANEL_BUSY_INTERVAL_NS = 50_000_000L

        /** How long after a park the 120-mode handover frames are ignored. */
        private const val PARK_GRACE_NS = 400_000_000L

        /**
         * Content counts as moving for this long after its last fast frame —
         * the settle window that gates parking. A buffering pause shorter
         * than this never parks the panel.
         */
        private const val IDLE_SETTLE_NS = 2_000_000_000L

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

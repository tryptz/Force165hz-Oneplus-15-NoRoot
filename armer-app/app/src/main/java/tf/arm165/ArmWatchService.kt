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
 * 165→55. Restore is rate starvation: the parked 120 ceiling serves every
 * cadence up to 120, and only content demanding more — seen as the panel
 * pinned near 120 — brings the armed rate back. Slower content is served by
 * the park and never triggers it, so the vote cannot oscillate. A parked
 * screen idles at 1 Hz; a starved one costs ~70 ms to restore.
 */
class ArmWatchService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    /** Binder calls stay off the main thread, and one tick cannot overlap the next. */
    private val worker = Executors.newSingleThreadExecutor()
    private var prefs: android.content.SharedPreferences? = null
    private var screenOn = true

    /** Armed packages whose vote is currently parked at the idle rate. */
    private val parked = HashSet<String>()

    /** Ticks since the last park; paces the parked-hold diagnostic line. */
    private var holdTicks = 0

    /** Consecutive quiet ticks per armed package, leading up to a release. */
    private val lowTicks = HashMap<String, Int>()

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

    /**
     * Consecutive fast frame pairs, counted in the callback below. The ramp
     * down to the idle floor also produces fast pairs for a few frames, and a
     * single fast pair cannot tell it from the ramp up after a touch — a run
     * of them can: decay gives one or two fast pairs and then falls below the
     * threshold, motion sustains. This is what keeps the parked state from
     * flapping between 120 and the armed rate.
     */
    /**
     * Consecutive STARVATION pairs — intervals at or under 10 ms — counted in
     * the callback below. The parked vote is a 120 ceiling, which serves every
     * cadence up to 120 invisibly; only content demanding more than 120 pins
     * the panel at ~8 ms pairs. The 165 mode's own 55 Hz floor sits at 18 ms
     * and a 60 fps app at 17 ms — both permanently above the line, so unlike
     * the 50 ms fast-run counter this one cannot be fed by our own pin or by
     * the mode-transition ramp. It is the only restore signal that cannot be
     * faked by the vote that is being measured.
     */
    private var panelStarveRun = 0

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
                    panelStarveRun = if (interval <= STARVED_INTERVAL_NS) panelStarveRun + 1 else 0
                    if (interval <= PANEL_BUSY_INTERVAL_NS) lastFastFrameNs = frameTimeNanos
                } else {
                    panelLastIntervalNs = 0L
                    panelEmaNs = 0L
                    panelStarveRun = 0
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
            worker.execute {
                if (!screenOn) return@execute // nothing to measure with the panel off
                // Read the armed set here, not when the tick was posted:
                // a disarm that lands in between has already taken the
                // app out, and replaying it would pin it straight back.
                val n = synchronized(RateLock) { tickOnce() }
                // Schedule from HERE, with the interval this pass just
                // computed. Scheduling on the main thread before the worker
                // ran would read the previous pass's interval — one tick of
                // staleness that turned every transition into the parked
                // 50 ms cadence into 5 s of blind polling, which is exactly
                // the resume latency this cadence exists to prevent.
                if (screenOn) {
                    handler.removeCallbacks(this)
                    handler.postDelayed(this, n)
                }
            }
        }
    }

    /**
     * One watchdog pass; returns the delay before the next. Every branch that
     * can mutate the armed set holds the [RateLock] the disarm path takes, so
     * an app being released mid-tick cannot be pinned straight back.
     */
    private fun tickOnce(): Long {
        val armedNow = prefs?.let { ArmedStore.read(it) }.orEmpty()

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
        if (armedNow.isEmpty() || !Oiface.isReachable()) {
            if (parked.isNotEmpty() && armedNow.isNotEmpty()) {
                Log.i(TAG, "restoring ${parked.size} parked vote(s)")
            }
            armedNow.forEach { (pkg, rateId) -> RateLock.arm(pkg, rateId) }
            parked.clear()
            lowTicks.clear()
            return INTERVAL_MS
        }

        val gpu = Oiface.gpuLoad()
        // Per-app measured fps, computed once per tick: motion evidence for
        // apps the vendor daemon tracks (it reads 0 forever for all others).
        val fpsBusy = armedNow.any { Oiface.fps(it.key) >= ACTIVE_FPS }
        // Busy = rate starvation first. The parked vote is a 120 ceiling: it
        // serves video, 30/60 fps apps, ordinary scrolling — everything up to
        // 120 — invisibly, so those never restore and never flap. The panel
        // only asks for the armed rate back when content pushes against the
        // ceiling itself: consecutive pairs at ~8 ms, or an EMA near 120.
        // Restoring on anything slower (50 ms pairs, 20 Hz EMA) re-lands 165
        // whose 55 Hz floor then reads as motion, the gate sees quiet, and the
        // vote oscillates — the 'unstable at 165' loop. GPU load and tracked
        // fps stay as conservative backups.
        val graceOver = System.nanoTime() >= parkGraceUntilNs
        val phz = panelHz()
        val busy = (parked.isNotEmpty() && graceOver &&
            (panelStarveRun >= STARVED_RUN_FRAMES || phz >= STARVED_HZ)) ||
            gpu >= GPU_ACTIVE || fpsBusy
        if (busy) {
            if (parked.isNotEmpty()) {
                // starve and fps are in the line so a restore that fires at a
                // still screen names its own trigger: a blip from a system
                // overlay pumps two fast pairs in with gpu at zero — versus
                // real motion, which drags the EMA up with it.
                Log.i(TAG, ("LTPO idle over (panel=%d Hz, gpu=%.2f, starve=%d, " +
                    "pair=%.1f ms, ema=%.0f ms, fps=%s) — restoring armed rates")
                    .format(phz, gpu, panelStarveRun, panelLastIntervalNs / 1e6,
                        panelEmaNs / 1e6, armedNow.entries.joinToString(",") {
                            (pkg, rateId) -> "${pkg.substringBeforeLast('.')}=" + Oiface.fps(pkg)
                        }))
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
        //
        // Except the panel cannot testify while a 165 vote is held: the 165
        // mode's floor is 55 Hz, so on a perfectly still armed screen every
        // frame pair reads "fast" and contentMoving() would hold the gate
        // shut forever — a 165-armed app would never park while every other
        // rate does. The detector would be reading its own pin, not the
        // content. For a pinned 165 vote the gate instead asks whether the
        // panel has climbed ABOVE the floor — a still screen idles at the
        // floor (55 Hz), motion pushes it over it — plus the starvation run
        // (only >120 Hz content produces it) and the vendor's fps counter,
        // the one signal our own pin cannot fake. Once parked, the vote lives
        // in the 120 mode whose floor is 1 Hz and the panel is honest again:
        // the restore path uses starvation evidence only.
        val panelPinned = armedNow.values.any { it > RateLock.RATE_144 }
        val gateBusy = if (panelPinned) fpsBusy ||
            panelStarveRun >= STARVED_RUN_FRAMES || phz >= PIN_FLOOR_HZ
            else contentMoving() || fpsBusy
        if (gateBusy) {
            lowTicks.clear()
        } else if (gpu in 0f..GPU_IDLE) {
            armedNow.forEach { (pkg, rateId) ->
                // 165 is parked never: the 165 mode's floor is 55 Hz, so a
                // still 165 app just rests there — and parking it puts the
                // whole panel into the 120 mode whose entry churn (every
                // system renderer redrawing at the new ceiling) reads as
                // demand and restores the vote ~0.9 s later, forever. The
                // parked set only ever holds 144 votes.
                if (pkg in parked || rateId <= RateLock.RATE_120 || rateId > RateLock.RATE_144) return@forEach
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
                    holdTicks = 0
                }
            }
        }

        // While parked, periodically report what the panel is actually doing.
        // This is the diagnostic that separates the three parked outcomes:
        //   panel → 1 Hz   the idle ramp engaged (a park behaving as designed)
        //   panel ≈ 120   the foreground app keeps rendering at the ceiling —
        //                 a continuously-animating app can never idle, LTPO or
        //                 not; no vote we hold can change that
        //   panel stuck mid-ramp (30/60) — a vendor mode floor problem
        // Emits one line every ~5 s, so a parked hold of a minute reads as a
        // dozen identical lines — or a dozen that descend. Cheap: no binder
        // calls beyond the gpu probe the tick already made.
        if (parked.isNotEmpty() && System.nanoTime() >= parkGraceUntilNs) {
            if (++holdTicks % HOLD_LOG_TICKS == 1) {
                Log.i(TAG, "parked hold: panel=%d Hz (pair=%.1f ms), gpu=%.2f, starve=%d"
                    .format(panelHz(), panelLastIntervalNs / 1e6, gpu, panelStarveRun))
            }
        }

        // Cadence by state: parked → 50 ms (resume within ~55 ms of motion);
        // a 165 vote still held but not parked → 1 s, because every tick it
        // holds the panel at its 55 Hz floor — the park countdown must not
        // take 2 × 5 s when the cost of waiting is measured in burned Hz;
        // anything else → the plain 5 s watchdog tick.
        return when {
            parked.isNotEmpty() -> RESUME_TICK_MS
            armedNow.any { (pkg, rateId) -> rateId > RateLock.RATE_144 && pkg !in parked } -> PINNED_TICK_MS
            else -> INTERVAL_MS
        }
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
                    }
                }
                handler.postDelayed(tick, 1500)
            } else {
                // nothing to measure with the panel off
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        prefs = ArmedStore.open(this)
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
        // The armed count is baked into the notification text, so re-post it:
        // the service is re-started on every change to the armed set.
        getSystemService(android.app.NotificationManager::class.java)
            ?.notify(NOTIFY_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        Choreographer.getInstance().removeFrameCallback(panelFrames)
        worker.shutdown()
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
         * True between [onCreate] and [onDestroy] — a cheap same-process
         * liveness probe for callers that only want to act when the
         * watchdog is NOT already watching (the activity's boot catch-up).
         */
        @Volatile var running = false
            private set


        /**
         * Tick rate while a vote is parked. The requirement is a return to the
         * armed rate in well under 0.3 s of motion: worst case the touch lands
         * right after a poll, so detection = one interval, plus one binder
         * set. 50 ms poll + ~5 ms set ≈ 55 ms of our own latency; the rest is
         * the panel's own ramp. Only runs while parked, and only after a park
         * that a live probe authorized.
         */
        private const val RESUME_TICK_MS = 50L

        /** Parked-hold diagnostic cadence: 100 parked ticks × 50 ms = one line per 5 s. */
        private const val HOLD_LOG_TICKS = 100

        /**
         * Tick rate while a 165 (or 144) vote is held but not yet parked.
         * The pin holds the panel at its mode floor — 55 Hz for 165 — so
         * every extra second of countdown is a second of unnecessary panel
         * power. Two quiet 1 s ticks park, against ten at the watchdog's
         * idle cadence.
         */
        private const val PINNED_TICK_MS = 1000L

        /**
         * Fast-pair stamp for [contentMoving] — the park gate for rates whose
         * modes idle BELOW 20 Hz, where a slow panel genuinely means still.
         * Never used as restore evidence: the 165 mode's 55 Hz floor feeds it
         * on a perfectly still screen.
         */
        private const val PANEL_BUSY_INTERVAL_NS = 50_000_000L

        /**
         * Panel EMA at or above this while parked = the panel is pinned near
         * the 120 ceiling = content demanding more than 120 = restore. Well
         * above the 55 Hz floor of the 165 mode (so a pinned vote's own floor
         * cannot feed it) and just under the ceiling it detects.
         */
        private const val STARVED_HZ = 90

        /** 1 / [STARVED_HZ] in ns — the starvation-pair threshold. */
        private const val STARVED_INTERVAL_NS = 10_000_000L

        /**
         * Consecutive starvation pairs required: one sub-10 ms pair can be a
         * mode-switch transient (the ramp down from 120 never produces two in
         * a row — its steps are 8→16→25 ms), two in a row is a panel pinned
         * near 120 by genuinely starved content.
         */
        private const val STARVED_RUN_FRAMES = 2

        /**
         * The 165 mode's measured idle floor is 55 Hz — a still armed screen
         * idles AT it. The park gate for a pinned 165 vote treats the panel
         * climbing meaningfully above the floor as motion; 10 Hz of margin
         * keeps the floor's own wobble below the line.
         */
        private const val PIN_FLOOR_HZ = 65

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

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ArmWatchService::class.java))
        }

        /**
         * Stops the watchdog once nothing is armed — a disarm should leave
         * nothing running behind it.
         */
        fun stopIfIdle(context: Context) {
            val prefs = ArmedStore.open(context)
            if (ArmedStore.read(prefs).isNotEmpty()) return
            context.stopService(Intent(context, ArmWatchService::class.java))
        }
    }
}

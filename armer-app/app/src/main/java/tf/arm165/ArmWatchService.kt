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
 * Watchdog: re-issues the requestGameRefreshRate vote while the screen is on.
 * Games that pin their own frame rate on focus (Unity/Unreal engines etc.)
 * overwrite our single-shot vote — re-arming continuously lands our vote after
 * theirs and wins.
 *
 * Every pass is ordered around the app on screen — from usage access where
 * the user has granted it, oiface's current game otherwise — and ends on it.
 * The server
 * writes the override onto the live windows of the package each call names,
 * and the panel takes its mode from the write that landed last, so a pass that
 * ends on a background package hands the display a vote for windows that do
 * not exist. That is what "Arm all" did every second: hundreds of votes in
 * package order, the foreground app's pin dissolved by whatever sorted after
 * it, and the panel free to fall back to its default 1-120 range — the LTPO
 * idling to 1 Hz inside an app that was supposed to be pinned. Background
 * votes are sticky in the vendor's map anyway (only a release takes one away),
 * so they are refreshed a bounded slice at a time instead of all at once.
 *
 * The watchdog also parks the panel instead of pinning it: when the screen has
 * been still for a couple of ticks the armed vote is downgraded to 120 Hz —
 * the one vendor rate whose panel mode spans 1–120, so the LTPO ramp reaches
 * its 1 Hz idle floor on a still screen — while the vote itself stays held, so
 * game engines that pin their own rate still lose to ours. One rule says what
 * may park: a vote whose ceiling is above that 120, which is 144 and 165.
 * Measured floors on this build: 60→30, 90→30, 120→1, 165→55 (144 estimated
 * at 48, see [RateLock.idleFloorHz]). Restore is rate starvation: the parked 120 ceiling serves every
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

    /** When each parked package was parked; feeds the churn backoff. */
    private val parkedAtNs = HashMap<String, Long>()

    /** Packages the park must leave alone until this time — see [noteParkChurn]. */
    private val parkBlockedUntilNs = HashMap<String, Long>()

    /** The armed package we believe is on screen, and when we last saw it. */
    private var focusPkg: String? = null
    private var focusSeenNs = 0L

    /** The package on screen, armed or not — for the log line only. */
    private var screenPkg: String? = null

    /** Last pass's focus, so a switch INTO an armed app can be noticed. */
    private var lastPassFocus: String? = null

    /** Pass counter and cursor for the bounded background repair. */
    private var passes = 0
    private var repairCursor = 0

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
        passes++

        // The armed set can change under us (disarm, re-arm, rate change) while
        // this service keeps running. Park/quiet state for a package that is no
        // longer armed is stale — and worse, a package that was DISARMED while
        // parked and then RE-ARMED would be skipped by the park branch forever
        // ("stops switching to 120 after re-enabling"), because its stale entry
        // made the branch treat it as already parked. Prune to the live set.
        parked.retainAll(armedNow.keys)
        lowTicks.keys.retainAll(armedNow.keys)
        parkedAtNs.keys.retainAll(parked)
        parkBlockedUntilNs.keys.retainAll(armedNow.keys)

        // Who is on screen, and which background votes this pass refreshes.
        // Every vote below is ordered around these two.
        val screen = screen(armedNow)
        val focus = screen.focus

        // The app on screen is identified and it is not armed, so no vote of
        // ours is in effect: nothing to refresh, nothing to park. Issuing
        // background votes here is how a pin leaks onto an app that was never
        // armed — the write would land on windows that do exist, just not the
        // ones we meant. Park state is kept: whatever is parked is off screen,
        // and gets its rate back below when it comes forward again.
        if (armedNow.isNotEmpty() && screen.known && focus == null) {
            lastPassFocus = null
            return if (parked.isEmpty()) INTERVAL_MS else PINNED_TICK_MS
        }

        // Switching INTO an armed app is the user asking for its rate now: a
        // vote left parked at 120 from the last time it was on screen must not
        // be what greets them. Not park churn — no content undid this one.
        if (focus != null && focus != lastPassFocus && focus in parked) {
            armedNow[focus]?.let { rateId ->
                if (RateLock.arm(focus, rateId)) {
                    parked -= focus
                    lowTicks -= focus
                    parkedAtNs -= focus
                    Log.i(TAG, "$focus back on screen — restored to ${RateLock.hz(rateId)} Hz")
                }
            }
        }
        lastPassFocus = focus

        // Computed only now: the cursor it advances must not be spent by a
        // pass that returned above without voting.
        val repair = repairTargets(armedNow, focus)

        // Feature off, nothing armed, or no oiface to measure with: plain
        // watchdog, votes out foreground-last, no park state to keep.
        if (armedNow.isEmpty() || !Oiface.isReachable()) {
            if (parked.isNotEmpty()) Log.i(TAG, "restoring ${parked.size} parked vote(s)")
            voteSweep(armedNow, focus, parked + repair)
            clearParkState()
            return INTERVAL_MS
        }

        val gpu = Oiface.gpuLoad()
        // Measured fps for the app on screen. This used to probe every armed
        // package — one binder call per armed app per tick, hundreds of them
        // once "Arm all" had been pressed — and the daemon only answers for
        // what it tracks, which is exactly what [focus] already names.
        val focusFps = focus?.let { Oiface.fps(it) } ?: 0
        val fpsBusy = focusFps >= ACTIVE_FPS
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
                    "pair=%.1f ms, ema=%.0f ms, focus=%s@%d fps) — restoring armed rates")
                    .format(phz, gpu, panelStarveRun, panelLastIntervalNs / 1e6,
                        panelEmaNs / 1e6, focus?.substringAfterLast('.') ?: "none", focusFps))
            }
            restore(armedNow, focus, churn = true, extra = repair)
            return INTERVAL_MS
        }

        // Quiet GPU alone is NOT idle: a 24 fps video on an app the vendor
        // daemon doesn't track still reads gpu=0. Moving content both voids
        // the quiet ticks counted so far and blocks new ones — symmetric with
        // the restore trigger. Only genuinely still content may park.
        //
        // Except the panel cannot testify while a vote whose mode floor sits
        // above the motion threshold is held: 165 idles at 55 Hz, 144 near 48,
        // 60 and 90 at 30 — all of them faster than the 20 Hz contentMoving()
        // reads as movement, so on a perfectly still armed screen every frame
        // pair looks "fast" and the gate would stay shut forever. The detector
        // would be reading our own pin, not the content. For those the gate
        // asks instead whether the panel has climbed meaningfully ABOVE the
        // mode's floor — a still screen idles AT it, motion pushes it over —
        // plus the starvation run (only >120 Hz content produces it) and the
        // vendor's fps counter, the one signal our own pin cannot fake. Only
        // the 120 mode (floor 1 Hz) leaves the panel honest enough for
        // contentMoving(); once parked there the restore path uses starvation
        // evidence only.
        //
        // This used to test `any { it > RATE_144 }`, which is 165 alone — and
        // 165 was also the one rate the park refused, so the two conditions
        // cancelled out and nothing could ever park: 144 fell to the
        // contentMoving() branch and read its own 48 Hz floor as motion.
        // The vote in effect is the foreground app's, so its floor is the one
        // the panel is sitting on. With no focus to go by, take the highest
        // floor in the armed set: over-estimating it only delays a park,
        // while under-estimating it would read the floor as motion.
        val floorHz = focus?.let { armedNow[it] }?.let { RateLock.idleFloorHz(it) }
            ?: armedNow.values.maxOfOrNull { RateLock.idleFloorHz(it) } ?: 0
        val floorHidesContent = floorHz >= PANEL_MOTION_HZ
        val gateBusy = if (floorHidesContent) fpsBusy ||
            panelStarveRun >= STARVED_RUN_FRAMES || phz >= floorHz + FLOOR_MARGIN_HZ
            else contentMoving() || fpsBusy
        if (gateBusy) {
            lowTicks.clear()
        } else if (gpu in 0f..GPU_IDLE) {
            val now = System.nanoTime()
            parkCandidates(armedNow, focus).forEach { (pkg, rateId) ->
                // ONE rule for what may park: a vote is parkable when its own
                // ceiling is above the 120 the park would put in its place.
                // That is what makes the swap both worth making and invisible
                // — 120's mode floor is 1 Hz against 144's 48 and 165's 55,
                // and every cadence up to 120 is still served, so nothing the
                // panel could already present is taken away. A 120 vote is
                // already the park; 60 and 90 are ceilings the user picked to
                // keep faster content OFF the panel (judder), and the park
                // must never raise a ceiling.
                if (pkg in parked || RateLock.hz(rateId) <= PARK_CEILING_HZ) return@forEach
                if (now < (parkBlockedUntilNs[pkg] ?: 0L)) return@forEach
                val n = (lowTicks[pkg] ?: 0) + 1
                lowTicks[pkg] = n
                if (n >= LOW_TICKS && RateLock.arm(pkg, RateLock.RATE_120)) {
                    parked += pkg
                    parkedAtNs[pkg] = now
                    // Parking switches the panel into the 120 mode directly
                    // (no discrete 60/30 hop): it can present at up to 120 for
                    // a beat until the idle ramp takes over inside the mode's
                    // 1-120 range. Those fast frames are the transition, not
                    // motion — ignore them briefly.
                    parkGraceUntilNs = now + PARK_GRACE_NS
                    Log.i(TAG, "%s parked at 120 (mode floor 1 Hz, from %d Hz floor %d, gpu=%.2f)"
                        .format(pkg, RateLock.hz(rateId), RateLock.idleFloorHz(rateId), gpu))
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
        // a 144 or 165 vote still held but not parked → 1 s, because every
        // tick it holds the panel at that mode's floor (55 Hz for 165) — the park countdown must not
        // take 2 × 5 s when the cost of waiting is measured in burned Hz;
        // anything else → the plain 5 s watchdog tick.
        return when {
            parked.isNotEmpty() -> RESUME_TICK_MS
            armedNow.any { (pkg, rateId) ->
                pkg !in parked && RateLock.hz(rateId) > PARK_CEILING_HZ
            } -> PINNED_TICK_MS
            else -> INTERVAL_MS
        }
    }

    /**
     * What a pass knows about the app on screen: [focus] is the armed package
     * whose vote the panel is actually following — the only one a game engine
     * can overwrite, and the one that must own the last write of the pass —
     * and [known] says whether anything positively identified what is in front
     * of the user, as opposed to the pass falling back on memory.
     */
    private class Screen(val focus: String?, val known: Boolean)

    /**
     * Identifies the app on screen, best signal first.
     *
     * [Foreground] (usage access) names it whatever it is, which is the only
     * way to learn that the app in front of the user is NOT armed — worth
     * knowing, because then no vote of ours is in effect and the pass has
     * nothing to do. oiface answers only for games its daemon tracks. With
     * neither, the last answer stands for [FOCUS_MEMORY_NS] so a momentary
     * null does not reorder the sweep, and after that the pass simply has no
     * focus: votes still go out, just without a package to end on.
     */
    private fun screen(armedNow: Map<String, Int>): Screen {
        Foreground.current(this)?.let { onScreen ->
            val armedOnScreen = onScreen.takeIf { it in armedNow }
            noteScreen(onScreen, armedOnScreen)
            return Screen(armedOnScreen, known = true)
        }
        Oiface.currentGamePackage()?.takeIf { it in armedNow }?.let { pkg ->
            noteScreen(pkg, pkg)
            return Screen(pkg, known = true)
        }
        val remembered = focusPkg
        if (remembered != null && remembered in armedNow &&
            System.nanoTime() - focusSeenNs < FOCUS_MEMORY_NS
        ) {
            return Screen(remembered, known = false)
        }
        focusPkg = null
        return Screen(null, known = false)
    }

    private fun noteScreen(onScreen: String, armedOnScreen: String?) {
        if (onScreen != screenPkg) {
            Log.i(TAG, "screen: $onScreen" + if (armedOnScreen == null) " (not armed)" else "")
        }
        screenPkg = onScreen
        focusPkg = armedOnScreen
        focusSeenNs = System.nanoTime()
    }

    /**
     * The armed entries a park may act on this pass.
     *
     * Only the vote in effect — the foreground app's — is worth parking: a
     * park writes a 120 vote, and writing 300 of them is the same flood as a
     * full sweep, at the moment the panel is trying to settle. With no focus
     * to go by, a small armed set is still parked whole (the boot-list scale
     * this feature was built at), and a large one waits until something
     * identifies the app on screen.
     */
    private fun parkCandidates(armedNow: Map<String, Int>, focus: String?): Map<String, Int> {
        if (focus != null) return armedNow[focus]?.let { mapOf(focus to it) }.orEmpty()
        return if (armedNow.size <= FULL_SWEEP_MAX) armedNow else emptyMap()
    }

    /**
     * Background packages to re-vote this pass.
     *
     * Their votes are sticky in the vendor's map — an arm writes one, only a
     * release takes it away — so re-issuing them buys nothing except repair of
     * a vote the server dropped. A handful per pass is what the boot list
     * always did and it never cost the pin; the few hundred "Arm all" produced
     * is what did. So a small set still goes out whole, and a large one is
     * refreshed [REPAIR_SLICE] at a time, every [REPAIR_EVERY_PASSES] passes,
     * cycling through the set.
     */
    private fun repairTargets(armedNow: Map<String, Int>, focus: String?): List<String> {
        val keys = armedNow.keys.filter { it != focus }
        if (keys.size <= FULL_SWEEP_MAX) return keys
        if (passes % REPAIR_EVERY_PASSES != 0) return emptyList()
        if (repairCursor >= keys.size) repairCursor = 0
        val end = minOf(repairCursor + REPAIR_SLICE, keys.size)
        val slice = ArrayList(keys.subList(repairCursor, end))
        repairCursor = if (end >= keys.size) 0 else end
        return slice
    }

    /**
     * Issues one pass of votes, [focus] last.
     *
     * The order is the point: the server writes the override onto the live
     * windows of the package each call names, and the panel takes its mode
     * from the write that landed last. Ending a pass on a background package
     * hands the display a vote for windows that do not exist, which is how
     * arming every app dissolved the pin the same pass had just set.
     */
    private fun voteSweep(armedNow: Map<String, Int>, focus: String?, others: Collection<String>) {
        val pass = LinkedHashMap<String, Int>(others.size + 1)
        others.forEach { pkg -> armedNow[pkg]?.let { pass[pkg] = it } }
        focus?.let { pkg -> armedNow[pkg]?.let { pass[pkg] = it } }
        if (pass.isNotEmpty()) RateLock.armEach(pass, focus)
    }

    /**
     * Puts every parked vote back at its armed rate, focus last, and drops the
     * park state. Only the parked packages need the write — their vote is the
     * only one this service changed — plus whatever [extra] repair the pass
     * had already lined up.
     */
    private fun restore(
        armedNow: Map<String, Int>,
        focus: String?,
        churn: Boolean,
        extra: Collection<String> = emptySet(),
    ) {
        if (churn) noteParkChurn()
        voteSweep(armedNow, focus, parked + extra)
        clearParkState()
    }

    private fun clearParkState() {
        parked.clear()
        lowTicks.clear()
        parkedAtNs.clear()
    }

    /**
     * A park that content undoes within [PARK_MIN_HOLD_NS] was not idleness.
     * Entering the 120 mode makes every system renderer redraw at the new
     * ceiling, and that churn can read as demand and restore the vote about a
     * second later — forever. That loop is why 165 used to be barred from
     * parking outright; backing the offending package off for a while instead
     * keeps the feature for the screens that do idle and costs one wasted park
     * on the ones that never will.
     */
    private fun noteParkChurn() {
        val now = System.nanoTime()
        parked.forEach { pkg ->
            val at = parkedAtNs[pkg] ?: return@forEach
            if (now - at >= PARK_MIN_HOLD_NS) return@forEach
            parkBlockedUntilNs[pkg] = now + PARK_BACKOFF_NS
            Log.i(TAG, "%s un-parked after %.1f s — not idle, park backing off %d s"
                .format(pkg, (now - at) / 1e9, PARK_BACKOFF_NS / 1_000_000_000L))
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
                        // A wake is not park churn: the animation that undoes
                        // the park here is the resume, not content demand.
                        restore(armedNow, screen(armedNow).focus, churn = false)
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
        // An armed set written by a build whose "Arm all" included the
        // framework and SystemUI still names them. Drop those entries and
        // withdraw the votes they left live, before the first pass reads them.
        prefs?.let { p ->
            val unvotable = ArmedStore.dropUnvotable(p)
            if (unvotable.isNotEmpty()) worker.execute {
                synchronized(RateLock) {
                    unvotable.forEach { (pkg, rateId) ->
                        Log.i(TAG, "dropping unvotable $pkg")
                        RateLock.release(pkg, rateId)
                    }
                }
            }
        }
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
        clearParkState()
        // A fresh arm is a new epoch: a park the previous one had backed off
        // is not this one's history either.
        parkBlockedUntilNs.clear()
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

        /** Below this the panel is slow enough to mean "still" — 1 / [PANEL_BUSY_INTERVAL_NS]. */
        private const val PANEL_MOTION_HZ = 20

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
         * A still armed screen idles AT its mode's floor
         * ([RateLock.idleFloorHz]), so the park gate treats the panel climbing
         * this far above the floor as motion. 10 Hz keeps the floor's own
         * wobble below the line — for 165 that is the 65 Hz this was a
         * hard-coded constant for before 144 needed the same treatment.
         */
        private const val FLOOR_MARGIN_HZ = 10

        /**
         * The ceiling a park puts in place of the armed vote, and therefore
         * the one rule for what may park: a vote above it, and nothing else.
         * The 120 mode spans 1-120, so it serves every slower cadence while
         * letting the LTPO ramp reach its 1 Hz floor.
         */
        private const val PARK_CEILING_HZ = 120

        /** How long the last oiface focus answer stands after it stops answering. */
        private const val FOCUS_MEMORY_NS = 5_000_000_000L

        /**
         * A park undone faster than this was the 120 mode's own entry churn
         * reading as demand, not content — back that package off.
         */
        private const val PARK_MIN_HOLD_NS = 3_000_000_000L

        /** How long a package that churned out of a park is left unparked. */
        private const val PARK_BACKOFF_NS = 30_000_000_000L

        /**
         * An armed set this small is re-voted whole every pass: it is the
         * scale the boot list has always run at, and it never cost the pin.
         */
        const val FULL_SWEEP_MAX = 8

        /** Background votes refreshed per repair pass, above [FULL_SWEEP_MAX]. */
        private const val REPAIR_SLICE = 16

        /** Passes between repair slices; every pass in between votes focus only. */
        private const val REPAIR_EVERY_PASSES = 10

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

package tf.arm165

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
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
 * Every pass is ordered around the app on screen, as oiface's game daemon
 * reports it, and ends on it. The server
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
 * game engines that pin their own rate still lose to ours. What may park is a
 * measurement rather than a rule: 144 alone, because from there the switch is
 * clean and the panel ramps to 1 Hz, while from 165 the park lands on a pinned
 * 120, above the 55 that mode idles to by itself.
 * Measured floors on this build: 60, 90 and 120 all reach 1 Hz, 165 stops at
 * 55 (144 estimated at 48, see [RateLock.idleFloorHz]), so 165 is the only
 * mode the park is needed for. Restore is rate starvation: the parked 120 ceiling serves every
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

    /** Game classification per package, and the vendor list it is read against. */
    private val gameCache = HashMap<String, Boolean>()
    private var systemGames: Set<String> = emptySet()
    private var gamesStampNs = 0L

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

    /** Last pass's focus, so a switch INTO an armed app can be noticed. */
    private var lastPassFocus: String? = null

    /** Ticks in the pinned-but-unparked state; paces its diagnostic line. */
    private var pinnedTicks = 0

    /** Consecutive parked ticks spent on the floor of the mode we tried to leave. */
    private var stuckTicks = 0

    /** Consecutive parked ticks with the panel held at the 120 ceiling. */
    private var ceilingTicks = 0

    /**
     * Packages whose park has already been re-asserted once in this park.
     *
     * A still screen gives the vendor no reason to re-evaluate its refresh
     * policy, so a vote that lands on a window nothing is redrawing can sit
     * there without taking effect. One more write, a beat later, is the cheap
     * test of that; a second failure is taken as the answer.
     */
    private val parkRetried = HashSet<String>()

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

    /**
     * Drops every panel-derived claim, so the next decision is made on frames
     * that arrived AFTER the vote changed.
     *
     * Without this the starvation counter carried its pre-park history across
     * the park: a screen held at 144 Hz had hundreds of sub-10 ms pairs
     * banked, and the first tick after the park grace restored the armed rate
     * on that stale evidence, 0.4 s after parking, every time. The park was
     * never actually measured.
     */
    private fun forgetPanel() {
        panelStarveRun = 0
        panelEmaNs = 0L
        panelLastIntervalNs = 0L
        lastFastFrameNs = 0L
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
        parkRetried.retainAll(parked)
        lowTicks.keys.retainAll(armedNow.keys)
        parkedAtNs.keys.retainAll(parked)
        parkBlockedUntilNs.keys.retainAll(armedNow.keys)

        // Who is on screen, and which background votes this pass refreshes.
        // Every vote below is ordered around these two.
        val focus = focus(armedNow)


        // Switching INTO an armed app is the user asking for its rate now: a
        // vote left parked at 120 from the last time it was on screen must not
        // be what greets them. Not park churn — no content undid this one.
        if (focus != null && focus != lastPassFocus && focus in parked) {
            armedNow[focus]?.let { rateId ->
                if (RateLock.arm(focus, rateId)) {
                    parked -= focus
                    lowTicks -= focus
                    parkedAtNs -= focus
                    Log.i(TAG, "$focus back on screen , restored to ${RateLock.hz(rateId)} Hz")
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

        // A park asked the panel for a 120 ceiling. Once the grace is over and
        // frames have arrived since, what the panel is doing says which of
        // three things happened, and they want different answers.
        val parkedAndMeasured = parked.isNotEmpty() && graceOver && phz > 0
        // Above the ceiling it was given: the vote did not take. Not demand,
        // and restoring on it would churn forever, so it backs the package off
        // as well. Worth naming in the log, because it is the vendor refusing
        // a lower vote on a live window rather than anything we can fix.
        val parkIgnored = parkedAndMeasured && phz >= PARK_CEILING_HZ + PIN_MARGIN_HZ
        // Or the other shape of a park that did not take: the panel resting on
        // the floor of the mode it was supposed to have LEFT. One reading of
        // that could be the ramp passing through, so it has to persist.
        val armedFloorHz = focus?.let { armedNow[it] }?.let { RateLock.idleFloorHz(it) } ?: 0
        val onOldFloor = parkedAndMeasured && armedFloorHz > PARK_CEILING_FLOOR_HZ &&
            kotlin.math.abs(phz - armedFloorHz) <= STUCK_MARGIN_HZ
        stuckTicks = if (onOldFloor) stuckTicks + 1 else 0
        val modeUnchanged = stuckTicks >= STUCK_TICKS
        // At the ceiling. This is the one that cost a working park: the panel
        // ENTERS the 120 mode at 120 and ramps down from there, so for the
        // first seconds of every park it looks exactly like content saturating
        // the ceiling, and its 8.3 ms pairs also feed the starvation counter.
        // Neither is evidence of anything: content wanting more than 120
        // cannot push the panel past a 120 vote, so the panel can never show
        // us that demand directly. What it can show is the ceiling being held
        // for longer than any entry ramp takes, which is real, and GPU load
        // and the vendor's fps counter stay as the fast path since they are
        // not gated by the grace.
        val atCeiling = parkedAndMeasured && !parkIgnored && phz >= STARVED_HZ
        ceilingTicks = if (atCeiling) ceilingTicks + 1 else 0
        // Held for longer than any entry ramp takes, so either content really
        // is saturating it or this park is not going to idle. Both end the
        // park; the backoff below stops it being retried in a loop.
        val ceilingSaturated = ceilingTicks >= CEILING_TICKS
        val busy = parkIgnored || ceilingSaturated || gpu >= GPU_ACTIVE || fpsBusy
        // The park did not take, and has not been re-asserted yet: write the
        // 120 once more rather than concluding anything. This is the whole
        // difference between "the vendor refuses a lower vote" and "the vote
        // needed a second nudge on a screen that never redraws".
        if ((parkIgnored || modeUnchanged) && parked.any { it !in parkRetried }) {
            val now = System.nanoTime()
            parked.forEach { pkg ->
                if (parkRetried.add(pkg) && RateLock.arm(pkg, RateLock.RATE_120)) {
                    Log.i(TAG, "%s still at %d Hz after the park. Re-asserting 120."
                        .format(pkg, phz))
                }
            }
            parkGraceUntilNs = now + PARK_GRACE_NS
            stuckTicks = 0
            forgetPanel()
            return RESUME_TICK_MS
        }

        // Re-asserted once and still on the old mode's floor: the vendor is
        // keeping the mode whatever we vote. Say so plainly and stop paying
        // 50 ms ticks for it.
        if (modeUnchanged) {
            Log.i(TAG, ("park changed the vote but not the mode: panel=%d Hz, the %d Hz " +
                "mode's own floor. Restoring the armed rate and backing off.")
                .format(phz, armedFloorHz))
            val now = System.nanoTime()
            parked.forEach { parkBlockedUntilNs[it] = now + PARK_BACKOFF_NS }
            stuckTicks = 0
            restore(armedNow, focus, churn = false, extra = repair)
            return INTERVAL_MS
        }


        if (busy) {
            if (parkIgnored) {
                Log.i(TAG, ("park had no effect: panel=%d Hz, above the %d it was given " +
                    "twice (pair=%.1f ms, gpu=%.2f). Restoring the armed rate and backing off.")
                    .format(phz, PARK_CEILING_HZ, panelLastIntervalNs / 1e6, gpu))
                val now = System.nanoTime()
                parked.forEach { parkBlockedUntilNs[it] = now + PARK_BACKOFF_NS }
            } else if (parked.isNotEmpty()) {
                // starve and fps are in the line so a restore that fires at a
                // still screen names its own trigger: a blip from a system
                // overlay pumps two fast pairs in with gpu at zero, versus
                // real motion, which drags the EMA up with it.
                Log.i(TAG, ("LTPO idle over (panel=%d Hz, gpu=%.2f, starve=%d, " +
                    "pair=%.1f ms, ema=%.0f ms, focus=%s@%d fps). Restoring armed rates")
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
        // This used to test `any { it > RATE_144 }`, which is 165 alone, and
        // 165 was also the one rate the park refused, so the two conditions
        // cancelled out and nothing could ever park: 144 fell to the
        // contentMoving() branch and read its own 48 Hz floor as motion.
        //
        // The vote in effect is the foreground app's, so its floor and its
        // ceiling are the two rates the panel is bounded by. With no focus to
        // go by, take the highest floor in the armed set: over-estimating it
        // only delays a park.
        val heldRate = focus?.let { armedNow[it] }
            ?: armedNow.values.maxByOrNull { RateLock.idleFloorHz(it) }
        val floorHz = heldRate?.let { RateLock.idleFloorHz(it) } ?: 0
        val pinnedHz = heldRate?.let { RateLock.hz(it) } ?: 0
        val floorHidesContent = floorHz >= PANEL_MOTION_HZ

        // A pinned panel can only testify about the content BETWEEN the two
        // rates our own vote sets. At the floor it is resting; at the pinned
        // rate it is showing us our own pin and nothing else.
        //
        // The top end is the one that mattered. A still 165 screen does reach
        // its 55 Hz floor (measured), but any moment the panel is back at 165
        // — the ramp not yet settled, a touch, a notification — both
        // `phz >= floor + margin` and `panelStarveRun >= 2` are true, the
        // latter because 6 ms pairs are well under the 10 ms that counts as
        // starved. So the park was vetoed by the very vote it was trying to
        // replace, and a screen that went quiet while the panel was still
        // high never got a countdown. Neither panel-derived term belongs
        // here; the GPU-idle requirement below is what keeps a game rendering
        // AT the pinned rate from being parked, and it cannot be fed by a
        // vote.
        val panelSaysMoving = floorHidesContent &&
            phz >= floorHz + FLOOR_MARGIN_HZ && phz <= pinnedHz - PIN_MARGIN_HZ
        val gateBusy = if (floorHidesContent) fpsBusy || panelSaysMoving
            else contentMoving() || fpsBusy
        if (gateBusy) {
            lowTicks.clear()
        } else if (gpu in 0f..GPU_IDLE) {
            val now = System.nanoTime()
            parkCandidates(armedNow, focus).forEach { (pkg, rateId) ->
                // What may park is a measurement, not a rule: see
                // RateLock.parkable. 144 gains a ramp to 1 Hz. 165 would land
                // on a pinned 120, above the 55 it idles to unaided, so it
                // rests at 55 instead. 120 already is the park, and 60 and 90
                // reach 1 Hz in their own modes, so there is nothing for a park
                // to win there — they are ceilings the user picked to keep
                // faster content OFF the panel (judder), and raising one to 120
                // would defeat the point of choosing it.
                if (pkg in parked || !RateLock.parkable(rateId)) return@forEach
                // A game never ramps. Every other armed app can afford to be
                // walked down to 120 and back on motion, because a missed
                // frame while it climbs costs nothing anyone can see. A game
                // renders continuously and is the reason this app exists: the
                // rate the user picked for it is the whole point, and a park
                // is a downgrade of exactly that rate — a 144-armed game is
                // measurably NOT idle, the vendor's own counters just cannot
                // always say so (they read 0 for anything the daemon does not
                // track). Deciding by the vendor's game list and the package's
                // own category is the one signal that does not depend on a
                // probe answering.
                if (isGame(pkg)) return@forEach
                if (now < (parkBlockedUntilNs[pkg] ?: 0L)) return@forEach
                val n = (lowTicks[pkg] ?: 0) + 1
                lowTicks[pkg] = n
                if (n >= LOW_TICKS && RateLock.arm(pkg, RateLock.RATE_120)) {
                    // Re-read the clock: park() sleeps between its two writes,
                    // and a grace measured from before that sleep is short by
                    // exactly the step, which is how a 1 s grace turned into
                    // 0.86 s and the restore landed inside the mode's own
                    // entry ramp.
                    val landed = System.nanoTime()
                    parked += pkg
                    parkedAtNs[pkg] = landed
                    // The ceiling just changed under the panel; nothing it did
                    // before this moment describes the new one.
                    forgetPanel()
                    // Parking switches the panel into the 120 mode directly
                    // (no discrete 60/30 hop): it can present at up to 120 for
                    // a beat until the idle ramp takes over inside the mode's
                    // 1-120 range. Those fast frames are the transition, not
                    // motion — ignore them briefly.
                    parkGraceUntilNs = landed + PARK_GRACE_NS
                    Log.i(TAG, "%s parked at 120 (mode floor 1 Hz, from %d Hz floor %d, gpu=%.2f)"
                        .format(pkg, RateLock.hz(rateId), RateLock.idleFloorHz(rateId), gpu))
                    holdTicks = 0
                }
            }
        }

        // The state this whole branch exists to leave: a high vote held, the
        // park not yet taken. One line every few seconds says why, which is
        // the difference between "the countdown is running" and "something
        // keeps vetoing it".
        if (parked.isEmpty() && floorHidesContent) {
            if (++pinnedTicks % HOLD_LOG_PINNED == 1) {
                // Why the countdown is where it is, because "quiet=0/2" on a
                // still screen has three different causes and they are not
                // distinguishable from the outside.
                val blockedFor = ((parkBlockedUntilNs[focus] ?: 0L) - System.nanoTime())
                    .coerceAtLeast(0L) / 1_000_000_000L
                val focusRate = focus?.let { armedNow[it] }
                val why = when {
                    focus == null -> "no focus, so no park candidate among ${armedNow.size} armed"
                    focus != null && isGame(focus) ->
                        "game package, never parked , holding ${focusRate?.let(RateLock::hz) ?: 0} Hz"
                    focusRate != null && !RateLock.parkable(focusRate) ->
                        "${RateLock.hz(focusRate)} Hz does not park on this build, " +
                            "so it rests at its own $floorHz Hz floor"
                    blockedFor > 0L -> "backing off for ${blockedFor}s"
                    gateBusy -> "gate says busy"
                    gpu > GPU_IDLE -> "gpu above idle"
                    else -> "counting"
                }
                Log.i(TAG, ("pinned hold: panel=%d Hz (floor %d, pin %d), gpu=%.2f, " +
                    "fps=%d, gate=%s, quiet=%d/%d, focus=%s, %s")
                    .format(phz, floorHz, pinnedHz, gpu, focusFps,
                        if (gateBusy) "busy" else "quiet",
                        lowTicks[focus] ?: 0, LOW_TICKS,
                        focus?.substringAfterLast('.') ?: "none", why))
            }
        } else {
            pinnedTicks = 0
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
                Log.i(TAG, "parked hold: panel=%d Hz (pair=%.1f ms), gpu=%.2f, ceiling=%d/%d"
                    .format(panelHz(), panelLastIntervalNs / 1e6, gpu,
                        ceilingTicks, CEILING_TICKS))
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
     * The armed package we believe is on screen: the only one whose vote a
     * game engine can overwrite, and the one that must own the last write of
     * every pass.
     *
     * oiface names the game its daemon currently considers foreground, and
     * that is all there is. Nothing else a rootless app can call names the
     * foreground app, so for an app the daemon does not track the answer is
     * "don't know": the last answer stands for [FOCUS_MEMORY_NS] so a
     * momentary null does not reorder the sweep, and after that the pass
     * simply has no focus. Votes still go out, just without a package to end
     * on.
     */
    private fun focus(armedNow: Map<String, Int>): String? {
        Oiface.currentGamePackage()?.takeIf { it in armedNow }?.let { pkg ->
            if (pkg != focusPkg) Log.i(TAG, "focus: $pkg")
            focusPkg = pkg
            focusSeenNs = System.nanoTime()
            return pkg
        }
        val remembered = focusPkg
        if (remembered != null && remembered in armedNow &&
            System.nanoTime() - focusSeenNs < FOCUS_MEMORY_NS
        ) {
            return remembered
        }
        focusPkg = null
        return null
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
        // Never a parked package: voteSweep issues each package's ARMED rate,
        // so including one here would write 165 back over the 120 a park just
        // put in place, leave the parked set still claiming it is parked, and
        // undo the park without anything in the log saying so. The focus is
        // excluded for a different reason: it is voted last, separately.
        val keys = armedNow.keys.filter { it != focus && it !in parked }
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
        if (parked.isNotEmpty()) forgetPanel()
        clearParkState()
    }

    /**
     * Whether [pkg] is a game, by the same three signals the app's own Games
     * filter uses: the vendor's recognized-game list, the manifest category
     * and the legacy game flag. Cached — this runs on the park path, which
     * ticks every second while a high vote is held, and both lookups are IPC.
     *
     * The list is re-read on a TTL rather than once: installing a game, or the
     * vendor deciding an installed one is a game, both happen while the
     * service is running, and a package cached as "not a game" would otherwise
     * stay parkable until the next arm.
     */
    private fun isGame(pkg: String): Boolean {
        val now = System.nanoTime()
        if (now - gamesStampNs >= GAME_LIST_TTL_NS) {
            gamesStampNs = now
            systemGames = RateLock.systemGameList()
            gameCache.clear()
        }
        return gameCache.getOrPut(pkg) {
            if (pkg in systemGames) return@getOrPut true
            try {
                val info = packageManager.getApplicationInfo(pkg, 0)
                @Suppress("DEPRECATION") // still set by older game APKs
                info.category == ApplicationInfo.CATEGORY_GAME ||
                    info.flags and ApplicationInfo.FLAG_IS_GAME != 0
            } catch (t: Throwable) {
                false // uninstalled mid-pass, or a package we cannot see
            }
        }
    }

    private fun clearParkState() {
        ceilingTicks = 0
        stuckTicks = 0
        parked.clear()
        lowTicks.clear()
        parkedAtNs.clear()
        parkRetried.clear()
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
            Log.i(TAG, "%s un-parked after %.1f s , not idle, park backing off %d s"
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
                            Log.i(TAG, "screen on , restoring ${parked.size} parked vote(s)")
                        }
                        // A wake is not park churn: the animation that undoes
                        // the park here is the resume, not content demand.
                        restore(armedNow, focus(armedNow), churn = false)
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

        /** How long a game classification is trusted before it is re-read. */
        private const val GAME_LIST_TTL_NS = 300_000_000_000L // 5 min
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

        /** Pinned-hold diagnostic cadence: 5 pinned ticks × 1 s = one line per 5 s. */
        private const val HOLD_LOG_PINNED = 5

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
        /**
         * Parked ticks at the ceiling before the park is given up on: 3 s,
         * comfortably longer than the 120 mode's entry ramp, so a panel that
         * was going to idle has already started.
         */
        private const val CEILING_TICKS = 60

        /**
         * A still armed screen idles AT its mode's floor
         * ([RateLock.idleFloorHz]), so the park gate treats the panel climbing
         * this far above the floor as motion. 10 Hz keeps the floor's own
         * wobble below the line — for 165 that is the 65 Hz this was a
         * hard-coded constant for before 144 needed the same treatment.
         */
        private const val FLOOR_MARGIN_HZ = 10

        /**
         * How close to the pinned rate still counts as "this is our own pin,
         * not the content". The panel wobbles a Hz or two around a held rate,
         * so the margin only has to cover that.
         */
        private const val PIN_MARGIN_HZ = 8

        /**
         * The ceiling a park puts in place of the armed vote, and therefore
         * the one rule for what may park: a vote above it, and nothing else.
         * The 120 mode spans 1-120, so it serves every slower cadence while
         * letting the LTPO ramp reach its 1 Hz floor.
         */
        private const val PARK_CEILING_HZ = 120

        /** How long the last oiface focus answer stands after it stops answering. */
        private const val FOCUS_MEMORY_NS = 5_000_000_000L

        /** A mode whose floor is this low is the one the park is aiming for. */
        private const val PARK_CEILING_FLOOR_HZ = 8

        /** How close to the old mode's floor counts as still being on it. */
        private const val STUCK_MARGIN_HZ = 6

        /** Parked ticks on that floor before the mode is called unchanged: ~1 s. */
        private const val STUCK_TICKS = 20

        /**
         * A park undone faster than this was the 120 mode's own entry churn
         * reading as demand, not content. Back that package off.
         */
        private const val PARK_MIN_HOLD_NS = 3_000_000_000L

        /** How long a package that churned out of a park is left unparked. */
        private const val PARK_BACKOFF_NS = 30_000_000_000L

        /**
         * An armed set this small is re-voted whole every pass: it is the
         * scale the boot list has always run at, and it never cost the pin.
         */
        private const val FULL_SWEEP_MAX = 8

        /** Background votes refreshed per repair pass, above [FULL_SWEEP_MAX]. */
        private const val REPAIR_SLICE = 16

        /** Passes between repair slices; every pass in between votes focus only. */
        private const val REPAIR_EVERY_PASSES = 10

        /**
         * How long after a park the panel is not asked about itself.
         *
         * It covers two things: the handover frames as the mode changes, and
         * the EMA rebuilding from scratch now that a park clears it. GPU load
         * and the vendor's fps counter are not gated by this, so real content
         * arriving during the grace still restores the armed rate at once.
         */
        private const val PARK_GRACE_NS = 1_000_000_000L

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

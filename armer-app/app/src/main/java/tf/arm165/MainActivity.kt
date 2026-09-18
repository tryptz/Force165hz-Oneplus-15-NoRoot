package tf.arm165

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

class MainActivity : ShellActivity() {

    private enum class Filter { ALL, ARMED, GAMES, USER, SYSTEM }

    private var all: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()

    /** Package to vendor rateId, mirroring what [ArmedStore] has on disk. */
    private val armed = LinkedHashMap<String, Int>()

    /** The rate a plain row tap arms at, picked in the hero's selector. */
    private var activeRate = RateLock.DEFAULT_RATE

    private var filter = Filter.ALL

    private lateinit var armedCount: TextView
    private lateinit var armedSub: TextView
    private lateinit var armedBar: ProgressBar
    private lateinit var rateSegments: LinearLayout
    private lateinit var watchdogDot: View
    private lateinit var watchdogLabel: TextView
    private lateinit var chips: List<Pair<TextView, Filter>>
    private var segments: List<Pair<TextView, Int>> = emptyList()
    private lateinit var adapter: AppRowAdapter

    override val layoutRes = R.layout.activity_main
    override val actionIds = listOf(R.id.btn_rearm, R.id.btn_arm_all, R.id.btn_clear)

    // ------------------------------------------------------------------ setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No-op after the first process start; makes the Settings log view work.
        LogRing // touch so the object is initialized early
        // An armed set written by a build whose "Arm all" swept in the
        // framework and SystemUI still names them, and the watchdog would keep
        // voting for packages whose windows sit next to every app's. Drop them
        // and withdraw the votes they left live. Idempotent — the service does
        // the same on its own start, and a clean set gets nothing back.
        ArmedStore.dropUnvotable(prefs).takeIf { it.isNotEmpty() }?.let { unvotable ->
            worker.execute {
                synchronized(RateLock) {
                    unvotable.forEach { (pkg, rateId) -> RateLock.release(pkg, rateId) }
                }
            }
        }
        armed.putAll(ArmedStore.read(prefs))
        activeRate = prefs.getInt(ArmedStore.KEY_RATE, RateLock.DEFAULT_RATE)
            .takeIf { RateLock.isKnown(it) } ?: RateLock.DEFAULT_RATE

        armedCount = findViewById(R.id.armed_count)
        armedSub = findViewById(R.id.armed_sub)
        armedBar = findViewById(R.id.armed_bar)
        rateSegments = findViewById(R.id.rate_segments)
        watchdogDot = findViewById(R.id.watchdog_dot)
        watchdogLabel = findViewById(R.id.watchdog_label)
        chips = listOf(
            findViewById<TextView>(R.id.chip_all) to Filter.ALL,
            findViewById<TextView>(R.id.chip_armed) to Filter.ARMED,
            findViewById<TextView>(R.id.chip_games) to Filter.GAMES,
            findViewById<TextView>(R.id.chip_user) to Filter.USER,
            findViewById<TextView>(R.id.chip_system) to Filter.SYSTEM,
        )
        chips.forEach { (chip, value) ->
            chip.setOnClickListener {
                if (filter == value) return@setOnClickListener
                filter = value
                syncChips(chips, filter)
                applyFilter()
                list.setSelection(0)
            }
        }
        syncChips(chips, filter)
        wireRates()
        wireActions()

        adapter = AppRowAdapter(
            activity = this,
            items = { shown },
            armedRate = { armed[it] },
            fallbackRate = { activeRate },
            onTap = ::onRowTapped,
            onDetails = ::showRatePicker,
        )
        list.adapter = adapter

        refreshStatus()
        updateState()

        reArmSavedQuietly() // covers a reboot the boot receiver missed
        AppCatalog.loadAsync(packageManager, packageName) { entries ->
            if (isFinishing || isDestroyed) return@loadAsync
            all = entries
            loading = false
            applyFilter()
            refreshStatus()
        }
        ArmWatchService.start(this) // watchdog: beat games that pin their own frame rate
        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        // The games page may have changed the armed set — re-read it.
        val latest = ArmedStore.read(prefs)
        if (latest != armed) {
            armed.clear()
            armed.putAll(latest)
            applyFilter()
            refreshStatus()
        }
    }

    // --------------------------------------------------------------- controls

    /** Builds the segmented rate selector so it always matches [RateLock.RATES]. */
    private fun wireRates() {
        segments = RateLock.RATES.map { (rateId, hz) ->
            val segment = TextView(this, null, 0, R.style.Segment).apply {
                // A style can't carry layout params onto a view built in code.
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                text = hz.toString()
                contentDescription = getString(R.string.rate_hz, hz)
                setOnClickListener { selectRate(rateId) }
            }
            rateSegments.addView(segment)
            segment to rateId
        }
        syncChips(segments, activeRate)
    }

    private fun selectRate(rateId: Int) {
        if (activeRate == rateId) return
        activeRate = rateId
        prefs.edit().putInt(ArmedStore.KEY_RATE, rateId).apply()
        syncChips(segments, activeRate)
        adapter.notifyDataSetChanged() // unarmed rows describe the rate they would use
    }

    private fun wireActions() {
        findViewById<View>(R.id.btn_rearm).setOnClickListener { reArmSaved() }
        findViewById<View>(R.id.btn_arm_all).setOnClickListener { armAll() }
        findViewById<View>(R.id.btn_clear).setOnClickListener { clearAll() }
        findViewById<View>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btn_coffee).setOnClickListener {
            open(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.coffee_url))))
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: ActivityNotFoundException) {
            snack(getString(R.string.no_browser))
        }
    }

    // ------------------------------------------------------------------ state

    override fun onQueryChanged() = applyFilter()

    private fun applyFilter() {
        shown = all.filter { entry ->
            (query.isEmpty() || entry.key.contains(query)) && when (filter) {
                Filter.ALL -> true
                Filter.ARMED -> entry.pkg in armed
                Filter.GAMES -> entry.game
                Filter.USER -> !entry.system
                Filter.SYSTEM -> entry.system
            }
        }
        adapter.notifyDataSetChanged()
        updateState()
    }

    private fun updateState() = renderState(
        empty = shown.isEmpty(),
        emptyTitle = R.string.empty_title,
        emptyBody =
            if (filter == Filter.ARMED && query.isEmpty()) R.string.empty_armed_body
            else R.string.empty_body,
    )

    private fun refreshStatus() {
        val count = armed.size
        armedCount.text = count.toString()
        armedSub.text = when {
            loading -> getString(R.string.hero_loading)
            count == 0 -> getString(R.string.hero_of, all.size)
            else -> rateBreakdown()
        }
        armedBar.max = maxOf(all.size, 1)
        armedBar.setProgress(if (loading) 0 else count, true)

        val live = count > 0
        watchdogLabel.setText(if (live) R.string.watchdog_live else R.string.watchdog_idle)
        watchdogDot.backgroundTintList = ColorStateList.valueOf(
            getColor(if (live) R.color.accent else R.color.text_disabled)
        )
    }

    /** e.g. "165 Hz × 8  ·  120 Hz × 4", fastest first, skipping unused rates. */
    private fun rateBreakdown(): String =
        RateLock.RATES.asReversed().mapNotNull { (rateId, hz) ->
            val n = armed.count { it.value == rateId }
            if (n == 0) null else getString(R.string.rate_breakdown_part, hz, n)
        }.joinToString("  ·  ")

    /** Call after [armed] changes so the hero, the rows and the Armed filter agree again. */
    private fun onArmedChanged() {
        ArmedStore.write(prefs, armed)
        // Nothing left to hold: a watchdog that keeps ticking every few seconds
        // would only spend battery and show a notification about no apps.
        if (armed.isEmpty()) ArmWatchService.stopIfIdle(this)
        if (isFinishing || isDestroyed) return
        refreshStatus()
        if (filter == Filter.ARMED) applyFilter() else { adapter.notifyDataSetChanged(); updateState() }
    }

    // ---------------------------------------------------------------- actions

    private fun onRowTapped(entry: AppEntry, toggle: RateSwitch) {
        if (rejectWhileBusy()) return
        when {
            entry.pkg in armed -> disarm(entry, toggle)
            prefs.getBoolean(KEY_WARNED, false) -> arm(entry, activeRate, toggle)
            // behind the dialog the row repaints itself, so nothing to animate
            else -> confirmFirstTime { arm(entry, activeRate, null) }
        }
    }

    private fun arm(entry: AppEntry, rateId: Int, toggle: RateSwitch?) {
        if (rejectWhileBusy()) return
        toggle?.setChecked(true, animate = true)
        if (RateLock.arm(entry.pkg, rateId)) {
            armed[entry.pkg] = rateId
            ArmWatchService.start(this)
        } else {
            snack(getString(R.string.arm_failed, entry.label))
        }
        onArmedChanged()
    }

    private fun disarm(entry: AppEntry, toggle: RateSwitch?) {
        if (rejectWhileBusy()) return
        toggle?.setChecked(false, animate = true)
        val rateId = armed.remove(entry.pkg) ?: return
        // Drop it from the stored set before touching the vendor: the watchdog
        // reads that set inside the same lock the release takes, so a tick can
        // no longer land in between and pin the app straight back.
        onArmedChanged()
        worker.execute {
            val released = synchronized(RateLock) { RateLock.release(entry.pkg, rateId) }
            if (!released) ui { snack(getString(R.string.disarm_failed, entry.label)) }
        }
    }

    /** Moves one app to [rateId]; the new vote replaces whatever it was pinned at. */
    private fun setRate(entry: AppEntry, rateId: Int) {
        if (rejectWhileBusy()) return
        val current = armed[entry.pkg]
        if (current == rateId) return
        var stale: Int? = null
        if (RateLock.arm(entry.pkg, rateId)) {
            armed[entry.pkg] = rateId
            ArmWatchService.start(this)
            snack(getString(R.string.rate_set, entry.label, RateLock.hz(rateId)))
        } else {
            // The move failed, so the row goes back to unarmed — but any vote it
            // already had is still live, and only a release takes that down.
            armed.remove(entry.pkg)
            stale = current
            snack(getString(R.string.arm_failed, entry.label))
        }
        onArmedChanged()
        stale?.let { rate -> worker.execute { synchronized(RateLock) { RateLock.release(entry.pkg, rate) } } }
    }

    private fun showRatePicker(entry: AppEntry) {
        if (rejectWhileBusy()) return
        val current = armed[entry.pkg]
        val labels = RateLock.RATES.map { getString(R.string.rate_hz, it.second) }.toTypedArray()
        val checked = RateLock.RATES.indexOfFirst { it.first == (current ?: activeRate) }

        val builder = AlertDialog.Builder(this)
            .setTitle(entry.label)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                confirmFirstTime { setRate(entry, RateLock.RATES[which].first) }
            }
            .setNegativeButton(R.string.risk_no, null)
        if (current != null) {
            builder.setNeutralButton(R.string.rate_picker_disarm) { _, _ -> disarm(entry, null) }
        }
        builder.show()
    }

    private fun reArmSaved() {
        val saved = armed.toMap()
        if (saved.isEmpty()) {
            snack(getString(R.string.nothing_saved))
            return
        }
        runBusy(getString(R.string.rearming)) {
            // Foreground last, like every other sweep — see RateLock.armEach.
            val ok = synchronized(RateLock) {
                RateLock.armEach(saved, Oiface.currentGamePackage()).size
            }
            ui { snack(getString(R.string.rearmed, ok, saved.size)) }
        }
    }

    private fun armAll() {
        if (all.isEmpty()) return
        confirmFirstTime {
            val rateId = activeRate
            // AppCatalog already keeps RateLock.NEVER_ARM out of the list;
            // repeated here because this is the sweep that used to carry them.
            val targets = all.map { it.pkg }.filter { it !in RateLock.NEVER_ARM }
            runBusy(getString(R.string.arming_all, targets.size, RateLock.hz(rateId))) {
                // One vote per app, whatever it was pinned at before: the call
                // is a set, so re-issuing an id an app already holds is free and
                // repairs a vote the vendor dropped. The app on screen is voted
                // last (see RateLock.armEach) — during this sweep that is the
                // armer itself, which is never armed, so the watchdog's next
                // pass is what lands the pin on whatever you open next.
                val done = synchronized(RateLock) {
                    RateLock.armEach(targets.associateWith { rateId }, Oiface.currentGamePackage())
                }
                main.post {
                    done.forEach { armed[it] = rateId }
                    onArmedChanged()
                    ArmWatchService.start(this)
                    snack(getString(R.string.armed_all, done.size, targets.size, RateLock.hz(rateId)))
                }
            }
        }
    }

    private fun clearAll() {
        if (rejectWhileBusy()) return
        val saved = armed.toMap()
        if (saved.isEmpty()) {
            snack(getString(R.string.nothing_saved))
            return
        }
        // Emptied up front for the same reason as a single disarm: nothing the
        // watchdog can read may still name an app this sweep is releasing.
        armed.clear()
        onArmedChanged()
        runBusy(getString(R.string.clearing)) {
            val stuck = synchronized(RateLock) {
                saved.count { (pkg, rateId) -> !RateLock.release(pkg, rateId) }
            }
            ui {
                snack(
                    if (stuck == 0) resources.getQuantityString(R.plurals.cleared, saved.size, saved.size)
                    else resources.getQuantityString(R.plurals.clear_failed, stuck, stuck)
                )
            }
        }
    }

    private fun reArmSavedQuietly() {
        // Boot catch-up ONLY: when the watchdog service is alive in this
        // process it already holds the votes and — critically — its own park
        // state. Re-arming behind its back would land 165 votes right over
        // a parked 120 set and un-park everything, so a mere cold start of
        // the activity (task discard, debug reinstall, user revisit) must
        // not touch the votes.
        if (ArmWatchService.running) return
        val saved = armed.toMap()
        if (saved.isEmpty()) return
        worker.execute {
            synchronized(RateLock) { RateLock.armEach(saved, Oiface.currentGamePackage()) }
        }
    }

    private fun requestNotificationPermission() {
        // The watchdog runs as a foreground service; without this its silent
        // notification never shows and Android may not keep the service alive.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    private companion object {
        const val REQ_NOTIFICATIONS = 165
    }
}

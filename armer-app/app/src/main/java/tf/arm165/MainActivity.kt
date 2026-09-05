package tf.arm165

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
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
    private lateinit var fpsChip: TextView
    private lateinit var chips: List<Pair<TextView, Filter>>
    private var segments: List<Pair<TextView, Int>> = emptyList()
    private lateinit var adapter: AppRowAdapter

    override val layoutRes = R.layout.activity_main
    override val actionIds = listOf(R.id.btn_rearm, R.id.btn_arm_all, R.id.btn_clear)

    // ------------------------------------------------------------------ setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        armed.putAll(ArmedStore.read(prefs))
        activeRate = prefs.getInt(ArmedStore.KEY_RATE, RateLock.DEFAULT_RATE)
            .takeIf { RateLock.isKnown(it) } ?: RateLock.DEFAULT_RATE

        armedCount = findViewById(R.id.armed_count)
        armedSub = findViewById(R.id.armed_sub)
        armedBar = findViewById(R.id.armed_bar)
        rateSegments = findViewById(R.id.rate_segments)
        watchdogDot = findViewById(R.id.watchdog_dot)
        watchdogLabel = findViewById(R.id.watchdog_label)
        fpsChip = findViewById(R.id.chip_fps)
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
        // The overlay permission is granted on a settings screen, and the games
        // page may have changed the armed set — re-read both.
        syncFpsChip()
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
        findViewById<View>(R.id.btn_coffee).setOnClickListener {
            open(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.coffee_url))))
        }
        fpsChip.setOnClickListener { toggleFpsOverlay() }
        fpsChip.setOnLongClickListener {
            if (overlayEnabled()) showOverlayPositionDialog() else toggleFpsOverlay()
            true
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: ActivityNotFoundException) {
            snack(getString(R.string.no_browser))
        }
    }

    // ------------------------------------------------------------ fps overlay

    private fun overlayEnabled(): Boolean =
        prefs.getBoolean(ArmWatchService.KEY_OVERLAY, false) && Settings.canDrawOverlays(this)

    private fun syncFpsChip() {
        fpsChip.isSelected = overlayEnabled()
    }

    private fun toggleFpsOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            // "Draw over other apps" is a settings screen, not a runtime permission.
            AlertDialog.Builder(this)
                .setTitle(R.string.fps_chip)
                .setMessage(R.string.fps_needs_permission)
                .setPositiveButton(R.string.fps_grant) { _, _ ->
                    open(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"),
                        )
                    )
                }
                .setNegativeButton(R.string.risk_no, null)
                .show()
            return
        }
        val on = !prefs.getBoolean(ArmWatchService.KEY_OVERLAY, false)
        prefs.edit().putBoolean(ArmWatchService.KEY_OVERLAY, on).apply()
        syncFpsChip()
        ArmWatchService.sync(this)
        snack(getString(if (on) R.string.fps_on else R.string.fps_off))
    }

    /**
     * No API reports where the status bar's own wifi/battery icons sit, so the
     * offset from the right edge is the user's to set. Updates live.
     */
    private fun showOverlayPositionDialog() {
        val current = prefs.getInt(ArmWatchService.KEY_OVERLAY_X, ArmWatchService.DEFAULT_RIGHT_OFFSET_DP)
        val value = TextView(this).apply {
            setPadding(dp(24), dp(16), dp(24), dp(2))
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            text = getString(R.string.fps_position_value, current)
        }
        val bar = SeekBar(this).apply {
            max = MAX_OVERLAY_OFFSET_DP
            progress = current.coerceIn(0, MAX_OVERLAY_OFFSET_DP)
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    value.text = getString(R.string.fps_position_value, p)
                    prefs.edit().putInt(ArmWatchService.KEY_OVERLAY_X, p).apply()
                    ArmWatchService.sync(this@MainActivity)
                }

                override fun onStartTrackingTouch(sb: SeekBar) = Unit
                override fun onStopTrackingTouch(sb: SeekBar) = Unit
            })
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.fps_position_title)
            .setMessage(R.string.fps_position_body)
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(value)
                addView(bar)
            })
            .setPositiveButton(R.string.done, null)
            .show()
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
        // Re-issuing the id an app is pinned at is what clears it, so replay
        // that app's own rate rather than whatever the selector says.
        armed[entry.pkg]?.let { RateLock.arm(entry.pkg, it) }
        armed.remove(entry.pkg)
        onArmedChanged()
    }

    /** Moves one app to [rateId], dropping whatever it was pinned at first. */
    private fun setRate(entry: AppEntry, rateId: Int) {
        if (rejectWhileBusy()) return
        val current = armed[entry.pkg]
        if (current == rateId) return
        if (current != null) RateLock.arm(entry.pkg, current)
        if (RateLock.arm(entry.pkg, rateId)) {
            armed[entry.pkg] = rateId
            ArmWatchService.start(this)
            snack(getString(R.string.rate_set, entry.label, RateLock.hz(rateId)))
        } else {
            armed.remove(entry.pkg)
            snack(getString(R.string.arm_failed, entry.label))
        }
        onArmedChanged()
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
            var ok = 0
            saved.forEach { (pkg, rateId) -> if (RateLock.arm(pkg, rateId)) ok++ }
            ui { snack(getString(R.string.rearmed, ok, saved.size)) }
        }
    }

    private fun armAll() {
        if (all.isEmpty()) return
        confirmFirstTime {
            val rateId = activeRate
            val targets = all.map { it.pkg }
            val existing = armed.toMap()
            runBusy(getString(R.string.arming_all, targets.size, RateLock.hz(rateId))) {
                val done = ArrayList<String>(targets.size)
                targets.forEach { pkg ->
                    val current = existing[pkg]
                    when {
                        // already pinned here: re-issuing would only toggle it off
                        current == rateId -> done.add(pkg)
                        else -> {
                            if (current != null) RateLock.arm(pkg, current) // drop the old pin
                            if (RateLock.arm(pkg, rateId)) done.add(pkg)
                        }
                    }
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
        val saved = armed.toMap()
        if (saved.isEmpty()) {
            snack(getString(R.string.nothing_saved))
            return
        }
        runBusy(getString(R.string.clearing)) {
            saved.forEach { (pkg, rateId) -> RateLock.arm(pkg, rateId) }
            main.post {
                armed.clear()
                onArmedChanged()
                snack(resources.getQuantityString(R.plurals.cleared, saved.size, saved.size))
            }
        }
    }

    private fun reArmSavedQuietly() {
        val saved = armed.toMap()
        if (saved.isEmpty()) return
        worker.execute { saved.forEach { (pkg, rateId) -> RateLock.arm(pkg, rateId) } }
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
        const val MAX_OVERLAY_OFFSET_DP = 260
    }
}

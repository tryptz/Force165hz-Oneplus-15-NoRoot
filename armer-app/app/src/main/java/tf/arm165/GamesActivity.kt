package tf.arm165

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.TextView

/**
 * 3D Games page. Boosting a game arms it at 165 Hz with the full treatment:
 * the persistent per-app override (survives foregrounding), the transient vote
 * the watchdog keeps re-issuing, and — when the vendor game service is
 * reachable — registration in OxygenOS's game list so HyperRendering applies.
 *
 * The armed set is shared with the main screen through [ArmedStore]; the extra
 * override/oiface work is layered on top when a game is boosted here.
 */
class GamesActivity : ShellActivity() {

    private enum class Filter { GAMES, BOOSTED, ALL }

    private var all: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    private val armed = LinkedHashMap<String, Int>()
    private val userGames = LinkedHashSet<String>()

    private var filter = Filter.GAMES
    private var oifaceReady = false

    private lateinit var boostCount: TextView
    private lateinit var boostSub: TextView
    private lateinit var oifaceDot: View
    private lateinit var oifaceLabel: TextView
    private lateinit var oifaceSub: TextView
    private lateinit var boostState: TextView
    private lateinit var boostToggle: RateSwitch
    private lateinit var grantBox: View
    private lateinit var chips: List<Pair<TextView, Filter>>
    private lateinit var adapter: AppRowAdapter

    override val layoutRes = R.layout.activity_games
    override val actionIds = listOf(R.id.btn_boost_all, R.id.btn_clear)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        armed.putAll(ArmedStore.read(prefs))
        userGames.addAll(prefs.getStringSet(KEY_USER_GAMES, emptySet()).orEmpty())

        boostCount = findViewById(R.id.boost_count)
        boostSub = findViewById(R.id.boost_sub)
        oifaceDot = findViewById(R.id.oiface_dot)
        oifaceLabel = findViewById(R.id.oiface_label)
        oifaceSub = findViewById(R.id.oiface_sub)
        boostState = findViewById(R.id.boost_state)
        boostToggle = findViewById(R.id.boost_toggle)
        grantBox = findViewById(R.id.grant_box)
        chips = listOf(
            findViewById<TextView>(R.id.chip_games) to Filter.GAMES,
            findViewById<TextView>(R.id.chip_boosted) to Filter.BOOSTED,
            findViewById<TextView>(R.id.chip_all) to Filter.ALL,
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

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_boost_all).setOnClickListener { boostAll() }
        findViewById<View>(R.id.btn_clear).setOnClickListener { clearAll() }
        boostToggle.setOnClickListener { onBoostSwitch() }
        findViewById<View>(R.id.grant_cmd).setOnClickListener { copyGrantCommand() }

        adapter = AppRowAdapter(
            activity = this,
            items = { shown },
            armedRate = { armed[it] },
            fallbackRate = { RateLock.RATE_165 },
            onTap = ::onRowTapped,
            onDetails = ::showGameSheet,
        )
        list.adapter = adapter

        refreshStatus()
        updateState()
        loadApps()

        // oiface reachability needs a binder round-trip; keep it off the UI thread.
        worker.execute {
            val ok = Oiface.isReachable()
            ui { oifaceReady = ok; refreshOiface() }
        }
    }

    override fun onResume() {
        super.onResume()
        val latest = ArmedStore.read(prefs)
        if (latest != armed) {
            armed.clear()
            armed.putAll(latest)
            applyFilter()
            refreshStatus()
        }
        refreshBoostSwitch()
    }

    // ------------------------------------------------------------------ state

    private fun loadApps() {
        AppCatalog.loadAsync(packageManager, packageName, userGames) { entries ->
            if (isFinishing || isDestroyed) return@loadAsync
            all = entries
            loading = false
            applyFilter()
            refreshStatus()
        }
    }

    override fun onQueryChanged() = applyFilter()

    private fun applyFilter() {
        shown = all.filter { entry ->
            (query.isEmpty() || entry.key.contains(query)) && when (filter) {
                Filter.GAMES -> entry.game
                Filter.BOOSTED -> entry.pkg in armed
                Filter.ALL -> true
            }
        }
        adapter.notifyDataSetChanged()
        updateState()
    }

    private fun updateState() = renderState(
        empty = shown.isEmpty(),
        emptyTitle = R.string.games_empty_title,
        emptyBody = when {
            filter == Filter.BOOSTED && query.isEmpty() -> R.string.games_empty_armed_body
            filter == Filter.GAMES && query.isEmpty() -> R.string.games_empty_body
            else -> R.string.empty_body
        },
    )

    private fun refreshStatus() {
        val games = all.asSequence().filter { it.game }.map { it.pkg }.toSet()
        boostCount.text = armed.keys.count { it in games }.toString()
        boostSub.text =
            if (loading) getString(R.string.games_hero_loading)
            else getString(R.string.games_hero_of, games.size)
    }

    private fun refreshOiface() {
        oifaceLabel.setText(if (oifaceReady) R.string.oiface_active else R.string.oiface_unsupported)
        oifaceSub.setText(if (oifaceReady) R.string.oiface_active_sub else R.string.oiface_unsupported_sub)
        oifaceDot.backgroundTintList = ColorStateList.valueOf(
            getColor(if (oifaceReady) R.color.accent else R.color.text_disabled)
        )
    }

    private fun refreshBoostSwitch() {
        val canWrite = SecureSettings.canWrite(this)
        grantBox.visibility = if (canWrite) View.GONE else View.VISIBLE
        val on = SecureSettings.getGlobalInt(this, SecureSettings.KEY_EXTREME_REFRESH, 0) == 1
        boostToggle.setChecked(canWrite && on, animate = false)
        boostToggle.alpha = if (canWrite) 1f else 0.4f
        boostState.setText(
            when {
                !canWrite -> R.string.boost_locked
                on -> R.string.boost_on
                else -> R.string.boost_off
            }
        )
    }

    private fun onArmedChanged() {
        ArmedStore.write(prefs, armed)
        if (isFinishing || isDestroyed) return
        refreshStatus()
        if (filter == Filter.BOOSTED) applyFilter() else { adapter.notifyDataSetChanged(); updateState() }
    }

    // ---------------------------------------------------------------- actions

    private fun onRowTapped(entry: AppEntry, toggle: RateSwitch) {
        if (rejectWhileBusy()) return
        if (entry.pkg in armed) unboost(entry, toggle) else confirmFirstTime { boost(entry, toggle) }
    }

    /** Full 165 Hz treatment: persistent override + vote + oiface registration. */
    private fun boost(entry: AppEntry, toggle: RateSwitch?) {
        toggle?.setChecked(true, animate = true)
        RateLock.setAppOverride(entry.pkg, RateLock.RATE_165)
        if (RateLock.arm(entry.pkg, RateLock.RATE_165)) {
            armed[entry.pkg] = RateLock.RATE_165
            Oiface.registerGame(entry.pkg)
            ArmWatchService.start(this)
            snack(getString(R.string.boosted_one, entry.label))
        } else {
            RateLock.clearAppOverride(entry.pkg)
            snack(getString(R.string.arm_failed, entry.label))
        }
        onArmedChanged()
    }

    private fun unboost(entry: AppEntry, toggle: RateSwitch?) {
        toggle?.setChecked(false, animate = true)
        armed[entry.pkg]?.let { RateLock.arm(entry.pkg, it) } // re-issue clears the vote
        RateLock.clearAppOverride(entry.pkg)
        Oiface.unregisterGame(entry.pkg)
        armed.remove(entry.pkg)
        snack(getString(R.string.unboosted_one, entry.label))
        onArmedChanged()
    }

    private fun boostAll() {
        val targets = all.filter { it.game && it.pkg !in armed }
        if (targets.isEmpty()) {
            snack(getString(R.string.nothing_boosted))
            return
        }
        confirmFirstTime {
            val gameTotal = all.count { it.game }
            runBusy(getString(R.string.boost_hold, targets.size)) {
                val done = ArrayList<String>(targets.size)
                targets.forEach { entry ->
                    RateLock.setAppOverride(entry.pkg, RateLock.RATE_165)
                    if (RateLock.arm(entry.pkg, RateLock.RATE_165)) {
                        Oiface.registerGame(entry.pkg)
                        done.add(entry.pkg)
                    } else {
                        RateLock.clearAppOverride(entry.pkg)
                    }
                }
                main.post {
                    done.forEach { armed[it] = RateLock.RATE_165 }
                    onArmedChanged()
                    ArmWatchService.start(this)
                    snack(getString(R.string.boosted_all, done.size, gameTotal))
                }
            }
        }
    }

    private fun clearAll() {
        val saved = armed.toMap()
        if (saved.isEmpty()) {
            snack(getString(R.string.nothing_boosted))
            return
        }
        runBusy(getString(R.string.clearing)) {
            saved.forEach { (pkg, rateId) ->
                RateLock.arm(pkg, rateId)
                RateLock.clearAppOverride(pkg)
                Oiface.unregisterGame(pkg)
            }
            main.post {
                armed.clear()
                onArmedChanged()
                snack(resources.getQuantityString(R.plurals.cleared, saved.size, saved.size))
            }
        }
    }

    /** Long-press / badge tap: mark an app as a game, or disarm it. */
    private fun showGameSheet(entry: AppEntry) {
        if (rejectWhileBusy()) return
        val marking = if (entry.pkg in userGames) R.string.unmark_game else R.string.mark_game
        val disarm = getString(R.string.rate_picker_disarm)
        val items = buildList {
            add(getString(marking))
            if (entry.pkg in armed) add(disarm)
        }
        AlertDialog.Builder(this)
            .setTitle(entry.label)
            .setItems(items.toTypedArray()) { _, which ->
                if (items[which] == disarm) unboost(entry, null)
                else setUserGame(entry, marking == R.string.mark_game)
            }
            .setNegativeButton(R.string.risk_no, null)
            .show()
    }

    private fun setUserGame(entry: AppEntry, isGame: Boolean) {
        if (isGame) userGames.add(entry.pkg) else userGames.remove(entry.pkg)
        prefs.edit().putStringSet(KEY_USER_GAMES, HashSet(userGames)).apply()
        snack(getString(if (isGame) R.string.marked_game else R.string.unmarked_game, entry.label))
        loadApps() // re-resolve so the game flag updates
    }

    private fun onBoostSwitch() {
        if (!SecureSettings.canWrite(this)) {
            boostToggle.setChecked(false, animate = false)
            snack(getString(R.string.boost_locked))
            return
        }
        val turnOn = !boostToggle.isChecked
        boostToggle.setChecked(turnOn, animate = true)
        SecureSettings.putGlobalInt(this, SecureSettings.KEY_EXTREME_REFRESH, if (turnOn) 1 else 0)
        refreshBoostSwitch()
    }

    private fun copyGrantCommand() {
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("adb", getString(R.string.boost_grant_cmd)))
        snack(getString(R.string.copied))
    }

    private companion object {
        const val KEY_USER_GAMES = "user_games"
    }
}

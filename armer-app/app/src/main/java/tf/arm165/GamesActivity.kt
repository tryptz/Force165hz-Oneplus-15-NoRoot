package tf.arm165

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * 3D Games page. Boosting a game arms it at 165 Hz with the full treatment:
 * the persistent per-app override (survives foregrounding), the transient vote
 * the watchdog keeps re-issuing, and — when the vendor game service is
 * reachable — registration in OxygenOS's game list so HyperRendering applies.
 *
 * The armed set is shared with the main screen through [ArmedStore]; the extra
 * override/oiface work is layered on top when a game is boosted here.
 */
class GamesActivity : Activity() {

    private enum class Filter { GAMES, BOOSTED, ALL }

    private lateinit var prefs: SharedPreferences

    private var all: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    private val armed = LinkedHashMap<String, Int>()
    private val userGames = LinkedHashSet<String>()

    private var filter = Filter.GAMES
    private var query = ""
    private var loading = true
    private var busy = false
    private var dividerShown = false
    private var oifaceReady = false

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var root: FrameLayout
    private lateinit var header: View
    private lateinit var divider: View
    private lateinit var list: ListView
    private lateinit var search: EditText
    private lateinit var searchClear: View
    private lateinit var boostCount: TextView
    private lateinit var boostSub: TextView
    private lateinit var oifaceDot: View
    private lateinit var oifaceLabel: TextView
    private lateinit var oifaceSub: TextView
    private lateinit var boostState: TextView
    private lateinit var boostToggle: RateSwitch
    private lateinit var grantBox: View
    private lateinit var grantCmd: TextView
    private lateinit var stateBox: View
    private lateinit var stateSpinner: View
    private lateinit var stateIcon: View
    private lateinit var stateTitle: TextView
    private lateinit var stateBody: TextView
    private lateinit var actionBar: View
    private lateinit var scrim: View
    private lateinit var snack: TextView
    private lateinit var actions: List<View>
    private lateinit var chips: List<Pair<TextView, Filter>>
    private lateinit var adapter: AppRowAdapter

    // ------------------------------------------------------------------ setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = ArmedStore.open(this)
        armed.putAll(ArmedStore.read(prefs))
        userGames.addAll(prefs.getStringSet(KEY_USER_GAMES, emptySet()).orEmpty())

        goEdgeToEdge()
        setContentView(R.layout.activity_games)
        bindViews()
        applyInsets()
        wireSearch()
        wireChips()
        wireActions()

        adapter = AppRowAdapter(
            activity = this,
            items = { shown },
            armedRate = { armed[it] },
            fallbackRate = { RateLock.RATE_165 },
            onTap = ::onRowTapped,
            onDetails = ::showGameSheet,
        )
        list.adapter = adapter
        list.setOnScrollListener(scrollListener)

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

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        worker.shutdown()
        super.onDestroy()
    }

    private fun bindViews() {
        root = findViewById(R.id.root)
        header = findViewById(R.id.header)
        divider = findViewById(R.id.header_divider)
        list = findViewById(R.id.list)
        search = findViewById(R.id.search)
        searchClear = findViewById(R.id.search_clear)
        boostCount = findViewById(R.id.boost_count)
        boostSub = findViewById(R.id.boost_sub)
        oifaceDot = findViewById(R.id.oiface_dot)
        oifaceLabel = findViewById(R.id.oiface_label)
        oifaceSub = findViewById(R.id.oiface_sub)
        boostState = findViewById(R.id.boost_state)
        boostToggle = findViewById(R.id.boost_toggle)
        grantBox = findViewById(R.id.grant_box)
        grantCmd = findViewById(R.id.grant_cmd)
        stateBox = findViewById(R.id.state_box)
        stateSpinner = findViewById(R.id.state_spinner)
        stateIcon = findViewById(R.id.state_icon)
        stateTitle = findViewById(R.id.state_title)
        stateBody = findViewById(R.id.state_body)
        actionBar = findViewById(R.id.action_bar)
        scrim = findViewById(R.id.scrim)
        snack = findViewById(R.id.snack)
        actions = listOf(findViewById(R.id.btn_boost_all), findViewById(R.id.btn_clear))
        chips = listOf(
            findViewById<TextView>(R.id.chip_games) to Filter.GAMES,
            findViewById<TextView>(R.id.chip_boosted) to Filter.BOOSTED,
            findViewById<TextView>(R.id.chip_all) to Filter.ALL,
        )
        syncChips()
    }

    private fun goEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    private fun applyInsets() {
        val barMargin = resources.getDimensionPixelSize(R.dimen.action_bar_margin)
        val barSpace = resources.getDimensionPixelSize(R.dimen.action_bar_height) + barMargin

        root.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                val ime = insets.getInsets(WindowInsets.Type.ime())
                top = bars.top
                bottom = maxOf(bars.bottom, ime.bottom)
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            header.setPaddingRelative(header.paddingStart, top + dp(8), header.paddingEnd, header.paddingBottom)
            setBottomMargin(actionBar, bottom + barMargin)
            setBottomMargin(snack, bottom + barSpace + dp(10))
            scrim.layoutParams = (scrim.layoutParams as FrameLayout.LayoutParams).apply {
                height = bottom + barSpace + dp(46)
            }
            list.setPadding(0, list.paddingTop, 0, bottom + barSpace + dp(16))
            insets
        }
        root.requestApplyInsets()
    }

    private fun setBottomMargin(view: View, value: Int) {
        view.layoutParams = (view.layoutParams as FrameLayout.LayoutParams).apply { bottomMargin = value }
    }

    // --------------------------------------------------------------- controls

    private fun wireSearch() {
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(e: Editable?) {
                val raw = e?.toString().orEmpty()
                searchClear.visibility = if (raw.isEmpty()) View.GONE else View.VISIBLE
                query = raw.trim().lowercase()
                applyFilter()
                list.setSelection(0)
            }
        })
        searchClear.setOnClickListener { search.setText("") }
        search.setOnEditorActionListener { _, _, _ -> search.clearFocus(); hideKeyboard(); true }
    }

    private fun wireChips() {
        chips.forEach { (chip, value) ->
            chip.setOnClickListener {
                if (filter == value) return@setOnClickListener
                filter = value
                syncChips()
                applyFilter()
                list.setSelection(0)
            }
        }
    }

    private fun syncChips() {
        chips.forEach { (chip, value) -> chip.isSelected = value == filter }
    }

    private fun wireActions() {
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_boost_all).setOnClickListener { boostAll() }
        findViewById<View>(R.id.btn_clear).setOnClickListener { clearAll() }
        boostToggle.setOnClickListener { onBoostSwitch() }
        grantCmd.setOnClickListener { copyGrantCommand() }
    }

    private val scrollListener = object : AbsListView.OnScrollListener {
        override fun onScrollStateChanged(view: AbsListView, state: Int) {
            if (state == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL && search.hasFocus()) {
                search.clearFocus()
                hideKeyboard()
            }
        }

        override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
            val show = list.canScrollVertically(-1)
            if (show != dividerShown) {
                dividerShown = show
                divider.animate().alpha(if (show) 1f else 0f).setDuration(140).start()
            }
        }
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

    private fun updateState() {
        val empty = shown.isEmpty()
        stateBox.visibility = if (loading || empty) View.VISIBLE else View.GONE
        stateSpinner.visibility = if (loading) View.VISIBLE else View.GONE
        stateIcon.visibility = if (!loading && empty) View.VISIBLE else View.GONE
        stateTitle.setText(if (loading) R.string.loading else R.string.games_empty_title)
        stateBody.visibility = if (loading) View.GONE else View.VISIBLE
        stateBody.setText(
            when {
                filter == Filter.BOOSTED && query.isEmpty() -> R.string.games_empty_armed_body
                filter == Filter.GAMES && query.isEmpty() -> R.string.games_empty_body
                else -> R.string.empty_body
            }
        )
    }

    private fun refreshStatus() {
        val games = gamePackages()
        // While the catalog is still loading nothing is known to be a game yet,
        // so fall back to the raw armed count rather than showing a hard zero.
        boostCount.text = (if (loading) armed.size else armed.count { it.key in games }).toString()
        boostSub.text =
            if (loading) getString(R.string.games_hero_loading)
            else getString(R.string.games_hero_of, games.size)
    }

    private fun gamePackages(): Set<String> =
        all.asSequence().filter { it.game }.map { it.pkg }.toSet()

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
        val voted = RateLock.arm(entry.pkg, RateLock.RATE_165)
        if (voted) {
            armed[entry.pkg] = RateLock.RATE_165
            Oiface.registerGame(entry.pkg)
            ArmWatchService.start(this)
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

    /** Per-game actions: mark/unmark as a game, or stop boosting it. */
    private fun showGameSheet(entry: AppEntry) {
        if (rejectWhileBusy()) return
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (entry.pkg in userGames) {
            actions += getString(R.string.unmark_game) to { setUserGame(entry, false) }
        } else {
            actions += getString(R.string.mark_game) to { setUserGame(entry, true) }
        }
        if (entry.pkg in armed) {
            actions += getString(R.string.rate_picker_disarm) to { unboost(entry, null) }
        }
        AlertDialog.Builder(this)
            .setTitle(entry.label)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
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
        val clip = getSystemService(ClipboardManager::class.java)
        clip?.setPrimaryClip(ClipData.newPlainText("adb", getString(R.string.boost_grant_cmd)))
        snack(getString(R.string.copied))
    }

    private fun rejectWhileBusy(): Boolean {
        if (busy) snack(getString(R.string.busy))
        return busy
    }

    private fun runBusy(message: String, work: () -> Unit) {
        if (rejectWhileBusy()) return
        busy = true
        setActionsEnabled(false)
        snack(message)
        worker.execute {
            work()
            ui { busy = false; setActionsEnabled(true) }
        }
    }

    private fun setActionsEnabled(enabled: Boolean) {
        actions.forEach {
            it.isEnabled = enabled
            it.animate().alpha(if (enabled) 1f else 0.45f).setDuration(120).start()
        }
    }

    private fun confirmFirstTime(onYes: () -> Unit) {
        if (prefs.getBoolean(KEY_WARNED, false)) {
            onYes()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.risk_title)
            .setMessage(R.string.risk_body)
            .setPositiveButton(R.string.risk_yes) { _, _ ->
                prefs.edit().putBoolean(KEY_WARNED, true).apply()
                onYes()
            }
            .setNegativeButton(R.string.risk_no, null)
            .show()
    }

    // --------------------------------------------------------------- feedback

    private val hideSnack = Runnable {
        snack.animate().alpha(0f).translationY(dp(10).toFloat()).setDuration(160)
            .withEndAction { snack.visibility = View.GONE }.start()
    }

    private fun snack(text: String) {
        if (isFinishing || isDestroyed) return
        main.removeCallbacks(hideSnack)
        snack.text = text
        if (snack.visibility != View.VISIBLE) {
            snack.visibility = View.VISIBLE
            snack.alpha = 0f
            snack.translationY = dp(12).toFloat()
        }
        snack.animate().alpha(1f).translationY(0f).setDuration(180).start()
        main.postDelayed(hideSnack, SNACK_MS)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun ui(block: () -> Unit) {
        main.post { if (!isFinishing && !isDestroyed) block() }
    }

    private companion object {
        const val KEY_WARNED = "warned"
        const val KEY_USER_GAMES = "user_games"
        const val SNACK_MS = 2400L
    }
}

package tf.arm165

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private enum class Filter { ALL, ARMED, USER, SYSTEM }

    private lateinit var prefs: SharedPreferences

    private var all: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    private val armed = LinkedHashSet<String>()

    private var filter = Filter.ALL
    private var query = ""
    private var loading = true
    private var busy = false
    private var dividerShown = false

    private val main = Handler(Looper.getMainLooper())


    /** Serialises the arm/disarm sweeps so two of them can never interleave. */
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var root: FrameLayout
    private lateinit var header: View
    private lateinit var divider: View
    private lateinit var list: ListView
    private lateinit var search: EditText
    private lateinit var searchClear: View
    private lateinit var armedCount: TextView
    private lateinit var armedSub: TextView
    private lateinit var armedBar: ProgressBar
    private lateinit var watchdogDot: View
    private lateinit var watchdogLabel: TextView
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
    private lateinit var adapter: AppAdapter

    // ------------------------------------------------------------------ setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        armed.addAll(prefs.getStringSet(KEY_ARMED, emptySet()).orEmpty())

        goEdgeToEdge()
        setContentView(R.layout.activity_main)
        bindViews()
        applyInsets()
        wireSearch()
        wireChips()
        wireActions()

        adapter = AppAdapter()
        list.adapter = adapter
        list.setOnScrollListener(scrollListener)

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
        armedCount = findViewById(R.id.armed_count)
        armedSub = findViewById(R.id.armed_sub)
        armedBar = findViewById(R.id.armed_bar)
        watchdogDot = findViewById(R.id.watchdog_dot)
        watchdogLabel = findViewById(R.id.watchdog_label)
        stateBox = findViewById(R.id.state_box)
        stateSpinner = findViewById(R.id.state_spinner)
        stateIcon = findViewById(R.id.state_icon)
        stateTitle = findViewById(R.id.state_title)
        stateBody = findViewById(R.id.state_body)
        actionBar = findViewById(R.id.action_bar)
        scrim = findViewById(R.id.scrim)
        snack = findViewById(R.id.snack)
        actions = listOf(
            findViewById(R.id.btn_rearm),
            findViewById(R.id.btn_arm_all),
            findViewById(R.id.btn_clear),
        )
        chips = listOf(
            findViewById<TextView>(R.id.chip_all) to Filter.ALL,
            findViewById<TextView>(R.id.chip_armed) to Filter.ARMED,
            findViewById<TextView>(R.id.chip_user) to Filter.USER,
            findViewById<TextView>(R.id.chip_system) to Filter.SYSTEM,
        )
        syncChips()
    }

    private fun goEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION") // no-op from targetSdk 35, still needed on API 30-34
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    /**
     * The layout draws under the status and navigation bars; everything that
     * touches an edge gets its offset from here. The keyboard counts as a
     * bottom inset so the action bar rides above it.
     */
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

            header.setPaddingRelative(
                header.paddingStart,
                top + dp(8),
                header.paddingEnd,
                header.paddingBottom,
            )
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
        view.layoutParams = (view.layoutParams as FrameLayout.LayoutParams).apply {
            bottomMargin = value
        }
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
        search.setOnEditorActionListener { _, _, _ ->
            search.clearFocus()
            hideKeyboard()
            true
        }
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
        findViewById<View>(R.id.btn_rearm).setOnClickListener { reArmSaved() }
        findViewById<View>(R.id.btn_arm_all).setOnClickListener { armAll() }
        findViewById<View>(R.id.btn_clear).setOnClickListener { clearAll() }
    }

    private val scrollListener = object : AbsListView.OnScrollListener {
        override fun onScrollStateChanged(view: AbsListView, state: Int) {
            if (state == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL && search.hasFocus()) {
                search.clearFocus()
                hideKeyboard()
            }
        }

        override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
            // hairline under the header, but only once there is content above the fold
            val show = list.canScrollVertically(-1)
            if (show != dividerShown) {
                dividerShown = show
                divider.animate().alpha(if (show) 1f else 0f).setDuration(140).start()
            }
        }
    }

    // ------------------------------------------------------------------ state

    private fun applyFilter() {
        shown = all.filter { entry ->
            (query.isEmpty() || entry.key.contains(query)) && when (filter) {
                Filter.ALL -> true
                Filter.ARMED -> entry.pkg in armed
                Filter.USER -> !entry.system
                Filter.SYSTEM -> entry.system
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
        stateTitle.setText(if (loading) R.string.loading else R.string.empty_title)
        stateBody.visibility = if (loading) View.GONE else View.VISIBLE
        stateBody.setText(
            if (filter == Filter.ARMED && query.isEmpty()) R.string.empty_armed_body
            else R.string.empty_body
        )
    }

    private fun refreshStatus() {
        val count = armed.size
        armedCount.text = count.toString()
        armedSub.text =
            if (loading) getString(R.string.hero_loading) else getString(R.string.hero_of, all.size)
        armedBar.max = maxOf(all.size, 1)
        armedBar.setProgress(if (loading) 0 else count, true)

        val live = count > 0
        watchdogLabel.setText(if (live) R.string.watchdog_live else R.string.watchdog_idle)
        watchdogDot.backgroundTintList = ColorStateList.valueOf(
            getColor(if (live) R.color.accent else R.color.text_disabled)
        )
    }

    /** Call after [armed] changes so the hero, the rows and the Armed filter agree again. */
    private fun onArmedChanged() {
        saveArmed()
        if (isFinishing || isDestroyed) return
        refreshStatus()
        if (filter == Filter.ARMED) {
            applyFilter()
        } else {
            adapter.notifyDataSetChanged()
            updateState()
        }
    }

    private fun saveArmed() {
        // SharedPreferences must not be handed a set we keep mutating.
        prefs.edit().putStringSet(KEY_ARMED, HashSet(armed)).apply()
    }

    // ---------------------------------------------------------------- actions

    private fun onRowTapped(entry: AppEntry, toggle: RateSwitch) {
        if (busy) {
            snack(getString(R.string.busy))
            return
        }
        when {
            entry.pkg in armed -> disarm(entry, toggle)
            prefs.getBoolean(KEY_WARNED, false) -> arm(entry, toggle)
            // behind the dialog the row repaints itself, so nothing to animate
            else -> confirmFirstTime { arm(entry, null) }
        }
    }

    private fun arm(entry: AppEntry, toggle: RateSwitch?) {
        toggle?.setChecked(true, animate = true)
        if (RateLock.arm(entry.pkg)) {
            armed.add(entry.pkg)
            ArmWatchService.start(this)
        } else {
            snack(getString(R.string.arm_failed, entry.label))
        }
        onArmedChanged()
    }

    private fun disarm(entry: AppEntry, toggle: RateSwitch?) {
        toggle?.setChecked(false, animate = true)
        RateLock.arm(entry.pkg) // the identical call toggles the override back off
        armed.remove(entry.pkg)
        onArmedChanged()
    }

    private fun reArmSaved() {
        val saved = armed.toList()
        if (saved.isEmpty()) {
            snack(getString(R.string.nothing_saved))
            return
        }
        runBusy(getString(R.string.rearming)) {
            var ok = 0
            saved.forEach { if (RateLock.arm(it)) ok++ }
            ui { snack(getString(R.string.rearmed, ok, saved.size)) }
        }
    }

    private fun armAll() {
        if (all.isEmpty()) return
        confirmFirstTime {
            val targets = all.map { it.pkg }
            runBusy(getString(R.string.arming_all, targets.size)) {
                val done = ArrayList<String>(targets.size)
                targets.forEach { if (RateLock.arm(it)) done.add(it) }
                main.post {
                    armed.addAll(done)
                    onArmedChanged()
                    ArmWatchService.start(this)
                    snack(getString(R.string.armed_all, done.size, targets.size))
                }
            }
        }
    }

    private fun clearAll() {
        val saved = armed.toList()
        if (saved.isEmpty()) {
            snack(getString(R.string.nothing_saved))
            return
        }
        runBusy(getString(R.string.clearing)) {
            saved.forEach { RateLock.arm(it) }
            main.post {
                armed.clear()
                onArmedChanged()
                snack(resources.getQuantityString(R.plurals.cleared, saved.size, saved.size))
            }
        }
    }

    private fun reArmSavedQuietly() {
        val saved = armed.toList()
        if (saved.isEmpty()) return
        worker.execute { saved.forEach { RateLock.arm(it) } }
    }

    private fun runBusy(message: String, work: () -> Unit) {
        if (busy) {
            snack(getString(R.string.busy))
            return
        }
        busy = true
        setActionsEnabled(false)
        snack(message)
        worker.execute {
            work()
            ui {
                busy = false
                setActionsEnabled(true)
            }
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

    // --------------------------------------------------------------- feedback

    private val hideSnack = Runnable {
        snack.animate()
            .alpha(0f)
            .translationY(dp(10).toFloat())
            .setDuration(160)
            .withEndAction { snack.visibility = View.GONE }
            .start()
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
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Posts to the main thread, dropping the work if the screen is already gone. */
    private fun ui(block: () -> Unit) {
        main.post { if (!isFinishing && !isDestroyed) block() }
    }

    // ---------------------------------------------------------------- adapter

    private class Holder(view: View) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val label: TextView = view.findViewById(R.id.label)
        val sub: TextView = view.findViewById(R.id.pkg)
        val toggle: RateSwitch = view.findViewById(R.id.toggle)
        var pkg: String? = null
        var background = 0
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_app, parent, false)
                .also { it.tag = Holder(it) }
            val holder = view.tag as Holder
            val entry = shown[position]
            val recycled = holder.pkg != entry.pkg
            holder.pkg = entry.pkg

            holder.label.text = entry.label
            holder.sub.text =
                if (entry.system) getString(R.string.system_prefix) + "  ·  " + entry.pkg
                else entry.pkg

            val isArmed = entry.pkg in armed
            val background = if (isArmed) R.drawable.bg_row_armed else R.drawable.bg_row
            if (holder.background != background) {
                holder.background = background
                view.setBackgroundResource(background)
            }

            val cached = AppCatalog.cachedIcon(entry.pkg)
            if (cached != null) {
                holder.icon.setImageDrawable(cached)
            } else {
                holder.icon.setImageDrawable(null)
                AppCatalog.loadIcon(packageManager, entry.pkg) { pkg, icon ->
                    if (holder.pkg == pkg) holder.icon.setImageDrawable(icon)
                }
            }

            // Skipping this while the state already matches keeps a running
            // toggle animation from being snapped by notifyDataSetChanged().
            if (recycled || holder.toggle.isChecked != isArmed) {
                holder.toggle.setChecked(isArmed, animate = false)
            }
            holder.toggle.contentDescription = getString(R.string.toggle_desc, entry.label)
            view.setOnClickListener { onRowTapped(entry, holder.toggle) }
            return view
        }
    }

    private companion object {
        const val PREFS = "arm165"
        const val KEY_ARMED = "armed"
        const val KEY_WARNED = "warned"
        const val REQ_NOTIFICATIONS = 165
        const val SNACK_MS = 2400L
    }
}

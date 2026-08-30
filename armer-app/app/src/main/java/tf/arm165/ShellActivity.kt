package tf.arm165

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
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
 * Everything the two screens share: the edge-to-edge shell, window insets,
 * search field, scroll-triggered divider, snackbar, busy handling and the
 * first-run risk dialog.
 *
 * Both layouts use the same ids for this furniture, so a subclass only supplies
 * its layout, its action-bar buttons and what to do when the query changes.
 */
abstract class ShellActivity : Activity() {

    protected lateinit var prefs: SharedPreferences

    protected var query = ""
    protected var loading = true
    protected var busy = false
    private var dividerShown = false

    protected val main = Handler(Looper.getMainLooper())

    /** Serialises sweeps so two of them can never interleave. */
    protected val worker = Executors.newSingleThreadExecutor()

    protected lateinit var root: FrameLayout
    protected lateinit var header: View
    protected lateinit var divider: View
    protected lateinit var list: ListView
    protected lateinit var search: EditText
    protected lateinit var searchClear: View
    protected lateinit var stateBox: View
    protected lateinit var stateSpinner: View
    protected lateinit var stateIcon: View
    protected lateinit var stateTitle: TextView
    protected lateinit var stateBody: TextView
    protected lateinit var actionBar: View
    protected lateinit var scrim: View
    protected lateinit var snackView: TextView
    protected lateinit var actions: List<View>

    protected abstract val layoutRes: Int

    /** Ids of the floating action-bar buttons, dimmed together while busy. */
    protected abstract val actionIds: List<Int>

    /** Called after [query] changes; recompute the visible list here. */
    protected abstract fun onQueryChanged()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = ArmedStore.open(this)
        goEdgeToEdge()
        setContentView(layoutRes)
        bindShell()
        applyInsets()
        wireSearch()
        list.setOnScrollListener(scrollListener)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        worker.shutdown()
        super.onDestroy()
    }

    private fun bindShell() {
        root = findViewById(R.id.root)
        header = findViewById(R.id.header)
        divider = findViewById(R.id.header_divider)
        list = findViewById(R.id.list)
        search = findViewById(R.id.search)
        searchClear = findViewById(R.id.search_clear)
        stateBox = findViewById(R.id.state_box)
        stateSpinner = findViewById(R.id.state_spinner)
        stateIcon = findViewById(R.id.state_icon)
        stateTitle = findViewById(R.id.state_title)
        stateBody = findViewById(R.id.state_body)
        actionBar = findViewById(R.id.action_bar)
        scrim = findViewById(R.id.scrim)
        snackView = findViewById(R.id.snack)
        actions = actionIds.map { findViewById<View>(it) }
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
            header.setPaddingRelative(header.paddingStart, top + dp(8), header.paddingEnd, header.paddingBottom)
            setBottomMargin(actionBar, bottom + barMargin)
            setBottomMargin(snackView, bottom + barSpace + dp(10))
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

    private fun wireSearch() {
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(e: Editable?) {
                val raw = e?.toString().orEmpty()
                searchClear.visibility = if (raw.isEmpty()) View.GONE else View.VISIBLE
                query = raw.trim().lowercase()
                onQueryChanged()
                list.setSelection(0)
            }
        })
        searchClear.setOnClickListener { search.setText("") }
        search.setOnEditorActionListener { _, _, _ -> search.clearFocus(); hideKeyboard(); true }
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

    /** Loading spinner / empty state over the list. */
    protected fun renderState(empty: Boolean, emptyTitle: Int, emptyBody: Int) {
        stateBox.visibility = if (loading || empty) View.VISIBLE else View.GONE
        stateSpinner.visibility = if (loading) View.VISIBLE else View.GONE
        stateIcon.visibility = if (!loading && empty) View.VISIBLE else View.GONE
        stateTitle.setText(if (loading) R.string.loading else emptyTitle)
        stateBody.visibility = if (loading) View.GONE else View.VISIBLE
        stateBody.setText(emptyBody)
    }

    protected fun <T> syncChips(chips: List<Pair<TextView, T>>, current: T) {
        chips.forEach { (chip, value) -> chip.isSelected = value == current }
    }

    // ---------------------------------------------------------------- helpers

    /** True when a sweep owns the armed set; tells the user why nothing happened. */
    protected fun rejectWhileBusy(): Boolean {
        if (busy) snack(getString(R.string.busy))
        return busy
    }

    protected fun runBusy(message: String, work: () -> Unit) {
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

    protected fun confirmFirstTime(onYes: () -> Unit) {
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

    private val hideSnack = Runnable {
        snackView.animate().alpha(0f).translationY(dp(10).toFloat()).setDuration(160)
            .withEndAction { snackView.visibility = View.GONE }.start()
    }

    protected fun snack(text: String) {
        if (isFinishing || isDestroyed) return
        main.removeCallbacks(hideSnack)
        snackView.text = text
        if (snackView.visibility != View.VISIBLE) {
            snackView.visibility = View.VISIBLE
            snackView.alpha = 0f
            snackView.translationY = dp(12).toFloat()
        }
        snackView.animate().alpha(1f).translationY(0f).setDuration(180).start()
        main.postDelayed(hideSnack, SNACK_MS)
    }

    protected fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(root.windowToken, 0)
    }

    protected fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Posts to the main thread, dropping the work if the screen is already gone. */
    protected fun ui(block: () -> Unit) {
        main.post { if (!isFinishing && !isDestroyed) block() }
    }

    protected companion object {
        const val KEY_WARNED = "warned"
        const val SNACK_MS = 2400L
    }
}

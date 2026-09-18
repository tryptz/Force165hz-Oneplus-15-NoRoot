package tf.arm165

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.widget.ScrollView
import android.widget.TextView

/**
 * The live console: what the watchdog and the vendor's rate machinery have been
 * doing. Backed by [LogRing] — the app's own logcat entries, which any app may
 * read.
 *
 * Its own page, reached from settings. It shared that screen until a setting
 * and a scrolling log were competing for the same room, which left the
 * console's own controls with none.
 *
 * Live updates are a switch rather than a button, and the choice is remembered:
 * reading a log usually means stopping it first, and a page that starts
 * scrolling again every time it is opened is a page that cannot be read.
 */
class LogActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private lateinit var logText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var liveSub: TextView
    private lateinit var liveToggle: RateSwitch

    /** Whether the buffer is re-read on a timer. Persisted across visits. */
    private var live = true

    private val refresh = object : Runnable {
        override fun run() {
            if (live) handler.postDelayed(this, REFRESH_MS)
            load()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        prefs = ArmedStore.open(this)
        live = prefs.getBoolean(KEY_LIVE, true)

        logText = findViewById(R.id.log_text)
        scroll = findViewById(R.id.log_scroll)
        liveSub = findViewById(R.id.live_sub)
        liveToggle = findViewById(R.id.live_toggle)

        // Edge-to-edge: without this the header sits under the status bar and
        // its buttons cannot be pressed.
        val root = findViewById<View>(R.id.log_root)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            } else {
                null
            }
            val top = bars?.top ?: @Suppress("DEPRECATION") insets.systemWindowInsetTop
            val bottom = bars?.bottom ?: @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            root.setPadding(0, top, 0, bottom)
            insets
        }
        root.requestApplyInsets()

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_log_refresh).setOnClickListener { load() }
        findViewById<View>(R.id.row_live).setOnClickListener { toggleLive() }

        findViewById<View>(R.id.btn_log_clear).setOnClickListener {
            // logcat -c empties the device buffer; everything before this tap
            // is gone for good. The app may clear its own entries.
            try { Runtime.getRuntime().exec(arrayOf("logcat", "-c")) } catch (_: Throwable) {}
            load()
        }

        findViewById<View>(R.id.btn_log_share).setOnClickListener {
            val text = LogRing.readText()
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "165 Armer log")
                putExtra(Intent.EXTRA_TEXT, text)
                // pre-fill a clip so apps that strip EXTRA_TEXT still get it
                clipData = ClipData.newPlainText("log", text)
            }
            startActivity(Intent.createChooser(send, getString(R.string.log_share)))
        }

        syncLive(animate = false)
    }

    override fun onResume() {
        super.onResume()
        load()
        if (live) handler.postDelayed(refresh, REFRESH_MS)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun toggleLive() {
        live = !live
        prefs.edit().putBoolean(KEY_LIVE, live).apply()
        syncLive(animate = true)
        handler.removeCallbacks(refresh)
        if (live) handler.postDelayed(refresh, REFRESH_MS)
    }

    private fun syncLive(animate: Boolean) {
        liveToggle.setChecked(live, animate)
        liveSub.setText(if (live) R.string.log_live_on else R.string.log_live_off)
        liveToggle.contentDescription = getString(R.string.log_live)
    }

    /** Replaces the console text; sticks to the bottom unless the user scrolled up. */
    private fun load() {
        val pinned = scroll.scrollY + scroll.height >= logText.height - 20
        logText.text = LogRing.read().joinToString("\n")
        if (pinned) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private companion object {
        const val REFRESH_MS = 2000L
        const val KEY_LIVE = "log_live"
    }
}

package tf.arm165

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.widget.ScrollView
import android.widget.TextView

/**
 * Settings: a live console of what the watchdog and the vendor's rate
 * machinery have been doing. Backed by [LogRing] — the app's own logcat
 * entries, which any app may read — with an auto-refresh toggle, a manual
 * refresh, a share (for bug reports) and a clear.
 */
class SettingsActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var auto = true

    private lateinit var logText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var autoBtn: TextView

    /** Pulls the buffer and repaints; keeps the view pinned to the newest line. */
    private val refresh = object : Runnable {
        override fun run() {
            if (auto) handler.postDelayed(this, REFRESH_MS)
            load()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        logText = findViewById(R.id.log_text)
        scroll = findViewById(R.id.log_scroll)

        // Edge-to-edge: without this the header sits under the status bar
        // and its buttons cannot be pressed. Same treatment ShellActivity gives
        // the main screens, minus the furniture it manages.
        val root = findViewById<View>(R.id.settings_root)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            } else {
                @Suppress("DEPRECATION")
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
        findViewById<View>(R.id.btn_log_clear).setOnClickListener {
            // logcat -c empties the device buffer; everything before this
            // tap is gone for good. The app may clear its own entries.
            try { Runtime.getRuntime().exec(arrayOf("logcat", "-c")) } catch (_: Throwable) {}
            load()
        }
        autoBtn = findViewById(R.id.btn_log_autorefresh)
        autoBtn.setOnClickListener { toggleAuto() }

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
    }

    override fun onResume() {
        super.onResume()
        auto = true
        syncAutoBtn()
        handler.postDelayed(refresh, 100)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun toggleAuto() {
        auto = !auto
        syncAutoBtn()
        if (auto) handler.postDelayed(refresh, REFRESH_MS) else handler.removeCallbacks(refresh)
    }

    private fun syncAutoBtn() {
        autoBtn.text = getString(if (auto) R.string.log_auto_on else R.string.log_auto_off)
        autoBtn.setBackgroundResource(if (auto) R.drawable.bg_btn_primary else R.drawable.bg_btn_tonal)
        autoBtn.setTextColor(getColor(if (auto) R.color.on_accent else R.color.text_primary))
    }

    /** Replaces the console text; sticks to the bottom unless the user scrolled up. */
    private fun load() {
        val pinned = scroll.scrollY + scroll.height >= logText.height - 20
        logText.text = LogRing.read().joinToString("\n")
        if (pinned) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private companion object {
        const val REFRESH_MS = 2000L
    }
}

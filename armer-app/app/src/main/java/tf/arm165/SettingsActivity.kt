package tf.arm165

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

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
    private lateinit var fgBody: TextView
    private lateinit var fgBtn: TextView
    private lateinit var peakBody: TextView
    private lateinit var peakBtn: TextView
    private lateinit var prefs: SharedPreferences

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

        fgBody = findViewById(R.id.fg_body)
        fgBtn = findViewById(R.id.btn_fg_grant)
        fgBtn.setOnClickListener { openUsageAccess() }

        prefs = ArmedStore.open(this)
        peakBody = findViewById(R.id.peak_body)
        peakBtn = findViewById(R.id.btn_peak)
        peakBtn.setOnClickListener { onPeakTapped() }
        // The adb route stays one long-press away: "Modify system settings" is
        // itself a restricted setting on a sideloaded build, and a shell write
        // does not care about that.
        peakBtn.setOnLongClickListener {
            copy(getString(R.string.peak_adb_cmd))
            true
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
    }

    override fun onResume() {
        super.onResume()
        auto = true
        syncAutoBtn()
        // Coming back from the usage-access screen is the one moment the grant
        // can have changed, so re-read it and drop the watchdog's cached
        // answer with it (same process, same object).
        Foreground.forget()
        syncFg()
        syncPeak()
        handler.postDelayed(refresh, 100)
    }

    /** Paints the usage-access card from the live appop state. */
    private fun syncFg() {
        val granted = Foreground.hasAccess(this, refresh = true)
        fgBody.setText(if (granted) R.string.fg_on else R.string.fg_off)
        fgBtn.setText(if (granted) R.string.fg_manage else R.string.fg_grant)
        fgBtn.setBackgroundResource(
            if (granted) R.drawable.bg_btn_tonal else R.drawable.bg_btn_primary
        )
        fgBtn.setTextColor(getColor(if (granted) R.color.text_primary else R.color.on_accent))
    }

    /**
     * Paints the ceiling card from the live settings values. Reads need no
     * permission, so this is accurate whether or not the write is allowed.
     */
    private fun syncPeak() {
        val peak = SecureSettings.getSystemFloat(this, SecureSettings.KEY_PEAK_REFRESH)
        val min = SecureSettings.getSystemFloat(this, SecureSettings.KEY_MIN_REFRESH)
        val body = StringBuilder(getString(R.string.peak_body, hz(peak), hz(min)))
        if (!SecureSettings.canWriteSystem(this)) {
            body.append("\n\n").append(getString(R.string.peak_needs_grant))
        }
        peakBody.text = body
        peakBtn.setText(
            when {
                !SecureSettings.canWriteSystem(this) -> R.string.peak_grant
                peak >= RAISED_HZ -> R.string.peak_restore
                else -> R.string.peak_raise
            }
        )
    }

    /** "165 Hz", or "default" for a key that is not set at all. */
    private fun hz(value: Float): String =
        if (value <= 0f) getString(R.string.peak_unset)
        else getString(R.string.peak_hz, value.toInt())

    /**
     * Raises the display ceiling to 165, or puts it back.
     *
     * Restore removes the key rather than writing a number: on a device where
     * it had never been set, writing our guess of the default would leave a
     * value behind that the system had been deciding for itself. The value
     * that was there before a raise is remembered for the one case where there
     * genuinely was one.
     */
    private fun onPeakTapped() {
        if (!SecureSettings.canWriteSystem(this)) {
            openWriteSettings()
            return
        }
        val peak = SecureSettings.getSystemFloat(this, SecureSettings.KEY_PEAK_REFRESH)
        val ok: Boolean
        if (peak >= RAISED_HZ) {
            val saved = prefs.getFloat(KEY_PEAK_SAVED, 0f)
            ok = if (saved > 0f) {
                SecureSettings.putSystemFloat(this, SecureSettings.KEY_PEAK_REFRESH, saved)
            } else {
                SecureSettings.clearSystem(this, SecureSettings.KEY_PEAK_REFRESH)
            }
            if (ok) {
                prefs.edit().remove(KEY_PEAK_SAVED).apply()
                toast(getString(R.string.peak_restored))
            }
        } else {
            prefs.edit().putFloat(KEY_PEAK_SAVED, peak).apply()
            ok = SecureSettings.putSystemFloat(this, SecureSettings.KEY_PEAK_REFRESH, RAISED_HZ)
            if (ok) {
                // Read back rather than echoing what was asked for: the vendor
                // may clamp the ceiling to a mode the panel actually has.
                val now = SecureSettings.getSystemFloat(this, SecureSettings.KEY_PEAK_REFRESH)
                toast(getString(R.string.peak_done, now.toInt()))
            }
        }
        if (!ok) toast(getString(R.string.peak_refused))
        syncPeak()
    }

    /** The "Modify system settings" screen, per app where the build has it. */
    private fun openWriteSettings() {
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS),
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: Throwable) {
                // try the next one
            }
        }
        Toast.makeText(this, R.string.peak_no_screen, Toast.LENGTH_LONG).show()
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("cmd", text))
        toast(getString(R.string.copied))
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    /**
     * Opens the usage-access list. There is no dialog to request this appop —
     * Settings is the only place it can be granted, which is why the card sends
     * the user there rather than asking.
     */
    private fun openUsageAccess() {
        val intents = listOf(
            // The per-app page where it exists, the whole list as the fallback.
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, android.net.Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: Throwable) {
                // try the next one
            }
        }
        Toast.makeText(this, R.string.fg_no_screen, Toast.LENGTH_LONG).show()
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

        /** The ceiling the Raise button asks for, and the test for "raised". */
        const val RAISED_HZ = 165f

        /** Remembers a ceiling that was genuinely set before a raise. */
        const val KEY_PEAK_SAVED = "peak_saved"
    }
}

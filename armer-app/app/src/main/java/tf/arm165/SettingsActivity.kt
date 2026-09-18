package tf.arm165

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.TextView
import android.widget.Toast

/**
 * The app's own switches, and the way into the live log.
 *
 * The console used to be this screen. It is its own page now ([LogActivity]):
 * a setting and a scrolling log were competing for the same room, and the log's
 * controls lost.
 */
class SettingsActivity : Activity() {

    private lateinit var fgBody: TextView
    private lateinit var fgBtn: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

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

        fgBody = findViewById(R.id.fg_body)
        fgBtn = findViewById(R.id.btn_fg_grant)
        fgBtn.setOnClickListener { openUsageAccess() }

        findViewById<View>(R.id.row_fps).setOnClickListener {
            startActivity(Intent(this, FpsTestActivity::class.java))
        }

        findViewById<View>(R.id.row_log).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the usage-access screen is the one moment the grant
        // can have changed, so re-read it and drop the watchdog's cached
        // answer with it (same process, same object).
        Foreground.forget()
        syncFg()
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
}

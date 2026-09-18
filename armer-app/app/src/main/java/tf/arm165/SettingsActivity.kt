package tf.arm165

import android.app.Activity
import android.content.ActivityNotFoundException
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

        // Usage access cannot be granted from here — only asked for. The
        // switch reports the state and the row opens the system page; onResume
        // re-reads it, which is how the switch follows a grant made there.
        findViewById<View>(R.id.row_usage).setOnClickListener { openUsageAccess() }

        findViewById<View>(R.id.row_fps).setOnClickListener {
            startActivity(Intent(this, FpsTestActivity::class.java))
        }

        findViewById<View>(R.id.row_log).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        syncUsageRow()
    }

    private fun syncUsageRow() {
        val granted = Foreground.hasAccess(this)
        findViewById<RateSwitch>(R.id.usage_switch).setChecked(granted, animate = false)
        findViewById<TextView>(R.id.usage_sub)
            .setText(if (granted) R.string.usage_on else R.string.usage_off)
    }

    private fun openUsageAccess() {
        // The per-app page is the one worth landing on; not every build
        // resolves it, so fall back to the list and then to saying so.
        val direct = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", packageName, null))
        try {
            startActivity(direct)
            return
        } catch (t: ActivityNotFoundException) {
            // fall through to the undirected list
        }
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (t: ActivityNotFoundException) {
            Toast.makeText(this, R.string.usage_no_screen, Toast.LENGTH_LONG).show()
        }
    }
}

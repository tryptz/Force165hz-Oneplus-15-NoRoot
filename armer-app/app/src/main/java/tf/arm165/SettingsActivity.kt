package tf.arm165

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets

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

        findViewById<View>(R.id.row_fps).setOnClickListener {
            startActivity(Intent(this, FpsTestActivity::class.java))
        }

        findViewById<View>(R.id.row_log).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
    }

}

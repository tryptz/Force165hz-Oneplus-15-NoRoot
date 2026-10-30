package tf.arm165

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
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

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguage.wrap(base))

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

        val updateSub = findViewById<TextView>(R.id.update_sub)
        updateSub.text = getString(R.string.update_row_sub, UpdateCheck.installed(this))
        findViewById<View>(R.id.row_update).setOnClickListener { checkForUpdate(updateSub) }

        findViewById<View>(R.id.row_lock120).setOnClickListener { tapLock120() }
        findViewById<View>(R.id.row_camera120).setOnClickListener { tapCamera120() }

        findViewById<View>(R.id.row_language).setOnClickListener { pickLanguage() }
        findViewById<TextView>(R.id.language_sub).text = AppLanguage.label(this, AppLanguage.current(this))

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
        syncBindingRow()
        syncLock120Row()
        syncCamera120Row()
    }

    /**
     * The switch, and a word on the phone's own mode: this is for Standard.
     * On High the lock screen is at 120 already and SystemUI is capped there
     * whatever this does; where the mode cannot be read, nothing is claimed.
     */
    private fun syncLock120Row() {
        val on = LockRate.enabled(this)
        findViewById<RateSwitch>(R.id.lock120_switch).setChecked(on, animate = false)
        val mode = LockRate.phoneMode(this)
        findViewById<TextView>(R.id.lock120_sub).setText(when {
            mode != null && mode != LockRate.MODE_STANDARD -> R.string.lock120_sub_not_standard
            on -> R.string.lock120_sub_on
            else -> R.string.lock120_sub_off
        })
    }

    private fun tapLock120() {
        val on = !LockRate.enabled(this)
        LockRate.setEnabled(this, on)
        if (on) ArmWatchService.start(this) else ArmWatchService.stopIfIdle(this)
        syncLock120Row()
    }

    /**
     * The same, for the camera — plus usage access, which is the only way
     * this app can see that the camera is the one on screen.
     */
    private fun syncCamera120Row() {
        val on = LockRate.cameraEnabled(this)
        findViewById<RateSwitch>(R.id.camera120_switch).setChecked(on, animate = false)
        val mode = LockRate.phoneMode(this)
        findViewById<TextView>(R.id.camera120_sub).setText(when {
            mode != null && mode != LockRate.MODE_STANDARD -> R.string.camera120_sub_not_standard
            on && !Foreground.hasAccess(this) -> R.string.camera120_sub_no_usage
            on -> R.string.camera120_sub_on
            else -> R.string.camera120_sub_off
        })
    }

    private fun tapCamera120() {
        val on = !LockRate.cameraEnabled(this)
        LockRate.setCameraEnabled(this, on)
        if (on) ArmWatchService.start(this) else ArmWatchService.stopIfIdle(this)
        syncCamera120Row()
    }

    /**
     * What the check at launch found. It is on the screen because on a build
     * this app was not written on it separates "the vendor closed the call"
     * from "this app is dialling the wrong number", and only the second is
     * something a report can fix. [RateLock.bind] has run by the time this
     * page can be opened — it is the first thing the main screen does.
     */
    private fun syncBindingRow() {
        val code = RateLock.voteCode
        findViewById<TextView>(R.id.binding_sub).text = when (RateLock.binding) {
            RateLock.Binding.STUB -> getString(R.string.binding_stub, code)
            RateLock.Binding.PROXY -> getString(R.string.binding_proxy)
            RateLock.Binding.PROBED -> getString(R.string.binding_probed, code)
            RateLock.Binding.SCANNED -> getString(R.string.binding_scanned, code)
            RateLock.Binding.ASSUMED -> getString(R.string.binding_assumed, code)
            RateLock.Binding.ABSENT -> getString(R.string.binding_absent)
            RateLock.Binding.NONE -> getString(R.string.binding_pending)
        }
    }

    /**
     * The grant only changes anything past [ArmWatchService.FULL_SWEEP_MAX]
     * armed apps: at or under it every armed app is voted on every pass, so a
     * single game needs nothing. The row says which of those two the user is
     * actually in rather than asking for a permission they may never need.
     */
    private fun syncUsageRow() {
        val granted = Foreground.hasAccess(this)
        val armed = ArmedStore.read(ArmedStore.open(this)).size
        val bound = ArmWatchService.FULL_SWEEP_MAX
        findViewById<RateSwitch>(R.id.usage_switch).setChecked(granted, animate = false)
        findViewById<TextView>(R.id.usage_sub).text = when {
            granted -> getString(R.string.usage_on, bound)
            armed > bound -> getString(R.string.usage_off_needed, armed, bound)
            else -> getString(R.string.usage_off, bound)
        }
    }

    /** The manual check: always asks, whatever the once-a-day gate says. */
    private fun checkForUpdate(sub: TextView) {
        val installed = UpdateCheck.installed(this)
        sub.setText(R.string.update_checking)
        UpdateCheck.check(this, ArmedStore.open(this)) { release ->
            sub.text = when {
                release == null -> getString(R.string.update_failed)
                UpdateCheck.isNewer(release.version, installed) -> {
                    UpdateCheck.offer(this, release)
                    getString(R.string.update_available_sub, release.version)
                }
                else -> getString(R.string.update_latest, installed)
            }
        }
    }

    private fun pickLanguage() {
        val tags = AppLanguage.TAGS
        val current = AppLanguage.current(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.language_title)
            .setSingleChoiceItems(tags.map { AppLanguage.label(this, it) }.toTypedArray(), tags.indexOf(current)) { dialog, which ->
                dialog.dismiss()
                if (tags[which] != current) AppLanguage.set(this, tags[which])
            }
            .show()
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

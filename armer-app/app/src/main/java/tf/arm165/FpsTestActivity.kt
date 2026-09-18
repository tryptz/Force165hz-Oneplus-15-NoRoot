package tf.arm165

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The smoothness test: moving blocks, one lane per rate, plus what the panel is
 * measured to be doing while they move.
 *
 * It is a test of THIS app's window, so it can only show a high rate while this
 * app holds a vote for itself — which is what the switch on the page does, at
 * whatever rate the main screen's selector is set to. Without it the page is a
 * fair demonstration of 120, and the lanes above 120 will look identical to it.
 *
 * The animation also keeps the watchdog's park away by itself: a screen that
 * never stops moving is never idle, so the vote stays at the armed rate for as
 * long as the page is open.
 */
class FpsTestActivity : Activity() {

    private lateinit var readout: TextView
    private lateinit var holdSub: TextView
    private lateinit var holdToggle: RateSwitch
    private lateinit var view: FpsTestView
    private var speedSegments: List<Pair<TextView, Float>> = emptyList()

    /**
     * True when the hold switch on this page is what armed this app, as
     * opposed to the row in the list having been on before we got here. Only
     * the first case is released on the way out: a page called "hold 165 Hz
     * HERE" must not leave the app pinned for the rest of the day, and it
     * should not undo a choice made somewhere else either.
     */
    private var armedBySelf = false

    private val prefs by lazy { ArmedStore.open(this) }

    /** The rate the main screen is set to arm at; what the switch holds. */
    private val rateId: Int
        get() = prefs.getInt(ArmedStore.KEY_RATE, RateLock.DEFAULT_RATE)
            .takeIf { RateLock.isKnown(it) } ?: RateLock.DEFAULT_RATE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fps_test)
        // A test that dims and locks halfway through is not a test.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        readout = findViewById(R.id.fps_readout)
        holdSub = findViewById(R.id.hold_sub)
        holdToggle = findViewById(R.id.hold_toggle)
        view = findViewById(R.id.fps_lanes)

        val root = findViewById<View>(R.id.fps_root)
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
        findViewById<View>(R.id.row_hold).setOnClickListener { toggleHold() }

        view.onMeasured = { hz, frameMs ->
            readout.text = getString(R.string.fps_readout, hz, frameMs)
        }

        wireSpeed()
    }

    /**
     * Speed control, built here rather than in the layout so the choices stay
     * in one place. 1x is the default and matches what testufo.com sweeps at,
     * which is fast enough for a 60 lane to look worse than a 120 one without
     * touching anything. Half speed is for studying one lane; double is for
     * separating 120 from 165, which are close enough to need it.
     */
    private fun wireSpeed() {
        val track = findViewById<LinearLayout>(R.id.speed_segments)
        speedSegments = SPEEDS.map { (labelRes, multiplier) ->
            val segment = TextView(this, null, 0, R.style.Segment).apply {
                // A style cannot carry layout params onto a view built in code.
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                setText(labelRes)
                setOnClickListener { setSpeed(multiplier) }
            }
            track.addView(segment)
            segment to multiplier
        }
        setSpeed(view.speed)
    }

    private fun setSpeed(multiplier: Float) {
        view.speed = multiplier
        speedSegments.forEach { (segment, value) -> segment.isSelected = value == multiplier }
    }

    override fun onResume() {
        super.onResume()
        syncHold(animate = false)
    }

    override fun onStop() {
        super.onStop()
        // Leaving the page by any route gives the hold back, but a rotation is
        // not leaving: this screen is locked to landscape and turning the
        // phone over recreates it.
        if (!isChangingConfigurations && armedBySelf) {
            setHold(false)
            armedBySelf = false
        }
    }

    /**
     * Arms or disarms this app, through the same store the list uses so the
     * armed count, the watchdog and this switch cannot disagree.
     */
    private fun toggleHold() {
        val held = packageName in ArmedStore.read(prefs)
        setHold(!held)
        // Turning it on here makes this page responsible for turning it off.
        armedBySelf = !held
        syncHold(animate = true)
    }

    private fun setHold(on: Boolean) {
        val rate = rateId
        val armed = ArmedStore.read(prefs)
        if (on) {
            if (RateLock.arm(packageName, rate)) {
                armed[packageName] = rate
                ArmedStore.write(prefs, armed)
                ArmWatchService.start(this)
            }
        } else {
            armed.remove(packageName)
            ArmedStore.write(prefs, armed)
            RateLock.release(packageName, rate)
            ArmWatchService.stopIfIdle(this)
        }
    }

    private companion object {
        /** Label to multiplier, in display order; 1x is the default. */
        val SPEEDS = listOf(
            R.string.fps_speed_half to 0.5f,
            R.string.fps_speed_one to 1f,
            R.string.fps_speed_two to 2f,
        )
    }

    private fun syncHold(animate: Boolean) {
        val held = packageName in ArmedStore.read(prefs)
        val hz = RateLock.hz(rateId)
        holdToggle.setChecked(held, animate)
        holdToggle.contentDescription = getString(R.string.fps_hold, hz)
        holdSub.text = getString(if (held) R.string.fps_hold_on else R.string.fps_hold_off, hz)
        findViewById<TextView>(R.id.hold_title).text = getString(R.string.fps_hold, hz)
    }
}

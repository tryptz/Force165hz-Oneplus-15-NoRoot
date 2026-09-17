package tf.arm165

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * A always-on-top readout in the status-bar strip, replacing Developer
 * Options' "Show refresh rate".
 *
 * Two different numbers, which this app exists to distinguish:
 *  - **Hz** — the panel's actual refresh cadence, counted from Choreographer
 *    vsync callbacks on our own overlay window.
 *  - **fps** — what the foreground game really renders at, read from the
 *    vendor game service ([Oiface.fps]). Only shown when that service answers.
 *
 * The window is not touchable, so it never eats a status-bar pull-down. Its
 * distance from the right edge is user-adjustable because no API reports where
 * the system's own wifi/battery icons sit.
 */
class FpsOverlay(private val context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    private var view: TextView? = null
    private var choreographer: Choreographer? = null

    private var frames = 0
    private var windowStartNs = 0L
    private var lastFrameNs = 0L

    /**
     * Exponentially smoothed interval between consecutive vsync callbacks, in
     * ns. Interval-based measurement is what makes sub-2 Hz readable: a frame
     * counter needs a window several times the interval (≥3 s at 1 Hz) before
     * it stops reading 0 or double, while a single pair of frames 1 s apart
     * already says 1 Hz. Recomputed every frame, read by the render tick.
     */
    private var emaIntervalNs = 0L
    private var hz = 0

    /** Latest vendor-reported game fps, 0 when unknown. */
    @Volatile private var gameFps = 0
    private var pollInFlight = false

    val isShowing: Boolean get() = view != null

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    fun show(rightOffsetDp: Int) {
        if (!canDraw()) return
        if (view != null) {
            move(rightOffsetDp)
            return
        }
        val text = TextView(context).apply {
            setBackgroundResource(R.drawable.bg_fps)
            setTextColor(context.getColor(R.color.overlay_text))
            textSize = 10.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            includeFontPadding = false
            setPadding(dp(7), dp(3), dp(7), dp(3))
            text = "—"
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(rightOffsetDp)
            y = dp(1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        try {
            wm?.addView(text, params)
        } catch (t: Throwable) {
            Log.w("Arm165", "overlay addView failed", t)
            return
        }
        view = text
        frames = 0
        windowStartNs = 0L
        lastFrameNs = 0L
        emaIntervalNs = 0L
        choreographer = Choreographer.getInstance().also { it.postFrameCallback(frameCallback) }
        handler.post(fpsPoll)
    }

    fun move(rightOffsetDp: Int) {
        val v = view ?: return
        val params = v.layoutParams as? WindowManager.LayoutParams ?: return
        params.x = dp(rightOffsetDp)
        try {
            wm?.updateViewLayout(v, params)
        } catch (t: Throwable) {
            Log.w("Arm165", "overlay move failed", t)
        }
    }

    fun hide() {
        choreographer?.removeFrameCallback(frameCallback)
        choreographer = null
        handler.removeCallbacks(fpsPoll)
        view?.let {
            try {
                wm?.removeView(it)
            } catch (t: Throwable) {
                // already gone
            }
        }
        view = null
        gameFps = 0
        lastFrameNs = 0L
        emaIntervalNs = 0L
    }

    // Measuring the interval between vsync callbacks (smoothed) rather than
    // counting frames per fixed window: at the 1 Hz LTPO idle floor a 500 ms
    // window contains 0 or 1 callbacks and reads 0 or 2, never 1. The EMA
    // converges in a handful of frames at any rate down to 1 Hz.
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (view == null) return
            if (lastFrameNs != 0L) {
                val interval = frameTimeNanos - lastFrameNs
                // >5 s between frames = renderer paused (app switch, screen
                // off) — stale data, restart the estimate.
                if (interval in 1..5_000_000_000L) {
                    emaIntervalNs = if (emaIntervalNs == 0L) interval
                    else (emaIntervalNs * 3 + interval) / 4
                } else {
                    emaIntervalNs = 0L
                }
            }
            lastFrameNs = frameTimeNanos
            frames++
            val elapsed = frameTimeNanos - windowStartNs
            if (elapsed >= SAMPLE_NS) {
                windowStartNs = frameTimeNanos
                frames = 0
                render()
            }
            choreographer?.postFrameCallback(this)
        }
    }

    /** Vendor fps is a binder round-trip, so poll it on its own slow cadence. */
    private val fpsPoll = object : Runnable {
        override fun run() {
            if (view == null) return
            if (!pollInFlight) {
                pollInFlight = true
                thread {
                    // isReachable() transacts on its first call, so probe here
                    // rather than on the main thread.
                    val pkg = if (Oiface.isReachable()) Oiface.currentGamePackage() else null
                    gameFps = if (pkg != null) Oiface.fps(pkg) else 0
                    pollInFlight = false
                }
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    private fun render() {
        val v = view ?: return
        // A stale estimate (no frame within 2.5 s) reads as stopped: show —
        // rather than the last known rate, which at idle could have been 1.
        val stale = lastFrameNs != 0L &&
            System.nanoTime() - lastFrameNs > 2_500_000_000L
        hz = if (!stale && emaIntervalNs > 0L) (1_000_000_000.0 / emaIntervalNs).roundToInt() else 0
        val fps = gameFps
        v.text = if (fps > 0) "$hz Hz · $fps fps" else "$hz Hz"
        v.setTextColor(context.getColor(tierColor(hz)))
    }

    private fun tierColor(value: Int): Int = when {
        value >= 150 -> R.color.overlay_good
        value >= 90 -> R.color.overlay_mid
        else -> R.color.overlay_low
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private companion object {
        const val SAMPLE_NS = 500_000_000L // recompute twice a second
        const val POLL_MS = 1000L
    }
}

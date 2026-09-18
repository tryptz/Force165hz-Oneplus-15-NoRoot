package tf.arm165

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Choreographer
import android.view.View
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A moving-block smoothness test, in the spirit of testufo.com.
 *
 * One lane per rate. Every lane moves at the same speed in pixels per second,
 * but each one may only *update* at its own rate: a lane's position is the
 * distance travelled, quantised to that lane's frame cadence
 * (`floor(t · fps) / fps`). So the lane whose rate matches what the panel is
 * doing glides, and the slower lanes step — the step is the judder, at the size
 * the eye actually sees it.
 *
 * Quantising by time rather than by counting vsyncs is what lets a lane have a
 * rate that is not a whole division of the panel's (144 against 165, say).
 *
 * No lane can look better than the panel it is drawn on, which is the point: on
 * an unarmed screen the 165, 144 and 120 lanes look identical, and they come
 * apart only once a vote actually holds the panel higher. The measured readout
 * above them says which of those two worlds you are in.
 */
class FpsTestView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), Choreographer.FrameCallback {

    /** Lane rates, fastest first — the rates this app can pin. */
    private val lanes = intArrayOf(165, 144, 120, 90, 60)

    private val d = resources.displayMetrics.density
    private val block = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }
    private val detail = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.on_accent) }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.outline) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_secondary)
        // applyDimension rather than scaledDensity: that field is deprecated
        // on SDK 34+, and this is the call that still respects font scaling.
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics,
        )
    }
    private val rect = RectF()

    private var startNs = 0L
    private var lastNs = 0L
    private var nowNs = 0L

    /**
     * Speed multiplier. A bigger gap per step is easier to see, so this is the
     * control that turns "these two lanes look the same" into a visible
     * difference — the judder is there either way, but a block that only moves
     * a couple of dp per step hides it.
     */
    var speed = 1f
        set(value) {
            field = value
            // Rebase the lane clock to now, so the sweep restarts from the
            // left instead of teleporting to wherever the new speed would
            // have put it. The vsync chain behind the readout is left alone.
            startNs = nowNs
            invalidate()
        }

    /**
     * Distinct positions each lane actually DREW in the last second.
     *
     * This is the lane's effective rate, and it is capped by the panel for
     * free: a position can only change on a frame we are asked to draw, so a
     * 165 lane on a 120 Hz panel counts 120. That makes the number the answer
     * to "is this lane really slower, or does it just look that way" —
     * and to "is the panel keeping up at all".
     */
    private val drawn = IntArray(lanes.size)
    private val shownRate = IntArray(lanes.size)
    private val lastX = FloatArray(lanes.size) { Float.NaN }
    private var windowStartNs = 0L

    /** Smoothed vsync interval — the panel's real rate while this view draws. */
    private var emaNs = 0L
    private var reportedAtNs = 0L

    /** Called a few times a second with the measured panel Hz and frame time. */
    var onMeasured: ((hz: Int, frameMs: Float) -> Unit)? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (startNs == 0L) startNs = frameTimeNanos
        if (lastNs != 0L) {
            val interval = frameTimeNanos - lastNs
            // A gap this long is the view being away, not a slow panel.
            if (interval in 1..500_000_000L) {
                emaNs = if (emaNs == 0L) interval else (emaNs * 3 + interval) / 4
            } else {
                emaNs = 0L
            }
        }
        lastNs = frameTimeNanos
        nowNs = frameTimeNanos

        if (windowStartNs == 0L) windowStartNs = frameTimeNanos
        if (frameTimeNanos - windowStartNs >= 1_000_000_000L) {
            // One second of counting; publish and start the next window.
            for (i in lanes.indices) {
                shownRate[i] = drawn[i]
                drawn[i] = 0
            }
            windowStartNs = frameTimeNanos
        }

        if (emaNs > 0L && frameTimeNanos - reportedAtNs > REPORT_EVERY_NS) {
            reportedAtNs = frameTimeNanos
            onMeasured?.invoke((1_000_000_000.0 / emaNs).roundToInt(), (emaNs / 1e6).toFloat())
        }

        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, (lanes.size * LANE_DP * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val seconds = (nowNs - startNs) / 1e9
        val laneH = LANE_DP * d
        val blockW = 52f * d
        val blockH = 24f * d
        val travel = width + blockW
        val radius = 5f * d

        lanes.forEachIndexed { index, fps ->
            val top = index * laneH
            val textY = top + 13f * d
            val shown = shownRate[index]
            canvas.drawText(
                if (shown == 0) "$fps Hz" else "$fps Hz   ·   $shown drawn/s",
                0f, textY, label,
            )

            val trackY = top + laneH - blockH / 2f - 6f * d
            rect.set(0f, trackY - 0.5f * d, width.toFloat(), trackY + 0.5f * d)
            canvas.drawRect(rect, track)

            // The lane's own clock: distance is only allowed to advance at the
            // lane's rate, so a slow lane teleports by a visible step while a
            // lane matching the panel moves a hair at a time.
            val stepped = floor(seconds * fps) / fps
            val x = (((stepped * SPEED_DP * speed * d) % travel) - blockW).toFloat()
            if (x != lastX[index]) {
                drawn[index]++
                lastX[index] = x
            }
            drawBlock(canvas, x, trackY, blockW, blockH, radius)
            // Draw the wrap-around copy so a block never pops in at the edge.
            if (x + blockW > width) {
                drawBlock(canvas, x - travel, trackY, blockW, blockH, radius)
            }
        }
    }

    /**
     * The moving marker: a bar with two stripes across it. The stripes are the
     * point — a plain rectangle sliding along gives the eye almost nothing to
     * lock onto, while a hard vertical edge inside the shape makes each jump
     * legible.
     */
    private fun drawBlock(canvas: Canvas, x: Float, cy: Float, w: Float, h: Float, radius: Float) {
        rect.set(x, cy - h / 2f, x + w, cy + h / 2f)
        canvas.drawRoundRect(rect, radius, radius, block)
        val stripeW = 3f * d
        for (i in 1..2) {
            val sx = x + w * i / 3f - stripeW / 2f
            rect.set(sx, cy - h / 2f + 4f * d, sx + stripeW, cy + h / 2f - 4f * d)
            canvas.drawRect(rect, detail)
        }
    }

    private companion object {
        /** Lane height in dp: label above, track and block below. */
        const val LANE_DP = 54f

        /**
         * Base travel speed, dp per second.
         *
         * This is the number that decides whether the test works at all. What
         * the eye reads as judder is the size of the jump between updates, and
         * that jump is speed / rate: at 420 dp/s a 60 Hz lane steps 7 dp and
         * looks perfectly smooth, which is why the lanes all looked alike. At
         * 960 — testufo.com's own default, which in CSS pixels on a phone
         * works out at roughly the same figure in dp — the same lane steps
         * 16 dp against 5.8 dp for 165, and they stop looking alike.
         */
        const val SPEED_DP = 960f

        /** How often the measured readout is pushed out: ~4 times a second. */
        const val REPORT_EVERY_NS = 250_000_000L
    }
}

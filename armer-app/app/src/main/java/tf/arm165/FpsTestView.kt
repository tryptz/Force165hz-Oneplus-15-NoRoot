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
        val blockW = 34f * d
        val blockH = 18f * d
        val travel = width + blockW
        val radius = 5f * d

        lanes.forEachIndexed { index, fps ->
            val top = index * laneH
            val textY = top + 13f * d
            canvas.drawText("$fps Hz", 0f, textY, label)

            val trackY = top + laneH - blockH / 2f - 6f * d
            rect.set(0f, trackY - 0.5f * d, width.toFloat(), trackY + 0.5f * d)
            canvas.drawRect(rect, track)

            // The lane's own clock: distance is only allowed to advance at the
            // lane's rate, so a slow lane teleports by a visible step while a
            // lane matching the panel moves a hair at a time.
            val stepped = floor(seconds * fps) / fps
            val x = (((stepped * SPEED_DP * d) % travel) - blockW).toFloat()
            rect.set(x, trackY - blockH / 2f, x + blockW, trackY + blockH / 2f)
            canvas.drawRoundRect(rect, radius, radius, block)
            // Draw the wrap-around copy so a block never pops in at the edge.
            if (x + blockW > width) {
                rect.offset(-travel, 0f)
                canvas.drawRoundRect(rect, radius, radius, block)
            }
        }
    }

    private companion object {
        /** Lane height in dp: label above, track and block below. */
        const val LANE_DP = 54f

        /** Travel speed in dp per second — brisk enough for a step to show. */
        const val SPEED_DP = 420f

        /** How often the measured readout is pushed out: ~4 times a second. */
        const val REPORT_EVERY_NS = 250_000_000L
    }
}

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

    /**
     * The marker's colour, as a resolved colour int. The page picks it; this
     * view only draws it, so nothing here has to know about theme attributes
     * or which entry in a list was chosen.
     *
     * Worth being able to change: what a moving edge looks like depends on the
     * panel as much as on the rate, and a colour that smears on one screen can
     * be crisp on another. The stripes inside the marker follow it, taking
     * black or white by its luminance, or they would vanish into a pale pick.
     */
    var blockColor: Int = context.getColor(R.color.accent)
        set(value) {
            field = value
            block.color = value
            detail.color =
                if (android.graphics.Color.luminance(value) > 0.5f) android.graphics.Color.BLACK
                else android.graphics.Color.WHITE
            invalidate()
        }
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

    /** The frame-time strip: one series, so its own label names it, no legend. */
    private val strip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        strokeJoin = Paint.Join.ROUND
        color = context.getColor(R.color.accent)
    }
    private val stripFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent)
        alpha = 38
    }
    /** A long frame is marked by weight and height, never by hue: the accent is
     *  the system's under Material You, so a status colour could land on it. */
    private val stripSpike = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * d
        color = context.getColor(R.color.accent)
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f * d
        color = context.getColor(R.color.outline_strong)
    }
    private val reference = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f * d
        color = context.getColor(R.color.outline_strong)
    }
    private val path = android.graphics.Path()

    /** Recent frame intervals in ms, oldest first once wrapped. */
    private val history = FloatArray(HISTORY)
    private var historyCount = 0
    private var historyHead = 0

    /** Frames that took much longer than the panel's own cadence. */
    private var longFrames = 0

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

    /**
     * Called a few times a second with the measured panel Hz, its frame time,
     * how much that frame time wanders, and how many frames have run long.
     */
    var onMeasured: ((hz: Int, frameMs: Float, jitterMs: Float, longFrames: Int) -> Unit)? = null

    /** Mean absolute deviation from the smoothed interval, in ms. */
    private fun jitterMs(): Float {
        if (historyCount == 0 || emaNs <= 0L) return 0f
        val mean = (emaNs / 1e6).toFloat()
        var sum = 0f
        for (i in 0 until historyCount) sum += kotlin.math.abs(history[i] - mean)
        return sum / historyCount
    }

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
                // A long frame is one that took half again as long as the
                // panel's own cadence: the frame the compositor missed.
                if (emaNs > 0L && interval > emaNs * 3 / 2) longFrames++
                emaNs = if (emaNs == 0L) interval else (emaNs * 3 + interval) / 4
                history[historyHead] = (interval / 1e6).toFloat()
                historyHead = (historyHead + 1) % HISTORY
                if (historyCount < HISTORY) historyCount++
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
            onMeasured?.invoke(
                (1_000_000_000.0 / emaNs).roundToInt(),
                (emaNs / 1e6).toFloat(),
                jitterMs(),
                longFrames,
            )
        }

        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val wanted = (lanes.size * LANE_DP * d).toInt()
        // The lanes ARE the page, so take the height offered rather than a
        // fixed one: sideways there is room to spare, and a taller lane is a
        // clearer lane.
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(wanted, MeasureSpec.getSize(heightMeasureSpec))
            else -> wanted
        }
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val seconds = (nowNs - startNs) / 1e9
        // The strip only earns its space where there is space: a short view
        // gives every pixel to the lanes.
        val stripH = if (height > MIN_FOR_STRIP_DP * d) STRIP_DP * d else 0f
        if (stripH > 0f) drawStrip(canvas, stripH)
        val lanesTop = stripH
        val laneH = (height - lanesTop) / lanes.size
        val blockW = 52f * d
        // The marker grows with the lane, within reason: a tall lane on a
        // landscape screen can carry a bar you can actually see stepping.
        val blockH = (laneH * 0.42f).coerceIn(18f * d, 40f * d)
        val travel = width + blockW
        val radius = 5f * d

        // The continuous position: where a block would be if its rate were
        // unlimited. Every lane trails it by up to one of its own frames, so
        // the gap between a block and this line IS that lane's latency, drawn
        // at the size the eye sees it.
        val refDist = seconds * SPEED_DP * speed * d
        val refX = ((refDist % travel) - blockW + blockW / 2f).toFloat()
        if (refX >= 0f) {
            canvas.drawLine(refX, lanesTop, refX, height.toFloat(), reference)
        }

        lanes.forEachIndexed { index, fps ->
            val top = lanesTop + index * laneH
            val textY = top + laneH * 0.34f
            val shown = shownRate[index]
            // Step distance is the judder number: speed divided by rate. The
            // rate alone does not tell anyone how far the thing jumps.
            val stepDp = (SPEED_DP * speed / fps).roundToInt()
            canvas.drawText(
                if (shown == 0) "$fps Hz" else "$fps Hz   ·   $shown drawn/s   ·   $stepDp dp per step",
                0f, textY, label,
            )

            val trackY = top + laneH * 0.70f
            rect.set(0f, trackY - 0.5f * d, width.toFloat(), trackY + 0.5f * d)
            canvas.drawRect(rect, track)

            // The lane's own clock: distance is only allowed to advance at the
            // lane's rate, so a slow lane teleports by a visible step while a
            // lane matching the panel moves a hair at a time.
            val stepped = floor(seconds * fps) / fps
            val dist = stepped * SPEED_DP * speed * d
            val x = ((dist % travel) - blockW).toFloat()
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

    /**
     * Frame intervals over the last couple of seconds: one series, its own
     * label, no legend. The recessive line across it is the panel's own
     * cadence, so a spike above it is a frame that arrived late and the shape
     * of the trace is the stutter you felt.
     *
     * Fixed scale rather than auto-fit: the ideal sits at a constant height,
     * which is what makes two glances comparable. Outliers clamp to the top
     * and are drawn heavier so a clipped spike still reads as a spike.
     */
    private fun drawStrip(canvas: Canvas, stripH: Float) {
        val top = 4f * d
        val bottom = stripH - 12f * d
        val h = bottom - top
        if (h <= 0f || historyCount < 2 || emaNs <= 0L) return

        val ideal = (emaNs / 1e6).toFloat()
        val full = ideal * SCALE_OF_IDEAL
        val idealY = bottom - (ideal / full) * h
        canvas.drawLine(0f, idealY, width.toFloat(), idealY, grid)

        val step = width.toFloat() / (HISTORY - 1)
        path.reset()
        var maxMs = 0f
        for (i in 0 until historyCount) {
            // Oldest first, so the newest frame is always at the right edge.
            val v = history[(historyHead - historyCount + i + HISTORY) % HISTORY]
            if (v > maxMs) maxMs = v
            val x = i * step
            val y = bottom - (v.coerceAtMost(full) / full) * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            if (v > ideal * 1.5f) canvas.drawLine(x, bottom, x, y, stripSpike)
        }
        canvas.drawPath(path, strip)
        // A thin fill under the trace, closed along the baseline.
        path.lineTo((historyCount - 1) * step, bottom)
        path.lineTo(0f, bottom)
        path.close()
        canvas.drawPath(path, stripFill)

        // Two direct labels, not a number per point: what this is, and the
        // worst frame in the window.
        canvas.drawText(
            context.getString(R.string.fps_strip_label), 0f, stripH - 2f * d, label,
        )
        val worst = context.getString(R.string.fps_strip_worst, maxMs)
        canvas.drawText(
            worst, width - label.measureText(worst), stripH - 2f * d, label,
        )
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

        /** Frame intervals kept for the strip: about two seconds at 165 Hz. */
        const val HISTORY = 330

        /** Height of the frame-time strip, and the height below which it is dropped. */
        const val STRIP_DP = 46f
        const val MIN_FOR_STRIP_DP = 260f

        /** The strip's top of scale, as a multiple of the panel's own interval. */
        const val SCALE_OF_IDEAL = 2.5f

    }
}

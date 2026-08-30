package tf.arm165

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator

/**
 * Small hand-drawn switch. The framework [android.widget.Switch] can't be
 * restyled far enough without pulling in a support library, and this app ships
 * with zero dependencies — so the track and thumb are drawn here instead.
 *
 * The view is not clickable: the whole list row is the touch target.
 */
class RateSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val trackOff = context.getColor(R.color.switch_track_off)
    private val trackOn = context.getColor(R.color.accent)
    private val thumbOff = context.getColor(R.color.switch_thumb_off)
    private val thumbOn = context.getColor(R.color.on_accent)
    private val strokeOff = context.getColor(R.color.outline_strong)

    private var anim: ValueAnimator? = null

    /** Drawing position: 0 = fully off, 1 = fully on. */
    private var pos = 0f
        set(value) {
            field = value
            invalidate()
        }

    var isChecked: Boolean = false
        private set

    init {
        // ListView recycles these; restoring per-id state would land on the wrong row.
        isSaveEnabled = false
    }

    fun setChecked(checked: Boolean, animate: Boolean) {
        isChecked = checked
        val target = if (checked) 1f else 0f
        anim?.cancel()
        anim = null
        if (!animate || pos == target) {
            pos = target
            return
        }
        anim = ValueAnimator.ofFloat(pos, target).apply {
            duration = 190
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { pos = it.animatedValue as Float }
            start()
        }
    }

    fun toggle(): Boolean {
        setChecked(!isChecked, animate = true)
        return isChecked
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((50f * d).toInt(), (30f * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f

        paint.style = Paint.Style.FILL
        paint.color = blend(trackOff, trackOn, pos)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, paint)

        // hairline outline on the off track, faded out as it turns on
        if (pos < 1f) {
            val inset = 0.75f * d
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * d
            paint.color = fade(strokeOff, 1f - pos)
            rect.set(inset, inset, w - inset, h - inset)
            canvas.drawRoundRect(rect, r - inset, r - inset, paint)
            paint.style = Paint.Style.FILL
        }

        // thumb grows slightly as it slides across
        val thumbMin = 8.5f * d
        val thumbMax = 11f * d
        val radius = thumbMin + (thumbMax - thumbMin) * pos
        val margin = 3f * d
        val left = margin + thumbMax
        val right = w - margin - thumbMax
        paint.color = blend(thumbOff, thumbOn, pos)
        canvas.drawCircle(left + (right - left) * pos, h / 2f, radius, paint)
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        val i = 1f - t
        return Color.argb(
            (Color.alpha(from) * i + Color.alpha(to) * t).toInt(),
            (Color.red(from) * i + Color.red(to) * t).toInt(),
            (Color.green(from) * i + Color.green(to) * t).toInt(),
            (Color.blue(from) * i + Color.blue(to) * t).toInt(),
        )
    }

    private fun fade(color: Int, t: Float): Int =
        Color.argb((Color.alpha(color) * t).toInt(), Color.red(color), Color.green(color), Color.blue(color))
}

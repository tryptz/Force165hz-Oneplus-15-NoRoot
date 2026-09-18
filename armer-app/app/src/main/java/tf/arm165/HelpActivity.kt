package tf.arm165

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView

/**
 * What the app does and how it does it, in the app itself — the README is not
 * on the phone when someone is looking at a switch and wondering what flipping
 * it will do.
 *
 * The cards are built here from [SECTIONS] rather than written out in the
 * layout: the text is the part that changes, and it lives in strings.xml where
 * it can be read and translated without wading through markup.
 */
class HelpActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)

        // Edge-to-edge, same treatment the other screens get: without this the
        // header sits under the status bar and the back button cannot be hit.
        val root = findViewById<View>(R.id.help_root)
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

        val sections = findViewById<LinearLayout>(R.id.help_sections)
        SECTIONS.forEach { (title, body) -> sections.addView(card(title, body)) }
    }

    /** One titled card, styled to match the rest of the app. */
    private fun card(titleRes: Int, bodyRes: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        setPadding(dp(14), dp(13), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) }

        addView(TextView(context).apply {
            setText(titleRes)
            setTextColor(getColor(R.color.text_primary))
            textSize = 14.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        addView(TextView(context).apply {
            setText(bodyRes)
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12.5f
            setLineSpacing(dp(3).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(5) }
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** Title to body, in reading order: what it is, then how, then limits. */
        val SECTIONS = listOf(
            R.string.help_what_title to R.string.help_what_body,
            R.string.help_how_title to R.string.help_how_body,
            R.string.help_pin_title to R.string.help_pin_body,
            R.string.help_watchdog_title to R.string.help_watchdog_body,
            R.string.help_focus_title to R.string.help_focus_body,
            R.string.help_park_title to R.string.help_park_body,
            R.string.help_optin_title to R.string.help_optin_body,
            R.string.help_peak_title to R.string.help_peak_body,
            R.string.help_disarm_title to R.string.help_disarm_body,
            R.string.help_limits_title to R.string.help_limits_body,
            R.string.help_log_title to R.string.help_log_body,
        )
    }
}

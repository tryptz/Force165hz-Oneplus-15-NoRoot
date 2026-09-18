package tf.arm165

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home-screen widget: what the panel is doing, and the two sweeps.
 *
 * The rate is the one [ArmWatchService] measures from its own frame timing —
 * a widget has no window of its own to time, so it shows the last reading the
 * service published and falls back to a dash when nothing has run yet.
 *
 * The buttons do not do the work. A broadcast receiver gets about ten seconds
 * before the system may kill it, and Arm all is one binder call per installed
 * package; so a tap starts [ArmWatchService] with an action and the sweep runs
 * on the worker there, where a foreground service can take as long as it takes.
 */
class ArmWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, manager, it) }
    }

    companion object {
        const val ACTION_ARM_ALL = "tf.arm165.widget.ARM_ALL"
        const val ACTION_CLEAR = "tf.arm165.widget.CLEAR"

        /** Last panel rate the service published, so the widget survives its death. */
        const val KEY_LAST_HZ = "widget_last_hz"

        /** The selector's segments, in the order RateLock.RATES lists them. */
        private val RATE_IDS = intArrayOf(
            R.id.w_rate_0, R.id.w_rate_1, R.id.w_rate_2, R.id.w_rate_3, R.id.w_rate_4,
        )

        /** Re-renders every placed widget. Safe from any thread. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, ArmWidget::class.java))
            ids.forEach { render(context, manager, it) }
        }

        /** No more than one widget render a second, whatever the panel does. */
        private const val PUBLISH_EVERY_NS = 1_000_000_000L

        @Volatile private var lastPublishNs = 0L

        /**
         * Publishes a panel rate for the widget to show.
         *
         * Called from every watchdog pass — as often as every 50 ms while a
         * vote is parked, and the panel is ramping through every value in its
         * range exactly then. So it is throttled twice: not more than once a
         * second, and only when the number actually changed. A home-screen
         * widget redrawn twenty times a second would cost more than the park
         * it is reporting saves.
         */
        fun publishHz(context: Context, hz: Int) {
            val now = System.nanoTime()
            if (now - lastPublishNs < PUBLISH_EVERY_NS) return
            val prefs = ArmedStore.open(context)
            if (prefs.getInt(KEY_LAST_HZ, -1) == hz) return
            lastPublishNs = now
            prefs.edit().putInt(KEY_LAST_HZ, hz).apply()
            refresh(context)
        }

        private fun render(context: Context, manager: AppWidgetManager, id: Int) {
            val prefs = ArmedStore.open(context)
            val armed = ArmedStore.read(prefs)
            val hz = prefs.getInt(KEY_LAST_HZ, 0)
            val activeRate = prefs.getInt(ArmedStore.KEY_RATE, RateLock.DEFAULT_RATE)
                .takeIf { RateLock.isKnown(it) } ?: RateLock.DEFAULT_RATE
            val views = RemoteViews(context.packageName, R.layout.widget_arm)

            views.setTextViewText(
                R.id.w_panel,
                if (hz > 0) context.getString(R.string.widget_hz, hz)
                else context.getString(R.string.widget_hz_none),
            )
            views.setTextViewText(R.id.w_count, armed.size.toString())
            views.setTextViewText(R.id.w_sub, breakdown(context, armed))

            // Against the sweep's own denominator when one has been recorded,
            // so a full bar means every app rather than every app so far.
            val total = maxOf(prefs.getInt(ArmedStore.KEY_TOTAL, 0), armed.size, 1)
            views.setProgressBar(R.id.w_bar, total, armed.size, false)

            RATE_IDS.forEachIndexed { i, viewId ->
                val rate = RateLock.RATES.getOrNull(i)
                if (rate == null) {
                    views.setViewVisibility(viewId, android.view.View.GONE)
                    return@forEachIndexed
                }
                val (rateId, rateHz) = rate
                views.setViewVisibility(viewId, android.view.View.VISIBLE)
                views.setTextViewText(viewId, rateHz.toString())
                // Background and colour set outright, not through the app's
                // state-list: a widget cannot drive a selector, because
                // setSelected is not among the methods RemoteViews is
                // guaranteed to be allowed to call. setBackgroundResource and
                // setTextColor are.
                val on = rateId == activeRate
                views.setInt(viewId, "setBackgroundResource",
                    if (on) R.drawable.segment_on else R.drawable.segment_off)
                views.setTextColor(viewId,
                    context.getColor(if (on) R.color.on_accent else R.color.text_secondary))
                views.setOnClickPendingIntent(viewId, setRate(context, rateId))
            }

            views.setOnClickPendingIntent(R.id.w_count_row, openApp(context))
            views.setOnClickPendingIntent(R.id.w_arm_all, action(context, ACTION_ARM_ALL))
            views.setOnClickPendingIntent(R.id.w_clear, action(context, ACTION_CLEAR))
            manager.updateAppWidget(id, views)
        }

        /** e.g. "165 Hz x 641", fastest first — the app's hero subtitle. */
        private fun breakdown(context: Context, armed: Map<String, Int>): String =
            RateLock.RATES.asReversed().mapNotNull { (rateId, hz) ->
                val n = armed.count { it.value == rateId }
                if (n == 0) null else context.getString(R.string.rate_breakdown_part, hz, n)
            }.joinToString("  \u00b7  ")

        /**
         * Sets the rate a later sweep will use. A broadcast rather than a
         * service start: it is a preference write, and nothing about it needs
         * the time a foreground service exists to buy.
         */
        private fun setRate(context: Context, rateId: Int): PendingIntent = PendingIntent.getBroadcast(
            context,
            rateId,
            Intent(context, WidgetRate::class.java)
                .setAction(WidgetRate.ACTION_SET_RATE)
                .putExtra(WidgetRate.EXTRA_RATE, rateId),
            PendingIntent.FLAG_IMMUTABLE,
        )

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        /**
         * Starts the service with [what]. Distinct request codes, or the two
         * buttons would share one PendingIntent and the second would silently
         * become the first.
         */
        private fun action(context: Context, what: String): PendingIntent = PendingIntent.getForegroundService(
            context,
            what.hashCode(),
            Intent(context, ArmWatchService::class.java).setAction(what),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

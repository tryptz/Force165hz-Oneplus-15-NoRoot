package tf.arm165

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView

/**
 * Shared list adapter for an app row (icon, label, package, rate badge, switch),
 * used by both the main screen and the games screen.
 *
 * State stays in the host activity: [items] supplies the current list, and
 * [armedRate] returns the rateId a package is armed at (or null). The host
 * mutates its own state then calls [notifyDataSetChanged]; this adapter reads
 * back through the suppliers, so both screens drive it the same way.
 */
class AppRowAdapter(
    private val activity: Activity,
    private val items: () -> List<AppEntry>,
    private val armedRate: (String) -> Int?,
    private val fallbackRate: () -> Int,
    private val onTap: (AppEntry, RateSwitch) -> Unit,
    private val onDetails: (AppEntry) -> Unit,
) : BaseAdapter() {

    private class Holder(view: View) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val label: TextView = view.findViewById(R.id.label)
        val sub: TextView = view.findViewById(R.id.pkg)
        val badge: TextView = view.findViewById(R.id.rate_badge)
        val toggle: RateSwitch = view.findViewById(R.id.toggle)
        var pkg: String? = null
        var background = 0
    }

    override fun getCount() = items().size
    override fun getItem(position: Int) = items()[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: activity.layoutInflater.inflate(R.layout.item_app, parent, false)
            .also { it.tag = Holder(it) }
        val holder = view.tag as Holder
        val entry = items()[position]
        val recycled = holder.pkg != entry.pkg
        holder.pkg = entry.pkg

        holder.label.text = entry.label
        holder.sub.text =
            if (entry.system) activity.getString(R.string.system_prefix) + "  ·  " + entry.pkg
            else entry.pkg

        val rateId = armedRate(entry.pkg)
        val isArmed = rateId != null
        val background = if (isArmed) R.drawable.bg_row_armed else R.drawable.bg_row
        if (holder.background != background) {
            holder.background = background
            view.setBackgroundResource(background)
        }

        if (rateId != null) {
            val hz = RateLock.hz(rateId)
            holder.badge.visibility = View.VISIBLE
            holder.badge.text = hz.toString()
            holder.badge.contentDescription =
                activity.getString(R.string.rate_badge_desc, entry.label, hz)
        } else {
            holder.badge.visibility = View.GONE
        }
        holder.badge.setOnClickListener { onDetails(entry) }

        val cached = AppCatalog.cachedIcon(entry.pkg)
        if (cached != null) {
            holder.icon.setImageDrawable(cached)
        } else {
            holder.icon.setImageDrawable(null)
            AppCatalog.loadIcon(activity.packageManager, entry.pkg) { pkg, icon ->
                if (holder.pkg == pkg) holder.icon.setImageDrawable(icon)
            }
        }

        // Skipping this while the state already matches keeps a running toggle
        // animation from being snapped by notifyDataSetChanged().
        if (recycled || holder.toggle.isChecked != isArmed) {
            holder.toggle.setChecked(isArmed, animate = false)
        }
        holder.toggle.contentDescription =
            activity.getString(R.string.toggle_desc, entry.label, RateLock.hz(rateId ?: fallbackRate()))

        view.setOnClickListener { onTap(entry, holder.toggle) }
        view.setOnLongClickListener {
            onDetails(entry)
            true
        }
        return view
    }
}

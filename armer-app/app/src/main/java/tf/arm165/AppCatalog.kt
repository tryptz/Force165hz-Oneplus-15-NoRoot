package tf.arm165

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.Collections
import java.util.concurrent.Executors

/** One installed package, resolved once so the list can filter without touching PackageManager. */
class AppEntry(val pkg: String, val label: String, val system: Boolean) {
    /** Lower-cased label + package, so search is a single substring test per row. */
    val key: String = "$label $pkg".lowercase()
}

/**
 * Off-main-thread package listing and icon loading.
 *
 * The old screen called PackageManager from getView() and again from the search
 * filter, which meant hundreds of IPCs per keystroke. Everything is resolved
 * once here and cached.
 */
object AppCatalog {

    private val pool = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "arm165-catalog").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private val icons = LruCache<String, Drawable>(120)
    private val pending = Collections.synchronizedSet(HashSet<String>())

    fun loadAsync(pm: PackageManager, self: String, onReady: (List<AppEntry>) -> Unit) {
        pool.execute {
            val entries = try {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
                    .asSequence()
                    .filter { it.packageName != self }
                    .map {
                        AppEntry(
                            pkg = it.packageName,
                            label = pm.getApplicationLabel(it).toString(),
                            system = it.flags and
                                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                        )
                    }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                    .toList()
            } catch (t: Throwable) {
                emptyList()
            }
            main.post { onReady(entries) }
        }
    }

    fun cachedIcon(pkg: String): Drawable? = icons.get(pkg)

    /** [onReady] runs on the main thread, and only if the icon actually resolved. */
    fun loadIcon(pm: PackageManager, pkg: String, onReady: (String, Drawable) -> Unit) {
        if (!pending.add(pkg)) return // already queued for this package
        pool.execute {
            val icon = try {
                pm.getApplicationIcon(pkg)
            } catch (t: Throwable) {
                null
            }
            pending.remove(pkg)
            if (icon == null) return@execute
            icons.put(pkg, icon)
            main.post { onReady(pkg, icon) }
        }
    }
}

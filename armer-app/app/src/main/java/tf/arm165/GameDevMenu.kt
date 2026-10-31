package tf.arm165

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.util.Log

/**
 * Game Space's hidden developer options (issue #24).
 *
 * The menu was reached over Brevent with
 *
 *     am start -n com.oplus.games/business.compact.activity.GameDevelopOptionsActivity \
 *         -a oplus.intent.action.GAMESPACEGAMEDEVELOP_OPTIONS \
 *         -c android.intent.category.DEFAULT \
 *         --es gameDevelopOptions GameDevelopOptionsActivity
 *
 * and stopped opening after a Game Space update. On a OnePlus 15 with the
 * current Game Space neither that name nor that action exists any more, so
 * nothing here is taken on trust: [find] asks the phone every time, in Game
 * Space and in COSA (the service behind GPA, frame interpolation and super
 * resolution, which the menu's switches drive), by the action, then the
 * known name, then any activity of its own whose name looks like a developer
 * options screen. The first one other apps may open wins. One that exists but is
 * closed is reported as such, because no app — and no adb shell either —
 * can start a screen its owner has not exported. When nothing matches, the
 * log lists, each time, every screen those packages do export: the only ones this app
 * could ever open, and what a renamed menu would be among.
 */
object GameDevMenu {
    private const val TAG = "Arm165"

    /** Game Space first: it is where the menu lived. */
    private val PACKAGES = listOf("com.oplus.games", "com.oplus.cosa")
    private const val ACTION = "oplus.intent.action.GAMESPACEGAMEDEVELOP_OPTIONS"
    private const val KNOWN = "business.compact.activity.GameDevelopOptionsActivity"
    private const val EXTRA = "gameDevelopOptions"

    /**
     * Lower-cased pieces of a class name that mark a developer-options screen.
     * Not "debug": Game Space 10.41 carries nineteen debug screens, every one
     * an ad or mini-program SDK's own, and none of them the menu.
     */
    private val NAME_HINTS = listOf("gamedevelop", "developoption", "developeroption", "devoption")

    /** Libraries bundled inside Game Space: their screens are never the menu. */
    private val SDK_PREFIXES = listOf("com.applovin.", "com.qq.", "com.tencent.", "com.google.", "com.bytedance.")

    sealed class Found {
        /** Other apps may open it: [component] is what [open] starts. */
        class Open(val component: ComponentName) : Found()

        /** It exists, but only for its own package or a permission we lack. */
        class Closed(val component: ComponentName) : Found()

        /** Game Space is installed and has no such screen. */
        object Missing : Found()

        /** No Game Space on this phone. */
        object NoGameSpace : Found()
    }

    fun find(context: Context): Found {
        val pm = context.packageManager
        // Package -> activity, in the order they should be tried.
        val candidates = ArrayList<Pair<String, ActivityInfo>>()
        val exported = LinkedHashMap<String, List<String>>()
        var gameSpace = false

        for (pkg in PACKAGES) {
            // 1. Whatever answers the menu's own action.
            try {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(Intent(ACTION).setPackage(pkg), PackageManager.MATCH_DISABLED_COMPONENTS)
                    .forEach { candidates += pkg to it.activityInfo }
            } catch (t: Throwable) {
                Log.w(TAG, "gamedev: action query in $pkg failed", t)
            }

            // 2 and 3. The known name, and anything named like it.
            val info = try {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.i(TAG, "gamedev: $pkg not installed")
                continue
            } catch (t: Throwable) {
                Log.w(TAG, "gamedev: could not list $pkg", t)
                continue
            }
            if (pkg == PACKAGES[0]) gameSpace = true
            val activities = info.activities.orEmpty()
            Log.i(TAG, "gamedev: $pkg ${info.versionName}, ${activities.size} screens, " +
                "${activities.count { it.exported }} open to other apps")
            activities.firstOrNull { it.name == KNOWN }?.let { candidates += pkg to it }
            activities.filter { a ->
                SDK_PREFIXES.none { a.name.startsWith(it) } &&
                    a.name.lowercase().let { n -> NAME_HINTS.any { it in n } }
            }
                .forEach { candidates += pkg to it }
            exported[pkg] = activities.filter { it.exported && it.enabled }.map { it.name }
        }

        if (!gameSpace && candidates.isEmpty()) return Found.NoGameSpace

        val unique = candidates.distinctBy { (pkg, a) -> "$pkg/${a.name}" }
        unique.forEach { (pkg, a) ->
            Log.i(TAG, "gamedev: $pkg/${a.name} exported=${a.exported} enabled=${a.enabled}" +
                (a.permission?.let { p -> " permission=$p" } ?: ""))
        }
        if (unique.isEmpty()) {
            Log.i(TAG, "gamedev: no developer-options screen in ${PACKAGES.joinToString()}")
            // Every time, not once per run: the log keeps 800 lines, and a
            // single Clear or Arm all writes more than that.
            exported.forEach { (pkg, names) ->
                Log.i(TAG, "gamedev: $pkg opens to other apps: " +
                    names.ifEmpty { listOf("nothing") }.joinToString())
            }
            return Found.Missing
        }
        val open = unique.firstOrNull { (_, a) -> openable(context, a) }
        return if (open != null) {
            Found.Open(ComponentName(open.first, open.second.name))
        } else {
            Found.Closed(ComponentName(unique.first().first, unique.first().second.name))
        }
    }

    private fun openable(context: Context, info: ActivityInfo): Boolean {
        if (!info.exported || !info.enabled) return false
        val perm = info.permission ?: return true
        return context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Starts the screen [find] found open. False when there was none, or
     * when the system refused it anyway; the log says which.
     */
    fun open(activity: Activity): Boolean {
        val found = find(activity) as? Found.Open ?: return false
        val intent = Intent(ACTION)
            .setComponent(found.component)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .putExtra(EXTRA, "GameDevelopOptionsActivity")
        return try {
            activity.startActivity(intent)
            Log.i(TAG, "gamedev: opened ${found.component.className}")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "gamedev: ${found.component.className} refused: ${e.message}")
            false
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "gamedev: ${found.component.className} not found: ${e.message}")
            false
        }
    }
}

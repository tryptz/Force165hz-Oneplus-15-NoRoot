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
 * and stopped opening after a Game Space update. Either the screen moved or
 * Game Space closed it to other apps, and only the phone can say which, so
 * nothing here is taken on trust: [find] asks the package for the screen
 * every time, by the action first, then by the known name, then by any
 * activity whose name says "GameDevelop". The first one other apps may
 * open wins. One that exists but is closed is reported as such, because no
 * app — and no adb shell either — can start a screen its owner has not
 * exported.
 */
object GameDevMenu {
    private const val TAG = "Arm165"

    const val PKG = "com.oplus.games"
    private const val ACTION = "oplus.intent.action.GAMESPACEGAMEDEVELOP_OPTIONS"
    private const val KNOWN = "business.compact.activity.GameDevelopOptionsActivity"
    private const val EXTRA = "gameDevelopOptions"
    private const val NAME_HINT = "GameDevelop"

    sealed class Found {
        /** Other apps may open it: [component] is what [open] starts. */
        class Open(val component: ComponentName) : Found()

        /** Game Space has it, but only for itself or a permission we lack. */
        class Closed(val component: ComponentName) : Found()

        /** Game Space is installed and has no such screen. */
        object Missing : Found()

        /** No Game Space on this phone. */
        object NoGameSpace : Found()
    }

    fun find(context: Context): Found {
        val pm = context.packageManager
        val candidates = LinkedHashMap<String, ActivityInfo>()

        // 1. Whatever answers the menu's own action.
        try {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(Intent(ACTION).setPackage(PKG), PackageManager.MATCH_DISABLED_COMPONENTS)
                .forEach { candidates.putIfAbsent(it.activityInfo.name, it.activityInfo) }
        } catch (t: Throwable) {
            Log.w(TAG, "gamedev: action query failed", t)
        }

        // 2 and 3. The known name, and anything renamed around it.
        val activities = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(PKG, PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS)
                .activities.orEmpty()
        } catch (e: PackageManager.NameNotFoundException) {
            if (candidates.isEmpty()) {
                Log.i(TAG, "gamedev: $PKG not installed")
                return Found.NoGameSpace
            }
            emptyArray()
        }
        activities.firstOrNull { it.name == KNOWN }?.let { candidates.putIfAbsent(it.name, it) }
        activities.filter { NAME_HINT in it.name }.forEach { candidates.putIfAbsent(it.name, it) }

        if (candidates.isEmpty()) {
            Log.i(TAG, "gamedev: $PKG has no developer-options screen")
            return Found.Missing
        }
        candidates.values.forEach {
            Log.i(TAG, "gamedev: ${it.name} exported=${it.exported} enabled=${it.enabled}" +
                (it.permission?.let { p -> " permission=$p" } ?: ""))
        }
        val open = candidates.values.firstOrNull { openable(context, it) }
        return if (open != null) {
            Found.Open(ComponentName(PKG, open.name))
        } else {
            Found.Closed(ComponentName(PKG, candidates.values.first().name))
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

package tf.arm165

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList

/**
 * The app's own language, apart from the phone's.
 *
 * On Android 13+ it is the system's per-app language ([LocaleManager]): the
 * choice is stored by the system, shows under the app's own page in Settings,
 * and every open screen is recreated in it. Below 13 there is no such thing,
 * so the pick is kept here and each screen applies it in attachBaseContext
 * ([wrap]); switching restarts the app so nothing is left in the old one.
 */
object AppLanguage {

    /** Language tags offered, in picker order. Empty follows the phone. */
    val TAGS = listOf("", "en", "zh-CN")

    private const val KEY = "app_language"

    /** Each language in its own name, so it can be found whatever is showing. */
    fun label(context: Context, tag: String): String = when (tag) {
        "en" -> "English"
        "zh-CN" -> "简体中文"
        else -> context.getString(R.string.language_system)
    }

    /** The offered tag in effect: one of [TAGS]. */
    fun current(context: Context): String {
        val set = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales?.toLanguageTags().orEmpty()
        } else {
            ArmedStore.open(context).getString(KEY, "").orEmpty()
        }
        // Settings may hand back a longer tag than we wrote (zh-Hans-CN).
        return TAGS.firstOrNull { it.isNotEmpty() && set.startsWith(it.substringBefore('-'), ignoreCase = true) } ?: ""
    }

    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            return
        }
        ArmedStore.open(activity).edit().putString(KEY, tag).commit()
        activity.startActivity(
            Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }

    /** For attachBaseContext below Android 13; a no-op where the system does it. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = ArmedStore.open(base).getString(KEY, "").orEmpty()
        if (tag.isEmpty()) return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tag))
        return base.createConfigurationContext(config)
    }
}

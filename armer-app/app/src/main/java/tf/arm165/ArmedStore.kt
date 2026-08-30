package tf.arm165

import android.content.Context
import android.content.SharedPreferences

/**
 * The armed set, persisted as package name to vendor rateId.
 *
 * Entries are stored in a StringSet as "pkg|rateId". Anything written before
 * per-app rates existed is a bare package name and reads back at 165 Hz, so an
 * upgrade keeps whatever was already armed.
 */
object ArmedStore {

    const val PREFS = "arm165"
    private const val KEY = "armed"
    private const val SEP = '|'

    fun open(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(prefs: SharedPreferences): LinkedHashMap<String, Int> {
        val out = LinkedHashMap<String, Int>()
        // The stored set has no order of its own; sort so the map is stable.
        prefs.getStringSet(KEY, emptySet()).orEmpty().sorted().forEach { entry ->
            val cut = entry.lastIndexOf(SEP)
            if (cut < 0) {
                if (entry.isNotEmpty()) out[entry] = RateLock.DEFAULT_RATE // pre-rates entry
                return@forEach
            }
            val pkg = entry.substring(0, cut)
            val rateId = entry.substring(cut + 1).toIntOrNull()
            if (pkg.isNotEmpty() && rateId != null && RateLock.isKnown(rateId)) out[pkg] = rateId
        }
        return out
    }

    fun write(prefs: SharedPreferences, armed: Map<String, Int>) {
        val encoded = armed.mapTo(HashSet(armed.size)) { "${it.key}$SEP${it.value}" }
        prefs.edit().putStringSet(KEY, encoded).apply()
    }
}

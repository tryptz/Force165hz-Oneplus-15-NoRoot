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

    /** The rate a plain row tap arms at, written by the hero's rate selector. */
    const val KEY_RATE = "rate"

    private const val KEY = "armed"
    private const val KEY_SCHEMA = "schema"
    private const val SEP = '|'

    /** Bumped whenever stored rateIds change meaning — see [migrate]. */
    private const val SCHEMA = 1

    fun open(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also(::migrate)

    /**
     * Schema 1: builds before the 60/90 fix had the two lowest vendor ids the
     * wrong way round — they saved id 1 for "60 Hz" and id 2 for "90 Hz", while
     * the vendor reads 1 as 90 and 2 as 60. Everything saved at those two rates
     * therefore ran at the rate the user had not picked, so swap the pair once
     * and a saved pick means what its label said. [KEY_SCHEMA] stops the swap
     * running twice and flipping straight back; the other rates are untouched.
     *
     * Every caller of [open] does so from a main-thread lifecycle callback, so
     * the read-then-write of [KEY_SCHEMA] cannot interleave with itself.
     */
    private fun migrate(prefs: SharedPreferences) {
        if (prefs.getInt(KEY_SCHEMA, 0) >= SCHEMA) return
        val edit = prefs.edit().putInt(KEY_SCHEMA, SCHEMA)

        val stored = prefs.getStringSet(KEY, emptySet()).orEmpty()
        if (stored.isNotEmpty()) {
            edit.putStringSet(KEY, stored.mapTo(HashSet(stored.size)) { entry ->
                val cut = entry.lastIndexOf(SEP)
                val rateId = if (cut < 0) null else entry.substring(cut + 1).toIntOrNull()
                // A bare package name carries no rate and is left exactly as is.
                if (rateId == null) entry else entry.substring(0, cut + 1) + swap60And90(rateId)
            })
        }
        if (prefs.contains(KEY_RATE)) {
            edit.putInt(KEY_RATE, swap60And90(prefs.getInt(KEY_RATE, RateLock.DEFAULT_RATE)))
        }
        edit.apply()
    }

    /**
     * Rewrites one id from the old numbering. Deliberately literal rather than
     * written in terms of [RateLock]: those constants now hold the corrected
     * values, so naming them here would describe the wrong side of the swap.
     */
    private fun swap60And90(rateId: Int): Int = when (rateId) {
        1 -> 2
        2 -> 1
        else -> rateId
    }

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

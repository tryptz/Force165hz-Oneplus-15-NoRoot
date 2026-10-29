package tf.arm165

import android.app.KeyguardManager
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * The lock screen at 120 Hz while SystemUI runs at the armed rate (issue #26).
 *
 * ColorOS's own "Screen refresh rate" setting decides two things at once. On
 * High, SystemUI — app switching, the shade — caps at 120 whatever is armed.
 * On Standard, SystemUI follows the armed rate, but the lock screen falls to
 * 60. Neither is what anyone wants, and the two never matter at the same time:
 * the lock screen is only up while the phone is locked. So the phone runs on
 * Standard while unlocked and is switched to High as the screen goes off,
 * which is the one moment the change cannot be seen; the unlock switches it
 * back.
 *
 * Which setting that option writes is known for the OnePlus 15 (see
 * [KNOWN_KEY]) but its High value is not a constant, other builds may differ,
 * and a wrong guess written to the secure table is worse than no feature. So it is learned instead: [startLearning] watches every settings
 * table while the user flips the option in the phone's own Display settings,
 * and [finishLearning] keeps each key that moved, with the value it had on
 * High and the one it was left on (Standard). Writing needs
 * WRITE_SECURE_SETTINGS, granted once over adb, like the extreme switch.
 * `Settings.System` keys are seen but not kept: an app cannot write a hidden
 * key there, granted or not.
 */
object LockRate {
    private const val TAG = "Arm165"

    private const val KEY_ON = "lock120_on"
    private const val KEY_LEARNED = "lock120_keys"

    /** One learned key: "table|name|high|standard". */
    private const val SEP = '|'

    /**
     * What the OnePlus 15's own Settings writes for the option (read out of
     * its system_ext Settings.apk, an Android 16 OTA): 0 Auto, 2 Standard, and High
     * as the last entry of a per-device list of modes — which is why the
     * values are learned rather than written in here. When learning sees this
     * key, it is the only one kept: anything else that moved at the same time
     * is the settings page's own bookkeeping, not the option.
     */
    private const val KNOWN_KEY = "oplus_customize_screen_refresh_rate"

    /** Only Global and Secure are writable with the grant. */
    private val WRITABLE = setOf("global", "secure")

    data class Key(val table: String, val name: String, val high: String, val standard: String)

    enum class Learned { OK, NOTHING_SEEN, SYSTEM_ONLY }

    fun learned(context: Context): List<Key> =
        ArmedStore.open(context).getStringSet(KEY_LEARNED, emptySet()).orEmpty().mapNotNull { s ->
            val p = s.split(SEP)
            if (p.size == 4) Key(p[0], p[1], p[2], p[3]) else null
        }

    fun enabled(context: Context): Boolean =
        ArmedStore.open(context).getBoolean(KEY_ON, false) && learned(context).isNotEmpty()

    /**
     * Turning it off puts the phone back on Standard — the value the user
     * left it on when this was learned — so a lock that happened while it was
     * on does not leave the phone on High behind it.
     */
    fun setEnabled(context: Context, on: Boolean) {
        ArmedStore.open(context).edit().putBoolean(KEY_ON, on).apply()
        if (!on) write(context, locked = false)
    }

    fun forget(context: Context) {
        setEnabled(context, false)
        ArmedStore.open(context).edit().remove(KEY_LEARNED).apply()
    }

    /** Lock went on: the lock screen should come up on High. */
    fun onScreenOff(context: Context) {
        if (enabled(context)) write(context, locked = true)
    }

    /**
     * Unlock, or a wake with no keyguard to pass: back to Standard. A wake
     * that lands on the keyguard stays on High until the unlock says so.
     */
    fun onUnlocked(context: Context) {
        if (enabled(context)) write(context, locked = false)
    }

    /** For a service start or a wake: the state the keyguard is in right now. */
    fun sync(context: Context) {
        if (!enabled(context)) return
        val km = context.getSystemService(KeyguardManager::class.java)
        write(context, locked = km?.isKeyguardLocked == true)
    }

    private fun write(context: Context, locked: Boolean) {
        val cr = context.contentResolver
        learned(context).forEach { k ->
            val value = if (locked) k.high else k.standard
            val ok = try {
                when (k.table) {
                    "global" -> Settings.Global.putString(cr, k.name, value)
                    "secure" -> Settings.Secure.putString(cr, k.name, value)
                    else -> false
                }
            } catch (t: Throwable) {
                Log.w(TAG, "lock120: writing ${k.table}/${k.name} failed", t)
                false
            }
            Log.i(TAG, "lock120: %s %s/%s=%s%s".format(
                if (locked) "locked," else "unlocked,", k.table, k.name, value,
                if (ok) "" else " (refused)"))
        }
    }

    // ---- learning ----------------------------------------------------------

    /** Every distinct value each key moved through, in order, while learning. */
    private val seen = LinkedHashMap<Pair<String, String>, MutableList<String>>()
    private var observer: ContentObserver? = null

    val learning: Boolean get() = observer != null

    private val TABLES = listOf(
        "global" to Settings.Global.CONTENT_URI,
        "secure" to Settings.Secure.CONTENT_URI,
        "system" to Settings.System.CONTENT_URI,
    )

    fun startLearning(context: Context) {
        stopLearning(context)
        seen.clear()
        val cr = context.applicationContext.contentResolver
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val segs = uri?.pathSegments ?: return
                if (segs.size < 2) return
                val table = segs[0]
                val name = segs[1]
                val value = read(cr, table, name) ?: return
                val list = seen.getOrPut(table to name) { mutableListOf() }
                if (list.lastOrNull() != value) list += value
            }
        }
        TABLES.forEach { (_, uri) -> cr.registerContentObserver(uri, true, obs) }
        observer = obs
    }

    fun stopLearning(context: Context) {
        observer?.let { context.applicationContext.contentResolver.unregisterContentObserver(it) }
        observer = null
    }

    /**
     * The user was asked for Standard, High, Standard. Whatever they started
     * on, that leaves every key the option writes with at least two distinct
     * values: the last is Standard, the one before it High. A key seen once
     * only (a counter, a timestamp the settings page bumps on open) or one
     * that ended where it started is not the option.
     */
    fun finishLearning(context: Context): Learned {
        stopLearning(context)
        val keys = seen.mapNotNull { (k, values) ->
            if (values.size < 2) return@mapNotNull null
            Key(k.first, k.second, values[values.size - 2], values.last())
        }
        seen.clear()
        keys.forEach { Log.i(TAG, "lock120: learned ${it.table}/${it.name} high=${it.high} standard=${it.standard}") }
        val writable = keys.filter { it.table in WRITABLE && SEP !in it.name && SEP !in it.high && SEP !in it.standard }
            .let { all -> all.filter { it.table == "secure" && it.name == KNOWN_KEY }.ifEmpty { all } }
        if (writable.isEmpty()) return if (keys.isEmpty()) Learned.NOTHING_SEEN else Learned.SYSTEM_ONLY
        ArmedStore.open(context).edit()
            .putStringSet(KEY_LEARNED, writable.mapTo(HashSet()) {
                listOf(it.table, it.name, it.high, it.standard).joinToString(SEP.toString())
            })
            .putBoolean(KEY_ON, true)
            .apply()
        return Learned.OK
    }

    private fun read(cr: android.content.ContentResolver, table: String, name: String): String? = try {
        when (table) {
            "global" -> Settings.Global.getString(cr, name)
            "secure" -> Settings.Secure.getString(cr, name)
            "system" -> Settings.System.getString(cr, name)
            else -> null
        }
    } catch (t: Throwable) {
        null
    }
}

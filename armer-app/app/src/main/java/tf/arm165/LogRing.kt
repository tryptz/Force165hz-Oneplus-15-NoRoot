package tf.arm165

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Log source for the Settings screen: shells out to `logcat` and reads back
 * the tags this feature runs on. An app may always read its OWN log entries —
 * READ_LOGS only gates other apps' — so no permission is needed here.
 *
 * Two tag sets matter for the idle feature:
 *  - `Arm165`      — ours: park / restore / arm / release decisions
 *  - `RefreshRate` — the vendor framework's own rate-change log; when the
 *    panel did not go where we asked, this says who won.
 */
object LogRing {

    private const val MAX_LINES = 800

    /** Oldest first; empty list when logcat is unavailable (rare, non-fatal). */
    fun read(): List<String> {
        val out = ArrayList<String>(MAX_LINES)
        val p = try {
            // -v time: "09-17 14:22:03.123 I/Arm165(1234): text"; -t 800: last
            // 800 lines; -s silences everything but the two tags we care about.
            Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "time", "-t", MAX_LINES.toString(),
                        "-s", "Arm165:V", "RefreshRate:V")
            )
        } catch (t: Throwable) {
            return listOf("<logcat unavailable: ${t.message}>")
        }
        try {
            BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                r.forEachLine { line ->
                    if (line.isNotBlank()) out.add(line)
                }
            }
        } catch (t: Throwable) {
            out.add("<read failed: ${t.message}>")
        }
        p.destroy()
        return out
    }

    /** Dumps the buffer to the clipboard-friendly text; used by share. */
    fun readText(): String = read().joinToString("\n")
}

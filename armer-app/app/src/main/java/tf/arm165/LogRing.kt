package tf.arm165

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Log source for the log page: follows `logcat` and keeps the tags this feature
 * runs on in memory. An app may always read its OWN log entries (READ_LOGS only
 * gates other apps'), so no permission is needed here.
 *
 * Two tag sets matter for the idle feature:
 *  - `Arm165`      ours: park / restore / arm / release decisions
 *  - `RefreshRate` the vendor framework's own rate-change log; when the panel
 *    did not go where we asked, this says who won.
 *
 * This used to fork `logcat -d` on every repaint, from the main thread, on a
 * timer. Now one process streams and every line it produces is an event the
 * page can repaint on, so the view follows the log instead of sampling it.
 */
object LogRing {

    private const val MAX_LINES = 800

    /** Oldest first. Guarded by itself; snapshots are copies. */
    private val lines = ArrayDeque<String>()

    @Volatile private var process: Process? = null
    @Volatile private var reader: Thread? = null

    /** Called on the reader thread for each new line; the page coalesces. */
    @Volatile var onLine: (() -> Unit)? = null

    /**
     * Starts following the log, if it is not already. Idempotent, so both the
     * page opening and the live switch turning on may call it.
     *
     * `-T` prints the most recent lines and then follows, which is what makes
     * one process serve both the history and the stream. `-v time` gives the
     * "09-17 14:22:03.123 I/Arm165(1234): text" shape the page displays.
     */
    @Synchronized
    fun start() {
        if (reader?.isAlive == true) return
        val p = try {
            Runtime.getRuntime().exec(
                arrayOf("logcat", "-v", "time", "-T", MAX_LINES.toString(),
                        "-s", "Arm165:V", "RefreshRate:V")
            )
        } catch (t: Throwable) {
            synchronized(lines) { lines.addLast("<logcat unavailable: ${t.message}>") }
            onLine?.invoke()
            return
        }
        process = p
        reader = Thread({ follow(p) }, "arm165-log").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /** Stops following. The buffer stays, and [start] refills it from `-T`. */
    @Synchronized
    fun stop() {
        onLine = null
        process?.destroy()
        process = null
        reader = null
    }

    private fun follow(p: Process) {
        try {
            BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                r.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    synchronized(lines) {
                        lines.addLast(line)
                        while (lines.size > MAX_LINES) lines.removeFirst()
                    }
                    onLine?.invoke()
                }
            }
        } catch (t: Throwable) {
            // A destroyed process closing its pipe lands here; that is a stop,
            // not a failure, and the next start() opens a new one.
        }
    }

    /** Oldest first, as a copy: the reader thread keeps appending to the real one. */
    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    /**
     * Empties the device buffer and ours, then follows again from nothing.
     * Everything before this call is gone for good, which is what the Clear
     * button on the page promises.
     */
    fun clear() {
        try { Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor() } catch (_: Throwable) {}
        val listener = onLine
        stop()
        synchronized(lines) { lines.clear() }
        onLine = listener
        start()
    }

    /** The buffer as text; used by share. */
    fun readText(): String = snapshot().joinToString("\n")
}

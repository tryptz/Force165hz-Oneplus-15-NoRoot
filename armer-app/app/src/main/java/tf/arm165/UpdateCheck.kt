package tf.arm165

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks GitHub for the latest release and offers it when it is newer than this
 * build. Read-only and anonymous: one GET to the public releases API, nothing
 * sent but the request itself. It runs on its own thread, never the worker the
 * votes go out on, so a slow network cannot hold a vote up.
 */
object UpdateCheck {

    private const val TAG = "Arm165Update"
    private const val API =
        "https://api.github.com/repos/tryptz/Force165hz-Oneplus-15-NoRoot/releases/latest"

    /** The asset the README's download link names; the release page if it is missing. */
    private const val APK_ASSET = "arm165.apk"

    private const val KEY_LAST = "update_last_check"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    class Release(val version: String, val page: String, val apk: String?)

    fun installed(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    /** Once a day at most, so opening the app is not a network call every time. */
    fun due(prefs: SharedPreferences): Boolean =
        System.currentTimeMillis() - prefs.getLong(KEY_LAST, 0L) > DAY_MS

    /** Fetches on a thread of its own and hands the answer back on the main thread. */
    fun check(activity: Activity, prefs: SharedPreferences, done: (Release?) -> Unit) {
        Thread {
            val release = fetch()
            if (release != null) prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
            activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) done(release) }
        }.start()
    }

    /** Null when GitHub could not be reached or answered with something unreadable. */
    private fun fetch(): Release? = try {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "arm165")
        }
        try {
            if (conn.responseCode != 200) {
                Log.w(TAG, "latest release: HTTP ${conn.responseCode}")
                null
            } else {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val assets = json.optJSONArray("assets")
                val apk = (0 until (assets?.length() ?: 0))
                    .map { assets!!.getJSONObject(it) }
                    .firstOrNull { it.optString("name") == APK_ASSET }
                    ?.optString("browser_download_url")
                Release(
                    version = json.getString("tag_name").removePrefix("v"),
                    page = json.getString("html_url"),
                    apk = apk?.takeIf { it.isNotEmpty() },
                )
            }
        } finally {
            conn.disconnect()
        }
    } catch (t: Throwable) {
        Log.w(TAG, "latest release: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    /** Numeric, part by part: 1.1.10 is newer than 1.1.9, and 1.1 equals 1.1.0. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun offer(activity: Activity, release: Release) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_title)
            .setMessage(activity.getString(R.string.update_body, release.version, installed(activity)))
            .setPositiveButton(R.string.update_download) { _, _ -> open(activity, release.apk ?: release.page) }
            .setNeutralButton(R.string.update_notes) { _, _ -> open(activity, release.page) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun open(activity: Activity, url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: ActivityNotFoundException) {
            Log.w(TAG, "no browser for $url")
        }
    }
}

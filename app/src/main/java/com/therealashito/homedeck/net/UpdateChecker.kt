package com.therealashito.homedeck.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.therealashito.homedeck.debug.AppLog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the public GitHub releases of TheRealAshito/homedeck for a newer
 * version. This is the ONLY outbound request the app makes that is not to a
 * service the user added: it reads public release metadata and sends nothing
 * else. Automatic checks are opt-in (Settings) and throttled to one per day;
 * the Settings button checks on demand.
 */
object UpdateChecker {

    const val LATEST_URL = "https://api.github.com/repos/TheRealAshito/homedeck/releases/latest"

    data class Release(
        val tag: String,
        val url: String,
        val apkUrl: String?,
        val notes: String
    )

    private val VERSION = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)")

    /** Pure semver-ish compare. Anything unparseable never claims an update. */
    fun isNewer(remoteTag: String, currentVersion: String): Boolean {
        val remote = triple(remoteTag) ?: return false
        val current = triple(currentVersion) ?: return false
        for (i in 0..2) {
            if (remote[i] != current[i]) return remote[i] > current[i]
        }
        return false
    }

    private fun triple(raw: String): List<Int>? {
        val match = VERSION.find(raw.trim()) ?: return null
        val (major, minor, patch) = match.destructured
        return listOf(major.toInt(), minor.toInt(), patch.toInt())
    }

    /** Pure parse of a GitHub /releases/latest response. */
    fun parseLatest(json: String): Release? {
        return try {
            val obj = JSONObject(json)
            val tag = obj.optString("tag_name")
            val url = obj.optString("html_url")
            if (tag.isBlank() || url.isBlank()) return null
            var apkUrl: String? = null
            val assets = obj.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name")
                    val download = asset.optString("browser_download_url")
                    if (name.endsWith(".apk") && download.isNotBlank()) {
                        apkUrl = download
                        break
                    }
                }
            }
            Release(tag, url, apkUrl, obj.optString("body"))
        } catch (e: Exception) {
            null
        }
    }

    /** Network call; run off the main thread. */
    fun fetchLatest(timeoutMs: Int = 5000): Release? = try {
        val conn = URL(LATEST_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "Homedeck-Updater")
        try {
            if (conn.responseCode != 200) {
                AppLog.w("Update", "GitHub returned ${conn.responseCode}")
                null
            } else {
                parseLatest(conn.inputStream.readBytes().decodeToString())
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        AppLog.w("Update", "update check failed: ${e.message}")
        null
    }

    fun openRelease(context: Context, release: Release) {
        val target = release.apkUrl ?: release.url
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
        } catch (e: Exception) {
            AppLog.w("Update", "no app can open $target")
        }
    }
}

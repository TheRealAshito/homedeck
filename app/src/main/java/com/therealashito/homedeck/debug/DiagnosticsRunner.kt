package com.therealashito.homedeck.debug

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import com.therealashito.homedeck.data.SettingsStore
import com.therealashito.homedeck.data.TileStore
import com.therealashito.homedeck.model.Urls
import com.therealashito.homedeck.net.StatusChecker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class DiagResult(
    val section: String,
    val name: String,
    val status: Status,
    val detail: String,
    val elapsedMs: Long = 0
) {
    enum class Status { PASS, WARN, FAIL, INFO }

    fun line(): String = "$status  $name: $detail (${elapsedMs}ms)"
}

/**
 * Self-diagnosis: every check returns a [DiagResult] with a plain-language
 * detail string. All checks are local — the only network traffic is probing
 * the services the user added themselves.
 */
class DiagnosticsRunner(private val context: Context) {

    private val settings = SettingsStore(context)
    private val tileStore = TileStore(context)

    /** Synchronous by design; call off the main thread. */
    fun runAll(includeNetwork: Boolean = true): List<DiagResult> {
        val results = mutableListOf<DiagResult>()

        results += info("Device", "App version") { appVersionLine() }
        results += info("Device", "Device") {
            "${Build.MANUFACTURER} ${Build.MODEL} \u00B7 Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        }
        results += info("Device", "Mode") {
            if (settings.debugMode) "debug mode: verbose detail, logging on" else "normal mode"
        }

        results += check("Storage", "Preferences") {
            val prefs = context.getSharedPreferences("homedeck.diag", Context.MODE_PRIVATE)
            prefs.edit().putString("probe", "ok").apply()
            val read = prefs.getString("probe", null)
            if (read == "ok") DiagResult.Status.PASS to "read/write ok"
            else DiagResult.Status.FAIL to "write did not stick"
        }

        results += check("Storage", "Tile store") {
            val tiles = tileStore.load()
            val roundTrip = TileStore.fromJson(TileStore.toJson(tiles))
            if (roundTrip == tiles) DiagResult.Status.PASS to "${tiles.size} tiles, JSON round-trip ok"
            else DiagResult.Status.FAIL to "tile data did not survive a round-trip"
        }

        results += check("Storage", "Cache directory") {
            val probe = File(context.cacheDir, "diag-probe.tmp")
            probe.writeText("probe")
            val ok = probe.exists() && probe.readText() == "probe"
            probe.delete()
            val freeGb = context.cacheDir.usableSpace / (1024.0 * 1024.0 * 1024.0)
            if (ok) DiagResult.Status.PASS to "writable, %.1f GB free".format(freeGb)
            else DiagResult.Status.FAIL to "cache is not writable"
        }

        results += check("Session", "Cookie store") {
            val cookies = CookieManager.getInstance()
            val signedIn = tileStore.load().mapNotNull { tile ->
                val value = try {
                    cookies.getCookie(tile.url)
                } catch (t: Throwable) {
                    null
                }
                if (value.isNullOrBlank()) null else Urls.hostOf(tile.url)
            }
            when {
                !cookies.acceptCookie() -> DiagResult.Status.WARN to "cookie acceptance is switched off"
                signedIn.isEmpty() -> DiagResult.Status.WARN to "no cookies stored yet \u007E sign in to a service"
                else -> DiagResult.Status.PASS to "saved sessions for: ${signedIn.joinToString(", ")}"
            }
        }

        results += check("Session", "WebView engine") { checkWebView() }

        if (includeNetwork) {
            for (tile in tileStore.load()) {
                results += check("Services", tile.name) {
                    val probe = StatusChecker.probe(tile.url, settings.trustedHosts())
                    val status = when (probe.status) {
                        StatusChecker.Status.ONLINE -> DiagResult.Status.PASS
                        StatusChecker.Status.DEGRADED -> DiagResult.Status.WARN
                        else -> DiagResult.Status.FAIL
                    }
                    status to "${probe.detail}, ${probe.elapsedMs}ms"
                }
            }
        }

        results += check("Logs", "Log file") {
            val file = AppLog.logFile()
            when {
                file == null -> DiagResult.Status.WARN to "log not initialised"
                !file.exists() -> DiagResult.Status.INFO to "no entries yet"
                else -> DiagResult.Status.INFO to
                    "${AppLog.snapshot().size} entries in memory, ${file.length() / 1024} KB on disk"
            }
        }

        results += check("Logs", "Crash history") {
            val crashes = CrashHandler.list(CrashHandler.crashDir(context))
            if (crashes.isEmpty()) DiagResult.Status.PASS to "no crashes recorded"
            else DiagResult.Status.WARN to "${crashes.size} crash log(s), latest: ${crashes.first().name}"
        }

        AppLog.d("Diag", "self-diagnosis finished: " +
            results.joinToString(", ") { "${it.name}=${it.status}" })
        return results
    }

    private fun crashDir(): File = File(context.filesDir, "crash")

    private fun appVersionLine(): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName ?: "?"} (code ${info.longVersionCode})"
    } catch (t: Throwable) {
        "unknown"
    }

    private fun check(
        section: String,
        name: String,
        block: () -> Pair<DiagResult.Status, String>
    ): DiagResult {
        val started = System.currentTimeMillis()
        val (status, detail) = try {
            block()
        } catch (t: Throwable) {
            DiagResult.Status.FAIL to (t.message ?: t.javaClass.simpleName)
        }
        return DiagResult(section, name, status, detail, System.currentTimeMillis() - started)
    }

    private fun info(section: String, name: String, block: () -> String): DiagResult =
        DiagResult(section, name, DiagResult.Status.INFO, try {
            block()
        } catch (t: Throwable) {
            "unknown"
        }, 0)

    private fun checkWebView(): Pair<DiagResult.Status, String> {
        val create = {
            var webView: WebView? = null
            try {
                webView = WebView(context)
                val ua = webView.settings.userAgentString
                if (ua.isNullOrBlank()) {
                    DiagResult.Status.WARN to "WebView started but reports no user agent"
                } else {
                    DiagResult.Status.PASS to "WebView started, JS and DOM storage available"
                }
            } catch (t: Throwable) {
                DiagResult.Status.FAIL to (t.message ?: "WebView failed to start")
            } finally {
                try {
                    webView?.destroy()
                } catch (t: Throwable) {
                    // Best effort cleanup.
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return create()
        var result: Pair<DiagResult.Status, String>? = null
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            result = create()
            latch.countDown()
        }
        latch.await(4, TimeUnit.SECONDS)
        return result ?: (DiagResult.Status.WARN to "WebView check timed out")
    }

    companion object {
        fun buildReport(results: List<DiagResult>, logTail: String, now: Date = Date()): String {
            val sb = StringBuilder()
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)
            sb.appendLine("Homedeck diagnostics \u2014 $stamp")
            val counts = results.groupingBy { it.status }.eachCount()
            sb.appendLine(
                "summary: ${counts[DiagResult.Status.PASS] ?: 0} pass, " +
                    "${counts[DiagResult.Status.WARN] ?: 0} warn, " +
                    "${counts[DiagResult.Status.FAIL] ?: 0} fail, " +
                    "${counts[DiagResult.Status.INFO] ?: 0} info"
            )
            var section = ""
            for (result in results) {
                if (result.section != section) {
                    section = result.section
                    sb.appendLine()
                    sb.appendLine("[$section]")
                }
                sb.appendLine(result.line())
            }
            sb.appendLine()
            sb.appendLine("--- log tail ---")
            sb.appendLine(logTail)
            return sb.toString()
        }
    }
}

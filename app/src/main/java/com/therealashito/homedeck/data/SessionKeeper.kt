package com.therealashito.homedeck.data

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import com.therealashito.homedeck.debug.AppLog
import com.therealashito.homedeck.model.Urls
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Keeps web sessions alive across app restarts, process kills, and idle gaps:
 *
 * 1. **Immortal cookies (non-destructive)**: snapshots live cookies and restores
 *    cookies that vanish across process kills (session-only cookies) before a
 *    service loads, without overwriting attributes (`HttpOnly`, `SameSite`,
 *    `Path`, `Domain`) on cookies that are already live in `CookieManager`.
 *    When a server or page deletes a cookie while open, the backup is synced
 *    so expired cookies are never resurrected.
 * 2. **DOM storage backup**: snapshots `window.localStorage` and
 *    `window.sessionStorage` per actual `window.location.origin` and syncs
 *    deletions so expired auth/crypto keys cannot cause reload loops.
 * 3. **Keep-alive cookie renewal**: feeds valid `Set-Cookie` headers received
 *    during status probes back into the cookie jar and honours cookie deletions.
 */
object SessionKeeper {

    const val ONE_YEAR_SECONDS = 31_536_000L

    private const val PREFS_NAME = "homedeck.session_keeper"
    private const val KEY_COOKIES = "cookies_v1"
    private const val KEY_LOCAL_STORAGE = "local_v1"
    private const val KEY_SESSION_STORAGE = "session_v1"

    private val RESERVED_COOKIE_ATTRS = setOf(
        "path", "domain", "expires", "max-age", "samesite", "secure", "httponly", "priority"
    )

    /** Parses a `Cookie` header (`"a=1; b=2"`) into ordered name-value pairs. */
    fun parseCookies(cookieHeader: String?): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        if (cookieHeader.isNullOrBlank()) return out
        for (part in cookieHeader.split(";")) {
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            val name = trimmed.substring(0, eq).trim()
            val value = trimmed.substring(eq + 1).trim()
            if (name.isEmpty() || value.isEmpty()) continue
            if (RESERVED_COOKIE_ATTRS.contains(name.lowercase())) continue
            out[name] = value
        }
        return out
    }

    /** Formats a cookie map back into a `Cookie` request header. */
    fun formatCookieHeader(cookies: Map<String, String>): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /**
     * Merges backed-up cookies with live cookies. Live non-empty values update
     * existing keys; backed-up cookies that vanished from the live jar are kept.
     */
    fun mergeCookies(
        backedUp: Map<String, String>,
        live: Map<String, String>
    ): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in backedUp) {
            if (k.isNotBlank() && v.isNotBlank()) out[k] = v
        }
        for ((k, v) in live) {
            if (k.isNotBlank() && v.isNotBlank()) out[k] = v
        }
        return out
    }

    /** Returns cookies present in [backedUp] that are absent from [live]. */
    fun missingCookies(
        backedUp: Map<String, String>,
        live: Map<String, String>
    ): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in backedUp) {
            if (k.isNotBlank() && v.isNotBlank() && live[k].isNullOrBlank()) {
                out[k] = v
            }
        }
        return out
    }

    /** Builds 1-year `Set-Cookie` headers to persist session cookies in WebView. */
    fun buildSetCookieHeaders(
        cookies: Map<String, String>,
        secure: Boolean,
        maxAgeSeconds: Long = ONE_YEAR_SECONDS
    ): List<String> {
        val suffix = buildString {
            append("; Path=/; Max-Age=")
            append(maxAgeSeconds)
            append("; SameSite=Lax")
            if (secure) append("; Secure")
        }
        return cookies.map { (name, value) -> "$name=$value$suffix" }
    }

    /**
     * Extracts the cookie name from a `Set-Cookie` header, or null if malformed.
     */
    fun setCookieName(rawSetCookie: String): String? {
        val first = rawSetCookie.substringBefore(';').trim()
        val eq = first.indexOf('=')
        if (eq <= 0) return null
        val name = first.substring(0, eq).trim()
        return name.ifEmpty { null }
    }

    /**
     * True when a server `Set-Cookie` header from a keep-alive probe sets or
     * renews a cookie rather than clearing/expiring it.
     */
    fun isRenewalSetCookie(rawSetCookie: String): Boolean {
        val segments = rawSetCookie.split(";")
        if (segments.isEmpty()) return false
        val first = segments[0].trim()
        val eq = first.indexOf('=')
        if (eq <= 0) return false
        val name = first.substring(0, eq).trim()
        val value = first.substring(eq + 1).trim().trim('"')
        if (name.isEmpty() || value.isEmpty() || value.equals("deleted", ignoreCase = true)) {
            return false
        }
        for (i in 1 until segments.size) {
            val attr = segments[i].trim().lowercase()
            if (attr == "max-age=0" || attr.startsWith("max-age=-")) {
                return false
            }
        }
        return true
    }

    /**
     * Unwraps the JSON-encoded string returned by `WebView.evaluateJavascript`.
     */
    fun unwrapEvalResult(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        if (trimmed.isEmpty() || trimmed == "null" || trimmed == "undefined") return null
        return try {
            when (val parsed = JSONTokener(trimmed).nextValue()) {
                is String -> parsed
                is JSONObject -> parsed.toString()
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Merges two JSON objects of `key -> value` strings, keeping non-blank values. */
    fun mergeStorageJson(existingJson: String?, incomingJson: String?): String {
        val merged = JSONObject()
        if (!existingJson.isNullOrBlank()) {
            try {
                val oldObj = JSONObject(existingJson)
                for (k in oldObj.keys()) {
                    val v = oldObj.optString(k, "")
                    if (k.isNotBlank() && v.isNotEmpty()) merged.put(k, v)
                }
            } catch (_: Exception) {
            }
        }
        if (!incomingJson.isNullOrBlank()) {
            try {
                val newObj = JSONObject(incomingJson)
                for (k in newObj.keys()) {
                    val v = newObj.optString(k, "")
                    if (k.isNotBlank() && v.isNotEmpty()) merged.put(k, v)
                }
            } catch (_: Exception) {
            }
        }
        return merged.toString()
    }

    /** JS expression that serializes `origin`, `localStorage`, and `sessionStorage`. */
    fun buildStorageCaptureJs(): String = """
        (function() {
            try {
                var l = {}, s = {};
                if (window.localStorage) {
                    for (var i = 0; i < localStorage.length; i++) {
                        var k = localStorage.key(i);
                        if (k) l[k] = localStorage.getItem(k);
                    }
                }
                if (window.sessionStorage) {
                    for (var j = 0; j < sessionStorage.length; j++) {
                        var sk = sessionStorage.key(j);
                        if (sk) s[sk] = sessionStorage.getItem(sk);
                    }
                }
                return JSON.stringify({origin: window.location.origin, local: l, session: s});
            } catch (e) {
                return "";
            }
        })();
    """.trimIndent()

    /**
     * JS expression that restores missing keys into `localStorage` and
     * `sessionStorage` without overwriting keys the page already set.
     * Uses `JSON.parse(JSONObject.quote(...))` so U+2028/U+2029 or control
     * characters in values never cause JS syntax errors.
     */
    fun buildStorageRestoreJs(localJson: String?, sessionJson: String?): String {
        val quotedLocal = JSONObject.quote(if (localJson.isNullOrBlank()) "{}" else localJson)
        val quotedSession = JSONObject.quote(if (sessionJson.isNullOrBlank()) "{}" else sessionJson)
        return """
            (function() {
                try {
                    var l = JSON.parse($quotedLocal);
                    var s = JSON.parse($quotedSession);
                    var restored = 0;
                    if (window.localStorage && l) {
                        Object.keys(l).forEach(function(k) {
                            if (localStorage.getItem(k) === null && l[k] !== undefined && l[k] !== null) {
                                localStorage.setItem(k, String(l[k]));
                                restored++;
                            }
                        });
                    }
                    if (window.sessionStorage && s) {
                        Object.keys(s).forEach(function(k) {
                            if (sessionStorage.getItem(k) === null && s[k] !== undefined && s[k] !== null) {
                                sessionStorage.setItem(k, String(s[k]));
                                restored++;
                            }
                        });
                    }
                    return restored;
                } catch (e) {
                    return -1;
                }
            })();
        """.trimIndent()
    }

    // ---- Android Context integration ----

    /**
     * Syncs our cookie backup with what is currently live in `CookieManager`
     * for [url] WITHOUT overwriting existing live cookies (preserving `HttpOnly`,
     * `SameSite`, `Path`, and `Domain` attributes).
     *
     * Because this replaces the backed-up set for [origin], any cookie the
     * server or app intentionally deleted (e.g. logout / expired session) is
     * pruned from the backup instead of being resurrected.
     */
    @Synchronized
    fun syncCookiesFromJar(context: Context, url: String) {
        val origin = Urls.originOf(url)
        val prefs = prefs(context)
        val liveRaw = try {
            CookieManager.getInstance().getCookie(url)
        } catch (t: Throwable) {
            return
        }
        val live = parseCookies(liveRaw)
        if (live.isEmpty()) {
            removeOriginKey(prefs, KEY_COOKIES, origin)
        } else {
            saveOriginCookies(prefs, origin, live)
        }
    }

    /**
     * Legacy helper preserved for startup/cold-start paths: snapshots live
     * cookies without touching already-present cookies in `CookieManager`.
     */
    @Synchronized
    fun immortalizeCookies(context: Context, url: String) {
        val origin = Urls.originOf(url)
        val prefs = prefs(context)
        val backedUp = loadOriginCookies(prefs, origin)
        val liveRaw = try {
            CookieManager.getInstance().getCookie(url)
        } catch (t: Throwable) {
            null
        }
        val live = parseCookies(liveRaw)
        val merged = mergeCookies(backedUp, live)
        if (merged.isEmpty()) return
        saveOriginCookies(prefs, origin, merged)
    }

    /**
     * Restores ONLY backed-up cookies that are currently missing from
     * `CookieManager` (for example after an OS process kill wipes session-only
     * cookies). Should be called on cold open BEFORE loading the service,
     * never repeatedly mid-navigation.
     */
    @Synchronized
    fun restoreIfMissing(context: Context, url: String): Int {
        val origin = Urls.originOf(url)
        val secure = origin.startsWith("https://")
        val prefs = prefs(context)
        val backedUp = loadOriginCookies(prefs, origin)
        if (backedUp.isEmpty()) return 0

        val liveRaw = try {
            CookieManager.getInstance().getCookie(url)
        } catch (t: Throwable) {
            null
        }
        val live = parseCookies(liveRaw)
        val missing = missingCookies(backedUp, live)
        if (missing.isEmpty()) return 0

        return try {
            val cm = CookieManager.getInstance()
            for (header in buildSetCookieHeaders(missing, secure)) {
                cm.setCookie(origin, header)
            }
            cm.flush()
            AppLog.i("Session", "$origin: restored ${missing.size} backed-up cookie(s)")
            missing.size
        } catch (t: Throwable) {
            0
        }
    }

    @Synchronized
    fun getCookieHeaderForProbe(context: Context, url: String): String? {
        val liveRaw = try {
            CookieManager.getInstance().getCookie(url)
        } catch (t: Throwable) {
            null
        }
        if (!liveRaw.isNullOrBlank()) return liveRaw
        val origin = Urls.originOf(url)
        val backedUp = loadOriginCookies(prefs(context), origin)
        return if (backedUp.isEmpty()) null else formatCookieHeader(backedUp)
    }

    @Synchronized
    fun applyServerSetCookies(context: Context, url: String, setCookies: List<String>) {
        if (setCookies.isEmpty()) return
        val origin = Urls.originOf(url)
        val prefs = prefs(context)
        val backedUp = loadOriginCookies(prefs, origin)
        var backupChanged = false

        val renewals = mutableListOf<String>()
        for (header in setCookies) {
            if (isRenewalSetCookie(header)) {
                renewals.add(header)
                val firstPair = header.substringBefore(';')
                backedUp.putAll(parseCookies(firstPair))
                backupChanged = true
            } else {
                // Server explicitly deleted/expired this cookie — prune from backup too.
                val name = setCookieName(header)
                if (name != null && backedUp.remove(name) != null) {
                    backupChanged = true
                }
            }
        }

        if (renewals.isNotEmpty()) {
            try {
                val cm = CookieManager.getInstance()
                for (header in renewals) {
                    cm.setCookie(url, header)
                }
                cm.flush()
            } catch (_: Throwable) {
            }
        }
        if (backupChanged) {
            if (backedUp.isEmpty()) {
                removeOriginKey(prefs, KEY_COOKIES, origin)
            } else {
                saveOriginCookies(prefs, origin, backedUp)
            }
        }
    }

    /**
     * Replaces the saved DOM storage snapshot for the page's actual origin
     * (`payload.origin`, falling back to [fallbackUrl]) so keys deleted by the
     * app stay deleted and never trigger an infinite expired-key reload loop.
     */
    @Synchronized
    fun saveCapturedDomStorage(context: Context, fallbackUrl: String, rawEvalResult: String?) {
        val unwrapped = unwrapEvalResult(rawEvalResult) ?: return
        val payload = try {
            JSONObject(unwrapped)
        } catch (e: Exception) {
            return
        }
        val payloadOrigin = payload.optString("origin").takeIf {
            it.startsWith("http://") || it.startsWith("https://")
        }
        val origin = Urls.originOf(payloadOrigin ?: fallbackUrl)
        val prefs = prefs(context)

        val localObj = payload.optJSONObject("local")
        if (localObj != null) {
            if (localObj.length() > 0) {
                saveOriginStorage(prefs, KEY_LOCAL_STORAGE, origin, localObj.toString())
            } else {
                removeOriginKey(prefs, KEY_LOCAL_STORAGE, origin)
            }
        }
        val sessionObj = payload.optJSONObject("session")
        if (sessionObj != null) {
            if (sessionObj.length() > 0) {
                saveOriginStorage(prefs, KEY_SESSION_STORAGE, origin, sessionObj.toString())
            } else {
                removeOriginKey(prefs, KEY_SESSION_STORAGE, origin)
            }
        }
    }

    @Synchronized
    fun buildRestoreDomStorageScript(context: Context, url: String): String? {
        val origin = Urls.originOf(url)
        val prefs = prefs(context)
        val local = loadOriginStorage(prefs, KEY_LOCAL_STORAGE, origin)
        val session = loadOriginStorage(prefs, KEY_SESSION_STORAGE, origin)
        if (local.isNullOrBlank() && session.isNullOrBlank()) return null
        return buildStorageRestoreJs(local, session)
    }

    @Synchronized
    fun clearForUrl(context: Context, url: String) {
        val origin = Urls.originOf(url)
        val prefs = prefs(context)
        removeOriginKey(prefs, KEY_COOKIES, origin)
        removeOriginKey(prefs, KEY_LOCAL_STORAGE, origin)
        removeOriginKey(prefs, KEY_SESSION_STORAGE, origin)
    }

    @Synchronized
    fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
    }

    @Synchronized
    fun summary(context: Context): Pair<Int, Int> {
        val prefs = prefs(context)
        val cookiesCount = try {
            JSONObject(prefs.getString(KEY_COOKIES, "{}") ?: "{}").length()
        } catch (_: Exception) {
            0
        }
        val localOrigins = try {
            JSONObject(prefs.getString(KEY_LOCAL_STORAGE, "{}") ?: "{}")
                .keys().asSequence().toSet()
        } catch (_: Exception) {
            emptySet()
        }
        val sessionOrigins = try {
            JSONObject(prefs.getString(KEY_SESSION_STORAGE, "{}") ?: "{}")
                .keys().asSequence().toSet()
        } catch (_: Exception) {
            emptySet()
        }
        return cookiesCount to (localOrigins + sessionOrigins).size
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadOriginCookies(
        prefs: SharedPreferences,
        origin: String
    ): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val raw = prefs.getString(KEY_COOKIES, null) ?: return out
        return try {
            val root = JSONObject(raw)
            val obj = root.optJSONObject(origin) ?: return out
            for (k in obj.keys()) {
                val v = obj.optString(k, "")
                if (k.isNotBlank() && v.isNotBlank()) out[k] = v
            }
            out
        } catch (e: Exception) {
            out
        }
    }

    private fun saveOriginCookies(
        prefs: SharedPreferences,
        origin: String,
        cookies: Map<String, String>
    ) {
        val root = try {
            JSONObject(prefs.getString(KEY_COOKIES, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        val obj = JSONObject()
        for ((k, v) in cookies) {
            obj.put(k, v)
        }
        root.put(origin, obj)
        prefs.edit().putString(KEY_COOKIES, root.toString()).apply()
    }

    private fun loadOriginStorage(
        prefs: SharedPreferences,
        prefKey: String,
        origin: String
    ): String? {
        val raw = prefs.getString(prefKey, null) ?: return null
        return try {
            val root = JSONObject(raw)
            root.optJSONObject(origin)?.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun saveOriginStorage(
        prefs: SharedPreferences,
        prefKey: String,
        origin: String,
        storageJson: String
    ) {
        val root = try {
            JSONObject(prefs.getString(prefKey, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        try {
            val parsed = JSONObject(storageJson)
            if (parsed.length() > 0) {
                root.put(origin, parsed)
                prefs.edit().putString(prefKey, root.toString()).apply()
            }
        } catch (_: Exception) {
        }
    }

    private fun removeOriginKey(prefs: SharedPreferences, prefKey: String, origin: String) {
        val raw = prefs.getString(prefKey, null) ?: return
        try {
            val root = JSONObject(raw)
            root.remove(origin)
            prefs.edit().putString(prefKey, root.toString()).apply()
        } catch (_: Exception) {
        }
    }
}

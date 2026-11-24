package com.therealashito.homedeck.debug

import android.content.Context
import com.therealashito.homedeck.model.Urls
import org.json.JSONObject

/**
 * Session continuity journal: records whether each service still has cookies
 * and, crucially, WHEN they vanish. Lets Diagnostics distinguish "the server
 * expired my session" (cookies still present) from "something wiped my
 * cookies" (cookies gone, all services at once). Local-only, like all logs.
 */
object SessionJournal {

    data class Entry(
        val present: Boolean,
        val since: Long,
        val lastSeen: Long,
        val losses: Int
    )

    /** Pure state transition (unit tested). Returns the new entry and an
     *  optional event message for the log. */
    fun observe(prev: Entry?, present: Boolean, now: Long): Pair<Entry, String?> {
        if (prev == null) {
            val entry = Entry(present, now, if (present) now else 0L, 0)
            return entry to if (present) "cookies appeared" else null
        }
        return when {
            present && !prev.present ->
                Entry(true, now, now, prev.losses) to "signed in again"
            present ->
                Entry(true, prev.since, now, prev.losses) to null
            prev.present ->
                Entry(false, now, prev.lastSeen, prev.losses + 1) to
                    "cookies VANISHED (were present since ${prev.since}, last seen ${prev.lastSeen})"
            else ->
                Entry(false, prev.since, prev.lastSeen, prev.losses) to null
        }
    }

    @Synchronized
    fun record(context: Context, host: String, present: Boolean) {
        val prefs = context.applicationContext
            .getSharedPreferences("homedeck.session", Context.MODE_PRIVATE)
        val map = load(prefs)
        val (entry, event) = observe(map[host], present, System.currentTimeMillis())
        map[host] = entry
        save(prefs, map)
        if (event != null) {
            if (entry.present) {
                AppLog.i("Session", "$host: $event")
            } else {
                AppLog.w("Session", "$host: $event")
            }
        }
    }

    /** Convenience: records cookie presence for the given service URL (keyed by origin). */
    fun recordUrl(context: Context, url: String) {
        val origin = Urls.originOf(url)
        val present = try {
            android.webkit.CookieManager.getInstance().getCookie(url)?.isNotBlank() == true
        } catch (t: Throwable) {
            false
        }
        record(context, origin, present)
    }

    @Synchronized
    fun snapshot(context: Context): Map<String, Entry> =
        load(context.applicationContext.getSharedPreferences("homedeck.session", Context.MODE_PRIVATE))

    private fun load(prefs: android.content.SharedPreferences): MutableMap<String, Entry> {
        val out = mutableMapOf<String, Entry>()
        val raw = prefs.getString("journal", null) ?: return out
        return try {
            val obj = JSONObject(raw)
            for (host in obj.keys()) {
                val e = obj.optJSONObject(host) ?: continue
                out[host] = Entry(
                    present = e.optBoolean("p"),
                    since = e.optLong("s"),
                    lastSeen = e.optLong("l"),
                    losses = e.optInt("k")
                )
            }
            out
        } catch (e: Exception) {
            out
        }
    }

    private fun save(prefs: android.content.SharedPreferences, map: Map<String, Entry>) {
        val obj = JSONObject()
        for ((host, e) in map) {
            val o = JSONObject()
            o.put("p", e.present)
            o.put("s", e.since)
            o.put("l", e.lastSeen)
            o.put("k", e.losses)
            obj.put(host, o)
        }
        prefs.edit().putString("journal", obj.toString()).apply()
    }
}

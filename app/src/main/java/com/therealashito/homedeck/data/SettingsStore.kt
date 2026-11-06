package com.therealashito.homedeck.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

/** Small preference wrapper for app-wide behaviour. */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("homedeck.settings", Context.MODE_PRIVATE)

    var themeMode: Int
        get() = prefs.getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = prefs.edit().putInt(KEY_THEME, value).apply()

    var statusChecks: Boolean
        get() = prefs.getBoolean(KEY_STATUS_CHECKS, true)
        set(value) = prefs.edit().putBoolean(KEY_STATUS_CHECKS, value).apply()

    var cacheFirst: Boolean
        get() = prefs.getBoolean(KEY_CACHE_FIRST, false)
        set(value) = prefs.edit().putBoolean(KEY_CACHE_FIRST, value).apply()

    /**
     * Debug mode: verbose per-service detail, logging, and a startup guard
     * that keeps the app open with a stack trace instead of crash-looping.
     * Defaults to ON for the early development phase.
     */
    var debugMode: Boolean
        get() = prefs.getBoolean(KEY_DEBUG, true)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG, value).apply()

    /**
     * Hosts whose certificate the user explicitly accepted in the in-app
     * browser. Only these are exempted from certificate validation anywhere
     * else in the app (e.g. the dashboard status checker).
     */
    fun trustedHosts(): Set<String> = prefs.getStringSet(KEY_TRUSTED_HOSTS, emptySet()) ?: emptySet()

    fun isHostTrusted(host: String): Boolean = trustedHosts().contains(host)

    fun trustHost(host: String) {
        val updated = trustedHosts().toMutableSet()
        updated.add(host)
        prefs.edit().putStringSet(KEY_TRUSTED_HOSTS, updated).apply()
    }

    companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_STATUS_CHECKS = "status_checks"
        const val KEY_CACHE_FIRST = "cache_first"
        const val KEY_DEBUG = "debug_mode"
        const val KEY_TRUSTED_HOSTS = "trusted_hosts"
    }
}

package com.therealashito.homedeck.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

/** Small preference wrapper for app-wide behaviour. */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("homedeck.settings", Context.MODE_PRIVATE)

    /**
     * UI theme: SYSTEM / DARK / LIGHT / AMOLED (pure black). Migrates the
     * old AppCompatDelegate-int based key on first read.
     */
    var uiTheme: Int
        get() {
            if (prefs.contains(KEY_UI_THEME)) return prefs.getInt(KEY_UI_THEME, THEME_SYSTEM)
            val migrated = when (prefs.getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)) {
                AppCompatDelegate.MODE_NIGHT_YES -> THEME_DARK
                AppCompatDelegate.MODE_NIGHT_NO -> THEME_LIGHT
                else -> THEME_SYSTEM
            }
            prefs.edit().putInt(KEY_UI_THEME, migrated).apply()
            return migrated
        }
        set(value) = prefs.edit().putInt(KEY_UI_THEME, value).apply()

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
     * Automatic update checks against the app's own GitHub repo. Opt-in and
     * OFF by default (privacy standing rule); the manual Settings button
     * works regardless. Throttled to at most one check per day.
     */
    var autoUpdateCheck: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPDATE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_UPDATE, value).apply()

    var lastUpdateCheckMs: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()

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
        const val THEME_SYSTEM = 0
        const val THEME_DARK = 1
        const val THEME_LIGHT = 2
        const val THEME_AMOLED = 3

        fun nightModeFor(uiTheme: Int): Int = when (uiTheme) {
            THEME_DARK, THEME_AMOLED -> AppCompatDelegate.MODE_NIGHT_YES
            THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }

        const val KEY_THEME = "theme_mode"
        const val KEY_UI_THEME = "ui_theme"
        const val KEY_STATUS_CHECKS = "status_checks"
        const val KEY_CACHE_FIRST = "cache_first"
        const val KEY_DEBUG = "debug_mode"
        const val KEY_AUTO_UPDATE = "auto_update_check"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check_ms"
        const val KEY_TRUSTED_HOSTS = "trusted_hosts"
    }
}

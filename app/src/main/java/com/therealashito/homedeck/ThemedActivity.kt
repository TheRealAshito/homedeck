package com.therealashito.homedeck

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.therealashito.homedeck.data.SettingsStore

/**
 * Applies the chosen UI theme (System / Dark / Light / AMOLED) before the
 * view tree is created. AMOLED forces night mode on and overlays the pure
 * black theme; the other modes map to AppCompatDelegate night modes.
 */
abstract class ThemedActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val uiTheme = SettingsStore(this).uiTheme
        AppCompatDelegate.setDefaultNightMode(SettingsStore.nightModeFor(uiTheme))
        if (uiTheme == SettingsStore.THEME_AMOLED) {
            setTheme(R.style.Theme_Homedeck_AMOLED)
        }
        super.onCreate(savedInstanceState)
    }
}

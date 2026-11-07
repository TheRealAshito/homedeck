package com.therealashito.homedeck

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.therealashito.homedeck.data.SettingsStore
import com.therealashito.homedeck.debug.AppLog
import com.therealashito.homedeck.net.UpdateChecker
import java.io.File

class SettingsActivity : ThemedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)
        val settings = SettingsStore(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.subtitle = if (settings.debugMode) "debug mode" else "normal mode"

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val themeGroup = findViewById<MaterialButtonToggleGroup>(R.id.themeGroup)
        themeGroup.check(
            when (settings.uiTheme) {
                SettingsStore.THEME_DARK -> R.id.themeDark
                SettingsStore.THEME_LIGHT -> R.id.themeLight
                SettingsStore.THEME_AMOLED -> R.id.themeAmoled
                else -> R.id.themeSystem
            }
        )
        themeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newTheme = when (checkedId) {
                R.id.themeDark -> SettingsStore.THEME_DARK
                R.id.themeLight -> SettingsStore.THEME_LIGHT
                R.id.themeAmoled -> SettingsStore.THEME_AMOLED
                else -> SettingsStore.THEME_SYSTEM
            }
            if (newTheme == settings.uiTheme) return@addOnButtonCheckedListener
            val oldNight = SettingsStore.nightModeFor(settings.uiTheme)
            settings.uiTheme = newTheme
            AppLog.i("Settings", "theme=$newTheme")
            val newNight = SettingsStore.nightModeFor(newTheme)
            // Night-mode changes recreate via the delegate; pure overlay
            // changes (Dark <-> AMOLED) need an explicit recreate.
            if (oldNight == newNight) {
                recreate()
            } else {
                AppCompatDelegate.setDefaultNightMode(newNight)
            }
        }

        val statusSwitch = findViewById<MaterialSwitch>(R.id.statusSwitch)
        statusSwitch.isChecked = settings.statusChecks
        statusSwitch.setOnCheckedChangeListener { _, checked -> settings.statusChecks = checked }

        val cacheSwitch = findViewById<MaterialSwitch>(R.id.cacheSwitch)
        cacheSwitch.isChecked = settings.cacheFirst
        cacheSwitch.setOnCheckedChangeListener { _, checked -> settings.cacheFirst = checked }

        val autoUpdateSwitch = findViewById<MaterialSwitch>(R.id.autoUpdateSwitch)
        autoUpdateSwitch.isChecked = settings.autoUpdateCheck
        autoUpdateSwitch.setOnCheckedChangeListener { _, checked ->
            settings.autoUpdateCheck = checked
            AppLog.i("Settings", "auto update check=$checked")
        }

        val updateResult = findViewById<TextView>(R.id.updateResultText)
        findViewById<MaterialButton>(R.id.checkUpdateBtn).setOnClickListener {
            updateResult.visibility = View.VISIBLE
            updateResult.text = "Checking GitHub\u2026"
            Thread {
                val release = UpdateChecker.fetchLatest()
                runOnUiThread {
                    val current = currentVersion()
                    when {
                        release == null -> {
                            updateResult.text =
                                "Could not reach GitHub. Check the connection or see Diagnostics."
                        }
                        UpdateChecker.isNewer(release.tag, current) -> {
                            updateResult.text =
                                "Update available: ${release.tag} (installed: $current)"
                            MaterialAlertDialogBuilder(this@SettingsActivity)
                                .setTitle("Update ${release.tag} available")
                                .setMessage("You have $current installed. Open the download page?")
                                .setPositiveButton("Download") { _, _ ->
                                    UpdateChecker.openRelease(this@SettingsActivity, release)
                                }
                                .setNegativeButton("Later", null)
                                .show()
                        }
                        else -> {
                            updateResult.text = "Up to date (installed: $current)."
                        }
                    }
                }
            }.start()
        }

        val debugSwitch = findViewById<MaterialSwitch>(R.id.debugSwitch)
        debugSwitch.isChecked = settings.debugMode
        debugSwitch.setOnCheckedChangeListener { _, checked ->
            settings.debugMode = checked
            AppLog.i("Settings", "debug mode=$checked")
            toolbar.subtitle = if (checked) "debug mode" else "normal mode"
        }

        findViewById<MaterialButton>(R.id.diagnosticsBtn).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.clearDataBtn).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Clear cookies & site data?")
                .setMessage("Every service will ask you to sign in again. Tiles and settings are kept.")
                .setPositiveButton("Clear") { _, _ -> clearBrowsingData() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
        findViewById<TextView>(R.id.aboutText).text =
            "Homedeck $version\nCookies, logins and cache stay on this device. " +
                "No analytics, no telemetry, no calls to anything except the services you add."
    }

    private fun currentVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    private fun clearBrowsingData() {
        AppLog.i("Settings", "clearing cookies and site data")
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        WebStorage.getInstance().deleteAllData()
        deleteRecursive(cacheDir)
        Toast.makeText(this, "Cookies and site data cleared", Toast.LENGTH_LONG).show()
    }

    private fun deleteRecursive(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursive(it) }
        file.delete()
    }
}

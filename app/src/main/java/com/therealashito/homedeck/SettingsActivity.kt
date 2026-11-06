package com.therealashito.homedeck

import android.os.Bundle
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
import java.io.File

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)
        val settings = SettingsStore(this)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val themeGroup = findViewById<MaterialButtonToggleGroup>(R.id.themeGroup)
        themeGroup.check(
            when (settings.themeMode) {
                AppCompatDelegate.MODE_NIGHT_YES -> R.id.themeDark
                AppCompatDelegate.MODE_NIGHT_NO -> R.id.themeLight
                else -> R.id.themeSystem
            }
        )
        themeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.themeDark -> AppCompatDelegate.MODE_NIGHT_YES
                R.id.themeLight -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            settings.themeMode = mode
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        val statusSwitch = findViewById<MaterialSwitch>(R.id.statusSwitch)
        statusSwitch.isChecked = settings.statusChecks
        statusSwitch.setOnCheckedChangeListener { _, checked -> settings.statusChecks = checked }

        val cacheSwitch = findViewById<MaterialSwitch>(R.id.cacheSwitch)
        cacheSwitch.isChecked = settings.cacheFirst
        cacheSwitch.setOnCheckedChangeListener { _, checked -> settings.cacheFirst = checked }

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

    private fun clearBrowsingData() {
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

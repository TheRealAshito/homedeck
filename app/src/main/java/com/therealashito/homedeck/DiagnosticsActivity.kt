package com.therealashito.homedeck

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.therealashito.homedeck.data.SettingsStore
import com.therealashito.homedeck.debug.AppLog
import com.therealashito.homedeck.debug.CrashHandler
import com.therealashito.homedeck.debug.DiagResult
import com.therealashito.homedeck.debug.DiagnosticsRunner
import com.therealashito.homedeck.ui.Palette
import java.util.concurrent.Executors

/**
 * Self-diagnosis screen: runs every local check, shows pass/warn/fail with
 * plain-language detail, and lets the user copy or share the report and log.
 * Everything stays on the device unless the user explicitly shares it.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var resultsContainer: LinearLayout
    private lateinit var logView: TextView
    private lateinit var crashView: TextView
    private lateinit var crashCard: View
    private lateinit var runBtn: MaterialButton
    private val executor = Executors.newSingleThreadExecutor()
    private var report: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_diagnostics)

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

        resultsContainer = findViewById(R.id.resultsContainer)
        logView = findViewById(R.id.logView)
        crashView = findViewById(R.id.crashView)
        crashCard = findViewById(R.id.crashCard)
        runBtn = findViewById(R.id.runBtn)

        findViewById<TextView>(R.id.headerText).text = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            "Homedeck ${info.versionName ?: "?"} \u00B7 ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} \u00B7 Android ${android.os.Build.VERSION.RELEASE}"
        } catch (t: Throwable) {
            "Homedeck"
        }

        runBtn.setOnClickListener { runChecks() }
        findViewById<MaterialButton>(R.id.copyLogBtn).setOnClickListener {
            copy("Homedeck log", AppLog.tail(200))
        }
        findViewById<MaterialButton>(R.id.shareReportBtn).setOnClickListener { shareReport() }
        findViewById<MaterialButton>(R.id.clearLogBtn).setOnClickListener {
            AppLog.clear()
            logView.text = "(log cleared)"
            Toast.makeText(this, "Log cleared", Toast.LENGTH_SHORT).show()
        }

        renderCrashHistory()
        logView.text = AppLog.tail(80).ifBlank { "(no log entries yet)" }
        runChecks()
        AppLog.i("Diag", "Diagnostics opened")
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    private fun runChecks() {
        runBtn.isEnabled = false
        resultsContainer.removeAllViews()
        addNote("Running checks\u2026")
        executor.execute {
            val results = try {
                DiagnosticsRunner(this).runAll()
            } catch (t: Throwable) {
                AppLog.e("Diag", "Diagnostics crashed", t)
                emptyList()
            }
            report = DiagnosticsRunner.buildReport(results, AppLog.tail(200))
            runOnUiThread {
                resultsContainer.removeAllViews()
                if (results.isEmpty()) {
                    addNote("Diagnostics failed to run. See the log tail below.")
                } else {
                    render(results)
                }
                logView.text = AppLog.tail(80).ifBlank { "(no log entries yet)" }
                runBtn.isEnabled = true
            }
        }
    }

    private fun render(results: List<DiagResult>) {
        var section = ""
        for (result in results) {
            if (result.section != section) {
                section = result.section
                addSectionHeader(section)
            }
            addRow(result)
        }
    }

    private fun addSectionHeader(title: String) {
        val view = TextView(this).apply {
            text = title
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.primary))
            val top = Palette.dp(context, 18f)
            setPadding(0, top, 0, Palette.dp(context, 4f))
        }
        resultsContainer.addView(view)
    }

    private fun addNote(text: String) {
        val view = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.status_unknown))
            setPadding(0, Palette.dp(context, 8f), 0, 0)
        }
        resultsContainer.addView(view)
    }

    private fun addRow(result: DiagResult) {
        val row = layoutInflater.inflate(R.layout.item_diag_row, resultsContainer, false)
        val dot = row.findViewById<View>(R.id.diagDot)
        val name = row.findViewById<TextView>(R.id.diagName)
        val detail = row.findViewById<TextView>(R.id.diagDetail)
        val elapsed = row.findViewById<TextView>(R.id.diagElapsed)

        val colorRes = when (result.status) {
            DiagResult.Status.PASS -> R.color.status_online
            DiagResult.Status.WARN -> R.color.status_degraded
            DiagResult.Status.FAIL -> R.color.status_offline
            DiagResult.Status.INFO -> R.color.status_unknown
        }
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(row.context, colorRes))
        }
        name.text = result.name
        detail.text = result.detail
        elapsed.text = if (result.elapsedMs > 0) "${result.elapsedMs}ms" else ""
        resultsContainer.addView(row)
    }

    private fun renderCrashHistory() {
        val crashes = CrashHandler.list(CrashHandler.crashDir(this))
        if (crashes.isEmpty()) {
            crashCard.visibility = View.GONE
            return
        }
        crashCard.visibility = View.VISIBLE
        val latest = crashes.first()
        val head = latest.readText().lineSequence().take(10).joinToString("\n")
        crashView.text = "${crashes.size} crash log(s) on this device. Latest: ${latest.name}\n\n$head"
    }

    private fun copy(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareReport() {
        val text = report.ifBlank { DiagnosticsRunner.buildReport(emptyList(), AppLog.tail(200)) }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(Intent.createChooser(send, "Share diagnostics"))
        } catch (e: Exception) {
            Toast.makeText(this, "No sharing app available", Toast.LENGTH_SHORT).show()
        }
    }
}

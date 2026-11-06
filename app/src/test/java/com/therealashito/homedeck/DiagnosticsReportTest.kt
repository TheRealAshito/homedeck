package com.therealashito.homedeck

import com.therealashito.homedeck.debug.DiagResult
import com.therealashito.homedeck.debug.DiagnosticsRunner
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportTest {

    @Test
    fun reportContainsSummarySectionsDetailsAndLogTail() {
        val results = listOf(
            DiagResult("Storage", "Cache directory", DiagResult.Status.PASS, "writable", 12),
            DiagResult("Storage", "Preferences", DiagResult.Status.FAIL, "broke", 3),
            DiagResult("Logs", "Crash history", DiagResult.Status.INFO, "none", 0)
        )
        val report = DiagnosticsRunner.buildReport(results, "12:00:00.000 I/App: started")

        assertTrue(report.contains("summary: 1 pass, 0 warn, 1 fail, 1 info"))
        assertTrue(report.contains("[Storage]"))
        assertTrue(report.contains("[Logs]"))
        assertTrue(report.contains("FAIL  Preferences: broke (3ms)"))
        assertTrue(report.contains("PASS  Cache directory: writable (12ms)"))
        assertTrue(report.contains("--- log tail ---"))
        assertTrue(report.contains("I/App: started"))
    }

    @Test
    fun emptyResultsStillProduceUsableReport() {
        val report = DiagnosticsRunner.buildReport(emptyList(), "")
        assertTrue(report.contains("summary: 0 pass, 0 warn, 0 fail, 0 info"))
        assertTrue(report.contains("--- log tail ---"))
    }
}

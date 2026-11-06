package com.therealashito.homedeck

import com.therealashito.homedeck.net.StatusChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusCheckerTest {

    @Test
    fun successIsOnline() {
        assertEquals(StatusChecker.Status.ONLINE, StatusChecker.classify(200))
    }

    @Test
    fun redirectIsOnline() {
        assertEquals(StatusChecker.Status.ONLINE, StatusChecker.classify(302))
    }

    @Test
    fun authRequiredStillMeansServiceIsUp() {
        assertEquals(StatusChecker.Status.ONLINE, StatusChecker.classify(401))
    }

    @Test
    fun serverErrorIsDegraded() {
        assertEquals(StatusChecker.Status.DEGRADED, StatusChecker.classify(503))
    }

    @Test
    fun nonsenseCodeIsUnknown() {
        assertEquals(StatusChecker.Status.UNKNOWN, StatusChecker.classify(-1))
        assertEquals(StatusChecker.Status.UNKNOWN, StatusChecker.classify(0))
    }

    @Test
    fun closedPortReportsNotReachable() {
        assertFalse(StatusChecker.tcpReachable("http://127.0.0.1:1", 300))
    }

    @Test
    fun describeFailureNamesCommonCauses() {
        assertEquals("host not found", StatusChecker.describeFailure(java.net.UnknownHostException("x")))
        assertEquals("connection refused", StatusChecker.describeFailure(java.net.ConnectException("x")))
        assertEquals("timed out", StatusChecker.describeFailure(java.net.SocketTimeoutException("x")))
        assertEquals("TLS certificate rejected", StatusChecker.describeFailure(javax.net.ssl.SSLException("x")))
        assertEquals("no response", StatusChecker.describeFailure(null))
    }

    @Test
    fun probeOnClosedPortReportsOfflineWithReason() {
        val probe = StatusChecker.probe("http://127.0.0.1:1", timeoutMs = 300)
        assertEquals(StatusChecker.Status.OFFLINE, probe.status)
        assertEquals("connection refused", probe.detail)
        assertTrue(probe.elapsedMs >= 0)
    }
}

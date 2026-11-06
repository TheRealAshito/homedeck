package com.therealashito.homedeck

import com.therealashito.homedeck.net.StatusChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}

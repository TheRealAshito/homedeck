package com.therealashito.homedeck

import com.therealashito.homedeck.debug.SessionJournal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionJournalTest {

    private val t0 = 1_000_000L
    private val t1 = t0 + 86_400_000L

    @Test
    fun firstObservationOfCookiesAppears() {
        val (entry, event) = SessionJournal.observe(null, present = true, now = t0)
        assertTrue(entry.present)
        assertEquals(t0, entry.since)
        assertEquals(0, entry.losses)
        assertNotNull(event)
    }

    @Test
    fun firstObservationWithoutCookiesIsQuiet() {
        val (entry, event) = SessionJournal.observe(null, present = false, now = t0)
        assertEquals(false, entry.present)
        assertNull(event)
    }

    @Test
    fun vanishIncrementsLossAndKeepsLastSeen() {
        val (present, _) = SessionJournal.observe(null, present = true, now = t0)
        val (gone, event) = SessionJournal.observe(present, present = false, now = t1)
        assertEquals(false, gone.present)
        assertEquals(1, gone.losses)
        assertEquals(t0, gone.lastSeen)
        assertNotNull(event)
        assertTrue(event!!.contains("VANISHED"))
    }

    @Test
    fun stayingSignedInIsQuiet() {
        val (a, _) = SessionJournal.observe(null, present = true, now = t0)
        val (b, event) = SessionJournal.observe(a, present = true, now = t1)
        assertTrue(b.present)
        assertEquals(t0, b.since) // unchanged: same continuous session
        assertEquals(t1, b.lastSeen)
        assertNull(event)
    }

    @Test
    fun reLoginDoesNotCountAsLoss() {
        val (a, _) = SessionJournal.observe(null, present = true, now = t0)
        val (gone, _) = SessionJournal.observe(a, present = false, now = t0 + 1)
        val (back, event) = SessionJournal.observe(gone, present = true, now = t1)
        assertEquals(1, back.losses) // the one real loss is kept
        assertNotNull(event)
    }

    @Test
    fun repeatedAbsenceIsQuiet() {
        val (a, _) = SessionJournal.observe(null, present = false, now = t0)
        val (b, event) = SessionJournal.observe(a, present = false, now = t1)
        assertEquals(0, b.losses)
        assertNull(event)
    }
}

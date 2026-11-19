package com.therealashito.homedeck

import com.therealashito.homedeck.data.SessionKeeper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionKeeperTest {

    @Test
    fun parseCookiesExtractsNameValueAndSkipsAttributes() {
        val parsed = SessionKeeper.parseCookies(
            "session_id=abc=123; theme=dark; Path=/; HttpOnly; Max-Age=3600"
        )
        assertEquals(2, parsed.size)
        assertEquals("abc=123", parsed["session_id"])
        assertEquals("dark", parsed["theme"])
    }

    @Test
    fun mergeAndMissingCookiesPreserveVanishedSessionCookies() {
        val backedUp = mapOf("sid" to "old_token", "user" to "ashito")
        val liveAfterRestart = mapOf("sid" to "new_token")

        val merged = SessionKeeper.mergeCookies(backedUp, liveAfterRestart)
        assertEquals("new_token", merged["sid"])
        assertEquals("ashito", merged["user"])

        val missing = SessionKeeper.missingCookies(backedUp, liveAfterRestart)
        assertEquals(mapOf("user" to "ashito"), missing)
    }

    @Test
    fun buildSetCookieHeadersAddsOneYearMaxAgeAndConditionalSecure() {
        val httpHeaders = SessionKeeper.buildSetCookieHeaders(
            mapOf("sid" to "token123"),
            secure = false
        )
        assertEquals(1, httpHeaders.size)
        assertTrue(httpHeaders[0].contains("sid=token123"))
        assertTrue(httpHeaders[0].contains("Max-Age=31536000"))
        assertFalse(httpHeaders[0].contains("Secure"))

        val httpsHeaders = SessionKeeper.buildSetCookieHeaders(
            mapOf("sid" to "token123"),
            secure = true
        )
        assertTrue(httpsHeaders[0].endsWith("; Secure"))
    }

    @Test
    fun isRenewalSetCookieAcceptsValidAndRejectsDeletions() {
        assertTrue(SessionKeeper.isRenewalSetCookie("sid=fresh_token; Path=/; HttpOnly"))
        assertFalse(SessionKeeper.isRenewalSetCookie("sid=; Path=/; Max-Age=0"))
        assertFalse(SessionKeeper.isRenewalSetCookie("sid=deleted; Path=/"))
        assertFalse(SessionKeeper.isRenewalSetCookie("sid=abc; Max-Age=0; Path=/"))
        assertFalse(SessionKeeper.isRenewalSetCookie("sid=abc; max-age=-1"))
    }

    @Test
    fun unwrapEvalResultHandlesJsonEncodedStringsFromWebView() {
        val raw = "\"{\\\"local\\\":{\\\"jwt\\\":\\\"tok\\\"}}\""
        assertEquals("{\"local\":{\"jwt\":\"tok\"}}", SessionKeeper.unwrapEvalResult(raw))
        assertNull(SessionKeeper.unwrapEvalResult("null"))
        assertNull(SessionKeeper.unwrapEvalResult(""))
    }

    @Test
    fun mergeStorageJsonPreservesExistingAndUpdatesNew() {
        val existing = """{"jwt":"old","refresh":"keep_me"}"""
        val incoming = """{"jwt":"new"}"""
        val merged = JSONObject(SessionKeeper.mergeStorageJson(existing, incoming))
        assertEquals("new", merged.getString("jwt"))
        assertEquals("keep_me", merged.getString("refresh"))
    }
}

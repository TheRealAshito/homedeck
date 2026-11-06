package com.therealashito.homedeck

import com.therealashito.homedeck.model.Urls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlsTest {

    @Test
    fun bareHostGainsHttpScheme() {
        assertEquals("http://kuma.lan", Urls.normalize("kuma.lan"))
    }

    @Test
    fun existingSchemeIsKept() {
        assertEquals("https://x.lan", Urls.normalize("https://x.lan"))
    }

    @Test
    fun hostWithPortIsKept() {
        assertEquals("http://192.168.1.5:3001", Urls.normalize("192.168.1.5:3001"))
    }

    @Test
    fun whitespaceIsTrimmed() {
        assertEquals("http://kuma.lan", Urls.normalize("  kuma.lan  "))
    }

    @Test
    fun blankIsRejected() {
        assertNull(Urls.normalize("   "))
        assertNull(Urls.normalize(""))
    }

    @Test
    fun nonHttpSchemesAreRejected() {
        assertNull(Urls.normalize("ftp://x"))
        assertNull(Urls.normalize("file:///etc/passwd"))
    }

    @Test
    fun hostOfFallsBackToRawValue() {
        assertEquals("kuma.lan", Urls.hostOf("http://kuma.lan"))
        assertEquals("kuma.lan", Urls.hostOf("http://kuma.lan/status"))
    }

    @Test
    fun defaultPorts() {
        assertEquals(80, Urls.defaultPort("http"))
        assertEquals(443, Urls.defaultPort("https"))
        assertEquals(80, Urls.defaultPort(null))
    }
}

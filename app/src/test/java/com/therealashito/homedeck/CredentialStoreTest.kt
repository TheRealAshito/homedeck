package com.therealashito.homedeck

import com.therealashito.homedeck.data.CredentialStore
import com.therealashito.homedeck.data.CredentialStore.Credential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialStoreTest {

    @Test
    fun jsonRoundTripPreservesCredentials() {
        val source = mapOf(
            "http://securo.lan" to Credential(
                origin = "http://securo.lan",
                username = "ashito",
                password = "secret!\"pass",
                autoSubmit = true,
                updatedAt = 12345L
            )
        )
        val restored = CredentialStore.fromJson(CredentialStore.toJson(source))
        assertEquals(1, restored.size)
        val cred = restored["http://securo.lan"]
        assertNotNull(cred)
        assertEquals("ashito", cred!!.username)
        assertEquals("secret!\"pass", cred.password)
        assertEquals(true, cred.autoSubmit)
        assertEquals(12345L, cred.updatedAt)
    }

    @Test
    fun matchForUrlIsolatesSchemesAndPortsStrictly() {
        val map = mapOf(
            "http://192.168.1.10:3000" to Credential(
                origin = "http://192.168.1.10:3000",
                username = "mood_user",
                password = "p1"
            ),
            "https://securo.lan" to Credential(
                origin = "https://securo.lan",
                username = "securo_user",
                password = "p2"
            )
        )
        assertEquals(
            "mood_user",
            CredentialStore.matchForUrl(map, "http://192.168.1.10:3000/login")?.username
        )
        assertEquals(
            "securo_user",
            CredentialStore.matchForUrl(map, "https://securo.lan/auth")?.username
        )
        // Must never downgrade HTTPS credentials to plaintext HTTP or cross ports.
        assertNull(CredentialStore.matchForUrl(map, "http://securo.lan/auth"))
        assertNull(CredentialStore.matchForUrl(map, "http://192.168.1.10:9000/"))
    }

    @Test
    fun buildAutoLoginJsGuardsAgainstLoopsAndRawFormSubmit() {
        val js = CredentialStore.buildAutoLoginJs("ashito", "p\"a\\ss", shouldSubmit = true)
        assertTrue(js.contains("\"ashito\""))
        assertTrue(js.contains("\"p\\\"a\\\\ss\""))
        assertTrue(js.contains("var shouldSubmit = true;"))
        assertTrue(js.contains("passInputs.length !== 1"))
        assertTrue(js.contains("_valueTracker"))
        assertTrue(js.contains("requestSubmit"))
        assertTrue(!js.contains("form.submit()"))
    }
}

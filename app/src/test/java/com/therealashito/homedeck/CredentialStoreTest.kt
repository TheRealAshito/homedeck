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
    fun matchForUrlIsolatesDifferentPortsOnSameDockerHost() {
        val map = mapOf(
            "http://192.168.1.10:3000" to Credential(
                origin = "http://192.168.1.10:3000",
                username = "mood_user",
                password = "p1"
            ),
            "http://192.168.1.10:8080" to Credential(
                origin = "http://192.168.1.10:8080",
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
            CredentialStore.matchForUrl(map, "http://192.168.1.10:8080/auth")?.username
        )
        assertNull(CredentialStore.matchForUrl(map, "http://192.168.1.10:9000/"))
    }

    @Test
    fun buildAutoLoginJsQuotesSpecialCharactersAndEmbedsSubmitFlag() {
        val js = CredentialStore.buildAutoLoginJs("ashito", "p\"a\\ss", shouldSubmit = true)
        assertTrue(js.contains("\"ashito\""))
        assertTrue(js.contains("\"p\\\"a\\\\ss\""))
        assertTrue(js.contains("var shouldSubmit = true;"))
    }
}

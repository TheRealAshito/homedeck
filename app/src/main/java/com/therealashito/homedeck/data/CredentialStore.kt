package com.therealashito.homedeck.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.therealashito.homedeck.debug.AppLog
import com.therealashito.homedeck.model.Urls
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Local-only vault for per-service login credentials so Homedeck can
 * automatically re-authenticate when a container restarts or a server
 * enforces a hard session expiry.
 *
 * Credentials are encrypted with an Android Keystore AES-GCM key on device
 * and never leave the app except to fill the matching service's own login form
 * or HTTP Basic Auth challenge.
 */
class CredentialStore(context: Context) {

    data class Credential(
        val origin: String,
        val username: String,
        val password: String,
        val autoSubmit: Boolean = true,
        val updatedAt: Long = 0L
    )

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun all(): MutableMap<String, Credential> {
        val raw = readDecryptedJson() ?: return mutableMapOf()
        return fromJson(raw)
    }

    fun count(): Int = all().size

    fun getForUrl(url: String): Credential? = matchForUrl(all(), url)

    fun saveForUrl(
        url: String,
        username: String,
        password: String,
        autoSubmit: Boolean = true,
        now: Long = System.currentTimeMillis()
    ) {
        val origin = Urls.originOf(url)
        val trimmedUser = username.trim()
        if (trimmedUser.isEmpty() && password.isEmpty()) {
            removeForUrl(url)
            return
        }
        val map = all()
        map[origin] = Credential(
            origin = origin,
            username = trimmedUser,
            password = password,
            autoSubmit = autoSubmit,
            updatedAt = now
        )
        writeEncryptedJson(toJson(map))
        AppLog.i("AuthVault", "saved login for $origin (user=${trimmedUser.ifEmpty { "(none)" }})")
    }

    fun removeForUrl(url: String) {
        val origin = Urls.originOf(url)
        val map = all()
        if (map.remove(origin) != null) {
            writeEncryptedJson(toJson(map))
            AppLog.i("AuthVault", "removed saved login for $origin")
        }
    }

    fun clearAll() {
        prefs.edit().clear().apply()
        AppLog.i("AuthVault", "cleared all saved logins")
    }

    private fun readDecryptedJson(): String? {
        val enc = prefs.getString(KEY_VAULT_ENC, null)
        val ivB64 = prefs.getString(KEY_VAULT_IV, null)
        if (!enc.isNullOrBlank() && !ivB64.isNullOrBlank()) {
            try {
                val key = getOrCreateSecretKey()
                if (key != null) {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    val iv = Base64.decode(ivB64, Base64.NO_WRAP)
                    val ciphertext = Base64.decode(enc, Base64.NO_WRAP)
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                    return cipher.doFinal(ciphertext).decodeToString()
                }
            } catch (t: Throwable) {
                AppLog.w("AuthVault", "keystore decrypt failed, falling back: ${t.message}")
            }
        }
        return prefs.getString(KEY_VAULT_PLAIN, null)
    }

    private fun writeEncryptedJson(json: String) {
        try {
            val key = getOrCreateSecretKey()
            if (key != null) {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key)
                val ciphertext = cipher.doFinal(json.encodeToByteArray())
                val ivB64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
                val encB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
                prefs.edit()
                    .putString(KEY_VAULT_ENC, encB64)
                    .putString(KEY_VAULT_IV, ivB64)
                    .remove(KEY_VAULT_PLAIN)
                    .apply()
                return
            }
        } catch (_: Throwable) {
            // Headless JVM / Robolectric environment without AndroidKeyStore.
        }
        prefs.edit().putString(KEY_VAULT_PLAIN, json).apply()
    }

    private fun getOrCreateSecretKey(): SecretKey? = try {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) {
            existing.secretKey
        } else {
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            kg.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            kg.generateKey()
        }
    } catch (_: Throwable) {
        null
    }

    companion object {
        private const val PREFS_NAME = "homedeck.credentials"
        private const val KEY_VAULT_ENC = "vault_enc_v1"
        private const val KEY_VAULT_IV = "vault_iv_v1"
        private const val KEY_VAULT_PLAIN = "vault_plain_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "homedeck_cred_key_v1"

        fun toJson(map: Map<String, Credential>): String {
            val root = JSONObject()
            for ((origin, cred) in map) {
                val o = JSONObject()
                o.put("origin", cred.origin)
                o.put("u", cred.username)
                o.put("p", cred.password)
                o.put("auto", cred.autoSubmit)
                o.put("t", cred.updatedAt)
                root.put(origin, o)
            }
            return root.toString()
        }

        fun fromJson(raw: String): MutableMap<String, Credential> {
            val out = mutableMapOf<String, Credential>()
            if (raw.isBlank()) return out
            return try {
                val root = JSONObject(raw)
                for (key in root.keys()) {
                    val o = root.optJSONObject(key) ?: continue
                    val origin = o.optString("origin").ifBlank { key }
                    val user = o.optString("u")
                    val pass = o.optString("p")
                    if (user.isBlank() && pass.isBlank()) continue
                    out[origin] = Credential(
                        origin = origin,
                        username = user,
                        password = pass,
                        autoSubmit = o.optBoolean("auto", true),
                        updatedAt = o.optLong("t", 0L)
                    )
                }
                out
            } catch (e: Exception) {
                out
            }
        }

        /**
         * Matches exact origin (`scheme://host[:port]`) first so different
         * Docker containers on different ports stay isolated; falls back to
         * matching host only if there is a single credential for that host.
         */
        fun matchForUrl(map: Map<String, Credential>, url: String): Credential? {
            if (map.isEmpty()) return null
            val origin = Urls.originOf(url)
            map[origin]?.let { return it }
            val host = Urls.hostOf(url).lowercase()
            val byHost = map.values.filter { Urls.hostOf(it.origin).lowercase() == host }
            return if (byHost.size == 1) byHost.first() else null
        }

        /**
         * JS snippet that detects a visible password login form on the current
         * page, fills the username & password using the native HTMLInputElement
         * setter (so React/Vue/Svelte state updates properly), and optionally
         * submits the form.
         *
         * Returns `"NO_FORM"`, `"FILLED"`, or `"SUBMITTED"`.
         */
        fun buildAutoLoginJs(
            username: String,
            password: String,
            shouldSubmit: Boolean
        ): String {
            val quotedUser = JSONObject.quote(username)
            val quotedPass = JSONObject.quote(password)
            return """
                (function() {
                    try {
                        var userVal = $quotedUser;
                        var passVal = $quotedPass;
                        var shouldSubmit = $shouldSubmit;

                        function isVisible(el) {
                            if (!el || el.disabled || el.readOnly) return false;
                            var r = el.getBoundingClientRect();
                            var style = window.getComputedStyle(el);
                            return r.width > 0 && r.height > 0 &&
                                style.visibility !== 'hidden' && style.display !== 'none';
                        }

                        function setNativeValue(el, val) {
                            if (!el) return;
                            el.focus();
                            var proto = Object.getPrototypeOf(el);
                            var desc = Object.getOwnPropertyDescriptor(proto, 'value') ||
                                Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                            if (desc && desc.set) {
                                desc.set.call(el, val);
                            } else {
                                el.value = val;
                            }
                            el.dispatchEvent(new Event('input', { bubbles: true }));
                            el.dispatchEvent(new Event('change', { bubbles: true }));
                        }

                        var passInputs = Array.prototype.slice.call(
                            document.querySelectorAll('input[type="password"]')
                        ).filter(isVisible);
                        if (passInputs.length === 0) return "NO_FORM";

                        var passEl = passInputs[0];
                        var scope = passEl.form || passEl.closest('form, [role="form"], main, body') || document;

                        var candidates = Array.prototype.slice.call(
                            scope.querySelectorAll(
                                'input[type="email"], input[autocomplete="username"], ' +
                                'input[name*="user" i], input[name*="email" i], input[name*="login" i], ' +
                                'input[id*="user" i], input[id*="email" i], input[id*="login" i], ' +
                                'input[type="text"], input:not([type])'
                            )
                        ).filter(function(el) {
                            return el !== passEl && isVisible(el);
                        });

                        var userEl = candidates.length > 0 ? candidates[0] : null;
                        if (userEl && userVal) {
                            setNativeValue(userEl, userVal);
                        }
                        if (passVal) {
                            setNativeValue(passEl, passVal);
                        }

                        if (!shouldSubmit) return "FILLED";

                        var form = passEl.form || passEl.closest('form');
                        var submitBtn = scope.querySelector(
                            'button[type="submit"], input[type="submit"]'
                        );
                        if (!submitBtn) {
                            var buttons = Array.prototype.slice.call(
                                scope.querySelectorAll('button, [role="button"]')
                            ).filter(isVisible);
                            for (var i = 0; i < buttons.length; i++) {
                                var txt = (buttons[i].innerText || buttons[i].textContent || '').trim().toLowerCase();
                                if (/^(sign\s*in|log\s*in|login|submit|continue|unlock|connect|enter)$/i.test(txt) ||
                                    txt.indexOf('sign in') !== -1 || txt.indexOf('log in') !== -1 || txt.indexOf('login') !== -1) {
                                    submitBtn = buttons[i];
                                    break;
                                }
                            }
                        }

                        if (submitBtn && isVisible(submitBtn)) {
                            submitBtn.click();
                            return "SUBMITTED";
                        }
                        if (form) {
                            if (typeof form.requestSubmit === 'function') {
                                form.requestSubmit();
                            } else {
                                form.submit();
                            }
                            return "SUBMITTED";
                        }
                        passEl.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
                        return "SUBMITTED";
                    } catch (e) {
                        return "ERROR:" + e.message;
                    }
                })();
            """.trimIndent()
        }

        /**
         * Idempotent JS hook injected into pages to detect when the user
         * submits a login form and offer to save the credentials in Homedeck.
         */
        fun buildCaptureHookJs(): String = """
            (function() {
                if (window.__homedeckLoginHookInstalled) return;
                window.__homedeckLoginHookInstalled = true;

                function captureNow() {
                    try {
                        if (!window.HomedeckAuth || typeof window.HomedeckAuth.onLoginSubmitted !== 'function') return;
                        var passInputs = document.querySelectorAll('input[type="password"]');
                        for (var i = 0; i < passInputs.length; i++) {
                            var passEl = passInputs[i];
                            var passVal = passEl.value || '';
                            if (!passVal) continue;
                            var scope = passEl.form || passEl.closest('form, [role="form"], main, body') || document;
                            var userEl = scope.querySelector(
                                'input[type="email"], input[autocomplete="username"], ' +
                                'input[name*="user" i], input[name*="email" i], input[name*="login" i], ' +
                                'input[id*="user" i], input[id*="email" i], input[id*="login" i], ' +
                                'input[type="text"], input:not([type])'
                            );
                            var userVal = (userEl && userEl !== passEl) ? (userEl.value || '').trim() : '';
                            var sig = userVal + '\n' + passVal;
                            if (window.__homedeckLastCaptured === sig) return;
                            window.__homedeckLastCaptured = sig;
                            window.HomedeckAuth.onLoginSubmitted(window.location.href, userVal, passVal);
                            return;
                        }
                    } catch (e) {}
                }

                document.addEventListener('submit', captureNow, true);
                document.addEventListener('keydown', function(e) {
                    if (e.key === 'Enter' || e.keyCode === 13) captureNow();
                }, true);
                document.addEventListener('click', function(e) {
                    var t = e.target && e.target.closest ? e.target.closest('button, input[type="submit"], [role="button"]') : null;
                    if (t) captureNow();
                }, true);
            })();
        """.trimIndent()
    }
}

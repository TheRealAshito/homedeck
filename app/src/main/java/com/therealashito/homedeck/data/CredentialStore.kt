package com.therealashito.homedeck.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
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
            return try {
                val key = getOrCreateSecretKey() ?: return null
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val iv = Base64.decode(ivB64, Base64.NO_WRAP)
                val ciphertext = Base64.decode(enc, Base64.NO_WRAP)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                cipher.doFinal(ciphertext).decodeToString()
            } catch (t: Throwable) {
                AppLog.w("AuthVault", "keystore decrypt failed: ${t.message}")
                null
            }
        }
        return if (isRobolectric()) prefs.getString(KEY_VAULT_PLAIN, null) else null
    }

    private fun writeEncryptedJson(json: String) {
        val key = getOrCreateSecretKey()
        if (key != null) {
            try {
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
            } catch (t: Throwable) {
                AppLog.e("AuthVault", "keystore encrypt failed; refusing plaintext write", t)
                return
            }
        }
        if (isRobolectric()) {
            prefs.edit().putString(KEY_VAULT_PLAIN, json).apply()
        } else {
            AppLog.e("AuthVault", "AndroidKeyStore unavailable; refusing plaintext credential write")
        }
    }

    private fun isRobolectric(): Boolean =
        Build.FINGERPRINT.equals("robolectric", ignoreCase = true)

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
         * Matches exact origin (`scheme://host[:port]`) strictly so different
         * Docker containers on different ports stay isolated and HTTPS
         * credentials are never leaked to plaintext HTTP endpoints.
         */
        fun matchForUrl(map: Map<String, Credential>, url: String): Credential? {
            if (map.isEmpty()) return null
            return map[Urls.originOf(url)]
        }

        /**
         * JS snippet that detects a single visible login password field (never
         * multi-field registration/change-password forms), fills username and
         * password with React/Vue `_valueTracker` synchronization, and
         * optionally submits via SPA-safe handlers (never raw `form.submit()`
         * which forces full-page reloads on SPAs).
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
                            var lastVal = el.value;
                            var proto = Object.getPrototypeOf(el);
                            var desc = Object.getOwnPropertyDescriptor(proto, 'value') ||
                                Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                            if (desc && desc.set) {
                                desc.set.call(el, val);
                            } else {
                                el.value = val;
                            }
                            if (el._valueTracker && typeof el._valueTracker.setValue === 'function') {
                                el._valueTracker.setValue(lastVal);
                            }
                            el.dispatchEvent(new Event('input', { bubbles: true }));
                            el.dispatchEvent(new Event('change', { bubbles: true }));
                        }

                        var passInputs = Array.prototype.slice.call(
                            document.querySelectorAll('input[type="password"]')
                        ).filter(isVisible);
                        // Only single-password forms are logins; >=2 means register/change-password.
                        if (passInputs.length !== 1) return "NO_FORM";

                        var passEl = passInputs[0];
                        var ac = (passEl.getAttribute('autocomplete') || '').toLowerCase();
                        var pHint = ((passEl.name || '') + ' ' + (passEl.id || '')).toLowerCase();
                        if (ac === 'new-password' || /confirm|repeat|new.?pass/i.test(pHint)) {
                            return "NO_FORM";
                        }
                        if (passEl.value && passEl.value.length > 0) {
                            return "NO_FORM";
                        }

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
                        if (userEl && userVal && (!userEl.value || userEl.value !== userVal)) {
                            setNativeValue(userEl, userVal);
                        }
                        if (passVal) {
                            setNativeValue(passEl, passVal);
                        }

                        if (!shouldSubmit) return "FILLED";

                        setTimeout(function() {
                            try {
                                var form = passEl.form || passEl.closest('form');
                                var submitBtn = scope.querySelector(
                                    'button[type="submit"]:not([disabled]), input[type="submit"]:not([disabled])'
                                );
                                if (!submitBtn) {
                                    var buttons = Array.prototype.slice.call(
                                        scope.querySelectorAll('button, [role="button"]')
                                    ).filter(function(b) { return !b.disabled && isVisible(b); });
                                    for (var i = 0; i < buttons.length; i++) {
                                        var txt = (buttons[i].innerText || buttons[i].textContent || '').trim().toLowerCase();
                                        if (/^(sign\s*in|log\s*in|login|submit|continue|unlock|connect|enter)$/i.test(txt) ||
                                            txt.indexOf('sign in') !== -1 || txt.indexOf('log in') !== -1 || txt.indexOf('login') !== -1) {
                                            submitBtn = buttons[i];
                                            break;
                                        }
                                    }
                                }

                                if (submitBtn && !submitBtn.disabled && isVisible(submitBtn)) {
                                    submitBtn.click();
                                    return;
                                }
                                if (form && typeof form.requestSubmit === 'function') {
                                    form.requestSubmit();
                                    return;
                                }
                                passEl.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
                            } catch (err) {}
                        }, 80);

                        return "SUBMITTED";
                    } catch (e) {
                        return "ERROR:" + e.message;
                    }
                })();
            """.trimIndent()
        }

        /**
         * Idempotent JS hook injected into pages to detect when the user
         * submits a login form (not multi-field registration/password-change)
         * and offer to save the credentials in Homedeck.
         */
        fun buildCaptureHookJs(): String = """
            (function() {
                if (window.__homedeckLoginHookInstalled) return;
                window.__homedeckLoginHookInstalled = true;

                function captureNow() {
                    try {
                        if (!window.HomedeckAuth || typeof window.HomedeckAuth.onLoginSubmitted !== 'function') return;
                        var passInputs = Array.prototype.slice.call(
                            document.querySelectorAll('input[type="password"]')
                        ).filter(function(el) {
                            var r = el.getBoundingClientRect();
                            return r.width > 0 && r.height > 0;
                        });
                        if (passInputs.length !== 1) return;
                        var passEl = passInputs[0];
                        var ac = (passEl.getAttribute('autocomplete') || '').toLowerCase();
                        if (ac === 'new-password') return;
                        var passVal = passEl.value || '';
                        if (!passVal) return;
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

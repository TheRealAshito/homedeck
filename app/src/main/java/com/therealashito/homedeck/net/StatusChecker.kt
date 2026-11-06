package com.therealashito.homedeck.net

import com.therealashito.homedeck.data.SettingsStore
import com.therealashito.homedeck.model.Urls
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Lightweight reachability probe used for the tile status dots. HTTP first
 * (so 5xx shows as degraded), then a plain TCP connect as fallback so
 * services behind self-signed certs still light up.
 */
object StatusChecker {

    enum class Status { ONLINE, DEGRADED, OFFLINE, UNKNOWN }

    /** Pure classification, unit tested. 4xx still means "service is up". */
    fun classify(code: Int): Status = when {
        code in 200..499 -> Status.ONLINE
        code >= 500 -> Status.DEGRADED
        else -> Status.UNKNOWN
    }

    fun check(url: String, trustedHosts: Set<String> = emptySet(), timeoutMs: Int = 3000): Status {
        val http = checkHttp(url, trustedHosts, timeoutMs)
        if (http != null) return http
        return if (tcpReachable(url, timeoutMs)) Status.DEGRADED else Status.OFFLINE
    }

    private fun checkHttp(url: String, trustedHosts: Set<String>, timeoutMs: Int): Status? {
        return try {
            val host = Urls.hostOf(url)
            val permissive = trustedHosts.contains(host)
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Homedeck/1.0 (Android)")
            if (permissive && conn is javax.net.ssl.HttpsURLConnection) {
                conn.sslSocketFactory = permissiveSslFactory()
                conn.hostnameVerifier = permissiveHostnameVerifier
            }
            try {
                classify(conn.responseCode)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun tcpReachable(url: String, timeoutMs: Int = 3000): Boolean {
        return try {
            val uri = java.net.URI(url)
            val host = uri.host ?: return false
            val port = if (uri.port > 0) uri.port else Urls.defaultPort(uri.scheme)
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    // ---- permissive TLS, used ONLY for hosts the user explicitly trusted ----

    private val permissiveHostnameVerifier = HostnameVerifier { _, _ -> true }

    @Volatile
    private var permissiveFactory: SSLSocketFactory? = null

    private fun permissiveSslFactory(): SSLSocketFactory {
        permissiveFactory?.let { return it }
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, SecureRandom())
        val factory = ctx.socketFactory
        permissiveFactory = factory
        return factory
    }
}

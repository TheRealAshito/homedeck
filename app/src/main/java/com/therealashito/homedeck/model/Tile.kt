package com.therealashito.homedeck.model

import java.net.URI
import java.util.UUID

/**
 * One dashboard entry: a named service on the LAN with a display icon and
 * accent color. [url] is always stored normalized (with scheme).
 */
data class Tile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val emoji: String = "",
    val colorIndex: Int = 0
)

object Urls {

    /** Returns a normalized http(s) URL, or null when [raw] is not usable. */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        val uri = try {
            URI(withScheme)
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        if (host.isEmpty()) return null
        return withScheme
    }

    /** Human-friendly host (or the raw URL when parsing fails). */
    fun hostOf(url: String): String = try {
        val host = URI(url).host
        if (host.isNullOrBlank()) url else host
    } catch (e: Exception) {
        url
    }

    fun defaultPort(scheme: String?): Int = when (scheme?.lowercase()) {
        "https" -> 443
        else -> 80
    }
}

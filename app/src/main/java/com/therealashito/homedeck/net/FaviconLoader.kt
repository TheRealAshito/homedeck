package com.therealashito.homedeck.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Fetches /favicon.ico for a service with a small memory + disk cache.
 * The callback always runs on the main thread; null means "no icon found".
 */
object FaviconLoader {

    private const val DISK_DIR = "favicons"
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val memory = object : LruCache<String, Bitmap>(32) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    fun load(context: Context, serviceUrl: String, callback: (Bitmap?) -> Unit) {
        val appContext = context.applicationContext
        val key = keyOf(serviceUrl)
        memory.get(key)?.let {
            mainHandler.post { callback(it) }
            return
        }
        executor.execute {
            val bitmap = fetch(appContext, serviceUrl, key)
            if (bitmap != null) memory.put(key, bitmap)
            mainHandler.post { callback(bitmap) }
        }
    }

    private fun fetch(context: Context, serviceUrl: String, key: String): Bitmap? {
        val diskFile = File(File(context.cacheDir, DISK_DIR).apply { mkdirs() }, "$key.png")
        if (diskFile.exists() && diskFile.length() > 0) {
            decode(diskFile)?.let { return it }
        }
        val iconUrl = iconUrlFor(serviceUrl) ?: return null
        return try {
            val conn = java.net.URL(iconUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.instanceFollowRedirects = true
            try {
                if (conn.responseCode != 200) return null
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.isEmpty() || bytes.size > 2_000_000) return null
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                try {
                    diskFile.outputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                } catch (e: Exception) {
                    // Disk cache is best-effort.
                }
                bitmap
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun decode(file: File): Bitmap? = try {
        BitmapFactory.decodeFile(file.absolutePath)
    } catch (e: Exception) {
        null
    }

    fun iconUrlFor(serviceUrl: String): String? {
        return try {
            val uri = URI(serviceUrl)
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "$scheme://$host$port/favicon.ico"
        } catch (e: Exception) {
            null
        }
    }

    private fun keyOf(url: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

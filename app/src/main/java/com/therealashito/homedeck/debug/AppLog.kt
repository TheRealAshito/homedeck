package com.therealashito.homedeck.debug

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local-only log: an in-memory ring buffer plus a file in the app's private
 * storage, mirrored to logcat. Nothing ever leaves the device; sharing a log
 * is always an explicit user action (share/copy button in Diagnostics).
 */
object AppLog {

    enum class Level { D, I, W, E }

    data class Entry(
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String
    ) {
        fun format(): String {
            val time = FORMAT.format(Date(timestamp))
            return "$time ${level.name}/$tag: $message"
        }
    }

    private const val MAX_ENTRIES = 500
    private val FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val buffer = ArrayDeque<Entry>()
    private var logFile: File? = null

    @Synchronized
    fun init(context: Context) {
        if (logFile != null) return
        val dir = File(context.applicationContext.filesDir, "logs")
        runCatching { dir.mkdirs() }
        logFile = File(dir, "homedeck.log")
    }

    fun d(tag: String, message: String) = log(Level.D, tag, message)

    fun i(tag: String, message: String) = log(Level.I, tag, message)

    fun w(tag: String, message: String) = log(Level.W, tag, message)

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val detail = if (throwable == null) message else "$message\n${throwable.stackTraceToString()}"
        log(Level.E, tag, detail)
    }

    @Synchronized
    fun log(level: Level, tag: String, message: String) {
        val entry = Entry(System.currentTimeMillis(), level, tag, message)
        buffer.addLast(entry)
        while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        runCatching {
            val priority = when (level) {
                Level.D -> android.util.Log.DEBUG
                Level.I -> android.util.Log.INFO
                Level.W -> android.util.Log.WARN
                Level.E -> android.util.Log.ERROR
            }
            android.util.Log.println(priority, "Homedeck/$tag", message)
        }
        runCatching { logFile?.appendText(entry.format() + "\n") }
    }

    @Synchronized
    fun snapshot(): List<Entry> = buffer.toList()

    @Synchronized
    fun tail(maxLines: Int = 80): String =
        snapshot().takeLast(maxLines).joinToString("\n") { it.format() }

    fun logFile(): File? = logFile

    @Synchronized
    fun clear() {
        buffer.clear()
        runCatching { logFile?.writeText("") }
    }
}

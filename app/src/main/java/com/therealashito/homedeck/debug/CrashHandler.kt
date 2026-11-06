package com.therealashito.homedeck.debug

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records uncaught exceptions to the log and to a per-crash file so the
 * Diagnostics screen can show what killed the app last time. The previous
 * handler still runs afterwards, so crash behaviour is otherwise unchanged.
 */
class CrashHandler(
    private val crashDir: File,
    private val previous: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching {
            AppLog.e("Crash", "Uncaught exception on thread '${thread.name}'", throwable)
            crashDir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            File(crashDir, "crash-$stamp.txt").writeText(
                "thread: ${thread.name}\ntime: ${Date()}\n\n${throwable.stackTraceToString()}"
            )
        }
        previous?.uncaughtException(thread, throwable)
    }

    companion object {
        fun install(context: Context) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            if (previous is CrashHandler) return
            val dir = File(context.applicationContext.filesDir, "crash")
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(dir, previous))
        }

        fun crashDir(context: Context): File = File(context.applicationContext.filesDir, "crash")

        fun list(crashDir: File): List<File> =
            crashDir.listFiles()?.sortedByDescending { it.name } ?: emptyList()
    }
}

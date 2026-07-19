package com.foqos.android.service.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists a plain-text copy of every uncaught exception to `filesDir/crashes/`, keeping only the
 * [MAX_CRASH_FILES] most recent, so a field crash (e.g. the accessibility-service crash-loop found
 * during Samsung Galaxy S21 testing) is diagnosable from the in-app "Crash logs" row in Settings
 * without needing adb/logcat access to the device. Installed once from
 * [com.foqos.android.FoqosApplication.onCreate] via [install], as early as possible so
 * startup-time crashes are captured too.
 */
object CrashLogger {

    private const val TAG = "CrashLogger"
    private const val CRASH_DIR_NAME = "crashes"
    private const val MAX_CRASH_FILES = 5

    /**
     * Wraps whatever [Thread.getDefaultUncaughtExceptionHandler] is already installed (normally
     * the platform's own handler, which shows the "app has stopped" dialog and kills the process)
     * so every uncaught crash is logged to disk first, then always delegates to it - this never
     * suppresses or changes the app's actual crash behavior, it only adds a side-effect.
     */
    fun install(context: Context) {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val appContext = context.applicationContext
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrashLog(appContext, thread, throwable) }
                .onFailure { Log.e(TAG, "Failed to persist crash log", it) }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable) {
        val dir = crashDir(context)
        if (!dir.exists()) dir.mkdirs()

        val stackTrace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            appendLine("Time: ${Date()}")
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(stackTrace)
        }

        val fileNameFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss.SSS", Locale.US)
        File(dir, "crash-${fileNameFormat.format(Date())}.txt").writeText(text)

        trimToMostRecent(dir)
    }

    private fun trimToMostRecent(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_CRASH_FILES).forEach { it.delete() }
    }

    /** All saved crash log files, most recent first - backs the "Crash logs" row in Settings. */
    fun listCrashLogs(context: Context): List<File> =
        crashDir(context).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    private fun crashDir(context: Context): File = File(context.applicationContext.filesDir, CRASH_DIR_NAME)
}

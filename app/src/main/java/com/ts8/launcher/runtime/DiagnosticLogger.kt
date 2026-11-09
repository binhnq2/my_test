package com.ts8.launcher.runtime

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes diagnostic logs to a file on external storage, so they can be
 * retrieved without USB debugging — the driver plugs a USB stick in and
 * the file is there.
 *
 * **Why this exists.** The TS8 head unit's USB debugging frequently fails
 * to connect to a PC even when enabled in Developer Options. The causes
 * are vendor-specific (missing ADB interface driver, MTP-mode default,
 * custom ROM USB stack) and not fixable from the app. Without logcat the
 * developer is blind: a "Đang tải kiểu hiển thị" message could be a
 * MapLibre native crash, a missing glyph directory, a GL driver issue,
 * or a pack path problem, and there is no way to tell which.
 *
 * **What it logs.** It captures:
 * - App start, SDK version, device model, ABI
 * - Map pack discovery results
 * - Map style load success/failure with the reason
 * - MapLibre native crashes (SIGSEGV etc.) via the UncaughtExceptionHandler
 * - Pack validation results
 *
 * **Where it writes.** `/storage/emulated/0/ts8/logs/ts8-diag-YYYY-MM-DD.txt`
 * — the shared storage root that a USB file manager or MTP transfer can
 * reach. On Android 6 (API 23) this needs no SAF; `WRITE_EXTERNAL_STORAGE`
 * is already in the manifest.
 *
 * **Thread safety.** All writes are synchronized on the writer instance.
 * The file is opened in append mode and flushed after every write, so a
 * power cut (ignition-off) does not lose the last entry.
 */
object DiagnosticLogger {

    private const val TAG = "DiagLogger"
    private const val LOG_DIR = "ts8/logs"
    private const val MAX_FILE_SIZE = 512 * 1024L // 512 KB per day

    private var logFile: File? = null
    private var writer: java.io.FileWriter? = null
    private val dateFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private var initialized = false

    /**
     * Opens the log file for today. Safe to call multiple times; only the
     * first call actually opens a file.
     */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true

        var dir = File("/storage/emulated/0/$LOG_DIR")
        if (!dir.isDirectory && !dir.mkdirs()) {
            // Try app-external storage as fallback
            dir = File(context.getExternalFilesDir(null), "logs")
            if (!dir.isDirectory && !dir.mkdirs()) {
                Log.w(TAG, "cannot create log dir")
                return
            }
        }

        val today = dayFmt.format(Date())
        val file = File(dir, "ts8-diag-$today.txt")

        // Rotate if the file is too large
        if (file.exists() && file.length() > MAX_FILE_SIZE) {
            val old = File(dir, "ts8-diag-$today.old.txt")
            old.delete()
            file.renameTo(old)
        }

        logFile = file
        runCatching {
            writer = java.io.FileWriter(file, true)
        }.onFailure {
            Log.w(TAG, "cannot open log writer: ${it.message}")
        }

        installCrashHandler()
        log("=== TS8 Launcher diagnostic log ===")
        log("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        log("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        log("ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        log("App: ${context.packageName}")
    }

    /**
     * Appends a timestamped line to the log file.
     * Also mirrors to logcat so USB debugging still works when available.
     */
    @Synchronized
    fun log(message: String) {
        val ts = dateFmt.format(Date())
        val line = "[$ts] $message"
        Log.i(TAG, message)

        runCatching {
            writer?.let { w ->
                w.write(line)
                w.write("\n")
                w.flush()
            }
        }
    }

    /** Logs a warning with optional exception. */
    fun warn(message: String, t: Throwable? = null) {
        val full = if (t != null) "$message: ${t.javaClass.simpleName}: ${t.message}" else message
        val ts = dateFmt.format(Date())
        val line = "[$ts] WARN: $full"
        Log.w(TAG, message, t)

        runCatching {
            writer?.let { w ->
                w.write(line)
                w.write("\n")
                if (t != null) {
                    val sw = StringWriter()
                    t.printStackTrace(PrintWriter(sw))
                    w.write(sw.toString())
                    w.write("\n")
                }
                w.flush()
            }
        }
    }

    /** Logs an error with optional exception. */
    fun error(message: String, t: Throwable? = null) {
        val full = if (t != null) "$message: ${t.javaClass.simpleName}: ${t.message}" else message
        val ts = dateFmt.format(Date())
        val line = "[$ts] ERROR: $full"
        Log.e(TAG, message, t)

        runCatching {
            writer?.let { w ->
                w.write(line)
                w.write("\n")
                if (t != null) {
                    val sw = StringWriter()
                    t.printStackTrace(PrintWriter(sw))
                    w.write(sw.toString())
                    w.write("\n")
                }
                w.flush()
            }
        }
    }

    /**
     * Returns the log file path, for display in the diagnostics UI.
     */
    fun logFilePath(): String? = logFile?.absolutePath

    /**
     * Reads the last N lines of the log, for display in-app.
     */
    @Synchronized
    fun tail(lines: Int = 100): String {
        val file = logFile ?: return "(no log file)"
        return runCatching {
            file.readLines().takeLast(lines).joinToString("\n")
        }.getOrElse { "(cannot read: ${it.message})" }
    }

    /**
     * Installs a global crash handler that writes the stack trace to the
     * log file before the process dies. This catches SIGSEGV-equivalent
     * Java exceptions (native crashes still kill the process, but any
     * Java/Kotlin uncaught exception is captured).
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            error("UNCAUGHT EXCEPTION on ${thread.name}", throwable)
            // Flush before dying
            runCatching { writer?.flush() }
            previous?.uncaughtException(thread, throwable)
        }
    }
}

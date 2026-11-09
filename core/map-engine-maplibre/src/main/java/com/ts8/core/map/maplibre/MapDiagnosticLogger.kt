package com.ts8.core.map.maplibre

/**
 * A file-based diagnostic logger that the app module provides.
 *
 * **Why this interface exists.** `MapLibreMapController` lives in
 * `core:map-engine-maplibre` and cannot directly reference `DiagnosticLogger`
 * in the `app` module. On real TS8 hardware, USB debugging doesn't work, so
 * logcat is invisible — only the file-based diagnostic log is readable by
 * copying a file to a USB stick. This interface lets the controller write
 * to that same file without a module dependency.
 *
 * The default no-op implementation means the controller still works when
 * the app hasn't provided a logger (e.g. in unit tests).
 */
interface MapDiagnosticLogger {
    fun log(message: String)
    fun error(message: String, throwable: Throwable? = null)

    companion object {
        private val NOOP = object : MapDiagnosticLogger {
            override fun log(message: String) {}
            override fun error(message: String, throwable: Throwable?) {}
        }

        @Volatile
        private var instance: MapDiagnosticLogger = NOOP

        /**
         * Installs the app-level diagnostic logger.
         * Call once at app startup, before any map is created.
         */
        fun install(logger: MapDiagnosticLogger) {
            instance = logger
        }

        /**
         * Returns the installed logger, or a no-op if none installed.
         */
        fun get(): MapDiagnosticLogger = instance
    }
}

package com.ts8.core.voice.offline

/**
 * Bridge for file-based diagnostic logging from the voice-offline module.
 *
 * **Why this exists.** The [RecognitionProviderRouter] lives in a core module
 * that cannot depend on the app's [com.ts8.launcher.runtime.DiagnosticLogger].
 * On the TS8 head unit, USB debugging does not work, so logcat is invisible.
 * Without file-based logging, the developer cannot see which provider was
 * chosen, what errors occurred, or why the driver sees "không hỗ trợ".
 *
 * Installed once at app startup via [install]; defaults to a no-op so the
 * router works in tests and in any app that does not wire it.
 */
interface VoiceDiagnosticLogger {

    fun log(message: String)

    fun error(message: String, throwable: Throwable? = null)

    companion object {
        private val NOOP = object : VoiceDiagnosticLogger {
            override fun log(message: String) = Unit
            override fun error(message: String, throwable: Throwable?) = Unit
        }

        @Volatile
        private var instance: VoiceDiagnosticLogger = NOOP

        /** Installs the bridge. Call once at app startup. */
        fun install(logger: VoiceDiagnosticLogger) {
            instance = logger
        }

        /** Returns the installed logger, or a no-op if none has been installed. */
        fun get(): VoiceDiagnosticLogger = instance
    }
}

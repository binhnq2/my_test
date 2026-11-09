package com.ts8.launcher

import android.app.Application

/**
 * Process-wide entry point.
 *
 * Holds nothing but the service registry: every engine is created lazily by
 * [com.ts8.launcher.di.AppGraph] so a cold start does not pay for the map
 * engine, the routing graph, or a media scan.
 */
class Ts8LauncherApp : Application() {
    lateinit var graph: com.ts8.launcher.di.AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // Start diagnostic logging before anything else — the map load is
        // the most failure-prone step and we need its log even if the app
        // crashes before reaching the home screen.
        com.ts8.launcher.runtime.DiagnosticLogger.init(this)
        com.ts8.launcher.runtime.DiagnosticLogger.log("Ts8LauncherApp.onCreate")

        // Bridge the app's DiagnosticLogger to the core map module so
        // MapLibreMapController can write to the same file log (critical
        // when USB debugging doesn't work — logcat is invisible).
        com.ts8.core.map.maplibre.MapDiagnosticLogger.install(
            object : com.ts8.core.map.maplibre.MapDiagnosticLogger {
                override fun log(message: String) {
                    com.ts8.launcher.runtime.DiagnosticLogger.log(message)
                }
                override fun error(message: String, throwable: Throwable?) {
                    com.ts8.launcher.runtime.DiagnosticLogger.error(message, throwable)
                }
            }
        )

        // Bridge the app's DiagnosticLogger to the core voice module so
        // RecognitionProviderRouter can write to the same file log.
        // Without this, the developer cannot see which provider was chosen
        // or why the driver sees "không hỗ trợ".
        com.ts8.core.voice.offline.VoiceDiagnosticLogger.install(
            object : com.ts8.core.voice.offline.VoiceDiagnosticLogger {
                override fun log(message: String) {
                    com.ts8.launcher.runtime.DiagnosticLogger.log(message)
                }
                override fun error(message: String, throwable: Throwable?) {
                    com.ts8.launcher.runtime.DiagnosticLogger.error(message, throwable)
                }
            }
        )

        graph = com.ts8.launcher.di.AppGraph(this)
        com.ts8.launcher.di.AppGraph.install(graph)
    }
}

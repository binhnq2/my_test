package com.ts8.launcher.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ts8.core.location.AndroidLocationSource
import com.ts8.core.location.LocationEngine
import com.ts8.core.location.LocationFix
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The one GPS subscription in the process.
 *
 * **Why this is not owned by a screen.** The spec says the position must keep
 * flowing while the driver moves between Home, the map and the music screen.
 * `LocationManager` re-registers do not just cost time — they discard the
 * smoothing state in [LocationEngine], so the marker jumps and the bearing
 * resets every time a screen opens. One source, reference-counted by the
 * screens that are visible, keeps a single continuous track.
 *
 * **Why the fan-out is here.** [AndroidLocationSource] delivers on its own
 * background thread. Listeners here are UI objects, so every delivery is
 * hopped to the main thread exactly once, before the fan-out. Without that,
 * each screen would have to remember to hop, and one that forgot would touch
 * a View from the wrong thread.
 */
class LocationRuntime(
    context: Context,
    private val engine: LocationEngine
) {

    interface Listener {
        /** An accepted, smoothed, possibly snapped fix. Main thread. */
        fun onFix(fix: LocationFix)

        /** Receiver status changed — used to show "đang tìm tín hiệu GPS". */
        fun onGpsStatus(hasFix: Boolean, satellites: Int) {}
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    private var source: AndroidLocationSource? = null

    /** Number of visible screens that asked for fixes. */
    private var refCount = 0

    @Volatile
    private var lastFixInternal: LocationFix? = null

    @Volatile
    private var lastGpsFix = false

    @Volatile
    private var lastSatellites = 0

    val lastFix: LocationFix? get() = lastFixInternal
    val hasGpsFix: Boolean get() = lastGpsFix
    val satellites: Int get() = lastSatellites

    /** True when the platform will let us read position at all. */
    fun hasPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Registers a listener and starts the receiver if this is the first user.
     *
     * Safe to call from `onStart` without checking anything: a second screen
     * simply joins the stream. The caller must pair it with [release].
     */
    fun acquire(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
        refCount++
        if (refCount == 1) start()
        // A screen that opens after the first fix must not sit blank until the
        // next one — it gets the current position immediately.
        lastFixInternal?.let { main.post { deliver(it) } }
    }

    fun release(listener: Listener) {
        listeners.remove(listener)
        if (refCount > 0) refCount--
        if (refCount == 0) stop()
    }

    private fun start() {
        if (source != null) return
        if (!hasPermission()) {
            Log.i(TAG, "no location permission; map will show the pack centre")
            return
        }
        val created = AndroidLocationSource(appContext, engine) { fix ->
            lastFixInternal = fix
            main.post { deliver(fix) }
        }
        source = created
        // `start` is annotated @RequiresPermission; `hasPermission()` above is
        // the check lint cannot see through, because it is a method on this
        // class rather than an inline `checkSelfPermission` call. The call is
        // still wrapped in `runCatching` because a vendor ROM can throw from a
        // provider that was denied even though the grant says otherwise.
        @Suppress("MissingPermission")
        runCatching { created.start() }
            .onFailure { Log.w(TAG, "location start failed: ${it.message}"); source = null }
        scheduleStatusPoll(created)
    }

    /**
     * Polls the satellite state on the main thread.
     *
     * [AndroidLocationSource] exposes its counters as plain fields; polling at
     * 2 s is enough for a status line and avoids adding a callback interface to
     * the core module for what is purely cosmetic.
     */
    private fun scheduleStatusPoll(created: AndroidLocationSource) {
        val poll = object : Runnable {
            override fun run() {
                if (source !== created) return
                val changed = lastGpsFix != created.hasGpsFix ||
                    lastSatellites != created.satelliteCount
                lastGpsFix = created.hasGpsFix
                lastSatellites = created.satelliteCount
                if (changed) {
                    for (l in listeners) runCatching { l.onGpsStatus(lastGpsFix, lastSatellites) }
                }
                main.postDelayed(this, STATUS_POLL_MS)
            }
        }
        main.postDelayed(poll, STATUS_POLL_MS)
    }

    private fun stop() {
        source?.let { runCatching { it.stop() } }
        source = null
        lastGpsFix = false
        lastSatellites = 0
    }

    private fun deliver(fix: LocationFix) {
        for (l in listeners) {
            // A listener that throws must not stop the others: the map still
            // needs its fix after the music card's binding failed.
            runCatching { l.onFix(fix) }
        }
    }

    companion object {
        private const val TAG = "Ts8LocationRuntime"
        private const val STATUS_POLL_MS = 2_000L
    }
}

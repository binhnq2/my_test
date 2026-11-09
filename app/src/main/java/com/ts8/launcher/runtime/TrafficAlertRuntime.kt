package com.ts8.launcher.runtime

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ts8.core.location.LocationFix
import com.ts8.core.traffic.TrafficAlert
import com.ts8.core.traffic.TrafficAlertController
import com.ts8.core.traffic.TrafficSign
import com.ts8.core.traffic.TrafficSignStore
import com.ts8.core.map.TrafficSignOverlay
import com.ts8.core.map.SignClusterer
import com.ts8.launcher.di.AppGraph
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The process-wide owner of the traffic-sign alert pipeline.
 *
 * **Why this mirrors [SpeedRuntime].** Traffic alerts share the same lifecycle
 * as speed alerts: they are driven by GPS fixes, they must survive screen
 * switches, and they deliver through the same audio bridge. A per-screen
 * controller would re-load the spatial-grid index on every tab switch and
 * accumulate duplicate cell caches; a process-wide runtime with reference
 * counting avoids both.
 *
 * **Reference counting.** Screens call [acquire] / [release]; the GPS
 * subscription and sign-loading loop run only while the count is > 0. The
 * subscription comes from [AppGraph.location], so three visible screens share
 * one GPS receiver with the speed and parking pipelines.
 *
 * **Threading.** GPS fixes arrive on the main thread (from
 * [LocationRuntime]). The store loads cells on the calling thread, which is
 * acceptable because each cell file is ≤50 KB of GZIP — the I/O is one
 * `AssetManager.open` and one `GZIPInputStream` read, both fast enough for
 * the main thread at 1 Hz. The expensive part (JSON parse) is also under 5 ms
 * per cell on the Cortex-A7.
 */
class TrafficAlertRuntime(
    private val context: Context,
    private val graph: AppGraph,
    private val settings: AppSettings
) {
    private val main = Handler(Looper.getMainLooper())

    /** The sign store, lazily created on first acquire. */
    @Volatile
    private var store: TrafficSignStore? = null

    /** The alert controller, lazily created on first acquire. */
    @Volatile
    private var controller: TrafficAlertController? = null

    /** Reference count for the GPS subscription. */
    private var refCount = 0

    /** Listeners fan-out, copy-on-write so iteration is safe without locks. */
    private val listeners = CopyOnWriteArrayList<TrafficAlertController.Listener>()

    /** The last set of signs pushed to map listeners, to avoid redundant updates. */
    @Volatile
    private var lastSignOverlay: List<TrafficSignOverlay> = emptyList()

    /**
     * Re-applies the driver's alert switches the moment they change.
     *
     * The settings screen writes to the same [AppSettings] store, so a
     * preference listener is enough — no screen has to remember to notify this
     * runtime, which is exactly the wiring that was missing when the switches
     * were added and nothing read them.
     */
    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> applySettings() }

    /**
     * Acquires the traffic alert pipeline.
     *
     * On the first acquire (refCount 0 → 1), creates the store and controller,
     * subscribes to [AppGraph.location], and starts feeding fixes. On
     * subsequent acquires, only increments the count.
     *
     * @param listener Optional listener for alert and sign-update events.
     *   The same listener must be passed to [release].
     */
    fun acquire(listener: TrafficAlertController.Listener? = null) {
        synchronized(this) {
            if (refCount == 0) {
                start()
            }
            refCount++
            if (listener != null) {
                listeners.addIfAbsent(listener)
                controller?.addListener(listener)
            }
        }
    }

    /**
     * Releases the pipeline. On the last release (refCount 1 → 0), unsubscribes
     * from GPS and clears the controller's state.
     */
    fun release(listener: TrafficAlertController.Listener? = null) {
        synchronized(this) {
            if (listener != null) {
                listeners.remove(listener)
                controller?.removeListener(listener)
            }
            refCount = (refCount - 1).coerceAtLeast(0)
            if (refCount == 0) {
                stop()
            }
        }
    }

    private fun start() {
        try {
            val s = TrafficSignStore(context)
            store = s

            // The speed limit resolver is owned by SpeedMonitor, which is owned
            // by SpeedRuntime. We pass it through the graph.
            val resolver = graph.speed.limitResolver()
            val ctrl = TrafficAlertController(
                store = s,
                speedLimitResolver = resolver,
                clock = { android.os.SystemClock.elapsedRealtime() }
            )
            controller = ctrl

            // The switches are read before the first fix is fed, so a driver
            // who muted cameras last week does not hear one on this cold start.
            ctrl.configure(alertConfig())
            settings.registerListener(prefsListener)

            // Subscribe to GPS fixes.
            graph.location.acquire(locationListener)

            // Feed the last known fix immediately so the map overlay shows
            // signs without waiting for the next GPS sample.
            graph.location.lastFix?.let { fix ->
                onFix(fix)
            }

            Log.i(TAG, "started; store ready=${s.indexReady}")
        } catch (e: Exception) {
            Log.w(TAG, "start failed: ${e.message}")
        }
    }

    private fun stop() {
        runCatching { settings.unregisterListener(prefsListener) }
        graph.location.release(locationListener)
        controller = null
        // The store is kept so cached cells survive a brief screen switch;
        // it is cheap and the data is static. A long idle would benefit from
        // dropping it, but the reference-counting model means it is rebuilt
        // on the next acquire anyway.
        lastSignOverlay = emptyList()
    }

    /**
     * GPS fix handler — drives the traffic alert pipeline.
     *
     * Called on the main thread by [LocationRuntime].
     */
    private val locationListener = object : com.ts8.launcher.runtime.LocationRuntime.Listener {
        override fun onFix(fix: LocationFix) = this@TrafficAlertRuntime.onFix(fix)
        override fun onGpsStatus(hasFix: Boolean, satellites: Int) = Unit
    }

    private fun onFix(fix: LocationFix) {
        val ctrl = controller ?: return
        // `bearingDeg` is nullable and negative means "the receiver has no
        // course" (a car park, a cold start). Both map to NaN, which the
        // controller reads as "no heading filter" rather than as north.
        val bearing = fix.bearingDeg
        val heading = if (bearing != null && bearing >= 0f) bearing else Float.NaN
        ctrl.onLocationUpdate(fix.point.lat, fix.point.lon, heading, fix.speedMps)
    }

    /**
     * Builds the controller configuration from the driver's settings.
     *
     * A one-way translation (`AppSettings` → [TrafficAlertController.Config]) so
     * there is exactly one place that decides what a switch means.
     */
    private fun alertConfig(): TrafficAlertController.Config =
        TrafficAlertController.Config(
            alertsEnabled = settings.drivingAlertsEnabled,
            cameraAlertsEnabled = settings.alertCameraEnabled,
            speedSignAlertsEnabled = settings.alertSpeedEnabled,
            showSignsOnMap = true
        )

    /**
     * Re-applies the switches and refreshes the alert already on screen.
     *
     * Re-feeding the last fix is what makes muting immediate: without it the
     * card would sit there until the next GPS sample, and on a parked car there
     * may not be one.
     */
    fun applySettings() {
        val ctrl = controller ?: return
        ctrl.configure(alertConfig())
        graph.location.lastFix?.let { onFix(it) }
    }

    /** The current active alert, or null. */
    fun currentAlert(): TrafficAlert? = controller?.currentAlert()

    fun signOverlay(): List<TrafficSignOverlay> {
        return signOverlay(Double.MAX_VALUE)
    }

    /**
     * Returns the current sign overlay list for map rendering, filtered and
     * deduplicated for the given zoom level.
     *
     * Converts the internal [TrafficSign] list to [TrafficSignOverlay] (the
     * map-API type) so the map engine does not depend on the location engine.
     * Then runs [SignClusterer.process] to prevent icon pile-up: in dense
     * urban cells (e.g. Hà Nội's 42_211 with 3,737 signs) showing every sign
     * at once produces an unreadable smudge.
     *
     * @param zoom The current map zoom. At low zoom, only cameras and major
     *   speed limits are shown. At high zoom, everything is shown (after
     *   proximity dedup). Pass [Double.MAX_VALUE] to show all signs unfiltered.
     */
    fun signOverlay(zoom: Double): List<TrafficSignOverlay> {
        val cached = controller?.let { store?.cachedSigns() } ?: return emptyList()
        if (cached.isEmpty()) return emptyList()
        val raw = cached.map {
            TrafficSignOverlay(
                point = it.point,
                typeCode = it.typeCode,
                speedLimit = it.speedLimit
            )
        }
        val overlay = SignClusterer.process(raw, zoom)
        lastSignOverlay = overlay
        return overlay
    }

    /**
     * Pushes the current sign overlay to the map controller, filtered by zoom.
     *
     * Called by screens after the map is ready and on each sign-update
     * notification. The zoom is read from the map controller so that the
     * overlay density matches what the driver can actually see: at zoom 12
     * only cameras are shown, at zoom 16 everything is.
     *
     * @param mapController The map controller to push signs to.
     * @param zoom The current map zoom, for density filtering.
     */
    fun pushSignOverlayTo(
        mapController: com.ts8.core.map.maplibre.MapLibreMapController?,
        zoom: Double = Double.MAX_VALUE
    ) {
        val mc = mapController ?: return
        val overlay = signOverlay(zoom)
        mc.setTrafficSigns(overlay)
    }

    companion object {
        private const val TAG = "TrafficAlertRuntime"
    }
}

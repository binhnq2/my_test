package com.ts8.core.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GpsStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresPermission

/**
 * Android 6 boundary for [DefaultLocationEngine].
 *
 * Uses `LocationManager` directly rather than Play Services: the head unit has
 * no Google Play, so `FusedLocationProvider` is not an option and the platform
 * provider is the only one that exists.
 *
 * The pipeline itself is platform-free; this class only translates
 * [Location] into [RawLocation], on a background thread so a slow filter never
 * blocks the main thread's map rendering.
 */
class AndroidLocationSource(
    context: Context,
    private val engine: LocationEngine,
    private val onFix: (LocationFix) -> Unit
) {
    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var listening = false
    private var gpsStatusListener: GpsStatus.Listener? = null

    /** Last known satellite count, for the diagnostics screen. */
    @Volatile
    var satelliteCount: Int = 0
        private set

    /** True when the platform reports a GPS fix lock. */
    @Volatile
    var hasGpsFix: Boolean = false
        private set

    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val raw = toRaw(location)
            val fix = engine.onRawLocation(raw)
            if (fix != null) onFix(fix)
        }

        // Deprecated on newer platforms but still the only callback on API 23.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            if (provider == LocationManager.GPS_PROVIDER) {
                hasGpsFix = status == android.location.LocationProvider.AVAILABLE
            }
        }

        override fun onProviderEnabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) hasGpsFix = false
        }

        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) hasGpsFix = false
        }
    }

    fun hasPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Starts listening. Silently does nothing without permission — the caller
     * is expected to have checked [hasPermission] first and to show the
     * permission prompt; a hard failure here would crash the launcher.
     */
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun start() {
        if (listening || !hasPermission()) return

        val t = HandlerThread("ts8-location").also { it.start() }
        thread = t
        handler = Handler(t.looper)

        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        if (!gpsEnabled) {
            // Network provider is a poor substitute but keeps the map from
            // sitting at the last known point forever where it is available.
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                requestProvider(LocationManager.NETWORK_PROVIDER)
                listening = true
            }
            return
        }

        requestProvider(LocationManager.GPS_PROVIDER)

        // Already have a recent fix? Feed it immediately so the map does not
        // wait ~30 s for the first satellite lock.
        lastKnown()?.let { location ->
            if (SystemClock.elapsedRealtime() - location.elapsedRealtimeNanos / 1_000_000 <
                MAX_FRESH_LAST_KNOWN_MS
            ) {
                engine.onRawLocation(toRaw(location))?.let(onFix)
            }
        }

        @Suppress("DEPRECATION")
        gpsStatusListener = GpsStatus.Listener { event ->
            if (event == GpsStatus.GPS_EVENT_SATELLITE_STATUS) {
                @Suppress("DEPRECATION")
                val status = locationManager.getGpsStatus(null)
                satelliteCount = status?.satellites?.count { it.usedInFix() } ?: 0
            }
        }.also { locationManager.addGpsStatusListener(it) }

        listening = true
    }

    @RequiresPermission(anyOf = [
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ])
    private fun requestProvider(provider: String) {
        val handler = this.handler ?: return
        runCatching {
            locationManager.requestLocationUpdates(
                provider,
                MIN_INTERVAL_MS,
                MIN_DISTANCE_M,
                listener,
                handler.looper
            )
        }
    }

    private fun lastKnown(): Location? {
        if (!hasPermission()) return null
        return runCatching {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()
    }

    fun stop() {
        if (!listening) return
        runCatching { locationManager.removeUpdates(listener) }
        gpsStatusListener?.let { runCatching { locationManager.removeGpsStatusListener(it) } }
        gpsStatusListener = null
        thread?.quitSafely()
        thread = null
        handler = null
        listening = false
        hasGpsFix = false
    }

    private fun toRaw(location: Location): RawLocation = RawLocation(
        point = com.ts8.core.map.GeoPoint(location.latitude, location.longitude),
        accuracyMeters = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE,
        elapsedRealtimeMs = location.elapsedRealtimeNanos / 1_000_000,
        provider = location.provider ?: "unknown",
        speedMps = if (location.hasSpeed()) location.speed else -1f,
        bearingDeg = if (location.hasBearing()) location.bearing else -1f,
        altitudeMeters = if (location.hasAltitude()) location.altitude else null
    )

    companion object {
        /**
         * 1 Hz. A car at 120 km/h covers 33 m per second, so anything slower
         * makes the marker visibly step; anything faster burns battery and CPU
         * the head unit needs for map rendering.
         */
        private const val MIN_INTERVAL_MS = 1_000L
        private const val MIN_DISTANCE_M = 0f
        private const val MAX_FRESH_LAST_KNOWN_MS = 60_000L
    }
}

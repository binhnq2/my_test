package com.ts8.core.location

import com.ts8.core.map.GeoPoint

/**
 * One raw fix from the platform location provider.
 *
 * Nothing here is filtered — [LocationEngine] is what turns a stream of these
 * into a [LocationFix] the navigator can trust. Keeping the raw shape separate
 * makes it obvious at call sites which of the two is in hand.
 */
data class RawLocation(
    /** WGS-84 position. */
    val point: GeoPoint,
    /** Accuracy radius in metres (68% confidence). */
    val accuracyMeters: Float,
    /** Elapsed realtime milliseconds at fix time. */
    val elapsedRealtimeMs: Long,
    /** Provider name: "gps", "network", "fused". */
    val provider: String = "gps",
    /** Ground speed in m/s, or -1 when the provider cannot report it. */
    val speedMps: Float = -1f,
    /** Course over ground in degrees, or -1 when unknown. */
    val bearingDeg: Float = -1f,
    /** Altitude in metres above the WGS-84 ellipsoid, or null. */
    val altitudeMeters: Double? = null
) {
    /** True when the fix carries enough to be plotted at all. */
    fun isUsable(): Boolean =
        point.isValid() &&
            accuracyMeters.isFinite() &&
            accuracyMeters > 0f &&
            accuracyMeters <= MAX_ACCURACY_METERS

    companion object {
        /**
         * Beyond this the fix is not "somewhere in this block" — it is noise.
         * A head unit parked under a concrete car park routinely reports 200 m;
         * 500 m means the provider guessed.
         */
        const val MAX_ACCURACY_METERS = 500f
    }
}

/** A fix the engine has accepted, smoothed and optionally snapped. */
data class LocationFix(
    /** Position after smoothing and (if a route is active) route snapping. */
    val point: GeoPoint,
    /** Position as reported, before snapping. Used to detect a wrong-way snap. */
    val rawPoint: GeoPoint,
    /** Reported accuracy in metres. */
    val accuracyMeters: Float,
    /** Filtered speed in m/s; never negative. */
    val speedMps: Float,
    /** Filtered course in degrees clockwise from north; null when stopped. */
    val bearingDeg: Float?,
    val elapsedRealtimeMs: Long,
    /** Distance in metres this fix sits from the route line, or null when free. */
    val distanceFromRouteMeters: Float? = null,
    /** Distance travelled along the active route, in metres. */
    val distanceAlongRouteMeters: Float? = null,
    /** True when [point] came from the route polyline rather than the GPS. */
    val snappedToRoute: Boolean = false
)

/**
 * GPS processing pipeline.
 *
 * The order matters and is enforced by [LocationEngine]:
 *
 * 1. **Validate** — reject NaN, impossible coordinates, and fixes whose
 *    accuracy radius makes them useless. A rejected fix is dropped, never
 *    "corrected".
 * 2. **Spike rejection** — a fix that implies a speed no car can achieve is
 *    held back. One bad sample must not move the marker, and on a route it
 *    must never trigger a reroute.
 * 3. **Smoothing** — an exponential moving average on position and speed.
 *    Raw GPS jitters by several metres every second even when parked, and a
 *    marker that shakes reads as broken.
 * 4. **Bearing filter** — the course is only trusted above walking speed and
 *    is rate-limited, because a stationary receiver emits random headings.
 * 5. **Snap to route** — when a route is active, the position is projected
 *    onto the route polyline so the marker follows the road, not the noise.
 *    Snapping is refused when the fix is too far from the line or moving
 *    clearly away from it, which is the signal the reroute logic consumes.
 */
interface LocationEngine {
    /** True once at least one accepted fix has produced an output. */
    fun hasFix(): Boolean

    /** Most recent accepted fix, or null before the first one. */
    fun lastFix(): LocationFix?

    /**
     * Feeds a raw fix through the pipeline.
     *
     * @return the accepted fix, or null when the sample was rejected. A null
     *         return is normal and callers must not treat it as an error.
     */
    fun onRawLocation(raw: RawLocation): LocationFix?

    /**
     * Activates route snapping. Passing null disables it.
     *
     * The polyline is the route's drawn geometry in travel order; the engine
     * precomputes cumulative distance so snapping and "distance along route"
     * are O(log n) per fix rather than a linear walk.
     */
    fun setRoute(points: List<GeoPoint>?)

    /** Clears smoothing state, e.g. after a route change or a long pause. */
    fun reset()

    /** Diagnostics counters, for the diagnostics screen. */
    fun stats(): LocationStats
}

/** Counters that explain *why* fixes were dropped. */
data class LocationStats(
    val received: Long = 0,
    val accepted: Long = 0,
    val rejectedInvalid: Long = 0,
    val rejectedSpike: Long = 0,
    val rejectedStale: Long = 0,
    val snapped: Long = 0,
    val snapRefused: Long = 0
) {
    val acceptanceRate: Float
        get() = if (received == 0L) 0f else accepted.toFloat() / received
}

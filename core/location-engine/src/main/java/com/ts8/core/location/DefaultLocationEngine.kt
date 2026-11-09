package com.ts8.core.location

import com.ts8.core.map.GeoPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The GPS pipeline described on [LocationEngine].
 *
 * Deliberately has no Android dependency: the platform hands it raw fixes, and
 * every decision it makes is testable on the JVM with synthetic tracks. The
 * Android side lives in [AndroidLocationSource].
 */
class DefaultLocationEngine(
    private val config: Config = Config()
) : LocationEngine {

    data class Config(
        /** Reject fixes worse than this accuracy. */
        val maxAccuracyMeters: Float = RawLocation.MAX_ACCURACY_METERS,
        /**
         * A fix implying a speed above this is a spike. 90 m/s is 324 km/h —
         * beyond any road in the pack, so it can only be noise or a teleport.
         */
        val maxPlausibleSpeedMps: Float = 90f,
        /** Position EMA weight for a new sample. Lower is smoother and laggier. */
        val positionAlpha: Float = 0.35f,
        /** Speed EMA weight. Speed is noisier than position, so it moves slower. */
        val speedAlpha: Float = 0.25f,
        /** Below this speed the reported course is meaningless; hold the last one. */
        val minBearingSpeedMps: Float = 1.2f,
        /** Max degrees the reported course may jump between fixes. */
        val maxBearingChangeDeg: Float = 45f,
        /** A fix older than this after the previous one is out of order. */
        val maxSampleGapMs: Long = 30_000L,
        /** Snap is refused beyond this distance from the route line. */
        val maxSnapDistanceMeters: Float = 50f,
        /**
         * Beyond this distance from the route, snapping is pointless and the
         * navigator should be told the driver has left it — but the engine
         * still reports the raw position so the UI shows where they are.
         */
        val offRouteDistanceMeters: Float = 60f,
        /** Two consecutive off-route fixes are needed before the flag sticks. */
        val offRouteConfirmSamples: Int = 3
    )

    private val cfg = config

    // -- smoothing state ---------------------------------------------------
    private var smoothedPoint: GeoPoint? = null
    private var smoothedSpeed = 0f
    private var filteredBearing: Float? = null
    private var lastAcceptedMs = 0L
    private var lastAccepted: LocationFix? = null

    // -- route state -------------------------------------------------------
    private var routePoints: List<GeoPoint> = emptyList()
    private var cumulative: FloatArray = FloatArray(0)
    private var routeLengthMeters = 0f
    private var lastProjection: Projection? = null
    private var offRouteStreak = 0

    // -- stats -------------------------------------------------------------
    private var received = 0L
    private var accepted = 0L
    private var rejectedInvalid = 0L
    private var rejectedSpike = 0L
    private var rejectedStale = 0L
    private var snappedCount = 0L
    private var snapRefused = 0L

    override fun hasFix(): Boolean = lastAccepted != null

    override fun lastFix(): LocationFix? = lastAccepted

    override fun stats(): LocationStats = LocationStats(
        received = received,
        accepted = accepted,
        rejectedInvalid = rejectedInvalid,
        rejectedSpike = rejectedSpike,
        rejectedStale = rejectedStale,
        snapped = snappedCount,
        snapRefused = snapRefused
    )

    override fun reset() {
        smoothedPoint = null
        smoothedSpeed = 0f
        filteredBearing = null
        lastAcceptedMs = 0L
        lastAccepted = null
        lastProjection = null
        offRouteStreak = 0
    }

    override fun setRoute(points: List<GeoPoint>?) {
        routePoints = points.orEmpty()
        routeLengthMeters = 0f
        if (routePoints.size < 2) {
            cumulative = FloatArray(0)
            lastProjection = null
            offRouteStreak = 0
            return
        }
        val cum = FloatArray(routePoints.size)
        var acc = 0f
        for (i in 1 until routePoints.size) {
            acc += distanceMeters(routePoints[i - 1], routePoints[i]).toFloat()
            cum[i] = acc
        }
        cumulative = cum
        routeLengthMeters = acc
        // The previous projection belongs to the previous route.
        lastProjection = null
        offRouteStreak = 0
    }

    override fun onRawLocation(raw: RawLocation): LocationFix? {
        received++

        // --- 1. validate --------------------------------------------------
        if (!raw.isUsable() || raw.accuracyMeters > cfg.maxAccuracyMeters) {
            rejectedInvalid++
            return null
        }
        // A fix timestamped before the last accepted one is out of order. GPS
        // providers do deliver these around a tunnel exit.
        if (lastAcceptedMs != 0L && raw.elapsedRealtimeMs < lastAcceptedMs) {
            rejectedStale++
            return null
        }

        // --- 2. spike rejection -------------------------------------------
        val previousPoint = smoothedPoint
        if (previousPoint != null && lastAcceptedMs != 0L) {
            val dtSec = ((raw.elapsedRealtimeMs - lastAcceptedMs).coerceAtLeast(0L)) / 1000.0
            if (dtSec > 0.0) {
                val moved = distanceMeters(previousPoint, raw.point)
                val impliedSpeed = (moved / dtSec).toFloat()

                // Allow the reported accuracy: two 30 m fixes can be 60 m apart
                // while standing still.
                val slack = raw.accuracyMeters + (lastAccepted?.accuracyMeters ?: 0f)
                val suspicious = impliedSpeed > cfg.maxPlausibleSpeedMps && moved > slack

                if (suspicious) {
                    rejectedSpike++
                    // Hold the last accepted fix rather than emitting a bogus
                    // one. Navigation state depends on this: a single spike
                    // must never look like "driver left the route".
                    return lastAccepted
                }
            }
        }

        // --- 3. smoothing -------------------------------------------------
        val smoothed = if (previousPoint == null) {
            raw.point
        } else {
            // A sample that has barely moved should pull the estimate less:
            // standing still should converge to a still point quickly.
            val alpha = cfg.positionAlpha
            GeoPoint(
                previousPoint.lat + (raw.point.lat - previousPoint.lat) * alpha,
                previousPoint.lon + (raw.point.lon - previousPoint.lon) * alpha
            )
        }

        val measuredSpeed = if (raw.speedMps >= 0f) {
            raw.speedMps
        } else if (previousPoint != null && lastAcceptedMs != 0L) {
            val dtSec = ((raw.elapsedRealtimeMs - lastAcceptedMs).coerceAtLeast(1L)) / 1000.0
            (distanceMeters(previousPoint, raw.point) / dtSec).toFloat()
        } else {
            0f
        }
        smoothedSpeed = if (smoothedPoint == null) {
            measuredSpeed.coerceAtLeast(0f)
        } else {
            smoothedSpeed + (measuredSpeed - smoothedSpeed) * cfg.speedAlpha
        }.coerceAtLeast(0f)

        // --- 4. bearing filter --------------------------------------------
        val candidateBearing = when {
            raw.bearingDeg >= 0f && raw.bearingDeg < 360f -> raw.bearingDeg
            smoothedPoint != null && smoothedSpeed > cfg.minBearingSpeedMps ->
                bearingBetween(smoothedPoint!!, smoothed)
            else -> null
        }
        filteredBearing = filterBearing(filteredBearing, candidateBearing)

        // --- 5. snap to route ---------------------------------------------
        var outputPoint = smoothed
        var distanceFromRoute: Float? = null
        var distanceAlong: Float? = null
        var didSnap = false

        if (routePoints.size >= 2) {
            val projection = projectOntoRoute(smoothed)
            if (projection != null) {
                distanceFromRoute = projection.distanceMeters
                val withinSnap = projection.distanceMeters <= cfg.maxSnapDistanceMeters
                val plausibleForward = isForwardProgress(projection)

                if (withinSnap && plausibleForward) {
                    outputPoint = projection.point
                    distanceAlong = projection.alongMeters
                    lastProjection = projection
                    offRouteStreak = 0
                    didSnap = true
                    snappedCount++
                } else {
                    snapRefused++
                    // Only count a fix as "off route" when it is genuinely
                    // outside the corridor; refusing to snap backwards along
                    // the route is not a departure.
                    if (projection.distanceMeters > cfg.offRouteDistanceMeters) {
                        offRouteStreak++
                    } else {
                        offRouteStreak = 0
                    }
                    lastProjection?.let { distanceAlong = it.alongMeters }
                }
            }
        } else {
            offRouteStreak = 0
        }

        smoothedPoint = smoothed
        lastAcceptedMs = raw.elapsedRealtimeMs
        accepted++

        val fix = LocationFix(
            point = outputPoint,
            rawPoint = raw.point,
            accuracyMeters = raw.accuracyMeters,
            speedMps = smoothedSpeed,
            bearingDeg = filteredBearing,
            elapsedRealtimeMs = raw.elapsedRealtimeMs,
            distanceFromRouteMeters = distanceFromRoute,
            distanceAlongRouteMeters = distanceAlong,
            snappedToRoute = didSnap
        )
        lastAccepted = fix
        return fix
    }

    // -- route projection --------------------------------------------------

    private data class Projection(
        val point: GeoPoint,
        val distanceMeters: Float,
        val alongMeters: Float,
        val segmentIndex: Int
    )

    /**
     * Nearest point on the route polyline.
     *
     * Projects onto every segment in a window around the last projection. On a
     * self-crossing route (a bridge you cross twice) a global search jumps the
     * marker to the wrong pass; the window keeps it on the pass being driven.
     */
    private fun projectOntoRoute(p: GeoPoint): Projection? {
        if (routePoints.size < 2) return null

        val window = 400
        val lastIdx = lastProjection?.segmentIndex
        val from = if (lastIdx == null) 0 else max(0, lastIdx - window)
        val to = if (lastIdx == null) routePoints.size - 2
        else min(routePoints.size - 2, lastIdx + window)

        var best: Projection? = null
        for (i in from..to) {
            val a = routePoints[i]
            val b = routePoints[i + 1]
            val proj = projectOnSegment(p, a, b)
            if (best == null || proj.distanceMeters < best.distanceMeters) {
                best = Projection(
                    point = proj.point,
                    distanceMeters = proj.distanceMeters,
                    alongMeters = cumulative[i] + proj.alongFraction *
                        (cumulative[i + 1] - cumulative[i]),
                    segmentIndex = i
                )
            }
        }

        // A window that found nothing plausible means the driver is far from
        // the last known position; fall back to a global scan so the UI can
        // still say how far off they are.
        if (best != null && lastIdx != null && best.distanceMeters > cfg.offRouteDistanceMeters * 4) {
            var globalBest: Projection? = null
            for (i in 0..routePoints.size - 2) {
                val proj = projectOnSegment(p, routePoints[i], routePoints[i + 1])
                if (globalBest == null || proj.distanceMeters < globalBest.distanceMeters) {
                    globalBest = Projection(
                        point = proj.point,
                        distanceMeters = proj.distanceMeters,
                        alongMeters = cumulative[i] + proj.alongFraction *
                            (cumulative[i + 1] - cumulative[i]),
                        segmentIndex = i
                    )
                }
            }
            if (globalBest != null && globalBest.distanceMeters < best.distanceMeters) {
                return globalBest
            }
        }
        return best
    }

    /**
     * Guards against the marker sliding backwards along the route when the
     * driver is stationary at a junction, or when a fix is pulled onto a
     * parallel carriageway.
     */
    private fun isForwardProgress(p: Projection): Boolean {
        val last = lastProjection ?: return true
        val backwards = p.alongMeters < last.alongMeters - BACKWARD_TOLERANCE_METERS
        if (!backwards) return true
        // Real U-turns and reversing into a parking space do happen; allow them
        // once the driver is clearly off the previous point.
        return p.distanceMeters > cfg.offRouteDistanceMeters
    }

    private fun filterBearing(previous: Float?, candidate: Float?): Float? = when {
        candidate == null -> previous
        previous == null -> candidate
        smoothedSpeed < cfg.minBearingSpeedMps -> previous
        else -> {
            val delta = normalizeAngleDeg(candidate - previous)
            // Rate-limit: a turn is reported over several samples rather than
            // snapping the map 90° at once.
            val clamped = delta.coerceIn(-cfg.maxBearingChangeDeg, cfg.maxBearingChangeDeg)
            ((previous + clamped) % 360f + 360f) % 360f
        }
    }

    /** True when enough consecutive fixes have been off the route to reroute. */
    fun shouldReroute(): Boolean = offRouteStreak >= cfg.offRouteConfirmSamples

    fun offRouteStreak(): Int = offRouteStreak

    fun routeLengthMeters(): Float = routeLengthMeters

    /** Fraction of the active route already travelled, 0..1. */
    fun routeProgress(): Float {
        if (routeLengthMeters <= 0f) return 0f
        val along = lastProjection?.alongMeters ?: return 0f
        return (along / routeLengthMeters).coerceIn(0f, 1f)
    }

    companion object {
        private const val BACKWARD_TOLERANCE_METERS = 15f

        /** Great-circle distance in metres. */
        fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val lat1 = Math.toRadians(a.lat)
            val lat2 = Math.toRadians(b.lat)
            val h = sin(dLat / 2) * sin(dLat / 2) +
                sin(dLon / 2) * sin(dLon / 2) * cos(lat1) * cos(lat2)
            return 2 * r * kotlin.math.asin(sqrt(h.coerceIn(0.0, 1.0)))
        }

        /** Initial bearing from [a] to [b], degrees clockwise from north. */
        fun bearingBetween(a: GeoPoint, b: GeoPoint): Float {
            val lat1 = Math.toRadians(a.lat)
            val lat2 = Math.toRadians(b.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val y = sin(dLon) * cos(lat2)
            val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
            return (((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0)).toFloat()
        }

        /** Wraps an angle difference into [-180, 180]. */
        fun normalizeAngleDeg(deg: Float): Float {
            var d = deg % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            return d
        }

        /** Result of projecting a point onto a single polyline segment. */
        class SegmentProjection(
            val point: GeoPoint,
            val distanceMeters: Float,
            /** Position along the segment, 0 at [a] and 1 at [b]. */
            val alongFraction: Float
        )

        /**
         * Projects [p] onto segment a-b in a local metric plane.
         *
         * Over a few hundred metres an equirectangular projection is accurate
         * to centimetres, which is far below GPS noise — and it avoids the
         * vector maths blowing up at the poles the way a naive 3D approach can.
         */
        fun projectOnSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): SegmentProjection {
            val latRad = Math.toRadians(p.lat)
            val kx = 111_320.0 * cos(latRad)
            val ky = 110_540.0

            val ax = a.lon * kx; val ay = a.lat * ky
            val bx = b.lon * kx; val by = b.lat * ky
            val px = p.lon * kx; val py = p.lat * ky

            val dx = bx - ax
            val dy = by - ay
            val lenSq = dx * dx + dy * dy

            val t = if (lenSq <= 1e-9) 0.0
            else (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)

            val cx = ax + t * dx
            val cy = ay + t * dy
            val projected = GeoPoint(cy / ky, cx / kx)

            return SegmentProjection(
                point = projected,
                distanceMeters = hypot(px - cx, py - cy).toFloat(),
                alongFraction = t.toFloat()
            )
        }

        private fun hypot(a: Double, b: Double): Double = sqrt(a * a + b * b)
    }
}

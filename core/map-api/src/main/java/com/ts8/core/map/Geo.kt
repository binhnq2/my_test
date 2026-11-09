package com.ts8.core.map

/**
 * Coordinate in WGS-84 degrees.
 *
 * The whole app uses WGS-84; the map engine is responsible for any projection.
 */
data class GeoPoint(val lat: Double, val lon: Double) {
    fun isValid(): Boolean =
        lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0 &&
            !lat.isNaN() && !lon.isNaN()

    /**
     * Great-circle distance to [other], in metres (haversine).
     *
     * Lives here rather than in each engine because the UI now needs it too —
     * the search screen prints "cách 6,2 km" and the saved-places list prints
     * the same number for a record that no engine touched. The engines keep
     * their own private copies, which is deliberate: they are on hot paths and
     * call it inside tight loops, where a virtual call per candidate is a
     * measurable cost on this CPU.
     *
     * A sphere, not an ellipsoid: at the kilometre scale of a head unit's
     * display the WGS-84 flattening changes the answer by under 0.3 %, which is
     * invisible next to the GPS's own 5–20 m error.
     */
    fun distanceTo(other: GeoPoint): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(other.lat - lat)
        val dLon = Math.toRadians(other.lon - lon)
        val lat1 = Math.toRadians(lat)
        val lat2 = Math.toRadians(other.lat)
        val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.sin(dLon / 2) * Math.sin(dLon / 2) * Math.cos(lat1) * Math.cos(lat2)
        return 2 * r * Math.asin(Math.sqrt(h.coerceIn(0.0, 1.0)))
    }
}

/** Axis-aligned bounding box in WGS-84 degrees. */
data class GeoBounds(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double
) {
    val centerLat: Double get() = (south + north) / 2.0
    val centerLon: Double get() = (west + east) / 2.0

    fun contains(p: GeoPoint): Boolean =
        p.lon in west..east && p.lat in south..north

    fun containsWithMargin(p: GeoPoint, marginDeg: Double): Boolean =
        p.lon >= west - marginDeg && p.lon <= east + marginDeg &&
            p.lat >= south - marginDeg && p.lat <= north + marginDeg
}

/** Day / night map styling. Resolved by [MapStyleMode.AUTO] from time of day. */
enum class MapStyleMode { DAY, NIGHT, AUTO }

/**
 * How the camera follows the vehicle during navigation.
 */
enum class CameraFollowMode {
    /** Free panning; the camera does not move on location updates. */
    NONE,

    /** Camera centres the vehicle, north stays up. */
    FOLLOW,

    /** Camera centres the vehicle and rotates to heading. */
    FOLLOW_BEARING,

    /** Camera centres the vehicle, rotates to heading, tilts for 3D. */
    FOLLOW_BEARING_TILT
}

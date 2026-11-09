package com.ts8.core.traffic

import com.ts8.core.map.GeoPoint

/**
 * A traffic sign, speed-camera or road-condition POI loaded from the offline
 * spatial-grid assets (`assets/vnsigns/`, `assets/vnhazard/`, …).
 *
 * Each POI comes from a GZIP-compressed `{x}_{y}_pins.bin` cell file. The binary
 * format is a JSON array whose objects use single-letter keys to keep the cell
 * files small:
 *
 * ```
 * { "y": 16.0678, "g": 108.2215, "t": "r420", "v": 50, "b": -1, "u": 12845 }
 * ```
 *
 * The mapping is documented on each field below.
 *
 * **Why this lives in `core:location-engine` and not a new module.** The traffic
 * alert pipeline feeds speed limits into [com.ts8.core.speed.SpeedLimitResolver]
 * and proximity alerts through the same audio bridge the overspeed path uses.
 * Keeping it beside the speed package avoids a circular dependency: the speed
 * package would have to depend on a new traffic module to receive posted limits,
 * while the traffic module needs `GeoPoint` from `core:map-api` which is already
 * on this module's classpath.
 */
data class TrafficSign(
    /** Latitude. `y` in the pin JSON. */
    val lat: Double,
    /** Longitude. `g` in the pin JSON. */
    val lon: Double,
    /** Raw type code from the pin JSON (`t`). See [SignType] for the catalog. */
    val typeCode: String,
    /** Speed-limit value in km/h (`v`). `0` when the sign has no speed value. */
    val speedLimit: Int = 0,
    /**
     * Bearing of the sign's facing direction in degrees, 0–360 (`b`).
     * `-1` means "omnidirectional" — the sign applies to all directions.
     */
    val bearing: Float = -1f,
    /** Unique ID (`u`). `0` when the pin carries none (some categories omit it). */
    val uniqueId: Long = 0L
) {
    /** The parsed sign category, or [SignType.UNKNOWN] for codes not yet catalogued. */
    val signType: SignType
        get() = SignType.fromCode(typeCode)

    /** `true` when this sign carries a posted speed limit that should be respected. */
    val hasSpeedLimit: Boolean
        get() = speedLimit > 0 && signType == SignType.SPEED_LIMIT

    /** WGS-84 position as a [GeoPoint], for distance calculations. */
    val point: GeoPoint
        get() = GeoPoint(lat, lon)

    /**
     * Checks whether the sign is relevant for the vehicle's current heading.
     *
     * Omnidirectional signs (`bearing < 0`) always match. Directional signs
     * match when the vehicle's heading is within [toleranceDeg] of the sign's
     * bearing — a sign facing oncoming traffic should not fire for a car
     * driving the other way.
     */
    fun isRelevantForHeading(heading: Float, toleranceDeg: Float = 45f): Boolean {
        if (bearing < 0f) return true
        val diff = Math.abs(heading - bearing) % 360f
        val angle = if (diff > 180f) 360f - diff else diff
        return angle <= toleranceDeg
    }

    /** Human-readable Vietnamese label for UI display. */
    fun displayLabel(): String = signType.displayLabel(speedLimit)
}

/**
 * Catalogue of traffic sign types found in the spatial-grid pin data.
 *
 * The codes come from the `xiaozhi` APK's data format, which in turn derives
 * from Vietnamese road sign regulations (QCVN 41:2019/BGTVT):
 *
 * - `r420` / `r421` — begin / end dense residential area
 * - `p125` / `p130` / `p131a` — prohibition signs (no entry, no parking, etc.)
 * - `limit` — posted speed limit sign (carries a `v` value)
 * - `cam` / `cam_red` — enforcement cameras (not present in all packs but
 *   supported so the pipeline is ready when a pack carries them)
 */
enum class SignType(
    /** The raw code from the pin JSON's `t` field. */
    val code: String,
    /** Whether this sign type carries a speed-limit value in `v`. */
    val carriesSpeed: Boolean
) {
    /** Posted maximum speed limit. `v` is the limit in km/h. */
    SPEED_LIMIT("limit", true),

    /** Begin dense residential area (R.420). Implies 60 km/h unless posted otherwise. */
    RESIDENTIAL_BEGIN("r420", false),

    /** End dense residential area (R.421). */
    RESIDENTIAL_END("r421", false),

    /** No entry (P.125). */
    NO_ENTRY("p125", false),

    /** No stopping/parking (P.130). */
    NO_STOPPING("p130", false),

    /** No overtaking (P.131a). */
    NO_OVERTAKING("p131a", false),

    /** Speed enforcement camera. `v` may carry the enforced limit. */
    CAMERA_SPEED("cam", false),

    /** Red-light enforcement camera. */
    CAMERA_RED_LIGHT("cam_red", false),

    /** Code not yet in the catalogue; treated as a generic informational sign. */
    UNKNOWN("", false);

    companion object {
        /** Resolves a raw pin code to a [SignType], or [UNKNOWN] for unmapped codes. */
        fun fromCode(code: String): SignType =
            entries.firstOrNull { it.code == code } ?: UNKNOWN

        /**
         * The implied speed limit for residential-area signs.
         *
         * `r420` (begin residential) implies 60 km/h under Vietnamese traffic law
         * unless a lower `limit` sign is posted. This is used as a fallback only
         * when no explicit `limit` sign is nearby; an explicit sign always wins.
         */
        const val RESIDENTIAL_IMPLIED_LIMIT = 60
    }

    /** Vietnamese display label, parameterised by speed when the sign carries one. */
    fun displayLabel(speedKmh: Int): String = when (this) {
        // `v` is optional in the pack: a `limit` pin without one would otherwise
        // be read out as "giới hạn tốc độ 0 km/h", which is a fabricated number
        // on a safety-critical prompt. Fall back to naming the sign instead.
        SPEED_LIMIT ->
            if (speedKmh > 0) "Giới hạn tốc độ ${speedKmh} km/h"
            else "Biển giới hạn tốc độ"
        RESIDENTIAL_BEGIN -> "Bắt đầu khu đông dân cư"
        RESIDENTIAL_END -> "Hết khu đông dân cư"
        NO_ENTRY -> "Cấm vào"
        NO_STOPPING -> "Cấm dừng đỗ"
        NO_OVERTAKING -> "Cấm vượt"
        CAMERA_SPEED -> if (speedKmh > 0) "Camera đo tốc độ ${speedKmh} km/h" else "Camera phạt nguội"
        CAMERA_RED_LIGHT -> "Camera vượt đèn đỏ"
        UNKNOWN -> "Biển báo giao thông"
    }
}

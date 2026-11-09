package com.ts8.core.map

/**
 * A traffic-sign marker to display on the map overlay.
 *
 * This is the map-API representation of a traffic sign, kept free of any
 * rendering-engine types. The engine implementation is responsible for
 * mapping [typeCode] and [speedLimit] to the appropriate icon image.
 *
 * **Why not pass `TrafficSign` directly.** `TrafficSign` lives in
 * `core:location-engine`, which depends on `core:map-api`. If `MapController`
 * took a `TrafficSign`, the map API would gain a dependency on the location
 * engine — exactly the kind of upward dependency that breaks modularisation.
 * This value object is the minimal projection the renderer needs.
 *
 * @param point WGS-84 position.
 * @param typeCode The raw sign type code (`"limit"`, `"cam"`, `"r420"`, …).
 * @param speedLimit Speed-limit value in km/h, or `0` when not applicable.
 */
data class TrafficSignOverlay(
    val point: GeoPoint,
    val typeCode: String,
    val speedLimit: Int = 0
)

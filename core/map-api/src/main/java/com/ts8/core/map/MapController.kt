package com.ts8.core.map

/**
 * The map rendering contract.
 *
 * `core:map-api` deliberately contains no rendering code and no MapLibre
 * types, so `navigation-core` and the launcher UI can depend on map behaviour
 * without pulling in a native renderer, and so a second engine can be added
 * later without touching callers.
 */
interface MapController {
    /**
     * Attaches a renderer-owned view. Implementations return their own view
     * type; callers only use it as an opaque handle to add to a container.
     */
    fun attach()

    /** Releases every native resource. Safe to call more than once. */
    fun destroy()

    /** True once the style has loaded and the camera can be driven. */
    fun isReady(): Boolean

    /** Registers a callback invoked once the style is ready (or on failure). */
    fun setReadyListener(listener: ReadyListener?)

    fun setStyleMode(mode: MapStyleMode)

    fun getStyleMode(): MapStyleMode

    /**
     * Applies the `Hiển thị` settings to everything already drawn.
     *
     * **Why this is separate from [setStyleMode].** Day/night and the display
     * group are two different questions — one changes colours, the other
     * changes which layers exist and how big they are — but both end in the same
     * expensive operation, rebuilding the style. Keeping them apart lets an
     * implementation skip the rebuild when only the palette moved, and lets the
     * settings screen push a whole snapshot in one call instead of one setter
     * per checkbox.
     *
     * Implementations must be idempotent: the launcher re-applies the stored
     * snapshot on every resume, and a rebuild per resume would blank the map on
     * every tab switch.
     */
    fun applyDisplayConfig(config: MapDisplayConfig) = Unit

    /** Moves the camera with an optional animation. */
    fun moveCamera(target: CameraPosition, animate: Boolean = true)

    fun getCameraPosition(): CameraPosition

    fun setCameraFollow(mode: CameraFollowMode)

    /** Draws (or replaces) the active route polyline. */
    fun setRoute(route: RouteGeometry?)

    /**
     * How much of the drawn route has already been driven (§8: "Hiển thị route
     * đã đi và route còn lại").
     *
     * **Why this is separate from [setRoute].** The route line is redrawn on a
     * reroute, an overview toggle or a style rebuild; the split point moves on
     * every accepted fix. Passing it through [setRoute] would mean rebuilding
     * the whole polyline once a second, and a caller that had only the progress
     * fraction would have to fabricate a `RouteGeometry` to say so. This carries
     * one `Int` and lets the renderer move a boundary it already knows the
     * geometry of.
     *
     * [travelledIndex] is an index into the last [setRoute]'s points: points
     * before it have been driven, points from it on are still ahead. Implementations
     * clamp, and treat an absent route or an ignored setting as "draw no split".
     */
    fun setRouteProgress(travelledIndex: Int) = Unit

    /**
     * Draws the routes that were *not* chosen, in the muted alternative colour.
     *
     * Drawn under [setRoute] and thinner, so the chosen line stays the one the
     * eye lands on. An empty list clears them; that is what the caller sends
     * when the driver starts driving, because from that moment the alternatives
     * are no longer selectable and a second line is just clutter on a road the
     * car is already committed to.
     */
    fun setAlternativeRoutes(routes: List<RouteGeometry>) = Unit

    /** Draws the vehicle marker; `bearingDeg` rotates the arrow. */
    fun setVehicleMarker(point: GeoPoint?, bearingDeg: Float)

    /** Adds a labelled search / destination marker. */
    fun setDestinationMarker(point: GeoPoint?, label: String?)

    /** Clears all overlays without touching the base map. */
    fun clearOverlays()

    /**
     * Draws (or replaces) traffic-sign markers on the map.
     *
     * Each sign is rendered as a small icon — a speed-limit roundel, a camera,
     * or a residential-area disc — at its GPS position. The markers are kept
     * in a separate GeoJSON source from routes and the vehicle marker, so they
     * can be updated independently when new cells load.
     *
     * Pass an empty list to clear all sign markers.
     */
    fun setTrafficSigns(signs: List<TrafficSignOverlay>) = Unit

    /** In-memory screenshot of the current view, or null if unavailable. */
    fun snapshot(): android.graphics.Bitmap?

    interface ReadyListener {
        fun onReady()
        fun onFailed(reason: String)
    }
}

/** A camera position in map terms. `zoom` follows the style's zoom scale. */
data class CameraPosition(
    val target: GeoPoint,
    val zoom: Double,
    val bearing: Float = 0f,
    val tilt: Float = 0f
)

/**
 * A route as a list of polyline points, already simplified by the routing
 * engine for display.
 */
data class RouteGeometry(
    val points: List<GeoPoint>,
    /** Index into [points] where the destination lies, for trimming. */
    val destinationIndex: Int = points.lastIndex
) {
    /**
     * Converts "metres driven along the route" into the point index that
     * [MapController.setRouteProgress] expects (see §8).
     *
     * **Why this lives here and not in the renderer.** The navigator reports
     * progress as metres — the unit the snapping pipeline already speaks — while
     * the split has to be made at a vertex, because a GeoJSON line is drawn
     * between points. Doing the conversion once, against the same point list
     * the renderer was handed, guarantees the two agree; doing it inside the
     * renderer would make it re-measure a polyline it was only told to draw, and
     * a simplification change upstream would silently move the boundary.
     *
     * Cumulative great-circle distance, not a fraction of the point count:
     * route points are unevenly spaced — a long motorway straight is two
     * points, an inner-city junction is ten — so a fraction of the index would
     * put the car kilometres from where it actually is.
     *
     * Returns `0` for an empty or single-point route and for a distance at or
     * behind the start, and `points.lastIndex` once the route is covered.
     */
    fun indexAtDistance(meters: Int): Int {
        if (points.size < 2) return 0
        if (meters <= 0) return 0
        var travelled = 0.0
        for (i in 1..points.lastIndex) {
            travelled += points[i - 1].distanceTo(points[i])
            if (travelled > meters) return i - 1
        }
        return points.lastIndex
    }
}

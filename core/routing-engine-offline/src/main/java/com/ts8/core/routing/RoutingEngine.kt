package com.ts8.core.routing

import com.ts8.core.map.GeoPoint
import kotlin.math.abs

/** A computed route, in the shape the map and navigator consume. */
data class Route(
    val points: List<GeoPoint>,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val maneuvers: List<Maneuver>,
    /** Vertices in travel order, used by snapping and reroute. */
    val nodeIds: IntArray
)

/** A single turn instruction. */
data class Maneuver(
    val type: ManeuverType,
    /** Point at which the instruction fires. */
    val point: GeoPoint,
    /** Distance along the route (metres) where this maneuver happens. */
    val distanceFromStart: Int,
    /** Street to continue onto; null when unknown. */
    val streetName: String?,
    /** Spoken instruction in Vietnamese. */
    val instruction: String,
    val direction: ManeuverDirection = ManeuverDirection.NONE
)

enum class ManeuverType { DEPART, TURN, CONTINUE, ROUNDABOUT, ARRIVE }

enum class ManeuverDirection { NONE, LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, SHARP_RIGHT, STRAIGHT, UTURN }

/** Options that change the cost function. */
data class RouteOptions(
    val avoidTolls: Boolean = false,
    val avoidFerries: Boolean = false,
    val preferFastest: Boolean = true
)

/** Result wrapper so failures are explicit rather than an empty list. */
sealed class RouteResult {
    data class Success(val route: Route) : RouteResult()
    data class Failure(val reason: RouteError, val detail: String = "") : RouteResult()
}

enum class RouteError {
    PACK_NOT_READY,
    NO_START_NODE,
    NO_END_NODE,
    UNREACHABLE,
    SAME_POINT,
    INTERNAL
}

/** Offline routing contract. */
interface RoutingEngine {
    fun isReady(): Boolean

    /** Nearest routable point, for snapping a free-form destination. */
    fun snapToRoad(point: GeoPoint): GeoPoint?

    /**
     * Computes a route. Runs synchronously; callers wrap it in a background
     * executor (the navigator owns one).
     */
    fun route(from: GeoPoint, to: GeoPoint, options: RouteOptions = RouteOptions()): RouteResult

    /**
     * Up to [maxRoutes] genuinely different routes, **fastest first**.
     *
     * The first element is the same route [route] returns, so a caller that
     * ignores this method still gets the right answer. An engine that cannot
     * enumerate alternatives returns just that one rather than inventing
     * near-duplicates, which is why the default is here and not abstract.
     */
    fun routeAlternatives(
        from: GeoPoint,
        to: GeoPoint,
        options: RouteOptions = RouteOptions(),
        maxRoutes: Int = 3
    ): List<Route> {
        val result = route(from, to, options)
        return if (result is RouteResult.Success) listOf(result.route) else emptyList()
    }

    /**
     * Speed limit for the road nearest [point], in km/h, or null when the graph
     * has no answer there.
     *
     * **Why this is on the routing engine and not derived in the UI.** The graph
     * stores `speedKph` per directed edge (FORMAT_SPECS §3.3); finding the edge
     * under the car needs the spatial index the router already holds. A null is
     * a real answer — an off-network fix, a pack whose extract has no speed for
     * that road — and the caller must render `--` rather than substitute a
     * per-class default of its own, which the brief forbids.
     *
     * Defaults to null so an engine that cannot answer (a test fake, a future
     * online router) satisfies the interface without pretending.
     */
    fun limitAt(point: GeoPoint): Int? = null

    fun close()
}

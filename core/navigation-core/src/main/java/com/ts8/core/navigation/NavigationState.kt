package com.ts8.core.navigation

/**
 * Navigation states, exactly as the specification enumerates them.
 *
 * The transitions are the contract; see [NavigationController] for which
 * events are legal in each state. Anything not listed there is a bug in the
 * caller, and the controller rejects it rather than guessing.
 */
enum class NavigationState {
    /** No destination. The map follows the car but offers no guidance. */
    IDLE,

    /** The driver is typing / a lookup is running. Debounced by the caller. */
    SEARCHING,

    /** A destination is picked and shown on the map; no route yet. */
    DESTINATION_PREVIEW,

    /** A route computation is in flight. */
    ROUTE_CALCULATING,

    /** A route is drawn and offered; not yet started. */
    ROUTE_PREVIEW,

    /** Guidance is active. */
    NAVIGATING,

    /**
     * The driver left the route and a new one is being computed. Music
     * continues, guidance announcements are suspended until the new route is
     * ready.
     */
    REROUTING,

    /** Within the arrival radius of the destination. */
    ARRIVED
}

/** Why navigation ended, for the summary card. */
enum class NavigationEndReason {
    /** The driver pressed "end". */
    USER_ENDED,

    /** The destination was reached. */
    ARRIVED,

    /** The driver cancelled at the preview stage. */
    CANCELLED_PREVIEW,

    /** No route could be computed even after a retry. */
    ROUTE_FAILED,

    /** The map pack became unavailable mid-drive (USB removed). */
    PACK_LOST,

    /** The activity was destroyed and navigation was not resumed. */
    TERMINATED
}

/** Progress along the active route, recomputed on every accepted fix. */
data class NavigationProgress(
    /** Metres left to the destination along the route. */
    val remainingMeters: Int,
    /** Seconds left, from the route's own speed model. */
    val remainingSeconds: Int,
    /** Metres travelled since guidance started. */
    val travelledMeters: Int,
    /** Fraction complete, 0..1. */
    val fraction: Float,
    /** Est. arrival time as epoch milliseconds, or 0 when unknown. */
    val etaEpochMs: Long,
    /** The next instruction to show, or null when only the destination is left. */
    val nextManeuver: com.ts8.core.routing.Maneuver?,
    /** Metres until [nextManeuver] fires; 0 when it is imminent. */
    val distanceToNextManeuverMeters: Int,
    /** The instruction after the next one, for the "sau đó" line. */
    val followingManeuver: com.ts8.core.routing.Maneuver?,
    /** How far off the route line the car currently is. */
    val offRouteMeters: Int
)

/** A guidance announcement the TTS layer must speak. */
data class Announcement(
    val text: String,
    /** Distance to the maneuver at the time of speaking, for the log. */
    val atMeters: Int,
    /** True when this must interrupt music rather than wait for it. */
    val urgent: Boolean
)

/** Everything the navigation UI renders that is not the map itself. */
data class NavigationUiState(
    val state: NavigationState,
    val destinationName: String? = null,
    val destination: com.ts8.core.map.GeoPoint? = null,
    val route: com.ts8.core.routing.Route? = null,
    val progress: NavigationProgress? = null,
    /** Message to show when the state is an error state. */
    val message: String? = null,
    /** Muted by the driver. Does not stop the visual guidance. */
    val muted: Boolean = false,
    /** True while the map should show the whole route instead of following. */
    val overview: Boolean = false
)

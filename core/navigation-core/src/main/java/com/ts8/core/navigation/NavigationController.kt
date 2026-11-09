package com.ts8.core.navigation

import com.ts8.core.map.GeoPoint
import com.ts8.core.routing.Maneuver
import com.ts8.core.routing.ManeuverType
import com.ts8.core.routing.Route
import com.ts8.core.routing.RouteOptions
import com.ts8.core.routing.RouteResult
import com.ts8.core.routing.RoutingEngine
import com.ts8.core.location.LocationFix
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The navigation state machine.
 *
 * This class owns *only* the decision logic: which state follows which event,
 * when a reroute is warranted, what to announce and when. It has no Android
 * dependency and no threading assumptions beyond "callers may be on any
 * thread, callbacks come back on the executor you supplied", which is what
 * makes the whole of it testable from a JVM test.
 *
 * The state diagram:
 *
 * ```
 *   IDLE ──search──> SEARCHING ──pick──> DESTINATION_PREVIEW
 *                                            │  route
 *                                            v
 *                                    ROUTE_CALCULATING
 *                                            │ ok          │ fail
 *                                            v             v
 *                                     ROUTE_PREVIEW      IDLE
 *                                       │ start    │ cancel
 *                                       v          v
 *                                  NAVIGATING   DESTINATION_PREVIEW
 *                                    │      │
 *                       off-route ───┘      └─── arrival
 *                            v                       v
 *                       REROUTING                ARRIVED
 *                            │ new route             │
 *                            v                       v
 *                       NAVIGATING                 IDLE
 * ```
 *
 * Reroute policy, which the spec calls out explicitly:
 *
 * - A single GPS spike must never trigger a reroute. The location engine
 *   already rejects spikes, and this class additionally requires
 *   [Config.rerouteConfirmSamples] consecutive off-route fixes.
 * - A cooldown prevents a reroute loop: after a reroute, the engine must have
 *   actually moved before another can fire. Without this, a driver parked 30 m
 *   off the line spends the drive recomputing.
 * - Only one computation may be in flight; a second request replaces the
 *   pending one rather than queueing.
 */
class NavigationController(
    private val routing: RoutingEngine,
    private val config: Config = Config(),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val clock: () -> Long = System::currentTimeMillis
) {

    data class Config(
        /** Below this the car is treated as arrived. */
        val arrivalRadiusMeters: Int = 30,
        /** Distance at which arrival is announced, ahead of the radius. */
        val arrivalAnnounceMeters: Int = 300,
        /** Consecutive off-route fixes required before rerouting. */
        val rerouteConfirmSamples: Int = 3,
        /** A fix further than this from the line counts as off route. */
        val offRouteDistanceMeters: Float = 60f,
        /** Minimum time between reroutes. */
        val rerouteCooldownMs: Long = 20_000,
        /** Announce a maneuver at these distances, in metres, largest first. */
        val announceDistancesMeters: List<Int> = listOf(1000, 500, 300, 150, 50),
        /** Timeout for a route computation before it is abandoned. */
        val routeTimeoutMs: Long = 15_000,
        /** Retry once on UNREACHABLE — snap noise can cause it. */
        val retryOnUnreachable: Boolean = true
    )

    // -- state -------------------------------------------------------------
    @Volatile
    private var state: NavigationState = NavigationState.IDLE

    @Volatile
    private var destination: GeoPoint? = null

    @Volatile
    private var destinationName: String? = null

    @Volatile
    private var route: Route? = null

    /**
     * The candidate routes offered for the current destination, fastest first.
     *
     * The first element is always the route in [route]; the navigator never
     * invents a "best" flag of its own, because the ordering *is* the ranking —
     * A* minimises time, so element 0 is the fastest by construction.
     */
    @Volatile
    private var alternatives: List<Route> = emptyList()

    @Volatile
    private var progress: NavigationProgress? = null

    @Volatile
    private var muted = false

    @Volatile
    private var overview = false

    @Volatile
    private var message: String? = null

    // -- reroute bookkeeping ----------------------------------------------
    private var offRouteStreak = 0
    private var lastRerouteAtMs = 0L
    private var routeStartedAtMs = 0L

    // -- announcement bookkeeping -----------------------------------------
    /** Distances already announced for the current maneuver. */
    private val announcedForManeuver = HashSet<Int>()
    private var currentManeuverIndex = -1
    private var arrivalAnnounced = false

    // -- computation -------------------------------------------------------
    private var pendingComputation: Future<*>? = null
    private val computing = AtomicBoolean(false)

    // -- listeners ---------------------------------------------------------
    interface Listener {
        fun onStateChanged(uiState: NavigationUiState)
        fun onAnnounce(announcement: Announcement)
        /** The map should draw this route. Null clears it. */
        fun onRouteChanged(route: Route?)
        /** The camera should show the whole route. */
        fun onOverviewChanged(overview: Boolean)
        /** Navigation ended; the caller should tear down guidance UI. */
        fun onEnded(reason: NavigationEndReason)
    }

    private var listener: Listener? = null

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun state(): NavigationState = state

    fun route(): Route? = route

    /** Candidate routes for the current destination, fastest first. */
    fun alternatives(): List<Route> = alternatives

    /**
     * Swaps the active route for one of [alternatives].
     *
     * Progress bookkeeping is reset for the same reason a reroute resets it:
     * the manoeuvre list, the announced distances and the driven split all
     * index into the *previous* polyline. Reusing them against a different
     * geometry would announce turns that are not on the new road.
     *
     * Ignored while guiding: changing the line under a moving car is a reroute,
     * not a preview selection, and must go through the reroute path.
     */
    fun selectAlternative(index: Int) {
        if (state == NavigationState.NAVIGATING || state == NavigationState.REROUTING) return
        val chosen = alternatives.getOrNull(index) ?: return
        if (chosen === route) return
        route = chosen
        announcedForManeuver.clear()
        currentManeuverIndex = -1
        arrivalAnnounced = false
        offRouteStreak = 0
        progress = null
        listener?.onRouteChanged(chosen)
        listener?.onStateChanged(uiState())
    }

    fun destination(): GeoPoint? = destination

    fun uiState(): NavigationUiState = NavigationUiState(
        state = state,
        destinationName = destinationName,
        destination = destination,
        route = route,
        progress = progress,
        message = message,
        muted = muted,
        overview = overview
    )

    // -- public transitions -------------------------------------------------

    /** Enters search mode. Illegal from NAVIGATING; end navigation first. */
    fun beginSearch() {
        if (state == NavigationState.NAVIGATING || state == NavigationState.REROUTING) return
        setState(NavigationState.SEARCHING, "beginSearch")
    }

    /**
     * The driver picked a destination. Starts route computation immediately:
     * on a head unit the "preview then confirm" step is a single screen, so
     * showing a pin and computing in parallel is what the driver expects.
     */
    fun selectDestination(point: GeoPoint, name: String?, from: GeoPoint?) {
        if (from == null) {
            fail("Chưa có vị trí hiện tại. Đang chờ tín hiệu GPS.")
            return
        }
        destination = point
        destinationName = name
        route = null
        alternatives = emptyList()
        progress = null
        message = null
        offRouteStreak = 0
        arrivalAnnounced = false
        announcedForManeuver.clear()
        currentManeuverIndex = -1

        setState(NavigationState.DESTINATION_PREVIEW, "selectDestination")
        listener?.onRouteChanged(null)
        calculateRoute(from, point, isReroute = false)
    }

    /** Recomputes from the given position. Used by the "Lộ trình" button. */
    fun recalculate(from: GeoPoint) {
        val dest = destination ?: return
        calculateRoute(from, dest, isReroute = false)
    }

    /** Starts guidance from the previewed route. */
    fun startNavigation() {
        if (state != NavigationState.ROUTE_PREVIEW) return
        routeStartedAtMs = clock()
        lastRerouteAtMs = 0L
        offRouteStreak = 0
        arrivalAnnounced = false
        announcedForManeuver.clear()
        currentManeuverIndex = -1
        setState(NavigationState.NAVIGATING, "startNavigation")
    }

    /** Overrides the routing engine's estimate with a position. */
    fun showOverview(enabled: Boolean) {
        if (overview == enabled) return
        overview = enabled
        listener?.onOverviewChanged(enabled)
        notifyState()
    }

    fun setMuted(value: Boolean) {
        if (muted == value) return
        muted = value
        // Re-announcing after unmute is desirable: the driver muted to take a
        // call and now wants the next instruction again.
        if (!muted) announcedForManeuver.clear()
        notifyState()
    }

    fun isMuted(): Boolean = muted

    /** Ends navigation for a caller-visible reason. */
    fun end(reason: NavigationEndReason) {
        pendingComputation?.cancel(true)
        pendingComputation = null
        computing.set(false)
        route = null
        alternatives = emptyList()
        destination = null
        destinationName = null
        progress = null
        offRouteStreak = 0
        arrivalAnnounced = false
        announcedForManeuver.clear()
        currentManeuverIndex = -1
        message = null
        overview = false
        setState(NavigationState.IDLE, "end($reason)")
        listener?.onRouteChanged(null)
        listener?.onEnded(reason)
    }

    /** The pack disappeared mid-drive (USB stick pulled). */
    fun onPackLost() {
        if (state == NavigationState.IDLE) return
        message = "Dữ liệu bản đồ không còn khả dụng. Kiểm tra thẻ nhớ hoặc USB."
        end(NavigationEndReason.PACK_LOST)
    }

    // -- the driving input --------------------------------------------------

    /**
     * Feeds an accepted location fix through guidance.
     *
     * This is the only place reroute and arrival are decided, so the policy is
     * readable in one screen rather than spread across callbacks.
     */
    fun onLocation(fix: LocationFix) {
        val activeRoute = route
        if (state != NavigationState.NAVIGATING || activeRoute == null) {
            // Still worth reporting progress in preview so the panel shows a
            // live distance while the driver waits at a light.
            val previewRoute = route
            if (state == NavigationState.ROUTE_PREVIEW && previewRoute != null) {
                updateProgressFromFix(fix, previewRoute)
            }
            return
        }

        val along = fix.distanceAlongRouteMeters
        if (along == null) {
            // No projection means the route geometry no longer matches; treat
            // as off route but require the usual confirmations.
            offRouteStreak++
            if (shouldRerouteNow()) reroute(fix.point)
            return
        }

        val updated = computeProgress(activeRoute, along.toInt(), fix)
        progress = updated

        // --- arrival ------------------------------------------------------
        if (updated.remainingMeters <= config.arrivalRadiusMeters) {
            arrive()
            return
        }
        if (!arrivalAnnounced && updated.remainingMeters <= config.arrivalAnnounceMeters) {
            arrivalAnnounced = true
            announce(
                Announcement(
                    text = buildArrivalInstruction(updated.remainingMeters),
                    atMeters = updated.remainingMeters,
                    urgent = false
                )
            )
        }

        // --- off-route detection -----------------------------------------
        // Only a measured lateral distance counts. "not snapped" also happens
        // when the engine refuses to snap backwards along the route, which is
        // the driver sitting still at a junction, not a departure.
        val offRouteMeters = fix.distanceFromRouteMeters
        if (offRouteMeters != null && offRouteMeters > config.offRouteDistanceMeters) {
            offRouteStreak++
        } else {
            offRouteStreak = 0
        }

        emitNextManeuver(updated)

        if (shouldRerouteNow()) {
            reroute(fix.point)
        } else {
            notifyState()
        }
    }

    private fun shouldRerouteNow(): Boolean {
        if (offRouteStreak < config.rerouteConfirmSamples) return false
        // Cooldown measured from the previous reroute, not from route start,
        // so an immediate second departure is still possible.
        if (lastRerouteAtMs != 0L && clock() - lastRerouteAtMs < config.rerouteCooldownMs) {
            return false
        }
        return true
    }

    private fun reroute(from: GeoPoint) {
        val dest = destination ?: return
        offRouteStreak = 0
        lastRerouteAtMs = clock()
        setState(NavigationState.REROUTING, "reroute")
        calculateRoute(from, dest, isReroute = true)
    }

    private fun arrive() {
        val dest = destination
        announce(Announcement("Đã đến nơi.", 0, urgent = false))
        setState(NavigationState.ARRIVED, "arrive")
        // Arrival is terminal for guidance; the UI shows the arrival card and
        // the driver dismisses it, which calls end().
        destination = dest
        progress = progress?.copy(
            remainingMeters = 0,
            remainingSeconds = 0,
            fraction = 1f
        )
        notifyState()
    }

    // -- route computation --------------------------------------------------

    private fun calculateRoute(from: GeoPoint, to: GeoPoint, isReroute: Boolean) {
        pendingComputation?.cancel(true)
        computing.set(true)
        if (!isReroute) setState(NavigationState.ROUTE_CALCULATING, "calculateRoute")

        pendingComputation = try {
            executor.submit {
                // Alternatives are only meaningful for a fresh destination: a
                // reroute happens under a moving car, where offering three
                // options would be a question the driver cannot answer.
                val candidates = if (isReroute) {
                    emptyList()
                } else {
                    runCatching {
                        routing.routeAlternatives(from, to, RouteOptions(), MAX_ROUTE_CHOICES)
                    }.getOrDefault(emptyList())
                }

                val result = if (candidates.isNotEmpty()) {
                    RouteResult.Success(candidates.first())
                } else {
                    runCatching { routing.route(from, to) }
                        .getOrElse {
                            RouteResult.Failure(
                                com.ts8.core.routing.RouteError.INTERNAL, it.message ?: ""
                            )
                        }
                }

                var outcome = result
                // A failed first attempt on UNREACHABLE is usually a snap that
                // landed on a dead-end vertex; snapping the endpoints and
                // retrying costs one query and avoids a dead end for the driver.
                if (outcome is RouteResult.Failure &&
                    outcome.reason == com.ts8.core.routing.RouteError.UNREACHABLE &&
                    config.retryOnUnreachable
                ) {
                    val snappedFrom = routing.snapToRoad(from)
                    val snappedTo = routing.snapToRoad(to)
                    if (snappedFrom != null && snappedTo != null) {
                        outcome = runCatching { routing.route(snappedFrom, snappedTo) }
                            .getOrElse { outcome }
                    }
                }

                computing.set(false)
                onRouteComputed(outcome, isReroute, candidates)
            }
        } catch (e: Exception) {
            computing.set(false)
            onRouteComputed(
                RouteResult.Failure(com.ts8.core.routing.RouteError.INTERNAL, e.message ?: ""),
                isReroute,
                emptyList()
            )
            null
        }
    }

    private fun onRouteComputed(
        result: RouteResult,
        isReroute: Boolean,
        candidates: List<Route> = emptyList()
    ) {
        when (result) {
            is RouteResult.Success -> {
                route = result.route
                alternatives = if (candidates.isEmpty()) listOf(result.route) else candidates
                message = null
                announcedForManeuver.clear()
                currentManeuverIndex = -1
                arrivalAnnounced = false
                offRouteStreak = 0
                listener?.onRouteChanged(result.route)

                if (isReroute) {
                    // Resume guidance directly: the driver is mid-drive and
                    // must not be asked to confirm.
                    setState(NavigationState.NAVIGATING, "rerouteComplete")
                } else {
                    setState(NavigationState.ROUTE_PREVIEW, "routeReady")
                }
            }
            is RouteResult.Failure -> {
                if (isReroute) {
                    // Keep the old route: a failed reroute must not strand the
                    // driver with no line on the map.
                    message = "Không thể tính lại lộ trình. Đang tiếp tục dùng lộ trình cũ."
                    setState(NavigationState.NAVIGATING, "rerouteFailed")
                } else {
                    val text = describeError(result)
                    message = text
                    setState(NavigationState.DESTINATION_PREVIEW, "routeFailed")
                }
            }
        }
    }

    private fun describeError(failure: RouteResult.Failure): String = when (failure.reason) {
        com.ts8.core.routing.RouteError.NO_START_NODE ->
            "Không tìm thấy đường gần vị trí hiện tại."
        com.ts8.core.routing.RouteError.NO_END_NODE ->
            "Không tìm thấy đường gần điểm đến."
        com.ts8.core.routing.RouteError.UNREACHABLE ->
            "Không có lộ trình lái xe tới điểm này."
        com.ts8.core.routing.RouteError.SAME_POINT ->
            "Điểm đến trùng với vị trí hiện tại."
        com.ts8.core.routing.RouteError.PACK_NOT_READY ->
            "Dữ liệu bản đồ chưa sẵn sàng."
        com.ts8.core.routing.RouteError.INTERNAL ->
            "Lỗi tính lộ trình: ${failure.detail}"
    }

    // -- progress ----------------------------------------------------------

    private fun updateProgressFromFix(fix: LocationFix, activeRoute: Route) {
        val along = fix.distanceAlongRouteMeters ?: return
        progress = computeProgress(activeRoute, along.toInt(), fix)
        notifyState()
    }

    private fun computeProgress(
        activeRoute: Route,
        alongMeters: Int,
        fix: LocationFix
    ): NavigationProgress {
        val total = activeRoute.distanceMeters.coerceAtLeast(1)
        val remaining = (total - alongMeters).coerceIn(0, total)
        val fraction = (alongMeters.toFloat() / total).coerceIn(0f, 1f)

        // Remaining time scales the route's own duration by the fraction left.
        // Using the route's speed model rather than the current speed keeps the
        // ETA from swinging every time the car slows for a light.
        val remainingSeconds = (activeRoute.durationSeconds * (1f - fraction))
            .toInt().coerceAtLeast(0)

        val nextIdx = nextManeuverIndex(activeRoute, alongMeters)
        val next = activeRoute.maneuvers.getOrNull(nextIdx)
        val following = activeRoute.maneuvers.getOrNull(nextIdx + 1)
        val distanceToNext = when {
            next == null -> remaining
            else -> (next.distanceFromStart - alongMeters).coerceAtLeast(0)
        }

        return NavigationProgress(
            remainingMeters = remaining,
            remainingSeconds = remainingSeconds,
            travelledMeters = alongMeters,
            fraction = fraction,
            etaEpochMs = if (remainingSeconds > 0) clock() + remainingSeconds * 1000L else 0L,
            nextManeuver = next,
            distanceToNextManeuverMeters = distanceToNext,
            followingManeuver = following,
            offRouteMeters = fix.distanceFromRouteMeters?.toInt() ?: 0
        )
    }

    /**
     * The maneuver whose `distanceFromStart` is the first one ahead.
     *
     * When none is ahead, the ARRIVE maneuver is returned, so the panel shows
     * "đến nơi" rather than blanking out on the final straight.
     */
    private fun nextManeuverIndex(activeRoute: Route, alongMeters: Int): Int {
        val ms = activeRoute.maneuvers
        for (i in ms.indices) {
            if (ms[i].distanceFromStart > alongMeters) {
                // Skip DEPART: it describes where we already are.
                if (ms[i].type == ManeuverType.DEPART) continue
                return i
            }
        }
        return if (ms.isNotEmpty()) ms.size - 1 else -1
    }

    private fun emitNextManeuver(p: NavigationProgress) {
        val m = p.nextManeuver ?: run {
            notifyState()
            return
        }
        // Arrival is announced by the arrival policy, not here. Announcing the
        // ARRIVE maneuver as well would speak "300 m, đã đến nơi" while the
        // car is still 300 m away, right after "300 m nữa là đến nơi".
        if (m.type == ManeuverType.ARRIVE) {
            notifyState()
            return
        }
        val idx = route?.maneuvers?.indexOf(m) ?: -1
        if (idx != currentManeuverIndex) {
            currentManeuverIndex = idx
            announcedForManeuver.clear()
        }
        if (muted) {
            notifyState()
            return
        }

        // Announce at the largest threshold the car has just crossed.
        val dist = p.distanceToNextManeuverMeters
        val threshold = config.announceDistancesMeters
            .filter { it >= dist }
            .minOrNull()
        if (threshold != null && announcedForManeuver.add(threshold)) {
            announce(
                Announcement(
                    text = speakInstruction(m, dist),
                    atMeters = dist,
                    urgent = dist <= 50
                )
            )
        }
        notifyState()
    }

    private fun speakInstruction(m: Maneuver, distanceMeters: Int): String {
        val prefix = formatDistance(distanceMeters)
        return if (prefix.isEmpty()) m.instruction else "$prefix, ${m.instruction}"
    }

    private fun buildArrivalInstruction(remaining: Int): String {
        val dist = formatDistance(remaining)
        return if (dist.isEmpty()) "Sắp đến nơi" else "$dist nữa là đến nơi"
    }

    private fun announce(a: Announcement) {
        listener?.onAnnounce(a)
    }

    // -- plumbing ----------------------------------------------------------

    private fun setState(next: NavigationState, because: String) {
        if (state == next) {
            notifyState()
            return
        }
        state = next
        notifyState()
    }

    private fun fail(text: String) {
        message = text
        notifyState()
    }

    private fun notifyState() {
        listener?.onStateChanged(uiState())
    }

    /** Cancels any in-flight computation. Call from `onDestroy`. */
    fun shutdown() {
        pendingComputation?.cancel(true)
        pendingComputation = null
        listener = null
        executor.shutdownNow()
    }

    /** Mute override for the voice seam; see [Announcement.urgent]. */
    fun shouldSpeak(a: Announcement): Boolean = !muted && a.text.isNotBlank()

    companion object {
        /**
         * How many routes are offered for a new destination.
         *
         * Three is the number a driver can compare at a glance on a 1024×600
         * panel: the fastest, plus at most two real alternatives. The panel
         * shows no list at all when the graph has only one corridor.
         */
        private const val MAX_ROUTE_CHOICES = 3

        /** "300 m", "1,2 km", "15 km", or "" when the distance is tiny. */
        fun formatDistance(meters: Int): String = when {
            meters < 0 -> ""
            meters < 20 -> ""
            meters < 1000 -> "${roundTo(meters, 10)} m"
            else -> {
                val km = meters / 1000.0
                // Vietnamese uses a comma as the decimal separator. Whole
                // kilometres drop the ",0" — "15 km" reads, "15,0 km" does not.
                val text = String.format(java.util.Locale.US, "%.1f", km).replace('.', ',')
                (if (text.endsWith(",0")) text.dropLast(2) else text) + " km"
            }
        }

        private fun roundTo(value: Int, step: Int): Int =
            ((value + step / 2) / step) * step
    }
}

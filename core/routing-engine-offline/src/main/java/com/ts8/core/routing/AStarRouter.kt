package com.ts8.core.routing

import com.ts8.core.map.GeoPoint
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Offline A* router over [RoutingGraph].
 *
 * Cost is time, not distance: the objective a driver cares about is "arrive
 * soonest", and the graph's per-edge speed limits are what distinguish a
 * motorway from a service lane.
 *
 * The heuristic is haversine distance divided by the graph's maximum speed,
 * which is admissible (it can never overestimate the cheapest remaining cost)
 * so A* returns the optimal route rather than a greedy approximation.
 *
 * Working arrays are allocated per query (four flat arrays of nodeCount) and
 * freed with it; nothing is cached between routes because a reroute runs on a
 * background thread and a shared mutable scratch pad would need locking that
 * costs more than the allocation on this graph size.
 */
class AStarRouter private constructor(
    private val graph: RoutingGraph,
    private val index: GraphIndex
) : RoutingEngine {

    private val nodeCount = graph.nodeCount
    private val edgeCount = graph.edgeCount

    /** Snapping and route endpoints must land on a car-accessible vertex. */
    private val routable = graph.routableNode

    override fun isReady(): Boolean = true

    private fun nearest(lat: Double, lon: Double): Int =
        index.nearestNodeExact(lat, lon, { graph.nodePoint(it) }, { routable[it] })

    override fun snapToRoad(point: GeoPoint): GeoPoint? {
        // A junction is a poor answer to "where on the road is this fix":
        // on a 700 m bridge the nearest *node* can be 300 m away, which is
        // exactly the straight "crow-fly" tail drivers were seeing between
        // the blue line and the destination pin. Project onto the edge's own
        // polyline instead; only fall back to a vertex off the network.
        val snap = snapToEdge(point, wide = true)
        if (snap != null) return snap.point
        val nodeId = nearest(point.lat, point.lon)
        if (nodeId < 0) return null
        return graph.nodePoint(nodeId)
    }

    /**
     * Projection of [point] onto one segment of a directed edge.
     *
     * The graph stores each edge as `[source] + interior geometry + [target]`.
     * Snapping to a *point on that chain* — rather than to whichever vertex is
     * nearest — is what makes a route begin at the car and finish at the pin
     * while still following the road's bends.
     */
    private class Projection(val distance: Double, val t: Double, val point: GeoPoint)

    private class EdgeSnap(
        val edgeIndex: Int,
        val fromNode: Int,
        val chain: List<GeoPoint>,
        val segmentIndex: Int,
        val t: Double,
        val point: GeoPoint,
        val distance: Double
    ) {
        /** Chain from the projected point to this edge's target node. */
        fun tailFromProjection(): List<GeoPoint> {
            val out = ArrayList<GeoPoint>(chain.size - segmentIndex + 1)
            out.add(point)
            for (i in segmentIndex + 1 until chain.size) out.add(chain[i])
            return out
        }

        /** Chain from this edge's source node to the projected point. */
        fun headToProjection(): List<GeoPoint> {
            val out = ArrayList<GeoPoint>(segmentIndex + 2)
            for (i in 0..segmentIndex) out.add(chain[i])
            out.add(point)
            return out
        }
    }

    /**
     * Finds the closest point on any car edge near [point].
     *
     * **Two passes, because one is not enough.** The cheap pass looks at the
     * edges leaving the nearest routable vertex and its immediate neighbours —
     * a handful of edges, right for a live GPS fix that is already on a road.
     *
     * The cheap pass alone is wrong for a route *endpoint*, and that was a
     * visible defect: in the middle of the 600 m Cầu Rồng the nearest junction
     * is 270 m away at the west abutment, and the bridge's eastbound carriageway
     * is an **incoming** edge of that junction, so nothing in the candidate set
     * passes near the pin. The router then ended the route at the abutment and
     * left a 270 m straight tail to the pin — exactly the "crow-fly" line the
     * driver reported.
     *
     * [wide] therefore adds a spatial pass over *every* node within
     * [WIDE_SNAP_RADIUS_M]. Route computation passes `true`; the 1 Hz
     * speed-limit lookup passes `false` and keeps the cheap path.
     */
    private fun snapToEdge(point: GeoPoint, wide: Boolean = false): EdgeSnap? {
        val anchor = nearest(point.lat, point.lon)
        if (anchor < 0) return null
        val candidates = HashSet<Int>()
        candidates.add(anchor)
        val anchorNode = graph.node(anchor)
        for (k in 0 until anchorNode.edgeCount) {
            val ei = anchorNode.firstEdge + k
            if (ei in 0 until edgeCount) {
                val target = graph.edge(ei).target
                if (target in 0 until nodeCount) candidates.add(target)
            }
        }
        val cheap = scanEdges(point, candidates)
        if (cheap != null || !wide) return cheap
        val around = index.nodesWithin(
            point.lat, point.lon, WIDE_SNAP_RADIUS_M
        ) { graph.nodePoint(it) }
        return scanEdges(point, around.asSequence().toHashSet())
    }

    /** Best projection of [point] onto the car edges leaving [nodes]. */
    private fun scanEdges(point: GeoPoint, nodes: Collection<Int>): EdgeSnap? {
        var best: EdgeSnap? = null
        var bestDistance = MAX_SNAP_DISTANCE_M
        for (nodeId in nodes) {
            val node = graph.node(nodeId)
            for (k in 0 until node.edgeCount) {
                val edgeIndex = node.firstEdge + k
                if (edgeIndex !in 0 until edgeCount) continue
                val edge = graph.edge(edgeIndex)
                if (edge.carForbidden || edge.ferry) continue
                val geometry = graph.edgeGeometry(edge)
                val ordered = if (edge.geometryReversed) geometry.asReversed() else geometry
                val chain = ArrayList<GeoPoint>(ordered.size + 2)
                chain.add(graph.nodePoint(nodeId))
                chain.addAll(ordered)
                chain.add(graph.nodePoint(edge.target))
                for (i in 0 until chain.size - 1) {
                    val projection = projectOnSegment(point, chain[i], chain[i + 1])
                    if (projection.distance < bestDistance) {
                        bestDistance = projection.distance
                        best = EdgeSnap(
                            edgeIndex, nodeId, chain, i,
                            projection.t, projection.point, projection.distance
                        )
                    }
                }
            }
        }
        return best
    }

    /** Nearest point on segment [a]..[b], in metres and in segment fraction. */
    private fun projectOnSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): Projection {
        val latScale = 110_540.0
        val lonScale = 111_320.0 * kotlin.math.cos(Math.toRadians(p.lat))
        val ax = (a.lon - p.lon) * lonScale
        val ay = (a.lat - p.lat) * latScale
        val bx = (b.lon - p.lon) * lonScale
        val by = (b.lat - p.lat) * latScale
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared <= 1e-9) 0.0
        else (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        val point = GeoPoint(p.lat + cy / latScale, p.lon + cx / lonScale)
        return Projection(kotlin.math.sqrt(cx * cx + cy * cy), t, point)
    }

    /**
     * Speed limit for the road nearest [point].
     *
     * **How the edge is chosen.** The nearest routable *node* is found with the
     * same index and the same car-accessible mask the router uses, then every
     * car edge leaving that node is scored by the distance from the fix to the
     * edge's own geometry — a node can be the junction of a 50 street and an 80
     * arterial, and picking "the first outgoing edge" would report whichever
     * happened to be written first. The nearest edge along its geometry is the
     * one the car is actually on.
     *
     * **Why it can return null.** Outside the extract, on a car-forbidden way,
     * or on an edge whose stored speed is 0, there is no limit to report. The
     * caller shows `--`; substituting a per-class default here would be
     * inventing the number the brief says not to invent.
     */
    override fun limitAt(point: GeoPoint): Int? {
        // Read the limit from the exact edge the car is on, not from the first
        // outgoing edge of the nearest junction. The graph builder now writes
        // the signed OSM `maxspeed` into speedKph, so this roundel is real data
        // rather than a per-class average.
        val snap = snapToEdge(point)
        if (snap != null) {
            if (snap.distance > MAX_LIMIT_DISTANCE_M) return null
            val speed = graph.edge(snap.edgeIndex).speedKph
            return if (speed > 0) speed else null
        }

        val nodeId = nearest(point.lat, point.lon)
        if (nodeId < 0) return null
        val node = graph.node(nodeId)
        if (node.edgeCount <= 0) return null

        var bestSpeed = 0
        var bestDistance = Double.MAX_VALUE
        for (k in 0 until node.edgeCount) {
            val edgeIndex = node.firstEdge + k
            if (edgeIndex < 0 || edgeIndex >= edgeCount) continue
            val edge = graph.edge(edgeIndex)
            if (edge.carForbidden || edge.ferry) continue
            val distance = distanceToEdge(point, nodeId, edge)
            if (distance < bestDistance) {
                bestDistance = distance
                bestSpeed = edge.speedKph
            }
        }
        // 50 m is roughly "on this road" in a 5–10 m accuracy GPS; beyond that
        // the nearest node is not the street the car is driving on.
        if (bestDistance > MAX_LIMIT_DISTANCE_M) return null
        return if (bestSpeed > 0) bestSpeed else null
    }

    /** Distance from [point] to an edge's polyline, in metres. */
    private fun distanceToEdge(point: GeoPoint, fromNode: Int, edge: RoutingGraph.EdgeRecord): Double {
        val start = graph.nodePoint(fromNode)
        val geometry = graph.edgeGeometry(edge)
        if (geometry.isEmpty()) {
            val end = graph.nodePoint(edge.target)
            return distanceToSegment(point, start, end)
        }
        var best = Double.MAX_VALUE
        var previous = start
        for (vertex in geometry) {
            val d = distanceToSegment(point, previous, vertex)
            if (d < best) best = d
            previous = vertex
        }
        val end = graph.nodePoint(edge.target)
        val tail = distanceToSegment(point, previous, end)
        return if (tail < best) tail else best
    }

    /**
     * Point-to-segment distance in metres, on a local flat projection.
     *
     * A flat approximation, not haversine: the only decision made from this
     * number is "which of these edges is nearest", over distances of tens of
     * metres, where the projection error is centimetres.
     */
    private fun distanceToSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val latScale = 110_540.0
        val lonScale = 111_320.0 * kotlin.math.cos(Math.toRadians(p.lat))
        val ax = (a.lon - p.lon) * lonScale
        val ay = (a.lat - p.lat) * latScale
        val bx = (b.lon - p.lon) * lonScale
        val by = (b.lat - p.lat) * latScale
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 1e-9) return kotlin.math.sqrt(ax * ax + ay * ay)
        var t = -(ax * dx + ay * dy) / lengthSquared
        t = t.coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return kotlin.math.sqrt(cx * cx + cy * cy)
    }

    override fun route(from: GeoPoint, to: GeoPoint, options: RouteOptions): RouteResult =
        routeWith(from, to, options, null)

    /**
     * [route] with an optional per-edge cost multiplier.
     *
     * The multiplier is how alternatives are produced: after a route is found,
     * every edge it used is made artificially expensive and A* is run again.
     * A* then either finds a genuinely different road or gives up, which is the
     * honest answer — two nearly identical lines on the map are worse than one,
     * because the driver cannot tell which is which.
     */
    private fun routeWith(
        from: GeoPoint,
        to: GeoPoint,
        options: RouteOptions,
        penalty: DoubleArray?
    ): RouteResult {
        // Both endpoints are projected onto real road geometry. Routing still
        // runs vertex-to-vertex, but the drawn line is extended along the
        // snapped edges up to the car and up to the pin, so there is no straight
        // "crow-fly" stub at either end.
        val startSnap = snapToEdge(from, wide = true)
        val endSnap = snapToEdge(to, wide = true)
        val startNode = startSnap?.let { graph.edge(it.edgeIndex).target }
            ?: nearest(from.lat, from.lon)
        val endNode = endSnap?.fromNode ?: nearest(to.lat, to.lon)
        if (startNode < 0) return RouteResult.Failure(RouteError.NO_START_NODE)
        if (endNode < 0) return RouteResult.Failure(RouteError.NO_END_NODE)

        // Car and destination already on the same directed road: no A* needed,
        // and going around the graph would produce a nonsensical detour.
        if (startSnap != null && endSnap != null &&
            startSnap.edgeIndex == endSnap.edgeIndex &&
            (startSnap.segmentIndex < endSnap.segmentIndex ||
                (startSnap.segmentIndex == endSnap.segmentIndex && startSnap.t <= endSnap.t))
        ) {
            val first = startSnap.point
            val points = ArrayList<GeoPoint>()
            points.add(first)
            for (i in startSnap.segmentIndex + 1..endSnap.segmentIndex) {
                points.add(startSnap.chain[i])
            }
            points.add(endSnap.point)
            val clean = dedupeConsecutive(points)
            val metres = polylineMeters(clean).toInt().coerceAtLeast(1)
            val speed = graph.edge(startSnap.edgeIndex).speedKph.coerceAtLeast(1)
            return RouteResult.Success(
                Route(
                    points = clean,
                    distanceMeters = metres,
                    durationSeconds = (metres / (speed * 1000.0 / 3600.0)).toInt().coerceAtLeast(1),
                    maneuvers = listOf(
                        Maneuver(ManeuverType.DEPART, first, 0, null, "Bắt đầu"),
                        Maneuver(ManeuverType.ARRIVE, clean.last(), metres, null, "Đã đến nơi")
                    ),
                    nodeIds = intArrayOf(startNode)
                )
            )
        }

        if (startNode == endNode) {
            // Short trip inside one junction: emit a two-point route rather
            // than failing, so "route to the shop next door" still works.
            val p0 = startSnap?.point ?: graph.nodePoint(startNode)
            val p1 = endSnap?.point ?: GeoPoint(to.lat, to.lon)
            val metres = haversine(p0, p1).toInt().coerceAtLeast(1)
            return RouteResult.Success(
                Route(
                    points = listOf(p0, p1),
                    distanceMeters = metres,
                    durationSeconds = (metres / 8.3).toInt().coerceAtLeast(10),
                    maneuvers = listOf(
                        Maneuver(ManeuverType.DEPART, p0, 0, null, "Bắt đầu"),
                        Maneuver(ManeuverType.ARRIVE, p1, metres, null, "Đã đến nơi")
                    ),
                    nodeIds = intArrayOf(startNode)
                )
            )
        }

        return try {
            var path = findPath(startNode, endNode, options, penalty)
            if (path == null && (startSnap != null || endSnap != null)) {
                // A snapped endpoint can sit on a one-way dead end whose source
                // has no other way in. Retry with the old vertex snapping before
                // declaring the destination unreachable.
                val fallbackStart = nearest(from.lat, from.lon)
                val fallbackEnd = nearest(to.lat, to.lon)
                if (fallbackStart >= 0 && fallbackEnd >= 0 && fallbackStart != fallbackEnd) {
                    path = findPath(fallbackStart, fallbackEnd, options, penalty)
                    if (path != null) {
                        return RouteResult.Success(
                            buildRoute(path.nodes, path.edges, to, null, null)
                        )
                    }
                }
            }
            if (path == null) RouteResult.Failure(RouteError.UNREACHABLE)
            else RouteResult.Success(buildRoute(path.nodes, path.edges, to, startSnap, endSnap))
        } catch (e: Exception) {
            RouteResult.Failure(RouteError.INTERNAL, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Up to [maxRoutes] alternatives, fastest first, produced by penalising the
     * edges the previous answer used.
     *
     * **Why penalise instead of enumerating k-shortest paths.** Yen's algorithm
     * needs a full edge-disjoint search per spur and a heap of candidate
     * deviations; on a 425 000-edge graph that is a second of CPU for a preview
     * the driver may dismiss immediately. Multiplying the cost of the used
     * edges by [ALT_PENALTY] gets the same practical result — A* is forced onto
     * a different corridor — in one extra search per alternative.
     *
     * A candidate is dropped when it shares more than
     * [MAX_SHARED_NODE_FRACTION] of its vertices with an accepted route, or
     * when it is more than [MAX_ALTERNATIVE_SLOWDOWN]× the fastest. Both filters
     * exist for the same reason: a second blue line that runs beside the first
     * for its whole length is not a choice, it is noise.
     */
    override fun routeAlternatives(
        from: GeoPoint,
        to: GeoPoint,
        options: RouteOptions,
        maxRoutes: Int
    ): List<Route> {
        if (maxRoutes <= 1) {
            val only = route(from, to, options)
            return if (only is RouteResult.Success) listOf(only.route) else emptyList()
        }
        val penalty = DoubleArray(edgeCount) { 1.0 }
        val accepted = ArrayList<Route>(maxRoutes)
        var fastest = 0
        var attempt = 0
        while (accepted.size < maxRoutes && attempt < MAX_ALTERNATIVE_ATTEMPTS) {
            val result = routeWith(from, to, options, if (attempt == 0) null else penalty)
            attempt++
            val candidate = (result as? RouteResult.Success)?.route ?: break
            if (accepted.isEmpty()) fastest = candidate.durationSeconds
            if (accepted.isNotEmpty() &&
                candidate.durationSeconds > fastest * MAX_ALTERNATIVE_SLOWDOWN
            ) {
                break
            }
            if (accepted.isNotEmpty() && overlapsAny(candidate, accepted)) {
                // Too close to an existing choice; push harder next round.
                penaliseRoute(candidate, penalty)
                continue
            }
            accepted.add(candidate)
            penaliseRoute(candidate, penalty)
        }
        return accepted.sortedBy { it.durationSeconds }
    }

    /** True when [candidate] shares most of its vertices with any accepted route. */
    private fun overlapsAny(candidate: Route, accepted: List<Route>): Boolean {
        val mine = candidate.nodeIds.toHashSet()
        if (mine.isEmpty()) return false
        for (other in accepted) {
            val shared = other.nodeIds.count { it in mine }
            val smaller = minOf(mine.size, other.nodeIds.size)
            if (smaller > 0 && shared.toDouble() / smaller >= MAX_SHARED_NODE_FRACTION) return true
        }
        return false
    }

    /**
     * Raises the cost of every edge the route traversed.
     *
     * The edge between two consecutive vertices is found by scanning the source
     * vertex's out-edges, which is at most a handful per junction — cheaper and
     * less invasive than threading the edge path out of the A* search just to
     * read it here.
     */
    private fun penaliseRoute(route: Route, penalty: DoubleArray) {
        val ids = route.nodeIds
        for (i in 0 until ids.size - 1) {
            val from = ids[i]
            val to = ids[i + 1]
            if (from !in 0 until nodeCount) continue
            val node = graph.node(from)
            for (k in 0 until node.edgeCount) {
                val edgeIdx = node.firstEdge + k
                if (edgeIdx !in 0 until edgeCount) continue
                if (graph.edge(edgeIdx).target == to) {
                    if (penalty[edgeIdx] < ALT_PENALTY) penalty[edgeIdx] = ALT_PENALTY
                    break
                }
            }
        }
    }

    private fun polylineMeters(points: List<GeoPoint>): Double {
        var sum = 0.0
        for (i in 1 until points.size) sum += haversine(points[i - 1], points[i])
        return sum
    }

    // -- A* -----------------------------------------------------------------

    private class Path(val nodes: IntArray, val edges: IntArray)

    /**
     * Returns the vertex path start..end, or null when the destination cannot
     * be reached (island, disconnected extract boundary, or every edge out of
     * the start is one-way against travel).
     */
    private fun findPath(
        start: Int,
        end: Int,
        options: RouteOptions,
        penalty: DoubleArray? = null
    ): Path? {
        val dist = DoubleArray(nodeCount) { Double.MAX_VALUE }
        val prevEdge = IntArray(nodeCount) { -1 }
        val prevNode = IntArray(nodeCount) { -1 }
        val closed = BooleanArray(nodeCount)
        val endPoint = graph.nodePoint(end)

        val open = MinHeap(estimateCapacity(start, end))
        dist[start] = 0.0
        open.push(start, heuristic(graph.nodePoint(start), endPoint))

        var explored = 0
        while (open.isNotEmpty()) {
            val current = open.pop()
            // Lazy deletion: a node can be pushed several times as its cost
            // improves, so the first pop of a node is the only useful one.
            if (closed[current]) continue
            if (current == end) return reconstruct(current, prevEdge, prevNode)
            closed[current] = true
            explored++

            // Safety valve: a pathological query should fail fast instead of
            // pinning the CPU for the rest of the drive.
            if (explored > MAX_EXPLORED) return null

            val node = graph.node(current)
            val base = node.firstEdge
            for (k in 0 until node.edgeCount) {
                val edgeIdx = base + k
                if (edgeIdx < 0 || edgeIdx >= edgeCount) continue
                val e = graph.edge(edgeIdx)
                if (e.carForbidden) continue
                if (options.avoidTolls && e.toll) continue
                if (options.avoidFerries && e.ferry) continue
                val next = e.target
                if (next < 0 || next >= nodeCount || closed[next]) continue

                val factor = penalty?.get(edgeIdx) ?: 1.0
                val tentative = dist[current] + e.baseSeconds * factor
                if (tentative < dist[next]) {
                    dist[next] = tentative
                    prevEdge[next] = edgeIdx
                    prevNode[next] = current
                    open.push(next, tentative + heuristic(graph.nodePoint(next), endPoint))
                }
            }
        }
        return null
    }

    private fun reconstruct(end: Int, prevEdge: IntArray, prevNode: IntArray): Path {
        val nodes = ArrayList<Int>(64)
        val edges = ArrayList<Int>(64)
        var cur = end
        while (true) {
            nodes.add(cur)
            val pe = prevEdge[cur]
            if (pe < 0) break
            edges.add(pe)
            cur = prevNode[cur]
            if (cur < 0) break
        }
        nodes.reverse()
        edges.reverse()
        return Path(nodes.toIntArray(), edges.toIntArray())
    }

    // -- route building -----------------------------------------------------

    private class RouteSegment(
        val fromNode: Int,
        val toNode: Int,
        val distance: Double,
        val name: String?,
        val roundabout: Boolean
    )

    private fun buildRoute(
        nodePath: IntArray,
        edgePath: IntArray,
        destination: GeoPoint,
        startSnap: EdgeSnap?,
        endSnap: EdgeSnap?
    ): Route {
        val points = ArrayList<GeoPoint>(nodePath.size * 4)
        var edgeDistance = 0.0
        var totalSeconds = 0.0
        val segments = ArrayList<RouteSegment>(edgePath.size)

        // Start with the piece of the snapped edge between the car and its
        // target vertex. Without it the line begins at a junction tens or
        // hundreds of metres away and looks like a crow-fly stub.
        if (startSnap != null) points.addAll(startSnap.tailFromProjection())
        else points.add(graph.nodePoint(nodePath[0]))
        for (i in edgePath.indices) {
            val edgeIdx = edgePath[i]
            val e = graph.edge(edgeIdx)
            edgeDistance += e.distanceMeters
            totalSeconds += e.baseSeconds
            segments.add(
                RouteSegment(
                    fromNode = nodePath[i],
                    toNode = e.target,
                    distance = e.distanceMeters,
                    name = graph.edgeName(e),
                    roundabout = e.roundabout
                )
            )

            // The writer emits the geometry blob once per undirected segment,
            // ordered from the way's start node to its end node. The paired
            // reverse edge points at the same blob and carries
            // FLAG_GEOM_REVERSED, because guessing from index ordering breaks
            // for one-way-reversed ways where only one edge exists.
            val geom = graph.edgeGeometry(e)
            val ordered = if (e.geometryReversed) geom.asReversed() else geom
            points.addAll(ordered)
            points.add(graph.nodePoint(e.target))
        }
        // ... and finish along the snapped edge from its source vertex up to
        // the destination projection, so the line actually reaches the pin.
        if (endSnap != null) points.addAll(endSnap.headToProjection())

        val clean = dedupeConsecutive(points)
        val drawnMeters = polylineMeters(clean)
        // Distance is measured off the polyline that is drawn, so the two
        // snapped partial edges count too. Duration scales with that distance
        // at the same average speed the A* edges were costed at.
        val totalDistance = if (edgeDistance > 0.0) drawnMeters else edgeDistance
        val scaledSeconds = if (edgeDistance > 0.0) {
            totalSeconds * (drawnMeters / edgeDistance)
        } else {
            totalSeconds
        }

        return Route(
            points = clean,
            distanceMeters = totalDistance.toInt().coerceAtLeast(1),
            durationSeconds = scaledSeconds.toInt().coerceAtLeast(1),
            maneuvers = buildManeuvers(segments, clean, destination),
            nodeIds = nodePath
        )
    }

    /**
     * Derives turn instructions from the bearing change between consecutive
     * segments. A "continue" is emitted when the street name changes without a
     * meaningful turn, which is what drivers hear as "Tiếp tục đi thẳng trên
     * <đường>".
     */
    private fun buildManeuvers(
        segments: List<RouteSegment>,
        points: List<GeoPoint>,
        destination: GeoPoint
    ): List<Maneuver> {
        if (segments.isEmpty()) {
            return listOf(
                Maneuver(ManeuverType.DEPART, points.firstOrNull() ?: destination, 0, null, "Bắt đầu"),
                Maneuver(ManeuverType.ARRIVE, destination, 0, null, "Đã đến nơi")
            )
        }

        val startPoint = points.firstOrNull() ?: destination
        val result = ArrayList<Maneuver>()
        result.add(
            Maneuver(
                ManeuverType.DEPART, startPoint, 0, segments.first().name,
                buildDepartInstruction(segments.first().name)
            )
        )

        var travelled = 0.0
        for (i in 0 until segments.size - 1) {
            val cur = segments[i]
            val next = segments[i + 1]
            travelled += cur.distance

            val atNode = graph.nodePoint(cur.toNode)
            val delta = normalizeAngle(
                bearingTo(atNode, graph.nodePoint(next.toNode)) -
                    bearingTo(graph.nodePoint(cur.fromNode), atNode)
            )
            val turn = classifyTurn(delta)

            when {
                next.roundabout || cur.roundabout -> result.add(
                    Maneuver(
                        ManeuverType.ROUNDABOUT, atNode, travelled.toInt(), next.name,
                        buildRoundaboutInstruction(next.name, delta)
                    )
                )
                turn != ManeuverDirection.STRAIGHT -> result.add(
                    Maneuver(
                        ManeuverType.TURN, atNode, travelled.toInt(), next.name,
                        buildTurnInstruction(turn, next.name), turn
                    )
                )
                nameChanged(cur.name, next.name) -> result.add(
                    Maneuver(
                        ManeuverType.CONTINUE, atNode, travelled.toInt(), next.name,
                        buildContinueInstruction(next.name), ManeuverDirection.STRAIGHT
                    )
                )
            }
        }

        val totalMetres = segments.sumOf { it.distance }.toInt()
        result.add(
            Maneuver(
                ManeuverType.ARRIVE, destination, totalMetres,
                segments.last().name, "Đã đến nơi"
            )
        )
        return mergeConsecutiveContinues(result)
    }

    /**
     * A long street split into many OSM segments produces one "continue" per
     * segment. Only the first is useful to the driver.
     */
    private fun mergeConsecutiveContinues(input: List<Maneuver>): List<Maneuver> {
        val out = ArrayList<Maneuver>(input.size)
        for (m in input) {
            val last = out.lastOrNull()
            if (m.type == ManeuverType.CONTINUE && last?.type == ManeuverType.CONTINUE &&
                last.streetName == m.streetName
            ) {
                continue
            }
            out.add(m)
        }
        return out
    }

    private fun nameChanged(a: String?, b: String?): Boolean =
        !b.isNullOrBlank() && a != b

    private fun buildDepartInstruction(name: String?): String =
        if (name.isNullOrBlank()) "Bắt đầu chuyến đi" else "Bắt đầu đi trên $name"

    private fun buildTurnInstruction(dir: ManeuverDirection, name: String?): String {
        val action = when (dir) {
            ManeuverDirection.LEFT -> "Rẽ trái"
            ManeuverDirection.RIGHT -> "Rẽ phải"
            ManeuverDirection.SLIGHT_LEFT -> "Chếch sang trái"
            ManeuverDirection.SLIGHT_RIGHT -> "Chếch sang phải"
            ManeuverDirection.SHARP_LEFT -> "Rẽ gắt sang trái"
            ManeuverDirection.SHARP_RIGHT -> "Rẽ gắt sang phải"
            ManeuverDirection.UTURN -> "Quay đầu"
            else -> "Đi thẳng"
        }
        return if (name.isNullOrBlank()) action else "$action vào $name"
    }

    private fun buildContinueInstruction(name: String?): String =
        if (name.isNullOrBlank()) "Tiếp tục đi thẳng" else "Tiếp tục đi thẳng trên $name"

    private fun buildRoundaboutInstruction(name: String?, delta: Double): String {
        val action = if (delta > 0) "Đi vào vòng xuyến rồi rẽ trái"
        else "Đi vào vòng xuyến rồi rẽ phải"
        return if (name.isNullOrBlank()) action else "$action ra $name"
    }

    private fun classifyTurn(deltaDeg: Double): ManeuverDirection {
        val d = deltaDeg
        return when {
            abs(d) <= TURN_THRESHOLD_DEG -> ManeuverDirection.STRAIGHT
            d > 0 && d <= 45 -> ManeuverDirection.SLIGHT_RIGHT
            d > 45 && d <= 135 -> ManeuverDirection.RIGHT
            d > 135 -> ManeuverDirection.SHARP_RIGHT
            d < 0 && d >= -45 -> ManeuverDirection.SLIGHT_LEFT
            d < 0 && d >= -135 -> ManeuverDirection.LEFT
            else -> ManeuverDirection.SHARP_LEFT
        }
    }

    /** Wraps a bearing difference into [-180, 180]. */
    private fun normalizeAngle(a: Float): Double {
        var x = a % 360.0
        if (x > 180) x -= 360
        if (x < -180) x += 360
        return x
    }

    /** Initial bearing from [a] to [b], degrees clockwise from north. */
    private fun bearingTo(a: GeoPoint, b: GeoPoint): Float {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val deg = Math.toDegrees(atan2(y, x))
        return ((deg + 360) % 360).toFloat()
    }

    private fun heuristic(from: GeoPoint, to: GeoPoint): Double =
        haversine(from, to) / (MAX_GRAPH_SPEED_KPH * 1000.0 / 3600.0)

    private fun estimateCapacity(start: Int, end: Int): Int {
        val d = haversine(graph.nodePoint(start), graph.nodePoint(end))
        return (d / 50.0).toInt().coerceIn(256, 65_536)
    }

    private fun dedupeConsecutive(points: List<GeoPoint>): List<GeoPoint> {
        if (points.size < 2) return points
        val out = ArrayList<GeoPoint>(points.size)
        var last: GeoPoint? = null
        for (p in points) {
            if (last == null || last.lat != p.lat || last.lon != p.lon) {
                out.add(p)
                last = p
            }
        }
        return out
    }

    override fun close() {
        graph.close()
    }

    companion object {
        /** Highest speed limit present in the pack; keeps the heuristic admissible. */
        private const val MAX_GRAPH_SPEED_KPH = 120.0
        private const val MAX_EXPLORED = 400_000
        private const val TURN_THRESHOLD_DEG = 25.0

        /**
         * Beyond this the nearest node is not "the road the car is on". Chosen
         * at 50 m: GPS accuracy is 5–20 m here, and a fix 50 m from every car
         * edge is a car park, a driveway or a bad sample — none of which has a
         * speed limit to report.
         */
        private const val MAX_LIMIT_DISTANCE_M = 50.0

        /**
         * How far a route endpoint may be from car road geometry and still be
         * snapped onto it. Wider than [MAX_LIMIT_DISTANCE_M] because a search
         * result can be a POI set back from the carriageway, but still tight
         * enough that a fix in the next district cannot capture a road it is
         * not on. Beyond this we fall back to plain vertex snapping; the line
         * then starts at a junction instead of inventing a long straight
         * connector across town.
         */
        private const val MAX_SNAP_DISTANCE_M = 250.0

        /**
         * Radius of the spatial pass used for route endpoints.
         *
         * Sized for the worst real case in this pack: the destination pin sits
         * in the middle of a 582 m bridge, so the bridge's *source* vertex can
         * be ~300 m away. 700 m covers that with margin while still scanning a
         * 7×7 block of index cells rather than the whole extract.
         */
        private const val WIDE_SNAP_RADIUS_M = 700.0

        /** Cost multiplier applied to the edges of an already-found route. */
        private const val ALT_PENALTY = 2.4

        /** How many A* runs the alternative search may spend. */
        private const val MAX_ALTERNATIVE_ATTEMPTS = 6

        /** A candidate sharing this much of a chosen route is not a choice. */
        private const val MAX_SHARED_NODE_FRACTION = 0.75

        /** A candidate slower than this multiple of the fastest is not offered. */
        private const val MAX_ALTERNATIVE_SLOWDOWN = 1.7

        /**
         * Opens the graph in [graphDir] — the directory that holds `graph.bin`
         * and `graph.idx`.
         *
         * **The parameter is the graph directory, not the pack root.** It used
         * to be the pack root with `"routing-graph"` appended here, while the
         * one caller passed `MapPack.routingDir()` — which has already resolved
         * that same segment. The result was `<pack>/routing-graph/routing-graph`,
         * a path that never exists, so `open` always answered `null`, so
         * `AppGraph.navigation()` was always `null`, and the map screen told
         * the driver "Chưa cài map pack — không thể dẫn đường" while a perfectly
         * good pack was installed and drawn behind the banner. Appending the
         * segment here also ignored a pack that names the directory something
         * else in `metadata.json`.
         */
        fun open(graphDir: File): AStarRouter? {
            val graph = RoutingGraph.open(graphDir) ?: return null
            val index = GraphIndex.open(File(graphDir, "graph.idx"), graph.nodeCount) ?: run {
                graph.close()
                return null
            }
            return AStarRouter(graph, index)
        }

        /** Haversine distance in metres. */
        fun haversine(a: GeoPoint, b: GeoPoint): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val lat1 = Math.toRadians(a.lat)
            val lat2 = Math.toRadians(b.lat)
            val h = sin(dLat / 2) * sin(dLat / 2) +
                sin(dLon / 2) * sin(dLon / 2) * cos(lat1) * cos(lat2)
            return 2 * r * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
        }
    }
}

/**
 * Binary min-heap keyed by priority.
 *
 * A* on this graph pushes far more entries than there are nodes (a node can be
 * improved several times), so the heap stores duplicate node ids and the
 * caller skips already-closed nodes — the standard "lazy deletion" pattern
 * that avoids a decrease-key structure.
 */
private class MinHeap(capacity: Int) {
    private var ids = IntArray(capacity.coerceAtLeast(16))
    private var prs = DoubleArray(capacity.coerceAtLeast(16))
    private var size = 0

    fun isNotEmpty(): Boolean = size > 0

    fun push(id: Int, priority: Double) {
        if (size == ids.size) grow()
        var i = size++
        ids[i] = id
        prs[i] = priority
        while (i > 0) {
            val parent = (i - 1) / 2
            if (prs[parent] <= prs[i]) break
            swap(i, parent)
            i = parent
        }
    }

    fun pop(): Int {
        val top = ids[0]
        size--
        if (size > 0) {
            ids[0] = ids[size]
            prs[0] = prs[size]
            var i = 0
            while (true) {
                val l = i * 2 + 1
                val r = l + 1
                var smallest = i
                if (l < size && prs[l] < prs[smallest]) smallest = l
                if (r < size && prs[r] < prs[smallest]) smallest = r
                if (smallest == i) break
                swap(i, smallest)
                i = smallest
            }
        }
        return top
    }

    private fun swap(a: Int, b: Int) {
        val ti = ids[a]; ids[a] = ids[b]; ids[b] = ti
        val tp = prs[a]; prs[a] = prs[b]; prs[b] = tp
    }

    private fun grow() {
        val newCap = ids.size * 2
        ids = ids.copyOf(newCap)
        prs = prs.copyOf(newCap)
    }
}

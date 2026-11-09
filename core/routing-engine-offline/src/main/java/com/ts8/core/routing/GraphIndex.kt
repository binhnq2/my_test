package com.ts8.core.routing

import com.ts8.core.map.GeoPoint
import kotlin.math.abs

/**
 * Spatial grid index over graph nodes, read from `routing-graph/graph.idx`.
 *
 * Used for two things: snapping a GPS fix to the nearest road vertex, and
 * locating the start/end vertices of a route. The format is specified in
 * `docs/internal/FORMAT_SPECS.md` §3.4.
 */
class GraphIndex private constructor(
    private val data: java.nio.ByteBuffer,
    private val cols: Int,
    private val rows: Int,
    private val cellDeg: Double,
    private val west: Double,
    private val south: Double,
    private val cellStartOffset: Int,
    private val cellNodesOffset: Int,
    private val total: Int
) {

    fun cellOf(lat: Double, lon: Double): Int {
        val c = ((lon - west) / cellDeg).toInt()
        val r = ((lat - south) / cellDeg).toInt()
        if (c < 0 || c >= cols || r < 0 || r >= rows) return -1
        return r * cols + c
    }

    private fun cellRange(cell: Int): IntRange {
        val start = data.getInt(cellStartOffset + cell * 4)
        val end = data.getInt(cellStartOffset + (cell + 1) * 4)
        return start until end
    }

    /**
     * Nearest node to [lat]/[lon], searching an expanding ring of cells so a
     * GPS fix that lands in an empty cell (water, park) still resolves.
     *
     * @return node id, or -1 when nothing is within [maxRing] rings.
     */
    fun nearestNode(lat: Double, lon: Double, maxRing: Int = 3): Int {
        val c0 = ((lon - west) / cellDeg).toInt()
        val r0 = ((lat - south) / cellDeg).toInt()
        if (c0 < -maxRing || c0 > cols + maxRing || r0 < -maxRing || r0 > rows + maxRing) return -1

        var best = -1
        var bestDist = Double.MAX_VALUE
        val latScale = 110_540.0
        val lonScale = 111_320.0 * kotlin.math.cos(Math.toRadians(lat))

        for (ring in 0..maxRing) {
            var found = false
            for (dr in -ring..ring) {
                for (dc in -ring..ring) {
                    // Only the shell of the ring; inner rings were scanned.
                    if (ring > 0 && abs(dr) != ring && abs(dc) != ring) continue
                    val c = c0 + dc
                    val r = r0 + dr
                    if (c < 0 || c >= cols || r < 0 || r >= rows) continue
                    val cell = r * cols + c
                    for (i in cellRange(cell)) {
                        val nodeId = data.getInt(cellNodesOffset + i * 4)
                        found = true
                        // The node coordinates are not stored in the index, so
                        // the caller-supplied distance is approximate here; the
                        // exact check happens in [nearestNodeExact].
                        val approx = (dc * cellDeg * lonScale) * (dc * cellDeg * lonScale) +
                            (dr * cellDeg * latScale) * (dr * cellDeg * latScale)
                        if (approx < bestDist) {
                            bestDist = approx
                            best = nodeId
                        }
                    }
                }
            }
            // Once a ring yielded candidates, one more ring is scanned to be
            // sure the true nearest is not just across a cell boundary.
            if (found && ring >= 1) break
        }
        return best
    }

    /**
     * Nearest node using real coordinates. [nodePoint] supplies vertex
     * positions, so this is exact rather than cell-approximate.
     *
     * [accept] filters candidates. Routing passes the graph's car-accessible
     * mask here so a GPS fix that lands in a park does not snap onto a footway
     * vertex and then report the destination unreachable.
     */
    fun nearestNodeExact(
        lat: Double,
        lon: Double,
        nodePoint: (Int) -> GeoPoint,
        accept: ((Int) -> Boolean)? = null,
        maxRing: Int = 4
    ): Int {
        val c0 = ((lon - west) / cellDeg).toInt()
        val r0 = ((lat - south) / cellDeg).toInt()
        var best = -1
        var bestDist = Double.MAX_VALUE
        for (ring in 0..maxRing) {
            for (dr in -ring..ring) {
                for (dc in -ring..ring) {
                    if (ring > 0 && abs(dr) != ring && abs(dc) != ring) continue
                    val c = c0 + dc
                    val r = r0 + dr
                    if (c < 0 || c >= cols || r < 0 || r >= rows) continue
                    val cell = r * cols + c
                    for (i in cellRange(cell)) {
                        val nodeId = data.getInt(cellNodesOffset + i * 4)
                        if (nodeId < 0 || nodeId >= total) continue
                        if (accept != null && !accept(nodeId)) continue
                        val p = nodePoint(nodeId)
                        val d = approximateMeters(lat, lon, p.lat, p.lon)
                        if (d < bestDist) {
                            bestDist = d
                            best = nodeId
                        }
                    }
                }
            }
            // The searched area already covers at least ring*cellDeg metres;
            // stop once that exceeds the best distance found.
            if (best >= 0 && bestDist < ring * cellDeg * 110_000.0) break
        }
        return best
    }

    /**
     * Every indexed node within [radiusMeters] of the point, unordered.
     *
     * **Why a radius query is needed at all.** [nearestNodeExact] answers "which
     * junction is closest", which is the wrong question for snapping a *route
     * endpoint*: in the middle of a 600 m bridge the closest junction can be
     * 270 m away, and the bridge's own edge may be an **incoming** edge of that
     * junction — so a search that only walks the junction's outgoing edges
     * misses the road the pin is standing on, and the route stops 270 m short
     * of it with a straight "crow-fly" tail.
     *
     * The cell grid is scanned over the rectangle that covers the radius, then
     * each candidate is filtered by its true position, so the result is exact
     * rather than cell-approximate.
     */
    fun nodesWithin(
        lat: Double,
        lon: Double,
        radiusMeters: Double,
        nodePoint: (Int) -> GeoPoint
    ): IntArray {
        val c0 = ((lon - west) / cellDeg).toInt()
        val r0 = ((lat - south) / cellDeg).toInt()
        val latScale = 110_540.0
        val lonScale = 111_320.0 * kotlin.math.cos(Math.toRadians(lat))
        val dc = kotlin.math.ceil(radiusMeters / (cellDeg * lonScale)).toInt().coerceAtLeast(1)
        val dr = kotlin.math.ceil(radiusMeters / (cellDeg * latScale)).toInt().coerceAtLeast(1)
        val out = ArrayList<Int>(64)
        val limit = radiusMeters * radiusMeters
        for (r in r0 - dr..r0 + dr) {
            if (r < 0 || r >= rows) continue
            for (c in c0 - dc..c0 + dc) {
                if (c < 0 || c >= cols) continue
                for (i in cellRange(r * cols + c)) {
                    val nodeId = data.getInt(cellNodesOffset + i * 4)
                    if (nodeId < 0 || nodeId >= total) continue
                    val p = nodePoint(nodeId)
                    val dy = (p.lat - lat) * latScale
                    val dx = (p.lon - lon) * lonScale
                    if (dx * dx + dy * dy <= limit) out.add(nodeId)
                }
            }
        }
        return out.toIntArray()
    }

    private fun approximateMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * 110_540.0
        val dLon = (lon2 - lon1) * 111_320.0 * kotlin.math.cos(Math.toRadians((lat1 + lat2) / 2))
        return kotlin.math.sqrt(dLat * dLat + dLon * dLon)
    }

    companion object {
        fun open(file: java.io.File, nodeCount: Int): GraphIndex? {
            if (!file.isFile) return null
            return try {
                val raf = java.io.RandomAccessFile(file, "r")
                val channel = raf.channel
                val buf = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size())
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val magic = ByteArray(4).also { buf.get(it) }
                if (String(magic, Charsets.US_ASCII) != "TS8I") {
                    channel.close()
                    return null
                }
                buf.short // version
                val cellE7 = buf.short.toInt() and 0xFFFF
                val cols = buf.int
                val rows = buf.int
                val west = buf.double
                val south = buf.double
                buf.double // east
                buf.double // north
                val cellStartOffset = buf.position()
                val cellNodesOffset = cellStartOffset + (cols * rows + 1) * 4
                val totalAvailable = (channel.size() - cellNodesOffset).toInt() / 4
                channel.close()

                if (cols <= 0 || rows <= 0 || cellE7 <= 0) return null
                val raf2 = java.io.RandomAccessFile(file, "r")
                val buf2 = raf2.channel.map(
                    java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, raf2.length()
                ).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                GraphIndex(
                    data = buf2, cols = cols, rows = rows,
                    cellDeg = cellE7 / 1e7, west = west, south = south,
                    cellStartOffset = cellStartOffset, cellNodesOffset = cellNodesOffset,
                    total = nodeCount.coerceAtMost(totalAvailable)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

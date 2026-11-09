package com.ts8.core.routing

import com.ts8.core.map.GeoPoint
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Memory-mapped reader for a pack's `routing-graph/graph.bin`.
 *
 * The file layout is specified in `docs/internal/FORMAT_SPECS.md` §3. The
 * graph is read through `MappedByteBuffer` so a city-sized graph (tens of MB)
 * costs address space, not heap — which matters on an API 23 head unit with
 * a 128 MB heap limit.
 */
class RoutingGraph private constructor(
    private val file: File,
    private val raf: RandomAccessFile,
    private val channel: FileChannel,
    private val buf: MappedGraphBuffer,
    val nodeCount: Int,
    val edgeCount: Int,
    val bounds: DoubleArray
) {

    /**
     * A small wrapper so reads are bounds-checked in one place and the buffer
     * can be released deterministically.
     */
    class MappedGraphBuffer(private val channel: FileChannel) {
        private val map = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
            .order(ByteOrder.LITTLE_ENDIAN)

        fun getInt(offset: Int): Int = map.getInt(offset)
        fun getShort(offset: Int): Short = map.getShort(offset)
        fun getDouble(offset: Int): Double = map.getDouble(offset)
        fun bytes(offset: Int, len: Int): ByteArray {
            val out = ByteArray(len)
            val dup = map.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            dup.position(offset)
            dup.get(out)
            return out
        }

        fun close() {
            // Mapped buffers are released by GC; on API 23 the cleaner is the
            // only route, and it is not public. Closing the channel stops
            // further mapping but the page cache stays until the mapping is
            // collected — acceptable because the graph is opened once per pack.
            runCatching { channel.close() }
        }
    }

    /** One graph vertex. */
    data class NodeRecord(val lat: Double, val lon: Double, val firstEdge: Int, val edgeCount: Int) {
        val point: GeoPoint get() = GeoPoint(lat, lon)
    }

    /** One directed edge. */
    data class EdgeRecord(
        val target: Int,
        val distanceMeters: Double,
        val flags: Int,
        val speedKph: Int,
        val nameIdx: Int,
        val reverseEdge: Int,
        val geometryOffset: Int
    ) {
        /**
         * Informational: the street this edge belongs to is one-way. It does
         * **not** mean the edge is untraversable — this graph stores directed
         * edges, so the opposite direction simply has no edge at all.
         */
        val onewayStreet: Boolean get() = flags and FLAG_ONEWAY != 0
        val toll: Boolean get() = flags and FLAG_TOLL != 0
        val ferry: Boolean get() = flags and FLAG_FERRY != 0
        val carForbidden: Boolean get() = flags and FLAG_CAR_NO != 0
        val roundabout: Boolean get() = flags and FLAG_ROUNDABOUT != 0
        val tunnel: Boolean get() = flags and FLAG_TUNNEL != 0
        val bridge: Boolean get() = flags and FLAG_BRIDGE != 0

        /** Geometry blob is stored against travel direction and must be reversed. */
        val geometryReversed: Boolean get() = flags and FLAG_GEOM_REVERSED != 0
        val roadClass: Int get() = (flags ushr 4) and 0xF
        val baseSeconds: Double get() = distanceMeters / (maxOf(speedKph, 1) * 1000.0 / 3600.0)
    }

    private val nameTableOffset: Int = buf.getInt(48)
    private val nameTableBytes: Int = buf.getInt(52)
    private val nodeTableOffset = HEADER_SIZE
    private val edgeTableOffset = nodeTableOffset + nodeCount * NODE_RECORD_SIZE

    /**
     * Header field 56 carries the geometry blob offset. The writer emits the
     * geometry between the edge table and the name table, so this cannot be
     * derived from node/edge counts alone without duplicating the blob size
     * math in two places — read it from the header instead.
     */
    private val geometryBase: Int = buf.getInt(56)

    /** Lazily built name cache; the name table is only walked once. */
    private val names: List<String> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val out = ArrayList<String>(256)
        var o = nameTableOffset
        val end = nameTableOffset + nameTableBytes
        while (o + 2 <= end) {
            val len = buf.getShort(o).toInt() and 0xFFFF
            if (o + 2 + len > end) break
            out.add(String(buf.bytes(o + 2, len), Charsets.UTF_8))
            o += 2 + len
        }
        out
    }

    // -- node / edge access -------------------------------------------------

    fun node(id: Int): NodeRecord {
        val o = nodeTableOffset + id * NODE_RECORD_SIZE
        return NodeRecord(
            lat = buf.getInt(o) / LAT_E7,
            lon = buf.getInt(o + 4) / LAT_E7,
            firstEdge = buf.getInt(o + 8),
            edgeCount = buf.getInt(o + 12)
        )
    }

    fun edge(index: Int): EdgeRecord {
        val o = edgeTableOffset + index * EDGE_RECORD_SIZE
        return EdgeRecord(
            target = buf.getInt(o),
            distanceMeters = buf.getInt(o + 4) / 100.0,
            flags = buf.getShort(o + 8).toInt() and 0xFFFF,
            speedKph = buf.getShort(o + 10).toInt() and 0xFFFF,
            nameIdx = buf.getInt(o + 12),
            reverseEdge = buf.getInt(o + 16),
            geometryOffset = buf.getInt(o + 20)
        )
    }

    /** Street name for an edge, or null when unnamed. */
    fun edgeName(edge: EdgeRecord): String? {
        if (edge.nameIdx == NONE_U32) return null
        val table = names
        return if (edge.nameIdx in table.indices) table[edge.nameIdx] else null
    }

    /** Intermediate geometry points for an edge, outermost-first. */
    fun edgeGeometry(edge: EdgeRecord): List<GeoPoint> {
        if (edge.geometryOffset == NONE_U32) return emptyList()
        val o = geometryBase + edge.geometryOffset
        val count = buf.getInt(o)
        if (count <= 0 || count > 10_000) return emptyList()
        val out = ArrayList<GeoPoint>(count)
        for (i in 0 until count) {
            val p = o + 4 + i * 8
            out.add(GeoPoint(buf.getInt(p) / LAT_E7, buf.getInt(p + 4) / LAT_E7))
        }
        return out
    }

    fun nodePoint(id: Int): GeoPoint {
        val o = nodeTableOffset + id * NODE_RECORD_SIZE
        return GeoPoint(buf.getInt(o) / LAT_E7, buf.getInt(o + 4) / LAT_E7)
    }

    /**
     * Nodes that touch at least one car-accessible edge, in either direction.
     *
     * Snapping must ignore footway/path/cycleway vertices: a GPS fix in a park
     * is often closest to a pedestrian node, and routing from there produces a
     * "no route" that looks like a data bug rather than a bad snap.
     *
     * A node with only *incoming* car edges still counts, because a one-way
     * dead end is a legitimate destination.
     */
    val routableNode: BooleanArray by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val ok = BooleanArray(nodeCount)
        for (n in 0 until nodeCount) {
            val o = nodeTableOffset + n * NODE_RECORD_SIZE
            val first = buf.getInt(o + 8)
            val count = buf.getInt(o + 12)
            for (k in 0 until count) {
                val e = first + k
                if (e < 0 || e >= edgeCount) continue
                val eo = edgeTableOffset + e * EDGE_RECORD_SIZE
                val flags = buf.getShort(eo + 8).toInt() and 0xFFFF
                if (flags and FLAG_CAR_NO != 0) continue
                ok[n] = true
                val target = buf.getInt(eo)
                if (target in 0 until nodeCount) ok[target] = true
            }
        }
        ok
    }

    fun close() {
        buf.close()
    }

    companion object {
        private const val HEADER_SIZE = 64
        private const val NODE_RECORD_SIZE = 16
        private const val EDGE_RECORD_SIZE = 24
        private const val NONE_U32 = 0xFFFFFFFF.toInt()
        private const val LAT_E7 = 1e7

        const val FLAG_ONEWAY = 1
        const val FLAG_TOLL = 1 shl 1
        const val FLAG_FERRY = 1 shl 2
        const val FLAG_CAR_NO = 1 shl 8
        const val FLAG_ROUNDABOUT = 1 shl 9
        const val FLAG_TUNNEL = 1 shl 10
        const val FLAG_BRIDGE = 1 shl 11
        const val FLAG_GEOM_REVERSED = 1 shl 12

        /**
         * Opens a graph file. Returns null when the file is missing or the
         * header is not a graph this build understands — callers must surface
         * that as "pack unavailable", never substitute demo data.
         */
        fun open(dir: File): RoutingGraph? {
            val file = File(dir, "graph.bin")
            if (!file.isFile) return null
            var raf: RandomAccessFile? = null
            var channel: FileChannel? = null
            try {
                raf = RandomAccessFile(file, "r")
                channel = raf.channel
                if (channel.size() < HEADER_SIZE) return null
                val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                channel.read(header, 0)
                header.flip()
                val magic = ByteArray(4).also { header.get(it) }
                if (String(magic, Charsets.US_ASCII) != "TS8G") return null
                val version = header.short.toInt()
                if (version != 1) return null
                header.short // flags
                val nodeCount = header.int
                val edgeCount = header.int
                if (nodeCount <= 0 || edgeCount <= 0) return null
                val west = header.double
                val south = header.double
                val east = header.double
                val north = header.double

                val expected = HEADER_SIZE.toLong() + nodeCount.toLong() * NODE_RECORD_SIZE +
                    edgeCount.toLong() * EDGE_RECORD_SIZE
                if (channel.size() < expected) return null

                val mapped = MappedGraphBuffer(channel)
                return RoutingGraph(
                    file, raf, channel, mapped, nodeCount, edgeCount,
                    doubleArrayOf(west, south, east, north)
                )
            } catch (e: Exception) {
                runCatching { channel?.close() }
                runCatching { raf?.close() }
                return null
            }
        }
    }
}

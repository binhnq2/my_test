package com.ts8.core.search

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.ts8.core.map.GeoBounds
import com.ts8.core.map.GeoPoint
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * SQLite/FTS4-backed offline search over a map pack's `places.db`.
 *
 * FTS4 (not FTS5) because Android 6 / API 23 does not ship FTS5. The FTS table
 * is a *candidate generator* only: FTS4's `simple` tokenizer cannot do prefix
 * ranking, so matching and scoring happen here.
 *
 * Threading: [SQLiteDatabase] is opened per instance and every call is
 * synchronous; [PlaceSearchService] owns the executor and is the only caller
 * from the UI.
 */
class SqlitePlaceSearch private constructor(
    private val db: SQLiteDatabase,
    private val packBounds: GeoBounds?
) : PlaceSearch {

    @Volatile
    private var closed = false

    override fun isReady(): Boolean = !closed && db.isOpen

    override fun search(query: String, options: SearchOptions): List<SearchResult> {
        val normQuery = VietnameseText.normalize(query)
        if (normQuery.isEmpty()) return emptyList()
        val match = VietnameseText.toFtsMatchExpression(query) ?: return emptyList()
        val fetchLimit = (options.limit * MAX_CANDIDATE_FACTOR).coerceAtLeast(MIN_CANDIDATES)

        val results = ArrayList<SearchResult>(fetchLimit)
        val sql = buildString {
            append("SELECT p.id, p.osm_type, p.osm_id, p.name, p.address, p.category, ")
            append("p.subclass, p.lat, p.lon, p.bbox_w, p.bbox_s, p.bbox_e, p.bbox_n, ")
            append("p.importance, p.name_norm ")
            // No offsets(places_fts) here. The pack builds its index with
            // `content=''`, and FTS4 forbids offsets()/snippet() on a
            // contentless table: the function needs the original text to
            // compute byte positions and there is none. On a query that matched
            // anything, SQLite raised "SQL logic error", the catch below
            // degraded to emptyList(), and the search screen printed
            // "0 kết quả" for every real place. The name column is read
            // directly, so prefix ranking needs no FTS auxiliary function.
            append("FROM places_fts JOIN places p ON p.id = places_fts.docid ")
            append("WHERE places_fts MATCH ? ")
            if (options.category != null) append("AND p.category = ? ")
            append("LIMIT ?")
        }
        val args = ArrayList<String>(3)
        args.add(match)
        if (options.category != null) args.add(options.category)
        args.add(fetchLimit.toString())

        try {
            db.rawQuery(sql, args.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    val place = readPlace(c)
                    val nameNorm = c.getString(NAME_NORM_INDEX)
                    val scored = score(place, nameNorm, normQuery, options)
                    results.add(scored)
                }
            }
        } catch (e: SQLiteException) {
            // A malformed MATCH expression must degrade to "no results", never
            // crash the driver's search box.
            return emptyList()
        }

        return results
            .filter { r ->
                options.maxDistanceMeters == null ||
                    (r.distanceMeters != null && r.distanceMeters <= options.maxDistanceMeters)
            }
            .sortedByDescending { it.score }
            .take(options.limit)
    }

    override fun suggest(query: String, limit: Int): List<Place> =
        search(query, SearchOptions(limit = limit)).map { it.place }

    override fun nearest(point: GeoPoint, maxMeters: Int): Place? {
        // A degree box around the point is a cheap prefilter; the exact
        // distance check then runs on the handful of candidates it returns.
        val dLat = maxMeters / 111_320.0
        val dLon = maxMeters / (111_320.0 * cos(Math.toRadians(point.lat)).coerceAtLeast(0.01))
        val sql = """
            SELECT p.id, p.osm_type, p.osm_id, p.name, p.address, p.category,
                   p.subclass, p.lat, p.lon, p.bbox_w, p.bbox_s, p.bbox_e, p.bbox_n,
                   p.importance, p.name_norm
            FROM places p
            WHERE p.lat BETWEEN ? AND ? AND p.lon BETWEEN ? AND ?
            LIMIT 200
        """.trimIndent()
        val args = arrayOf(
            (point.lat - dLat).toString(), (point.lat + dLat).toString(),
            (point.lon - dLon).toString(), (point.lon + dLon).toString()
        )
        var best: Place? = null
        var bestDist = Int.MAX_VALUE
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                val place = readPlace(c)
                val d = distanceMeters(point, place.point).toInt()
                if (d <= maxMeters && d < bestDist) {
                    bestDist = d
                    best = place
                }
            }
        }
        return best
    }

    /**
     * The most important named POIs, for the offline recogniser's grammar.
     *
     * **Why `category = 'poi'` and a non-empty name.** A grammar entry for a
     * residential street or an address number is a phrase nobody says as a
     * destination, and every one of them costs decoding graph the Cortex-A7 has
     * to build. The `importance` column is the map's own ranking — the same
     * number behind "which labels are worth drawing" — so what the recogniser
     * can hear is what the driver can see.
     *
     * **Why `DISTINCT` on the name.** Duplicate names are common (three
     * "Bến Thành" within a city); the grammar is a set of phrases, so a repeat
     * is bytes in the graph and a second scoring pass. They are resolved at
     * command time by the search index, which knows their coordinates.
     */
    override fun topPlaces(limit: Int): List<Place> {
        if (limit <= 0 || !isReady()) return emptyList()
        val sql = """
            SELECT p.id, p.osm_type, p.osm_id, p.name, p.address, p.category,
                   p.subclass, p.lat, p.lon, p.bbox_w, p.bbox_s, p.bbox_e,
                   p.bbox_n, p.importance, p.name_norm
            FROM places p
            WHERE p.category = 'poi'
              AND p.name IS NOT NULL AND length(trim(p.name)) > 0
              AND p.id IN (
                  SELECT MIN(id) FROM places
                  WHERE category = 'poi' AND name IS NOT NULL AND length(trim(name)) > 0
                  GROUP BY name
              )
            ORDER BY p.importance DESC, p.id ASC
            LIMIT ?
        """.trimIndent()
        return try {
            db.rawQuery(sql, arrayOf(limit.toString())).use { c ->
                val out = ArrayList<Place>(minOf(limit, 64))
                while (c.moveToNext()) out += readPlace(c)
                out
            }
        } catch (e: SQLiteException) {
            // A pack built by an older tool may lack the importance index; the
            // grammar then falls back to the fixed commands, which is a smaller
            // vocabulary rather than a broken microphone button.
            emptyList()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            db.close()
        } catch (_: Exception) {
            // Closing an already-closed handle is not an error worth surfacing.
        }
    }

    // -- scoring ------------------------------------------------------------

    private fun score(
        place: Place,
        nameNorm: String,
        normQuery: String,
        options: SearchOptions
    ): SearchResult {
        // FTS4 MATCH searched both `name_norm` and `address_norm`, so a hit can
        // come from the address. Checking the name itself is what keeps a
        // street-address hit from outranking a place whose *name* starts with
        // the query, and it works without offsets() on a contentless index.
        val prefix = VietnameseText.prefixMatch(normQuery, nameNorm)
        val distMeters = options.biasPoint?.let { distanceMeters(it, place.point).toInt() }

        var s = 0.0
        // Exact whole-name match dominates: typing the full name should put it
        // first regardless of category.
        if (nameNorm == normQuery) s += 5_000_000.0
        if (prefix) s += 1_000_000.0
        if (nameNorm.startsWith(normQuery)) s += 400_000.0
        // Popular/important features (cities, hospitals, bridges) outrank shops.
        s += place.importance * 10_000.0
        if (distMeters != null) {
            // Capped so a far-away exact match still beats a near prefix match.
            s -= distMeters.coerceAtMost(60_000) / 6.0
        }
        return SearchResult(place = place, distanceMeters = distMeters, score = s, prefixMatch = prefix)
    }

    private fun readPlace(c: Cursor): Place {
        val lat = c.getDouble(LAT_INDEX)
        val lon = c.getDouble(LON_INDEX)
        val hasBox = !c.isNull(BBOX_W_INDEX)
        val bounds = if (hasBox) GeoBounds(
            west = c.getDouble(BBOX_W_INDEX),
            south = c.getDouble(BBOX_S_INDEX),
            east = c.getDouble(BBOX_E_INDEX),
            north = c.getDouble(BBOX_N_INDEX)
        ) else null
        return Place(
            id = c.getLong(0),
            osmType = c.getString(1),
            osmId = c.getLong(2),
            name = c.getString(3) ?: "",
            address = if (c.isNull(4)) null else c.getString(4),
            category = c.getString(5) ?: "poi",
            subclass = if (c.isNull(6)) null else c.getString(6),
            point = GeoPoint(lat, lon),
            bounds = bounds,
            importance = c.getInt(13)
        )
    }

    companion object {
        private const val MIN_CANDIDATES = 200
        private const val MAX_CANDIDATE_FACTOR = 12

        private const val LAT_INDEX = 7
        private const val LON_INDEX = 8
        private const val BBOX_W_INDEX = 9
        private const val BBOX_S_INDEX = 10
        private const val BBOX_E_INDEX = 11
        private const val BBOX_N_INDEX = 12
        private const val NAME_NORM_INDEX = 14

        /** Haversine distance in metres. */
        fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val lat1 = Math.toRadians(a.lat)
            val lat2 = Math.toRadians(b.lat)
            val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.sin(dLon / 2) * Math.sin(dLon / 2) * cos(lat1) * cos(lat2)
            return 2 * r * Math.asin(sqrt(h.coerceIn(0.0, 1.0)))
        }

        /**
         * Opens the pack's search database, or returns null when the pack has
         * none. Read-only so a pack on a USB stick is never modified.
         */
        fun open(dbFile: File, packBounds: GeoBounds? = null): SqlitePlaceSearch? {
            if (!dbFile.isFile) return null
            return try {
                val db = SQLiteDatabase.openDatabase(
                    dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
                )
                SqlitePlaceSearch(db, packBounds)
            } catch (_: SQLiteException) {
                null
            }
        }
    }
}

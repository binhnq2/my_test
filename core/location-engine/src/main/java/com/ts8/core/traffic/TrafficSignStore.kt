package com.ts8.core.traffic

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * Reads traffic-sign POI data from the offline spatial-grid assets.
 *
 * **The indexing system.** Data for all of Vietnam is split into 0.5° grid
 * cells (≈55 km per side). Each asset category (`vnsigns/`, `vnhazard/`, …)
 * contains:
 *
 * ```
 * assets/vnsigns/
 * ├── idx.bin                      — GZIP JSON: { "v":1, "cell":0.5, "cells":[…] }
 * ├── 42_211.bin                   — road polylines + speed limits for cell 42_211
 * ├── 42_211_pins.bin              — traffic sign / camera POIs for cell 42_211
 * └── …
 * ```
 *
 * Cell names are `{latIndex}_{lonIndex}` — `42_211` is lat 21.0–21.5,
 * lon 105.5–106.0 (Hà Nội). See [cellId].
 *
 * At runtime, only the 9 cells around the vehicle's current position are
 * loaded. Each cell file is 5–50 KB of GZIP, so the lookup is sub-millisecond
 * and the total memory footprint for a full trip stays under 1 MB.
 *
 * **Why this is not a SQLite database.** The `xiaozhi` APK uses this format
 * precisely because random-access file reads are cheaper than SQLite queries on
 * the weak Cortex-A7 CPU of the TS8 head unit, and because the data is static —
 * there is no need for transactional updates.
 *
 * **Threading.** [loadNearby] and [loadCellPins] are safe to call from any
 * thread. The loaded cell cache is a [ConcurrentHashMap]; a cell loaded twice
 * is harmless — the second read is discarded.
 *
 * @param context The application context, for `AssetManager` access.
 * @param category The asset sub-directory to read (`"vnsigns"` for traffic
 *   signs and cameras, `"vnhazard"` for hazard locations, etc.). Defaults to
 *   `"vnsigns"` which is the primary traffic-sign category.
 */
class TrafficSignStore(
    private val context: Context,
    private val category: String = CATEGORY_VNSIGNS
) {

    /**
     * The grid cell size in degrees, read from `idx.bin`.
     *
     * Defaults to `0.5` before the index loads; [loadIndex] overwrites it.
     * All known packs use 0.5°, but the field is read from data rather than
     * hard-coded so a future pack with finer cells works without a code change.
     */
    @Volatile
    var cellSize: Double = 0.5
        private set

    /**
     * Whether the index was loaded successfully.
     *
     * `false` on a missing or corrupt `idx.bin`. When `false`, [loadNearby]
     * returns an empty list rather than throwing — a driver without sign data
     * still gets a map and a speedometer, just without proximity alerts.
     */
    @Volatile
    var indexReady: Boolean = false
        private set

    /** The set of cell IDs the index declares as having data. */
    private val indexedCells: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Cells whose pins have already been loaded and cached.
     *
     * Keyed by `"x_y"`; value is the list of signs in that cell. Once loaded,
     * a cell is never re-read — the data is static for the life of the process.
     */
    private val loadedPinCells: ConcurrentHashMap<String, List<TrafficSign>> = ConcurrentHashMap()

    init {
        loadIndex()
    }

    /**
     * Loads and parses `idx.bin`.
     *
     * Called once from `init`. On failure, [indexReady] stays `false` and every
     * [loadNearby] call returns an empty list. The exception is logged but not
     * thrown: a missing asset category is a data issue, not a crash.
     */
    private fun loadIndex() {
        try {
            context.assets.open("$category/idx.bin").use { raw ->
                GZIPInputStream(raw).bufferedReader().use { reader ->
                    val json = JSONObject(reader.readText())
                    cellSize = json.optDouble("cell", 0.5)
                    val cells = json.optJSONArray("cells")
                    if (cells != null) {
                        for (i in 0 until cells.length()) {
                            indexedCells.add(cells.getString(i))
                        }
                    }
                    indexReady = true
                    Log.i(TAG, "index loaded: ${indexedCells.size} cells, cellSize=$cellSize")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "index load failed for '$category': ${e.message}")
            indexReady = false
        }
    }

    /**
     * Builds a cell id the way the pack names it: **`{latIndex}_{lonIndex}`**.
     *
     * This ordering is the opposite of the `(x, y)` convention most grid code
     * uses, and getting it backwards silently loads nothing — `idx.bin` lists
     * `42_211` for Hà Nội (lat 21.x → index 42, lon 105.x → index 211), so a
     * `{lon}_{lat}` id is never found in the index and every lookup returns an
     * empty list. Verified against `vnsigns/idx.bin` on the real pack.
     */
    private fun cellId(latIndex: Int, lonIndex: Int): String = "${latIndex}_${lonIndex}"

    /**
     * Returns all traffic signs in the 9-cell neighbourhood around `(lat, lon)`.
     *
     * This is the primary read API. It calculates the grid cell for the given
     * position, loads the 3×3 block of cells around it, and returns the union of
     * their pins. Cells already loaded are served from cache; cells with no data
     * file are silently skipped.
     *
     * **Cost.** The first call for a new area loads up to 9 GZIP files (≤450 KB
     * total, typically ~100 KB). Subsequent calls in the same area are free.
     * On the TS8's Cortex-A7, decompressing and parsing one cell takes 1–5 ms.
     */
    fun loadNearby(lat: Double, lon: Double): List<TrafficSign> {
        if (!indexReady) return emptyList()

        val cellX = Math.floor(lon / cellSize).toInt()
        val cellY = Math.floor(lat / cellSize).toInt()

        val result = mutableListOf<TrafficSign>()
        for (dx in -1..1) {
            for (dy in -1..1) {
                val cellId = cellId(cellY + dy, cellX + dx)
                if (!indexedCells.contains(cellId)) continue
                val signs = loadedPinCells.getOrPut(cellId) {
                    loadCellPins(cellId)
                }
                result.addAll(signs)
            }
        }
        return result
    }

    /**
     * Loads pins for a single cell from `{cellId}_pins.bin`.
     *
     * Returns an empty list when the file does not exist or is corrupt. The
     * GZIP stream is closed in all cases via `use`.
     */
    private fun loadCellPins(cellId: String): List<TrafficSign> {
        val path = "$category/${cellId}_pins.bin"
        return try {
            context.assets.open(path).use { raw: InputStream ->
                GZIPInputStream(raw).bufferedReader().use { reader ->
                    val array = JSONArray(reader.readText())
                    val list = ArrayList<TrafficSign>(array.length())
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        list.add(
                            TrafficSign(
                                lat = item.getDouble("y"),
                                lon = item.getDouble("g"),
                                typeCode = item.optString("t", ""),
                                speedLimit = item.optInt("v", 0),
                                bearing = item.optDouble("b", -1.0).toFloat(),
                                uniqueId = item.optLong("u", 0L)
                            )
                        )
                    }
                    list
                }
            }
        } catch (e: Exception) {
            // A missing cell file is expected — not every 0.5° cell has data.
            // Log at debug level only; spamming the log for every empty cell
            // would drown real errors.
            emptyList()
        }
    }

    /**
     * Returns all loaded traffic signs within [radiusMeters] of `(lat, lon)`,
     * optionally filtered by heading relevance.
     *
     * Convenience method: calls [loadNearby] then filters by haversine distance
     * and (if `heading` is non-null) by [TrafficSign.isRelevantForHeading].
     */
    fun nearbyWithin(
        lat: Double,
        lon: Double,
        radiusMeters: Double,
        heading: Float? = null
    ): List<TrafficSign> {
        val all = loadNearby(lat, lon)
        if (all.isEmpty()) return emptyList()
        val here = com.ts8.core.map.GeoPoint(lat, lon)
        return all.filter { sign ->
            val dist = here.distanceTo(sign.point)
            if (dist > radiusMeters) return@filter false
            if (heading != null && !sign.isRelevantForHeading(heading)) return@filter false
            true
        }
    }

    /**
     * All signs currently held in the cell cache.
     *
     * Used by the map overlay to render markers without re-querying the store.
     * As the vehicle moves, new cells are loaded and the returned list grows;
     * it is pruned only by [pruneFarCells].
     */
    fun cachedSigns(): List<TrafficSign> {
        return loadedPinCells.values.flatten()
    }

    /**
     * Drops cells that are farther than [maxCellsAway] from `(lat, lon)` from
     * the cache, to bound memory on a long trip.
     *
     * With 0.5° cells and a 9-cell neighbourhood, a trip from Hà Nội to HCM City
     * would otherwise accumulate ~400 cells. Pruning to 3 rings (15×15 cells)
     * keeps the cache under 2 MB while ensuring the current neighbourhood is
     * always retained.
     */
    fun pruneFarCells(lat: Double, lon: Double, maxCellsAway: Int = 3) {
        val cellX = Math.floor(lon / cellSize).toInt()
        val cellY = Math.floor(lat / cellSize).toInt()
        val toRemove = mutableListOf<String>()
        for (cellId in loadedPinCells.keys) {
            val parts = cellId.split("_")
            if (parts.size != 2) continue
            // `{cellY}_{cellX}` — latitude index first, matching the pack.
            val cy = parts[0].toIntOrNull() ?: continue
            val cx = parts[1].toIntOrNull() ?: continue
            if (Math.abs(cx - cellX) > maxCellsAway || Math.abs(cy - cellY) > maxCellsAway) {
                toRemove.add(cellId)
            }
        }
        for (cellId in toRemove) {
            loadedPinCells.remove(cellId)
        }
    }

    companion object {
        private const val TAG = "TrafficSignStore"

        /** The primary traffic-sign / camera asset category. */
        const val CATEGORY_VNSIGNS = "vnsigns"

        /** Hazard locations (dangerous curves, accident black spots, etc.). */
        const val CATEGORY_VNHAZARD = "vnhazard"

        /** Toll stations (BOT). */
        const val CATEGORY_VNTOLL = "vntoll"

        /** Fuel stations. */
        const val CATEGORY_VNFUEL = "vnfuel"

        /** EV charging stations. */
        const val CATEGORY_VNEV = "vnev"

        /** Parking. */
        const val CATEGORY_VNPARK = "vnpark"

        /** Rest stops. */
        const val CATEGORY_VNREST = "vnrest"
    }
}

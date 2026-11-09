package com.ts8.core.search

import com.ts8.core.map.GeoBounds
import com.ts8.core.map.GeoPoint

/** A place record as stored in the pack's `places.db`. */
data class Place(
    val id: Long,
    val osmType: String,
    val osmId: Long,
    /** Display name with original Vietnamese diacritics. */
    val name: String,
    /** Street / ward / city string, already formatted for display. */
    val address: String?,
    /** `poi` | `street` | `place` | `address`. */
    val category: String,
    /** `restaurant`, `bridge`, `residential`, … */
    val subclass: String?,
    val point: GeoPoint,
    val bounds: GeoBounds?,
    val importance: Int
)

/** A search hit plus why it ranked where it did. */
data class SearchResult(
    val place: Place,
    val distanceMeters: Int?,
    val score: Double,
    /** True when the query matched the start of a name token. */
    val prefixMatch: Boolean
)

/** Search options that the UI can tune without touching the engine. */
data class SearchOptions(
    val limit: Int = 20,
    /** Restrict to a category (`poi`, `street`, …) or null for all. */
    val category: String? = null,
    /** Bias results toward this point; null disables proximity scoring. */
    val biasPoint: GeoPoint? = null,
    /** Hard radius filter; null means unbounded. */
    val maxDistanceMeters: Int? = null
)

/**
 * Offline place search contract.
 *
 * Implementations must never block the caller's thread: the interface is
 * synchronous only because it is always invoked from a background executor;
 * callers in UI code go through [PlaceSearchService] instead.
 */
interface PlaceSearch {
    /**
     * @throws IllegalStateException when no pack is installed.
     */
    fun search(query: String, options: SearchOptions = SearchOptions()): List<SearchResult>

    /** Reverse lookup by coordinates; null when nothing is close enough. */
    fun nearest(point: GeoPoint, maxMeters: Int = 250): Place?

    /** Incremental autocomplete; same ranking, smaller default limit. */
    fun suggest(query: String, limit: Int = 8): List<Place>

    fun isReady(): Boolean

    fun close()

    /**
     * The [limit] most important places in this pack, for a constrained
     * recogniser vocabulary.
     *
     * **Why this is not [search] with an empty query.** The offline recogniser
     * builds a decoding graph from the names it is allowed to hear; asking it to
     * cover a whole province is hundreds of thousands of phrases and a graph the
     * TS8 cannot build. This returns the handful of landmarks a driver actually
     * says out loud — ranked the same way the map ranks them for display, so the
     * vocabulary matches what is drawn on screen.
     *
     * The default returns nothing: an implementation without a ranked index is
     * still a valid search backend, and an empty list simply means the grammar
     * carries the fixed commands alone.
     */
    fun topPlaces(limit: Int): List<Place> = emptyList()
}

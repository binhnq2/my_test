package com.ts8.launcher.runtime

import android.content.Context
import com.ts8.core.map.GeoPoint
import com.ts8.core.search.Place
import org.json.JSONArray
import org.json.JSONObject

/**
 * Places the driver saved from the search screen.
 *
 * **Why this exists at all.** The design puts a "Lưu" button next to
 * "Lộ trình". A button that does nothing, or that shows a toast saying
 * "đã lưu" while writing nowhere, is exactly the fabricated success the spec
 * forbids. So the button writes to a real store, and the store is read back:
 * an empty query on the search screen lists what was saved.
 *
 * **Why SharedPreferences rather than SQLite.** This holds a handful of
 * records — a home address, work, a favourite car park. A table, a cursor and
 * a schema version for that is more code than the feature. The file is small
 * enough to parse on the main thread, which is what lets the search screen show
 * the list without a spinner.
 *
 * **Coordinates are stored as doubles, not text.** Round-tripping through a
 * formatted string loses precision at the metre level, which is the difference
 * between routing to the bridge ramp and routing to the river.
 */
class SavedPlaces(private val context: Context) {

    /** A saved place, reduced to what the search list renders. */
    data class Saved(
        val name: String,
        val address: String?,
        val point: GeoPoint,
        val savedAtMs: Long
    ) {
        /** Stable identity for de-duplication: same name at the same spot. */
        val key: String get() = "$name|${point.lat}|${point.lon}"
    }

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Saved places, newest first. Empty when nothing has been saved. */
    fun all(): List<Saved> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val parsed = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("n").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val lat = o.optDouble("la", Double.NaN)
                val lon = o.optDouble("lo", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) return@mapNotNull null
                val point = GeoPoint(lat, lon)
                if (!point.isValid()) return@mapNotNull null
                Saved(
                    name = name,
                    address = o.optString("a").takeIf { it.isNotBlank() },
                    point = point,
                    savedAtMs = o.optLong("t", 0L)
                )
            }
        }.getOrDefault(emptyList())
        return parsed.sortedByDescending { it.savedAtMs }
    }

    fun isSaved(place: Place): Boolean = contains(place.name, place.point)

    /**
     * True when a record with this name at this spot is stored.
     *
     * Name-and-point rather than a search-result id: the saved list is built
     * from records that have already been detached from the search index, and
     * the same bridge found twice from two queries must toggle off, not save
     * twice.
     */
    fun contains(name: String, point: GeoPoint): Boolean =
        all().any { it.name == name && it.point == point }

    /**
     * Saves a name at a point, or removes it if it is already there.
     *
     * Toggling rather than always adding is what makes the button's label
     * ("Lưu" / "Đã lưu") honest without a second control.
     *
     * @return true when the place is now saved.
     */
    fun toggle(
        name: String,
        address: String?,
        point: GeoPoint,
        atMs: Long = System.currentTimeMillis()
    ): Boolean {
        val current = all()
        val existing = current.firstOrNull { it.name == name && it.point == point }
        val next = if (existing != null) {
            current.filterNot { it.key == existing.key }
        } else {
            val entry = Saved(
                name = name,
                address = address,
                point = point,
                savedAtMs = atMs
            )
            // Capped so the JSON stays small and the list stays scrollable.
            (listOf(entry) + current).take(MAX_ENTRIES)
        }
        write(next)
        return existing == null
    }

    /** Convenience overload for a search hit. */
    fun toggle(place: Place, atMs: Long = System.currentTimeMillis()): Boolean =
        toggle(place.name, place.address, place.point, atMs)

    fun remove(saved: Saved) {
        write(all().filterNot { it.key == saved.key })
    }

    private fun write(entries: List<Saved>) {
        val array = JSONArray()
        for (e in entries) {
            array.put(
                JSONObject().apply {
                    put("n", e.name)
                    e.address?.let { put("a", it) }
                    put("la", e.point.lat)
                    put("lo", e.point.lon)
                    put("t", e.savedAtMs)
                }
            )
        }
        // `commit` on the main thread would be a disk write the driver waits
        // for; `apply` is queued and is what a preference write should use.
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        private const val PREFS = "ts8_saved_places"
        private const val KEY = "places_v1"

        /** Enough for home, work and a handful of regular stops. */
        private const val MAX_ENTRIES = 30
    }
}

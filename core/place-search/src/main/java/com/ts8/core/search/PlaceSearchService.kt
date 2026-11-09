package com.ts8.core.search

import android.os.Handler
import android.os.Looper
import com.ts8.core.map.GeoPoint
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * The asynchronous front of [PlaceSearch].
 *
 * [PlaceSearch] is synchronous by design — it is always called from a
 * background executor — but the search box cannot be: every keystroke would
 * run an FTS query on the main thread and the map would stall behind it. This
 * class is the only thing the UI talks to, and it owns the three rules the
 * spec states for search:
 *
 * 1. **Debounce.** A query is dispatched only after the driver stops typing.
 *    Typing `cầu rồng` one character at a time must not run nine queries.
 * 2. **Bounded results.** The limit is clamped to [MAX_RESULTS]; a caller
 *    cannot ask for the whole table.
 * 3. **Never block the UI, never deliver a stale result.** Every dispatch takes
 *    a ticket, and a result whose ticket is no longer current is dropped. This
 *    is what stops the answer for `cau` from overwriting the answer for
 *    `cau rong` when the first query happened to be slower.
 *
 * Results arrive on the main thread, which is where a RecyclerView adapter may
 * touch them.
 */
class PlaceSearchService(
    private val search: PlaceSearch,
    executor: ExecutorService? = null,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS
) : AutoCloseable {

    private val executor: ExecutorService = executor ?: Executors.newSingleThreadExecutor { r ->
        Thread(r, "ts8-search").apply { isDaemon = true }
    }

    private val ownedExecutor = executor == null
    private val main = Handler(Looper.getMainLooper())

    /** Ticket of the most recent dispatch; older answers are discarded. */
    private val ticket = AtomicInteger(0)

    /** Separate ticket for [reverse], so a tap and a search do not cancel
     *  each other's answers. */
    private val reverseTicket = AtomicInteger(0)

    private var inFlight: Future<*>? = null

    /** The scheduled debounce, held so it can be removed precisely. */
    private var debounce: Runnable? = null

    /** Set by the screen. Called on the main thread with the query it answers. */
    var onResults: ((query: String, results: List<SearchResult>) -> Unit)? = null

    /** Called when a lookup fails outright, so the screen can say so. */
    var onError: ((query: String, detail: String) -> Unit)? = null

    /** True when a lookup for a non-empty query is in flight. */
    @Volatile
    var isSearching: Boolean = false
        private set

    /**
     * Schedules a lookup once the driver stops typing.
     *
     * An empty query clears the callback instead of dispatching: "nothing
     * typed" is not the same answer as "nothing found", and the screen renders
     * them differently.
     */
    fun updateQuery(query: String, bias: GeoPoint? = null, limit: Int = MAX_RESULTS) {
        val trimmed = query.trim()
        cancelPending()
        if (trimmed.isEmpty()) {
            isSearching = false
            onResults?.invoke("", emptyList())
            return
        }
        // Typing again invalidates the previous answer immediately, so the list
        // does not sit on results for a prefix the driver has moved past.
        isSearching = true

        val myTicket = ticket.get()
        val runnable = Runnable { dispatch(trimmed, bias, limit, myTicket) }
        debounce = runnable
        main.postDelayed(runnable, debounceMs)
    }

    /** Runs a lookup now, bypassing the debounce (the IME's search key). */
    fun submit(query: String, bias: GeoPoint? = null, limit: Int = MAX_RESULTS) {
        val trimmed = query.trim()
        cancelPending()
        if (trimmed.isEmpty()) {
            isSearching = false
            onResults?.invoke("", emptyList())
            return
        }
        isSearching = true
        dispatch(trimmed, bias, limit, ticket.get())
    }

    /** Abandons any in-flight or debounced lookup without emitting a result. */
    fun clear() {
        cancelPending()
        isSearching = false
    }

    /**
     * Drops the pending debounce and invalidates whatever is running.
     *
     * The ticket is bumped here rather than at dispatch time because a running
     * SQLite query cannot be interrupted — the only reliable way to stop its
     * answer reaching the screen is to make it stale.
     */
    private fun cancelPending() {
        debounce?.let { main.removeCallbacks(it) }
        debounce = null
        ticket.incrementAndGet()
    }

    private fun dispatch(query: String, bias: GeoPoint?, limit: Int, myTicket: Int) {
        val clamped = limit.coerceIn(1, MAX_RESULTS)
        if (!search.isReady()) {
            isSearching = false
            onError?.invoke(query, "Chưa có dữ liệu tìm kiếm")
            return
        }
        inFlight = try {
            executor.submit {
                val result = runCatching {
                    search.search(
                        query,
                        SearchOptions(limit = clamped, biasPoint = bias)
                    )
                }
                main.post {
                    if (myTicket != ticket.get()) return@post
                    isSearching = false
                    val failure = result.exceptionOrNull()
                    if (failure == null) {
                        onResults?.invoke(query, result.getOrDefault(emptyList()))
                    } else {
                        onError?.invoke(query, failure.message ?: "Lỗi tìm kiếm")
                    }
                }
            }
        } catch (e: Exception) {
            isSearching = false
            onError?.invoke(query, e.message ?: "Lỗi tìm kiếm")
            null
        }
    }

    /** The nearest named place, for the map's "what is this?" tap. */
    fun reverse(point: GeoPoint, onResult: (Place?) -> Unit) {
        // A separate ticket from the search one: tapping the map must not
        // cancel a search that is about to answer, and a search must not
        // discard the tapped point's answer.
        val myTicket = reverseTicket.incrementAndGet()
        runCatching {
            executor.execute {
                val found = runCatching { search.nearest(point) }.getOrNull()
                main.post { if (myTicket == reverseTicket.get()) onResult(found) }
            }
        }
    }

    override fun close() {
        cancelPending()
        isSearching = false
        onResults = null
        onError = null
        inFlight?.cancel(false)
        inFlight = null
        if (ownedExecutor) executor.shutdownNow()
    }

    companion object {
        /**
         * 250 ms. Long enough that a normal typing burst is one query, short
         * enough that the list feels attached to the keyboard. On this CPU an
         * FTS4 prefix query over 11 000 rows measures well under this, so the
         * debounce — not the query — is the latency.
         */
        const val DEFAULT_DEBOUNCE_MS = 250L

        /** The spec's cap: "tối đa 20 kết quả". Enforced here, not by callers. */
        const val MAX_RESULTS = 20
    }
}

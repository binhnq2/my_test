package com.ts8.launcher.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ts8.core.map.GeoPoint
import com.ts8.core.search.PlaceSearchService
import com.ts8.core.search.SearchResult
import com.ts8.launcher.R
import com.ts8.launcher.di.AppGraph
import com.ts8.launcher.runtime.AppStateMachine
import com.ts8.launcher.runtime.SavedPlaces
import java.util.Locale

/**
 * Search — design state 4.
 *
 * **What is real.** The list is the pack's own FTS4 index through
 * [AppGraph.searchService], debounced at 250 ms, capped at
 * [PlaceSearchService.MAX_RESULTS]. Typing `cau r` finds `Cầu Rồng` because the
 * index stores a diacritic-free normalised column and the query is folded the
 * same way before it becomes a MATCH expression. The distance beside each hit
 * is computed against the last GPS fix, not against the pack centre.
 *
 * **Why the right panel is text and not a map.** A second `MapView` on this
 * screen would be a second GL surface and a second style load on a device that
 * can barely afford the first. Worse, the map tab's `MapView` already exists and
 * is only paused, so two live GL contexts would compete for the same GPU while
 * the driver is choosing a destination. The panel therefore shows the record —
 * name, address, category, distance, coordinates — and "Lộ trình" hands the
 * point to the real map, which recentres on it. The design's map preview is a
 * *picture of the place*; the place's own coordinates are the honest version of
 * the same information on hardware that cannot afford the picture.
 *
 * **Why results are kept on the service, not here.** The service owns the ticket
 * that makes a late answer for `cau` unable to overwrite the answer for
 * `cau rong`. A screen that ran its own queries would have to reimplement that,
 * and would get it wrong on the first device where SQLite answers out of order.
 * The callbacks are installed in [onStart] and removed in [onStop]: the service
 * outlives this screen and must not hold a dead view tree.
 *
 * **Why an empty query lists saved places.** Otherwise the screen opens blank
 * and the "Lưu" button writes into a store nobody can see. The design has no
 * favourites screen; this is where a saved place has to live.
 */
class SearchActivity : BaseScreenActivity() {

    /**
     * Search is not a tab.
     *
     * It is opened over Home or over the map, so the bar shows no selection and
     * back returns to whichever screen launched it — see [onBack].
     */
    override val screen: BottomNavView.Screen = BottomNavView.Screen.NONE

    /**
     * §16's `SEARCH` state.
     *
     * Search is not a tab but it *is* one of the ten states, and it is the one
     * the map screen has to come back to know it was left mid-lookup. Declaring
     * it here is what makes Back-from-search return to `MAP_BROWSE` rather than
     * to whatever the machine last thought.
     */
    override val primaryState: AppStateMachine.State? = AppStateMachine.State.SEARCH

    private lateinit var graph: AppGraph
    private lateinit var savedPlaces: SavedPlaces

    /** Null when the pack ships no `places.db`; the screen then says so once. */
    private var service: PlaceSearchService? = null

    private lateinit var input: EditText
    private lateinit var spinner: ProgressBar
    private lateinit var btnClear: ImageButton
    private lateinit var list: RecyclerView
    private lateinit var header: TextView
    private lateinit var emptyView: View
    private lateinit var emptyText: TextView
    private lateinit var detailHint: View
    private lateinit var detailBody: View
    private lateinit var detailCategory: TextView
    private lateinit var detailName: TextView
    private lateinit var detailAddress: TextView
    private lateinit var detailDistance: TextView
    private lateinit var detailCoords: TextView
    private lateinit var btnRoute: Button
    private lateinit var btnSave: Button

    private lateinit var adapter: HitAdapter

    /** The row the detail pane is showing, or null when nothing is selected. */
    private var selected: Hit? = null

    /** True between [onStart] and [onStop], so a late answer cannot touch views. */
    private var attached = false

    // -- lifecycle -----------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        bindNavigation()

        graph = AppGraph.require()
        savedPlaces = SavedPlaces(this)
        service = graph.searchService()

        bindViews()
        wireInput()
        wireDetail()

        adapter = HitAdapter { hit -> select(hit) }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.setHasFixedSize(true)

        if (service == null) {
            // No pack, no index. Say it once, plainly, instead of letting every
            // keystroke come back as "no results" and blaming the query.
            showMessage(getString(R.string.search_unavailable))
            input.isEnabled = false
        }

        // A query spoken to Voice Instant is already a finished search, so it
        // skips the debounce: the driver said the words, there is nothing to
        // wait out.
        readSpokenQuery(intent)?.let { spoken ->
            input.setText(spoken)
            input.setSelection(spoken.length)
        }
    }

    /**
     * Picks up a query from a launch that reuses this screen.
     *
     * `singleTop` in the manifest means Voice Instant's second "tìm …" arrives
     * here rather than at a new instance; without this override the second
     * command would raise a screen still showing the first search, which reads
     * as the command being ignored.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // The field must also be updated: `getIntent()` returns the original
        // intent, and the next `finish()`/recreate would otherwise resurrect the
        // old query.
        setIntent(intent)
        val spoken = readSpokenQuery(intent) ?: return
        input.setText(spoken)
        input.setSelection(spoken.length)
    }

    private fun readSpokenQuery(source: Intent?): String? =
        source?.getStringExtra(EXTRA_QUERY)?.trim()?.takeIf { it.isNotEmpty() }

    override fun onStart() {
        super.onStart()
        attached = true
        service?.let { svc ->
            svc.onResults = { query, results -> onResults(query, results) }
            svc.onError = { _, detail -> onSearchFailed(detail) }
        }
        // Coming back to a screen that still has its query must show that
        // query's list again; the service was cleared in onStop.
        if (input.text.isNullOrBlank()) {
            showSaved()
        } else {
            runQuery(input.text.toString(), immediate = true)
        }
    }

    override fun onStop() {
        attached = false
        service?.let {
            it.onResults = null
            it.onError = null
            it.clear()
        }
        super.onStop()
    }

    /**
     * Back returns to the screen that opened search.
     *
     * Not [BottomNavView.Screen.HOME] unconditionally: search is reachable from
     * the map, and a driver who opened it from the map expects the map back —
     * not a jump to Home that loses the route they were previewing.
     */
    override fun onBack() {
        finish()
    }

    // -- views ---------------------------------------------------------------

    private fun bindViews() {
        input = findViewById(R.id.searchInput)
        spinner = findViewById(R.id.searchSpinner)
        btnClear = findViewById(R.id.btnClear)
        list = findViewById(R.id.resultsList)
        header = findViewById(R.id.resultsHeader)
        emptyView = findViewById(R.id.resultsEmpty)
        emptyText = findViewById(R.id.resultsEmptyText)
        detailHint = findViewById(R.id.detailHint)
        detailBody = findViewById(R.id.detailBody)
        detailCategory = findViewById(R.id.detailCategory)
        detailName = findViewById(R.id.detailName)
        detailAddress = findViewById(R.id.detailAddress)
        detailDistance = findViewById(R.id.detailDistance)
        detailCoords = findViewById(R.id.detailCoords)
        btnRoute = findViewById(R.id.btnRoute)
        btnSave = findViewById(R.id.btnSave)

        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }
        // Voice Instant from the field. Wired here rather than in the layout
        // because starting a session needs the permission dance, which the base
        // class owns; a layout `android:onClick` would have to reinvent it.
        findViewById<ImageButton>(R.id.btnVoice).setOnClickListener { startVoice() }
    }

    private fun wireInput() {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                btnClear.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                if (text.isBlank()) {
                    service?.clear()
                    spinner.visibility = View.GONE
                    showSaved()
                } else {
                    runQuery(text, immediate = false)
                }
            }
        })
        btnClear.setOnClickListener {
            // Clearing the text fires the watcher, which is what returns the
            // list to the saved places; doing it here as well would run twice.
            input.setText("")
        }
        // The IME's search key skips the debounce: the driver has said the
        // query is finished, so waiting another 250 ms is only latency.
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runQuery(input.text.toString(), immediate = true)
                true
            } else {
                false
            }
        }
        // Focus the field on open: search is the only thing this screen can do,
        // so the keyboard appearing is not stealing focus from anything.
        input.requestFocus()
    }

    private fun wireDetail() {
        btnRoute.setOnClickListener {
            val hit = selected ?: return@setOnClickListener
            startActivity(MapActivity.intentTo(this, hit.point, hit.name))
            // The map is the destination of this handoff; leaving search on the
            // back stack would put it between the map and Home, and back from
            // the map would land on a stale result list.
            finish()
        }
        btnSave.setOnClickListener {
            val hit = selected ?: return@setOnClickListener
            val nowSaved = savedPlaces.toggle(hit.name, hit.address, hit.point)
            btnSave.setText(if (nowSaved) R.string.search_saved else R.string.search_save)
            // A saved place appearing or disappearing is a change to the list
            // the driver is looking at when the query is empty.
            if (input.text.isNullOrBlank()) showSaved()
        }
    }

    // -- searching -----------------------------------------------------------

    /**
     * Dispatches a query.
     *
     * @param immediate skip the debounce — used by the IME key and by [onStart]
     *        re-running a restored query, where there is no typing to wait out.
     */
    private fun runQuery(raw: String, immediate: Boolean) {
        val svc = service ?: return
        val query = raw.trim()
        if (query.isEmpty()) {
            showSaved()
            return
        }
        // Bias by the car's position so "cầu" ranks the bridge the driver is
        // approaching ahead of the one 400 km away. Null when there is no fix,
        // which the ranking treats as "no proximity evidence" rather than as a
        // coordinate at (0, 0).
        val bias = graph.location.lastFix?.point
        if (immediate) svc.submit(query, bias) else svc.updateQuery(query, bias)
        spinner.visibility = if (svc.isSearching) View.VISIBLE else View.GONE
    }

    private fun onResults(query: String, results: List<SearchResult>) {
        if (!attached) return
        spinner.visibility = View.GONE
        // The service already drops answers whose ticket is stale; this second
        // check covers the one gap it cannot see — a query that was cleared
        // after its answer was already posted to the main thread.
        val typed = input.text?.toString()?.trim().orEmpty()
        if (query.isNotEmpty() && !typed.equals(query, ignoreCase = true)) return
        if (query.isEmpty()) {
            showSaved()
            return
        }
        val hits = results.mapNotNull { toHit(it) }
        render(hits, getString(R.string.search_result_count, hits.size))
        if (hits.isEmpty()) showMessage(getString(R.string.search_no_results))
    }

    private fun onSearchFailed(detail: String) {
        if (!attached) return
        spinner.visibility = View.GONE
        // The engine's message is already Vietnamese and already specific
        // ("Chưa có dữ liệu tìm kiếm"); replacing it with a generic failure
        // would lose the one part that tells the driver what to fix.
        showMessage(detail)
    }

    /** Lists what the driver has saved, for the empty query state. */
    private fun showSaved() {
        val saved = savedPlaces.all()
        val fix = graph.location.lastFix?.point
        val hits = saved.map { s ->
            Hit(
                name = s.name,
                address = s.address,
                point = s.point,
                category = null,
                distanceMeters = fix?.let { s.point.distanceTo(it) },
                saved = true
            )
        }
        if (hits.isEmpty()) {
            header.visibility = View.GONE
            showMessage(getString(R.string.search_no_saved))
        } else {
            // Selection does not survive a list that was rebuilt around it:
            // the row the pane showed may not be in the new list.
            clearSelection()
            render(hits, getString(R.string.search_saved_label))
        }
    }

    private fun toHit(result: SearchResult): Hit? {
        val place = result.place
        if (!place.point.isValid()) return null
        val distance = result.distanceMeters?.toDouble()
            ?: graph.location.lastFix?.point?.let { place.point.distanceTo(it) }
        return Hit(
            name = place.name.ifBlank { place.address ?: "—" },
            address = place.address,
            point = place.point,
            category = place.category,
            distanceMeters = distance,
            saved = savedPlaces.contains(place.name, place.point)
        )
    }

    private fun render(hits: List<Hit>, headerText: String) {
        header.text = headerText
        header.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        list.visibility = View.VISIBLE
        adapter.submit(hits)
        // A single hit is almost always the one the driver meant; selecting it
        // saves a tap and, more importantly, makes the "Lộ trình" button reachable
        // without aiming at a row while the car is moving.
        if (hits.size == 1 && selected == null) select(hits.first())
    }

    private fun showMessage(message: String) {
        list.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
        emptyText.text = message
        adapter.submit(emptyList())
        clearSelection()
    }

    // -- selection -----------------------------------------------------------

    private fun select(hit: Hit) {
        selected = hit
        detailHint.visibility = View.GONE
        detailBody.visibility = View.VISIBLE

        // The keyboard is only needed while typing. On a landscape head unit it
        // covers the lower half of the detail pane — including `Lộ trình` — so
        // leaving it up after a tap made the search-to-route handoff impossible
        // to reach: the tap that was meant for the button landed on a key and
        // appended a letter to the query instead. Hide it as soon as a result
        // is chosen; tapping the field brings it straight back.
        hideKeyboard()

        val category = hit.category
        if (category == null) {
            detailCategory.visibility = View.GONE
        } else {
            detailCategory.setText(categoryLabel(category))
            detailCategory.visibility = View.VISIBLE
        }
        detailName.text = hit.name
        if (hit.address.isNullOrBlank()) {
            detailAddress.visibility = View.GONE
        } else {
            detailAddress.text = hit.address
            detailAddress.visibility = View.VISIBLE
        }
        val distance = hit.distanceMeters
        if (distance == null) {
            detailDistance.visibility = View.GONE
        } else {
            detailDistance.text = getString(
                R.string.search_distance_away,
                ClockFormat.distance(distance)
            )
            detailDistance.visibility = View.VISIBLE
        }
        // Five decimals is ~1 m at this latitude: enough to distinguish the
        // two ends of a bridge, short enough to read out loud.
        detailCoords.text = String.format(
            Locale.ROOT,
            "%.5f, %.5f",
            hit.point.lat,
            hit.point.lon
        )
        btnSave.setText(if (hit.saved) R.string.search_saved else R.string.search_save)
    }

    private fun clearSelection() {
        selected = null
        detailHint.visibility = View.VISIBLE
        detailBody.visibility = View.GONE
    }

    private fun hideKeyboard() {
        input.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    /**
     * Human label for an index category.
     *
     * The four categories are the ones `build_places_db.py` writes. An unknown
     * one is shown as the place's address instead of leaking a database token
     * (`poi`) to the driver.
     */
    private fun categoryLabel(category: String): String = when (category) {
        "poi" -> getString(R.string.search_cat_poi)
        "street" -> getString(R.string.search_cat_street)
        "place" -> getString(R.string.search_cat_place)
        "address" -> getString(R.string.search_cat_address)
        else -> category
    }

    // -- model ---------------------------------------------------------------

    /**
     * One list row, whether it came from the index or from the saved store.
     *
     * A single shape for both is what lets the same adapter and the same detail
     * pane serve a search hit and a saved place; `category` is null for saved
     * records because [SavedPlaces] does not keep one, and the chip simply hides.
     */
    private data class Hit(
        val name: String,
        val address: String?,
        val point: GeoPoint,
        val category: String?,
        val distanceMeters: Double?,
        val saved: Boolean
    )

    // -- adapter -------------------------------------------------------------

    private class HitAdapter(
        private val onClick: (Hit) -> Unit
    ) : RecyclerView.Adapter<HitHolder>() {

        private var items: List<Hit> = emptyList()

        fun submit(next: List<Hit>) {
            items = next
            // The list changes wholesale on a keystroke, and it is at most 20
            // rows; a diff would compare every pair to save rebinding a handful.
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HitHolder =
            HitHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.view_search_row, parent, false)
            )

        override fun onBindViewHolder(holder: HitHolder, position: Int) {
            val hit = items[position]
            holder.bind(hit)
            holder.itemView.setOnClickListener { onClick(hit) }
        }
    }

    private class HitHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.resultName)
        private val address: TextView = view.findViewById(R.id.resultAddress)
        private val distance: TextView = view.findViewById(R.id.resultDistance)

        fun bind(hit: Hit) {
            name.text = hit.name
            if (hit.address.isNullOrBlank()) {
                address.visibility = View.GONE
            } else {
                address.text = hit.address
                address.visibility = View.VISIBLE
            }
            val d = hit.distanceMeters
            if (d == null) {
                distance.visibility = View.GONE
            } else {
                distance.text = ClockFormat.distance(d)
                distance.visibility = View.VISIBLE
            }
        }
    }

    companion object {
        /**
         * Query handed over by Voice Instant's `search_place` command.
         *
         * A string extra rather than a shared global: the voice executor starts
         * this screen with `startActivity`, and an extra is the only channel that
         * survives the launch surviving to `onNewIntent` on a screen that was
         * already open (`singleTop` in the manifest).
         */
        const val EXTRA_QUERY = "com.ts8.launcher.extra.SEARCH_QUERY"
    }
}

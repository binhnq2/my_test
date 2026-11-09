package com.ts8.launcher.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ts8.launcher.R

/**
 * Apps — design state 5, the app drawer.
 *
 * **Every tile is a real installed app.** The list comes from
 * [AppCatalog], which resolves MAIN/LAUNCHER activities through
 * PackageManager; nothing is hard-coded, so the drawer on a firmware without a
 * Radio shows no Radio, and on one with vendor apps shows them. A tile that
 * opens nothing is the failure the spec names, so a tap that the system refuses
 * reports it rather than doing nothing.
 *
 * **Failures are answered, not silenced.** A launch that throws — a package the
 * firmware restricted, an activity that was disabled after the list was read —
 * shows a toast naming the failure. Launching is the one action here that can
 * legitimately be refused by the platform, and the driver deserves to know
 * their tap was received.
 */
class AppDrawerActivity : BaseScreenActivity() {

    override val screen: BottomNavView.Screen = BottomNavView.Screen.APPS

    private lateinit var catalog: AppCatalog
    private lateinit var grid: RecyclerView
    private lateinit var emptyView: View
    private lateinit var emptyText: TextView
    private lateinit var adapter: AppGridAdapter

    private val main = Handler(Looper.getMainLooper())

    /** Everything installed, before the filter. */
    private var allApps: List<AppCatalog.AppEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_drawer)
        bindNavigation()

        catalog = AppCatalog(this)
        grid = findViewById(R.id.appsGrid)
        emptyView = findViewById(R.id.appsEmpty)
        emptyText = findViewById(R.id.appsEmptyText)

        adapter = AppGridAdapter(catalog) { entry -> open(entry) }
        grid.layoutManager = GridLayoutManager(this, spanCount())
        grid.adapter = adapter
        // Tiles have a fixed height, so the grid skips a measure per scroll.
        grid.setHasFixedSize(true)

        val search = findViewById<EditText>(R.id.appsSearch)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })

        catalog.load { apps ->
            allApps = apps
            applyFilter("")
        }
    }

    override fun onDestroy() {
        catalog.shutdown()
        super.onDestroy()
    }

    /**
     * Columns from the available width rather than a fixed count.
     *
     * The acceptance criterion is "at least 1024×600"; a fixed 6-column grid
     * that is right at 1024 turns into fat tiles on a wider panel, and one that
     * is right at 1280 leaves 5 columns of dead space at 1024. Dividing by a
     * target tile width keeps the density constant at both.
     */
    private fun spanCount(): Int {
        val widthDp = resources.configuration.screenWidthDp
        if (widthDp <= 0) return DEFAULT_COLUMNS
        val columns = widthDp / TARGET_TILE_DP
        return columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
    }

    /**
     * Filters on the folded label.
     *
     * Folding both sides is what makes typing `camera` find `Camera` and typing
     * `dien thoai` find `Điện thoại` — the driver's keyboard on this unit may
     * not offer Vietnamese input at all, so a diacritic-sensitive match would
     * make half the list unreachable.
     */
    private fun applyFilter(rawQuery: String) {
        val query = VietnameseText.fold(rawQuery.trim())
        val filtered = if (query.isEmpty()) {
            allApps
        } else {
            allApps.filter { entry ->
                entry.sortKey.contains(query) ||
                    VietnameseText.fold(entry.packageName).contains(query)
            }
        }
        adapter.submit(filtered)

        if (filtered.isEmpty()) {
            grid.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
            // "No results" and "no apps at all" are different problems, and the
            // driver can act on only one of them.
            emptyText.setText(
                if (allApps.isEmpty()) R.string.apps_empty else R.string.search_no_results
            )
        } else {
            grid.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
        }
    }

    /**
     * Opens an app.
     *
     * The explicit component intent is built by [AppCatalog]; it is wrapped
     * because a firmware can refuse it after the fact (a disabled activity, a
     * package being updated). A `SecurityException` here means the system will
     * not let this launcher start that activity, which is a real answer the
     * driver should see rather than a dead tap.
     */
    private fun open(entry: AppCatalog.AppEntry) {
        val result = runCatching { startActivity(catalog.intentFor(entry)) }
        result.onFailure { error ->
            val message = when (error) {
                is SecurityException -> getString(R.string.apps_permission_denied)
                else -> getString(R.string.apps_open_failed)
            }
            Toast.makeText(this, "$message\n${entry.label}", Toast.LENGTH_SHORT).show()
        }
    }

    // -- adapter -------------------------------------------------------------

    private inner class AppGridAdapter(
        private val catalog: AppCatalog,
        private val onClick: (AppCatalog.AppEntry) -> Unit
    ) : RecyclerView.Adapter<AppGridViewHolder>() {

        private var items: List<AppCatalog.AppEntry> = emptyList()

        fun submit(next: List<AppCatalog.AppEntry>) {
            items = next
            // The list changes wholesale on a filter keystroke; a full rebind is
            // cheaper than a diff over dozens of unchanged tiles and cannot be
            // wrong about which tile moved.
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppGridViewHolder =
            AppGridViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.view_app_tile, parent, false)
            )

        override fun onBindViewHolder(holder: AppGridViewHolder, position: Int) {
            val entry = items[position]
            holder.bind(entry, catalog)
            holder.itemView.setOnClickListener { onClick(entry) }
        }
    }

    private class AppGridViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.appIcon)
        private val label: TextView = view.findViewById(R.id.appLabel)

        fun bind(entry: AppCatalog.AppEntry, catalog: AppCatalog) {
            label.text = entry.label
            val drawable = catalog.iconFor(entry)
            if (drawable == null) {
                // A package that gives no icon still gets a tile: a blank square
                // is the honest rendering, and the label still identifies it.
                icon.setImageDrawable(null)
                icon.setBackgroundResource(R.drawable.bg_card_raised)
            } else {
                icon.setBackground(null)
                icon.setImageDrawable(drawable)
            }
        }
    }

    companion object {
        /** Roughly the width the design gives one tile, including its gaps. */
        private const val TARGET_TILE_DP = 150
        private const val MIN_COLUMNS = 4
        private const val MAX_COLUMNS = 8
        private const val DEFAULT_COLUMNS = 6
    }
}

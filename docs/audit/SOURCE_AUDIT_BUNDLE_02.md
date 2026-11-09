# Source audit bundle 02

Excerpted verbatim from the working tree. Every section is
`## <relative path>` followed by `BEGIN FILE` / `END FILE` with the
file's exact contents between them. Nothing is summarised, elided or
reformatted.

Files in this bundle: 125

---

## app/src/main/java/com/ts8/launcher/ui/MusicRowAdapter.kt

BEGIN FILE
package com.ts8.launcher.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.ts8.core.music.MusicCollection
import com.ts8.core.music.Playlist
import com.ts8.core.music.Track
import com.ts8.launcher.R

/**
 * One list for every music tab.
 *
 * **Why one adapter and not four.** The playlist tab lists tracks, the songs
 * tab lists tracks, the albums and artists tabs list collections. Those are two
 * shapes, not four, and a second adapter would duplicate the view holder, the
 * diffing and the artwork lookup for no gain. The row model below is the union:
 * a title, a subtitle, an optional album id for the cover, an optional chevron,
 * and the payload the row plays or opens.
 *
 * **Why the payload is kept opaque.** The adapter does not know what a track id
 * means to the player; the screen supplies a callback that receives the row it
 * rendered. That keeps the adapter free of `MusicLibrary` calls, which are
 * SQLite reads that must not happen during a scroll.
 */
class MusicRowAdapter(
    private val artLoader: AlbumArtLoader
) : RecyclerView.Adapter<MusicRowAdapter.RowHolder>() {

    /** What a row shows, independent of where it came from. */
    data class Row(
        /** Stable key for diffing; unique within one list. */
        val key: String,
        val title: String,
        val subtitle: String?,
        /** Album id for a real cover, null when the row has none. */
        val albumId: Long?,
        /** True when tapping opens a collection instead of playing. */
        val opensCollection: Boolean,
        /** Set on the row that is playing right now. */
        val playing: Boolean = false,
        /** The track this row plays; null for a collection row. */
        val track: Track? = null,
        /** The collection this row opens; null for a track row. */
        val collection: MusicCollection? = null,
        /**
         * A row that is a group heading rather than something to play.
         *
         * §13 requires the list to separate `Trên thiết bị` from `Trực tuyến`.
         * A heading row rather than two RecyclerViews: the two groups scroll as
         * one list, which is what keeps a search result readable on a 600 px
         * panel, and the adapter already knows how to draw a row.
         */
        val header: Boolean = false,
        /** Resolved artwork URL for an online row; null for a local one. */
        val artworkUrl: String? = null
    )

    private var rows: List<Row> = emptyList()

    /** Fired with the index and the row. The screen decides what to do. */
    var onRowClick: ((Row) -> Unit)? = null

    fun submit(next: List<Row>) {
        val old = rows
        rows = next
        // A full rebind would drop every decoded cover and re-request it; the
        // diff keeps untouched rows and their bitmaps in place.
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = next.size
            override fun areItemsTheSame(a: Int, b: Int): Boolean = old[a].key == next[b].key
            override fun areContentsTheSame(a: Int, b: Int): Boolean = old[a] == next[b]
        }).dispatchUpdatesTo(this)
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder =
        RowHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.view_music_row, parent, false),
            artLoader
        )

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        val row = rows[position]
        holder.bind(row)
        holder.itemView.setOnClickListener { onRowClick?.invoke(row) }
    }

    class RowHolder(
        view: View,
        private val artLoader: AlbumArtLoader
    ) : RecyclerView.ViewHolder(view) {

        private val artwork: ImageView = view.findViewById(R.id.rowArtwork)
        private val icon: ImageView = view.findViewById(R.id.rowIcon)
        private val title: TextView = view.findViewById(R.id.rowTitle)
        private val subtitle: TextView = view.findViewById(R.id.rowSubtitle)
        private val chevron: ImageView = view.findViewById(R.id.rowChevron)
        private val playingDot: View = view.findViewById(R.id.rowPlayingDot)

        /** Which row the current decode belongs to, so a recycled holder that
         *  scrolls away does not paint the previous album's cover. */
        private var boundKey: String? = null

        fun bind(row: Row) {
            boundKey = row.key
            title.text = row.title
            if (row.subtitle.isNullOrBlank()) {
                subtitle.visibility = View.GONE
            } else {
                subtitle.text = row.subtitle
                subtitle.visibility = View.VISIBLE
            }
            chevron.visibility = if (row.opensCollection) View.VISIBLE else View.GONE
            playingDot.visibility = if (row.playing) View.VISIBLE else View.GONE

            if (row.header) {
                // A heading is not a place: a cover, a dot and a chevron would
                // all be wrong on it. The row is styled by the group label
                // itself so it reads as a divider, not as a broken item.
                artwork.setImageDrawable(null)
                artwork.visibility = View.GONE
                icon.visibility = View.GONE
                itemView.isClickable = false
                title.alpha = 0.7f
                return
            }
            itemView.isClickable = true
            title.alpha = 1f

            // An online row may carry a cover URL; the album-art loader only
            // knows MediaStore ids, so an online row keeps the placeholder tile
            // rather than showing a local file's cover under a station's name.
            if (row.artworkUrl != null) {
                artwork.setImageDrawable(null)
                artwork.visibility = View.GONE
                icon.visibility = View.VISIBLE
                return
            }
            val cached = artLoader.load(row.albumId) { albumId, bitmap ->
                // Stale guard: the holder may have been reused for another row
                // by the time the decode finishes.
                if (boundKey == row.key && row.albumId == albumId) {
                    showArtwork(bitmap)
                }
            }
            if (cached != null) showArtwork(cached) else showPlaceholder()
        }

        private fun showArtwork(bitmap: android.graphics.Bitmap) {
            artwork.setImageBitmap(bitmap)
            artwork.visibility = View.VISIBLE
            icon.visibility = View.GONE
        }

        private fun showPlaceholder() {
            artwork.setImageDrawable(null)
            artwork.visibility = View.GONE
            icon.visibility = View.VISIBLE
        }
    }

    companion object {
        /** Builds the row for a track. */
        fun trackRow(track: Track, playing: Boolean): Row = Row(
            key = "t:${track.id}",
            title = track.title.ifBlank { "—" },
            subtitle = track.subtitle.ifBlank { null },
            albumId = track.albumId,
            opensCollection = false,
            playing = playing,
            track = track
        )

        /** Builds the row for an album, artist or folder. */
        fun collectionRow(collection: MusicCollection, trackCountLabel: (Int) -> String): Row = Row(
            key = "c:${collection.kind}:${collection.id}",
            title = collection.name.ifBlank { "—" },
            subtitle = trackCountLabel(collection.trackCount),
            // Only albums carry artwork; artists and folders do not.
            albumId = if (collection.kind == MusicCollection.Kind.ALBUM) collection.id else null,
            opensCollection = true,
            collection = collection
        )

        /** Builds the row for a playlist. */
        fun playlistRow(playlist: Playlist, trackCountLabel: (Int) -> String): Row = Row(
            key = "p:${playlist.id}",
            title = playlist.name.ifBlank { "—" },
            subtitle = trackCountLabel(playlist.trackCount),
            albumId = null,
            opensCollection = true
        )

        /**
         * A group heading.
         *
         * The key is prefixed `h:` so it can never collide with a track (`t:`),
         * a collection (`c:`) or a playlist (`p:`) key in the same list — the
         * diff in [submit] compares keys, and two rows sharing one would make
         * `DiffUtil` move the wrong row.
         */
        fun headerRow(label: String): Row = Row(
            key = "h:$label",
            title = label,
            subtitle = null,
            albumId = null,
            opensCollection = false,
            header = true
        )

        /**
         * The row for a search result from an online provider.
         *
         * The provider's display name is carried in the subtitle so a driver
         * sees which catalogue a station came from without a second chip
         * competing with the row's own text — and so a podcast episode and a
         * radio station with the same name are distinguishable.
         *
         * No [Track] is attached yet: turning a result into a playable track
         * costs a network round trip, which must not happen during a bind. The
         * screen resolves it on tap.
         */
        fun onlineRow(online: com.ts8.core.music.online.OnlineTrack, providerLabel: String): Row = Row(
            key = "o:${online.providerId}:${online.id}",
            title = online.title.ifBlank { "—" },
            subtitle = listOfNotNull(
                online.subtitle.takeIf { it.isNotBlank() },
                providerLabel
            ).joinToString(" · ").ifBlank { null },
            albumId = null,
            opensCollection = false,
            artworkUrl = online.artworkUrl
        )
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/ui/ParkingModeActivity.kt

BEGIN FILE
package com.ts8.launcher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.ts8.core.parking.ParkingDecision
import com.ts8.core.parking.ParkingState
import com.ts8.launcher.R
import com.ts8.launcher.di.AppGraph
import com.ts8.launcher.runtime.AppStateMachine

/**
 * Parking mode — design state 7.
 *
 * **A clock and nothing else.** The screen exists for the two minutes a driver
 * spends stationary before leaving the car: a large time, the date, and the same
 * navigation bar so nothing about the launcher looks different. It shows no map,
 * no speed, no notifications — anything that needs refreshing would keep the
 * panel awake for information nobody is looking at.
 *
 * **Why the clock updates from `ACTION_TIME_TICK`.** A `Handler` posting every
 * second would wake the CPU sixty times a minute to redraw a minute display;
 * `ACTION_TIME_TICK` fires once a minute and is exactly this screen's resolution.
 * It also follows a manual clock change and a timezone change for free, which a
 * handler would have to be told about.
 *
 * **Why a tap exits.** Back is already handled by the base class (it goes Home),
 * but a driver who has turned back to the car is not aiming at a bar item. The
 * whole pane finishes the screen, which is the only thing the design offers.
 *
 * **Why it holds the parking detector open.** `ParkingRuntime` is reference
 * counted by visible screens. Without this screen joining it, the count reaches
 * zero the moment Home stops, the dwell window is discarded, and a car that
 * quietly drives off while this screen is up would never be noticed — the driver
 * would be left staring at a clock over a moving car. Holding it also lets the
 * screen leave by itself, which is what the resolver's own exit rule is for.
 */
class ParkingModeActivity : BaseScreenActivity() {

    /** Not a tab: reached from Quick Settings, dismissed back to Home. */
    override val screen: BottomNavView.Screen = BottomNavView.Screen.NONE

    /** §16's `PARKING` state; see [BaseScreenActivity.primaryState]. */
    override val primaryState: AppStateMachine.State? = AppStateMachine.State.PARKING

    private lateinit var clock: TextView
    private lateinit var date: TextView

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            refreshClock()
        }
    }

    /**
     * Leaves the screen when the car is demonstrably moving again.
     *
     * Only [ParkingState.DRIVING] dismisses, and only after the resolver's own
     * six-second sustained-movement rule has already said so — so a single
     * GPS spike, a fix lost under a roof, or the driver's own tap do not race
     * this into firing. A restored `PARKED` from the last boot does not, either:
     * the resolver will re-evaluate it on live signals first.
     */
    private val parkingListener = object : com.ts8.launcher.runtime.ParkingRuntime.Listener {
        override fun onParkingDecision(decision: ParkingDecision) {
            if (decision.state == ParkingState.DRIVING) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parking_mode)
        bindNavigation()

        clock = findViewById(R.id.parkingClock)
        date = findViewById(R.id.parkingDate)

        val exit = View.OnClickListener { leaveByHand() }
        findViewById<View>(R.id.parkingPane).setOnClickListener(exit)
        findViewById<View>(R.id.parkingExitHint).setOnClickListener(exit)

        refreshClock()
    }

    override fun onStart() {
        super.onStart()
        AppGraph.require().parking.acquire(parkingListener)
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        runCatching { registerReceiver(receiver, filter) }
            .onSuccess { registered = true }
        refreshClock()
    }

    override fun onStop() {
        AppGraph.require().parking.release(parkingListener)
        if (registered) {
            runCatching { unregisterReceiver(receiver) }
            registered = false
        }
        super.onStop()
    }

    /**
     * The driver said "back to the map", not "I have un-parked".
     *
     * The resolver latches this as a manual exit until the car has actually
     * driven away, which is what stops the heuristic from putting the same
     * screen straight back in front of a driver who is still standing in a
     * space. Without the latch the two-minute dwell would re-open it the next
     * tick and the tap would read as broken.
     */
    private fun leaveByHand() {
        runCatching { AppGraph.require().parking.requestExit() }
        finish()
    }

    private fun refreshClock() {
        clock.text = ClockFormat.time()
        date.text = ClockFormat.date()
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/ui/QsTileView.kt

BEGIN FILE
package com.ts8.launcher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ts8.launcher.R

/**
 * One quick-settings tile: an icon, a label, and the platform's current state.
 *
 * **Why a custom view rather than four copies in the layout.** The tile's job is
 * to keep three things consistent — the icon tint, the value text, and whether
 * the control is offered at all — and a copy per tile is how one of them ends up
 * showing "Bật" while its radio is off. The view exposes exactly one binding
 * call, [bind], so a screen cannot update two of the three.
 *
 * **The value is never an XML default.** It starts blank and stays blank until
 * the screen has read the real state. A tile that says "Bật" before anything has
 * been queried is the fabricated status the spec forbids, and on this unit a
 * firmware that omits a service would keep saying it forever.
 */
class QsTileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val icon: ImageView
    private val label: TextView
    private val value: TextView

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_qs_tile, this, true)

        icon = findViewById(R.id.tileIcon)
        label = findViewById(R.id.tileLabel)
        value = findViewById(R.id.tileValue)
        // The merge inflates into this view, so the row's own padding and
        // background come from the style; nothing here overrides them.

        // `recycle()`, not `use {}`: `TypedArray.close()` only exists from
        // API 31, and this APK runs on 23.
        val a = context.obtainStyledAttributes(attrs, R.styleable.QsTileView)
        try {
            val iconRes = a.getResourceId(R.styleable.QsTileView_qsIcon, 0)
            if (iconRes != 0) icon.setImageResource(iconRes)
            val labelRes = a.getResourceId(R.styleable.QsTileView_qsLabel, 0)
            if (labelRes != 0) label.setText(labelRes)
        } finally {
            a.recycle()
        }
    }

    /**
     * Shows the current state.
     *
     * @param state one of [State]. The tile is what decides how a state looks;
     *        the screen never sets a colour or an alpha directly, so a
     *        `UNAVAILABLE` tile cannot accidentally be drawn as `ON`.
     */
    fun bind(state: State, valueText: String? = null) {
        when (state) {
            State.ON -> {
                icon.setColorFilter(context.getColor(R.color.accent))
                value.setTextColor(context.getColor(R.color.accent_text))
                value.text = valueText ?: context.getString(R.string.qs_on)
                alpha = 1f
                isEnabled = true
            }
            State.OFF -> {
                icon.setColorFilter(context.getColor(R.color.text_secondary))
                value.setTextColor(context.getColor(R.color.text_tertiary))
                value.text = valueText ?: context.getString(R.string.qs_off)
                alpha = 1f
                isEnabled = true
            }
            State.UNAVAILABLE -> {
                // Dimmed *and* not clickable: the platform cannot do this, so
                // offering a tappable tile would be a dead control.
                icon.setColorFilter(context.getColor(R.color.text_tertiary))
                value.setTextColor(context.getColor(R.color.text_tertiary))
                value.text = valueText ?: context.getString(R.string.qs_unavailable)
                alpha = 0.45f
                isEnabled = false
            }
            State.NEEDS_PERMISSION -> {
                // Offered, and tappable, because tapping asks for the grant.
                icon.setColorFilter(context.getColor(R.color.text_tertiary))
                value.setTextColor(context.getColor(R.color.text_tertiary))
                value.text = valueText ?: context.getString(R.string.qs_permission_required)
                alpha = 1f
                isEnabled = true
            }
        }
    }

    /** What a tile is showing. */
    enum class State { ON, OFF, UNAVAILABLE, NEEDS_PERMISSION }
}

END FILE

## app/src/main/java/com/ts8/launcher/ui/QuickSettingsActivity.kt

BEGIN FILE
package com.ts8.launcher.ui

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.ts8.core.map.MapStyleMode
import com.ts8.launcher.R
import com.ts8.launcher.di.AppGraph
import com.ts8.launcher.runtime.AppSettings
import com.ts8.launcher.runtime.AppStateMachine

/**
 * Quick settings — design state 6.
 *
 * **The rule this screen is built around:** every control acts on Android 6 with
 * only the permission a normal app can hold, or it is visibly unavailable. There
 * is no switch that flicks back silently, and no setting written to a preference
 * when the platform refused to change.
 *
 * | control       | API used                                  | on this unit |
 * |---------------|-------------------------------------------|--------------|
 * | Wi-Fi         | `WifiManager.setWifiEnabled` (deprecated, still present on 23) | works |
 * | Bluetooth     | `BluetoothAdapter.enable`/`disable`       | works |
 * | Mobile data   | **none** — `MODIFY_PHONE_STATE` is a signature permission | **unavailable**, shown as such |
 * | Day/night     | a launcher preference, applied to the map  | works |
 * | Brightness    | `Settings.System.SCREEN_BRIGHTNESS`, needs `WRITE_SETTINGS` | works after the grant |
 * | Volume        | `VehicleAudioBridge.setMediaVolume`       | works |
 * | Screen off    | a black full-screen activity, **not** `PowerManager.goToSleep` | works |
 * | Restart       | `PowerManager.reboot` is `REBOOT` (system-only) | **unavailable**, replaced by the app restart |
 * | Settings      | the platform settings app, with a fallback  | works |
 *
 * The two unavailable rows are the spec's "không giả báo thành công" made
 * concrete: mobile data toggling and device reboot are genuinely not available
 * to third-party apps on Android 6, so the design's tiles for them stay dimmed
 * with a reason rather than pretending.
 */
class QuickSettingsActivity : BaseScreenActivity() {

    /**
     * Not a tab. Reached from the home screen's status row and from Settings;
     * the bar shows no selection and back returns where the driver came from.
     */
    override val screen: BottomNavView.Screen = BottomNavView.Screen.NONE

    /** §16's `QUICK_SETTINGS` state; see [BaseScreenActivity.primaryState]. */
    override val primaryState: AppStateMachine.State? = AppStateMachine.State.QUICK_SETTINGS

    private lateinit var graph: AppGraph
    private lateinit var settings: AppSettings

    private lateinit var tileWifi: QsTileView
    private lateinit var tileBluetooth: QsTileView
    private lateinit var tileMobile: QsTileView
    private lateinit var tileTheme: QsTileView
    private lateinit var brightnessSlider: SeekBar
    private lateinit var brightnessValue: TextView
    private lateinit var volumeSlider: SeekBar
    private lateinit var volumeValue: TextView

    private val main = Handler(Looper.getMainLooper())

    /** True while the sliders are being written by the screen, not by a finger. */
    private var bindingSliders = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quick_settings)
        bindNavigation()

        graph = AppGraph.require()
        settings = AppSettings(this)

        tileWifi = findViewById(R.id.tileWifi)
        tileBluetooth = findViewById(R.id.tileBluetooth)
        tileMobile = findViewById(R.id.tileMobile)
        tileTheme = findViewById(R.id.tileTheme)
        brightnessSlider = findViewById(R.id.brightnessSlider)
        brightnessValue = findViewById(R.id.brightnessValue)
        volumeSlider = findViewById(R.id.volumeSlider)
        volumeValue = findViewById(R.id.volumeValue)

        wireTiles()
        wireSliders()
        wireActions()
    }

    override fun onResume() {
        super.onResume()
        // Re-read everything: the driver may have changed Wi-Fi from the
        // platform's own panel, granted WRITE_SETTINGS in the system dialog, or
        // plugged in a Bluetooth phone while this screen was in the background.
        refreshAll()
    }

    // -- tiles ---------------------------------------------------------------

    private fun wireTiles() {
        tileWifi.setOnClickListener { toggleWifi() }
        tileBluetooth.setOnClickListener { toggleBluetooth() }
        tileMobile.setOnClickListener {
            // Reaches the system's own mobile-data panel. That is the only
            // action available to a third-party app, and it is a real action —
            // the driver lands somewhere they can change the setting.
            openSystemPanel(Settings.ACTION_DATA_ROAMING_SETTINGS, R.string.qs_unavailable)
        }
        tileTheme.setOnClickListener {
            settings.mapStyleMode = settings.nextStyleMode()
            // The map screens read this on resume, so the change is visible the
            // moment the driver returns to Home or the map.
            refreshThemeTile()
        }
    }

    private fun refreshAll() {
        refreshWifiTile()
        refreshBluetoothTile()
        refreshMobileTile()
        refreshThemeTile()
        refreshBrightness()
        refreshVolume()
        refreshParkingButton()
    }

    private fun refreshWifiTile() {
        val state = runCatching {
            val wm = wifiManager()
            when {
                wm == null -> QsTileView.State.UNAVAILABLE
                wm.isWifiEnabled -> QsTileView.State.ON
                else -> QsTileView.State.OFF
            }
        }.getOrDefault(QsTileView.State.UNAVAILABLE)
        tileWifi.bind(state)
    }

    /**
     * `WIFI_SERVICE` is resolved on the **application** context.
     *
     * Before Android N the `WifiManager` keeps a reference to whatever context
     * asked for it, so asking an Activity leaks that Activity — and this one is
     * recreated on every visit to this screen. Holding the manager for the
     * process lifetime instead is both the fix and one fewer lookup.
     */
    private fun wifiManager(): WifiManager? =
        applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager

    private fun refreshBluetoothTile() {
        val state = runCatching {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            when {
                adapter == null -> QsTileView.State.UNAVAILABLE
                !adapter.isEnabled -> QsTileView.State.OFF
                else -> QsTileView.State.ON
            }
        }.getOrDefault(QsTileView.State.UNAVAILABLE)
        tileBluetooth.bind(state)
    }

    /**
     * Mobile data has no togglable API for a normal app.
     *
     * `TelephonyManager.setDataEnabled` requires `MODIFY_PHONE_STATE`, which is
     * a signature permission the launcher cannot hold. So the tile reports what
     * the radio is doing and, when tapped, opens the panel where the driver can
     * change it — a real action rather than a switch that lies.
     */
    private fun refreshMobileTile() {
        val connected = runCatching {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
            @Suppress("DEPRECATION")
            val info = cm?.activeNetworkInfo
            info != null && info.isConnected && info.type == ConnectivityManager.TYPE_MOBILE
        }.getOrDefault(false)
        if (connected) {
            tileMobile.bind(QsTileView.State.ON)
        } else {
            // Not "Tắt": the launcher cannot read whether the radio is off or
            // merely has no data session, and claiming "off" would be a guess.
            tileMobile.bind(QsTileView.State.NEEDS_PERMISSION, getString(R.string.qs_open_panel))
        }
    }

    private fun refreshThemeTile() {
        val mode = settings.mapStyleMode
        val label = getString(
            when (mode) {
                MapStyleMode.AUTO -> R.string.qs_theme_auto
                MapStyleMode.DAY -> R.string.qs_theme_day
                MapStyleMode.NIGHT -> R.string.qs_theme_night
            }
        )
        // "On" always: the tile is a three-way cycle, and its value line is the
        // mode, not a boolean. Rendering it as ON keeps the icon accent-lit,
        // which is how the design draws this tile.
        tileTheme.bind(QsTileView.State.ON, label)
    }

    // -- toggles -------------------------------------------------------------

    private fun toggleWifi() {
        val wm = wifiManager()
        if (wm == null) {
            Toast.makeText(this, R.string.qs_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val target = !wm.isWifiEnabled
        // The setter *method* is called, not the `isWifiEnabled` property:
        // Kotlin's synthetic property assignment yields `Unit`, which would
        // throw away `setWifiEnabled`'s boolean — the exact value this screen
        // needs in order to know whether the platform accepted the change.
        // Deprecated at API 29, and still the only API that exists at 23.
        @Suppress("DEPRECATION")
        val accepted = runCatching { wm.setWifiEnabled(target) }.getOrDefault(false)
        if (!accepted) {
            Toast.makeText(this, R.string.qs_permission_required, Toast.LENGTH_SHORT).show()
        }
        // Wi-Fi state settles asynchronously; a short re-read shows the truth
        // rather than the value we hoped for.
        main.postDelayed({ refreshWifiTile() }, STATE_SETTLE_MS)
    }

    private fun toggleBluetooth() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Toast.makeText(this, R.string.qs_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val target = !adapter.isEnabled
        // `BLUETOOTH` and `BLUETOOTH_ADMIN` are granted at install time on
        // Android 6 — this app targets SDK 30, below the 31 split that
        // introduced `BLUETOOTH_CONNECT`, so lint's warning names a permission
        // that does not exist on the target platform. The call is wrapped
        // anyway because a vendor ROM can refuse it, and `enable()` returning
        // false is handled below rather than assumed to have worked.
        @Suppress("MissingPermission")
        val accepted = runCatching {
            if (target) adapter.enable() else adapter.disable()
        }.getOrDefault(false)
        if (!accepted) {
            Toast.makeText(this, R.string.qs_permission_required, Toast.LENGTH_SHORT).show()
        }
        main.postDelayed({ refreshBluetoothTile() }, STATE_SETTLE_MS)
    }

    // -- sliders -------------------------------------------------------------

    private fun wireSliders() {
        brightnessSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                brightnessValue.text = getString(R.string.qs_percent, progress)
                if (!fromUser) return
                writeBrightness(progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) {
                // Writing every intermediate value would hammer the settings
                // provider with 100 writes per drag; this one is the final one.
                writeBrightness(bar.progress)
            }
        })

        volumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                volumeValue.text = progress.toString()
                if (!fromUser) return
                graph.audioBridge.setMediaVolume(progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
    }

    private fun refreshBrightness() {
        val canWrite = Settings.System.canWrite(this)
        val percent = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(DEFAULT_BRIGHTNESS)
        bindingSliders = true
        brightnessSlider.max = 100
        brightnessSlider.progress = percent.coerceIn(0, 100)
        brightnessSlider.isEnabled = canWrite
        brightnessSlider.alpha = if (canWrite) 1f else 0.4f
        brightnessValue.text = getString(R.string.qs_percent, percent.coerceIn(0, 100))
        bindingSliders = false

        if (!canWrite) {
            // Tapping a disabled slider does nothing, so the row offers the
            // grant instead. Without this the control is a dead-looking half
            // of the screen with no explanation.
            findViewById<View>(R.id.brightnessRow).setOnClickListener {
                openWriteSettingsGrant()
            }
        } else {
            findViewById<View>(R.id.brightnessRow).setOnClickListener(null)
        }
    }

    private fun writeBrightness(percent: Int) {
        if (bindingSliders) return
        if (!Settings.System.canWrite(this)) {
            openWriteSettingsGrant()
            return
        }
        val value = (percent * 255 / 100).coerceIn(1, 255)
        val written = runCatching {
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        }.getOrDefault(false)
        if (!written) {
            Toast.makeText(this, R.string.qs_permission_required, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWriteSettingsGrant() {
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            .setData(Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { startActivity(intent) }
        if (opened.isFailure) {
            Toast.makeText(this, R.string.qs_permission_required, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshVolume() {
        val bridge = graph.audioBridge
        val current = bridge.mediaVolume()
        bindingSliders = true
        volumeSlider.max = bridge.maxMediaVolume().coerceAtLeast(1)
        volumeSlider.progress = current.coerceIn(0, volumeSlider.max)
        volumeValue.text = current.toString()
        bindingSliders = false
    }

    // -- actions -------------------------------------------------------------

    private fun wireActions() {
        findViewById<View>(R.id.btnScreenOff).setOnClickListener {
            // The design calls this "màn hình tối". `PowerManager.goToSleep`
            // needs the DEVICE_POWER signature permission, so the launcher
            // shows a black screen of its own instead — an honest, working
            // substitute that leaves the head unit on and the music playing.
            startActivity(Intent(this, ScreenOffActivity::class.java))
        }
        findViewById<View>(R.id.btnParking).setOnClickListener { toggleParking() }
        findViewById<View>(R.id.btnRestart).setOnClickListener {
            confirmAppRestart()
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    /**
     * The driver's own parking instruction (§19).
     *
     * **Why this is not just "open the parking screen".** A button that only
     * navigates would leave the resolver believing the car is driving, so the
     * automatic path would not know the driver had already decided; and the
     * screen's own "tap to exit" could not tell the resolver to stay out of it
     * afterwards. Pressing here therefore *tells the detector* first and opens
     * the screen second — the same order the resolver's manual branch requires,
     * and the reason [com.ts8.launcher.runtime.ParkingRuntime.requestEnter] is a
     * process-level call rather than a screen-level one.
     *
     * **Why the same button exits.** One control in one place, and its label is
     * the state it will produce. A separate exit button would be a second thing
     * to find on a screen the driver is looking at while walking away.
     */
    private fun toggleParking() {
        val parking = graph.parking
        if (parking.isParked()) {
            parking.requestExit()
            refreshParkingButton()
            // Return to whichever screen the driver came from rather than
            // leaving the panel on a settings page they no longer need.
            finish()
            return
        }
        parking.requestEnter()
        startActivity(Intent(this, ParkingModeActivity::class.java))
    }

    /** The button says what it will do; nothing else on this screen changes. */
    private fun refreshParkingButton() {
        findViewById<android.widget.Button?>(R.id.btnParking)?.setText(
            if (graph.parking.isParked()) R.string.parking_exit else R.string.qs_parking_mode
        )
    }

    /**
     * Restarts the launcher, not the device.
     *
     * `PowerManager.reboot` requires the `REBOOT` permission, which is granted
     * only to the system. So the button offers the action it can keep: tearing
     * the task down and starting Home again, which is what a driver actually
     * wants when the panel has wedged.
     */
    private fun confirmAppRestart() {
        showTrackedDialog(
            android.app.AlertDialog.Builder(this)
                .setMessage(R.string.qs_restart_confirm)
                .setNegativeButton(R.string.generic_cancel, null)
                .setPositiveButton(R.string.generic_ok) { _, _ ->
                    // A fresh Home task, with everything else finished behind it.
                    val home = Intent(this, HomeActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
                    startActivity(home)
                    finish()
                }
                .show()
        )
    }

    private fun openSystemPanel(action: String, failureMessage: Int) {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // A firmware that stripped the platform panel: say so instead of a
            // dead tap.
            Toast.makeText(this, failureMessage, Toast.LENGTH_SHORT).show()
        }
    }

    /** Re-reads the volume after the bar's own stepper has changed it. */
    override fun onStart() {
        super.onStart()
        if (::volumeSlider.isInitialized) refreshVolume()
    }

    companion object {
        /** Wi-Fi and Bluetooth settle over a broadcast, not synchronously. */
        private const val STATE_SETTLE_MS = 600L

        /** Android's default when nothing has ever been written. */
        private const val DEFAULT_BRIGHTNESS = 128

        /** Opens Quick Settings from a status-row tap. */
        fun intentTo(context: android.content.Context): Intent =
            Intent(context, QuickSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/ui/ScreenOffActivity.kt

BEGIN FILE
package com.ts8.launcher.ui

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import com.ts8.launcher.R

/**
 * A black screen, standing in for the panel being off.
 *
 * **Why this exists.** The design's "màn hình tối" is a power control, and the
 * only API that actually turns the panel off — `PowerManager.goToSleep` — is
 * restricted to the system on Android 6. A launcher that printed "đã tắt màn
 * hình" and did nothing would be the fabricated success the spec forbids. So
 * this activity covers the screen with black instead: the backlight stays on,
 * the launcher says it is a screen-off *view* rather than a power command, and
 * a tap brings the driver back. It is a real, working, honestly-named
 * substitute.
 *
 * **Music keeps playing.** Nothing here touches the player or the navigation
 * state machine; this activity only draws a black window over them. `Music` and
 * the map stay alive in their own tasks, so the first tap returns to a running
 * trip rather than a restarted one.
 *
 * **Why it never turns the display off itself.** `setKeepScreenOn` is left
 * false, so the platform's own display timeout still applies — if the unit is
 * configured to sleep after N minutes, this screen does not block that.
 */
class ScreenOffActivity : BaseScreenActivity() {

    override val screen: BottomNavView.Screen = BottomNavView.Screen.NONE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen_off)

        // Full brightness is wrong here and so is minimum: this window is
        // pure black, so any brightness draws the same black. Touching it would
        // only fight the user's own setting.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv())
        findViewById<View>(R.id.screenOffRoot).setOnClickListener { finish() }
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/ui/ScreenTransition.kt

BEGIN FILE
package com.ts8.launcher.ui

import android.app.Activity
import android.provider.Settings
import com.ts8.launcher.runtime.AppSettings

/**
 * §17's transitions, in one place.
 *
 * The brief's four rules are short and easy to satisfy one screen at a time and
 * impossible to keep that way by hand:
 *
 * 1. *"Chỉ dùng alpha/translation ngắn, nhẹ trên TS8."* Nothing here rotates,
 *    scales or runs longer than the platform's own fade. A cross-fade is the
 *    cheapest transition that still reads as a change rather than a glitch.
 * 2. *"Không animation liên tục."* There is no animator object and no loop: a
 *    window transition runs once, when a window changes, and then it is over.
 *    Nothing in this class can keep drawing.
 * 3. *"Không block input trong transition."* `overridePendingTransition` finishes
 *    before input resumes because it is the *window manager* animating two
 *    already-drawn surfaces; the Activity is never waiting on it. Nothing here
 *    posts a delayed action that holds a flag the UI checks.
 * 4. *"Tôn trọng reduced-animation/low-performance mode."* Both switches are
 *    read here — the platform's own animator scale (a driver can set it to 0,
 *    and developer options do) and the launcher's `Tiết kiệm` performance mode,
 *    which exists precisely because this GPU struggles with composite work.
 *    Either one being off means an instant cut.
 *
 * **Why the platform setting is `Settings.Global`, not `System`.** The animator
 * duration scale lives in `Global` from API 17 onwards; reading `System` would
 * silently return the default and the driver's choice of "no animations" would
 * be ignored on every screen.
 */
object ScreenTransition {

    /** The concrete transition for this device right now. */
    enum class Kind {
        /** A short cross-fade. */
        FADE,

        /** No animation at all. */
        NONE
    }

    /**
     * What [activity] should use.
     *
     * Reads preferences, so call this where a transition is about to happen
     * (a click, a nav tap) rather than caching it: a driver can change
     * `Tiết kiệm` in Settings and the very next screen change must honour it.
     */
    fun kind(activity: Activity): Kind {
        val saver = runCatching {
            AppSettings(activity).performance == AppSettings.Performance.SAVER
        }.getOrDefault(false)
        if (saver) return Kind.NONE
        return if (animationsEnabled(activity)) Kind.FADE else Kind.NONE
    }

    /**
     * Applies the transition to the activity that is leaving.
     *
     * Must be called after `startActivity` — the pairing is the platform's, not
     * this class's — and is a no-op when the kind is [Kind.NONE], which is what
     * makes it safe to call unconditionally.
     */
    fun apply(activity: Activity) {
        if (kind(activity) == Kind.NONE) return
        runCatching {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    /**
     * Whether the platform is animating windows at all.
     *
     * Zero means the driver (or a very old vendor ROM's power saver) switched
     * animations off, and honouring that is not politeness: an animator scale
     * of 0 makes `overridePendingTransition` show nothing, so a screen written
     * to *wait* for a transition would wait forever. Anything unreadable is
     * treated as enabled, because a fade that is slightly unwanted is a smaller
     * fault than a launcher that silently stops transitioning.
     */
    private fun animationsEnabled(activity: Activity): Boolean {
        val scale = runCatching {
            Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        return scale > 0f
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/ui/SearchActivity.kt

BEGIN FILE
package com.ts8.launcher.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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

END FILE

## app/src/main/java/com/ts8/launcher/ui/SettingsActivity.kt

BEGIN FILE
package com.ts8.launcher.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.ts8.core.map.maplibre.MapPack
import com.ts8.core.music.online.ProviderKind
import com.ts8.launcher.R
import com.ts8.launcher.di.AppGraph

/**
 * Settings — reachable from Quick Settings, not one of the design's seven
 * states.
 *
 * **What belongs here.** Only two kinds of row: a fact the launcher can read
 * from the platform, and an action the launcher can actually perform. That is
 * the map pack it is currently using, which permissions it holds, which build
 * this is, and a rescan that re-walks storage for a newly plugged-in USB stick.
 * Everything else a settings screen usually offers — a launcher default, a
 * screen timeout, a reboot — either belongs to the platform or needs a
 * signature permission, and a row that pretends otherwise is the fabricated
 * success the spec forbids.
 *
 * **Why the pack section is first.** A head unit with no map pack is the single
 * most common failure state of this launcher, and the exact path it looked in
 * is the one piece of information that lets a driver fix it. Burying it behind
 * a general settings list would mean the driver never finds it.
 *
 * **Why permissions are re-read in `onResume`.** The driver leaves this screen
 * to grant a permission in the system dialog and comes back. Reading the grant
 * state once in `onCreate` would show a stale "chưa cấp" for a permission that
 * was granted thirty seconds earlier.
 *
 * **No diagnostics entry.** `core/diagnostics` is an empty shell in this build;
 * a button that opens nothing is worse than no button. It comes back with the
 * module.
 */
class SettingsActivity : BaseScreenActivity() {

    /** Not a tab: opened from Quick Settings, back returns there. */
    override val screen: BottomNavView.Screen = BottomNavView.Screen.NONE

    private lateinit var graph: AppGraph

    private lateinit var packName: TextView
    private lateinit var packPath: TextView
    private lateinit var packAttribution: TextView
    private lateinit var permissionList: LinearLayout

    private lateinit var onlineToggleRow: LinearLayout
    private lateinit var onlineKindRow: LinearLayout
    private lateinit var onlineFeed: EditText
    private lateinit var onlineServer: EditText
    private lateinit var onlineUser: EditText
    private lateinit var onlinePass: EditText
    private lateinit var onlinePassState: TextView
    private lateinit var onlineStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        bindNavigation()

        graph = AppGraph.require()

        packName = findViewById(R.id.packName)
        packPath = findViewById(R.id.packPath)
        packAttribution = findViewById(R.id.packAttribution)
        permissionList = findViewById(R.id.permissionList)

        onlineToggleRow = findViewById(R.id.onlineToggleRow)
        onlineKindRow = findViewById(R.id.onlineKindRow)
        onlineFeed = findViewById(R.id.onlineFeed)
        onlineServer = findViewById(R.id.onlineServer)
        onlineUser = findViewById(R.id.onlineUser)
        onlinePass = findViewById(R.id.onlinePass)
        onlinePassState = findViewById(R.id.onlinePassState)
        onlineStatus = findViewById(R.id.onlineStatus)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnRescanPack).setOnClickListener { rescanPack() }
        findViewById<View>(R.id.btnSaveOnline).setOnClickListener { saveOnline() }

        bindVersion()
        renderPack()
        renderOnline()
    }

    override fun onResume() {
        super.onResume()
        // The driver comes back from the system permission dialog; the grant
        // state has to be re-read or the list keeps saying "chưa cấp".
        renderPermissions()
    }

    // -- build ---------------------------------------------------------------

    /**
     * Version and platform, both read from the platform rather than from a
     * constant: a build number pasted into a string drifts from the APK the
     * driver installed, and this screen exists partly so a support call can
     * read the exact build out loud.
     *
     * `BuildConfig` is not available here — this module does not generate it —
     * and `PackageInfo` is the authority either way.
     */
    private fun bindVersion() {
        val info = runCatching {
            packageManager.getPackageInfo(packageName, 0)
        }.getOrNull()
        val versionName = info?.versionName ?: getString(R.string.settings_version_unknown)
        @Suppress("DEPRECATION")
        val versionCode = info?.versionCode ?: 0
        findViewById<TextView>(R.id.versionText).text =
            getString(R.string.settings_version, versionName, versionCode)
        findViewById<TextView>(R.id.platformText).text =
            getString(R.string.settings_platform, Build.VERSION.RELEASE, Build.VERSION.SDK_INT)
    }

    // -- map pack ------------------------------------------------------------

    /**
     * Renders whatever the pack lookup currently says — including a failure.
     *
     * The failure text is the same sentence the map screen shows, not a
     * generic "không tìm thấy dữ liệu": it names the directories that were
     * searched, which is the only actionable part.
     */
    private fun renderPack() {
        when (val result = graph.loadPack()) {
            is MapPack.LoadResult.Ok -> {
                val meta = result.pack.metadata
                packName.text = meta.name
                packName.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                packPath.text = result.pack.directory.absolutePath
                val attribution = meta.attribution
                if (attribution.isNullOrBlank()) {
                    packAttribution.visibility = View.GONE
                } else {
                    packAttribution.visibility = View.VISIBLE
                    packAttribution.text = attribution
                }
            }
            is MapPack.LoadResult.Failure -> {
                packName.text = getString(R.string.settings_pack_missing)
                packName.setTextColor(ContextCompat.getColor(this, R.color.danger))
                packPath.text = result.reason
                packAttribution.visibility = View.GONE
            }
        }
    }

    /**
     * Forgets the cached lookup and tries again.
     *
     * This is the action a driver takes after plugging in the USB stick that
     * carries the map. It also drops the search service and the router, which
     * were built on the previous pack — [AppGraph.invalidatePack] owns that
     * ordering, and doing it here instead would be a second copy of the rule.
     */
    private fun rescanPack() {
        graph.invalidatePack()
        renderPack()
        val loaded = graph.pack()
        val message = if (loaded != null) {
            getString(R.string.settings_pack_ready, loaded.metadata.name)
        } else {
            getString(R.string.settings_pack_missing)
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // -- online music (§12–13) -----------------------------------------------

    /**
     * Fills the four fields and the two chip rows from what is stored.
     *
     * **Why the password field is left empty even when one is stored.** A
     * password re-rendered into a text field is a password on a screen at
     * arm's length from the driver's seat, and on this ROM a `textPassword`
     * field with content shows its dots while the cursor is in it. The line
     * underneath states whether something is stored, which is the fact the
     * driver actually needs; an empty field means "keep it" and typing replaces
     * it. That is also why [saveOnline] only writes the field when non-blank.
     *
     * **Why the status line shows the gateway's own sentences.** §13's
     * `BLOCKED_EXTERNAL_CONFIG` names the setting that is missing; a sentence
     * composed here would be a second, possibly different, description of the
     * same state.
     */
    private fun renderOnline() {
        val settings = graph.settings
        onlineFeed.setText(settings.onlineFeedUrl)
        onlineServer.setText(settings.onlineServerUrl)
        onlineUser.setText(settings.onlineUser)
        onlinePass.setText("")

        onlinePassState.text = if (settings.onlineToken.isNotBlank()) {
            getString(R.string.settings_online_pass_saved)
        } else {
            getString(R.string.settings_online_pass_none)
        }

        renderOnlineToggle(settings.onlineEnabled)
        renderKindChips(settings.onlineServerKind)

        graph.onlineMusic.status { lines ->
            if (isFinishing || isDestroyed) return@status
            if (lines.isEmpty()) {
                onlineStatus.visibility = View.GONE
            } else {
                onlineStatus.visibility = View.VISIBLE
                onlineStatus.text = lines.joinToString("\n")
            }
        }
    }

    private fun renderOnlineToggle(enabled: Boolean) {
        onlineToggleRow.removeAllViews()
        val title = TextView(this).apply {
            text = getString(R.string.music_online_settings_title)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        onlineToggleRow.addView(title)
        onlineToggleRow.addView(
            chip(getString(R.string.qs_on), enabled) {
                graph.settings.onlineEnabled = true
                graph.onlineMusic.invalidate()
                renderOnline()
            }
        )
        onlineToggleRow.addView(
            chip(getString(R.string.qs_off), !enabled) {
                graph.settings.onlineEnabled = false
                graph.onlineMusic.invalidate()
                renderOnline()
            }
        )
    }

    /**
     * Two chips for the Subsonic family.
     *
     * Both write the same enum, and the choice is a label rather than a
     * behaviour difference on the wire: Jellyfin's Subsonic endpoint takes the
     * same requests, and the value decides what the result rows are called.
     * Stored by name so a later build cannot move a driver's Jellyfin onto the
     * Navidrome branch by reordering an enum.
     */
    private fun renderKindChips(stored: String) {
        onlineKindRow.removeAllViews()
        for (kind in listOf(ProviderKind.NAVIDROME, ProviderKind.JELLYFIN)) {
            val label = if (kind == ProviderKind.JELLYFIN) "Jellyfin" else "Navidrome"
            onlineKindRow.addView(
                chip(label, stored == kind.name) {
                    graph.settings.onlineServerKind = kind.name
                    graph.onlineMusic.invalidate()
                    renderOnline()
                }
            )
        }
    }

    /**
     * Writes the fields back and forces the gateway to be rebuilt.
     *
     * `invalidate()` is not optional: a provider captures its address and
     * credentials when it is constructed, so a corrected host or password
     * would otherwise be ignored by the instance already in hand until the
     * process restarted. The state line is re-read afterwards, which is what
     * turns "saved" from a toast into evidence.
     */
    private fun saveOnline() {
        val settings = graph.settings
        settings.onlineFeedUrl = onlineFeed.text?.toString().orEmpty()
        settings.onlineServerUrl = onlineServer.text?.toString().orEmpty()
        settings.onlineUser = onlineUser.text?.toString().orEmpty()
        // Blank = keep the stored one, never "clear it": an empty password
        // field is the screen's normal state after a reopen.
        val typed = onlinePass.text?.toString().orEmpty()
        if (typed.isNotBlank()) settings.onlineToken = typed

        graph.onlineMusic.invalidate()
        onlinePass.setText("")
        Toast.makeText(this, R.string.settings_online_saved, Toast.LENGTH_SHORT).show()
        renderOnline()
    }

    // -- permissions ---------------------------------------------------------

    /**
     * One row per permission the launcher genuinely asks for.
     *
     * Only three, and only the three that change what the launcher can do:
     * location for the map and navigation, storage for the music library, and
     * `WRITE_SETTINGS` for the brightness slider. Microphone, contacts and the
     * rest are not listed because nothing in this build uses them, and a
     * permission row for an unused permission teaches the driver to distrust
     * the list.
     */
    private fun renderPermissions() {
        permissionList.removeAllViews()
        addPermission(R.string.perm_location_title, Manifest.permission.ACCESS_FINE_LOCATION)
        addPermission(R.string.perm_storage_title, Manifest.permission.READ_EXTERNAL_STORAGE)
        addWriteSettingsPermission()
    }

    private fun addPermission(labelRes: Int, permission: String) {
        val granted = ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
        addRow(
            label = getString(labelRes),
            granted = granted,
            // A runtime permission is re-requested by the screen that needs it;
            // from here the only useful destination is the app's own page,
            // where a "don't ask again" denial can be undone.
            action = if (granted) null else View.OnClickListener { openAppDetails() }
        )
    }

    /**
     * `WRITE_SETTINGS` is a special permission with its own grant screen, not a
     * runtime permission, so its request path is different from the two above
     * and it gets its own row rather than being folded in.
     */
    private fun addWriteSettingsPermission() {
        val granted = runCatching { Settings.System.canWrite(this) }.getOrDefault(false)
        addRow(
            label = getString(R.string.settings_perm_brightness),
            granted = granted,
            action = if (granted) null else View.OnClickListener {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
                val opened = runCatching { startActivity(intent) }
                if (opened.isFailure) {
                    Toast.makeText(this, R.string.qs_permission_required, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun addRow(label: String, granted: Boolean, action: View.OnClickListener?) {
        val row = TextView(this).apply {
            text = if (granted) getString(R.string.settings_perm_granted, label)
            else getString(R.string.settings_perm_denied, label)
            setTextColor(
                ContextCompat.getColor(
                    this@SettingsActivity,
                    if (granted) R.color.text_secondary else R.color.accent_text
                )
            )
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }
        if (action != null) {
            row.setOnClickListener(action)
        }
        permissionList.addView(row)
    }

    private fun openAppDetails() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, R.string.qs_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * A 44 dp chip, the same control the map settings panel uses.
     *
     * Not a `Switch`: on this ROM the platform switch draws in the theme's
     * disabled colour and all but vanishes against the dark card. A chip is
     * also a 44 dp touch target by construction, which is the size a driver
     * can hit without looking.
     */
    private fun chip(labelText: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = labelText
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT
            setPadding(dp(14), dp(10), dp(14), dp(10))
            minWidth = dp(72)
            minHeight = dp(40)
            setTextColor(
                ContextCompat.getColor(
                    this@SettingsActivity,
                    if (selected) R.color.text_on_accent else R.color.text_secondary
                )
            )
            setBackgroundResource(
                if (selected) R.drawable.bg_button_primary else R.drawable.bg_chip
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

END FILE

## app/src/main/java/com/ts8/launcher/ui/SpeedViews.kt

BEGIN FILE
package com.ts8.launcher.ui

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ts8.core.speed.SpeedState
import com.ts8.core.speed.SpeedUiState
import com.ts8.launcher.R

/**
 * Binds one [SpeedUiState] onto the speed chip and the speed-limit roundel.
 *
 * **Why this is shared by every screen.** The brief requires one `SpeedMonitor`
 * boundary and forbids the UI from reading GPS. Two screens rendering the same
 * state with their own formatting is the same defect at a smaller scale: Home
 * would print `48`, the map `48.2`, and one of them would forget to hide the
 * roundel when the pack has no limit. One function, one appearance.
 *
 * **Why `--` rather than a hidden view.** Â§8 says a missing limit must show `--`
 * and must not be guessed. Hiding the roundel entirely makes "the pack has no
 * limit here" indistinguishable from "the feature is off"; printing a number
 * would be an invention. `--` in the roundel is the only honest third option.
 */
object SpeedViews {

    /**
     * Applies [state] to the views.
     *
     * Every view is optional so a screen that only has a speed chip (the
     * parking overlay, for instance) can call the same function.
     */
    fun bind(
        chip: View?,
        value: TextView?,
        unit: TextView?,
        limitRoundel: View?,
        limitValue: TextView?,
        state: SpeedUiState
    ) {
        // The chip carries both readouts, so it exists only when the driver
        // asked to see either of them.
        val showChip = state.showSpeedSetting || state.showLimitSetting
        chip?.visibility = if (showChip) View.VISIBLE else View.GONE

        value?.apply {
            text = state.displaySpeed?.toString() ?: NO_DATA
            // Red while the alert is live: the visual half of a VISUAL/BOTH
            // alert, which must work with the volume at zero.
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (state.alerting) R.color.danger else R.color.text_primary
                )
            )
            visibility = if (state.showSpeedSetting) View.VISIBLE else View.GONE
        }

        unit?.apply {
            text = state.unitLabel
            visibility = if (state.showSpeedSetting) View.VISIBLE else View.GONE
        }

        limitRoundel?.visibility = if (state.showLimitSetting) View.VISIBLE else View.GONE
        limitValue?.apply {
            // `--` when the pack has no answer for this road. The brief's rule:
            // never show a limit that is not backed by a source.
            text = state.displayLimit?.toString() ?: NO_DATA
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (state.displayLimit == null) R.color.text_tertiary else R.color.speed_limit_ring
                )
            )
        }
    }

    /**
     * Accessibility/description of the current state for the chip.
     *
     * Kept short: it is read by a screen reader and also shown in the
     * diagnostics panel, so it names the state and the numbers, not a sentence.
     */
    fun describe(state: SpeedUiState): String {
        val speed = state.displaySpeed?.let { "$it ${state.unitLabel}" } ?: NO_DATA
        val limit = state.displayLimit?.let { "$it ${state.unitLabel}" } ?: NO_DATA
        return "speed=$speed state=${state.state} limit=$limit"
    }

    /** The placeholder the brief fixes for "no data". */
    const val NO_DATA = "--"

    /** True when the state can drive an alert at all. */
    fun canAlert(state: SpeedUiState): Boolean =
        state.state == SpeedState.OVERSPEED_PENDING || state.state == SpeedState.OVERSPEED_ACTIVE
}

END FILE

## app/src/main/java/com/ts8/launcher/ui/StatusBarView.kt

BEGIN FILE
package com.ts8.launcher.ui

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.ts8.launcher.R

/**
 * The status row: clock, date, and the connectivity icons the design shows.
 *
 * **Every icon is read from a real API.** The design draws Wi-Fi, signal,
 * Bluetooth and battery; a launcher that draws them in a fixed "on" state is
 * lying to the driver about whether the car has a connection. So each icon is
 * tinted `text_tertiary` when the radio is off and `text_secondary` when it is
 * on, and the receivers are registered on the view's own window so a screen
 * that is not showing costs nothing.
 *
 * **API 23 note.** `ConnectivityManager.getActiveNetwork()` and
 * `NetworkInfo.isConnected` are the only APIs available here; the
 * `NetworkCallback`-based path and `getNetworkCapabilities` do not exist until
 * API 23/24 respectively in a form that helps. `WifiManager.isWifiEnabled`
 * needs no permission beyond `ACCESS_WIFI_STATE` when only read.
 */
class StatusBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val clock: TextView
    private val date: TextView
    private val iconWifi: ImageView
    private val iconSignal: ImageView
    private val iconBluetooth: ImageView
    private val iconBattery: ImageView

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_TIME_TICK,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED -> refreshClock()
                else -> refreshState()
            }
        }
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_status_bar, this, true)
        clock = findViewById(R.id.clockText)
        date = findViewById(R.id.dateText)
        iconWifi = findViewById(R.id.iconWifi)
        iconSignal = findViewById(R.id.iconSignal)
        iconBluetooth = findViewById(R.id.iconBluetooth)
        iconBattery = findViewById(R.id.iconBattery)

        // The design has no settings affordance anywhere else on the seven
        // screens, and every one of them draws this icon row. Making it the
        // entry point means Quick Settings is reachable from wherever the
        // driver is without adding a control the design does not have.
        findViewById<View>(R.id.statusIcons)?.setOnClickListener {
            runCatching {
                context.startActivity(QuickSettingsActivity.intentTo(context))
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!registered) {
            // TIME_TICK fires once a minute, which is exactly the clock's
            // resolution; a Handler would wake the CPU at the same rate for
            // more code.
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                addAction(ConnectivityManager.CONNECTIVITY_ACTION)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            }
            context.registerReceiver(receiver, filter)
            registered = true
        }
        refreshClock()
        refreshState()
    }

    override fun onDetachedFromWindow() {
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
        super.onDetachedFromWindow()
    }

    fun refreshClock() {
        clock.text = ClockFormat.time()
        date.text = ClockFormat.date()
    }

    /**
     * Re-reads every radio state.
     *
     * Each read is wrapped: a vendor firmware that stripped a service, or
     * denied a read, must leave the icon dim rather than crash the launcher.
     */
    fun refreshState() {
        val ctx = context

        val wifiOn = runCatching {
            val wm = ctx.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wm?.isWifiEnabled == true
        }.getOrDefault(false)
        tint(iconWifi, wifiOn)

        val mobileOn = runCatching {
            val cm = ctx.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            @Suppress("DEPRECATION")
            val info: NetworkInfo? = cm?.activeNetworkInfo
            info != null && info.isConnected && info.type == ConnectivityManager.TYPE_MOBILE
        }.getOrDefault(false)
        tint(iconSignal, mobileOn)

        val btOn = runCatching {
            BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
        }.getOrDefault(false)
        tint(iconBluetooth, btOn)

        // Battery: the icon is always shown, but a charging unit is brighter
        // because on a head unit "is it charging" is the question that matters.
        val charging = runCatching {
            val bm = ctx.applicationContext
                .getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            @Suppress("DEPRECATION")
            bm?.isCharging == true
        }.getOrDefault(false)
        tint(iconBattery, charging)
    }

    private fun tint(view: ImageView, on: Boolean) {
        view.setColorFilter(
            context.getColor(if (on) R.color.text_secondary else R.color.text_tertiary)
        )
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/ui/VoiceOverlayHost.kt

BEGIN FILE
package com.ts8.launcher.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import com.ts8.core.voice.VoiceState
import com.ts8.core.voice.VoiceStateSnapshot
import com.ts8.launcher.di.AppGraph
import com.ts8.launcher.runtime.AppStateMachine
import com.ts8.launcher.runtime.VoiceInstantController
import com.ts8.launcher.runtime.VoiceTrigger

/**
 * Hangs Voice Instant's overlay on a screen and connects the screen's Back key
 * to it.
 *
 * **Why a host object instead of code in [BaseScreenActivity].** The overlay
 * needs a `VoiceOverlayView` added above the screen's own content, a listener
 * attached on `onStart` and detached on `onStop`, and a `VoiceTrigger` bound to
 * the activity — about sixty lines that would otherwise repeat in every screen
 * that offers voice. The base class owns the one instance and its lifecycle;
 * this object owns the wiring, so a screen that wants a mic button only needs
 * the host.
 *
 * **Why the overlay is a child of `android.R.id.content`.** That is the one
 * container every screen here has, it is a `FrameLayout`, and adding the bar
 * there puts it above the screen's own views without any elevation arithmetic
 * or a second window. A `Dialog` would dim the launcher and outlive the screen;
 * a sibling `ViewGroup` per layout would have to be added to twelve XML files
 * and would drift.
 *
 * **Why `onStop` detaches rather than cancels.** The interaction belongs to the
 * process, not to the screen: the driver can say "dẫn đường tới Cầu Rồng" and
 * the map comes forward, which stops Home. Cancelling on stop would kill the
 * command in the middle of the navigation it started. Detaching only stops the
 * *rendering*; the router keeps its state, and the next screen's host renders
 * it again from [VoiceInstantController.snapshot].
 *
 * **No continuous recording.** [attach] never opens the microphone; only
 * [trigger] does, and that is only ever called from a tap.
 */
class VoiceOverlayHost(private val activity: BaseScreenActivity) {

    private val graph: AppGraph get() = AppGraph.require()

    private val controller: VoiceInstantController get() = graph.voiceInstant()

    private var overlay: VoiceOverlayView? = null
    private var trigger: VoiceTrigger? = null
    private var attached = false

    /** The last caption the router spoke, so a re-attach can restore it. */
    private var caption: String? = null

    private val listener = object : VoiceInstantController.Listener {
        override fun onState(snapshot: VoiceStateSnapshot) {
            if (snapshot.state == VoiceState.IDLE) caption = null
            overlay?.render(snapshot, caption)
            syncOverlayState(snapshot.state)
        }

        override fun onCaption(text: String) {
            caption = text
            overlay?.render(controller.snapshot(), text)
        }
    }

    /**
     * Mirrors the voice interaction into the §16 overlay stack.
     *
     * Two overlay entries rather than one per `VoiceState`: the brief names
     * `VOICE_LISTENING` and `VOICE_CONFIRMATION`, and the three middle states
     * (PROCESSING, EXECUTING, CONFIRMING) are all "the driver must answer or
     * wait, and Back cancels" — which is what `VOICE_CONFIRMATION` means here.
     * All three take Back, and none of them draws anything different on screen
     * once the caption is being shown; splitting them would give the machine
     * three stack entries for one Back press to unwind.
     *
     * A refused transition is not possible here: the overlay stack is
     * orthogonal to the primary state, so pushing a voice overlay from any
     * screen is legal by construction.
     */
    private fun syncOverlayState(state: VoiceState) {
        val machine = runCatching { graph.uiState }.getOrNull() ?: return
        when (state) {
            VoiceState.LISTENING -> {
                machine.popOverlay(AppStateMachine.Overlay.VOICE_CONFIRMATION)
                machine.pushOverlay(AppStateMachine.Overlay.VOICE_LISTENING)
            }
            VoiceState.PROCESSING,
            VoiceState.EXECUTING,
            VoiceState.CONFIRMING -> {
                machine.popOverlay(AppStateMachine.Overlay.VOICE_LISTENING)
                machine.pushOverlay(AppStateMachine.Overlay.VOICE_CONFIRMATION)
            }
            // Terminal and idle states leave nothing on the stack: the bar
            // either shows a result that Back does not dismiss (SUCCESS,
            // ERROR) or is gone entirely.
            VoiceState.IDLE, VoiceState.SUCCESS, VoiceState.ERROR -> {
                machine.popOverlay(AppStateMachine.Overlay.VOICE_LISTENING)
                machine.popOverlay(AppStateMachine.Overlay.VOICE_CONFIRMATION)
            }
        }
    }

    /**
     * Creates the bar and adds it above the screen's content.
     *
     * **Must tolerate being called twice.** `AppCompatDelegate.setContentView`
     * fires `onContentChanged` once for the sub-decor and again after the
     * screen's own layout is inflated, and the second inflation clears
     * `android.R.id.content`. So the bar view is kept and re-attached whenever
     * it has lost its parent, rather than being created once and assumed to
     * still be there — the alternative is a bar that exists in the field only
     * on the screens whose layout happened to be set first.
     */
    fun install() {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val bar = overlay ?: VoiceOverlayView(activity).also { created ->
            created.onConfirm = { yes -> controller.confirm(yes) }
            created.onCancel = { dismiss() }
            created.onRetry = {
                // Retrying is a fresh interaction, not a resume: the recogniser
                // session that failed is gone and the router has already
                // returned to IDLE, so the only correct next step is to open the
                // microphone again through the trigger (which also re-checks the
                // permission).
                trigger?.request()
            }
            created.onOpenSettings = { trigger?.openPermissionSettings() }
            overlay = created
        }
        if (trigger == null) trigger = VoiceTrigger(activity, controller)
        if (bar.parent === root) return
        (bar.parent as? ViewGroup)?.removeView(bar)
        root.addView(
            bar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    /** Starts rendering the current snapshot. Call from `onStart`. */
    fun attach() {
        if (attached) return
        attached = true
        controller.attach(listener)
        // A state that survived the screen switch — a SUCCESS the driver has
        // not dismissed, or a CONFIRMING that was on the previous screen — is
        // drawn immediately; otherwise the bar is hidden.
        overlay?.render(controller.snapshot(), caption)
    }

    /** Stops rendering. The interaction itself keeps running. Call from `onStop`. */
    fun detach() {
        if (!attached) return
        attached = false
        controller.detach()
        overlay?.hide()
    }

    /** Opens the microphone, requesting the permission when it is missing. */
    fun trigger(): Boolean = trigger?.request() ?: false

    /**
     * True when Back belongs to the overlay rather than to the screen.
     *
     * The snapshot's own rule is used ([VoiceStateSnapshot.backCancels]) rather
     * than a list written here: the router decides which states a Back cancels,
     * and a second copy of that rule would drift the first time a state is
     * added. The §16 machine's stack is then trimmed, so a Back that closes the
     * microphone does not leave `VOICE_LISTENING` on the stack for the next
     * Back press to close a second time.
     */
    fun handleBack(): Boolean {
        val snapshot = controller.snapshot()
        if (!snapshot.backCancels) return false
        runCatching { graph.uiState }.getOrNull()?.popTopOverlay()
        dismiss()
        return true
    }

    /** Closes the overlay and abandons whatever the interaction was doing. */
    fun dismiss() {
        caption = null
        controller.cancel()
        overlay?.render(controller.snapshot(), null)
    }

    /** Forwards the permission answer; see [BaseScreenActivity]. */
    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray): Boolean =
        trigger?.onRequestPermissionsResult(requestCode, grantResults) ?: false

    /** True while the bar is on screen, so a screen can suppress a hint behind it. */
    fun isShowing(): Boolean = controller.snapshot().overlayVisible

    /** Releases the view references; the process-owned controller stays alive. */
    fun release() {
        detach()
        overlay?.let { bar ->
            (bar.parent as? ViewGroup)?.removeView(bar)
        }
        overlay = null
        trigger = null
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/ui/VoiceOverlayView.kt

BEGIN FILE
package com.ts8.launcher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ts8.core.voice.VoiceError
import com.ts8.core.voice.VoiceState
import com.ts8.core.voice.VoiceStateSnapshot
import com.ts8.core.voice.VoiceStateStore
import com.ts8.launcher.R

/**
 * Draws one [VoiceStateSnapshot] and nothing else.
 *
 * **Why a plain view and not a dialog.** A `Dialog` on API 23 owns a window, a
 * back-press dispatch and its own dim layer; the driver would see the launcher
 * dim behind a voice prompt, and a dialog left up by a bug survives the screen
 * that created it. This view is a child of the activity's content root, so it
 * disappears when the screen does and Back reaches the activity first.
 *
 * **Why it never animates the bar's height.** The pill is one fixed shape and
 * only its contents change per state. Growing/shrinking it during CONFIRMING
 * would move the two buttons down exactly while the driver is aiming at them.
 *
 * **What it does not decide.** Which buttons *mean* what, whether an error is
 * retryable and whether Back cancels are all answered by the snapshot and by
 * `VoiceStateSnapshot.backCancels`; the view maps them to views. Adding a rule
 * here would put a voice decision in a layout class, which is the split
 * `VoiceRouter` exists to keep.
 *
 * **The `Voice service unavailable` sentence.** §10 requires that exact phrase
 * when the platform has no recogniser. It is rendered as the title with the
 * Vietnamese explanation as the caption, so the driver sees both the literal
 * state and what it means — see `voice_error_unavailable`.
 */
class VoiceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Answer to a confirmation prompt. */
    var onConfirm: ((Boolean) -> Unit)? = null

    /** Close / Back inside the overlay. */
    var onCancel: (() -> Unit)? = null

    /** "Nói lại" after a retryable failure. */
    var onRetry: (() -> Unit)? = null

    /** "Mở cài đặt quyền" after `PERMISSION_DENIED`. */
    var onOpenSettings: (() -> Unit)? = null

    private val pill: LinearLayout
    private val icon: ImageView
    private val title: TextView
    private val caption: TextView
    private val actions: LinearLayout
    private val btnYes: Button
    private val btnNo: Button
    private val btnRetry: Button
    private val btnSettings: Button
    private val btnClose: ImageButton

    /** Set while a confirmation is up, so the caller can query it cheaply. */
    var isConfirming: Boolean = false
        private set

    /** The last rendered snapshot; null before the first render. */
    var snapshot: VoiceStateSnapshot? = null
        private set

    init {
        LayoutInflater.from(context).inflate(R.layout.view_voice_overlay, this, true)
        pill = findViewById(R.id.voicePill)
        icon = findViewById(R.id.voiceIcon)
        title = findViewById(R.id.voiceTitle)
        caption = findViewById(R.id.voiceCaption)
        actions = findViewById(R.id.voiceActions)
        btnYes = findViewById(R.id.btnVoiceYes)
        btnNo = findViewById(R.id.btnVoiceNo)
        btnRetry = findViewById(R.id.btnVoiceRetry)
        btnSettings = findViewById(R.id.btnVoiceSettings)
        btnClose = findViewById(R.id.btnVoiceClose)

        btnYes.setOnClickListener { onConfirm?.invoke(true) }
        btnNo.setOnClickListener { onConfirm?.invoke(false) }
        btnRetry.setOnClickListener { onRetry?.invoke() }
        btnSettings.setOnClickListener { onOpenSettings?.invoke() }
        btnClose.setOnClickListener { onCancel?.invoke() }

        visibility = View.GONE
    }

    /**
     * Renders [state], with [spoken] as the caption line.
     *
     * [spoken] is passed through rather than stored because the caption is the
     * router's own sentence, not something the view may compose: quoting the
     * wrong reason is the failure mode this parameter exists to avoid.
     */
    fun render(state: VoiceStateSnapshot, spoken: String?) {
        snapshot = state
        isConfirming = state.state == VoiceState.CONFIRMING

        if (!state.overlayVisible) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE

        title.setText(titleFor(state))
        // The caption is the router's sentence when there is one, and the state
        // description otherwise — never an empty second line, which reads as a
        // rendering bug rather than "nothing to add".
        val line = spoken?.takeIf { it.isNotBlank() }
        if (line == null) {
            caption.visibility = View.GONE
        } else {
            caption.text = line
            caption.visibility = View.VISIBLE
        }

        val error = state.error
        btnYes.visibility = if (state.state == VoiceState.CONFIRMING) View.VISIBLE else View.GONE
        btnNo.visibility = if (state.state == VoiceState.CONFIRMING) View.VISIBLE else View.GONE

        // Retry is offered only where saying it again can plausibly work. A
        // missing recogniser is not retryable (the platform will answer the
        // same way), and neither is a denied permission — that one gets a
        // Settings button instead.
        val retryable = state.state == VoiceState.ERROR &&
            (error == VoiceError.NO_SPEECH ||
                error == VoiceError.NO_MATCH ||
                error == VoiceError.NOT_UNDERSTOOD ||
                error == VoiceError.COMMAND_FAILED)
        btnRetry.visibility = if (retryable) View.VISIBLE else View.GONE
        btnSettings.visibility =
            if (state.state == VoiceState.ERROR && error == VoiceError.PERMISSION_DENIED) {
                View.VISIBLE
            } else {
                View.GONE
            }

        // Close is pointless in CONFIRMING (the No button is the cancel) but
        // required everywhere else, including SUCCESS, so the driver can clear
        // a result without waiting out the auto-dismiss.
        btnClose.visibility =
            if (state.state == VoiceState.CONFIRMING) View.GONE else View.VISIBLE

        icon.setColorFilter(
            context.getColor(accentOrDanger(state))
        )
    }

    /** Hides the bar without changing the snapshot, e.g. when the screen stops. */
    fun hide() {
        isConfirming = false
        visibility = View.GONE
    }

    private fun accentOrDanger(state: VoiceStateSnapshot): Int = when (state.state) {
        VoiceState.ERROR -> R.color.danger
        VoiceState.SUCCESS -> R.color.success
        else -> R.color.accent_text
    }

    private fun titleFor(state: VoiceStateSnapshot): String = when (state.state) {
        VoiceState.IDLE -> ""
        VoiceState.LISTENING -> context.getString(R.string.voice_listening)
        VoiceState.PROCESSING -> context.getString(R.string.voice_processing)
        VoiceState.CONFIRMING -> context.getString(R.string.voice_confirming)
        VoiceState.EXECUTING -> context.getString(R.string.voice_executing)
        VoiceState.SUCCESS -> context.getString(R.string.voice_state_success)
        VoiceState.ERROR -> errorTitle(state.error)
    }

    /**
     * The error's own sentence, except for the platform-missing case where §10
     * fixes the wording at `Voice service unavailable`.
     */
    private fun errorTitle(error: VoiceError?): String = when (error) {
        VoiceError.SERVICE_UNAVAILABLE -> context.getString(R.string.voice_error_unavailable)
        VoiceError.PERMISSION_DENIED -> context.getString(R.string.voice_error_permission)
        VoiceError.NO_SPEECH, VoiceError.NO_MATCH ->
            context.getString(R.string.voice_error_no_match)
        VoiceError.TIMEOUT -> context.getString(R.string.voice_error_timeout)
        VoiceError.NOT_UNDERSTOOD -> context.getString(R.string.voice_state_error)
        VoiceError.COMMAND_FAILED, null -> context.getString(R.string.voice_state_error)
    }

    companion object {
        /**
         * The key `VoiceStateStore` writes to; re-exported so a screen can hand
         * the overlay the restored snapshot without knowing the store's shape.
         */
        const val STATE_KEY: String = VoiceStateStore.KEY
    }
}

END FILE

## app/src/main/res/color/music_tab_tint.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!--
  Library tab label colour.

  The tabs are plain TextViews rather than a TabLayout: a TabLayout on this
  panel brings its own ripple and sliding indicator, and four tabs of one word
  each are not enough content to justify the dependency. Selection is a state
  the screen sets with `isSelected`, which both this selector and
  @drawable/bg_tab_pill read — so the label brightens and the outline appears
  from the same flag, and the two cannot drift apart.

  Correction: an earlier version of this comment argued the design has no
  indicator bar. It does. At 5x on the Music panel the selected tab is an
  outlined rounded rectangle with a brighter underscore inside it, and the
  three unselected tabs are bare labels. The outline is drawn in bg_tab_pill;
  the underscore is not reproduced, because it is a 2 px accent line inside a
  36 dp pill and at 1024x600 it lands under the target density where it reads
  as a rendering artefact rather than a second element. Recorded in
  docs/UI_GAP_REPORT.md rather than silently dropped.
-->
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_selected="true" android:color="@color/accent_text" />
    <item android:color="@color/text_tertiary" />
</selector>

END FILE

## app/src/main/res/color/nav_item_tint.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_selected="true" android:color="@color/icon_active" />
    <item android:color="@color/icon_inactive" />
</selector>

END FILE

## app/src/main/res/drawable/bg_button_ghost.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_pressed="true">
        <shape android:shape="rectangle">
            <solid android:color="@color/surface_chip" />
            <stroke android:width="1dp" android:color="@color/accent" />
            <corners android:radius="@dimen/card_radius_small" />
        </shape>
    </item>
    <item>
        <shape android:shape="rectangle">
            <solid android:color="@color/surface_card_raised" />
            <stroke android:width="1dp" android:color="@color/divider_strong" />
            <corners android:radius="@dimen/card_radius_small" />
        </shape>
    </item>
</selector>

END FILE

## app/src/main/res/drawable/bg_button_primary.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_pressed="true">
        <shape android:shape="rectangle">
            <solid android:color="@color/accent_pressed" />
            <corners android:radius="@dimen/card_radius_small" />
        </shape>
    </item>
    <item>
        <shape android:shape="rectangle">
            <solid android:color="@color/accent" />
            <corners android:radius="@dimen/card_radius_small" />
        </shape>
    </item>
</selector>

END FILE

## app/src/main/res/drawable/bg_card.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/surface_card" />
    <corners android:radius="@dimen/card_radius" />
</shape>

END FILE

## app/src/main/res/drawable/bg_card_raised.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/surface_card_raised" />
    <corners android:radius="@dimen/card_radius" />
</shape>

END FILE

## app/src/main/res/drawable/bg_chip.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/surface_chip" />
    <corners android:radius="@dimen/card_radius_small" />
</shape>

END FILE

## app/src/main/res/drawable/bg_circle_accent.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_pressed="true">
        <shape android:shape="oval">
            <solid android:color="@color/accent_pressed" />
        </shape>
    </item>
    <item>
        <shape android:shape="oval">
            <solid android:color="@color/accent" />
        </shape>
    </item>
</selector>

END FILE

## app/src/main/res/drawable/bg_circle_control.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!--
  Round transport / map control button.

  A circle rather than a rounded rect because the design's play button is a
  circle and a 44 dp round target is what a thumb actually hits on a car
  screen while the car is moving.
-->
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_pressed="true">
        <shape android:shape="oval">
            <solid android:color="@color/surface_chip" />
            <stroke android:width="1dp" android:color="@color/accent" />
        </shape>
    </item>
    <item>
        <shape android:shape="oval">
            <solid android:color="@color/surface_card_raised" />
            <stroke android:width="1dp" android:color="@color/divider_strong" />
        </shape>
    </item>
</selector>

END FILE

## app/src/main/res/drawable/bg_circle_ring.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!--
  Play/pause button surface: a hollow ring, not a filled disc.

  Measured on two independent panels of docs/UI_design.png:

    Home card      ring 45 px across, centred at x~656 on the music card
    Music screen   ring 54 px across, bbox (234,669)-(287,722)

  In both, a horizontal scan through the vertical centre finds the row as
  background / thin bright edge / background / white glyph bars / background /
  thin bright edge / background — i.e. the interior is the same navy as the
  panel, and only a hairline circle is drawn. The glyph inside is pure white
  rgb(252,253,251).

  This project previously used @drawable/bg_circle_accent here: a solid
  @color/accent disc. That reads as "the app's action button", which is not
  what the sheet draws on either screen — the design keeps the transport
  controls quiet and lets the album art carry the colour.

  The stroke is 2dp. In the sheet the ring is ~1 px at a panel scale of about
  1.38 (1024 / 742), so 1.4dp of true width; 2dp is the nearest whole value and
  the difference is under one pixel at the target density. The ring colour is
  @color/text_primary because the sampled edge pixels are a light near-white
  over the dark navy — a saturated accent would have shown as a blue-dominant
  edge, and it does not.

  Deliberately NOT reused by the 6dp "now playing" dot on a list row
  (view_music_row.xml), which stays a filled @drawable/bg_circle_accent: a
  hollow 6dp ring would be invisible.
-->
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_pressed="true">
        <shape android:shape="oval">
            <solid android:color="@android:color/transparent" />
            <stroke
                android:width="2dp"
                android:color="@color/text_secondary" />
        </shape>
    </item>
    <item>
        <shape android:shape="oval">
            <solid android:color="@android:color/transparent" />
            <stroke
                android:width="2dp"
                android:color="@color/text_primary" />
        </shape>
    </item>
</selector>

END FILE

## app/src/main/res/drawable/bg_eta_strip.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!--
  The ETA strip under the maneuver card.

  Surface is the panel's own raised navy, NOT a green fill. The design sheet
  draws this band as rgb(1,22,36) — the same near-black navy as the cards above
  it — with only the duration "12 phút" in green rgb(2,235,110). Measured on the
  Navigation panel: the band's dominant colour is rgb(1,22,36) across every
  scanned row (y 320–356), and the only saturated pixels in the whole band are
  318 green ones inside the duration text, bbox x814–1136.

  An earlier version filled the entire strip with @color/success and set the
  text white, which read as a solid green bar under a dark card. That inverted
  the design: the colour belongs on the number being read at a glance, not on
  the surface behind it.

  Corners: square on the left, rounded on the right, so the strip continues the
  card above it.
-->
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/surface_card_raised" />
    <corners
        android:topLeftRadius="0dp"
        android:topRightRadius="@dimen/card_radius_small"
        android:bottomLeftRadius="0dp"
        android:bottomRightRadius="@dimen/card_radius_small" />
</shape>

END FILE

## app/src/main/res/drawable/bg_input_field.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!--
  A text field on a card.

  Rounder than the card's own corner and stroked with `divider_strong`, so an
  empty field still reads as an input on a 1024×600 panel where a 1 px divider
  all but disappears at arm's length. The search field on the music screen uses
  the pill variant (`bg_search_field`); this one is the settings form variant,
  where four stacked fields at 26 dp radius would look like four buttons.
-->
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/surface_card_raised" />
    <stroke android:width="1dp" android:color="@color/divider_strong" />
    <corners android:radius="8dp" />
</shape>

END FILE

## app/src/main/res/drawable/bg_list_item.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_pressed="true">
        <shape android:shape="rectangle">
            <solid android:color="@color/surface_chip" />
            <corners android:radius="@dimen/card_radius_small" />
        </shape>
    </item>
    <item android:state_activated="true">
        <shape android:shape="rectangle">
            <solid android:color="@color/surface_card_raised" />
            <corners android:radius="@dimen/card_radius_small" />
        </shape>
    </item>
    <item>
        <shape android:shape="rectangle">
            <solid android:color="@android:color/transparent" />
            <corners android:radius="@dimen/card_radius_small" />
        </shape>
    </item>
</selector>

END FILE

## app/src/main/res/drawable/bg_parking.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!--
  Parking backdrop: a vertical ramp from near-black at the top to a deep blue
  at the bottom, which is what the design's dusk photograph reads as once it is
  reduced to something the app can legally ship.

  A gradient rather than a bundled photo, deliberately. The design's image is a
  licensed photograph; drawing a mountain silhouette to imitate it would be a
  fake asset presented as the real one. A gradient makes no claim at all.
-->
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <gradient
        android:angle="270"
        android:startColor="@color/bg_root"
        android:centerColor="@color/bg_window"
        android:endColor="#FF122440"
        android:type="linear" />
</shape>

END FILE

## app/src/main/res/drawable/bg_search_field.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/surface_card_raised" />
    <stroke android:width="1dp" android:color="@color/divider_strong" />
    <corners android:radius="26dp" />
</shape>

END FILE

## app/src/main/res/drawable/bg_speed_card.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/surface_scrim" />
    <corners android:radius="@dimen/card_radius_small" />
</shape>

END FILE

## app/src/main/res/drawable/bg_speed_limit.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- The European speed-limit roundel the design draws: a thick red ring with a
     white field. Drawn, not shipped as a PNG, so it stays crisp at any density
     and its colour stays tied to the palette token. -->
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <solid android:color="@color/speed_limit_fill" />
    <stroke android:width="7dp" android:color="@color/speed_limit_ring" />
</shape>

END FILE

## app/src/main/res/drawable/bg_tab_pill.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!--
  Background for a library tab: an outlined pill when selected, nothing when
  not.

  The design sheet draws the selected tab as a rounded rectangle with a 1 px
  accent hairline and a brighter underscore inside it, and leaves the other
  three as bare labels. This project had no background at all — selection only
  swapped the label colour — so the row read as four equal words with one
  slightly bluer.

  Why a selector and not a TabLayout: the comment in @color/music_tab_tint used
  to argue a TabLayout was wrong because "the design has no indicator bar". The
  design does have one — the underscore is visible in the sheet at 5x. What the
  design does not have is TabLayout's ripple and its sliding indicator, so the
  outline and underscore are drawn here as a static shape instead.

  The pill's corner radius matches card_radius_small so the tab reads as the
  same family as the row highlight beneath it.
-->
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_selected="true">
        <shape android:shape="rectangle">
            <solid android:color="@color/accent_soft" />
            <stroke
                android:width="1dp"
                android:color="@color/icon_active" />
            <corners android:radius="@dimen/card_radius_small" />
        </shape>
    </item>
    <item>
        <shape android:shape="rectangle">
            <solid android:color="@android:color/transparent" />
        </shape>
    </item>
</selector>

END FILE

## app/src/main/res/drawable/ic_battery.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M15.67,4H14V2h-4v2H8.33C7.6,4 7,4.6 7,5.33v15.33C7,21.4 7.6,22 8.33,22h7.33c0.74,0 1.34,-0.6 1.34,-1.34V5.33C17,4.6 16.4,4 15.67,4z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_bluetooth.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M17.71,7.71L12,2h-1v7.59L6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 11,14.41V22h1l5.71,-5.71L13.41,12l4.3,-4.29zM13,5.83l1.88,1.88L13,9.59V5.83zM14.88,16.29L13,18.17v-3.76l1.88,1.88z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_brightness.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M12,7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5 5,-2.24 5,-5 -2.24,-5 -5,-5zM2,13h2c0.55,0 1,-0.45 1,-1s-0.45,-1 -1,-1H2c-0.55,0 -1,0.45 -1,1s0.45,1 1,1zM20,13h2c0.55,0 1,-0.45 1,-1s-0.45,-1 -1,-1h-2c-0.55,0 -1,0.45 -1,1s0.45,1 1,1zM11,2v2c0,0.55 0.45,1 1,1s1,-0.45 1,-1V2c0,-0.55 -0.45,-1 -1,-1s-1,0.45 -1,1zM11,20v2c0,0.55 0.45,1 1,1s1,-0.45 1,-1v-2c0,-0.55 -0.45,-1 -1,-1s-1,0.45 -1,1zM5.99,4.58c-0.39,-0.39 -1.03,-0.39 -1.41,0 -0.39,0.39 -0.39,1.03 0,1.41l1.06,1.06c0.39,0.39 1.03,0.39 1.41,0s0.39,-1.03 0,-1.41L5.99,4.58zM18.36,16.95c-0.39,-0.39 -1.03,-0.39 -1.41,0 -0.39,0.39 -0.39,1.03 0,1.41l1.06,1.06c0.39,0.39 1.03,0.39 1.41,0 0.39,-0.39 0.39,-1.03 0,-1.41l-1.06,-1.06zM19.42,5.99c0.39,-0.39 0.39,-1.03 0,-1.41 -0.39,-0.39 -1.03,-0.39 -1.41,0l-1.06,1.06c-0.39,0.39 -0.39,1.03 0,1.41s1.03,0.39 1.41,0l1.06,-1.06zM7.05,18.36c0.39,-0.39 0.39,-1.03 0,-1.41 -0.39,-0.39 -1.03,-0.39 -1.41,0l-1.06,1.06c-0.39,0.39 -0.39,1.03 0,1.41s1.03,0.39 1.41,0l1.06,-1.06z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_chevron_left.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M15.41,7.41L14,6l-6,6 6,6 1.41,-1.41L10.83,12z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_chevron_right.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_close.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_compass.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM14.5,14.5L6,18l3.5,-8.5L18,6l-3.5,8.5z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_expand.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M15,3l2.3,2.3 -2.89,2.87 1.42,1.42L18.7,6.7 21,9V3zM3,9l2.3,-2.3 2.87,2.89 1.42,-1.42L6.7,5.3 9,3H3zM9,21l-2.3,-2.3 2.89,-2.87 -1.42,-1.42L5.3,17.3 3,15v6zM21,15l-2.3,2.3 -2.87,-2.89 -1.42,1.42 2.89,2.87L15,21h6z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_folder.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_heart.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_heart_filled.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_launcher_foreground.xml

BEGIN FILE
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <!-- Background plate -->
    <path
        android:fillColor="#0A1120"
        android:pathData="M0,0h108v108h-108z" />
    <!-- Road ribbon, echoing the home map -->
    <path
        android:fillColor="#1B2740"
        android:pathData="M30,108 C42,74 62,52 108,38 L108,108 Z" />
    <path
        android:fillColor="#3B82F6"
        android:pathData="M38,108 C48,80 66,60 108,46 L108,56 C70,68 54,86 46,108 Z" />
    <!-- Navigation arrow -->
    <path
        android:fillColor="#F2F6FC"
        android:pathData="M54,34 L70,66 L54,59 L38,66 Z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_layers.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M11.99,18.54l-7.37,-5.73L3,14.07l9,7 9,-7 -1.63,-1.27 -7.38,5.74zM12,16l7.36,-5.73L21,9l-9,-7 -9,7 1.63,1.27L12,16z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_maneuver_arrive.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M12,2 C8.13,2 5,5.13 5,9 C5,14.25 12,22 12,22 C12,22 19,14.25 19,9 C19,5.13 15.87,2 12,2 Z M12,11.5 C10.62,11.5 9.5,10.38 9.5,9 C9.5,7.62 10.62,6.5 12,6.5 C13.38,6.5 14.5,7.62 14.5,9 C14.5,10.38 13.38,11.5 12,11.5 Z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_maneuver_depart.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M12,2 L19,11 L14.5,11 L14.5,22 L9.5,22 L9.5,11 L5,11 Z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_maneuver_left.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M2,9 L11,2 L11,6.2 L15.6,6.2 C18.03,6.2 20,8.17 20,10.6 L20,22 L15,22 L15,11.2 C15,10.54 14.46,10 13.8,10 L11,10 L11,14 Z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_maneuver_right.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M22,9 L13,2 L13,6.2 L8.4,6.2 C5.97,6.2 4,8.17 4,10.6 L4,22 L9,22 L9,11.2 C9,10.54 9.54,10 10.2,10 L13,10 L13,14 Z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_maneuver_roundabout.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M12,2 C7.03,2 3,6.03 3,11 C3,15.03 5.61,18.44 9.25,19.58 L9.25,17.4 C6.71,16.36 5,13.88 5,11 C5,7.13 8.13,4 12,4 C15.87,4 19,7.13 19,11 C19,13.88 17.29,16.36 14.75,17.4 L14.75,19.58 C18.39,18.44 21,15.03 21,11 C21,6.03 16.97,2 12,2 Z M9.75,13 L9.75,22 L14.25,22 L14.25,13 Z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_maneuver_slight_left.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M4,3 L3,13.5 L6.9,12.1 L12.4,20.6 C12.64,20.96 13,21.15 13.42,21.15 L21,21.15 L21,16.15 L14.8,16.15 L9.4,7.8 L13,6.4 Z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_maneuver_slight_right.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M20,3 L21,13.5 L17.1,12.1 L11.6,20.6 C11.36,20.96 11,21.15 10.58,21.15 L3,21.15 L3,16.15 L9.2,16.15 L14.6,7.8 L11,6.4 Z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_maneuver_straight.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M12,2 L19,11 L14.5,11 L14.5,22 L9.5,22 L9.5,11 L5,11 Z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_maneuver_uturn.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M7,21 L7,10 C7,6.69 9.69,4 13,4 C16.31,4 19,6.69 19,10 L19,15 L22,15 L17.5,21 L13,15 L16,15 L16,10 C16,8.34 14.66,7 13,7 C11.34,7 10,8.34 10,10 L10,21 Z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_mic.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17,11c0,3 -2.54,5.1 -5,5.1S7,14 7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-2z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_minus.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M19,13H5v-2h14v2z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_moon.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M20.5,14.5A8.5,8.5 0 0,1 9.5,3.5 8.5,8.5 0 1,0 20.5,14.5Z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_mute.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v2.21l2.45,2.45c0.03,-0.2 0.05,-0.41 0.05,-0.63zM19,12c0,0.94 -0.2,1.82 -0.54,2.64l1.51,1.51C20.63,14.91 21,13.5 21,12c0,-3.87 -2.99,-7.07 -7,-7.45v2.06c2.89,0.86 5,3.54 5,6.71zM4.27,3L3,4.27 7.73,9H3v6h4l5,5v-6.73l4.25,4.25c-0.67,0.52 -1.42,0.93 -2.25,1.19v2.06c1.38,-0.31 2.63,-0.95 3.69,-1.81L19.73,21 21,19.73l-9,-9L4.27,3zM12,4L9.91,6.09 12,8.18V4z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_nav_apps.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M4,8h4V4H4v4zM10,20h4v-4h-4v4zM4,20h4v-4H4v4zM4,14h4v-4H4v4zM10,14h4v-4h-4v4zM16,4v4h4V4h-4zM10,8h4V4h-4v4zM16,14h4v-4h-4v4zM16,20h4v-4h-4v4z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_nav_home.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_nav_map.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M12,2 4.5,20.29l0.71,0.71L12,18l6.79,3 0.71,-0.71z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_nav_music.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_nav_phone.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M6.62,10.79c1.44,2.83 3.76,5.14 6.59,6.59l2.2,-2.2c0.27,-0.27 0.67,-0.36 1.02,-0.24 1.12,0.37 2.33,0.57 3.57,0.57 0.55,0 1,0.45 1,1V20c0,0.55 -0.45,1 -1,1 -9.39,0 -17,-7.61 -17,-17 0,-0.55 0.45,-1 1,-1h3.5c0.55,0 1,0.45 1,1 0,1.25 0.2,2.45 0.57,3.57 0.11,0.35 0.03,0.74 -0.25,1.02l-2.2,2.2z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_next.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_parking.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M6,3h7.5C16.54,3 19,5.46 19,8.5S16.54,14 13.5,14H10v7H6V3zM10,7v3h3.5C14.33,10 15,9.33 15,8.5S14.33,7 13.5,7H10z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_pause.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M6,19h4V5H6v14zM14,5v14h4V5h-4z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_pin.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M12,2C8.13,2 5,5.13 5,9c0,5.25 7,13 7,13s7,-7.75 7,-13c0,-3.87 -3.13,-7 -7,-7zM12,11.5c-1.38,0 -2.5,-1.12 -2.5,-2.5s1.12,-2.5 2.5,-2.5 2.5,1.12 2.5,2.5 -1.12,2.5 -2.5,2.5z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_play.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M8,5v14l11,-7z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_plus.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_power.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M13,3h-2v10h2V3zM17.83,5.17l-1.42,1.42C17.99,7.86 19,9.81 19,12c0,3.87 -3.13,7 -7,7s-7,-3.13 -7,-7c0,-2.19 1.01,-4.14 2.58,-5.42L6.17,5.17C4.23,6.82 3,9.26 3,12c0,4.97 4.03,9 9,9s9,-4.03 9,-9c0,-2.74 -1.23,-5.18 -3.17,-6.83z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_prev.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M6,6h2v12H6zM9.5,12l8.5,6V6z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_queue.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M4,14h2v-2H4v2zM4,18h2v-2H4v2zM4,10h2V8H4v2zM8,14h12v-2H8v2zM8,18h12v-2H8v2zM8,8v2h12V8H8z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_refresh.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -8,8s3.58,8 8,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_repeat.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M7,7h10v3l4,-4 -4,-4v3H5v6h2V7zM17,17H7v-3l-4,4 4,4v-3h12v-6h-2v4z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_route.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M19,15.18V7c0,-2.76 -2.24,-5 -5,-5s-5,2.24 -5,5v10c0,1.66 -1.34,3 -3,3s-3,-1.34 -3,-3V5H1v12c0,2.76 2.24,5 5,5s5,-2.24 5,-5V7c0,-1.66 1.34,-3 3,-3s3,1.34 3,3v8.18c-1.16,0.41 -2,1.51 -2,2.82 0,1.66 1.34,3 3,3s3,-1.34 3,-3c0,-1.31 -0.84,-2.41 -2,-2.82z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_search.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_settings.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94 0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6 3.6,1.62 3.6,3.6 -1.62,3.6 -3.6,3.6z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_shuffle.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M10.59,9.17L5.41,4 4,5.41l5.17,5.17 1.42,-1.41zM14.5,4l2.04,2.04L4,18.59 5.41,20 17.96,7.46 20,9.5V4h-5.5zM14.83,13.41l-1.41,1.41 3.13,3.13L14.5,20H20v-5.5l-2.04,2.04 -3.13,-3.13z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_signal.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M2,22h20V2z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_volume.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_warning.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M1,21h22L12,2 1,21zM13,18h-2v-2h2v2zM13,14h-2v-4h2v4z" />
</vector>

END FILE

## app/src/main/res/drawable/ic_wifi.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/ui/gen_icons.py — edit the table there, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M1,9l2,2c4.97,-4.97 13.03,-4.97 18,0l2,-2C16.93,2.93 7.08,2.93 1,9zM9,17l3,3 3,-3c-1.65,-1.66 -4.34,-1.66 -6,0zM5,13l2,2c2.76,-2.76 7.24,-2.76 10,0l2,-2C15.14,9.14 8.87,9.14 5,13z" />
</vector>

END FILE

## app/src/main/res/drawable/progress_music.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!-- Seek bar for the music card. `progressDrawable` replaces the whole bar, so
     the background and the accent fill are both defined here. -->
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:id="@android:id/background">
        <shape android:shape="rectangle">
            <solid android:color="@color/divider_strong" />
            <corners android:radius="3dp" />
            <size android:height="4dp" />
        </shape>
    </item>
    <item android:id="@android:id/progress">
        <clip>
            <shape android:shape="rectangle">
                <solid android:color="@color/accent" />
                <corners android:radius="3dp" />
                <size android:height="4dp" />
            </shape>
        </clip>
    </item>
</layer-list>

END FILE

## app/src/main/res/drawable/seek_thumb.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <solid android:color="@color/accent" />
    <size android:width="14dp" android:height="14dp" />
</shape>

END FILE

## app/src/main/res/layout/activity_app_drawer.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!--
  Apps — design state 5, the app drawer.

  A grid of the apps actually installed on the unit, each launched with its own
  launch intent. Nothing here is a hard-coded tile: the design shows Maps,
  Music, Phone, Radio, Camera, Settings, Bluetooth, Chrome, YouTube, Files, EQ
  and Car Info, and what appears depends entirely on what the firmware ships.
  When an app is missing the tile is simply absent — the alternative, a tile
  that opens nothing, is the failure mode the spec calls out.

  The search field exists because a launchable list on this unit can run to
  several screens and scrolling while driving is worse than typing.
-->
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/bg_root">

    <com.ts8.launcher.ui.StatusBarView
        android:id="@+id/statusBar"
        android:layout_width="0dp"
        android:layout_height="@dimen/status_height"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <!-- Title and filter, on one row so the grid gets the rest of the height. -->
    <LinearLayout
        android:id="@+id/appsHeader"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:paddingStart="@dimen/screen_pad"
        android:paddingEnd="@dimen/screen_pad"
        android:paddingTop="@dimen/gap_small"
        android:paddingBottom="@dimen/gap_small"
        app:layout_constraintTop_toBottomOf="@id/statusBar"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <TextView
            style="@style/Text.Title"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/apps_title" />

        <LinearLayout
            android:layout_width="0dp"
            android:layout_height="44dp"
            android:layout_weight="1"
            android:layout_marginStart="@dimen/gap"
            android:orientation="horizontal"
            android:gravity="center_vertical"
            android:paddingStart="@dimen/gap"
            android:paddingEnd="@dimen/gap"
            android:background="@drawable/bg_search_field">

            <ImageView
                android:layout_width="18dp"
                android:layout_height="18dp"
                android:src="@drawable/ic_search"
                app:tint="@color/text_tertiary"
                android:contentDescription="@null" />

            <EditText
                android:id="@+id/appsSearch"
                android:layout_width="0dp"
                android:layout_height="match_parent"
                android:layout_weight="1"
                android:layout_marginStart="@dimen/gap_small"
                android:background="@null"
                android:hint="@string/apps_search_hint"
                android:textColorHint="@color/text_tertiary"
                android:textColor="@color/text_primary"
                android:textSize="@dimen/text_body"
                android:inputType="text"
                android:imeOptions="actionSearch"
                android:maxLines="1"
                android:singleLine="true"
                android:importantForAutofill="no" />
        </LinearLayout>
    </LinearLayout>

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/appsGrid"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:paddingStart="@dimen/screen_pad"
        android:paddingEnd="@dimen/screen_pad"
        android:paddingBottom="@dimen/screen_pad"
        android:clipToPadding="false"
        android:scrollbars="none"
        app:layout_constraintTop_toBottomOf="@id/appsHeader"
        app:layout_constraintBottom_toTopOf="@id/bottomNav"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <LinearLayout
        android:id="@+id/appsEmpty"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:orientation="vertical"
        android:gravity="center"
        android:visibility="gone"
        app:layout_constraintTop_toBottomOf="@id/appsHeader"
        app:layout_constraintBottom_toTopOf="@id/bottomNav"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        tools:visibility="visible">

        <ImageView
            android:layout_width="40dp"
            android:layout_height="40dp"
            android:src="@drawable/ic_nav_apps"
            app:tint="@color/text_tertiary"
            android:contentDescription="@null" />

        <TextView
            android:id="@+id/appsEmptyText"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="@dimen/gap_small"
            android:textColor="@color/text_secondary"
            android:textSize="@dimen/text_body"
            android:text="@string/apps_empty" />
    </LinearLayout>

    <com.ts8.launcher.ui.BottomNavView
        android:id="@+id/bottomNav"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />
</androidx.constraintlayout.widget.ConstraintLayout>

END FILE

## app/src/main/res/layout/activity_home.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!--
  Home — design state 1.

  `ConstraintLayout` rather than nested LinearLayouts because the map must fill
  *all* space the right column does not take, at any width from 1024x600 up, and
  must be able to grow under the floating speed and zoom controls. Constraints
  express that in one place; weights across nested rows do not.

  Nothing here is a placeholder. `mapHost` is the real MapView container (the
  view is added in code, on the Activity's lifecycle, by `MapHost`),
  `musicCard` is a real `MusicCardView` reading the same `PlaybackState` the
  music screen does, and the speed chip reads the location pipeline's own speed.
-->
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/bg_root">

    <!-- ================================================================
         Status row
         ================================================================ -->
    <com.ts8.launcher.ui.StatusBarView
        android:id="@+id/statusBar"
        android:layout_width="0dp"
        android:layout_height="@dimen/status_height"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <!-- ================================================================
         Map, left/centre
         ================================================================ -->
    <FrameLayout
        android:id="@+id/mapHost"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:layout_marginStart="@dimen/gap"
        android:layout_marginTop="@dimen/gap_small"
        android:layout_marginEnd="@dimen/gap"
        android:layout_marginBottom="@dimen/gap_small"
        android:background="@color/surface_card"
        app:layout_constraintTop_toBottomOf="@id/statusBar"
        app:layout_constraintBottom_toTopOf="@id/bottomNav"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toStartOf="@id/musicCard">

        <!--
          The MapView is inserted at index 0 here, so every sibling below stays
          above it without any elevation tricks.
        -->

        <!-- Shown instead of the map when the pack is missing or unreadable.
             A real message, never a drawn fake map. The two actions are the
             difference between "the map is broken" and a driver who can fix it:
             one opens Map Management to import, the other re-runs discovery
             after a USB stick was plugged in. -->
        <LinearLayout
            android:id="@+id/mapPlaceholder"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:orientation="vertical"
            android:gravity="center"
            android:visibility="gone"
            tools:visibility="visible">

            <ImageView
                android:layout_width="40dp"
                android:layout_height="40dp"
                android:src="@drawable/ic_warning"
                app:tint="@color/text_tertiary"
                android:contentDescription="@null" />

            <TextView
                android:id="@+id/mapPlaceholderText"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="@dimen/gap_small"
                android:paddingStart="24dp"
                android:paddingEnd="24dp"
                android:gravity="center"
                android:maxLines="4"
                android:ellipsize="end"
                android:textColor="@color/text_secondary"
                android:textSize="@dimen/text_body"
                android:text="@string/home_map_unavailable" />

            <LinearLayout
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="@dimen/gap"
                android:orientation="horizontal">

                <Button
                    android:id="@+id/btnPlaceholderImport"
                    android:layout_width="wrap_content"
                    android:layout_height="44dp"
                    android:minWidth="132dp"
                    android:background="@drawable/bg_button_primary"
                    android:textColor="@color/text_on_accent"
                    android:textAllCaps="false"
                    android:textSize="@dimen/text_body"
                    android:text="@string/map_cta_install" />

                <Button
                    android:id="@+id/btnPlaceholderRetry"
                    android:layout_width="wrap_content"
                    android:layout_height="44dp"
                    android:layout_marginStart="@dimen/gap"
                    android:minWidth="120dp"
                    android:background="@drawable/bg_chip"
                    android:textColor="@color/text_primary"
                    android:textAllCaps="false"
                    android:textSize="@dimen/text_body"
                    android:text="@string/map_cta_retry" />
            </LinearLayout>
        </LinearLayout>

        <!-- Speed chip, top-left of the map as the design places it. -->
        <LinearLayout
            android:id="@+id/speedChip"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="top|start"
            android:layout_margin="@dimen/gap_small"
            android:orientation="vertical"
            android:gravity="center"
            android:minWidth="72dp"
            android:padding="@dimen/gap_small"
            android:background="@drawable/bg_speed_card">

            <TextView
                android:id="@+id/speedValue"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textColor="@color/text_primary"
                android:textSize="@dimen/text_display"
                android:includeFontPadding="false"
                android:text="0"
                tools:text="48" />

            <TextView
                android:id="@+id/speedUnit"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textColor="@color/text_tertiary"
                android:textSize="@dimen/text_title"
                android:text="@string/home_speed_unit" />
        </LinearLayout>

        <!--
          Speed-limit roundel, drawn to the design's shape but hidden unless the
          pack actually carries a limit for the road under the car. The design
          shows 60; printing 60 everywhere would be a fabricated limit, which
          the spec forbids — the Đà Nẵng extract tags `maxspeed` on only ~6 % of
          highway ways, so in practice this stays hidden. See
          docs/KNOWN_LIMITATIONS.md.
        -->
        <FrameLayout
            android:id="@+id/speedLimit"
            android:layout_width="64dp"
            android:layout_height="64dp"
            android:layout_gravity="top|start"
            android:layout_marginStart="96dp"
            android:layout_marginTop="@dimen/gap_small"
            android:visibility="gone"
            tools:visibility="visible">

            <View
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:background="@drawable/bg_speed_limit" />

            <TextView
                android:id="@+id/speedLimitValue"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:gravity="center"
                android:textColor="@color/speed_limit_ring"
                android:textSize="@dimen/text_title"
                android:textStyle="bold"
                tools:text="60" />
        </FrameLayout>

        <!-- Compass / zoom / layers, right edge as in the design. -->
        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="top|end"
            android:layout_margin="@dimen/gap_small"
            android:orientation="vertical">

            <ImageButton
                android:id="@+id/btnRecenter"
                android:layout_width="@dimen/map_control_size"
                android:layout_height="@dimen/map_control_size"
                android:background="@drawable/bg_circle_control"
                android:src="@drawable/ic_compass"
                app:tint="@color/text_primary"
                android:contentDescription="@string/home_recenter" />

            <ImageButton
                android:id="@+id/btnZoomIn"
                android:layout_width="@dimen/map_control_size"
                android:layout_height="@dimen/map_control_size"
                android:layout_marginTop="@dimen/gap_small"
                android:background="@drawable/bg_circle_control"
                android:src="@drawable/ic_plus"
                app:tint="@color/text_primary"
                android:contentDescription="@string/home_zoom_in" />

            <ImageButton
                android:id="@+id/btnZoomOut"
                android:layout_width="@dimen/map_control_size"
                android:layout_height="@dimen/map_control_size"
                android:layout_marginTop="@dimen/gap_small"
                android:background="@drawable/bg_circle_control"
                android:src="@drawable/ic_minus"
                app:tint="@color/text_primary"
                android:contentDescription="@string/home_zoom_out" />

            <ImageButton
                android:id="@+id/btnLayers"
                android:layout_width="@dimen/map_control_size"
                android:layout_height="@dimen/map_control_size"
                android:layout_marginTop="@dimen/gap_small"
                android:background="@drawable/bg_circle_control"
                android:src="@drawable/ic_layers"
                app:tint="@color/text_primary"
                android:contentDescription="@string/home_layers" />
        </LinearLayout>

        <!-- §16: "Khi quay Home trong lúc navigation, Home hiển thị
             navigation-aware layout". A strip above the search field, gone
             unless guidance is actually running, showing the next instruction
             and the distance to it — read from the navigator, never a sample.
             Tapping it returns to the navigation screen.

             Placed here rather than in a second window so it cannot be left
             behind over the map, and constrained above the search field so it
             never covers the one control that opens search. -->
        <LinearLayout
            android:id="@+id/navStrip"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_gravity="bottom"
            android:layout_marginStart="@dimen/gap"
            android:layout_marginEnd="@dimen/gap"
            android:layout_marginBottom="64dp"
            android:orientation="horizontal"
            android:gravity="center_vertical"
            android:paddingStart="@dimen/gap"
            android:paddingEnd="@dimen/gap"
            android:paddingTop="@dimen/gap_small"
            android:paddingBottom="@dimen/gap_small"
            android:background="@drawable/bg_eta_strip"
            android:visibility="gone"
            tools:visibility="visible">

            <ImageView
                android:id="@+id/navStripIcon"
                android:layout_width="24dp"
                android:layout_height="24dp"
                android:src="@drawable/ic_maneuver_straight"
                app:tint="@color/accent"
                android:contentDescription="@null" />

            <TextView
                android:id="@+id/navStripText"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:layout_marginStart="@dimen/gap_small"
                android:textColor="@color/text_primary"
                android:textSize="@dimen/text_body"
                android:maxLines="2"
                android:ellipsize="end"
                tools:text="Rẽ phải vào Nguyễn Văn Linh · 320 m" />

            <ImageView
                android:layout_width="20dp"
                android:layout_height="20dp"
                android:src="@drawable/ic_chevron_right"
                app:tint="@color/text_tertiary"
                android:contentDescription="@null" />
        </LinearLayout>

        <!-- Search entry, bottom of the map. Opens the search screen. -->
        <LinearLayout
            android:id="@+id/searchField"
            android:layout_width="match_parent"
            android:layout_height="48dp"
            android:layout_gravity="bottom"
            android:layout_margin="@dimen/gap"
            android:orientation="horizontal"
            android:gravity="center_vertical"
            android:paddingStart="@dimen/gap"
            android:paddingEnd="@dimen/gap_small"
            android:background="@drawable/bg_search_field">

            <ImageView
                android:layout_width="20dp"
                android:layout_height="20dp"
                android:src="@drawable/ic_search"
                app:tint="@color/text_tertiary"
                android:contentDescription="@null" />

            <TextView
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:layout_marginStart="@dimen/gap_small"
                android:textColor="@color/text_tertiary"
                android:textSize="@dimen/text_body"
                android:text="@string/search_hint" />

            <!-- Voice Instant's trigger: the design's microphone, now backed by
                 a real recogniser (`VoiceInstantController`). The field itself
                 still opens search on tap; this button is its own target so a
                 thumb aiming at the mic cannot open the keyboard instead. -->
            <ImageButton
                android:id="@+id/btnVoice"
                android:layout_width="40dp"
                android:layout_height="40dp"
                android:layout_marginEnd="@dimen/gap_small"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:src="@drawable/ic_mic"
                app:tint="@color/text_secondary"
                android:contentDescription="@string/voice_mic" />
        </LinearLayout>

        <!-- GPS waiting hint, shown until the first accepted fix. -->
        <TextView
            android:id="@+id/gpsHint"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="center_horizontal|top"
            android:layout_marginTop="@dimen/gap"
            android:paddingStart="@dimen/gap"
            android:paddingEnd="@dimen/gap"
            android:paddingTop="6dp"
            android:paddingBottom="6dp"
            android:background="@drawable/bg_chip"
            android:textColor="@color/text_secondary"
            android:textSize="@dimen/text_caption"
            android:text="@string/home_gps_searching"
            android:visibility="gone"
            tools:visibility="visible" />
    </FrameLayout>

    <!-- ================================================================
         Music card, right column
         ================================================================ -->
    <com.ts8.launcher.ui.MusicCardView
        android:id="@+id/musicCard"
        android:layout_width="@dimen/music_card_width"
        android:layout_height="0dp"
        android:layout_marginTop="@dimen/gap_small"
        android:layout_marginEnd="@dimen/gap"
        android:layout_marginBottom="@dimen/gap_small"
        app:layout_constraintTop_toBottomOf="@id/statusBar"
        app:layout_constraintBottom_toTopOf="@id/bottomNav"
        app:layout_constraintEnd_toEndOf="parent" />

    <!-- ================================================================
         Bottom navigation — the only navigation in the app.
         ================================================================ -->
    <com.ts8.launcher.ui.BottomNavView
        android:id="@+id/bottomNav"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />
</androidx.constraintlayout.widget.ConstraintLayout>

END FILE

## app/src/main/res/layout/activity_map.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!--
  Navigation — design states 2 and 3.

  The map is full-bleed under the chrome so the road ahead has the most pixels
  the panel can give it. Everything else sits in one of four overlays, and each
  one is shown by exactly one navigation state (see `MapActivity.render`):

  * the maneuver card, top-left, only while guiding;
  * the green ETA strip under it, only while guiding;
  * the destination card, bottom, only while previewing a route;
  * the compact music strip, right, which is the design's small card.

  The compact card uses the same `MusicCardView` as Home with `musicCompact`,
  so a change to the card cannot make the two screens disagree.

  There is no speed-limit roundel drawn permanently: it appears only when the
  map pack actually carries a limit for the current road. The design shows 60;
  this pack does not, and inventing it is forbidden. See KNOWN_LIMITATIONS.md.
-->
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/bg_root">

    <com.ts8.launcher.ui.StatusBarView
        android:id="@+id/statusBar"
        android:layout_width="0dp"
        android:layout_height="@dimen/status_height"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <!-- The map fills everything between the status row and the nav bar. -->
    <FrameLayout
        android:id="@+id/mapHost"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:background="@color/surface_card"
        app:layout_constraintTop_toBottomOf="@id/statusBar"
        app:layout_constraintBottom_toTopOf="@id/bottomNav"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <!-- Shown instead of the map when the pack is missing. -->
        <LinearLayout
            android:id="@+id/mapPlaceholder"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:orientation="vertical"
            android:gravity="center"
            android:visibility="gone"
            tools:visibility="visible">

            <ImageView
                android:layout_width="44dp"
                android:layout_height="44dp"
                android:src="@drawable/ic_warning"
                app:tint="@color/text_tertiary"
                android:contentDescription="@null" />

            <TextView
                android:id="@+id/mapPlaceholderText"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="@dimen/gap_small"
                android:paddingStart="32dp"
                android:paddingEnd="32dp"
                android:gravity="center"
                android:textColor="@color/text_secondary"
                android:textSize="@dimen/text_body"
                android:text="@string/nav_no_pack" />
        </LinearLayout>

        <!-- ============================================================
             Top-left stack: the maneuver card and the ETA strip under it.

             They live in one vertical container rather than each being
             placed by `layout_below`, because the card's height is
             `wrap_content` and depends on whether the "sau đó" line is
             shown. A fixed offset would leave a gap on some maneuvers and
             overlap the card on others.
             ============================================================ -->
        <LinearLayout
            android:id="@+id/topLeftStack"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="top|start"
            android:layout_margin="@dimen/gap"
            android:orientation="vertical">

        <!-- Maneuver card. Top-left, the design's largest element when
             guiding: distance on top, instruction under it, then the
             "sau đó" preview line.
             ============================================================ -->
        <LinearLayout
            android:id="@+id/maneuverCard"
            android:layout_width="@dimen/maneuver_card_width"
            android:layout_height="wrap_content"
            android:layout_gravity="top|start"
            android:orientation="horizontal"
            android:padding="@dimen/card_pad"
            android:background="@drawable/bg_card_raised"
            android:visibility="gone"
            tools:visibility="visible">

            <ImageView
                android:id="@+id/maneuverIcon"
                android:layout_width="56dp"
                android:layout_height="56dp"
                android:src="@drawable/ic_maneuver_straight"
                app:tint="@color/accent"
                android:contentDescription="@null" />

            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:layout_marginStart="@dimen/gap"
                android:orientation="vertical">

                <TextView
                    android:id="@+id/maneuverDistance"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textColor="@color/text_primary"
                    android:textSize="@dimen/text_headline"
                    android:fontFamily="sans-serif-medium"
                    android:includeFontPadding="false"
                    tools:text="300 m" />

                <TextView
                    android:id="@+id/maneuverInstruction"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="4dp"
                    android:textColor="@color/text_primary"
                    android:textSize="@dimen/text_title"
                    android:maxLines="2"
                    android:ellipsize="end"
                    tools:text="Rẽ phải vào Nguyễn Văn Linh" />

                <TextView
                    android:id="@+id/maneuverThen"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="6dp"
                    android:textColor="@color/text_tertiary"
                    android:textSize="@dimen/text_caption"
                    android:maxLines="1"
                    android:ellipsize="end"
                    android:visibility="gone"
                    tools:visibility="visible"
                    tools:text="Sau đó → Cầu Tân Thuận" />
            </LinearLayout>
        </LinearLayout>

        <!-- ============================================================
             ETA strip. Green when a route is active, because that is the
             one number the driver checks at a light.
             ============================================================ -->
        <TextView
            android:id="@+id/etaStrip"
            android:layout_width="wrap_content"
            android:layout_height="@dimen/eta_strip_height"
            android:layout_marginTop="@dimen/gap_small"
            android:paddingStart="@dimen/card_pad"
            android:paddingEnd="@dimen/card_pad"
            android:gravity="center_vertical"
            android:textColor="@color/text_primary"
            android:textSize="@dimen/text_body"
            android:background="@drawable/bg_eta_strip"
            android:visibility="gone"
            tools:visibility="visible"
            tools:text="12 phút • 6,2 km • Đến 17:36" />

        </LinearLayout>

        <!-- ============================================================
             Speed chip and the speed-limit roundel, bottom-left.

             The design puts the pair at the bottom of the map, left of
             centre, side by side: the chip is a rounded navy card, the
             roundel sits immediately to its right on the same baseline.
             Measured on the Navigation panel — chip left edge x~782, roundel
             centred x~915, both bottoms on y~285 in sheet coordinates.

             This project had both at `top|end`, which put them under the
             status bar in the opposite corner from the design. They also sat
             in two separate FrameLayout children, so the roundel needed a
             hand-tuned 96dp margin to clear the chip. A single horizontal
             container removes that coupling: the roundel's position is a
             consequence of the chip's width instead of a number that has to
             be re-tuned whenever the chip changes.

             Hidden independently — the chip needs only a speed, the roundel
             needs a limit from the pack for the current road.
             ============================================================ -->
        <LinearLayout
            android:id="@+id/speedCluster"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="bottom|start"
            android:layout_margin="@dimen/gap"
            android:layout_marginBottom="72dp"
            android:orientation="horizontal"
            android:gravity="bottom|start">

            <LinearLayout
                android:id="@+id/speedChip"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:gravity="center"
                android:minWidth="72dp"
                android:padding="@dimen/gap_small"
                android:background="@drawable/bg_speed_card"
                android:visibility="gone"
                tools:visibility="visible">

                <TextView
                    android:id="@+id/speedValue"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textColor="@color/text_primary"
                    android:textSize="@dimen/text_display"
                    android:includeFontPadding="false"
                    android:text="0" />

                <TextView
                    android:id="@+id/speedUnit"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textColor="@color/text_tertiary"
                    android:textSize="@dimen/text_title"
                    android:text="@string/home_speed_unit" />
            </LinearLayout>

            <!-- Real limit only; hidden when the pack has none for this road. -->
            <FrameLayout
                android:id="@+id/speedLimit"
                android:layout_width="64dp"
                android:layout_height="64dp"
                android:layout_marginStart="@dimen/gap_small"
                android:layout_marginBottom="@dimen/gap_small"
                android:visibility="gone">

                <View
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:background="@drawable/bg_speed_limit" />

                <TextView
                    android:id="@+id/speedLimitValue"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:gravity="center"
                    android:textColor="@color/speed_limit_ring"
                    android:textSize="@dimen/text_title"
                    android:textStyle="bold"
                    tools:text="60" />
            </FrameLayout>
        </LinearLayout>

        <!-- ============================================================
             Driving controls, right edge: mute, overview, end.
             ============================================================ -->
        <LinearLayout
            android:id="@+id/guidanceControls"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="center_vertical|end"
            android:layout_margin="@dimen/gap"
            android:orientation="vertical"
            android:visibility="gone"
            tools:visibility="visible">

            <ImageButton
                android:id="@+id/btnMute"
                android:layout_width="@dimen/map_control_size"
                android:layout_height="@dimen/map_control_size"
                android:background="@drawable/bg_circle_control"
                android:src="@drawable/ic_volume"
                app:tint="@color/text_primary"
                android:contentDescription="@string/nav_mute" />

            <ImageButton
                android:id="@+id/btnOverview"
                android:layout_width="@dimen/map_control_size"
                android:layout_height="@dimen/map_control_size"
                android:layout_marginTop="@dimen/gap_small"
                android:background="@drawable/bg_circle_control"
                android:src="@drawable/ic_expand"
                app:tint="@color/text_primary"
                android:contentDescription="@string/nav_overview" />

            <ImageButton
                android:id="@+id/btnEnd"
                android:layout_width="@dimen/map_control_size"
                android:layout_height="@dimen/map_control_size"
                android:layout_marginTop="@dimen/gap_small"
                android:background="@drawable/bg_circle_control"
                android:src="@drawable/ic_close"
                app:tint="@color/danger"
                android:contentDescription="@string/nav_end" />
        </LinearLayout>

        <!-- Free-navigation controls: zoom, recentre, layers. -->
        <LinearLayout
            android:id="@+id/browseControls"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="bottom|end"
            android:layout_margin="@dimen/gap"
            android:orientation="vertical">

            <ImageButton
                android:id="@+id/btnRecenter"
                android:layout_width="@dimen/map_control_size"
                android:layout_height="@dimen/map_control_size"
                android:background="@drawable/bg_circle_control"
                android:src="@drawable/ic_compass"
                app:tint="@color/text_primary"
                android:contentDescription="@string/home_recenter" />

            <ImageButton
                android:id="@+id/btnZoomIn"
                android:layout_width="@dimen/map_control_size"
                android:layout_height="@dimen/map_control_size"
                android:layout_marginTop="@dimen/gap_small"
                android:background="@drawable/bg_circle_control"
                android:src="@drawable/ic_plus"
                app:tint="@color/text_primary"
                android:contentDescription="@string/home_zoom_in" />

            <ImageButton
                android:id="@+id/btnZoomOut"
                android:layout_width="@dimen/map_control_size"
                android:layout_height="@dimen/map_control_size"
                android:layout_marginTop="@dimen/gap_small"
                android:background="@drawable/bg_circle_control"
                android:src="@drawable/ic_minus"
                app:tint="@color/text_primary"
                android:contentDescription="@string/home_zoom_out" />
        </LinearLayout>

        <!-- ============================================================
             Destination / route preview card, bottom-left.
             ============================================================ -->
        <LinearLayout
            android:id="@+id/destinationCard"
            android:layout_width="360dp"
            android:layout_height="wrap_content"
            android:layout_gravity="bottom|start"
            android:layout_margin="@dimen/gap"
            android:orientation="vertical"
            android:padding="@dimen/card_pad"
            android:background="@drawable/bg_card_raised"
            android:visibility="gone"
            tools:visibility="visible">

            <TextView
                android:id="@+id/destinationName"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:textColor="@color/text_primary"
                android:textSize="@dimen/text_title"
                android:textStyle="bold"
                android:maxLines="1"
                android:ellipsize="end"
                tools:text="Cầu Rồng" />

            <TextView
                android:id="@+id/destinationSummary"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:textColor="@color/text_secondary"
                android:textSize="@dimen/text_body"
                tools:text="6,2 km • khoảng 12 phút" />

            <TextView
                android:id="@+id/destinationMessage"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:textColor="@color/danger"
                android:textSize="@dimen/text_caption"
                android:visibility="gone"
                tools:visibility="visible"
                tools:text="Không có lộ trình lái xe tới điểm này." />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="@dimen/gap"
                android:orientation="horizontal">

                <Button
                    android:id="@+id/btnStart"
                    style="@style/Button.Primary"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="@string/nav_start_navigation" />

                <Button
                    android:id="@+id/btnCancelPreview"
                    style="@style/Button.Ghost"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="@dimen/gap_small"
                    android:text="@string/nav_cancel" />
            </LinearLayout>
        </LinearLayout>

        <!-- Search entry while idle: tap opens the search screen. -->
        <LinearLayout
            android:id="@+id/searchField"
            android:layout_width="match_parent"
            android:layout_height="48dp"
            android:layout_gravity="bottom"
            android:layout_margin="@dimen/gap"
            android:orientation="horizontal"
            android:gravity="center_vertical"
            android:paddingStart="@dimen/gap"
            android:paddingEnd="@dimen/gap_small"
            android:background="@drawable/bg_search_field">

            <ImageView
                android:layout_width="20dp"
                android:layout_height="20dp"
                android:src="@drawable/ic_search"
                app:tint="@color/text_tertiary"
                android:contentDescription="@null" />

            <TextView
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:layout_marginStart="@dimen/gap_small"
                android:textColor="@color/text_tertiary"
                android:textSize="@dimen/text_body"
                android:text="@string/search_hint" />

            <ImageButton
                android:id="@+id/btnVoice"
                android:layout_width="40dp"
                android:layout_height="40dp"
                android:layout_marginEnd="@dimen/gap_small"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:src="@drawable/ic_mic"
                app:tint="@color/text_secondary"
                android:contentDescription="@string/voice_mic" />
        </LinearLayout>

        <!-- Transient banner for states with no card: calculating, rerouting,
             arrival. Text is set by the Activity from the state itself. -->
        <TextView
            android:id="@+id/stateBanner"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="center_horizontal|top"
            android:layout_marginTop="@dimen/gap"
            android:paddingStart="@dimen/card_pad"
            android:paddingEnd="@dimen/card_pad"
            android:paddingTop="8dp"
            android:paddingBottom="8dp"
            android:background="@drawable/bg_chip"
            android:textColor="@color/text_primary"
            android:textSize="@dimen/text_body"
            android:visibility="gone"
            tools:visibility="visible"
            tools:text="@string/nav_calculating" />
    </FrameLayout>

    <!-- Compact music strip, the design's small card on the navigation screen. -->
    <com.ts8.launcher.ui.MusicCardView
        android:id="@+id/musicCard"
        android:layout_width="@dimen/music_card_width_compact"
        android:layout_height="0dp"
        android:layout_marginTop="@dimen/gap_small"
        android:layout_marginEnd="@dimen/gap"
        android:layout_marginBottom="@dimen/gap_small"
        app:musicCompact="true"
        app:layout_constraintTop_toBottomOf="@id/statusBar"
        app:layout_constraintBottom_toTopOf="@id/bottomNav"
        app:layout_constraintEnd_toEndOf="parent"
        android:visibility="gone"
        tools:visibility="gone" />

    <com.ts8.launcher.ui.BottomNavView
        android:id="@+id/bottomNav"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />
</androidx.constraintlayout.widget.ConstraintLayout>

END FILE

## app/src/main/res/layout/activity_map_management.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!--
  Map Management & settings — the screen §8 of the repair brief requires.

  It is not one of the design's seven states: it is the place a driver goes when
  the map is missing, and the place where the two settings groups the brief
  names (`Hiển thị`, `Tốc độ & cảnh báo`) actually live. Reached from the map
  placeholder's "Cài bản đồ", from Settings and from Quick Settings.

  Layout reasoning for 1024x600:
  * Everything scrolls in one column. A two-column layout would fit the settings
    but not the pack cards, and a mixed layout reflows badly on 600 dp height.
  * Rows are 44 dp minimum: below that a thumb misses while the car is moving.
  * Each setting is a label plus a row of chips (segmented control) built in
    code from `OptionRow`, so a new value is one string and one enum constant,
    not a new layout.
-->
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/bg_root">

    <com.ts8.launcher.ui.StatusBarView
        android:id="@+id/statusBar"
        android:layout_width="0dp"
        android:layout_height="@dimen/status_height"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <ImageButton
        android:id="@+id/btnBack"
        android:layout_width="44dp"
        android:layout_height="44dp"
        android:layout_marginStart="@dimen/gap_small"
        android:layout_marginTop="@dimen/gap_small"
        android:background="?attr/selectableItemBackgroundBorderless"
        android:src="@drawable/ic_chevron_left"
        app:tint="@color/text_secondary"
        android:contentDescription="@string/generic_back"
        app:layout_constraintTop_toBottomOf="@id/statusBar"
        app:layout_constraintStart_toStartOf="parent" />

    <TextView
        android:id="@+id/screenTitle"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="@dimen/gap_small"
        android:layout_marginEnd="@dimen/screen_pad"
        android:textColor="@color/text_primary"
        android:textSize="@dimen/text_title"
        android:fontFamily="sans-serif-medium"
        android:text="@string/map_mgmt_title"
        app:layout_constraintTop_toTopOf="@id/btnBack"
        app:layout_constraintBottom_toBottomOf="@id/btnBack"
        app:layout_constraintStart_toEndOf="@id/btnBack"
        app:layout_constraintEnd_toEndOf="parent" />

    <ScrollView
        android:id="@+id/contentScroll"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:layout_marginTop="@dimen/gap_small"
        android:layout_marginBottom="@dimen/gap_small"
        android:paddingStart="@dimen/screen_pad"
        android:paddingEnd="@dimen/screen_pad"
        android:scrollbars="none"
        app:layout_constraintTop_toBottomOf="@id/btnBack"
        app:layout_constraintBottom_toTopOf="@id/bottomNav"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical">

            <TextView
                style="@style/Text.Caption"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:paddingStart="@dimen/gap_small"
                android:paddingBottom="4dp"
                android:text="@string/map_section_packs" />

            <LinearLayout
                android:id="@+id/packList"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical" />

            <TextView
                android:id="@+id/packEmpty"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:padding="@dimen/card_pad"
                android:background="@drawable/bg_card"
                android:textColor="@color/text_secondary"
                android:textSize="@dimen/text_body"
                android:text="@string/map_pack_none"
                android:visibility="gone"
                tools:visibility="visible" />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="@dimen/gap_small"
                android:orientation="horizontal">

                <Button
                    android:id="@+id/btnImport"
                    style="@style/Button.Primary"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="@string/map_action_import" />

                <Button
                    android:id="@+id/btnRescan"
                    style="@style/Button.Ghost"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="@dimen/gap_small"
                    android:layout_weight="1"
                    android:text="@string/map_action_validate" />
            </LinearLayout>

            <TextView
                android:id="@+id/importStatus"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="@dimen/gap_small"
                android:paddingStart="@dimen/gap_small"
                android:paddingEnd="@dimen/gap_small"
                android:textColor="@color/text_secondary"
                android:textSize="@dimen/text_caption"
                android:visibility="gone"
                tools:text="Đang nhập map pack…"
                tools:visibility="visible" />

            <TextView
                style="@style/Text.Caption"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="@dimen/gap"
                android:paddingStart="@dimen/gap_small"
                android:paddingBottom="4dp"
                android:text="@string/map_section_storage" />

            <TextView
                android:id="@+id/storageText"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:padding="@dimen/card_pad"
                android:background="@drawable/bg_card"
                android:textColor="@color/text_secondary"
                android:textSize="@dimen/text_caption"
                android:lineSpacingExtra="2dp"
                tools:text="Vị trí lưu: /data/user/0/…/map-pack" />

            <TextView
                style="@style/Text.Caption"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="@dimen/gap"
                android:paddingStart="@dimen/gap_small"
                android:paddingBottom="4dp"
                android:text="@string/map_section_display" />

            <LinearLayout
                android:id="@+id/displayGroup"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:padding="@dimen/card_pad"
                android:background="@drawable/bg_card" />

            <Button
                android:id="@+id/btnRestoreDisplay"
                style="@style/Button.Ghost"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="@dimen/gap_small"
                android:text="@string/disp_restore_defaults" />

            <TextView
                style="@style/Text.Caption"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="@dimen/gap"
                android:paddingStart="@dimen/gap_small"
                android:paddingBottom="4dp"
                android:text="@string/map_section_speed" />

            <LinearLayout
                android:id="@+id/speedGroup"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:padding="@dimen/card_pad"
                android:background="@drawable/bg_card" />

            <TextView
                style="@style/Text.Caption"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="@dimen/gap"
                android:paddingStart="@dimen/gap_small"
                android:paddingBottom="4dp"
                android:text="@string/map_section_diagnostics" />

            <TextView
                android:id="@+id/diagText"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:padding="@dimen/card_pad"
                android:background="@drawable/bg_card"
                android:fontFamily="monospace"
                android:textColor="@color/text_secondary"
                android:textSize="@dimen/text_micro"
                android:lineSpacingExtra="2dp"
                android:text="@string/map_diag_empty"
                android:textIsSelectable="true" />

            <Button
                android:id="@+id/btnCopyDiag"
                style="@style/Button.Ghost"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="@dimen/gap_small"
                android:layout_marginBottom="@dimen/gap"
                android:text="@string/map_diag_copy" />
        </LinearLayout>
    </ScrollView>

    <com.ts8.launcher.ui.BottomNavView
        android:id="@+id/bottomNav"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />
</androidx.constraintlayout.widget.ConstraintLayout>

END FILE

## app/src/main/res/layout/activity_music_fullscreen.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!--
  Music — design state 3, fullscreen.

  Two columns, as the design has them: what is playing on the left, how to
  choose something else on the right. The split is a horizontal `LinearLayout`
  with weights rather than a fixed width, so the panel keeps its proportions on
  a 1024×600 unit and on a wider one.

  Everything on the left is bound from the player's own `PlaybackState`. The
  library on the right is paged from SQLite by tab; an empty library shows one
  sentence and nothing else, which is the truthful state on a unit with no
  music yet.

  The `FLAC` badge on the artwork in the design is reproduced as a real codec
  tag: it is filled from the track's own MIME type and hidden when the track
  carries none, rather than printed unconditionally.
-->
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/bg_root">

    <com.ts8.launcher.ui.StatusBarView
        android:id="@+id/statusBar"
        android:layout_width="0dp"
        android:layout_height="@dimen/status_height"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:orientation="horizontal"
        android:paddingStart="@dimen/screen_pad"
        android:paddingEnd="@dimen/screen_pad"
        android:paddingTop="@dimen/gap_small"
        android:paddingBottom="@dimen/gap_small"
        app:layout_constraintTop_toBottomOf="@id/statusBar"
        app:layout_constraintBottom_toTopOf="@id/bottomNav"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <!-- ================= now playing ================= -->
        <LinearLayout
            android:id="@+id/nowPlayingPanel"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:orientation="horizontal"
            android:padding="@dimen/card_pad"
            android:background="@drawable/bg_card">

            <!-- Artwork, with the codec badge over its bottom-left corner. -->
            <FrameLayout
                android:id="@+id/artworkFrame"
                android:layout_width="@dimen/music_artwork_large"
                android:layout_height="@dimen/music_artwork_large"
                android:layout_gravity="center_vertical"
                android:background="@drawable/bg_card_raised">

                <ImageView
                    android:id="@+id/artwork"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:scaleType="centerCrop"
                    android:contentDescription="@string/cd_music_artwork"
                    android:visibility="gone" />

                <ImageView
                    android:id="@+id/artworkPlaceholder"
                    android:layout_width="56dp"
                    android:layout_height="56dp"
                    android:layout_gravity="center"
                    android:src="@drawable/ic_nav_music"
                    app:tint="@color/text_tertiary"
                    android:contentDescription="@null" />

                <TextView
                    android:id="@+id/codecBadge"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_gravity="bottom|start"
                    android:layout_margin="@dimen/gap_small"
                    android:paddingStart="6dp"
                    android:paddingEnd="6dp"
                    android:paddingTop="2dp"
                    android:paddingBottom="2dp"
                    android:background="@drawable/bg_chip"
                    android:textColor="@color/text_secondary"
                    android:textSize="@dimen/text_micro"
                    android:visibility="gone"
                    tools:visibility="visible"
                    tools:text="FLAC" />
            </FrameLayout>

            <!-- Title, progress and transport. -->
            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="match_parent"
                android:layout_weight="1"
                android:layout_marginStart="@dimen/card_pad"
                android:orientation="vertical"
                android:gravity="center_vertical">

                <TextView
                    android:id="@+id/trackTitle"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:textColor="@color/text_primary"
                    android:textSize="@dimen/text_headline"
                    android:textStyle="bold"
                    android:maxLines="2"
                    android:ellipsize="end"
                    android:text="@string/home_music_empty" />

                <TextView
                    android:id="@+id/trackSubtitle"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="4dp"
                    android:textColor="@color/text_secondary"
                    android:textSize="@dimen/text_body"
                    android:maxLines="2"
                    android:ellipsize="end"
                    android:text="@string/home_music_hint" />

                <TextView
                    android:id="@+id/trackMeta"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="2dp"
                    android:textColor="@color/text_tertiary"
                    android:textSize="@dimen/text_caption"
                    android:maxLines="1"
                    android:ellipsize="end"
                    android:visibility="gone"
                    tools:visibility="visible"
                    tools:text="Album · 2021" />

                <!-- A seek bar, not a read-only progress bar: the design puts
                     the position here and the driver expects to scrub. -->
                <SeekBar
                    android:id="@+id/seekBar"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="@dimen/gap"
                    android:progressDrawable="@drawable/progress_music"
                    android:thumb="@drawable/seek_thumb"
                    android:paddingStart="0dp"
                    android:paddingEnd="0dp"
                    android:max="1000" />

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="horizontal">

                    <TextView
                        android:id="@+id/positionText"
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_weight="1"
                        android:textColor="@color/text_tertiary"
                        android:textSize="@dimen/text_micro"
                        android:text="00:00" />

                    <TextView
                        android:id="@+id/durationText"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:textColor="@color/text_tertiary"
                        android:textSize="@dimen/text_micro"
                        android:text="00:00" />
                </LinearLayout>

                <!-- Transport: heart, shuffle, prev, play, next, repeat. -->
                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="@dimen/gap"
                    android:orientation="horizontal"
                    android:gravity="center_vertical">

                    <ImageButton
                        android:id="@+id/btnFavorite"
                        android:layout_width="@dimen/music_control_size"
                        android:layout_height="@dimen/music_control_size"
                        android:background="?attr/selectableItemBackgroundBorderless"
                        android:src="@drawable/ic_heart"
                        app:tint="@color/text_secondary"
                        android:contentDescription="@string/music_favorite" />

                    <Space
                        android:layout_width="0dp"
                        android:layout_height="1dp"
                        android:layout_weight="1" />

                    <ImageButton
                        android:id="@+id/btnShuffle"
                        android:layout_width="@dimen/music_control_size"
                        android:layout_height="@dimen/music_control_size"
                        android:background="?attr/selectableItemBackgroundBorderless"
                        android:src="@drawable/ic_shuffle"
                        app:tint="@color/text_secondary"
                        android:contentDescription="@string/music_shuffle" />

                    <ImageButton
                        android:id="@+id/btnPrevious"
                        android:layout_width="@dimen/music_control_size"
                        android:layout_height="@dimen/music_control_size"
                        android:layout_marginStart="@dimen/gap_small"
                        android:background="?attr/selectableItemBackgroundBorderless"
                        android:src="@drawable/ic_prev"
                        app:tint="@color/text_primary"
                        android:contentDescription="@string/music_previous" />

                    <ImageButton
                        android:id="@+id/btnPlayPause"
                        android:layout_width="64dp"
                        android:layout_height="64dp"
                        android:layout_marginStart="@dimen/gap_small"
                        android:background="@drawable/bg_circle_ring"
                        android:src="@drawable/ic_play"
                        app:tint="@color/text_primary"
                        android:contentDescription="@string/music_play" />

                    <ImageButton
                        android:id="@+id/btnNext"
                        android:layout_width="@dimen/music_control_size"
                        android:layout_height="@dimen/music_control_size"
                        android:layout_marginStart="@dimen/gap_small"
                        android:background="?attr/selectableItemBackgroundBorderless"
                        android:src="@drawable/ic_next"
                        app:tint="@color/text_primary"
                        android:contentDescription="@string/music_next" />

                    <ImageButton
                        android:id="@+id/btnRepeat"
                        android:layout_width="@dimen/music_control_size"
                        android:layout_height="@dimen/music_control_size"
                        android:layout_marginStart="@dimen/gap_small"
                        android:background="?attr/selectableItemBackgroundBorderless"
                        android:src="@drawable/ic_repeat"
                        app:tint="@color/text_secondary"
                        android:contentDescription="@string/music_repeat" />

                    <Space
                        android:layout_width="0dp"
                        android:layout_height="1dp"
                        android:layout_weight="1" />

                    <!-- Keeps the transport optically centred without a second
                         heart; the queue lives on the right column. -->
                    <View
                        android:layout_width="@dimen/music_control_size"
                        android:layout_height="1dp" />
                </LinearLayout>
            </LinearLayout>
        </LinearLayout>

        <!-- ================= library ================= -->
        <LinearLayout
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:layout_marginStart="@dimen/gap"
            android:orientation="vertical"
            android:padding="@dimen/card_pad"
            android:background="@drawable/bg_card">

            <!-- One field for both catalogues (§13). An empty field leaves the
                 four tabs as they are; typing replaces the tabbed library with
                 two labelled groups — `Trên thiết bị` and `Trực tuyến` — so a
                 driver can tell a USB track from a radio station without
                 reading a badge, and never sees one list that silently mixes
                 the two. -->
            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:gravity="center_vertical"
                android:paddingBottom="@dimen/gap_small">

                <ImageView
                    android:layout_width="20dp"
                    android:layout_height="20dp"
                    android:layout_marginEnd="@dimen/gap_small"
                    android:src="@drawable/ic_search"
                    app:tint="@color/text_tertiary"
                    android:contentDescription="@null" />

                <EditText
                    android:id="@+id/musicSearch"
                    android:layout_width="0dp"
                    android:layout_height="40dp"
                    android:layout_weight="1"
                    android:background="@drawable/bg_search_field"
                    android:hint="@string/music_search_hint"
                    android:textColorHint="@color/text_tertiary"
                    android:paddingStart="@dimen/gap_small"
                    android:paddingEnd="@dimen/gap_small"
                    android:textColor="@color/text_primary"
                    android:textSize="@dimen/text_body"
                    android:singleLine="true"
                    android:imeOptions="actionSearch"
                    android:inputType="text"
                    android:autofillHints=""
                    android:importantForAutofill="no"
                    tools:ignore="LabelFor" />
            </LinearLayout>

            <!-- ============================================================
                 Library tabs.

                 Each label sits in its own weighted cell and is sized to its
                 text, so the selected pill hugs the word instead of filling a
                 quarter of the row. The design draws the selected tab as a
                 rounded outline around the label, not as a full-width bar.

                 The cell is what takes the weight; the TextView inside wraps.
                 Putting the weight on the TextView directly would stretch the
                 pill across the whole cell.
                 ============================================================ -->
            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal">

                <FrameLayout
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1">

                    <TextView
                        android:id="@+id/tabPlaylist"
                        style="@style/Text.Caption"
                        android:layout_width="wrap_content"
                        android:layout_height="36dp"
                        android:layout_gravity="center"
                        android:background="@drawable/bg_tab_pill"
                        android:gravity="center"
                        android:paddingStart="20dp"
                        android:paddingEnd="20dp"
                        android:textColor="@color/music_tab_tint"
                        android:text="@string/music_tab_playlist" />
                </FrameLayout>

                <FrameLayout
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1">

                    <TextView
                        android:id="@+id/tabSongs"
                        style="@style/Text.Caption"
                        android:layout_width="wrap_content"
                        android:layout_height="36dp"
                        android:layout_gravity="center"
                        android:background="@drawable/bg_tab_pill"
                        android:gravity="center"
                        android:paddingStart="20dp"
                        android:paddingEnd="20dp"
                        android:textColor="@color/music_tab_tint"
                        android:text="@string/music_tab_songs" />
                </FrameLayout>

                <FrameLayout
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1">

                    <TextView
                        android:id="@+id/tabAlbums"
                        style="@style/Text.Caption"
                        android:layout_width="wrap_content"
                        android:layout_height="36dp"
                        android:layout_gravity="center"
                        android:background="@drawable/bg_tab_pill"
                        android:gravity="center"
                        android:paddingStart="20dp"
                        android:paddingEnd="20dp"
                        android:textColor="@color/music_tab_tint"
                        android:text="@string/music_tab_albums" />
                </FrameLayout>

                <FrameLayout
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1">

                    <TextView
                        android:id="@+id/tabArtists"
                        style="@style/Text.Caption"
                        android:layout_width="wrap_content"
                        android:layout_height="36dp"
                        android:layout_gravity="center"
                        android:background="@drawable/bg_tab_pill"
                        android:gravity="center"
                        android:paddingStart="20dp"
                        android:paddingEnd="20dp"
                        android:textColor="@color/music_tab_tint"
                        android:text="@string/music_tab_artists" />
                </FrameLayout>
            </LinearLayout>

            <View
                android:layout_width="match_parent"
                android:layout_height="1dp"
                android:background="@color/divider" />

            <FrameLayout
                android:layout_width="match_parent"
                android:layout_height="0dp"
                android:layout_weight="1"
                android:layout_marginTop="@dimen/gap_small">

                <androidx.recyclerview.widget.RecyclerView
                    android:id="@+id/libraryList"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:clipToPadding="false"
                    android:scrollbars="none" />

                <LinearLayout
                    android:id="@+id/libraryEmpty"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:orientation="vertical"
                    android:gravity="center"
                    android:visibility="gone"
                    tools:visibility="visible">

                    <ImageView
                        android:layout_width="40dp"
                        android:layout_height="40dp"
                        android:src="@drawable/ic_nav_music"
                        app:tint="@color/text_tertiary"
                        android:contentDescription="@null" />

                    <TextView
                        android:id="@+id/libraryEmptyText"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="@dimen/gap_small"
                        android:gravity="center"
                        android:textColor="@color/text_secondary"
                        android:textSize="@dimen/text_body"
                        android:text="@string/music_empty" />

                    <TextView
                        android:id="@+id/libraryEmptyHint"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="4dp"
                        android:gravity="center"
                        android:textColor="@color/text_tertiary"
                        android:textSize="@dimen/text_caption"
                        android:text="@string/music_empty_hint" />
                </LinearLayout>

                <ProgressBar
                    android:id="@+id/libraryLoading"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_gravity="center"
                    android:indeterminateTint="@color/accent"
                    android:visibility="gone" />
            </FrameLayout>

            <TextView
                android:id="@+id/libraryFooter"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:textColor="@color/text_tertiary"
                android:textSize="@dimen/text_micro"
                android:maxLines="1"
                android:ellipsize="end"
                android:visibility="gone"
                tools:visibility="visible"
                tools:text="248 bài • 16 giờ 12 phút" />
        </LinearLayout>
    </LinearLayout>

    <com.ts8.launcher.ui.BottomNavView
        android:id="@+id/bottomNav"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />
</androidx.constraintlayout.widget.ConstraintLayout>

END FILE

## app/src/main/res/layout/activity_parking_mode.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!--
  Parking mode — design state 7.

  A large 24-hour clock, the date, one friendly line, a still background and the
  same bottom bar as every other screen.

  Nothing here is a live map. The design shows a sunset photograph; this screen
  shows a dark gradient behind the clock because there is no image the app is
  licensed to ship and a photograph substituted with a drawn "mountains" shape
  would be exactly the kind of fake asset the spec warns against. The clock, the
  date and the message are real.
-->
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/bg_parking">

    <LinearLayout
        android:id="@+id/parkingPane"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:layout_marginStart="64dp"
        android:layout_marginEnd="64dp"
        android:orientation="vertical"
        android:gravity="center_vertical"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toTopOf="@id/bottomNav"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <TextView
            android:id="@+id/parkingClock"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@color/text_primary"
            android:textSize="@dimen/parking_clock"
            android:fontFamily="sans-serif-light"
            android:includeFontPadding="false"
            android:shadowColor="#80000000"
            android:shadowRadius="18"
            android:shadowDx="0"
            android:shadowDy="2"
            android:text="--:--" />

        <TextView
            android:id="@+id/parkingDate"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="@dimen/gap"
            android:textColor="@color/text_secondary"
            android:textSize="@dimen/text_headline" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="@dimen/gap"
            android:textColor="@color/text_tertiary"
            android:textSize="@dimen/text_body"
            android:text="@string/parking_message" />
    </LinearLayout>

    <!--
      The whole pane is the exit target, not a small button. A driver reaching
      back to the car should not have to find a 48 dp control on a screen whose
      only affordance is "tap to go back".
    -->
    <TextView
        android:id="@+id/parkingExitHint"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginBottom="@dimen/gap"
        android:paddingStart="@dimen/gap"
        android:paddingEnd="@dimen/gap"
        android:paddingTop="6dp"
        android:paddingBottom="6dp"
        android:background="@drawable/bg_chip"
        android:textColor="@color/text_tertiary"
        android:textSize="@dimen/text_caption"
        android:text="@string/parking_tap_to_exit"
        app:layout_constraintBottom_toTopOf="@id/bottomNav"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <com.ts8.launcher.ui.BottomNavView
        android:id="@+id/bottomNav"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />
</androidx.constraintlayout.widget.ConstraintLayout>

END FILE

## app/src/main/res/layout/activity_quick_settings.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!--
  Quick settings — design state 6.

  Four toggle tiles in the design (Wi-Fi, Bluetooth, dữ liệu 4G, chế độ tối),
  then a brightness slider and a volume slider, then three wide actions
  (màn hình tối, khởi động lại, cài đặt).

  Every control is bound to an action this launcher is actually allowed to
  perform on Android 6. What is not allowed is either hidden or visibly
  disabled with the reason — never a switch that flicks back with no message.
  See `QuickSettingsActivity` for the per-control permissions table.
-->
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/bg_root">

    <com.ts8.launcher.ui.StatusBarView
        android:id="@+id/statusBar"
        android:layout_width="0dp"
        android:layout_height="@dimen/status_height"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <TextView
        android:id="@+id/qsTitle"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="@dimen/screen_pad"
        android:layout_marginTop="@dimen/gap_small"
        android:textColor="@color/text_primary"
        android:textSize="@dimen/text_title"
        android:fontFamily="sans-serif-medium"
        android:text="@string/qs_title"
        app:layout_constraintTop_toBottomOf="@id/statusBar"
        app:layout_constraintStart_toStartOf="parent" />

    <TextView
        android:id="@+id/qsNote"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="@dimen/gap"
        android:layout_marginEnd="@dimen/screen_pad"
        android:maxLines="1"
        android:ellipsize="end"
        android:textColor="@color/text_tertiary"
        android:textSize="@dimen/text_caption"
        android:visibility="gone"
        tools:visibility="visible"
        tools:text="Mục mờ cần quyền hệ thống"
        app:layout_constraintTop_toTopOf="@id/qsTitle"
        app:layout_constraintBottom_toBottomOf="@id/qsTitle"
        app:layout_constraintStart_toEndOf="@id/qsTitle"
        app:layout_constraintEnd_toEndOf="parent" />

    <!-- ================= toggles ================= -->
    <LinearLayout
        android:id="@+id/toggleRow"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="@dimen/screen_pad"
        android:layout_marginEnd="@dimen/screen_pad"
        android:layout_marginTop="@dimen/gap"
        android:orientation="horizontal"
        android:baselineAligned="false"
        app:layout_constraintTop_toBottomOf="@id/qsTitle"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <com.ts8.launcher.ui.QsTileView
            android:id="@+id/tileWifi"
            style="@style/QsTile"
            app:qsIcon="@drawable/ic_wifi"
            app:qsLabel="@string/qs_wifi" />

        <com.ts8.launcher.ui.QsTileView
            android:id="@+id/tileBluetooth"
            style="@style/QsTile"
            android:layout_marginStart="@dimen/gap"
            app:qsIcon="@drawable/ic_bluetooth"
            app:qsLabel="@string/qs_bluetooth" />

        <com.ts8.launcher.ui.QsTileView
            android:id="@+id/tileMobile"
            style="@style/QsTile"
            android:layout_marginStart="@dimen/gap"
            app:qsIcon="@drawable/ic_signal"
            app:qsLabel="@string/qs_mobile_data" />

        <com.ts8.launcher.ui.QsTileView
            android:id="@+id/tileTheme"
            style="@style/QsTile"
            android:layout_marginStart="@dimen/gap"
            app:qsIcon="@drawable/ic_moon"
            app:qsLabel="@string/qs_theme" />
    </LinearLayout>

    <!-- ================= sliders ================= -->
    <LinearLayout
        android:id="@+id/brightnessRow"
        android:layout_width="0dp"
        android:layout_height="@dimen/qs_slider_height"
        android:layout_marginStart="@dimen/screen_pad"
        android:layout_marginEnd="@dimen/screen_pad"
        android:layout_marginTop="@dimen/gap"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:paddingStart="@dimen/gap"
        android:paddingEnd="@dimen/gap"
        android:background="@drawable/bg_card_raised"
        app:layout_constraintTop_toBottomOf="@id/toggleRow"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <ImageView
            android:id="@+id/brightnessIcon"
            android:layout_width="22dp"
            android:layout_height="22dp"
            android:src="@drawable/ic_brightness"
            app:tint="@color/text_secondary"
            android:contentDescription="@string/qs_brightness" />

        <!--
          A SeekBar here steps the system brightness. It is disabled until the
          driver grants WRITE_SETTINGS, and tapping it in that state opens the
          grant screen: the setting genuinely needs that permission, so asking
          for it is the honest behaviour rather than faking the ramp.
        -->
        <SeekBar
            android:id="@+id/brightnessSlider"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:layout_marginStart="@dimen/gap"
            android:progressDrawable="@drawable/progress_music"
            android:thumb="@drawable/seek_thumb" />

        <TextView
            android:id="@+id/brightnessValue"
            android:layout_width="44dp"
            android:layout_height="wrap_content"
            android:gravity="center"
            android:textColor="@color/text_secondary"
            android:textSize="@dimen/text_caption"
            tools:text="70%" />
    </LinearLayout>

    <LinearLayout
        android:id="@+id/volumeRow"
        android:layout_width="0dp"
        android:layout_height="@dimen/qs_slider_height"
        android:layout_marginStart="@dimen/screen_pad"
        android:layout_marginEnd="@dimen/screen_pad"
        android:layout_marginTop="@dimen/gap_small"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:paddingStart="@dimen/gap"
        android:paddingEnd="@dimen/gap"
        android:background="@drawable/bg_card_raised"
        app:layout_constraintTop_toBottomOf="@id/brightnessRow"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <ImageView
            android:layout_width="22dp"
            android:layout_height="22dp"
            android:src="@drawable/ic_volume"
            app:tint="@color/text_secondary"
            android:contentDescription="@string/qs_volume" />

        <SeekBar
            android:id="@+id/volumeSlider"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:layout_marginStart="@dimen/gap"
            android:progressDrawable="@drawable/progress_music"
            android:thumb="@drawable/seek_thumb" />

        <TextView
            android:id="@+id/volumeValue"
            android:layout_width="44dp"
            android:layout_height="wrap_content"
            android:gravity="center"
            android:textColor="@color/text_secondary"
            android:textSize="@dimen/text_caption"
            tools:text="24" />
    </LinearLayout>

    <!-- ================= wide actions ================= -->
    <LinearLayout
        android:id="@+id/actionRow"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="@dimen/screen_pad"
        android:layout_marginEnd="@dimen/screen_pad"
        android:layout_marginTop="@dimen/gap"
        android:orientation="horizontal"
        android:baselineAligned="false"
        app:layout_constraintTop_toBottomOf="@id/volumeRow"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <Button
            android:id="@+id/btnScreenOff"
            style="@style/Button.Ghost"
            android:layout_width="0dp"
            android:layout_height="56dp"
            android:layout_weight="1"
            android:text="@string/qs_fullscreen"
            android:drawableStart="@drawable/ic_moon"
            android:drawablePadding="@dimen/gap_small" />

        <Button
            android:id="@+id/btnParking"
            style="@style/Button.Ghost"
            android:layout_width="0dp"
            android:layout_height="56dp"
            android:layout_weight="1"
            android:layout_marginStart="@dimen/gap"
            android:text="@string/qs_parking_mode"
            android:drawableStart="@drawable/ic_parking"
            android:drawablePadding="@dimen/gap_small" />

        <Button
            android:id="@+id/btnRestart"
            style="@style/Button.Ghost"
            android:layout_width="0dp"
            android:layout_height="56dp"
            android:layout_weight="1"
            android:layout_marginStart="@dimen/gap"
            android:text="@string/qs_restart"
            android:drawableStart="@drawable/ic_power"
            android:drawablePadding="@dimen/gap_small" />

        <Button
            android:id="@+id/btnSettings"
            style="@style/Button.Ghost"
            android:layout_width="0dp"
            android:layout_height="56dp"
            android:layout_weight="1"
            android:layout_marginStart="@dimen/gap"
            android:text="@string/qs_settings"
            android:drawableStart="@drawable/ic_settings"
            android:drawablePadding="@dimen/gap_small" />
    </LinearLayout>

    <com.ts8.launcher.ui.BottomNavView
        android:id="@+id/bottomNav"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />
</androidx.constraintlayout.widget.ConstraintLayout>

END FILE

## app/src/main/res/layout/activity_screen_off.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!--
  An all-black surface.

  `PowerManager.goToSleep` needs the DEVICE_POWER signature permission, so a
  third-party launcher cannot dim the panel. What it can do is cover it: a black
  window at full screen with the navigation bar hidden. Playback and navigation
  keep running underneath, which is the point — the driver wants the light off
  during a night stop, not the music.
-->
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/screenOffRoot"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@android:color/black"
    android:keepScreenOn="false">
</FrameLayout>

END FILE

## app/src/main/res/layout/activity_search.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!--
  Search — design state 4.

  Three regions, as the design lays them out:

  * the query field, full width at the top, with a clear button;
  * the result list on the left, one row per place with name, address and
    straight-line distance;
  * the selected place on the right, over a map preview of it.

  The map preview is the real renderer, in the map tab. A second `MapView` on
  the search screen would mean a second GL surface and a second style load on a
  device that can barely afford one, so the right panel shows the place's
  information and the map appears when the driver taps "Lộ trình" — that
  handoff opens the real map already centred on the place. See `SearchActivity`.
-->
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/bg_root">

    <com.ts8.launcher.ui.StatusBarView
        android:id="@+id/statusBar"
        android:layout_width="0dp"
        android:layout_height="@dimen/status_height"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <!-- ================= query field ================= -->
    <LinearLayout
        android:id="@+id/searchBar"
        android:layout_width="0dp"
        android:layout_height="52dp"
        android:layout_marginStart="@dimen/screen_pad"
        android:layout_marginEnd="@dimen/screen_pad"
        android:layout_marginTop="@dimen/gap_small"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:paddingStart="@dimen/gap"
        android:paddingEnd="@dimen/gap_small"
        android:background="@drawable/bg_search_field"
        app:layout_constraintTop_toBottomOf="@id/statusBar"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <ImageView
            android:layout_width="20dp"
            android:layout_height="20dp"
            android:src="@drawable/ic_search"
            app:tint="@color/text_tertiary"
            android:contentDescription="@null" />

        <EditText
            android:id="@+id/searchInput"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:layout_marginStart="@dimen/gap_small"
            android:background="@null"
            android:hint="@string/search_hint"
            android:textColorHint="@color/text_tertiary"
            android:textColor="@color/text_primary"
            android:textSize="@dimen/text_body"
            android:inputType="text"
            android:imeOptions="actionSearch"
            android:maxLines="1"
            android:singleLine="true"
            android:importantForAutofill="no" />

        <ProgressBar
            android:id="@+id/searchSpinner"
            android:layout_width="18dp"
            android:layout_height="18dp"
            android:layout_marginEnd="@dimen/gap_small"
            android:indeterminateTint="@color/accent"
            android:visibility="gone" />

        <!-- Voice Instant from the search field.
             The base implementation hid a microphone here because there was no
             backend; there is one now (`VoiceInstantController`), so the
             affordance is drawn and wired in `SearchActivity`. It sits inside
             the field because that is where the design puts it, and it speaks a
             query rather than opening a second overlay. -->
        <ImageButton
            android:id="@+id/btnVoice"
            android:layout_width="40dp"
            android:layout_height="40dp"
            android:layout_marginEnd="@dimen/gap_small"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:src="@drawable/ic_mic"
            app:tint="@color/text_secondary"
            android:contentDescription="@string/voice_mic" />

        <ImageButton
            android:id="@+id/btnClear"
            android:layout_width="40dp"
            android:layout_height="40dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:src="@drawable/ic_close"
            app:tint="@color/text_tertiary"
            android:contentDescription="@string/search_clear"
            android:visibility="gone"
            tools:visibility="visible" />

        <ImageButton
            android:id="@+id/btnClose"
            android:layout_width="40dp"
            android:layout_height="40dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:src="@drawable/ic_chevron_left"
            app:tint="@color/text_secondary"
            android:contentDescription="@string/search_close" />
    </LinearLayout>

    <!-- ================= results ================= -->
    <!--
      A guideline rather than `layout_constraintWidth_percent`: the two panes
      are siblings and a percentage width on each would leave their shared edge
      to rounding, so on a 1280 panel the split would drift by a few pixels
      between them.
    -->
    <androidx.constraintlayout.widget.Guideline
        android:id="@+id/splitGuide"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        app:layout_constraintGuide_percent="0.56" />

    <LinearLayout
        android:id="@+id/resultsPane"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:layout_marginStart="@dimen/screen_pad"
        android:layout_marginTop="@dimen/gap_small"
        android:layout_marginBottom="@dimen/gap_small"
        android:orientation="vertical"
        app:layout_constraintTop_toBottomOf="@id/searchBar"
        app:layout_constraintBottom_toTopOf="@id/bottomNav"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toStartOf="@id/splitGuide">

        <!-- Says what the list currently is: the saved places when nothing is
             typed, otherwise the hit count. Without it an empty query looks
             like a broken search that returns a list. -->
        <TextView
            android:id="@+id/resultsHeader"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:paddingStart="@dimen/gap_small"
            android:paddingEnd="@dimen/gap_small"
            android:paddingBottom="@dimen/gap_small"
            android:textColor="@color/text_tertiary"
            android:textSize="@dimen/text_caption"
            android:maxLines="1"
            android:ellipsize="end"
            android:visibility="gone"
            tools:visibility="visible"
            tools:text="12 kết quả" />

        <FrameLayout
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1">

        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/resultsList"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:clipToPadding="false"
            android:scrollbars="none" />

        <LinearLayout
            android:id="@+id/resultsEmpty"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:orientation="vertical"
            android:gravity="center"
            android:visibility="gone"
            tools:visibility="visible">

            <ImageView
                android:layout_width="40dp"
                android:layout_height="40dp"
                android:src="@drawable/ic_search"
                app:tint="@color/text_tertiary"
                android:contentDescription="@null" />

            <TextView
                android:id="@+id/resultsEmptyText"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="@dimen/gap_small"
                android:gravity="center"
                android:paddingStart="24dp"
                android:paddingEnd="24dp"
                android:textColor="@color/text_secondary"
                android:textSize="@dimen/text_body"
                android:text="@string/search_empty_prompt" />
        </LinearLayout>
        </FrameLayout>
    </LinearLayout>

    <!--
      The pane is always present. Hiding it when nothing is selected would let
      the result list stretch to full width and then snap back on the first tap,
      and a ConstraintSet swap to avoid that costs a layout pass for a state the
      screen is in at most a second. A hint occupies it instead.
    -->
    <LinearLayout
        android:id="@+id/detailPane"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:layout_marginStart="@dimen/gap"
        android:layout_marginEnd="@dimen/screen_pad"
        android:layout_marginTop="@dimen/gap_small"
        android:layout_marginBottom="@dimen/gap_small"
        android:orientation="vertical"
        android:padding="@dimen/card_pad"
        android:background="@drawable/bg_card"
        app:layout_constraintTop_toBottomOf="@id/searchBar"
        app:layout_constraintBottom_toTopOf="@id/bottomNav"
        app:layout_constraintStart_toEndOf="@id/resultsPane"
        app:layout_constraintEnd_toEndOf="parent">

        <TextView
            android:id="@+id/detailHint"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:gravity="center"
            android:paddingStart="24dp"
            android:paddingEnd="24dp"
            android:textColor="@color/text_tertiary"
            android:textSize="@dimen/text_body"
            android:text="@string/search_select_hint" />

        <LinearLayout
            android:id="@+id/detailBody"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:orientation="vertical"
            android:visibility="gone"
            tools:visibility="visible">

        <!-- The place's category as a small chip, then its name. -->
        <TextView
            android:id="@+id/detailCategory"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:paddingStart="8dp"
            android:paddingEnd="8dp"
            android:paddingTop="2dp"
            android:paddingBottom="2dp"
            android:background="@drawable/bg_chip"
            android:textColor="@color/text_secondary"
            android:textSize="@dimen/text_micro"
            android:visibility="gone"
            tools:visibility="visible"
            tools:text="Cầu" />

        <TextView
            android:id="@+id/detailName"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="@dimen/gap_small"
            android:textColor="@color/text_primary"
            android:textSize="@dimen/text_headline"
            android:textStyle="bold"
            android:maxLines="2"
            android:ellipsize="end"
            tools:text="Cầu Rồng" />

        <TextView
            android:id="@+id/detailAddress"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:textColor="@color/text_secondary"
            android:textSize="@dimen/text_body"
            android:maxLines="2"
            android:ellipsize="end"
            tools:text="Đ. Nguyễn Văn Linh, Đà Nẵng" />

        <TextView
            android:id="@+id/detailDistance"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="@dimen/gap_small"
            android:textColor="@color/text_tertiary"
            android:textSize="@dimen/text_caption"
            tools:text="6,2 km" />

        <View
            android:layout_width="match_parent"
            android:layout_height="1dp"
            android:layout_marginTop="@dimen/gap"
            android:background="@color/divider" />

        <!-- Nearest nothing: the place's own coordinates, which is what the
             route will use. Shown because on a head unit the driver sometimes
             needs to read the number out, and because it proves the record. -->
        <TextView
            android:id="@+id/detailCoords"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="@dimen/gap_small"
            android:textColor="@color/text_tertiary"
            android:textSize="@dimen/text_micro"
            android:maxLines="1"
            android:ellipsize="end"
            tools:text="16.06167, 108.22718" />

        <Space
            android:layout_width="1dp"
            android:layout_height="0dp"
            android:layout_weight="1" />

        <Button
            android:id="@+id/btnRoute"
            style="@style/Button.Primary"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="@string/search_route" />

        <Button
            android:id="@+id/btnSave"
            style="@style/Button.Ghost"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="@dimen/gap_small"
            android:text="@string/search_save" />
        </LinearLayout>
    </LinearLayout>

    <com.ts8.launcher.ui.BottomNavView
        android:id="@+id/bottomNav"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />
</androidx.constraintlayout.widget.ConstraintLayout>

END FILE

## app/src/main/res/layout/activity_settings.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!--
  Settings.

  Not one of the design's seven states — it is reachable from Quick Settings and
  holds the things that do not fit a tile: which map pack is installed, whether
  each permission is granted, and the attribution the map data requires.

  Every row is either a real, working action or a real, readable fact. There is
  no row for anything the launcher cannot do.
-->
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/bg_root">

    <com.ts8.launcher.ui.StatusBarView
        android:id="@+id/statusBar"
        android:layout_width="0dp"
        android:layout_height="@dimen/status_height"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <ImageButton
        android:id="@+id/btnBack"
        android:layout_width="44dp"
        android:layout_height="44dp"
        android:layout_marginStart="@dimen/gap_small"
        android:layout_marginTop="@dimen/gap_small"
        android:background="?attr/selectableItemBackgroundBorderless"
        android:src="@drawable/ic_chevron_left"
        app:tint="@color/text_secondary"
        android:contentDescription="@string/generic_back"
        app:layout_constraintTop_toBottomOf="@id/statusBar"
        app:layout_constraintStart_toStartOf="parent" />

    <TextView
        android:id="@+id/settingsTitle"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="@dimen/gap_small"
        android:layout_marginEnd="@dimen/screen_pad"
        android:textColor="@color/text_primary"
        android:textSize="@dimen/text_title"
        android:fontFamily="sans-serif-medium"
        android:text="@string/qs_settings"
        app:layout_constraintTop_toTopOf="@id/btnBack"
        app:layout_constraintBottom_toBottomOf="@id/btnBack"
        app:layout_constraintStart_toEndOf="@id/btnBack"
        app:layout_constraintEnd_toEndOf="parent" />

    <ScrollView
        android:id="@+id/settingsScroll"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:layout_marginTop="@dimen/gap_small"
        android:layout_marginBottom="@dimen/gap_small"
        android:paddingStart="@dimen/screen_pad"
        android:paddingEnd="@dimen/screen_pad"
        android:scrollbars="none"
        app:layout_constraintTop_toBottomOf="@id/btnBack"
        app:layout_constraintBottom_toTopOf="@id/bottomNav"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical">

            <!-- ===== map pack ===== -->
            <TextView
                style="@style/Text.Caption"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:paddingStart="@dimen/gap_small"
                android:paddingBottom="4dp"
                android:text="@string/settings_section_map" />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:padding="@dimen/card_pad"
                android:background="@drawable/bg_card">

                <TextView
                    android:id="@+id/packName"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:textColor="@color/text_primary"
                    android:textSize="@dimen/text_body"
                    tools:text="Đà Nẵng v1" />

                <TextView
                    android:id="@+id/packPath"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="2dp"
                    android:textColor="@color/text_tertiary"
                    android:textSize="@dimen/text_micro"
                    android:maxLines="2"
                    android:ellipsize="middle"
                    tools:text="/sdcard/ts8/map-pack/danang" />

                <TextView
                    android:id="@+id/packAttribution"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="@dimen/gap_small"
                    android:textColor="@color/text_secondary"
                    android:textSize="@dimen/text_micro"
                    tools:text="© OpenStreetMap contributors" />
            </LinearLayout>

            <Button
                android:id="@+id/btnRescanPack"
                style="@style/Button.Ghost"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="@dimen/gap_small"
                android:text="@string/settings_rescan_pack" />

            <!-- ===== permissions ===== -->
            <TextView
                style="@style/Text.Caption"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="@dimen/gap"
                android:paddingStart="@dimen/gap_small"
                android:paddingBottom="4dp"
                android:text="@string/settings_section_permissions" />

            <LinearLayout
                android:id="@+id/permissionList"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:padding="@dimen/card_pad"
                android:background="@drawable/bg_card" />

            <!-- ===== online music (§12–13) ===== -->
            <TextView
                style="@style/Text.Caption"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="@dimen/gap"
                android:paddingStart="@dimen/gap_small"
                android:paddingBottom="4dp"
                android:text="@string/settings_section_online" />

            <LinearLayout
                android:id="@+id/onlineCard"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:padding="@dimen/card_pad"
                android:background="@drawable/bg_card">

                <!-- Bật/tắt drawn as two chips rather than a `Switch`: the same
                     reason the map settings panel does it. The platform switch
                     on this ROM renders in the theme's disabled colour, which is
                     invisible against this card. -->
                <LinearLayout
                    android:id="@+id/onlineToggleRow"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="horizontal"
                    android:gravity="center_vertical" />

                <!-- The three providers are configured separately because they
                     are unrelated endpoints: a podcast feed URL and a Subsonic
                     server URL entered into one field would make saving one
                     silently break the other. A provider with a blank endpoint
                     reports BLOCKED_EXTERNAL_CONFIG on the music screen, which
                     is the truthful state, not an error to hide. -->
                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="@dimen/gap_small"
                    android:textColor="@color/text_tertiary"
                    android:textSize="@dimen/text_micro"
                    android:text="@string/settings_online_hint" />

                <EditText
                    android:id="@+id/onlineFeed"
                    android:layout_width="match_parent"
                    android:layout_height="40dp"
                    android:layout_marginTop="@dimen/gap_small"
                    android:background="@drawable/bg_input_field"
                    android:hint="@string/settings_online_feed_hint"
                    android:textColorHint="@color/text_tertiary"
                    android:paddingStart="@dimen/gap_small"
                    android:paddingEnd="@dimen/gap_small"
                    android:textColor="@color/text_primary"
                    android:textSize="@dimen/text_body"
                    android:singleLine="true"
                    android:inputType="textUri"
                    android:imeOptions="actionNext"
                    android:importantForAutofill="no"
                    tools:ignore="LabelFor" />

                <EditText
                    android:id="@+id/onlineServer"
                    android:layout_width="match_parent"
                    android:layout_height="40dp"
                    android:layout_marginTop="@dimen/gap_small"
                    android:background="@drawable/bg_input_field"
                    android:hint="@string/music_online_server_url"
                    android:textColorHint="@color/text_tertiary"
                    android:paddingStart="@dimen/gap_small"
                    android:paddingEnd="@dimen/gap_small"
                    android:textColor="@color/text_primary"
                    android:textSize="@dimen/text_body"
                    android:singleLine="true"
                    android:inputType="textUri"
                    android:imeOptions="actionNext"
                    android:importantForAutofill="no"
                    tools:ignore="LabelFor" />

                <EditText
                    android:id="@+id/onlineUser"
                    android:layout_width="match_parent"
                    android:layout_height="40dp"
                    android:layout_marginTop="@dimen/gap_small"
                    android:background="@drawable/bg_input_field"
                    android:hint="@string/music_online_server_user"
                    android:textColorHint="@color/text_tertiary"
                    android:paddingStart="@dimen/gap_small"
                    android:paddingEnd="@dimen/gap_small"
                    android:textColor="@color/text_primary"
                    android:textSize="@dimen/text_body"
                    android:singleLine="true"
                    android:inputType="text"
                    android:imeOptions="actionNext"
                    android:importantForAutofill="no"
                    tools:ignore="LabelFor" />

                <!-- The password field is never pre-filled. An empty field
                     means "keep what is stored"; the line under it says whether
                     something is stored, so the driver can tell a saved
                     credential from a blank one without the screen echoing the
                     secret back. -->
                <EditText
                    android:id="@+id/onlinePass"
                    android:layout_width="match_parent"
                    android:layout_height="40dp"
                    android:layout_marginTop="@dimen/gap_small"
                    android:background="@drawable/bg_input_field"
                    android:hint="@string/music_online_server_pass"
                    android:textColorHint="@color/text_tertiary"
                    android:paddingStart="@dimen/gap_small"
                    android:paddingEnd="@dimen/gap_small"
                    android:textColor="@color/text_primary"
                    android:textSize="@dimen/text_body"
                    android:singleLine="true"
                    android:inputType="textPassword"
                    android:imeOptions="actionDone"
                    android:importantForAutofill="no"
                    tools:ignore="LabelFor" />

                <TextView
                    android:id="@+id/onlinePassState"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="4dp"
                    android:textColor="@color/text_tertiary"
                    android:textSize="@dimen/text_micro"
                    tools:text="Đã lưu mật khẩu — để trống nếu giữ nguyên" />

                <!-- Which Subsonic family the URL points at. Chips, filled in
                     by the Activity, because both entries come from one enum. -->
                <LinearLayout
                    android:id="@+id/onlineKindRow"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="@dimen/gap_small"
                    android:orientation="horizontal" />

                <TextView
                    android:id="@+id/onlineStatus"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="@dimen/gap_small"
                    android:textColor="@color/text_tertiary"
                    android:textSize="@dimen/text_micro"
                    android:visibility="gone"
                    tools:visibility="visible"
                    tools:text="BLOCKED_EXTERNAL_CONFIG — chưa cấu hình endpoint/credential cho nguồn này" />
            </LinearLayout>

            <Button
                android:id="@+id/btnSaveOnline"
                style="@style/Button.Ghost"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="@dimen/gap_small"
                android:text="@string/music_online_save" />

            <!-- ===== about ===== -->
            <TextView
                style="@style/Text.Caption"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="@dimen/gap"
                android:paddingStart="@dimen/gap_small"
                android:paddingBottom="4dp"
                android:text="@string/settings_section_about" />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:padding="@dimen/card_pad"
                android:background="@drawable/bg_card">

                <TextView
                    android:id="@+id/versionText"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:textColor="@color/text_secondary"
                    android:textSize="@dimen/text_body"
                    tools:text="TS8 Launcher 1.0.0 (1)" />

                <TextView
                    android:id="@+id/platformText"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="2dp"
                    android:textColor="@color/text_tertiary"
                    android:textSize="@dimen/text_caption"
                    tools:text="Android 6.0 (API 23)" />
            </LinearLayout>
        </LinearLayout>
    </ScrollView>

    <com.ts8.launcher.ui.BottomNavView
        android:id="@+id/bottomNav"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />
</androidx.constraintlayout.widget.ConstraintLayout>

END FILE

## app/src/main/res/layout/view_app_tile.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!--
  One tile in the app grid: icon over label, the way the design draws them.

  The tile is a vertical LinearLayout with a 64 dp icon and a two-line label;
  the label is capped at two lines and ellipsised because a head-unit app list
  routinely contains names like "Car Info" and "File Explorer" on one screen,
  and wrapping to three lines makes the grid rows unequal.
-->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:gravity="center"
    android:padding="@dimen/gap_small"
    android:minHeight="112dp"
    android:background="@drawable/bg_list_item">

    <ImageView
        android:id="@+id/appIcon"
        android:layout_width="@dimen/app_icon_size"
        android:layout_height="@dimen/app_icon_size"
        android:contentDescription="@string/cd_app_icon"
        tools:src="@drawable/ic_nav_apps" />

    <TextView
        android:id="@+id/appLabel"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="@dimen/gap_small"
        android:gravity="center"
        android:textColor="@color/text_secondary"
        android:textSize="@dimen/text_caption"
        android:maxLines="2"
        android:ellipsize="end"
        tools:text="Camera" />
</LinearLayout>

END FILE

## app/src/main/res/layout/view_bottom_nav.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!--
  The single navigation bar. Every screen includes this one file.

  Two rules from the spec are enforced structurally here rather than by
  convention:

  * There is **no left menu anywhere**. A drawer would have to be added to a
    screen's own layout, and no screen's layout has one; this bar is the only
    way to change screen.
  * The order is fixed to Home | Map | Music | Phone | Apps, so the driver's
    muscle memory works on every screen.

  `Phone` is part of the required bar but has no screen of its own: it opens
  the dialer, which is what the design's phone icon means on a head unit. It is
  an item here rather than a separate screen so the bar stays identical
  everywhere.
-->
<merge xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">

    <!--
      The bar has no id of its own: the enclosing BottomNavView carries
      `@+id/bottomNav`, and giving the inner row the same id would make
      `findViewById` ambiguous and the lint check fail on a duplicate id.
    -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="@dimen/bottom_nav_height"
        android:orientation="horizontal"
        android:background="@color/surface_nav"
        android:elevation="8dp">

        <LinearLayout
            android:id="@+id/navHome"
            style="@style/BottomNavItem">

            <ImageView
                android:id="@+id/navHomeIcon"
                style="@style/BottomNavIcon"
                android:src="@drawable/ic_nav_home"
                android:contentDescription="@string/nav_home" />

            <TextView
                android:id="@+id/navHomeLabel"
                style="@style/BottomNavLabel"
                android:text="@string/nav_home" />
        </LinearLayout>

        <LinearLayout
            android:id="@+id/navMap"
            style="@style/BottomNavItem">

            <ImageView
                android:id="@+id/navMapIcon"
                style="@style/BottomNavIcon"
                android:src="@drawable/ic_nav_map"
                android:contentDescription="@string/nav_map" />

            <TextView
                android:id="@+id/navMapLabel"
                style="@style/BottomNavLabel"
                android:text="@string/nav_map" />
        </LinearLayout>

        <LinearLayout
            android:id="@+id/navMusic"
            style="@style/BottomNavItem">

            <ImageView
                android:id="@+id/navMusicIcon"
                style="@style/BottomNavIcon"
                android:src="@drawable/ic_nav_music"
                android:contentDescription="@string/nav_music" />

            <TextView
                android:id="@+id/navMusicLabel"
                style="@style/BottomNavLabel"
                android:text="@string/nav_music" />
        </LinearLayout>

        <LinearLayout
            android:id="@+id/navPhone"
            style="@style/BottomNavItem">

            <ImageView
                android:id="@+id/navPhoneIcon"
                style="@style/BottomNavIcon"
                android:src="@drawable/ic_nav_phone"
                android:contentDescription="@string/nav_phone" />

            <TextView
                android:id="@+id/navPhoneLabel"
                style="@style/BottomNavLabel"
                android:text="@string/nav_phone" />
        </LinearLayout>

        <LinearLayout
            android:id="@+id/navApps"
            style="@style/BottomNavItem">

            <ImageView
                android:id="@+id/navAppsIcon"
                style="@style/BottomNavIcon"
                android:src="@drawable/ic_nav_apps"
                android:contentDescription="@string/nav_apps" />

            <TextView
                android:id="@+id/navAppsLabel"
                style="@style/BottomNavLabel"
                android:text="@string/nav_apps" />
        </LinearLayout>

        <!--
          Volume stepper. The design shows a speaker with a value to either
          side; that value is the media stream level, not a climate setpoint.
          The launcher does not draw a temperature control: on this head unit
          the HVAC is owned by the CANBUS app with no public API, and the spec
          forbids presenting a control that cannot actually act.
        -->
        <LinearLayout
            android:id="@+id/navVolume"
            android:layout_width="wrap_content"
            android:layout_height="match_parent"
            android:orientation="horizontal"
            android:gravity="center_vertical"
            android:paddingStart="8dp"
            android:paddingEnd="4dp">

            <ImageView
                android:id="@+id/volDown"
                android:layout_width="40dp"
                android:layout_height="40dp"
                android:padding="10dp"
                android:src="@drawable/ic_minus"
                android:background="?attr/selectableItemBackgroundBorderless"
                app:tint="@color/icon_inactive"
                android:contentDescription="@string/qs_volume" />

            <ImageView
                android:id="@+id/volIcon"
                android:layout_width="28dp"
                android:layout_height="28dp"
                android:padding="3dp"
                android:src="@drawable/ic_volume"
                app:tint="@color/text_secondary"
                android:contentDescription="@string/qs_volume" />

            <TextView
                android:id="@+id/volLabel"
                android:layout_width="44dp"
                android:layout_height="wrap_content"
                android:gravity="center"
                android:textColor="@color/text_primary"
                android:textSize="@dimen/text_body"
                android:text="0" />

            <ImageView
                android:id="@+id/volUp"
                android:layout_width="40dp"
                android:layout_height="40dp"
                android:padding="10dp"
                android:src="@drawable/ic_plus"
                android:background="?attr/selectableItemBackgroundBorderless"
                app:tint="@color/icon_inactive"
                android:contentDescription="@string/qs_volume" />
        </LinearLayout>
    </LinearLayout>
</merge>

END FILE

## app/src/main/res/layout/view_music_card.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!--
  The music card, in the two shapes the design uses.

  Home shows the full card (artwork, title, artist, progress, transport, and the
  secondary row of heart / shuffle / repeat / queue). The navigation screen
  shows the same card with the secondary row hidden and a smaller artwork, so
  the maneuver card and ETA strip have room. Both are inflated from this one
  file: `MusicCardView` reads its `musicCompact` attribute and hides what does
  not fit, which keeps the two shapes from drifting apart in two files.

  Nothing is filled with demo text. An empty library shows the empty string and
  the transport is disabled, which is the truthful state.
-->
<merge xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical"
        android:background="@drawable/bg_card"
        android:padding="@dimen/card_pad">

        <!-- Artwork. A real album bitmap when the engine has one; otherwise the
             neutral tile with a note icon, which reads as "no artwork" rather
             than as a broken image. -->
        <FrameLayout
            android:id="@+id/artworkFrame"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1"
            android:minHeight="@dimen/music_artwork_size"
            android:background="@drawable/bg_card_raised">

            <ImageView
                android:id="@+id/artwork"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:scaleType="centerCrop"
                android:contentDescription="@string/cd_music_artwork"
                android:visibility="gone" />

            <ImageView
                android:id="@+id/artworkPlaceholder"
                android:layout_width="48dp"
                android:layout_height="48dp"
                android:layout_gravity="center"
                android:src="@drawable/ic_nav_music"
                app:tint="@color/text_tertiary"
                android:contentDescription="@null" />
        </FrameLayout>

        <!-- Title / artist -->
        <TextView
            android:id="@+id/trackTitle"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="@dimen/gap"
            android:textColor="@color/text_primary"
            android:textSize="@dimen/text_body"
            android:textStyle="bold"
            android:maxLines="1"
            android:ellipsize="end"
            android:text="@string/home_music_empty" />

        <TextView
            android:id="@+id/trackSubtitle"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="2dp"
            android:textColor="@color/text_secondary"
            android:textSize="@dimen/text_caption"
            android:maxLines="1"
            android:ellipsize="end"
            android:text="@string/home_music_hint" />

        <!-- Progress. `ProgressBar` on the horizontal style with a custom
             layer-list so the bar is 4 dp with the accent fill the design
             shows; the stock style is 2 dp and barely visible in daylight. -->
        <ProgressBar
            android:id="@+id/position"
            style="?android:attr/progressBarStyleHorizontal"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="@dimen/gap_small"
            android:progressDrawable="@drawable/progress_music"
            android:max="1000"
            android:progress="0" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:layout_marginTop="4dp">

            <TextView
                android:id="@+id/positionText"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:textColor="@color/text_tertiary"
                android:textSize="@dimen/text_micro"
                android:text="00:00" />

            <TextView
                android:id="@+id/durationText"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textColor="@color/text_tertiary"
                android:textSize="@dimen/text_micro"
                android:text="00:00" />
        </LinearLayout>

        <!-- Transport -->
        <LinearLayout
            android:id="@+id/transportRow"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="@dimen/gap_small"
            android:orientation="horizontal"
            android:gravity="center_vertical">

            <ImageButton
                android:id="@+id/btnPrevious"
                android:layout_width="@dimen/music_control_size"
                android:layout_height="@dimen/music_control_size"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:src="@drawable/ic_prev"
                app:tint="@color/text_primary"
                android:contentDescription="@string/music_previous" />

            <Space
                android:layout_width="0dp"
                android:layout_height="1dp"
                android:layout_weight="1" />

            <ImageButton
                android:id="@+id/btnPlayPause"
                android:layout_width="@dimen/music_control_size_large"
                android:layout_height="@dimen/music_control_size_large"
                android:background="@drawable/bg_circle_ring"
                android:src="@drawable/ic_play"
                app:tint="@color/text_primary"
                android:contentDescription="@string/music_play" />

            <Space
                android:layout_width="0dp"
                android:layout_height="1dp"
                android:layout_weight="1" />

            <ImageButton
                android:id="@+id/btnNext"
                android:layout_width="@dimen/music_control_size"
                android:layout_height="@dimen/music_control_size"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:src="@drawable/ic_next"
                app:tint="@color/text_primary"
                android:contentDescription="@string/music_next" />
        </LinearLayout>

        <!-- Secondary row: heart / shuffle / repeat / queue. In the compact
             shape the navigation screen uses, `MusicCardView` hides repeat
             only — the design's compact card keeps the other three. -->
        <LinearLayout
            android:id="@+id/secondaryRow"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="@dimen/gap_small"
            android:orientation="horizontal"
            android:gravity="center_vertical">

            <ImageButton
                android:id="@+id/btnFavorite"
                android:layout_width="@dimen/music_control_size"
                android:layout_height="@dimen/music_control_size"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:src="@drawable/ic_heart"
                app:tint="@color/text_secondary"
                android:contentDescription="@string/music_favorite" />

            <Space
                android:layout_width="0dp"
                android:layout_height="1dp"
                android:layout_weight="1" />

            <ImageButton
                android:id="@+id/btnShuffle"
                android:layout_width="@dimen/music_control_size"
                android:layout_height="@dimen/music_control_size"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:src="@drawable/ic_shuffle"
                app:tint="@color/text_secondary"
                android:contentDescription="@string/music_shuffle" />

            <Space
                android:layout_width="0dp"
                android:layout_height="1dp"
                android:layout_weight="1" />

            <ImageButton
                android:id="@+id/btnRepeat"
                android:layout_width="@dimen/music_control_size"
                android:layout_height="@dimen/music_control_size"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:src="@drawable/ic_repeat"
                app:tint="@color/text_secondary"
                android:contentDescription="@string/music_repeat" />

            <Space
                android:layout_width="0dp"
                android:layout_height="1dp"
                android:layout_weight="1" />

            <ImageButton
                android:id="@+id/btnQueue"
                android:layout_width="@dimen/music_control_size"
                android:layout_height="@dimen/music_control_size"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:src="@drawable/ic_queue"
                app:tint="@color/text_secondary"
                android:contentDescription="@string/music_tab_playlist" />
        </LinearLayout>

        <!-- Shown when playback stopped for a reason worth telling the driver:
             USB pulled, unsupported codec. Takes the subtitle's place rather
             than pushing the transport off the card. -->
        <TextView
            android:id="@+id/errorText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:textColor="@color/danger"
            android:textSize="@dimen/text_micro"
            android:maxLines="2"
            android:visibility="gone"
            tools:visibility="visible"
            tools:text="@string/music_usb_removed" />
    </LinearLayout>
</merge>

END FILE

## app/src/main/res/layout/view_music_row.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!--
  One row in the music library list.

  Used for tracks, albums, artists and playlists alike: the list is a single
  RecyclerView whose content changes with the tab, and giving each kind its own
  row layout would mean four adapters and four chances to drift. The leading
  tile is the album-art position; the trailing chevron means "this opens
  something" and is hidden for leaf rows (a track plays in place).
-->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:minHeight="56dp"
    android:paddingStart="8dp"
    android:paddingEnd="8dp"
    android:paddingTop="6dp"
    android:paddingBottom="6dp"
    android:background="@drawable/bg_list_item">

    <FrameLayout
        android:id="@+id/rowArtworkFrame"
        android:layout_width="44dp"
        android:layout_height="44dp"
        android:background="@drawable/bg_card_raised">

        <ImageView
            android:id="@+id/rowArtwork"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:scaleType="centerCrop"
            android:contentDescription="@null"
            android:visibility="gone" />

        <ImageView
            android:id="@+id/rowIcon"
            android:layout_width="22dp"
            android:layout_height="22dp"
            android:layout_gravity="center"
            android:src="@drawable/ic_nav_music"
            app:tint="@color/text_tertiary"
            android:contentDescription="@null" />
    </FrameLayout>

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:layout_marginStart="@dimen/gap"
        android:orientation="vertical">

        <TextView
            android:id="@+id/rowTitle"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textColor="@color/text_primary"
            android:textSize="@dimen/text_body"
            android:maxLines="1"
            android:ellipsize="end"
            tools:text="Chạy Về Nơi Phía Anh" />

        <TextView
            android:id="@+id/rowSubtitle"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="2dp"
            android:textColor="@color/text_secondary"
            android:textSize="@dimen/text_caption"
            android:maxLines="1"
            android:ellipsize="end"
            tools:text="Khắc Việt" />
    </LinearLayout>

    <!-- Shown only for rows that open a collection. -->
    <ImageView
        android:id="@+id/rowChevron"
        android:layout_width="20dp"
        android:layout_height="20dp"
        android:src="@drawable/ic_chevron_right"
        app:tint="@color/text_tertiary"
        android:contentDescription="@null"
        android:visibility="gone"
        tools:visibility="visible" />

    <!-- The "now playing" bar on the current row. A dot rather than a
         background change, so it reads on both a light and a dark row. -->
    <View
        android:id="@+id/rowPlayingDot"
        android:layout_width="6dp"
        android:layout_height="6dp"
        android:layout_marginEnd="@dimen/gap_small"
        android:background="@drawable/bg_circle_accent"
        android:visibility="gone"
        tools:visibility="gone" />
</LinearLayout>

END FILE

## app/src/main/res/layout/view_qs_tile.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!--
  The contents of one quick-settings tile, inflated by `QsTileView`.

  A `<merge>` because the view itself is the row: an extra wrapper inside would
  cost a measure pass per tile on a device that already fights for frames, and
  the tiles are the first thing drawn on this screen.
-->
<merge xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <ImageView
        android:id="@+id/tileIcon"
        android:layout_width="26dp"
        android:layout_height="26dp"
        android:contentDescription="@null" />

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:layout_marginStart="@dimen/gap"
        android:orientation="vertical">

        <TextView
            android:id="@+id/tileLabel"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textColor="@color/text_primary"
            android:textSize="@dimen/text_body"
            android:maxLines="1"
            android:ellipsize="end"
            tools:text="Wi-Fi" />

        <TextView
            android:id="@+id/tileValue"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="2dp"
            android:textColor="@color/text_tertiary"
            android:textSize="@dimen/text_caption"
            android:maxLines="1"
            android:ellipsize="end"
            tools:text="Bật" />
    </LinearLayout>
</merge>

END FILE

## app/src/main/res/layout/view_search_row.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!--
  One row in the search result list.

  Two lines of text and a trailing distance. The distance is a separate view
  rather than part of the subtitle because it is right-aligned: a place name and
  its distance read as two columns, and joining them into one string makes the
  numbers ragged down the list.
-->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:minHeight="56dp"
    android:paddingStart="@dimen/gap"
    android:paddingEnd="@dimen/gap"
    android:paddingTop="6dp"
    android:paddingBottom="6dp"
    android:background="@drawable/bg_list_item">

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:orientation="vertical">

        <TextView
            android:id="@+id/resultName"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textColor="@color/text_primary"
            android:textSize="@dimen/text_body"
            android:maxLines="1"
            android:ellipsize="end"
            tools:text="Cầu Rồng" />

        <TextView
            android:id="@+id/resultAddress"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="2dp"
            android:textColor="@color/text_secondary"
            android:textSize="@dimen/text_caption"
            android:maxLines="1"
            android:ellipsize="end"
            tools:text="Đ. Nguyễn Văn Linh, Đà Nẵng" />
    </LinearLayout>

    <TextView
        android:id="@+id/resultDistance"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="@dimen/gap"
        android:textColor="@color/text_tertiary"
        android:textSize="@dimen/text_caption"
        android:maxLines="1"
        tools:text="6,2 km" />
</LinearLayout>

END FILE

## app/src/main/res/layout/view_status_bar.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!--
  The status row the design puts at the top of every screen.

  Left: clock and date, the two things a driver glances at.
  Right: connectivity icons and the "Đà Nẵng 28°C" chip.

  The weather chip is the one element the design shows that this launcher
  cannot truthfully fill. The spec forbids mock data standing in for a
  production result, and a fabricated temperature is exactly that, so the chip
  is hidden until a real reading exists. There is no weather provider in this
  campaign (the spec lists weather widgets as out of scope), so in practice it
  stays hidden — which is the honest outcome, not a gap to paper over.
-->
<merge xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools">

    <LinearLayout
        android:id="@+id/statusRow"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:paddingStart="@dimen/screen_pad"
        android:paddingEnd="@dimen/screen_pad"
        android:paddingTop="6dp"
        android:paddingBottom="6dp">

        <TextView
            android:id="@+id/clockText"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@color/text_primary"
            android:textSize="@dimen/text_title"
            android:fontFamily="sans-serif-medium"
            android:text="--:--" />

        <TextView
            android:id="@+id/dateText"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="12dp"
            android:textColor="@color/text_secondary"
            android:textSize="@dimen/text_caption"
            android:text="" />

        <Space
            android:layout_width="0dp"
            android:layout_height="1dp"
            android:layout_weight="1" />

        <!-- Reserved for a real provider; hidden by default, see the note above. -->
        <TextView
            android:id="@+id/weatherChip"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginEnd="12dp"
            android:textColor="@color/text_secondary"
            android:textSize="@dimen/text_caption"
            android:visibility="gone"
            tools:ignore="HardcodedText"
            android:text="" />

        <!-- The radio icons double as the Quick Settings entry point, which is
             the idiom every head unit on this panel uses: the driver taps the
             row of icons they were already looking at. The row is its own
             clickable child so a tap on the clock is not swallowed by it. -->
        <LinearLayout
            android:id="@+id/statusIcons"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:gravity="center_vertical"
            android:clickable="true"
            android:focusable="true"
            android:paddingStart="8dp"
            android:paddingEnd="4dp"
            android:paddingTop="4dp"
            android:paddingBottom="4dp"
            android:background="?attr/selectableItemBackgroundBorderless">

            <ImageView
                android:id="@+id/iconSignal"
                android:layout_width="18dp"
                android:layout_height="18dp"
                android:layout_marginStart="6dp"
                android:src="@drawable/ic_signal"
                app:tint="@color/text_tertiary"
                android:contentDescription="@string/qs_mobile_data" />

            <ImageView
                android:id="@+id/iconWifi"
                android:layout_width="18dp"
                android:layout_height="18dp"
                android:layout_marginStart="8dp"
                android:src="@drawable/ic_wifi"
                app:tint="@color/text_tertiary"
                android:contentDescription="@string/qs_wifi" />

            <ImageView
                android:id="@+id/iconBluetooth"
                android:layout_width="18dp"
                android:layout_height="18dp"
                android:layout_marginStart="8dp"
                android:src="@drawable/ic_bluetooth"
                app:tint="@color/text_tertiary"
                android:contentDescription="@string/qs_bluetooth" />

            <ImageView
                android:id="@+id/iconBattery"
                android:layout_width="18dp"
                android:layout_height="18dp"
                android:layout_marginStart="8dp"
                android:src="@drawable/ic_battery"
                app:tint="@color/text_tertiary"
                android:contentDescription="@string/qs_title" />
        </LinearLayout>
    </LinearLayout>
</merge>

END FILE

## app/src/main/res/layout/view_voice_overlay.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<!--
  Voice Instant's overlay: one bar, bottom-centre, never a dialog.

  §11 says the overlay must not cover the maneuver card or the navigation. On
  this panel the maneuver card and the ETA strip sit top-left, the speed chip
  top-right, so the only region that is clear on *every* screen is the strip
  just above the bottom navigation. It is also where the driver's hand already
  is when they reach for the bar, which matters more in a car than symmetry.

  The bar is 320 dp wide rather than full width: at 1024x600 a full-width strip
  reads as a modal sheet and hides the map row behind it, and the driver is
  still supposed to be looking at the road.

  Buttons are declared all at once and shown per state. Declaring them
  conditionally would mean inflating different bar heights for different
  states, and the bar would jump vertically between LISTENING and CONFIRMING —
  exactly when the driver's eyes are on it.
-->
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:clickable="false"
    android:focusable="false">

    <LinearLayout
        android:id="@+id/voicePill"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|center_horizontal"
        android:layout_marginStart="@dimen/gap"
        android:layout_marginEnd="@dimen/gap"
        android:layout_marginBottom="84dp"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:paddingStart="@dimen/card_pad"
        android:paddingEnd="@dimen/gap_small"
        android:paddingTop="@dimen/gap_small"
        android:paddingBottom="@dimen/gap_small"
        android:background="@drawable/bg_card_raised"
        android:clickable="true"
        android:focusable="true"
        android:elevation="8dp">

        <ImageView
            android:id="@+id/voiceIcon"
            android:layout_width="24dp"
            android:layout_height="24dp"
            android:src="@drawable/ic_mic"
            app:tint="@color/accent_text"
            android:contentDescription="@string/voice_mic" />

        <!-- `wrap_content` + `maxWidth` rather than 0dp + weight: the pill
             itself is wrap_content, and a weighted child inside a wrap_content
             parent measures against an unbounded second pass, which makes the
             caption jump width between states. -->
        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="@dimen/gap"
            android:maxWidth="320dp"
            android:orientation="vertical">

            <TextView
                android:id="@+id/voiceTitle"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:textColor="@color/text_primary"
                android:textSize="@dimen/text_body"
                android:maxLines="2"
                android:ellipsize="end"
                tools:text="Đang nghe…" />

            <!-- The sentence being spoken. Shown because a driver in a noisy
                 cabin reads it when they cannot hear it. -->
            <TextView
                android:id="@+id/voiceCaption"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="2dp"
                android:textColor="@color/text_secondary"
                android:textSize="@dimen/text_caption"
                android:maxLines="2"
                android:ellipsize="end"
                android:visibility="gone"
                tools:visibility="visible"
                tools:text="Xác nhận dẫn đường tới Cầu Rồng?" />
        </LinearLayout>

        <LinearLayout
            android:id="@+id/voiceActions"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="@dimen/gap_small"
            android:orientation="horizontal"
            android:gravity="center_vertical">

            <Button
                android:id="@+id/btnVoiceYes"
                style="@style/Button.Primary"
                android:layout_width="wrap_content"
                android:layout_height="44dp"
                android:minWidth="96dp"
                android:text="@string/voice_confirm_yes"
                android:visibility="gone" />

            <Button
                android:id="@+id/btnVoiceNo"
                style="@style/Button.Ghost"
                android:layout_width="wrap_content"
                android:layout_height="44dp"
                android:layout_marginStart="@dimen/gap_small"
                android:minWidth="80dp"
                android:text="@string/voice_confirm_no"
                android:visibility="gone" />

            <Button
                android:id="@+id/btnVoiceRetry"
                style="@style/Button.Primary"
                android:layout_width="wrap_content"
                android:layout_height="44dp"
                android:minWidth="96dp"
                android:text="@string/voice_retry"
                android:visibility="gone" />

            <Button
                android:id="@+id/btnVoiceSettings"
                style="@style/Button.Ghost"
                android:layout_width="wrap_content"
                android:layout_height="44dp"
                android:minWidth="96dp"
                android:text="@string/voice_open_settings"
                android:visibility="gone" />

            <ImageButton
                android:id="@+id/btnVoiceClose"
                android:layout_width="44dp"
                android:layout_height="44dp"
                android:layout_marginStart="@dimen/gap_small"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:src="@drawable/ic_close"
                app:tint="@color/text_secondary"
                android:contentDescription="@string/voice_close"
                android:visibility="gone" />
        </LinearLayout>
    </LinearLayout>
</FrameLayout>

END FILE

## app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/bg_window" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>

END FILE

## app/src/main/res/mipmap-anydpi/ic_launcher.xml

BEGIN FILE
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="48dp"
    android:height="48dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#0A1120"
        android:pathData="M0,0h108v108h-108z" />
    <path
        android:fillColor="#1B2740"
        android:pathData="M30,108 C42,74 62,52 108,38 L108,108 Z" />
    <path
        android:fillColor="#3B82F6"
        android:pathData="M38,108 C48,80 66,60 108,46 L108,56 C70,68 54,86 46,108 Z" />
    <path
        android:fillColor="#F2F6FC"
        android:pathData="M54,34 L70,66 L54,59 L38,66 Z" />
</vector>

END FILE

## app/src/main/res/values/attrs.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!--
      MusicCardView has two shapes in the design: the full card on Home and the
      short strip on the Navigation screen, where the maneuver card and the ETA
      strip need the vertical space more than the transport row does.

      It is one view with one attribute rather than two classes because the two
      shapes share every binding — same artwork, same title, same progress, same
      transport — and a second class would be a copy that drifts. The attribute
      only decides which secondary row is inflated.
    -->
    <declare-styleable name="MusicCardView">
        <attr name="musicCompact" format="boolean" />
    </declare-styleable>

    <!--
      One quick-settings tile. The icon and the label are declared in XML so the
      tile is described where it is placed; the *value* line ("Bật" / "Tắt") is
      never declared, because it is platform state and an XML default would be a
      claim the app has not read yet.
    -->
    <declare-styleable name="QsTileView">
        <attr name="qsIcon" format="reference" />
        <attr name="qsLabel" format="reference" />
    </declare-styleable>
</resources>

END FILE

## app/src/main/res/values/colors.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- ==================================================================
         Colour tokens derived from docs/UI_design.png.
         Dark theme is the product default (the head unit is always dark);
         the light block exists so the "Chế độ tối" toggle has a real target.
         ================================================================== -->

    <!-- Surfaces -->
    <color name="bg_root">#FF070B14</color>
    <color name="bg_window">#FF0A1120</color>
    <color name="surface_card">#FF111A2B</color>
    <color name="surface_card_raised">#FF16223A</color>
    <color name="surface_nav">#FF0B1220</color>
    <color name="surface_chip">#FF1B2740</color>
    <color name="surface_scrim">#B3000000</color>

    <!-- Accent -->
    <color name="accent">#FF3B82F6</color>
    <color name="accent_pressed">#FF2A6FD6</color>
    <color name="accent_soft">#333B82F6</color>
    <color name="accent_text">#FF6BA5FF</color>

    <!-- Text -->
    <color name="text_primary">#FFF2F6FC</color>
    <color name="text_secondary">#FFA8B4C8</color>
    <color name="text_tertiary">#FF6C7A91</color>
    <color name="text_on_accent">#FFFFFFFF</color>

    <!-- Semantic -->
    <color name="speed_limit_ring">#FFE5383B</color>
    <color name="speed_limit_fill">#FFFFFFFF</color>
    <color name="route_line">#FF3B82F6</color>
    <color name="route_line_casing">#FF0B3A66</color>
    <color name="nav_muted">#FF9AA6B8</color>
    <color name="danger">#FFEF4444</color>
    <color name="success">#FF22C55E</color>

    <!-- Divider -->
    <color name="divider">#1FFFFFFF</color>
    <color name="divider_strong">#33FFFFFF</color>

    <!-- Icons -->
    <color name="icon_inactive">#FF8A97AC</color>
    <!--
      The selected bottom-nav item, and only that. Measured off the design
      sheet: rgb(32,226,252) on Home, rgb(26,218,251) on Navigation,
      rgb(3,223,253) on Search — three independent panels, so this is the
      design's nav accent and not a compression artefact.

      It is deliberately NOT @color/accent. The sheet uses three different
      saturated colours and this project had collapsed them into one:
        nav active   rgb(32,226,252)  <- this value
        primary CTA  rgb(1,117,252)   <- @color/accent, already correct
        media slider rgb(0,163,247)   <- @color/text_tertiary track today
      Using the accent blue here made the nav read as "the button colour,
      faded". Splitting the token costs nothing: nav_item_tint is the only
      consumer, so this changes the bottom bar and nothing else.
    -->
    <color name="icon_active">#FF20E2FC</color>

    <!-- Legacy aliases used by AppCompat widgets -->
    <color name="colorPrimary">@color/accent</color>
    <color name="colorPrimaryDark">@color/bg_root</color>
    <color name="colorAccent">@color/accent</color>
</resources>

END FILE

## app/src/main/res/values/dimens.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Scale-safe dimensions. The acceptance gate requires the layout to work
         at 1024x600; these values are used with sw600dp qualifiers where a
         larger screen would otherwise leave dead space. -->
    <dimen name="screen_pad">16dp</dimen>
    <dimen name="card_radius">20dp</dimen>
    <dimen name="card_radius_small">14dp</dimen>
    <dimen name="card_pad">16dp</dimen>
    <dimen name="gap">12dp</dimen>
    <dimen name="gap_small">8dp</dimen>

    <dimen name="bottom_nav_height">72dp</dimen>
    <dimen name="bottom_nav_icon">26dp</dimen>
    <dimen name="bottom_nav_label">12sp</dimen>
    <dimen name="scorebar_height">44dp</dimen>

    <!-- The status row. Sized for a 24 sp clock plus the 18 dp radio icons
         and 6 dp vertical padding; a taller row would push the map down on a
         600 dp-tall panel, which is exactly the screen this must fit. -->
    <dimen name="status_height">44dp</dimen>

    <dimen name="music_card_width">300dp</dimen>
    <dimen name="music_card_width_compact">236dp</dimen>
    <dimen name="music_artwork_size">84dp</dimen>
    <dimen name="music_artwork_size_compact">56dp</dimen>
    <dimen name="music_artwork_large">248dp</dimen>
    <dimen name="music_control_size">44dp</dimen>
    <dimen name="music_control_size_large">64dp</dimen>

    <!-- Map screen chrome. Every round control is 44 dp: below that a thumb
         misses while the car is moving, which is the only ergonomics that
         matters on this panel. -->
    <dimen name="map_control_size">44dp</dimen>
    <dimen name="maneuver_card_width">320dp</dimen>
    <dimen name="eta_strip_height">48dp</dimen>

    <!-- App drawer: six columns as the design draws them. -->
    <dimen name="app_icon_size">64dp</dimen>
    <dimen name="app_grid_spacing">12dp</dimen>

    <!-- Quick settings -->
    <dimen name="qs_tile_height">92dp</dimen>
    <dimen name="qs_slider_height">48dp</dimen>

    <!-- Parking mode clock -->
    <dimen name="parking_clock">96sp</dimen>

    <dimen name="text_micro">10sp</dimen>
    <dimen name="text_caption">12sp</dimen>
    <dimen name="text_body">14sp</dimen>
    <dimen name="text_title">18sp</dimen>
    <dimen name="text_headline">24sp</dimen>
    <dimen name="text_display">36sp</dimen>
    <dimen name="text_huge">64sp</dimen>
</resources>

END FILE

## app/src/main/res/values/strings.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">TS8 Launcher</string>

    <!-- Bottom navigation -->
    <string name="nav_home">Trang chủ</string>
    <string name="nav_map">Bản đồ</string>
    <string name="nav_music">Âm nhạc</string>
    <string name="nav_phone">Điện thoại</string>
    <string name="nav_apps">Ứng dụng</string>

    <!-- Home -->
    <string name="home_speed_unit">km/h</string>
    <string name="home_music_empty">Chưa phát nhạc</string>
    <string name="home_music_hint">Mở Âm nhạc để chọn bài</string>
    <string name="home_gps_searching">Đang tìm tín hiệu GPS</string>
    <string name="home_map_unavailable">Bản đồ chưa sẵn sàng</string>
    <string name="home_zoom_in">Phóng to</string>
    <string name="home_zoom_out">Thu nhỏ</string>
    <string name="home_recenter">Về vị trí xe</string>
    <string name="home_layers">Lớp bản đồ</string>
    <string name="home_speed_limit_none">Chưa có dữ liệu giới hạn tốc độ</string>

    <!-- Search -->
    <string name="search_hint">Tìm kiếm địa điểm, địa chỉ…</string>
    <string name="search_empty_prompt">Nhập tên địa điểm để tìm</string>
    <string name="search_no_results">Không tìm thấy kết quả</string>
    <string name="search_route">Lộ trình</string>
    <string name="search_save">Lưu</string>
    <string name="search_km">km</string>
    <string name="search_minute">phút</string>
    <string name="search_close">Đóng tìm kiếm</string>
    <string name="search_clear">Xoá nội dung</string>
    <string name="search_loading">Đang tìm…</string>
    <string name="search_unavailable">Chưa có dữ liệu địa điểm. Kiểm tra map pack.</string>
    <string name="search_result_count">%1$d kết quả</string>
    <string name="search_open_map">Mở bản đồ</string>
    <string name="search_pick_on_map">Chọn trên bản đồ</string>
    <string name="search_select_hint">Chọn một kết quả để xem chi tiết</string>
    <string name="search_results_label">Kết quả</string>
    <string name="search_saved_label">Địa điểm đã lưu</string>
    <string name="search_no_saved">Chưa lưu địa điểm nào. Nhập tên để tìm.</string>
    <string name="search_distance_away">Cách %1$s</string>
    <string name="search_saved">Đã lưu</string>
    <string name="search_unsave">Bỏ lưu</string>
    <string name="search_cat_poi">Địa điểm</string>
    <string name="search_cat_street">Đường</string>
    <string name="search_cat_place">Khu vực</string>
    <string name="search_cat_address">Địa chỉ</string>

    <!-- Music -->
    <string name="music_play">Phát</string>
    <string name="music_pause">Tạm dừng</string>
    <string name="music_next">Bài tiếp</string>
    <string name="music_previous">Bài trước</string>
    <string name="music_shuffle">Ngẫu nhiên</string>
    <string name="music_repeat">Lặp lại</string>
    <string name="music_repeat_one">Lặp một bài</string>
    <string name="music_favorite">Yêu thích</string>
    <string name="music_tab_playlist">Danh sách phát</string>
    <string name="music_tab_songs">Bài hát</string>
    <string name="music_tab_albums">Album</string>
    <string name="music_tab_artists">Nghệ sĩ</string>
    <string name="music_empty">Chưa có bài hát nào</string>
    <string name="music_empty_hint">Cắm USB hoặc chép nhạc vào bộ nhớ trong</string>
    <string name="music_usb_removed">USB đã được tháo</string>
    <string name="music_scanning">Đang quét nhạc…</string>
    <string name="music_source_unavailable">Không đọc được tệp. Kiểm tra USB.</string>
    <string name="music_stream_unavailable">Không kết nối được nguồn trực tuyến</string>
    <string name="music_stream_interrupted">Mất kết nối khi đang phát</string>
    <string name="music_buffering">Đang tải…</string>
    <string name="music_retry">Thử lại</string>
    <string name="music_unknown_track">Không rõ tên bài</string>
    <string name="music_unknown_artist">Không rõ nghệ sĩ</string>
    <string name="music_track_count">%1$d bài</string>
    <string name="music_library_footer">%1$d bài • %2$s</string>
    <string name="music_queue_empty">Danh sách phát trống</string>

    <!-- Online music (§13). The two group labels are the brief's own words and
         are what makes a mixed result list readable at a glance. -->
    <string name="music_search_hint">Tìm bài hát hoặc đài…</string>
    <string name="music_search_local_group">Trên thiết bị</string>
    <string name="music_search_online_group">Trực tuyến</string>
    <string name="music_search_empty">Không có kết quả cho \"%1$s\"</string>
    <string name="music_search_offline">Nhạc trực tuyến đang tắt trong Cài đặt</string>
    <string name="music_online_loading">Đang tìm trên nguồn trực tuyến…</string>
    <string name="music_online_no_config">Chưa cấu hình nguồn trực tuyến. Vào Cài đặt để nhập địa chỉ.</string>
    <string name="music_online_provider_line">%1$s: %2$s</string>
    <string name="music_online_count">%1$d kết quả</string>
    <string name="music_online_failed">Không hỏi được nguồn</string>
    <string name="music_online_ready">sẵn sàng</string>

    <!-- Navigation -->
    <string name="nav_mute">Tắt tiếng</string>
    <string name="nav_unmute">Bật tiếng</string>
    <string name="nav_end">Kết thúc</string>
    <string name="nav_overview">Toàn cảnh</string>
    <string name="nav_arrived">Đã đến nơi</string>
    <string name="nav_then">Sau đó</string>
    <string name="nav_eta_format">%1$s • %2$s • Đến %3$s</string>
    <string name="nav_route_summary">%1$s • khoảng %2$s</string>
    <string name="nav_start">Bắt đầu</string>
    <string name="nav_cancel">Huỷ</string>
    <string name="nav_calculating">Đang tính lộ trình…</string>
    <string name="nav_rerouting">Đang tìm lộ trình mới…</string>
    <string name="nav_no_destination">Chưa chọn điểm đến</string>
    <string name="nav_no_pack">Chưa cài map pack — không thể dẫn đường</string>
    <string name="nav_no_route">Chưa có lộ trình</string>
    <string name="nav_start_navigation">Đi</string>
    <string name="nav_voice_unavailable">Thiết bị không có giọng tiếng Việt</string>
    <string name="nav_follow_off">Bản đồ tự do</string>

    <!-- Quick settings -->
    <string name="qs_title">Cài đặt nhanh</string>
    <string name="qs_wifi">Wi-Fi</string>
    <string name="qs_bluetooth">Bluetooth</string>
    <string name="qs_mobile_data">Dữ liệu 4G</string>
    <string name="qs_theme">Chế độ tối</string>
    <string name="qs_theme_auto">Tự động</string>
    <string name="qs_theme_day">Ban ngày</string>
    <string name="qs_theme_night">Ban đêm</string>
    <string name="qs_brightness">Độ sáng</string>
    <string name="qs_volume">Âm lượng</string>
    <string name="qs_fullscreen">Màn hình tối</string>
    <string name="qs_restart">Khởi động lại</string>
    <string name="qs_settings">Cài đặt</string>
    <string name="qs_unavailable">Thiết bị không hỗ trợ</string>
    <string name="qs_permission_required">Cần cấp quyền để thay đổi mục này</string>
    <string name="qs_on">Bật</string>
    <string name="qs_off">Tắt</string>
    <string name="qs_percent">%1$d%%</string>
    <string name="qs_open_panel">Mở cài đặt</string>
    <string name="qs_parking_mode">Chế độ đỗ xe</string>
    <string name="qs_restart_confirm">Khởi động lại thiết bị?</string>
    <string name="qs_restart_mic">Mic xe</string>

    <!-- Settings. Every string here names a real fact or a real action; there
         is no row for anything this build cannot do. -->
    <string name="settings_section_map">Bản đồ</string>
    <string name="settings_rescan_pack">Tìm lại map pack</string>
    <string name="settings_pack_missing">Chưa có map pack</string>
    <string name="settings_pack_ready">Đã dùng map pack: %1$s</string>
    <string name="settings_section_permissions">Quyền truy cập</string>
    <string name="settings_perm_brightness">Độ sáng màn hình</string>
    <string name="settings_perm_granted">%1$s — đã cấp</string>
    <string name="settings_perm_denied">%1$s — chạm để cấp</string>
    <string name="settings_section_online">Nhạc trực tuyến</string>
    <string name="settings_online_hint">Để trống nguồn nào thì nguồn đó báo BLOCKED_EXTERNAL_CONFIG trên màn hình nhạc. Đài Internet Radio không cần cấu hình.</string>
    <string name="settings_online_feed_hint">URL feed Podcast (RSS)</string>
    <string name="settings_online_pass_saved">Đã lưu mật khẩu — để trống nếu giữ nguyên</string>
    <string name="settings_online_pass_none">Chưa có mật khẩu</string>
    <string name="settings_online_saved">Đã lưu cấu hình nhạc trực tuyến</string>

    <string name="settings_section_about">Thông tin</string>
    <string name="settings_version">TS8 Launcher %1$s (%2$d)</string>
    <string name="settings_version_unknown">không rõ</string>
    <string name="settings_platform">Android %1$s (API %2$d)</string>

    <!-- Parking -->
    <string name="parking_message">Chuyến đi tốt đẹp nhé!</string>
    <string name="parking_tap_to_exit">Chạm để quay lại</string>

    <!-- Apps -->
    <string name="apps_title">Ứng dụng</string>
    <string name="apps_empty">Không tìm thấy ứng dụng</string>
    <string name="apps_open_failed">Không mở được ứng dụng này</string>
    <string name="apps_permission_denied">Cần cấp quyền để mở ứng dụng</string>
    <string name="apps_search_hint">Tìm ứng dụng…</string>

    <!-- Permissions -->
    <string name="perm_location_title">Cần quyền vị trí</string>
    <string name="perm_location_body">Bản đồ và dẫn đường cần quyền truy cập GPS.</string>
    <string name="perm_storage_title">Cần quyền đọc bộ nhớ</string>
    <string name="perm_storage_body">Ứng dụng cần đọc bộ nhớ để tìm nhạc trong máy và USB.</string>
    <string name="perm_grant">Cấp quyền</string>
    <string name="perm_denied_forever">Quyền đã bị từ chối. Mở Cài đặt để cấp lại.</string>
    <string name="perm_open_settings">Mở Cài đặt</string>

    <!-- Generic -->
    <string name="cd_map">Bản đồ</string>
    <string name="cd_music_artwork">Ảnh bìa album</string>
    <string name="cd_app_icon">Biểu tượng ứng dụng</string>
    <string name="generic_loading">Đang tải…</string>
    <string name="generic_error">Đã xảy ra lỗi</string>

    <!-- Map management / map settings (§8). Every label here maps to a real
         stored value that the map actually reads. -->
    <string name="map_mgmt_title">Quản lý bản đồ</string>
    <string name="map_mgmt_entry">Quản lý bản đồ &amp; cài đặt</string>
    <string name="map_cta_install">Cài bản đồ</string>
    <string name="map_cta_retry">Kiểm tra lại</string>
    <string name="map_cta_diagnostics">Chẩn đoán</string>
    <string name="map_section_packs">Map pack đã cài</string>
    <string name="map_section_storage">Dung lượng</string>
    <string name="map_section_display">Hiển thị</string>
    <string name="map_section_speed">Tốc độ &amp; cảnh báo</string>
    <string name="map_section_diagnostics">Chẩn đoán</string>
    <string name="map_pack_active">Đang dùng</string>
    <string name="map_pack_none">Chưa cài map pack</string>
    <string name="map_pack_status_ready">Sẵn sàng</string>
    <string name="map_pack_status_invalid">Lỗi: %1$s</string>
    <string name="map_pack_status_validating">Đang kiểm tra…</string>
    <string name="map_pack_region">Khu vực: %1$s</string>
    <string name="map_pack_version">Phiên bản: %1$s</string>
    <string name="map_pack_size">Dung lượng: %1$s</string>
    <string name="map_pack_data_date">Dữ liệu: %1$s</string>
    <string name="map_pack_routing_ok">Dữ liệu dẫn đường: có</string>
    <string name="map_pack_routing_missing">Dữ liệu dẫn đường: thiếu — không thể dẫn đường</string>
    <string name="map_pack_search_ok">Chỉ mục tìm kiếm: có (%1$d địa điểm)</string>
    <string name="map_pack_search_missing">Chỉ mục tìm kiếm: thiếu — tìm offline không dùng được</string>
    <string name="map_action_import">Nhập từ USB / bộ nhớ</string>
    <string name="map_action_validate">Kiểm tra lại</string>
    <string name="map_action_activate">Kích hoạt</string>
    <string name="map_action_remove">Xoá</string>
    <string name="map_action_repair">Sửa chữa</string>
    <string name="map_import_title">Chọn file map pack</string>
    <string name="map_import_zip_hint">Chọn file .ts8map hoặc .zip chứa metadata.json</string>
    <string name="map_import_running">Đang nhập map pack…</string>
    <string name="map_import_stage">Bước: %1$s</string>
    <string name="map_import_done">Đã nhập map pack: %1$s</string>
    <string name="map_import_failed">Nhập thất bại: %1$s</string>
    <string name="map_import_zip_slip">File nén chứa đường dẫn không an toàn — đã chặn</string>
    <string name="map_import_no_room">Không đủ dung lượng để nhập pack này</string>
    <string name="map_import_kept_old">Map pack cũ vẫn được giữ nguyên</string>
    <string name="map_remove_title">Xoá map pack?</string>
    <string name="map_remove_body">Pack “%1$s” sẽ bị xoá khỏi bộ nhớ. Không thể hoàn tác.</string>
    <string name="map_remove_active_body">“%1$s” đang được dùng. Hãy chọn pack thay thế trước, hoặc xác nhận để chuyển sang trạng thái không có bản đồ.</string>
    <string name="map_remove_confirm_no_map">Chuyển sang không có bản đồ</string>
    <string name="map_remove_done">Đã xoá %1$s</string>
    <string name="map_remove_failed">Không xoá được: %1$s</string>
    <string name="map_activate_done">Đang dùng %1$s</string>
    <string name="map_free_space">Còn trống: %1$s / %2$s</string>
    <string name="map_storage_location">Vị trí lưu: %1$s</string>
    <string name="map_diag_copy">Sao chép chẩn đoán</string>
    <string name="map_diag_copied">Đã sao chép chẩn đoán</string>
    <string name="map_diag_empty">Chưa có dữ liệu chẩn đoán</string>

    <!-- Display settings group (§8). Options are Vietnamese labels over stable
         enum names, so the stored value survives a locale change. -->
    <string name="disp_mode">Chế độ bản đồ</string>
    <string name="disp_mode_2d">2D</string>
    <string name="disp_mode_3d">3D</string>
    <string name="disp_mode_auto">Tự động</string>
    <string name="disp_theme">Giao diện</string>
    <string name="disp_theme_day">Sáng</string>
    <string name="disp_theme_night">Tối</string>
    <string name="disp_theme_auto">Theo thời gian</string>
    <string name="disp_camera">Góc nhìn khi dẫn đường</string>
    <string name="disp_camera_north">Hướng Bắc</string>
    <string name="disp_camera_heading">Theo hướng xe</string>
    <string name="disp_camera_perspective">Phối cảnh</string>
    <string name="disp_zoom_default">Mức zoom mặc định</string>
    <string name="disp_zoom_nav">Mức zoom khi dẫn đường</string>
    <string name="disp_buildings_3d">Toà nhà 3D</string>
    <string name="disp_labels_road">Tên đường</string>
    <string name="disp_labels_poi">POI</string>
    <string name="disp_labels_place">Địa danh</string>
    <string name="disp_labels_housenumber">Số nhà</string>
    <string name="disp_poi_density">Mật độ POI</string>
    <string name="disp_poi_low">Thấp</string>
    <string name="disp_poi_normal">Vừa</string>
    <string name="disp_poi_high">Cao</string>
    <string name="disp_text_size">Cỡ chữ và icon</string>
    <string name="disp_size_small">Nhỏ</string>
    <string name="disp_size_normal">Vừa</string>
    <string name="disp_size_large">Lớn</string>
    <string name="disp_route_travelled">Hiện đường đã đi</string>
    <string name="disp_route_colour">Màu đường chính</string>
    <string name="disp_route_alt_colour">Màu đường thay thế</string>
    <string name="disp_auto_follow">Tự bám theo xe</string>
    <string name="disp_auto_centre">Tự căn giữa</string>
    <string name="disp_follow_return">Quay lại bám xe sau</string>
    <string name="disp_follow_return_5">5 giây</string>
    <string name="disp_follow_return_10">10 giây</string>
    <string name="disp_follow_return_20">20 giây</string>
    <string name="disp_follow_return_off">Không tự quay lại</string>
    <string name="disp_show_zoom">Nút zoom</string>
    <string name="disp_show_compass">La bàn</string>
    <string name="disp_show_recenter">Nút căn giữa</string>
    <string name="disp_show_overview">Nút toàn cảnh</string>
    <string name="disp_performance">Chế độ hiệu năng</string>
    <string name="disp_perf_quality">Chất lượng</string>
    <string name="disp_perf_balanced">Cân bằng</string>
    <string name="disp_perf_saver">Tiết kiệm</string>
    <string name="disp_restore_defaults">Khôi phục mặc định</string>
    <string name="disp_restored">Đã khôi phục cài đặt hiển thị mặc định</string>

    <!-- Speed & alerts group (§8). -->
    <string name="speed_unit">Đơn vị tốc độ</string>
    <string name="speed_unit_kmh">km/h</string>
    <string name="speed_unit_mph">mph</string>
    <string name="speed_show_current">Hiện tốc độ hiện tại</string>
    <string name="speed_show_limit">Hiện giới hạn tốc độ</string>
    <string name="speed_gps_filter">Ngưỡng lọc tốc độ GPS</string>
    <string name="speed_gps_filter_off">Tắt lọc</string>
    <string name="speed_smoothing">Làm mượt tốc độ</string>
    <string name="speed_smooth_fast">Nhanh</string>
    <string name="speed_smooth_balanced">Cân bằng</string>
    <string name="speed_smooth_smooth">Mượt</string>
    <string name="speed_alerts">Cảnh báo vượt tốc</string>
    <string name="speed_threshold">Ngưỡng cảnh báo</string>
    <string name="speed_threshold_0">Đúng giới hạn (0)</string>
    <string name="speed_threshold_5">+5 km/h</string>
    <string name="speed_threshold_10">+10 km/h</string>
    <string name="speed_threshold_15">+15 km/h</string>
    <string name="speed_threshold_custom">Tuỳ chỉnh (%1$d km/h)</string>
    <string name="speed_alert_form">Hình thức cảnh báo</string>
    <string name="speed_alert_visual">Chỉ hình ảnh</string>
    <string name="speed_alert_audio">Âm thanh</string>
    <string name="speed_alert_both">Cả hai</string>
    <string name="speed_alert_volume">Âm lượng cảnh báo</string>
    <string name="speed_cooldown">Khoảng nghỉ giữa cảnh báo</string>
    <string name="speed_cooldown_5">5 giây</string>
    <string name="speed_cooldown_15">15 giây</string>
    <string name="speed_cooldown_30">30 giây</string>
    <string name="speed_cooldown_60">60 giây</string>
    <string name="speed_sustained">Chỉ cảnh báo khi vượt liên tục</string>
    <string name="speed_no_data">--</string>
    <string name="speed_limit_source">Nguồn giới hạn: %1$s</string>
    <string name="speed_limit_none">Không có dữ liệu giới hạn tốc độ cho đường này</string>
    <string name="speed_state">Trạng thái: %1$s</string>
    <string name="speed_overspeed_alert">Vượt quá %1$d %2$s</string>

    <!-- Voice Instant (§10-11). -->
    <string name="voice_listening">Đang nghe…</string>
    <string name="voice_processing">Đang xử lý…</string>
    <string name="voice_confirm">Xác nhận: %1$s?</string>
    <string name="voice_confirm_yes">Đồng ý</string>
    <string name="voice_confirm_no">Huỷ</string>
    <string name="voice_success">Đã thực hiện: %1$s</string>
    <string name="voice_error_unavailable">Voice service unavailable — thiết bị không có dịch vụ nhận dạng giọng nói</string>
    <string name="voice_error_no_match">Không nghe rõ. Thử lại hoặc nói ngắn hơn.</string>
    <string name="voice_error_timeout">Hết thời gian chờ</string>
    <string name="voice_error_permission">Cần quyền micro để dùng lệnh giọng nói</string>
    <string name="voice_error_unknown_command">Không hiểu lệnh: “%1$s”</string>
    <string name="voice_unavailable_short">Voice</string>
    <string name="voice_cancel">Huỷ</string>
    <string name="voice_cmd_map">Mở bản đồ</string>
    <string name="voice_cmd_navigate">Dẫn đường đến %1$s</string>
    <string name="voice_cmd_search">Tìm %1$s</string>
    <string name="voice_cmd_music_play">Phát nhạc</string>
    <string name="voice_cmd_music_stop">Dừng nhạc</string>
    <string name="voice_cmd_next">Bài tiếp theo</string>
    <string name="voice_cmd_previous">Bài trước</string>
    <string name="voice_cmd_phone">Mở điện thoại</string>
    <string name="voice_cmd_app">Mở ứng dụng %1$s</string>
    <string name="voice_cmd_volume_up">Tăng âm lượng</string>
    <string name="voice_cmd_volume_down">Giảm âm lượng</string>
    <string name="voice_cmd_home">Về trang chủ</string>
    <string name="voice_conflict_music">Không rõ bài nào — đang phát nhạc thay thế</string>

    <!-- Overlay chrome. Deliberately a small bar and not a full-screen sheet:
         §11 forbids covering the maneuver card, which sits top-left on the map
         at exactly the height a centred dialog would occupy. -->
    <string name="voice_mic">Lệnh giọng nói</string>
    <string name="voice_overlay_title">Voice Instant</string>
    <string name="voice_hint">Nói lệnh ngắn, ví dụ “mở bản đồ”</string>
    <string name="voice_close">Đóng</string>
    <string name="voice_open_settings">Mở cài đặt quyền</string>
    <string name="voice_retry">Nói lại</string>
    <string name="voice_command_label">Lệnh: %1$s</string>
    <string name="voice_executing">Đang thực hiện…</string>
    <string name="voice_confirming">Cần xác nhận</string>
    <string name="voice_state_error">Không thực hiện được</string>
    <string name="voice_state_success">Xong</string>

    <!-- Online music (§12-13). -->
    <string name="music_tab_device">Trên thiết bị</string>
    <string name="music_tab_online">Trực tuyến</string>
    <string name="music_online_radio">Internet Radio</string>
    <string name="music_online_podcast">Podcast</string>
    <string name="music_online_server">Máy chủ nhạc</string>
    <string name="music_online_buffering">Đang tải luồng…</string>
    <string name="music_online_unavailable">Nguồn trực tuyến không dùng được: %1$s</string>
    <string name="music_online_blocked">BLOCKED_EXTERNAL_CONFIG — chưa cấu hình endpoint/credential cho nguồn này</string>
    <string name="music_online_retry">Thử lại</string>
    <string name="music_online_offline">Không có kết nối — đang dùng nhạc trên thiết bị</string>
    <string name="music_online_settings_title">Nguồn nhạc trực tuyến</string>
    <string name="music_online_radio_hint">Dán URL luồng radio (http/https) để thêm vào danh sách</string>
    <string name="music_online_server_url">Địa chỉ máy chủ (Navidrome/Jellyfin)</string>
    <string name="music_online_server_user">Tên đăng nhập</string>
    <string name="music_online_server_pass">Mật khẩu / token</string>
    <string name="music_online_save">Lưu</string>
    <string name="music_online_health_ok">Kết nối tốt</string>
    <string name="music_online_health_fail">Không kết nối được: %1$s</string>
    <string name="music_online_no_stream">Nguồn này không cho phép phát</string>

    <!-- Parking mode (§18-19). -->
    <string name="parking_title">Đỗ xe</string>
    <string name="parking_enter">Vào chế độ đỗ xe</string>
    <string name="parking_exit">Thoát chế độ đỗ xe</string>
    <string name="parking_auto">Tự vào chế độ đỗ xe</string>
    <string name="parking_auto_hint">Chỉ tự vào khi tín hiệu xe và GPS đều xác nhận đã đỗ</string>
    <string name="parking_state">Trạng thái đỗ: %1$s</string>
    <string name="parking_reason">Lý do: %1$s</string>
    <string name="parking_return">Quay lại</string>

    <!-- App state machine / overlays (§16-17). -->
    <string name="overlay_import_progress">Đang nhập map pack…</string>
    <string name="error_dialog_ok">Đóng</string>
    <string name="error_dialog_retry">Thử lại</string>

    <string name="generic_retry">Thử lại</string>
    <string name="generic_back">Quay lại</string>
    <string name="generic_cancel">Huỷ</string>
    <string name="generic_ok">Đồng ý</string>
</resources>

END FILE

## app/src/main/res/values/themes.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<resources>

    <style name="Theme.Ts8Launcher" parent="Theme.AppCompat.NoActionBar">
        <item name="colorPrimary">@color/accent</item>
        <item name="colorPrimaryDark">@color/bg_root</item>
        <item name="colorAccent">@color/accent</item>

        <item name="android:windowBackground">@color/bg_root</item>
        <item name="android:colorBackground">@color/bg_root</item>
        <item name="android:textColorPrimary">@color/text_primary</item>
        <item name="android:textColorSecondary">@color/text_secondary</item>

        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
        <item name="android:windowDrawsSystemBarBackgrounds">true</item>

        <item name="android:windowActionModeOverlay">true</item>
        <item name="android:windowNoTitle">true</item>
    </style>

    <!-- Applied by Activities rendered over the map: no system bars at all,
         the launcher draws its own status row as the design does. -->
    <style name="Theme.Ts8Launcher.Fullscreen">
        <item name="android:windowFullscreen">true</item>
        <item name="android:windowContentOverlay">@null</item>
    </style>

    <style name="Text.Title" parent="">
        <item name="android:textSize">@dimen/text_title</item>
        <item name="android:textColor">@color/text_primary</item>
        <item name="android:fontFamily">sans-serif-medium</item>
    </style>

    <style name="Text.Body" parent="">
        <item name="android:textSize">@dimen/text_body</item>
        <item name="android:textColor">@color/text_secondary</item>
    </style>

    <style name="Text.Caption" parent="">
        <item name="android:textSize">@dimen/text_caption</item>
        <item name="android:textColor">@color/text_tertiary</item>
    </style>

    <style name="Card.Surface" parent="">
        <item name="android:background">@drawable/bg_card</item>
        <item name="android:padding">@dimen/card_pad</item>
    </style>

    <style name="Button.Primary" parent="Widget.AppCompat.Button.Borderless">
        <item name="android:background">@drawable/bg_button_primary</item>
        <item name="android:textColor">@color/text_on_accent</item>
        <item name="android:textAllCaps">false</item>
        <item name="android:minHeight">48dp</item>
        <item name="android:paddingStart">20dp</item>
        <item name="android:paddingEnd">20dp</item>
        <item name="android:textSize">@dimen/text_body</item>
    </style>

    <style name="Button.Ghost" parent="Widget.AppCompat.Button.Borderless">
        <item name="android:background">@drawable/bg_button_ghost</item>
        <item name="android:textColor">@color/text_primary</item>
        <item name="android:textAllCaps">false</item>
        <item name="android:minHeight">48dp</item>
        <item name="android:paddingStart">20dp</item>
        <item name="android:paddingEnd">20dp</item>
        <item name="android:textSize">@dimen/text_body</item>
    </style>

    <!-- ==================================================================
         Bottom navigation items.

         One item is icon-over-label, weight 1, with a 48dp minimum touch
         target and the selected state expressed as a tint change plus the
         accent label. Selected state is applied in code with
         `isSelected = true`, which the drawable selector and the colour
         selector both read.
         ================================================================== -->
    <style name="BottomNavItem">
        <item name="android:layout_width">0dp</item>
        <item name="android:layout_height">match_parent</item>
        <item name="android:layout_weight">1</item>
        <item name="android:orientation">vertical</item>
        <item name="android:gravity">center</item>
        <item name="android:clickable">true</item>
        <item name="android:focusable">true</item>
        <item name="android:background">?attr/selectableItemBackground</item>
    </style>

    <style name="BottomNavIcon">
        <item name="android:layout_width">@dimen/bottom_nav_icon</item>
        <item name="android:layout_height">@dimen/bottom_nav_icon</item>
        <!-- `tint` (no `android:` prefix) is AppCompat's own attribute: the
             platform attribute only exists from API 21 for some widgets and
             cannot be changed by a selector on a plain `ImageView` before
             AppCompat's view inflater rewrites it. The selectable state is what
             the bar relies on, so this has to be the AppCompat one. -->
        <item name="tint">@color/nav_item_tint</item>
    </style>

    <style name="BottomNavLabel">
        <item name="android:layout_width">wrap_content</item>
        <item name="android:layout_height">wrap_content</item>
        <item name="android:layout_marginTop">2dp</item>
        <item name="android:textSize">@dimen/bottom_nav_label</item>
        <item name="android:textColor">@color/nav_item_tint</item>
        <item name="android:singleLine">true</item>
    </style>

    <!-- ==================================================================
         Quick settings tiles.

         A tile is icon-over-value-over-label, weight 1, in a row that is
         exactly as wide as the panel. The label is the thing being toggled
         ("Wi-Fi"); the value line is its state ("Bật" / "Tắt") and is filled
         from the platform, never from an assumption.
         ================================================================== -->
    <style name="QsTile">
        <item name="android:layout_width">0dp</item>
        <item name="android:layout_height">@dimen/qs_tile_height</item>
        <item name="android:layout_weight">1</item>
        <item name="android:orientation">horizontal</item>
        <item name="android:gravity">center_vertical</item>
        <item name="android:paddingStart">@dimen/gap</item>
        <item name="android:paddingEnd">@dimen/gap</item>
        <item name="android:background">@drawable/bg_card_raised</item>
        <item name="android:clickable">true</item>
        <item name="android:focusable">true</item>
    </style>

    <style name="QsTileIcon">
        <item name="android:layout_width">26dp</item>
        <item name="android:layout_height">26dp</item>
        <item name="tint">@color/text_secondary</item>
    </style>

</resources>

END FILE

## app/src/main/res/xml/usb_device_filter.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Mass-storage devices only: the receiver is informational, the real
         media discovery path is MediaStore + StorageManager volumes. -->
    <usb-device class="8" subclass="6" />
</resources>

END FILE

## app/src/test/java/com/ts8/launcher/runtime/AppStateMachineTest.kt

BEGIN FILE
package com.ts8.launcher.runtime

import com.ts8.core.navigation.NavigationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §16–17's state machine, exercised as a pure state table.
 *
 * **Why this is a plain JVM test.** [AppStateMachine] holds no engine, no
 * `Context` and no Looper — that was the design goal, and this file is where
 * the goal is checked. Every rule the brief states about transitions, Back and
 * process recreation is a rule about two enums and a stack, so it can be proven
 * here in milliseconds instead of on a head unit.
 *
 * **What is deliberately not tested.** Nothing about which Activity is on
 * screen: the machine does not know, and a test that asserted it would be
 * asserting the wiring in `BaseScreenActivity` twice over.
 */
class AppStateMachineTest {

    /** A [StateStore] that keeps values in a map, like a preference file. */
    private class FakeStore(
        private var value: String? = null,
        private val failOnPut: Boolean = false
    ) : StateStore {
        var writes = 0
            private set

        override fun get(key: String): String? = value

        override fun put(key: String, value: String) {
            if (failOnPut) throw IllegalStateException("disk full")
            this.value = value
            writes++
        }

        fun raw(): String? = value
    }

    private fun machine(
        store: StateStore = FakeStore(),
        navigationRunning: Boolean = false
    ) = AppStateMachine(store) { navigationRunning }

    // -- the ten states and legal moves ------------------------------------

    @Test
    fun `starts on HOME with no overlay`() {
        val m = machine()
        assertEquals(AppStateMachine.State.HOME, m.state())
        assertNull(m.topOverlay())
        assertFalse(m.isOverlayShowing())
    }

    @Test
    fun `every primary state is reachable from somewhere`() {
        // A state with no legal source is unreachable, which would be a typo in
        // `allowed` rather than a design decision — and an unreachable state in
        // a spec list is a spec item that was never implemented.
        val reachable = HashMap<AppStateMachine.State, AppStateMachine.State>()
        val all = AppStateMachine.State.values()
        for (target in all) {
            for (source in all) {
                if (source == target) continue
                val probe = machine()
                probe.moveTo(source)
                if (probe.state() != source) continue
                if (probe.moveTo(target)) reachable[target] = source
            }
        }
        AppStateMachine.State.values().forEach { state ->
            assertTrue("$state is unreachable from any other state", reachable.containsKey(state))
        }
    }

    @Test
    fun `bottom tab moves are accepted in both directions`() {
        val m = machine()
        val order = listOf(
            AppStateMachine.State.MAP_BROWSE,
            AppStateMachine.State.MUSIC_EXPANDED,
            AppStateMachine.State.APP_DRAWER,
            AppStateMachine.State.QUICK_SETTINGS,
            AppStateMachine.State.HOME,
            AppStateMachine.State.MUSIC_EXPANDED,
            AppStateMachine.State.HOME
        )
        order.forEach { target ->
            assertTrue("expected $target to be accepted", m.moveTo(target))
            assertEquals(target, m.state())
        }
    }

    @Test
    fun `parking is reachable only from Home and the map`() {
        val fromMusic = machine()
        fromMusic.moveTo(AppStateMachine.State.MUSIC_EXPANDED)
        assertFalse(fromMusic.moveTo(AppStateMachine.State.PARKING))
        assertEquals(AppStateMachine.State.MUSIC_EXPANDED, fromMusic.state())

        val fromHome = machine()
        assertTrue(fromHome.moveTo(AppStateMachine.State.PARKING))
    }

    @Test
    fun `illegal move leaves the state alone and returns false`() {
        val m = machine()
        m.moveTo(AppStateMachine.State.SEARCH)
        // SEARCH may not go straight to ROUTE_PREVIEW: a route without a
        // destination is exactly the inconsistency the machine exists to stop.
        assertFalse(m.moveTo(AppStateMachine.State.ROUTE_PREVIEW))
        assertEquals(AppStateMachine.State.SEARCH, m.state())
    }

    @Test
    fun `moving to the current state is accepted and writes nothing`() {
        val store = FakeStore()
        val m = machine(store)
        assertTrue(m.moveTo(AppStateMachine.State.HOME))
        assertEquals(0, store.writes)
    }

    // -- navigation mirroring ----------------------------------------------

    @Test
    fun `navigator RUNNING states map to NAVIGATING`() {
        listOf(
            NavigationState.NAVIGATING,
            NavigationState.REROUTING,
            NavigationState.ARRIVED
        ).forEach { navState ->
            val m = machine()
            m.moveTo(AppStateMachine.State.MAP_BROWSE)
            m.syncNavigation(navState)
            assertEquals(navState.name, AppStateMachine.State.NAVIGATING, m.state())
        }
    }

    @Test
    fun `ending a route falls back to the map, not to whatever screen is open`() {
        val m = machine()
        m.moveTo(AppStateMachine.State.MAP_BROWSE)
        m.syncNavigation(NavigationState.NAVIGATING)
        m.syncNavigation(NavigationState.IDLE)
        assertEquals(AppStateMachine.State.MAP_BROWSE, m.state())
    }

    @Test
    fun `ending a route does not yank the driver out of the music screen`() {
        // §16's "Khi mở Music, player mở rộng nhưng route/navigation tiếp tục
        // nền" implies the converse too: a route ending in the background must
        // not close the screen the driver is actually looking at.
        val m = machine()
        m.moveTo(AppStateMachine.State.MAP_BROWSE)
        m.syncNavigation(NavigationState.NAVIGATING)
        m.moveTo(AppStateMachine.State.MUSIC_EXPANDED)
        m.syncNavigation(NavigationState.IDLE)
        assertEquals(AppStateMachine.State.MUSIC_EXPANDED, m.state())
    }

    @Test
    fun `guidance starting from Home leaves the driver on Home`() {
        // A voice command can start a route from Home. Moving to NAVIGATING
        // there is legal but wrong: the driver asked to stay on Home, and §16
        // says that Home must then be navigation-aware, not replaced.
        val m = machine()
        assertEquals(AppStateMachine.State.HOME, m.state())
        m.syncNavigation(NavigationState.NAVIGATING)
        assertTrue(
            "Home must stay a legal state while navigating",
            m.moveTo(AppStateMachine.State.HOME)
        )
    }

    // -- overlays ----------------------------------------------------------

    @Test
    fun `overlays stack and Back closes the top one`() {
        val m = machine()
        assertTrue(m.pushOverlay(AppStateMachine.Overlay.VOICE_LISTENING))
        assertTrue(m.pushOverlay(AppStateMachine.Overlay.ERROR_DIALOG))
        assertEquals(AppStateMachine.Overlay.ERROR_DIALOG, m.topOverlay())

        assertTrue(m.popTopOverlay())
        assertEquals(AppStateMachine.Overlay.VOICE_LISTENING, m.topOverlay())
        assertTrue(m.popTopOverlay())
        assertNull(m.topOverlay())
        assertFalse(m.popTopOverlay())
    }

    @Test
    fun `re-pushing the same overlay does not stack it twice`() {
        // The voice bar re-renders on every state change; without this a
        // single Back would have to close an unbounded number of entries.
        val m = machine()
        assertTrue(m.pushOverlay(AppStateMachine.Overlay.VOICE_LISTENING))
        assertFalse(m.pushOverlay(AppStateMachine.Overlay.VOICE_LISTENING))
        assertEquals(1, m.overlays().size)
        assertTrue(m.popTopOverlay())
        assertNull(m.topOverlay())
    }

    @Test
    fun `popping a lower overlay leaves the top one in place`() {
        val m = machine()
        m.pushOverlay(AppStateMachine.Overlay.VOICE_LISTENING)
        m.pushOverlay(AppStateMachine.Overlay.MAP_IMPORT_PROGRESS)
        // An import finishing must not close a voice bar raised while it ran.
        assertTrue(m.popOverlay(AppStateMachine.Overlay.VOICE_LISTENING))
        assertEquals(AppStateMachine.Overlay.MAP_IMPORT_PROGRESS, m.topOverlay())
        assertFalse(m.popOverlay(AppStateMachine.Overlay.VOICE_LISTENING))
    }

    @Test
    fun `overlays survive a primary state change`() {
        val m = machine()
        m.pushOverlay(AppStateMachine.Overlay.VOICE_CONFIRMATION)
        m.moveTo(AppStateMachine.State.MAP_BROWSE)
        assertTrue(m.isOverlayShowing())
        assertEquals(AppStateMachine.Overlay.VOICE_CONFIRMATION, m.topOverlay())
    }

    @Test
    fun `listeners hear state and overlay changes`() {
        val m = machine()
        val seen = ArrayList<String>()
        m.addListener { previous, current, overlays ->
            seen.add("$previous->$current:${overlays.joinToString(",")}")
        }
        m.moveTo(AppStateMachine.State.MAP_BROWSE)
        m.pushOverlay(AppStateMachine.Overlay.ERROR_DIALOG)
        m.popTopOverlay()

        assertEquals(3, seen.size)
        assertTrue(seen[0].startsWith("HOME->MAP_BROWSE"))
        assertTrue(seen[1].endsWith(":ERROR_DIALOG"))
        assertTrue(seen[2].endsWith(":"))
    }

    @Test
    fun `a throwing listener does not stop the others`() {
        val m = machine()
        var heard = 0
        m.addListener { _, _, _ -> throw IllegalStateException("boom") }
        m.addListener { _, _, _ -> heard++ }
        m.moveTo(AppStateMachine.State.MAP_BROWSE)
        assertEquals(1, heard)
    }

    // -- persistence -------------------------------------------------------

    @Test
    fun `each accepted move is written synchronously`() {
        val store = FakeStore()
        val m = machine(store)
        m.moveTo(AppStateMachine.State.MUSIC_EXPANDED)
        assertEquals(AppStateMachine.State.MUSIC_EXPANDED.name, store.raw())
    }

    @Test
    fun `a refused move writes nothing`() {
        val store = FakeStore()
        val m = machine(store)
        m.moveTo(AppStateMachine.State.SEARCH)
        val afterSearch = store.writes
        m.moveTo(AppStateMachine.State.ROUTE_PREVIEW)
        assertEquals(afterSearch, store.writes)
    }

    @Test
    fun `a store that fails to write does not crash the move`() {
        // A head unit losing power mid-write is the normal case, not the
        // exceptional one; the screen change must still happen.
        val m = machine(FakeStore(failOnPut = true))
        assertTrue(m.moveTo(AppStateMachine.State.MAP_BROWSE))
        assertEquals(AppStateMachine.State.MAP_BROWSE, m.state())
    }

    @Test
    fun `restore degrades transient states to the map`() {
        listOf(
            AppStateMachine.State.SEARCH,
            AppStateMachine.State.DESTINATION_PREVIEW,
            AppStateMachine.State.ROUTE_PREVIEW
        ).forEach { stored ->
            val m = machine(FakeStore(stored.name))
            assertEquals(
                "stored $stored should come back as the map",
                AppStateMachine.State.MAP_BROWSE,
                m.restoreAfterProcessDeath()
            )
        }
    }

    @Test
    fun `restore keeps NAVIGATING only when guidance is really running`() {
        val running = machine(FakeStore(AppStateMachine.State.NAVIGATING.name), navigationRunning = true)
        assertEquals(AppStateMachine.State.NAVIGATING, running.restoreAfterProcessDeath())

        val dead = machine(FakeStore(AppStateMachine.State.NAVIGATING.name), navigationRunning = false)
        assertEquals(AppStateMachine.State.MAP_BROWSE, dead.restoreAfterProcessDeath())
    }

    @Test
    fun `restore keeps the durable tabs as they were`() {
        listOf(
            AppStateMachine.State.HOME,
            AppStateMachine.State.MAP_BROWSE,
            AppStateMachine.State.MUSIC_EXPANDED,
            AppStateMachine.State.APP_DRAWER,
            AppStateMachine.State.QUICK_SETTINGS,
            AppStateMachine.State.PARKING
        ).forEach { stored ->
            val m = machine(FakeStore(stored.name))
            assertEquals(stored, m.restoreAfterProcessDeath())
        }
    }

    @Test
    fun `restore runs once, so a later screen cannot rewind the state`() {
        val store = FakeStore(AppStateMachine.State.MUSIC_EXPANDED.name)
        val m = machine(store)
        assertEquals(AppStateMachine.State.MUSIC_EXPANDED, m.restoreAfterProcessDeath())

        m.moveTo(AppStateMachine.State.HOME)
        // A second screen's `onCreate` calls restore again; the driver is on
        // Home now and must stay there.
        assertEquals(AppStateMachine.State.HOME, m.restoreAfterProcessDeath())
        assertEquals(AppStateMachine.State.HOME, m.state())
    }

    @Test
    fun `an unreadable stored value leaves the machine on HOME`() {
        // A preference written by a newer build, or half-written after a power
        // cut, must not brick the launcher.
        val m = machine(FakeStore("NOT_A_STATE"))
        assertEquals(AppStateMachine.State.HOME, m.restoreAfterProcessDeath())
    }

    @Test
    fun `an empty store leaves the machine on HOME`() {
        val m = machine(FakeStore(null))
        assertEquals(AppStateMachine.State.HOME, m.restoreAfterProcessDeath())
    }

    @Test
    fun `restore never restores an overlay`() {
        // An import has no archive handle after a kill and an error dialog's
        // cause is gone; restoring either would be a spinner that never stops.
        // The store is all that survives a process death, so the honest test is
        // the new process: same store, fresh machine, no overlays.
        val store = FakeStore(AppStateMachine.State.MAP_BROWSE.name)
        val dying = machine(store)
        dying.pushOverlay(AppStateMachine.Overlay.MAP_IMPORT_PROGRESS)
        dying.pushOverlay(AppStateMachine.Overlay.ERROR_DIALOG)
        assertTrue(dying.isOverlayShowing())

        val reborn = machine(store)
        reborn.restoreAfterProcessDeath()
        assertEquals(AppStateMachine.State.MAP_BROWSE, reborn.state())
        assertFalse(reborn.isOverlayShowing())
        assertNull(reborn.topOverlay())
    }
}

END FILE

## app/src/test/java/com/ts8/launcher/runtime/OnlineTrackIdTest.kt

BEGIN FILE
package com.ts8.launcher.runtime

import com.ts8.core.music.local.TrackIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The online id space, tested as arithmetic.
 *
 * **Why this deserves its own file.** §13 requires that a queue hold local and
 * online items together without duplicating playback state, and the queue is
 * keyed by `Track.id` — a `Long` that the local engine owns. If an online id
 * can collide with a MediaStore row id or with a USB path hash, a driver taps a
 * radio station and the player highlights a file on the stick. That failure is
 * silent, rare, and impossible to reproduce on demand, so the only defence is
 * to prove the ranges cannot overlap.
 *
 * [OnlineMusicRuntime.onlineTrackId] is a pure function on two strings; it is
 * reachable here without a `Context` because it lives on the companion.
 */
class OnlineTrackIdTest {

    @Test
    fun `an online id never looks like a MediaStore row id`() {
        // MediaStore hands out small sequential ids; anything above 2^61 cannot
        // be one. That is the whole point of the bit.
        val ids = listOf(
            OnlineMusicRuntime.onlineTrackId("radio-browser", "station-1"),
            OnlineMusicRuntime.onlineTrackId("podcast", "episode-1"),
            OnlineMusicRuntime.onlineTrackId("subsonic", "song-1")
        )
        ids.forEach { id ->
            assertTrue("$id must be >= 2^61", id >= (1L shl 61))
        }
    }

    @Test
    fun `an online id never looks like a USB path hash`() {
        // Bit 62 is `TrackIds.forUsbPath`'s marker. Reusing it would let the two
        // hashed spaces collide, and `TrackIds.isUsb` would then claim a radio
        // station is a file on a stick.
        val online = OnlineMusicRuntime.onlineTrackId("radio-browser", "/mnt/usb/a.mp3")
        val usb = TrackIds.forUsbPath("/mnt/usb/a.mp3")

        assertNotEquals(online, usb)
        assertFalse("a USB id must claim only itself", TrackIds.isUsb(online))
        assertTrue(TrackIds.isUsb(usb))
    }

    @Test
    fun `the same station gets the same id across searches`() {
        // Determinism is what lets the queue highlight the row the driver is
        // playing when the search is re-run.
        assertEquals(
            OnlineMusicRuntime.onlineTrackId("radio-browser", "abc"),
            OnlineMusicRuntime.onlineTrackId("radio-browser", "abc")
        )
    }

    @Test
    fun `two providers issuing the same track id stay apart`() {
        // Subsonic song ids and radio station UUIDs are both opaque strings and
        // can coincide; the provider id is part of the hash so they cannot.
        assertNotEquals(
            OnlineMusicRuntime.onlineTrackId("subsonic", "abc"),
            OnlineMusicRuntime.onlineTrackId("radio-browser", "abc")
        )
    }

    @Test
    fun `a separator keeps concatenated pairs distinct`() {
        // Without the NUL, "ab"+"c" and "a"+"bc" would hash identically — a real
        // collision for a provider that issues ids like that.
        assertNotEquals(
            OnlineMusicRuntime.onlineTrackId("ab", "c"),
            OnlineMusicRuntime.onlineTrackId("a", "bc")
        )
    }

    @Test
    fun `the id is always positive`() {
        // A negative id would sort below every library row and, more
        // importantly, would mean bit 63 survived the mask.
        listOf("", "a", "zzzzzzzzzzzzzzzzzzzz", "nguyễn văn linh").forEach { value ->
            assertTrue(
                "id for \"$value\" must be positive",
                OnlineMusicRuntime.onlineTrackId("radio-browser", value) > 0L
            )
        }
    }
}

END FILE

## build.gradle.kts

BEGIN FILE
// Plugin versions come from buildSrc/build.gradle.kts, which puts AGP and the
// Kotlin plugin on the classpath. Declaring a version again here fails the
// build with "plugin is already on the classpath with an unknown version".
plugins {
    id("com.android.application") apply false
    id("com.android.library") apply false
    id("org.jetbrains.kotlin.android") apply false
}

END FILE

## buildSrc/build.gradle.kts

BEGIN FILE
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation("com.android.tools.build:gradle:8.7.3")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.25")
}

END FILE

## buildSrc/src/main/kotlin/CoreModulePlugin.kt

BEGIN FILE
import com.android.build.gradle.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * Shared build configuration for every `core:*` module.
 *
 * Keeping this in `buildSrc` rather than a loop over `subprojects` means each
 * module's build file stays a one-liner and IDE sync sees identical values.
 */
class CoreModulePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.apply("com.android.library")
        project.plugins.apply("org.jetbrains.kotlin.android")

        val android = project.extensions.getByType(LibraryExtension::class.java)
        android.compileSdk = 34
        android.defaultConfig.minSdk = 23
        android.compileOptions.apply {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
            isCoreLibraryDesugaringEnabled = true
        }
        android.lint.abortOnError = false

        project.extensions.getByType(KotlinAndroidProjectExtension::class.java)
            .compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)

        project.dependencies.add("implementation", "androidx.annotation:annotation:1.7.1")
        project.dependencies.add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
        project.dependencies.add("coreLibraryDesugaring", "com.android.tools:desugar_jdk_libs:2.0.4")

        project.dependencies.add("testImplementation", "junit:junit:4.13.2")
        project.dependencies.add("testImplementation", "org.robolectric:robolectric:4.11.1")
        project.dependencies.add("testImplementation", "androidx.test:core:1.5.0")
    }
}

END FILE

## buildSrc/src/main/resources/META-INF/gradle-plugins/ts8.core-module.properties

BEGIN FILE
implementation-class=CoreModulePlugin

END FILE

## core/diagnostics/build.gradle.kts

BEGIN FILE
plugins {
    id("ts8.core-module")
}

android {
    namespace = "com.ts8.core.diagnostics"
}

END FILE

## core/diagnostics/src/main/AndroidManifest.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<manifest />

END FILE

## core/location-engine/build.gradle.kts

BEGIN FILE
plugins {
    id("ts8.core-module")
}

android {
    namespace = "com.ts8.core.location"
}

dependencies {
    api(project(":core:map-api"))
}

END FILE

## core/location-engine/src/main/AndroidManifest.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<manifest />

END FILE

## core/location-engine/src/main/java/com/ts8/core/location/AndroidLocationSource.kt

BEGIN FILE
package com.ts8.core.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GpsStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresPermission

/**
 * Android 6 boundary for [DefaultLocationEngine].
 *
 * Uses `LocationManager` directly rather than Play Services: the head unit has
 * no Google Play, so `FusedLocationProvider` is not an option and the platform
 * provider is the only one that exists.
 *
 * The pipeline itself is platform-free; this class only translates
 * [Location] into [RawLocation], on a background thread so a slow filter never
 * blocks the main thread's map rendering.
 */
class AndroidLocationSource(
    context: Context,
    private val engine: LocationEngine,
    private val onFix: (LocationFix) -> Unit
) {
    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var listening = false
    private var gpsStatusListener: GpsStatus.Listener? = null

    /** Last known satellite count, for the diagnostics screen. */
    @Volatile
    var satelliteCount: Int = 0
        private set

    /** True when the platform reports a GPS fix lock. */
    @Volatile
    var hasGpsFix: Boolean = false
        private set

    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val raw = toRaw(location)
            val fix = engine.onRawLocation(raw)
            if (fix != null) onFix(fix)
        }

        // Deprecated on newer platforms but still the only callback on API 23.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            if (provider == LocationManager.GPS_PROVIDER) {
                hasGpsFix = status == android.location.LocationProvider.AVAILABLE
            }
        }

        override fun onProviderEnabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) hasGpsFix = false
        }

        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) hasGpsFix = false
        }
    }

    fun hasPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Starts listening. Silently does nothing without permission — the caller
     * is expected to have checked [hasPermission] first and to show the
     * permission prompt; a hard failure here would crash the launcher.
     */
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun start() {
        if (listening || !hasPermission()) return

        val t = HandlerThread("ts8-location").also { it.start() }
        thread = t
        handler = Handler(t.looper)

        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        if (!gpsEnabled) {
            // Network provider is a poor substitute but keeps the map from
            // sitting at the last known point forever where it is available.
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                requestProvider(LocationManager.NETWORK_PROVIDER)
                listening = true
            }
            return
        }

        requestProvider(LocationManager.GPS_PROVIDER)

        // Already have a recent fix? Feed it immediately so the map does not
        // wait ~30 s for the first satellite lock.
        lastKnown()?.let { location ->
            if (SystemClock.elapsedRealtime() - location.elapsedRealtimeNanos / 1_000_000 <
                MAX_FRESH_LAST_KNOWN_MS
            ) {
                engine.onRawLocation(toRaw(location))?.let(onFix)
            }
        }

        @Suppress("DEPRECATION")
        gpsStatusListener = GpsStatus.Listener { event ->
            if (event == GpsStatus.GPS_EVENT_SATELLITE_STATUS) {
                @Suppress("DEPRECATION")
                val status = locationManager.getGpsStatus(null)
                satelliteCount = status?.satellites?.count { it.usedInFix() } ?: 0
            }
        }.also { locationManager.addGpsStatusListener(it) }

        listening = true
    }

    @RequiresPermission(anyOf = [
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ])
    private fun requestProvider(provider: String) {
        val handler = this.handler ?: return
        runCatching {
            locationManager.requestLocationUpdates(
                provider,
                MIN_INTERVAL_MS,
                MIN_DISTANCE_M,
                listener,
                handler.looper
            )
        }
    }

    private fun lastKnown(): Location? {
        if (!hasPermission()) return null
        return runCatching {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()
    }

    fun stop() {
        if (!listening) return
        runCatching { locationManager.removeUpdates(listener) }
        gpsStatusListener?.let { runCatching { locationManager.removeGpsStatusListener(it) } }
        gpsStatusListener = null
        thread?.quitSafely()
        thread = null
        handler = null
        listening = false
        hasGpsFix = false
    }

    private fun toRaw(location: Location): RawLocation = RawLocation(
        point = com.ts8.core.map.GeoPoint(location.latitude, location.longitude),
        accuracyMeters = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE,
        elapsedRealtimeMs = location.elapsedRealtimeNanos / 1_000_000,
        provider = location.provider ?: "unknown",
        speedMps = if (location.hasSpeed()) location.speed else -1f,
        bearingDeg = if (location.hasBearing()) location.bearing else -1f,
        altitudeMeters = if (location.hasAltitude()) location.altitude else null
    )

    companion object {
        /**
         * 1 Hz. A car at 120 km/h covers 33 m per second, so anything slower
         * makes the marker visibly step; anything faster burns battery and CPU
         * the head unit needs for map rendering.
         */
        private const val MIN_INTERVAL_MS = 1_000L
        private const val MIN_DISTANCE_M = 0f
        private const val MAX_FRESH_LAST_KNOWN_MS = 60_000L
    }
}

END FILE

## core/location-engine/src/main/java/com/ts8/core/location/DefaultLocationEngine.kt

BEGIN FILE
package com.ts8.core.location

import com.ts8.core.map.GeoPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The GPS pipeline described on [LocationEngine].
 *
 * Deliberately has no Android dependency: the platform hands it raw fixes, and
 * every decision it makes is testable on the JVM with synthetic tracks. The
 * Android side lives in [AndroidLocationSource].
 */
class DefaultLocationEngine(
    private val config: Config = Config()
) : LocationEngine {

    data class Config(
        /** Reject fixes worse than this accuracy. */
        val maxAccuracyMeters: Float = RawLocation.MAX_ACCURACY_METERS,
        /**
         * A fix implying a speed above this is a spike. 90 m/s is 324 km/h —
         * beyond any road in the pack, so it can only be noise or a teleport.
         */
        val maxPlausibleSpeedMps: Float = 90f,
        /** Position EMA weight for a new sample. Lower is smoother and laggier. */
        val positionAlpha: Float = 0.35f,
        /** Speed EMA weight. Speed is noisier than position, so it moves slower. */
        val speedAlpha: Float = 0.25f,
        /** Below this speed the reported course is meaningless; hold the last one. */
        val minBearingSpeedMps: Float = 1.2f,
        /** Max degrees the reported course may jump between fixes. */
        val maxBearingChangeDeg: Float = 45f,
        /** A fix older than this after the previous one is out of order. */
        val maxSampleGapMs: Long = 30_000L,
        /** Snap is refused beyond this distance from the route line. */
        val maxSnapDistanceMeters: Float = 50f,
        /**
         * Beyond this distance from the route, snapping is pointless and the
         * navigator should be told the driver has left it — but the engine
         * still reports the raw position so the UI shows where they are.
         */
        val offRouteDistanceMeters: Float = 60f,
        /** Two consecutive off-route fixes are needed before the flag sticks. */
        val offRouteConfirmSamples: Int = 3
    )

    private val cfg = config

    // -- smoothing state ---------------------------------------------------
    private var smoothedPoint: GeoPoint? = null
    private var smoothedSpeed = 0f
    private var filteredBearing: Float? = null
    private var lastAcceptedMs = 0L
    private var lastAccepted: LocationFix? = null

    // -- route state -------------------------------------------------------
    private var routePoints: List<GeoPoint> = emptyList()
    private var cumulative: FloatArray = FloatArray(0)
    private var routeLengthMeters = 0f
    private var lastProjection: Projection? = null
    private var offRouteStreak = 0

    // -- stats -------------------------------------------------------------
    private var received = 0L
    private var accepted = 0L
    private var rejectedInvalid = 0L
    private var rejectedSpike = 0L
    private var rejectedStale = 0L
    private var snappedCount = 0L
    private var snapRefused = 0L

    override fun hasFix(): Boolean = lastAccepted != null

    override fun lastFix(): LocationFix? = lastAccepted

    override fun stats(): LocationStats = LocationStats(
        received = received,
        accepted = accepted,
        rejectedInvalid = rejectedInvalid,
        rejectedSpike = rejectedSpike,
        rejectedStale = rejectedStale,
        snapped = snappedCount,
        snapRefused = snapRefused
    )

    override fun reset() {
        smoothedPoint = null
        smoothedSpeed = 0f
        filteredBearing = null
        lastAcceptedMs = 0L
        lastAccepted = null
        lastProjection = null
        offRouteStreak = 0
    }

    override fun setRoute(points: List<GeoPoint>?) {
        routePoints = points.orEmpty()
        routeLengthMeters = 0f
        if (routePoints.size < 2) {
            cumulative = FloatArray(0)
            lastProjection = null
            offRouteStreak = 0
            return
        }
        val cum = FloatArray(routePoints.size)
        var acc = 0f
        for (i in 1 until routePoints.size) {
            acc += distanceMeters(routePoints[i - 1], routePoints[i]).toFloat()
            cum[i] = acc
        }
        cumulative = cum
        routeLengthMeters = acc
        // The previous projection belongs to the previous route.
        lastProjection = null
        offRouteStreak = 0
    }

    override fun onRawLocation(raw: RawLocation): LocationFix? {
        received++

        // --- 1. validate --------------------------------------------------
        if (!raw.isUsable() || raw.accuracyMeters > cfg.maxAccuracyMeters) {
            rejectedInvalid++
            return null
        }
        // A fix timestamped before the last accepted one is out of order. GPS
        // providers do deliver these around a tunnel exit.
        if (lastAcceptedMs != 0L && raw.elapsedRealtimeMs < lastAcceptedMs) {
            rejectedStale++
            return null
        }

        // --- 2. spike rejection -------------------------------------------
        val previousPoint = smoothedPoint
        if (previousPoint != null && lastAcceptedMs != 0L) {
            val dtSec = ((raw.elapsedRealtimeMs - lastAcceptedMs).coerceAtLeast(0L)) / 1000.0
            if (dtSec > 0.0) {
                val moved = distanceMeters(previousPoint, raw.point)
                val impliedSpeed = (moved / dtSec).toFloat()

                // Allow the reported accuracy: two 30 m fixes can be 60 m apart
                // while standing still.
                val slack = raw.accuracyMeters + (lastAccepted?.accuracyMeters ?: 0f)
                val suspicious = impliedSpeed > cfg.maxPlausibleSpeedMps && moved > slack

                if (suspicious) {
                    rejectedSpike++
                    // Hold the last accepted fix rather than emitting a bogus
                    // one. Navigation state depends on this: a single spike
                    // must never look like "driver left the route".
                    return lastAccepted
                }
            }
        }

        // --- 3. smoothing -------------------------------------------------
        val smoothed = if (previousPoint == null) {
            raw.point
        } else {
            // A sample that has barely moved should pull the estimate less:
            // standing still should converge to a still point quickly.
            val alpha = cfg.positionAlpha
            GeoPoint(
                previousPoint.lat + (raw.point.lat - previousPoint.lat) * alpha,
                previousPoint.lon + (raw.point.lon - previousPoint.lon) * alpha
            )
        }

        val measuredSpeed = if (raw.speedMps >= 0f) {
            raw.speedMps
        } else if (previousPoint != null && lastAcceptedMs != 0L) {
            val dtSec = ((raw.elapsedRealtimeMs - lastAcceptedMs).coerceAtLeast(1L)) / 1000.0
            (distanceMeters(previousPoint, raw.point) / dtSec).toFloat()
        } else {
            0f
        }
        smoothedSpeed = if (smoothedPoint == null) {
            measuredSpeed.coerceAtLeast(0f)
        } else {
            smoothedSpeed + (measuredSpeed - smoothedSpeed) * cfg.speedAlpha
        }.coerceAtLeast(0f)

        // --- 4. bearing filter --------------------------------------------
        val candidateBearing = when {
            raw.bearingDeg >= 0f && raw.bearingDeg < 360f -> raw.bearingDeg
            smoothedPoint != null && smoothedSpeed > cfg.minBearingSpeedMps ->
                bearingBetween(smoothedPoint!!, smoothed)
            else -> null
        }
        filteredBearing = filterBearing(filteredBearing, candidateBearing)

        // --- 5. snap to route ---------------------------------------------
        var outputPoint = smoothed
        var distanceFromRoute: Float? = null
        var distanceAlong: Float? = null
        var didSnap = false

        if (routePoints.size >= 2) {
            val projection = projectOntoRoute(smoothed)
            if (projection != null) {
                distanceFromRoute = projection.distanceMeters
                val withinSnap = projection.distanceMeters <= cfg.maxSnapDistanceMeters
                val plausibleForward = isForwardProgress(projection)

                if (withinSnap && plausibleForward) {
                    outputPoint = projection.point
                    distanceAlong = projection.alongMeters
                    lastProjection = projection
                    offRouteStreak = 0
                    didSnap = true
                    snappedCount++
                } else {
                    snapRefused++
                    // Only count a fix as "off route" when it is genuinely
                    // outside the corridor; refusing to snap backwards along
                    // the route is not a departure.
                    if (projection.distanceMeters > cfg.offRouteDistanceMeters) {
                        offRouteStreak++
                    } else {
                        offRouteStreak = 0
                    }
                    lastProjection?.let { distanceAlong = it.alongMeters }
                }
            }
        } else {
            offRouteStreak = 0
        }

        smoothedPoint = smoothed
        lastAcceptedMs = raw.elapsedRealtimeMs
        accepted++

        val fix = LocationFix(
            point = outputPoint,
            rawPoint = raw.point,
            accuracyMeters = raw.accuracyMeters,
            speedMps = smoothedSpeed,
            bearingDeg = filteredBearing,
            elapsedRealtimeMs = raw.elapsedRealtimeMs,
            distanceFromRouteMeters = distanceFromRoute,
            distanceAlongRouteMeters = distanceAlong,
            snappedToRoute = didSnap
        )
        lastAccepted = fix
        return fix
    }

    // -- route projection --------------------------------------------------

    private data class Projection(
        val point: GeoPoint,
        val distanceMeters: Float,
        val alongMeters: Float,
        val segmentIndex: Int
    )

    /**
     * Nearest point on the route polyline.
     *
     * Projects onto every segment in a window around the last projection. On a
     * self-crossing route (a bridge you cross twice) a global search jumps the
     * marker to the wrong pass; the window keeps it on the pass being driven.
     */
    private fun projectOntoRoute(p: GeoPoint): Projection? {
        if (routePoints.size < 2) return null

        val window = 400
        val lastIdx = lastProjection?.segmentIndex
        val from = if (lastIdx == null) 0 else max(0, lastIdx - window)
        val to = if (lastIdx == null) routePoints.size - 2
        else min(routePoints.size - 2, lastIdx + window)

        var best: Projection? = null
        for (i in from..to) {
            val a = routePoints[i]
            val b = routePoints[i + 1]
            val proj = projectOnSegment(p, a, b)
            if (best == null || proj.distanceMeters < best.distanceMeters) {
                best = Projection(
                    point = proj.point,
                    distanceMeters = proj.distanceMeters,
                    alongMeters = cumulative[i] + proj.alongFraction *
                        (cumulative[i + 1] - cumulative[i]),
                    segmentIndex = i
                )
            }
        }

        // A window that found nothing plausible means the driver is far from
        // the last known position; fall back to a global scan so the UI can
        // still say how far off they are.
        if (best != null && lastIdx != null && best.distanceMeters > cfg.offRouteDistanceMeters * 4) {
            var globalBest: Projection? = null
            for (i in 0..routePoints.size - 2) {
                val proj = projectOnSegment(p, routePoints[i], routePoints[i + 1])
                if (globalBest == null || proj.distanceMeters < globalBest.distanceMeters) {
                    globalBest = Projection(
                        point = proj.point,
                        distanceMeters = proj.distanceMeters,
                        alongMeters = cumulative[i] + proj.alongFraction *
                            (cumulative[i + 1] - cumulative[i]),
                        segmentIndex = i
                    )
                }
            }
            if (globalBest != null && globalBest.distanceMeters < best.distanceMeters) {
                return globalBest
            }
        }
        return best
    }

    /**
     * Guards against the marker sliding backwards along the route when the
     * driver is stationary at a junction, or when a fix is pulled onto a
     * parallel carriageway.
     */
    private fun isForwardProgress(p: Projection): Boolean {
        val last = lastProjection ?: return true
        val backwards = p.alongMeters < last.alongMeters - BACKWARD_TOLERANCE_METERS
        if (!backwards) return true
        // Real U-turns and reversing into a parking space do happen; allow them
        // once the driver is clearly off the previous point.
        return p.distanceMeters > cfg.offRouteDistanceMeters
    }

    private fun filterBearing(previous: Float?, candidate: Float?): Float? = when {
        candidate == null -> previous
        previous == null -> candidate
        smoothedSpeed < cfg.minBearingSpeedMps -> previous
        else -> {
            val delta = normalizeAngleDeg(candidate - previous)
            // Rate-limit: a turn is reported over several samples rather than
            // snapping the map 90° at once.
            val clamped = delta.coerceIn(-cfg.maxBearingChangeDeg, cfg.maxBearingChangeDeg)
            ((previous + clamped) % 360f + 360f) % 360f
        }
    }

    /** True when enough consecutive fixes have been off the route to reroute. */
    fun shouldReroute(): Boolean = offRouteStreak >= cfg.offRouteConfirmSamples

    fun offRouteStreak(): Int = offRouteStreak

    fun routeLengthMeters(): Float = routeLengthMeters

    /** Fraction of the active route already travelled, 0..1. */
    fun routeProgress(): Float {
        if (routeLengthMeters <= 0f) return 0f
        val along = lastProjection?.alongMeters ?: return 0f
        return (along / routeLengthMeters).coerceIn(0f, 1f)
    }

    companion object {
        private const val BACKWARD_TOLERANCE_METERS = 15f

        /** Great-circle distance in metres. */
        fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val lat1 = Math.toRadians(a.lat)
            val lat2 = Math.toRadians(b.lat)
            val h = sin(dLat / 2) * sin(dLat / 2) +
                sin(dLon / 2) * sin(dLon / 2) * cos(lat1) * cos(lat2)
            return 2 * r * kotlin.math.asin(sqrt(h.coerceIn(0.0, 1.0)))
        }

        /** Initial bearing from [a] to [b], degrees clockwise from north. */
        fun bearingBetween(a: GeoPoint, b: GeoPoint): Float {
            val lat1 = Math.toRadians(a.lat)
            val lat2 = Math.toRadians(b.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val y = sin(dLon) * cos(lat2)
            val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
            return (((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0)).toFloat()
        }

        /** Wraps an angle difference into [-180, 180]. */
        fun normalizeAngleDeg(deg: Float): Float {
            var d = deg % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            return d
        }

        /** Result of projecting a point onto a single polyline segment. */
        class SegmentProjection(
            val point: GeoPoint,
            val distanceMeters: Float,
            /** Position along the segment, 0 at [a] and 1 at [b]. */
            val alongFraction: Float
        )

        /**
         * Projects [p] onto segment a-b in a local metric plane.
         *
         * Over a few hundred metres an equirectangular projection is accurate
         * to centimetres, which is far below GPS noise — and it avoids the
         * vector maths blowing up at the poles the way a naive 3D approach can.
         */
        fun projectOnSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): SegmentProjection {
            val latRad = Math.toRadians(p.lat)
            val kx = 111_320.0 * cos(latRad)
            val ky = 110_540.0

            val ax = a.lon * kx; val ay = a.lat * ky
            val bx = b.lon * kx; val by = b.lat * ky
            val px = p.lon * kx; val py = p.lat * ky

            val dx = bx - ax
            val dy = by - ay
            val lenSq = dx * dx + dy * dy

            val t = if (lenSq <= 1e-9) 0.0
            else (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)

            val cx = ax + t * dx
            val cy = ay + t * dy
            val projected = GeoPoint(cy / ky, cx / kx)

            return SegmentProjection(
                point = projected,
                distanceMeters = hypot(px - cx, py - cy).toFloat(),
                alongFraction = t.toFloat()
            )
        }

        private fun hypot(a: Double, b: Double): Double = sqrt(a * a + b * b)
    }
}

END FILE

## core/location-engine/src/main/java/com/ts8/core/location/LocationEngine.kt

BEGIN FILE
package com.ts8.core.location

import com.ts8.core.map.GeoPoint

/**
 * One raw fix from the platform location provider.
 *
 * Nothing here is filtered — [LocationEngine] is what turns a stream of these
 * into a [LocationFix] the navigator can trust. Keeping the raw shape separate
 * makes it obvious at call sites which of the two is in hand.
 */
data class RawLocation(
    /** WGS-84 position. */
    val point: GeoPoint,
    /** Accuracy radius in metres (68% confidence). */
    val accuracyMeters: Float,
    /** Elapsed realtime milliseconds at fix time. */
    val elapsedRealtimeMs: Long,
    /** Provider name: "gps", "network", "fused". */
    val provider: String = "gps",
    /** Ground speed in m/s, or -1 when the provider cannot report it. */
    val speedMps: Float = -1f,
    /** Course over ground in degrees, or -1 when unknown. */
    val bearingDeg: Float = -1f,
    /** Altitude in metres above the WGS-84 ellipsoid, or null. */
    val altitudeMeters: Double? = null
) {
    /** True when the fix carries enough to be plotted at all. */
    fun isUsable(): Boolean =
        point.isValid() &&
            accuracyMeters.isFinite() &&
            accuracyMeters > 0f &&
            accuracyMeters <= MAX_ACCURACY_METERS

    companion object {
        /**
         * Beyond this the fix is not "somewhere in this block" — it is noise.
         * A head unit parked under a concrete car park routinely reports 200 m;
         * 500 m means the provider guessed.
         */
        const val MAX_ACCURACY_METERS = 500f
    }
}

/** A fix the engine has accepted, smoothed and optionally snapped. */
data class LocationFix(
    /** Position after smoothing and (if a route is active) route snapping. */
    val point: GeoPoint,
    /** Position as reported, before snapping. Used to detect a wrong-way snap. */
    val rawPoint: GeoPoint,
    /** Reported accuracy in metres. */
    val accuracyMeters: Float,
    /** Filtered speed in m/s; never negative. */
    val speedMps: Float,
    /** Filtered course in degrees clockwise from north; null when stopped. */
    val bearingDeg: Float?,
    val elapsedRealtimeMs: Long,
    /** Distance in metres this fix sits from the route line, or null when free. */
    val distanceFromRouteMeters: Float? = null,
    /** Distance travelled along the active route, in metres. */
    val distanceAlongRouteMeters: Float? = null,
    /** True when [point] came from the route polyline rather than the GPS. */
    val snappedToRoute: Boolean = false
)

/**
 * GPS processing pipeline.
 *
 * The order matters and is enforced by [LocationEngine]:
 *
 * 1. **Validate** — reject NaN, impossible coordinates, and fixes whose
 *    accuracy radius makes them useless. A rejected fix is dropped, never
 *    "corrected".
 * 2. **Spike rejection** — a fix that implies a speed no car can achieve is
 *    held back. One bad sample must not move the marker, and on a route it
 *    must never trigger a reroute.
 * 3. **Smoothing** — an exponential moving average on position and speed.
 *    Raw GPS jitters by several metres every second even when parked, and a
 *    marker that shakes reads as broken.
 * 4. **Bearing filter** — the course is only trusted above walking speed and
 *    is rate-limited, because a stationary receiver emits random headings.
 * 5. **Snap to route** — when a route is active, the position is projected
 *    onto the route polyline so the marker follows the road, not the noise.
 *    Snapping is refused when the fix is too far from the line or moving
 *    clearly away from it, which is the signal the reroute logic consumes.
 */
interface LocationEngine {
    /** True once at least one accepted fix has produced an output. */
    fun hasFix(): Boolean

    /** Most recent accepted fix, or null before the first one. */
    fun lastFix(): LocationFix?

    /**
     * Feeds a raw fix through the pipeline.
     *
     * @return the accepted fix, or null when the sample was rejected. A null
     *         return is normal and callers must not treat it as an error.
     */
    fun onRawLocation(raw: RawLocation): LocationFix?

    /**
     * Activates route snapping. Passing null disables it.
     *
     * The polyline is the route's drawn geometry in travel order; the engine
     * precomputes cumulative distance so snapping and "distance along route"
     * are O(log n) per fix rather than a linear walk.
     */
    fun setRoute(points: List<GeoPoint>?)

    /** Clears smoothing state, e.g. after a route change or a long pause. */
    fun reset()

    /** Diagnostics counters, for the diagnostics screen. */
    fun stats(): LocationStats
}

/** Counters that explain *why* fixes were dropped. */
data class LocationStats(
    val received: Long = 0,
    val accepted: Long = 0,
    val rejectedInvalid: Long = 0,
    val rejectedSpike: Long = 0,
    val rejectedStale: Long = 0,
    val snapped: Long = 0,
    val snapRefused: Long = 0
) {
    val acceptanceRate: Float
        get() = if (received == 0L) 0f else accepted.toFloat() / received
}

END FILE

## core/location-engine/src/main/java/com/ts8/core/parking/LocationStability.kt

BEGIN FILE
package com.ts8.core.parking

import com.ts8.core.map.GeoPoint

/**
 * Decides whether the car's reported position has stopped wandering.
 *
 * **Why this is a separate class and not a field on the resolver.** The
 * resolver's heuristic needs two independent facts — the car is not moving
 * *and* the receiver agrees it is in one place. The first comes from speed; the
 * second cannot come from speed at all. A parked receiver emits a fix a second
 * with a 0 km/h reading while the position hops four metres between samples,
 * and that hop is precisely what a red light under an urban canyon produces.
 * Measuring the hop is a small, pure, stateful job, so it lives here where it
 * can be run against synthetic tracks on a build host.
 *
 * **Why a window and not a single sample pair.** Two consecutive fixes can be
 * metres apart merely because one of them was a bad sample; that is noise, not
 * motion. Comparing the oldest fix in a window against the newest one measures
 * how far the *car* went, not how far a *sample* jumped. The window is bounded
 * in time rather than in sample count because the receiver's rate varies with
 * sky view, and a count of five samples on a 2 Hz receiver is not the same
 * observation as five on a 1 Hz one.
 *
 * **Why [verdict] can be null.** "Stable" and "unstable" are both conclusions,
 * and a window younger than [minSpanMs] has not earned either. Returning false
 * there would tell the resolver the car is moving when it has simply not looked
 * long enough — which is exactly the confusion [ParkingSignals] documents
 * between a measurement of zero and the absence of a measurement.
 */
class LocationStability(
    /** How far back the window reaches, in milliseconds. */
    private val windowMs: Long = 25_000L,
    /**
     * How far the newest fix may be from the oldest one in the window.
     *
     * Ten metres is below one parking space and above a receiver's urban
     * jitter, so a car genuinely held in a space reads stable while a car
     * creeping forward through a queue does not.
     */
    private val maxDriftMeters: Double = 10.0,
    /**
     * How much history must exist before a verdict is given.
     *
     * Shorter than the resolver's candidate dwell on purpose: by the time the
     * twenty-second candidate window is up, this has had at least two windows
     * to compare, so the answer is available exactly when it is needed rather
     * than one decision later.
     */
    private val minSpanMs: Long = 8_000L
) {

    private data class Sample(val point: GeoPoint, val atMs: Long)

    /** Oldest first. Small by construction: a window of seconds at 1 Hz. */
    private val samples = ArrayList<Sample>(32)

    /**
     * Adds a fix.
     *
     * A sample older than the last one is a caller bug (two location sources,
     * or a clock read from the wrong source). The history is dropped rather
     * than kept, because an out-of-order sample would make the "oldest" entry
     * wrong and turn a moving car into a stable one.
     */
    fun add(point: GeoPoint, atMs: Long) {
        val newest = samples.lastOrNull()
        if (newest != null && atMs < newest.atMs) samples.clear()
        samples += Sample(point, atMs)
        trim(atMs)
    }

    /**
     * True = the position is steady, false = it is still moving, null = the
     * window does not cover enough time yet.
     *
     * @param nowMs the resolver's clock. Samples older than the window are
     *        ignored, so a receiver that has been silent for a minute and then
     *        delivers a single fix is not mistaken for a stable one.
     */
    fun verdict(nowMs: Long): Boolean? {
        trim(nowMs)
        val oldest = samples.firstOrNull() ?: return null
        val newest = samples.lastOrNull() ?: return null
        if (newest.atMs - oldest.atMs < minSpanMs) return null
        val drift = oldest.point.distanceTo(newest.point)
        if (!drift.isFinite()) return null
        return drift <= maxDriftMeters
    }

    /** Drops every sample; called when the location pipeline is released. */
    fun reset() {
        samples.clear()
    }

    /**
     * Keeps the window bounded.
     *
     * One sample is always retained even when it falls outside the window, so
     * [verdict] can still see "nothing has arrived for a minute" as a span of
     * zero rather than as an empty list — the two give the same null, but the
     * retained sample keeps [add]'s ordering check meaningful.
     */
    private fun trim(nowMs: Long) {
        while (samples.size > 1 && nowMs - samples[1].atMs > windowMs) {
            samples.removeAt(0)
        }
    }
}

END FILE

## core/location-engine/src/main/java/com/ts8/core/parking/ParkingScenarios.kt

BEGIN FILE
package com.ts8.core.parking

/**
 * Executable acceptance cases for the parking resolver, as plain Kotlin.
 *
 * **Why not JUnit/Robolectric.** The build host cannot download dependencies,
 * and Robolectric in particular is a 100 MB artifact plus an Android SDK it
 * would have to fetch. The resolver and the store are deliberately free of
 * Android imports precisely so their rules can be exercised here, in the same
 * module, with nothing but the Kotlin stdlib. The class returns failure
 * descriptions rather than asserting, because it has to be runnable from a
 * diagnostics screen on the head unit as well as from a build step — a thrown
 * exception there would take down the screen that exists to report the fault.
 *
 * **Why these scenarios and not the easy ones.** Every case below is a way the
 * feature is expected to fail in a real car: a red light, a lost fix, a GPS
 * spike, a phone call, a unit whose bus is silent. A suite that only proved
 * "speed 0 for ten minutes parks" would pass while the launcher showed the
 * parking clock at every junction.
 */
object ParkingScenarios {

    /**
     * Runs every scenario.
     *
     * @return one line per failure, in Vietnamese, each naming the scenario and
     *         the decision actually produced. An empty list means all passed —
     *         callers must treat that as success rather than as "nothing ran",
     *         which is why [scenarioCount] is public.
     */
    fun verify(): List<String> {
        val failures = ArrayList<String>()
        redLightNeverParks(failures)
        parkedTenMinutes(failures)
        singleSpikeDoesNotExit(failures)
        sustainedMovementExits(failures)
        callBlocksAutoEntry(failures)
        ignitionOffParks(failures)
        noSignalsIsUnknown(failures)
        manualWins(failures)
        parkingBrakeAloneIsCandidate(failures)
        lostFixDoesNotPark(failures)
        autoEnterOffStaysCandidate(failures)
        corruptStoreIsUnknown(failures)
        return failures
    }

    /** How many cases [verify] runs; a UI can show "0/12 lỗi". */
    val scenarioCount: Int = 12

    // -- scenarios ---------------------------------------------------------

    /**
     * A red light: speed zero, dwell short, position jittering.
     *
     * The case the brief names first. Two independent facts must stop it: the
     * dwell window has not elapsed, and the location engine says the position
     * is still moving. Either one alone is enough here, so the scenario holds
     * the car for longer than the candidate window with jitter to prove the
     * stability half is doing work rather than the clock.
     */
    private fun redLightNeverParks(failures: MutableList<String>) {
        val h = harness()
        val seen = ArrayList<ParkingState>()
        // 45 s: past the 20 s candidate window, well short of the 2 min park
        // window, with the receiver hopping between four points.
        seen += h.resolve(signals(atMs = 0, speed = 0.0, stable = false)).state
        seen += h.resolve(signals(atMs = 15_000, speed = 0.2, stable = false)).state
        seen += h.resolve(signals(atMs = 45_000, speed = 0.0, stable = false)).state

        if (seen.any { it == ParkingState.PARKED }) {
            failures += "Đèn đỏ: đã vào PARKED dù vị trí dao động (states=$seen)."
        }
        val final = h.state()
        if (final.state != ParkingState.STOPPED_TEMPORARILY) {
            failures += "Đèn đỏ: mong STOPPED_TEMPORARILY, nhận ${final.state} (${final.reason})."
        }
    }

    /** Ten minutes stationary with a stable position is a parked car. */
    private fun parkedTenMinutes(failures: MutableList<String>) {
        val h = harness()
        var t = 0L
        while (t <= 600_000L) {
            h.resolve(signals(atMs = t, speed = 0.0, stable = true))
            t += 60_000L
        }
        val final = h.state()
        if (final.state != ParkingState.PARKED) {
            failures += "Đỗ 10 phút: mong PARKED, nhận ${final.state} (${final.reason})."
        }
        if (final.confidence != ParkingConfidence.HIGH) {
            failures += "Đỗ 10 phút: mong HIGH, nhận ${final.confidence}."
        }
        if (final.reason != ParkingReason.GPS_HEURISTIC) {
            failures += "Đỗ 10 phút: mong GPS_HEURISTIC, nhận ${final.reason}."
        }
    }

    /**
     * One 40 km/h sample after parking must not dismiss the screen.
     *
     * 40 km/h is 11.1 m/s, well over the exit threshold, and is the classic
     * under-a-bridge artifact. The exit hold window is what saves it.
     */
    private fun singleSpikeDoesNotExit(failures: MutableList<String>) {
        val h = parkedHarness()
        if (!h.resolver.isParked()) {
            failures += "GPS spike: không vào được PARKED trước khi kiểm tra."
            return
        }
        h.resolve(signals(atMs = 610_000, speed = 11.11, stable = true))
        val afterSpike = h.resolve(signals(atMs = 611_000, speed = 0.0, stable = true))
        if (afterSpike.state != ParkingState.PARKED) {
            failures += "GPS spike 40 km/h: thoát khỏi PARKED sau một mẫu (${afterSpike.state})."
        }
    }

    /**
     * Driving away must actually leave PARKED.
     *
     * The mirror of the spike case: the hold window delays exit, it does not
     * cancel it. A resolver that latched into PARKED would pass every "does
     * not exit" test while leaving the driver stuck on the clock screen.
     */
    private fun sustainedMovementExits(failures: MutableList<String>) {
        val h = parkedHarness()
        var t = 601_000L
        while (t <= 610_000L) {
            h.resolve(signals(atMs = t, speed = 11.11, stable = true))
            t += 1_000L
        }
        val final = h.state()
        if (final.state == ParkingState.PARKED) {
            failures += "Chạy đi: vẫn ở PARKED sau 9 s tốc độ trên ngưỡng thoát."
        }
        if (final.reason != ParkingReason.SPEED_ABOVE_THRESHOLD) {
            failures += "Chạy đi: mong SPEED_ABOVE_THRESHOLD, nhận ${final.reason}."
        }
    }

    /** A call while stationary keeps the map; it is not a parking space. */
    private fun callBlocksAutoEntry(failures: MutableList<String>) {
        val h = harness()
        var t = 0L
        while (t <= 300_000L) {
            h.resolve(signals(atMs = t, speed = 0.0, stable = true, callActive = true))
            t += 60_000L
        }
        val final = h.state()
        if (final.state == ParkingState.PARKED) {
            failures += "Cuộc gọi: vẫn vào PARKED khi đang gọi."
        }
        if (final.reason != ParkingReason.CALL_ACTIVE) {
            failures += "Cuộc gọi: mong reason CALL_ACTIVE, nhận ${final.reason}."
        }
    }

    /** Ignition off is the strongest available signal and needs no dwell. */
    private fun ignitionOffParks(failures: MutableList<String>) {
        val h = harness()
        val decision = h.resolve(
            signals(atMs = 1_000, speed = 0.0, stable = null, ignitionOff = true)
        )
        if (decision.state != ParkingState.PARKED) {
            failures += "Tắt máy: mong PARKED, nhận ${decision.state} (${decision.reason})."
        }
        if (decision.confidence != ParkingConfidence.HIGH) {
            failures += "Tắt máy: mong HIGH, nhận ${decision.confidence}."
        }
        if (decision.reason != ParkingReason.VEHICLE_IGNITION_OFF) {
            failures += "Tắt máy: mong VEHICLE_IGNITION_OFF, nhận ${decision.reason}."
        }
    }

    /**
     * A unit whose bus is silent and which has no fix has no answer.
     *
     * The brief's second constraint: no invented bus fields. A resolver that
     * converted every null to false would report DRIVING here; the correct
     * report is that nothing is known.
     */
    private fun noSignalsIsUnknown(failures: MutableList<String>) {
        val h = harness()
        val decision = h.resolve(ParkingSignals(elapsedRealtimeMs = 5_000))
        if (decision.state != ParkingState.UNKNOWN) {
            failures += "Không tín hiệu: mong UNKNOWN, nhận ${decision.state}."
        }
        if (decision.reason != ParkingReason.INSUFFICIENT_SIGNALS) {
            failures += "Không tín hiệu: mong INSUFFICIENT_SIGNALS, nhận ${decision.reason}."
        }
        if (decision.confidence != ParkingConfidence.NONE) {
            failures += "Không tín hiệu: mong NONE, nhận ${decision.confidence}."
        }
    }

    /** The driver's button beats every inference, including a blocker. */
    private fun manualWins(failures: MutableList<String>) {
        val h = harness()
        val decision = h.resolve(
            signals(atMs = 1_000, speed = 7.0, stable = false, callActive = true, manual = true)
        )
        if (decision.state != ParkingState.PARKED) {
            failures += "Yêu cầu thủ công: mong PARKED, nhận ${decision.state}."
        }
        if (decision.reason != ParkingReason.MANUAL) {
            failures += "Yêu cầu thủ công: mong MANUAL, nhận ${decision.reason}."
        }
        if (decision.confidence != ParkingConfidence.HIGH) {
            failures += "Yêu cầu thủ công: mong HIGH, nhận ${decision.confidence}."
        }
    }

    /**
     * Losing the fix is not parking.
     *
     * The tunnel and the car-park-under-concrete case: speed is null, so the
     * only honest answer is UNKNOWN even after minutes of silence. A resolver
     * that treated null as zero would park the car in every dead zone on the
     * route.
     */
    private fun lostFixDoesNotPark(failures: MutableList<String>) {
        val h = harness()
        val decision = h.resolve(signals(atMs = 900_000, speed = null, stable = null))
        if (decision.state == ParkingState.PARKED) {
            failures += "Mất GPS: đã kết luận PARKED khi không có fix."
        }
        if (decision.state != ParkingState.UNKNOWN) {
            failures += "Mất GPS: mong UNKNOWN, nhận ${decision.state}."
        }

        // Dwell must not accrue while the receiver is dark. One minute of
        // clean dwell, a three-minute tunnel, then a stationary fix: the car
        // has been observed standing still for one minute, not four, so it is
        // a candidate at most — never PARKED.
        val gap = harness()
        var t = 0L
        while (t <= 20_000L) {
            gap.resolve(signals(atMs = t, speed = 0.0, stable = true))
            t += 10_000L
        }
        var dark = 21_000L
        while (dark <= 200_000L) {
            gap.resolve(signals(atMs = dark, speed = null, stable = null))
            dark += 10_000L
        }
        val afterGap = gap.resolve(signals(atMs = 201_000, speed = 0.0, stable = true))
        if (afterGap.state == ParkingState.PARKED) {
            failures += "Mất GPS 3 phút: thời gian dừng bị cộng dồn và vào PARKED."
        }
    }

    /**
     * The parking brake is applied at every long red light, so it may not open
     * the screen by itself even with auto-enter on.
     */
    private fun parkingBrakeAloneIsCandidate(failures: MutableList<String>) {
        val h = harness()
        val decision = h.resolve(
            ParkingSignals(
                vehicleParkingBrake = true,
                speedMps = 0.0,
                speedAccuracyMeters = 6f,
                locationStable = false,
                elapsedRealtimeMs = 1_000
            )
        )
        if (decision.state == ParkingState.PARKED) {
            failures += "Chỉ phanh tay: đã tự vào PARKED."
        }
        if (decision.state != ParkingState.PARKING_CANDIDATE) {
            failures += "Chỉ phanh tay: mong PARKING_CANDIDATE, nhận ${decision.state}."
        }
        if (decision.reason != ParkingReason.VEHICLE_PARKING_BRAKE) {
            failures += "Chỉ phanh tay: mong VEHICLE_PARKING_BRAKE, nhận ${decision.reason}."
        }
    }

    /**
     * Auto-entry off means the screen never opens by itself, however strong
     * the evidence — the setting is why the feature can ship at all.
     */
    private fun autoEnterOffStaysCandidate(failures: MutableList<String>) {
        val h = harness()
        val decision = h.resolve(
            ParkingSignals(
                vehicleIgnitionOff = true,
                speedMps = 0.0,
                speedAccuracyMeters = 6f,
                elapsedRealtimeMs = 1_000
            ),
            allowAutoEnter = false
        )
        if (decision.state == ParkingState.PARKED) {
            failures += "Tự động vào đang tắt: vẫn vào PARKED."
        }
        if (decision.state != ParkingState.PARKING_CANDIDATE) {
            failures += "Tự động vào đang tắt: mong PARKING_CANDIDATE, nhận ${decision.state}."
        }
    }

    /**
     * A truncated preferences row must degrade to UNKNOWN, never throw.
     *
     * Written as a scenario rather than a unit test because it exercises the
     * store and the resolver's shared vocabulary: a restart on this unit
     * happens on a power cut, so this path runs on real hardware far more often
     * than the happy path does.
     */
    private fun corruptStoreIsUnknown(failures: MutableList<String>) {
        val backing = HashMap<String, String>()
        val store = object : KeyValueStore {
            override fun get(key: String): String? = backing[key]
            override fun put(key: String, value: String) {
                backing[key] = value
            }
        }
        val stateStore = ParkingStateStore(store) { 0L }
        store.put(ParkingStateStore.KEY, "TS8PK1\nstate=PARKED\nconfidence=HIGH\nreason=GPS_HEUR")
        val restored = stateStore.restore()
        if (restored.state != ParkingState.UNKNOWN) {
            failures += "Bản ghi hỏng: mong UNKNOWN, nhận ${restored.state}."
        }
        if (restored.reason != ParkingReason.INSUFFICIENT_SIGNALS) {
            failures += "Bản ghi hỏng: mong INSUFFICIENT_SIGNALS, nhận ${restored.reason}."
        }
    }

    // -- helpers -----------------------------------------------------------

    /**
     * A resolver whose clock tracks the newest snapshot it is handed.
     *
     * Deliberately not a clock fixed at zero: the resolver rejects a snapshot
     * older than [ParkingStateResolver.Config.maxSnapshotAgeMs], and the
     * ten-minute scenarios would then be read as stale instead of as dwell.
     * Freshness has its own semantics; each scenario should fail for the rule
     * it is testing.
     */
    private class Harness {
        var now: Long = 0L
        val resolver: ParkingStateResolver =
            ParkingStateResolver(clock = { now }, config = ParkingStateResolver.Config())

        fun resolve(signals: ParkingSignals, allowAutoEnter: Boolean = true): ParkingDecision {
            now = signals.elapsedRealtimeMs
            return resolver.resolve(signals, allowAutoEnter)
        }

        fun state(): ParkingDecision = resolver.current()
    }

    private fun harness(): Harness = Harness()

    /** A harness already parked through the ten-minute stable-dwell path. */
    private fun parkedHarness(): Harness {
        val h = harness()
        var t = 0L
        while (t <= 600_000L) {
            h.resolve(signals(atMs = t, speed = 0.0, stable = true))
            t += 60_000L
        }
        return h
    }

    private fun signals(
        atMs: Long,
        speed: Double?,
        stable: Boolean?,
        callActive: Boolean = false,
        ignitionOff: Boolean? = null,
        manual: Boolean? = null
    ): ParkingSignals = ParkingSignals(
        vehicleGearPark = null,
        vehicleAccOn = null,
        vehicleParkingBrake = null,
        vehicleIgnitionOff = ignitionOff,
        speedMps = speed,
        speedAccuracyMeters = if (speed == null) null else 6f,
        locationStable = stable,
        navigationActive = false,
        callActive = callActive,
        reverseCameraActive = false,
        explicitManualRequest = manual,
        elapsedRealtimeMs = atMs
    )
}

END FILE

## core/location-engine/src/main/java/com/ts8/core/parking/ParkingSignals.kt

BEGIN FILE
package com.ts8.core.parking

/**
 * One instant of everything the parking decision is allowed to look at.
 *
 * **Why a snapshot and not a bag of setters.** The decision depends on signals
 * that arrive at completely different rates: the vehicle bus every few hundred
 * milliseconds, GPS once a second, the call and reverse-camera state whenever
 * the platform feels like broadcasting it. If each of those wrote into the
 * resolver separately, a decision could be taken halfway through a transition
 * — gear already in P, speed still the value from three seconds ago — and the
 * resolver would have no way to say which combination it actually saw. A
 * snapshot is also what makes the resolver a pure function, which is why it
 * can be exercised without a head unit, a looper or a test framework.
 *
 * **Why almost every field is nullable.** The TS8 firmware does not document a
 * vehicle-bus contract, so field 18 forbids assuming one. `null` means *this
 * unit does not report this signal at all*; it does not mean false, zero or
 * "no". Collapsing the two is what makes naive parking detectors park a car at
 * a red light: no signal becomes false becomes "not moving". Every nullable
 * field below therefore carries the "no answer" case explicitly, and the
 * resolver treats a null as absent evidence rather than as counter-evidence.
 */
data class ParkingSignals(
    /**
     * Gear selector reports Park.
     *
     * `null` = the bridge does not expose the selector (the common case on this
     * unit), `false` = it does and the car is *not* in Park. The difference
     * matters: `false` in Drive is evidence of driving, `null` is not, and a
     * resolver that treated both as false would either park every car whose bus
     * is silent or refuse to park every car whose selector reads "N".
     */
    val vehicleGearPark: Boolean? = null,

    /**
     * Accessory circuit is on.
     *
     * `null` = not reported. `true` is weak information: the radio runs in ACC
     * while the car is parked, so it must never be read as "engine running".
     * `false` is stronger: ACC off with a stationary car almost always means
     * the driver has finished, though a voltage dip during cranking produces
     * the same reading for a moment — which is why the resolver rates it below
     * [vehicleIgnitionOff] and below the gear signal.
     */
    val vehicleAccOn: Boolean? = null,

    /**
     * Parking brake is applied.
     *
     * `null` = not reported, `false` = reported released. `true` is real
     * evidence that the car is being held, but drivers routinely pull the
     * handbrake at a long red light, so on its own it is a candidate, not a
     * conclusion. A resolver that promoted `true` to PARKED would trigger on
     * every queue at every junction.
     */
    val vehicleParkingBrake: Boolean? = null,

    /**
     * Ignition is off.
     *
     * `null` = not reported. `true` is the strongest single signal available
     * without a documented bus contract: nobody drives with the ignition off.
     * `false` proves nothing — a keyless car in READY mode reports ignition on
     * while standing in a car park — so it must not be treated as "driving".
     */
    val vehicleIgnitionOff: Boolean? = null,

    /**
     * Ground speed from the best available source, in metres per second.
     *
     * **`null` is not `0.0`.** Null means there is no fix at all: the receiver
     * has not solved a position, the provider is disabled, or the location
     * engine rejected the sample. A fix that says `0.0` is a measurement that
     * the car is not moving. A parking decision may be taken from the second;
     * the first must produce UNKNOWN, because "GPS lost" and "parked in a
     * tunnel" look identical from inside the car and only one of them is a
     * reason to show the parking screen.
     */
    val speedMps: Double? = null,

    /**
     * Accuracy radius of the fix that produced [speedMps], in metres.
     *
     * `null` = the provider did not supply one; the speed is then accepted at
     * face value because there is nothing to contradicted it. A value above the
     * resolver's threshold makes the speed unusable and is *not* silently
     * rounded up to "probably zero" — urban-canyon fixes report 100 m accuracy
     * and a speed of zero while the car is moving, which is exactly the sample
     * that must not park the car.
     */
    val speedAccuracyMeters: Float? = null,

    /**
     * The location engine's verdict that the position has stopped wandering.
     *
     * `null` = no verdict yet (too few fixes, or the engine is not running);
     * `false` = it measured jitter above its tolerance. Stability is a
     * different question from speed: a stationary receiver can report 0 km/h
     * while the position hops between four points, and it is that hop which
     * distinguishes a red light from a parking space. Null is not "stable" and
     * not "unstable" — it simply does not count towards the agreement the
     * heuristic requires.
     */
    val locationStable: Boolean? = null,

    /**
     * Turn-by-turn guidance is running.
     *
     * Non-null by contract: the navigation controller always knows this, so
     * there is no "unreported" case to represent. Guidance active means the
     * driver is going somewhere; an automatic PARKED would hide the map in the
     * middle of a route, so this blocks entry.
     */
    val navigationActive: Boolean = false,

    /**
     * A phone call is in progress.
     *
     * Non-null because the telephony layer is the one platform signal that is
     * reliably present on API 23. A call while stationary is a delivery driver
     * pulled over, not a parked car; treating it as parked would hide the map
     * and the call controls at the moment the driver is using them.
     */
    val callActive: Boolean = false,

    /**
     * The reverse camera is showing.
     *
     * Non-null: the camera is part of this head unit's own UI, so its state is
     * always known. Camera active means the driver is manoeuvring into or out
     * of a space — the one moment when an automatic parking screen would cover
     * the screen the driver is actually looking at.
     */
    val reverseCameraActive: Boolean = false,

    /**
     * The driver's own instruction: `true` = "vào chế độ đỗ xe", `false` =
     * "thoát chế độ đỗ xe", `null` = no manual input on this snapshot.
     *
     * **`false` is not "unknown".** It is a deliberate exit and the resolver
     * latches it until the car actually moves, because otherwise the GPS
     * heuristic would put the driver straight back into the screen they just
     * dismissed. Null leaves the latch alone, which is what a snapshot taken on
     * a tick where the button was not touched should mean.
     */
    val explicitManualRequest: Boolean? = null,

    /**
     * `SystemClock.elapsedRealtime()` at the moment the snapshot was taken.
     *
     * The resolver measures dwell windows against this rather than against its
     * own clock read, because the two are not the same instant: a snapshot can
     * sit in a queue while the main thread finishes a frame, and counting that
     * delay as parking time is how a detector accumulates minutes of dwell at
     * a red light. It also lets the resolver reject a snapshot that is already
     * stale instead of accepting it as current. There is deliberately no
     * default: a caller that forgot to stamp a snapshot would otherwise measure
     * every dwell window against zero and park the car on the first tick.
     */
    val elapsedRealtimeMs: Long
)

END FILE

## core/location-engine/src/main/java/com/ts8/core/parking/ParkingStateResolver.kt

BEGIN FILE
package com.ts8.core.parking

/**
 * The five parking situations the launcher may show, exactly as §19 names them.
 *
 * [STOPPED_TEMPORARILY] exists so a red light has somewhere honest to land. The
 * alternative — two states, driving and parked — forces every stationary second
 * to be classified as one of them, and a detector that must choose always ends
 * up showing the parking clock at a junction. [PARKING_CANDIDATE] is the
 * evidence-is-there-but-the-screen-is-not state: signals agree the car is
 * parked, but a blocker (a call, the reverse camera, an active route) or the
 * auto-enter setting keeps the map on screen.
 */
enum class ParkingState {
    /** Moving, or stopped only long enough to be traffic. */
    DRIVING,

    /** Below the stop threshold, but not long enough to be a candidate. */
    STOPPED_TEMPORARILY,

    /** Evidence of parking exists, but entry is blocked or not permitted. */
    PARKING_CANDIDATE,

    /** Confirmed parked; the parking screen may be shown. */
    PARKED,

    /** Not enough trustworthy information to say anything. */
    UNKNOWN
}

/**
 * How much the [ParkingState] is trusted.
 *
 * Only [HIGH] may open the parking screen automatically. [MEDIUM] is the
 * parking-brake-alone case: real evidence, but the same reading occurs at every
 * long red light, so it stays a candidate. [NONE] belongs to [ParkingState.UNKNOWN]
 * only — a decision never claims confidence about nothing.
 */
enum class ParkingConfidence { NONE, LOW, MEDIUM, HIGH }

/**
 * Why the state was chosen, for diagnostics.
 *
 * The spec requires reason and confidence to be stored rather than inferred
 * later, because "the screen showed" and "the screen was correct" are different
 * questions and only the recorded reason can answer the second. Each value maps
 * to exactly one branch in [ParkingStateResolver.resolve], so a bug report that
 * says `CALL_ACTIVE` points at one place in the code.
 */
enum class ParkingReason {
    /** Gear selector reported Park. */
    VEHICLE_GEAR_PARK,

    /** Ignition reported off. */
    VEHICLE_IGNITION_OFF,

    /** Parking brake reported applied. Never enough on its own for auto entry. */
    VEHICLE_PARKING_BRAKE,

    /** The driver pressed the manual button. */
    MANUAL,

    /** Speed, dwell and location stability agreed; the fallback path. */
    GPS_HEURISTIC,

    /** The car is moving faster than the stop threshold. */
    SPEED_ABOVE_THRESHOLD,

    /** No usable signal, or the snapshot was too old to conclude anything. */
    INSUFFICIENT_SIGNALS,

    /** A phone call is in progress; automatic entry is suppressed. */
    CALL_ACTIVE,

    /** The reverse camera is showing; automatic entry is suppressed. */
    REVERSE_ACTIVE,

    /** Guidance is running; automatic entry is suppressed. */
    NAVIGATION_ACTIVE
}

/**
 * One resolved parking decision.
 *
 * [evidence] is a short Vietnamese explanation for the diagnostics screen. It
 * deliberately contains **no coordinates and no position at all** — the type
 * that produces it never receives a position, and [ParkingStateStore] writes
 * this string to disk. A parked car's coordinates are the most sensitive thing
 * the launcher handles; keeping them out of the type makes "the evidence log
 * leaked where the car was" impossible rather than merely discouraged.
 */
data class ParkingDecision(
    val state: ParkingState,
    val confidence: ParkingConfidence,
    val reason: ParkingReason,
    val evidence: String
) {
    /** True when the parking screen may actually be shown. */
    val showsParkingScreen: Boolean
        get() = state == ParkingState.PARKED && confidence == ParkingConfidence.HIGH

    companion object {
        /**
         * The honest no-answer. Every failure path resolves to this instead of
         * to a plausible-looking PARKED or DRIVING, so a caller that forgets to
         * check has nothing to show rather than the wrong screen.
         */
        val UNKNOWN = ParkingDecision(
            state = ParkingState.UNKNOWN,
            confidence = ParkingConfidence.NONE,
            reason = ParkingReason.INSUFFICIENT_SIGNALS,
            evidence = "Chưa đủ tín hiệu để xác định trạng thái đỗ."
        )
    }
}

/**
 * Resolves [ParkingSignals] into a [ParkingDecision].
 *
 * **Why one resolver and not "speed == 0".** The brief's first sentence in §18
 * is the failure this class exists to prevent: GPS speed zero means a red
 * light exactly as often as it means a parking space, and losing GPS produces
 * the same zero. The old shortcut therefore cannot be repaired by tuning a
 * threshold; it has to be replaced by priority-ordered evidence, which is what
 * the three branches below are.
 *
 * **Priority, implemented in this order:**
 * 1. **Explicit vehicle signals**, when this unit actually reports them:
 *    ignition off or gear in Park are definitive ([ParkingConfidence.HIGH]);
 *    parking brake alone is [ParkingConfidence.MEDIUM] because it is applied
 *    at every long red light too. Without a contract from the MCU these are
 *    nullable, and a null signal is skipped rather than read as false.
 * 2. **Manual driver request.** It is a direct instruction and outranks the
 *    heuristic completely. A manual *exit* is handled even before the vehicle
 *    signals, because a dismissal that a gear reading could overrule would be
 *    a button that does not work.
 * 3. **The GPS heuristic**, only when independently corroborated: speed below
 *    the stop threshold **and** the car has held that for the dwell window
 *    **and** the location engine reports the position stable. A red light
 *    fails dwell and usually stability, so it lands on
 *    [ParkingState.STOPPED_TEMPORARILY] and never on a candidate.
 *
 * Measured motion contradicts *evidence* but not an *instruction*: a vehicle
 * signal that says parked while the car is moving is a stale bus reading, so
 * it is reported as driving; a manual request is honoured as asked.
 *
 * **Why null signals are skipped rather than folded into false.** Field 18
 * forbids inventing a bus contract. A unit whose firmware does not report the
 * gear selector sends null on every tick; if that null became false the
 * resolver would think it had evidence the car is not in Park, and either
 * never park or (worse, if the branch were inverted) park on silence. Null
 * simply does not vote.
 *
 * **Anti-chatter.** Entry needs the dwell window, which no red light survives.
 * Exit from [ParkingState.PARKED] needs speed above the exit threshold
 * *sustained* for the hold window, so the single 40 km/h spike every receiver
 * emits under trees or a bridge cannot dismiss the screen. Once parked, losing
 * the bus signal or the GPS fix does not exit either: silence is not evidence
 * of movement.
 *
 * **Threading.** Single-threaded by contract: the main looper owns this object
 * and every method is called from there. There is no synchronisation because
 * adding it would hide the real requirement — the signals snapshot and the
 * setting read must be taken on the same thread as the decision, or the
 * resolver can be told the auto-enter setting is on after it decided with it
 * off. Calling from any other thread is a bug, not a supported mode.
 *
 * **Purity.** No Android imports: neither `SystemClock`, `Looper` nor a
 * `Context` is reachable from here. Time arrives through [clock] and the
 * platform's own state through [ParkingSignals], which is what lets
 * [ParkingScenarios] exercise every threshold on the build host without
 * Robolectric.
 */
class ParkingStateResolver(
    /** Injected rather than read from `SystemClock` so the class stays pure. */
    private val clock: () -> Long,
    private val config: Config = Config()
) {

    /**
     * Thresholds and windows, in one immutable object.
     *
     * Defaults are chosen for this head unit, not for a phone: the stop
     * threshold is 3.6 km/h because GPS under a car park roof jitters by about
     * that much; the exit threshold is 10.8 km/h so a creep out of a space
     * does not dismiss the screen; the park dwell is two minutes because a
     * queue at a junction can hold a car for ninety seconds; the hold window
     * is six seconds because a GPS spike lasts one or two samples.
     */
    data class Config(
        /** At or below this the car counts as standing still, in m/s. */
        val stopSpeedMps: Double = 1.0,
        /** Above this sustained for [exitHoldMs] the car counts as driving. */
        val exitSpeedMps: Double = 3.0,
        /** A fix worse than this cannot say whether the car moved, in metres. */
        val maxSpeedAccuracyMeters: Float = 40f,
        /** A snapshot older than this is not a description of the car now. */
        val maxSnapshotAgeMs: Long = 5_000L,
        /** Dwell needed before the heuristic even calls it a candidate. */
        val candidateDwellMs: Long = 20_000L,
        /** Dwell needed before the heuristic may call it parked. */
        val parkDwellMs: Long = 120_000L,
        /** How long speed must stay above the exit threshold to leave PARKED. */
        val exitHoldMs: Long = 6_000L,
        /**
         * How long a fix may be missing, and dwell still be carried over.
         *
         * Dwell is measured time, and time without a fix is not measured. A
         * receiver that drops out at a red light for two minutes and returns
         * still stationary must not be credited with two minutes of dwell —
         * that turns the most common dead zone into a false parking screen.
         * A short gap (one lost sample, one rejected fix) is ignored so a
         * jittery receiver does not restart the clock forever.
         */
        val noFixGraceMs: Long = 15_000L
    )

    private var last: ParkingDecision = ParkingDecision.UNKNOWN
    private var lastEvaluatedMs: Long = -1L

    /** True once a decision has put the car in [ParkingState.PARKED]. */
    private var parked = false

    /** Start of the current below-stop-threshold stretch, or -1. */
    private var stationarySinceMs: Long = -1L

    /** Start of the current above-exit-threshold stretch, or -1. */
    private var movingSinceMs: Long = -1L

    /** When the current no-fix stretch began, or -1 while a fix is present. */
    private var noFixSinceMs: Long = -1L

    /** Set by an explicit "thoát" so the heuristic cannot re-enter at once. */
    private var manualExitLatched = false

    private var lastParkReason: ParkingReason = ParkingReason.GPS_HEURISTIC
    private var lastParkConfidence: ParkingConfidence = ParkingConfidence.MEDIUM

    /**
     * Evaluates one snapshot.
     *
     * @param allowAutoEnter the caller's read of the "tự vào chế độ đỗ xe"
     *        setting. It is a parameter rather than a field because the driver
     *        can change it while parked and the next tick must honour the new
     *        value; caching it in the constructor would make the checkbox
     *        appear to do nothing until the process restarts.
     * @return the decision, also available through [current]. Never null: a
     *         caller that has signals but no answer gets [ParkingState.UNKNOWN],
     *         not a made-up DRIVING.
     */
    @JvmOverloads
    fun resolve(signals: ParkingSignals, allowAutoEnter: Boolean = false): ParkingDecision {
        val now = signals.elapsedRealtimeMs

        // A snapshot stamped before the last one is a caller bug (two sources
        // feeding the resolver, or a clock read from the wrong source). Reusing
        // dwell accumulated against a different timeline would invent duration,
        // so the history is dropped and the snapshot is evaluated as the first
        // one of a new stretch.
        if (lastEvaluatedMs >= 0L && now < lastEvaluatedMs) {
            resetTracking()
        }
        lastEvaluatedMs = now

        val age = clock() - now
        if (age > config.maxSnapshotAgeMs) {
            return publish(
                ParkingDecision.UNKNOWN.copy(
                    evidence = "Mẫu tín hiệu đã cũ (${age} ms); không dùng để kết luận."
                )
            )
        }

        // A manual *exit* is handled first and alone. It is the one instruction
        // that must never be overruled by the evidence it is dismissing —
        // otherwise a gear reading would make the exit button appear dead.
        if (signals.explicitManualRequest == false) {
            val wasParked = parked
            parked = false
            manualExitLatched = true
            movingSinceMs = -1L
            val exitSpeed = signals.speedMps
            val moving = exitSpeed != null && exitSpeed >= config.stopSpeedMps
            return publish(
                ParkingDecision(
                    state = if (moving) ParkingState.DRIVING else ParkingState.STOPPED_TEMPORARILY,
                    confidence = ParkingConfidence.LOW,
                    reason = ParkingReason.MANUAL,
                    evidence = if (wasParked) {
                        "Người lái yêu cầu thoát chế độ đỗ xe."
                    } else {
                        "Người lái yêu cầu thoát; giữ nguyên cho tới khi xe chạy đủ lâu."
                    }
                )
            )
        }

        val speed = signals.speedMps
        val trustworthySpeed = speed != null && isSpeedAccurate(signals.speedAccuracyMeters)
        val aboveExit = trustworthySpeed && speed!! >= config.exitSpeedMps
        val aboveStop = trustworthySpeed && speed!! >= config.stopSpeedMps

        if (aboveExit) {
            if (movingSinceMs < 0L) movingSinceMs = now
        } else {
            movingSinceMs = -1L
        }
        val exitSustained = aboveExit && now - movingSinceMs >= config.exitHoldMs

        // Already parked: hold through a single spike, a lost fix or a silent
        // bus. Only sustained movement or the manual exit above ends it, which
        // is exactly the "không thoát chỉ vì một GPS spike" rule.
        if (parked) {
            if (exitSustained) {
                parked = false
                manualExitLatched = false
                stationarySinceMs = -1L
                return publish(
                    ParkingDecision(
                        state = ParkingState.DRIVING,
                        confidence = ParkingConfidence.HIGH,
                        reason = ParkingReason.SPEED_ABOVE_THRESHOLD,
                        evidence = "Xe đã chạy trên ngưỡng thoát liên tục ${config.exitHoldMs} ms; thoát chế độ đỗ."
                    )
                )
            }
            // A manual "vào" while already parked must still change the
            // recorded reason: diagnostics asked "who decided this", and the
            // answer changed even though the state did not.
            if (signals.explicitManualRequest == true) {
                lastParkReason = ParkingReason.MANUAL
                lastParkConfidence = ParkingConfidence.HIGH
            }
            return publish(
                ParkingDecision(
                    state = ParkingState.PARKED,
                    confidence = lastParkConfidence,
                    reason = lastParkReason,
                    evidence = if (aboveExit) {
                        "Có xóc tốc độ ngắn nhưng chưa đủ thời gian giữ; vẫn giữ trạng thái đỗ."
                    } else {
                        "Tín hiệu đỗ còn hiệu lực; chỉ thoát khi xe chạy liên tục."
                    }
                )
            )
        }

        // A manual exit stands until the car has actually driven away.
        if (manualExitLatched) {
            if (exitSustained) {
                manualExitLatched = false
            } else {
                return publish(
                    ParkingDecision(
                        state = if (aboveStop) ParkingState.DRIVING else ParkingState.STOPPED_TEMPORARILY,
                        confidence = ParkingConfidence.LOW,
                        reason = ParkingReason.MANUAL,
                        evidence = "Đã thoát theo yêu cầu; chưa vào lại cho tới khi xe chạy đủ lâu."
                    )
                )
            }
        }

        // Measured motion contradicts *evidence*: a bus line still reading
        // "P" while the car is doing 30 km/h is a stale frame, so the speed
        // wins. It does not contradict an *instruction*, which is why this
        // check is skipped when the driver asked to enter parking.
        if (aboveStop && signals.explicitManualRequest != true) {
            stationarySinceMs = -1L
            return publish(
                ParkingDecision(
                    state = ParkingState.DRIVING,
                    confidence = ParkingConfidence.HIGH,
                    reason = ParkingReason.SPEED_ABOVE_THRESHOLD,
                    evidence = "Tốc độ ${formatMps(speed!!)} m/s, trên ngưỡng dừng."
                )
            )
        }

        // 1. Explicit vehicle signals, when this unit reports them.
        val vehicle = vehicleEvidence(signals)
        if (vehicle != null) {
            return publish(automatic(signals, allowAutoEnter, now, vehicle))
        }

        // 2. The driver's own instruction. It outranks the heuristic and is
        //    not an inference, so it needs no corroboration and no dwell.
        if (signals.explicitManualRequest == true) {
            parked = true
            manualExitLatched = false
            movingSinceMs = -1L
            if (stationarySinceMs < 0L) stationarySinceMs = now
            lastParkReason = ParkingReason.MANUAL
            lastParkConfidence = ParkingConfidence.HIGH
            return publish(
                ParkingDecision(
                    state = ParkingState.PARKED,
                    confidence = ParkingConfidence.HIGH,
                    reason = ParkingReason.MANUAL,
                    evidence = "Người lái yêu cầu vào chế độ đỗ xe."
                )
            )
        }

        // 3. GPS heuristic. Speed alone never concludes parking: a fix worse
        //    than the accuracy limit says nothing about movement, and no fix at
        //    all is the tunnel case the brief calls out explicitly.
        val fixUsable = speed != null && isSpeedAccurate(signals.speedAccuracyMeters)
        if (!fixUsable) {
            if (noFixSinceMs < 0L) noFixSinceMs = now
            // A gap longer than the grace period means the dwell the car
            // "held" was never observed; keeping it would park the car by the
            // length of a tunnel. The stretch restarts when the fix returns.
            if (now - noFixSinceMs > config.noFixGraceMs) stationarySinceMs = -1L
            return publish(
                ParkingDecision.UNKNOWN.copy(
                    reason = ParkingReason.INSUFFICIENT_SIGNALS,
                    evidence = if (speed == null) {
                        "Không có fix GPS; mất tín hiệu không có nghĩa là đã đỗ."
                    } else {
                        "Độ chính xác GPS ${formatAccuracy(signals.speedAccuracyMeters)} m, không đủ để xác định."
                    }
                )
            )
        }
        noFixSinceMs = -1L

        if (stationarySinceMs < 0L) stationarySinceMs = now
        val dwellMs = now - stationarySinceMs
        val stable = signals.locationStable == true

        if (dwellMs >= config.candidateDwellMs && stable) {
            val confidence = if (dwellMs >= config.parkDwellMs) {
                ParkingConfidence.HIGH
            } else {
                ParkingConfidence.MEDIUM
            }
            return publish(
                automatic(
                    signals = signals,
                    allowAutoEnter = allowAutoEnter,
                    nowMs = now,
                    candidate = Candidate(
                        confidence = confidence,
                        reason = ParkingReason.GPS_HEURISTIC,
                        evidence = "Dừng ${dwellMs / 1_000} s, vị trí ổn định, tốc độ dưới ngưỡng."
                    )
                )
            )
        }

        val evidence = when {
            dwellMs < config.candidateDwellMs ->
                "Dừng ${dwellMs / 1_000} s, chưa đủ thời gian để coi là ứng viên đỗ."
            signals.locationStable == false ->
                "Vị trí còn dao động; xe có thể đang dừng đèn đỏ."
            else ->
                "Chưa có kết luận ổn định vị trí; không đủ hai tín hiệu độc lập."
        }
        return publish(
            ParkingDecision(
                state = ParkingState.STOPPED_TEMPORARILY,
                confidence = if (stable) ParkingConfidence.MEDIUM else ParkingConfidence.LOW,
                reason = ParkingReason.GPS_HEURISTIC,
                evidence = evidence
            )
        )
    }

    /** The latest decision, or [ParkingDecision.UNKNOWN] before the first call. */
    fun current(): ParkingDecision = last

    /** True while the resolver holds the car in [ParkingState.PARKED]. */
    fun isParked(): Boolean = parked

    /** Forgets everything, e.g. when the process is torn down or GPS is released. */
    fun reset() {
        resetTracking()
        last = ParkingDecision.UNKNOWN
        lastEvaluatedMs = -1L
    }

    // -- internals ---------------------------------------------------------

    /**
     * A positive vehicle signal, with the confidence it earns.
     *
     * Only `true` is evidence. Explicit `false` is not counter-evidence: a car
     * in a space can be in N with the ignition in READY, and this unit's
     * "ignition off" line is absent on keyless variants. Treating those as
     * "not parked" would make the resolver refuse the one case it can prove.
     *
     * [ParkingSignals.vehicleAccOn] is deliberately absent from this list.
     * The radio runs in ACC while the car is parked, so `true` says nothing
     * about parking; and a voltage dip during cranking produces `false` for a
     * few hundred milliseconds, so it must not vote either. The field stays in
     * the snapshot because the diagnostics screen shows it and because a
     * future verified contract may give it meaning — but guessing at that
     * meaning now would be exactly the invented bus semantics §18 bans.
     */
    private fun vehicleEvidence(signals: ParkingSignals): Candidate? = when {
        signals.vehicleIgnitionOff == true -> Candidate(
            confidence = ParkingConfidence.HIGH,
            reason = ParkingReason.VEHICLE_IGNITION_OFF,
            evidence = "Tín hiệu xe báo đã tắt máy."
        )
        signals.vehicleGearPark == true -> Candidate(
            confidence = ParkingConfidence.HIGH,
            reason = ParkingReason.VEHICLE_GEAR_PARK,
            evidence = "Cần số báo vị trí P."
        )
        signals.vehicleParkingBrake == true -> Candidate(
            // Real evidence, but pulled at every long red light, so it can
            // never open the screen automatically.
            confidence = ParkingConfidence.MEDIUM,
            reason = ParkingReason.VEHICLE_PARKING_BRAKE,
            evidence = "Phanh tay đang bật; chưa đủ để tự động vào chế độ đỗ."
        )
        else -> null
    }

    /**
     * Applies the blockers, the auto-enter setting and the confidence gate to a
     * conclusion, and only then lets it become [ParkingState.PARKED].
     *
     * Keeping these three gates in one place is what stops one branch (say the
     * ignition signal) from bypassing a rule the others obey.
     */
    private fun automatic(
        signals: ParkingSignals,
        allowAutoEnter: Boolean,
        nowMs: Long,
        candidate: Candidate
    ): ParkingDecision {
        val blocker = blocker(signals)
        if (blocker != null) {
            return ParkingDecision(
                state = ParkingState.PARKING_CANDIDATE,
                confidence = ParkingConfidence.MEDIUM,
                reason = blocker,
                evidence = "${candidate.evidence} Nhưng ${blockerText(blocker)} nên không tự vào."
            )
        }
        if (!allowAutoEnter) {
            return ParkingDecision(
                state = ParkingState.PARKING_CANDIDATE,
                confidence = candidate.confidence,
                reason = candidate.reason,
                evidence = "${candidate.evidence} Chế độ tự động vào đang tắt."
            )
        }
        if (candidate.confidence != ParkingConfidence.HIGH) {
            return ParkingDecision(
                state = ParkingState.PARKING_CANDIDATE,
                confidence = candidate.confidence,
                reason = candidate.reason,
                evidence = candidate.evidence
            )
        }

        parked = true
        manualExitLatched = false
        movingSinceMs = -1L
        if (stationarySinceMs < 0L) stationarySinceMs = nowMs
        lastParkReason = candidate.reason
        lastParkConfidence = candidate.confidence
        return ParkingDecision(
            state = ParkingState.PARKED,
            confidence = candidate.confidence,
            reason = candidate.reason,
            evidence = candidate.evidence
        )
    }

    /** Which platform state forbids an automatic parking screen, if any. */
    private fun blocker(signals: ParkingSignals): ParkingReason? = when {
        signals.callActive -> ParkingReason.CALL_ACTIVE
        signals.reverseCameraActive -> ParkingReason.REVERSE_ACTIVE
        signals.navigationActive -> ParkingReason.NAVIGATION_ACTIVE
        else -> null
    }

    private fun blockerText(reason: ParkingReason): String = when (reason) {
        ParkingReason.CALL_ACTIVE -> "đang có cuộc gọi"
        ParkingReason.REVERSE_ACTIVE -> "camera lùi đang bật"
        ParkingReason.NAVIGATION_ACTIVE -> "đang dẫn đường"
        else -> "có tín hiệu khác đang bật"
    }

    private fun isSpeedAccurate(accuracyMeters: Float?): Boolean =
        accuracyMeters == null || accuracyMeters <= config.maxSpeedAccuracyMeters

    private fun publish(decision: ParkingDecision): ParkingDecision {
        last = decision
        return decision
    }

    private fun resetTracking() {
        parked = false
        stationarySinceMs = -1L
        movingSinceMs = -1L
        noFixSinceMs = -1L
        manualExitLatched = false
        lastParkReason = ParkingReason.GPS_HEURISTIC
        lastParkConfidence = ParkingConfidence.MEDIUM
    }

    /** One line per thousandth, because a "0" from 0.04 m/s would be a lie. */
    private fun formatMps(value: Double): String {
        val scaled = Math.round(value * 1000.0)
        return "${scaled / 1000},${Math.abs(scaled % 1000).toString().padStart(3, '0')}"
    }

    private fun formatAccuracy(value: Float?): String {
        if (value == null) return "không rõ"
        val scaled = Math.round(value * 10.0)
        return "${scaled / 10},${Math.abs(scaled % 10)}"
    }

    /** A conclusion before the blockers and the setting have had their say. */
    private data class Candidate(
        val confidence: ParkingConfidence,
        val reason: ParkingReason,
        val evidence: String
    )
}

END FILE


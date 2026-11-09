package com.ts8.launcher.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ts8.core.music.MusicBackend
import com.ts8.core.music.MusicCollection
import com.ts8.core.music.MusicLibrary
import com.ts8.core.music.PlaybackState
import com.ts8.core.music.RepeatMode
import com.ts8.core.music.Track
import com.ts8.launcher.R
import com.ts8.launcher.di.AppGraph
import com.ts8.launcher.runtime.MusicRuntime

/**
 * Music — design state 3, fullscreen.
 *
 * **Playback is not owned here.** The player lives in
 * [com.ts8.launcher.service.MusicPlaybackService] so it survives this Activity
 * being destroyed and recreated, which on a head unit happens on every USB
 * mount and every theme change. This screen binds to it, renders its state and
 * sends commands; killing the screen leaves the music playing, as it must.
 *
 * **The library is paged off the main thread.** Every query below is a SQLite
 * read. They run on [AppGraph.io] and are posted back, and each load carries a
 * generation number so a tab switch during a slow query cannot paint the
 * previous tab's rows over the new one.
 *
 * **What the design shows and this screen does not fabricate.** The `FLAC`
 * badge is filled from the track's own MIME type and hidden when there is none;
 * the artwork is a real album cover from MediaStore or the neutral tile; the
 * left column's title falls back to "Chưa phát nhạc" when nothing is loaded.
 * There is no sample track, no stock cover, and no invented duration.
 */
class MusicFullscreenActivity : BaseScreenActivity() {

    override val screen: BottomNavView.Screen = BottomNavView.Screen.MUSIC

    private lateinit var graph: AppGraph
    private lateinit var music: MusicRuntime

    private lateinit var artworkFrame: View
    private lateinit var artwork: ImageView
    private lateinit var artworkPlaceholder: ImageView
    private lateinit var codecBadge: TextView
    private lateinit var trackTitle: TextView
    private lateinit var trackSubtitle: TextView
    private lateinit var trackMeta: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnPrevious: ImageButton
    private lateinit var btnFavorite: ImageButton
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnRepeat: ImageButton
    private lateinit var libraryList: RecyclerView
    private lateinit var libraryEmpty: View
    private lateinit var libraryEmptyText: TextView
    private lateinit var libraryEmptyHint: TextView
    private lateinit var libraryLoading: View
    private lateinit var libraryFooter: TextView
    private lateinit var tabs: Map<Tab, TextView>

    private lateinit var adapter: MusicRowAdapter
    private lateinit var artLoader: AlbumArtLoader
    private lateinit var searchField: android.widget.EditText
    private lateinit var providerList: LinearLayout
    private lateinit var sourceViews: Map<Source, TextView>

    /**
     * Which catalogue the right column is showing.
     *
     * **Why this is separate from [tab].** A tab answers "which slice of the
     * library"; a source answers "which library". Merging them would make
     * `USB` a fifth tab beside `Album`, which is not the same kind of choice —
     * a driver with no stick would see an empty tab forever rather than a row
     * that says the stick is not there.
     */
    private var source: Source = Source.DEVICE

    private enum class Source { DEVICE, USB, ONLINE }

    private val main = Handler(Looper.getMainLooper())

    /** Which tab the right column is showing. */
    private var tab: Tab = Tab.SONGS

    /** Bumped on every library load; a stale answer is dropped by number. */
    private var loadGeneration = 0

    /** Rows currently rendered, so the playing marker can be re-applied. */
    private var currentRows: List<MusicRowAdapter.Row> = emptyList()

    /**
     * The online results for the current query, kept so a tap can resolve one
     * without searching again.
     *
     * Keyed by the row key the adapter holds, because the adapter's payload is
     * deliberately opaque — it reports the row it rendered, and this is what
     * turns that back into the provider's own object.
     */
    private var onlineRows: Map<String, com.ts8.core.music.online.OnlineTrack> = emptyMap()

    /**
     * True while the field has text, i.e. the right column shows search results
     * rather than the tabbed library.
     *
     * The field is the mode: there is no third control to get out of sync with,
     * and clearing it is the documented way back to the tabs.
     */
    private var searching = false

    /** Where the query in flight came from, so the loader can be cancelled. */
    private val searchDebounce = Runnable { runOnlineSearch() }

    /** True while the driver is dragging the seek bar, so playback does not
     *  yank the thumb out from under them. */
    private var scrubbing = false

    /** Set while a programmatic seek is in flight, to ignore its echo. */
    private var playingTrackId: Long? = null

    private enum class Tab { PLAYLIST, SONGS, ALBUMS, ARTISTS }

    // -- player --------------------------------------------------------------

    private val observer = object : MusicRuntime.Observer {
        override fun onPlaybackState(state: PlaybackState) {
            renderPlayback(state)
        }

        override fun onBackendChanged(backend: MusicBackend?) {
            renderPlayback(music.state())
            // A backend appearing means the scan may have finished, so the
            // library the driver is looking at can now be stale.
            loadLibrary()
        }
    }

    // -- lifecycle -----------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music_fullscreen)
        bindNavigation()

        graph = AppGraph.require()
        music = MusicRuntime.get(this)

        bindViews()
        holdInitialFocus()
        artLoader = AlbumArtLoader(this, targetPx())
        buildList()
        wireTabs()
        wireSources()
        wireTransport()
        wireSeekBar()
        wireSearch()

        renderPlayback(music.state())
        loadLibrary()
    }

    private fun bindViews() {
        artworkFrame = findViewById(R.id.artworkFrame)
        artwork = findViewById(R.id.artwork)
        artworkPlaceholder = findViewById(R.id.artworkPlaceholder)
        codecBadge = findViewById(R.id.codecBadge)
        trackTitle = findViewById(R.id.trackTitle)
        trackSubtitle = findViewById(R.id.trackSubtitle)
        trackMeta = findViewById(R.id.trackMeta)
        seekBar = findViewById(R.id.seekBar)
        positionText = findViewById(R.id.positionText)
        durationText = findViewById(R.id.durationText)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnNext = findViewById(R.id.btnNext)
        btnPrevious = findViewById(R.id.btnPrevious)
        btnFavorite = findViewById(R.id.btnFavorite)
        btnShuffle = findViewById(R.id.btnShuffle)
        btnRepeat = findViewById(R.id.btnRepeat)
        libraryList = findViewById(R.id.libraryList)
        libraryEmpty = findViewById(R.id.libraryEmpty)
        libraryEmptyText = findViewById(R.id.libraryEmptyText)
        libraryEmptyHint = findViewById(R.id.libraryEmptyHint)
        libraryLoading = findViewById(R.id.libraryLoading)
        libraryFooter = findViewById(R.id.libraryFooter)
        searchField = findViewById(R.id.musicSearch)
        providerList = findViewById(R.id.providerList)
    }

    /**
     * §7: the screen opens with the keyboard down and the search field unfocused.
     *
     * `android:windowSoftInputMode="stateAlwaysHidden"` in the manifest stops the
     * IME from being *shown* when this activity starts, but it does not stop the
     * platform's initial-focus pass from handing focus to the first
     * `focusableInTouchMode` view — which here is the search field, because it is
     * the only one in the layout. A field that holds focus draws its caret and
     * its cursor, and any later window change (a USB mount, the vendor bar
     * redrawing, the library finishing a load) can bring the IME up behind it.
     *
     * So the root takes focus explicitly. It is focusable and
     * `focusableInTouchMode` in `activity_music_fullscreen.xml` for exactly this
     * call; a root that cannot take focus would drop this back onto the field.
     * The IME is also dismissed outright, because on some vendor builds the
     * service was already attached before this line runs.
     *
     * Focus is only touched when the field has none yet in `onResume` — see
     * [restoreFocusUnlessSearching] — so a driver who tapped Search keeps their
     * keyboard.
     */
    private fun holdInitialFocus() {
        findViewById<View>(R.id.musicRoot)?.requestFocus()
        searchField.clearFocus()
        hideKeyboard()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager ?: return
        runCatching { imm.hideSoftInputFromWindow(searchField.windowToken, 0) }
    }

    override fun onStart() {
        super.onStart()
        music.attach(observer)
    }

    override fun onResume() {
        super.onResume()
        renderPlayback(music.state())
        restoreFocusUnlessSearching()
    }

    /**
     * On the way back in, focus is only re-taken when the driver was not
     * searching. Coming back from a USB permission dialog or the vendor bar
     * must not leave a keyboard up over the library; coming back from a track
     * tap inside the list must not steal focus from where they were.
     */
    private fun restoreFocusUnlessSearching() {
        if (searching) return
        if (searchField.hasFocus()) {
            searchField.clearFocus()
            findViewById<View>(R.id.musicRoot)?.requestFocus()
            hideKeyboard()
        }
    }

    override fun onPause() {
        // The resume point is written here rather than in `onStop` because the
        // player may be killed with the process at any point after that.
        runCatching { music.backend?.player?.saveCheckpoint() }
        super.onPause()
    }

    override fun onStop() {
        music.detach(observer)
        super.onStop()
    }

    override fun onDestroy() {
        artLoader.shutdown()
        super.onDestroy()
    }

    // -- rendering the player ------------------------------------------------

    private fun renderPlayback(state: PlaybackState) {
        val track = state.track
        if (track == null) {
            trackTitle.setText(R.string.home_music_empty)
            trackSubtitle.setText(R.string.home_music_hint)
            trackMeta.visibility = View.GONE
            codecBadge.visibility = View.GONE
            seekBar.progress = 0
            seekBar.isEnabled = false
            positionText.text = ClockFormat.trackPosition(0)
            durationText.text = ClockFormat.trackPosition(0)
            showArtworkPlaceholder()
            playingTrackId = null
        } else {
            trackTitle.text = track.title.ifBlank { getString(R.string.music_unknown_track) }
            trackSubtitle.text = track.subtitle.ifBlank {
                getString(R.string.music_unknown_artist)
            }
            renderMeta(track)
            renderCodecBadge(track)

            if (!scrubbing) {
                seekBar.isEnabled = true
                val duration = if (state.durationMs > 0) state.durationMs else track.durationMs
                seekBar.progress = if (duration <= 0L) 0 else {
                    ((state.positionMs.toFloat() / duration) * SEEK_MAX).toInt()
                        .coerceIn(0, SEEK_MAX)
                }
                positionText.text = ClockFormat.trackPosition(state.positionMs)
                durationText.text = ClockFormat.trackPosition(duration)
            }

            if (playingTrackId != track.id) {
                playingTrackId = track.id
                loadCover(track)
            }
            refreshPlayingRow(track.id)
        }

        btnPlayPause.setImageResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlayPause.contentDescription =
            getString(if (state.isPlaying) R.string.music_pause else R.string.music_play)
        btnPlayPause.alpha = if (track != null) 1f else 0.4f
        btnPlayPause.isEnabled = track != null
        btnNext.isEnabled = track != null
        btnPrevious.isEnabled = track != null

        val favourite = track?.let { t ->
            runCatching { music.backend?.library?.isFavorite(t.id) }.getOrNull()
        } ?: false
        btnFavorite.setImageResource(
            if (favourite) R.drawable.ic_heart_filled else R.drawable.ic_heart
        )
        btnFavorite.setColorFilter(
            getColor(if (favourite) R.color.accent else R.color.text_secondary)
        )

        btnShuffle.setColorFilter(
            getColor(if (state.shuffle) R.color.accent else R.color.text_secondary)
        )
        btnRepeat.setColorFilter(
            getColor(
                if (state.repeatMode == RepeatMode.OFF) R.color.text_secondary else R.color.accent
            )
        )
        btnRepeat.contentDescription = getString(
            if (state.repeatMode == RepeatMode.ONE) R.string.music_repeat_one else R.string.music_repeat
        )

        val error = state.error
        if (error != null) {
            // Errors go where the subtitle was, so the transport never moves.
            trackSubtitle.text = describeError(error)
        }
    }

    /** Album and year, from the track's own tags; hidden when untagged. */
    private fun renderMeta(track: Track) {
        val parts = buildList {
            track.album?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (track.year > 0) add(track.year.toString())
        }
        if (parts.isEmpty()) {
            trackMeta.visibility = View.GONE
        } else {
            trackMeta.text = parts.joinToString(" · ")
            trackMeta.visibility = View.VISIBLE
        }
    }

    /**
     * The design's `FLAC` badge, driven by the file's own type.
     *
     * Only shown for a lossless container, and only when the engine actually
     * reported one. A track with no MIME type says nothing rather than assuming
     * a format — the badge is a claim about the audio, so it must be true.
     */
    private fun renderCodecBadge(track: Track) {
        val label = when (track.mimeType?.lowercase()) {
            "audio/flac", "audio/x-flac" -> "FLAC"
            "audio/wav", "audio/x-wav", "audio/wave" -> "WAV"
            else -> null
        }
        if (label == null) {
            codecBadge.visibility = View.GONE
        } else {
            codecBadge.text = label
            codecBadge.visibility = View.VISIBLE
        }
    }

    private fun describeError(error: com.ts8.core.music.PlaybackError): String =
        getString(
            when (error) {
                com.ts8.core.music.PlaybackError.SOURCE_UNAVAILABLE -> R.string.music_usb_removed
                com.ts8.core.music.PlaybackError.UNSUPPORTED_FORMAT -> R.string.qs_unavailable
                com.ts8.core.music.PlaybackError.DECODE_FAILED -> R.string.generic_error
                com.ts8.core.music.PlaybackError.AUDIO_FOCUS_LOST -> R.string.music_pause
                com.ts8.core.music.PlaybackError.STREAM_UNAVAILABLE ->
                    R.string.music_stream_unavailable
                com.ts8.core.music.PlaybackError.STREAM_INTERRUPTED ->
                    R.string.music_stream_interrupted
                com.ts8.core.music.PlaybackError.UNKNOWN -> R.string.generic_error
            }
        )

    // -- artwork -------------------------------------------------------------

    private fun targetPx(): Int =
        resources.getDimensionPixelSize(R.dimen.music_artwork_large)

    private fun loadCover(track: Track) {
        val cached = artLoader.load(track.albumId) { albumId, bitmap ->
            if (playingTrackId != null && track.albumId == albumId) showArtwork(bitmap)
        }
        if (cached != null) showArtwork(cached) else showArtworkPlaceholder()
    }

    private fun showArtwork(bitmap: android.graphics.Bitmap) {
        artwork.setImageBitmap(bitmap)
        artwork.visibility = View.VISIBLE
        artworkPlaceholder.visibility = View.GONE
    }

    private fun showArtworkPlaceholder() {
        artwork.setImageDrawable(null)
        artwork.visibility = View.GONE
        artworkPlaceholder.visibility = View.VISIBLE
    }

    // -- transport -----------------------------------------------------------

    private fun wireTransport() {
        btnPlayPause.setOnClickListener { music.toggle() }
        btnNext.setOnClickListener { music.next() }
        btnPrevious.setOnClickListener { music.previous() }
        btnShuffle.setOnClickListener { music.setShuffle(!music.state().shuffle) }
        btnRepeat.setOnClickListener { music.setRepeatMode(music.state().repeatMode.next()) }
        btnFavorite.setOnClickListener {
            val track = music.state().track ?: return@setOnClickListener
            val library = music.backend?.library ?: return@setOnClickListener
            main.post {
                val nowFavourite = !library.isFavorite(track.id)
                library.setFavorite(track.id, nowFavourite)
                renderPlayback(music.state())
            }
        }
    }

    /**
     * A seek bar that does not fight the driver.
     *
     * `onProgressChanged` fires for programmatic updates too, so the seek is
     * only issued `fromUser`. Without the `scrubbing` guard, the player's own
     * position push would move the thumb back mid-drag and the drag would
     * become unusable.
     */
    private fun wireSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val duration = effectiveDuration()
                if (duration <= 0L) return
                positionText.text =
                    ClockFormat.trackPosition((progress.toLong() * duration) / SEEK_MAX)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                scrubbing = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                scrubbing = false
                val duration = effectiveDuration()
                if (duration > 0L) {
                    music.seekTo((bar.progress.toLong() * duration) / SEEK_MAX)
                }
            }
        })
    }

    private fun effectiveDuration(): Long {
        val state = music.state()
        return if (state.durationMs > 0) state.durationMs else state.track?.durationMs ?: 0L
    }

    // -- search: local and online (§13) --------------------------------------

    /**
     * Wires the single field that drives both catalogues.
     *
     * **Why one field and not two searches.** A driver in a car types once. The
     * app then asks the local library and every configured provider for the
     * same words and shows the answers as two labelled groups, `Trên thiết bị`
     * and `Trực tuyến`. Two separate search boxes would make the driver decide
     * which catalogue has a song before they know the answer — which is
     * exactly the question the search was supposed to answer.
     *
     * **Why the debounce differs from the online call's own.** The local query
     * is a SQLite read and runs on every keystroke at once; the online call is
     * a network round trip per provider and waits [ONLINE_DEBOUNCE_MS]. Firing
     * a round trip per character would be four providers × six characters on a
     * phone hotspot.
     */
    private fun wireSearch() {
        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString()?.trim().orEmpty()
                main.removeCallbacks(searchDebounce)
                if (text.isEmpty()) {
                    searching = false
                    onlineRows = emptyMap()
                    loadLibrary()
                    return
                }
                searching = true
                // Local results are immediate: they are already on disk, and a
                // driver should see their own music the moment they type its
                // name rather than after a network timeout.
                showLocalMatches(text)
                main.postDelayed(searchDebounce, ONLINE_DEBOUNCE_MS)
            }
        })
        searchField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                main.removeCallbacks(searchDebounce)
                runOnlineSearch()
                true
            } else {
                false
            }
        }
    }

    /**
     * Renders the local matches at once, with a "searching online" hint below.
     *
     * The hint is a row, not a spinner: it is the honest statement that a
     * second group is still coming, and it keeps the list from jumping when the
     * online rows arrive (the heading and the placeholder row are replaced
     * rather than inserted).
     */
    private fun showLocalMatches(query: String) {
        val library = music.backend?.library
        val generation = ++loadGeneration
        if (library == null) {
            showLoading(true)
            return
        }
        graph.io().execute {
            val tracks = runCatching { library.searchTracks(query, PAGE_SIZE) }.getOrDefault(emptyList())
            main.post {
                if (generation != loadGeneration || !searching) return@post
                val local = tracks.map { MusicRowAdapter.trackRow(it, it.id == playingTrackId) }
                renderSearch(local, online = emptyList(), pending = true, query = query)
            }
        }
    }

    /** Kicks the online half of the search. */
    private fun runOnlineSearch() {
        val query = searchField.text?.toString()?.trim().orEmpty()
        if (query.isEmpty() || !searching) return
        val generation = loadGeneration
        showOnlineLoading(true)
        graph.onlineMusic.search(query) { snapshot ->
            if (!searching || generation != loadGeneration) return@search
            showOnlineLoading(false)
            onlineRows = snapshot.tracks.associateBy {
                "o:${it.providerId}:${it.id}"
            }
            // Re-run the local half so the two groups are rendered from one
            // consistent pass: the driver may have typed more since the local
            // query above, and a local list from an older prefix beside an
            // online list from the current one is a list that disagrees with
            // itself.
            val library = music.backend?.library
            if (library == null) {
                renderSearch(emptyList(), snapshot.tracks, pending = false, query = query,
                    failure = snapshot.failure)
                return@search
            }
            graph.io().execute {
                val tracks = runCatching { library.searchTracks(query, PAGE_SIZE) }
                    .getOrDefault(emptyList())
                main.post {
                    if (!searching || generation != loadGeneration) return@post
                    renderSearch(
                        local = tracks.map {
                            MusicRowAdapter.trackRow(it, it.id == playingTrackId)
                        },
                        online = snapshot.tracks,
                        pending = false,
                        query = query,
                        failure = snapshot.failure
                    )
                }
            }
        }
    }

    /**
     * Draws the two groups, with a heading for each that has content.
     *
     * A group with no results and no failure is *omitted*, not drawn empty: an
     * empty `Trực tuyến` heading under a working local list would read as "the
     * internet found nothing", which the app does not know. A group with a
     * failure, however, is drawn with the reason — that is the case where the
     * driver must be told the search did not happen.
     */
    private fun renderSearch(
        local: List<MusicRowAdapter.Row>,
        online: List<com.ts8.core.music.online.OnlineTrack>,
        pending: Boolean,
        query: String,
        failure: String? = null
    ) {
        val rows = ArrayList<MusicRowAdapter.Row>(local.size + online.size + 4)
        providerList.visibility = View.GONE
        if (local.isNotEmpty()) {
            rows.add(MusicRowAdapter.headerRow(getString(R.string.music_search_local_group)))
            rows.addAll(local)
        }
        val onlineRowsMapped = online.map { MusicRowAdapter.onlineRow(it, providerLabel(it.providerId)) }
        if (onlineRowsMapped.isNotEmpty()) {
            rows.add(MusicRowAdapter.headerRow(getString(R.string.music_search_online_group)))
            rows.addAll(onlineRowsMapped)
        }

        val message = when {
            rows.isNotEmpty() -> null
            pending -> getString(R.string.music_online_loading)
            failure != null -> failure
            else -> getString(R.string.music_search_empty, query)
        }

        if (message != null) {
            currentRows = emptyList()
            adapter.submit(emptyList())
            libraryList.visibility = View.GONE
            libraryEmpty.visibility = View.VISIBLE
            libraryEmptyText.text = message
            libraryEmptyHint.visibility = View.GONE
        } else {
            currentRows = rows
            adapter.submit(rows)
            libraryList.visibility = View.VISIBLE
            libraryEmpty.visibility = View.GONE
        }
        libraryFooter.visibility = View.GONE
        applyTabVisibility(!searching)
    }

    /**
     * The provider's name for a result.
     *
     * **Why `subsonic` is no longer hard-coded to Navidrome.** The id names the
     * *protocol* family, and §12 lets the driver pick either Navidrome or
     * Jellyfin behind it. A label that always said "Navidrome" therefore told a
     * Jellyfin user their results came from a server they do not run — the same
     * class of fabricated fact as a made-up temperature. The configured kind is
     * read here so the label follows the setting; the runtime's own
     * `providers()` remains the source for the *source list*, which needs the
     * configuration state as well as the name.
     */
    private fun providerLabel(providerId: String): String = when (providerId) {
        "radio-browser" -> "Đài phát thanh"
        "podcast" -> "Podcast"
        "subsonic" -> if (graph.settings.onlineServerKind == "JELLYFIN") "Jellyfin" else "Navidrome"
        else -> providerId
    }

    private fun showOnlineLoading(loading: Boolean) {
        // The footer is where a one-line status belongs: it does not move the
        // list, and it disappears the moment the answer arrives.
        if (loading) {
            libraryFooter.text = getString(R.string.music_online_loading)
            libraryFooter.visibility = View.VISIBLE
        } else if (!searching) {
            libraryFooter.visibility = View.GONE
        }
    }

    /** Tabs are meaningless while a query is showing; the field is the mode. */
    private fun applyTabVisibility(visible: Boolean) {
        // ...and they are meaningless under `Trực tuyến` as well, which draws
        // provider cards rather than a tabbed library. Both conditions are
        // checked here so a search that finishes after the driver switched
        // source cannot bring the tabs back over the provider list.
        val shown = visible && source != Source.ONLINE
        val visibility = if (shown) View.VISIBLE else View.INVISIBLE
        tabs.values.forEach { it.visibility = visibility }
    }

    // -- library -------------------------------------------------------------

    private fun buildList() {
        adapter = MusicRowAdapter(artLoader)
        adapter.onRowClick = { row -> onRowClicked(row) }
        libraryList.layoutManager = LinearLayoutManager(this)
        libraryList.adapter = adapter
        // A row height is fixed, so the list can skip a measure pass per scroll
        // frame — worth doing on this CPU.
        libraryList.setHasFixedSize(true)
    }

    private fun wireTabs() {
        tabs = mapOf(
            Tab.PLAYLIST to findViewById(R.id.tabPlaylist),
            Tab.SONGS to findViewById(R.id.tabSongs),
            Tab.ALBUMS to findViewById(R.id.tabAlbums),
            Tab.ARTISTS to findViewById(R.id.tabArtists)
        )
        tabs.forEach { (tab, view) ->
            view.setOnClickListener {
                this.tab = tab
                applyTabSelection()
                loadLibrary()
            }
        }
        applyTabSelection()
    }

    private fun applyTabSelection() {
        tabs.forEach { (t, view) -> view.isSelected = t == tab }
    }

    // -- sources: Trên thiết bị | USB | Trực tuyến (§7) -----------------------

    /**
     * Wires the source row.
     *
     * **Why selecting a source does not stop playback or rebuild the player.**
     * §7 keeps one `MusicPlaybackService` and one playback state; the source is
     * a view over the library, so switching it changes what the right column
     * lists and nothing about what is playing. A driver browsing the stick
     * while a track from internal storage plays keeps hearing it.
     */
    private fun wireSources() {
        sourceViews = mapOf(
            Source.DEVICE to findViewById(R.id.sourceDevice),
            Source.USB to findViewById(R.id.sourceUsb),
            Source.ONLINE to findViewById(R.id.sourceOnline)
        )
        sourceViews.forEach { (s, view) ->
            view.setOnClickListener {
                if (source == s) return@setOnClickListener
                source = s
                // A query belongs to the source that was showing when it was
                // typed. Leaving the text in the field over a different
                // catalogue would show `Trên thiết bị` rows under a `USB`
                // heading, so the field is cleared and the tabs come back.
                if (searchField.text?.isNotEmpty() == true) searchField.setText("")
                applySourceSelection()
                loadLibrary()
            }
        }
        applySourceSelection()
    }

    private fun applySourceSelection() {
        sourceViews.forEach { (s, view) -> view.isSelected = s == source }

        val online = source == Source.ONLINE
        // The tabs describe the local catalogues; under `Trực tuyến` they would
        // offer `Album` and `Nghệ sĩ` over a provider list, which are not things
        // it has. The search field stays enabled in every source — §13's whole
        // point is that one query reaches both catalogues and the answer is
        // labelled per group, so disabling it here would remove the only way to
        // search online at all.
        tabs.values.forEach { it.visibility = if (online) View.INVISIBLE else View.VISIBLE }
        searchField.isEnabled = true
        if (online) hideKeyboard()
        renderProviders()
    }

    /**
     * Draws the online source list, one card per provider.
     *
     * **Why every provider is drawn, configured or not.** §7: a provider with
     * no configuration must still be visible with a `Thiết lập` CTA, never
     * silently absent. An unconfigured source that disappeared would leave the
     * driver unable to discover that the app could play podcasts at all.
     *
     * **Why the CTA is a chip and not the whole row.** Tapping a configured
     * provider browses it; tapping the whole row of an unconfigured one would
     * give no way to also read what it needs. The state line names the missing
     * setting, taken from the runtime's own answer rather than composed here.
     */
    private fun renderProviders() {
        if (source != Source.ONLINE) {
            providerList.visibility = View.GONE
            return
        }
        providerList.removeAllViews()
        val runtime = graph.onlineMusic
        for (info in runtime.providers()) {
            providerList.addView(providerCard(info, runtime.onlineEnabled()))
        }
        providerList.visibility = View.VISIBLE
        // The library widgets underneath are hidden rather than cleared: their
        // adapter still holds rows, and clearing it would throw away the diff
        // state the local tabs will need when the driver switches back.
        libraryList.visibility = View.GONE
        libraryEmpty.visibility = View.GONE
        libraryLoading.visibility = View.GONE
        libraryFooter.visibility = View.GONE
    }

    private fun providerCard(
        info: com.ts8.launcher.runtime.OnlineMusicRuntime.ProviderInfo,
        onlineEnabled: Boolean
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@MusicFullscreenActivity, R.drawable.bg_card)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        labels.addView(TextView(this).apply {
            text = info.displayName
            setTextColor(ContextCompat.getColor(this@MusicFullscreenActivity, R.color.text_primary))
            textSize = 15f
        })
        labels.addView(TextView(this).apply {
            // The state is one of three sentences, each of which is true:
            // switched off entirely, missing a named setting, or configured.
            text = when {
                !onlineEnabled -> getString(R.string.music_search_offline)
                info.configured -> getString(R.string.music_source_configured)
                info.missing != null -> getString(R.string.music_source_missing, info.missing)
                else -> getString(R.string.music_source_not_configured)
            }
            setTextColor(
                ContextCompat.getColor(
                    this@MusicFullscreenActivity,
                    if (info.configured && onlineEnabled) R.color.text_tertiary else R.color.text_secondary
                )
            )
            textSize = 12f
        })
        card.addView(labels)

        val needsSetup = !info.configured || !onlineEnabled
        card.addView(TextView(this).apply {
            text = getString(
                if (needsSetup) R.string.music_source_setup else R.string.music_search_open
            )
            setTextColor(ContextCompat.getColor(this@MusicFullscreenActivity, R.color.text_primary))
            textSize = 13f
            background = ContextCompat.getDrawable(this@MusicFullscreenActivity, R.drawable.bg_chip)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (needsSetup) {
                    openOnlineSetup()
                } else {
                    // Configured: fill the field with the provider's own name
                    // and run a search, because there is nothing else a source
                    // row can do until the driver names something.
                    searchField.setText(info.displayName)
                }
            }
        })
        return card
    }

    /**
     * Opens Settings on the online section.
     *
     * Settings is a full screen rather than a dialog on this build, and it is
     * opened with `REORDER_TO_FRONT` so a driver who already has it in the
     * stack returns to it instead of stacking a second copy. The section is
     * not scrolled to programmatically — the online card is the third block on
     * a 600 px panel and is reachable in one short swipe, and a scroll request
     * fired from another Activity is the kind of cross-screen coupling that
     * breaks when a section is reordered.
     */
    private fun openOnlineSetup() {
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun loadLibrary() {
        // `Trực tuyến` is not a slice of the local library, so there is nothing
        // to query. The provider cards are the content; they come from settings
        // and need no thread.
        if (source == Source.ONLINE) {
            renderProviders()
            return
        }
        val backend = music.backend
        val generation = ++loadGeneration
        if (backend == null) {
            // The service has not connected yet. Say "loading" rather than
            // "empty": the difference matters, one of them is a false claim.
            showLoading(true)
            return
        }
        val library = backend.library
        val tabAtStart = tab
        val sourceAtStart = source
        showLoading(true)
        graph.io().execute {
            val loaded = runCatching {
                val kind = sourceAtStart.storageKind()
                // Both numbers in one pass: the footer is a count and a sum,
                // and two separate trips to SQLite to build one line is a
                // round trip the panel does not need.
                Batch(
                    rows = queryTab(library, tabAtStart, kind),
                    trackCount = library.sourceCount(kind),
                    totalMs = 0L
                )
            }
            main.post {
                if (generation != loadGeneration) return@post
                showLoading(false)
                loaded.onSuccess { batch ->
                    renderRows(batch.rows)
                    renderFooter(batch.trackCount, batch.totalMs)
                }.onFailure { renderLibraryFailure() }
            }
        }
    }

    /**
     * The storage kind behind a local source.
     *
     * `DEVICE` is internal storage — `MEDIA_STORE` on this schema — and `USB`
     * is the stick. A screen only ever reaches here with one of the two; the
     * online case returns early in [loadLibrary].
     */
    private fun Source.storageKind(): Track.SourceKind =
        if (this == Source.USB) Track.SourceKind.USB else Track.SourceKind.MEDIA_STORE

    /** Everything one library load produces, computed once on the IO thread. */
    private class Batch(val rows: List<MusicRowAdapter.Row>, val trackCount: Int, val totalMs: Long)

    private fun renderFooter(trackCount: Int, totalMs: Long) {
        if (trackCount <= 0) {
            libraryFooter.visibility = View.GONE
            return
        }
        libraryFooter.text = if (totalMs > 0L) {
            getString(
                R.string.music_library_footer,
                trackCount,
                ClockFormat.duration((totalMs / 1000).toInt())
            )
        } else {
            // No duration was queried for this source: show only what is known
            // rather than a fabricated "0 giờ 00 phút".
            getString(R.string.music_track_count, trackCount)
        }
        libraryFooter.visibility = View.VISIBLE
    }

    /**
     * The query for one tab, restricted to one storage kind. Always called on
     * an IO thread.
     *
     * **Why the kind is a parameter and not read from the field.** This runs on
     * the IO thread while the driver can still tap another source; reading
     * [source] here would let a query issued for `USB` finish under a `Trên
     * thiết bị` selection. The caller captures the source before dispatching
     * and posts the answer only if that source is still the one on screen.
     *
     * **What the footer counts.** `trackCount` and `totalDurationMs` used to be
     * the whole library regardless of source — a `USB` list of three songs
     * under a footer reading "248 bài". The count is now the selected source's
     * own; the duration is left at 0 and the footer falls back to the count
     * alone, because a second aggregate query per tab switch buys a number
     * nobody reads.
     */
    private fun queryTab(
        library: MusicLibrary,
        tab: Tab,
        kind: Track.SourceKind
    ): List<MusicRowAdapter.Row> {
        val countLabel: (Int) -> String = { getString(R.string.music_track_count, it) }
        return when (tab) {
            Tab.SONGS -> library.tracksBySource(kind, 0, PAGE_SIZE).map { track ->
                MusicRowAdapter.trackRow(track, playing = track.id == playingTrackId)
            }

            Tab.PLAYLIST -> {
                // "Danh sách phát" is the queue that is actually loaded, which is
                // what the design's highlighted row means. The player exposes it
                // by state, so the current track plus recently played is the
                // honest content until a persisted queue exists.
                //
                // Filtered in memory rather than in SQL: `recentlyPlayed` is an
                // indexed query capped at PAGE_SIZE, so this is a bounded array,
                // and a third source-aware SQL variant would be a third place
                // the predicate lives for a list of at most 500.
                library.recentlyPlayed(PAGE_SIZE)
                    .filter { it.sourceKind == kind }
                    .map { track ->
                        MusicRowAdapter.trackRow(track, playing = track.id == playingTrackId)
                    }
            }

            Tab.ALBUMS -> library.albumsBySource(kind)
                .map { MusicRowAdapter.collectionRow(it, countLabel) }

            Tab.ARTISTS -> library.artistsBySource(kind)
                .map { MusicRowAdapter.collectionRow(it, countLabel) }
        }
    }

    private fun renderRows(rows: List<MusicRowAdapter.Row>) {
        currentRows = rows
        adapter.submit(rows)
        providerList.visibility = View.GONE
        if (rows.isEmpty()) {
            libraryList.visibility = View.GONE
            libraryEmpty.visibility = View.VISIBLE
            // "Nothing here" differs by tab: an empty album list on a unit whose
            // songs did load is not the same statement as an empty library, so
            // the hint is only offered where adding music would help.
            libraryEmptyText.setText(R.string.music_empty)
            // And it differs by source: an empty `USB` list is not "you have no
            // music", it is "the stick you selected has none", which the hint
            // about copying music to internal storage would contradict.
            libraryEmptyHint.visibility = if (
                (tab == Tab.SONGS || tab == Tab.PLAYLIST) && source != Source.USB
            ) View.VISIBLE else View.GONE
        } else {
            libraryList.visibility = View.VISIBLE
            libraryEmpty.visibility = View.GONE
        }
    }

    private fun renderLibraryFailure() {
        adapter.submit(emptyList())
        providerList.visibility = View.GONE
        libraryList.visibility = View.GONE
        libraryEmpty.visibility = View.VISIBLE
        // A failed query is not "no music"; the storage may be unreadable.
        libraryEmptyText.setText(R.string.music_source_unavailable)
        libraryEmptyHint.visibility = View.GONE
    }

    private fun showLoading(loading: Boolean) {
        libraryLoading.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) {
            libraryEmpty.visibility = View.GONE
        }
    }
    /**
     * Rebuilds only the playing flags, without re-querying.
     *
     * Called on every state push — that is, once a second during playback.
     * A full reload per second would be a SQLite query per second for a change
     * that is one boolean per row.
     */
    private fun refreshPlayingRow(trackId: Long) {
        val updated = currentRows.map { row ->
            val playing = row.track?.id == trackId
            if (playing == row.playing) row else row.copy(playing = playing)
        }
        if (updated != currentRows) {
            currentRows = updated
            adapter.submit(updated)
        }
    }

    private fun onRowClicked(row: MusicRowAdapter.Row) {
        // A heading is not a target; the click listener is disabled on it too,
        // but a keyboard/d-pad activation can still reach the callback.
        if (row.header) return
        val collection = row.collection
        if (collection != null) {
            openCollection(collection)
            return
        }
        val track = row.track
        if (track != null) {
            playLocalFromRow(track)
            return
        }
        // An online row: its payload is the provider's own track, held in
        // `onlineRows`. Resolving it costs a network round trip, so it happens
        // here, on tap, and not during the bind.
        val online = onlineRows[row.key] ?: return
        playOnline(online)
    }

    private fun playLocalFromRow(track: Track) {
        // Play the queue as the list shows it, from the tapped row, so "next"
        // means the next row on screen rather than the next track in the album.
        val queue = currentRows.mapNotNull { it.track }
        val startIndex = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        if (queue.isEmpty()) {
            music.setQueue(listOf(track), 0)
        } else {
            music.setQueue(queue, startIndex)
        }
    }

    /**
     * Plays one search result from an online provider.
     *
     * **Why this is not `setQueue` of the whole online group.** Every item in
     * that group would need its own resolve call before the player could open
     * the first one, which is four to thirty network requests to play a single
     * station. A single-item queue is what the driver tapped, and the local
     * list beside it stays playable through its own rows.
     *
     * **Why the resolve happens on the IO pool and the queue is set on main.**
     * `toTrack` blocks on the provider; `setQueue` must run where the player
     * expects it. Doing the resolve on the main thread would freeze the screen
     * for the connect timeout of a dead host.
     */
    private fun playOnline(online: com.ts8.core.music.online.OnlineTrack) {
        val runtime = graph.onlineMusic
        showOnlineLoading(true)
        graph.io().execute {
            val track = runtime.toTrack(online)
            main.post {
                showOnlineLoading(false)
                if (track == null) {
                    // The provider's own sentence is the honest one; the
                    // gateway already produced it and there is no better
                    // wording to invent here.
                    libraryFooter.text = getString(R.string.music_stream_unavailable)
                    libraryFooter.visibility = View.VISIBLE
                    return@post
                }
                music.setQueue(listOf(track), 0)
            }
        }
    }

    /** Drills into an album or artist by replacing the list with its tracks. */
    private fun openCollection(collection: MusicCollection) {
        val library = music.backend?.library ?: return
        val generation = ++loadGeneration
        showLoading(true)
        graph.io().execute {
            val tracks = runCatching {
                when (collection.kind) {
                    MusicCollection.Kind.ALBUM -> library.tracksInAlbum(collection.id)
                    MusicCollection.Kind.ARTIST -> library.tracksByArtist(collection.id)
                    MusicCollection.Kind.FOLDER -> library.tracksInFolder(collection.name)
                    else -> emptyList()
                }
            }.getOrDefault(emptyList())
            main.post {
                if (generation != loadGeneration) return@post
                showLoading(false)
                renderRows(
                    tracks.map { MusicRowAdapter.trackRow(it, it.id == playingTrackId) }
                )
            }
        }
    }

    companion object {
        /** 500 rows per page: enough to scroll a while, small enough to bind
         *  without a visible stall on this CPU. */
        private const val PAGE_SIZE = 500

        private const val SEEK_MAX = 1000

        /**
         * How long typing must pause before the online half of a search runs.
         *
         * 400 ms is long enough that `cầu rồng` is one request and not nine,
         * short enough that a driver who stopped typing is not left waiting.
         * The local half runs on every keystroke regardless — it is a SQLite
         * read and there is nothing to gain by delaying it.
         */
        private const val ONLINE_DEBOUNCE_MS = 400L
    }
}

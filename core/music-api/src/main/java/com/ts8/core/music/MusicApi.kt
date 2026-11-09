package com.ts8.core.music

/**
 * The music side of the launcher, as the UI sees it.
 *
 * The interface exists so the UI never knows whether tracks come from
 * MediaStore, a USB stick, or a network provider. It is also the seam a future
 * online provider plugs into without the UI changing.
 */
interface MusicLibrary {
    /** True once at least one scan has completed. */
    fun isReady(): Boolean

    /** Total tracks known, without loading them. */
    fun trackCount(): Int

    /** Tracks matching a free-text query, capped at [limit]. */
    fun searchTracks(query: String, limit: Int = 100): List<Track>

    fun tracksInAlbum(albumId: Long): List<Track>

    fun tracksByArtist(artistId: Long): List<Track>

    fun tracksInFolder(folder: String): List<Track>

    fun albums(): List<MusicCollection>

    fun artists(): List<MusicCollection>

    fun folders(): List<MusicCollection>

    fun playlists(): List<Playlist>

    fun playlistTracks(playlistId: Long): List<Track>

    fun favorites(): List<Track>

    fun recentlyPlayed(limit: Int = 50): List<Track>

    /** All tracks, ordered for browsing. Pageable so the UI never loads 10k rows. */
    fun allTracks(offset: Int = 0, limit: Int = 200): List<Track>

    /**
     * Tracks that came from one storage kind, paged.
     *
     * §7 makes `USB` a source a driver selects rather than a property they
     * discover: a unit with a 64 GB stick and 8 GB of internal storage shows
     * two different libraries, and one merged list makes the driver scroll
     * past internal music to reach the stick they just plugged in.
     *
     * **Why this is a query and not a filter over [allTracks].** Filtering in
     * memory after a `LIMIT` returns a page whose size depends on how the two
     * sources happen to interleave — a USB-only screen would show three rows
     * when the page held three USB tracks among 200. The predicate belongs in
     * the SQL, where `source_kind` is already indexed by the table's own
     * scan order.
     */
    fun tracksBySource(
        kind: Track.SourceKind,
        offset: Int = 0,
        limit: Int = 200
    ): List<Track>

    /** How many tracks came from one storage kind, without loading them. */
    fun sourceCount(kind: Track.SourceKind): Int

    /**
     * Albums that have at least one track from one storage kind.
     *
     * §7's `USB` is a source selector, not a filter chip: a driver who picks
     * it is asking to see the stick, and an album list drawn from the whole
     * library would list albums whose tracks the list beside it cannot show.
     * Sized for the panel — an albums tab is not paged today and does not need
     * to be.
     */
    fun albumsBySource(kind: Track.SourceKind): List<MusicCollection>

    /** Artists that have at least one track from one storage kind. */
    fun artistsBySource(kind: Track.SourceKind): List<MusicCollection>

    fun track(id: Long): Track?

    // -- mutations ---------------------------------------------------------

    /** Creates a playlist; returns its new id. */
    fun createPlaylist(name: String): Long

    fun addToPlaylist(playlistId: Long, trackId: Long): Boolean

    fun removeFromPlaylist(playlistId: Long, trackId: Long): Boolean

    fun deletePlaylist(playlistId: Long)

    fun setFavorite(trackId: Long, favorite: Boolean)

    fun isFavorite(trackId: Long): Boolean

    /** Records that a track started playing, for the "recent" list. */
    fun recordPlayed(trackId: Long, atMs: Long)

    /** Total duration of everything, in milliseconds — used by the UI footer. */
    fun totalDurationMs(): Long
}

/**
 * Playback control. Implemented by the engine and driven by the service.
 *
 * All methods are safe to call from the main thread; the engine moves heavy
 * work off it. None of them throw when there is nothing loaded — they are
 * no-ops or return false, because a head unit must never crash because the
 * driver tapped next on an empty queue.
 */
interface MusicPlayer {
    fun state(): PlaybackState

    /** Plays the given queue starting at [startIndex]. */
    fun setQueue(tracks: List<Track>, startIndex: Int = 0, autoPlay: Boolean = true)

    fun play()

    fun pause()

    fun togglePlayPause()

    fun next()

    fun previous()

    /** Seeks within the current track. Out-of-range values are clamped. */
    fun seekTo(positionMs: Long)

    fun setShuffle(enabled: Boolean)

    fun setRepeatMode(mode: RepeatMode)

    /**
     * Saves the resume point: current track id and position. Called on
     * Activity stop and on audio focus loss, and read back at boot so the car
     * resumes where it left off rather than at the first track.
     */
    fun saveCheckpoint()

    /** Restores the last saved checkpoint, without auto-playing. */
    fun restoreCheckpoint()

    /** Releases the player and audio focus. */
    fun release()

    /**
     * Attaches a resolver that turns a [Track.SourceKind.ONLINE] track's
     * `source` into a URL the platform player may open.
     *
     * **Why the player cannot do this itself.** Resolving a stream is a network
     * call to a provider, and this module knows no provider — by design, so the
     * local engine has no dependency on an online catalogue. The app hands one
     * in when there is one and leaves it null when there is not, and a player
     * with no resolver reports
     * [PlaybackError.STREAM_UNAVAILABLE] for an online track rather than
     * handing `MediaPlayer` a `null` and dying.
     *
     * **Why it is a call, not a constructor argument.** The service binds the
     * player before the online gateway may exist, and the gateway can be
     * reconfigured (a server address entered) without the player being rebuilt.
     *
     * The resolver runs on the player's own background path and must block; it
     * is called at most once per track open, and only when the source is not
     * already an `http(s)` URL.
     */
    fun setStreamResolver(resolver: StreamResolver?) = Unit
}

/**
 * Turns an online track into a playable URL.
 *
 * Deliberately a one-method interface returning a reason string rather than
 * throwing: a radio station that went offline between search and playback is
 * an ordinary event in a car, and a player that has to catch an exception to
 * read it would end up with a `catch (Throwable)` that swallows real bugs.
 */
fun interface StreamResolver {
    /**
     * The URL to open, or a Vietnamese reason it could not be produced.
     *
     * Called off the main thread. Null [track.source] values do not reach here —
     * the player refuses before asking.
     */
    fun resolve(track: Track): StreamResolution
}

/** Result of a [StreamResolver] call. */
sealed class StreamResolution {
    /** Open this URL; [mimeType] and [durationMs] refine the player's view. */
    data class Playable(
        val url: String,
        val mimeType: String? = null,
        /** 0 for a live stream with no known end. */
        val durationMs: Long = 0L,
        /** True when the stream has no end and the player should not expect EOF. */
        val isLive: Boolean = false
    ) : StreamResolution()

    /** The provider could not produce a URL. [reason] is Vietnamese and shown. */
    data class Unavailable(
        val reason: String,
        /** True when a later retry could succeed (network), false when not. */
        val retryable: Boolean = true
    ) : StreamResolution()
}

/** Listener for playback changes. All callbacks arrive on the main thread. */
interface PlaybackListener {
    fun onStateChanged(state: PlaybackState)
    fun onError(error: PlaybackError, track: Track?)
}

/** Observable side of [MusicPlayer]. */
interface MusicPlayerObservable {
    fun addListener(listener: PlaybackListener)
    fun removeListener(listener: PlaybackListener)
}

/** Everything the music feature needs, in one bundle for DI. */
interface MusicBackend {
    val library: MusicLibrary
    val player: MusicPlayer
}

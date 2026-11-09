package com.ts8.core.music

import com.ts8.core.map.GeoPoint

/**
 * A playable audio item.
 *
 * The model is deliberately flat: on a head unit the whole library is paged
 * from SQLite, and a nested object graph costs allocations the device cannot
 * spare. Albums and artists are [id] references plus a display name.
 */
data class Track(
    /** Stable local id (MediaStore id, or a hash for USB files). Not a path. */
    val id: Long,
    /** Absolute path or content URI, as the engine needs it to open the file. */
    val source: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumId: Long?,
    val artistId: Long?,
    /** Duration in milliseconds; 0 when unknown. */
    val durationMs: Long,
    /** Folder this file lives in, for the folder browser. */
    val folder: String?,
    /** Track number within its album, 0 when untagged. */
    val trackNumber: Int,
    /** Release year, 0 when unknown. */
    val year: Int,
    /** Audio MIME type, e.g. "audio/mpeg". */
    val mimeType: String?,
    /** File size in bytes, used for incremental rescan. */
    val sizeBytes: Long,
    /** Last modification time in epoch seconds, used for incremental rescan. */
    val modifiedSeconds: Long,
    /** Where this track came from — internal storage, a USB volume, or a provider. */
    val sourceKind: SourceKind,

    /**
     * The online provider that issued this track, or null for a local file.
     *
     * **Why the id and not a reference.** The player must be able to persist a
     * queue and hand it back without holding a provider object; and a queue
     * restored from a build that had a provider this one does not must degrade
     * to "nguồn không còn" rather than crash. A string is the only form that
     * survives both. It is the same value as
     * [com.ts8.core.music.online.OnlineTrack.providerId], which is what lets
     * `getPlayableSource` be re-issued for a track whose URL expired.
     *
     * Null, not an empty string, for a local file: a local track genuinely has
     * no provider and a sentinel would be a value the UI has to special-case.
     */
    val providerId: String? = null
) {
    enum class SourceKind {
        MEDIA_STORE,

        USB,

        /**
         * A network stream from an online provider, or one whose URL is
         * resolved lazily.
         *
         * **Why this lives on the same type as a file.** §13 requires one queue
         * that holds both a local file and a radio station without duplicating
         * playback state. The player, the `MediaSession`, the notification and
         * the three screens that draw "now playing" all take a [Track]; giving
         * online items a second parallel type would mean a second queue, a
         * second now-playing row and two answers to "what is playing". So an
         * online item is a [Track] whose [source] is an `http(s)` URL.
         *
         * **What is deliberately absent.** [id] is a hash of the provider id and
         * the provider's own track id, never a library row id — online items are
         * not written to SQLite and are not in the media database. That is why a
         * saved checkpoint drops them on the next boot instead of failing to
         * resolve them.
         */
        ONLINE
    }

    /** Display string for a subtitle line. */
    val subtitle: String
        get() = listOfNotNull(artist?.takeIf { it.isNotBlank() }, album?.takeIf { it.isNotBlank() })
            .joinToString(" · ")
}

/** A collection shown in the music library. */
data class MusicCollection(
    val id: Long,
    val name: String,
    val kind: Kind,
    val trackCount: Int,
    /** Number of tracks, or null when unknown without counting. */
    val artworkUri: String? = null
) {
    enum class Kind { ALBUM, ARTIST, FOLDER, PLAYLIST, FAVORITE, RECENT }
}

/** A user-created playlist. */
data class Playlist(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val createdAtMs: Long
)

/** Repeat mode of the queue. */
enum class RepeatMode {
    OFF,

    /** Restart the queue when the last item finishes. */
    ALL,

    /** Repeat the current track only. */
    ONE;

    fun next(): RepeatMode = when (this) {
        OFF -> ALL
        ALL -> ONE
        ONE -> OFF
    }
}

/** Playback state as the UI renders it. */
data class PlaybackState(
    val track: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedPercent: Int = 0,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    /** True while audio is held down for a navigation announcement. */
    val duckedForNavigation: Boolean = false,
    /** True while paused because a phone call is in progress. */
    val pausedForCall: Boolean = false,
    /** Set when playback stopped for a reason worth telling the driver. */
    val error: PlaybackError? = null,
    /**
     * Where a network stream is in its buffering life cycle.
     *
     * A local file is always [BufferingState.IDLE]: it needs no buffering, and
     * reporting otherwise would make every song show a spinner for its first
     * frame.
     */
    val buffering: BufferingState = BufferingState.IDLE
) {
    val hasTrack: Boolean get() = track != null
    val progressFraction: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/** Why playback stopped. Surfaced in the UI rather than swallowed. */
enum class PlaybackError {
    /** The file disappeared — typically the USB stick was pulled. */
    SOURCE_UNAVAILABLE,

    /** The codec cannot decode this file on this device. */
    UNSUPPORTED_FORMAT,

    /** The file exists but is corrupt or truncated. */
    DECODE_FAILED,

    /** The audio output was taken by another app and not given back. */
    AUDIO_FOCUS_LOST,

    /**
     * A network stream could not be opened at all.
     *
     * Distinct from [SOURCE_UNAVAILABLE] because the two are different
     * sentences on screen and different actions for the driver: a vanished file
     * means "cắm lại USB", a stream that never opened means "kiểm tra kết nối"
     * and is worth a retry button. Collapsing them would tell a driver with a
     * dead modem to go looking for a USB stick.
     */
    STREAM_UNAVAILABLE,

    /** The stream opened and then died mid-playback. */
    STREAM_INTERRUPTED,

    /** Anything the engine cannot classify. */
    UNKNOWN
}

/**
 * Where a playing item is in its buffering life cycle.
 *
 * [com.ts8.core.music.PlaybackState.bufferedPercent] alone cannot express
 * "stalled": a stream at 40 % that stopped receiving data is not buffering
 * forward, and a progress bar that keeps its last value says nothing at all.
 * A separate state is what lets the UI show "Đang tải…" and a spinner rather
 * than a frozen bar.
 */
enum class BufferingState {
    /** Not a stream, or a stream already fully buffered: draw the position. */
    IDLE,

    /** Waiting for the first bytes; the player has not started. */
    BUFFERING,

    /** Playing, but below the point where a hiccup is unlikely. */
    STALLED
}

/**
 * Which audio stream the launcher should take over.
 *
 * Kept as an enum rather than a raw stream constant so the bridge can map it
 * onto whatever the vehicle bus exposes later.
 */
enum class AudioRoute {
    /** Normal playback through the media stream. */
    MEDIA,

    /** Navigation announcements, which must be audible over music. */
    NAVIGATION,

    /** Phone call audio; never used by this app for playback. */
    CALL
}

/** A geographic position attached to a track, for "songs near me". */
data class TrackPlace(val trackId: Long, val point: GeoPoint)

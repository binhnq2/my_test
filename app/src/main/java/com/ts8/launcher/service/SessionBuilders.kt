package com.ts8.launcher.service

import android.content.Context
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.ts8.core.music.PlaybackState as MusicPlaybackState
import com.ts8.core.music.Track

/**
 * Translators between the engine's [MusicPlaybackState] and the two session
 * APIs the platform offers.
 *
 * **Why both.** `MediaSessionCompat` is the portable metadata container and is
 * what the vendor Bluetooth stack reads over AVRCP. The framework
 * `android.media.session.MediaSession` is what the system's own media
 * notification and lock-screen transport read on API 21+. Feeding only the
 * compat session leaves the notification's play/pause dead on some firmware,
 * and feeding only the framework session loses the AVRCP metadata. Both are
 * cheap to update from one state object, so both are kept in step here rather
 * than at every call site.
 *
 * **Why not `MediaSessionCompat` only.** androidx.media 1.7 drops the
 * `PlaybackStateCompat.ACTION_*` to `PlaybackStateCompat` mapping for API 23 in
 * a way that omits `ACTION_SEEK_TO`; declaring the actions explicitly keeps
 * seek available on the notification.
 */
internal object PlaybackStateCompatBuilder {

    /** Bitmask of everything this player can do while a track is loaded. */
    private const val ACTIONS =
        PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_STOP

    fun build(st: MusicPlaybackState): PlaybackStateCompat =
        PlaybackStateCompat.Builder()
            .setActions(if (st.hasTrack) ACTIONS else 0L)
            .setState(stateCode(st), st.positionMs, playbackSpeed(st))
            .build()

    internal fun stateCode(st: MusicPlaybackState): Int = when {
        st.error != null -> PlaybackStateCompat.STATE_ERROR
        st.isPlaying -> PlaybackStateCompat.STATE_PLAYING
        st.hasTrack -> PlaybackStateCompat.STATE_PAUSED
        else -> PlaybackStateCompat.STATE_NONE
    }

    /** 1.0 while playing, 0.0 otherwise: the session API treats any other value
     *  as a speed multiplier and the vendor head unit's progress bar wobbles. */
    internal fun playbackSpeed(st: MusicPlaybackState): Float = if (st.isPlaying) 1f else 0f
}

internal object MediaMetadataCompatBuilder {

    fun build(track: Track?): MediaMetadataCompat {
        val b = MediaMetadataCompat.Builder()
        if (track == null) {
            return b.putString(MediaMetadataCompat.METADATA_KEY_TITLE, "").build()
        }
        b.putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
        b.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist ?: "")
        b.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.album ?: "")
        b.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.durationMs)
        b.putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, track.trackNumber.toLong())
        b.putLong(MediaMetadataCompat.METADATA_KEY_YEAR, track.year.toLong())
        // The media id is the track id as a string: the session uses it to tell
        // "same track, new position" from "different track" when the vendor app
        // queries over AVRCP.
        b.putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, track.id.toString())
        return b.build()
    }
}

internal object FrameworkMetadataBuilder {

    fun build(track: Track?): MediaMetadata {
        val b = MediaMetadata.Builder()
        if (track == null) {
            return b.putString(MediaMetadata.METADATA_KEY_TITLE, "").build()
        }
        b.putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
        b.putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist ?: "")
        b.putString(MediaMetadata.METADATA_KEY_ALBUM, track.album ?: "")
        b.putLong(MediaMetadata.METADATA_KEY_DURATION, track.durationMs)
        b.putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, track.trackNumber.toLong())
        b.putLong(MediaMetadata.METADATA_KEY_YEAR, track.year.toLong())
        b.putString(MediaMetadata.METADATA_KEY_MEDIA_ID, track.id.toString())
        return b.build()
    }
}

internal object FrameworkPlaybackStateBuilder {

    /**
     * Framework state. It needs a real [PendingIntent] for the "now playing"
     * tap, and `setActions` must be non-zero or the transport buttons render
     * disabled — which is the specific symptom this object exists to prevent.
     */
    fun build(st: MusicPlaybackState, context: Context): PlaybackState {
        val actions = if (!st.hasTrack) 0L else
            (PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_STOP)

        val code = when {
            st.error != null -> PlaybackState.STATE_ERROR
            st.isPlaying -> PlaybackState.STATE_PLAYING
            st.hasTrack -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_NONE
        }

        return PlaybackState.Builder()
            .setActions(actions)
            .setState(code, st.positionMs, if (st.isPlaying) 1f else 0f)
            .build()
    }
}

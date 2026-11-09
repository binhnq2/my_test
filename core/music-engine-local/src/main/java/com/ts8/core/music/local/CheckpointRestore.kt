package com.ts8.core.music.local

import com.ts8.core.music.Track

/**
 * Rebuilds a queue from a saved checkpoint.
 *
 * Separated from [LocalMusicPlayer] because the rules here are the ones that
 * have to be right after an ignition-off — and they are the ones that are
 * easiest to get subtly wrong:
 *
 * - A track whose file vanished (USB stick left in the other car) is **dropped**,
 *   not fatal. Losing one song is acceptable; losing the whole queue is not.
 * - The resume position belongs to the track that was playing. If that track is
 *   the one that vanished, the index must be re-derived rather than trusted, or
 *   playback resumes in the middle of a different song.
 * - If nothing survives, the checkpoint is dead and must be cleared. Otherwise
 *   every boot retries a restore that can never succeed.
 */
object CheckpointRestore {

    /**
     * The queue to restore, or null when the checkpoint cannot be honoured.
     *
     * @param lookup resolves an id to a track; returning null means it is gone.
     */
    fun plan(checkpoint: MusicCheckpoint, lookup: (Long) -> Track?): Restored? {
        val tracks = ArrayList<Track>(checkpoint.queueTrackIds.size)
        var indexOfResumed = -1

        for (id in checkpoint.queueTrackIds) {
            val track = try {
                lookup(id)
            } catch (e: Exception) {
                // A database closed under us reads as "gone", not as a crash.
                null
            } ?: continue
            if (track.id == checkpoint.trackId) indexOfResumed = tracks.size
            tracks.add(track)
        }

        if (tracks.isEmpty()) return null

        // The saved index is only trustworthy when the saved track itself
        // survived; otherwise it points into a list that no longer matches.
        val index = when {
            indexOfResumed >= 0 -> indexOfResumed
            else -> checkpoint.index.coerceIn(0, tracks.size - 1)
        }

        return Restored(tracks, index, checkpoint.positionMs, checkpoint.shuffle, checkpoint.repeatMode)
    }

    data class Restored(
        val tracks: List<Track>,
        val index: Int,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeatMode: com.ts8.core.music.RepeatMode
    )
}

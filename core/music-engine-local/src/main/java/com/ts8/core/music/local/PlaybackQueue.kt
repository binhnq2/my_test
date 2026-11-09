package com.ts8.core.music.local

import com.ts8.core.music.Track
import kotlin.random.Random

/**
 * Play order of the current queue.
 *
 * Deliberately free of Android types: shuffle, repeat-wrap and "restore the
 * exact order that was playing when the ignition went off" are the parts of
 * playback that are easiest to get subtly wrong, so they live in a class that
 * can be tested on a workstation instead of only in the car.
 *
 * The queue holds *positions into the track list*, not the tracks themselves,
 * in `order`. In sequential mode `order` is `0,1,2,…`; in shuffle mode it is a
 * permutation whose first element is the track that was playing when shuffle
 * was switched on. That keeps "next" and "previous" identical code in both
 * modes, which is what removes a whole class of shuffle bugs.
 */
class PlaybackQueue(
    /** Injected so tests can shuffle deterministically. */
    private val random: Random = Random.Default
) {
    private var tracks: List<Track> = emptyList()
    private var order: IntArray = IntArray(0)
    private var pos: Int = 0

    /** True when playback follows a shuffled order. */
    var shuffle: Boolean = false
        private set

    /** Number of items in the queue. */
    val size: Int get() = order.size

    /** Position within [order], 0-based. */
    val position: Int get() = pos

    fun isEmpty(): Boolean = tracks.isEmpty()

    /**
     * Replaces the queue.
     *
     * @param startIndex index into [tracks] to play first.
     * @param shuffle keep the current shuffle setting unless overridden.
     */
    fun setQueue(
        newTracks: List<Track>,
        startIndex: Int = 0,
        shuffle: Boolean = this.shuffle
    ) {
        tracks = ArrayList(newTracks)
        this.shuffle = shuffle
        if (tracks.isEmpty()) {
            order = IntArray(0)
            pos = 0
            return
        }
        val start = startIndex.coerceIn(0, tracks.size - 1)
        order = if (shuffle) {
            // The chosen track goes first, the rest follow shuffled. Building
            // the permutation this way means "play this song now" never jumps
            // to something else just because shuffle is on.
            val others = (0 until tracks.size).filter { it != start }
                .shuffled(random)
                .toIntArray()
            IntArray(1 + others.size).also {
                it[0] = start
                others.copyInto(it, 1)
            }
        } else {
            indices(tracks.size)
        }
        pos = if (shuffle) 0 else start
    }

    /**
     * Restores a queue exactly as it was saved: [tracks] are already in play
     * order, so no reshuffle happens. Used by checkpoint restore, where the
     * saved order must survive a reboot.
     */
    fun restore(savedTracks: List<Track>, savedPosition: Int, shuffle: Boolean) {
        tracks = ArrayList(savedTracks)
        this.shuffle = shuffle
        order = indices(savedTracks.size)
        pos = if (savedTracks.isEmpty()) 0
        else savedPosition.coerceIn(0, savedTracks.size - 1)
    }

    fun current(): Track? = order.getOrNull(pos)?.let { tracks.getOrNull(it) }

    /**
     * Moves to the next item.
     *
     * @param wrap when true, wrap to the first item after the last.
     * @return the new current track, or null when the queue ended and the
     *         caller should stop.
     */
    fun next(wrap: Boolean): Track? {
        if (order.isEmpty()) return null
        if (pos + 1 < order.size) {
            pos++
            return current()
        }
        // Repeat-all must loop even for a single-track queue; that is what
        // "repeat" means to someone parked with one song on the stick.
        if (wrap) {
            pos = 0
            return current()
        }
        return null
    }

    /** Moves to the previous item, with the same wrapping rules as [next]. */
    fun previous(wrap: Boolean): Track? {
        if (order.isEmpty()) return null
        if (pos > 0) {
            pos--
            return current()
        }
        if (wrap) {
            pos = order.size - 1
            return current()
        }
        return null
    }

    /** Jumps to a specific track (index into the track list, as a list row would pass). */
    fun select(indexInTracks: Int): Track? {
        val at = order.indexOf(indexInTracks)
        if (at < 0) return null
        pos = at
        return tracks[indexInTracks]
    }

    /**
     * Turns shuffle on or off without interrupting playback: the current track
     * stays current, and only what comes after it is reordered.
     */
    fun setShuffle(enabled: Boolean) {
        if (enabled == shuffle) return
        val currentIndex = order.getOrNull(pos) ?: -1
        shuffle = enabled
        if (tracks.isEmpty()) {
            order = IntArray(0)
            pos = 0
            return
        }
        order = if (enabled) {
            val others = (0 until tracks.size)
                .filter { it != currentIndex }
                .shuffled(random)
                .toIntArray()
            IntArray(if (currentIndex >= 0) 1 + others.size else others.size).also {
                if (currentIndex >= 0) {
                    it[0] = currentIndex
                    others.copyInto(it, 1)
                } else {
                    others.copyInto(it, 0)
                }
            }
        } else {
            indices(tracks.size)
        }
        pos = if (enabled) 0 else order.indexOf(currentIndex).coerceAtLeast(0)
    }

    /** The queue in play order — what a checkpoint saves and a UI list shows. */
    fun tracksInOrder(): List<Track> = order.toList().mapNotNull { tracks.getOrNull(it) }

    /** Track ids in play order, for the checkpoint table. */
    fun trackIdsInOrder(): List<Long> = order.toList().mapNotNull { tracks.getOrNull(it)?.id }

    private fun indices(n: Int): IntArray = IntArray(n) { it }
}

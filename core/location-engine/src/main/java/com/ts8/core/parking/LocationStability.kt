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

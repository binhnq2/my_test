package com.ts8.core.speed

import com.ts8.core.map.GeoPoint

/**
 * Delivers an overspeed alert through the two channels the settings allow, at a
 * volume independent of the music.
 *
 * **Why the volume is independent.** The brief requires the alert level to be
 * its own setting that still respects audio focus. On this head unit the only
 * lever API 23 gives an app is `STREAM_MUSIC`, so "independent" cannot mean a
 * separate hardware stream — it means the alert is *scaled against the user's
 * alert-volume setting and re-applied after any duck*, not inherited from
 * whatever the music slider happens to be at. Two consequences this class
 * enforces:
 *
 * 1. **Ducking is shared, not private.** The alert reuses the app's single
 *    [SpeedAlertSink] so the music duck and restore around a navigation prompt
 *    and around an alert never fight. If the alert took the volume itself and
 *    restored it while navigation was still speaking, music would come back
 *    loud in the middle of a prompt.
 * 2. **A silent alert is a lie.** If the sink refuses (audio focus held by a
 *    call, a vendor ROM denying the volume change) the alert downgrades to
 *    visual and reports that — it never claims sound.
 *
 * The controller also owns the "cooldown" and "one alert per event" bookkeeping
 * that the detector deliberately leaves to its caller: the detector says *when*
 * a threshold crossing is real, this says *whether* it is delivered.
 */
class SpeedAlertController(
    private val sink: SpeedAlertSink,
    private val clock: () -> Long
) {

    /** What the app must provide. Implemented over `VehicleAudioBridge`. */
    interface SpeedAlertSink {
        /**
         * Requests the speakers for a short alert.
         *
         * Returns false when focus could not be taken; the caller must then
         * stay visual-only rather than assuming sound happened.
         */
        fun acquireAlertFocus(): Boolean

        /** Releases the speakers and restores whatever was ducked. */
        fun releaseAlertFocus()

        /** Plays [tone] at [volume] (0..1 relative to this alert's own max). */
        fun play(tone: AlertTone, volume: Float)

        /** Stops playback started by [play], restoring any duck. */
        fun stop()
    }

    /** Which alert sound. Two only: a chime for over-limit, a shorter tick. */
    enum class AlertTone { OVERSPEED, OVERSPEED_RECOVERED }

    data class Config(
        val form: SpeedMonitor.AlertForm = SpeedMonitor.AlertForm.BOTH,
        val volume: Float = 0.8f
    )

    /** What the UI should show after an alert was requested. */
    data class Delivery(val visual: Boolean, val audio: Boolean, val streamedReason: String?)

    private var lastDeliveredMs = Long.MIN_VALUE
    private var lastVisualMs = Long.MIN_VALUE

    /**
     * Reports a delivered overspeed event.
     *
     * [form] and [volume] come from the live [SpeedMonitor.Config], read at the
     * moment of delivery rather than cached, so a driver who turns the alert
     * volume down hears the next alert at the new level instead of at the level
     * that was set when the screen opened.
     */
    fun deliver(overByKmh: Int, form: SpeedMonitor.AlertForm, volume: Float): Delivery {
        val now = clock()
        val visual = form == SpeedMonitor.AlertForm.VISUAL || form == SpeedMonitor.AlertForm.BOTH
        val wantsAudio = form == SpeedMonitor.AlertForm.AUDIO || form == SpeedMonitor.AlertForm.BOTH

        if (visual) lastVisualMs = now
        if (!wantsAudio) {
            return Delivery(visual = visual, audio = false, streamedReason = null)
        }
        if (volume <= 0f) {
            // A zero volume is a valid setting; do not pretend a sound played.
            return Delivery(visual = visual, audio = false, streamedReason = "âm lượng cảnh báo = 0")
        }
        if (!sink.acquireAlertFocus()) {
            return Delivery(
                visual = visual,
                audio = false,
                streamedReason = "không lấy được audio focus (cuộc gọi đang diễn ra?)"
            )
        }
        return try {
            sink.play(AlertTone.OVERSPEED, volume.coerceIn(0f, 1f))
            lastDeliveredMs = now
            Delivery(visual = visual, audio = true, streamedReason = null)
        } catch (e: Exception) {
            Delivery(visual = visual, audio = false, streamedReason = e.message ?: "phát âm thanh thất bại")
        } finally {
            // Released in `finally`: a sink that throws must not leave the music
            // ducked for the rest of the drive.
            runCatching { sink.releaseAlertFocus() }
        }
    }

    /** Stops any alert sound, e.g. on leaving the screen or a call arriving. */
    fun silence() {
        runCatching { sink.stop() }
    }

    fun lastVisualMs(): Long = lastVisualMs

    fun lastAudioMs(): Long = lastDeliveredMs
}

/**
 * Where the limit for the current road comes from.
 *
 * **Why this is an interface, and why the answer is usually null.** Deriving a
 * limit needs the graph edge under the car, and that lookup is only possible
 * while a route exists or the pack exposes a spatial index over edges. The
 * offline router does expose one, so [RoutingEdgeLimits] below is the real
 * implementation; a pack without a usable index, or a car off the road network,
 * yields null and the roundel shows `--`, which is the required behaviour
 * rather than a guess.
 */
interface SpeedLimitSource {
    /**
     * The limit for the edge nearest [point], or null when the pack has no
     * answer there.
     */
    fun limitAt(point: GeoPoint): Int?
}

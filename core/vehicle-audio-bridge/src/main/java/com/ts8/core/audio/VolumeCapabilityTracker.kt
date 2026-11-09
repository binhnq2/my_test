package com.ts8.core.audio

/**
 * Tracks whether this device's media volume actually responds to a write (§8).
 *
 * **Why a tracker and not a single `isSupported` flag.** One write that changes
 * nothing is not evidence of anything: an audio HAL may apply the change a few
 * milliseconds late, the driver may have tapped the boundary of the stream, or
 * another app may have moved the stream between the read and the write. §8's
 * rule is to require **two consecutive** valid write/read-back cycles with no
 * change before a device is declared NO_EFFECT, and this class is that rule
 * made explicit and testable.
 *
 * **What is deliberately excluded from the count** — three cases where "the
 * value did not change" is the correct, expected answer and counting it would
 * declare a healthy device broken:
 *
 *  - increasing while already at max;
 *  - decreasing while already at min;
 *  - an attempt the platform rejected (permission denied, exception, fixed
 *    stream). Those are [Capability.UNAVAILABLE] territory or not measured at
 *    all, not NO_EFFECT.
 *
 * **Where this lives.** In the bridge module, not the app, because the counter
 * is a property of the hardware the bridge talks to, and because a future
 * `SyuAudioBridge` swapping in must inherit the same honesty rule rather than
 * reimplement it. It holds no Android type, so it is exercised on a plain JVM.
 */
class VolumeCapabilityTracker(
    /** How many consecutive no-change cycles prove the write is inert. */
    private val threshold: Int = DEFAULT_THRESHOLD
) {

    /** The three states §8 names. There is no fourth. */
    enum class Capability {
        /** Not enough evidence yet: the device has not been exercised. */
        UNKNOWN,

        /** A write moved the stream. The control works. */
        SUPPORTED,

        /** [threshold] consecutive valid writes changed nothing. */
        NO_EFFECT,

        /** The platform refuses the write, or the stream is fixed. */
        UNAVAILABLE
    }

    var capability: Capability = Capability.UNKNOWN
        private set

    /** Consecutive no-change cycles; reset by any move or any exclusion. */
    var consecutiveNoChange: Int = 0
        private set

    /**
     * Records the outcome of one write.
     *
     * @param requested the level the caller asked for.
     * @param before    the level read immediately before the write.
     * @param after     the level read immediately after it.
     * @param result    what the bridge returned.
     * @return the capability after this observation.
     */
    fun record(
        requested: Int,
        before: Int,
        after: Int,
        result: AudioResult
    ): Capability {
        // A rejected or impossible write says nothing about whether a valid
        // write would work; it is a different failure and it is not counted.
        when (result) {
            is AudioResult.Unsupported -> {
                return set(Capability.UNAVAILABLE)
            }
            is AudioResult.Failed,
            is AudioResult.Denied -> {
                // Leave the counter and the capability alone: a transient
                // refusal must not age evidence either way.
                return capability
            }
            is AudioResult.Success,
            is AudioResult.NoEffect -> Unit
        }

        // At the boundary, "no change" is the honest and expected answer.
        // Increasing at max and decreasing at min are not evidence of inertness.
        if (before == after) {
            val increasing = requested > before
            val decreasing = requested < before
            val atBoundary = (increasing && before >= maxKnown) ||
                (decreasing && before <= 0)
            if (atBoundary) {
                // Neither evidence for nor against: the caller asked for a
                // level the stream cannot reach. Do not reset a streak either —
                // the device has not been exercised either way.
                return capability
            }
        }

        return if (after != before) {
            // The write worked. Any accumulated doubt is cleared.
            consecutiveNoChange = 0
            set(Capability.SUPPORTED)
        } else {
            consecutiveNoChange += 1
            if (consecutiveNoChange >= threshold) {
                set(Capability.NO_EFFECT)
            } else {
                capability
            }
        }
    }

    /**
     * Marks the capability unavailable without a write.
     *
     * Used when `isVolumeFixed()` reports true or the UI could not read the
     * stream at all — neither requires exercising the control to know there is
     * nothing to offer.
     */
    fun markUnavailable(): Capability = set(Capability.UNAVAILABLE)

    /** Marks it supported from an out-of-band observation (a settings write). */
    fun markSupported(): Capability {
        consecutiveNoChange = 0
        return set(Capability.SUPPORTED)
    }

    /**
     * Forgets the streak, keeping whatever has already been proved.
     *
     * Called when the app is told the audio route changed: after a source
     * switch the stream may be handled by a different mixer, so evidence
     * gathered before it is about a different device path. The capability is
     * demoted to UNKNOWN rather than kept, because NO_EFFECT proved against the
     * Bluetooth mixer says nothing about the speaker one.
     */
    fun onAudioRouteChanged(): Capability {
        consecutiveNoChange = 0
        return set(Capability.UNKNOWN)
    }

    /** The maximum stream level, told to the tracker by the caller. */
    var maxKnown: Int = 0
        private set

    /** Records the stream maximum so boundary taps can be recognised. */
    fun setMaxKnown(max: Int): Capability {
        maxKnown = max.coerceAtLeast(1)
        return capability
    }

    private fun set(next: Capability): Capability {
        capability = next
        if (next != Capability.NO_EFFECT) consecutiveNoChange = 0
        return next
    }

    companion object {
        /**
         * Two, per §8's "tối thiểu 2 lần write/read-back hợp lệ liên tiếp".
         *
         * Not one: a single inert write is within the range of a slow HAL. Not
         * three: on a device that is genuinely inert the driver has already
         * tapped three times and been lied to three times, and the point of the
         * rule is to stop lying as early as the evidence allows.
         */
        const val DEFAULT_THRESHOLD = 2
    }
}

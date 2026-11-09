package com.ts8.core.speed

/**
 * Decides whether a sample may be believed at all.
 *
 * **The rule this exists to enforce.** A GPS receiver in a car reports three
 * kinds of nonsense: speed derived from a jittering position while stationary
 * (a "1 km/h" that is really 0), a stale sample replayed by the platform after
 * a tunnel, and a physically impossible jump at a bridge or under trees. None of
 * those may reach the alert path. Rather than each consumer re-checking, one
 * gate produces [SpeedState.UNRELIABLE] and everything downstream stays silent.
 *
 * Thresholds come from the location engine's own validation constants where
 * they overlap, so a fix it already accepted is not re-rejected here for a
 * different reason.
 */
class SpeedGate(private val config: Config = Config()) {

    data class Config(
        /** A sample older than this cannot describe the car right now. */
        val maxAgeMs: Long = 5_000L,
        /** Above this, GPS is guessing: tunnels, urban canyons, cold start. */
        val maxAccuracyMeters: Float = 60f,
        /** ~160 km/h; nothing on these roads is faster and a jump is a spike. */
        val maxPlausibleMps: Double = 45.0,
        /**
         * When configured, speeds below this are reported as zero rather than as
         * a jittering small number. The brief calls this the GPS speed filter
         * threshold and requires it to be settable, including off.
         */
        val minimumSpeedMps: Double = 0.0
    )

    /** Returns null when the sample is trustworthy, or the reason it is not. */
    fun reject(sample: SpeedSample, nowElapsedMs: Long): String? {
        val age = nowElapsedMs - sample.elapsedRealtimeMs
        if (age > config.maxAgeMs) return "stale ${age}ms"
        if (age < -1_000L) return "timestamp in future"
        if (sample.accuracyMeters > config.maxAccuracyMeters) {
            return "accuracy ${sample.accuracyMeters}m"
        }
        if (sample.speedMps > config.maxPlausibleMps) return "implausible ${sample.speedMps}m/s"
        if (sample.speedMps < 0) return "negative speed"
        // A degraded sample is the location engine telling us it returned its
        // last accepted value rather than a fresh one. It can still drive the
        // readout (that is the point of holding a value) but must not open an
        // alert.
        if (sample.degraded) return "degraded sample"
        return null
    }

    /**
     * Applies the optional dead-band, mapping a jittering low speed to zero.
     *
     * The threshold is passed in rather than read from [config] because it is
     * a driver setting ("ngưỡng lọc tốc độ GPS"), changed from the settings
     * screen while a capture is running; a value baked into the gate at
     * construction would ignore it until the next process start.
     */
    @JvmOverloads
    fun applyDeadBand(speedMps: Double, thresholdMps: Double = config.minimumSpeedMps): Double =
        if (thresholdMps > 0 && speedMps < thresholdMps) 0.0 else speedMps
}

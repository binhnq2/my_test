package com.ts8.core.speed

/**
 * Speed smoothing policy.
 *
 * **Why three settings and not one.** A slow-but-steady number is right for a
 * driver watching a speed readout; a fast-responding one is right for an alert
 * that must not arrive after the trap. The brief asks for `Nhanh`, `Cân bằng`,
 * `Mượt` and defaults to `Cân bằng`; these are the exponential smoothing
 * coefficients that make each of those real.
 *
 * **Why not the location engine's own filter.** That filter smooths *position*
 * and produces speed as a side effect, tuned for drawing a marker. Reusing its
 * coefficient for a speed alert couples two different requirements: changing the
 * marker's smoothness would change when alerts fire. This filter runs on the
 * speed value itself, downstream of the engine's validation — it never sees a
 * raw GPS sample.
 */
class SpeedFilter(private val config: Config = Config()) {

    data class Config(
        /** Weight of a new sample; higher reacts faster and jitters more. */
        val alphaFast: Double = 0.55,
        val alphaBalanced: Double = 0.32,
        val alphaSmooth: Double = 0.16,
        /** Below this the car is treated as stopped, in m/s (≈3.6 km/h). */
        val movementThresholdMps: Double = 1.0
    )

    private var smoothedMps = 0.0
    private var primed = false
    private var lastSource: SpeedSource? = null

    /**
     * Folds a sample in and returns the smoothed speed.
     *
     * A change of [SpeedSource] re-seeds rather than blends: blending a bus
     * reading with a GPS reading produces a number neither source reported,
     * which is exactly the kind of invented value the brief bans. The first
     * sample after a source change is taken as-is and the filter restarts.
     */
    fun update(sample: SpeedSample, smoothing: Smoothing): Double {
        if (!primed || sample.source != lastSource) {
            smoothedMps = sample.speedMps
            primed = true
            lastSource = sample.source
            return smoothedMps
        }
        val alpha = when (smoothing) {
            Smoothing.FAST -> config.alphaFast
            Smoothing.BALANCED -> config.alphaBalanced
            Smoothing.SMOOTH -> config.alphaSmooth
        }
        smoothedMps += alpha * (sample.speedMps - smoothedMps)
        return smoothedMps
    }

    /** True when the smoothed value is below the stop threshold. */
    fun isStationary(): Boolean = smoothedMps < config.movementThresholdMps

    fun currentMps(): Double = smoothedMps

    fun reset() {
        primed = false
        smoothedMps = 0.0
        lastSource = null
    }

    enum class Smoothing { FAST, BALANCED, SMOOTH }
}

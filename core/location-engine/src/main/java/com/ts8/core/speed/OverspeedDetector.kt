package com.ts8.core.speed

/**
 * Turns a speed and a limit into "is this an overspeed event", with hysteresis.
 *
 * **Three rules, each of which fixes a real false alert:**
 *
 * 1. **No valid limit, no event.** [SpeedLimit.valid] must be true. The design's
 *    60 is not a limit; a graph-default speed is a limit with MEDIUM confidence
 *    and is allowed, a fabricated class lookup is not representable here.
 * 2. **Sustained, not momentary.** A crossing only becomes active after the
 *    speed stays over the threshold for [Config.confirmMs]. This is the
 *    "chống GPS spike" requirement — one sample at 90 km/h in a 50 zone while
 *    passing a bridge must not fire.
 * 3. **Cooldown after firing.** Once an alert has been delivered, another one
 *    cannot fire for [Config.cooldownMs] even if the condition persists, so the
 *    driver is warned once rather than every tick.
 */
class OverspeedDetector(private val config: Config = Config()) {

    data class Config(
        /** How long the speed must stay over the threshold before alerting. */
        val confirmMs: Long = 2_500L,
        /** Silence between two delivered alerts. */
        val cooldownMs: Long = 15_000L,
        /** Speed must fall this far under the limit to re-arm. */
        val hysteresiskmh: Int = 3
    )

    private var overSinceMs: Long = -1L
    private var lastAlertMs: Long = Long.MIN_VALUE
    private var armed = true
    private var active = false

    /**
     * Advances the detector.
     *
     * @param requireSustained when false, a single over-threshold sample is
     *        enough (the driver turned "chỉ cảnh báo khi vượt liên tục" off).
     * @param cooldownMs silence after a delivered alert; passed in because the
     *        setting is changeable from the settings screen.
     * @return true exactly on the tick where a new alert should be delivered.
     */
    @JvmOverloads
    fun update(
        speedKmh: Double,
        limit: SpeedLimit,
        thresholdKmh: Int,
        nowMs: Long,
        requireSustained: Boolean = true,
        cooldownMs: Long = config.cooldownMs
    ): Boolean {
        active = false
        if (!limit.valid) {
            overSinceMs = -1L
            armed = true
            return false
        }

        val trigger = limit.limitKmh + thresholdKmh
        val over = speedKmh > trigger

        if (!over) {
            overSinceMs = -1L
            // Re-arm only after falling clearly below the limit, so a speed
            // hovering exactly on the threshold does not alert repeatedly.
            if (speedKmh <= limit.limitKmh - config.hysteresiskmh) armed = true
            return false
        }

        if (overSinceMs < 0L) overSinceMs = nowMs
        val sustainedFor = nowMs - overSinceMs
        if (requireSustained && sustainedFor < config.confirmMs) return false

        active = true
        if (!armed) return false
        if (lastAlertMs != Long.MIN_VALUE && nowMs - lastAlertMs < cooldownMs) return false

        armed = false
        lastAlertMs = nowMs
        return true
    }

    /** True when the speed is over the trigger and has been for the window. */
    fun isActive(): Boolean = active

    /** True during the confirmation window: over, but not yet announced. */
    fun isPending(): Boolean = overSinceMs >= 0L && !active

    fun reset() {
        overSinceMs = -1L
        lastAlertMs = Long.MIN_VALUE
        armed = true
        active = false
    }
}

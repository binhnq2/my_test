package com.ts8.core.speed

import com.ts8.core.location.LocationFix
import com.ts8.core.map.GeoPoint

/**
 * One speed measurement before any policy is applied.
 *
 * **Why a separate type from [LocationFix].** The fix is the location engine's
 * output: a smoothed position, a snapped route point, a bearing. A speed sample
 * only needs the number, its age, its accuracy and where the number came from —
 * and the vehicle bus will eventually produce samples that have no position at
 * all. Keeping the two apart is what lets a vehicle-speed source be added
 * without teaching the location engine about the CAN bus.
 */
data class SpeedSample(
    val speedMps: Double,
    /** Accuracy of the underlying fix in metres; negative when unknown. */
    val accuracyMeters: Float,
    /** `SystemClock.elapsedRealtime()` when the sample was taken. */
    val elapsedRealtimeMs: Long,
    val source: SpeedSource,
    /** True when the underlying sample was itself an interpolated/rejected one. */
    val degraded: Boolean = false
) {
    /** km/h, for display and thresholds. */
    val speedKmh: Double get() = speedMps * 3.6
}

/**
 * Where a speed reading came from.
 *
 * Ordering is priority: a verified vehicle-bus speed always wins over GPS,
 * because GPS speed is derived from position deltas and is the noisier of the
 * two — especially in a tunnel or between tall buildings, exactly where an
 * overspeed alert matters least but a false one is most annoying.
 */
enum class SpeedSource {
    /** Received from the vehicle bus through a bridge with a known contract. */
    VEHICLE_BUS,

    /** Derived from the GPS fix by the location engine. */
    GPS
}

/**
 * What the speed pipeline currently believes.
 *
 * `OVERSPEED_PENDING` is the state that makes the feature usable in a car: a
 * threshold crossing has to survive [SpeedMonitor]'s confirmation window before
 * it becomes `OVERSPEED_ACTIVE`. Without it a single bad GPS sample at a bridge
 * produces an alert.
 */
enum class SpeedState {
    /** No usable sample yet, or no limit to compare against. */
    UNKNOWN,

    /** Confidently below the movement threshold. */
    STATIONARY,

    /** Moving, but no limit is known or speed is within it. */
    MOVING,

    /** Samples are too stale, too inaccurate or physically implausible. */
    UNRELIABLE,

    /** Over the threshold, waiting out the confirmation window. */
    OVERSPEED_PENDING,

    /** Over the threshold for the whole window; the alert is live. */
    OVERSPEED_ACTIVE
}

/** How the limit for the current road was established. */
enum class LimitSource {
    /** Tagged on the road in the routing graph (`maxspeed`). */
    ROUTING_GRAPH,

    /** Supplied by the vehicle bus. */
    VEHICLE_BUS,

    /** Nothing valid; the UI must show `--` and no alert may fire. */
    NONE
}

/**
 * A speed limit with provenance.
 *
 * The brief forbids guessing: an alert may only fire against a limit whose
 * [source] is not [LimitSource.NONE] and whose [confidence] is not
 * [Confidence.NONE]. Carrying the timestamp is what lets the diagnostics panel
 * show *when* the limit was resolved, which is the difference between "the pack
 * has no limits" and "the limit lookup stopped running".
 */
data class SpeedLimit(
    val limitKmh: Int,
    val source: LimitSource,
    val resolvedAtElapsedMs: Long,
    val confidence: Confidence
) {
    enum class Confidence { NONE, LOW, MEDIUM, HIGH }

    val valid: Boolean get() = limitKmh > 0 && source != LimitSource.NONE && confidence != Confidence.NONE

    companion object {
        val NONE = SpeedLimit(0, LimitSource.NONE, 0L, Confidence.NONE)
    }
}

/**
 * Which unit the readout uses.
 *
 * Stored as a preference and applied here, not in the view: the threshold is
 * configured in km/h and the alert fires on km/h, so converting only the
 * printed number keeps one source of truth and makes "vượt 5 km/h" mean the
 * same thing whether the driver reads mph or km/h.
 */
enum class SpeedUnit { KMH, MPH }

/** What the UI is allowed to render, and nothing more. */
data class SpeedUiState(
    val state: SpeedState,
    val speedKmh: Int?,
    val limitKmh: Int?,
    val limitSource: LimitSource,
    val overBy: Int,
    val sampleSource: SpeedSource?,
    val sampleAgeMs: Long,
    val unit: SpeedUnit = SpeedUnit.KMH,
    /** Whether the driver asked for the two readouts at all. */
    val showSpeedSetting: Boolean = true,
    val showLimitSetting: Boolean = true
) {
    /** `--` semantics: null means "no trustworthy number", not zero. */
    val showSpeed: Boolean get() = showSpeedSetting && speedKmh != null

    val showLimit: Boolean get() = showLimitSetting && limitKmh != null

    val alerting: Boolean get() = state == SpeedState.OVERSPEED_ACTIVE

    /** The speed in the driver's chosen unit, rounded the way a dial rounds. */
    val displaySpeed: Int?
        get() = speedKmh?.let { if (unit == SpeedUnit.MPH) Math.round(it / 1.609344).toInt() else it }

    val displayLimit: Int?
        get() = limitKmh?.let { if (unit == SpeedUnit.MPH) Math.round(it / 1.609344).toInt() else it }

    val unitLabel: String get() = if (unit == SpeedUnit.MPH) "mph" else "km/h"

    companion object {
        /**
         * The honest empty state: no sample and no limit, so both readouts show
         * `--` rather than a zero that would read as a measurement.
         */
        val EMPTY = SpeedUiState(
            state = SpeedState.UNKNOWN,
            speedKmh = null,
            limitKmh = null,
            limitSource = LimitSource.NONE,
            overBy = 0,
            sampleSource = null,
            sampleAgeMs = -1L
        )
    }
}

/** Where a vehicle-bus speed may come from, when a verified bridge exists. */
interface VehicleSpeedSource {
    /** Latest bus speed in m/s, or null when the bus does not publish one. */
    fun latest(): SpeedSample?

    /** True only when the bridge has a documented contract for this signal. */
    fun available(): Boolean
}

/** GPS speed, already validated and smoothed by the location engine. */
interface LocationSpeedSource {
    fun latest(): SpeedSample?

    /** Feeds the monitor from the location pipeline; UI never calls this. */
    fun provide(fix: LocationFix)
}

/** Raised by the UI when the car crosses into a new road segment. */
data class RoadContext(val point: GeoPoint?, val resolvedAtElapsedMs: Long)

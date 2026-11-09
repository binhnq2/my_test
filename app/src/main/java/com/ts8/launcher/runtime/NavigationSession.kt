package com.ts8.launcher.runtime

/**
 * A live navigation session, queried by voice commands like "còn bao xa".
 *
 * This is a thin interface over whatever navigation controller is active —
 * the [com.ts8.core.navigation.NavigationController] in the current build,
 * or a future turn-by-turn engine. The voice executor only needs to know
 * whether a route is active, how far remains, and when it expects to arrive.
 *
 * **Why an interface and not a concrete class.** The navigation controller's
 * API is still evolving (offline A*, online turn-by-turn, or a hybrid), and
 * the voice executor must not be coupled to whichever one is current. The
 * interface is what lets the executor speak "còn 12 km" without knowing
 * whether that number came from a graph edge sum or a server response.
 */
interface NavigationSession {

    /** True when a route is active and the car is following it. */
    fun isActive(): Boolean

    /**
     * The remaining distance in metres, or 0 when no route is active.
     *
     * The voice executor divides by 1000 and speaks "còn X km" when this
     * is ≥ 1000, and "còn X mét" otherwise. A value of 0 is ambiguous — it
     * could mean "arrived" or "no route" — so [isActive] is checked first.
     */
    fun remainingDistanceMeters(): Int

    /**
     * The estimated arrival time as epoch milliseconds, or 0 when unknown.
     *
     * The voice executor formats this as a clock time when non-zero.
     */
    fun estimatedArrivalEpochMs(): Long

    /** Cancels the current route. Safe to call when [isActive] is false. */
    fun cancel()
}

/**
 * A TPMS (tyre pressure monitoring) service, queried by voice commands.
 *
 * Optional: most TS8 head units do not have a TPMS receiver. The field in
 * [com.ts8.launcher.di.AppGraph] is null when no service is bound, and the
 * voice executor says "thiết bị không có cảm biến áp lốp" rather than
 * crashing.
 *
 * **Why a map and not four fields.** The head unit may have 4 or 6 sensors
 * (spare tyres on trucks), and a map lets the executor iterate without
 * knowing the count. Keys are position labels: "FL", "FR", "RL", "RR".
 */
interface TpmsService {

    /**
     * All tyre pressures, keyed by position label.
     *
     * @return empty map when no sensor data has been received yet.
     */
    fun allTyrePressures(): Map<String, Float>
}

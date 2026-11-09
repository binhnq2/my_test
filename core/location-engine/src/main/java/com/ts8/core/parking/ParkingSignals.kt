package com.ts8.core.parking

/**
 * One instant of everything the parking decision is allowed to look at.
 *
 * **Why a snapshot and not a bag of setters.** The decision depends on signals
 * that arrive at completely different rates: the vehicle bus every few hundred
 * milliseconds, GPS once a second, the call and reverse-camera state whenever
 * the platform feels like broadcasting it. If each of those wrote into the
 * resolver separately, a decision could be taken halfway through a transition
 * — gear already in P, speed still the value from three seconds ago — and the
 * resolver would have no way to say which combination it actually saw. A
 * snapshot is also what makes the resolver a pure function, which is why it
 * can be exercised without a head unit, a looper or a test framework.
 *
 * **Why almost every field is nullable.** The TS8 firmware does not document a
 * vehicle-bus contract, so field 18 forbids assuming one. `null` means *this
 * unit does not report this signal at all*; it does not mean false, zero or
 * "no". Collapsing the two is what makes naive parking detectors park a car at
 * a red light: no signal becomes false becomes "not moving". Every nullable
 * field below therefore carries the "no answer" case explicitly, and the
 * resolver treats a null as absent evidence rather than as counter-evidence.
 */
data class ParkingSignals(
    /**
     * Gear selector reports Park.
     *
     * `null` = the bridge does not expose the selector (the common case on this
     * unit), `false` = it does and the car is *not* in Park. The difference
     * matters: `false` in Drive is evidence of driving, `null` is not, and a
     * resolver that treated both as false would either park every car whose bus
     * is silent or refuse to park every car whose selector reads "N".
     */
    val vehicleGearPark: Boolean? = null,

    /**
     * Accessory circuit is on.
     *
     * `null` = not reported. `true` is weak information: the radio runs in ACC
     * while the car is parked, so it must never be read as "engine running".
     * `false` is stronger: ACC off with a stationary car almost always means
     * the driver has finished, though a voltage dip during cranking produces
     * the same reading for a moment — which is why the resolver rates it below
     * [vehicleIgnitionOff] and below the gear signal.
     */
    val vehicleAccOn: Boolean? = null,

    /**
     * Parking brake is applied.
     *
     * `null` = not reported, `false` = reported released. `true` is real
     * evidence that the car is being held, but drivers routinely pull the
     * handbrake at a long red light, so on its own it is a candidate, not a
     * conclusion. A resolver that promoted `true` to PARKED would trigger on
     * every queue at every junction.
     */
    val vehicleParkingBrake: Boolean? = null,

    /**
     * Ignition is off.
     *
     * `null` = not reported. `true` is the strongest single signal available
     * without a documented bus contract: nobody drives with the ignition off.
     * `false` proves nothing — a keyless car in READY mode reports ignition on
     * while standing in a car park — so it must not be treated as "driving".
     */
    val vehicleIgnitionOff: Boolean? = null,

    /**
     * Ground speed from the best available source, in metres per second.
     *
     * **`null` is not `0.0`.** Null means there is no fix at all: the receiver
     * has not solved a position, the provider is disabled, or the location
     * engine rejected the sample. A fix that says `0.0` is a measurement that
     * the car is not moving. A parking decision may be taken from the second;
     * the first must produce UNKNOWN, because "GPS lost" and "parked in a
     * tunnel" look identical from inside the car and only one of them is a
     * reason to show the parking screen.
     */
    val speedMps: Double? = null,

    /**
     * Accuracy radius of the fix that produced [speedMps], in metres.
     *
     * `null` = the provider did not supply one; the speed is then accepted at
     * face value because there is nothing to contradicted it. A value above the
     * resolver's threshold makes the speed unusable and is *not* silently
     * rounded up to "probably zero" — urban-canyon fixes report 100 m accuracy
     * and a speed of zero while the car is moving, which is exactly the sample
     * that must not park the car.
     */
    val speedAccuracyMeters: Float? = null,

    /**
     * The location engine's verdict that the position has stopped wandering.
     *
     * `null` = no verdict yet (too few fixes, or the engine is not running);
     * `false` = it measured jitter above its tolerance. Stability is a
     * different question from speed: a stationary receiver can report 0 km/h
     * while the position hops between four points, and it is that hop which
     * distinguishes a red light from a parking space. Null is not "stable" and
     * not "unstable" — it simply does not count towards the agreement the
     * heuristic requires.
     */
    val locationStable: Boolean? = null,

    /**
     * Turn-by-turn guidance is running.
     *
     * Non-null by contract: the navigation controller always knows this, so
     * there is no "unreported" case to represent. Guidance active means the
     * driver is going somewhere; an automatic PARKED would hide the map in the
     * middle of a route, so this blocks entry.
     */
    val navigationActive: Boolean = false,

    /**
     * A phone call is in progress.
     *
     * Non-null because the telephony layer is the one platform signal that is
     * reliably present on API 23. A call while stationary is a delivery driver
     * pulled over, not a parked car; treating it as parked would hide the map
     * and the call controls at the moment the driver is using them.
     */
    val callActive: Boolean = false,

    /**
     * The reverse camera is showing.
     *
     * Non-null: the camera is part of this head unit's own UI, so its state is
     * always known. Camera active means the driver is manoeuvring into or out
     * of a space — the one moment when an automatic parking screen would cover
     * the screen the driver is actually looking at.
     */
    val reverseCameraActive: Boolean = false,

    /**
     * The driver's own instruction: `true` = "vào chế độ đỗ xe", `false` =
     * "thoát chế độ đỗ xe", `null` = no manual input on this snapshot.
     *
     * **`false` is not "unknown".** It is a deliberate exit and the resolver
     * latches it until the car actually moves, because otherwise the GPS
     * heuristic would put the driver straight back into the screen they just
     * dismissed. Null leaves the latch alone, which is what a snapshot taken on
     * a tick where the button was not touched should mean.
     */
    val explicitManualRequest: Boolean? = null,

    /**
     * `SystemClock.elapsedRealtime()` at the moment the snapshot was taken.
     *
     * The resolver measures dwell windows against this rather than against its
     * own clock read, because the two are not the same instant: a snapshot can
     * sit in a queue while the main thread finishes a frame, and counting that
     * delay as parking time is how a detector accumulates minutes of dwell at
     * a red light. It also lets the resolver reject a snapshot that is already
     * stale instead of accepting it as current. There is deliberately no
     * default: a caller that forgot to stamp a snapshot would otherwise measure
     * every dwell window against zero and park the car on the first tick.
     */
    val elapsedRealtimeMs: Long
)

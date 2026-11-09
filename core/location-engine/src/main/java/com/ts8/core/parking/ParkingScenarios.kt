package com.ts8.core.parking

/**
 * Executable acceptance cases for the parking resolver, as plain Kotlin.
 *
 * **Why not JUnit/Robolectric.** The build host cannot download dependencies,
 * and Robolectric in particular is a 100 MB artifact plus an Android SDK it
 * would have to fetch. The resolver and the store are deliberately free of
 * Android imports precisely so their rules can be exercised here, in the same
 * module, with nothing but the Kotlin stdlib. The class returns failure
 * descriptions rather than asserting, because it has to be runnable from a
 * diagnostics screen on the head unit as well as from a build step — a thrown
 * exception there would take down the screen that exists to report the fault.
 *
 * **Why these scenarios and not the easy ones.** Every case below is a way the
 * feature is expected to fail in a real car: a red light, a lost fix, a GPS
 * spike, a phone call, a unit whose bus is silent. A suite that only proved
 * "speed 0 for ten minutes parks" would pass while the launcher showed the
 * parking clock at every junction.
 */
object ParkingScenarios {

    /**
     * Runs every scenario.
     *
     * @return one line per failure, in Vietnamese, each naming the scenario and
     *         the decision actually produced. An empty list means all passed —
     *         callers must treat that as success rather than as "nothing ran",
     *         which is why [scenarioCount] is public.
     */
    fun verify(): List<String> {
        val failures = ArrayList<String>()
        redLightNeverParks(failures)
        parkedTenMinutes(failures)
        singleSpikeDoesNotExit(failures)
        sustainedMovementExits(failures)
        callBlocksAutoEntry(failures)
        ignitionOffParks(failures)
        noSignalsIsUnknown(failures)
        manualWins(failures)
        parkingBrakeAloneIsCandidate(failures)
        lostFixDoesNotPark(failures)
        autoEnterOffStaysCandidate(failures)
        corruptStoreIsUnknown(failures)
        return failures
    }

    /** How many cases [verify] runs; a UI can show "0/12 lỗi". */
    val scenarioCount: Int = 12

    // -- scenarios ---------------------------------------------------------

    /**
     * A red light: speed zero, dwell short, position jittering.
     *
     * The case the brief names first. Two independent facts must stop it: the
     * dwell window has not elapsed, and the location engine says the position
     * is still moving. Either one alone is enough here, so the scenario holds
     * the car for longer than the candidate window with jitter to prove the
     * stability half is doing work rather than the clock.
     */
    private fun redLightNeverParks(failures: MutableList<String>) {
        val h = harness()
        val seen = ArrayList<ParkingState>()
        // 45 s: past the 20 s candidate window, well short of the 2 min park
        // window, with the receiver hopping between four points.
        seen += h.resolve(signals(atMs = 0, speed = 0.0, stable = false)).state
        seen += h.resolve(signals(atMs = 15_000, speed = 0.2, stable = false)).state
        seen += h.resolve(signals(atMs = 45_000, speed = 0.0, stable = false)).state

        if (seen.any { it == ParkingState.PARKED }) {
            failures += "Đèn đỏ: đã vào PARKED dù vị trí dao động (states=$seen)."
        }
        val final = h.state()
        if (final.state != ParkingState.STOPPED_TEMPORARILY) {
            failures += "Đèn đỏ: mong STOPPED_TEMPORARILY, nhận ${final.state} (${final.reason})."
        }
    }

    /** Ten minutes stationary with a stable position is a parked car. */
    private fun parkedTenMinutes(failures: MutableList<String>) {
        val h = harness()
        var t = 0L
        while (t <= 600_000L) {
            h.resolve(signals(atMs = t, speed = 0.0, stable = true))
            t += 60_000L
        }
        val final = h.state()
        if (final.state != ParkingState.PARKED) {
            failures += "Đỗ 10 phút: mong PARKED, nhận ${final.state} (${final.reason})."
        }
        if (final.confidence != ParkingConfidence.HIGH) {
            failures += "Đỗ 10 phút: mong HIGH, nhận ${final.confidence}."
        }
        if (final.reason != ParkingReason.GPS_HEURISTIC) {
            failures += "Đỗ 10 phút: mong GPS_HEURISTIC, nhận ${final.reason}."
        }
    }

    /**
     * One 40 km/h sample after parking must not dismiss the screen.
     *
     * 40 km/h is 11.1 m/s, well over the exit threshold, and is the classic
     * under-a-bridge artifact. The exit hold window is what saves it.
     */
    private fun singleSpikeDoesNotExit(failures: MutableList<String>) {
        val h = parkedHarness()
        if (!h.resolver.isParked()) {
            failures += "GPS spike: không vào được PARKED trước khi kiểm tra."
            return
        }
        h.resolve(signals(atMs = 610_000, speed = 11.11, stable = true))
        val afterSpike = h.resolve(signals(atMs = 611_000, speed = 0.0, stable = true))
        if (afterSpike.state != ParkingState.PARKED) {
            failures += "GPS spike 40 km/h: thoát khỏi PARKED sau một mẫu (${afterSpike.state})."
        }
    }

    /**
     * Driving away must actually leave PARKED.
     *
     * The mirror of the spike case: the hold window delays exit, it does not
     * cancel it. A resolver that latched into PARKED would pass every "does
     * not exit" test while leaving the driver stuck on the clock screen.
     */
    private fun sustainedMovementExits(failures: MutableList<String>) {
        val h = parkedHarness()
        var t = 601_000L
        while (t <= 610_000L) {
            h.resolve(signals(atMs = t, speed = 11.11, stable = true))
            t += 1_000L
        }
        val final = h.state()
        if (final.state == ParkingState.PARKED) {
            failures += "Chạy đi: vẫn ở PARKED sau 9 s tốc độ trên ngưỡng thoát."
        }
        if (final.reason != ParkingReason.SPEED_ABOVE_THRESHOLD) {
            failures += "Chạy đi: mong SPEED_ABOVE_THRESHOLD, nhận ${final.reason}."
        }
    }

    /** A call while stationary keeps the map; it is not a parking space. */
    private fun callBlocksAutoEntry(failures: MutableList<String>) {
        val h = harness()
        var t = 0L
        while (t <= 300_000L) {
            h.resolve(signals(atMs = t, speed = 0.0, stable = true, callActive = true))
            t += 60_000L
        }
        val final = h.state()
        if (final.state == ParkingState.PARKED) {
            failures += "Cuộc gọi: vẫn vào PARKED khi đang gọi."
        }
        if (final.reason != ParkingReason.CALL_ACTIVE) {
            failures += "Cuộc gọi: mong reason CALL_ACTIVE, nhận ${final.reason}."
        }
    }

    /** Ignition off is the strongest available signal and needs no dwell. */
    private fun ignitionOffParks(failures: MutableList<String>) {
        val h = harness()
        val decision = h.resolve(
            signals(atMs = 1_000, speed = 0.0, stable = null, ignitionOff = true)
        )
        if (decision.state != ParkingState.PARKED) {
            failures += "Tắt máy: mong PARKED, nhận ${decision.state} (${decision.reason})."
        }
        if (decision.confidence != ParkingConfidence.HIGH) {
            failures += "Tắt máy: mong HIGH, nhận ${decision.confidence}."
        }
        if (decision.reason != ParkingReason.VEHICLE_IGNITION_OFF) {
            failures += "Tắt máy: mong VEHICLE_IGNITION_OFF, nhận ${decision.reason}."
        }
    }

    /**
     * A unit whose bus is silent and which has no fix has no answer.
     *
     * The brief's second constraint: no invented bus fields. A resolver that
     * converted every null to false would report DRIVING here; the correct
     * report is that nothing is known.
     */
    private fun noSignalsIsUnknown(failures: MutableList<String>) {
        val h = harness()
        val decision = h.resolve(ParkingSignals(elapsedRealtimeMs = 5_000))
        if (decision.state != ParkingState.UNKNOWN) {
            failures += "Không tín hiệu: mong UNKNOWN, nhận ${decision.state}."
        }
        if (decision.reason != ParkingReason.INSUFFICIENT_SIGNALS) {
            failures += "Không tín hiệu: mong INSUFFICIENT_SIGNALS, nhận ${decision.reason}."
        }
        if (decision.confidence != ParkingConfidence.NONE) {
            failures += "Không tín hiệu: mong NONE, nhận ${decision.confidence}."
        }
    }

    /** The driver's button beats every inference, including a blocker. */
    private fun manualWins(failures: MutableList<String>) {
        val h = harness()
        val decision = h.resolve(
            signals(atMs = 1_000, speed = 7.0, stable = false, callActive = true, manual = true)
        )
        if (decision.state != ParkingState.PARKED) {
            failures += "Yêu cầu thủ công: mong PARKED, nhận ${decision.state}."
        }
        if (decision.reason != ParkingReason.MANUAL) {
            failures += "Yêu cầu thủ công: mong MANUAL, nhận ${decision.reason}."
        }
        if (decision.confidence != ParkingConfidence.HIGH) {
            failures += "Yêu cầu thủ công: mong HIGH, nhận ${decision.confidence}."
        }
    }

    /**
     * Losing the fix is not parking.
     *
     * The tunnel and the car-park-under-concrete case: speed is null, so the
     * only honest answer is UNKNOWN even after minutes of silence. A resolver
     * that treated null as zero would park the car in every dead zone on the
     * route.
     */
    private fun lostFixDoesNotPark(failures: MutableList<String>) {
        val h = harness()
        val decision = h.resolve(signals(atMs = 900_000, speed = null, stable = null))
        if (decision.state == ParkingState.PARKED) {
            failures += "Mất GPS: đã kết luận PARKED khi không có fix."
        }
        if (decision.state != ParkingState.UNKNOWN) {
            failures += "Mất GPS: mong UNKNOWN, nhận ${decision.state}."
        }

        // Dwell must not accrue while the receiver is dark. One minute of
        // clean dwell, a three-minute tunnel, then a stationary fix: the car
        // has been observed standing still for one minute, not four, so it is
        // a candidate at most — never PARKED.
        val gap = harness()
        var t = 0L
        while (t <= 20_000L) {
            gap.resolve(signals(atMs = t, speed = 0.0, stable = true))
            t += 10_000L
        }
        var dark = 21_000L
        while (dark <= 200_000L) {
            gap.resolve(signals(atMs = dark, speed = null, stable = null))
            dark += 10_000L
        }
        val afterGap = gap.resolve(signals(atMs = 201_000, speed = 0.0, stable = true))
        if (afterGap.state == ParkingState.PARKED) {
            failures += "Mất GPS 3 phút: thời gian dừng bị cộng dồn và vào PARKED."
        }
    }

    /**
     * The parking brake is applied at every long red light, so it may not open
     * the screen by itself even with auto-enter on.
     */
    private fun parkingBrakeAloneIsCandidate(failures: MutableList<String>) {
        val h = harness()
        val decision = h.resolve(
            ParkingSignals(
                vehicleParkingBrake = true,
                speedMps = 0.0,
                speedAccuracyMeters = 6f,
                locationStable = false,
                elapsedRealtimeMs = 1_000
            )
        )
        if (decision.state == ParkingState.PARKED) {
            failures += "Chỉ phanh tay: đã tự vào PARKED."
        }
        if (decision.state != ParkingState.PARKING_CANDIDATE) {
            failures += "Chỉ phanh tay: mong PARKING_CANDIDATE, nhận ${decision.state}."
        }
        if (decision.reason != ParkingReason.VEHICLE_PARKING_BRAKE) {
            failures += "Chỉ phanh tay: mong VEHICLE_PARKING_BRAKE, nhận ${decision.reason}."
        }
    }

    /**
     * Auto-entry off means the screen never opens by itself, however strong
     * the evidence — the setting is why the feature can ship at all.
     */
    private fun autoEnterOffStaysCandidate(failures: MutableList<String>) {
        val h = harness()
        val decision = h.resolve(
            ParkingSignals(
                vehicleIgnitionOff = true,
                speedMps = 0.0,
                speedAccuracyMeters = 6f,
                elapsedRealtimeMs = 1_000
            ),
            allowAutoEnter = false
        )
        if (decision.state == ParkingState.PARKED) {
            failures += "Tự động vào đang tắt: vẫn vào PARKED."
        }
        if (decision.state != ParkingState.PARKING_CANDIDATE) {
            failures += "Tự động vào đang tắt: mong PARKING_CANDIDATE, nhận ${decision.state}."
        }
    }

    /**
     * A truncated preferences row must degrade to UNKNOWN, never throw.
     *
     * Written as a scenario rather than a unit test because it exercises the
     * store and the resolver's shared vocabulary: a restart on this unit
     * happens on a power cut, so this path runs on real hardware far more often
     * than the happy path does.
     */
    private fun corruptStoreIsUnknown(failures: MutableList<String>) {
        val backing = HashMap<String, String>()
        val store = object : KeyValueStore {
            override fun get(key: String): String? = backing[key]
            override fun put(key: String, value: String) {
                backing[key] = value
            }
        }
        val stateStore = ParkingStateStore(store) { 0L }
        store.put(ParkingStateStore.KEY, "TS8PK1\nstate=PARKED\nconfidence=HIGH\nreason=GPS_HEUR")
        val restored = stateStore.restore()
        if (restored.state != ParkingState.UNKNOWN) {
            failures += "Bản ghi hỏng: mong UNKNOWN, nhận ${restored.state}."
        }
        if (restored.reason != ParkingReason.INSUFFICIENT_SIGNALS) {
            failures += "Bản ghi hỏng: mong INSUFFICIENT_SIGNALS, nhận ${restored.reason}."
        }
    }

    // -- helpers -----------------------------------------------------------

    /**
     * A resolver whose clock tracks the newest snapshot it is handed.
     *
     * Deliberately not a clock fixed at zero: the resolver rejects a snapshot
     * older than [ParkingStateResolver.Config.maxSnapshotAgeMs], and the
     * ten-minute scenarios would then be read as stale instead of as dwell.
     * Freshness has its own semantics; each scenario should fail for the rule
     * it is testing.
     */
    private class Harness {
        var now: Long = 0L
        val resolver: ParkingStateResolver =
            ParkingStateResolver(clock = { now }, config = ParkingStateResolver.Config())

        fun resolve(signals: ParkingSignals, allowAutoEnter: Boolean = true): ParkingDecision {
            now = signals.elapsedRealtimeMs
            return resolver.resolve(signals, allowAutoEnter)
        }

        fun state(): ParkingDecision = resolver.current()
    }

    private fun harness(): Harness = Harness()

    /** A harness already parked through the ten-minute stable-dwell path. */
    private fun parkedHarness(): Harness {
        val h = harness()
        var t = 0L
        while (t <= 600_000L) {
            h.resolve(signals(atMs = t, speed = 0.0, stable = true))
            t += 60_000L
        }
        return h
    }

    private fun signals(
        atMs: Long,
        speed: Double?,
        stable: Boolean?,
        callActive: Boolean = false,
        ignitionOff: Boolean? = null,
        manual: Boolean? = null
    ): ParkingSignals = ParkingSignals(
        vehicleGearPark = null,
        vehicleAccOn = null,
        vehicleParkingBrake = null,
        vehicleIgnitionOff = ignitionOff,
        speedMps = speed,
        speedAccuracyMeters = if (speed == null) null else 6f,
        locationStable = stable,
        navigationActive = false,
        callActive = callActive,
        reverseCameraActive = false,
        explicitManualRequest = manual,
        elapsedRealtimeMs = atMs
    )
}

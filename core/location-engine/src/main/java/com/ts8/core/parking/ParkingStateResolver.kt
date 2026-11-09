package com.ts8.core.parking

/**
 * The five parking situations the launcher may show, exactly as §19 names them.
 *
 * [STOPPED_TEMPORARILY] exists so a red light has somewhere honest to land. The
 * alternative — two states, driving and parked — forces every stationary second
 * to be classified as one of them, and a detector that must choose always ends
 * up showing the parking clock at a junction. [PARKING_CANDIDATE] is the
 * evidence-is-there-but-the-screen-is-not state: signals agree the car is
 * parked, but a blocker (a call, the reverse camera, an active route) or the
 * auto-enter setting keeps the map on screen.
 */
enum class ParkingState {
    /** Moving, or stopped only long enough to be traffic. */
    DRIVING,

    /** Below the stop threshold, but not long enough to be a candidate. */
    STOPPED_TEMPORARILY,

    /** Evidence of parking exists, but entry is blocked or not permitted. */
    PARKING_CANDIDATE,

    /** Confirmed parked; the parking screen may be shown. */
    PARKED,

    /** Not enough trustworthy information to say anything. */
    UNKNOWN
}

/**
 * How much the [ParkingState] is trusted.
 *
 * Only [HIGH] may open the parking screen automatically. [MEDIUM] is the
 * parking-brake-alone case: real evidence, but the same reading occurs at every
 * long red light, so it stays a candidate. [NONE] belongs to [ParkingState.UNKNOWN]
 * only — a decision never claims confidence about nothing.
 */
enum class ParkingConfidence { NONE, LOW, MEDIUM, HIGH }

/**
 * Why the state was chosen, for diagnostics.
 *
 * The spec requires reason and confidence to be stored rather than inferred
 * later, because "the screen showed" and "the screen was correct" are different
 * questions and only the recorded reason can answer the second. Each value maps
 * to exactly one branch in [ParkingStateResolver.resolve], so a bug report that
 * says `CALL_ACTIVE` points at one place in the code.
 */
enum class ParkingReason {
    /** Gear selector reported Park. */
    VEHICLE_GEAR_PARK,

    /** Ignition reported off. */
    VEHICLE_IGNITION_OFF,

    /** Parking brake reported applied. Never enough on its own for auto entry. */
    VEHICLE_PARKING_BRAKE,

    /** The driver pressed the manual button. */
    MANUAL,

    /** Speed, dwell and location stability agreed; the fallback path. */
    GPS_HEURISTIC,

    /** The car is moving faster than the stop threshold. */
    SPEED_ABOVE_THRESHOLD,

    /** No usable signal, or the snapshot was too old to conclude anything. */
    INSUFFICIENT_SIGNALS,

    /** A phone call is in progress; automatic entry is suppressed. */
    CALL_ACTIVE,

    /** The reverse camera is showing; automatic entry is suppressed. */
    REVERSE_ACTIVE,

    /** Guidance is running; automatic entry is suppressed. */
    NAVIGATION_ACTIVE
}

/**
 * One resolved parking decision.
 *
 * [evidence] is a short Vietnamese explanation for the diagnostics screen. It
 * deliberately contains **no coordinates and no position at all** — the type
 * that produces it never receives a position, and [ParkingStateStore] writes
 * this string to disk. A parked car's coordinates are the most sensitive thing
 * the launcher handles; keeping them out of the type makes "the evidence log
 * leaked where the car was" impossible rather than merely discouraged.
 */
data class ParkingDecision(
    val state: ParkingState,
    val confidence: ParkingConfidence,
    val reason: ParkingReason,
    val evidence: String
) {
    /** True when the parking screen may actually be shown. */
    val showsParkingScreen: Boolean
        get() = state == ParkingState.PARKED && confidence == ParkingConfidence.HIGH

    companion object {
        /**
         * The honest no-answer. Every failure path resolves to this instead of
         * to a plausible-looking PARKED or DRIVING, so a caller that forgets to
         * check has nothing to show rather than the wrong screen.
         */
        val UNKNOWN = ParkingDecision(
            state = ParkingState.UNKNOWN,
            confidence = ParkingConfidence.NONE,
            reason = ParkingReason.INSUFFICIENT_SIGNALS,
            evidence = "Chưa đủ tín hiệu để xác định trạng thái đỗ."
        )
    }
}

/**
 * Resolves [ParkingSignals] into a [ParkingDecision].
 *
 * **Why one resolver and not "speed == 0".** The brief's first sentence in §18
 * is the failure this class exists to prevent: GPS speed zero means a red
 * light exactly as often as it means a parking space, and losing GPS produces
 * the same zero. The old shortcut therefore cannot be repaired by tuning a
 * threshold; it has to be replaced by priority-ordered evidence, which is what
 * the three branches below are.
 *
 * **Priority, implemented in this order:**
 * 1. **Explicit vehicle signals**, when this unit actually reports them:
 *    ignition off or gear in Park are definitive ([ParkingConfidence.HIGH]);
 *    parking brake alone is [ParkingConfidence.MEDIUM] because it is applied
 *    at every long red light too. Without a contract from the MCU these are
 *    nullable, and a null signal is skipped rather than read as false.
 * 2. **Manual driver request.** It is a direct instruction and outranks the
 *    heuristic completely. A manual *exit* is handled even before the vehicle
 *    signals, because a dismissal that a gear reading could overrule would be
 *    a button that does not work.
 * 3. **The GPS heuristic**, only when independently corroborated: speed below
 *    the stop threshold **and** the car has held that for the dwell window
 *    **and** the location engine reports the position stable. A red light
 *    fails dwell and usually stability, so it lands on
 *    [ParkingState.STOPPED_TEMPORARILY] and never on a candidate.
 *
 * Measured motion contradicts *evidence* but not an *instruction*: a vehicle
 * signal that says parked while the car is moving is a stale bus reading, so
 * it is reported as driving; a manual request is honoured as asked.
 *
 * **Why null signals are skipped rather than folded into false.** Field 18
 * forbids inventing a bus contract. A unit whose firmware does not report the
 * gear selector sends null on every tick; if that null became false the
 * resolver would think it had evidence the car is not in Park, and either
 * never park or (worse, if the branch were inverted) park on silence. Null
 * simply does not vote.
 *
 * **Anti-chatter.** Entry needs the dwell window, which no red light survives.
 * Exit from [ParkingState.PARKED] needs speed above the exit threshold
 * *sustained* for the hold window, so the single 40 km/h spike every receiver
 * emits under trees or a bridge cannot dismiss the screen. Once parked, losing
 * the bus signal or the GPS fix does not exit either: silence is not evidence
 * of movement.
 *
 * **Threading.** Single-threaded by contract: the main looper owns this object
 * and every method is called from there. There is no synchronisation because
 * adding it would hide the real requirement — the signals snapshot and the
 * setting read must be taken on the same thread as the decision, or the
 * resolver can be told the auto-enter setting is on after it decided with it
 * off. Calling from any other thread is a bug, not a supported mode.
 *
 * **Purity.** No Android imports: neither `SystemClock`, `Looper` nor a
 * `Context` is reachable from here. Time arrives through [clock] and the
 * platform's own state through [ParkingSignals], which is what lets
 * [ParkingScenarios] exercise every threshold on the build host without
 * Robolectric.
 */
class ParkingStateResolver(
    /** Injected rather than read from `SystemClock` so the class stays pure. */
    private val clock: () -> Long,
    private val config: Config = Config()
) {

    /**
     * Thresholds and windows, in one immutable object.
     *
     * Defaults are chosen for this head unit, not for a phone: the stop
     * threshold is 3.6 km/h because GPS under a car park roof jitters by about
     * that much; the exit threshold is 10.8 km/h so a creep out of a space
     * does not dismiss the screen; the park dwell is two minutes because a
     * queue at a junction can hold a car for ninety seconds; the hold window
     * is six seconds because a GPS spike lasts one or two samples.
     */
    data class Config(
        /** At or below this the car counts as standing still, in m/s. */
        val stopSpeedMps: Double = 1.0,
        /** Above this sustained for [exitHoldMs] the car counts as driving. */
        val exitSpeedMps: Double = 3.0,
        /** A fix worse than this cannot say whether the car moved, in metres. */
        val maxSpeedAccuracyMeters: Float = 40f,
        /** A snapshot older than this is not a description of the car now. */
        val maxSnapshotAgeMs: Long = 5_000L,
        /** Dwell needed before the heuristic even calls it a candidate. */
        val candidateDwellMs: Long = 20_000L,
        /** Dwell needed before the heuristic may call it parked. */
        val parkDwellMs: Long = 120_000L,
        /** How long speed must stay above the exit threshold to leave PARKED. */
        val exitHoldMs: Long = 6_000L,
        /**
         * How long a fix may be missing, and dwell still be carried over.
         *
         * Dwell is measured time, and time without a fix is not measured. A
         * receiver that drops out at a red light for two minutes and returns
         * still stationary must not be credited with two minutes of dwell —
         * that turns the most common dead zone into a false parking screen.
         * A short gap (one lost sample, one rejected fix) is ignored so a
         * jittery receiver does not restart the clock forever.
         */
        val noFixGraceMs: Long = 15_000L
    )

    private var last: ParkingDecision = ParkingDecision.UNKNOWN
    private var lastEvaluatedMs: Long = -1L

    /** True once a decision has put the car in [ParkingState.PARKED]. */
    private var parked = false

    /** Start of the current below-stop-threshold stretch, or -1. */
    private var stationarySinceMs: Long = -1L

    /** Start of the current above-exit-threshold stretch, or -1. */
    private var movingSinceMs: Long = -1L

    /** When the current no-fix stretch began, or -1 while a fix is present. */
    private var noFixSinceMs: Long = -1L

    /** Set by an explicit "thoát" so the heuristic cannot re-enter at once. */
    private var manualExitLatched = false

    private var lastParkReason: ParkingReason = ParkingReason.GPS_HEURISTIC
    private var lastParkConfidence: ParkingConfidence = ParkingConfidence.MEDIUM

    /**
     * Evaluates one snapshot.
     *
     * @param allowAutoEnter the caller's read of the "tự vào chế độ đỗ xe"
     *        setting. It is a parameter rather than a field because the driver
     *        can change it while parked and the next tick must honour the new
     *        value; caching it in the constructor would make the checkbox
     *        appear to do nothing until the process restarts.
     * @return the decision, also available through [current]. Never null: a
     *         caller that has signals but no answer gets [ParkingState.UNKNOWN],
     *         not a made-up DRIVING.
     */
    @JvmOverloads
    fun resolve(signals: ParkingSignals, allowAutoEnter: Boolean = false): ParkingDecision {
        val now = signals.elapsedRealtimeMs

        // A snapshot stamped before the last one is a caller bug (two sources
        // feeding the resolver, or a clock read from the wrong source). Reusing
        // dwell accumulated against a different timeline would invent duration,
        // so the history is dropped and the snapshot is evaluated as the first
        // one of a new stretch.
        if (lastEvaluatedMs >= 0L && now < lastEvaluatedMs) {
            resetTracking()
        }
        lastEvaluatedMs = now

        val age = clock() - now
        if (age > config.maxSnapshotAgeMs) {
            return publish(
                ParkingDecision.UNKNOWN.copy(
                    evidence = "Mẫu tín hiệu đã cũ (${age} ms); không dùng để kết luận."
                )
            )
        }

        // A manual *exit* is handled first and alone. It is the one instruction
        // that must never be overruled by the evidence it is dismissing —
        // otherwise a gear reading would make the exit button appear dead.
        if (signals.explicitManualRequest == false) {
            val wasParked = parked
            parked = false
            manualExitLatched = true
            movingSinceMs = -1L
            val exitSpeed = signals.speedMps
            val moving = exitSpeed != null && exitSpeed >= config.stopSpeedMps
            return publish(
                ParkingDecision(
                    state = if (moving) ParkingState.DRIVING else ParkingState.STOPPED_TEMPORARILY,
                    confidence = ParkingConfidence.LOW,
                    reason = ParkingReason.MANUAL,
                    evidence = if (wasParked) {
                        "Người lái yêu cầu thoát chế độ đỗ xe."
                    } else {
                        "Người lái yêu cầu thoát; giữ nguyên cho tới khi xe chạy đủ lâu."
                    }
                )
            )
        }

        val speed = signals.speedMps
        val trustworthySpeed = speed != null && isSpeedAccurate(signals.speedAccuracyMeters)
        val aboveExit = trustworthySpeed && speed!! >= config.exitSpeedMps
        val aboveStop = trustworthySpeed && speed!! >= config.stopSpeedMps

        if (aboveExit) {
            if (movingSinceMs < 0L) movingSinceMs = now
        } else {
            movingSinceMs = -1L
        }
        val exitSustained = aboveExit && now - movingSinceMs >= config.exitHoldMs

        // Already parked: hold through a single spike, a lost fix or a silent
        // bus. Only sustained movement or the manual exit above ends it, which
        // is exactly the "không thoát chỉ vì một GPS spike" rule.
        if (parked) {
            if (exitSustained) {
                parked = false
                manualExitLatched = false
                stationarySinceMs = -1L
                return publish(
                    ParkingDecision(
                        state = ParkingState.DRIVING,
                        confidence = ParkingConfidence.HIGH,
                        reason = ParkingReason.SPEED_ABOVE_THRESHOLD,
                        evidence = "Xe đã chạy trên ngưỡng thoát liên tục ${config.exitHoldMs} ms; thoát chế độ đỗ."
                    )
                )
            }
            // A manual "vào" while already parked must still change the
            // recorded reason: diagnostics asked "who decided this", and the
            // answer changed even though the state did not.
            if (signals.explicitManualRequest == true) {
                lastParkReason = ParkingReason.MANUAL
                lastParkConfidence = ParkingConfidence.HIGH
            }
            return publish(
                ParkingDecision(
                    state = ParkingState.PARKED,
                    confidence = lastParkConfidence,
                    reason = lastParkReason,
                    evidence = if (aboveExit) {
                        "Có xóc tốc độ ngắn nhưng chưa đủ thời gian giữ; vẫn giữ trạng thái đỗ."
                    } else {
                        "Tín hiệu đỗ còn hiệu lực; chỉ thoát khi xe chạy liên tục."
                    }
                )
            )
        }

        // A manual exit stands until the car has actually driven away.
        if (manualExitLatched) {
            if (exitSustained) {
                manualExitLatched = false
            } else {
                return publish(
                    ParkingDecision(
                        state = if (aboveStop) ParkingState.DRIVING else ParkingState.STOPPED_TEMPORARILY,
                        confidence = ParkingConfidence.LOW,
                        reason = ParkingReason.MANUAL,
                        evidence = "Đã thoát theo yêu cầu; chưa vào lại cho tới khi xe chạy đủ lâu."
                    )
                )
            }
        }

        // Measured motion contradicts *evidence*: a bus line still reading
        // "P" while the car is doing 30 km/h is a stale frame, so the speed
        // wins. It does not contradict an *instruction*, which is why this
        // check is skipped when the driver asked to enter parking.
        if (aboveStop && signals.explicitManualRequest != true) {
            stationarySinceMs = -1L
            return publish(
                ParkingDecision(
                    state = ParkingState.DRIVING,
                    confidence = ParkingConfidence.HIGH,
                    reason = ParkingReason.SPEED_ABOVE_THRESHOLD,
                    evidence = "Tốc độ ${formatMps(speed!!)} m/s, trên ngưỡng dừng."
                )
            )
        }

        // 1. Explicit vehicle signals, when this unit reports them.
        val vehicle = vehicleEvidence(signals)
        if (vehicle != null) {
            return publish(automatic(signals, allowAutoEnter, now, vehicle))
        }

        // 2. The driver's own instruction. It outranks the heuristic and is
        //    not an inference, so it needs no corroboration and no dwell.
        if (signals.explicitManualRequest == true) {
            parked = true
            manualExitLatched = false
            movingSinceMs = -1L
            if (stationarySinceMs < 0L) stationarySinceMs = now
            lastParkReason = ParkingReason.MANUAL
            lastParkConfidence = ParkingConfidence.HIGH
            return publish(
                ParkingDecision(
                    state = ParkingState.PARKED,
                    confidence = ParkingConfidence.HIGH,
                    reason = ParkingReason.MANUAL,
                    evidence = "Người lái yêu cầu vào chế độ đỗ xe."
                )
            )
        }

        // 3. GPS heuristic. Speed alone never concludes parking: a fix worse
        //    than the accuracy limit says nothing about movement, and no fix at
        //    all is the tunnel case the brief calls out explicitly.
        val fixUsable = speed != null && isSpeedAccurate(signals.speedAccuracyMeters)
        if (!fixUsable) {
            if (noFixSinceMs < 0L) noFixSinceMs = now
            // A gap longer than the grace period means the dwell the car
            // "held" was never observed; keeping it would park the car by the
            // length of a tunnel. The stretch restarts when the fix returns.
            if (now - noFixSinceMs > config.noFixGraceMs) stationarySinceMs = -1L
            return publish(
                ParkingDecision.UNKNOWN.copy(
                    reason = ParkingReason.INSUFFICIENT_SIGNALS,
                    evidence = if (speed == null) {
                        "Không có fix GPS; mất tín hiệu không có nghĩa là đã đỗ."
                    } else {
                        "Độ chính xác GPS ${formatAccuracy(signals.speedAccuracyMeters)} m, không đủ để xác định."
                    }
                )
            )
        }
        noFixSinceMs = -1L

        if (stationarySinceMs < 0L) stationarySinceMs = now
        val dwellMs = now - stationarySinceMs
        val stable = signals.locationStable == true

        if (dwellMs >= config.candidateDwellMs && stable) {
            val confidence = if (dwellMs >= config.parkDwellMs) {
                ParkingConfidence.HIGH
            } else {
                ParkingConfidence.MEDIUM
            }
            return publish(
                automatic(
                    signals = signals,
                    allowAutoEnter = allowAutoEnter,
                    nowMs = now,
                    candidate = Candidate(
                        confidence = confidence,
                        reason = ParkingReason.GPS_HEURISTIC,
                        evidence = "Dừng ${dwellMs / 1_000} s, vị trí ổn định, tốc độ dưới ngưỡng."
                    )
                )
            )
        }

        val evidence = when {
            dwellMs < config.candidateDwellMs ->
                "Dừng ${dwellMs / 1_000} s, chưa đủ thời gian để coi là ứng viên đỗ."
            signals.locationStable == false ->
                "Vị trí còn dao động; xe có thể đang dừng đèn đỏ."
            else ->
                "Chưa có kết luận ổn định vị trí; không đủ hai tín hiệu độc lập."
        }
        return publish(
            ParkingDecision(
                state = ParkingState.STOPPED_TEMPORARILY,
                confidence = if (stable) ParkingConfidence.MEDIUM else ParkingConfidence.LOW,
                reason = ParkingReason.GPS_HEURISTIC,
                evidence = evidence
            )
        )
    }

    /** The latest decision, or [ParkingDecision.UNKNOWN] before the first call. */
    fun current(): ParkingDecision = last

    /** True while the resolver holds the car in [ParkingState.PARKED]. */
    fun isParked(): Boolean = parked

    /** Forgets everything, e.g. when the process is torn down or GPS is released. */
    fun reset() {
        resetTracking()
        last = ParkingDecision.UNKNOWN
        lastEvaluatedMs = -1L
    }

    // -- internals ---------------------------------------------------------

    /**
     * A positive vehicle signal, with the confidence it earns.
     *
     * Only `true` is evidence. Explicit `false` is not counter-evidence: a car
     * in a space can be in N with the ignition in READY, and this unit's
     * "ignition off" line is absent on keyless variants. Treating those as
     * "not parked" would make the resolver refuse the one case it can prove.
     *
     * [ParkingSignals.vehicleAccOn] is deliberately absent from this list.
     * The radio runs in ACC while the car is parked, so `true` says nothing
     * about parking; and a voltage dip during cranking produces `false` for a
     * few hundred milliseconds, so it must not vote either. The field stays in
     * the snapshot because the diagnostics screen shows it and because a
     * future verified contract may give it meaning — but guessing at that
     * meaning now would be exactly the invented bus semantics §18 bans.
     */
    private fun vehicleEvidence(signals: ParkingSignals): Candidate? = when {
        signals.vehicleIgnitionOff == true -> Candidate(
            confidence = ParkingConfidence.HIGH,
            reason = ParkingReason.VEHICLE_IGNITION_OFF,
            evidence = "Tín hiệu xe báo đã tắt máy."
        )
        signals.vehicleGearPark == true -> Candidate(
            confidence = ParkingConfidence.HIGH,
            reason = ParkingReason.VEHICLE_GEAR_PARK,
            evidence = "Cần số báo vị trí P."
        )
        signals.vehicleParkingBrake == true -> Candidate(
            // Real evidence, but pulled at every long red light, so it can
            // never open the screen automatically.
            confidence = ParkingConfidence.MEDIUM,
            reason = ParkingReason.VEHICLE_PARKING_BRAKE,
            evidence = "Phanh tay đang bật; chưa đủ để tự động vào chế độ đỗ."
        )
        else -> null
    }

    /**
     * Applies the blockers, the auto-enter setting and the confidence gate to a
     * conclusion, and only then lets it become [ParkingState.PARKED].
     *
     * Keeping these three gates in one place is what stops one branch (say the
     * ignition signal) from bypassing a rule the others obey.
     */
    private fun automatic(
        signals: ParkingSignals,
        allowAutoEnter: Boolean,
        nowMs: Long,
        candidate: Candidate
    ): ParkingDecision {
        val blocker = blocker(signals)
        if (blocker != null) {
            return ParkingDecision(
                state = ParkingState.PARKING_CANDIDATE,
                confidence = ParkingConfidence.MEDIUM,
                reason = blocker,
                evidence = "${candidate.evidence} Nhưng ${blockerText(blocker)} nên không tự vào."
            )
        }
        if (!allowAutoEnter) {
            return ParkingDecision(
                state = ParkingState.PARKING_CANDIDATE,
                confidence = candidate.confidence,
                reason = candidate.reason,
                evidence = "${candidate.evidence} Chế độ tự động vào đang tắt."
            )
        }
        if (candidate.confidence != ParkingConfidence.HIGH) {
            return ParkingDecision(
                state = ParkingState.PARKING_CANDIDATE,
                confidence = candidate.confidence,
                reason = candidate.reason,
                evidence = candidate.evidence
            )
        }

        parked = true
        manualExitLatched = false
        movingSinceMs = -1L
        if (stationarySinceMs < 0L) stationarySinceMs = nowMs
        lastParkReason = candidate.reason
        lastParkConfidence = candidate.confidence
        return ParkingDecision(
            state = ParkingState.PARKED,
            confidence = candidate.confidence,
            reason = candidate.reason,
            evidence = candidate.evidence
        )
    }

    /** Which platform state forbids an automatic parking screen, if any. */
    private fun blocker(signals: ParkingSignals): ParkingReason? = when {
        signals.callActive -> ParkingReason.CALL_ACTIVE
        signals.reverseCameraActive -> ParkingReason.REVERSE_ACTIVE
        signals.navigationActive -> ParkingReason.NAVIGATION_ACTIVE
        else -> null
    }

    private fun blockerText(reason: ParkingReason): String = when (reason) {
        ParkingReason.CALL_ACTIVE -> "đang có cuộc gọi"
        ParkingReason.REVERSE_ACTIVE -> "camera lùi đang bật"
        ParkingReason.NAVIGATION_ACTIVE -> "đang dẫn đường"
        else -> "có tín hiệu khác đang bật"
    }

    private fun isSpeedAccurate(accuracyMeters: Float?): Boolean =
        accuracyMeters == null || accuracyMeters <= config.maxSpeedAccuracyMeters

    private fun publish(decision: ParkingDecision): ParkingDecision {
        last = decision
        return decision
    }

    private fun resetTracking() {
        parked = false
        stationarySinceMs = -1L
        movingSinceMs = -1L
        noFixSinceMs = -1L
        manualExitLatched = false
        lastParkReason = ParkingReason.GPS_HEURISTIC
        lastParkConfidence = ParkingConfidence.MEDIUM
    }

    /** One line per thousandth, because a "0" from 0.04 m/s would be a lie. */
    private fun formatMps(value: Double): String {
        val scaled = Math.round(value * 1000.0)
        return "${scaled / 1000},${Math.abs(scaled % 1000).toString().padStart(3, '0')}"
    }

    private fun formatAccuracy(value: Float?): String {
        if (value == null) return "không rõ"
        val scaled = Math.round(value * 10.0)
        return "${scaled / 10},${Math.abs(scaled % 10)}"
    }

    /** A conclusion before the blockers and the setting have had their say. */
    private data class Candidate(
        val confidence: ParkingConfidence,
        val reason: ParkingReason,
        val evidence: String
    )
}

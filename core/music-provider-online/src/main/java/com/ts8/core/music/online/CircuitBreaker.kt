package com.ts8.core.music.online

import java.util.concurrent.atomic.AtomicInteger

/**
 * Trips a provider out of the search path once it has proven itself broken.
 *
 * **The failure mode this prevents.** A provider that is down does not fail
 * fast — it fails at the *timeout*. With a 4 s connect budget and a 4 s read
 * budget, one dead provider costs eight seconds, and a search that fans out to
 * three providers costs that on every keystroke submittal. On this head unit
 * the search box is the screen the driver uses while moving; an eight-second
 * freeze per query is not a degraded feature, it is a hazard. The breaker
 * converts "slow and broken" into "refused immediately", which the gateway
 * turns into a status line instead of a wait.
 *
 * **Why a local counter instead of a library.** Hystrix and Resilience4j both
 * drag in a dependency graph this APK cannot afford to carry onto a head unit
 * with a slow sideload link, and neither offers anything this class does not:
 * a threshold, a cooldown and one probe.
 *
 * **Behaviour.** Calls are [allow]ed until [FAILURE_THRESHOLD] consecutive
 * failures. Then the breaker opens for [cooldownMs]; while open, [allow]
 * returns false and the caller should answer `CIRCUIT_OPEN` without touching
 * the network. When the cooldown has elapsed the breaker goes half-open and
 * permits exactly one probe: if it succeeds the breaker closes, if it fails it
 * reopens for another cooldown. Exactly one probe, because letting every
 * queued search through at the end of the cooldown rebuilds the same stampede
 * the breaker exists to stop.
 *
 * **Thread-safe without a lock on the hot path.** The state is a single
 * `AtomicInteger` and the clock is injected, so it is testable on the JVM and
 * safe to share between the gateway's executor threads. A racing pair of
 * threads at the half-open boundary may both be admitted in the same
 * microsecond; the cost is one extra probe, which is why this is not worth a
 * monitor.
 *
 * @param failureThreshold consecutive failures that open the breaker. Two, not
 *        five: a provider that times out twice in a row within one cooldown is
 *        not recovering between them.
 * @param cooldownMs how long the breaker stays open before a probe.
 * @param clock monotonic milliseconds — pass `SystemClock::elapsedRealtime` on
 *        device. Never wall-clock: the TS8's RTC is unreliable across ignition.
 */
class CircuitBreaker(
    private val failureThreshold: Int = DEFAULT_THRESHOLD,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /** What the breaker is currently doing. */
    enum class State {
        /** Calls pass through and failures are counted. */
        CLOSED,

        /** Calls are refused without touching the network. */
        OPEN,

        /** One probe is permitted; its result decides the next state. */
        HALF_OPEN
    }

    /** Consecutive failures while closed. Guarded by [this]. */
    private var consecutiveFailures = 0

    /** When the breaker opened, on the injected clock. Guarded by [this]. */
    private var openedAtMs = 0L

    /** True once the single half-open probe has been handed out. */
    private var probeInFlight = false

    /** When that probe was granted, so a lost one cannot wedge the breaker. */
    private var probeGrantedAtMs = 0L

    /**
     * Number of calls refused since construction. Exposed so the diagnostics
     * screen can show that a provider was skipped rather than empty.
     */
    private val refused = AtomicInteger(0)

    /**
     * True when the caller may perform the real request.
     *
     * A false answer is not an error condition to log per call — it is the
     * normal, desired outcome while a provider is down. The caller should
     * answer `Failure(CIRCUIT_OPEN)` and move on.
     */
    @Synchronized
    fun allow(): Boolean {
        when (state()) {
            State.CLOSED -> return true
            State.OPEN -> {
                refused.incrementAndGet()
                return false
            }
            State.HALF_OPEN -> {
                // Only the first caller after the cooldown gets through; the
                // rest wait for its verdict. Releasing them all is the
                // stampede this class exists to prevent.
                //
                // A probe that was granted and never reported back — the caller
                // threw an Error between allow() and recordFailure(), or the
                // process was suspended mid-request — would otherwise hold this
                // flag forever, and the provider could never be tried again.
                // Treating a probe older than one cooldown as lost makes that a
                // one-cooldown stall instead of a permanent one.
                if (probeInFlight && clock() - probeGrantedAtMs < cooldownMs) {
                    refused.incrementAndGet()
                    return false
                }
                probeInFlight = true
                probeGrantedAtMs = clock()
                return true
            }
        }
    }

    /**
     * Records a successful call.
     *
     * Closes the breaker from any state: a working probe is proof the provider
     * is back, and nothing else can be proof.
     */
    @Synchronized
    fun recordSuccess() {
        consecutiveFailures = 0
        openedAtMs = 0L
        probeInFlight = false
        probeGrantedAtMs = 0L
    }

    /**
     * Records a failed call.
     *
     * A failure while half-open reopens immediately regardless of the
     * threshold — the probe already spent the one request that was granted, so
     * counting it against a fresh budget would send traffic at a provider that
     * just failed the test it was given.
     */
    @Synchronized
    fun recordFailure() {
        if (state() == State.HALF_OPEN || probeInFlight) {
            probeInFlight = false
            probeGrantedAtMs = 0L
            openedAtMs = clock()
            consecutiveFailures = failureThreshold
            return
        }
        consecutiveFailures++
        if (consecutiveFailures >= failureThreshold) {
            openedAtMs = clock()
        }
    }

    /**
     * Current state, computed from the clock.
     *
     * Recomputed on read rather than stored as a third variable, because a
     * breaker opened an hour ago must report OPEN even if nothing called it
     * since; deriving it removes the timer that would otherwise have to exist
     * to flip it.
     */
    @Synchronized
    fun state(): State = when {
        consecutiveFailures < failureThreshold -> State.CLOSED
        clock() - openedAtMs >= cooldownMs -> State.HALF_OPEN
        else -> State.OPEN
    }

    /** Calls refused locally since construction, for diagnostics. */
    fun refusedCount(): Int = refused.get()

    /**
     * Forces the breaker closed. For the Settings screen's "Thử lại ngay"
     * button: a driver who has just fixed the server address should not have
     * to wait out a 30 s cooldown they did not cause.
     */
    @Synchronized
    fun reset() {
        consecutiveFailures = 0
        openedAtMs = 0L
        probeInFlight = false
        probeGrantedAtMs = 0L
    }

    companion object {
        /** Two consecutive timeouts is a pattern, not a blip. */
        const val DEFAULT_THRESHOLD = 2

        /**
         * 30 s. Long enough that a search burst sees an instant refusal after
         * the first two timeouts, short enough that a provider which came back
         * while the driver was parked is usable again without a restart.
         */
        const val DEFAULT_COOLDOWN_MS = 30_000L
    }
}

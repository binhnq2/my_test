package com.ts8.core.parking

/**
 * The only thing the parking feature is allowed to persist, in the launcher's
 * platform-free shape.
 *
 * **Why an interface and not `SharedPreferences`.** This module has to compile
 * and its scenarios have to run on a build host with no Android runtime and no
 * dependency downloads. A one-method interface lets the app pass a
 * `SharedPreferences`-backed adapter while [ParkingStateStore] and its codec
 * stay testable as plain Kotlin. It also keeps the "the resolver is pure"
 * property honest: nothing below can reach a `Context`.
 *
 * **Why only get/put.** A store with `remove` and `clear` invites a second,
 * subtly different lifecycle in the app; one key with one row means the whole
 * record is written or not written, which is the only atomicity this feature
 * needs.
 *
 * A plain `interface`, not a `fun interface`: two abstract methods are what
 * the caller actually needs, and Kotlin 1.9 refuses `fun interface` beyond one.
 * The app adapter is therefore a named object rather than a SAM lambda, which
 * is the right shape anyway — a preferences adapter has state.
 */
interface KeyValueStore {
    /** The stored string, or null when the key was never written or was removed. */
    fun get(key: String): String?

    /** Replaces the value for [key]. Implementations must not throw for a normal write. */
    fun put(key: String, value: String)
}

/**
 * A decision as it survives a process restart: the four values a screen needs
 * to come back looking the same, plus when it was taken.
 *
 * [savedAtElapsedMs] is the resolver's own clock, so it is only comparable
 * inside one boot — a head unit power-cycles constantly and `elapsedRealtime`
 * restarts at zero, which is why [ParkingStateStore] never computes an age
 * from it across a restart. It is stored to answer "was this written before or
 * after the last screen was shown", not to expire anything.
 */
data class StoredParkingDecision(
    val decision: ParkingDecision,
    val savedAtElapsedMs: Long
)

/**
 * Persists the last [ParkingDecision] so a reboot lands on the same screen.
 *
 * **Why this exists.** Parking mode is entered while the car is stationary and
 * often with the ignition about to go off; the launcher is then killed and
 * restarted (cold boot, vendor memory pressure). Recomputing from scratch
 * would show the map for the two minutes the resolver needs to prove dwell —
 * visible as the parking screen flicking to the map and back every time the
 * driver opens a door. Storing the state name, reason and confidence also
 * satisfies §19's requirement that the reason be kept for diagnostics, and it
 * records the confidence so a restart cannot promote a MEDIUM candidate into
 * a PARKED screen.
 *
 * **Why a corrupt value resolves to UNKNOWN instead of throwing.** This unit
 * loses power without a graceful shutdown, so a truncated preferences file is a
 * routine event, not an exceptional one. A store that threw on it would turn a
 * half-written row into a crash loop at boot — the one failure a head unit
 * cannot recover from, because the crash happens before any UI exists to let
 * the driver clear it. An unknown state costs the driver one manual entry; a
 * crash costs them the launcher.
 */
class ParkingStateStore(
    private val store: KeyValueStore,
    /** Injected for the same reason the resolver's is: no `SystemClock` here. */
    private val clock: () -> Long
) {

    /**
     * Writes [decision].
     *
     * Called on every change rather than on a timer, so the value on disk is
     * always the one the screen is showing. [evidence] is persisted because
     * the diagnostics screen reads it after a restart; it never contains a
     * position, which is a property of [ParkingDecision] rather than something
     * this class has to scrub.
     */
    fun save(decision: ParkingDecision) {
        runCatching {
            store.put(KEY, ParkingDecisionCodec.encode(StoredParkingDecision(decision, clock())))
        }
    }

    /**
     * Reads the last decision, or null when nothing valid was stored.
     *
     * Null is returned for "never saved" and for "saved but unreadable"; the
     * two are deliberately not distinguished, because the only caller behaviour
     * that matters is "show [ParkingDecision.UNKNOWN] and let the resolver
     * decide again".
     */
    fun load(): StoredParkingDecision? = runCatching {
        ParkingDecisionCodec.decode(store.get(KEY))
    }.getOrNull()

    /**
     * A decision that is safe to show after a restart.
     *
     * Never returns a fabricated PARKED: an absent or corrupt row yields
     * [ParkingDecision.UNKNOWN]. Note that a stored `PARKED` is returned as
     * stored — this class does not re-validate it against live signals, because
     * it has none; the caller is expected to feed the resolver and let the
     * anti-chatter rules correct it once the car actually moves.
     */
    fun restore(): ParkingDecision = load()?.decision ?: ParkingDecision.UNKNOWN

    /**
     * Overwrites the record with an empty value.
     *
     * [KeyValueStore] deliberately has no `remove`, because a preferences
     * adapter that removes a key can leave the file in a state the next writer
     * has to re-create; an empty value is a row that decodes to "nothing",
     * which is the same answer with fewer moving parts.
     */
    fun clear() {
        runCatching { store.put(KEY, "") }
    }

    private object ParkingDecisionCodec {
        private const val MAGIC = "TS8PK1"
        private const val END = "end"
        private const val MAX_INPUT = 8_000

        /** Line-oriented on purpose: no parser library, and a truncation is obvious. */
        fun encode(record: StoredParkingDecision): String = buildString {
            append(MAGIC).append('\n')
            append("state=").append(record.decision.state.name).append('\n')
            append("confidence=").append(record.decision.confidence.name).append('\n')
            append("reason=").append(record.decision.reason.name).append('\n')
            append("evidence=").append(sanitize(record.decision.evidence)).append('\n')
            append("saved_at=").append(record.savedAtElapsedMs).append('\n')
            // Explicit terminator: a row from an older build that lacks a newer
            // key must not decode the same as one truncated just before it.
            append(END).append('\n')
        }

        fun decode(raw: String?): StoredParkingDecision? {
            if (raw.isNullOrBlank() || raw.length > MAX_INPUT) return null
            val lines = raw.split('\n')
            if (lines.firstOrNull()?.trim() != MAGIC) return null
            if (lines.none { it.trim() == END }) return null

            var state: ParkingState? = null
            var confidence: ParkingConfidence? = null
            var reason: ParkingReason? = null
            var evidence = ""
            var savedAt = 0L
            var sawSavedAt = false

            for (line in lines.drop(1)) {
                if (line.isBlank() || line.trim() == END) continue
                val eq = line.indexOf('=')
                if (eq <= 0) return null
                val key = line.substring(0, eq)
                val value = line.substring(eq + 1)
                when (key) {
                    "state" -> state = runCatching { ParkingState.valueOf(value) }.getOrNull()
                        ?: return null
                    "confidence" -> confidence =
                        runCatching { ParkingConfidence.valueOf(value) }.getOrNull() ?: return null
                    "reason" -> reason = runCatching { ParkingReason.valueOf(value) }.getOrNull()
                        ?: return null
                    "evidence" -> evidence = value
                    "saved_at" -> {
                        savedAt = value.toLongOrNull() ?: return null
                        sawSavedAt = true
                    }
                    // Forward compatibility: a newer build may write keys this
                    // one does not know. Ignoring them is safe; failing is not.
                    else -> Unit
                }
            }

            if (!sawSavedAt) return null
            val s = state ?: return null
            val c = confidence ?: return null
            val r = reason ?: return null

            // An UNKNOWN record is written by the exit paths, so it is legal;
            // a PARKED record with NONE confidence is not — a stale row that
            // says so would let a restart show the parking screen with no
            // evidence behind it. Downgrade rather than trust it.
            if (s == ParkingState.PARKED && c == ParkingConfidence.NONE) {
                return StoredParkingDecision(ParkingDecision.UNKNOWN, savedAt)
            }
            return StoredParkingDecision(ParkingDecision(s, c, r, evidence), savedAt)
        }

        /**
         * Keeps the row one line per field.
         *
         * Evidence is Vietnamese prose assembled by the resolver, so it cannot
         * contain a newline today; sanitising anyway means a future reason
         * string with a line break cannot silently split the file into fields
         * that decode as different values.
         */
        private fun sanitize(text: String): String =
            text.replace('\n', ' ').replace('\r', ' ')
    }

    companion object {
        /** One row: the newest decision replaces the old one. */
        const val KEY = "parking_last_decision_v1"
    }
}

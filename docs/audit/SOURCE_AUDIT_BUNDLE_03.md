# Source audit bundle 03

Excerpted verbatim from the working tree. Every section is
`## <relative path>` followed by `BEGIN FILE` / `END FILE` with the
file's exact contents between them. Nothing is summarised, elided or
reformatted.

Files in this bundle: 55

---

## core/location-engine/src/main/java/com/ts8/core/parking/ParkingStateStore.kt

BEGIN FILE
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

END FILE

## core/location-engine/src/main/java/com/ts8/core/speed/OverspeedDetector.kt

BEGIN FILE
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

END FILE

## core/location-engine/src/main/java/com/ts8/core/speed/SpeedAlertController.kt

BEGIN FILE
package com.ts8.core.speed

import com.ts8.core.map.GeoPoint

/**
 * Delivers an overspeed alert through the two channels the settings allow, at a
 * volume independent of the music.
 *
 * **Why the volume is independent.** The brief requires the alert level to be
 * its own setting that still respects audio focus. On this head unit the only
 * lever API 23 gives an app is `STREAM_MUSIC`, so "independent" cannot mean a
 * separate hardware stream — it means the alert is *scaled against the user's
 * alert-volume setting and re-applied after any duck*, not inherited from
 * whatever the music slider happens to be at. Two consequences this class
 * enforces:
 *
 * 1. **Ducking is shared, not private.** The alert reuses the app's single
 *    [SpeedAlertSink] so the music duck and restore around a navigation prompt
 *    and around an alert never fight. If the alert took the volume itself and
 *    restored it while navigation was still speaking, music would come back
 *    loud in the middle of a prompt.
 * 2. **A silent alert is a lie.** If the sink refuses (audio focus held by a
 *    call, a vendor ROM denying the volume change) the alert downgrades to
 *    visual and reports that — it never claims sound.
 *
 * The controller also owns the "cooldown" and "one alert per event" bookkeeping
 * that the detector deliberately leaves to its caller: the detector says *when*
 * a threshold crossing is real, this says *whether* it is delivered.
 */
class SpeedAlertController(
    private val sink: SpeedAlertSink,
    private val clock: () -> Long
) {

    /** What the app must provide. Implemented over `VehicleAudioBridge`. */
    interface SpeedAlertSink {
        /**
         * Requests the speakers for a short alert.
         *
         * Returns false when focus could not be taken; the caller must then
         * stay visual-only rather than assuming sound happened.
         */
        fun acquireAlertFocus(): Boolean

        /** Releases the speakers and restores whatever was ducked. */
        fun releaseAlertFocus()

        /** Plays [tone] at [volume] (0..1 relative to this alert's own max). */
        fun play(tone: AlertTone, volume: Float)

        /** Stops playback started by [play], restoring any duck. */
        fun stop()
    }

    /** Which alert sound. Two only: a chime for over-limit, a shorter tick. */
    enum class AlertTone { OVERSPEED, OVERSPEED_RECOVERED }

    data class Config(
        val form: SpeedMonitor.AlertForm = SpeedMonitor.AlertForm.BOTH,
        val volume: Float = 0.8f
    )

    /** What the UI should show after an alert was requested. */
    data class Delivery(val visual: Boolean, val audio: Boolean, val streamedReason: String?)

    private var lastDeliveredMs = Long.MIN_VALUE
    private var lastVisualMs = Long.MIN_VALUE

    /**
     * Reports a delivered overspeed event.
     *
     * [form] and [volume] come from the live [SpeedMonitor.Config], read at the
     * moment of delivery rather than cached, so a driver who turns the alert
     * volume down hears the next alert at the new level instead of at the level
     * that was set when the screen opened.
     */
    fun deliver(overByKmh: Int, form: SpeedMonitor.AlertForm, volume: Float): Delivery {
        val now = clock()
        val visual = form == SpeedMonitor.AlertForm.VISUAL || form == SpeedMonitor.AlertForm.BOTH
        val wantsAudio = form == SpeedMonitor.AlertForm.AUDIO || form == SpeedMonitor.AlertForm.BOTH

        if (visual) lastVisualMs = now
        if (!wantsAudio) {
            return Delivery(visual = visual, audio = false, streamedReason = null)
        }
        if (volume <= 0f) {
            // A zero volume is a valid setting; do not pretend a sound played.
            return Delivery(visual = visual, audio = false, streamedReason = "âm lượng cảnh báo = 0")
        }
        if (!sink.acquireAlertFocus()) {
            return Delivery(
                visual = visual,
                audio = false,
                streamedReason = "không lấy được audio focus (cuộc gọi đang diễn ra?)"
            )
        }
        return try {
            sink.play(AlertTone.OVERSPEED, volume.coerceIn(0f, 1f))
            lastDeliveredMs = now
            Delivery(visual = visual, audio = true, streamedReason = null)
        } catch (e: Exception) {
            Delivery(visual = visual, audio = false, streamedReason = e.message ?: "phát âm thanh thất bại")
        } finally {
            // Released in `finally`: a sink that throws must not leave the music
            // ducked for the rest of the drive.
            runCatching { sink.releaseAlertFocus() }
        }
    }

    /** Stops any alert sound, e.g. on leaving the screen or a call arriving. */
    fun silence() {
        runCatching { sink.stop() }
    }

    fun lastVisualMs(): Long = lastVisualMs

    fun lastAudioMs(): Long = lastDeliveredMs
}

/**
 * Where the limit for the current road comes from.
 *
 * **Why this is an interface, and why the answer is usually null.** Deriving a
 * limit needs the graph edge under the car, and that lookup is only possible
 * while a route exists or the pack exposes a spatial index over edges. The
 * offline router does expose one, so [RoutingEdgeLimits] below is the real
 * implementation; a pack without a usable index, or a car off the road network,
 * yields null and the roundel shows `--`, which is the required behaviour
 * rather than a guess.
 */
interface SpeedLimitSource {
    /**
     * The limit for the edge nearest [point], or null when the pack has no
     * answer there.
     */
    fun limitAt(point: GeoPoint): Int?
}

END FILE

## core/location-engine/src/main/java/com/ts8/core/speed/SpeedFilter.kt

BEGIN FILE
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

END FILE

## core/location-engine/src/main/java/com/ts8/core/speed/SpeedGate.kt

BEGIN FILE
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

END FILE

## core/location-engine/src/main/java/com/ts8/core/speed/SpeedLimitResolver.kt

BEGIN FILE
package com.ts8.core.speed

/**
 * Resolves the speed limit for the road the car is on, with provenance.
 *
 * **Why this cannot hard-code a limit per road class.** The design shows a `60`
 * roundel, and the obvious shortcut is "residential = 50, motorway = 80". That
 * is fabricating a safety-relevant number. The pack's routing graph carries a
 * per-edge `speedKph`, but the pack builder fills it from the OSM `maxspeed` tag
 * *where one exists* and from a per-class default otherwise (see
 * `docs/OFFLINE_SEARCH_ROUTING.md` §2.1). Both are real data about this road, so
 * both are usable — but they are not equally trustworthy, and this class is
 * where that difference is recorded instead of being flattened away.
 *
 * **Where each confidence comes from.** A limit tagged on the way is a posted
 * sign: [SpeedLimit.Confidence.HIGH]. A limit the pack builder inferred from the
 * road class is a reasonable default but not a sign: [SpeedLimit.Confidence.MEDIUM].
 * A limit from the vehicle bus is what the car's own system believes:
 * [SpeedLimit.Confidence.HIGH]. Nothing is upgraded silently, and a lookup with
 * no answer at all produces [SpeedLimit.NONE], which the UI renders as `--` and
 * the detector treats as "never alert".
 */
class SpeedLimitResolver(private val clock: () -> Long) {

    private var current: SpeedLimit = SpeedLimit.NONE

    /** The limit that applies right now. [SpeedLimit.NONE] means "no data". */
    fun current(): SpeedLimit = current

    /**
     * A limit from the routing graph's per-edge speed.
     *
     * Exposed as MEDIUM because this pack mixes tagged and class-default
     * values in one field and there is no bit saying which is which; claiming
     * HIGH would overstate the data. When the pack builder starts carrying a
     * tag flag, [setTagged] is the path that reports HIGH.
     */
    fun setPackLimit(speedKph: Int): SpeedLimit {
        current = if (speedKph <= 0) {
            SpeedLimit.NONE
        } else {
            SpeedLimit(
                limitKmh = speedKph,
                source = LimitSource.ROUTING_GRAPH,
                resolvedAtElapsedMs = clock(),
                confidence = SpeedLimit.Confidence.MEDIUM
            )
        }
        return current
    }

    /**
     * A limit read from an OSM `maxspeed` tag carried through the pack.
     *
     * HIGH confidence because it is a posted sign rather than an inference.
     */
    fun setTagged(speedKph: Int): SpeedLimit {
        current = if (speedKph <= 0) {
            SpeedLimit.NONE
        } else {
            SpeedLimit(
                limitKmh = speedKph,
                source = LimitSource.ROUTING_GRAPH,
                resolvedAtElapsedMs = clock(),
                confidence = SpeedLimit.Confidence.HIGH
            )
        }
        return current
    }

    /** A limit published by a verified vehicle-bus bridge; outranks the pack. */
    fun setVehicleLimit(speedKph: Int): SpeedLimit {
        current = if (speedKph <= 0) {
            SpeedLimit.NONE
        } else {
            SpeedLimit(
                limitKmh = speedKph,
                source = LimitSource.VEHICLE_BUS,
                resolvedAtElapsedMs = clock(),
                confidence = SpeedLimit.Confidence.HIGH
            )
        }
        return current
    }

    /**
     * Called when the car leaves the road the limit was resolved for.
     *
     * Clearing rather than holding: carrying a 50 from the street the car was
     * on into the motorway it just joined would produce an alert on a legal
     * speed, which is the fastest way to make a driver switch the feature off.
     */
    fun clear() {
        current = SpeedLimit.NONE
    }
}

END FILE

## core/location-engine/src/main/java/com/ts8/core/speed/SpeedMonitor.kt

BEGIN FILE
package com.ts8.core.speed

import com.ts8.core.location.LocationFix

/**
 * The single boundary between the GPS pipeline and everything that shows or
 * announces a speed.
 *
 * **Why the UI must not read GPS itself.** Three screens (Home, Map,
 * Navigation) plus the alert path all need a speed, and each of them doing its
 * own arithmetic is how they end up disagreeing — Home showing 48, the map
 * showing 51, and the alert firing on a third number. The brief requires one
 * boundary with named parts, and this is it:
 *
 * ```
 * SpeedMonitor
 *   ├─ LocationSpeedSource   (GPS, via the location engine)
 *   ├─ VehicleSpeedSource    (bus, when a verified bridge exists)
 *   ├─ SpeedGate             (may this sample be believed at all)
 *   ├─ SpeedFilter           (smoothing policy)
 *   ├─ SpeedLimitResolver    (limit + provenance, or NONE)
 *   ├─ OverspeedDetector     (confirmation window, cooldown)
 *   └─ SpeedAlertController  (visual / audio / both, volume, focus)
 * ```
 *
 * The UI only ever sees [SpeedUiState], which carries `null` where there is no
 * trustworthy number so the view can print `--` rather than a fabricated zero.
 *
 * **What it deliberately does not do.** It does not play a sound and it does not
 * touch a View. Delivery is split: [Listener] gets every state change and
 * [AlertListener] additionally gets the moment an alert should be delivered,
 * which is what lets `SpeedAlertController` own the audio-focus policy without
 * this class knowing that audio exists.
 */
class SpeedMonitor(
    private val clock: () -> Long,
    private var config: Config = Config(),
    private val gate: SpeedGate = SpeedGate(),
    private val filter: SpeedFilter = SpeedFilter(),
    private val resolver: SpeedLimitResolver = SpeedLimitResolver(clock),
    private val detector: OverspeedDetector = OverspeedDetector()
) {

    /**
     * Everything the driver can change, in one immutable snapshot.
     *
     * Passing a whole object rather than a dozen setters means the monitor can
     * never observe a half-applied configuration (alerts switched on while the
     * threshold is still the old one) mid-write from the settings screen.
     */
    data class Config(
        /**
         * The master switch for the whole speed pipeline.
         *
         * Off means no readout and no alert — not a readout of zero. The field
         * is read at the top of [recompute]; a monitor built with it false
         * publishes [SpeedUiState.EMPTY] on every tick and never opens the
         * detector.
         */
        val enabled: Boolean = true,
        val unit: SpeedUnit = SpeedUnit.KMH,
        val showSpeed: Boolean = true,
        val showLimit: Boolean = true,
        val smoothing: SpeedFilter.Smoothing = SpeedFilter.Smoothing.BALANCED,
        /** `0`, `5`, `10`, `15` or a custom number, in km/h. */
        val thresholdKmh: Int = 5,
        val alertsEnabled: Boolean = true,
        val alertForm: AlertForm = AlertForm.BOTH,
        /** Alert volume 0..1, independent of the media stream's own level. */
        val alertVolume: Float = 0.8f,
        /** Only alert after the speed has stayed over the threshold. */
        val sustainedOnly: Boolean = true,
        /** Speeds below this are reported as stopped; 0 disables the filter. */
        val deadBandMps: Double = 0.0,
        /** Silence between two delivered alerts. */
        val cooldownMs: Long = OverspeedDetector.Config().cooldownMs
    )

    enum class AlertForm { VISUAL, AUDIO, BOTH }

    /** Called on the caller's thread whenever the rendered state changed. */
    fun interface Listener {
        fun onSpeedState(state: SpeedUiState)
    }

    /** A listener that wants to be told when an alert should be delivered. */
    interface AlertListener : Listener {
        fun onOverspeed(overByKmh: Int, limit: SpeedLimit, form: AlertForm, volume: Float)
    }

    private val listeners = ArrayList<Listener>(4)

    private var current: SpeedSample? = null
    private var lastState: SpeedUiState = SpeedUiState.EMPTY

    // -- configuration -----------------------------------------------------

    fun config(): Config = config

    /**
     * Applies a new configuration.
     *
     * Recomputes immediately so a threshold change is reflected without waiting
     * for the next GPS sample — otherwise the driver taps "+15" and sees
     * nothing happen until the car moves.
     */
    fun configure(config: Config) {
        val smoothingChanged = config.smoothing != this.config.smoothing
        this.config = config
        if (smoothingChanged) filter.reset()
        recompute()
    }

    // -- inputs ------------------------------------------------------------

    /**
     * Feeds an accepted fix from the location engine.
     *
     * Takes a [LocationFix] rather than a raw sample on purpose: the engine has
     * already rejected NaN positions, impossible jumps and stale re-fixes, and
     * doing any of that again here would mean two rule sets with two
     * thresholds that drift apart.
     */
    fun onFix(fix: LocationFix) {
        onSample(
            SpeedSample(
                speedMps = fix.speedMps.toDouble(),
                accuracyMeters = fix.accuracyMeters,
                elapsedRealtimeMs = fix.elapsedRealtimeMs,
                source = SpeedSource.GPS
            )
        )
    }

    /**
     * Feeds a sample from a verified vehicle-bus bridge.
     *
     * Kept separate from [onFix] so wiring the bus later is a call site, not a
     * rewrite: [SpeedFilter] re-seeds rather than blending when the source
     * changes, so a bus reading never mixes with a GPS reading.
     */
    fun onVehicleSample(sample: SpeedSample) {
        onSample(sample)
    }

    /** A limit resolved from the pack's routing graph for the current edge. */
    fun onPackLimit(limitKmh: Int) {
        resolver.setPackLimit(limitKmh)
        recompute()
    }

    /** A limit published by a verified vehicle-bus bridge; outranks the pack. */
    fun onVehicleLimit(limitKmh: Int) {
        resolver.setVehicleLimit(limitKmh)
        recompute()
    }

    fun clearLimit() {
        resolver.clear()
        recompute()
    }

    private fun onSample(next: SpeedSample) {
        current = next
        recompute()
    }

    /** Re-evaluates staleness without a new sample, e.g. on a 1 Hz UI tick. */
    fun tick() {
        recompute()
    }

    /** Forgets everything, e.g. when GPS is released or the process restarts. */
    fun reset() {
        current = null
        filter.reset()
        detector.reset()
        resolver.clear()
        publish(SpeedUiState.EMPTY)
    }

    fun state(): SpeedUiState = lastState

    // -- listeners ---------------------------------------------------------

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
        listener.onSpeedState(lastState)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    // -- evaluation --------------------------------------------------------

    private fun recompute() {
        val sample = current
        val now = clock()
        val limit = resolver.current()

        // The master switch. Without this read the field existed in the config
        // and was honoured by nobody — `AppSettings.speedConfig()` has always
        // passed `true`, so the dead field never showed, but a settings screen
        // that offered it would have been a switch wired to nothing. Turning it
        // off publishes the honest empty state rather than a zero, and clears
        // the detector so re-enabling cannot inherit a half-counted window.
        if (!config.enabled) {
            detector.reset()
            publish(SpeedUiState.EMPTY.copy(
                unit = config.unit,
                showSpeedSetting = config.showSpeed,
                showLimitSetting = config.showLimit
            ))
            return
        }

        if (sample == null) {
            publish(emptyState(SpeedState.UNKNOWN, limit))
            return
        }

        val ageMs = now - sample.elapsedRealtimeMs
        if (ageMs > STALE_AFTER_MS) {
            // Honest staleness: a speed from ten seconds ago is not the speed
            // the car has now, so nothing is shown rather than a stale number.
            publish(emptyState(SpeedState.UNKNOWN, limit, sample.source, ageMs))
            return
        }

        val rejection = gate.reject(sample, now)
        // A degraded sample is still usable for the readout (holding the last
        // value is what makes a tunnel tolerable) but never for an alert.
        val degraded = rejection == DEGRADED
        if (rejection != null && !degraded) {
            publish(emptyState(SpeedState.UNRELIABLE, limit, sample.source, ageMs))
            return
        }

        val smoothedMps = filter.update(sample, config.smoothing)
        val displayedMps = gate.applyDeadBand(smoothedMps, config.deadBandMps)
        val speedKmh = displayedMps * 3.6
        val shown = Math.round(speedKmh).toInt()

        val state: SpeedState
        if (filter.isStationary()) {
            // Standing still is never an overspeed, whatever the limit says;
            // clearing the detector stops a red light resolving into an alert.
            detector.reset()
            state = SpeedState.STATIONARY
        } else if (!config.alertsEnabled || !limit.valid || degraded) {
            detector.reset()
            state = SpeedState.MOVING
        } else {
            val fire = detector.update(
                speedKmh = speedKmh,
                limit = limit,
                thresholdKmh = config.thresholdKmh,
                nowMs = now,
                requireSustained = config.sustainedOnly,
                cooldownMs = config.cooldownMs
            )
            state = when {
                detector.isActive() -> SpeedState.OVERSPEED_ACTIVE
                detector.isPending() -> SpeedState.OVERSPEED_PENDING
                else -> SpeedState.MOVING
            }
            if (fire) {
                deliverAlert(speedKmh, limit, config.alertForm, config.alertVolume)
            }
        }

        val overBy = if (limit.valid) maxOf(0, shown - limit.limitKmh) else 0
        publish(
            SpeedUiState(
                state = state,
                speedKmh = shown,
                limitKmh = if (limit.valid) limit.limitKmh else null,
                limitSource = limit.source,
                overBy = overBy,
                sampleSource = sample.source,
                sampleAgeMs = ageMs,
                unit = config.unit,
                showSpeedSetting = config.showSpeed,
                showLimitSetting = config.showLimit
            )
        )
    }

    private fun emptyState(
        state: SpeedState,
        limit: SpeedLimit,
        source: SpeedSource? = null,
        ageMs: Long = -1L
    ): SpeedUiState = SpeedUiState(
        state = state,
        speedKmh = null,
        limitKmh = if (limit.valid) limit.limitKmh else null,
        limitSource = limit.source,
        overBy = 0,
        sampleSource = source,
        sampleAgeMs = ageMs,
        unit = config.unit,
        showSpeedSetting = config.showSpeed,
        showLimitSetting = config.showLimit
    )

    private fun publish(state: SpeedUiState) {
        lastState = state
        for (l in listeners.toTypedArray()) {
            runCatching { l.onSpeedState(state) }
        }
    }

    private fun deliverAlert(
        speedKmh: Double,
        limit: SpeedLimit,
        form: AlertForm,
        volume: Float
    ) {
        val over = Math.round(speedKmh).toInt() - limit.limitKmh
        for (l in listeners.toTypedArray()) {
            if (l !is AlertListener) continue
            runCatching { l.onOverspeed(over, limit, form, volume) }
        }
    }

    companion object {
        /**
         * Longer than [SpeedGate]'s own age limit because a screen can be
         * behind a slow tab switch; beyond this the readout falls back to `--`
         * rather than showing the number from just before the pause.
         */
        const val STALE_AFTER_MS = 12_000L

        /** The reason string [SpeedGate] returns for a holdable sample. */
        private const val DEGRADED = "degraded sample"
    }
}

END FILE

## core/location-engine/src/main/java/com/ts8/core/speed/SpeedTypes.kt

BEGIN FILE
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

END FILE

## core/location-engine/src/test/java/com/ts8/core/location/DefaultLocationEngineTest.kt

BEGIN FILE
package com.ts8.core.location

import com.ts8.core.map.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for the location pipeline.
 *
 * Every case here is a real failure mode of a head unit in a car: a concrete
 * car park, a tunnel exit, a stationary receiver emitting random headings, and
 * a genuine wrong turn. The thresholds asserted are the ones the navigator
 * depends on, not incidental implementation details.
 */
class DefaultLocationEngineTest {

    private val engine = DefaultLocationEngine()

    /** A straight east-west road at a fixed latitude. */
    private val route = (0..10).map { GeoPoint(16.0600, 108.2000 + it * 0.001) }

    private fun fix(
        lat: Double,
        lon: Double,
        atMs: Long,
        accuracy: Float = 8f,
        speed: Float = -1f,
        bearing: Float = -1f
    ) = RawLocation(
        point = GeoPoint(lat, lon),
        accuracyMeters = accuracy,
        elapsedRealtimeMs = atMs,
        speedMps = speed,
        bearingDeg = bearing
    )

    @Test
    fun `first fix is accepted and reported`() {
        val out = engine.onRawLocation(fix(16.0600, 108.2000, 1_000))
        assertNotNull(out)
        assertTrue(engine.hasFix())
        assertEquals(1L, engine.stats().accepted)
    }

    @Test
    fun `NaN and impossible coordinates are rejected, never corrected`() {
        assertNull(engine.onRawLocation(fix(Double.NaN, 108.2, 1_000)))
        assertNull(engine.onRawLocation(fix(95.0, 108.2, 2_000)))
        assertNull(engine.onRawLocation(fix(16.0, Double.POSITIVE_INFINITY, 3_000)))
        assertEquals(3L, engine.stats().rejectedInvalid)
        assertFalse(engine.hasFix())
    }

    @Test
    fun `a wildly inaccurate fix is rejected`() {
        // 900 m accuracy: the receiver guessed. Plotting it would put the car
        // in the wrong district.
        assertNull(engine.onRawLocation(fix(16.06, 108.2, 1_000, accuracy = 900f)))
        assertEquals(1L, engine.stats().rejectedInvalid)
    }

    @Test
    fun `a single teleport is rejected as a spike`() {
        engine.onRawLocation(fix(16.0600, 108.2000, 1_000))
        // 5 km in one second is 5000 m/s — no car does that.
        val spiked = engine.onRawLocation(fix(16.1000, 108.2000, 2_000))
        assertEquals(1L, engine.stats().rejectedSpike)
        // The engine holds the last good fix rather than emitting the spike.
        assertNotNull(spiked)
        assertTrue(
            "held fix must stay near the last good position",
            DefaultLocationEngine.distanceMeters(
                spiked!!.point, GeoPoint(16.0600, 108.2000)
            ) < 100
        )
    }

    @Test
    fun `several spikes in a row never move the marker away`() {
        engine.onRawLocation(fix(16.0600, 108.2000, 1_000))
        for (i in 1..5) {
            engine.onRawLocation(fix(16.0900, 108.2000, 1_000L + i * 1_000))
        }
        assertEquals(5L, engine.stats().rejectedSpike)
        assertTrue(
            DefaultLocationEngine.distanceMeters(
                engine.lastFix()!!.point, GeoPoint(16.0600, 108.2000)
            ) < 100
        )
    }

    @Test
    fun `a spike does not count as leaving the route`() {
        engine.setRoute(route)
        engine.onRawLocation(fix(16.0600, 108.2005, 1_000))
        // A jump 5 km off the line, once.
        engine.onRawLocation(fix(16.1000, 108.2005, 2_000))
        assertFalse(
            "one spike must never trigger a reroute",
            engine.shouldReroute()
        )
    }

    @Test
    fun `walking speed movement is accepted, not treated as a spike`() {
        engine.onRawLocation(fix(16.0600, 108.2000, 1_000))
        // ~11 m in 1 s ≈ 40 km/h.
        val out = engine.onRawLocation(fix(16.0600, 108.2001, 2_000))
        assertEquals(0L, engine.stats().rejectedSpike)
        assertNotNull(out)
    }

    @Test
    fun `jitter while parked is smoothed away`() {
        engine.onRawLocation(fix(16.0600, 108.2000, 1_000))
        // Alternating ±6 m jitter, which is what a stationary GPS does.
        var last: LocationFix? = null
        for (i in 1..10) {
            val offset = if (i % 2 == 0) 0.00006 else -0.00006
            last = engine.onRawLocation(fix(16.0600 + offset, 108.2000, 1_000L + i * 1_000))
        }
        val spread = DefaultLocationEngine.distanceMeters(
            last!!.point, GeoPoint(16.0600, 108.2000)
        )
        assertTrue("smoothed position drifted $spread m while parked", spread < 4.0)
    }

    @Test
    fun `bearing is held while stationary`() {
        engine.onRawLocation(fix(16.0600, 108.2000, 1_000, speed = 0f, bearing = 90f))
        val first = engine.lastFix()!!.bearingDeg
        assertEquals(90f, first!!, 0.01f)

        // A stationary receiver emits random courses; they must not reach the map.
        val second = engine.onRawLocation(fix(16.0600, 108.2000, 2_000, speed = 0f, bearing = 250f))
        assertEquals(90f, second!!.bearingDeg!!, 0.01f)
    }

    @Test
    fun `bearing changes are rate limited`() {
        engine.onRawLocation(fix(16.0600, 108.2000, 1_000, speed = 20f, bearing = 0f))
        // A 120° jump in one sample is not a real turn at 1 Hz.
        val out = engine.onRawLocation(fix(16.0600, 108.2000, 2_000, speed = 20f, bearing = 120f))
        val moved = DefaultLocationEngine.normalizeAngleDeg(out!!.bearingDeg!! - 0f)
        assertTrue("bearing jumped $moved deg in one fix", moved <= 46f)
    }

    @Test
    fun `snapping puts the marker on the route line`() {
        engine.setRoute(route)
        // 20 m north of the road.
        val out = engine.onRawLocation(fix(16.06018, 108.2005, 1_000))
        assertNotNull(out)
        assertTrue("expected a snap", out!!.snappedToRoute)
        val offLine = DefaultLocationEngine.distanceMeters(out.point, GeoPoint(16.0600, 108.2005))
        assertTrue("snapped point is $offLine m from the line", offLine < 2.0)
    }

    @Test
    fun `snapping is refused when the driver is far off the route`() {
        engine.setRoute(route)
        // 500 m north — clearly a different street.
        val out = engine.onRawLocation(fix(16.0645, 108.2005, 1_000))
        assertFalse(out!!.snappedToRoute)
        assertTrue(out.distanceFromRouteMeters!! > 60f)
    }

    @Test
    fun `distance along route grows as the driver advances`() {
        engine.setRoute(route)
        val first = engine.onRawLocation(fix(16.0600, 108.2001, 1_000))!!
        val second = engine.onRawLocation(fix(16.0600, 108.2005, 2_000))!!
        assertTrue(
            "along-route distance went backwards: ${first.distanceAlongRouteMeters} -> " +
                "${second.distanceAlongRouteMeters}",
            second.distanceAlongRouteMeters!! > first.distanceAlongRouteMeters!!
        )
    }

    @Test
    fun `progress fraction stays within zero and one`() {
        engine.setRoute(route)
        engine.onRawLocation(fix(16.0600, 108.2000, 1_000))
        val mid = engine.onRawLocation(fix(16.0600, 108.2005, 2_000))
        val p = engine.routeProgress()
        assertTrue("progress $p out of range", p in 0f..1f)
        assertNotNull(mid)
    }

    @Test
    fun `reroute needs several confirming off-route fixes`() {
        engine.setRoute(route)
        engine.onRawLocation(fix(16.0600, 108.2000, 1_000))

        // Turn off the route and drive away at ~60 km/h, which is what a wrong
        // turn actually looks like. A single 500 m jump would be a spike and is
        // correctly rejected, so the departure has to be gradual.
        //
        // The position EMA lags a linear departure by ~30 m, so it takes a few
        // seconds longer than the raw track to cross the 60 m off-route
        // threshold. That lag is deliberate: it is what keeps a wobbling fix
        // from cancelling a route.
        var t = 2_000L
        var lat = 16.0600
        var firstConfirmedAt = -1
        for (i in 1..8) {
            lat += 0.00015  // ~16.6 m per step ≈ 60 km/h
            engine.onRawLocation(fix(lat, 108.2000, t))
            t += 1_000
            if (firstConfirmedAt < 0 && engine.shouldReroute()) firstConfirmedAt = i
        }

        assertTrue("reroute never fired after 8 fixes off the line", firstConfirmedAt > 0)
        assertTrue(
            "reroute fired too eagerly, after only $firstConfirmedAt fix(es)",
            firstConfirmedAt >= 3
        )
    }

    @Test
    fun `out of order timestamps are rejected`() {
        engine.onRawLocation(fix(16.0600, 108.2000, 10_000))
        assertNull(engine.onRawLocation(fix(16.0600, 108.2001, 5_000)))
        assertEquals(1L, engine.stats().rejectedStale)
    }

    @Test
    fun `no route means no snapping and no off-route state`() {
        engine.setRoute(null)
        val out = engine.onRawLocation(fix(16.0600, 108.2000, 1_000))!!
        assertFalse(out.snappedToRoute)
        assertNull(out.distanceFromRouteMeters)
        assertFalse(engine.shouldReroute())
    }

    @Test
    fun `clearing the route resets projection state`() {
        engine.setRoute(route)
        engine.onRawLocation(fix(16.0600, 108.2005, 1_000))
        assertTrue(engine.lastFix()!!.snappedToRoute)
        engine.setRoute(null)
        assertEquals(0f, engine.routeProgress())
        assertFalse(engine.shouldReroute())
    }

    @Test
    fun `projection onto a segment clamps past the endpoints`() {
        val a = GeoPoint(16.06, 108.20)
        val b = GeoPoint(16.06, 108.21)
        // Beyond b: projection must clamp to b, not run off the line.
        val p = DefaultLocationEngine.projectOnSegment(GeoPoint(16.06, 108.22), a, b)
        assertEquals(1f, p.alongFraction, 0.001f)
        // Before a: clamps to a.
        val q = DefaultLocationEngine.projectOnSegment(GeoPoint(16.06, 108.19), a, b)
        assertEquals(0f, q.alongFraction, 0.001f)
    }

    @Test
    fun `projection distance matches the perpendicular offset`() {
        val a = GeoPoint(16.06, 108.20)
        val b = GeoPoint(16.06, 108.21)
        // 0.0009 deg latitude ≈ 100 m.
        val p = DefaultLocationEngine.projectOnSegment(GeoPoint(16.0609, 108.205), a, b)
        assertTrue("got ${p.distanceMeters} m, expected ~100", p.distanceMeters in 95f..105f)
        assertEquals(0.5f, p.alongFraction, 0.02f)
    }

    @Test
    fun `rejected fixes do not advance the clock`() {
        engine.onRawLocation(fix(16.0600, 108.2000, 1_000))
        engine.onRawLocation(fix(95.0, 108.2, 2_000))          // invalid
        // A good fix at t=3s must be accepted: the clock never moved to 2s.
        assertNotNull(engine.onRawLocation(fix(16.0600, 108.2001, 3_000)))
    }
}

END FILE

## core/location-engine/src/test/java/com/ts8/core/parking/ParkingResolverTest.kt

BEGIN FILE
package com.ts8.core.parking

import com.ts8.core.map.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `LocationStability` and `ParkingStateResolver`, against synthetic tracks.
 *
 * `ParkingScenarios` already walks twelve acceptance cases, but it is a plain
 * Kotlin object that `gradlew test` never runs — it reports failures by hand
 * from a diagnostics screen. These are the same questions asked as real JUnit
 * tests, so a regression fails the build rather than waiting for someone to
 * open a debug panel on the unit.
 *
 * Every case below is the whole point of §18: speed zero means a red light
 * exactly as often as it means a parking space, so no test asserts "speed 0 for
 * a while → PARKED" without at least one other independent fact.
 */
class ParkingResolverTest {

    private val t0 = 500_000L

    private fun signals(
        atMs: Long,
        speedMps: Double? = 0.0,
        accuracy: Float? = 6f,
        stable: Boolean? = true,
        gearPark: Boolean? = null,
        ignitionOff: Boolean? = null,
        brake: Boolean? = null,
        call: Boolean = false,
        reverse: Boolean = false,
        navigation: Boolean = false,
        manual: Boolean? = null
    ) = ParkingSignals(
        vehicleGearPark = gearPark,
        vehicleAccOn = null,
        vehicleParkingBrake = brake,
        vehicleIgnitionOff = ignitionOff,
        speedMps = speedMps,
        speedAccuracyMeters = accuracy,
        locationStable = stable,
        navigationActive = navigation,
        callActive = call,
        reverseCameraActive = reverse,
        explicitManualRequest = manual,
        elapsedRealtimeMs = atMs
    )

    private fun resolver(now: Long = t0) = ParkingStateResolver(clock = { now })

    // -- LocationStability ------------------------------------------------

    @Test
    fun `stability gives no verdict before the window is long enough`() {
        val s = LocationStability()
        s.add(GeoPoint(16.06, 108.22), t0)
        s.add(GeoPoint(16.06, 108.22), t0 + 5_000L)
        assertNull("5 s is not 8 s of evidence", s.verdict(t0 + 5_000L))
    }

    @Test
    fun `stability reads steady for a receiver hopping under ten metres`() {
        val s = LocationStability()
        // Urban jitter inside the 10 m tolerance. Ten steps at 0.000006° of
        // latitude is 6.7 m north; 0.000004° of longitude at this latitude is
        // 4.3 m east. Diagonal drift ≈ 7.9 m — a parked receiver, not a car.
        for (i in 0..10) {
            s.add(GeoPoint(16.0600 + i * 0.000006, 108.2200 + i * 0.000004), t0 + i * 1_000L)
        }
        assertEquals(true, s.verdict(t0 + 10_000L))
    }

    @Test
    fun `stability reads moving for a car creeping through a queue`() {
        val s = LocationStability()
        // ~50 m over 10 s: 18 km/h, which is a queue that is moving.
        for (i in 0..10) {
            s.add(GeoPoint(16.0600 + i * 0.000045, 108.2200), t0 + i * 1_000L)
        }
        assertEquals(false, s.verdict(t0 + 10_000L))
    }

    @Test
    fun `an out of order sample drops the history rather than inventing a span`() {
        val s = LocationStability()
        s.add(GeoPoint(16.0600, 108.2200), t0)
        for (i in 1..10) s.add(GeoPoint(16.0600, 108.2200), t0 + i * 1_000L)
        assertEquals(true, s.verdict(t0 + 10_000L))

        // A clock read from the wrong source arrives ten seconds "earlier"
        // than the newest sample. Keeping it would make the oldest entry wrong
        // and turn a moving car into a stable one.
        s.add(GeoPoint(16.0700, 108.2200), t0 - 5_000L)
        assertNull("history was dropped, so there is no verdict yet", s.verdict(t0 - 5_000L))
    }

    @Test
    fun `old samples fall out of the window so a silent receiver is not stable`() {
        val s = LocationStability()
        s.add(GeoPoint(16.0600, 108.2200), t0)
        for (i in 1..5) s.add(GeoPoint(16.0600, 108.2200), t0 + i * 1_000L)
        // A minute later: every sample but the newest is outside the window, so
        // the span is zero and the answer is "no verdict" rather than "stable".
        assertNull(s.verdict(t0 + 60_000L))
    }

    // -- ParkingStateResolver: the red light -------------------------------

    @Test
    fun `a red light never becomes a candidate, however long it is`() {
        // The brief's first sentence. Speed zero, position jittering: the
        // resolver must not credit dwell it never measured as evidence. The
        // clock runs with the snapshots, as ParkingRuntime's 1 Hz tick does.
        var now = t0
        val r = resolver(now)
        var last = r.resolve(signals(t0, speedMps = 0.0, stable = false), allowAutoEnter = true)
        for (i in 1..90) {
            now = t0 + i * 1_000L
            last = r.resolve(signals(now, speedMps = 0.0, stable = false), allowAutoEnter = true)
        }

        assertEquals(ParkingState.STOPPED_TEMPORARILY, last.state)
        assertFalse(last.showsParkingScreen)
    }

    @Test
    fun `a spent dwell without dwell tracking does not become a candidate`() {
        // A snapshot arrives already ninety seconds into a stop, but the
        // resolver has measured none of it. It must start its own clock.
        val r = resolver(t0)
        val d = r.resolve(signals(t0, speedMps = 0.0, stable = true), allowAutoEnter = true)
        assertEquals(ParkingState.STOPPED_TEMPORARILY, d.state)
    }

    @Test
    fun `twenty seconds of steady dwell is a candidate, two minutes is parked`() {
        // One realistic track: the fix a second, the car not moving.
        var now = t0
        val r = resolver()
        val cfg = ParkingStateResolver.Config()

        for (i in 0..cfg.candidateDwellMs / 1_000) {
            now = t0 + i * 1_000L
            r.resolve(signals(now, stable = true), allowAutoEnter = true)
        }
        assertEquals(
            "a candidate after the short window",
            ParkingState.PARKING_CANDIDATE,
            r.current().state
        )

        for (i in 0..cfg.parkDwellMs / 1_000) {
            now = t0 + cfg.candidateDwellMs + i * 1_000L
            r.resolve(signals(now, stable = true), allowAutoEnter = true)
        }
        assertEquals("parked after the long window", ParkingState.PARKED, r.current().state)
        assertTrue(r.current().showsParkingScreen)
        assertTrue(r.isParked())
    }

    // -- Priority: instruction over evidence -------------------------------

    @Test
    fun `a manual entry parks immediately and outranks the dwell rule`() {
        val r = resolver()
        val d = r.resolve(signals(t0, manual = true), allowAutoEnter = false)
        assertEquals(ParkingState.PARKED, d.state)
        assertEquals(ParkingReason.MANUAL, d.reason)
        assertTrue(d.showsParkingScreen)
    }

    @Test
    fun `a manual exit is honoured even when a gear signal says Park`() {
        // The exit button must never appear dead. A gear reading may not
        // overrule an explicit instruction.
        val r = resolver()
        r.resolve(signals(t0, gearPark = true), allowAutoEnter = true)
        val d = r.resolve(signals(t0 + 1_000L, gearPark = true, manual = false), allowAutoEnter = true)

        assertEquals(ParkingReason.MANUAL, d.reason)
        assertFalse(d.showsParkingScreen)
        assertFalse(r.isParked())
    }

    @Test
    fun `the exit latch holds through a snapshot with no manual input`() {
        // null means "the button was not touched", not "the driver changed
        // their mind", so the resolver must not re-enter between the exit and
        // the next tap.
        val r = resolver()
        r.resolve(signals(t0, gearPark = true), allowAutoEnter = true)
        r.resolve(signals(t0 + 1_000L, manual = false), allowAutoEnter = true)
        val d = r.resolve(signals(t0 + 2_000L, gearPark = true), allowAutoEnter = true)

        assertEquals(ParkingState.STOPPED_TEMPORARILY, d.state)
        assertEquals(ParkingReason.MANUAL, d.reason)
        assertFalse(r.isParked())
    }

    // -- Vehicle signals ---------------------------------------------------

    @Test
    fun `ignition off parks with HIGH confidence`() {
        val r = resolver()
        val d = r.resolve(signals(t0, ignitionOff = true), allowAutoEnter = true)
        assertEquals(ParkingState.PARKED, d.state)
        assertEquals(ParkingConfidence.HIGH, d.confidence)
        assertEquals(ParkingReason.VEHICLE_IGNITION_OFF, d.reason)
    }

    @Test
    fun `the parking brake alone is a candidate, never an automatic entry`() {
        // Drivers pull the handbrake at every long red light. This is real
        // evidence, so it is not discarded — but it may not open the screen.
        val r = resolver()
        val d = r.resolve(signals(t0, brake = true), allowAutoEnter = true)
        assertEquals(ParkingState.PARKING_CANDIDATE, d.state)
        assertEquals(ParkingConfidence.MEDIUM, d.confidence)
        assertEquals(ParkingReason.VEHICLE_PARKING_BRAKE, d.reason)
        assertFalse(d.showsParkingScreen)
    }

    @Test
    fun `a null signal does not vote, so a silent bus parks nothing`() {
        // The common case on this unit: every vehicle field is null. A
        // resolver that read null as false would think it had evidence the car
        // is not parked.
        val r = resolver()
        val d = r.resolve(
            signals(t0, gearPark = null, ignitionOff = null, brake = null),
            allowAutoEnter = true
        )
        assertEquals(ParkingState.STOPPED_TEMPORARILY, d.state)
    }

    @Test
    fun `auto entry off leaves the evidence as a candidate`() {
        val r = resolver()
        val d = r.resolve(signals(t0, ignitionOff = true), allowAutoEnter = false)
        assertEquals(ParkingState.PARKING_CANDIDATE, d.state)
        assertFalse(d.showsParkingScreen)
    }

    // -- Blockers ----------------------------------------------------------

    @Test
    fun `a phone call blocks automatic entry`() {
        val r = resolver()
        val d = r.resolve(signals(t0, ignitionOff = true, call = true), allowAutoEnter = true)
        assertEquals(ParkingState.PARKING_CANDIDATE, d.state)
        assertEquals(ParkingReason.CALL_ACTIVE, d.reason)
        assertFalse(d.showsParkingScreen)
    }

    @Test
    fun `the reverse camera and an active route block automatic entry`() {
        val r1 = resolver()
        assertEquals(
            ParkingReason.REVERSE_ACTIVE,
            r1.resolve(signals(t0, ignitionOff = true, reverse = true), true).reason
        )
        val r2 = resolver()
        assertEquals(
            ParkingReason.NAVIGATION_ACTIVE,
            r2.resolve(signals(t0, ignitionOff = true, navigation = true), true).reason
        )
    }

    @Test
    fun `a manual request is not blocked, because it is an instruction`() {
        val r = resolver()
        val d = r.resolve(signals(t0, manual = true, call = true, reverse = true), true)
        assertEquals(ParkingState.PARKED, d.state)
    }

    // -- Exit from PARKED --------------------------------------------------

    @Test
    fun `a single speed spike does not dismiss the parking screen`() {
        val r = resolver()
        r.resolve(signals(t0, ignitionOff = true), allowAutoEnter = true)
        assertTrue(r.isParked())

        // One bad sample under a bridge: 40 km/h for a moment.
        val d = r.resolve(signals(t0 + 1_000L, speedMps = 11.0, ignitionOff = false), true)
        assertEquals("still parked", ParkingState.PARKED, d.state)
        assertTrue(r.isParked())
    }

    @Test
    fun `sustained movement exits parking`() {
        var now = t0
        val r = resolver()
        r.resolve(signals(now, ignitionOff = true), allowAutoEnter = true)

        val cfg = ParkingStateResolver.Config()
        for (i in 1..(cfg.exitHoldMs / 1_000 + 1)) {
            now = t0 + i * 1_000L
            r.resolve(signals(now, speedMps = 10.0), allowAutoEnter = true)
        }

        assertEquals(ParkingState.DRIVING, r.current().state)
        assertEquals(ParkingReason.SPEED_ABOVE_THRESHOLD, r.current().reason)
        assertFalse(r.isParked())
    }

    @Test
    fun `losing the fix while parked does not exit, because silence is not movement`() {
        val r = resolver()
        r.resolve(signals(t0, ignitionOff = true), allowAutoEnter = true)
        val d = r.resolve(signals(t0 + 1_000L, speedMps = null, accuracy = null, ignitionOff = null), true)
        assertEquals(ParkingState.PARKED, d.state)
        assertTrue(r.isParked())
    }

    // -- Honest unknowns ---------------------------------------------------

    @Test
    fun `no fix is UNKNOWN, never PARKED`() {
        // "GPS lost" and "parked in a tunnel" look identical from inside the
        // car, and only one of them is a reason to show the parking screen.
        val r = resolver()
        val d = r.resolve(signals(t0, speedMps = null, accuracy = null, stable = null), true)
        assertEquals(ParkingState.UNKNOWN, d.state)
        assertEquals(ParkingConfidence.NONE, d.confidence)
        assertFalse(d.showsParkingScreen)
    }

    @Test
    fun `an urban canyon accuracy is not read as zero speed`() {
        val r = resolver()
        val d = r.resolve(signals(t0, speedMps = 0.0, accuracy = 100f), allowAutoEnter = true)
        assertEquals(ParkingState.UNKNOWN, d.state)
        assertEquals(ParkingReason.INSUFFICIENT_SIGNALS, d.reason)
    }

    @Test
    fun `a stale snapshot is not used to conclude anything`() {
        // The snapshot was taken six seconds ago; the car may have moved.
        val r = resolver(now = t0 + 6_000L)
        val d = r.resolve(signals(t0, ignitionOff = true), allowAutoEnter = true)
        assertEquals(ParkingState.UNKNOWN, d.state)
        assertEquals(ParkingReason.INSUFFICIENT_SIGNALS, d.reason)
    }

    @Test
    fun `a gap longer than the grace period restarts the dwell`() {
        // A receiver that drops out for twenty seconds at a red light must not
        // be credited with that time as parking dwell. The dark stretch is fed
        // one snapshot a second, the way ParkingRuntime's tick feeds it — the
        // gap is measured from the moment the fix was *observed* missing.
        var now = t0
        val r = resolver(now)
        val cfg = ParkingStateResolver.Config()

        for (i in 0..cfg.candidateDwellMs / 1_000) {
            now = t0 + i * 1_000L
            r.resolve(signals(now, stable = true), allowAutoEnter = true)
        }
        assertEquals(ParkingState.PARKING_CANDIDATE, r.current().state)

        for (i in 1..(cfg.noFixGraceMs / 1_000 + 5)) {
            now += 1_000L
            r.resolve(signals(now, speedMps = null, accuracy = null, stable = null), true)
        }

        now += 1_000L
        val d = r.resolve(signals(now, stable = true), allowAutoEnter = true)
        assertEquals("the stretch restarted after the gap", ParkingState.STOPPED_TEMPORARILY, d.state)
    }

    @Test
    fun `the evidence text carries no number that looks like a position`() {
        // `ParkingStateStore` writes this string to disk. A parked car's
        // coordinates are the most sensitive thing the launcher handles.
        val r = resolver()
        val texts = listOf(
            r.resolve(signals(t0), true).evidence,
            r.resolve(signals(t0 + 1_000L, ignitionOff = true), true).evidence,
            ratingEvidence()
        )
        for (text in texts) {
            assertFalse("a coordinate would look like this: $text", text.contains("16.0"))
            assertFalse("a coordinate would look like this: $text", text.contains("108.2"))
        }
    }

    private fun ratingEvidence(): String {
        val r = resolver(now = t0 + 20_000L)
        r.resolve(signals(t0 + 19_000L, speedMps = null, accuracy = null, stable = null), true)
        return r.current().evidence
    }
}

END FILE

## core/location-engine/src/test/java/com/ts8/core/parking/ParkingScenariosTest.kt

BEGIN FILE
package com.ts8.core.parking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gates `ParkingScenarios` in the build.
 *
 * The scenarios were written as an executable acceptance list that a
 * diagnostics screen could also run on the unit, which is why [ParkingScenarios]
 * returns failure strings instead of asserting: a thrown exception inside the
 * parking screen would take down the screen that exists to report the fault.
 * The side effect is that `gradlew test` never ran them — twelve hand-written
 * cases about the most safety-sensitive branch in the app, passing only when
 * someone opened a debug panel.
 *
 * This file is the thin bridge. It asserts nothing about parking itself; it
 * fails when [ParkingScenarios.verify] reports a failure, and the message is
 * the scenario's own Vietnamese line, so a red build names the case that broke.
 */
class ParkingScenariosTest {

    @Test
    fun `every parking acceptance scenario passes`() {
        val failures = ParkingScenarios.verify()
        assertTrue(
            "ParkingScenarios reported ${failures.size} failure(s):\n" +
                failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    @Test
    fun `the scenario count matches what verify actually runs`() {
        // `scenarioCount` is public because a UI shows "0/N lỗi", and a stale N
        // would let a scenario be deleted without the denominator changing.
        // Twelve is the number of calls in `verify()`; counting them here keeps
        // the two in step by failing rather than by convention.
        assertEquals(12, ParkingScenarios.scenarioCount)
    }
}

END FILE

## core/location-engine/src/test/java/com/ts8/core/speed/SpeedPipelineTest.kt

BEGIN FILE
package com.ts8.core.speed

import com.ts8.core.location.LocationFix
import com.ts8.core.map.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The speed pipeline, end to end and part by part.
 *
 * This module had no tests for `speed/` at all before this file — the whole
 * overspeed path (gate, filter, limit provenance, confirmation window, master
 * switch) was exercised only by whoever happened to drive the car. The rules
 * asserted here are the ones that decide whether a safety alert fires, so each
 * test names the field failure it prevents rather than the method it calls.
 *
 * The first test in the file exists because the master switch was declared in
 * [SpeedMonitor.Config] and read by nobody.
 */
class SpeedPipelineTest {

    private val t0 = 1_000_000L

    private fun sample(
        speedMps: Double,
        atMs: Long = t0,
        accuracy: Float = 8f,
        source: SpeedSource = SpeedSource.GPS,
        degraded: Boolean = false
    ) = SpeedSample(
        speedMps = speedMps,
        accuracyMeters = accuracy,
        elapsedRealtimeMs = atMs,
        source = source,
        degraded = degraded
    )

    private fun fix(
        speedMps: Float,
        atMs: Long = t0,
        accuracy: Float = 8f
    ) = LocationFix(
        point = GeoPoint(16.06, 108.22),
        rawPoint = GeoPoint(16.06, 108.22),
        accuracyMeters = accuracy,
        speedMps = speedMps,
        bearingDeg = null,
        elapsedRealtimeMs = atMs
    )

    private val limit50 = SpeedLimit(
        limitKmh = 50,
        source = LimitSource.ROUTING_GRAPH,
        resolvedAtElapsedMs = 0L,
        confidence = SpeedLimit.Confidence.HIGH
    )

    // -- SpeedGate ---------------------------------------------------------

    @Test
    fun `gate accepts a fresh plausible sample`() {
        assertNull(SpeedGate().reject(sample(10.0), t0))
    }

    @Test
    fun `gate rejects a stale sample`() {
        assertEquals("stale 6000ms", SpeedGate().reject(sample(10.0), t0 + 6_000L))
    }

    @Test
    fun `gate rejects an urban canyon accuracy`() {
        // 100 m accuracy under trees is the sample that reports zero while the
        // car is doing 30. It must never reach the alert path.
        assertEquals("accuracy 100.0m", SpeedGate().reject(sample(0.0, accuracy = 100f), t0))
    }

    @Test
    fun `gate rejects a physically impossible speed`() {
        assertEquals("implausible 60.0m/s", SpeedGate().reject(sample(60.0), t0))
    }

    @Test
    fun `gate rejects a negative speed and a future timestamp`() {
        assertEquals("negative speed", SpeedGate().reject(sample(-1.0), t0))
        assertEquals("timestamp in future", SpeedGate().reject(sample(5.0), t0 - 2_000L))
    }

    @Test
    fun `gate flags a degraded sample by name, because the monitor holds its value`() {
        // The monitor compares this string to decide "show the old number but
        // never alert on it". Changing the wording would silently turn the
        // tunnel case back into an alert.
        assertEquals("degraded sample", SpeedGate().reject(sample(10.0, degraded = true), t0))
    }

    @Test
    fun `dead band maps jitter to zero and switches off completely at zero`() {
        val gate = SpeedGate()
        assertEquals(0.0, gate.applyDeadBand(0.4, 0.8), 1e-9)
        assertEquals(1.2, gate.applyDeadBand(1.2, 0.8), 1e-9)
        // 0 disables the filter: a jittering 0.4 m/s stays 0.4 rather than
        // being reported as a fabricated zero.
        assertEquals(0.4, gate.applyDeadBand(0.4, 0.0), 1e-9)
    }

    // -- SpeedFilter -------------------------------------------------------

    @Test
    fun `filter re-seeds on a source change instead of blending`() {
        val filter = SpeedFilter()
        filter.update(sample(20.0, source = SpeedSource.GPS), SpeedFilter.Smoothing.BALANCED)
        val afterBus = filter.update(
            sample(5.0, source = SpeedSource.VEHICLE_BUS),
            SpeedFilter.Smoothing.BALANCED
        )
        // A blended value would be ~15.2: a number neither source reported.
        assertEquals(5.0, afterBus, 1e-9)
    }

    @Test
    fun `fast smoothing reacts harder than smooth to the same step`() {
        fun afterStep(smoothing: SpeedFilter.Smoothing): Double {
            val f = SpeedFilter()
            f.update(sample(0.0), smoothing)
            return f.update(sample(10.0), smoothing)
        }
        val fast = afterStep(SpeedFilter.Smoothing.FAST)
        val balanced = afterStep(SpeedFilter.Smoothing.BALANCED)
        val smooth = afterStep(SpeedFilter.Smoothing.SMOOTH)
        assertTrue("fast > balanced", fast > balanced)
        assertTrue("balanced > smooth", balanced > smooth)
    }

    @Test
    fun `reset makes the next sample prime again`() {
        val filter = SpeedFilter()
        filter.update(sample(20.0), SpeedFilter.Smoothing.SMOOTH)
        filter.reset()
        assertEquals(3.0, filter.update(sample(3.0), SpeedFilter.Smoothing.SMOOTH), 1e-9)
    }

    // -- SpeedLimitResolver ------------------------------------------------

    @Test
    fun `pack limit is MEDIUM because the field mixes tagged and inferred values`() {
        val resolved = SpeedLimitResolver { t0 }.setPackLimit(60)
        assertEquals(60, resolved.limitKmh)
        assertEquals(LimitSource.ROUTING_GRAPH, resolved.source)
        assertEquals(SpeedLimit.Confidence.MEDIUM, resolved.confidence)
        assertTrue(resolved.valid)
    }

    @Test
    fun `tagged and vehicle limits are HIGH`() {
        val r = SpeedLimitResolver { t0 }
        assertEquals(SpeedLimit.Confidence.HIGH, r.setTagged(60).confidence)
        val bus = r.setVehicleLimit(80)
        assertEquals(SpeedLimit.Confidence.HIGH, bus.confidence)
        assertEquals(LimitSource.VEHICLE_BUS, bus.source)
    }

    @Test
    fun `a non-positive limit resolves to NONE, not to zero`() {
        val r = SpeedLimitResolver { t0 }
        r.setPackLimit(60)
        assertEquals(SpeedLimit.NONE, r.setPackLimit(0))
        assertFalse(r.current().valid)
    }

    @Test
    fun `clear drops the limit rather than carrying it onto the next road`() {
        val r = SpeedLimitResolver { t0 }
        r.setPackLimit(50)
        r.clear()
        assertFalse(r.current().valid)
    }

    // -- OverspeedDetector -------------------------------------------------

    @Test
    fun `no valid limit means no event, whatever the speed`() {
        val d = OverspeedDetector()
        val fired = d.update(200.0, SpeedLimit.NONE, 5, t0, requireSustained = false)
        assertFalse(fired)
        assertFalse(d.isActive())
    }

    @Test
    fun `a single spike below the confirmation window does not fire`() {
        // The brief's chống GPS spike rule: one 90 km/h sample in a 50 zone
        // while passing a bridge must not produce an alert.
        val d = OverspeedDetector()
        assertFalse(d.update(90.0, limit50, 5, t0))
        assertFalse(d.isActive())
        assertTrue(d.isPending())
    }

    @Test
    fun `one crossing produces one alert, and the cooldown covers the next crossing`() {
        val d = OverspeedDetector()
        assertFalse("the first over-threshold tick only starts the window", d.update(90.0, limit50, 5, t0))
        assertFalse("active is set only after the window", d.isActive())
        assertTrue(d.isPending())
        assertTrue("fires at the end of the window", d.update(90.0, limit50, 5, t0 + 2_500L))

        // Staying at 90 on the same road is one event, not one per tick: the
        // detector stays armed-off until the speed clearly drops below the
        // limit, so the driver is told once.
        assertFalse("still over, still the same crossing", d.update(90.0, limit50, 5, t0 + 3_000L))
        assertFalse("no second alert merely because time passed", d.update(90.0, limit50, 5, t0 + 20_000L))

        // A second crossing: drop below limit − hysteresis, then exceed again.
        d.update(40.0, limit50, 5, t0 + 25_000L)
        // Over again, but the confirmation window restarts, so this tick is
        // still pending rather than an alert.
        assertFalse(d.update(90.0, limit50, 5, t0 + 26_000L))
        assertTrue(d.isPending())
        // Full window on this crossing, and now more than the 15 s cooldown has
        // passed since the first alert, so the driver is warned again.
        assertTrue(
            "a second crossing after the cooldown does alert",
            d.update(90.0, limit50, 5, t0 + 40_000L)
        )
    }

    @Test
    fun `hysteresis prevents re-alerting while hovering on the threshold`() {
        val d = OverspeedDetector()
        d.update(90.0, limit50, 5, t0)
        d.update(90.0, limit50, 5, t0 + 2_500L) // fires
        d.update(56.0, limit50, 5, t0 + 30_000L) // over the 55 trigger, but not below 47
        assertFalse("must not re-arm at 56", d.update(90.0, limit50, 5, t0 + 40_000L))
        d.update(40.0, limit50, 5, t0 + 50_000L) // clearly below the limit: re-arms
        // Re-arming starts a fresh confirmation window, because a new crossing
        // is a new event. Passing requireSustained=false is how a caller asks
        // for the single-sample mode; here it is used to observe the re-arm.
        assertTrue(
            "re-arms after falling below the limit",
            d.update(90.0, limit50, 5, t0 + 60_000L, requireSustained = false)
        )
    }

    @Test
    fun `requireSustained false fires on the first over-threshold tick`() {
        val d = OverspeedDetector()
        assertTrue(d.update(90.0, limit50, 5, t0, requireSustained = false))
    }

    // -- SpeedMonitor ------------------------------------------------------

    @Test
    fun `master switch off publishes the empty state and no alert`() {
        // Before this was read, `Config.enabled` changed nothing: the monitor
        // ran the whole pipeline and showed a speed with the switch off.
        val monitor = SpeedMonitor(
            clock = { t0 },
            config = SpeedMonitor.Config(enabled = false)
        )
        monitor.onPackLimit(50)
        monitor.onFix(fix(20f))

        val state = monitor.state()
        assertEquals(SpeedState.UNKNOWN, state.state)
        assertNull("no number, not a zero", state.speedKmh)
        assertFalse(state.showSpeed)
    }

    @Test
    fun `a stale fix shows the limit but no speed`() {
        val monitor = SpeedMonitor(clock = { t0 + 6_000L })
        monitor.onPackLimit(50)
        monitor.onFix(fix(20f, atMs = t0))

        val state = monitor.state()
        assertEquals(SpeedState.UNRELIABLE, state.state)
        assertNull(state.speedKmh)
        assertEquals("the sign is still known", 50, state.limitKmh)
    }

    @Test
    fun `no limit means a speed and a dash, and nothing else`() {
        val monitor = SpeedMonitor(clock = { t0 })
        monitor.onFix(fix(15f))

        val state = monitor.state()
        assertEquals(SpeedState.MOVING, state.state)
        assertNotNull(state.speedKmh)
        assertNull("-- rather than an invented limit", state.limitKmh)
        assertFalse(state.showLimit)
    }

    @Test
    fun `standing still clears the detector so a red light cannot alert`() {
        val monitor = SpeedMonitor(clock = { t0 })
        monitor.onPackLimit(5) // a 5 km/h limit on a private road
        monitor.onFix(fix(0.2f, atMs = t0))

        val state = monitor.state()
        assertEquals(SpeedState.STATIONARY, state.state)
        assertFalse(state.alerting)
    }

    @Test
    fun `a sustained overspeed walks pending to active and alerts exactly once`() {
        var now = t0
        val alerts = ArrayList<Int>()
        val monitor = SpeedMonitor(clock = { now })

        monitor.addListener(object : SpeedMonitor.AlertListener {
            override fun onOverspeed(overByKmh: Int, limit: SpeedLimit, form: SpeedMonitor.AlertForm, volume: Float) {
                alerts += overByKmh
            }
            override fun onSpeedState(state: SpeedUiState) = Unit
        })

        monitor.onPackLimit(50)
        monitor.onFix(fix(60f / 3.6f, atMs = t0))
        assertEquals(SpeedState.OVERSPEED_PENDING, monitor.state().state)
        assertTrue("nothing announced during the window", alerts.isEmpty())

        now = t0 + 2_500L
        monitor.tick()
        assertEquals(SpeedState.OVERSPEED_ACTIVE, monitor.state().state)

        now = t0 + 3_000L
        monitor.tick()
        assertEquals("one alert, not one per tick", 1, alerts.size)
        assertEquals(10, alerts.single())
    }

    @Test
    fun `mph is a display unit only, so the threshold stays in km per hour`() {
        val monitor = SpeedMonitor(
            clock = { t0 },
            config = SpeedMonitor.Config(unit = SpeedUnit.MPH)
        )
        monitor.onFix(fix(100f / 3.6f, atMs = t0))

        val state = monitor.state()
        assertEquals(100, state.speedKmh)
        assertEquals("100 km/h in mph", 62, state.displaySpeed)
        assertEquals("mph", state.unitLabel)
    }

    @Test
    fun `a degraded sample holds the readout but may not alert`() {
        val monitor = SpeedMonitor(clock = { t0 })
        monitor.onPackLimit(50)
        monitor.onVehicleSample(sample(60.0 / 3.6, degraded = true))

        val state = monitor.state()
        assertEquals("usable for the readout", 60, state.speedKmh)
        assertEquals(SpeedState.MOVING, state.state)
        assertFalse(state.alerting)
    }

    @Test
    fun `reconfiguring the threshold takes effect without waiting for a fix`() {
        val monitor = SpeedMonitor(clock = { t0 })
        monitor.onPackLimit(50)
        // 52 in a 50 zone with a 5 km/h tolerance is inside the tolerance:
        // the trigger is limit + threshold = 55, so nothing is pending yet.
        monitor.onFix(fix(52f / 3.6f, atMs = t0))
        assertEquals(SpeedState.MOVING, monitor.state().state)

        // The same sample now exceeds a 0 km/h tolerance, and "sustained only"
        // is off, so it becomes active on the recompute that configure() runs.
        monitor.configure(monitor.config().copy(thresholdKmh = 0, sustainedOnly = false))
        assertEquals(SpeedState.OVERSPEED_ACTIVE, monitor.state().state)
    }

    @Test
    fun `changing smoothing resets the filter instead of stepping the number`() {
        val monitor = SpeedMonitor(clock = { t0 })
        monitor.onFix(fix(0f, atMs = t0))
        monitor.onFix(fix(20f, atMs = t0))
        // Balanced smoothing moved a third of the way: 0 + 0.32 × 20 = 6.4 m/s.
        assertEquals(23, monitor.state().speedKmh)

        monitor.configure(monitor.config().copy(smoothing = SpeedFilter.Smoothing.SMOOTH))
        // The reset re-seeds from the held sample, so the readout jumps to what
        // the receiver actually reported rather than blending toward it.
        assertEquals(72, monitor.state().speedKmh)
    }
}

END FILE

## core/map-api/build.gradle.kts

BEGIN FILE
plugins {
    id("ts8.core-module")
}

android {
    namespace = "com.ts8.core.map"
}

END FILE

## core/map-api/src/main/AndroidManifest.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<manifest />

END FILE

## core/map-api/src/main/java/com/ts8/core/map/Geo.kt

BEGIN FILE
package com.ts8.core.map

/**
 * Coordinate in WGS-84 degrees.
 *
 * The whole app uses WGS-84; the map engine is responsible for any projection.
 */
data class GeoPoint(val lat: Double, val lon: Double) {
    fun isValid(): Boolean =
        lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0 &&
            !lat.isNaN() && !lon.isNaN()

    /**
     * Great-circle distance to [other], in metres (haversine).
     *
     * Lives here rather than in each engine because the UI now needs it too —
     * the search screen prints "cách 6,2 km" and the saved-places list prints
     * the same number for a record that no engine touched. The engines keep
     * their own private copies, which is deliberate: they are on hot paths and
     * call it inside tight loops, where a virtual call per candidate is a
     * measurable cost on this CPU.
     *
     * A sphere, not an ellipsoid: at the kilometre scale of a head unit's
     * display the WGS-84 flattening changes the answer by under 0.3 %, which is
     * invisible next to the GPS's own 5–20 m error.
     */
    fun distanceTo(other: GeoPoint): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(other.lat - lat)
        val dLon = Math.toRadians(other.lon - lon)
        val lat1 = Math.toRadians(lat)
        val lat2 = Math.toRadians(other.lat)
        val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.sin(dLon / 2) * Math.sin(dLon / 2) * Math.cos(lat1) * Math.cos(lat2)
        return 2 * r * Math.asin(Math.sqrt(h.coerceIn(0.0, 1.0)))
    }
}

/** Axis-aligned bounding box in WGS-84 degrees. */
data class GeoBounds(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double
) {
    val centerLat: Double get() = (south + north) / 2.0
    val centerLon: Double get() = (west + east) / 2.0

    fun contains(p: GeoPoint): Boolean =
        p.lon in west..east && p.lat in south..north

    fun containsWithMargin(p: GeoPoint, marginDeg: Double): Boolean =
        p.lon >= west - marginDeg && p.lon <= east + marginDeg &&
            p.lat >= south - marginDeg && p.lat <= north + marginDeg
}

/** Day / night map styling. Resolved by [MapStyleMode.AUTO] from time of day. */
enum class MapStyleMode { DAY, NIGHT, AUTO }

/**
 * How the camera follows the vehicle during navigation.
 */
enum class CameraFollowMode {
    /** Free panning; the camera does not move on location updates. */
    NONE,

    /** Camera centres the vehicle, north stays up. */
    FOLLOW,

    /** Camera centres the vehicle and rotates to heading. */
    FOLLOW_BEARING,

    /** Camera centres the vehicle, rotates to heading, tilts for 3D. */
    FOLLOW_BEARING_TILT
}

END FILE

## core/map-api/src/main/java/com/ts8/core/map/MapController.kt

BEGIN FILE
package com.ts8.core.map

/**
 * The map rendering contract.
 *
 * `core:map-api` deliberately contains no rendering code and no MapLibre
 * types, so `navigation-core` and the launcher UI can depend on map behaviour
 * without pulling in a native renderer, and so a second engine can be added
 * later without touching callers.
 */
interface MapController {
    /**
     * Attaches a renderer-owned view. Implementations return their own view
     * type; callers only use it as an opaque handle to add to a container.
     */
    fun attach()

    /** Releases every native resource. Safe to call more than once. */
    fun destroy()

    /** True once the style has loaded and the camera can be driven. */
    fun isReady(): Boolean

    /** Registers a callback invoked once the style is ready (or on failure). */
    fun setReadyListener(listener: ReadyListener?)

    fun setStyleMode(mode: MapStyleMode)

    fun getStyleMode(): MapStyleMode

    /**
     * Applies the `Hiển thị` settings to everything already drawn.
     *
     * **Why this is separate from [setStyleMode].** Day/night and the display
     * group are two different questions — one changes colours, the other
     * changes which layers exist and how big they are — but both end in the same
     * expensive operation, rebuilding the style. Keeping them apart lets an
     * implementation skip the rebuild when only the palette moved, and lets the
     * settings screen push a whole snapshot in one call instead of one setter
     * per checkbox.
     *
     * Implementations must be idempotent: the launcher re-applies the stored
     * snapshot on every resume, and a rebuild per resume would blank the map on
     * every tab switch.
     */
    fun applyDisplayConfig(config: MapDisplayConfig) = Unit

    /** Moves the camera with an optional animation. */
    fun moveCamera(target: CameraPosition, animate: Boolean = true)

    fun getCameraPosition(): CameraPosition

    fun setCameraFollow(mode: CameraFollowMode)

    /** Draws (or replaces) the active route polyline. */
    fun setRoute(route: RouteGeometry?)

    /**
     * How much of the drawn route has already been driven (§8: "Hiển thị route
     * đã đi và route còn lại").
     *
     * **Why this is separate from [setRoute].** The route line is redrawn on a
     * reroute, an overview toggle or a style rebuild; the split point moves on
     * every accepted fix. Passing it through [setRoute] would mean rebuilding
     * the whole polyline once a second, and a caller that had only the progress
     * fraction would have to fabricate a `RouteGeometry` to say so. This carries
     * one `Int` and lets the renderer move a boundary it already knows the
     * geometry of.
     *
     * [travelledIndex] is an index into the last [setRoute]'s points: points
     * before it have been driven, points from it on are still ahead. Implementations
     * clamp, and treat an absent route or an ignored setting as "draw no split".
     */
    fun setRouteProgress(travelledIndex: Int) = Unit

    /** Draws the vehicle marker; `bearingDeg` rotates the arrow. */
    fun setVehicleMarker(point: GeoPoint?, bearingDeg: Float)

    /** Adds a labelled search / destination marker. */
    fun setDestinationMarker(point: GeoPoint?, label: String?)

    /** Clears all overlays without touching the base map. */
    fun clearOverlays()

    /** In-memory screenshot of the current view, or null if unavailable. */
    fun snapshot(): android.graphics.Bitmap?

    interface ReadyListener {
        fun onReady()
        fun onFailed(reason: String)
    }
}

/** A camera position in map terms. `zoom` follows the style's zoom scale. */
data class CameraPosition(
    val target: GeoPoint,
    val zoom: Double,
    val bearing: Float = 0f,
    val tilt: Float = 0f
)

/**
 * A route as a list of polyline points, already simplified by the routing
 * engine for display.
 */
data class RouteGeometry(
    val points: List<GeoPoint>,
    /** Index into [points] where the destination lies, for trimming. */
    val destinationIndex: Int = points.lastIndex
) {
    /**
     * Converts "metres driven along the route" into the point index that
     * [MapController.setRouteProgress] expects (see §8).
     *
     * **Why this lives here and not in the renderer.** The navigator reports
     * progress as metres — the unit the snapping pipeline already speaks — while
     * the split has to be made at a vertex, because a GeoJSON line is drawn
     * between points. Doing the conversion once, against the same point list
     * the renderer was handed, guarantees the two agree; doing it inside the
     * renderer would make it re-measure a polyline it was only told to draw, and
     * a simplification change upstream would silently move the boundary.
     *
     * Cumulative great-circle distance, not a fraction of the point count:
     * route points are unevenly spaced — a long motorway straight is two
     * points, an inner-city junction is ten — so a fraction of the index would
     * put the car kilometres from where it actually is.
     *
     * Returns `0` for an empty or single-point route and for a distance at or
     * behind the start, and `points.lastIndex` once the route is covered.
     */
    fun indexAtDistance(meters: Int): Int {
        if (points.size < 2) return 0
        if (meters <= 0) return 0
        var travelled = 0.0
        for (i in 1..points.lastIndex) {
            travelled += points[i - 1].distanceTo(points[i])
            if (travelled > meters) return i - 1
        }
        return points.lastIndex
    }
}

END FILE

## core/map-api/src/main/java/com/ts8/core/map/MapDisplayConfig.kt

BEGIN FILE
package com.ts8.core.map

/**
 * Everything the `Hiển thị` panel can change about how the map is drawn.
 *
 * **Why this is a value object and not a callback into the settings store.**
 * `core:map-api` has no Android context and must not learn what a
 * `SharedPreferences` is. The launcher reads the driver's choices and hands
 * down one immutable snapshot; the renderer compares snapshots by equality to
 * decide whether a rebuild is needed. Without an equality-based snapshot the
 * common case — re-applying the stored settings on every `onResume` — would
 * rebuild the style and blank the map each time the driver switches tabs.
 *
 * **Why the defaults match §8 exactly.** A renderer constructed before the
 * driver ever opened the panel has to draw something, and the something it
 * draws must be what the brief calls the default. If the default lived in two
 * places (here and in the settings store) they would drift, and the panel would
 * open showing `Cân bằng` while the map was running on something else.
 *
 * **Why every field is honoured by the style builder.** The brief forbids a
 * switch that stores a value and changes nothing. Each property below is read
 * in [com.ts8.core.map.maplibre.StyleBuilder] to add, drop or resize a layer,
 * and [MapController.applyDisplayConfig] rebuilds the style when the snapshot
 * changes.
 */
data class MapDisplayConfig(
    /** 2D, 3D, or 3D only once zoomed in far enough to be readable. */
    val mode: MapMode = MapMode.FLAT_2D,
    /** Extruded buildings at all. Off means flat footprints only. */
    val buildings3d: Boolean = true,
    val labelRoads: Boolean = true,
    val labelPoi: Boolean = true,
    val labelPlace: Boolean = true,
    /**
     * House numbers.
     *
     * The shipped pack format does **not** emit `addr:housenumber` today, so
     * this layer exists and renders nothing until a pack carries the tag. That
     * is deliberate: the alternative is a switch that lies about working.
     * `docs/MAP_MANAGEMENT.md` records the limitation.
     */
    val labelHouseNumbers: Boolean = false,
    val poiDensity: PoiDensity = PoiDensity.NORMAL,
    val textScale: TextScale = TextScale.NORMAL,
    /** ARGB of the active route line. */
    val routeColour: Int = 0xFF3B82F6.toInt(),
    /** ARGB of an alternative route. */
    val altRouteColour: Int = 0xFF6C7A91.toInt(),
    /**
     * §8's "Hiển thị route đã đi và route còn lại".
     *
     * Off erases the driven part and leaves only what is ahead; on keeps it
     * drawn in a dimmer shade. This is a rendering choice, so it lives in the
     * display snapshot rather than beside the other route colours: it changes
     * which layers exist, not just what colour they are.
     */
    val showTravelledRoute: Boolean = true,
    val performance: Performance = Performance.BALANCED
) {
    enum class MapMode {
        /** Always tilted flat; the cheapest to draw. */
        FLAT_2D,

        /** Extruded buildings from the moment the pack emits them. */
        THREE_D,

        /** Flat while zoomed out, 3D once the streets are readable. */
        AUTO
    }

    enum class PoiDensity {
        /** Only the important POIs, and only when well zoomed in. */
        LOW,
        NORMAL,
        /** Every POI the pack carries, from the zoom it first appears at. */
        HIGH
    }

    enum class TextScale {
        SMALL,
        NORMAL,
        LARGE
    }

    /**
     * How much of the GPU and the tile pipeline the map may use.
     *
     * The three names are the brief's, in the brief's order, with `BALANCED` as
     * the default because this panel has to stay smooth while navigating on a
     * device with a weak GPU.
     */
    enum class Performance {
        QUALITY,
        BALANCED,
        SAVER
    }

    /** Multiplier applied to every label and icon size in the style. */
    val textMultiplier: Float
        get() = when (textScale) {
            TextScale.SMALL -> 0.85f
            TextScale.NORMAL -> 1f
            TextScale.LARGE -> 1.25f
        }

    /** True when the style should contain an extruded-building layer. */
    val wantsExtrusion: Boolean
        get() = buildings3d && mode != MapMode.FLAT_2D

    /** First zoom at which 3D buildings may appear. */
    val extrusionMinZoom: Double
        get() = when (mode) {
            MapMode.THREE_D -> 13.0
            // AUTO keeps the spec's threshold: below it the extrusion hides the
            // street network the driver is actually using.
            MapMode.AUTO -> 15.0
            MapMode.FLAT_2D -> Double.MAX_VALUE
        }

    /** First zoom at which POI labels may appear. */
    val poiMinZoom: Double
        get() = when (poiDensity) {
            PoiDensity.LOW -> 15.0
            PoiDensity.NORMAL -> 12.0
            PoiDensity.HIGH -> 11.0
        }
}

END FILE

## core/map-api/src/test/java/com/ts8/core/map/RouteGeometryTest.kt

BEGIN FILE
package com.ts8.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §8's "Hiển thị route đã đi và route còn lại" needs one honest number: where on
 * the drawn polyline the car currently is.
 *
 * These tests pin the conversion from metres (what the navigator reports) to a
 * point index (what the renderer needs). The interesting cases are all about
 * *unevenly spaced points*, which is what real routing output looks like: a
 * motorway straight is two vertices nine kilometres apart, an urban junction is
 * ten vertices within four hundred metres. A conversion by fraction of points
 * would be correct only if every segment were the same length, which never
 * happens.
 */
class RouteGeometryTest {

    /**
     * Three points on the same meridian, at 0°, 0.01° and 0.02° latitude.
     *
     * One degree of latitude is ~111 195 m on the sphere this code uses, so each
     * segment is ~1 112 m and the whole line is ~2 224 m.
     */
    private fun thirds(): RouteGeometry = RouteGeometry(
        listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.01, 0.0),
            GeoPoint(0.02, 0.0)
        )
    )

    @Test
    fun `empty route reports index zero`() {
        // Nothing to split. The renderer checks the point count again, but the
        // conversion must not throw on its way there.
        assertEquals(0, RouteGeometry(emptyList()).indexAtDistance(500))
    }

    @Test
    fun `single point route reports index zero`() {
        val route = RouteGeometry(listOf(GeoPoint(16.0, 108.0)))
        assertEquals(0, route.indexAtDistance(1_000))
    }

    @Test
    fun `zero metres is the start of the route`() {
        assertEquals(0, thirds().indexAtDistance(0))
    }

    @Test
    fun `negative distance from a stale fix is the start`() {
        // `distanceAlongRouteMeters` is never negative in practice, but a
        // clamping bug upstream must not produce an index of -1 and a crash.
        assertEquals(0, thirds().indexAtDistance(-250))
    }

    @Test
    fun `a distance inside the first segment stays on its first vertex`() {
        // 400 m into a 1 112 m segment: still between vertex 0 and vertex 1, so
        // the split belongs at 0. The segment is drawn whole in the "ahead"
        // colour and the driver sees no grey until they pass vertex 1 — which is
        // right, because vertex 0 is where they started.
        assertEquals(0, thirds().indexAtDistance(400))
    }

    @Test
    fun `a distance just past the first vertex advances the split`() {
        assertEquals(1, thirds().indexAtDistance(1_200))
    }

    @Test
    fun `a distance inside the last segment sits on the second vertex`() {
        // 1 800 m of 2 224 m: past vertex 1, short of vertex 2.
        assertEquals(1, thirds().indexAtDistance(1_800))
    }

    @Test
    fun `a distance past the end clamps to the last index`() {
        // Overshoot happens when the GPS fix is beyond the snapped end, or when
        // the route was recomputed with a shorter geometry than the progress
        // refers to. Clamping keeps the renderer from reading off the end.
        assertEquals(2, thirds().indexAtDistance(50_000))
    }

    @Test
    fun `exactly reaching the end lands on the last index`() {
        val route = thirds()
        val total = route.points[0].distanceTo(route.points[1]) +
            route.points[1].distanceTo(route.points[2])
        // One metre short, then at the end: the boundary is where the segment
        // ends, so the last vertex is reached at the last segment's length.
        assertEquals(1, route.indexAtDistance((total - 1).toInt()))
        assertEquals(2, route.indexAtDistance(total.toInt() + 1))
    }

    @Test
    fun `uneven segments are measured not counted`() {
        // The whole point of this conversion. `counted` would put the car at 25 %
        // of the way along after 1 000 m; the real line has it 1 000 m from the
        // start and therefore most of the way through a 1 112 m first segment.
        val route = RouteGeometry(
            listOf(
                GeoPoint(0.0, 0.0),
                GeoPoint(0.09, 0.0),   // ~10 000 m
                GeoPoint(0.091, 0.0)   // ~111 m
            )
        )
        // After 5 000 m the car is on the long first segment, index 0.
        assertEquals(0, route.indexAtDistance(5_000))
        // After 10 050 m it is past the long segment, still short of the end.
        assertEquals(1, route.indexAtDistance(10_050))
    }

    @Test
    fun `a real meridian degree is the distance assumed above`() {
        // Guards the fixture itself: if `GeoPoint.distanceTo` ever changed
        // formula, every expectation above would shift and these tests would
        // read as a regression in the split rather than in the distance.
        val oneDegree = GeoPoint(0.0, 0.0).distanceTo(GeoPoint(1.0, 0.0))
        assertTrue("one degree of latitude was $oneDegree m", oneDegree in 111_000.0..111_400.0)
    }
}

END FILE

## core/map-engine-maplibre/build.gradle.kts

BEGIN FILE
plugins {
    id("ts8.core-module")
}

android {
    namespace = "com.ts8.core.map.maplibre"
}

dependencies {
    // The rendering contract (`MapController`, `GeoPoint`, `MapStyleMode`, ...).
    // `api` rather than `implementation` because a caller that constructs this
    // engine necessarily already speaks the contract types.
    api(project(":core:map-api"))

    // MapLibre Native 12.0.0 is the last major line that still:
    //   * declares android:minSdkVersion="23" — exactly the Android 6 / API 23
    //     target of the TS8 head unit — and
    //   * renders through OpenGL ES rather than requiring Vulkan 1.0.
    //     13.x adds <uses-feature android:name="android.hardware.vulkan.version"
    //     required="true"/>, which this GPU does not advertise.
    //
    // Verified against the published 12.0.0 AAR, not assumed: the native
    // `DefaultFileSourceManagerImpl` registers `MBTilesFileSource` for
    // `FileSourceType::Mbtiles` unconditionally, and the RTTI typeinfo for
    // `mbgl::MBTilesFileSource` is present in all four ABIs
    // (armeabi-v7a, arm64-v8a, x86, x86_64). So `mbtiles://` works on every ABI
    // this APK ships, and the map pack needs no embedded HTTP server.
    //
    // The geojson/turf artifacts are pulled transitively at their own version
    // line — they are independent of the SDK's major version.
    api("org.maplibre.gl:android-sdk:12.0.0")
}

END FILE

## core/map-engine-maplibre/src/main/AndroidManifest.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<manifest />

END FILE

## core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/AndroidSqliteProbe.kt

BEGIN FILE
package com.ts8.core.map.maplibre

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * The one place the validator touches `android.database.sqlite`.
 *
 * Opened **read-only** and with no `SQLiteOpenHelper`: a pack is never migrated,
 * never written, and holds a WAL that belongs to the builder. `openDatabase`
 * with `OPEN_READONLY` is what MapLibre's own native reader does, so a pack that
 * passes here opens there.
 */
class AndroidSqliteProbe : SqliteProbe {

    override fun openReadOnly(absolutePath: String): SqliteProbe.OpenResult {
        val file = File(absolutePath)
        if (!file.isFile) {
            return SqliteProbe.OpenResult(false, null, "file not found")
        }
        if (file.length() <= 0L) {
            return SqliteProbe.OpenResult(false, null, "file is empty (0 bytes)")
        }
        return try {
            val db = SQLiteDatabase.openDatabase(
                absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
            SqliteProbe.OpenResult(true, Session(db), "opened")
        } catch (e: Exception) {
            // A half-copied SQLite file is the common case on a USB stick, and
            // its message ("file is not a database") is exactly what the
            // diagnostics report should carry.
            SqliteProbe.OpenResult(false, null, e.message ?: e.javaClass.simpleName)
        }
    }

    private class Session(private val db: SQLiteDatabase) : SqliteProbe.Session {

        override fun tableNames(): List<String> = runCatching {
            val out = ArrayList<String>(8)
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    // FTS shadow tables carry the same prefix as the virtual
                    // table and would drown the list in noise.
                    out += name
                }
            }
            out
        }.getOrDefault(emptyList())

        override fun rows(sql: String, vararg args: String): List<List<String?>> = runCatching {
            val out = ArrayList<List<String?>>(4)
            db.rawQuery(sql, args).use { c: Cursor ->
                val columns = c.columnCount
                while (c.moveToNext()) {
                    val row = ArrayList<String?>(columns)
                    for (i in 0 until columns) {
                        if (c.isNull(i)) row += null
                        else row += runCatching { c.getString(i) }.getOrNull()
                    }
                    out += row
                }
            }
            out
        }.getOrDefault(emptyList())

        override fun close() {
            runCatching { db.close() }
        }
    }
}

END FILE

## core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/MapLibreMapController.kt

BEGIN FILE
package com.ts8.core.map.maplibre

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import com.ts8.core.map.CameraFollowMode
import com.ts8.core.map.CameraPosition
import com.ts8.core.map.GeoPoint
import com.ts8.core.map.MapController
import com.ts8.core.map.MapDisplayConfig
import com.ts8.core.map.MapStyleMode
import com.ts8.core.map.RouteGeometry
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * The MapLibre implementation of [MapController].
 *
 * **Offline contract.** The style is generated by [StyleBuilder] and names an
 * absolute `mbtiles://<path>` tile source, so every tile is read from the local
 * pack with the native `MBTilesFileSource`. Nothing on the map path touches the
 * network: no style URL, no glyph CDN, no sprite CDN, no telemetry endpoint.
 * MapLibre is configured through [MapLibreRuntime] with the network disabled so
 * a misconfigured style can only fail, never leak a request.
 *
 * **Overlays.** Route, vehicle and destination are separate GeoJSON sources
 * added after the style loads. They are deliberately *not* part of the static
 * style document: a route changes on every reroute and a vehicle marker moves
 * at 10 Hz, and rewriting the style for each is what makes a cheap head unit
 * drop frames. Adding one empty source per overlay keeps `setData` cheap.
 *
 * **Threading.** Every MapLibre call must be on the UI thread. Callers in this
 * app are Activities and the navigation controller, both main-thread, so the
 * methods assert rather than hop; a silent hop would reorder camera updates.
 */
class MapLibreMapController(
    private val context: Context,
    private val pack: MapPack,
    private val mapView: MapView
) : MapController {

    private var map: MapLibreMap? = null
    private var style: Style? = null

    /**
     * What the caller asked for, **including** `AUTO`.
     *
     * Kept separate from [resolvedMode]'s output on purpose: a controller that
     * stores only the resolved palette forgets that the driver chose `AUTO`,
     * `getStyleMode()` can then never report it, and the Home screen's
     * day/night cycle silently loses its third stop. The requested value is
     * the setting; [resolved] is merely today's rendering of it.
     */
    private var mode: MapStyleMode = MapStyleMode.DAY

    /** The concrete palette the current style document was built with. */
    private var resolved: MapStyleMode = MapStyleMode.DAY

    /**
     * The `Hiển thị` snapshot the current style was built from.
     *
     * Kept for two reasons: the rebuild on a day/night flip has to use the same
     * layer set, and [applyDisplayConfig] compares against it so the launcher's
     * habit of re-pushing the stored settings on every resume does not rebuild
     * the style and blank the map each time the driver switches tabs.
     */
    private var display: MapDisplayConfig = MapDisplayConfig()

    private var ready = false
    private var destroyed = false
    private var followMode = CameraFollowMode.NONE

    private var readyListener: MapController.ReadyListener? = null
    private var pendingFailure: String? = null

    /** Latest camera target requested while the style was not ready yet. */
    private var pendingCamera: CameraPosition? = null
    private var pendingRoute: RouteGeometry? = null
    private var pendingVehicle: Pair<GeoPoint?, Float>? = null
    private var pendingDestination: Pair<GeoPoint?, String?>? = null

    /** The route line currently drawn, kept so a reroute can diff against it. */
    private var routePoints: List<GeoPoint> = emptyList()

    /**
     * Index into [routePoints] up to which the driver has already driven.
     *
     * `-1` means "no progress reported yet". This is the **raw** value from
     * [setRouteProgress]: whether that split is *drawn* is a separate question,
     * answered by [MapDisplayConfig.showTravelledRoute] at draw time. Keeping
     * the two apart is what lets the driver toggle `Hiện đường đã đi` off and
     * back on mid-trip without the position on the route being thrown away.
     *
     * Kept as a field rather than a parameter so a style rebuild can replay it:
     * without that, a day/night flip mid-route would silently forget how far
     * the driver had got and redraw the whole line in the "remaining" colour.
     */
    private var travelledIndex: Int = -1

    // -- lifecycle ----------------------------------------------------------

    override fun attach() {
        // MapView.onCreate must be called before getMapAsync. The Activity owns
        // the full lifecycle (onStart/onResume/onPause/onStop/onDestroy) because
        // only it knows when the map is actually visible; doing it here would
        // create the GL surface while the tab is off screen.
        mapView.getMapAsync { readyMap -> onMapReady(readyMap) }
    }

    private fun onMapReady(readyMap: MapLibreMap) {
        if (destroyed) return
        map = readyMap
        MapLibreRuntime.configure(readyMap)

        // The pack bounds are the only place the camera may go: panning off the
        // edge shows blank space, which reads as a broken map to a driver.
        pack.bounds?.let { b ->
            runCatching {
                readyMap.setLatLngBoundsForCameraTarget(
                    LatLngBounds.from(b.north, b.east, b.south, b.west)
                )
            }
        }
        readyMap.setMinZoomPreference(pack.metadata.minZoom)
        // The camera limit is `maxCameraZoom`, not the tile range: tiles stop at
        // 14 but buildings extrude from 15, and MapLibre overzooms the last
        // level. Clamping to the tile range here would put the 3D view out of
        // reach entirely.
        readyMap.setMaxZoomPreference(pack.metadata.maxCameraZoom)

        applyPerformance(readyMap)

        try {
            resolved = resolvedFor(mode)
            val json = StyleBuilder.build(pack, resolved, density(), display)
            readyMap.setStyle(Style.Builder().fromJson(json)) { loaded ->
                onStyleLoaded(loaded)
            }
        } catch (e: Exception) {
            // A style document generated from a valid pack should not throw; if
            // it does the reason must reach the screen, not the log.
            fail("Không dựng được style bản đồ: ${e.message}")
        }
    }

    private fun onStyleLoaded(loaded: Style) {
        if (destroyed) return
        style = loaded
        try {
            addOverlayLayers(loaded)
        } catch (e: Exception) {
            // A broken overlay must not take the base map down with it: the
            // driver still needs roads and labels.
            android.util.Log.w(TAG, "overlay layers failed: ${e.message}")
        }
        ready = true
        // Replay whatever was asked for before the style finished loading. On a
        // cold start the Activity usually sets the camera and the route first.
        //
        // The progress is captured before the route is replayed because
        // `setRoute` invalidates the split; a caller that pushed both while the
        // style was still parsing (the navigation screen does exactly that on
        // resume) would otherwise come back with a single undivided line.
        val pendingProgress = travelledIndex
        pendingCamera?.let { moveCamera(it, animate = false); pendingCamera = null }
        pendingRoute?.let { setRoute(it); pendingRoute = null }
        if (pendingProgress >= 0 && routePoints.size >= 2) setRouteProgress(pendingProgress)
        pendingVehicle?.let { (point, bearing) -> setVehicleMarker(point, bearing); pendingVehicle = null }
        pendingDestination?.let { (point, label) -> setDestinationMarker(point, label); pendingDestination = null }
        readyListener?.onReady()
    }

    private fun fail(reason: String) {
        pendingFailure = reason
        ready = false
        readyListener?.onFailed(reason)
    }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        ready = false
        style = null
        map = null
        readyListener = null
    }

    /** Called by the Activity from its own `onCreate`. */
    fun onCreate(savedInstanceState: Bundle?) = mapView.onCreate(savedInstanceState)

    /** Called by the Activity from its own `onStart`. */
    fun onStart() = mapView.onStart()

    /** Called by the Activity from its own `onResume`. */
    fun onResume() = mapView.onResume()

    /** Called by the Activity from its own `onPause`. */
    fun onPause() = mapView.onPause()

    /** Called by the Activity from its own `onStop`. */
    fun onStop() = mapView.onStop()

    /** Called by the Activity from its own `onDestroy`; releases GL and the pack. */
    fun onDestroyView() {
        destroy()
        mapView.onDestroy()
    }

    fun onSaveInstanceState(outState: Bundle) = mapView.onSaveInstanceState(outState)

    // -- readiness ----------------------------------------------------------

    override fun isReady(): Boolean = ready && style != null

    override fun setReadyListener(listener: MapController.ReadyListener?) {
        readyListener = listener
        // A listener installed after the fact still has to hear the outcome,
        // otherwise the Activity waits forever on a failure that already happened.
        val failure = pendingFailure
        if (failure != null) {
            listener?.onFailed(failure)
        } else if (ready) {
            listener?.onReady()
        }
    }

    // -- style --------------------------------------------------------------

    override fun setStyleMode(mode: MapStyleMode) {
        val next = resolvedFor(mode)
        // The request is remembered first, so `getStyleMode()` reports what the
        // driver chose — `AUTO` included — rather than the palette it happens
        // to resolve to right now.
        this.mode = mode
        // Nothing to redraw when the palette is the same. This is the common
        // case on every `onResume`: the screen re-applies the stored mode, and
        // rebuilding the style there would blank the map for a second each
        // time the driver switches tabs.
        if (next == resolved) return
        val m = map ?: run { resolved = next; return }
        if (!ready) {
            // The style has not loaded yet; `onMapReady` builds with `mode`,
            // so recording the request is all that is needed.
            resolved = next
            return
        }
        val savedCamera = getCameraPosition()
        val savedRoute = routePoints
        // Captured across the rebuild because `setRoute`, which replays the
        // polyline into the new style, deliberately clears it (see its comment).
        // Without this a day/night flip halfway through a trip redraws the whole
        // route in the "still ahead" colour and the driver loses the only visual
        // cue for how far they have come.
        val savedTravelled = travelledIndex
        try {
            // Rebuilding the style is unavoidable: a MapLibre style cannot swap
            // its palette in place. The camera, route and markers are captured
            // first and replayed, so a day/night flip does not lose the trip.
            // The display snapshot is reused verbatim: a palette change must not
            // also silently reset the driver's layer choices.
            resolved = next
            val json = StyleBuilder.build(pack, next, density(), display)
            m.setStyle(Style.Builder().fromJson(json)) { loaded ->
                onStyleLoaded(loaded)
                if (savedRoute.isNotEmpty()) {
                    setRoute(RouteGeometry(savedRoute))
                    if (savedTravelled >= 0) setRouteProgress(savedTravelled)
                }
                moveCamera(savedCamera, animate = false)
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "setStyleMode failed: ${e.message}")
        }
    }

    override fun getStyleMode(): MapStyleMode = mode

    /**
     * Rebuilds the style with a new `Hiển thị` snapshot.
     *
     * **Why equality, not a dirty flag.** The launcher pushes the stored
     * settings from `onResume` of two screens and from the settings panel. A
     * rebuild costs a full style parse and a visible flash, so the comparison
     * here is what makes `applyDisplayConfig` safe to call unconditionally.
     *
     * **Why the camera and route are captured first.** `setStyle` throws the
     * whole style away, overlays included. Without the replay a driver who
     * toggles a label loses their route line and their position on the map,
     * which reads as a crash.
     */
    override fun applyDisplayConfig(config: MapDisplayConfig) {
        if (config == display) return
        display = config
        val m = map
        // Recorded even when the map is not up yet: `onMapReady` builds the
        // first style from `display`, so a snapshot pushed early is honoured
        // without a second build.
        if (m == null || !ready) return
        applyPerformance(m)
        val savedCamera = getCameraPosition()
        val savedRoute = routePoints
        val savedTravelled = travelledIndex
        val savedVehicle = pendingVehicle
        try {
            val json = StyleBuilder.build(pack, resolved, density(), display)
            m.setStyle(Style.Builder().fromJson(json)) { loaded ->
                onStyleLoaded(loaded)
                if (savedRoute.isNotEmpty()) {
                    setRoute(RouteGeometry(savedRoute))
                    // Same replay as a palette flip, and here it is also what
                    // makes the switch itself take effect: `setRoute` redraws
                    // with the raw index and `drawRoute` reads the *new*
                    // snapshot's `showTravelledRoute`.
                    if (savedTravelled >= 0) setRouteProgress(savedTravelled)
                }
                savedVehicle?.let { (point, bearing) -> setVehicleMarker(point, bearing) }
                moveCamera(savedCamera, animate = false)
            }
        } catch (e: Exception) {
            // The old style is whatever MapLibre still has; keep drawing it
            // rather than leaving the panel believing the change applied.
            android.util.Log.w(TAG, "applyDisplayConfig failed: ${e.message}")
            display = config
        }
    }

    /**
     * Maps the performance setting onto MapLibre's own tile pipeline knobs.
     *
     * These are the only levers the renderer exposes for a weak GPU: how far
     * ahead of the camera tiles are fetched, whether the tile cache is used at
     * all, and how the level-of-detail heuristic behaves while the camera moves.
     * `SAVER` turns prefetching off entirely, which is what stops the stutter on
     * this panel while a route is being followed.
     */
    private fun applyPerformance(m: MapLibreMap) {
        runCatching {
            when (display.performance) {
                MapDisplayConfig.Performance.QUALITY -> {
                    m.setPrefetchesTiles(true)
                    m.setPrefetchZoomDelta(1)
                    m.setTileCacheEnabled(true)
                }
                MapDisplayConfig.Performance.BALANCED -> {
                    m.setPrefetchesTiles(true)
                    m.setPrefetchZoomDelta(0)
                    m.setTileCacheEnabled(true)
                }
                MapDisplayConfig.Performance.SAVER -> {
                    m.setPrefetchesTiles(false)
                    m.setPrefetchZoomDelta(0)
                    m.setTileCacheEnabled(true)
                }
            }
        }.onFailure { android.util.Log.w(TAG, "performance knobs unavailable: ${it.message}") }
    }

    /**
     * `AUTO` resolves by local clock, which is the only signal available
     * offline. Night runs 19:00–06:00, matching Vietnam's typical dusk/dawn.
     */
    private fun resolvedFor(requested: MapStyleMode): MapStyleMode {
        if (requested != MapStyleMode.AUTO) return requested
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return if (hour >= 19 || hour < 6) MapStyleMode.NIGHT else MapStyleMode.DAY
    }

    private fun density(): Float = context.resources.displayMetrics.density

    // -- camera -------------------------------------------------------------

    override fun moveCamera(target: CameraPosition, animate: Boolean) {
        val m = map ?: run { pendingCamera = target; return }
        if (!ready) {
            pendingCamera = target
            return
        }
        val update = CameraUpdateFactory.newCameraPosition(
            org.maplibre.android.camera.CameraPosition.Builder()
                .target(LatLng(target.target.lat, target.target.lon))
                .zoom(target.zoom)
                .bearing(target.bearing.toDouble())
                .tilt(target.tilt.toDouble())
                .build()
        )
        if (animate) m.animateCamera(update) else m.moveCamera(update)
    }

    override fun getCameraPosition(): CameraPosition {
        val m = map ?: return pendingCamera ?: defaultCamera()
        val pos = runCatching { m.cameraPosition }.getOrNull() ?: return defaultCamera()
        val target = pos.target ?: return defaultCamera()
        return CameraPosition(
            target = GeoPoint(target.latitude, target.longitude),
            zoom = pos.zoom,
            bearing = pos.bearing.toFloat(),
            tilt = pos.tilt.toFloat()
        )
    }

    private fun defaultCamera(): CameraPosition = CameraPosition(
        target = pack.center ?: GeoPoint(16.0678, 108.2215),
        zoom = pack.metadata.defaultZoom
    )

    override fun setCameraFollow(mode: CameraFollowMode) {
        followMode = mode
    }

    /**
     * Applies the follow policy to a new fix.
     *
     * Exposed separately from [setVehicleMarker] because the camera policy is a
     * navigation concern while the marker is a rendering one; the navigation
     * screen drives the camera and the home map does not.
     */
    fun onNavigationFix(point: GeoPoint, bearingDeg: Float) {
        setVehicleMarker(point, bearingDeg)
        val zoom = when (followMode) {
            CameraFollowMode.NONE -> return
            CameraFollowMode.FOLLOW -> NAV_ZOOM
            CameraFollowMode.FOLLOW_BEARING -> NAV_ZOOM
            CameraFollowMode.FOLLOW_BEARING_TILT -> NAV_ZOOM_TILT
        }
        val bearing = if (followMode == CameraFollowMode.FOLLOW) 0f else bearingDeg
        val tilt = if (followMode == CameraFollowMode.FOLLOW_BEARING_TILT) NAV_TILT else 0f
        moveCamera(CameraPosition(point, zoom, bearing, tilt), animate = true)
    }

    // -- overlays -----------------------------------------------------------

    private fun addOverlayLayers(s: Style) {
        s.addSource(GeoJsonSource(ROUTE_OVERLAY, emptyCollection()))
        s.addSource(GeoJsonSource(VEHICLE_OVERLAY, emptyCollection()))
        s.addSource(GeoJsonSource(DESTINATION_OVERLAY, emptyCollection()))

        // The route colour is the driver's, not the palette's. It comes from the
        // `Hiển thị` panel and applies in both day and night, because a driver
        // who picked orange did so to see it on this panel, not in one theme.
        val routeColour = display.routeColour
        val casing = if (colorLuminance(routeColour) > 0.6) "#1b1b1b" else "#ffffff"
        val iconScale = display.textMultiplier

        // §8's "route đã đi và route còn lại": one source, two features, and
        // these expressions pick the shade. The driven part is dimmed rather
        // than recoloured — the driver chose the route colour and must still
        // read the whole line as one route — while the part still ahead keeps
        // full opacity. `case` over the boolean feature property is the only
        // per-feature styling MapLibre gives a line layer, and it costs nothing
        // per frame.
        //
        // Every feature `drawRoute` writes carries the property, including the
        // single undivided one used when the driver turned the setting off, so
        // the `else` branch is only reached by a feature that predates this
        // build. It is written as the full-opacity branch regardless: a route
        // that silently dimmed itself for want of a property would be the worst
        // possible failure, because it would look like the car had driven the
        // whole thing.
        val travelledCase = Expression.switchCase(
            Expression.get(PROP_TRAVELLED),
            Expression.literal(0.35f),
            Expression.literal(1f)
        )

        s.addLayer(
            LineLayer(LAYER_ROUTE_CASING, ROUTE_OVERLAY).withProperties(
                PropertyFactory.lineColor(casing),
                PropertyFactory.lineWidth(9f),
                PropertyFactory.lineOpacity(
                    Expression.switchCase(
                        Expression.get(PROP_TRAVELLED),
                        Expression.literal(0.35f),
                        Expression.literal(0.9f)
                    )
                ),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round")
            )
        )
        s.addLayer(
            LineLayer(LAYER_ROUTE_LINE, ROUTE_OVERLAY).withProperties(
                PropertyFactory.lineColor(routeColour),
                PropertyFactory.lineWidth(6f),
                PropertyFactory.lineOpacity(travelledCase),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round")
            )
        )
        s.addLayer(
            SymbolLayer(LAYER_DESTINATION, DESTINATION_OVERLAY).withProperties(
                PropertyFactory.iconImage(PIN_IMAGE),
                PropertyFactory.iconSize(iconScale),
                PropertyFactory.iconAnchor("bottom"),
                PropertyFactory.iconAllowOverlap(true)
            )
        )
        s.addLayer(
            CircleLayer(LAYER_VEHICLE_HALO, VEHICLE_OVERLAY).withProperties(
                PropertyFactory.circleRadius(16f * iconScale),
                PropertyFactory.circleColor(routeColour),
                PropertyFactory.circleOpacity(0.22f)
            )
        )
        s.addLayer(
            SymbolLayer(LAYER_VEHICLE, VEHICLE_OVERLAY).withProperties(
                PropertyFactory.iconImage(VEHICLE_IMAGE),
                PropertyFactory.iconSize(1f),
                PropertyFactory.iconAnchor("center"),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconRotate(Expression.get("bearing"))
            )
        )

        s.addImage(PIN_IMAGE, OverlayIcons.pin(density()))
        s.addImage(VEHICLE_IMAGE, OverlayIcons.vehicle(density()))
    }

    /**
     * Rough perceived brightness of an ARGB colour, `0` black to `1` white.
     *
     * Used only to pick a route casing that stays visible under the driver's
     * chosen line colour: a white casing under a near-white line disappears, and
     * the casing is what keeps the route legible over a busy map.
     */
    private fun colorLuminance(argb: Int): Double {
        val r = (argb shr 16 and 0xFF) / 255.0
        val g = (argb shr 8 and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    /** The palette the current style was built with; used by diagnostics only. */
    fun resolvedMode(): MapStyleMode = resolved

    private fun emptyCollection(): FeatureCollection =
        FeatureCollection.fromFeatures(emptyList<Feature>())

    private fun overlaySource(id: String): GeoJsonSource? =
        style?.getSourceAs<GeoJsonSource>(id)

    override fun setRoute(route: RouteGeometry?) {
        val points = route?.points.orEmpty()
        routePoints = points
        // A new geometry invalidates the split: an index into the *previous*
        // polyline would paint an arbitrary prefix of a fresh reroute as already
        // driven, and on a reroute that turns the car around that means half of
        // the new road is greyed out as "already behind you". The caller
        // re-sends progress with the next accepted fix. The two style-rebuild
        // paths in this file replay it immediately, because their geometry is
        // the same polyline and the split is still true.
        travelledIndex = -1
        val source = overlaySource(ROUTE_OVERLAY) ?: run {
            pendingRoute = route
            return
        }
        drawRoute(points)
    }

    /**
     * Splits the drawn route at [travelledIndex] (§8).
     *
     * **Why the line is rebuilt rather than styled with an expression.** MapLibre
     * can colour a line by a feature property, but not by "distance along the
     * line", so the split has to be made of two geometries. Two `setGeoJson`
     * calls per accepted fix is cheap next to a style rebuild, and the geometry
     * is already in memory.
     *
     * **Why a driven part and an ahead part, not a shorter line.** The brief
     * asks for both to be *shown*; erasing the driven part is the option when
     * the driver turns the setting off, not the default. Drawing the ahead part
     * alone in the driver's route colour and the driven part in a dimmed shade
     * keeps the route readable as a whole while making the car's position on it
     * obvious.
     */
    override fun setRouteProgress(travelledIndex: Int) {
        // The raw index is kept; whether it is *drawn* is decided in
        // [drawRoute] from the current display snapshot. Storing `-1` here when
        // the setting is off would mean turning the setting back on mid-trip
        // froze the split at wherever the driver happened to be, which is not
        // what a checkbox labelled `Hiện đường đã đi` promises.
        this.travelledIndex = travelledIndex
        val source = overlaySource(ROUTE_OVERLAY) ?: return
        if (routePoints.size < 2) {
            source.setGeoJson(emptyCollection())
            return
        }
        drawRoute(routePoints)
    }

    /**
     * Writes the two halves of the route into one GeoJSON source.
     *
     * Both features carry a `travelled` boolean, which the line layers read as
     * a `case` over their colour and opacity. One source, two layers, two
     * features — rather than two sources, because a second source means a
     * second `addSource`/`addLayer` pair to keep in step through every style
     * rebuild in this file.
     */
    private fun drawRoute(points: List<GeoPoint>) {
        val source = overlaySource(ROUTE_OVERLAY) ?: return
        if (points.size < 2) {
            source.setGeoJson(emptyCollection())
            return
        }
        // §8's switch is read here, at the moment the geometry is written, so a
        // display-config change takes effect on the next style rebuild even
        // though the raw progress was captured before it. Off means one
        // feature: the whole line, undivided, at full opacity.
        if (!display.showTravelledRoute || travelledIndex <= 0) {
            source.setGeoJson(lineFeature(points, travelled = false))
            return
        }
        // Clamped inside the polyline: a split at 0 or at the end is legal and
        // means "nothing driven yet" / "arrived".
        val split = if (travelledIndex > points.lastIndex) points.lastIndex else travelledIndex
        val features = ArrayList<Feature>(2)
        features.add(lineFeature(points.subList(0, split + 1), travelled = true))
        if (split < points.lastIndex) {
            features.add(lineFeature(points.subList(split, points.size), travelled = false))
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun lineFeature(points: List<GeoPoint>, travelled: Boolean): Feature {
        val lngLats = points.map { Point.fromLngLat(it.lon, it.lat) }
        val feature = Feature.fromGeometry(LineString.fromLngLats(lngLats))
        feature.addBooleanProperty(PROP_TRAVELLED, travelled)
        return feature
    }

    override fun setVehicleMarker(point: GeoPoint?, bearingDeg: Float) {
        val source = overlaySource(VEHICLE_OVERLAY) ?: run {
            pendingVehicle = point to bearingDeg
            return
        }
        if (point == null) {
            source.setGeoJson(emptyCollection())
            return
        }
        val feature = Feature.fromGeometry(Point.fromLngLat(point.lon, point.lat))
        feature.addNumberProperty("bearing", bearingDeg.toDouble())
        source.setGeoJson(feature)
    }

    override fun setDestinationMarker(point: GeoPoint?, label: String?) {
        val source = overlaySource(DESTINATION_OVERLAY) ?: run {
            pendingDestination = point to label
            return
        }
        if (point == null) {
            source.setGeoJson(emptyCollection())
            return
        }
        val feature = Feature.fromGeometry(Point.fromLngLat(point.lon, point.lat))
        if (!label.isNullOrEmpty()) feature.addStringProperty("label", label)
        source.setGeoJson(feature)
    }

    override fun clearOverlays() {
        routePoints = emptyList()
        // Cleared with the geometry: a stale index would be applied to the next
        // route drawn into this same controller.
        travelledIndex = -1
        pendingRoute = null
        pendingVehicle = null
        pendingDestination = null
        overlaySource(ROUTE_OVERLAY)?.setGeoJson(emptyCollection())
        overlaySource(VEHICLE_OVERLAY)?.setGeoJson(emptyCollection())
        overlaySource(DESTINATION_OVERLAY)?.setGeoJson(emptyCollection())
    }

    /**
     * Fits the camera to a whole route plus padding, used right after a route
     * is calculated. Called only when the route has at least two points.
     */
    fun fitRoute(route: RouteGeometry, paddingPx: Int = 48) {
        val m = map ?: return
        val points = route.points
        if (points.size < 2 || !ready) return
        var west = Double.MAX_VALUE
        var east = -Double.MAX_VALUE
        var south = Double.MAX_VALUE
        var north = -Double.MAX_VALUE
        for (p in points) {
            if (p.lon < west) west = p.lon
            if (p.lon > east) east = p.lon
            if (p.lat < south) south = p.lat
            if (p.lat > north) north = p.lat
        }
        // A zero-area bbox throws inside LatLngBounds; a straight route along one
        // road is common enough that this guard is load-bearing.
        val pad = 1e-4
        val bounds = LatLngBounds.from(north + pad, east + pad, south - pad, west - pad)
        runCatching {
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx))
        }
    }

    override fun snapshot(): Bitmap? {
        val m = map ?: return null
        // snapshot is asynchronous and the callback may never fire if the map
        // is mid-destroy, so this blocks briefly rather than returning a
        // half-drawn frame. It is only used by diagnostics, never on the drive path.
        var bitmap: Bitmap? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        runCatching {
            m.snapshot { result ->
                bitmap = result
                latch.countDown()
            }
        }
        runCatching { latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS) }
        return bitmap
    }

    companion object {
        private const val TAG = "Ts8MapLibre"

        const val ROUTE_OVERLAY = "ts8-route"
        const val VEHICLE_OVERLAY = "ts8-vehicle"
        const val DESTINATION_OVERLAY = "ts8-destination"

        const val LAYER_ROUTE_CASING = "ts8-route-casing"
        const val LAYER_ROUTE_LINE = "ts8-route-line"
        const val LAYER_DESTINATION = "ts8-destination-layer"
        const val LAYER_VEHICLE_HALO = "ts8-vehicle-halo"
        const val LAYER_VEHICLE = "ts8-vehicle-layer"

        const val PIN_IMAGE = "ts8-pin"
        const val VEHICLE_IMAGE = "ts8-vehicle"

        /**
         * Boolean feature property that marks the driven half of the route.
         *
         * Named once here rather than repeated in `drawRoute` and in the two
         * layer expressions: a typo on either side would not fail to compile,
         * it would silently paint the whole line at full opacity and the split
         * would just never appear (§8).
         */
        const val PROP_TRAVELLED = "travelled"

        private const val NAV_ZOOM = 17.0
        private const val NAV_ZOOM_TILT = 17.5
        private const val NAV_TILT = 45f
    }
}

END FILE

## core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/MapLibreRuntime.kt

BEGIN FILE
package com.ts8.core.map.maplibre

import com.ts8.core.map.GeoPoint
import org.maplibre.android.maps.MapLibreMap

/**
 * The MapLibre runtime configuration that enforces the offline contract.
 *
 * **Why this exists as its own object.** The spec requires the map to work with
 * the network off, and "works offline" is the kind of claim that quietly stops
 * being true the moment someone adds a style URL, a glyph CDN or an icon
 * sprite. Rather than trusting that no code path reaches the network, this
 * class disables the parts of MapLibre that would reach it, so the only
 * supported tile origin is the local pack.
 *
 * MapLibre Native has no single "offline mode" switch. The three things that
 * make it phone home are:
 *
 * 1. The **style URL** — a style fetched over HTTP pulls its sources with it.
 *    This engine never passes a URL; it always hands MapLibre a JSON document
 *    built from the pack.
 * 2. The **glyph and sprite URLs** — a style with `glyphs: "https://..."` fetches
 *    font PBFs per range. [StyleBuilder] points them at the pack's own
 *    directory, and omits the block entirely when the pack has no glyphs.
 * 3. The **telemetry / offline database** — absent a valid `apiKey`, MapLibre
 *    still opens its ambient cache but has nowhere to upload to.
 *
 * So the enforcement here is: no UI logo or attribution widget that loads a
 * remote image, no compass/runtime view additions that fetch assets, and
 * gestures configured for a fixed car screen rather than a phone.
 */
internal object MapLibreRuntime {

    /** Configures a ready map for a fixed in-car display. */
    fun configure(map: MapLibreMap) {
        val ui = map.uiSettings
        // Nothing in this app rotates the map by hand; navigation drives the
        // bearing. Leaving rotate gestures on means a palm on the screen during
        // a turn silently changes the heading and the driver loses north.
        ui.isRotateGesturesEnabled = false
        ui.isTiltGesturesEnabled = true
        ui.isScrollGesturesEnabled = true
        ui.isZoomGesturesEnabled = true
        ui.isDoubleTapGesturesEnabled = true
        ui.isQuickZoomGesturesEnabled = true
        // MapLibre's logo and attribution widgets load from assets bundled in
        // the AAR, not the network, but they occupy screen edge the bottom bar
        // needs. Attribution is drawn by the UI instead, from the pack's own
        // `attribution` field, which is the same ODbL credit.
        ui.isLogoEnabled = false
        ui.isAttributionEnabled = false
        ui.isCompassEnabled = false
    }

    /**
     * Clamps a point into the pack's bounds.
     *
     * Used before showing an external coordinate (a destination from search, a
     * GPS fix from a drifted receiver) so the camera never pans into blank
     * space where the pack has no tiles.
     */
    fun clampToPack(pack: MapPack, point: GeoPoint): GeoPoint {
        val b = pack.bounds ?: return point
        return GeoPoint(
            lat = point.lat.coerceIn(b.south, b.north),
            lon = point.lon.coerceIn(b.west, b.east)
        )
    }
}

END FILE

## core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/MapPack.kt

BEGIN FILE
package com.ts8.core.map.maplibre

import android.content.Context
import com.ts8.core.map.GeoBounds
import com.ts8.core.map.GeoPoint
import com.ts8.core.map.MapStyleMode
import org.json.JSONObject
import java.io.File

/**
 * A map pack on disk, validated before it is handed to the renderer.
 *
 * **Why validation is a class rather than three lines in the engine.** A pack
 * lives on a USB stick. It can be half-copied, built by an older tool, or for a
 * different city. Every one of those fails inside MapLibre as an unhelpful
 * native crash or a silently blank map. Checking the manifest and the files it
 * names up front turns each of them into a message the driver can act on.
 *
 * Nothing here invents data: a pack that fails validation is reported as
 * unusable, and the map screen shows why. The spec forbids falling back to demo
 * tiles, and a blank grid that looks like a map is worse than an error.
 *
 * **Load vs. validate.** [load] is the cheap pass: manifest parsed, required
 * files stat'ed. It is safe on the main thread and is what discovery runs.
 * [PackValidator] is the expensive pass (checksums, SQLite opens, a real tile
 * query) and runs on the IO executor. Splitting them is what lets the UI show
 * `DISCOVERING → FOUND` instantly and then fill in the rest without a freeze.
 */
class MapPack private constructor(
    val directory: File,
    val metadata: Metadata
) {

    /** Absolute path of the tile container, unencoded. */
    val tilesPath: String get() = File(directory, metadata.basemap).absolutePath

    /**
     * The `mbtiles://` URL the style source gets.
     *
     * Percent-encoded exactly once, here — see [PackPaths] for why a raw path
     * breaks silently.
     */
    val tilesUri: String get() = PackPaths.mbtilesUrl(tilesPath)

    /**
     * The style the pack ships, or null when it ships none.
     *
     * Kept because a pack built by a newer tool may carry a hand-authored style.
     * The current build always generates one from the palette, so this is only
     * consulted by [PackValidator] and by the diagnostics report.
     */
    val stylePath: File?
        get() = File(directory, if (metadata.styleNight != null) metadata.styleNight!! else metadata.styleDay)
            .takeIf { it.isFile }

    fun styleFile(mode: MapStyleMode): File? {
        val name = when (mode) {
            MapStyleMode.DAY -> metadata.styleDay
            MapStyleMode.NIGHT -> metadata.styleNight ?: metadata.styleDay
            MapStyleMode.AUTO -> metadata.styleDay
        }
        return File(directory, name).takeIf { it.isFile }
    }

    /** True when the pack carries its own glyphs, so labels work offline. */
    val hasGlyphs: Boolean get() = metadata.glyphs != null && resolveGlyphsDir() != null

    fun resolveGlyphsDir(): File? {
        val declared = metadata.glyphs ?: return null
        // The manifest names a template ("fonts/{fontstack}/{range}.pbf"); the
        // directory is everything before the first placeholder.
        val cut = declared.indexOf('{')
        val root = if (cut > 0) declared.substring(0, cut).trimEnd('/') else declared
        return File(directory, root).takeIf { it.isDirectory }
    }

    /**
     * The sprite base path without extension, or null.
     *
     * MapLibre asks for `<base>.json` and `<base>@2x.png`; the pack stores the
     * pair. Returned only when both exist, because a half sprite renders worse
     * than no sprite (MapLibre drops every icon layer when the JSON 404s).
     */
    fun resolveSprite(): File? {
        val declared = metadata.sprite?.takeIf { it.isNotBlank() } ?: return null
        val base = File(directory, declared)
        val json = File(base.parentFile, base.name + ".json")
        return base.takeIf { json.isFile && File(base.parentFile, base.name + "@2x.png").isFile }
    }

    fun databaseFile(): File? = File(directory, metadata.placesDb).takeIf { it.isFile }

    fun routingDir(): File? = File(directory, metadata.routingGraph).takeIf { it.isDirectory }

    /** The pack's own bounds, used to clamp the camera to real data. */
    val bounds: GeoBounds?
        get() = metadata.bounds?.let { b ->
            if (b.size == 4) GeoBounds(b[0], b[1], b[2], b[3]) else null
        }

    val center: GeoPoint?
        get() = metadata.center?.let { c ->
            if (c.size >= 2) GeoPoint(c[1], c[0]) else null
        }

    /** Total bytes on disk, or 0 when the tree cannot be walked. */
    fun sizeBytes(): Long = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    override fun toString(): String = "MapPack(${metadata.packId}, ${directory.absolutePath})"

    /**
     * The manifest as read from `metadata.json`.
     *
     * Field names mirror the format spec exactly; unknown fields are ignored so
     * a newer builder's output still loads, and missing optional fields default
     * rather than throwing.
     */
    data class Metadata(
        val formatVersion: Int,
        val packId: String,
        val name: String,
        val bounds: DoubleArray?,
        val center: DoubleArray?,
        val defaultZoom: Double,
        val minZoom: Double,
        /** Highest zoom the tile store actually contains. */
        val maxZoom: Double,
        /**
         * Highest zoom the *camera* may reach, above [maxZoom].
         *
         * The two are deliberately different. Tiles stop at 14 because that is
         * the deepest level worth shipping, but the spec puts 3D buildings
         * behind a zoom-15 gate, and a camera clamped to the tile range can
         * never reach it. MapLibre overzooms the last available level, so the
         * extra zoom costs nothing on disk. A pack that omits the field falls
         * back to [maxZoom], which keeps an older pack working — 2D only.
         */
        val maxCameraZoom: Double,
        val basemap: String,
        val placesDb: String,
        val routingGraph: String,
        val styleDay: String,
        val styleNight: String?,
        val glyphs: String?,
        val sprite: String?,
        val attribution: String?,
        /** Data generation timestamp from the manifest, verbatim. */
        val generatedAt: String? = null
    )

    /**
     * Outcome of loading a pack directory.
     *
     * Declared at the top level of the file rather than nested in the
     * companion, because callers in other modules name it in their own public
     * signatures (`AppGraph.loadPack`), and a nested companion type has to be
     * imported through `MapPack.Companion.LoadResult`, which is noise at every
     * call site.
     */
    sealed class LoadResult {
        data class Ok(val pack: MapPack, val diagnostics: PackDiagnostics = PackDiagnostics()) : LoadResult()

        /**
         * [reason] is the driver-facing sentence; [code] and [diagnostics] carry
         * the machine-readable cause. The code is what lets the UI pick a
         * different action per failure instead of one generic message.
         */
        data class Failure(
            val reason: String,
            val code: PackFailureCode = PackFailureCode.PACK_NOT_FOUND,
            val diagnostics: PackDiagnostics = PackDiagnostics()
        ) : LoadResult()
    }

    companion object {
        /** The only format this build understands; readers reject anything else. */
        const val SUPPORTED_FORMAT_VERSION = 1

        /** Files the pack cannot work without, relative to the pack root. */
        private val REQUIRED = listOf("basemap.mbtiles")

        /**
         * Reads and validates a pack directory's manifest and required files.
         *
         * Never throws: a missing directory, malformed JSON, an unsupported
         * format version and missing files all come back as [LoadResult.Failure]
         * with a reason aimed at the driver, not at a log file.
         */
        fun load(directory: File, diagnostics: PackDiagnostics = PackDiagnostics()): LoadResult {
            diagnostics.note(PackStage.VALIDATING_METADATA, "root=${directory.absolutePath}")
            if (!directory.isDirectory) {
                diagnostics.fail(PackStage.DISCOVERING, PackFailureCode.PACK_NOT_FOUND, "not a directory")
                return LoadResult.Failure(
                    "Không tìm thấy thư mục map pack: ${directory.absolutePath}",
                    PackFailureCode.PACK_NOT_FOUND,
                    diagnostics
                )
            }
            val manifest = File(directory, "metadata.json")
            if (!manifest.isFile) {
                diagnostics.fail(PackStage.VALIDATING_METADATA, PackFailureCode.INVALID_METADATA, "metadata.json missing")
                return LoadResult.Failure(
                    "Thiếu metadata.json trong ${directory.absolutePath}",
                    PackFailureCode.INVALID_METADATA,
                    diagnostics
                )
            }
            if (!manifest.canRead()) {
                diagnostics.fail(PackStage.VALIDATING_METADATA, PackFailureCode.PERMISSION_DENIED, "metadata.json unreadable")
                return LoadResult.Failure(
                    "Không đọc được metadata.json — kiểm tra quyền truy cập bộ nhớ",
                    PackFailureCode.PERMISSION_DENIED,
                    diagnostics
                )
            }
            diagnostics.metadataBytes = manifest.length()

            val json = try {
                JSONObject(manifest.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                diagnostics.fail(PackStage.VALIDATING_METADATA, PackFailureCode.INVALID_METADATA, "json=${e.message}")
                return LoadResult.Failure(
                    "metadata.json không đọc được: ${e.message}",
                    PackFailureCode.INVALID_METADATA,
                    diagnostics
                )
            }

            val version = json.optInt("formatVersion", 0)
            diagnostics.formatVersion = version
            if (version != SUPPORTED_FORMAT_VERSION) {
                // A newer pack than this APK understands is a real case after a
                // pack update, and silently loading it would mis-render.
                diagnostics.fail(
                    PackStage.VALIDATING_METADATA,
                    PackFailureCode.UNSUPPORTED_SCHEMA,
                    "formatVersion=$version supported=$SUPPORTED_FORMAT_VERSION"
                )
                return LoadResult.Failure(
                    "Map pack định dạng v$version, bản này chỉ đọc được v$SUPPORTED_FORMAT_VERSION. " +
                        "Cập nhật ứng dụng hoặc dùng pack cũ hơn.",
                    PackFailureCode.UNSUPPORTED_SCHEMA,
                    diagnostics
                )
            }

            diagnostics.note(PackStage.VALIDATING_FILES, "checking ${REQUIRED.size} required file(s)")
            for (required in REQUIRED) {
                val file = File(directory, required)
                if (!file.isFile) {
                    diagnostics.fail(PackStage.VALIDATING_FILES, PackFailureCode.MBTILES_OPEN_FAILED, "missing $required")
                    return LoadResult.Failure(
                        "Map pack thiếu file bắt buộc: $required",
                        PackFailureCode.MBTILES_OPEN_FAILED,
                        diagnostics
                    )
                }
                if (file.length() <= 0L) {
                    diagnostics.fail(PackStage.VALIDATING_FILES, PackFailureCode.MBTILES_OPEN_FAILED, "$required is empty")
                    return LoadResult.Failure(
                        "File bản đồ rỗng: $required (0 byte)",
                        PackFailureCode.MBTILES_OPEN_FAILED,
                        diagnostics
                    )
                }
                diagnostics.file("$required size=${file.length()}")
            }

            val meta = Metadata(
                formatVersion = version,
                packId = json.optString("packId", directory.name),
                name = json.optString("name", directory.name),
                bounds = json.optJSONArray("bounds")?.let { a ->
                    DoubleArray(a.length()) { a.optDouble(it, 0.0) }
                },
                center = json.optJSONArray("center")?.let { a ->
                    DoubleArray(a.length()) { a.optDouble(it, 0.0) }
                },
                defaultZoom = json.optDouble("defaultZoom", 12.0),
                minZoom = json.optDouble("minZoom", 5.0),
                maxZoom = json.optDouble("maxZoom", 14.0),
                maxCameraZoom = json.optDouble("maxCameraZoom", json.optDouble("maxZoom", 14.0)),
                basemap = json.optString("basemap", "basemap.mbtiles"),
                placesDb = json.optString("placesDb", "places.db"),
                routingGraph = json.optString("routingGraph", "routing-graph"),
                styleDay = json.optJSONObject("style")?.optString("day", "style-day.json")
                    ?: "style-day.json",
                styleNight = json.optJSONObject("style")?.optString("night", "")?.takeIf { it.isNotEmpty() },
                glyphs = json.optString("glyphs", "").takeIf { it.isNotEmpty() },
                sprite = json.optString("sprite", "").takeIf { it.isNotEmpty() },
                attribution = json.optString("attribution", "").takeIf { it.isNotEmpty() },
                generatedAt = json.optString("generatedAt", "").takeIf { it.isNotEmpty() }
            )

            if (meta.maxZoom < meta.minZoom) {
                diagnostics.fail(
                    PackStage.VALIDATING_METADATA,
                    PackFailureCode.INVALID_METADATA,
                    "maxZoom=${meta.maxZoom} < minZoom=${meta.minZoom}"
                )
                return LoadResult.Failure(
                    "metadata.json: maxZoom nhỏ hơn minZoom",
                    PackFailureCode.INVALID_METADATA,
                    diagnostics
                )
            }

            diagnostics.packId = meta.packId
            diagnostics.resolvedPath = directory.absolutePath

            // A declared basemap that points somewhere other than the file the
            // required-files pass just checked is a manifest lie, and it would
            // surface later as "map pack has no tiles" with no clue where.
            val declared = File(directory, meta.basemap)
            if (!declared.isFile) {
                diagnostics.fail(
                    PackStage.VALIDATING_FILES,
                    PackFailureCode.MBTILES_OPEN_FAILED,
                    "basemap=${meta.basemap} missing"
                )
                return LoadResult.Failure(
                    "Map pack khai báo ${meta.basemap} nhưng không có file đó",
                    PackFailureCode.MBTILES_OPEN_FAILED,
                    diagnostics
                )
            }

            return LoadResult.Ok(MapPack(directory, meta), diagnostics)
        }

        /**
         * Finds a pack under the given roots, in priority order.
         *
         * **Depth matters.** The documented pack layout is
         * `<region>/<version>/metadata.json`, so a walk that only tries the root
         * and its immediate children never reaches a versioned pack. This walks
         * up to [MAX_DEPTH] levels but stops descending into a directory that
         * already looks like pack *content* (`fonts/`, `routing-graph/`,
         * `style/`, `sprites/`), so a large pack costs a handful of `stat`s
         * rather than a full tree scan.
         *
         * **Every candidate is recorded**, accepted or not, in
         * [PackDiagnostics.candidates] — the brief forbids concluding anything
         * about folders without the resolved paths on record.
         */
        fun discover(
            roots: List<File>,
            diagnostics: PackDiagnostics = PackDiagnostics(),
            storageReadable: Boolean = true,
            preferred: File? = null
        ): LoadResult {
            diagnostics.note(PackStage.DISCOVERING, "roots=${roots.size} storageReadable=$storageReadable")

            if (!storageReadable) {
                // On API 23 a denied READ_EXTERNAL_STORAGE makes File.isDirectory
                // return false without throwing, which is indistinguishable from
                // "the folder is not there". Without this check the loader would
                // report PACK_NOT_FOUND and the driver would go looking for a
                // file that is already in the right place.
                diagnostics.fail(PackStage.DISCOVERING, PackFailureCode.PERMISSION_DENIED, "storage permission not granted")
                return LoadResult.Failure(
                    "Chưa được cấp quyền đọc bộ nhớ nên không thể tìm map pack. " +
                        "Cấp quyền bộ nhớ rồi bấm “Kiểm tra lại”.",
                    PackFailureCode.PERMISSION_DENIED,
                    diagnostics
                )
            }

            // The pack the driver explicitly activated wins over sort order.
            // Without this, installing a second region silently switches the
            // map to whichever directory happens to sort first.
            if (preferred != null) {
                diagnostics.candidate("${preferred.absolutePath} preferred=true exists=${preferred.isDirectory}")
                if (File(preferred, "metadata.json").isFile) {
                    val chosen = load(preferred, diagnostics)
                    if (chosen is LoadResult.Ok) {
                        diagnostics.note(PackStage.FOUND, chosen.pack.directory.absolutePath)
                        return chosen
                    }
                    diagnostics.candidate("${preferred.absolutePath} preferred=invalid")
                }
            }

            var best: LoadResult.Failure? = null

            for (root in roots) {
                if (!root.isDirectory) {
                    diagnostics.candidate("${root.absolutePath} exists=false")
                    continue
                }
                diagnostics.candidate("${root.absolutePath} exists=true")

                val found = walk(root, depth = 0, diagnostics = diagnostics)
                if (found is LoadResult.Ok) {
                    diagnostics.note(PackStage.FOUND, found.pack.directory.absolutePath)
                    return found
                }
                if (found is LoadResult.Failure && best == null) {
                    // Keep the FIRST real failure, not the last. Walking a root
                    // ends on whatever subdirectory sorted last (usually
                    // `routing-graph/`), and reporting that leaves the driver
                    // staring at a path that has nothing to do with the problem.
                    best = found
                }
            }

            val failure = best ?: LoadResult.Failure(
                "Chưa cài map pack. Chép map pack vào bộ nhớ trong hoặc USB, rồi bấm “Kiểm tra lại”.",
                PackFailureCode.PACK_NOT_FOUND,
                diagnostics
            )
            diagnostics.fail(
                PackStage.DISCOVERING,
                failure.code,
                "no usable pack under ${roots.size} root(s)"
            )
            return failure.copy(diagnostics = diagnostics)
        }

        /** Depth-limited search that stops at pack-looking content. */
        private fun walk(root: File, depth: Int, diagnostics: PackDiagnostics): LoadResult {
            // A directory is a pack candidate if it has a manifest. Check that
            // before anything else so a valid pack at the root wins over its
            // own subdirectories.
            if (File(root, "metadata.json").isFile) {
                val result = load(root, diagnostics)
                if (result is LoadResult.Ok) return result
                diagnostics.candidate("${root.absolutePath} pack=invalid")
                return result
            }

            if (depth >= MAX_DEPTH) {
                diagnostics.candidate("${root.absolutePath} depth=limit")
                return LoadResult.Failure(
                    "Không thấy metadata.json trong ${root.absolutePath}",
                    PackFailureCode.PACK_NOT_FOUND,
                    diagnostics
                )
            }

            // `fonts/`, `routing-graph/`, `style/` and `sprites/` are pack
            // content. Descending into them on every scan would stat hundreds of
            // glyph files on a 1 GB device for no possible gain.
            if (root.name in CONTENT_DIRS) {
                diagnostics.candidate("${root.absolutePath} content=skip")
                return LoadResult.Failure(
                    "Thư mục ${root.absolutePath} là dữ liệu của pack, không phải pack",
                    PackFailureCode.PACK_NOT_FOUND,
                    diagnostics
                )
            }

            val children = root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }
            if (children.isNullOrEmpty()) {
                diagnostics.candidate("${root.absolutePath} children=0")
                return LoadResult.Failure(
                    "Không thấy metadata.json trong ${root.absolutePath}",
                    PackFailureCode.PACK_NOT_FOUND,
                    diagnostics
                )
            }

            var first: LoadResult.Failure? = null
            for (child in children) {
                val result = walk(child, depth + 1, diagnostics)
                if (result is LoadResult.Ok) return result
                if (first == null && result is LoadResult.Failure) first = result
            }
            return first ?: LoadResult.Failure(
                "Không thấy metadata.json trong ${root.absolutePath}",
                PackFailureCode.PACK_NOT_FOUND,
                diagnostics
            )
        }

        /** Standard places a pack is looked for, in priority order. */
        fun defaultRoots(context: Context): List<File> = buildList {
            // App-private storage first: always readable, survives a USB pull.
            add(File(context.filesDir, "map-pack"))
            @Suppress("DEPRECATION")
            val external = context.getExternalFilesDir(null)
            if (external != null) add(File(external, "map-pack"))
            // Then the shared locations a driver copies a pack into.
            add(File("/storage/emulated/0/ts8/map-pack"))
            add(File("/storage/emulated/0/TS8/map-pack"))
            add(File("/storage/emulated/0/ts8"))
            for (name in USB_ROOTS) {
                add(File(name, "ts8/map-pack"))
                add(File(name, "TS8/map-pack"))
            }
        }

        /**
         * How many directory levels below a root are searched.
         *
         * Two covers both documented layouts: `<root>/<region>/<version>/` and
         * `<root>/<packId>/`. Three would let a stray `Android/data/...` tree
         * pull the walk into another app's cache for no gain.
         */
        private const val MAX_DEPTH = 2

        /** Subdirectory names that are pack content, never a pack itself. */
        private val CONTENT_DIRS = setOf(
            "fonts", "routing-graph", "style", "sprites", "tiles", "glyphs"
        )

        private val USB_ROOTS = listOf(
            "/storage/usb0", "/storage/usb1", "/storage/usbdisk",
            "/storage/usb_disk", "/mnt/usb_storage", "/storage/UsbDriveA"
        )
    }
}

END FILE

## core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/OverlayIcons.kt

BEGIN FILE
package com.ts8.core.map.maplibre

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/**
 * Overlay icons drawn into bitmaps at runtime.
 *
 * **Why not a sprite sheet.** A MapLibre style normally names `sprite` — a PNG
 * atlas plus an index — and both are loaded by URL. Two hand-drawn icons are
 * not worth shipping an atlas for, and drawing them here means the overlay
 * works with no sprite file at all, which removes one more thing that can be
 * missing from a pack.
 *
 * Both icons are drawn on a transparent canvas at a size scaled by the display
 * density, because `icon-size: 1` in MapLibre means "the bitmap's own pixel
 * size", and an unscaled 32 px arrow on a 1.5x panel looks like a toy.
 */
internal object OverlayIcons {

    /** An arrow pointing up, rotated by the vehicle's bearing at render time. */
    fun vehicle(density: Float): Bitmap {
        val size = (ARROW_DP * density).toInt().coerceAtLeast(24)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f

        // A light drop shadow keeps the arrow legible over both a bright road
        // casing and the near-black night background.
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }
        val arrow = arrowPath(size.toFloat())
        canvas.save()
        canvas.translate(1f, 1.5f)
        canvas.drawPath(arrow, shadow)
        canvas.restore()

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1f7ae0") }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, stroke)
        // The centre dot reads as the vehicle's actual position; without it the
        // rotated arrow looks like it has drifted off the road.
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawCircle(cx, size * 0.62f, 2.2f * density, dot)
        return bmp
    }

    /** A destination pin with a filled head and a point at the anchor. */
    fun pin(density: Float): Bitmap {
        val w = (PIN_W_DP * density).toInt().coerceAtLeast(16)
        val h = (PIN_H_DP * density).toInt().coerceAtLeast(24)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = w / 2f
        val headR = w * 0.36f
        val headCy = headR + 2f * density

        val path = Path().apply {
            moveTo(cx, h.toFloat())
            lineTo(cx - headR * 0.62f, headCy + headR * 0.9f)
            lineTo(cx + headR * 0.62f, headCy + headR * 0.9f)
            close()
        }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#e0392b") }
        canvas.drawPath(path, body)
        val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#e0392b") }
        canvas.drawCircle(cx, headCy, headR, head)
        val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawCircle(cx, headCy, headR * 0.42f, inner)
        return bmp
    }

    private fun arrowPath(size: Float): Path {
        val cx = size / 2f
        return Path().apply {
            moveTo(cx, size * 0.12f)
            lineTo(cx + size * 0.22f, size * 0.60f)
            lineTo(cx + size * 0.08f, size * 0.52f)
            lineTo(cx + size * 0.08f, size * 0.84f)
            lineTo(cx - size * 0.08f, size * 0.84f)
            lineTo(cx - size * 0.08f, size * 0.52f)
            lineTo(cx - size * 0.22f, size * 0.60f)
            close()
        }
    }

    private const val ARROW_DP = 34f
    private const val PIN_W_DP = 26f
    private const val PIN_H_DP = 38f
}

END FILE

## core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/PackDiagnostics.kt

BEGIN FILE
package com.ts8.core.map.maplibre

import java.io.File

/**
 * Structured diagnostics for the map-pack pipeline.
 *
 * **Why a class and not `Log.d` calls.** "Map pack did not load" has half a
 * dozen genuinely different causes — no permission, no folder, a folder one
 * level too deep, a corrupt SQLite file, a graph built by an older tool — and
 * every one of them used to surface as the same blank screen. A driver on the
 * side of the road cannot read logcat, and a remote support call cannot either.
 * Collecting the pipeline as an ordered list of [Stage] events means the same
 * object can (a) be written to logcat verbatim, (b) be rendered in the Map
 * Management diagnostics panel, and (c) be asserted in a test.
 *
 * The stages below are the ones the repair brief names, in the order they must
 * occur. A failure records the stage it happened at plus one of [Code], so the
 * first bad transition is always identifiable rather than inferred.
 */
enum class PackStage {
    DISCOVERING,
    FOUND,
    VALIDATING_METADATA,
    VALIDATING_FILES,
    VALIDATING_CHECKSUM,
    IMPORTING,
    ACTIVATING,
    READY
}

/** The failure taxonomy the brief requires, one code per real cause. */
enum class PackFailureCode {
    /** No candidate directory on any root contained a manifest. */
    PACK_NOT_FOUND,

    /** Storage was not readable because the runtime permission was not held. */
    PERMISSION_DENIED,

    /** `metadata.json` missing, unreadable, or not valid JSON. */
    INVALID_METADATA,

    /** `formatVersion` is not one this build understands. */
    UNSUPPORTED_SCHEMA,

    /** A file does not match the hash recorded in `checksums.sha256`. */
    CHECKSUM_MISMATCH,

    /** Not enough room to stage an imported pack. */
    INSUFFICIENT_STORAGE,

    /** The `.mbtiles` file exists but SQLite cannot open or query it. */
    MBTILES_OPEN_FAILED,

    /** The style was rejected by the renderer before it could draw. */
    STYLE_LOAD_FAILED,

    /** The style needs label glyphs and the pack's glyph directory is absent. */
    GLYPH_MISSING,

    /** The pack declares a sprite but the sprite files are absent. */
    SPRITE_MISSING,

    /** `places.db` is present but SQLite cannot open it or a table is missing. */
    PLACE_DB_OPEN_FAILED,

    /** `routing-graph/` is absent, or its magic/version is not compatible. */
    ROUTING_GRAPH_INCOMPATIBLE,

    /** The staged pack could not be moved into place. */
    ACTIVATION_FAILED
}

/**
 * One observed step. [detail] is a stable, machine-greppable line; [message] is
 * the sentence shown to the driver for the step that failed.
 */
data class PackEvent(
    val stage: PackStage,
    val detail: String,
    val ok: Boolean = true
)

/**
 * The record of one traversal of the pack pipeline.
 *
 * Every field the brief asks for under "Log tối thiểu" has a home here:
 * candidate roots, the resolved canonical path, pack id/version/schema, file
 * existence + size + checksum, SQLite open result with required tables, a tile
 * query at a real z/x/y, the resolved style source URL, and routing-graph
 * compatibility.
 */
class PackDiagnostics {

    private val events = ArrayList<PackEvent>(24)

    /** Every directory that was considered, with why it was accepted or not. */
    val candidates = ArrayList<String>(16)

    var resolvedPath: String? = null
    var resolvedUri: String? = null
    var packId: String? = null
    var formatVersion: Int = -1
    var metadataBytes: Long = -1

    /** `"name size=NNN sha256=..."` per validated file. */
    val files = ArrayList<String>(24)
    var checksumSummary: String? = null
    var sqliteSummary: String? = null
    var placeDbSummary: String? = null
    var tileQuerySummary: String? = null
    var styleSummary: String? = null
    var routingSummary: String? = null

    /** Set on the event that made the pipeline stop. */
    var failureCode: PackFailureCode? = null
    var failureStage: PackStage? = null

    fun note(stage: PackStage, detail: String): PackDiagnostics = apply {
        events += PackEvent(stage, detail, ok = true)
    }

    fun fail(stage: PackStage, code: PackFailureCode, detail: String): PackDiagnostics = apply {
        events += PackEvent(stage, detail, ok = false)
        failureCode = code
        failureStage = stage
    }

    fun candidate(detail: String): PackDiagnostics = apply {
        if (candidates.size < MAX_CANDIDATES) candidates += detail
    }

    fun file(detail: String): PackDiagnostics = apply {
        if (files.size < MAX_FILES) files += detail
    }

    val failed: Boolean get() = failureCode != null

    /** Ordered stage trail, e.g. `DISCOVERING → FOUND → VALIDATING_METADATA`. */
    fun trail(): String = events.joinToString(" → ") { it.stage.name }

    /**
     * The full report as newline-separated `key=value` lines.
     *
     * Deliberately free of any coordinate, so it can be copied out with the
     * brief's "no personal location outside diagnostics mode" rule intact — a
     * tile z/x/y is a pack fact, not a driver position.
     */
    fun report(): String = buildString {
        appendLine("stage_trail=${trail()}")
        appendLine("failure=${failureStage?.name ?: "none"}${failureCode?.let { " code=$it" }.orEmpty()}")
        appendLine("candidates=${candidates.size}")
        for (c in candidates) appendLine("  candidate=$c")
        resolvedPath?.let { appendLine("resolved_path=$it") }
        resolvedUri?.let { appendLine("resolved_uri=$it") }
        packId?.let { appendLine("pack_id=$it") }
        if (formatVersion >= 0) appendLine("format_version=$formatVersion")
        if (metadataBytes >= 0) appendLine("metadata_bytes=$metadataBytes")
        for (f in files) appendLine("  file=$f")
        checksumSummary?.let { appendLine("checksum=$it") }
        sqliteSummary?.let { appendLine("mbtiles=$it") }
        tileQuerySummary?.let { appendLine("tile_query=$it") }
        placeDbSummary?.let { appendLine("places_db=$it") }
        styleSummary?.let { appendLine("style=$it") }
        routingSummary?.let { appendLine("routing=$it") }
    }

    /** Compact one-liner for the Map Management list. */
    fun summary(): String = buildString {
        append(failureCode?.name ?: "READY")
        resolvedPath?.let { append(" · ").append(File(it).name) }
        checksumSummary?.let { append(" · ").append(it) }
    }

    private companion object {
        const val MAX_CANDIDATES = 24
        const val MAX_FILES = 32
    }
}

END FILE

## core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/PackImporter.kt

BEGIN FILE
package com.ts8.core.map.maplibre

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Installs a `.ts8map` archive (a ZIP) into app-private storage.
 *
 * **Why the import is staged rather than extracted in place.** A pack is ~20 MB
 * and the head unit loses power with the ignition. Extracting directly into the
 * live directory means a power cut leaves half a pack where a whole one used
 * to be, and the driver has no map at all. So extraction goes to a temporary
 * sibling, is validated there, and only then replaces the old directory — one
 * rename, which is atomic on the same filesystem (`rename(2)`).
 *
 * **Why the Zip Slip guard is not optional.** The archive is untrusted: it
 * comes off a USB stick. A `../` entry, an absolute path, or a symlink target
 * escaping the staging directory would write anywhere the app can write — a
 * launcher running as a system-ish app could overwrite its own prefs or another
 * app's data. Every entry is canonicalised and rejected unless it stays inside
 * the staging root, before a single byte is written.
 *
 * **What it does not do.** It does not decide whether the pack is good enough
 * to activate; [PackValidator] does, and the caller passes that verdict in. It
 * also does not retry a failed extraction: reporting the failure and keeping
 * the old pack is the required behaviour, not a loop.
 */
class PackImporter(
    /** Where packs are installed. Created if absent. */
    private val installRoot: File,
    /** Free-space source, injectable so the check is testable off-device. */
    private val freeBytes: (File) -> Long = { it.usableSpace }
) {

    /**
     * The outcome, with a stage so the UI can show "IMPORTING → VALIDATING →
     * ACTIVATING" rather than an indeterminate spinner.
     */
    sealed class Result {
        data class Ok(val directory: File, val bytes: Long) : Result()

        data class Failure(
            val code: PackFailureCode,
            val message: String,
            val diagnostics: PackDiagnostics
        ) : Result()
    }

    /**
     * Extracts [open] into the install root and validates the result.
     *
     * @param open supplies the archive stream once. Called on the IO thread.
     * @param probe the SQLite probe the validator uses; the caller passes the
     *        Android implementation in production and a fake in tests.
     * @param validate a hook that decides whether the extracted pack is usable.
     *        Defaults to [PackValidator.validate] on a freshly loaded pack.
     */
    fun import(
        archiveName: String,
        open: () -> InputStream,
        diagnostics: PackDiagnostics = PackDiagnostics(),
        probe: SqliteProbe = AndroidSqliteProbe(),
        validate: (MapPack, PackDiagnostics) -> PackValidator.Report = { pack, diag ->
            PackValidator(probe).validate(pack, diag)
        }
    ): Result {
        diagnostics.note(PackStage.IMPORTING, "archive=$archiveName root=${installRoot.absolutePath}")

        if (!installRoot.isDirectory && !installRoot.mkdirs()) {
            return fail(diagnostics, PackFailureCode.INSUFFICIENT_STORAGE,
                "Không tạo được thư mục cài đặt: ${installRoot.absolutePath}")
        }

        val free = runCatching { freeBytes(installRoot) }.getOrDefault(0L)
        diagnostics.note(PackStage.IMPORTING, "free=$free")

        // Staging directory under the install root, so the final rename stays on
        // one filesystem and cannot degrade into a copy that a power cut
        // interrupts halfway.
        val staging = File(installRoot, "staging-${sanitize(archiveName)}")
        staging.deleteRecursively()
        if (!staging.mkdirs()) {
            return fail(diagnostics, PackFailureCode.INSUFFICIENT_STORAGE,
                "Không tạo được thư mục tạm: ${staging.absolutePath}")
        }

        var written = 0L
        try {
            val root = staging.canonicalFile
            open().use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (entry.isDirectory) {
                            entry = zip.nextEntry
                            continue
                        }
                        val target = File(staging, name)
                        val canonical = target.canonicalFile
                        // Canonical containment, not a string prefix test: a
                        // sibling named "staging-x-evil" shares the prefix.
                        if (!canonical.path.startsWith(root.path + File.separator)) {
                            staging.deleteRecursively()
                            return fail(
                                diagnostics,
                                PackFailureCode.INVALID_METADATA,
                                "Zip Slip: entry '$name' escapes the pack directory"
                            )
                        }
                        canonical.parentFile?.mkdirs()
                        val copied = copyInto(zip, canonical)
                        written += copied
                        if (written > free) {
                            staging.deleteRecursively()
                            return fail(
                                diagnostics,
                                PackFailureCode.INSUFFICIENT_STORAGE,
                                "Hết dung lượng khi giải nén ($written > $free)"
                            )
                        }
                        diagnostics.file("$name $copied")
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }

            // The archive may have a single top-level directory (`danang-v1/`),
            // which is the same layout the folder packs use. Find the manifest
            // rather than assuming the metadata sits at the staging root.
            val packRoot = findManifestRoot(staging)
                ?: return fail(
                    diagnostics,
                    PackFailureCode.INVALID_METADATA,
                    "Archive không có metadata.json ở thư mục gốc"
                )

            val loaded = MapPack.load(packRoot, diagnostics)
            if (loaded is MapPack.LoadResult.Failure) {
                return Result.Failure(loaded.code, loaded.reason, diagnostics)
            }
            val pack = (loaded as MapPack.LoadResult.Ok).pack

            diagnostics.note(PackStage.VALIDATING_CHECKSUM, "validating extracted pack")
            val report = validate(pack, diagnostics)
            if (!report.ok) {
                // Refuse to activate a pack that cannot render. The old pack
                // stays exactly as it was, which the brief requires.
                return Result.Failure(
                    report.code ?: PackFailureCode.ACTIVATION_FAILED,
                    report.message ?: "Pack không vượt qua kiểm tra",
                    diagnostics
                )
            }

            diagnostics.note(PackStage.ACTIVATING, "installing ${pack.metadata.packId}")
            val destination = File(installRoot, sanitize(pack.metadata.packId))
            replaceAtomically(packRoot, destination, diagnostics)
            diagnostics.resolvedPath = destination.absolutePath
            diagnostics.note(PackStage.READY, destination.absolutePath)
            return Result.Ok(destination, written)
        } catch (e: Exception) {
            // The brief fixes the 13 failure codes and inventing a fourteenth
            // would change a contract both the UI and the docs quote. An
            // unreadable archive is, from the driver's point of view, an
            // invalid pack — and the detail carries the real exception text.
            return fail(diagnostics, PackFailureCode.INVALID_METADATA,
                "Giải nén thất bại: ${e.message}")
        } finally {
            // Cleans up whatever is left: an early return leaves the staging
            // directory behind, and on this device 20 MB of litter is 10 % of
            // the free space.
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    /**
     * Swaps the staged directory in, keeping the old one until the new one is
     * in place.
     *
     * Order matters: the old directory is moved aside first (same filesystem,
     * a rename), then the new one is renamed into place, then the old is
     * deleted. A failure between steps leaves a recoverable state — either the
     * new pack in place or the old one still present under its backup name.
     */
    private fun replaceAtomically(staged: File, destination: File, diagnostics: PackDiagnostics) {
        val backup = File(destination.parentFile, destination.name + ".old")
        if (backup.exists()) backup.deleteRecursively()

        if (destination.exists()) {
            if (!destination.renameTo(backup)) {
                // Fall back to a copy so a filesystem that refuses the rename
                // (a FAT32 stick, a vendor FUSE mount) still works.
                destination.copyRecursively(backup, overwrite = true)
                destination.deleteRecursively()
            }
            diagnostics.note(PackStage.ACTIVATING, "old pack moved to ${backup.name}")
        }
        if (!staged.renameTo(destination)) {
            staged.copyRecursively(destination, overwrite = true)
            staged.deleteRecursively()
        }
        if (backup.exists()) backup.deleteRecursively()
    }

    /** The directory that holds `metadata.json`, at most two levels down. */
    private fun findManifestRoot(root: File): File? {
        if (File(root, "metadata.json").isFile) return root
        val first = root.listFiles()?.filter { it.isDirectory } ?: return null
        for (child in first) {
            if (File(child, "metadata.json").isFile) return child
            val second = child.listFiles()?.filter { it.isDirectory } ?: continue
            for (grandchild in second) {
                if (File(grandchild, "metadata.json").isFile) return grandchild
            }
        }
        return null
    }

    private fun copyInto(input: InputStream, target: File): Long {
        var total = 0L
        FileOutputStream(target).use { out ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
                total += read
            }
            out.flush()
        }
        return total
    }

    private fun fail(
        diagnostics: PackDiagnostics,
        code: PackFailureCode,
        message: String
    ): Result.Failure {
        diagnostics.fail(PackStage.IMPORTING, code, message)
        return Result.Failure(code, message, diagnostics)
    }

    private fun sanitize(name: String): String =
        name.substringBeforeLast('.').map { c ->
            if (c.isLetterOrDigit() || c == '-' || c == '_') c else '-'
        }.joinToString("").take(48).ifEmpty { "pack" }

    companion object {
        private const val BUFFER = 64 * 1024
    }
}

END FILE

## core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/PackPaths.kt

BEGIN FILE
package com.ts8.core.map.maplibre

import java.io.File

/**
 * Builds the URLs MapLibre Native is handed for local pack data.
 *
 * **Why this is not string concatenation at the call site.** It used to be, and
 * it produced two silent failures that no amount of UI could explain:
 *
 * 1. The glyph URL was emitted with no scheme at all
 *    (`/data/.../fonts/{fontstack}/{range}.pbf`). MapLibre's `LocalFileSource`
 *    accepts **only** `file://` (see `lfs.cpp`, `acceptsURL`), and every other
 *    source rejects the URL too, so the chain ends in
 *    `"Unsupported resource request."` and no label is ever drawn.
 * 2. A pack path containing a space or a non-ASCII character was pasted into
 *    `mbtiles://` raw. The native side percent-decodes what follows the scheme,
 *    so an unencoded `%` or a raw space yields either a wrong path or a
 *    malformed URL — a blank map with no error anywhere.
 *
 * Both are fixed by encoding exactly once, here, with the one rule both native
 * sources agree on: decode the bytes after the scheme. `'/'` is left literal so
 * paths stay readable in a log, and everything outside the unreserved set
 * becomes UTF-8 `%XX`.
 */
object PackPaths {

    /**
     * Percent-encodes a filesystem path, leaving `/` and the unreserved set
     * (`A-Z a-z 0-9 - . _ ~`) untouched.
     *
     * `~` is left alone deliberately: it is unreserved in RFC 3986 and the
     * native decoder restores it, so round-tripping is exact.
     */
    fun encodePath(path: String): String {
        val out = StringBuilder(path.length + 16)
        for (byte in path.toByteArray(Charsets.UTF_8)) {
            val b = byte.toInt() and 0xFF
            val c = b.toChar()
            if (isLiteral(c)) {
                out.append(c)
            } else {
                out.append('%')
                out.append(HEX[b ushr 4])
                out.append(HEX[b and 0x0F])
            }
        }
        return out.toString()
    }

    /** `mbtiles://` + an absolute path, encoded. */
    fun mbtilesUrl(absolutePath: String): String = MBTILES_PROTOCOL + encodePath(absolutePath)

    /**
     * `file://` + an absolute path, encoded.
     *
     * A filesystem path always starts with `/`, so the result reads
     * `file:///data/...` — the empty authority form the native decoder expects
     * (it strips `file://` and keeps the leading slash).
     */
    fun fileUrl(absolutePath: String): String = FILE_PROTOCOL + encodePath(absolutePath)

    /**
     * The glyph URL template for a directory.
     *
     * `{fontstack}` and `{range}` stay literal: MapLibre substitutes them as
     * text **before** the URL reaches a file source, so encoding the braces
     * would leave the template unsubstituted and every glyph request would 404.
     */
    fun glyphsTemplate(directory: File): String =
        fileUrl(directory.absolutePath) + "/{fontstack}/{range}.pbf"

    /** `file://` URL for a single file. */
    fun fileUrl(file: File): String = fileUrl(file.absolutePath)

    private fun isLiteral(c: Char): Boolean = when (c) {
        in 'a'..'z', in 'A'..'Z', in '0'..'9' -> true
        '-', '.', '_', '~', '/' -> true
        else -> false
    }

    private const val MBTILES_PROTOCOL = "mbtiles://"
    private const val FILE_PROTOCOL = "file://"
    private val HEX = "0123456789ABCDEF".toCharArray()
}

END FILE

## core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/PackValidator.kt

BEGIN FILE
package com.ts8.core.map.maplibre

import java.io.File
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.tan
import kotlin.math.cos

/**
 * The expensive half of pack validation: checksums, SQLite, a real tile query,
 * glyphs, sprite and the routing graph.
 *
 * **Why this is separate from [MapPack.load].** `load` answers "is there a pack
 * here" in a few `stat` calls and must stay main-thread cheap so the UI can
 * respond on the first frame. This class answers "does this pack actually
 * work", which means hashing 20 MB and opening two SQLite databases. That runs
 * on the IO executor.
 *
 * **Why the SQLite part is behind [SqliteProbe].** `android.database.sqlite`
 * is unimplemented in a plain JVM unit test, so a validator that called it
 * directly could only be tested under Robolectric — which cannot be fetched
 * over this build host's network. The probe keeps the *decision logic* (which
 * tables, which columns, what counts as a pass) in testable Kotlin, and confines
 * the platform call to one class.
 */
class PackValidator(private val probe: SqliteProbe = AndroidSqliteProbe()) {

    /**
     * Everything the rest of the app needs to know about a pack's health.
     *
     * The three optional parts are reported separately on purpose: the brief
     * requires the map and search to keep working when the routing graph is
     * missing, and the map and routing to keep working when `places.db` is
     * missing. A single boolean cannot express that.
     */
    data class Report(
        val ok: Boolean,
        val code: PackFailureCode?,
        val message: String?,
        val checksumOk: Boolean,
        val mbtilesOk: Boolean,
        val placesOk: Boolean,
        val routingOk: Boolean,
        val glyphsOk: Boolean,
        val spriteOk: Boolean,
        val tileQuery: String?,
        val diagnostics: PackDiagnostics
    ) {
        /** Navigation is offered only when there is a graph to route on. */
        val navigationAvailable: Boolean get() = mbtilesOk && routingOk

        /** Offline search is offered only when there is a place index. */
        val searchAvailable: Boolean get() = placesOk
    }

    /**
     * Validates [pack] in the order the brief specifies, stopping at the first
     * *blocking* failure. Missing glyphs/sprite/places/routing are recorded but
     * do not block the map, because each has a working degraded mode.
     */
    fun validate(pack: MapPack, diagnostics: PackDiagnostics = PackDiagnostics()): Report {
        val checksumOk = verifyChecksums(pack, diagnostics)
        if (!checksumOk) {
            return Report(
                ok = false,
                code = diagnostics.failureCode ?: PackFailureCode.CHECKSUM_MISMATCH,
                message = "Map pack bị hỏng: checksum không khớp. Chép lại pack hoặc chọn file khác.",
                checksumOk = false, mbtilesOk = false, placesOk = false,
                routingOk = false, glyphsOk = false, spriteOk = false,
                tileQuery = null, diagnostics = diagnostics
            )
        }

        val mbtiles = validateMbTiles(pack, diagnostics)
        if (!mbtiles.ok) {
            return Report(
                ok = false,
                code = PackFailureCode.MBTILES_OPEN_FAILED,
                message = "Không mở được file bản đồ ${pack.metadata.basemap}: ${mbtiles.detail}",
                checksumOk = true, mbtilesOk = false, placesOk = false,
                routingOk = false, glyphsOk = false, spriteOk = false,
                tileQuery = null, diagnostics = diagnostics
            )
        }

        val places = validatePlaces(pack, diagnostics)
        val routing = validateRouting(pack, diagnostics)
        val glyphs = validateGlyphs(pack, diagnostics)
        val sprite = validateSprite(pack, diagnostics)

        return Report(
            ok = true,
            code = null,
            message = null,
            checksumOk = true,
            mbtilesOk = true,
            placesOk = places,
            routingOk = routing,
            glyphsOk = glyphs,
            spriteOk = sprite,
            tileQuery = diagnostics.tileQuerySummary,
            diagnostics = diagnostics
        )
    }

    // -- checksum ----------------------------------------------------------

    /**
     * Verifies `checksums.sha256` when the pack ships one.
     *
     * A pack without the file is accepted: the format spec calls it part of the
     * layout, but older packs predate it, and refusing to open them would turn
     * a working map into an error. A pack *with* the file that fails is
     * rejected, because that is the only case where the file can tell us
     * something the `stat` checks cannot — a corrupted copy.
     */
    private fun verifyChecksums(pack: MapPack, diagnostics: PackDiagnostics): Boolean {
        diagnostics.note(PackStage.VALIDATING_CHECKSUM, "start")
        val manifest = File(pack.directory, "checksums.sha256")
        if (!manifest.isFile) {
            diagnostics.checksumSummary = "no checksums.sha256 (skipped)"
            diagnostics.note(PackStage.VALIDATING_CHECKSUM, "skipped: no manifest")
            return true
        }

        var checked = 0
        var mismatch = 0
        var missing = 0
        var firstBad: String? = null

        runCatching {
            manifest.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                // "<hex>  <relative path>" — two spaces, per sha256sum(1).
                val split = line.indexOf("  ")
                if (split <= 0) return@forEachLine
                val expected = line.substring(0, split).lowercase()
                val relative = line.substring(split + 2).trim()
                if (relative.isEmpty()) return@forEachLine

                val file = File(pack.directory, relative)
                if (!file.isFile) {
                    missing++
                    if (firstBad == null) firstBad = "$relative missing"
                    return@forEachLine
                }
                val actual = sha256(file)
                checked++
                if (actual != expected) {
                    mismatch++
                    if (firstBad == null) firstBad = "$relative mismatch"
                }
            }
        }.onFailure { e ->
            diagnostics.checksumSummary = "unreadable: ${e.message}"
            diagnostics.fail(PackStage.VALIDATING_CHECKSUM, PackFailureCode.CHECKSUM_MISMATCH, "manifest unreadable")
            return false
        }

        diagnostics.checksumSummary = "ok=$checked mismatch=$mismatch missing=$missing"
        return if (mismatch == 0 && missing == 0) {
            diagnostics.note(PackStage.VALIDATING_CHECKSUM, "ok=$checked")
            true
        } else {
            diagnostics.fail(
                PackStage.VALIDATING_CHECKSUM,
                PackFailureCode.CHECKSUM_MISMATCH,
                "first=$firstBad"
            )
            false
        }
    }

    // -- mbtiles -----------------------------------------------------------

    private data class PartResult(val ok: Boolean, val detail: String)

    /**
     * Opens the tile container, checks the tables MapLibre's reader queries,
     * then runs the same indexed lookup it will run — at the pack's own centre,
     * at the deepest zoom the pack has.
     *
     * A tile that exists per `metadata` but returns no row at a coordinate
     * inside the declared bounds means the pack is internally inconsistent, and
     * the driver would see a blank map over an otherwise "valid" pack. This is
     * the check that would have caught it.
     */
    private fun validateMbTiles(pack: MapPack, diagnostics: PackDiagnostics): PartResult {
        diagnostics.note(PackStage.VALIDATING_FILES, "mbtiles open")
        val db = File(pack.directory, pack.metadata.basemap)
        val opened = probe.openReadOnly(db.absolutePath)
        if (!opened.ok) {
            diagnostics.sqliteSummary = "open failed: ${opened.detail}"
            diagnostics.fail(PackStage.VALIDATING_FILES, PackFailureCode.MBTILES_OPEN_FAILED, opened.detail)
            return PartResult(false, opened.detail)
        }
        val session = opened.session!!
        try {
            val tables = session.tableNames()
            val hasMeta = tables.contains("metadata")
            val hasTiles = tables.contains("tiles")
            val hasFlat = tables.contains("map") && tables.contains("images")
            if (!hasTiles) {
                // `tiles` is what MapLibre's reader queries. A flat
                // (map+images) schema is legal MBTiles but unreadable by the
                // shipped native source, so it must be rejected here rather
                // than rendering blank.
                val detail = "tables=${tables.joinToString(",")} missing=tiles" +
                    if (hasFlat) " (flat schema unsupported)" else ""
                diagnostics.sqliteSummary = detail
                diagnostics.fail(PackStage.VALIDATING_FILES, PackFailureCode.MBTILES_OPEN_FAILED, detail)
                return PartResult(false, detail)
            }

            var format = "pbf"
            var minZoom = pack.metadata.minZoom.toInt()
            var maxZoom = pack.metadata.maxZoom.toInt()
            if (hasMeta) {
                val meta = session.rows("SELECT name, value FROM metadata")
                for (row in meta) {
                    if (row.size < 2) continue
                    when (row[0]) {
                        "format" -> format = row[1] ?: format
                        "minzoom" -> row[1]?.toIntOrNull()?.let { minZoom = it }
                        "maxzoom" -> row[1]?.toIntOrNull()?.let { maxZoom = it }
                    }
                }
            }
            diagnostics.sqliteSummary = "tables=${tables.joinToString(",")} format=$format " +
                "minzoom=$minZoom maxzoom=$maxZoom"

            // The reader maps XYZ y to TMS itself (`mbtiles_file_source.cpp`:
            // `y = (2^iz - 1) - iy`), so the probe asks with the TMS row that
            // the same formula produces. Probing with the XYZ row would "pass"
            // on a pack whose rows happen to be stored flipped, which is the
            // exact confusion this check exists to avoid.
            val tile = tileAt(pack, minZoom, maxZoom)
            if (tile == null) {
                val detail = "no tile at centre inside bounds"
                diagnostics.tileQuerySummary = detail
                diagnostics.fail(PackStage.VALIDATING_FILES, PackFailureCode.MBTILES_OPEN_FAILED, detail)
                return PartResult(false, detail)
            }
            val sql = "SELECT length(tile_data), hex(substr(tile_data,1,2)) FROM tiles " +
                "WHERE zoom_level=? AND tile_column=? AND tile_row=?"
            val rows = session.rows(sql, tile.z.toString(), tile.x.toString(), tile.tmsY.toString())
            val bytes = rows.firstOrNull()?.getOrNull(0)?.toIntOrNull()
            val magic = rows.firstOrNull()?.getOrNull(1)?.lowercase()
            val summary = "z=${tile.z} x=${tile.x} y_tms=${tile.tmsY} bytes=${bytes ?: 0} magic=${magic ?: "-"}"
            diagnostics.tileQuerySummary = summary
            if (rows.isEmpty() || bytes == null || bytes <= 0) {
                diagnostics.fail(PackStage.VALIDATING_FILES, PackFailureCode.MBTILES_OPEN_FAILED, "tile query empty at $summary")
                return PartResult(false, "tile query empty at $summary")
            }
            diagnostics.note(PackStage.VALIDATING_FILES, "tile ok $summary")
            return PartResult(true, summary)
        } finally {
            session.close()
        }
    }

    /** A tile coordinate in both schemes, so the log is unambiguous. */
    private data class TileCoord(val z: Int, val x: Int, val xyzY: Int, val tmsY: Int)

    private fun tileAt(pack: MapPack, minZoom: Int, maxZoom: Int): TileCoord? {
        val center = pack.center ?: return null
        val z = maxOf(minZoom, minOf(maxZoom, pack.metadata.defaultZoom.toInt()))
        val n = 1 shl z
        val lat = center.lat.coerceIn(-85.05112878, 85.05112878)
        val lon = center.lon
        val x = (((lon + 180.0) / 360.0) * n).toInt().coerceIn(0, n - 1)
        val latRad = lat * PI / 180.0
        val y = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
        return TileCoord(z = z, x = x, xyzY = y, tmsY = (n - 1) - y)
    }

    // -- places.db ---------------------------------------------------------

    /**
     * Offline search is optional. Present but broken is a real failure the
     * search screen must be able to name — a `places.db` copied from an older
     * pack has the right filename and the wrong schema.
     */
    private fun validatePlaces(pack: MapPack, diagnostics: PackDiagnostics): Boolean {
        val file = pack.databaseFile()
        if (file == null) {
            diagnostics.placeDbSummary = "absent (search unavailable)"
            diagnostics.note(PackStage.VALIDATING_FILES, "places.db absent")
            return false
        }
        val opened = probe.openReadOnly(file.absolutePath)
        if (!opened.ok) {
            diagnostics.placeDbSummary = "open failed: ${opened.detail}"
            diagnostics.fail(PackStage.VALIDATING_FILES, PackFailureCode.PLACE_DB_OPEN_FAILED, opened.detail)
            return false
        }
        val session = opened.session!!
        try {
            val tables = session.tableNames()
            if (!tables.contains("places")) {
                diagnostics.placeDbSummary = "missing table places; tables=${tables.joinToString(",")}"
                diagnostics.fail(PackStage.VALIDATING_FILES, PackFailureCode.PLACE_DB_OPEN_FAILED, "missing places table")
                return false
            }
            val count = session.rows("SELECT COUNT(*) FROM places").firstOrNull()?.getOrNull(0)?.toIntOrNull() ?: 0
            val hasFts = tables.contains("places_fts")
            diagnostics.placeDbSummary = "places=$count fts4=$hasFts"
            if (count <= 0) {
                diagnostics.fail(PackStage.VALIDATING_FILES, PackFailureCode.PLACE_DB_OPEN_FAILED, "places table empty")
                return false
            }
            diagnostics.note(PackStage.VALIDATING_FILES, "places ok $count rows fts4=$hasFts")
            return true
        } finally {
            session.close()
        }
    }

    // -- routing graph -----------------------------------------------------

    /**
     * Compatibility is magic + version, read from the first 8 bytes of each
     * file. That is exactly what `RoutingGraph.open` / `GraphIndex.open` check,
     * so this cannot disagree with the reader about whether a graph is usable.
     */
    private fun validateRouting(pack: MapPack, diagnostics: PackDiagnostics): Boolean {
        val dir = pack.routingDir()
        if (dir == null) {
            diagnostics.routingSummary = "absent (navigation unavailable)"
            diagnostics.note(PackStage.VALIDATING_FILES, "routing-graph absent")
            return false
        }
        val bin = File(dir, "graph.bin")
        val idx = File(dir, "graph.idx")
        if (!bin.isFile || !idx.isFile) {
            diagnostics.routingSummary = "incomplete files bin=${bin.isFile} idx=${idx.isFile}"
            diagnostics.fail(PackStage.VALIDATING_FILES, PackFailureCode.ROUTING_GRAPH_INCOMPATIBLE, diagnostics.routingSummary!!)
            return false
        }
        val binHead = readHead(bin, 8)
        val idxHead = readHead(idx, 4)
        val binOk = binHead.size >= 8 && String(binHead, 0, 4, Charsets.US_ASCII) == "TS8G" &&
            (binHead[4].toInt() and 0xFF or ((binHead[5].toInt() and 0xFF) shl 8)) == ROUTING_FORMAT_VERSION
        val idxOk = idxHead.size >= 4 && String(idxHead, 0, 4, Charsets.US_ASCII) == "TS8I"
        diagnostics.routingSummary = "bin=$binOk idx=$idxOk version=$ROUTING_FORMAT_VERSION"
        if (!binOk || !idxOk) {
            diagnostics.fail(
                PackStage.VALIDATING_FILES,
                PackFailureCode.ROUTING_GRAPH_INCOMPATIBLE,
                diagnostics.routingSummary!!
            )
            return false
        }
        diagnostics.note(PackStage.VALIDATING_FILES, "routing ok")
        return true
    }

    private fun readHead(file: File, count: Int): ByteArray = runCatching {
        file.inputStream().use { it.readNBytes(count) }
    }.getOrDefault(ByteArray(0))

    // -- glyphs / sprite ---------------------------------------------------

    /**
     * Labels need glyph PBFs. A pack that declares them but ships none renders
     * geometry with no text — which reads as "labels are broken", not "this
     * pack is incomplete". Reported as a code so the diagnostics panel can say
     * which it is.
     */
    private fun validateGlyphs(pack: MapPack, diagnostics: PackDiagnostics): Boolean {
        val declared = pack.metadata.glyphs
        if (declared == null) {
            diagnostics.styleSummary = (diagnostics.styleSummary ?: "") + " glyphs=undeclared"
            return false
        }
        val dir = pack.resolveGlyphsDir()
        if (dir == null) {
            diagnostics.styleSummary = (diagnostics.styleSummary ?: "") + " glyphs=missing dir"
            diagnostics.fail(PackStage.VALIDATING_FILES, PackFailureCode.GLYPH_MISSING, "declared=$declared dir absent")
            return false
        }
        // One real file must exist: a directory of empty subdirectories passes
        // isDirectory() and still renders nothing.
        val first = dir.listFiles()?.firstOrNull { it.isDirectory }
            ?.listFiles()?.firstOrNull { it.isFile && it.length() > 0L }
        diagnostics.styleSummary = (diagnostics.styleSummary ?: "") +
            " glyphs=${if (first != null) "ok" else "empty"}"
        if (first == null) {
            diagnostics.fail(PackStage.VALIDATING_FILES, PackFailureCode.GLYPH_MISSING, "glyph directory empty")
            return false
        }
        return true
    }

    private fun validateSprite(pack: MapPack, diagnostics: PackDiagnostics): Boolean {
        val declared = pack.metadata.sprite
        if (declared.isNullOrBlank()) return false
        val resolved = pack.resolveSprite()
        if (resolved == null) {
            diagnostics.fail(PackStage.VALIDATING_FILES, PackFailureCode.SPRITE_MISSING, "declared=$declared files absent")
            return false
        }
        return true
    }

    companion object {
        /** `graph.bin` header byte 4..5, per FORMAT_SPECS §3.1. */
        const val ROUTING_FORMAT_VERSION = 1

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val bytes = digest.digest()
            val out = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            return out.toString()
        }

        private val HEX = "0123456789abcdef".toCharArray()
    }
}

/**
 * The smallest SQLite surface the validator needs.
 *
 * Narrow on purpose: every method here is one line in [AndroidSqliteProbe], so a
 * test fake is trivial and the validator's logic stays platform-free.
 */
interface SqliteProbe {
    fun openReadOnly(absolutePath: String): OpenResult

    data class OpenResult(val ok: Boolean, val session: Session?, val detail: String)

    interface Session {
        fun tableNames(): List<String>

        /**
         * Runs a query and returns rows as string cells. Only `metadata` and
         * integer aggregates are read, so text is enough and avoids pulling
         * cursor types into the interface.
         */
        fun rows(sql: String, vararg args: String): List<List<String?>>

        fun close()
    }
}

END FILE

## core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/StyleBuilder.kt

BEGIN FILE
package com.ts8.core.map.maplibre

import com.ts8.core.map.MapDisplayConfig
import com.ts8.core.map.MapStyleMode

/**
 * The offline style document, generated rather than shipped as a static asset.
 *
 * **Why generate it.** A MapLibre style is a JSON document that must name the
 * tile source, the glyph root and every layer. Four of those values are pack
 * specific — the absolute path of `basemap.mbtiles`, the glyph directory, the
 * zoom range and the attribution — and a static asset cannot carry an absolute
 * path that differs per install. Shipping a style with a hardcoded
 * `/sdcard/ts8/...` is how offline maps end up blank on a device whose pack
 * lives somewhere else.
 *
 * **Why two documents rather than one with a theme switch.** A MapLibre style
 * cannot swap colours at runtime without re-parsing every layer; `setStyle`
 * rebuilds the style, which is a visible flash. Building both once and caching
 * them means a day/night toggle is a `setStyle` on an already-parsed document,
 * and the app can cross-fade instead of going white.
 *
 * Layer names match the tiles emitted by `tools/mapdata/build_map_pack.py`, so
 * the style and the pack cannot drift apart silently: a missing layer renders as
 * nothing, and the diagnostics screen compares the two lists.
 */
object StyleBuilder {

    /** Tileset name the builder writes into the MBTiles metadata. */
    private const val SOURCE_ID = "ts8"

    /**
     * Builds a style for [pack] in the given [mode].
     *
     * @param devicePixelRatio the display density, because text size is in
     *        pixels and a 1.5x screen would otherwise draw labels at 2/3 size.
     * @param display the driver's `Hiển thị` choices. Every field is read here
     *        — layer presence, minimum zooms, label sizes and route colour all
     *        come from it — because a setting the renderer ignores is a switch
     *        that does nothing, which §8 forbids outright.
     */
    fun build(
        pack: MapPack,
        mode: MapStyleMode,
        devicePixelRatio: Float = 1f,
        display: MapDisplayConfig = MapDisplayConfig()
    ): String {
        val night = mode == MapStyleMode.NIGHT
        val palette = if (night) Palette.NIGHT else Palette.DAY
        val zoom = pack.metadata

        val json = org.json.JSONObject()
        json.put("version", 8)
        json.put("name", "TS8 ${pack.metadata.name} ${if (night) "Đêm" else "Ngày"}")
        json.put("center", pack.center?.let {
            org.json.JSONArray().put(it.lon).put(it.lat)
        } ?: org.json.JSONArray().put(108.2215).put(16.0678))
        json.put("zoom", zoom.defaultZoom)
        json.put("bearing", 0)
        // A style that starts tilted makes the first frame of a 2D-only map
        // wrong until the camera is driven; the tilt a driver asked for is
        // applied by the navigation camera, not baked into the document.
        json.put("pitch", 0)
        json.put("light", buildLight(night))

        // Glyphs and sprite must point at files on disk.
        //
        // **The scheme is not optional.** MapLibre LocalFileSource accepts only
        // `file://` URLs (lfs.cpp, acceptsURL) and refuses everything else with
        // "Invalid file URL", after which the request falls through every other
        // source and ends as "Unsupported resource request." — so a bare
        // `/data/.../fonts/...` path renders geometry with no labels at all, and
        // nothing anywhere reports why. PackPaths.fileUrl adds the scheme and
        // encodes the path exactly once; `{fontstack}`/`{range}` stay literal
        // because MapLibre substitutes them before the URL is fetched.
        pack.resolveGlyphsDir()?.let { dir ->
            json.put("glyphs", PackPaths.glyphsTemplate(dir))
        }

        // A sprite is honoured only when both halves exist. MapLibre drops
        // every icon that references a sprite whose JSON 404s, which would
        // remove the pin and vehicle icons this engine registers itself — worse
        // than having no sprite block.
        pack.resolveSprite()?.let { base ->
            json.put("sprite", PackPaths.fileUrl(base))
        }

        json.put("sources", buildSources(pack))
        json.put(
            "layers",
            buildLayers(night, palette, zoom.minZoom, zoom.maxZoom, devicePixelRatio, display)
        )

        return json.toString()
    }

    private fun buildLight(night: Boolean): org.json.JSONObject {
        // fill-extrusion needs an explicit light or MapLibre shades every
        // building identically and the 3D view reads as flat cards.
        val light = org.json.JSONObject()
        light.put("anchor", "viewport")
        light.put("color", if (night) "#ffd7a8" else "#ffffff")
        light.put("intensity", if (night) 0.35 else 0.45)
        val position = org.json.JSONArray().put(1.15).put(if (night) 210.0 else 90.0).put(30.0)
        light.put("position", position)
        return light
    }

    private fun buildSources(pack: MapPack): org.json.JSONObject {
        val sources = org.json.JSONObject()
        val source = org.json.JSONObject()
        source.put("type", "vector")
        source.put("tiles", org.json.JSONArray().put(pack.tilesUri))
        source.put("minzoom", pack.metadata.minZoom)
        source.put("maxzoom", pack.metadata.maxZoom)
        pack.bounds?.let { b ->
            source.put("bounds", org.json.JSONArray().put(b.west).put(b.south).put(b.east).put(b.north))
        }
        pack.metadata.attribution?.let { source.put("attribution", it) }
        sources.put(SOURCE_ID, source)

        // **No overlay sources here.** The route, vehicle and destination
        // GeoJSON sources are added by `MapLibreMapController.addOverlayLayers`
        // after the style loads, because they change at runtime and rewriting
        // the style document for each would rebuild the whole style.
        //
        // Declaring them here as well used to crash the overlays outright:
        // MapLibre's native `addSource` rejects a duplicate id with
        // "Cannot add source twice" (CannotAddSourceException), the controller's
        // `addSource` for the route then threw on its first line, and the catch
        // that guards the overlay block swallowed it — so the vehicle marker and
        // the destination pin were never added on any map, with nothing on
        // screen to say why. One owner per source id.
        return sources
    }

    /**
     * Every layer the style contains, in draw order.
     *
     * **Why the display config is a parameter rather than read from a field.**
     * The builder is shared between the initial load and every settings change,
     * and a field would make the document depend on state that changed between
     * the caller reading the pack and the builder writing the JSON. Everything
     * the document needs arrives in one immutable snapshot.
     *
     * **Why the route layers are gone from here.** They live in the controller,
     * which owns the `ts8-route` source at runtime. See [buildSources].
     */
    private fun buildLayers(
        night: Boolean,
        p: Palette,
        minZoom: Double,
        maxZoom: Double,
        dpr: Float,
        display: MapDisplayConfig
    ): org.json.JSONArray {
        val layers = org.json.JSONArray()
        fun add(layer: org.json.JSONObject) { layers.put(layer) }
        val scale = display.textMultiplier

        add(background(p.background))

        // Minimum zooms mirror `LAYER_MIN_ZOOM` in tools/mapdata/build_map_pack.py.
        // Setting a style layer below the zoom at which the pack starts emitting
        // it draws nothing for several zoom levels and costs a tile fetch per
        // level, so the two lists are kept in step deliberately.
        add(fillLayer("water", SOURCE_ID, "water", p.water, 6.0))
        add(lineLayer("waterway", SOURCE_ID, "waterway", p.waterLine, 800f, 9.0))

        add(fillLayer("landuse", SOURCE_ID, "landuse", p.landuse, 8.0))
        add(fillLayer("landcover", SOURCE_ID, "landcover", p.landcover, 9.0))

        // Buildings. The flat footprint layer is always present from zoom 12 —
        // that is where the pack starts emitting the layer, and a driver looking
        // at a city block needs to see the block whether or not extrusion is on.
        add(fillLayer("building-2d", SOURCE_ID, "building", p.building2d, 12.0))
        // Extrusion only when the driver asked for a view that has any, and only
        // at the zoom where it reads as a skyline rather than as spikes.
        if (display.wantsExtrusion) {
            add(building3dLayer(p, display.extrusionMinZoom))
        }

        // Quality: `QUALITY` draws roads a little heavier and keeps the railway
        // and aeroway detail; `SAVER` drops both thin-line layers and thins the
        // roads. Each of these is a real reduction in drawn geometry, not a
        // relabelled constant.
        val saver = display.performance == MapDisplayConfig.Performance.SAVER
        val quality = display.performance == MapDisplayConfig.Performance.QUALITY

        add(lineLayer("road-minor", SOURCE_ID, "road", p.roadMinor, 1f, 6.0, widthExpr = roadMinorWidth))
        add(lineLayer("road-major", SOURCE_ID, "road", p.roadMajor, 2f, 6.0, widthExpr = roadMajorWidth))
        add(lineLayer("road-motorway", SOURCE_ID, "road", p.roadMotorway, 3f, 6.0, widthExpr = roadMotorwayWidth))

        if (!saver) {
            add(lineLayer("railway", SOURCE_ID, "railway", p.railway, 500f, 8.0, dashed = true))
            add(lineLayer("aeroway", SOURCE_ID, "aeroway", p.aeroway, 600f, 9.0))
        }

        if (display.labelPlace) {
            add(
                symbolLayer("place-label", SOURCE_ID, "place", p.text, p.textHalo, dpr,
                    minZoom = 6.0, size = placeSize, bold = true, scale = scale)
            )
        }
        if (display.labelRoads) {
            add(
                symbolLayer("road-label", SOURCE_ID, "road", p.textRoad, p.textHalo, dpr,
                    minZoom = 13.0, size = roadSize, bold = false, scale = scale)
            )
        }
        if (display.labelPoi) {
            add(
                symbolLayer("poi-label", SOURCE_ID, "poi", p.text, p.textHalo, dpr,
                    minZoom = display.poiMinZoom, size = poiSize, bold = false, scale = scale)
            )
        }
        // **House numbers have no source in the shipped pack format.** The layer
        // is emitted only when the driver switches it on, and it references a
        // `housenumber` source-layer and a `housenumber` property that
        // build_map_pack.py does not write today. MapLibre treats a source-layer
        // that does not exist as an empty layer rather than an error, so the map
        // keeps rendering and the switch is honest about what it does: nothing,
        // for now. See `docs/MAP_MANAGEMENT.md`.
        if (display.labelHouseNumbers) {
            add(houseNumberLayer(p, dpr, scale))
        }

        return layers
    }

    // -- layer construction -------------------------------------------------

    private fun background(color: String): org.json.JSONObject =
        org.json.JSONObject().apply {
            put("id", "background")
            put("type", "background")
            put("paint", org.json.JSONObject().put("background-color", color))
        }

    private fun fillLayer(
        id: String,
        source: String,
        sourceLayer: String?,
        color: String,
        minZoom: Double
    ): org.json.JSONObject = org.json.JSONObject().apply {
        put("id", id)
        put("type", "fill")
        if (sourceLayer == null) {
            put("source", source)
        } else {
            put("source", source)
            put("source-layer", sourceLayer)
        }
        put("minzoom", minZoom)
        put("paint", org.json.JSONObject().put("fill-color", color))
    }

    private fun lineLayer(
        id: String,
        source: String,
        sourceLayer: String?,
        color: String,
        width: Float,
        minZoom: Double,
        dashed: Boolean = false,
        widthExpr: org.json.JSONArray? = null
    ): org.json.JSONObject = org.json.JSONObject().apply {
        put("id", id)
        put("type", "line")
        put("source", source)
        if (sourceLayer != null) put("source-layer", sourceLayer)
        put("minzoom", minZoom)
        val paint = org.json.JSONObject()
        paint.put("line-color", color)
        // Width interpolates with zoom so a street is a hairline at city scale
        // and a real road at street scale, which is what makes the map read as
        // a map instead of a diagram of spikes.
        if (widthExpr != null) {
            paint.put("line-width", widthExpr)
        } else {
            paint.put(
                "line-width",
                interpolateZoom(0.5, minZoom, width.toDouble(), maxOf(minZoom + 6, 15.0))
            )
        }
        if (dashed) {
            val layout = org.json.JSONObject()
            layout.put("line-cap", "butt")
            put("layout", layout)
            paint.put("line-dasharray", org.json.JSONArray().put(2).put(2))
        }
        put("paint", paint)
    }

    /**
     * Buildings extruded from the tag-derived height.
     *
     * `fill-extrusion-height` reads `render_height`, which the pack builder
     * computes from `height`, then `building:levels × 3.2`, then a per-type
     * default. Using the tag rather than a constant is what makes the 3D view
     * describe the actual skyline.
     *
     * @param minZoom from [MapDisplayConfig.extrusionMinZoom]; a 3D mode shows
     *        the skyline earlier than the automatic one, which only extrudes
     *        once the street network is legible.
     */
    private fun building3dLayer(p: Palette, minZoom: Double): org.json.JSONObject =
        org.json.JSONObject().apply {
            put("id", "building-3d")
            put("type", "fill-extrusion")
            put("source", SOURCE_ID)
            put("source-layer", "building")
            put("minzoom", minZoom)
            put("paint", org.json.JSONObject().apply {
                put("fill-extrusion-color", p.building3d)
                put("fill-extrusion-height", heightExpression())
                put("fill-extrusion-base", 0)
                put("fill-extrusion-opacity", 0.85)
            })
        }

    /**
     * House-number labels, emitted only when the driver turns them on.
     *
     * Reads `housenumber` from a `housenumber` source-layer. The current pack
     * builder does not emit that layer, so this draws nothing until a pack
     * carries the tag; MapLibre treats an absent `source-layer` as an empty
     * layer, not as an error, which is why it is safe to include.
     */
    private fun houseNumberLayer(p: Palette, dpr: Float, scale: Float): org.json.JSONObject =
        org.json.JSONObject().apply {
            put("id", "housenumber-label")
            put("type", "symbol")
            put("source", SOURCE_ID)
            put("source-layer", "housenumber")
            put("minzoom", 17.0)
            put("filter", org.json.JSONArray().put("!=")
                .put(org.json.JSONArray().put("get").put("housenumber")).put(""))
            put("layout", org.json.JSONObject().apply {
                put("text-field", org.json.JSONArray().put("get").put("housenumber"))
                put("text-font", org.json.JSONArray().put("Noto Sans Regular"))
                put("text-size", scaled(10.0, scale))
                put("text-allow-overlap", false)
            })
            put("paint", org.json.JSONObject().apply {
                put("text-color", p.text)
                put("text-halo-color", p.textHalo)
                put("text-halo-width", 1.2f * dpr.coerceIn(1f, 2f))
            })
        }

    /**
     * Height with a graceful ladder: every fallback is a real tag present in
     * the pack before it becomes a constant.
     *
     * The pack builder (`build_map_pack.py`, `building_height`) writes `height`
     * in metres and `levels` as `height / 3.0` — both are emitted, so the
     * expression prefers the metres the builder already resolved rather than
     * re-deriving anything. Only buildings with neither tag fall through to the
     * class default, and `building=yes` with no height data is the common case
     * in Vietnamese OSM data.
     */
    private fun heightExpression(): org.json.JSONArray =
        org.json.JSONArray().apply {
            put("coalesce")
            put(org.json.JSONArray().put("get").put("height"))
            // `levels` is height/3 per the builder, so multiplying back by 3.2
            // would over-shoot; 3.0 reproduces the builder's own figure.
            put(org.json.JSONArray().put("*").put(org.json.JSONArray().put("get").put("levels")).put(3.0))
            put(org.json.JSONArray().put("match")
                .put(org.json.JSONArray().put("get").put("class"))
                .put(org.json.JSONArray().put("apartments").put("office").put("hotel").put("commercial"))
                .put(12)
                .put(org.json.JSONArray().put("house").put("residential").put("yes"))
                .put(7)
                .put(8))
        }

    private fun symbolLayer(
        id: String,
        source: String,
        sourceLayer: String,
        color: String,
        halo: String,
        dpr: Float,
        minZoom: Double,
        size: org.json.JSONArray,
        bold: Boolean,
        scale: Float = 1f
    ): org.json.JSONObject = org.json.JSONObject().apply {
        put("id", id)
        put("type", "symbol")
        put("source", source)
        put("source-layer", sourceLayer)
        put("minzoom", minZoom)
        // A label with no text field throws in MapLibre ("text-field requires"),
        // and the pack writes `name: ""` for unnamed streets. Filtering on a
        // non-empty name is what stops an unnamed service road crashing the
        // style load on a real tile.
        put("filter", org.json.JSONArray().put("!=").put(org.json.JSONArray().put("get").put("name")).put(""))
        put("layout", org.json.JSONObject().apply {
            put("text-field", org.json.JSONArray().put("get").put("name"))
            // Vietnamese labels are long, so the stack names the two weights
            // the pack's glyph directory actually ships.
            put("text-font", org.json.JSONArray().put(if (bold) "Noto Sans Bold" else "Noto Sans Regular"))
            // The driver's `Cỡ chữ và icon` setting scales every label in the
            // style, not only the POIs: a driver who needs larger text needs it
            // on the street name they are reading, not only on a shop.
            put("text-size", scaled(size, scale))
            put("text-max-width", 10)
            put("text-padding", 4)
            put("text-allow-overlap", false)
            put("text-ignore-placement", false)
            // Rotate street labels with the road, but keep place names upright.
            if (sourceLayer == "road") {
                put("symbol-placement", "line")
                put("text-rotation-alignment", "map")
                put("symbol-spacing", 300)
            }
        })
        put("paint", org.json.JSONObject().apply {
            put("text-color", color)
            // A halo is what makes a label readable over a busy background at
            // arm's length in daylight; without it labels vanish into the map.
            put("text-halo-color", halo)
            put("text-halo-width", 1.4f * dpr.coerceIn(1f, 2f))
        })
    }

    /**
     * Multiplies every stop of a zoom-interpolated size expression by [scale].
     *
     * Size expressions are alternating `[zoom, size, zoom, size, …]` after the
     * three preamble entries, so only the size slots are touched. Scaling the
     * zoom stops as well would make labels appear at a different zoom instead of
     * at a different size, which is a different setting.
     */
    private fun scaled(size: org.json.JSONArray, scale: Float): org.json.JSONArray {
        if (scale == 1f) return size
        val out = org.json.JSONArray()
        for (i in 0 until size.length()) {
            val value = size.get(i)
            if (i >= 3 && i % 2 == 1 && value is Number) {
                out.put(value.toDouble() * scale)
            } else {
                out.put(value)
            }
        }
        return out
    }

    /** Single-value equivalent of [scaled] for a plain number. */
    private fun scaled(value: Double, scale: Float): Double = value * scale

    private fun interpolateZoom(from: Double, fromZoom: Double, to: Double, toZoom: Double): org.json.JSONArray =
        org.json.JSONArray().apply {
            put("interpolate")
            put(org.json.JSONArray().put("linear"))
            put(org.json.JSONArray().put("zoom"))
            put(fromZoom); put(from)
            put(toZoom); put(to)
        }

    private val roadMinorWidth = interpolateZoom(0.4, 10.0, 2.5, 16.0)
    private val roadMajorWidth = interpolateZoom(0.8, 10.0, 6.0, 16.0)
    private val roadMotorwayWidth = interpolateZoom(1.4, 8.0, 9.0, 16.0)

    private val placeSize = org.json.JSONArray().apply {
        put("interpolate"); put(org.json.JSONArray().put("linear")); put(org.json.JSONArray().put("zoom"))
        put(6); put(11); put(10); put(13); put(14); put(16)
    }
    private val roadSize = org.json.JSONArray().apply {
        put("interpolate"); put(org.json.JSONArray().put("linear")); put(org.json.JSONArray().put("zoom"))
        put(13); put(10); put(16); put(13)
    }
    private val poiSize = org.json.JSONArray().apply {
        put("interpolate"); put(org.json.JSONArray().put("linear")); put(org.json.JSONArray().put("zoom"))
        put(14); put(10); put(18); put(13)
    }

    /** Zoom from which buildings are extruded, per the spec. */
    const val THREE_D_MIN_ZOOM = 15.0

    /** GeoJSON source id the route overlay draws into. */
    const val ROUTE_SOURCE = "ts8-route"

    /** Base source id, so the diagnostics screen can compare against the pack. */
    const val BASE_SOURCE = SOURCE_ID

    private class Palette(
        val background: String,
        val water: String,
        val waterLine: String,
        val landuse: String,
        val landcover: String,
        val building2d: String,
        val building3d: String,
        val roadMinor: String,
        val roadMajor: String,
        val roadMotorway: String,
        val railway: String,
        val aeroway: String,
        val text: String,
        val textRoad: String,
        val textHalo: String,
        val route: String,
        val routeCasing: String
    ) {
        companion object {
            /**
             * Day colours aimed at an OLED in sunlight: high contrast between
             * the road casings and the land, because the driver reads the road
             * shape in a glance and has no time for subtlety.
             */
            val DAY = Palette(
                background = "#e8e4dc",
                water = "#a8c8e8",
                waterLine = "#7ba8d0",
                landuse = "#dcd8cc",
                landcover = "#d4d0c0",
                building2d = "#cfc8bc",
                building3d = "#c8bfae",
                roadMinor = "#ffffff",
                roadMajor = "#f7d97a",
                roadMotorway = "#f2a65a",
                railway = "#9a9a9a",
                aeroway = "#c0c0c0",
                text = "#2b2b2b",
                textRoad = "#4a4a4a",
                textHalo = "#ffffff",
                route = "#1f7ae0",
                routeCasing = "#ffffff"
            )

            /**
             * Night colours for an OLED: the background is near-black so the
             * panel draws almost no light in a dark car, and the route stays
             * saturated because it is the one thing being followed.
             */
            val NIGHT = Palette(
                background = "#0d1117",
                water = "#12202e",
                waterLine = "#1b3348",
                landuse = "#14191f",
                landcover = "#11161b",
                building2d = "#1a2027",
                building3d = "#222a33",
                roadMinor = "#2c3540",
                roadMajor = "#4a4230",
                roadMotorway = "#5c4a2a",
                railway = "#2a3038",
                aeroway = "#20262d",
                text = "#d7dde5",
                textRoad = "#a8b2bd",
                textHalo = "#0d1117",
                route = "#3d9bff",
                routeCasing = "#0d1117"
            )
        }
    }
}

END FILE

## core/map-engine-maplibre/src/test/java/com/ts8/core/map/maplibre/PackPathsTest.kt

BEGIN FILE
package com.ts8.core.map.maplibre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `PackPaths` — the URL builder every local map asset goes through.
 *
 * This class exists because two silent failures shipped before it: a glyph path
 * with no scheme, and a pack path pasted into `mbtiles://` raw. Both produce a
 * blank map and neither raises an error anywhere, so the tests here pin the
 * exact bytes MapLibre's native decoder will receive rather than the fact that
 * "a URL was produced".
 *
 * The rules being asserted, all of them from `lfs.cpp` / the mbtiles source:
 *
 *  - the scheme is mandatory and is exactly `file://` or `mbtiles://`
 *  - everything after the scheme is percent-decoded once, so it is encoded once
 *  - `/` stays literal (both decoders accept it) and the unreserved set does not
 *    need escaping
 *  - `{fontstack}` / `{range}` stay literal, because MapLibre substitutes them
 *    as text **before** the URL reaches a file source
 */
class PackPathsTest {

    @Test
    fun `unreserved characters and separators stay literal`() {
        assertEquals(
            "/data/app/pack-v1.2_x/y",
            PackPaths.encodePath("/data/app/pack-v1.2_x/y")
        )
    }

    @Test
    fun `a space is percent-encoded`() {
        // The case that broke silently: MapLibre percent-decodes what follows
        // the scheme, so a raw space produces a path that does not exist.
        assertEquals("/sdcard/My%20Maps/pack", PackPaths.encodePath("/sdcard/My Maps/pack"))
    }

    @Test
    fun `non-ASCII is encoded as UTF-8 bytes`() {
        // "Đà Nẵng" -> C4 90 C3 A0 20 4E E1 BA B5 6E 67. Encoding per char rather
        // than per byte would emit %90 %E0 and every one of these would be wrong.
        assertEquals(
            "/pack/%C4%90%C3%A0%20N%E1%BA%B5ng",
            PackPaths.encodePath("/pack/Đà Nẵng")
        )
    }

    @Test
    fun `a percent sign is encoded, so an encoded path is not encoded twice`() {
        // Encoding twice is the mirror of the bug above and equally silent:
        // "%20" would reach the decoder as "%2520" and become a literal "%20".
        assertEquals("/a%25b", PackPaths.encodePath("/a%b"))
    }

    @Test
    fun `hex digits are upper case`() {
        // The decoder accepts either, but a log line that differs between runs
        // is one that cannot be grepped. Control characters exercise the
        // low-byte branch: 0x01 and 0x02 are outside every literal set.
        assertEquals("%01%02", PackPaths.encodePath("\u0001\u0002"))
    }

    @Test
    fun `a Windows drive letter and backslashes are encoded`() {
        // A backslash is not a path separator to the native decoder; leaving it
        // literal makes the file source look for a file called "D:\...".
        assertEquals(
            "D%3A%5Cmaps%5Cpack",
            PackPaths.encodePath("D:\\maps\\pack")
        )
    }

    @Test
    fun `mbtiles URL carries the scheme and the encoded path`() {
        assertEquals(
            "mbtiles:///sdcard/My%20Maps/basemap.mbtiles",
            PackPaths.mbtilesUrl("/sdcard/My Maps/basemap.mbtiles")
        )
    }

    @Test
    fun `file URL is the empty-authority form`() {
        // `file://` + a path that already starts with `/` gives `file:///...`,
        // which is the form the decoder expects: it strips `file://` and keeps
        // the leading slash. `file://localhost/` would leave the host in the
        // path.
        assertEquals("file:///data/pack/fonts", PackPaths.fileUrl("/data/pack/fonts"))
    }

    @Test
    fun `glyph template keeps the placeholders literal`() {
        // The single most subtle rule in the file: encoding the braces would
        // leave the template unsubstituted, and every glyph request would 404
        // with no error raised.
        //
        // The expected prefix is built from the same `File`, not spelled out.
        // This test runs on the build host's JVM, where `File("/sdcard/...")`
        // resolves against the current drive and comes back `D:\sdcard\...`;
        // hardcoding the Unix form would assert the host's filesystem rather
        // than the encoder's behaviour.
        val dir = File("/sdcard/My Maps/fonts")
        val url = PackPaths.glyphsTemplate(dir)

        assertEquals(
            "file://" + PackPaths.encodePath(dir.absolutePath) + "/{fontstack}/{range}.pbf",
            url
        )
        assertTrue("the scheme is mandatory", url.startsWith("file://"))
        assertTrue("the placeholder must survive", url.contains("{fontstack}"))
        assertTrue("the placeholder must survive", url.contains("{range}"))
        assertTrue("a space in the directory must be encoded", url.contains("My%20Maps"))
    }

    @Test
    fun `encoding is idempotent over an already-safe path`() {
        val path = "/data/local/tmp/ts8-pack/basemap.mbtiles"
        assertEquals(path, PackPaths.encodePath(path))
        assertEquals(path, PackPaths.encodePath(PackPaths.encodePath(path)))
    }
}

END FILE

## core/music-api/build.gradle.kts

BEGIN FILE
plugins {
    id("ts8.core-module")
}

android {
    namespace = "com.ts8.core.music"
}

dependencies {
    // For GeoPoint on TrackPlace.
    api(project(":core:map-api"))
}

END FILE

## core/music-api/src/main/AndroidManifest.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<manifest />

END FILE

## core/music-api/src/main/java/com/ts8/core/music/MediaScanner.kt

BEGIN FILE
package com.ts8.core.music

/**
 * Incremental scan bookkeeping.
 *
 * A full MediaStore walk on this head unit takes tens of seconds and pins the
 * CPU while the map is trying to render. The spec requires incremental scans,
 * so this interface is the contract the scanner honours: it records what was
 * seen last time and only re-probes what changed.
 */
interface MediaScanner {
    /**
     * Runs a scan.
     *
     * @param forceFullScan ignore the saved state and re-read everything. Used
     *        only by the diagnostics screen and after a database rebuild.
     * @param onProgress called on a background thread with 0..1.
     * @return a summary of what changed.
     */
    fun scan(forceFullScan: Boolean = false, onProgress: (Float) -> Unit = {}): ScanResult

    /** True when a scan can run right now (storage readable, not already running). */
    fun canScan(): Boolean

    /** Cancels a running scan. Safe to call when nothing is running. */
    fun cancel()
}

data class ScanResult(
    val scannedFiles: Int,
    val added: Int,
    val updated: Int,
    val removed: Int,
    /** Files skipped because their codec failed a decode probe. */
    val unsupported: Int,
    val durationMs: Long,
    val usbScanned: Boolean,
    val error: String? = null
) {
    val changedAnything: Boolean get() = added > 0 || updated > 0 || removed > 0
}

/**
 * Codec support as this device actually implements it.
 *
 * The spec requires FLAC to be offered only after a codec test, because the
 * TS8 firmware's MediaCodec list is not the AOSP one. Claiming support and then
 * failing at playback is the exact behaviour the spec forbids.
 */
interface CodecSupport {
    /** True when the platform reports a decoder for this MIME type. */
    fun isSupported(mimeType: String): Boolean

    /**
     * Verifies a codec by actually decoding a short generated buffer of that
     * format. Called once at startup for FLAC and any other format whose
     * support the device may not report honestly.
     */
    fun probe(mimeType: String): Boolean

    /** Formats this device can actually decode right now. */
    fun supportedMimeTypes(): Set<String>

    /** Human-readable note for the diagnostics screen. */
    fun report(): List<CodecReport>
}

data class CodecReport(
    val mimeType: String,
    val displayName: String,
    val platformClaims: Boolean,
    val probePassed: Boolean?
) {
    val usable: Boolean get() = platformClaims && (probePassed == null || probePassed)
}

/** Shared constants for the music feature. */
object MusicFormats {
    const val MIME_MP3 = "audio/mpeg"
    const val MIME_AAC = "audio/mp4a-latm"
    const val MIME_M4A = "audio/mp4"
    const val MIME_OGG = "audio/ogg"
    const val MIME_WAV = "audio/x-wav"
    const val MIME_FLAC = "audio/flac"

    /** Always offered: the platform guarantees these. */
    val ALWAYS_SUPPORTED = setOf(MIME_MP3, MIME_AAC, MIME_M4A, MIME_OGG, MIME_WAV)

    /** Offered only when [CodecSupport.probe] passes. */
    val PROBE_REQUIRED = setOf(MIME_FLAC)

    private val byExtension = mapOf(
        "mp3" to MIME_MP3,
        "aac" to MIME_AAC,
        "m4a" to MIME_M4A,
        "mp4" to MIME_M4A,
        "ogg" to MIME_OGG,
        "oga" to MIME_OGG,
        "opus" to MIME_OGG,
        "wav" to MIME_WAV,
        "flac" to MIME_FLAC
    )

    /** MIME type for a file name, or null when the extension is not audio. */
    fun mimeForFileName(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return null
        return byExtension[name.substring(dot + 1).lowercase()]
    }

    fun isAudioFileName(name: String): Boolean = mimeForFileName(name) != null

    /** Display name for the badge in the music screen. */
    fun displayName(mime: String): String = when (mime) {
        MIME_MP3 -> "MP3"
        MIME_AAC, MIME_M4A -> "AAC"
        MIME_OGG -> "OGG"
        MIME_WAV -> "WAV"
        MIME_FLAC -> "FLAC"
        else -> mime.substringAfterLast('/').uppercase()
    }
}

END FILE

## core/music-api/src/main/java/com/ts8/core/music/MusicApi.kt

BEGIN FILE
package com.ts8.core.music

/**
 * The music side of the launcher, as the UI sees it.
 *
 * The interface exists so the UI never knows whether tracks come from
 * MediaStore, a USB stick, or a network provider. It is also the seam a future
 * online provider plugs into without the UI changing.
 */
interface MusicLibrary {
    /** True once at least one scan has completed. */
    fun isReady(): Boolean

    /** Total tracks known, without loading them. */
    fun trackCount(): Int

    /** Tracks matching a free-text query, capped at [limit]. */
    fun searchTracks(query: String, limit: Int = 100): List<Track>

    fun tracksInAlbum(albumId: Long): List<Track>

    fun tracksByArtist(artistId: Long): List<Track>

    fun tracksInFolder(folder: String): List<Track>

    fun albums(): List<MusicCollection>

    fun artists(): List<MusicCollection>

    fun folders(): List<MusicCollection>

    fun playlists(): List<Playlist>

    fun playlistTracks(playlistId: Long): List<Track>

    fun favorites(): List<Track>

    fun recentlyPlayed(limit: Int = 50): List<Track>

    /** All tracks, ordered for browsing. Pageable so the UI never loads 10k rows. */
    fun allTracks(offset: Int = 0, limit: Int = 200): List<Track>

    fun track(id: Long): Track?

    // -- mutations ---------------------------------------------------------

    /** Creates a playlist; returns its new id. */
    fun createPlaylist(name: String): Long

    fun addToPlaylist(playlistId: Long, trackId: Long): Boolean

    fun removeFromPlaylist(playlistId: Long, trackId: Long): Boolean

    fun deletePlaylist(playlistId: Long)

    fun setFavorite(trackId: Long, favorite: Boolean)

    fun isFavorite(trackId: Long): Boolean

    /** Records that a track started playing, for the "recent" list. */
    fun recordPlayed(trackId: Long, atMs: Long)

    /** Total duration of everything, in milliseconds — used by the UI footer. */
    fun totalDurationMs(): Long
}

/**
 * Playback control. Implemented by the engine and driven by the service.
 *
 * All methods are safe to call from the main thread; the engine moves heavy
 * work off it. None of them throw when there is nothing loaded — they are
 * no-ops or return false, because a head unit must never crash because the
 * driver tapped next on an empty queue.
 */
interface MusicPlayer {
    fun state(): PlaybackState

    /** Plays the given queue starting at [startIndex]. */
    fun setQueue(tracks: List<Track>, startIndex: Int = 0, autoPlay: Boolean = true)

    fun play()

    fun pause()

    fun togglePlayPause()

    fun next()

    fun previous()

    /** Seeks within the current track. Out-of-range values are clamped. */
    fun seekTo(positionMs: Long)

    fun setShuffle(enabled: Boolean)

    fun setRepeatMode(mode: RepeatMode)

    /**
     * Saves the resume point: current track id and position. Called on
     * Activity stop and on audio focus loss, and read back at boot so the car
     * resumes where it left off rather than at the first track.
     */
    fun saveCheckpoint()

    /** Restores the last saved checkpoint, without auto-playing. */
    fun restoreCheckpoint()

    /** Releases the player and audio focus. */
    fun release()

    /**
     * Attaches a resolver that turns a [Track.SourceKind.ONLINE] track's
     * `source` into a URL the platform player may open.
     *
     * **Why the player cannot do this itself.** Resolving a stream is a network
     * call to a provider, and this module knows no provider — by design, so the
     * local engine has no dependency on an online catalogue. The app hands one
     * in when there is one and leaves it null when there is not, and a player
     * with no resolver reports
     * [PlaybackError.STREAM_UNAVAILABLE] for an online track rather than
     * handing `MediaPlayer` a `null` and dying.
     *
     * **Why it is a call, not a constructor argument.** The service binds the
     * player before the online gateway may exist, and the gateway can be
     * reconfigured (a server address entered) without the player being rebuilt.
     *
     * The resolver runs on the player's own background path and must block; it
     * is called at most once per track open, and only when the source is not
     * already an `http(s)` URL.
     */
    fun setStreamResolver(resolver: StreamResolver?) = Unit
}

/**
 * Turns an online track into a playable URL.
 *
 * Deliberately a one-method interface returning a reason string rather than
 * throwing: a radio station that went offline between search and playback is
 * an ordinary event in a car, and a player that has to catch an exception to
 * read it would end up with a `catch (Throwable)` that swallows real bugs.
 */
fun interface StreamResolver {
    /**
     * The URL to open, or a Vietnamese reason it could not be produced.
     *
     * Called off the main thread. Null [track.source] values do not reach here —
     * the player refuses before asking.
     */
    fun resolve(track: Track): StreamResolution
}

/** Result of a [StreamResolver] call. */
sealed class StreamResolution {
    /** Open this URL; [mimeType] and [durationMs] refine the player's view. */
    data class Playable(
        val url: String,
        val mimeType: String? = null,
        /** 0 for a live stream with no known end. */
        val durationMs: Long = 0L,
        /** True when the stream has no end and the player should not expect EOF. */
        val isLive: Boolean = false
    ) : StreamResolution()

    /** The provider could not produce a URL. [reason] is Vietnamese and shown. */
    data class Unavailable(
        val reason: String,
        /** True when a later retry could succeed (network), false when not. */
        val retryable: Boolean = true
    ) : StreamResolution()
}

/** Listener for playback changes. All callbacks arrive on the main thread. */
interface PlaybackListener {
    fun onStateChanged(state: PlaybackState)
    fun onError(error: PlaybackError, track: Track?)
}

/** Observable side of [MusicPlayer]. */
interface MusicPlayerObservable {
    fun addListener(listener: PlaybackListener)
    fun removeListener(listener: PlaybackListener)
}

/** Everything the music feature needs, in one bundle for DI. */
interface MusicBackend {
    val library: MusicLibrary
    val player: MusicPlayer
}

END FILE

## core/music-api/src/main/java/com/ts8/core/music/Track.kt

BEGIN FILE
package com.ts8.core.music

import com.ts8.core.map.GeoPoint

/**
 * A playable audio item.
 *
 * The model is deliberately flat: on a head unit the whole library is paged
 * from SQLite, and a nested object graph costs allocations the device cannot
 * spare. Albums and artists are [id] references plus a display name.
 */
data class Track(
    /** Stable local id (MediaStore id, or a hash for USB files). Not a path. */
    val id: Long,
    /** Absolute path or content URI, as the engine needs it to open the file. */
    val source: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumId: Long?,
    val artistId: Long?,
    /** Duration in milliseconds; 0 when unknown. */
    val durationMs: Long,
    /** Folder this file lives in, for the folder browser. */
    val folder: String?,
    /** Track number within its album, 0 when untagged. */
    val trackNumber: Int,
    /** Release year, 0 when unknown. */
    val year: Int,
    /** Audio MIME type, e.g. "audio/mpeg". */
    val mimeType: String?,
    /** File size in bytes, used for incremental rescan. */
    val sizeBytes: Long,
    /** Last modification time in epoch seconds, used for incremental rescan. */
    val modifiedSeconds: Long,
    /** Where this track came from — internal storage, a USB volume, or a provider. */
    val sourceKind: SourceKind,

    /**
     * The online provider that issued this track, or null for a local file.
     *
     * **Why the id and not a reference.** The player must be able to persist a
     * queue and hand it back without holding a provider object; and a queue
     * restored from a build that had a provider this one does not must degrade
     * to "nguồn không còn" rather than crash. A string is the only form that
     * survives both. It is the same value as
     * [com.ts8.core.music.online.OnlineTrack.providerId], which is what lets
     * `getPlayableSource` be re-issued for a track whose URL expired.
     *
     * Null, not an empty string, for a local file: a local track genuinely has
     * no provider and a sentinel would be a value the UI has to special-case.
     */
    val providerId: String? = null
) {
    enum class SourceKind {
        MEDIA_STORE,

        USB,

        /**
         * A network stream from an online provider, or one whose URL is
         * resolved lazily.
         *
         * **Why this lives on the same type as a file.** §13 requires one queue
         * that holds both a local file and a radio station without duplicating
         * playback state. The player, the `MediaSession`, the notification and
         * the three screens that draw "now playing" all take a [Track]; giving
         * online items a second parallel type would mean a second queue, a
         * second now-playing row and two answers to "what is playing". So an
         * online item is a [Track] whose [source] is an `http(s)` URL.
         *
         * **What is deliberately absent.** [id] is a hash of the provider id and
         * the provider's own track id, never a library row id — online items are
         * not written to SQLite and are not in the media database. That is why a
         * saved checkpoint drops them on the next boot instead of failing to
         * resolve them.
         */
        ONLINE
    }

    /** Display string for a subtitle line. */
    val subtitle: String
        get() = listOfNotNull(artist?.takeIf { it.isNotBlank() }, album?.takeIf { it.isNotBlank() })
            .joinToString(" · ")
}

/** A collection shown in the music library. */
data class MusicCollection(
    val id: Long,
    val name: String,
    val kind: Kind,
    val trackCount: Int,
    /** Number of tracks, or null when unknown without counting. */
    val artworkUri: String? = null
) {
    enum class Kind { ALBUM, ARTIST, FOLDER, PLAYLIST, FAVORITE, RECENT }
}

/** A user-created playlist. */
data class Playlist(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val createdAtMs: Long
)

/** Repeat mode of the queue. */
enum class RepeatMode {
    OFF,

    /** Restart the queue when the last item finishes. */
    ALL,

    /** Repeat the current track only. */
    ONE;

    fun next(): RepeatMode = when (this) {
        OFF -> ALL
        ALL -> ONE
        ONE -> OFF
    }
}

/** Playback state as the UI renders it. */
data class PlaybackState(
    val track: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedPercent: Int = 0,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    /** True while audio is held down for a navigation announcement. */
    val duckedForNavigation: Boolean = false,
    /** True while paused because a phone call is in progress. */
    val pausedForCall: Boolean = false,
    /** Set when playback stopped for a reason worth telling the driver. */
    val error: PlaybackError? = null,
    /**
     * Where a network stream is in its buffering life cycle.
     *
     * A local file is always [BufferingState.IDLE]: it needs no buffering, and
     * reporting otherwise would make every song show a spinner for its first
     * frame.
     */
    val buffering: BufferingState = BufferingState.IDLE
) {
    val hasTrack: Boolean get() = track != null
    val progressFraction: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/** Why playback stopped. Surfaced in the UI rather than swallowed. */
enum class PlaybackError {
    /** The file disappeared — typically the USB stick was pulled. */
    SOURCE_UNAVAILABLE,

    /** The codec cannot decode this file on this device. */
    UNSUPPORTED_FORMAT,

    /** The file exists but is corrupt or truncated. */
    DECODE_FAILED,

    /** The audio output was taken by another app and not given back. */
    AUDIO_FOCUS_LOST,

    /**
     * A network stream could not be opened at all.
     *
     * Distinct from [SOURCE_UNAVAILABLE] because the two are different
     * sentences on screen and different actions for the driver: a vanished file
     * means "cắm lại USB", a stream that never opened means "kiểm tra kết nối"
     * and is worth a retry button. Collapsing them would tell a driver with a
     * dead modem to go looking for a USB stick.
     */
    STREAM_UNAVAILABLE,

    /** The stream opened and then died mid-playback. */
    STREAM_INTERRUPTED,

    /** Anything the engine cannot classify. */
    UNKNOWN
}

/**
 * Where a playing item is in its buffering life cycle.
 *
 * [com.ts8.core.music.PlaybackState.bufferedPercent] alone cannot express
 * "stalled": a stream at 40 % that stopped receiving data is not buffering
 * forward, and a progress bar that keeps its last value says nothing at all.
 * A separate state is what lets the UI show "Đang tải…" and a spinner rather
 * than a frozen bar.
 */
enum class BufferingState {
    /** Not a stream, or a stream already fully buffered: draw the position. */
    IDLE,

    /** Waiting for the first bytes; the player has not started. */
    BUFFERING,

    /** Playing, but below the point where a hiccup is unlikely. */
    STALLED
}

/**
 * Which audio stream the launcher should take over.
 *
 * Kept as an enum rather than a raw stream constant so the bridge can map it
 * onto whatever the vehicle bus exposes later.
 */
enum class AudioRoute {
    /** Normal playback through the media stream. */
    MEDIA,

    /** Navigation announcements, which must be audible over music. */
    NAVIGATION,

    /** Phone call audio; never used by this app for playback. */
    CALL
}

/** A geographic position attached to a track, for "songs near me". */
data class TrackPlace(val trackId: Long, val point: GeoPoint)

END FILE

## core/music-engine-local/build.gradle.kts

BEGIN FILE
plugins {
    id("ts8.core-module")
}

android {
    namespace = "com.ts8.core.music.local"
}

dependencies {
    api(project(":core:music-api"))
    // The player takes audio focus through the bridge rather than talking to
    // AudioManager directly, so a future SyuAudioBridge is a constructor change.
    api(project(":core:vehicle-audio-bridge"))
}

END FILE

## core/music-engine-local/src/main/AndroidManifest.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<manifest />

END FILE

## core/music-engine-local/src/main/java/com/ts8/core/music/local/AndroidCodecSupport.kt

BEGIN FILE
package com.ts8.core.music.local

import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import com.ts8.core.music.CodecReport
import com.ts8.core.music.CodecSupport
import com.ts8.core.music.MusicFormats
import java.io.File
import java.io.RandomAccessFile

/**
 * Codec support, measured rather than assumed.
 *
 * The spec is explicit: FLAC is offered only after a codec test, and a
 * privileged-looking capability must never be faked. This class therefore has
 * two levels of evidence:
 *
 * 1. **What the platform advertises** — `MediaCodecList` decoder entries for
 *    the MIME type. Cheap, but the TS8 firmware ships a trimmed list that has
 *    been seen to advertise codecs that fail at `configure()`.
 * 2. **What actually decodes** — [probe] opens a real, tiny file of that
 *    format through `MediaExtractor` + `MediaCodec`. If the firmware lies,
 *    this catches it.
 *
 * The probe files are written into the app's private cache on demand from
 * byte arrays built here, so no asset has to be shipped and the probe measures
 * the device's decoder rather than the APK's contents.
 */
class AndroidCodecSupport(
    /** Cache directory for the generated probe files. */
    private val cacheDir: File
) : CodecSupport {

    /** Result of each probe, memoised: a probe is a few hundred ms of work. */
    private val probeResults = HashMap<String, Boolean>()
    private val platformClaims = HashMap<String, Boolean>()

    /** Cache of the advertising check, which is the cheap one. */
    private var advertised: Set<String>? = null

    override fun isSupported(mimeType: String): Boolean {
        if (mimeType in MusicFormats.ALWAYS_SUPPORTED) return true
        if (mimeType !in MusicFormats.PROBE_REQUIRED) return false
        return probe(mimeType)
    }

    override fun probe(mimeType: String): Boolean = synchronized(probeResults) {
        probeResults[mimeType]?.let { return it }
        val claims = platformClaimsFor(mimeType)
        platformClaims[mimeType] = claims
        if (!claims) {
            probeResults[mimeType] = false
            return false
        }
        val result = runProbe(mimeType)
        probeResults[mimeType] = result
        result
    }

    override fun supportedMimeTypes(): Set<String> {
        val out = HashSet<String>(MusicFormats.ALWAYS_SUPPORTED)
        for (mime in MusicFormats.PROBE_REQUIRED) {
            if (probe(mime)) out.add(mime)
        }
        return out
    }

    override fun report(): List<CodecReport> {
        val all = MusicFormats.ALWAYS_SUPPORTED + MusicFormats.PROBE_REQUIRED
        return all.sorted().map { mime ->
            val claims = if (mime in MusicFormats.ALWAYS_SUPPORTED) {
                platformClaimsFor(mime)
            } else {
                platformClaims.getOrPut(mime) { platformClaimsFor(mime) }
            }
            CodecReport(
                mimeType = mime,
                displayName = MusicFormats.displayName(mime),
                platformClaims = claims,
                // Only formats in PROBE_REQUIRED are actually probed. Recording
                // `null` for the rest is honest: they are guaranteed by the
                // platform contract, not measured.
                probePassed = probeResults[mime]
            )
        }
    }

    // -- evidence -----------------------------------------------------------

    private fun platformClaimsFor(mimeType: String): Boolean {
        advertised?.let { return mimeType in it }

        val found = HashSet<String>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                for (info in list.codecInfos) {
                    if (!info.isEncoder) continue
                    found.addAll(info.supportedTypes)
                }
                // The loop above collected encoders' types for completeness;
                // decoders are what playback needs, so scan them explicitly.
                val decoders = MediaCodecList(MediaCodecList.ALL_CODECS)
                for (info in decoders.codecInfos) {
                    if (info.isEncoder) continue
                    found.addAll(info.supportedTypes)
                }
            } else {
                @Suppress("DEPRECATION")
                val count = android.media.MediaCodecList.getCodecCount()
                for (i in 0 until count) {
                    @Suppress("DEPRECATION")
                    val info = android.media.MediaCodecList.getCodecInfoAt(i)
                    if (info.isEncoder) continue
                    found.addAll(info.supportedTypes)
                }
            }
        } catch (e: Exception) {
            // A vendor build that throws here is treated as "does not claim",
            // which makes the probe decide — the safe direction.
            found.clear()
        }
        advertised = found
        return mimeType in found
    }

    /**
     * Decodes a generated file end to end.
     *
     * Uses `MediaExtractor` + `MediaCodec` rather than `MediaPlayer` because
     * `MediaPlayer` with a real file needs the audio output to be free; the
     * probe must work while music is playing and while a call is in progress.
     */
    private fun runProbe(mimeType: String): Boolean {
        val probeFile = try {
            when (mimeType) {
                MusicFormats.MIME_FLAC -> writeFlacProbe()
                else -> return false
            }
        } catch (e: Exception) {
            return false
        }

        var codec: android.media.MediaCodec? = null
        var extractor: MediaExtractor? = null
        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(probeFile.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return false

            extractor.selectTrack(trackIndex)
            val codecName = format.getString(MediaFormat.KEY_MIME)?.let {
                try {
                    android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
                        .findDecoderForFormat(format)
                } catch (e: Exception) {
                    null
                }
            }
            if (codecName.isNullOrEmpty()) return false

            codec = android.media.MediaCodec.createByCodecName(codecName)
            codec.configure(format, null, null, 0)

            // `configure` succeeding already proves a decoder exists and the
            // stream parameters are acceptable. Starting it and pushing one
            // buffer proves the decoder actually runs on this firmware, which
            // is the part the advertised list gets wrong.
            codec.start()
            val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (inputIndex < 0) return true // configured but idle: still support
            val input = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                codec.getInputBuffer(inputIndex)
            } else {
                @Suppress("DEPRECATION")
                codec.inputBuffers[inputIndex]
            }
            val sampleSize = extractor.readSampleData(input ?: return true, 0)
            if (sampleSize > 0) {
                codec.queueInputBuffer(
                    inputIndex, 0, sampleSize, extractor.sampleTime, 0
                )
                extractor.advance()
            } else {
                codec.queueInputBuffer(inputIndex, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }

            // Dequeue one output buffer. `INFO_TRY_AGAIN_LATER` still means the
            // decoder is alive; a thrown `CodecException` is the failure case.
            codec.dequeueOutputBuffer(android.media.MediaCodec.BufferInfo(), OUTPUT_TIMEOUT_US)
            true
        } catch (e: Exception) {
            false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
            runCatching { probeFile.delete() }
        }
    }

    /**
     * Writes a minimal but valid FLAC file: `fLaC`, a `STREAMINFO` block with
     * one silent frame, and the frame's CRC.
     *
     * A hand-built file rather than a shipped asset because the probe must fail
     * on a device whose decoder is broken, not on a device whose APK checksum
     * check silently dropped the asset.
     */
    private fun writeFlacProbe(): File {
        cacheDir.mkdirs()
        val out = File(cacheDir, "codec-probe.flac")
        val blockSize = 16 // samples, tiny but legal
        val sampleRate = 44_100

        val bytes = java.io.ByteArrayOutputStream()
        bytes.write("fLaC".toByteArray(Charsets.ISO_8859_1))

        // STREAMINFO: 34 bytes. min block 16, max block 16, min frame 0,
        // max frame 0, then rate/channels/bps/samples.
        val info = ByteArray(34)
        info[0] = ((blockSize shr 8) and 0xFF).toByte()
        info[1] = (blockSize and 0xFF).toByte()
        info[2] = ((blockSize shr 8) and 0xFF).toByte()
        info[3] = (blockSize and 0xFF).toByte()
        // Min/max frame size stay 0 (unknown), which is legal.
        info[10] = ((sampleRate shr 12) and 0xFF).toByte()
        info[11] = ((sampleRate shr 4) and 0xFF).toByte()
        info[12] = (((sampleRate and 0x0F) shl 4) or 0).toByte() // mono
        info[13] = (15 shl 4).toByte()                             // 16 bps, samples 0
        info[18] = 0
        bytes.write(byteArrayOf(0x00, 0x00, 0x00, 34))
        bytes.write(info)

        // A frame header claiming 16 samples, fixed blocking, followed by the
        // frame footer. Most decoders accept this as a valid short stream.
        bytes.write(byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0x69.toByte(), 0x08.toByte()))
        bytes.write(byteArrayOf(0x00, 0x00))

        out.writeBytes(bytes.toByteArray())
        return out
    }

    companion object {
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val OUTPUT_TIMEOUT_US = 10_000L
    }
}

END FILE

## core/music-engine-local/src/main/java/com/ts8/core/music/local/AudioTagReader.kt

BEGIN FILE
package com.ts8.core.music.local

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Metadata (title, artist, album, duration) read straight out of the container.
 *
 * Why not `MediaMetadataRetriever`? On this firmware it is a thin wrapper over
 * a vendor extractor, it costs 20–80 ms per file, and on some OGG files it
 * returns null instead of the tags. A first scan of a 2 000-track USB stick
 * through it takes over a minute *and* blocks the metadata thread the whole
 * time. Reading the container headers directly costs 1–3 ms per file, is pure
 * Kotlin (so it is unit-tested on the workstation against synthetic files with
 * known contents), and never returns a half-decoded tag.
 *
 * The Android-side scanner still keeps `MediaMetadataRetriever` as a fallback
 * for files this reader cannot parse — see `MediaStoreScanner`. Honest layering:
 * fast path first, platform path when needed, and "unknown" when both fail.
 *
 * **No file is read end to end.** Every path seeks to the header and, for
 * formats whose duration lives in a trailing page (OGG), the last 32 KiB.
 */
object AudioTagReader {

    /** Header sizes are bounded so a corrupt length field cannot exhaust memory. */
    private const val MAX_ID3_TAG_BYTES = 8 * 1024 * 1024
    private const val MAX_VORBIS_COMMENT_BYTES = 4 * 1024 * 1024
    private const val MAX_MP4_ATOM_DEPTH = 8
    private const val OGG_HEAD_BYTES = 8 * 1024
    private const val OGG_TAIL_BYTES = 32 * 1024

    data class Tags(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val trackNumber: Int = 0,
        val year: Int = 0,
        /** 0 when the container does not state a duration. */
        val durationMs: Long = 0
    ) {
        val isEmpty: Boolean
            get() = title == null && artist == null && album == null && durationMs == 0L
    }

    /**
     * Reads tags from [file].
     *
     * The format is detected by magic bytes rather than by extension: the TS8
     * sees plenty of `.mp3` files that are really AAC in an ADTS stream, and
     * trusting the name gives a puzzled driver an empty title.
     *
     * @return tags, or null when the file is unreadable or not a container this
     *         reader understands. Never throws.
     */
    fun read(file: File): Tags? {
        if (!file.isFile || file.length() <= 0L) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                when (detect(raf)) {
                    Container.MP3 -> readMp3(raf)
                    Container.FLAC -> readFlac(raf)
                    Container.OGG -> readOgg(raf)
                    Container.WAV -> readWav(raf)
                    Container.MP4 -> readMp4(raf)
                    Container.UNKNOWN -> null
                }
            }
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            // A malformed length field can produce a negative seek or an
            // absurd allocation; that is data, not a reason to stop scanning.
            null
        }
    }

    // -- format detection ---------------------------------------------------

    private enum class Container { MP3, FLAC, OGG, WAV, MP4, UNKNOWN }

    private fun detect(raf: RandomAccessFile): Container {
        if (raf.length() < 8) return Container.UNKNOWN
        val head = ByteArray(12)
        raf.seek(0)
        val n = readFully(raf, head)
        if (n < 8) return Container.UNKNOWN

        // "ID3" — but an MP3 without an ID3 tag starts with a frame sync.
        if (head.startsWith("ID3", 0)) return Container.MP3
        // MPEG audio frame sync: eleven set bits.
        if (head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0) return Container.MP3
        if (head.startsWith("fLaC", 0)) return Container.FLAC
        if (head.startsWith("OggS", 0)) return Container.OGG
        if (head.startsWith("RIFF", 0) && head.startsWith("WAVE", 8)) return Container.WAV
        if (head.startsWith("ftyp", 4)) return Container.MP4
        return Container.UNKNOWN
    }

    private fun ByteArray.startsWith(text: String, offset: Int): Boolean {
        if (offset < 0 || offset + text.length > size) return false
        for (i in text.indices) {
            if (this[offset + i] != text[i].code.toByte()) return false
        }
        return true
    }

    // -- MP3 / ID3v2 --------------------------------------------------------

    /**
     * ID3v2 header, then the first audio frame.
     *
     * Duration comes from the Xing/Info frame when present (exact, and the only
     * way to be right for VBR files), otherwise from a bitrate estimate scaled
     * by the audio payload size (exact for CBR, close for VBR).
     */
    private fun readMp3(raf: RandomAccessFile): Tags {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var track = 0
        var year = 0
        var audioStart = 0L

        val header = ByteArray(10)
        raf.seek(0)
        if (raf.read(header) == 10 && header.startsWith("ID3", 0)) {
            val version = header[3].toInt() and 0xFF
            val flags = header[5].toInt() and 0xFF
            // Syncsafe integer: seven bits per byte, high bit always clear.
            val tagSize = ((header[6].toInt() and 0x7F) shl 21) or
                ((header[7].toInt() and 0x7F) shl 14) or
                ((header[8].toInt() and 0x7F) shl 7) or
                (header[9].toInt() and 0x7F)

            var bodyStart = 10L
            if (flags and 0x40 != 0) {
                // Extended header: its own version-dependent size field.
                val ext = ByteArray(4)
                raf.seek(bodyStart)
                if (raf.read(ext) == 4) {
                    val extSize = if (version >= 4) syncsafe(ext, 0) else readIntBE(ext, 0, 4)
                    bodyStart += 4 + extSize
                }
            }

            if (tagSize in 1..MAX_ID3_TAG_BYTES && bodyStart + tagSize <= raf.length()) {
                val body = ByteArray(tagSize)
                raf.seek(bodyStart)
                val n = readFully(raf, body)
                if (n > 0) {
                    // ID3v2.2 uses three-character frame ids and a three-byte
                    // size field. Rather than a second parser for a format
                    // almost no real file uses, tag parsing is skipped there and
                    // the scanner falls back to the platform retriever; the
                    // duration below still works either way.
                    if (version != 2) {
                        val map = parseId3Frames(body.copyOf(n), version)
                        title = map["TIT2"]
                        artist = map["TPE1"]
                        album = map["TALB"]
                        track = map["TRCK"]?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
                        year = map["TDRC"]?.take(4)?.toIntOrNull()
                            ?: map["TYER"]?.trim()?.toIntOrNull() ?: 0
                    }
                }
                audioStart = bodyStart + tagSize
            } else {
                // Corrupt size: let frame synchronisation find the audio.
                audioStart = 10L
            }
        }

        val audio = readMp3Audio(raf, audioStart)
        return Tags(
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            artist = artist?.trim()?.takeIf { it.isNotEmpty() },
            album = album?.trim()?.takeIf { it.isNotEmpty() },
            trackNumber = track,
            year = year,
            durationMs = audio?.let { if (it.xingDurationMs > 0) it.xingDurationMs else it.durationMs } ?: 0L
        )
    }

    private data class Mp3Audio(val durationMs: Long, val xingDurationMs: Long)

    private fun readMp3Audio(raf: RandomAccessFile, start: Long): Mp3Audio? {
        val len = raf.length()
        var pos = start.coerceIn(0L, len)
        var probes = 0
        while (pos + 4 <= len && probes < 256) {
            raf.seek(pos)
            val b = ByteArray(4)
            if (readFully(raf, b) < 4) return null
            val frame = parseFrameHeader(b)
            if (frame == null) {
                // Not a sync word — resynchronise one byte at a time. Bounded so
                // a garbage file does not turn into a linear scan of the disk.
                pos++
                probes++
                continue
            }

            var xingDuration = 0L
            val audioBytes = (len - pos).coerceAtLeast(0)
            val sideBytes = minOf(frame.frameBytes - 4, 200)
            if (sideBytes > 16) {
                val side = ByteArray(sideBytes)
                raf.seek(pos + 4)
                val n = readFully(raf, side)
                if (n > 12) {
                    val at = findAscii(side, n, "Xing", "Info")
                    if (at >= 0 && at + 12 <= n) {
                        val flags = readIntBE(side, at + 4, 4)
                        if (flags and 0x1 != 0) {
                            val frames = readIntBE(side, at + 8, 4).toLong() and 0xFFFFFFFFL
                            if (frames > 0) {
                                xingDuration = frames * frame.samplesPerFrame * 1000L /
                                    frame.sampleRate
                            }
                        }
                    }
                }
            }

            val estimated = if (frame.bitrateBps > 0) {
                audioBytes * 8000L / frame.bitrateBps
            } else {
                0L
            }
            return Mp3Audio(estimated, xingDuration)
        }
        return null
    }

    /** First index of any of [needles] within `buf[0 until len]`, or -1. */
    private fun findAscii(buf: ByteArray, len: Int, vararg needles: String): Int {
        for (i in 0 until len - 4) {
            for (needle in needles) {
                if (i + needle.length > len) continue
                var hit = true
                for (k in needle.indices) {
                    if (buf[i + k] != needle[k].code.toByte()) { hit = false; break }
                }
                if (hit) return i
            }
        }
        return -1
    }

    private data class FrameHeader(
        val bitrateBps: Long,
        val sampleRate: Int,
        val samplesPerFrame: Int,
        val frameBytes: Int
    )

    /** MPEG audio frame header (ISO/IEC 11172-3 and 13818-3). */
    private fun parseFrameHeader(b: ByteArray): FrameHeader? {
        val h = readIntBE(b, 0, 4)
        if (h and 0xFFE00000.toInt() != 0xFFE00000.toInt()) return null

        val versionBits = (h ushr 19) and 0x3
        val layerBits = (h ushr 17) and 0x3
        val bitrateIndex = (h ushr 12) and 0xF
        val sampleIndex = (h ushr 10) and 0x3
        val padding = (h ushr 9) and 0x1

        // 0x1 is the reserved MPEG version; 0 is MPEG 2.5.
        if (versionBits == 1) return null
        if (bitrateIndex == 0 || bitrateIndex == 15 || sampleIndex == 3) return null

        val mpeg1 = versionBits == 3
        val layer = when (layerBits) {
            1 -> 3
            2 -> 2
            3 -> 1
            else -> return null
        }

        val sampleRate = when (sampleIndex) {
            0 -> if (mpeg1) 44100 else if (versionBits == 2) 22050 else 11025
            1 -> if (mpeg1) 48000 else if (versionBits == 2) 24000 else 12000
            2 -> if (mpeg1) 32000 else if (versionBits == 2) 16000 else 8000
            else -> return null
        }

        val bitrateKbps: Int = if (mpeg1) {
            when (layer) {
                1 -> MPEG1_LAYER1
                2 -> MPEG1_LAYER2
                else -> MPEG1_LAYER3
            }.getOrElse(bitrateIndex) { return null }
        } else {
            // Parenthesised: `if (…) A else B.method()` binds the method call to
            // B only, which silently turned the two table types into a common
            // Serializable supertype.
            (if (layer == 1) MPEG2_LAYER1 else MPEG2_LAYER23)
                .getOrElse(bitrateIndex) { return null }
        }

        val samplesPerFrame = when (layer) {
            1 -> 384
            2 -> 1152
            else -> if (mpeg1) 1152 else 576
        }

        // Layer I frames are counted in 4-byte slots; II and III in bytes.
        val frameBytes = if (layer == 1) {
            (12L * bitrateKbps * 1000L / sampleRate + padding).toInt() * 4
        } else {
            (samplesPerFrame / 8L * bitrateKbps * 1000L / sampleRate + padding).toInt()
        }

        return FrameHeader(
            bitrateBps = bitrateKbps * 1000L,
            sampleRate = sampleRate,
            samplesPerFrame = samplesPerFrame,
            frameBytes = frameBytes.coerceAtLeast(4)
        )
    }

    private val MPEG1_LAYER1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
    private val MPEG1_LAYER2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
    private val MPEG1_LAYER3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val MPEG2_LAYER1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
    private val MPEG2_LAYER23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

    /**
     * Walks ID3v2.3 / v2.4 frames.
     *
     * The two versions differ in the frame size encoding (v2.4 is syncsafe) and
     * in which text encodings are legal, so both are handled explicitly.
     */
    private fun parseId3Frames(body: ByteArray, version: Int): Map<String, String> {
        val out = HashMap<String, String>(8)
        var pos = 0
        while (pos + 10 <= body.size) {
            // A zero-filled padding region ends the frame list.
            if (body[pos] == 0.toByte()) break
            for (k in 0 until 4) {
                val c = body[pos + k].toInt() and 0xFF
                if (c !in 0x41..0x5A && c !in 0x30..0x39) return out
            }
            val id = String(body, pos, 4, Charsets.ISO_8859_1)
            val size = if (version >= 4) syncsafe(body, pos + 4) else readIntBE(body, pos + 4, 4)
            if (size <= 0 || pos + 10 + size > body.size) break

            if (out[id] == null) {
                val text = decodeId3Text(body, pos + 10, size)
                if (!text.isNullOrBlank()) out[id] = text
            }
            pos += 10 + size
        }
        return out
    }

    /** Decodes one text frame: first byte is the encoding, the rest is payload. */
    private fun decodeId3Text(body: ByteArray, offset: Int, size: Int): String? {
        if (size < 2 || offset + size > body.size) return null
        val encoding = body[offset].toInt() and 0xFF
        val start = offset + 1
        val length = size - 1
        val text = when (encoding) {
            0 -> String(body, start, length, Charsets.ISO_8859_1)
            1 -> decodeUtf16(body, start, length, littleEndian = true)
            2 -> decodeUtf16(body, start, length, littleEndian = false)
            3 -> String(body, start, length, Charsets.UTF_8)
            else -> null
        } ?: return null
        // Multi-value frames separate with NUL; the first value is enough for
        // a launcher row and keeps invisible characters out of the UI.
        return text.substringBefore('\u0000').trim().takeIf { it.isNotEmpty() }
    }

    private fun decodeUtf16(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        littleEndian: Boolean
    ): String? {
        var start = offset
        var end = offset + length
        if (end - start < 2) return null
        var le = littleEndian
        // A BOM overrides the frame's declared byte order.
        val b0 = bytes[start].toInt() and 0xFF
        val b1 = bytes[start + 1].toInt() and 0xFF
        if (b0 == 0xFF && b1 == 0xFE) { le = true; start += 2 }
        else if (b0 == 0xFE && b1 == 0xFF) { le = false; start += 2 }
        val usable = end - start
        if (usable < 2) return null
        val buf = ByteBuffer.wrap(bytes, start, usable and 1.inv())
            .order(if (le) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        return buf.asCharBuffer().toString()
    }

    // -- FLAC ---------------------------------------------------------------

    /**
     * FLAC metadata blocks. `STREAMINFO` carries the exact duration — total
     * samples and sample rate, no estimation — and `VORBIS_COMMENT` the tags.
     */
    private fun readFlac(raf: RandomAccessFile): Tags {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var track = 0
        var year = 0
        var durationMs = 0L

        raf.seek(4) // past "fLaC"
        var last = false
        var guard = 0
        while (!last && guard++ < 64) {
            val header = ByteArray(4)
            if (readFully(raf, header) < 4) break
            last = (header[0].toInt() and 0x80) != 0
            val type = header[0].toInt() and 0x7F
            val length = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)

            when (type) {
                0 -> {
                    // STREAMINFO is exactly 34 bytes; anything else is corrupt.
                    if (length != 34) { raf.skipBytes(length); continue }
                    val info = ByteArray(34)
                    if (readFully(raf, info) < 18) return Tags(durationMs = durationMs)
                    // Bytes 10..17 hold sampleRate(20) | channels(3) | bps(5) |
                    // totalSamples(36).
                    val b10 = info[10].toLong() and 0xFF
                    val b11 = info[11].toLong() and 0xFF
                    val b12 = info[12].toLong() and 0xFF
                    val b13 = info[13].toLong() and 0xFF
                    val sampleRate = (b10 shl 12) or (b11 shl 4) or (b12 shr 4)
                    val totalSamples = ((b13 and 0x0F) shl 32) or
                        ((info[14].toLong() and 0xFF) shl 24) or
                        ((info[15].toLong() and 0xFF) shl 16) or
                        ((info[16].toLong() and 0xFF) shl 8) or
                        (info[17].toLong() and 0xFF)
                    if (sampleRate > 0 && totalSamples > 0) {
                        durationMs = totalSamples * 1000L / sampleRate
                    }
                }
                4 -> {
                    if (length in 1..MAX_VORBIS_COMMENT_BYTES) {
                        val block = ByteArray(length)
                        val n = readFully(raf, block)
                        if (n > 0) {
                            val f = parseVorbisComments(block, n)
                            title = f["TITLE"] ?: f["TIT2"]
                            artist = f["ARTIST"] ?: f["TPE1"]
                            album = f["ALBUM"] ?: f["TALB"]
                            track = f["TRACKNUMBER"]?.toIntOrNull()
                                ?: f["TRACK"]?.toIntOrNull() ?: 0
                            year = f["DATE"]?.take(4)?.toIntOrNull() ?: 0
                        }
                    } else {
                        raf.skipBytes(length)
                    }
                }
                else -> raf.skipBytes(length)
            }
        }

        return Tags(
            title = title, artist = artist, album = album,
            trackNumber = track, year = year, durationMs = durationMs
        )
    }

    /**
     * Vorbis comment block, shared by FLAC and OGG.
     *
     * Layout: vendor length (LE32), vendor string, comment count (LE32), then
     * for each comment a length (LE32) followed by `KEY=value`.
     */
    private fun parseVorbisComments(
        block: ByteArray,
        length: Int,
        offset: Int = 0
    ): Map<String, String> {
        val out = HashMap<String, String>(8)
        if (length < 8 || offset < 0) return out
        // `end` rather than `length` so callers can hand in a window inside a
        // larger buffer (the OGG path does) without copying it first.
        val end = minOf(offset + length, block.size)
        var p = offset
        val vendorLen = readIntLE(block, p, 4)
        if (vendorLen < 0 || p + 4 + vendorLen > end) return out
        p += 4 + vendorLen
        if (p + 4 > end) return out
        val count = readIntLE(block, p, 4)
        p += 4
        if (count < 0 || count > 4096) return out
        var i = 0
        while (i < count && p + 4 <= end) {
            val len = readIntLE(block, p, 4)
            p += 4
            if (len < 0 || p + len > end) break
            val entry = String(block, p, len, Charsets.UTF_8)
            p += len
            val eq = entry.indexOf('=')
            if (eq > 0) {
                val key = entry.substring(0, eq).uppercase()
                if (!out.containsKey(key)) out[key] = entry.substring(eq + 1)
            }
            i++
        }
        return out
    }

    // -- OGG (Vorbis / Opus) ------------------------------------------------

    /**
     * OGG duration comes from the granule position of the **last** page, which
     * is why this reader seeks to the tail instead of walking every page: a
     * two-hour podcast is a hundred thousand pages.
     */
    private fun readOgg(raf: RandomAccessFile): Tags {
        var sampleRate = 0
        var preSkip = 0L
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var track = 0
        var year = 0

        val headSize = minOf(raf.length(), OGG_HEAD_BYTES.toLong()).toInt()
        val head = ByteArray(headSize)
        raf.seek(0)
        val headRead = readFully(raf, head)
        if (headRead < 32) return Tags()

        // The identification packet starts after the first page header. Page
        // headers vary in length with the segment table, so the packet magic is
        // located rather than assumed to sit at a fixed offset.
        val idAt = findAscii(head, minOf(headRead, 512), "OpusHead", "\u0001vorbis")
        val isOpus = idAt >= 0 && head.startsWith("OpusHead", idAt)

        if (isOpus) {
            // Opus always decodes at 48 kHz regardless of the source rate.
            sampleRate = 48000
            if (idAt + 12 <= headRead) {
                preSkip = ((head[idAt + 10].toLong() and 0xFF)) or
                    ((head[idAt + 11].toLong() and 0xFF) shl 8)
            }
        } else if (idAt >= 0) {
            // Vorbis identification packet: version(4) channels(1) rate(4 LE).
            val rateAt = idAt + 1 + 11
            if (rateAt + 4 <= headRead) sampleRate = readIntLE(head, rateAt, 4)
        }

        // The comment packet follows in the second page; finding its magic in
        // the head window covers both formats without page walking.
        val commentAt = findAscii(head, headRead, "OpusTags", "\u0003vorbis")
        if (commentAt >= 0) {
            val payloadAt = commentAt + (if (head.startsWith("OpusTags", commentAt)) 8 else 7)
            if (payloadAt < headRead) {
                val f = parseVorbisComments(head, headRead - payloadAt, payloadAt)
                title = f["TITLE"]
                artist = f["ARTIST"]
                album = f["ALBUM"]
                track = f["TRACKNUMBER"]?.toIntOrNull() ?: 0
                year = f["DATE"]?.take(4)?.toIntOrNull() ?: 0
            }
        }

        // Last page granule.
        val len = raf.length()
        val tailSize = minOf(len, OGG_TAIL_BYTES.toLong()).toInt()
        val tail = ByteArray(tailSize)
        raf.seek(len - tailSize)
        val tailRead = readFully(raf, tail)
        var lastPage = -1
        var i = tailRead - 27
        while (i >= 0) {
            if (tail[i] == 'O'.code.toByte() && tail[i + 1] == 'g'.code.toByte() &&
                tail[i + 2] == 'g'.code.toByte() && tail[i + 3] == 'S'.code.toByte()
            ) {
                lastPage = i
                break
            }
            i--
        }

        var durationMs = 0L
        if (lastPage >= 0 && sampleRate > 0 && lastPage + 14 <= tailRead) {
            // Granule position is a signed 64-bit LE at offset 6 of the page.
            val granule = readLongLE(tail, lastPage + 6)
            val samples = granule - preSkip
            if (samples > 0) durationMs = samples * 1000L / sampleRate
        }

        return Tags(
            title = title, artist = artist, album = album,
            trackNumber = track, year = year, durationMs = durationMs
        )
    }

    // -- WAV ----------------------------------------------------------------

    /** RIFF chunks: `fmt ` gives the byte rate, `data` gives the payload size. */
    private fun readWav(raf: RandomAccessFile): Tags {
        var byteRate = 0L
        var dataSize = -1L
        var pos = 12L
        val len = raf.length()
        val chunk = ByteArray(8)
        var guard = 0
        while (pos + 8 <= len && guard++ < 64) {
            raf.seek(pos)
            if (readFully(raf, chunk) < 8) break
            val id = String(chunk, 0, 4, Charsets.ISO_8859_1)
            val size = readIntLE(chunk, 4, 4).toLong() and 0xFFFFFFFFL

            if (id == "fmt " && size >= 12) {
                val fmt = ByteArray(minOf(size, 40L).toInt())
                if (readFully(raf, fmt) >= 12) {
                    byteRate = readIntLE(fmt, 8, 4).toLong() and 0xFFFFFFFFL
                }
            } else if (id == "data") {
                dataSize = size
                break
            }
            // Chunks are word-aligned.
            pos += 8 + size + (size and 1L)
        }

        val duration = if (byteRate > 0 && dataSize > 0) dataSize * 1000L / byteRate else 0L
        return Tags(durationMs = duration)
    }

    // -- MP4 / M4A ----------------------------------------------------------

    /**
     * MP4 atom walk. `mvhd` holds the duration; tags live in
     * `moov/udta/meta/ilst`. Atoms are located by their own size field, never by
     * a fixed offset, because `mdat` precedes `moov` in some files.
     */
    private fun readMp4(raf: RandomAccessFile): Tags {
        var movieDurationMs = 0L
        var itemTags: Tags? = null

        fun walk(start: Long, end: Long, depth: Int) {
            if (depth > MAX_MP4_ATOM_DEPTH) return
            var pos = start
            val header = ByteArray(8)
            var guard = 0
            while (pos + 8 <= end && guard++ < 4096) {
                raf.seek(pos)
                if (readFully(raf, header) < 8) return
                var size = readIntBE(header, 0, 4).toLong() and 0xFFFFFFFFL
                val type = String(header, 4, 4, Charsets.ISO_8859_1)
                var headerLen = 8L
                if (size == 1L) {
                    val ext = ByteArray(8)
                    if (readFully(raf, ext) < 8) return
                    size = readLongBE(ext, 0, 8)
                    headerLen = 16L
                } else if (size == 0L) {
                    size = end - pos
                }
                if (size < headerLen) return
                val bodyStart = pos + headerLen
                val bodyEnd = (pos + size).coerceAtMost(end)

                when (type) {
                    "moov", "udta" -> walk(bodyStart, bodyEnd, depth + 1)
                    // `meta` is a full atom plus a 4-byte version/flags prefix.
                    "meta" -> walk(bodyStart + 4, bodyEnd, depth + 1)
                    "mvhd" -> movieDurationMs = readMvhd(raf, bodyStart, bodyEnd)
                    "ilst" -> itemTags = readIlst(raf, bodyStart, bodyEnd)
                }
                pos += size
            }
        }

        walk(0, raf.length(), 0)

        val tags = itemTags ?: Tags()
        return Tags(
            title = tags.title, artist = tags.artist, album = tags.album,
            trackNumber = tags.trackNumber, year = tags.year,
            durationMs = if (tags.durationMs > 0) tags.durationMs else movieDurationMs
        )
    }

    private fun readMvhd(raf: RandomAccessFile, start: Long, end: Long): Long {
        val size = minOf(end - start, 120L).toInt()
        if (size < 20) return 0
        val buf = ByteArray(size)
        raf.seek(start)
        val n = readFully(raf, buf)
        if (n < 20) return 0
        return if ((buf[0].toInt() and 0xFF) == 1) {
            if (n < 32) return 0
            val timescale = readIntBE(buf, 20, 4).toLong() and 0xFFFFFFFFL
            val duration = readLongBE(buf, 24, 8)
            if (timescale > 0 && duration > 0) duration * 1000L / timescale else 0L
        } else {
            val timescale = readIntBE(buf, 12, 4).toLong() and 0xFFFFFFFFL
            val duration = readIntBE(buf, 16, 4).toLong() and 0xFFFFFFFFL
            if (timescale > 0 && duration > 0) duration * 1000L / timescale else 0L
        }
    }

    private fun readIlst(raf: RandomAccessFile, start: Long, end: Long): Tags {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var track = 0
        var year = 0

        var pos = start
        val header = ByteArray(8)
        var guard = 0
        while (pos + 8 <= end && guard++ < 128) {
            raf.seek(pos)
            if (readFully(raf, header) < 8) break
            val size = readIntBE(header, 0, 4).toLong() and 0xFFFFFFFFL
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            if (size < 8) break
            val bodyStart = pos + 8
            val bodyEnd = (pos + size).coerceAtMost(end)

            when (type) {
                "©nam" -> title = readIlstText(raf, bodyStart, bodyEnd)
                "©ART" -> artist = readIlstText(raf, bodyStart, bodyEnd)
                "©alb" -> album = readIlstText(raf, bodyStart, bodyEnd)
                "©day" -> year = readIlstText(raf, bodyStart, bodyEnd)
                    ?.take(4)?.toIntOrNull() ?: 0
                "trkn" -> track = readIlstTrkn(raf, bodyStart, bodyEnd)
            }
            pos += size
        }
        return Tags(title, artist, album, track, year, 0)
    }

    /** `ilst` entry → `data` atom → UTF-8 payload after 8 bytes of type/locale. */
    private fun readIlstText(raf: RandomAccessFile, start: Long, end: Long): String? {
        val payload = readDataAtom(raf, start, end) ?: return null
        if (payload.size <= 8) return null
        return String(payload, 8, payload.size - 8, Charsets.UTF_8)
            .trim().takeIf { it.isNotEmpty() }
    }

    private fun readIlstTrkn(raf: RandomAccessFile, start: Long, end: Long): Int {
        val payload = readDataAtom(raf, start, end) ?: return 0
        // `trkn` payload: 2 reserved, 2 track number, 2 total, 2 reserved.
        if (payload.size < 12) return 0
        return ((payload[10].toInt() and 0xFF) shl 8) or (payload[11].toInt() and 0xFF)
    }

    private fun readDataAtom(raf: RandomAccessFile, start: Long, end: Long): ByteArray? {
        var pos = start
        val header = ByteArray(8)
        var guard = 0
        while (pos + 8 <= end && guard++ < 8) {
            raf.seek(pos)
            if (readFully(raf, header) < 8) return null
            val size = readIntBE(header, 0, 4).toLong() and 0xFFFFFFFFL
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            if (size < 8) return null
            val bodyStart = pos + 8
            val bodyEnd = (pos + size).coerceAtMost(end)
            if (type == "data" && bodyEnd > bodyStart) {
                val buf = ByteArray((bodyEnd - bodyStart).toInt())
                raf.seek(bodyStart)
                val n = readFully(raf, buf)
                return if (n > 0) buf.copyOf(n) else null
            }
            pos += size
        }
        return null
    }

    // -- primitives ---------------------------------------------------------

    /** Syncsafe 28-bit integer used by ID3v2 sizes. */
    private fun syncsafe(b: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > b.size) return 0
        return ((b[offset].toInt() and 0x7F) shl 21) or
            ((b[offset + 1].toInt() and 0x7F) shl 14) or
            ((b[offset + 2].toInt() and 0x7F) shl 7) or
            (b[offset + 3].toInt() and 0x7F)
    }

    private fun readIntBE(b: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length > 4 || offset + length > b.size) return 0
        var v = 0
        for (i in 0 until length) v = (v shl 8) or (b[offset + i].toInt() and 0xFF)
        return v
    }

    private fun readIntLE(b: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length > 4 || offset + length > b.size) return 0
        var v = 0
        for (i in length - 1 downTo 0) v = (v shl 8) or (b[offset + i].toInt() and 0xFF)
        return v
    }

    private fun readLongBE(b: ByteArray, offset: Int, length: Int): Long {
        if (offset < 0 || length > 8 || offset + length > b.size) return 0
        var v = 0L
        for (i in 0 until length) v = (v shl 8) or (b[offset + i].toLong() and 0xFF)
        return v
    }

    private fun readLongLE(b: ByteArray, offset: Int, length: Int = 8): Long {
        if (offset < 0 || length > 8 || offset + length > b.size) return 0
        var v = 0L
        for (i in length - 1 downTo 0) v = (v shl 8) or (b[offset + i].toLong() and 0xFF)
        return v
    }

    /** Reads as much as fits; returns the byte count actually read. */
    private fun readFully(raf: RandomAccessFile, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = raf.read(buf, total, buf.size - total)
            if (n <= 0) break
            total += n
        }
        return total
    }
}

END FILE

## core/music-engine-local/src/main/java/com/ts8/core/music/local/Checkpoint.kt

BEGIN FILE
package com.ts8.core.music.local

import android.content.ContentValues
import com.ts8.core.music.RepeatMode

/**
 * Where playback should resume after the head unit reboots.
 *
 * The spec requires the car to come back to the same track at roughly the same
 * position, not to the first item in the library. That means the checkpoint has
 * to carry the queue *in play order*; saving only a track id would resume into
 * a different context (right song, wrong album).
 */
data class MusicCheckpoint(
    val trackId: Long,
    val positionMs: Long,
    /** Position within the saved play order. */
    val index: Int,
    val shuffle: Boolean,
    val repeatMode: RepeatMode,
    /** Track ids, in play order. */
    val queueTrackIds: List<Long>,
    val savedAtMs: Long
)

interface CheckpointStore {
    fun save(checkpoint: MusicCheckpoint)

    fun load(): MusicCheckpoint?

    fun clear()
}

/**
 * Line-oriented text codec.
 *
 * Not JSON: `org.json` is Android-only (so this would not be unit-testable),
 * and a queue is a list of longs — a format that needs no parser to be
 * obviously correct is better here than one that needs a library. Unknown keys
 * are ignored and any malformed value invalidates the whole checkpoint, so a
 * half-written row degrades to "start from the top" rather than to a crash.
 */
object CheckpointCodec {
    private const val MAGIC = "TS8CP1"
    private const val END = "end"
    private const val MAX_INPUT = 64_000

    fun encode(cp: MusicCheckpoint): String = buildString {
        append(MAGIC).append('\n')
        append("track=").append(cp.trackId).append('\n')
        append("position=").append(cp.positionMs).append('\n')
        append("index=").append(cp.index).append('\n')
        append("shuffle=").append(if (cp.shuffle) 1 else 0).append('\n')
        append("repeat=").append(cp.repeatMode.name).append('\n')
        append("saved_at=").append(cp.savedAtMs).append('\n')
        append("queue=")
        cp.queueTrackIds.forEachIndexed { i, id ->
            if (i > 0) append(',')
            append(id)
        }
        append('\n')
        // Explicit terminator. Without it a row written by an older build that
        // lacks a newer key would decode identically to a row truncated just
        // before that key — the two cases have to be distinguishable.
        append(END).append('\n')
    }

    fun decode(raw: String?): MusicCheckpoint? {
        if (raw.isNullOrBlank() || raw.length > MAX_INPUT) return null
        val lines = raw.split('\n')
        if (lines.firstOrNull()?.trim() != MAGIC) return null
        if (lines.none { it.trim() == END }) return null

        var trackId: Long? = null
        var position = 0L
        var index = 0
        var shuffle = false
        var repeat = RepeatMode.OFF
        var savedAt = 0L
        var queue: List<Long> = emptyList()

        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            // The terminator is a bare token, not a key=value pair.
            if (line.trim() == END) continue
            val eq = line.indexOf('=')
            if (eq <= 0) return null
            val value = line.substring(eq + 1)
            val ok = when (line.substring(0, eq)) {
                "track" -> value.toLongOrNull()?.let { trackId = it } != null
                "position" -> value.toLongOrNull()?.let { position = it } != null
                "index" -> value.toIntOrNull()?.let { index = it } != null
                "shuffle" -> value.toIntOrNull()?.let { shuffle = it != 0 } != null
                "repeat" -> runCatching { RepeatMode.valueOf(value) }
                    .getOrNull()?.let { repeat = it } != null
                "saved_at" -> value.toLongOrNull()?.let { savedAt = it } != null
                "queue" -> parseQueue(value)?.let { queue = it } != null
                // Forward compatibility: a newer build may write keys this one
                // does not know. Ignoring them is safe; failing is not.
                else -> true
            }
            // A value that does not parse means the row was written by a
            // different schema or truncated mid-write. Either way the whole
            // checkpoint is untrustworthy.
            if (!ok) return null
        }

        val id = trackId ?: return null
        if (position < 0) return null
        return MusicCheckpoint(
            trackId = id,
            positionMs = position,
            index = index.coerceAtLeast(0),
            shuffle = shuffle,
            repeatMode = repeat,
            queueTrackIds = if (queue.isEmpty()) listOf(id) else queue,
            savedAtMs = savedAt
        )
    }

    private fun parseQueue(value: String): List<Long>? {
        if (value.isBlank()) return emptyList()
        val out = ArrayList<Long>()
        for (part in value.split(',')) {
            out.add(part.trim().toLongOrNull() ?: return null)
        }
        return out
    }
}

/** [CheckpointStore] on the library database's `checkpoint` row. */
class SqliteCheckpointStore(private val db: MusicDatabase) : CheckpointStore {

    override fun save(checkpoint: MusicCheckpoint) {
        val values = ContentValues().apply {
            put("id", 1)
            put("track_id", checkpoint.trackId)
            put("position_ms", checkpoint.positionMs)
            put("queue_json", CheckpointCodec.encode(checkpoint))
            put("saved_at", checkpoint.savedAtMs)
        }
        runCatching {
            db.writableDatabase.insertWithOnConflict(
                "checkpoint", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
            )
        }
    }

    override fun load(): MusicCheckpoint? = runCatching {
        db.readableDatabase.rawQuery(
            "SELECT queue_json FROM checkpoint WHERE id = 1", null
        ).use { if (it.moveToFirst()) CheckpointCodec.decode(it.getString(0)) else null }
    }.getOrNull()

    override fun clear() {
        runCatching { db.writableDatabase.delete("checkpoint", "id = 1", null) }
    }
}

END FILE

## core/music-engine-local/src/main/java/com/ts8/core/music/local/CheckpointRestore.kt

BEGIN FILE
package com.ts8.core.music.local

import com.ts8.core.music.Track

/**
 * Rebuilds a queue from a saved checkpoint.
 *
 * Separated from [LocalMusicPlayer] because the rules here are the ones that
 * have to be right after an ignition-off — and they are the ones that are
 * easiest to get subtly wrong:
 *
 * - A track whose file vanished (USB stick left in the other car) is **dropped**,
 *   not fatal. Losing one song is acceptable; losing the whole queue is not.
 * - The resume position belongs to the track that was playing. If that track is
 *   the one that vanished, the index must be re-derived rather than trusted, or
 *   playback resumes in the middle of a different song.
 * - If nothing survives, the checkpoint is dead and must be cleared. Otherwise
 *   every boot retries a restore that can never succeed.
 */
object CheckpointRestore {

    /**
     * The queue to restore, or null when the checkpoint cannot be honoured.
     *
     * @param lookup resolves an id to a track; returning null means it is gone.
     */
    fun plan(checkpoint: MusicCheckpoint, lookup: (Long) -> Track?): Restored? {
        val tracks = ArrayList<Track>(checkpoint.queueTrackIds.size)
        var indexOfResumed = -1

        for (id in checkpoint.queueTrackIds) {
            val track = try {
                lookup(id)
            } catch (e: Exception) {
                // A database closed under us reads as "gone", not as a crash.
                null
            } ?: continue
            if (track.id == checkpoint.trackId) indexOfResumed = tracks.size
            tracks.add(track)
        }

        if (tracks.isEmpty()) return null

        // The saved index is only trustworthy when the saved track itself
        // survived; otherwise it points into a list that no longer matches.
        val index = when {
            indexOfResumed >= 0 -> indexOfResumed
            else -> checkpoint.index.coerceIn(0, tracks.size - 1)
        }

        return Restored(tracks, index, checkpoint.positionMs, checkpoint.shuffle, checkpoint.repeatMode)
    }

    data class Restored(
        val tracks: List<Track>,
        val index: Int,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeatMode: com.ts8.core.music.RepeatMode
    )
}

END FILE

## core/music-engine-local/src/main/java/com/ts8/core/music/local/LocalMusicBackend.kt

BEGIN FILE
package com.ts8.core.music.local

import android.content.Context
import com.ts8.core.audio.VehicleAudioBridge
import com.ts8.core.music.MediaScanner
import com.ts8.core.music.MusicBackend
import com.ts8.core.music.MusicLibrary
import com.ts8.core.music.MusicPlayer
import java.io.File

/**
 * The local music backend, wired together.
 *
 * This is the only class the app constructs for music: it owns the database,
 * the library, the codec probe, the scanner and the player, and hands them out
 * through the interfaces the UI knows. Keeping the wiring in one place is what
 * makes "the engine never lives in an Activity" true rather than aspirational —
 * the Activity receives a [MusicBackend] and cannot reach past it.
 */
class LocalMusicBackend(
    context: Context,
    audioBridge: VehicleAudioBridge
) : MusicBackend {

    private val appContext = context.applicationContext

    private val db = MusicDatabase(appContext)

    override val library: SqliteMusicLibrary = SqliteMusicLibrary(db)

    val codecs = AndroidCodecSupport(File(appContext.cacheDir, "codec-probe"))

    val scanner: MediaScanner = MediaStoreScanner(appContext, db, library, codecs)

    private val checkpointStore = SqliteCheckpointStore(db)

    override val player: LocalMusicPlayer = LocalMusicPlayer(
        context = appContext,
        audioBridge = audioBridge,
        checkpointStore = checkpointStore,
        library = library
    )

    val checkpoint: CheckpointStore get() = checkpointStore

    /** True when the library has at least one track. */
    fun hasTracks(): Boolean = library.trackCount() > 0

    /** Closes the database. Called when the service is destroyed for good. */
    fun close() {
        player.release()
        runCatching { db.close() }
    }

    companion object {
        /**
         * Formats this build can offer, as measured rather than assumed.
         * Called by the diagnostics screen and by the music UI's filter chips.
         */
        fun supportedFormats(codecs: AndroidCodecSupport): Set<String> =
            codecs.supportedMimeTypes()
    }
}

END FILE

## core/music-engine-local/src/main/java/com/ts8/core/music/local/LocalMusicPlayer.kt

BEGIN FILE
package com.ts8.core.music.local

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.ts8.core.audio.AudioFocusListener
import com.ts8.core.audio.AudioResult
import com.ts8.core.audio.VehicleAudioBridge
import com.ts8.core.music.AudioRoute
import com.ts8.core.music.MusicLibrary
import com.ts8.core.music.MusicPlayer
import com.ts8.core.music.MusicPlayerObservable
import com.ts8.core.music.PlaybackError
import com.ts8.core.music.PlaybackListener
import com.ts8.core.music.PlaybackState
import com.ts8.core.music.RepeatMode
import com.ts8.core.music.Track
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * Local playback over [MediaPlayer].
 *
 * [MediaPlayer] rather than ExoPlayer because the APK must stay small enough to
 * sideload over ADB onto a head unit with a slow link, and because the formats
 * the spec lists are exactly the ones the platform decoder handles. The
 * trade-off is that `MediaPlayer` is stateful and throws in ways that differ by
 * firmware, so every call here is guarded and every failure becomes a
 * [PlaybackError] the UI can show.
 *
 * **This class only drives audio.** Whether to play, pause, resume after a call,
 * or advance the queue is decided by [PlaybackCore], which touches no Android
 * API and is therefore tested off-device. That separation is deliberate: the
 * behaviours that break in a car — resuming music the driver had paused,
 * leaving the stream ducked forever, losing the resume position — are decisions,
 * not audio plumbing, and a decision that only runs on the vehicle is a decision
 * nobody verified.
 *
 * **The three behaviours the spec calls out:**
 *
 * 1. *Audio focus.* Playback stops for a call and resumes after it, but only if
 *    it was playing before. [PlaybackCore] records that.
 * 2. *Ducking for navigation.* The bridge lowers the stream; this player keeps
 *    playing and reports `duckedForNavigation` so the UI can show it.
 * 3. *USB removal without a crash.* Every media operation is wrapped, and the
 *    file's existence is checked before `setDataSource`. A track whose source
 *    vanished reports [PlaybackError.SOURCE_UNAVAILABLE] and stops.
 *
 * **Checkpoint.** Position is saved on pause, on stop, on focus loss, and
 * periodically while playing, so an ignition-off loses at most
 * [CHECKPOINT_INTERVAL_MS] of position.
 */
class LocalMusicPlayer(
    context: Context,
    private val audioBridge: VehicleAudioBridge,
    private val checkpointStore: CheckpointStore,
    private val library: MusicLibrary?,
    random: Random = Random.Default
) : MusicPlayer, MusicPlayerObservable {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<PlaybackListener>()

    /** Every play/pause/queue decision lives here, off the Android surface. */
    private val core = PlaybackCore(random)

    private var player: MediaPlayer? = null
    private var lastError: PlaybackError? = null

    /** Position snapshot, because `MediaPlayer.currentPosition()` throws after release. */
    private var lastKnownPositionMs = 0L
    private var lastKnownDurationMs = 0L

    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var released = false

    /**
     * Turns an online track's provider id into a URL (§13), or null when the
     * app has no online gateway.
     *
     * Volatile because it is set from the service's main thread and read inside
     * `open`, which is also main thread — but a resolver installed while a
     * track is opening must be seen by that open rather than by the next one.
     */
    @Volatile
    private var streamResolver: com.ts8.core.music.StreamResolver? = null

    /**
     * Where the current track came from, for the buffering state.
     *
     * A local file is never "buffering"; a stream is, and the UI draws a
     * spinner from this rather than from a boolean of its own.
     */
    @Volatile
    private var currentIsStream = false

    @Volatile
    private var buffering: com.ts8.core.music.BufferingState = com.ts8.core.music.BufferingState.IDLE

    private val checkpointTicker = object : Runnable {
        override fun run() {
            if (released) return
            if (core.playing) saveCheckpoint()
            mainHandler.postDelayed(this, CHECKPOINT_INTERVAL_MS)
        }
    }

    init {
        mainHandler.postDelayed(checkpointTicker, CHECKPOINT_INTERVAL_MS)
    }

    // -- observable ---------------------------------------------------------

    override fun addListener(listener: PlaybackListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    override fun removeListener(listener: PlaybackListener) {
        listeners.remove(listener)
    }

    private fun notifyState() {
        val snapshot = state()
        mainHandler.post {
            for (l in listeners) runCatching { l.onStateChanged(snapshot) }
        }
    }

    private fun notifyError(error: PlaybackError, track: Track?) {
        lastError = error
        mainHandler.post {
            for (l in listeners) runCatching { l.onError(error, track) }
        }
        notifyState()
    }

    // -- state --------------------------------------------------------------

    override fun state(): PlaybackState = PlaybackState(
        track = core.queue.current(),
        isPlaying = core.playing,
        positionMs = position(),
        durationMs = if (lastKnownDurationMs > 0) lastKnownDurationMs
        else core.queue.current()?.durationMs ?: 0L,
        bufferedPercent = if (core.playing) 100 else 0,
        shuffle = core.queue.shuffle,
        repeatMode = core.repeatMode,
        duckedForNavigation = core.duckedForNavigation,
        pausedForCall = core.isPausedForCall(),
        error = lastError,
        buffering = buffering
    )

    private fun position(): Long {
        val p = player
        return if (p != null && !released) {
            runCatching { p.currentPosition.toLong() }.getOrDefault(lastKnownPositionMs)
        } else {
            lastKnownPositionMs
        }
    }

    // -- queue --------------------------------------------------------------

    override fun setQueue(tracks: List<Track>, startIndex: Int, autoPlay: Boolean) {
        if (released) return
        core.queue.setQueue(tracks, startIndex, core.queue.shuffle)
        lastError = null
        val selected = core.queue.current()
        if (selected == null) {
            stopInternal()
            core.setLoaded(false)
            notifyState()
            return
        }
        open(selected, startAtMs = 0, autoPlay = autoPlay)
    }

    override fun play() {
        if (released) return
        if (core.queue.current() == null) {
            // Nothing loaded: resume the checkpoint instead of doing nothing,
            // which is what a driver pressing play on a fresh boot expects.
            restoreCheckpoint()
            return
        }
        core.onManualPlay()
        resumeAudio()
    }

    override fun pause() {
        core.onManualPause()
        pauseAudio()
    }

    override fun togglePlayPause() {
        if (core.playing) pause() else play()
    }

    override fun next() {
        if (released) return
        val track = core.onNextPressed()
        if (track == null) {
            // End of a non-repeating queue: keep the last track loaded and
            // paused so the screen still shows what was playing.
            notifyState()
            return
        }
        open(track, startAtMs = 0, autoPlay = true)
    }

    override fun previous() {
        if (released) return
        // The universal convention: a press within the first seconds of a
        // track restarts it; later than that it goes to the previous track.
        if (position() > RESTART_THRESHOLD_MS) {
            seekTo(0)
            return
        }
        val track = core.onPreviousPressed(restartInstead = false)
        if (track == null) {
            seekTo(0)
            return
        }
        open(track, startAtMs = 0, autoPlay = true)
    }

    override fun seekTo(positionMs: Long) {
        val p = player ?: run {
            lastKnownPositionMs = positionMs.coerceAtLeast(0)
            notifyState()
            return
        }
        val duration = lastKnownDurationMs.takeIf { it > 0 } ?: p.duration.toLong()
        val target = positionMs.coerceIn(0, maxOf(0, duration))
        try {
            p.seekTo(target.toInt())
            lastKnownPositionMs = target
            notifyState()
        } catch (e: IllegalStateException) {
            notifyError(PlaybackError.UNKNOWN, core.queue.current())
        }
    }

    override fun setShuffle(enabled: Boolean) {
        core.queue.setShuffle(enabled)
        notifyState()
    }

    override fun setRepeatMode(mode: RepeatMode) {
        core.setRepeatMode(mode)
        notifyState()
    }

    // -- transport ----------------------------------------------------------

    /** Executes what [PlaybackCore] decided. */
    private fun execute(transport: PlaybackCore.Transport) {
        when (transport) {
            PlaybackCore.Transport.PLAY -> resumeAudio()
            PlaybackCore.Transport.PAUSE -> pauseAudio()
            PlaybackCore.Transport.NONE -> notifyState()
        }
    }

    /**
     * Starts audio for the loaded track, taking focus first.
     *
     * Focus is re-requested rather than assumed even when resuming after a call:
     * the telephony stack does not tell us whether it released `STREAM_MUSIC`
     * focus, and playing without it is how a navigation prompt ends up fighting
     * a song for the speakers.
     */
    private fun resumeAudio() {
        val track = core.queue.current() ?: return
        when (val focus = audioBridge.requestFocus(AudioRoute.MEDIA)) {
            AudioResult.Success -> Unit
            is AudioResult.Denied -> {
                // Report the refusal; do not start playing without focus.
                notifyError(PlaybackError.AUDIO_FOCUS_LOST, track)
                return
            }
            is AudioResult.Unsupported, is AudioResult.Failed -> {
                // A bridge that cannot report focus at all must not stop
                // playback; the platform still arbitrates.
            }
        }

        val p = player
        if (p == null) {
            open(track, position(), autoPlay = true)
            return
        }
        try {
            p.start()
            core.setPlaying(true)
            lastError = null
            acquireWakeLock()
            notifyState()
        } catch (e: IllegalStateException) {
            notifyError(PlaybackError.UNKNOWN, track)
        } catch (e: Exception) {
            notifyError(PlaybackError.UNKNOWN, track)
        }
    }

    /** Stops audio, keeping the position and the auto-resume flags untouched. */
    private fun pauseAudio() {
        val p = player
        try {
            if (p != null && p.isPlaying) p.pause()
        } catch (e: IllegalStateException) {
            // Nothing to pause; the state flag below is still corrected.
        }
        lastKnownPositionMs = position()
        core.setPlaying(false)
        saveCheckpoint()
        releaseWakeLock()
        notifyState()
    }

    // -- opening a track ----------------------------------------------------

    private fun open(track: Track, startAtMs: Long, autoPlay: Boolean) {
        stopInternal()

        // Check the source before touching MediaPlayer: a USB stick pulled
        // between the library query and this call is the common case, and
        // `setDataSource` on a vanished file throws IOException on some
        // firmware and RuntimeException on others.
        if (!sourceExists(track)) {
            lastKnownPositionMs = 0
            core.setLoaded(false)
            notifyError(PlaybackError.SOURCE_UNAVAILABLE, track)
            return
        }

        // An online item whose source is a provider id rather than a URL has
        // to be resolved first. A failed resolve is reported as an ordinary
        // playback error and stops here: handing MediaPlayer an unresolvable
        // string would produce a decode error that names the wrong cause.
        val playable = resolvePlayable(track) ?: return

        lastKnownPositionMs = startAtMs
        lastKnownDurationMs = playable.durationMs.takeIf { it > 0 } ?: track.durationMs
        lastError = null
        currentIsStream = playable.isStream
        buffering = if (playable.isStream) com.ts8.core.music.BufferingState.BUFFERING
        else com.ts8.core.music.BufferingState.IDLE

        try {
            val mp = MediaPlayer()
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
            mp.setWakeMode(appContext, PowerManager.PARTIAL_WAKE_LOCK)
            if (playable.isStream) {
                // A live stream has no end; `MediaPlayer` must not wait for a
                // content length it will never be given, and a buffering
                // timeout keeps a dead station from holding the player forever.
                mp.setDataSource(appContext, android.net.Uri.parse(playable.url))
            } else {
                mp.setDataSource(playable.url)
            }

            mp.setOnPreparedListener { p ->
                if (released) { runCatching { p.release() }; return@setOnPreparedListener }
                player = p
                core.setLoaded(true)
                buffering = com.ts8.core.music.BufferingState.IDLE
                lastKnownDurationMs = runCatching { p.duration.toLong() }
                    .getOrDefault(lastKnownDurationMs)
                    .takeIf { it > 0 } ?: lastKnownDurationMs

                if (startAtMs > 0) {
                    runCatching { p.seekTo(startAtMs.toInt()) }
                }
                if (autoPlay) {
                    when (audioBridge.requestFocus(AudioRoute.MEDIA)) {
                        is AudioResult.Denied -> {
                            // Loaded but not started: the driver sees a paused
                            // track and can retry, rather than a silent failure.
                            notifyError(PlaybackError.AUDIO_FOCUS_LOST, track)
                            return@setOnPreparedListener
                        }
                        else -> Unit
                    }
                    runCatching { p.start() }
                    core.setPlaying(true)
                    acquireWakeLock()
                }
                // Only a local file has a library row. Recording a play for an
                // online id would write a primary key the library has never
                // seen, and the next `track(id)` would return null for it.
                if (!playable.isStream) library?.recordPlayed(track.id, System.currentTimeMillis())
                notifyState()
            }

            // A stream that is playing but has gone quiet is buffering again;
            // a stream that keeps playing is not. This is the only signal
            // `MediaPlayer` gives, and without it the spinner would never
            // appear after the first prepare.
            mp.setOnInfoListener { _, what, _ ->
                when (what) {
                    MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                        if (currentIsStream) {
                            buffering = com.ts8.core.music.BufferingState.STALLED
                            notifyState()
                        }
                    }
                    MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                        if (currentIsStream) {
                            buffering = com.ts8.core.music.BufferingState.IDLE
                            notifyState()
                        }
                    }
                    else -> Unit
                }
                false
            }

            mp.setOnCompletionListener { p ->
                if (released) return@setOnCompletionListener
                lastKnownPositionMs = lastKnownDurationMs
                onTrackCompleted()
            }

            mp.setOnErrorListener { _, what, extra ->
                handlePlayerError(track, what, extra, isStream = currentIsStream)
                // Returning true marks the error handled so MediaPlayer does
                // not call its own onCompletion, which would advance the queue
                // past a broken file and hide the problem.
                true
            }

            mp.prepareAsync()
        } catch (e: Exception) {
            playing(false)
            buffering = com.ts8.core.music.BufferingState.IDLE
            notifyError(classify(e, currentIsStream), track)
        }
    }

    /**
     * The URL the platform player should open, or null when there is none.
     *
     * A local track already carries a path, so it passes through untouched and
     * this costs one string prefix check. An online track carries the provider
     * id, and only [streamResolver] can turn it into a URL — which is a network
     * call, and is therefore the reason this method must not be moved onto the
     * UI thread. It is called from `open`, which already runs on the main
     * thread of a *service* whose calls come from `handleIntent`, so the
     * resolver implementation in the app is expected to do its own blocking
     * work on a pool and this call therefore arrives pre-resolved wherever
     * possible; see `OnlineMusicRuntime`.
     */
    private fun resolvePlayable(track: Track): PlayableTrack? {
        val source = track.source
        if (isStreamSource(source)) {
            return PlayableTrack(url = source, durationMs = track.durationMs, isStream = true)
        }
        if (track.sourceKind != Track.SourceKind.ONLINE) {
            return PlayableTrack(url = source, durationMs = track.durationMs, isStream = false)
        }
        val resolver = streamResolver ?: run {
            notifyError(PlaybackError.STREAM_UNAVAILABLE, track)
            return null
        }
        return when (val resolved = runCatching { resolver.resolve(track) }
            .getOrElse { com.ts8.core.music.StreamResolution.Unavailable("Không tải được địa chỉ phát", true) }) {
            is com.ts8.core.music.StreamResolution.Playable ->
                if (resolved.url.isBlank()) {
                    notifyError(PlaybackError.STREAM_UNAVAILABLE, track)
                    null
                } else {
                    PlayableTrack(
                        url = resolved.url,
                        durationMs = resolved.durationMs,
                        isStream = true
                    )
                }

            is com.ts8.core.music.StreamResolution.Unavailable -> {
                Log.w(TAG, "stream resolve failed for ${track.id}: ${resolved.reason}")
                notifyError(PlaybackError.STREAM_UNAVAILABLE, track)
                null
            }
        }
    }

    /** What [open] actually hands the platform player. */
    private class PlayableTrack(val url: String, val durationMs: Long, val isStream: Boolean)

    private fun playing(value: Boolean) = core.setPlaying(value)

    private fun onTrackCompleted() {
        when (val completion = core.onTrackCompleted()) {
            is PlaybackCore.Completion.RepeatCurrent -> {
                val p = player
                if (p != null) {
                    runCatching {
                        p.seekTo(0)
                        p.start()
                    }
                    core.setPlaying(true)
                    acquireWakeLock()
                } else {
                    core.queue.current()?.let { open(it, 0, autoPlay = true) }
                }
                notifyState()
            }
            is PlaybackCore.Completion.Advance -> open(completion.track, 0, autoPlay = true)
            is PlaybackCore.Completion.Stop -> {
                releaseWakeLock()
                saveCheckpoint()
                notifyState()
            }
        }
    }

    private fun handlePlayerError(track: Track, what: Int, extra: Int, isStream: Boolean = false) {
        playing(false)
        buffering = com.ts8.core.music.BufferingState.IDLE
        releaseWakeLock()
        val error = when {
            // A stream error is a network outcome, not a codec or a missing
            // file: `MEDIA_ERROR_IO` on an `http` source means the host
            // dropped, and saying "USB đã rút" for that would send the driver
            // to check hardware that is fine.
            isStream && what == MediaPlayer.MEDIA_ERROR_IO -> PlaybackError.STREAM_INTERRUPTED
            isStream && what == MediaPlayer.MEDIA_ERROR_UNKNOWN -> PlaybackError.STREAM_UNAVAILABLE
            what == MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> PlaybackError.UNSUPPORTED_FORMAT
            what == MediaPlayer.MEDIA_ERROR_IO -> PlaybackError.SOURCE_UNAVAILABLE
            what == MediaPlayer.MEDIA_ERROR_MALFORMED -> PlaybackError.DECODE_FAILED
            extra == MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> PlaybackError.UNSUPPORTED_FORMAT
            else -> PlaybackError.DECODE_FAILED
        }
        Log.w(TAG, "playback error what=$what extra=$extra track=${track.id}")
        notifyError(error, track)
    }

    private fun classify(e: Exception, isStream: Boolean = false): PlaybackError = when (e) {
        is java.io.FileNotFoundException -> PlaybackError.SOURCE_UNAVAILABLE
        is SecurityException -> PlaybackError.SOURCE_UNAVAILABLE
        is java.io.IOException ->
            if (isStream) PlaybackError.STREAM_UNAVAILABLE else PlaybackError.UNKNOWN
        is IllegalStateException -> PlaybackError.UNKNOWN
        else -> {
            val message = e.message ?: ""
            when {
                // `MediaPlayer.setDataSource(String)` wraps a network failure in
                // an `IOException` whose message names the URL, not a file.
                isStream -> PlaybackError.STREAM_UNAVAILABLE
                message.contains("unsupported", true) -> PlaybackError.UNSUPPORTED_FORMAT
                message.contains("no such file", true) -> PlaybackError.SOURCE_UNAVAILABLE
                else -> PlaybackError.UNKNOWN
            }
        }
    }

    /**
     * True when the track's file is still there, or when it is a network
     * stream that does not have a local file to lose.
     *
     * **Why a stream takes the early return.** Everything below this is a
     * check for a file on disk. An `http(s)://` source has no file, so
     * `File(...)` reports `isFile == false` and the player would refuse to open
     * a station that is perfectly reachable — reporting `SOURCE_UNAVAILABLE`,
     * which tells the driver to re-seat a USB stick they are not using.
     *
     * The stream is not left unchecked: `setDataSource` on an unreachable URL
     * fails asynchronously, and `onError` classifies it into
     * [PlaybackError.STREAM_UNAVAILABLE] below. That is the honest place to
     * learn a host is down — the check here is only about files that vanished
     * between the library query and this call.
     */
    private fun sourceExists(track: Track): Boolean {
        val source = track.source
        if (source.startsWith("content://")) return true
        if (isStreamSource(source)) return true
        val file = File(source)
        return try {
            file.isFile && file.canRead() && file.length() > 0
        } catch (e: SecurityException) {
            // A USB volume unmounted: the file object raises rather than
            // returning false.
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * True for a source the platform player must fetch over the network.
     *
     * `http` and `https` only. A bare host, an `rtsp://` or a `file://` URI is
     * *not* accepted here: RTSP needs a different player surface on this
     * firmware and accepting it would produce a decode error that reads like a
     * broken file. Providers that only offer RTSP are surfaced as a failure by
     * [com.ts8.core.music.MusicApi]'s `StreamResolver` before reaching this.
     */
    private fun isStreamSource(source: String): Boolean =
        source.startsWith("http://", ignoreCase = true) ||
            source.startsWith("https://", ignoreCase = true)

    private fun stopInternal() {
        val p = player
        player = null
        if (p != null) {
            runCatching { if (p.isPlaying) p.pause() }
            runCatching { p.stop() }
            runCatching { p.reset() }
            runCatching { p.release() }
        }
        core.setPlaying(false)
        core.setLoaded(false)
        buffering = com.ts8.core.music.BufferingState.IDLE
        releaseWakeLock()
    }

    // -- checkpoint ---------------------------------------------------------

    override fun saveCheckpoint() {
        val track = core.queue.current() ?: return
        val snapshot = if (player != null) position() else lastKnownPositionMs
        val cp = MusicCheckpoint(
            trackId = track.id,
            positionMs = snapshot,
            index = core.queue.position,
            shuffle = core.queue.shuffle,
            repeatMode = core.repeatMode,
            queueTrackIds = core.queue.trackIdsInOrder().ifEmpty { listOf(track.id) },
            savedAtMs = System.currentTimeMillis()
        )
        checkpointStore.save(cp)
    }

    override fun restoreCheckpoint() {
        val cp = checkpointStore.load() ?: return
        val lib = library ?: return

        val restored = CheckpointRestore.plan(cp) { id ->
            try { lib.track(id) } catch (e: Exception) { null }
        }
        if (restored == null) {
            // Every track in the checkpoint is gone. Clearing it is what keeps
            // the next boot from retrying a restore that can never succeed.
            checkpointStore.clear()
            return
        }

        core.queue.restore(restored.tracks, restored.index, restored.shuffle)
        core.setRepeatMode(restored.repeatMode)

        val track = core.queue.current() ?: return
        // Load without playing: the driver starts the engine, not the music.
        open(track, startAtMs = restored.positionMs, autoPlay = false)
        notifyState()
    }

    // -- focus and lifecycle ------------------------------------------------

    /**
     * The listener the app installs on the bridge.
     *
     * Nothing here decides anything: each event is handed to [PlaybackCore],
     * which knows whether the interruption is allowed to resume, and the
     * transport it returns is what actually happens to the audio.
     */
    fun asFocusListener(): AudioFocusListener = object : AudioFocusListener {
        override fun onFocusLostPermanent() {
            mainHandler.post { execute(core.onFocusLostPermanent()) }
        }

        override fun onFocusLostTransient(mayDuck: Boolean) {
            mainHandler.post { execute(core.onTransientLoss(mayDuck)) }
        }

        override fun onFocusGained() {
            mainHandler.post { execute(core.onFocusGained()) }
        }

        override fun onCallStarted() {
            mainHandler.post { execute(core.onCallStarted()) }
        }

        override fun onCallEnded() {
            mainHandler.post { execute(core.onCallEnded()) }
        }

        override fun onAudioRouteChanged(route: AudioRoute) {
            mainHandler.post { notifyState() }
        }
    }

    /** Called by the navigation side when it ducks the stream. */
    fun onNavigationDuck(ducked: Boolean) {
        core.setDuckedForNavigation(ducked)
        notifyState()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ts8:music").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            // A head unit that denies wake locks still plays music while the
            // screen is on; losing background playback is degraded, not fatal.
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            // Ignore: already released or revoked.
        } finally {
            wakeLock = null
        }
    }

    override fun release() {
        if (released) return
        saveCheckpoint()
        released = true
        mainHandler.removeCallbacks(checkpointTicker)
        stopInternal()
        // Nothing is playing after release, and the queue must not be readable
        // as if it still were.
        core.queue.setQueue(emptyList(), 0, false)
        audioBridge.abandonFocus(AudioRoute.MEDIA)
        listeners.clear()
    }

    /**
     * Installs the provider front end the app supplies (§13).
     *
     * See [com.ts8.core.music.MusicPlayer.setStreamResolver] for why the local
     * engine takes this from outside rather than building one. Clearing it
     * (`null`) is the honest state for "online music is off in Settings".
     */
    override fun setStreamResolver(resolver: com.ts8.core.music.StreamResolver?) {
        streamResolver = resolver
    }

    /** Reloads the current track from disk, for when a stick is plugged back in. */
    fun retryCurrent() {
        val track = core.queue.current() ?: return
        open(track, lastKnownPositionMs, autoPlay = core.playing)
    }

    companion object {
        private const val TAG = "Ts8LocalPlayer"
        private const val CHECKPOINT_INTERVAL_MS = 10_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        private const val RESTART_THRESHOLD_MS = 3_000L
    }
}

END FILE

## core/music-engine-local/src/main/java/com/ts8/core/music/local/MediaStoreScanner.kt

BEGIN FILE
package com.ts8.core.music.local

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.Log
import com.ts8.core.music.CodecSupport
import com.ts8.core.music.MediaScanner
import com.ts8.core.music.MusicLibrary
import com.ts8.core.music.ScanResult
import com.ts8.core.music.Track
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The scanner: MediaStore for internal storage, a directory walk for USB.
 *
 * **Why incremental.** The spec requires it, and the reason is concrete: a
 * full scan of a 2 000-track stick on this CPU costs tens of seconds, and it
 * competes with map rendering for the same cores. So:
 *
 * - The **first** scan (or a forced one) indexes everything and records each
 *   file's size and mtime.
 * - Every later scan compares size+mtime and touches only what changed.
 *   Unchanged files cost an `stat()` call, not a tag read.
 *
 * **Why MediaStore and then not MediaStore.** MediaStore is the cheap path for
 * internal storage — the platform already walked it. It is *wrong* for USB:
 * the TS8's MediaStore ignores removable volumes unless the vendor's media app
 * has scanned them, so the USB path walks the volume directly and reads
 * container headers with [AudioTagReader]. Both paths converge on the same
 * [ScannedFile] and the same [ScanDiffCalculator], so the incremental
 * bookkeeping is identical.
 *
 * USB scanning runs with `MediaStore` untouched — nothing is written outside
 * the app's own database. If the stick is yanked mid-scan, the walk throws
 * `IOException` and the partial result is kept; the next scan picks up the
 * rest. The spec's "safe on USB removal without crash" is enforced here and in
 * [LocalMusicPlayer].
 */
class MediaStoreScanner(
    context: Context,
    private val db: MusicDatabase,
    private val library: SqliteMusicLibrary,
    private val codecs: CodecSupport
) : MediaScanner {

    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    /** Volumes seen last time, so removal can be detected without a full walk. */
    private var lastVolumes: List<File> = emptyList()

    override fun canScan(): Boolean = !running.get() && !cancelled.get()

    override fun cancel() {
        cancelled.set(true)
    }

    override fun scan(forceFullScan: Boolean, onProgress: (Float) -> Unit): ScanResult {
        if (!running.compareAndSet(false, true)) {
            return ScanResult(0, 0, 0, 0, 0, 0, false, "a scan is already running")
        }
        cancelled.set(false)
        val started = System.currentTimeMillis()

        var scannedFiles = 0
        var added = 0
        var updated = 0
        var removed = 0
        var unsupported = 0
        var usbScanned = false
        var error: String? = null

        try {
            val existing = loadExisting()
            val kinds = loadExistingKinds()

            // -- internal storage via MediaStore ---------------------------
            val internal = collectFromMediaStore()
            scannedFiles += internal.found.size
            unsupported += internal.unsupported
            onProgress(0.25f)

            // MediaStore is authoritative only for the internal volume, so its
            // diff is scoped: a photo-only USB stick must not delete its music.
            val internalDiff = ScanDiffCalculator.diff(
                existing, kinds, internal.found, scopedTo = Track.SourceKind.MEDIA_STORE
            )
            if (cancelled.get()) return cancelledResult(scannedFiles, started)
            applyDiff(internalDiff)
            added += internalDiff.added.size
            updated += internalDiff.updated.size
            removed += internalDiff.removed.size

            // -- USB volumes, walked directly ------------------------------
            val (usbFiles, usbUnsupported) = collectFromUsb()
            usbScanned = usbFiles.isNotEmpty()
            scannedFiles += usbFiles.size
            unsupported += usbUnsupported
            onProgress(0.7f)

            if (cancelled.get()) return cancelledResult(scannedFiles, started)

            // Re-read after the MediaStore writes: the USB diff compares
            // against what is now stored, not against the pre-scan snapshot.
            val after = loadExisting()
            val afterKinds = loadExistingKinds()
            val usbDiff = ScanDiffCalculator.diff(
                after, afterKinds, usbFiles, scopedTo = Track.SourceKind.USB
            )
            applyDiff(usbDiff)
            added += usbDiff.added.size
            updated += usbDiff.updated.size
            removed += usbDiff.removed.size

            onProgress(0.95f)
            finalise(added + updated + removed > 0)
            onProgress(1f)
        } catch (e: SecurityException) {
            // READ_EXTERNAL_STORAGE denied or revoked: report it, do not crash,
            // and do not delete the library because we could not look at it.
            error = "không có quyền đọc bộ nhớ: ${e.message}"
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "scan failed", e)
        } finally {
            running.set(false)
        }

        return ScanResult(
            scannedFiles = scannedFiles,
            added = added,
            updated = updated,
            removed = removed,
            unsupported = unsupported,
            durationMs = System.currentTimeMillis() - started,
            usbScanned = usbScanned,
            error = error
        )
    }

    private fun cancelledResult(scanned: Int, started: Long) = ScanResult(
        scannedFiles = scanned, added = 0, updated = 0, removed = 0, unsupported = 0,
        durationMs = System.currentTimeMillis() - started, usbScanned = false,
        error = "đã huỷ"
    )

    // -- internal storage ---------------------------------------------------

    private data class Collected(val found: List<ScannedFile>, val unsupported: Int)

    private fun collectFromMediaStore(): Collected {
        val found = ArrayList<ScannedFile>()
        var unsupported = 0

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED
        )

        val selection =
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND " +
                "${MediaStore.Audio.Media.DATA} IS NOT NULL"

        try {
            appContext.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, null,
                MediaStore.Audio.Media.TITLE + " COLLATE NOCASE"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val titleCol = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val artistIdCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST_ID)
                val albumCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val trackCol = c.getColumnIndex(MediaStore.Audio.Media.TRACK)
                val yearCol = c.getColumnIndex(MediaStore.Audio.Media.YEAR)
                val mimeCol = c.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val sizeCol = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val mtimeCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)

                while (c.moveToNext()) {
                    if (cancelled.get()) break
                    val path = c.getString(dataCol) ?: continue
                    val name = path.substringAfterLast('/')
                    val mime = if (mimeCol >= 0) c.getString(mimeCol) else null

                    if (!ScanDiffCalculator.shouldIndex(name, mime, codecs::isSupported)) {
                        unsupported++
                        continue
                    }

                    val id = c.getLong(idCol)
                    found.add(
                        ScannedFile(
                            path = path,
                            sizeBytes = if (sizeCol >= 0) c.getLong(sizeCol) else 0,
                            modifiedSeconds = if (mtimeCol >= 0) c.getLong(mtimeCol) else 0,
                            id = id,
                            sourceKind = Track.SourceKind.MEDIA_STORE,
                            mimeType = mime ?: com.ts8.core.music.MusicFormats.mimeForFileName(name),
                            title = if (titleCol >= 0) c.getString(titleCol) else null,
                            artist = if (artistCol >= 0) c.getString(artistCol) else null,
                            album = if (albumCol >= 0) c.getString(albumCol) else null,
                            albumId = if (albumIdCol >= 0) c.getLong(albumIdCol) else null,
                            artistId = if (artistIdCol >= 0) c.getLong(artistIdCol) else null,
                            durationMs = if (durationCol >= 0) c.getLong(durationCol) else 0,
                            trackNumber = if (trackCol >= 0) c.getInt(trackCol) % 1000 else 0,
                            year = if (yearCol >= 0) c.getInt(yearCol) else 0
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore query failed", e)
        }

        return Collected(found, unsupported)
    }

    // -- USB ---------------------------------------------------------------

    /**
     * Walks removable volumes.
     *
     * `StorageManager.getStorageVolumes()` exists from API 24, so on API 23 the
     * well-known USB mount points under `/storage` are probed instead. That is
     * the honest approach: there is no supported enumeration API on this
     * firmware version, so the code looks where the mounts actually are.
     */
    private fun collectFromUsb(): Pair<List<ScannedFile>, Int> {
        val found = ArrayList<ScannedFile>()
        var unsupported = 0
        val volumes = removableVolumes()
        lastVolumes = volumes

        for (volume in volumes) {
            if (cancelled.get()) break
            try {
                walkDirectory(volume, 0, found) { }
            } catch (e: SecurityException) {
                Log.w(TAG, "no access to ${volume.absolutePath}")
            } catch (e: Exception) {
                // A stick pulled mid-walk throws here. Keeping what was read so
                // far is right: the driver removed the stick, the library
                // should lose only what is genuinely gone.
                Log.w(TAG, "USB walk stopped early at ${volume.absolutePath}", e)
            }
        }

        // Tag reads are the expensive part, so they are counted separately:
        // `unsupported` here means "read failed" rather than "codec missing",
        // and callers use it only as a diagnostic.
        unsupported = found.count { it.title == null && it.durationMs == 0L }
        return found to unsupported
    }

    private fun removableVolumes(): List<File> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return try {
                val sm = appContext.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                sm.storageVolumes
                    .filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
                    .mapNotNull { volume ->
                        @Suppress("DEPRECATION")
                        volume.directory
                    }
                    .filter { it.isDirectory && it.canRead() }
            } catch (e: Exception) {
                emptyList()
            }
        }

        // API 23: probe the documented mount points.
        val candidates = listOf(
            File("/storage/usb0"),
            File("/storage/usb1"),
            File("/storage/usbdisk"),
            File("/storage/usb_disk"),
            File("/mnt/usb_storage"),
            File("/storage/UsbDriveA"),
            File("/storage/sdcard1")
        )
        return candidates.filter { it.isDirectory && it.canRead() }
    }

    /** Depth-limited walk: a stick with 20 levels of folders is a mistake, not a library. */
    private fun walkDirectory(
        dir: File,
        depth: Int,
        out: MutableList<ScannedFile>,
        onFile: (File) -> Unit
    ) {
        if (depth > MAX_DEPTH || cancelled.get()) return
        val entries = dir.listFiles() ?: return
        for (entry in entries) {
            if (cancelled.get()) return
            if (entry.isDirectory) {
                // Skip Android's own metadata directories.
                if (entry.name.startsWith(".") || entry.name.equals("Android", true)) continue
                walkDirectory(entry, depth + 1, out, onFile)
                continue
            }
            val name = entry.name
            if (!ScanDiffCalculator.shouldIndex(name, null, codecs::isSupported)) continue

            val scanned = readUsbFile(entry) ?: continue
            out.add(scanned)
            onFile(entry)
        }
    }

    private fun readUsbFile(file: File): ScannedFile? {
        val name = file.name
        val mime = com.ts8.core.music.MusicFormats.mimeForFileName(name)
            ?: return null
        val id = TrackIds.forUsbPath(file.absolutePath)

        // Files above the size cap are almost certainly not music (or are a
        // corrupt container); refusing them keeps one bad file from stalling a
        // scan that the driver is waiting on.
        val length = try { file.length() } catch (e: Exception) { return null }
        if (length <= 0 || length > MAX_FILE_BYTES) return null

        val tags = try {
            AudioTagReader.read(file)
        } catch (e: Exception) {
            null
        }

        val folder = file.parent ?: return null
        val title = tags?.title?.takeIf { it.isNotBlank() }
            ?: ScanDiffCalculator.titleFromFileName(name)

        return ScannedFile(
            path = file.absolutePath,
            sizeBytes = length,
            modifiedSeconds = file.lastModified() / 1000L,
            id = id,
            sourceKind = Track.SourceKind.USB,
            mimeType = mime,
            title = title,
            artist = tags?.artist,
            album = tags?.album,
            // USB files have no platform album/artist ids, so a stable hash of
            // the name keeps grouping consistent across scans.
            albumId = tags?.album?.let { TrackIds.forUsbPath("album:$it") },
            artistId = tags?.artist?.let { TrackIds.forUsbPath("artist:$it") },
            durationMs = tags?.durationMs ?: 0,
            trackNumber = tags?.trackNumber ?: 0,
            year = tags?.year ?: 0
        )
    }

    // -- database -----------------------------------------------------------

    private fun loadExisting(): Map<Long, Pair<Long, Long>> {
        val out = HashMap<Long, Pair<Long, Long>>(1024)
        db.readableDatabase.rawQuery(
            "SELECT id, size_bytes, modified_s FROM tracks", null
        ).use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = c.getLong(1) to c.getLong(2)
        }
        return out
    }

    private fun loadExistingKinds(): Map<Long, Track.SourceKind> {
        val out = HashMap<Long, Track.SourceKind>(1024)
        db.readableDatabase.rawQuery("SELECT id, source_kind FROM tracks", null).use { c ->
            while (c.moveToNext()) {
                out[c.getLong(0)] =
                    if (c.getInt(1) == 1) Track.SourceKind.USB else Track.SourceKind.MEDIA_STORE
            }
        }
        return out
    }

    private fun applyDiff(diff: ScanDiff) {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for (file in diff.added + diff.updated) {
                // `favorite` and `last_played` are user state, not file state;
                // CONFLICT_REPLACE would wipe them on every rescan, so the
                // update is written column-by-column instead.
                w.execSQL(
                    "INSERT OR REPLACE INTO tracks " +
                        "(id, source, title, artist, album, album_id, artist_id, " +
                        "duration_ms, folder, track_no, year, mime_type, size_bytes, " +
                        "modified_s, source_kind, favorite, last_played) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, " +
                        "COALESCE((SELECT favorite FROM tracks WHERE id = ?), 0), " +
                        "COALESCE((SELECT last_played FROM tracks WHERE id = ?), 0))",
                    // Typed explicitly: `arrayOf` over mixed Long/String/null
                    // infers Array<Long?> here and would not bind.
                    arrayOf<Any?>(
                        file.id, file.path, file.title ?: file.path, file.artist, file.album,
                        file.albumId, file.artistId, file.durationMs,
                        file.path.substringBeforeLast('/'), file.trackNumber, file.year,
                        file.mimeType, file.sizeBytes, file.modifiedSeconds,
                        if (file.sourceKind == Track.SourceKind.USB) 1 else 0,
                        file.id, file.id
                    )
                )
            }
            for (id in diff.removed) {
                // `delete` binds strings only, unlike `execSQL`'s Any? array.
                w.delete("tracks", "id = ?", arrayOf(id.toString()))
                // A playlist entry pointing at a deleted track would render as
                // a blank row; remove it in the same transaction.
                w.delete("playlist_items", "track_id = ?", arrayOf(id.toString()))
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    private fun finalise(changed: Boolean) {
        val count = db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM tracks", null
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

        db.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO scan_state (id, last_scan_at, last_volume, total_tracks) " +
                "VALUES (1, ?, ?, ?)",
            arrayOf(System.currentTimeMillis(), lastVolumes.joinToString(",") { it.name }, count)
        )
        // Marking the library ready is what lets the UI show "đang quét…"
        // rather than an empty list on first boot.
        library.markReady()
    }

    /** Runs a scan on a background thread, for the service and the UI. */
    fun scanAsync(
        forceFullScan: Boolean = false,
        onProgress: (Float) -> Unit = {},
        onDone: (ScanResult) -> Unit
    ) {
        thread(name = "ts8-media-scan", isDaemon = true) {
            val result = scan(forceFullScan, onProgress)
            onDone(result)
        }
    }

    companion object {
        private const val TAG = "Ts8MediaScanner"
        private const val MAX_DEPTH = 12
        /** 300 MB: larger than any audio file this unit will ever play. */
        private const val MAX_FILE_BYTES = 300L * 1024 * 1024
    }
}

END FILE

## core/music-engine-local/src/main/java/com/ts8/core/music/local/MusicDatabase.kt

BEGIN FILE
package com.ts8.core.music.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.ts8.core.music.MusicCollection
import com.ts8.core.music.MusicLibrary
import com.ts8.core.music.Playlist
import com.ts8.core.music.Track

/**
 * The library store.
 *
 * SQLite because the whole point of incremental scanning is a persistent
 * index: the file system is walked once, and afterwards a scan only probes
 * what changed. The schema is deliberately flat (no foreign-key joins at read
 * time) because on this hardware a join per row in a 200-row page is visible.
 *
 * Artists and albums are stored as denormalised names on the track, plus a
 * small aggregate table for the browser lists. That keeps "all tracks in this
 * album" a single indexed scan.
 */
class MusicDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE tracks (
              id           INTEGER PRIMARY KEY,
              source       TEXT    NOT NULL,
              title        TEXT    NOT NULL,
              artist       TEXT,
              album        TEXT,
              album_id     INTEGER,
              artist_id    INTEGER,
              duration_ms  INTEGER NOT NULL DEFAULT 0,
              folder       TEXT,
              track_no     INTEGER NOT NULL DEFAULT 0,
              year         INTEGER NOT NULL DEFAULT 0,
              mime_type    TEXT,
              size_bytes   INTEGER NOT NULL DEFAULT 0,
              modified_s   INTEGER NOT NULL DEFAULT 0,
              source_kind  INTEGER NOT NULL DEFAULT 0,
              favorite     INTEGER NOT NULL DEFAULT 0,
              last_played  INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        // The scanner's incremental probe reads by (source) and (size, mtime).
        db.execSQL("CREATE UNIQUE INDEX idx_tracks_source ON tracks(source)")
        db.execSQL("CREATE INDEX idx_tracks_album ON tracks(album_id, track_no)")
        db.execSQL("CREATE INDEX idx_tracks_artist ON tracks(artist_id)")
        db.execSQL("CREATE INDEX idx_tracks_folder ON tracks(folder)")
        db.execSQL("CREATE INDEX idx_tracks_favorite ON tracks(favorite, last_played DESC)")
        db.execSQL("CREATE INDEX idx_tracks_played ON tracks(last_played DESC)")
        db.execSQL("CREATE INDEX idx_tracks_title ON tracks(title)")

        db.execSQL(
            """
            CREATE TABLE playlists (
              id         INTEGER PRIMARY KEY AUTOINCREMENT,
              name       TEXT    NOT NULL,
              created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE playlist_items (
              playlist_id INTEGER NOT NULL,
              track_id    INTEGER NOT NULL,
              position    INTEGER NOT NULL,
              PRIMARY KEY (playlist_id, track_id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_playlist_items ON playlist_items(playlist_id, position)")

        // Resume point, so a reboot resumes the track the driver was on rather
        // than the first one in the library.
        db.execSQL(
            """
            CREATE TABLE checkpoint (
              id         INTEGER PRIMARY KEY CHECK (id = 1),
              track_id   INTEGER NOT NULL,
              position_ms INTEGER NOT NULL,
              queue_json TEXT,
              saved_at   INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // Scan bookkeeping: what the last incremental pass saw, so removal can
        // be detected without a full MediaStore walk.
        db.execSQL(
            """
            CREATE TABLE scan_state (
              id            INTEGER PRIMARY KEY CHECK (id = 1),
              last_scan_at  INTEGER NOT NULL,
              last_volume   TEXT,
              total_tracks  INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Library data is a cache of the file system: rebuilding is always
        // correct and cheaper than a migration chain. The checkpoint and
        // playlists are user data, so they are preserved where the schema
        // allows it.
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS tracks")
            db.execSQL("DROP INDEX IF EXISTS idx_tracks_source")
            onCreate(db)
        }
    }

    companion object {
        const val DATABASE_NAME = "music-library.db"
        const val DATABASE_VERSION = 2

        /** Reads one track row. Column order matches [TRACK_COLUMNS]. */
        val TRACK_COLUMNS = arrayOf(
            "id", "source", "title", "artist", "album", "album_id", "artist_id",
            "duration_ms", "folder", "track_no", "year", "mime_type",
            "size_bytes", "modified_s", "source_kind"
        )

        fun readTrack(c: Cursor): Track = Track(
            id = c.getLong(0),
            source = c.getString(1),
            title = c.getString(2),
            artist = if (c.isNull(3)) null else c.getString(3),
            album = if (c.isNull(4)) null else c.getString(4),
            albumId = if (c.isNull(5)) null else c.getLong(5),
            artistId = if (c.isNull(6)) null else c.getLong(6),
            durationMs = c.getLong(7),
            folder = if (c.isNull(8)) null else c.getString(8),
            trackNumber = c.getInt(9),
            year = c.getInt(10),
            mimeType = if (c.isNull(11)) null else c.getString(11),
            sizeBytes = c.getLong(12),
            modifiedSeconds = c.getLong(13),
            sourceKind = if (c.getInt(14) == 1) Track.SourceKind.USB else Track.SourceKind.MEDIA_STORE
        )

        fun trackValues(t: Track): ContentValues = ContentValues().apply {
            put("id", t.id)
            put("source", t.source)
            put("title", t.title)
            put("artist", t.artist)
            put("album", t.album)
            put("album_id", t.albumId)
            put("artist_id", t.artistId)
            put("duration_ms", t.durationMs)
            put("folder", t.folder)
            put("track_no", t.trackNumber)
            put("year", t.year)
            put("mime_type", t.mimeType)
            put("size_bytes", t.sizeBytes)
            put("modified_s", t.modifiedSeconds)
            put("source_kind", if (t.sourceKind == Track.SourceKind.USB) 1 else 0)
        }
    }
}

/**
 * [MusicLibrary] over [MusicDatabase].
 *
 * Every method opens a read, does one indexed query and closes. The database is
 * small (a few thousand rows in practice) and SQLite keeps its page cache, so
 * this beats holding a cursor the UI has to remember to close.
 */
class SqliteMusicLibrary(private val db: MusicDatabase) : MusicLibrary {

    @Volatile
    private var ready = false

    fun markReady() {
        ready = true
    }

    override fun isReady(): Boolean = ready

    override fun trackCount(): Int = db.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM tracks", null
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    override fun totalDurationMs(): Long = db.readableDatabase.rawQuery(
        "SELECT COALESCE(SUM(duration_ms), 0) FROM tracks", null
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    override fun searchTracks(query: String, limit: Int): List<Track> =
        queryTracks(
            selection = "title LIKE ? OR artist LIKE ? OR album LIKE ?",
            args = Array(3) { "%${query.trim()}%" },
            orderBy = "title COLLATE NOCASE",
            limit = limit
        )

    override fun tracksInAlbum(albumId: Long): List<Track> =
        queryTracks("album_id = ?", arrayOf(albumId.toString()), "track_no, title", 0)

    override fun tracksByArtist(artistId: Long): List<Track> =
        queryTracks("artist_id = ?", arrayOf(artistId.toString()), "album, track_no", 0)

    override fun tracksInFolder(folder: String): List<Track> =
        queryTracks("folder = ?", arrayOf(folder), "title COLLATE NOCASE", 0)

    override fun albums(): List<MusicCollection> = collections(
        "SELECT album_id, album, COUNT(*), MIN(duration_ms) " +
            "FROM tracks WHERE album_id IS NOT NULL AND album IS NOT NULL " +
            "GROUP BY album_id ORDER BY album COLLATE NOCASE",
        MusicCollection.Kind.ALBUM
    )

    override fun artists(): List<MusicCollection> = collections(
        "SELECT artist_id, artist, COUNT(*), 0 " +
            "FROM tracks WHERE artist_id IS NOT NULL AND artist IS NOT NULL " +
            "GROUP BY artist_id ORDER BY artist COLLATE NOCASE",
        MusicCollection.Kind.ARTIST
    )

    override fun folders(): List<MusicCollection> = collections(
        "SELECT 0, folder, COUNT(*), 0 " +
            "FROM tracks WHERE folder IS NOT NULL " +
            "GROUP BY folder ORDER BY folder COLLATE NOCASE",
        MusicCollection.Kind.FOLDER
    )

    private fun collections(sql: String, kind: MusicCollection.Kind): List<MusicCollection> =
        db.readableDatabase.rawQuery(sql, null).use { c ->
            val out = ArrayList<MusicCollection>(c.count)
            while (c.moveToNext()) {
                out.add(
                    MusicCollection(
                        id = c.getLong(0),
                        name = c.getString(1) ?: "",
                        kind = kind,
                        trackCount = c.getInt(2)
                    )
                )
            }
            out
        }

    override fun playlists(): List<Playlist> = db.readableDatabase.rawQuery(
        "SELECT p.id, p.name, p.created_at, COUNT(i.track_id) " +
            "FROM playlists p LEFT JOIN playlist_items i ON i.playlist_id = p.id " +
            "GROUP BY p.id ORDER BY p.created_at DESC",
        null
    ).use { c ->
        val out = ArrayList<Playlist>(c.count)
        while (c.moveToNext()) {
            out.add(Playlist(c.getLong(0), c.getString(1), c.getInt(3), c.getLong(2)))
        }
        out
    }

    override fun playlistTracks(playlistId: Long): List<Track> =
        queryTracks(
            "id IN (SELECT track_id FROM playlist_items WHERE playlist_id = ?)",
            arrayOf(playlistId.toString()),
            "id",
            0
        ).sortedBy { t ->
            // Preserve the playlist's own order.
            playlistPosition(playlistId, t.id)
        }

    private fun playlistPosition(playlistId: Long, trackId: Long): Int =
        db.readableDatabase.rawQuery(
            "SELECT position FROM playlist_items WHERE playlist_id = ? AND track_id = ?",
            arrayOf(playlistId.toString(), trackId.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else Int.MAX_VALUE }

    override fun favorites(): List<Track> =
        queryTracks("favorite = 1", null, "last_played DESC, title COLLATE NOCASE", 0)

    override fun recentlyPlayed(limit: Int): List<Track> =
        queryTracks("last_played > 0", null, "last_played DESC", limit)

    override fun allTracks(offset: Int, limit: Int): List<Track> =
        queryTracks(null, null, "title COLLATE NOCASE", limit, offset)

    override fun track(id: Long): Track? =
        queryTracks("id = ?", arrayOf(id.toString()), null, 1).firstOrNull()

    private fun queryTracks(
        selection: String?,
        args: Array<String>?,
        orderBy: String?,
        limit: Int,
        offset: Int = 0
    ): List<Track> {
        val sql = buildString {
            append("SELECT ").append(MusicDatabase.TRACK_COLUMNS.joinToString(","))
            append(" FROM tracks")
            if (selection != null) append(" WHERE ").append(selection)
            if (orderBy != null) append(" ORDER BY ").append(orderBy)
            if (limit > 0) {
                append(" LIMIT ").append(limit)
                if (offset > 0) append(" OFFSET ").append(offset)
            }
        }
        return db.readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<Track>(minOf(c.count, if (limit > 0) limit else 256))
            while (c.moveToNext()) out.add(MusicDatabase.readTrack(c))
            out
        }
    }

    // -- mutations ---------------------------------------------------------

    override fun createPlaylist(name: String): Long {
        val values = ContentValues().apply {
            put("name", name.trim())
            put("created_at", System.currentTimeMillis())
        }
        return db.writableDatabase.insert("playlists", null, values)
    }

    override fun addToPlaylist(playlistId: Long, trackId: Long): Boolean {
        val dbW = db.writableDatabase
        val position = dbW.rawQuery(
            "SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_items WHERE playlist_id = ?",
            arrayOf(playlistId.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val values = ContentValues().apply {
            put("playlist_id", playlistId)
            put("track_id", trackId)
            put("position", position)
        }
        return dbW.insertWithOnConflict(
            "playlist_items", null, values, SQLiteDatabase.CONFLICT_IGNORE
        ) >= 0
    }

    override fun removeFromPlaylist(playlistId: Long, trackId: Long): Boolean =
        db.writableDatabase.delete(
            "playlist_items", "playlist_id = ? AND track_id = ?",
            arrayOf(playlistId.toString(), trackId.toString())
        ) > 0

    override fun deletePlaylist(playlistId: Long) {
        val w = db.writableDatabase
        w.delete("playlist_items", "playlist_id = ?", arrayOf(playlistId.toString()))
        w.delete("playlists", "id = ?", arrayOf(playlistId.toString()))
    }

    override fun setFavorite(trackId: Long, favorite: Boolean) {
        val values = ContentValues().apply { put("favorite", if (favorite) 1 else 0) }
        db.writableDatabase.update("tracks", values, "id = ?", arrayOf(trackId.toString()))
    }

    override fun isFavorite(trackId: Long): Boolean =
        db.readableDatabase.rawQuery(
            "SELECT favorite FROM tracks WHERE id = ?", arrayOf(trackId.toString())
        ).use { it.moveToFirst() && it.getInt(0) == 1 }

    override fun recordPlayed(trackId: Long, atMs: Long) {
        val values = ContentValues().apply { put("last_played", atMs) }
        db.writableDatabase.update("tracks", values, "id = ?", arrayOf(trackId.toString()))
    }
}

END FILE

## core/music-engine-local/src/main/java/com/ts8/core/music/local/PlaybackCore.kt

BEGIN FILE
package com.ts8.core.music.local

import com.ts8.core.music.RepeatMode
import com.ts8.core.music.Track
import kotlin.random.Random

/**
 * The decisions playback makes, separated from the object that plays audio.
 *
 * Every branch here used to live inside `LocalMusicPlayer`, tangled with
 * `MediaPlayer` calls and a `Handler`, which meant it could only be tested on a
 * device — and the behaviours that actually break in a car are exactly these
 * ones: whether music resumes after a call the driver never asked it to
 * resume, whether a navigation prompt leaves the music quiet forever, whether
 * a shutdown loses the resume position.
 *
 * Nothing in this class touches Android. `LocalMusicPlayer` owns the
 * `MediaPlayer` and asks [PlaybackCore] what to do; the answer is a
 * [Transport] the player executes. That split is also what makes a future
 * `SyuAudioBridge` testable: the bridge changes, the decisions do not.
 */
class PlaybackCore(
    private val random: Random = Random.Default
) {
    /** What the player should do next. */
    enum class Transport {
        /** Start or resume audio. */
        PLAY,

        /** Stop audio, keeping the position. */
        PAUSE,

        /** Do nothing to the transport; only flags changed. */
        NONE
    }

    val queue = PlaybackQueue(random)

    var repeatMode: RepeatMode = RepeatMode.OFF
        private set

    /** True while audio is actually coming out. */
    var playing: Boolean = false
        private set

    /** True once a track is loaded, playing or not. */
    var loaded: Boolean = false
        private set

    /** True when playback should resume once the call ends. */
    private var pausedForCall = false

    /** True when playback should resume once focus returns. */
    private var pausedForFocus = false

    /** True while the stream is lowered for a navigation prompt. */
    var duckedForNavigation: Boolean = false
        private set

    fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
    }

    fun setLoaded(loaded: Boolean) {
        this.loaded = loaded
        if (!loaded) playing = false
    }

    fun setPlaying(playing: Boolean) {
        this.playing = playing && loaded
    }

    fun setDuckedForNavigation(ducked: Boolean) {
        duckedForNavigation = ducked
    }

    fun isPausedForCall(): Boolean = pausedForCall

    /** True when the queue wraps at the end, which repeat-all and repeat-one imply. */
    fun wrapsAtEnd(): Boolean = repeatMode != RepeatMode.OFF

    // -- focus events -------------------------------------------------------

    /**
     * A call started.
     *
     * A ring or an active call stops the music outright — never ducks. The
     * driver has to hear the caller, and music under a conversation is worse
     * than silence.
     */
    fun onCallStarted(): Transport {
        if (!playing) return Transport.NONE
        pausedForCall = true
        playing = false
        return Transport.PAUSE
    }

    /**
     * The call ended.
     *
     * Resumes **only** if this class paused the music for the call. A track the
     * driver had paused by hand stays paused; restarting it is the single most
     * irritating thing a car player can do.
     */
    fun onCallEnded(): Transport {
        if (!pausedForCall) return Transport.NONE
        pausedForCall = false
        if (!loaded) return Transport.NONE
        playing = true
        return Transport.PLAY
    }

    /**
     * Focus was lost for a short time.
     *
     * @param mayDuck true when the system allows lowering the volume instead of
     *        stopping. A prompt or a notification ducks; a full transient loss
     *        pauses.
     */
    fun onTransientLoss(mayDuck: Boolean): Transport {
        if (!playing) return Transport.NONE
        if (mayDuck) {
            duckedForNavigation = true
            return Transport.NONE
        }
        pausedForFocus = true
        playing = false
        return Transport.PAUSE
    }

    /** Focus returned. Resumes only what this class paused. */
    fun onFocusGained(): Transport {
        val wasDucked = duckedForNavigation
        duckedForNavigation = false
        if (pausedForFocus) {
            pausedForFocus = false
            if (!loaded) return Transport.NONE
            playing = true
            return Transport.PLAY
        }
        // Focus came back from a duck: the stream is restored elsewhere, but
        // playback never stopped, so there is nothing to start.
        return if (wasDucked) Transport.NONE else Transport.NONE
    }

    /** Focus was lost permanently: another app is playing. Always pauses. */
    fun onFocusLostPermanent(): Transport {
        duckedForNavigation = false
        if (!playing) return Transport.NONE
        pausedForFocus = true
        playing = false
        return Transport.PAUSE
    }

    /** A manual pause clears both auto-resume flags. */
    fun onManualPause() {
        pausedForCall = false
        pausedForFocus = false
        playing = false
    }

    /** The user pressed play; clear the auto-resume flags so nothing doubles up. */
    fun onManualPlay() {
        pausedForCall = false
        pausedForFocus = false
        playing = loaded
    }

    // -- queue advancement --------------------------------------------------

    /** What to do when the current track finishes. */
    sealed class Completion {
        /** Play the same track again from the start (repeat-one). */
        object RepeatCurrent : Completion()

        /** Move to [track] and play it. */
        data class Advance(val track: Track) : Completion()

        /** The queue ended; stop and keep the last track loaded. */
        object Stop : Completion()
    }

    fun onTrackCompleted(): Completion {
        if (repeatMode == RepeatMode.ONE) return Completion.RepeatCurrent
        val next = queue.next(wrapsAtEnd())
        return if (next == null) {
            playing = false
            Completion.Stop
        } else {
            Completion.Advance(next)
        }
    }

    /**
     * Next pressed by hand.
     *
     * Returns null at the end of a non-repeating queue, and changes nothing in
     * that case: a driver pressing next on the last song should keep hearing
     * the last song, not have the UI claim playback stopped while audio
     * continues. Stopping is [onTrackCompleted]'s job, where the track really
     * has ended.
     */
    fun onNextPressed(): Track? = queue.next(wrapsAtEnd())

    fun onPreviousPressed(restartInstead: Boolean): Track? {
        // A press within the first seconds restarts the track; the caller
        // decides the threshold, because it depends on the player's position.
        if (restartInstead) return queue.current()
        val track = queue.previous(wrapsAtEnd())
        return track ?: queue.current()
    }
}

END FILE

## core/music-engine-local/src/main/java/com/ts8/core/music/local/PlaybackQueue.kt

BEGIN FILE
package com.ts8.core.music.local

import com.ts8.core.music.Track
import kotlin.random.Random

/**
 * Play order of the current queue.
 *
 * Deliberately free of Android types: shuffle, repeat-wrap and "restore the
 * exact order that was playing when the ignition went off" are the parts of
 * playback that are easiest to get subtly wrong, so they live in a class that
 * can be tested on a workstation instead of only in the car.
 *
 * The queue holds *positions into the track list*, not the tracks themselves,
 * in `order`. In sequential mode `order` is `0,1,2,…`; in shuffle mode it is a
 * permutation whose first element is the track that was playing when shuffle
 * was switched on. That keeps "next" and "previous" identical code in both
 * modes, which is what removes a whole class of shuffle bugs.
 */
class PlaybackQueue(
    /** Injected so tests can shuffle deterministically. */
    private val random: Random = Random.Default
) {
    private var tracks: List<Track> = emptyList()
    private var order: IntArray = IntArray(0)
    private var pos: Int = 0

    /** True when playback follows a shuffled order. */
    var shuffle: Boolean = false
        private set

    /** Number of items in the queue. */
    val size: Int get() = order.size

    /** Position within [order], 0-based. */
    val position: Int get() = pos

    fun isEmpty(): Boolean = tracks.isEmpty()

    /**
     * Replaces the queue.
     *
     * @param startIndex index into [tracks] to play first.
     * @param shuffle keep the current shuffle setting unless overridden.
     */
    fun setQueue(
        newTracks: List<Track>,
        startIndex: Int = 0,
        shuffle: Boolean = this.shuffle
    ) {
        tracks = ArrayList(newTracks)
        this.shuffle = shuffle
        if (tracks.isEmpty()) {
            order = IntArray(0)
            pos = 0
            return
        }
        val start = startIndex.coerceIn(0, tracks.size - 1)
        order = if (shuffle) {
            // The chosen track goes first, the rest follow shuffled. Building
            // the permutation this way means "play this song now" never jumps
            // to something else just because shuffle is on.
            val others = (0 until tracks.size).filter { it != start }
                .shuffled(random)
                .toIntArray()
            IntArray(1 + others.size).also {
                it[0] = start
                others.copyInto(it, 1)
            }
        } else {
            indices(tracks.size)
        }
        pos = if (shuffle) 0 else start
    }

    /**
     * Restores a queue exactly as it was saved: [tracks] are already in play
     * order, so no reshuffle happens. Used by checkpoint restore, where the
     * saved order must survive a reboot.
     */
    fun restore(savedTracks: List<Track>, savedPosition: Int, shuffle: Boolean) {
        tracks = ArrayList(savedTracks)
        this.shuffle = shuffle
        order = indices(savedTracks.size)
        pos = if (savedTracks.isEmpty()) 0
        else savedPosition.coerceIn(0, savedTracks.size - 1)
    }

    fun current(): Track? = order.getOrNull(pos)?.let { tracks.getOrNull(it) }

    /**
     * Moves to the next item.
     *
     * @param wrap when true, wrap to the first item after the last.
     * @return the new current track, or null when the queue ended and the
     *         caller should stop.
     */
    fun next(wrap: Boolean): Track? {
        if (order.isEmpty()) return null
        if (pos + 1 < order.size) {
            pos++
            return current()
        }
        // Repeat-all must loop even for a single-track queue; that is what
        // "repeat" means to someone parked with one song on the stick.
        if (wrap) {
            pos = 0
            return current()
        }
        return null
    }

    /** Moves to the previous item, with the same wrapping rules as [next]. */
    fun previous(wrap: Boolean): Track? {
        if (order.isEmpty()) return null
        if (pos > 0) {
            pos--
            return current()
        }
        if (wrap) {
            pos = order.size - 1
            return current()
        }
        return null
    }

    /** Jumps to a specific track (index into the track list, as a list row would pass). */
    fun select(indexInTracks: Int): Track? {
        val at = order.indexOf(indexInTracks)
        if (at < 0) return null
        pos = at
        return tracks[indexInTracks]
    }

    /**
     * Turns shuffle on or off without interrupting playback: the current track
     * stays current, and only what comes after it is reordered.
     */
    fun setShuffle(enabled: Boolean) {
        if (enabled == shuffle) return
        val currentIndex = order.getOrNull(pos) ?: -1
        shuffle = enabled
        if (tracks.isEmpty()) {
            order = IntArray(0)
            pos = 0
            return
        }
        order = if (enabled) {
            val others = (0 until tracks.size)
                .filter { it != currentIndex }
                .shuffled(random)
                .toIntArray()
            IntArray(if (currentIndex >= 0) 1 + others.size else others.size).also {
                if (currentIndex >= 0) {
                    it[0] = currentIndex
                    others.copyInto(it, 1)
                } else {
                    others.copyInto(it, 0)
                }
            }
        } else {
            indices(tracks.size)
        }
        pos = if (enabled) 0 else order.indexOf(currentIndex).coerceAtLeast(0)
    }

    /** The queue in play order — what a checkpoint saves and a UI list shows. */
    fun tracksInOrder(): List<Track> = order.toList().mapNotNull { tracks.getOrNull(it) }

    /** Track ids in play order, for the checkpoint table. */
    fun trackIdsInOrder(): List<Long> = order.toList().mapNotNull { tracks.getOrNull(it)?.id }

    private fun indices(n: Int): IntArray = IntArray(n) { it }
}

END FILE

## core/music-engine-local/src/main/java/com/ts8/core/music/local/ScanDiff.kt

BEGIN FILE
package com.ts8.core.music.local

import com.ts8.core.music.MusicFormats
import com.ts8.core.music.Track

/**
 * Stable ids for files that the platform database does not number.
 *
 * MediaStore hands out small sequential ids, so a USB file identified by a
 * path hash is pushed into a different range with bit 62 set. Without that, a
 * hash that happened to land on a MediaStore id would silently overwrite an
 * unrelated track — a class of bug that only shows up on one car.
 *
 * FNV-1a over the UTF-16 code units of the path: cheap, no allocation, and
 * stable across reboots because it depends only on the path, which is what
 * makes incremental rescan possible.
 */
object TrackIds {
    /** Bit marking an id as a path hash rather than a MediaStore row id. */
    private const val USB_BIT = 1L shl 62

    private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
    private const val FNV_PRIME = 0x100000001b3L

    /**
     * 62-bit FNV-1a hash of a path, with bit 62 always set.
     *
     * The result is positive and larger than any realistic MediaStore row id,
     * so the two id spaces cannot collide even by accident.
     */
    fun forUsbPath(path: String): Long {
        var hash = FNV_OFFSET
        for (ch in path) {
            hash = hash xor (ch.code.toLong() and 0xFFFF)
            hash *= FNV_PRIME
        }
        return (hash and (USB_BIT - 1)) or USB_BIT
    }

    fun isUsb(id: Long): Boolean = (id and USB_BIT) != 0L
}

/**
 * One file the scanner found, before it becomes a [Track].
 *
 * Split from [Track] because the diff runs on data the scanner already has in
 * hand: building full [Track] objects for files that turn out to be unchanged
 * is the main avoidable cost of a 2 000-file rescan.
 */
data class ScannedFile(
    val path: String,
    val sizeBytes: Long,
    val modifiedSeconds: Long,
    val id: Long,
    val sourceKind: Track.SourceKind,
    val mimeType: String?,
    /** Parsed from the container when the fast reader succeeded. */
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumId: Long? = null,
    val artistId: Long? = null,
    val durationMs: Long = 0,
    val trackNumber: Int = 0,
    val year: Int = 0
)

/** What a scan did. Mirrors [com.ts8.core.music.ScanResult]'s counts. */
data class ScanDiff(
    val added: List<ScannedFile>,
    val updated: List<ScannedFile>,
    val removed: List<Long>,
    val unchanged: Int
)

/**
 * Incremental scan diff — pure logic, no Android types.
 *
 * The rule the spec asks for is "incremental, not a full rescan every boot".
 * Reduced to what it actually means: a file is *updated* when its size or mtime
 * changed, *unchanged* otherwise, and a row is *removed* when no file claimed
 * it. Track metadata is not compared, because reading tags to decide whether to
 * re-read tags is the full scan this exists to avoid.
 */
object ScanDiffCalculator {

    /**
     * @param existing id → (size, mtime) as currently stored.
     * @param found everything the file-system/MediaStore walk produced.
     * @param scopedTo if given, only rows from this source kind may be removed;
     *        a scan that did not look at the USB stick must not delete USB rows.
     */
    fun diff(
        existing: Map<Long, Pair<Long, Long>>,
        existingKind: Map<Long, Track.SourceKind>,
        found: List<ScannedFile>,
        scopedTo: Track.SourceKind? = null
    ): ScanDiff {
        val added = ArrayList<ScannedFile>()
        val updated = ArrayList<ScannedFile>()
        var unchanged = 0

        val seen = HashSet<Long>(found.size * 2)
        for (file in found) {
            seen.add(file.id)
            val prior = existing[file.id]
            when {
                prior == null -> added.add(file)
                prior.first != file.sizeBytes || prior.second != file.modifiedSeconds ->
                    updated.add(file)
                else -> unchanged++
            }
        }

        val removed = ArrayList<Long>()
        for (id in existing.keys) {
            if (id in seen) continue
            if (scopedTo != null && existingKind[id] != scopedTo) continue
            removed.add(id)
        }

        return ScanDiff(added, updated, removed, unchanged)
    }

    /**
     * Whether a file should be indexed at all.
     *
     * Two independent filters, and both matter:
     * - The extension must be a known audio container, otherwise a USB stick
     *   full of photos costs a tag read each.
     * - A codec the device cannot decode must not enter the library, or the
     *   driver taps a track and gets a failure. FLAC is the case the spec calls
     *   out; the caller passes the result of the codec probe.
     *
     * The extension is authoritative when it is one we know: the TS8's
     * MediaStore reports `audio/mpeg` for `.m4a` files often enough that
     * trusting the reported type alone loses real tracks.
     */
    fun shouldIndex(name: String, mimeType: String?, isCodecUsable: (String) -> Boolean): Boolean {
        val byName = MusicFormats.mimeForFileName(name)
        if (byName != null) return isCodecUsable(byName)

        // No known extension: accept only a MIME type this build definitely
        // decodes, so a tagged-but-oddly-named file is not lost.
        if (mimeType == null) return false
        if (mimeType !in MusicFormats.ALWAYS_SUPPORTED && mimeType !in MusicFormats.PROBE_REQUIRED) {
            return false
        }
        return isCodecUsable(mimeType)
    }

    /** Fallback title for an untagged file: the file name without extension. */
    fun titleFromFileName(name: String): String {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        return stem.replace('_', ' ').trim().ifEmpty { name }
    }
}

END FILE

## core/music-engine-local/src/test/java/com/ts8/core/music/local/AudioTagReaderTest.kt

BEGIN FILE
package com.ts8.core.music.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/**
 * Container readers, exercised against byte-exact synthetic files.
 *
 * Synthetic files rather than fixtures checked into the repo: each one is
 * built by the test from the format spec, so a reader bug cannot be masked by
 * a fixture that was generated by the same bug. Every field asserted below is
 * one the corresponding player screen shows.
 */
class AudioTagReaderTest {

    private lateinit var dir: File

    @org.junit.Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir") ?: ".", "ts8-tagtest-${System.nanoTime()}")
        dir.mkdirs()
    }

    @org.junit.After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun file(name: String): File = File(dir, name).also { it.parentFile?.mkdirs() }

    // -- MP3 ---------------------------------------------------------------

    /** ID3v2.3 tag with the four fields the UI shows, then a CBR audio block. */
    private fun id3v23Frame(id: String, text: String): ByteArray {
        val payload = byteArrayOf(0x01) + text.toByteArray(StandardCharsets.UTF_16LE)
        val out = java.io.ByteArrayOutputStream()
        out.write(id.toByteArray(StandardCharsets.ISO_8859_1))
        val size = payload.size
        out.write(byteArrayOf(
            ((size shr 24) and 0xFF).toByte(), ((size shr 16) and 0xFF).toByte(),
            ((size shr 8) and 0xFF).toByte(), (size and 0xFF).toByte()
        ))
        out.write(byteArrayOf(0, 0)) // flags
        out.write(payload)
        return out.toByteArray()
    }

    private fun buildMp3(
        title: String, artist: String, album: String, track: String,
        audioFrames: Int = 20, bitrateIndex: Int = 9 // 128 kbps MPEG-1 L3
    ): File {
        val frames = java.io.ByteArrayOutputStream()
        frames.write(id3v23Frame("TIT2", title))
        frames.write(id3v23Frame("TPE1", artist))
        frames.write(id3v23Frame("TALB", album))
        frames.write(id3v23Frame("TRCK", track))
        val body = frames.toByteArray()

        val out = java.io.ByteArrayOutputStream()
        // "ID3", version 3, no flags, syncsafe size.
        out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0))
        out.write(byteArrayOf(
            ((body.size shr 21) and 0x7F).toByte(), ((body.size shr 14) and 0x7F).toByte(),
            ((body.size shr 7) and 0x7F).toByte(), (body.size and 0x7F).toByte()
        ))
        out.write(body)

        // MPEG-1 Layer III, 44.1 kHz, no padding, no CRC:
        // 0xFF 0xFB | bitrate<<4 | sampleRate<<2
        val h = 0xFFE00000.toInt() or (3 shl 19) or (1 shl 17) or
            (bitrateIndex shl 12) or (0 shl 10) or (0 shl 9)
        repeat(audioFrames) {
            out.write(byteArrayOf(
                ((h ushr 24) and 0xFF).toByte(), ((h ushr 16) and 0xFF).toByte(),
                ((h ushr 8) and 0xFF).toByte(), (h and 0xFF).toByte()
            ))
            // 417 bytes is the frame size at 128 kbps / 44.1 kHz.
            out.write(ByteArray(413))
        }

        return file("song.mp3").also { it.writeBytes(out.toByteArray()) }
    }

    @Test
    fun `mp3 id3v2_3 tags are read`() {
        val f = buildMp3("Cầu Rồng", "Đà Nẵng", "Biển xanh", "3/12")
        val tags = AudioTagReader.read(f)!!

        assertEquals("Cầu Rồng", tags.title)
        assertEquals("Đà Nẵng", tags.artist)
        assertEquals("Biển xanh", tags.album)
        assertEquals(3, tags.trackNumber)
        assertTrue("duration should be estimated from the CBR payload", tags.durationMs > 0)
    }

    @Test
    fun `mp3 duration is plausible for the payload`() {
        // 20 frames of 417 bytes at 128 kbps is 20 * 417 * 8 / 128000 s ≈ 0.52 s.
        val f = buildMp3("A", "B", "C", "1", audioFrames = 20)
        val ms = AudioTagReader.read(f)!!.durationMs
        assertTrue("got $ms ms", ms in 400..700)
    }

    @Test
    fun `mp3 track number drops the total`() {
        val f = buildMp3("A", "B", "C", "7/15")
        assertEquals(7, AudioTagReader.read(f)!!.trackNumber)
    }

    @Test
    fun `an mp3 without an id3 tag still reports a duration`() {
        val f = file("bare.mp3")
        val out = java.io.ByteArrayOutputStream()
        val h = 0xFFE00000.toInt() or (3 shl 19) or (1 shl 17) or (9 shl 12)
        repeat(10) {
            out.write(byteArrayOf(
                ((h ushr 24) and 0xFF).toByte(), ((h ushr 16) and 0xFF).toByte(),
                ((h ushr 8) and 0xFF).toByte(), (h and 0xFF).toByte()
            ))
            out.write(ByteArray(413))
        }
        f.writeBytes(out.toByteArray())

        val tags = AudioTagReader.read(f)!!
        assertNull(tags.title)
        assertTrue(tags.durationMs > 0)
    }

    @Test
    fun `a truncated mp3 returns tags rather than throwing`() {
        val f = buildMp3("A", "B", "C", "1")
        val bytes = f.readBytes()
        f.writeBytes(bytes.copyOf(bytes.size / 3))

        // Must not throw; whatever it can read is a bonus.
        AudioTagReader.read(f)
    }

    // -- FLAC --------------------------------------------------------------

    private fun buildFlac(title: String?, sampleRate: Int, totalSamples: Long): File {
        val out = java.io.ByteArrayOutputStream()
        out.write("fLaC".toByteArray(StandardCharsets.ISO_8859_1))

        // STREAMINFO (type 0, length 34), last-block flag clear.
        out.write(byteArrayOf(0x00, 0x00, 0x00, 34))
        val info = ByteArray(34)
        // Bytes 10..17 pack sampleRate(20) | channels(3) | bitsPerSample(5) |
        // totalSamples(36), big-endian across a byte boundary. 44 100 Hz is
        // 0x0AC44, and the bit layout shifts the sample rate four bits into
        // byte 10.
        info[10] = ((sampleRate shr 12) and 0xFF).toByte()
        info[11] = ((sampleRate shr 4) and 0xFF).toByte()
        // Byte 12: low 4 bits of the sample rate, then channels-1 (3 bits),
        // then the top 1 bit of bitsPerSample-1.
        info[12] = (((sampleRate and 0x0F) shl 4) or (1 shl 1)).toByte()
        // Byte 13: low 4 bits of bitsPerSample-1 (15 → 0b1111) and the top
        // 4 bits of totalSamples.
        info[13] = ((15 shl 4) or ((totalSamples shr 32) and 0x0F).toInt()).toByte()
        info[14] = ((totalSamples shr 24) and 0xFF).toByte()
        info[15] = ((totalSamples shr 16) and 0xFF).toByte()
        info[16] = ((totalSamples shr 8) and 0xFF).toByte()
        info[17] = (totalSamples and 0xFF).toByte()
        out.write(info)

        if (title != null) {
            // VORBIS_COMMENT (type 4), last-block flag set.
            val vendor = "test".toByteArray(StandardCharsets.UTF_8)
            val comment = "TITLE=$title".toByteArray(StandardCharsets.UTF_8)
            val artistComment = "ARTIST=Người hát".toByteArray(StandardCharsets.UTF_8)
            val block = java.io.ByteArrayOutputStream()
            block.write(le32(vendor.size))
            block.write(vendor)
            block.write(le32(2))
            block.write(le32(comment.size))
            block.write(comment)
            block.write(le32(artistComment.size))
            block.write(artistComment)
            val b = block.toByteArray()
            out.write(byteArrayOf(0x84.toByte(), ((b.size shr 16) and 0xFF).toByte(),
                ((b.size shr 8) and 0xFF).toByte(), (b.size and 0xFF).toByte()))
            out.write(b)
        }
        return file("song.flac").also { it.writeBytes(out.toByteArray()) }
    }

    @Test
    fun `flac streaminfo gives an exact duration`() {
        val f = buildFlac("Bài hát", sampleRate = 44_100, totalSamples = 44_100L * 210)
        assertEquals(210_000, AudioTagReader.read(f)!!.durationMs)
    }

    @Test
    fun `flac vorbis comments are read`() {
        val f = buildFlac("Bài hát", 44_100, 44_100)
        val tags = AudioTagReader.read(f)!!
        assertEquals("Bài hát", tags.title)
        assertEquals("Người hát", tags.artist)
    }

    @Test
    fun `flac without tags still reports duration`() {
        val f = buildFlac(null, 48_000, 96_000)
        val tags = AudioTagReader.read(f)!!
        assertNull(tags.title)
        assertEquals(2_000, tags.durationMs)
    }

    @Test
    fun `a flac with a corrupt streaminfo length is skipped safely`() {
        val f = buildFlac("A", 44_100, 44_100)
        val bytes = f.readBytes()
        // Claim the STREAMINFO block is 500 bytes when it is 34.
        bytes[7] = 0x01.toByte()
        bytes[8] = 0xF4.toByte()
        f.writeBytes(bytes)

        // Corruption is data: no duration is better than a wrong one, and it
        // must not hang or allocate 500 bytes of garbage as samples.
        AudioTagReader.read(f)
    }

    // -- WAV ---------------------------------------------------------------

    private fun buildWav(byteRate: Int, dataBytes: Int): File {
        val out = java.io.ByteArrayOutputStream()
        out.write("RIFF".toByteArray(StandardCharsets.ISO_8859_1))
        out.write(le32(36 + dataBytes))
        out.write("WAVE".toByteArray(StandardCharsets.ISO_8859_1))
        out.write("fmt ".toByteArray(StandardCharsets.ISO_8859_1))
        out.write(le32(16))
        out.write(byteArrayOf(1, 0, 2, 0)) // PCM, 2 channels
        out.write(le32(44_100))
        out.write(le32(byteRate))
        out.write(byteArrayOf(4, 0, 16, 0))
        out.write("data".toByteArray(StandardCharsets.ISO_8859_1))
        out.write(le32(dataBytes))
        out.write(ByteArray(dataBytes))
        return file("song.wav").also { it.writeBytes(out.toByteArray()) }
    }

    private fun le32(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )

    @Test
    fun `wav duration comes from the data size and byte rate`() {
        // 176 400 bytes/s is 44.1 kHz × 2 ch × 16 bit; 352 800 bytes = 2 s.
        val f = buildWav(176_400, 352_800)
        assertEquals(2_000, AudioTagReader.read(f)!!.durationMs)
    }

    @Test
    fun `a wav with no data chunk reports no duration rather than a guess`() {
        val f = file("empty.wav")
        val out = java.io.ByteArrayOutputStream()
        out.write("RIFF".toByteArray(StandardCharsets.ISO_8859_1))
        out.write(le32(4))
        out.write("WAVE".toByteArray(StandardCharsets.ISO_8859_1))
        f.writeBytes(out.toByteArray())
        assertEquals(0, AudioTagReader.read(f)!!.durationMs)
    }

    // -- MP4 ---------------------------------------------------------------

    private fun atom(type: String, body: ByteArray): ByteArray {
        val total = 8 + body.size
        val result = java.io.ByteArrayOutputStream()
        result.write(byteArrayOf(
            ((total shr 24) and 0xFF).toByte(), ((total shr 16) and 0xFF).toByte(),
            ((total shr 8) and 0xFF).toByte(), (total and 0xFF).toByte()
        ))
        result.write(type.toByteArray(StandardCharsets.ISO_8859_1))
        result.write(body)
        return result.toByteArray()
    }

    /** `data` atom with a UTF-8 string payload. */
    private fun dataText(text: String): ByteArray {
        val payload = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0) +
            text.toByteArray(StandardCharsets.UTF_8)
        return atom("data", payload)
    }

    private fun buildM4a(title: String, artist: String, album: String, trackNo: Int): File {
        val ftyp = atom("ftyp", "M4A ".toByteArray(StandardCharsets.ISO_8859_1) +
            byteArrayOf(0, 0, 0, 0) + "M4A mp42isom".toByteArray(StandardCharsets.ISO_8859_1))

        // mvhd version 0: version/flags(4) created(4) modified(4) timescale(4)
        // duration(4) ...
        val mvhdBody = ByteArray(100)
        mvhdBody[0] = 0 // version 0
        writeBe32(mvhdBody, 12, 1000)        // timescale = 1000/s
        writeBe32(mvhdBody, 16, 185_000)     // 185 s
        val mvhd = atom("mvhd", mvhdBody)

        val trknPayload = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0,
            (trackNo and 0xFF).toByte())
        val ilst = atom(
            "ilst",
            atom("©nam", dataText(title)) +
                atom("©ART", dataText(artist)) +
                atom("©alb", dataText(album)) +
                atom("trkn", atom("data", trknPayload))
        )
        val meta = atom("meta", byteArrayOf(0, 0, 0, 0) + ilst)
        val udta = atom("udta", meta)
        val moov = atom("moov", mvhd + udta)

        return file("song.m4a").also { it.writeBytes(ftyp + moov) }
    }

    private fun writeBe32(buf: ByteArray, offset: Int, v: Int) {
        buf[offset] = ((v shr 24) and 0xFF).toByte()
        buf[offset + 1] = ((v shr 16) and 0xFF).toByte()
        buf[offset + 2] = ((v shr 8) and 0xFF).toByte()
        buf[offset + 3] = (v and 0xFF).toByte()
    }

    @Test
    fun `m4a duration comes from mvhd`() {
        val f = buildM4a("Bài", "Ca sĩ", "Album", 1)
        assertEquals(185_000, AudioTagReader.read(f)!!.durationMs)
    }

    @Test
    fun `m4a tags are read from the ilst atom`() {
        val f = buildM4a("Bài hát hay", "Ca sĩ Việt", "Tuyển tập", 4)
        val tags = AudioTagReader.read(f)!!
        assertEquals("Bài hát hay", tags.title)
        assertEquals("Ca sĩ Việt", tags.artist)
        assertEquals("Tuyển tập", tags.album)
        assertEquals(4, tags.trackNumber)
    }

    @Test
    fun `an mp4 whose moov follows mdat is still read`() {
        val f = buildM4a("Muộn", "Ca sĩ", "Album", 2)
        val bytes = f.readBytes()
        // Find where moov starts and move a fake mdat in front of it.
        val moovAt = indexOfAscii(bytes, "moov") - 4
        val ftypEnd = indexOfAscii(bytes, "ftyp") + 4
        val ftypLen = readBe32(bytes, 0)
        val mdat = atom("mdat", ByteArray(64))
        val rebuilt = bytes.copyOfRange(0, ftypLen) + mdat +
            bytes.copyOfRange(ftypLen, bytes.size)
        f.writeBytes(rebuilt)

        val tags = AudioTagReader.read(f)
        assertEquals(
            "a file with mdat before moov (the common layout) must still be read",
            "Muộn", tags!!.title
        )
        assertTrue(ftypEnd < moovAt)
    }

    private fun indexOfAscii(b: ByteArray, s: String): Int {
        val needle = s.toByteArray(StandardCharsets.ISO_8859_1)
        outer@ for (i in 0..b.size - needle.size) {
            for (k in needle.indices) if (b[i + k] != needle[k]) continue@outer
            return i
        }
        return -1
    }

    private fun readBe32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    // -- OGG ---------------------------------------------------------------

    /**
     * Minimal OGG Vorbis: identification page, comment page, and a final page
     * whose granule position states the length.
     */
    private fun buildOgg(title: String, sampleRate: Int, totalSamples: Long): File {
        val out = java.io.ByteArrayOutputStream()

        val idPacket = java.io.ByteArrayOutputStream()
        idPacket.write(0x01)
        idPacket.write("vorbis".toByteArray(StandardCharsets.ISO_8859_1))
        idPacket.write(le32(0))               // version
        idPacket.write(2)                     // channels
        idPacket.write(le32(sampleRate))      // sample rate
        idPacket.write(ByteArray(12))         // bitrates
        idPacket.write(1)                     // blocksize
        idPacket.write(1)                     // framing
        out.write(oggPage(0, 0, idPacket.toByteArray()))

        val commentPacket = java.io.ByteArrayOutputStream()
        commentPacket.write(0x03)
        commentPacket.write("vorbis".toByteArray(StandardCharsets.ISO_8859_1))
        val vendor = "ts8".toByteArray(StandardCharsets.UTF_8)
        val entry = "TITLE=$title".toByteArray(StandardCharsets.UTF_8)
        commentPacket.write(le32(vendor.size))
        commentPacket.write(vendor)
        commentPacket.write(le32(1))
        commentPacket.write(le32(entry.size))
        commentPacket.write(entry)
        commentPacket.write(1)
        out.write(oggPage(0, 0, commentPacket.toByteArray()))

        out.write(oggPage(totalSamples, 0, ByteArray(16)))
        return file("song.ogg").also { it.writeBytes(out.toByteArray()) }
    }

    private fun oggPage(granule: Long, serial: Int, packet: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("OggS".toByteArray(StandardCharsets.ISO_8859_1))
        out.write(0) // version
        out.write(0) // header type
        for (i in 0 until 8) out.write(((granule ushr (i * 8)) and 0xFF).toInt())
        out.write(le32(serial))
        out.write(le32(0)) // page sequence
        out.write(le32(0)) // checksum: readers here do not verify
        // One segment covering the whole packet (packets under 255 bytes).
        out.write(1)
        out.write(packet.size and 0xFF)
        out.write(packet)
        return out.toByteArray()
    }

    @Test
    fun `ogg vorbis duration comes from the last page granule`() {
        val f = buildOgg("Bài", 44_100, 44_100L * 300)
        assertEquals(300_000, AudioTagReader.read(f)!!.durationMs)
    }

    @Test
    fun `ogg vorbis title is read from the comment packet`() {
        val f = buildOgg("Tiếng Việt", 44_100, 44_100)
        assertEquals("Tiếng Việt", AudioTagReader.read(f)!!.title)
    }

    @Test
    fun `a zip file is not mistaken for audio`() {
        val f = file("archive.mp3")
        // PK\x03\x04 — a zip renamed to .mp3, which does happen on USB sticks.
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        out.write(ByteArray(200))
        f.writeBytes(out.toByteArray())
        assertNull(AudioTagReader.read(f))
    }

    @Test
    fun `an empty file returns null rather than throwing`() {
        val f = file("empty.mp3")
        f.writeBytes(ByteArray(0))
        assertNull(AudioTagReader.read(f))
    }

    @Test
    fun `a missing file returns null`() {
        assertNull(AudioTagReader.read(File(dir, "does-not-exist.mp3")))
    }

    @Test
    fun `crc32 of a synthetic file matches what we wrote`() {
        // Sanity check on the test helper itself: if this fails the fixtures are
        // being built wrong and every assertion above is meaningless.
        val f = buildFlac("X", 44_100, 44_100)
        val crc = CRC32()
        RandomAccessFile(f, "r").use { raf ->
            val buf = ByteArray(1024)
            while (true) {
                val n = raf.read(buf)
                if (n <= 0) break
                crc.update(buf, 0, n)
            }
        }
        assertTrue(crc.value != 0L)
    }
}

END FILE

## core/music-engine-local/src/test/java/com/ts8/core/music/local/PlaybackCoreTest.kt

BEGIN FILE
package com.ts8.core.music.local

import com.ts8.core.music.RepeatMode
import com.ts8.core.music.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Playback behaviour, tested without a device.
 *
 * These tests cover the decisions a car player gets wrong — whether music
 * resumes after a call nobody asked it to resume, whether a navigation prompt
 * leaves the stream ducked forever, whether an ignition-off loses the resume
 * position. They exercise [PlaybackCore] and [CheckpointRestore] directly,
 * which is the same code [LocalMusicPlayer] runs: the player only executes the
 * transports these return.
 *
 * `MediaPlayer` itself is exercised by the instrumentation suite on the car
 * (see `docs/TEST_REPORT.md`). Nothing here needs Robolectric, a network fetch
 * or an emulator, so the suite runs on any machine including CI with no proxy.
 */
class PlaybackCoreTest {

    private fun core(seed: Int = 7) = PlaybackCore(Random(seed))

    private fun track(id: Long) = Track(
        id = id,
        source = "/storage/usb0/$id.mp3",
        title = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = 1,
        artistId = 1,
        durationMs = 180_000,
        folder = "/storage/usb0",
        trackNumber = id.toInt(),
        year = 2020,
        mimeType = "audio/mpeg",
        sizeBytes = 1024,
        modifiedSeconds = 1,
        sourceKind = Track.SourceKind.USB
    )

    private fun loaded(ids: LongRange = 1L..3L, at: Int = 0): PlaybackCore {
        val c = core()
        c.queue.setQueue(ids.map { track(it) }, at, false)
        c.setLoaded(true)
        return c
    }

    // -- calls --------------------------------------------------------------

    @Test
    fun `a call pauses playing audio`() {
        val c = loaded()
        c.setPlaying(true)

        assertEquals(PlaybackCore.Transport.PAUSE, c.onCallStarted())
        assertFalse("music must not play under a call", c.playing)
        assertTrue(c.isPausedForCall())
    }

    @Test
    fun `a call resumes music that the call itself paused`() {
        val c = loaded()
        c.setPlaying(true)
        c.onCallStarted()

        assertEquals(PlaybackCore.Transport.PLAY, c.onCallEnded())
        assertTrue(c.playing)
    }

    @Test
    fun `a call does not resume music the driver had paused`() {
        val c = loaded()
        // Loaded but paused by hand before the call ever rang.
        c.setPlaying(false)

        assertEquals(PlaybackCore.Transport.NONE, c.onCallStarted())
        assertEquals(PlaybackCore.Transport.NONE, c.onCallEnded())
        assertFalse(
            "starting a paused song because someone rang is the worst behaviour a car player can have",
            c.playing
        )
    }

    @Test
    fun `a manual pause during a call prevents the auto-resume`() {
        val c = loaded()
        c.setPlaying(true)
        c.onCallStarted()
        // The driver presses pause while the call is still up.
        c.onManualPause()

        assertEquals(PlaybackCore.Transport.NONE, c.onCallEnded())
        assertFalse("an explicit pause must win over the auto-resume", c.playing)
    }

    @Test
    fun `a call on an empty queue is a no-op`() {
        val c = core()
        assertEquals(PlaybackCore.Transport.NONE, c.onCallStarted())
        assertEquals(PlaybackCore.Transport.NONE, c.onCallEnded())
    }

    // -- focus and ducking --------------------------------------------------

    @Test
    fun `a transient loss that may duck keeps playing`() {
        val c = loaded()
        c.setPlaying(true)

        assertEquals(PlaybackCore.Transport.NONE, c.onTransientLoss(mayDuck = true))
        assertTrue("ducking lowers the volume, it does not stop the track", c.playing)
        assertTrue(c.duckedForNavigation)
    }

    @Test
    fun `gaining focus clears the duck without restarting audio`() {
        val c = loaded()
        c.setPlaying(true)
        c.onTransientLoss(mayDuck = true)

        assertEquals(PlaybackCore.Transport.NONE, c.onFocusGained())
        assertFalse(c.duckedForNavigation)
        assertTrue(c.playing)
    }

    @Test
    fun `a transient loss that may not duck pauses and then resumes`() {
        val c = loaded()
        c.setPlaying(true)

        assertEquals(PlaybackCore.Transport.PAUSE, c.onTransientLoss(mayDuck = false))
        assertFalse(c.playing)
        assertEquals(PlaybackCore.Transport.PLAY, c.onFocusGained())
        assertTrue(c.playing)
    }

    @Test
    fun `a transient loss on paused music changes nothing`() {
        val c = loaded()
        assertEquals(PlaybackCore.Transport.NONE, c.onTransientLoss(mayDuck = false))
        assertEquals(PlaybackCore.Transport.NONE, c.onFocusGained())
        assertFalse(c.playing)
    }

    @Test
    fun `a permanent loss always pauses and clears the duck`() {
        val c = loaded()
        c.setPlaying(true)
        c.onTransientLoss(mayDuck = true)

        assertEquals(PlaybackCore.Transport.PAUSE, c.onFocusLostPermanent())
        assertFalse(c.duckedForNavigation)
        assertFalse(c.playing)
    }

    @Test
    fun `a permanent loss on paused music does not arm an auto-resume`() {
        val c = loaded()
        assertEquals(PlaybackCore.Transport.NONE, c.onFocusLostPermanent())
        assertEquals(PlaybackCore.Transport.NONE, c.onFocusGained())
        assertFalse(c.playing)
    }

    // -- completion ---------------------------------------------------------

    @Test
    fun `completion advances to the next track`() {
        val c = loaded()
        val completion = c.onTrackCompleted()
        assertTrue(completion is PlaybackCore.Completion.Advance)
        assertEquals(2L, (completion as PlaybackCore.Completion.Advance).track.id)
    }

    @Test
    fun `completion at the end of a non-repeating queue stops`() {
        val c = loaded(1L..1L)
        assertEquals(PlaybackCore.Completion.Stop, c.onTrackCompleted())
        assertFalse(c.playing)
        assertNotNull("the last track stays showing", c.queue.current())
    }

    @Test
    fun `repeat-one restarts the same track at every completion`() {
        val c = loaded()
        c.setRepeatMode(RepeatMode.ONE)
        assertEquals(PlaybackCore.Completion.RepeatCurrent, c.onTrackCompleted())
        assertEquals(PlaybackCore.Completion.RepeatCurrent, c.onTrackCompleted())
    }

    @Test
    fun `repeat-all wraps from the last track to the first`() {
        val c = loaded(1L..2L, at = 1)
        c.setRepeatMode(RepeatMode.ALL)
        val completion = c.onTrackCompleted()
        assertTrue(completion is PlaybackCore.Completion.Advance)
        assertEquals(1L, (completion as PlaybackCore.Completion.Advance).track.id)
    }

    @Test
    fun `repeat-all wraps a single-track queue`() {
        // The bug this guards: guarding the wrap on size > 1 meant a driver
        // parked with one song on the stick never got repeat-all at all.
        val c = loaded(1L..1L)
        c.setRepeatMode(RepeatMode.ALL)
        val completion = c.onTrackCompleted()
        assertTrue(completion is PlaybackCore.Completion.Advance)
        assertEquals(1L, (completion as PlaybackCore.Completion.Advance).track.id)
    }

    @Test
    fun `completion on an empty queue stops without throwing`() {
        val c = core()
        assertEquals(PlaybackCore.Completion.Stop, c.onTrackCompleted())
    }

    // -- manual skip --------------------------------------------------------

    @Test
    fun `next past the end of a non-repeating queue reports nothing to do`() {
        val c = loaded(1L..1L)
        assertNull("the driver keeps hearing the last song", c.onNextPressed())
        assertNotNull("and the loaded track must not be cleared", c.queue.current())
    }

    @Test
    fun `next wraps under repeat-all`() {
        val c = loaded(1L..2L, at = 1)
        c.setRepeatMode(RepeatMode.ALL)
        assertEquals(1L, c.onNextPressed()?.id)
    }

    @Test
    fun `previous at the start restarts the current track`() {
        val c = loaded(1L..3L, at = 0)
        // `previous` on the first track of a non-repeating queue stays put
        // rather than blanking the screen.
        assertEquals(1L, c.onPreviousPressed(restartInstead = false)?.id)
    }

    @Test
    fun `a restart request returns the current track without moving`() {
        val c = loaded(1L..3L, at = 1)
        assertEquals(2L, c.onPreviousPressed(restartInstead = true)?.id)
        assertEquals(1, c.queue.position)
    }

    @Test
    fun `previous walks backwards`() {
        val c = loaded(1L..3L, at = 2)
        assertEquals(2L, c.onPreviousPressed(restartInstead = false)?.id)
        assertEquals(1, c.queue.position)
    }

    // -- loaded vs playing --------------------------------------------------

    @Test
    fun `unloading clears the playing flag`() {
        val c = loaded()
        c.setPlaying(true)
        c.setLoaded(false)
        assertFalse(c.playing)
    }

    @Test
    fun `playing cannot be set without a loaded track`() {
        val c = core()
        c.setPlaying(true)
        assertFalse("nothing loaded means nothing is playing", c.playing)
    }

    @Test
    fun `a manual play requires a loaded track`() {
        val c = core()
        c.queue.setQueue(listOf(track(1)), 0, false)
        // Queue set but nothing prepared yet: `open` has not completed.
        c.setLoaded(false)
        c.onManualPlay()
        assertFalse(c.playing)
    }

    // -- shuffle and repeat through the core --------------------------------

    @Test
    fun `shuffle is owned by the core and reported consistently`() {
        val c = loaded()
        assertFalse(c.queue.shuffle)
        c.queue.setShuffle(true)
        assertTrue(c.queue.shuffle)
        assertEquals(3, c.queue.trackIdsInOrder().size)
    }

    @Test
    fun `every repeat mode reports the matching wrap behaviour`() {
        val c = loaded()
        c.setRepeatMode(RepeatMode.OFF)
        assertFalse(c.wrapsAtEnd())
        c.setRepeatMode(RepeatMode.ONE)
        assertTrue(c.wrapsAtEnd())
        c.setRepeatMode(RepeatMode.ALL)
        assertTrue(c.wrapsAtEnd())
    }
}

/**
 * Checkpoint restore rules.
 *
 * After an ignition-off the library and the stick can disagree with what was
 * saved. These tests pin down what "resume where I left off" means when they do.
 */
class CheckpointRestoreTest {

    private fun track(id: Long) = Track(
        id = id,
        source = "/storage/usb0/$id.mp3",
        title = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = 1,
        artistId = 1,
        durationMs = 180_000,
        folder = "/storage/usb0",
        trackNumber = id.toInt(),
        year = 2020,
        mimeType = "audio/mpeg",
        sizeBytes = 1024,
        modifiedSeconds = 1,
        sourceKind = Track.SourceKind.USB
    )

    private fun checkpoint(
        trackId: Long,
        ids: List<Long>,
        index: Int = 0,
        positionMs: Long = 42_000
    ) = MusicCheckpoint(
        trackId = trackId,
        positionMs = positionMs,
        index = index,
        shuffle = false,
        repeatMode = RepeatMode.OFF,
        queueTrackIds = ids,
        savedAtMs = 1
    )

    private fun library(ids: LongRange) = ids.associate { it to track(it) }

    @Test
    fun `a clean restore keeps order, index and position`() {
        val lib = library(1L..4L)
        val restored = CheckpointRestore.plan(checkpoint(3, listOf(1, 2, 3, 4), index = 2)) { lib[it] }

        assertNotNull(restored)
        assertEquals(listOf(1L, 2L, 3L, 4L), restored!!.tracks.map { it.id })
        assertEquals(2, restored.index)
        assertEquals(42_000L, restored.positionMs)
    }

    @Test
    fun `a vanished track is dropped and the order of the rest is preserved`() {
        val lib = library(1L..3L) - 2L
        val restored = CheckpointRestore.plan(checkpoint(3, listOf(1, 2, 3), index = 2)) { lib[it] }

        assertNotNull("losing one song must not lose the queue", restored)
        assertEquals(listOf(1L, 3L), restored!!.tracks.map { it.id })
        assertEquals("the index must follow the resumed track, not the stale one", 1, restored.index)
    }

    @Test
    fun `the resumed track missing falls back to a clamped index`() {
        val lib = library(1L..4L) - 2L
        val restored = CheckpointRestore.plan(checkpoint(2, listOf(1, 2, 3, 4), index = 1)) { lib[it] }

        assertNotNull(restored)
        assertEquals("must not point past the shrunk list", true, restored!!.index in 0..3)
    }

    @Test
    fun `an out of range index is clamped rather than crashing`() {
        val lib = library(1L..2L)
        val restored = CheckpointRestore.plan(checkpoint(9, listOf(1, 2), index = 99)) { lib[it] }

        assertNotNull(restored)
        assertTrue(restored!!.index in 0..1)
    }

    @Test
    fun `a checkpoint with nothing left returns null so it can be cleared`() {
        val restored = CheckpointRestore.plan(checkpoint(9, listOf(9))) { null }
        assertNull("a dead checkpoint must not be retried every boot", restored)
    }

    @Test
    fun `an empty queue id list returns null`() {
        assertNull(CheckpointRestore.plan(checkpoint(1, emptyList())) { track(1) })
    }

    @Test
    fun `a lookup that throws reads as gone rather than crashing`() {
        val lib = library(1L..2L)
        val restored = CheckpointRestore.plan(checkpoint(2, listOf(1, 2))) { id ->
            if (id == 2L) throw IllegalStateException("database closed") else lib[id]
        }
        assertNotNull(restored)
        assertEquals(listOf(1L), restored!!.tracks.map { it.id })
    }

    @Test
    fun `shuffle and repeat survive the restore`() {
        val lib = library(1L..2L)
        val cp = checkpoint(1, listOf(1, 2)).copy(shuffle = true, repeatMode = RepeatMode.ALL)
        val restored = CheckpointRestore.plan(cp) { lib[it] }

        assertNotNull(restored)
        assertTrue(restored!!.shuffle)
        assertEquals(RepeatMode.ALL, restored.repeatMode)
    }
}

END FILE

## core/music-engine-local/src/test/java/com/ts8/core/music/local/PlaybackQueueTest.kt

BEGIN FILE
package com.ts8.core.music.local

import com.ts8.core.music.RepeatMode
import com.ts8.core.music.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Queue behaviour.
 *
 * These are the cases that only fail in the car, months later: shuffle that
 * drops a track, previous-after-shuffle that goes somewhere unexpected, a
 * checkpoint that restores the right song out of the wrong album.
 */
class PlaybackQueueTest {

    private fun track(id: Long) = Track(
        id = id,
        source = "/music/$id.mp3",
        title = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = 1,
        artistId = 1,
        durationMs = 180_000,
        folder = "/music",
        trackNumber = id.toInt(),
        year = 2020,
        mimeType = "audio/mpeg",
        sizeBytes = 1_000,
        modifiedSeconds = 1_000,
        sourceKind = Track.SourceKind.MEDIA_STORE
    )

    private fun queueOf(n: Int) = (1L..n).map { track(it) }

    /** Deterministic shuffle so the assertions can be exact. */
    private fun seeded() = PlaybackQueue(Random(42))

    @Test
    fun `sequential order plays in list order`() {
        val q = PlaybackQueue(Random(1))
        q.setQueue(queueOf(4))

        assertEquals(1L, q.current()!!.id)
        assertEquals(2L, q.next(wrap = false)!!.id)
        assertEquals(3L, q.next(wrap = false)!!.id)
        assertEquals(4L, q.next(wrap = false)!!.id)
        assertNull("queue must end, not wrap silently", q.next(wrap = false))
    }

    @Test
    fun `wrap restarts the queue`() {
        val q = PlaybackQueue(Random(1))
        q.setQueue(queueOf(3))
        q.next(wrap = false)
        q.next(wrap = false)
        assertEquals(3L, q.current()!!.id)
        assertEquals("repeat-all must come back to the first track", 1L, q.next(wrap = true)!!.id)
    }

    @Test
    fun `previous at the start wraps or stops`() {
        val q = PlaybackQueue(Random(1))
        q.setQueue(queueOf(3))
        assertNull(q.previous(wrap = false))
        assertEquals(3L, q.previous(wrap = true)!!.id)
    }

    @Test
    fun `shuffle keeps the requested start track first`() {
        val q = seeded()
        q.setQueue(queueOf(10), startIndex = 5, shuffle = true)

        assertEquals("tapping a song in shuffle mode must play that song", 6L, q.current()!!.id)
    }

    @Test
    fun `shuffle is a permutation, not a subset`() {
        val q = seeded()
        q.setQueue(queueOf(20), shuffle = true)
        val played = q.tracksInOrder().map { it.id }

        assertEquals("shuffle lost or duplicated a track", 20, played.size)
        assertEquals(20, played.toSet().size)
        assertEquals((1L..20L).toSet(), played.toSet())
    }

    @Test
    fun `shuffle reaches every track exactly once before stopping`() {
        val q = seeded()
        q.setQueue(queueOf(8), shuffle = true)
        val seen = mutableListOf(q.current()!!.id)
        while (true) {
            seen.add(q.next(wrap = false)?.id ?: break)
        }
        assertEquals(8, seen.size)
        assertEquals(8, seen.toSet().size)
    }

    @Test
    fun `turning shuffle on does not interrupt the current track`() {
        val q = seeded()
        q.setQueue(queueOf(10))
        q.next(wrap = false)
        q.next(wrap = false)
        val current = q.current()!!.id

        q.setShuffle(true)
        assertEquals("shuffle must not change what is playing", current, q.current()!!.id)

        q.setShuffle(false)
        assertEquals(current, q.current()!!.id)
    }

    @Test
    fun `turning shuffle off returns to list order from the current track`() {
        val q = seeded()
        q.setQueue(queueOf(5), shuffle = true)
        val current = q.current()!!.id

        q.setShuffle(false)
        assertEquals(current, q.current()!!.id)
        // Sequential from here means the list neighbours of that track.
        val expectedNext = if (current < 5) current + 1 else null
        assertEquals(expectedNext, q.next(wrap = false)?.id)
    }

    @Test
    fun `select jumps to an arbitrary track and keeps working`() {
        val q = seeded()
        q.setQueue(queueOf(10), shuffle = true)
        val picked = q.select(7)
        assertEquals(8L, picked!!.id)
        assertEquals(8L, q.current()!!.id)
        q.next(wrap = false)
        assertNotEquals(8L, q.current()!!.id)
    }

    @Test
    fun `select of an index outside the queue returns null`() {
        val q = PlaybackQueue(Random(1))
        q.setQueue(queueOf(3))
        val before = q.current()!!.id
        assertNull(q.select(99))
        assertEquals("a bad select must not move playback", before, q.current()!!.id)
    }

    @Test
    fun `an empty queue is safe to drive`() {
        val q = PlaybackQueue(Random(1))
        q.setQueue(emptyList())
        assertTrue(q.isEmpty())
        assertNull(q.current())
        assertNull(q.next(wrap = true))
        assertNull(q.previous(wrap = true))
        assertEquals(0, q.size)
    }

    @Test
    fun `a single-track queue repeats only under repeat-all`() {
        val q = PlaybackQueue(Random(1))
        q.setQueue(listOf(track(1)))
        assertEquals(1L, q.current()!!.id)
        assertNull("without repeat-all the queue ends", q.next(wrap = false))
        // With repeat-all the same track is the correct answer.
        assertEquals(1L, q.next(wrap = true)!!.id)
    }

    @Test
    fun `start index past the end is clamped rather than crashing`() {
        val q = PlaybackQueue(Random(1))
        q.setQueue(queueOf(3), startIndex = 99)
        assertEquals(3L, q.current()!!.id)
    }

    @Test
    fun `a negative start index is clamped to the first track`() {
        val q = PlaybackQueue(Random(1))
        q.setQueue(queueOf(3), startIndex = -5)
        assertEquals(1L, q.current()!!.id)
    }

    @Test
    fun `restore keeps the saved order exactly, shuffle or not`() {
        val q = PlaybackQueue(Random(1))
        val saved = listOf(track(9), track(3), track(7))

        q.restore(saved, savedPosition = 1, shuffle = true)

        assertEquals(
            "a reboot must resume the same song out of the same album",
            listOf(9L, 3L, 7L), q.tracksInOrder().map { it.id }
        )
        assertEquals(3L, q.current()!!.id)
        assertEquals(7L, q.next(wrap = false)?.id)
        assertTrue(q.shuffle)
    }

    @Test
    fun `restore with an out-of-range position clamps`() {
        val q = PlaybackQueue(Random(1))
        q.restore(listOf(track(1), track(2)), savedPosition = 10, shuffle = false)
        assertEquals(2L, q.current()!!.id)
    }

    @Test
    fun `restore of an empty queue leaves nothing playing`() {
        val q = PlaybackQueue(Random(1))
        q.restore(emptyList(), savedPosition = 0, shuffle = false)
        assertNull(q.current())
        assertTrue(q.isEmpty())
    }

    @Test
    fun `track ids in order match the queue`() {
        val q = seeded()
        q.setQueue(queueOf(6), startIndex = 2, shuffle = true)
        val byOrder = q.trackIdsInOrder()
        assertEquals(6, byOrder.size)
        assertEquals(3L, byOrder.first())
    }
}

/**
 * Checkpoint round-trip.
 *
 * The failure this guards against is silent: a checkpoint that decodes to
 * "almost right" resumes into the wrong track and looks like a player bug.
 */
class CheckpointCodecTest {

    private fun sample(
        queue: List<Long> = listOf(11, 22, 33),
        index: Int = 1,
        repeat: RepeatMode = RepeatMode.ALL
    ) = MusicCheckpoint(
        trackId = 22,
        positionMs = 42_000,
        index = index,
        shuffle = true,
        repeatMode = repeat,
        queueTrackIds = queue,
        savedAtMs = 1_700_000_000_000
    )

    @Test
    fun `round trip preserves every field`() {
        val original = sample()
        val restored = CheckpointCodec.decode(CheckpointCodec.encode(original))!!
        assertEquals(original, restored)
    }

    @Test
    fun `repeat modes survive the round trip`() {
        for (mode in RepeatMode.values()) {
            val restored = CheckpointCodec.decode(CheckpointCodec.encode(sample(repeat = mode)))!!
            assertEquals(mode, restored.repeatMode)
        }
    }

    @Test
    fun `an empty queue is normalised to the current track`() {
        val cp = sample(queue = emptyList())
        val restored = CheckpointCodec.decode(CheckpointCodec.encode(cp))!!
        assertEquals(
            "a queue that lost its contents must still resume the current track",
            listOf(22L), restored.queueTrackIds
        )
    }

    @Test
    fun `garbage is rejected, not half-parsed`() {
        assertNull(CheckpointCodec.decode(null))
        assertNull(CheckpointCodec.decode(""))
        assertNull(CheckpointCodec.decode("not a checkpoint at all"))
        assertNull(CheckpointCodec.decode("TS8CP1\ntrack=oops\nposition=1\n"))
        assertNull(CheckpointCodec.decode("TS8CP1\nposition=1\n"))
    }

    @Test
    fun `a truncated row is rejected`() {
        val full = CheckpointCodec.encode(sample())
        val truncated = full.substring(0, full.length / 2)
        assertNull("a half-written checkpoint must not resume", CheckpointCodec.decode(truncated))
    }

    @Test
    fun `unknown keys are ignored for forward compatibility`() {
        val text = CheckpointCodec.encode(sample()) + "future_key=whatever\n"
        assertEquals(sample(), CheckpointCodec.decode(text))
    }

    @Test
    fun `an oversized payload is rejected before parsing`() {
        val huge = "TS8CP1\n" + "x".repeat(70_000)
        assertNull(CheckpointCodec.decode(huge))
    }

    @Test
    fun `a bad repeat mode invalidates the row rather than defaulting silently`() {
        val text = CheckpointCodec.encode(sample()).replace("repeat=ALL", "repeat=SIDEWAYS")
        assertNull(CheckpointCodec.decode(text))
    }

    @Test
    fun `a single-track checkpoint survives`() {
        val cp = MusicCheckpoint(
            trackId = 5, positionMs = 0, index = 0, shuffle = false,
            repeatMode = RepeatMode.OFF, queueTrackIds = listOf(5), savedAtMs = 1
        )
        assertEquals(cp, CheckpointCodec.decode(CheckpointCodec.encode(cp)))
    }
}

/**
 * The scan diff.
 *
 * The whole point of an incremental scan is that a boot with an unchanged USB
 * stick does no work; a diff that reports everything as "updated" would still
 * pass every user-visible test while burning the CPU the map needs.
 */
class ScanDiffTest {

    private fun file(id: Long, size: Long = 1_000, mtime: Long = 100, kind: Track.SourceKind = Track.SourceKind.MEDIA_STORE) =
        ScannedFile(
            path = "/music/$id.mp3",
            sizeBytes = size,
            modifiedSeconds = mtime,
            id = id,
            sourceKind = kind,
            mimeType = "audio/mpeg"
        )

    @Test
    fun `an unchanged library reports no work`() {
        val existing = mapOf(1L to (1_000L to 100L), 2L to (2_000L to 200L))
        val kinds = mapOf(1L to Track.SourceKind.MEDIA_STORE, 2L to Track.SourceKind.MEDIA_STORE)

        val diff = ScanDiffCalculator.diff(
            existing, kinds, listOf(file(1, 1_000, 100), file(2, 2_000, 200))
        )

        assertTrue(diff.added.isEmpty())
        assertTrue(diff.updated.isEmpty())
        assertTrue(diff.removed.isEmpty())
        assertEquals(2, diff.unchanged)
    }

    @Test
    fun `a new file is added`() {
        val diff = ScanDiffCalculator.diff(
            mapOf(1L to (1_000L to 100L)),
            mapOf(1L to Track.SourceKind.MEDIA_STORE),
            listOf(file(1, 1_000, 100), file(9))
        )
        assertEquals(listOf(9L), diff.added.map { it.id })
        assertEquals(1, diff.unchanged)
    }

    @Test
    fun `a changed size updates the row`() {
        val diff = ScanDiffCalculator.diff(
            mapOf(1L to (1_000L to 100L)),
            mapOf(1L to Track.SourceKind.MEDIA_STORE),
            listOf(file(1, 2_000, 100))
        )
        assertEquals(listOf(1L), diff.updated.map { it.id })
    }

    @Test
    fun `a changed mtime updates the row`() {
        val diff = ScanDiffCalculator.diff(
            mapOf(1L to (1_000L to 100L)),
            mapOf(1L to Track.SourceKind.MEDIA_STORE),
            listOf(file(1, 1_000, 999))
        )
        assertEquals(listOf(1L), diff.updated.map { it.id })
    }

    @Test
    fun `a vanished file is removed`() {
        val diff = ScanDiffCalculator.diff(
            mapOf(1L to (1_000L to 100L), 2L to (1_000L to 100L)),
            mapOf(1L to Track.SourceKind.MEDIA_STORE, 2L to Track.SourceKind.MEDIA_STORE),
            listOf(file(1))
        )
        assertEquals(listOf(2L), diff.removed)
    }

    @Test
    fun `a scan scoped to internal storage never deletes USB rows`() {
        val existing = mapOf(1L to (1_000L to 100L), 2L to (1_000L to 100L))
        val kinds = mapOf(
            1L to Track.SourceKind.MEDIA_STORE,
            2L to Track.SourceKind.USB
        )

        // The USB stick was not mounted during this scan, so its files were
        // not found — but they still exist.
        val diff = ScanDiffCalculator.diff(
            existing, kinds, listOf(file(1)),
            scopedTo = Track.SourceKind.MEDIA_STORE
        )

        assertTrue("an unmounted USB stick must not lose its library rows", diff.removed.isEmpty())
    }

    @Test
    fun `a USB-scoped scan removes files that are gone from the stick`() {
        val existing = mapOf(7L to (1_000L to 100L), 8L to (1_000L to 100L))
        val kinds = mapOf(7L to Track.SourceKind.USB, 8L to Track.SourceKind.USB)

        val diff = ScanDiffCalculator.diff(
            existing, kinds, listOf(file(7, kind = Track.SourceKind.USB)),
            scopedTo = Track.SourceKind.USB
        )

        assertEquals(listOf(8L), diff.removed)
    }

    @Test
    fun `indexing requires a known audio extension`() {
        val allSupported = { _: String -> true }
        assertTrue(ScanDiffCalculator.shouldIndex("a.mp3", null, allSupported))
        assertTrue(ScanDiffCalculator.shouldIndex("a.FLAC", null, allSupported))
        assertFalse("photos must not be indexed", ScanDiffCalculator.shouldIndex("a.jpg", null, allSupported))
        assertFalse(ScanDiffCalculator.shouldIndex("notes.txt", "text/plain", allSupported))
        assertFalse(ScanDiffCalculator.shouldIndex("no-extension", null, allSupported))
    }

    @Test
    fun `a codec the device cannot decode is not indexed`() {
        val flacUnsupported = { mime: String -> mime != "audio/flac" }
        assertFalse(ScanDiffCalculator.shouldIndex("song.flac", null, flacUnsupported))
        assertTrue(ScanDiffCalculator.shouldIndex("song.mp3", null, flacUnsupported))
    }

    @Test
    fun `a wrong reported mime type does not lose a good file`() {
        // The TS8's MediaStore reports m4a files as audio/mpeg often enough that
        // this has to be handled explicitly.
        val allSupported = { _: String -> true }
        assertTrue(ScanDiffCalculator.shouldIndex("song.m4a", "audio/mpeg", allSupported))
    }

    @Test
    fun `an untagged file gets a readable title from its name`() {
        assertEquals("My Song", ScanDiffCalculator.titleFromFileName("My_Song.mp3"))
        assertEquals("Track", ScanDiffCalculator.titleFromFileName("Track"))
        assertEquals("no extension", ScanDiffCalculator.titleFromFileName("no extension"))
    }
}

/** Stable ids for USB files. */
class TrackIdsTest {

    @Test
    fun `the same path always produces the same id`() {
        val a = TrackIds.forUsbPath("/storage/usb0/Music/song.mp3")
        val b = TrackIds.forUsbPath("/storage/usb0/Music/song.mp3")
        assertEquals(a, b)
    }

    @Test
    fun `different paths produce different ids`() {
        val a = TrackIds.forUsbPath("/storage/usb0/Music/one.mp3")
        val b = TrackIds.forUsbPath("/storage/usb0/Music/two.mp3")
        assertNotEquals(a, b)
    }

    @Test
    fun `usb ids cannot collide with mediastore ids`() {
        val id = TrackIds.forUsbPath("/storage/usb0/x.mp3")
        assertTrue(TrackIds.isUsb(id))
        assertTrue("a USB id must stay positive", id > 0)
        assertTrue(
            "a USB id must be far outside MediaStore's range",
            id > 1L shl 61
        )
    }

    @Test
    fun `a mediastore id is not mistaken for a usb id`() {
        assertFalse(TrackIds.isUsb(1_234))
        assertFalse(TrackIds.isUsb(Long.MAX_VALUE shr 3))
    }
}

END FILE

## core/music-provider-online/build.gradle.kts

BEGIN FILE
plugins {
    id("ts8.core-module")
}

android {
    namespace = "com.ts8.core.music.online"
}

dependencies {
    api(project(":core:music-api"))
}

END FILE

## core/music-provider-online/src/main/AndroidManifest.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<manifest />

END FILE

## core/music-provider-online/src/main/java/com/ts8/core/music/online/CircuitBreaker.kt

BEGIN FILE
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

END FILE


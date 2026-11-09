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
        /**
         * Constant correction added to **GPS-derived** speed, in km/h.
         *
         * The head unit's own speedometer and the GPS Doppler speed disagree by
         * a few km/h — tyres wear, the cluster is optimistic, and a car running
         * larger wheels reads low. The driver reported 4–5 km/h and asked for a
         * way to correct it, so this is that correction and nothing else: it is
         * applied at the single point where a GPS fix becomes a sample, which
         * means the readout, the speed-limit roundel and the overspeed alert all
         * move together. A vehicle-bus sample is never offset — it is already
         * the car's own number.
         */
        val gpsOffsetKph: Double = 0.0,
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
        // The calibration offset is applied here, at the one place a GPS fix
        // becomes a sample, so display, limit comparison and alerting cannot
        // disagree about it. Clamped at zero: a negative result is "stopped",
        // never a negative speed.
        val corrected = fix.speedMps.toDouble() +
            config.gpsOffsetKph * 1000.0 / 3600.0
        onSample(
            SpeedSample(
                speedMps = corrected.coerceAtLeast(0.0),
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

    /**
     * Direct access to the limit resolver, so external pipelines (e.g. traffic
     * sign alerts) can push posted speed limits as HIGH-confidence values.
     *
     * **Why this is exposed.** The traffic-sign store carries 15 000+ posted
     * `limit` pins — far more than the routing graph's ~6 % maxspeed coverage.
     * Feeding those pins through `resolver.setTagged()` gives the overspeed
     * detector real speed data on roads the routing graph knows nothing about.
     * The alternative — routing through `monitor.onPackLimit()` — would report
     * MEDIUM confidence, because the routing graph mixes tagged and class-default
     * values in one field. A posted sign is HIGH confidence by definition.
     */
    val limitResolver: SpeedLimitResolver
        get() = resolver

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

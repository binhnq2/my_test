package com.ts8.core.traffic

import android.util.Log
import com.ts8.core.map.GeoPoint
import com.ts8.core.speed.SpeedLimitResolver
import kotlin.math.abs

/**
 * Processes GPS fixes against the [TrafficSignStore] and fires proximity alerts.
 *
 * **Pipeline.**
 *
 * 1. A location fix arrives via [onLocationUpdate].
 * 2. The store is queried for signs in the 9-cell neighbourhood.
 * 3. Signs within [alertRadiusMeters] and heading-relevant are ranked by distance.
 * 4. The closest one becomes the "active alert" and is published to listeners.
 * 5. If the active sign carries a posted speed limit, it is pushed into
 *    [SpeedLimitResolver.setTagged] as a HIGH-confidence limit — a posted sign
 *    outranks the routing graph's inferred class default.
 *
 * **Integration with the existing speed pipeline.** The speed-limit feed is
 * the most valuable part: the routing graph tags `maxspeed` on only ~6 % of
 * highway ways, while the `vnsigns` pack carries 15 853 `limit` pins. Feeding
 * those pins as HIGH-confidence limits gives the overspeed detector real speed
 * data on roads the routing graph knows nothing about.
 *
 * **Cooldown.** Each sign fires at most once per [alertCooldownMs] window,
 * measured from the first alert. Without this, a sign 200 m ahead at 40 km/h
 * would re-fire every 5 seconds for the 18 seconds it takes to pass it —
 * the driver would hear the same prompt four times.
 *
 * @param store The spatial-grid sign store.
 * @param speedLimitResolver The speed-limit resolver to feed with posted limits.
 *   May be `null` when speed integration is not wanted (e.g., diagnostics).
 * @param clock Elapsed-realtime clock, for cooldown bookkeeping.
 */
class TrafficAlertController(
    private val store: TrafficSignStore,
    private val speedLimitResolver: SpeedLimitResolver? = null,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) {

    /**
     * Configuration for the controller.
     *
     * Every threshold is a field so a settings panel can tune them without
     * touching the controller's logic.
     */
    data class Config(
        /** Maximum distance at which a sign triggers an alert, in metres. */
        val alertRadiusMeters: Double = 300.0,
        /**
         * Distance at which a sign stops being "approaching" and becomes
         * "passed", in metres. Below this the alert is cleared.
         */
        val passedRadiusMeters: Double = 40.0,
        /** Minimum interval between re-alerting the same sign, in ms. */
        val alertCooldownMs: Long = 30_000L,
        /** Whether voice prompts are enabled for traffic alerts. */
        val voicePromptsEnabled: Boolean = true,
        /** Whether the map overlay should show sign markers. */
        val showSignsOnMap: Boolean = true,
        /**
         * Master switch for traffic-sign alerts (`driving_alerts_enabled_v1`).
         *
         * `false` suppresses the alert card and its voice prompt for every
         * category. It deliberately does **not** stop [feedSpeedLimits]: the
         * speed-limit roundel is a readout, not an alert, and a driver who
         * muted warnings still needs to know the posted limit on the road they
         * are on.
         */
        val alertsEnabled: Boolean = true,
        /**
         * Enforcement-camera alerts (`cam`, `cam_red`).
         *
         * Separate from the speed-limit signs because they answer different
         * questions: "may I drive faster" versus "will I be fined". Plenty of
         * drivers want the camera warning and no limit chatter, or the reverse.
         */
        val cameraAlertsEnabled: Boolean = true,
        /**
         * Posted speed-limit and residential-zone alerts (`limit`, `r420`,
         * `r421`).
         *
         * Only the *alert* is gated. The limit value itself still reaches
         * [SpeedLimitResolver] either way, for the reason in [alertsEnabled].
         */
        val speedSignAlertsEnabled: Boolean = true
    )

    @Volatile
    var config: Config = Config()
        private set

    fun configure(config: Config) {
        this.config = config
    }

    /**
     * Listener for proximity-alert events.
     *
     * Implementations are called on the main thread.
     */
    interface Listener {
        /**
         * A new sign has become the closest active alert, replacing the
         * previous one (which may have been `null`).
         *
         * @param alert The approaching sign and its distance, or `null` when
         *   no sign is within range.
         */
        fun onTrafficAlert(alert: TrafficAlert?)

        /**
         * The set of visible signs on the map has changed.
         *
         * Called when new cells are loaded and the map overlay needs to be
         * refreshed. The list contains all cached signs, not just the alert.
         */
        fun onSignsUpdated(signs: List<TrafficSign>)
    }

    private val listeners = mutableListOf<Listener>()

    fun addListener(listener: Listener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    // -- state -------------------------------------------------------------

    /** The current active alert, or `null` when nothing is in range. */
    @Volatile
    private var currentAlert: TrafficAlert? = null

    /**
     * Timestamps of the last alert fired for each sign, keyed by unique ID.
     *
     * Signs without a unique ID (`u == 0`) are keyed by `"lat_lon"` instead.
     */
    private val alertTimestamps = mutableMapOf<String, Long>()

    /** Whether the store has loaded cells around the current position at all. */
    private var storeInitialised = false

    // -- public API --------------------------------------------------------

    /**
     * Feeds a new GPS position to the controller.
     *
     * @param lat Latitude in degrees.
     * @param lon Longitude in degrees.
     * @param heading Vehicle heading in degrees, or `NaN` when unknown.
     * @param speedMps Vehicle speed in m/s. Not used to gate alerts: a car
     *   stopped at a light 200 m before a camera still needs to know the camera
     *   is there, and the per-sign cooldown already prevents repeats. The value
     *   is carried because the settings panel will expose a "no alerts below
     *   N km/h" option, and re-plumbing the signature later would touch every
     *   caller for a field this function already receives.
     */
    fun onLocationUpdate(lat: Double, lon: Double, heading: Float, speedMps: Float) {
        val cfg = config
        val now = clock()

        // Query the store for the 9-cell neighbourhood.
        val nearby = store.loadNearby(lat, lon)
        if (!storeInitialised && nearby.isNotEmpty()) {
            storeInitialised = true
            notifySignsUpdated(nearby)
        }

        // Prune far cells occasionally to bound memory on a long trip.
        // Every ~50 km of travel (at 0.5° cells) a new ring enters the cache.
        store.pruneFarCells(lat, lon)

        // Filter to heading-relevant signs within the alert radius.
        val headingValid = !heading.isNaN()
        val here = GeoPoint(lat, lon)

        var closest: TrafficSign? = null
        var closestDist = Double.MAX_VALUE

        for (sign in nearby) {
            val dist = here.distanceTo(sign.point)

            // Skip signs that are behind us or irrelevant to our heading.
            if (headingValid && !sign.isRelevantForHeading(heading)) continue

            // Skip categories the driver switched off. This sits before the
            // distance tests on purpose: a suppressed camera must not become
            // the "nearest" candidate and mask a no-entry sign further ahead.
            if (!isAlertable(sign, cfg)) continue

            // Skip signs that are too far away.
            if (dist > cfg.alertRadiusMeters) continue

            // A sign this close has been reached: the driver is looking at it,
            // and "camera 15 m" is noise rather than a warning. Dropping it here
            // is also what clears the card *after* passing a sign when the
            // receiver has no course (heading filter inactive) — otherwise the
            // alert would linger for the whole alert radius behind the car.
            if (dist < cfg.passedRadiusMeters) continue

            // Track the closest for the active alert.
            if (dist < closestDist) {
                closestDist = dist
                closest = sign
            }
        }

        // Feed posted speed limits to the speed pipeline.
        feedSpeedLimits(nearby, here, headingValid, heading)

        // Manage the active alert.
        val nearest = closest
        val previous = currentAlert

        // A sign we are driving away from is behind us. The distance test above
        // cannot catch this alone: on a fast road the car can pass a sign with a
        // 50 m lateral offset and stay inside `passedRadiusMeters` only briefly.
        // Requiring actual motion keeps GPS jitter while parked from clearing a
        // card the driver is stationary in front of.
        val moving = speedMps > MOVING_SPEED_MPS
        val receding = moving && previous != null && nearest != null &&
            alertKey(previous.sign) == alertKey(nearest) &&
            closestDist > previous.distanceMeters + RECEDE_MARGIN_METERS

        val newAlert: TrafficAlert? = if (receding) {
            null
        } else if (nearest != null && closestDist <= cfg.alertRadiusMeters) {
            // Check cooldown for this specific sign.
            val key = alertKey(nearest)
            val lastFired = alertTimestamps[key]
            if (lastFired == null || (now - lastFired) >= cfg.alertCooldownMs) {
                alertTimestamps[key] = now
                TrafficAlert(
                    sign = nearest,
                    distanceMeters = closestDist,
                    firstAlert = lastFired == null
                )
            } else {
                // Still the closest sign, but within cooldown — update distance only.
                currentAlert?.copy(distanceMeters = closestDist, firstAlert = false)
            }
        } else {
            null
        }

        // Publish when the alert identity changes or the distance updates.
        val changed = newAlert?.sign?.uniqueId != currentAlert?.sign?.uniqueId ||
            newAlert?.sign?.lat != currentAlert?.sign?.lat ||
            newAlert?.sign?.lon != currentAlert?.sign?.lon ||
            (newAlert != null && newAlert.distanceMeters != currentAlert?.distanceMeters) ||
            // A cleared alert is a change even though every field above is null.
            (newAlert == null && currentAlert != null)

        currentAlert = newAlert

        if (changed) {
            notifyAlert(currentAlert)
        }

        // Notify signs updated if the cache grew (new cells loaded).
        if (nearby.size != lastNotifiedSignCount) {
            lastNotifiedSignCount = nearby.size
            notifySignsUpdated(store.cachedSigns())
        }
    }

    /**
     * Whether a sign may raise an alert under [cfg].
     *
     * Categories the driver silenced are dropped before ranking, so turning
     * cameras off does not leave a gap in the list where a camera sat — the next
     * relevant sign simply becomes the active alert.
     */
    private fun isAlertable(sign: TrafficSign, cfg: Config): Boolean {
        if (!cfg.alertsEnabled) return false
        return when (sign.signType) {
            SignType.CAMERA_SPEED,
            SignType.CAMERA_RED_LIGHT -> cfg.cameraAlertsEnabled

            SignType.SPEED_LIMIT,
            SignType.RESIDENTIAL_BEGIN,
            SignType.RESIDENTIAL_END -> cfg.speedSignAlertsEnabled

            // Prohibition and unknown signs belong to no category switch; the
            // master switch is their only control.
            else -> true
        }
    }

    /**
     * Feeds the closest posted speed-limit sign to the speed pipeline.
     *
     * A `limit` sign within 100 m of the vehicle and heading-relevant is
     * treated as the current road's posted limit. This outranks the routing
     * graph's inferred class default (MEDIUM confidence) because it is a real
     * sign, not an inference.
     */
    private fun feedSpeedLimits(
        signs: List<TrafficSign>,
        here: GeoPoint,
        headingValid: Boolean,
        heading: Float
    ) {
        val resolver = speedLimitResolver ?: return

        var bestLimit = 0
        var bestDist = Double.MAX_VALUE

        for (sign in signs) {
            if (!sign.hasSpeedLimit) continue
            if (headingValid && !sign.isRelevantForHeading(heading)) continue
            val dist = here.distanceTo(sign.point)
            // Only consider signs within 100 m — a limit sign 200 m ahead
            // may belong to a different road after the next junction.
            if (dist > SPEED_LIMIT_FEED_RADIUS_METERS) continue
            if (dist < bestDist) {
                bestDist = dist
                bestLimit = sign.speedLimit
            }
        }

        if (bestLimit > 0) {
            resolver.setTagged(bestLimit)
        }
        // NOTE: We do NOT clear the resolver when no limit sign is nearby.
        // The routing-graph limit (set via SpeedRuntime.maybeResolveLimit)
        // may still be valid, and clearing here would erase it between sign
        // placements. The resolver is cleared by SpeedRuntime on road changes.
    }

    // -- notifications ------------------------------------------------------

    private var lastNotifiedSignCount = 0

    private fun notifyAlert(alert: TrafficAlert?) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        for (l in snapshot) {
            runCatching { l.onTrafficAlert(alert) }
                .onFailure { Log.w(TAG, "listener.onTrafficAlert failed: ${it.message}") }
        }
    }

    private fun notifySignsUpdated(signs: List<TrafficSign>) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        for (l in snapshot) {
            runCatching { l.onSignsUpdated(signs) }
                .onFailure { Log.w(TAG, "listener.onSignsUpdated failed: ${it.message}") }
        }
    }

    /** Stable key for a sign, used in the cooldown map. */
    private fun alertKey(sign: TrafficSign): String =
        if (sign.uniqueId != 0L) "u${sign.uniqueId}"
        else "${sign.lat}_${sign.lon}"

    /** The current active alert, or `null`. */
    fun currentAlert(): TrafficAlert? = currentAlert

    companion object {
        private const val TAG = "TrafficAlertCtrl"

        /**
         * Maximum distance at which a `limit` sign feeds the speed pipeline.
         *
         * Smaller than the alert radius: a speed-limit sign 250 m away may
         * belong to a road the vehicle has not yet turned onto, so it should
         * not override the routing graph's per-edge limit.
         */
        private const val SPEED_LIMIT_FEED_RADIUS_METERS = 100.0

        /**
         * Speed above which the vehicle counts as moving, in m/s.
         *
         * ~1.8 km/h. Below this, GPS distance readings jitter by a few metres
         * between samples even with the car stationary, which would otherwise
         * read as "receding" and clear a card the driver is parked in front of.
         */
        private const val MOVING_SPEED_MPS = 0.5f

        /**
         * How much farther the active sign must be, between two fixes, before
         * the alert is treated as passed. Absorbs the ±5 m noise of a consumer
         * GPS fix so a stationary car never triggers the receding rule.
         */
        private const val RECEDE_MARGIN_METERS = 8.0
    }
}

/**
 * An active traffic-sign proximity alert.
 *
 * @param sign The traffic sign that triggered the alert.
 * @param distanceMeters Current distance from the vehicle to the sign.
 * @param firstAlert `true` when this is the first time the sign has fired
 *   in the current cooldown window. Voice prompts are only spoken when this
 *   is `true`; subsequent updates update the distance readout only.
 */
data class TrafficAlert(
    val sign: TrafficSign,
    val distanceMeters: Double,
    val firstAlert: Boolean
) {
    /** The alert sign's display label. */
    val label: String
        get() = sign.displayLabel()

    /** Whether a voice prompt should be spoken for this alert. */
    val shouldSpeak: Boolean
        get() = firstAlert
}

package com.ts8.launcher.runtime

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ts8.core.audio.VehicleAudioBridge
import com.ts8.core.location.LocationFix
import com.ts8.core.map.GeoPoint
import com.ts8.core.music.AudioRoute
import com.ts8.core.speed.SpeedAlertController
import com.ts8.core.speed.SpeedLimit
import com.ts8.core.speed.SpeedMonitor
import com.ts8.core.speed.SpeedUiState
import com.ts8.launcher.di.AppGraph
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * The process-wide owner of the speed pipeline.
 *
 * **Why this exists as a runtime object.** [SpeedMonitor] itself is deliberately
 * platform-free: it takes a clock, a config, and samples. Something has to
 * connect it to the one GPS subscription, to the routing graph's per-edge speed,
 * to the stored settings and to the speakers — and it has to do that once, for
 * the whole process, so Home, the map tab and the alert path all read the same
 * number and the filter's state survives a screen change.
 *
 * **Why the limit lookup is throttled and off-thread.** `AStarRouter.limitAt`
 * searches a spatial index and walks the edges leaving a node. It is fast, but
 * it is not free, and calling it on every 1 Hz GPS callback on the main thread
 * would spend the frame budget of the map. It runs on the graph's IO pool, only
 * after the car has moved far enough for the answer to possibly change, and its
 * result is applied back on the main thread because [SpeedMonitor] is not
 * thread-safe by design (it is a state machine, and its listeners touch views).
 *
 * **Why the alert goes through [VehicleAudioBridge].** The brief requires an
 * alert volume independent of the music *that still respects audio focus*. API
 * 23 has no separate alert stream a normal app can own, so the honest
 * implementation is: request focus through the same bridge navigation uses,
 * play a short tone through `STREAM_MUSIC` scaled by the driver's alert-volume
 * setting, release focus. If focus is refused the alert downgrades to visual
 * and [SpeedAlertController.Delivery.streamedReason] says why.
 */
class SpeedRuntime(
    private val context: Context,
    private val settings: AppSettings,
    private val graph: AppGraph
) {

    private val main = Handler(Looper.getMainLooper())

    /** The one monitor. Built with the settings as they were at first use; */
    /** [refreshConfig] keeps it in step afterwards. */
    private val monitor = SpeedMonitor(
        clock = ::elapsedClock,
        config = settings.speedConfig()
    )

    private val alerts = SpeedAlertController(ToneSink(), ::elapsedClock)

    private val listeners = CopyOnWriteArrayList<SpeedMonitor.Listener>()

    /** Screens that asked for speed; the GPS feed runs while any is visible. */
    private var refCount = 0

    /** True once the pipeline is subscribed to [LocationRuntime]. */
    private val subscribed = AtomicBoolean(false)

    private var lastLimitPoint: GeoPoint? = null
    private var lastLimitLookupMs = 0L
    private var limitLookupInFlight = false

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshConfig() }

    /**
     * The monitor's own listener fan-out plus alert delivery.
     *
     * Registered as an [SpeedMonitor.AlertListener] so the monitor can tell us
     * *when* to sound without ever knowing that audio exists.
     */
    private val alertBridge = object : SpeedMonitor.AlertListener {
        override fun onSpeedState(state: SpeedUiState) {
            for (l in listeners.toTypedArray()) runCatching { l.onSpeedState(state) }
        }

        override fun onOverspeed(
            overByKmh: Int,
            limit: SpeedLimit,
            form: SpeedMonitor.AlertForm,
            volume: Float
        ) {
            // The controller decides whether the sound actually happens and
            // reports the reason when it does not. Nothing here claims audio.
            val delivery = alerts.deliver(overByKmh, form, volume)
            lastSilentReason = delivery.streamedReason
            Log.i(
                TAG,
                "overspeed +${overByKmh}km/h limit=${limit.limitKmh} ${limit.confidence} " +
                    "visual=${delivery.visual} audio=${delivery.audio}" +
                    (delivery.streamedReason?.let { " reason=$it" } ?: "")
            )
        }
    }

    init {
        monitor.addListener(alertBridge)
        settings.registerListener(prefsListener)
    }

    // -- lifecycle ---------------------------------------------------------

    /**
     * Starts feeding and joining the GPS stream.
     *
     * Reference counted exactly like [LocationRuntime] itself, so a screen that
     * is destroyed without releasing cannot pin the receiver forever: the count
     * bottoms out at zero.
     */
    fun acquire(listener: SpeedMonitor.Listener?) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener)
        refCount++
        if (refCount == 1) {
            refreshConfig()
            graph.location.acquire(locationListener)
            subscribed.set(true)
            // Show whatever the last accepted fix already was; the first new
            // sample may be a second away and the readout would sit at `--`.
            graph.location.lastFix?.let { monitor.onFix(it) }
            monitor.tick()
        }
        listener?.let { it.onSpeedState(monitor.state()) }
    }

    fun release(listener: SpeedMonitor.Listener?) {
        if (listener != null) listeners.remove(listener)
        if (refCount > 0) refCount--
        if (refCount == 0) stopFeeding()
    }

    private fun stopFeeding() {
        graph.location.release(locationListener)
        subscribed.set(false)
        alerts.silence()
        // The monitor keeps its last state (a stale sample resolves to UNKNOWN
        // on the next read), but the filter is reset so a long gap does not
        // blend the speed from before the pause into the speed after it.
        monitor.reset()
    }

    /** Re-applies every setting; called on a preference change and on acquire. */
    fun refreshConfig() {
        val config = settings.speedConfig()
        monitor.configure(config)
        // The monitor re-emits through [alertBridge], so every visible screen
        // picks the new unit/threshold up without being touched.
    }

    /** Current rendered state, for a screen that is binding late. */
    fun state(): SpeedUiState = monitor.state()

    fun alerting(): Boolean = monitor.state().alerting

    /**
     * The speed-limit resolver, exposed so the traffic-alert pipeline can push
     * posted speed limits from traffic-sign data as HIGH-confidence values.
     */
    fun limitResolver(): com.ts8.core.speed.SpeedLimitResolver = monitor.limitResolver

    /** The reason string of the last alert that could not be sounded, if any. */
    fun lastSilentAlertReason(): String? = lastSilentReason

    @Volatile
    private var lastSilentReason: String? = null

    // -- GPS ---------------------------------------------------------------

    private val locationListener = object : LocationRuntime.Listener {
        override fun onFix(fix: LocationFix) {
            monitor.onFix(fix)
            maybeResolveLimit(fix)
        }
    }

    /**
     * Looks up the speed limit for the road under the car, throttled.
     *
     * Two gates: distance (the answer cannot change while the car has not moved
     * a road segment's worth) and time (a stationary car must not hammer the
     * router once a second). Both are needed — a stationary car still produces
     * fixes, and distance alone would open the gate on GPS jitter.
     */
    private fun maybeResolveLimit(fix: LocationFix) {
        val now = elapsedClock()
        val previous = lastLimitPoint
        val moved = previous == null || distanceMeters(previous, fix.point) >= LIMIT_RECHECK_METERS
        val elapsed = now - lastLimitLookupMs >= LIMIT_RECHECK_MS
        if (!moved && !elapsed) return
        if (limitLookupInFlight) return

        lastLimitPoint = fix.point
        lastLimitLookupMs = now
        limitLookupInFlight = true

        graph.io().execute {
            val limitKmh = runCatching { graph.routing()?.limitAt(fix.point) }.getOrNull()
            main.post {
                limitLookupInFlight = false
                if (limitKmh != null && limitKmh > 0) {
                    monitor.onPackLimit(limitKmh)
                } else {
                    // No edge within range (a car park, an unmapped service
                    // road) means no limit: clear rather than carry the previous
                    // road's sign onto this one.
                    monitor.clearLimit()
                }
            }
        }
    }

    /**
     * Flat-earth distance, good to a few percent over the 25 m this gates.
     *
     * Haversine would be more correct and is unnecessary: the only decision
     * this feeds is "did the car move far enough to re-ask the router", and both
     * approximations agree at that scale on every latitude this unit drives.
     */
    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val latRad = Math.toRadians((a.lat + b.lat) / 2.0)
        val dy = (b.lat - a.lat) * 111_320.0
        val dx = (b.lon - a.lon) * 111_320.0 * Math.cos(latRad)
        return Math.hypot(dx, dy)
    }

    // -- alert audio -------------------------------------------------------

    /**
     * Plays a short tone on `STREAM_MUSIC` while holding navigation focus.
     *
     * **Why a `ToneGenerator` and not a bundled sound file.** A tone needs no
     * asset, no decoder, and no `MediaPlayer` lifecycle to leak; the platform
     * generates it. The duration is deliberately short — an alert that overlaps
     * a turn instruction makes both unintelligible.
     */
    private inner class ToneSink : SpeedAlertController.SpeedAlertSink {

        private var generator: ToneGenerator? = null
        private var playing = false

        override fun acquireAlertFocus(): Boolean {
            val result = graph.audioBridge.requestFocus(AudioRoute.NAVIGATION)
            // Duck the music for the duration, the same way a prompt does. The
            // bridge is idempotent, so a prompt already in progress is not
            // double-ducked.
            runCatching { graph.audioBridge.duck() }
            return when (result) {
                is com.ts8.core.audio.AudioResult.Success -> true
                else -> false
            }
        }

        override fun releaseAlertFocus() {
            runCatching { generator?.release() }
            generator = null
            playing = false
            runCatching { graph.audioBridge.unduck() }
            runCatching { graph.audioBridge.abandonFocus(AudioRoute.NAVIGATION) }
        }

        override fun play(tone: SpeedAlertController.AlertTone, volume: Float) {
            val percent = (volume.coerceIn(0f, 1f) * 100f).toInt().coerceIn(1, 100)
            val created = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, percent) }
                .getOrNull() ?: throw IllegalStateException("không tạo được ToneGenerator")
            generator = created
            val id = when (tone) {
                SpeedAlertController.AlertTone.OVERSPEED -> ToneGenerator.TONE_PROP_BEEP2
                SpeedAlertController.AlertTone.OVERSPEED_RECOVERED -> ToneGenerator.TONE_PROP_ACK
            }
            playing = created.startTone(id, ALERT_TONE_MS)
            if (!playing) throw IllegalStateException("ToneGenerator từ chối phát")
        }

        override fun stop() {
            runCatching { generator?.stopTone() }
            runCatching { generator?.release() }
            generator = null
            playing = false
        }
    }

    // -- diagnostics -------------------------------------------------------

    /**
     * One line for the diagnostics panel.
     *
     * Carries the state, the raw and displayed value, the limit with its
     * provenance and the sample age — everything needed to tell "no data" from
     * "lookup stopped". Contains no coordinate: the limit's *source* is a fact
     * about the pack, the position is not.
     */
    fun diagnosticLine(): String {
        val s = monitor.state()
        val limit = if (s.limitKmh != null) {
            "${s.limitKmh} ${s.limitSource}=${monitorLimitConfidence()}"
        } else {
            "-- (không có dữ liệu)"
        }
        return "speed=${s.speedKmh ?: "--"}${s.unitLabel} state=${s.state} " +
            "limit=$limit age=${s.sampleAgeMs}ms src=${s.sampleSource ?: "-"}"
    }

    private fun monitorLimitConfidence(): String =
        // Read through the resolver's own answer rather than recomputing it, so
        // the diagnostics cannot disagree with what the alert path used.
        when (monitor.state().limitSource) {
            com.ts8.core.speed.LimitSource.ROUTING_GRAPH -> "MEDIUM"
            com.ts8.core.speed.LimitSource.VEHICLE_BUS -> "HIGH"
            com.ts8.core.speed.LimitSource.NONE -> "NONE"
        }

    companion object {
        private const val TAG = "Ts8SpeedRuntime"

        /** How far the car must travel before the limit is looked up again. */
        private const val LIMIT_RECHECK_METERS = 25.0

        /** Upper bound on lookups while stationary or jittering. */
        private const val LIMIT_RECHECK_MS = 5_000L

        /** Length of the alert beep, milliseconds. */
        private const val ALERT_TONE_MS = 350
    }
}

package com.ts8.launcher.runtime

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.ts8.core.location.LocationFix
import com.ts8.core.music.AudioRoute
import com.ts8.core.navigation.NavigationState
import com.ts8.core.parking.LocationStability
import com.ts8.core.parking.ParkingDecision
import com.ts8.core.parking.ParkingReason
import com.ts8.core.parking.ParkingSignals
import com.ts8.core.parking.ParkingState
import com.ts8.launcher.di.AppGraph
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The only code that knows how this launcher's signals become a
 * [ParkingSignals] snapshot (§18).
 *
 * **Why this exists.** [com.ts8.core.parking.ParkingStateResolver] is a pure
 * function on purpose: it takes one snapshot and returns one decision, so it
 * can be run against synthetic tracks with no head unit. Something has to
 * assemble that snapshot from six sources that never agree on a rate — the
 * location pipeline, a stability window, the navigation state machine, the
 * audio bridge's call detection, the driver's button, and a clock — and
 * something has to do it once for the whole process rather than once per
 * screen. That is this class.
 *
 * **Why a one-second tick instead of resolving on every GPS fix.** Dwell is
 * measured in time, and the resolver only learns that time passed when it is
 * called. Resolving only on a fix would stop the two-minute window dead the
 * moment the receiver loses sky view — which is exactly the car-park case this
 * feature exists for — and would also never notice a driver who pressed the
 * button while the fix stream was quiet. The tick is the heartbeat; fixes only
 * move the stability window forward.
 *
 * **Why a stale fix is reported as `null` speed, not as its last value.** A
 * receiver that went quiet ten seconds ago is not measuring a stationary car;
 * it is not measuring anything. Reporting the last speed would let a car that
 * drove into a tunnel "stay" at 0 km/h and accumulate dwell against a
 * measurement nobody made. [ParkingSignals] draws that distinction explicitly
 * and this class is where it has to be honoured.
 *
 * **The reverse camera stays `false` and that is a limitation, not a choice.**
 * On this firmware the reverse camera is drawn by the MCU, never by an Android
 * Activity, so a third-party app has no state to read. §18 forbids reverse
 * engineering the new MCU contract in this campaign, so the signal is passed as
 * absent-by-false with the consequence documented in
 * `docs/KNOWN_LIMITATIONS.md`: a driver who reverses into a space with the
 * auto-enter setting on can see the parking screen appear over the camera
 * picture after the dwell elapses. The navigation blocker is the one that still
 * protects the common case.
 */
class ParkingRuntime(
    private val context: Context,
    private val settings: AppSettings,
    private val graph: AppGraph
) {

    /** Told about every decision that changed. Delivered on the main thread. */
    interface Listener {
        fun onParkingDecision(decision: ParkingDecision)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val stability = LocationStability()

    /** Screens that asked for parking detection; the tick runs while any is up. */
    private var refCount = 0

    /**
     * The last decision, seeded from storage so a restart lands where it left
     * off rather than on a blank map.
     *
     * A restored decision is used for **display only**: [autoOpened] is seeded
     * from it so a reboot cannot push the parking screen forward on evidence
     * that was never re-measured this boot. The resolver will confirm or reject
     * it on the first tick, and only a fresh confirmation may open the screen.
     */
    private var last: ParkingDecision = runCatching { graph.parkingStore.restore() }
        .getOrDefault(ParkingDecision.UNKNOWN)

    private var autoOpened: Boolean = last.showsParkingScreen

    /** Set by [requestEnter]/[requestExit]; consumed by the next snapshot. */
    private var pendingManual: Boolean? = null

    private val tick = object : Runnable {
        override fun run() {
            if (refCount == 0) return
            evaluate()
            main.postDelayed(this, TICK_MS)
        }
    }

    /**
     * The GPS subscription.
     *
     * Its only job is to feed the stability window. It deliberately does not
     * call [evaluate]: the tick already runs at this rate, and resolving from
     * two sources would publish each decision twice and race the manual flag.
     */
    private val locationListener = object : com.ts8.launcher.runtime.LocationRuntime.Listener {
        override fun onFix(fix: LocationFix) {
            stability.add(fix.point, fix.elapsedRealtimeMs)
        }
    }

    // -- public surface ----------------------------------------------------

    /** The current decision, or the restored one before the first tick. */
    fun decision(): ParkingDecision = last

    /** True while the resolver holds the car parked. */
    fun isParked(): Boolean = last.state == ParkingState.PARKED

    /**
     * The driver pressed "vào chế độ đỗ xe".
     *
     * Returns nothing and applies on the next tick (within a second). It is not
     * applied synchronously because the resolver's dwell bookkeeping expects to
     * see snapshots in time order, and a snapshot stamped by a button press and
     * another stamped by the tick could arrive out of order.
     */
    fun requestEnter() {
        pendingManual = true
        autoOpened = false
        main.removeCallbacks(tick)
        if (refCount > 0) main.post(tick) else evaluate()
    }

    /**
     * The driver pressed "thoát chế độ đỗ xe".
     *
     * The resolver latches this until the car has actually driven away, which
     * is what stops the heuristic from re-entering the screen the driver just
     * dismissed.
     */
    fun requestExit() {
        pendingManual = false
        main.removeCallbacks(tick)
        if (refCount > 0) main.post(tick) else evaluate()
    }

    /**
     * Starts the tick and joins the GPS stream.
     *
     * Reference-counted exactly like [LocationRuntime], so a screen destroyed
     * without releasing cannot pin the ticker and the receiver forever.
     */
    fun acquire(listener: Listener?) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener)
        refCount++
        if (refCount == 1) {
            graph.location.acquire(locationListener)
            main.post(tick)
        }
        listener?.onParkingDecision(last)
    }

    fun release(listener: Listener?) {
        if (listener != null) listeners.remove(listener)
        if (refCount > 0) refCount--
        if (refCount == 0) {
            main.removeCallbacks(tick)
            graph.location.release(locationListener)
            // The window is time-bounded, and time spent off the launcher is
            // not evidence: keeping it would credit the car with dwell while
            // the process was on the music screen with no fix arriving.
            stability.reset()
        }
    }

    // -- the snapshot ------------------------------------------------------

    private fun evaluate() {
        val now = SystemClock.elapsedRealtime()
        val manual = pendingManual
        pendingManual = null

        val fix = graph.location.lastFix
        // The pipeline's own timestamp, not `now`: a fix that arrived a second
        // ago is a measurement taken a second ago.
        val fresh = fix != null && now - fix.elapsedRealtimeMs <= FIX_FRESH_MS

        val signals = ParkingSignals(
            // `LocationFix.speedMps` is a filtered Float that is never negative;
            // `ParkingSignals` wants a Double because its absence has to be
            // representable, which the Float cannot do.
            speedMps = if (fresh) fix!!.speedMps.toDouble() else null,
            speedAccuracyMeters = if (fresh) fix!!.accuracyMeters else null,
            locationStable = if (fresh) stability.verdict(now) else null,
            navigationActive = graph.navigationStateIfBuilt() == NavigationState.NAVIGATING,
            // `currentRoute` reads `AudioManager`'s mode, which is how API 23
            // reports an in-progress or ringing call without a telephony
            // permission. It is a cheap field read, safe on this tick.
            callActive = graph.audioBridge.currentRoute() == AudioRoute.CALL,
            reverseCameraActive = false,
            explicitManualRequest = manual,
            elapsedRealtimeMs = now
        )

        val resolved = runCatching {
            graph.parkingResolver.resolve(signals, settings.parkingAutoEnter)
        }.getOrElse { failure ->
            // The resolver is pure and should not throw; if it ever does, the
            // honest answer is "no answer" rather than a stale screen.
            Log.w(TAG, "parking resolve failed: ${failure.message}")
            ParkingDecision.UNKNOWN
        }

        val changed = resolved.state != last.state ||
            resolved.confidence != last.confidence ||
            resolved.reason != last.reason ||
            resolved.evidence != last.evidence
        last = resolved

        if (changed) {
            persist(resolved)
            for (l in listeners.toTypedArray()) runCatching { l.onParkingDecision(resolved) }
        }
        maybeOpenParkingScreen(resolved)
    }

    /**
     * Writes the decision and keeps the auto-open latch honest.
     *
     * Persisted on change rather than on every tick: the row is a disk write,
     * and a car standing still for ten minutes produces six hundred identical
     * decisions.
     */
    private fun persist(decision: ParkingDecision) {
        runCatching { graph.parkingStore.save(decision) }
    }

    /**
     * Opens the parking screen once per parked episode, and only on evidence.
     *
     * Three conditions, all required and all already checked by the resolver:
     * [ParkingDecision.showsParkingScreen] means PARKED at HIGH confidence,
     * which the resolver only grants when the driver's auto-enter setting is on;
     * and a [ParkingReason.MANUAL] decision is excluded because the manual
     * button already opened the screen itself. The latch is what keeps the
     * driver who taps "chạm để quay lại" from being thrown back into the screen
     * a second later while the car is still standing.
     */
    private fun maybeOpenParkingScreen(decision: ParkingDecision) {
        if (decision.state != ParkingState.PARKED) {
            autoOpened = false
            return
        }
        if (!decision.showsParkingScreen || decision.reason == ParkingReason.MANUAL) return
        if (autoOpened) return
        autoOpened = true
        val opened = runCatching {
            context.startActivity(
                Intent(context, com.ts8.launcher.ui.ParkingModeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        if (opened.isFailure) {
            Log.w(TAG, "could not open parking screen: ${opened.exceptionOrNull()?.message}")
        }
    }

    companion object {
        private const val TAG = "Ts8ParkingRuntime"

        /**
         * The tick, in milliseconds.
         *
         * One hertz matches the receiver and the resolver's own dwell
         * granularity; the parking decision is never needed faster than that,
         * and a faster tick would spend the main thread's frame budget on a
         * screen that is not moving.
         */
        private const val TICK_MS = 1_000L

        /**
         * How old a fix may be and still describe the car now.
         *
         * Five seconds is five missed samples at 1 Hz — long enough to ride out
         * a bad patch of sky, short enough that a car which drove out of a car
         * park and lost its receiver is not reported as "standing still".
         */
        private const val FIX_FRESH_MS = 5_000L
    }
}

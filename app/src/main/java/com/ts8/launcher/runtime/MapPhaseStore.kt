package com.ts8.launcher.runtime

/**
 * Where the map renderer is, in a vocabulary the screens and the diagnostics
 * report can both speak (§6).
 *
 * **What problem this solves.** Before this class, both map screens handled the
 * renderer as two states: a string `failure` or nothing. A map that was still
 * loading, a map waiting for its first frame, and a map that had failed all
 * rendered as the same empty rectangle — and because `MapHost.failure` is set
 * once and never cleared, a pack that appeared a second later (a USB mount
 * completing during startup) could not recover the screen without a restart.
 *
 * **What it deliberately is not.** It is not the renderer and it does not fix
 * it. Every transition below is reported by the code that already owned that
 * moment — the pack lookup, the style request, the `ReadyListener` — and this
 * class only names it. §1 and §6 both forbid claiming a rendering repair, and
 * nothing here draws a tile.
 *
 * **Why the pack name is carried rather than read on demand.** [Graph.loadPack]
 * is a filesystem walk. The error surface must show the active pack at the
 * moment of failure, and re-asking while the pack directory is the thing that
 * just failed is how a message hangs. The name is captured when the phase
 * machine learns about the pack.
 *
 * **Why it has no Android dependency.** The seven regression tests in §12 run
 * on a plain JVM — Robolectric's `android-all` jar is not in the offline cache —
 * so anything they must assert on has to be constructible without a Context.
 * This class is one of those things, which also happens to be the right shape:
 * it is a state machine over strings.
 */
class MapPhaseStore {

    /** The six phases §6 names, in the order a working unit passes through them. */
    enum class Phase {
        /** Before the pack has been looked for. */
        INITIALIZING,

        /** The pack directory is being found and its metadata read. */
        VALIDATING_PACK,

        /** A pack was found; its style document is being prepared. */
        LOADING_STYLE,

        /** Style applied, waiting for the renderer to produce a frame. */
        WAITING_FIRST_FRAME,

        /** A frame has been drawn. */
        READY,

        /** Something went wrong; [Snapshot.errorCode] says what. */
        FAILED
    }

    /**
     * One observation of the renderer's progress.
     *
     * @param phase what it is doing now.
     * @param errorCode a stable, short identifier for a failure — the same
     *        string the diagnostics export carries, so a driver reading it out
     *        loud and a developer reading a log are looking at one value. Null
     *        outside [Phase.FAILED].
     * @param detail the Vietnamese sentence shown to the driver. Comes from the
     *        layer that failed (the pack lookup, MapLibre's own reason) rather
     *        than being composed here.
     * @param packName the active pack's display name at the time of the
     *        observation, or null when none was found.
     */
    data class Snapshot(
        val phase: Phase,
        val errorCode: String? = null,
        val detail: String? = null,
        val packName: String? = null
    ) {
        val isFailed: Boolean get() = phase == Phase.FAILED
        val isReady: Boolean get() = phase == Phase.READY

        /** True while the screen should be showing a progress line, not an error. */
        val isBusy: Boolean get() = phase != Phase.READY && phase != Phase.FAILED

        /**
         * What the diagnostics report prints for this snapshot.
         *
         * One line, no stack trace, no file paths beyond the pack's own name:
         * §11 forbids credentials and personal location data by default, and a
         * path is neither of those but is also not needed to decide anything.
         */
        fun diagnosticsLine(): String = when (phase) {
            Phase.FAILED -> {
                val code = errorCode ?: UNKNOWN_CODE
                val pack = packName ?: NO_PACK
                "$code · $pack"
            }
            Phase.READY -> "READY"
            else -> phase.name
        }
    }

    var snapshot: Snapshot = Snapshot(Phase.INITIALIZING)
        private set

    private val listeners = ArrayList<(Snapshot) -> Unit>(2)

    /**
     * Subscribes and immediately receives the current value.
     *
     * The immediate call is deliberate: a screen created while the map is
     * already `READY` would otherwise never be told, because nothing will
     * change again until the driver leaves.
     */
    fun addListener(listener: (Snapshot) -> Unit) {
        listeners.add(listener)
        listener(snapshot)
    }

    fun removeListener(listener: (Snapshot) -> Unit) {
        listeners.remove(listener)
    }

    // -- transitions -------------------------------------------------------

    /**
     * Moves to a phase that is not a failure.
     *
     * The pack name is sticky: a phase reported by the style loader does not
     * have it, and dropping it there would blank the "Gói đang dùng" line on a
     * later failure.
     */
    fun moveTo(phase: Phase, packName: String? = snapshot.packName) {
        if (phase == Phase.FAILED) return
        publish(Snapshot(phase, packName = packName))
    }

    /** Records the pack that was found, without changing the phase. */
    fun setPack(name: String?) {
        publish(snapshot.copy(packName = name))
    }

    /**
     * Records a failure.
     *
     * **Why a failure is not terminal.** A pack copied onto the stick after the
     * unit booted is the most ordinary recovery there is, and §6 gives the
     * error surface a `Thử lại` button precisely for it. Retry goes back to
     * [Phase.INITIALIZING] through [reset].
     */
    fun fail(
        errorCode: String,
        detail: String?,
        packName: String? = snapshot.packName
    ) {
        publish(
            Snapshot(
                phase = Phase.FAILED,
                errorCode = errorCode,
                detail = detail,
                packName = packName
            )
        )
    }

    /** Back to the beginning, for `Thử lại`. */
    fun reset() {
        publish(Snapshot(Phase.INITIALIZING, packName = snapshot.packName))
    }

    private fun publish(next: Snapshot) {
        if (next == snapshot) return
        snapshot = next
        // Copied before iterating: a listener that removes itself (a screen
        // finishing from inside its own callback) would otherwise make the
        // loop skip the listener after it.
        for (listener in ArrayList(listeners)) {
            runCatching { listener(next) }
        }
    }

    companion object {
        /**
         * Failure codes. Short, stable, and identical to what the diagnostics
         * export writes — a code that reads differently in a log and on the
         * screen is a code that costs a support call.
         */
        const val CODE_NO_PACK = "MAP_NO_PACK"
        const val CODE_PACK_UNREADABLE = "MAP_PACK_UNREADABLE"
        const val CODE_RENDERER_UNAVAILABLE = "MAP_RENDERER_UNAVAILABLE"
        const val CODE_STYLE_FAILED = "MAP_STYLE_FAILED"
        const val CODE_FIRST_FRAME_TIMEOUT = "MAP_FIRST_FRAME_TIMEOUT"
        const val UNKNOWN_CODE = "MAP_UNKNOWN"
        const val NO_PACK = "chưa có"

        /**
         * How long the shell waits for a first frame before it stops claiming
         * to be loading and says so.
         *
         * 20 s: the TS8 Cortex-A7 is much slower than the emulator, and a cold
         * MapLibre start reading a 50 MB mbtiles off slow flash can genuinely
         * take 15+ seconds. Shortening this to 12 s (the previous value) caused
         * false "timeout" errors on real hardware where the map was simply
         * taking its time, not broken. 20 s is still short enough that a truly
         * hung renderer is caught before the driver gives up.
         */
        const val FIRST_FRAME_TIMEOUT_MS = 20_000L
    }
}

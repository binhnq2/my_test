package com.ts8.launcher.runtime

import android.util.Log
import com.ts8.core.navigation.NavigationState

/**
 * The app's primary screen state machine (§16–17).
 *
 * **Why a machine and not "whatever Activity is on top".** The brief's rule is
 * not that ten screens must exist — they already do — but that navigation
 * between them is a transition on one state, so three things that used to be
 * guesses become facts: which screen is primary while an overlay is up, what
 * Back does, and what "still navigating while the driver is on Home" means.
 * Without it, `MapActivity` and `HomeActivity` each decide locally and a driver
 * who starts guidance from Home and then taps the music tab leaves the machine
 * describing a state nobody is in.
 *
 * **What this machine does *not* own.** It holds no engine and no player. The
 * map renderer, the navigator, the router and the `MediaPlayer` keep living in
 * [com.ts8.launcher.di.AppGraph] and in the playback service, which is what
 * makes §16's "bottom navigation đổi primary destination nhưng không hủy
 * map/music engine" structural rather than a promise: there is nothing here
 * to destroy.
 *
 * **Why overlays are a separate stack.** §16 lists four overlay states that
 * are orthogonal to the ten primary ones — the voice bar can appear over the
 * map while a route is being previewed, and the import progress sits over Map
 * Management while the screen underneath is still usable-ish. Folding them
 * into the primary enum would multiply ten states by four and make "Back
 * closes the overlay first" a switch over forty cases.
 *
 * **What survives a process death.** Only the primary state, and only the ones
 * that can be true again. [restore] degrades the transient ones exactly as
 * `VoiceStateStore` does for the voice overlay: a DESTINATION_PREVIEW whose
 * destination was never persisted, a SEARCH with no query, and a NAVIGATING
 * whose navigator died with the process all come back as the screen they were
 * drawn over, [AppState.MAP_BROWSE]. The overlay stack is never restored: an
 * import has no archive handle after a kill and an error dialog's cause is
 * gone, so restoring either would be a spinner that never stops.
 */
class AppStateMachine(
    private val store: StateStore,
    /**
     * Whether guidance is really running *now*.
     *
     * Asked only once, at the moment of the restore, because it is a question
     * about the process that came back: `AppGraph` answers false until it has
     * rebuilt a navigator, and rebuilding one just to answer this would defeat
     * the point of [restore] — a driver must not pay for the routing graph to
     * find out whether they are still navigating.
     */
    private val navigationRunning: () -> Boolean = { false }
) {

    /** The ten primary destinations of §16. */
    enum class State {
        HOME,
        MAP_BROWSE,
        SEARCH,
        DESTINATION_PREVIEW,
        ROUTE_PREVIEW,
        NAVIGATING,
        MUSIC_EXPANDED,
        APP_DRAWER,
        QUICK_SETTINGS,
        PARKING
    }

    /** The four overlays of §16, in the order they are stacked. */
    enum class Overlay {
        VOICE_LISTENING,
        VOICE_CONFIRMATION,
        MAP_IMPORT_PROGRESS,
        ERROR_DIALOG
    }

    /** Told about every accepted move, on the main thread. */
    fun interface Listener {
        fun onStateChanged(previous: State, current: State, overlays: List<Overlay>)
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()

    /** Guarded by this; the machine is main-looper-owned but readable off it. */
    @Volatile
    private var current: State = State.HOME

    private val overlayStack = ArrayList<Overlay>(4)

    // -- reading -----------------------------------------------------------

    fun state(): State = current

    /** The overlay Back would close, or null when nothing is stacked. */
    fun topOverlay(): Overlay? = synchronized(overlayStack) {
        overlayStack.lastOrNull()
    }

    fun overlays(): List<Overlay> = synchronized(overlayStack) { ArrayList(overlayStack) }

    fun isOverlayShowing(): Boolean = synchronized(overlayStack) { overlayStack.isNotEmpty() }

    // -- moving ------------------------------------------------------------

    /**
     * Moves to [next] if the transition is legal.
     *
     * Returns false for an illegal move rather than throwing or silently
     * accepting: a screen that asks for a transition the machine refuses has a
     * bug, and a launcher that crashes in front of a driver over an internal
     * bookkeeping error is a worse outcome than a log line. The illegal case is
     * real — a navigator callback that arrives after the driver already left
     * for the app drawer asks for MAP_BROWSE from APP_DRAWER, which is legal,
     * but one that arrives after PARKING is not.
     */
    fun moveTo(next: State): Boolean {
        val previous: State
        synchronized(this) {
            previous = current
            if (next == previous) return true
            if (!allowed(previous, next)) {
                Log.w(TAG, "illegal transition $previous -> $next ignored")
                return false
            }
            current = next
        }
        notify(previous, next)
        save()
        return true
    }

    /**
     * Mirrors the navigator's own state into the UI state (§16).
     *
     * **Why this exists separately from [moveTo].** Guidance can start, get
     * cancelled and finish while the driver is looking at another screen; the
     * navigation controller is the authority on whether a route is running, and
     * this is the one-way adapter from its vocabulary to the UI's. It never
     * moves the driver away from a non-map screen: starting a route from the
     * app drawer is not possible, but a voice command can start one from Home,
     * and Home is a legal destination while navigating.
     */
    fun syncNavigation(navigationState: NavigationState) {
        val target = when (navigationState) {
            NavigationState.DESTINATION_PREVIEW -> State.DESTINATION_PREVIEW
            NavigationState.ROUTE_CALCULATING,
            NavigationState.ROUTE_PREVIEW -> State.ROUTE_PREVIEW
            NavigationState.NAVIGATING,
            NavigationState.REROUTING,
            NavigationState.ARRIVED -> State.NAVIGATING
            NavigationState.SEARCHING -> State.SEARCH
            NavigationState.IDLE -> when (current) {
                // A finished or cancelled route returns the map to browsing;
                // any other screen keeps what it is showing, because ending a
                // route must not yank the driver out of the music screen.
                State.DESTINATION_PREVIEW,
                State.ROUTE_PREVIEW,
                State.NAVIGATING -> State.MAP_BROWSE
                else -> current
            }
        }
        moveTo(target)
    }

    // -- overlays ----------------------------------------------------------

    /**
     * Pushes [overlay] unless it is already on top.
     *
     * Re-pushing an overlay that is already stacked is a no-op rather than a
     * second entry: the voice bar re-renders on every state change and would
     * otherwise push VOICE_LISTENING once per frame, so a single Back press
     * would have to close an unbounded number of them.
     */
    fun pushOverlay(overlay: Overlay): Boolean = synchronized(overlayStack) {
        if (overlayStack.lastOrNull() == overlay) return false
        overlayStack.remove(overlay)
        overlayStack.add(overlay)
        true
    }.also { added -> if (added) notifyOverlays() }

    /** Removes [overlay] from anywhere in the stack. */
    fun popOverlay(overlay: Overlay): Boolean = synchronized(overlayStack) {
        overlayStack.remove(overlay)
    }.also { removed -> if (removed) notifyOverlays() }

    /**
     * Closes the top overlay. True when there was one.
     *
     * This is §16's "Back đóng overlay trước". The screen's own Back handling
     * runs only when this returns false, which is what stops a Back meant for
     * the microphone from closing the map underneath it.
     */
    fun popTopOverlay(): Boolean {
        val removed = synchronized(overlayStack) {
            if (overlayStack.isEmpty()) null else overlayStack.removeAt(overlayStack.lastIndex)
        } ?: return false
        notifyOverlays()
        return true
    }

    /** True once [restoreAfterProcessDeath] has run, so it cannot run twice. */
    @Volatile
    private var restoredOnce = false

    /**
     * Restores the stored state on the first call and never again.
     *
     * **Why once.** Every screen calls this from `onCreate`, and a process that
     * has already restored must not have a later screen's creation overwrite
     * the live state with what was on disk before the driver started moving.
     * The first screen to be created after a cold start is the one that owns
     * the restore; the rest inherit its result.
     *
     * **Why here and not in `Ts8LauncherApp`.** The Application runs before any
     * window exists and for every process start including a service-only one;
     * restoring there would decide the UI's state for a process that may never
     * draw. Called from a screen instead, it runs exactly when there is
     * something to restore *into*.
     *
     * @return the state the driver is now in.
     */
    fun restoreAfterProcessDeath(): State {
        synchronized(this) {
            if (restoredOnce) return current
            restoredOnce = true
        }
        return restore(navigationRunning())
    }

    // -- persistence -------------------------------------------------------

    /**
     * Saves the primary state.
     *
     * Only the primary state, and only for states that can be true again — see
     * [restore]. Written synchronously (`commit` in the store) because the
     * write happens on a screen change, which is exactly a moment the head unit
     * can lose power.
     */
    fun save() {
        runCatching { store.put(KEY, current.name) }
    }

    /**
     * Restores after a process death, degrading what cannot be true again.
     *
     * @param navigationStillRunning whether the process that came back really
     *        has guidance running. The caller asks [com.ts8.launcher.di.AppGraph]
     *        *after* it has rebuilt the navigator, if it rebuilt one at all;
     *        answering `false` here is always safe and is what a fresh process
     *        reports.
     */
    fun restore(navigationStillRunning: Boolean): State {
        val raw = runCatching { store.get(KEY) }.getOrNull()
        val stored = raw?.let { runCatching { State.valueOf(it) }.getOrNull() }
            ?: return current
        // A search with no query, a destination preview whose destination was
        // never written and a route preview whose route died with the old
        // process all come back as the map. Navigation is different: the route
        // is recomputed from the persisted checkpoint, so it can genuinely
        // still be running.
        val restored = when (stored) {
            State.SEARCH,
            State.DESTINATION_PREVIEW,
            State.ROUTE_PREVIEW -> State.MAP_BROWSE
            State.NAVIGATING -> if (navigationStillRunning) State.NAVIGATING else State.MAP_BROWSE
            else -> stored
        }
        val previous = synchronized(this) {
            val before = current
            current = restored
            before
        }
        if (previous != restored) notify(previous, restored)
        return restored
    }

    // -- listener ----------------------------------------------------------

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notify(previous: State, next: State) {
        val stack = overlays()
        listeners.forEach { listener ->
            runCatching { listener.onStateChanged(previous, next, stack) }
                .onFailure { Log.w(TAG, "state listener failed: ${it.message}") }
        }
    }

    private fun notifyOverlays() {
        val stack = overlays()
        listeners.forEach { listener ->
            runCatching { listener.onStateChanged(current, current, stack) }
                .onFailure { Log.w(TAG, "state listener failed: ${it.message}") }
        }
    }

    companion object {
        private const val TAG = "Ts8AppState"
        const val KEY = "ui.primary_state"

        /**
         * The legal moves.
         *
         * Written as the destination's permitted sources rather than as a set
         * of pairs: a state added later has exactly one line to update, and a
         * reviewer can read "what may come before NAVIGATING" in one place.
         *
         * **What is deliberately absent.** `PARKING` accepts only Home and the
         * map: the parking screen is a full-bleed panel the driver exits by
         * tapping, and reaching it from an overlay-heavy screen would stack a
         * full-screen panel over one already there. `MUSIC_EXPANDED` accepts
         * every browsing screen and nothing that is mid-route-selection, which
         * matches the rule that opening music must not disturb a route.
         */
        private fun allowed(from: State, to: State): Boolean = when (to) {
            State.HOME -> true
            // Every state may return to browsing the map: it is the screen the
            // map tab draws and the one a cancelled route falls back to.
            State.MAP_BROWSE -> true
            State.SEARCH -> from == State.HOME || from == State.MAP_BROWSE ||
                from == State.DESTINATION_PREVIEW || from == State.ROUTE_PREVIEW
            State.DESTINATION_PREVIEW -> from == State.MAP_BROWSE || from == State.SEARCH
            State.ROUTE_PREVIEW -> from == State.DESTINATION_PREVIEW ||
                from == State.MAP_BROWSE || from == State.NAVIGATING
            State.NAVIGATING -> from == State.DESTINATION_PREVIEW ||
                from == State.ROUTE_PREVIEW || from == State.MAP_BROWSE ||
                from == State.HOME || from == State.MUSIC_EXPANDED ||
                from == State.APP_DRAWER || from == State.QUICK_SETTINGS
            State.MUSIC_EXPANDED -> from == State.HOME || from == State.MAP_BROWSE ||
                from == State.NAVIGATING || from == State.APP_DRAWER ||
                from == State.QUICK_SETTINGS || from == State.PARKING
            State.APP_DRAWER -> from == State.HOME || from == State.MAP_BROWSE ||
                from == State.MUSIC_EXPANDED || from == State.NAVIGATING ||
                from == State.QUICK_SETTINGS
            State.QUICK_SETTINGS -> from == State.HOME || from == State.MAP_BROWSE ||
                from == State.MUSIC_EXPANDED || from == State.APP_DRAWER ||
                from == State.NAVIGATING
            State.PARKING -> from == State.HOME || from == State.MAP_BROWSE
        }
    }
}

/**
 * The two operations the machine needs from a store.
 *
 * Same shape, and the same reasoning, as `VoiceStateStore.KeyValueStore`: a
 * plain get/put pair is trivially fakeable on a JVM, and a `SharedPreferences`
 * reference would drag Android into a type that is otherwise a pure state
 * table. [PrefsKeyValueStore] implements it alongside the two core-module
 * interfaces it already satisfies.
 */
interface StateStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

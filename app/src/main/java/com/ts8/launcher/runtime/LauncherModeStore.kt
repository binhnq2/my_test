package com.ts8.launcher.runtime

/**
 * The one place that knows which launcher mode the driver is in (§10).
 *
 * **Why this exists when [AppStateMachine] already does.** The machine has ten
 * states and describes the app's *primary destination*, which is not the same
 * question. Its biggest problem is that it is **write-only**: `state()` has no
 * production caller, every screen re-derives its own layout from its own tab,
 * and nothing can answer "is guidance running while Home is showing?" — which
 * is exactly the question Home's navigation strip needs answered. Adding a
 * second, well-read store is cheaper and safer than teaching ten screens to
 * read a machine none of them was written against.
 *
 * **Why the eight modes and not ten states.** §10 names exactly eight:
 * HOME, MAP_BROWSE, NAVIGATING, MUSIC, SEARCH, APP_DRAWER, QUICK_SETTINGS,
 * PARKING. The machine's DESTINATION_PREVIEW and ROUTE_PREVIEW are *not* modes
 * — they are what the map screen draws while the mode is still MAP_BROWSE — so
 * they belong to the map's own render state, not here.
 *
 * **NAVIGATING is derived, not set.** It is not an Activity: guidance runs
 * while the driver may be on Home, on the music screen or on the map. So the
 * store keeps two facts — the [mode] the front screen claims, and whether a
 * route is [navActive] — and [effectiveMode] combines them. This is what makes
 * "bottom navigation đổi mode nhưng không recreate Map engine" true: switching
 * to Home does not stop the route, and coming back to Map does not start one.
 *
 * **What it deliberately does not do.** It holds no engine, no player and no
 * Activity reference. It is a small, synchronously readable state object, which
 * is why it can be exercised on a plain JVM without Android.
 */
class LauncherModeStore(
    /** Number of modes Back remembers, so a round trip can be undone. */
    private val historyDepth: Int = MAX_HISTORY
) {

    /** The eight modes §10 enumerates. */
    enum class Mode {
        HOME,
        MAP_BROWSE,
        NAVIGATING,
        MUSIC,
        SEARCH,
        APP_DRAWER,
        QUICK_SETTINGS,
        PARKING
    }

    /**
     * The mode the front screen claims.
     *
     * Never [Mode.NAVIGATING]: a screen never *is* navigation, a route is
     * running. Read [effectiveMode] for the combined answer.
     */
    @Volatile
    var mode: Mode = Mode.HOME
        private set

    /** True while a route is running, wherever the driver is looking. */
    @Volatile
    var navActive: Boolean = false
        private set

    /**
     * The mode a resumed screen claims, when it is not the same as the one the
     * store already holds.
     *
     * **Why this is needed for §10's "không để Home onResume ghi đè state vừa
     * restore".** Every Activity's `onResume` calls in, and Home's `onResume`
     * arrives *after* the restore on a cold start. Before this guard, Home
     * would claim HOME unconditionally and silently overwrite a restored
     * MUSIC_EXPANDED or MAP_BROWSE, because HOME is a legal destination from
     * everywhere and the machine's `moveTo` therefore always accepted it.
     *
     * The rule encoded: the **first** claim after a restore wins, and a later
     * claim from a different surface is accepted only when it is not simply
     * "Home reasserting itself over something that was restored". Concretely,
     * a Home resume is ignored while [restorePending] is set; the flag clears
     * on the first non-Home claim or on an explicit navigation by the driver.
     */
    @Volatile
    private var restorePending: Boolean = false

    /** The modes Back has seen, most recent last. */
    private val history = ArrayDeque<Mode>(historyDepth)

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Mode, Boolean) -> Unit>()

    /**
     * The mode the UI should treat as current: [Mode.NAVIGATING] when a route
     * is running and the driver is on the map or on Home, otherwise [mode].
     *
     * **Why Home reads as NAVIGATING.** The driver asked for guidance and then
     * went back to the home screen; leaving them on Home is normal, but the
     * strip that shows the next turn must appear, and the map tab must return
     * to a live route rather than a fresh browse. Reporting the combined mode
     * is what lets both happen without Home guessing.
     *
     * **Why only Home and Map.** On the music screen or the app drawer a
     * running route is background information, not the screen's mode; showing
     * the maneuver strip there would put navigation chrome over a screen whose
     * composition §5 says is something else entirely.
     */
    fun effectiveMode(): Mode = when {
        navActive && (mode == Mode.HOME || mode == Mode.MAP_BROWSE) -> Mode.NAVIGATING
        else -> mode
    }

    /**
     * Called by a screen in `onResume`.
     *
     * @param claimed what the screen thinks it is.
     * @param fromRestore true when this claim is the first one after a process
     *        restore, in which case a Home claim must not win over the restored
     *        mode.
     * @return the mode now in force.
     */
    fun onSurfaceResumed(claimed: Mode, fromRestore: Boolean = false): Mode {
        // §10's guard. A restored MAP_BROWSE or MUSIC must survive the Home
        // activity that the launcher role recreates around it.
        if (restorePending && claimed == Mode.HOME) {
            return effectiveMode()
        }
        if (restorePending) restorePending = false
        if (claimed == Mode.NAVIGATING) {
            // A screen never claims navigation; treat it as the map.
            setNavActive(true)
            return setModeInternal(Mode.MAP_BROWSE)
        }
        return setModeInternal(claimed)
    }

    /** Tells the store that a process restore is about to hand a mode back. */
    fun beginRestore() {
        restorePending = true
    }

    /**
     * Installs the mode a restore produced, without treating it as a user
     * navigation.
     *
     * Distinct from [setMode] on purpose: the driver did not tap anything, so
     * this must not push history, and for [Mode.NAVIGATING] it must turn
     * [navActive] on rather than pretend an Activity is called "Navigating".
     */
    fun setModeForRestore(restored: Mode) {
        restorePending = true
        if (restored == Mode.NAVIGATING) {
            navActive = true
            mode = Mode.MAP_BROWSE
        } else {
            mode = restored
        }
        notifyListeners()
    }

    /**
     * Sets the mode explicitly, from a deliberate user action.
     *
     * Clears [restorePending] because the driver has now chosen where to go;
     * whatever was restored is history.
     */
    fun setMode(next: Mode): Mode {
        restorePending = false
        return setModeInternal(next)
    }

    /** Records whether a route is running. Never moves the mode. */
    fun setNavActive(active: Boolean) {
        if (navActive == active) return
        navActive = active
        notifyListeners()
    }

    /**
     * Where Back should go from here (§10: "Back đóng overlay trước rồi về mode
     * hợp lý").
     *
     * The overlay half of that rule is already enforced a level up, in
     * `BaseScreenActivity.onBackPressed`, before any screen's Back runs. This
     * answers only the second half: from a screen opened *over* another, return
     * to the one underneath; from a tab, go Home; on Home, nowhere.
     *
     * @return the mode Back should open, or null when Back should do nothing
     *         (Home) — a launcher that exits leaves the driver on a black panel.
     */
    fun backTarget(): Mode? = when (mode) {
        Mode.HOME -> null
        Mode.PARKING -> Mode.HOME
        Mode.SEARCH, Mode.QUICK_SETTINGS -> historyBeforeCurrent() ?: Mode.HOME
        Mode.MUSIC, Mode.APP_DRAWER, Mode.MAP_BROWSE,
        Mode.NAVIGATING -> Mode.HOME
    }

    private fun historyBeforeCurrent(): Mode? =
        history.lastOrNull { it != mode && it != Mode.SEARCH && it != Mode.QUICK_SETTINGS }

    private fun setModeInternal(next: Mode): Mode {
        if (next == mode) {
            notifyListeners()
            return mode
        }
        pushHistory(mode)
        mode = next
        notifyListeners()
        return mode
    }

    private fun pushHistory(previous: Mode) {
        // SEARCH and QUICK_SETTINGS are overlays in spirit; pushing them would
        // make Back from one of them return to the other.
        if (previous == Mode.SEARCH || previous == Mode.QUICK_SETTINGS) return
        history.addLast(previous)
        while (history.size > historyDepth) history.removeFirst()
    }

    // -- listeners ---------------------------------------------------------

    fun addListener(listener: (Mode, Boolean) -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: (Mode, Boolean) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        val m = effectiveMode()
        val nav = navActive
        listeners.forEach { runCatching { it(m, nav) } }
    }

    /** Clears history and returns to the cold-start state. For tests. */
    fun reset() {
        history.clear()
        navActive = false
        restorePending = false
        mode = Mode.HOME
        notifyListeners()
    }

    companion object {
        /** Three is enough to undo Home→Map→Music without unbounded growth. */
        private const val MAX_HISTORY = 3
    }
}

package com.ts8.launcher.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.ts8.launcher.R
import com.ts8.launcher.di.AppGraph
import com.ts8.launcher.runtime.AppStateMachine
import com.ts8.launcher.runtime.LauncherModeStore

/**
 * Shared behaviour for every launcher screen.
 *
 * **Why a base class rather than a shared layout includes and copy-paste.** The
 * seven screens differ entirely in their content but agree on four things: the
 * status row at the top, the bottom bar at the bottom, the landscape/immersive
 * window treatment, and what the bottom bar does when tapped. Putting those
 * here means a screen cannot accidentally ship with two nav bars or none, which
 * is the failure the spec's "bottom navigation is the only navigation" rule is
 * guarding against.
 *
 * **Back handling.** A launcher has no back stack to pop. Pressing back on any
 * screen goes to Home, and back on Home does nothing — it must not exit to a
 * black screen on a head unit with no system UI behind it.
 *
 * **Voice Instant's overlay lives here (§11).** Every launcher screen can
 * receive a voice command — the driver says "dẫn đường tới Cầu Rồng" from Home
 * and the map comes forward mid-interaction — so the bar belongs to the base
 * class rather than to the screens that happen to draw a microphone. It is a
 * child of the content root, added in [onCreate] after the subclass set its
 * layout, and it takes Back only while a voice interaction is in flight
 * ([VoiceOverlayHost.handleBack]); otherwise Back behaves exactly as before.
 */
abstract class BaseScreenActivity : AppCompatActivity() {

    /** Which nav item this screen highlights. */
    protected abstract val screen: BottomNavView.Screen

    /**
     * The screen's Voice Instant surface.
     *
     * Subclasses that want their own microphone button call [startVoice]; a
     * screen that does not draw one still gets the overlay when a command
     * arrives from elsewhere, because the host is installed for everyone.
     */
    protected val voice: VoiceOverlayHost by lazy { VoiceOverlayHost(this) }

    private var nav: BottomNavView? = null

    /**
     * The primary state this screen represents in the §16 machine, or null for
     * a screen that is not one of the ten.
     *
     * Derived from [screen] rather than declared per subclass: every tab screen
     * maps one-to-one onto a state, and the screens opened *over* another
     * override this to name the state they are. Settings and Map Management
     * return null deliberately — they are reached from Quick Settings, they
     * are not in §16's enumeration, and a launcher settings page is not a
     * destination the state machine should claim the driver arrived at.
     */
    protected open val primaryState: AppStateMachine.State?
        get() = when (screen) {
            BottomNavView.Screen.HOME -> AppStateMachine.State.HOME
            BottomNavView.Screen.MAP -> AppStateMachine.State.MAP_BROWSE
            BottomNavView.Screen.MUSIC -> AppStateMachine.State.MUSIC_EXPANDED
            BottomNavView.Screen.APPS -> AppStateMachine.State.APP_DRAWER
            BottomNavView.Screen.NONE -> null
        }

    /**
     * The §10 mode this screen is, or null for a screen that is not a mode.
     *
     * A second, narrower mapping alongside [primaryState] rather than a
     * replacement for it. [primaryState] drives the overlay stack and the
     * legacy transitions; this drives the eight-mode store the UI now reads,
     * and the two deliberately differ: DESTINATION_PREVIEW and ROUTE_PREVIEW
     * exist in the machine but are not modes, and NAVIGATING is a mode but has
     * no screen of its own.
     */
    protected open val launcherMode: LauncherModeStore.Mode?
        get() = when (screen) {
            BottomNavView.Screen.HOME -> LauncherModeStore.Mode.HOME
            BottomNavView.Screen.MAP -> LauncherModeStore.Mode.MAP_BROWSE
            BottomNavView.Screen.MUSIC -> LauncherModeStore.Mode.MUSIC
            BottomNavView.Screen.APPS -> LauncherModeStore.Mode.APP_DRAWER
            BottomNavView.Screen.NONE -> null
        }

    /**
     * Whether this screen keeps the parking detector running.
     *
     * True for every screen that is a place the driver actually is. The
     * exception is [ScreenOffActivity], which draws a black window standing in
     * for the panel being off: it holds the detector, the tick sees the car come
     * to rest, and the parking screen opens *over the black screen* the driver
     * just asked for. Nothing about that is what "màn hình tối" means.
     */
    protected open val runsParkingDetector: Boolean
        get() = true

    /** The process-wide §16 machine; null only if the graph is not installed. */
    protected val uiState: AppStateMachine?
        get() = runCatching { AppGraph.require().uiState }.getOrNull()

    /** The process-wide §10 mode store; null only if the graph is not installed. */
    protected val modes: LauncherModeStore?
        get() = runCatching { AppGraph.require().launcherMode }.getOrNull()

    /**
     * True for the first screen created after a cold start.
     *
     * Set in [onCreate] before [super.onCreate] runs the restore, and read in
     * [onResume] so the first resume can tell the mode store that this is a
     * restore rather than a deliberate navigation. §10's rule that Home must
     * not overwrite a just-restored mode depends on that distinction.
     */
    private var firstResumeAfterRestore = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // §16's "process recreation phải restore state an toàn", done here
        // because every screen passes through this method and the first one to
        // arrive after a cold start is the one that owns the restore — see
        // [AppStateMachine.restoreAfterProcessDeath], which is one-shot.
        //
        // What it buys, precisely, because it is easy to overclaim: the machine
        // starts from the stored state and *degraded* to something that can be
        // true again (a SEARCH with no query and a preview whose destination
        // died with the old process both become MAP_BROWSE), instead of
        // claiming HOME and then contradicting itself on the first resume. No
        // screen is relaunched from it: launching an Activity from another
        // Activity's `onCreate` is exactly the loop this rule is guarding
        // against, and the head unit's own launcher role means the process is
        // rebuilt by Home anyway.
        //
        // §10's guard is the second half of this. The restore's *result* is now
        // used: the mode store is told a restore is in progress, so the Home
        // resume that follows milliseconds later cannot overwrite the restored
        // MAP_BROWSE or MUSIC. Before this, Home's `onResume` won every time,
        // because HOME is a legal destination from every state and the
        // machine's `moveTo` therefore always accepted it.
        firstResumeAfterRestore = true
        runCatching {
            val restored = AppGraph.require().uiState.restoreAfterProcessDeath()
            AppGraph.require().launcherMode.beginRestore()
            restoredModeFrom(restored)?.let { restoredMode ->
                AppGraph.require().launcherMode.setModeForRestore(restoredMode)
            }
        }
        // The launcher is the only chrome on this display: no title bar.
        //
        // Deliberately NOT `SYSTEM_UI_FLAG_FULLSCREEN` any more (§4). The TS8
        // draws its own vendor status bar and nothing the launcher can do will
        // remove it; asking the platform to hide a bar that the vendor re-draws
        // bought nothing and cost a re-layout on every screen change. What is
        // kept is the layout-stable flag, so content does not shift when the
        // vendor bar appears.
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    /**
     * Maps a restored §16 state onto a §10 mode, or null when it is not a mode.
     *
     * The previews and SEARCH degrade to the map for the same reason the
     * machine degrades them: a preview whose destination did not survive is a
     * map browse, and a search with no query is not a search.
     */
    private fun restoredModeFrom(state: AppStateMachine.State): LauncherModeStore.Mode? =
        when (state) {
            AppStateMachine.State.HOME -> LauncherModeStore.Mode.HOME
            AppStateMachine.State.MAP_BROWSE,
            AppStateMachine.State.SEARCH,
            AppStateMachine.State.DESTINATION_PREVIEW,
            AppStateMachine.State.ROUTE_PREVIEW -> LauncherModeStore.Mode.MAP_BROWSE
            AppStateMachine.State.NAVIGATING -> LauncherModeStore.Mode.NAVIGATING
            AppStateMachine.State.MUSIC_EXPANDED -> LauncherModeStore.Mode.MUSIC
            AppStateMachine.State.APP_DRAWER -> LauncherModeStore.Mode.APP_DRAWER
            AppStateMachine.State.QUICK_SETTINGS -> LauncherModeStore.Mode.QUICK_SETTINGS
            AppStateMachine.State.PARKING -> LauncherModeStore.Mode.PARKING
        }

    /**
     * Installs the voice bar the moment the screen's layout exists.
     *
     * **Why here and not in [onCreate].** `setContentView` clears
     * `android.R.id.content` before inflating, so a bar added before the
     * subclass set its layout would be removed by it; and adding it afterwards
     * would need every one of the twelve screens to remember a call. The
     * platform delivers `onContentChanged()` right after inflation (it is the
     * `Window.Callback` notification that the content view was replaced), which
     * is the single point that is both after the layout and independent of the
     * screen.
     */
    override fun onContentChanged() {
        super.onContentChanged()
        voice.install()
    }

    /** Called after the content view is set, to wire the bar. */
    protected fun bindNavigation() {
        val bar = findViewById<BottomNavView?>(R.id.bottomNav)
        nav = bar
        if (bar == null) return
        bar.setSelectedScreen(screen)
        bar.onNavigate = { target -> navigateTo(target) }
    }

    /**
     * Opens the microphone.
     *
     * The one entry point a screen's microphone affordance needs; the
     * permission request, the denial path and the recogniser session all live
     * behind it so no screen can get them differently.
     */
    fun startVoice(): Boolean = voice.trigger()

    override fun onStart() {
        super.onStart()
        voice.attach()
        // The parking detector follows *every* screen, not just Home.
        //
        // **Why here.** `ParkingRuntime` runs its one-second tick only while
        // something holds it, and until now the only holders were Home and the
        // parking screen itself. That made the feature unreachable in exactly
        // the situation it is for: the driver navigating with the map up is on
        // `MapActivity`, Home is stopped, the reference count is zero, and the
        // detector never sees the car come to rest. Stopping at a shop with the
        // map forward produced no parking screen — which is the report this
        // fixes. Home's own acquire stays where it is; this is the same
        // process-wide detector joined from the seam every screen shares, so
        // Music, Apps, Search and Quick Settings count too rather than each
        // having to remember.
        //
        // Passing no listener: no screen renders the decision except the
        // parking screen, which subscribes to it itself. `runCatching` because
        // a graph that is not installed is a test-harness case, not a reason
        // for a screen to fail to start.
        if (runsParkingDetector) runCatching { AppGraph.require().parking.acquire(null) }
    }

    override fun onStop() {
        // Detaches the rendering only. A command in flight — the driver said
        // "mở bản đồ" and the map is coming forward — must survive this screen
        // stopping, which is why the controller lives in `AppGraph`.
        voice.detach()
        // Guarded by the same flag as the acquire: releasing a count this screen
        // never took would decrement someone else's hold and stop the tick for
        // every other screen in the process.
        if (runsParkingDetector) runCatching { AppGraph.require().parking.release(null) }
        super.onStop()
    }

    override fun onDestroy() {
        voice.release()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        voice.onRequestPermissionsResult(requestCode, grantResults)
    }

    override fun onResume() {
        super.onResume()
        // §16: the machine follows the screen that is actually in front. Done
        // in `onResume` rather than `onCreate` so returning to a screen that
        // was left behind — REORDER_TO_FRONT reuses the instance — re-asserts
        // it, and so a refused transition is logged rather than silently
        // diverging.
        //
        // §10's guard is the `fromRestore` argument. On a cold start the first
        // resume here is Home's, arriving after the restore; claiming HOME
        // unconditionally is precisely the overwrite §10 forbids, and the mode
        // store now refuses it while a restore is pending.
        uiState?.let { machine -> primaryState?.let { machine.moveTo(it) } }
        val justRestored = firstResumeAfterRestore
        firstResumeAfterRestore = false
        modes?.let { store -> launcherMode?.let { store.onSurfaceResumed(it, justRestored) } }

        nav?.setSelectedScreen(screen)
        nav?.refreshVolume()
        // The clock and date used to be refreshed here for the status row on
        // every screen. That row is gone (§4): the TS8 vendor bar owns the
        // clock, and Parking Mode's large clock refreshes itself. Nothing left
        // to update on a normal screen.
    }

    /**
     * Switches screen.
     *
     * A screen that is already showing is not re-launched: on a head unit,
     * tapping "Trang chủ" while on Home must not restart the map's GL surface
     * and cost two seconds of black.
     */
    private fun navigateTo(target: BottomNavView.Screen) {
        if (target == screen) return
        // §10: the mode changes on the tap, not when the destination resumes.
        // Recording it here means Back knows where the driver came from even
        // if the launched screen never gets a resume (a resource-starved head
        // unit can lose the activity it just asked for).
        modes?.let { store ->
            when (target) {
                BottomNavView.Screen.HOME -> store.setMode(LauncherModeStore.Mode.HOME)
                BottomNavView.Screen.MAP -> store.setMode(LauncherModeStore.Mode.MAP_BROWSE)
                BottomNavView.Screen.MUSIC -> store.setMode(LauncherModeStore.Mode.MUSIC)
                BottomNavView.Screen.APPS -> store.setMode(LauncherModeStore.Mode.APP_DRAWER)
                BottomNavView.Screen.NONE -> Unit
            }
        }
        val next = when (target) {
            BottomNavView.Screen.HOME -> HomeActivity::class.java
            BottomNavView.Screen.MAP -> MapActivity::class.java
            BottomNavView.Screen.MUSIC -> MusicFullscreenActivity::class.java
            BottomNavView.Screen.APPS -> AppDrawerActivity::class.java
            BottomNavView.Screen.NONE -> return
        }
        runCatching {
            startActivity(
                Intent(this, next).addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
            // REORDER_TO_FRONT reuses an existing instance; the standard
            // activity transition would slide the map in from the side, which
            // reads as a modal sheet. Launcher screens cross-fade, unless §17's
            // reduced-animation / low-performance rule says otherwise.
            ScreenTransition.apply(this)
        }
    }

    /**
     * Intercepts back and routes it through [onBack].
     *
     * `super.onBackPressed()` is deliberately **not** called. Its AppCompat
     * body finishes the activity, which is precisely what a launcher screen
     * must not do: the driver would land on an empty display. The subclasses'
     * [onBack] decide instead — a tab screen goes Home, Home ignores it, and a
     * screen opened over another (search, quick settings) finishes itself.
     * Lint's `MissingSuperCall` is a heuristic for the ordinary case, where an
     * override that skips the super call loses the platform's default; here the
     * default is the bug.
     *
     * **A running voice interaction owns Back** (§17: "Back đóng overlay
     * trước"). It is checked first and consumes the press, so a driver who
     * started a command by mistake presses Back once and the microphone stops
     * instead of a screen closing underneath a live recogniser.
     */
    @Suppress("MissingSuperCall")
    override fun onBackPressed() {
        if (voice.handleBack()) return
        // §16's "Back đóng overlay trước, sau đó quay về state hợp lý": an
        // overlay the machine knows about is closed before any screen-level
        // Back runs, so a Back aimed at a progress panel or a dialog cannot
        // close the screen underneath it. `VoiceOverlayHost` pops its own entry
        // as part of `handleBack`, so the two do not double-pop.
        val closed = uiState?.popTopOverlay() ?: false
        if (closed) {
            // The overlay is gone; whatever leaving it means *for this screen*
            // is the screen's business. Ignoring the press is right for a
            // dialog the driver dismissed, and wrong for an import panel whose
            // screen has nothing left to show.
            onOverlayDismissed()
            return
        }
        onBack()
    }

    /**
     * Called when a §16 overlay was closed by Back.
     *
     * The default does nothing, which is the correct answer for the voice bar
     * and for a dialog: the screen behind them was never gone. Screens that
     * pushed an overlay representing *their own* long-running work override
     * this to leave as well.
     */
    protected open fun onOverlayDismissed() = Unit

    /**
     * Shows [dialog] and keeps §16's ERROR_DIALOG overlay in step with it.
     *
     * **Why a dialog is an overlay and not a state.** It is drawn over whatever
     * screen is current, it takes Back, and dismissing it must not close that
     * screen — the exact three properties §16 lists for an overlay. Tracking it
     * here is what makes the rule true for dialogs too, instead of only for the
     * ones the machine happens to know about.
     *
     * **Why the pop is on dismiss, not on the button.** A dialog dismissed by a
     * tap outside, by Back or by the platform's own cancel path leaves the same
     * way, and only `setOnDismissListener` sees all three. Popping from the
     * positive button would leave the overlay stacked over a dialog that is
     * already gone, and the next Back would then be swallowed by a phantom.
     */
    protected fun showTrackedDialog(dialog: android.app.AlertDialog): android.app.AlertDialog {
        val machine = uiState
        machine?.pushOverlay(AppStateMachine.Overlay.ERROR_DIALOG)
        dialog.setOnDismissListener {
            machine?.popOverlay(AppStateMachine.Overlay.ERROR_DIALOG)
        }
        return dialog
    }

    /**
     * Where back goes. Overridable so a screen opened *over* another — search,
     * which can be reached from Home or from the map — can return to whichever
     * one launched it instead of jumping to Home.
     *
     * The default is the launcher rule: a tab screen's back goes Home, and Home
     * ignores it.
     */
    protected open fun onBack() {
        if (screen == BottomNavView.Screen.HOME) {
            // Deliberately does nothing: a Home app that exits leaves the
            // driver on a black or stock-launcher screen with no way back.
            return
        }
        // §10's "Back ... về mode hợp lý", answered by the store rather than by
        // "always Home". From the music screen or the app drawer that is still
        // Home; from a screen opened over another it is the one underneath.
        val target = modes?.backTarget() ?: LauncherModeStore.Mode.HOME
        startActivity(
            Intent(this, activityFor(target)).addFlags(
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
    }

    /**
     * The screen behind a mode.
     *
     * NAVIGATING resolves to the map: guidance is drawn there, and Back while a
     * route runs should show it, not end the route.
     */
    private fun activityFor(mode: LauncherModeStore.Mode): Class<out android.app.Activity> =
        when (mode) {
            LauncherModeStore.Mode.HOME -> HomeActivity::class.java
            LauncherModeStore.Mode.MAP_BROWSE,
            LauncherModeStore.Mode.NAVIGATING -> MapActivity::class.java
            LauncherModeStore.Mode.MUSIC -> MusicFullscreenActivity::class.java
            LauncherModeStore.Mode.APP_DRAWER -> AppDrawerActivity::class.java
            LauncherModeStore.Mode.SEARCH -> SearchActivity::class.java
            LauncherModeStore.Mode.QUICK_SETTINGS -> QuickSettingsActivity::class.java
            LauncherModeStore.Mode.PARKING -> ParkingModeActivity::class.java
        }
}

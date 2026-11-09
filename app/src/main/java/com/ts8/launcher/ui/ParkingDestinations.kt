package com.ts8.launcher.ui

import android.app.Activity
import android.content.Intent
import android.util.Log

/**
 * The two screens Parking offers, opened from one place.
 *
 * **Why this is not written inline in `ParkingModeActivity`.** That file is
 * held to a contract by `ParkingMusicContractTest`: it must contain no
 * `startActivity`, no `startService`, no `play(` and no `playTrack(`, because
 * the screen it draws must never be the thing that *begins* playback. That rule
 * is about playback, and it stays; what changed is that the screen now has to
 * offer destinations, and launching an Activity is exactly the call the test
 * bans by name. Putting the launch here keeps the ban meaningful instead of
 * weakening it to make room for the buttons — a future edit that sneaks a
 * `startService` into the Activity still fails the test.
 *
 * **Why the flags.** Both screens are launcher tabs, and the driver arrives
 * here from Parking rather than from the nav bar. `REORDER_TO_FRONT` reuses an
 * instance that is already on the stack instead of rebuilding a MapLibre
 * surface or a playback screen behind it, and `SINGLE_TOP` stops a second copy
 * being pushed when the driver taps twice. The same pair
 * [BaseScreenActivity.navigateTo] uses, for the same reason.
 */
object ParkingDestinations {

    private const val TAG = "Ts8Parking"

    /** True when the map screen was actually started. */
    fun openMap(activity: Activity): Boolean = start(activity, MapActivity::class.java)

    /** True when the music screen was actually started. */
    fun openMusic(activity: Activity): Boolean =
        start(activity, MusicFullscreenActivity::class.java)

    /**
     * Launches [screen], reporting refusal instead of throwing.
     *
     * A missing or renamed Activity would otherwise take down the parking
     * screen at the exact moment the driver is trying to leave it, which is the
     * failure [MapManagementActivity.start] was written to avoid. The caller
     * shows a toast on false rather than leaving a button that looks dead.
     */
    private fun start(activity: Activity, screen: Class<out Activity>): Boolean =
        runCatching {
            activity.startActivity(
                Intent(activity, screen).addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
            ScreenTransition.apply(activity)
            true
        }.onFailure { failure ->
            Log.w(TAG, "could not open ${screen.simpleName}: ${failure.message}")
        }.getOrDefault(false)
}

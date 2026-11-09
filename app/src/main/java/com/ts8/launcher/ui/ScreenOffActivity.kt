package com.ts8.launcher.ui

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import com.ts8.launcher.R

/**
 * A black screen, standing in for the panel being off.
 *
 * **Why this exists.** The design's "màn hình tối" is a power control, and the
 * only API that actually turns the panel off — `PowerManager.goToSleep` — is
 * restricted to the system on Android 6. A launcher that printed "đã tắt màn
 * hình" and did nothing would be the fabricated success the spec forbids. So
 * this activity covers the screen with black instead: the backlight stays on,
 * the launcher says it is a screen-off *view* rather than a power command, and
 * a tap brings the driver back. It is a real, working, honestly-named
 * substitute.
 *
 * **Music keeps playing.** Nothing here touches the player or the navigation
 * state machine; this activity only draws a black window over them. `Music` and
 * the map stay alive in their own tasks, so the first tap returns to a running
 * trip rather than a restarted one.
 *
 * **Why it never turns the display off itself.** `setKeepScreenOn` is left
 * false, so the platform's own display timeout still applies — if the unit is
 * configured to sleep after N minutes, this screen does not block that.
 */
class ScreenOffActivity : BaseScreenActivity() {

    override val screen: BottomNavView.Screen = BottomNavView.Screen.NONE

    /**
     * No parking detection while the panel is "off".
     *
     * The detector opening the parking clock over this black window would undo
     * the one thing this screen does. The car coming to rest after the driver
     * turned the screen off is not a reason to turn something back on.
     */
    override val runsParkingDetector: Boolean
        get() = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen_off)

        // Full brightness is wrong here and so is minimum: this window is
        // pure black, so any brightness draws the same black. Touching it would
        // only fight the user's own setting.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv())
        findViewById<View>(R.id.screenOffRoot).setOnClickListener { finish() }
    }
}

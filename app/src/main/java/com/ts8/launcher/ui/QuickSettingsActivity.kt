package com.ts8.launcher.ui

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.ts8.core.map.MapStyleMode
import com.ts8.launcher.R
import com.ts8.launcher.di.AppGraph
import com.ts8.launcher.runtime.AppSettings
import com.ts8.launcher.runtime.AppStateMachine

/**
 * Quick settings — design state 6.
 *
 * **The rule this screen is built around:** every control acts on Android 6 with
 * only the permission a normal app can hold, or it is visibly unavailable. There
 * is no switch that flicks back silently, and no setting written to a preference
 * when the platform refused to change.
 *
 * | control       | API used                                  | on this unit |
 * |---------------|-------------------------------------------|--------------|
 * | Wi-Fi         | `WifiManager.setWifiEnabled` (deprecated, still present on 23) | works |
 * | Bluetooth     | `BluetoothAdapter.enable`/`disable`       | works |
 * | Mobile data   | **none** — `MODIFY_PHONE_STATE` is a signature permission | **unavailable**, shown as such |
 * | Day/night     | a launcher preference, applied to the map  | works |
 * | Brightness    | `Settings.System.SCREEN_BRIGHTNESS`, needs `WRITE_SETTINGS` | works after the grant |
 * | Volume        | `VehicleAudioBridge.setMediaVolume`       | works |
 * | Screen off    | a black full-screen activity, **not** `PowerManager.goToSleep` | works |
 * | Restart       | `PowerManager.reboot` is `REBOOT` (system-only) | **unavailable**, replaced by the app restart |
 * | Settings      | the platform settings app, with a fallback  | works |
 *
 * The two unavailable rows are the spec's "không giả báo thành công" made
 * concrete: mobile data toggling and device reboot are genuinely not available
 * to third-party apps on Android 6, so the design's tiles for them stay dimmed
 * with a reason rather than pretending.
 */
class QuickSettingsActivity : BaseScreenActivity() {

    /**
     * Not a tab. Reached from the home screen's status row and from Settings;
     * the bar shows no selection and back returns where the driver came from.
     */
    override val screen: BottomNavView.Screen = BottomNavView.Screen.NONE

    /** §16's `QUICK_SETTINGS` state; see [BaseScreenActivity.primaryState]. */
    override val primaryState: AppStateMachine.State? = AppStateMachine.State.QUICK_SETTINGS

    private lateinit var graph: AppGraph
    private lateinit var settings: AppSettings

    private lateinit var tileWifi: QsTileView
    private lateinit var tileBluetooth: QsTileView
    private lateinit var tileMobile: QsTileView
    private lateinit var tileTheme: QsTileView
    private lateinit var brightnessSlider: SeekBar
    private lateinit var brightnessValue: TextView
    private lateinit var volumeSlider: SeekBar
    private lateinit var volumeValue: TextView

    private val main = Handler(Looper.getMainLooper())

    /** True while the sliders are being written by the screen, not by a finger. */
    private var bindingSliders = false

    /**
     * True once the NO_EFFECT sentence has been shown this session.
     *
     * A volume drag produces tens of writes. §8 asks for the explanation, not
     * for ten copies of it: the second toast in a row is the one the driver
     * learns to dismiss without reading, and then the first one too.
     */
    private var noEffectAnnounced = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quick_settings)
        bindNavigation()

        graph = AppGraph.require()
        settings = AppSettings(this)

        tileWifi = findViewById(R.id.tileWifi)
        tileBluetooth = findViewById(R.id.tileBluetooth)
        tileMobile = findViewById(R.id.tileMobile)
        tileTheme = findViewById(R.id.tileTheme)
        brightnessSlider = findViewById(R.id.brightnessSlider)
        brightnessValue = findViewById(R.id.brightnessValue)
        volumeSlider = findViewById(R.id.volumeSlider)
        volumeValue = findViewById(R.id.volumeValue)

        wireTiles()
        wireSliders()
        wireActions()
    }

    override fun onResume() {
        super.onResume()
        // Re-read everything: the driver may have changed Wi-Fi from the
        // platform's own panel, granted WRITE_SETTINGS in the system dialog, or
        // plugged in a Bluetooth phone while this screen was in the background.
        refreshAll()
    }

    // -- tiles ---------------------------------------------------------------

    private fun wireTiles() {
        tileWifi.setOnClickListener { toggleWifi() }
        tileBluetooth.setOnClickListener { toggleBluetooth() }
        tileMobile.setOnClickListener {
            // Reaches the system's own mobile-data panel. That is the only
            // action available to a third-party app, and it is a real action —
            // the driver lands somewhere they can change the setting.
            openSystemPanel(Settings.ACTION_DATA_ROAMING_SETTINGS, R.string.qs_unavailable)
        }
        tileTheme.setOnClickListener {
            settings.mapStyleMode = settings.nextStyleMode()
            // The map screens read this on resume, so the change is visible the
            // moment the driver returns to Home or the map.
            refreshThemeTile()
        }
    }

    private fun refreshAll() {
        refreshWifiTile()
        refreshBluetoothTile()
        refreshMobileTile()
        refreshThemeTile()
        refreshBrightness()
        refreshVolume()
        refreshParkingButton()
    }

    private fun refreshWifiTile() {
        val state = runCatching {
            val wm = wifiManager()
            when {
                wm == null -> QsTileView.State.UNAVAILABLE
                wm.isWifiEnabled -> QsTileView.State.ON
                else -> QsTileView.State.OFF
            }
        }.getOrDefault(QsTileView.State.UNAVAILABLE)
        tileWifi.bind(state)
    }

    /**
     * `WIFI_SERVICE` is resolved on the **application** context.
     *
     * Before Android N the `WifiManager` keeps a reference to whatever context
     * asked for it, so asking an Activity leaks that Activity — and this one is
     * recreated on every visit to this screen. Holding the manager for the
     * process lifetime instead is both the fix and one fewer lookup.
     */
    private fun wifiManager(): WifiManager? =
        applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager

    private fun refreshBluetoothTile() {
        val state = runCatching {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            when {
                adapter == null -> QsTileView.State.UNAVAILABLE
                !adapter.isEnabled -> QsTileView.State.OFF
                else -> QsTileView.State.ON
            }
        }.getOrDefault(QsTileView.State.UNAVAILABLE)
        tileBluetooth.bind(state)
    }

    /**
     * Mobile data has no togglable API for a normal app.
     *
     * `TelephonyManager.setDataEnabled` requires `MODIFY_PHONE_STATE`, which is
     * a signature permission the launcher cannot hold. So the tile reports what
     * the radio is doing and, when tapped, opens the panel where the driver can
     * change it — a real action rather than a switch that lies.
     */
    private fun refreshMobileTile() {
        val connected = runCatching {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
            @Suppress("DEPRECATION")
            val info = cm?.activeNetworkInfo
            info != null && info.isConnected && info.type == ConnectivityManager.TYPE_MOBILE
        }.getOrDefault(false)
        if (connected) {
            tileMobile.bind(QsTileView.State.ON)
        } else {
            // Not "Tắt": the launcher cannot read whether the radio is off or
            // merely has no data session, and claiming "off" would be a guess.
            tileMobile.bind(QsTileView.State.NEEDS_PERMISSION, getString(R.string.qs_open_panel))
        }
    }

    private fun refreshThemeTile() {
        val mode = settings.mapStyleMode
        val label = getString(
            when (mode) {
                MapStyleMode.AUTO -> R.string.qs_theme_auto
                MapStyleMode.DAY -> R.string.qs_theme_day
                MapStyleMode.NIGHT -> R.string.qs_theme_night
            }
        )
        // "On" always: the tile is a three-way cycle, and its value line is the
        // mode, not a boolean. Rendering it as ON keeps the icon accent-lit,
        // which is how the design draws this tile.
        tileTheme.bind(QsTileView.State.ON, label)
    }

    // -- toggles -------------------------------------------------------------

    private fun toggleWifi() {
        val wm = wifiManager()
        if (wm == null) {
            Toast.makeText(this, R.string.qs_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val target = !wm.isWifiEnabled
        // The setter *method* is called, not the `isWifiEnabled` property:
        // Kotlin's synthetic property assignment yields `Unit`, which would
        // throw away `setWifiEnabled`'s boolean — the exact value this screen
        // needs in order to know whether the platform accepted the change.
        // Deprecated at API 29, and still the only API that exists at 23.
        @Suppress("DEPRECATION")
        val accepted = runCatching { wm.setWifiEnabled(target) }.getOrDefault(false)
        if (!accepted) {
            Toast.makeText(this, R.string.qs_permission_required, Toast.LENGTH_SHORT).show()
        }
        // Wi-Fi state settles asynchronously; a short re-read shows the truth
        // rather than the value we hoped for.
        main.postDelayed({ refreshWifiTile() }, STATE_SETTLE_MS)
    }

    private fun toggleBluetooth() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Toast.makeText(this, R.string.qs_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val target = !adapter.isEnabled
        // `BLUETOOTH` and `BLUETOOTH_ADMIN` are granted at install time on
        // Android 6 — this app targets SDK 30, below the 31 split that
        // introduced `BLUETOOTH_CONNECT`, so lint's warning names a permission
        // that does not exist on the target platform. The call is wrapped
        // anyway because a vendor ROM can refuse it, and `enable()` returning
        // false is handled below rather than assumed to have worked.
        @Suppress("MissingPermission")
        val accepted = runCatching {
            if (target) adapter.enable() else adapter.disable()
        }.getOrDefault(false)
        if (!accepted) {
            Toast.makeText(this, R.string.qs_permission_required, Toast.LENGTH_SHORT).show()
        }
        main.postDelayed({ refreshBluetoothTile() }, STATE_SETTLE_MS)
    }

    // -- sliders -------------------------------------------------------------

    private fun wireSliders() {
        brightnessSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                brightnessValue.text = getString(R.string.qs_percent, progress)
                if (!fromUser) return
                writeBrightness(progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) {
                // Writing every intermediate value would hammer the settings
                // provider with 100 writes per drag; this one is the final one.
                writeBrightness(bar.progress)
            }
        })

        volumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                // §8: the label is only ever written from a read of the stream,
                // never from the finger's position. While the drag is running a
                // preview is shown *dimmed* rather than as the truth, because a
                // stream that will not move must not appear to have moved.
                if (!fromUser) return
                writeVolume(progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
    }

    private fun refreshBrightness() {
        val canWrite = Settings.System.canWrite(this)
        val percent = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(DEFAULT_BRIGHTNESS)
        bindingSliders = true
        brightnessSlider.max = 100
        brightnessSlider.progress = percent.coerceIn(0, 100)
        brightnessSlider.isEnabled = canWrite
        brightnessSlider.alpha = if (canWrite) 1f else 0.4f
        brightnessValue.text = getString(R.string.qs_percent, percent.coerceIn(0, 100))
        bindingSliders = false

        if (!canWrite) {
            // Tapping a disabled slider does nothing, so the row offers the
            // grant instead. Without this the control is a dead-looking half
            // of the screen with no explanation.
            findViewById<View>(R.id.brightnessRow).setOnClickListener {
                openWriteSettingsGrant()
            }
        } else {
            findViewById<View>(R.id.brightnessRow).setOnClickListener(null)
        }
    }

    private fun writeBrightness(percent: Int) {
        if (bindingSliders) return
        if (!Settings.System.canWrite(this)) {
            openWriteSettingsGrant()
            return
        }
        val value = (percent * 255 / 100).coerceIn(1, 255)
        val written = runCatching {
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        }.getOrDefault(false)
        if (!written) {
            Toast.makeText(this, R.string.qs_permission_required, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWriteSettingsGrant() {
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            .setData(Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { startActivity(intent) }
        if (opened.isFailure) {
            Toast.makeText(this, R.string.qs_permission_required, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshVolume() {
        val bridge = graph.audioBridge
        val current = runCatching { bridge.mediaVolume() }.getOrDefault(0)
        val max = runCatching { bridge.maxMediaVolume() }.getOrDefault(1).coerceAtLeast(1)
        val fixed = runCatching { bridge.isVolumeFixed() }.getOrDefault(false)

        bindingSliders = true
        volumeSlider.max = max
        volumeSlider.progress = current.coerceIn(0, max)
        bindingSliders = false

        if (fixed) {
            // §8: "hiển thị `Không hỗ trợ trên thiết bị này` hoặc ẩn control
            // theo capability". A slider that cannot move the stream is a
            // control that lies about what the panel can do.
            volumeSlider.isEnabled = false
            volumeSlider.alpha = 0.4f
            volumeValue.text = getString(R.string.volume_unavailable)
            graph.volumeCapability.markUnavailable()
            return
        }

        volumeSlider.isEnabled = true
        volumeSlider.alpha = 1f
        volumeValue.text = renderVolume(current, max, graph.volumeCapability.capability)
    }

    /**
     * The number, plus the one word that keeps it honest.
     *
     * **Why the label carries the capability, not only the level.** The stream
     * on this class of head unit accepts `setStreamVolume` and does not move;
     * a bare "7" beside a slider the driver just pushed to 12 is the fabricated
     * success §8 exists to prevent. [VolumeCapabilityTracker.Capability.NO_EFFECT]
     * turns the caption into "Không có tác dụng · 7/15" — still showing the real
     * level, now saying what the panel will actually do with it.
     *
     * `UNKNOWN` deliberately shows the bare number: the device has not been
     * exercised yet, so there is nothing true to add, and pre-emptively
     * dimming a control that may work is its own kind of lie.
     */
    private fun renderVolume(
        level: Int,
        max: Int,
        capability: com.ts8.core.audio.VolumeCapabilityTracker.Capability
    ): String = when (capability) {
        com.ts8.core.audio.VolumeCapabilityTracker.Capability.SUPPORTED -> "$level/$max"
        com.ts8.core.audio.VolumeCapabilityTracker.Capability.NO_EFFECT ->
            "${getString(R.string.volume_no_effect_short)} · $level/$max"
        com.ts8.core.audio.VolumeCapabilityTracker.Capability.UNAVAILABLE ->
            getString(R.string.volume_unavailable)
        com.ts8.core.audio.VolumeCapabilityTracker.Capability.UNKNOWN -> "$level/$max"
    }

    /**
     * One volume write, read before and after (§8).
     *
     * **Why read-before is not the slider's value.** The drag continues while
     * the write happens, and another app may have moved the stream. The level
     * that actually changed is the difference between two *reads*, and that is
     * what the tracker is given.
     *
     * **Why the UI snaps back when the read-back does not move.** §8's rule is
     * that a failed or inert write must not leave the fingers' value on screen
     * as though it had been applied. The bar is re-bound to the stream's real
     * level, which is the same correction the bottom bar's stepper makes.
     */
    private fun writeVolume(requested: Int) {
        if (bindingSliders) return
        val bridge = graph.audioBridge
        val before = runCatching { bridge.mediaVolume() }.getOrDefault(requested)
        val max = runCatching { bridge.maxMediaVolume() }.getOrDefault(1).coerceAtLeast(1)
        val tracker = graph.volumeCapability
        tracker.setMaxKnown(max)

        val result = runCatching { bridge.setMediaVolume(requested) }
            .getOrElse { com.ts8.core.audio.AudioResult.Failed(it.message ?: "write failed") }
        val after = runCatching { bridge.mediaVolume() }.getOrDefault(before)
        val capability = tracker.record(requested, before, after, result)

        // Re-bind the bar to what the stream actually holds. On a supported
        // stream this is the requested value and nothing visibly moves; on an
        // inert one it snaps back, which is the whole point.
        bindingSliders = true
        volumeSlider.progress = after.coerceIn(0, volumeSlider.max)
        bindingSliders = false
        volumeValue.text = renderVolume(after, max, capability)

        when (result) {
            is com.ts8.core.audio.AudioResult.Unsupported ->
                Toast.makeText(this, R.string.volume_unavailable, Toast.LENGTH_SHORT).show()
            is com.ts8.core.audio.AudioResult.Failed ->
                Toast.makeText(this, R.string.volume_unavailable, Toast.LENGTH_SHORT).show()
            is com.ts8.core.audio.AudioResult.Denied ->
                Toast.makeText(this, R.string.volume_no_effect, Toast.LENGTH_SHORT).show()
            else -> {
                if (capability == com.ts8.core.audio.VolumeCapabilityTracker.Capability.NO_EFFECT) {
                    announceNoEffect()
                }
            }
        }
    }

    /**
     * Says it once per session, not once per drag frame.
     *
     * A drag produces tens of writes; a toast per write would stack into a wall
     * of the same sentence and teach the driver to dismiss it unread.
     */
    private fun announceNoEffect() {
        if (noEffectAnnounced) return
        noEffectAnnounced = true
        Toast.makeText(this, R.string.volume_no_effect, Toast.LENGTH_LONG).show()
    }

    // -- actions -------------------------------------------------------------

    private fun wireActions() {
        findViewById<View>(R.id.btnScreenOff).setOnClickListener {
            // The design calls this "màn hình tối". `PowerManager.goToSleep`
            // needs the DEVICE_POWER signature permission, so the launcher
            // shows a black screen of its own instead — an honest, working
            // substitute that leaves the head unit on and the music playing.
            startActivity(Intent(this, ScreenOffActivity::class.java))
        }
        findViewById<View>(R.id.btnParking).setOnClickListener { toggleParking() }
        findViewById<View>(R.id.btnRestart).setOnClickListener {
            confirmAppRestart()
        }
        // §6's Quick Settings entry into Map Management. Settings > Bản đồ has
        // one, but Quick Settings is where a driver looks for a system-level
        // fix, and it is reachable from every screen — the only route to the
        // map pack from the music screen or the drawer. Launched through the
        // helper, which reports refusal rather than throwing, so a future
        // manifest edit cannot take Quick Settings down with it.
        findViewById<View>(R.id.btnMapManagement).setOnClickListener {
            if (!MapManagementActivity.start(this)) {
                Toast.makeText(this, R.string.map_management_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    /**
     * The driver's own parking instruction (§19).
     *
     * **Why this is not just "open the parking screen".** A button that only
     * navigates would leave the resolver believing the car is driving, so the
     * automatic path would not know the driver had already decided; and the
     * screen's own "tap to exit" could not tell the resolver to stay out of it
     * afterwards. Pressing here therefore *tells the detector* first and opens
     * the screen second — the same order the resolver's manual branch requires,
     * and the reason [com.ts8.launcher.runtime.ParkingRuntime.requestEnter] is a
     * process-level call rather than a screen-level one.
     *
     * **Why the same button exits.** One control in one place, and its label is
     * the state it will produce. A separate exit button would be a second thing
     * to find on a screen the driver is looking at while walking away.
     */
    private fun toggleParking() {
        val parking = graph.parking
        if (parking.isParked()) {
            parking.requestExit()
            refreshParkingButton()
            // Return to whichever screen the driver came from rather than
            // leaving the panel on a settings page they no longer need.
            finish()
            return
        }
        parking.requestEnter()
        // SINGLE_TOP plus REORDER_TO_FRONT: pressing this a second time while
        // the parking screen is already up must return to that instance, not
        // stack a second clock behind it. Without the flags a driver who tapped
        // twice would find Back landed on a duplicate parking screen that
        // looked identical to the one they dismissed.
        startActivity(
            Intent(this, ParkingModeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    /** The button says what it will do; nothing else on this screen changes. */
    private fun refreshParkingButton() {
        findViewById<android.widget.Button?>(R.id.btnParking)?.setText(
            if (graph.parking.isParked()) R.string.parking_exit else R.string.qs_parking_mode
        )
    }

    /**
     * Restarts the launcher, not the device.
     *
     * `PowerManager.reboot` requires the `REBOOT` permission, which is granted
     * only to the system. So the button offers the action it can keep: tearing
     * the task down and starting Home again, which is what a driver actually
     * wants when the panel has wedged.
     */
    private fun confirmAppRestart() {
        showTrackedDialog(
            android.app.AlertDialog.Builder(this)
                .setMessage(R.string.qs_restart_confirm)
                .setNegativeButton(R.string.generic_cancel, null)
                .setPositiveButton(R.string.generic_ok) { _, _ ->
                    // A fresh Home task, with everything else finished behind it.
                    val home = Intent(this, HomeActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
                    startActivity(home)
                    finish()
                }
                .show()
        )
    }

    private fun openSystemPanel(action: String, failureMessage: Int) {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // A firmware that stripped the platform panel: say so instead of a
            // dead tap.
            Toast.makeText(this, failureMessage, Toast.LENGTH_SHORT).show()
        }
    }

    /** Re-reads the volume after the bar's own stepper has changed it. */
    override fun onStart() {
        super.onStart()
        if (::volumeSlider.isInitialized) refreshVolume()
    }

    companion object {
        /** Wi-Fi and Bluetooth settle over a broadcast, not synchronously. */
        private const val STATE_SETTLE_MS = 600L

        /** Android's default when nothing has ever been written. */
        private const val DEFAULT_BRIGHTNESS = 128

        /** Opens Quick Settings from a status-row tap. */
        fun intentTo(context: android.content.Context): Intent =
            Intent(context, QuickSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

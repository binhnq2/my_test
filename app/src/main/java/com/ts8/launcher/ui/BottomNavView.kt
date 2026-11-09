package com.ts8.launcher.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.ts8.core.audio.AudioResult
import com.ts8.core.audio.VolumeCapabilityTracker
import com.ts8.launcher.R
import com.ts8.launcher.di.AppGraph

/**
 * The one navigation bar in the app.
 *
 * **Why it is a custom view rather than a fragment.** Every screen needs the
 * identical bar in the identical position, and a fragment would let one screen
 * drift (a screen that forgot to add it, a screen that reordered the items).
 * As a view inflated into each screen's layout, the bar is part of the screen
 * and cannot be omitted by omission.
 *
 * **The Phone item is not a screen.** On a head unit, tapping "Điện thoại" is a
 * request to place a call, and the design's phone icon means exactly that. So
 * this item resolves a dialer intent instead of pushing a screen — and when the
 * device has no dialer, it opens the app drawer filtered to what is installed
 * rather than failing silently, which is the spec's "graceful when an app is
 * missing" requirement.
 */
class BottomNavView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Which screen is showing, so the bar can render the selected item. */
    enum class Screen { HOME, MAP, MUSIC, APPS, NONE }

    var onNavigate: ((Screen) -> Unit)? = null

    private val items: Map<Screen, LinearLayout>
    private val icons: Map<Screen, ImageView>
    private val labels: Map<Screen, TextView>

    private val volumeLabel: TextView
    private var selected: Screen = Screen.NONE

    /** True once the NO_EFFECT explanation has been shown, so it happens once. */
    private var noEffectAnnounced = false

    init {
        LayoutInflater.from(context).inflate(R.layout.view_bottom_nav, this, true)

        items = mapOf(
            Screen.HOME to findViewById(R.id.navHome),
            Screen.MAP to findViewById(R.id.navMap),
            Screen.MUSIC to findViewById(R.id.navMusic),
            Screen.APPS to findViewById(R.id.navApps)
        )
        icons = mapOf(
            Screen.HOME to findViewById(R.id.navHomeIcon),
            Screen.MAP to findViewById(R.id.navMapIcon),
            Screen.MUSIC to findViewById(R.id.navMusicIcon),
            Screen.APPS to findViewById(R.id.navAppsIcon)
        )
        labels = mapOf(
            Screen.HOME to findViewById(R.id.navHomeLabel),
            Screen.MAP to findViewById(R.id.navMapLabel),
            Screen.MUSIC to findViewById(R.id.navMusicLabel),
            Screen.APPS to findViewById(R.id.navAppsLabel)
        )
        volumeLabel = findViewById(R.id.volLabel)

        items.forEach { (screen, item) ->
            item.setOnClickListener { select(screen) }
        }
        findViewById<LinearLayout>(R.id.navPhone).setOnClickListener { openDialer() }

        findViewById<View>(R.id.volDown).setOnClickListener { stepVolume(-1) }
        findViewById<View>(R.id.volUp).setOnClickListener { stepVolume(+1) }

        findViewById<View>(R.id.btnVoice)?.setOnClickListener {
            onVoice?.invoke() ?: (context as? BaseScreenActivity)?.startVoice()
        }
        findViewById<View>(R.id.btnQuickSettings)?.setOnClickListener {
            runCatching { context.startActivity(QuickSettingsActivity.intentTo(context)) }
        }

        refreshVolume()
    }

    var onVoice: (() -> Unit)? = null

    /** Highlights [screen] and leaves the others muted. */
    fun setSelectedScreen(screen: Screen) {
        if (selected == screen) return
        selected = screen
        items.forEach { (s, item) ->
            item.isSelected = s == screen
            // The label colour is a selector on `state_selected`, so setting it
            // on the item propagates without touching the child views.
            labels[s]?.isSelected = s == screen
        }
    }

    private fun select(screen: Screen) {
        setSelectedScreen(screen)
        onNavigate?.invoke(screen)
    }

    // -- phone -------------------------------------------------------------

    /**
     * Opens the platform dialer.
     *
     * Two fallbacks, in order: the dial pad, then the app drawer. A head unit
     * whose vendor firmware stripped `com.android.dialer` still has to let the
     * driver reach whatever it uses to make calls, so the drawer is a truthful
     * answer rather than an error toast.
     */
    private fun openDialer() {
        val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (dial.resolveActivity(context.packageManager) != null) {
            runCatching { context.startActivity(dial) }
                .onFailure { openDrawerInstead() }
            return
        }
        openDrawerInstead()
    }

    private fun openDrawerInstead() {
        val opened = runCatching {
            context.startActivity(
                Intent(context, AppDrawerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        if (opened.isFailure) {
            // Neither a dialer nor our own drawer: say so instead of a dead tap.
            Toast.makeText(context, R.string.qs_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    // -- volume ------------------------------------------------------------

    /**
     * Steps the media stream and shows the new level.
     *
     * Through [com.ts8.core.audio.VehicleAudioBridge] rather than
     * `AudioManager` directly, so that a later firmware bridge which routes
     * volume through the MCU changes one implementation instead of every
     * control.
     *
     * **§8: never move the number unless the stream moved.** Before Batch 1
     * this treated `Denied` as success-equivalent and collapsed `Unsupported`
     * and `Failed` into one message, and — worse — a firmware that accepted the
     * write and ignored it returned `Success`, so the label re-read the old
     * value and the driver got no explanation at all for a "+" that did
     * nothing. Now every outcome is its own case, and the label is only ever
     * written from a fresh read, never from the number the finger asked for.
     *
     * The driver is told once per session, not once per tap: a stream that
     * cannot move would otherwise raise the same toast on every press, which
     * is how a real warning becomes noise the driver learns to ignore.
     */
    private fun stepVolume(delta: Int) {
        val bridge = AppGraph.current?.audioBridge ?: return
        val current = bridge.mediaVolume()
        val max = bridge.maxMediaVolume().coerceAtLeast(1)
        val target = (current + delta).coerceIn(0, max)
        if (target == current) return

        val capability = volumeTracker
        capability?.setMaxKnown(max)

        when (val r = bridge.setMediaVolume(target)) {
            is AudioResult.Success -> {
                // Read back rather than trusting the request: the bridge has
                // already compared them, but a second read here is what the
                // label shows, so it is never a number nobody observed.
                capability?.record(target, current, bridge.mediaVolume(), r)
                refreshVolume()
            }
            is AudioResult.NoEffect -> {
                val state = capability?.record(target, current, r.observed, r)
                // The number does not move: it shows the level the stream
                // actually has. The message explains the silence.
                refreshVolume()
                if (state == VolumeCapabilityTracker.Capability.NO_EFFECT) {
                    announceNoEffect()
                }
            }
            is AudioResult.Denied -> {
                // A refusal right now (call in progress, focus elsewhere) is
                // not a device limitation; say so instead of pretending.
                refreshVolume()
                Toast.makeText(context, R.string.volume_no_effect, Toast.LENGTH_SHORT).show()
            }
            is AudioResult.Unsupported -> {
                capability?.markUnavailable()
                refreshVolume()
                Toast.makeText(context, R.string.volume_unavailable, Toast.LENGTH_SHORT).show()
            }
            is AudioResult.Failed -> {
                refreshVolume()
                Toast.makeText(context, R.string.volume_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Raises the NO_EFFECT explanation at most once per session. */
    private fun announceNoEffect() {
        if (noEffectAnnounced) return
        noEffectAnnounced = true
        Toast.makeText(context, R.string.volume_no_effect, Toast.LENGTH_LONG).show()
        // The control stays visible but reads as degraded: the driver can still
        // try, and Diagnostics can re-test, but the label no longer implies the
        // number is something the cabin follows.
        volumeLabel.alpha = 0.55f
    }

    /** Re-reads the stream level; called on attach and after a step. */
    fun refreshVolume() {
        val bridge = AppGraph.current?.audioBridge ?: return
        val level = bridge.mediaVolume()
        volumeLabel.text = level.toString()
        // A stream the platform reports as fixed has nothing to offer; saying
        // so is better than a stepper that can never move (§8's "hiển thị
        // `Không hỗ trợ trên thiết bị này` hoặc ẩn control theo capability").
        if (runCatching { bridge.isVolumeFixed() }.getOrDefault(false)) {
            volumeLabel.text = context.getString(R.string.volume_unavailable)
            volumeLabel.textSize = 9f
            volumeTracker?.markUnavailable()
        }
    }

    /**
     * The capability tracker for this control.
     *
     * Held process-wide in [AppGraph] rather than here: the same stream is
     * stepped from Quick Settings and from a voice command, and three separate
     * trackers would each need two taps of their own to learn what one tap
     * anywhere already proved.
     */
    private val volumeTracker: VolumeCapabilityTracker?
        get() = AppGraph.current?.volumeCapability

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshVolume()
    }
}

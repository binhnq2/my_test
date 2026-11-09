package com.ts8.launcher.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import com.ts8.core.voice.VoiceState
import com.ts8.core.voice.VoiceStateSnapshot
import com.ts8.launcher.di.AppGraph
import com.ts8.launcher.runtime.AppStateMachine
import com.ts8.launcher.runtime.VoiceInstantController
import com.ts8.launcher.runtime.VoiceTrigger

/**
 * Hangs Voice Instant's overlay on a screen and connects the screen's Back key
 * to it.
 *
 * **Why a host object instead of code in [BaseScreenActivity].** The overlay
 * needs a `VoiceOverlayView` added above the screen's own content, a listener
 * attached on `onStart` and detached on `onStop`, and a `VoiceTrigger` bound to
 * the activity — about sixty lines that would otherwise repeat in every screen
 * that offers voice. The base class owns the one instance and its lifecycle;
 * this object owns the wiring, so a screen that wants a mic button only needs
 * the host.
 *
 * **Why the overlay is a child of `android.R.id.content`.** That is the one
 * container every screen here has, it is a `FrameLayout`, and adding the bar
 * there puts it above the screen's own views without any elevation arithmetic
 * or a second window. A `Dialog` would dim the launcher and outlive the screen;
 * a sibling `ViewGroup` per layout would have to be added to twelve XML files
 * and would drift.
 *
 * **Why `onStop` detaches rather than cancels.** The interaction belongs to the
 * process, not to the screen: the driver can say "dẫn đường tới Cầu Rồng" and
 * the map comes forward, which stops Home. Cancelling on stop would kill the
 * command in the middle of the navigation it started. Detaching only stops the
 * *rendering*; the router keeps its state, and the next screen's host renders
 * it again from [VoiceInstantController.snapshot].
 *
 * **No continuous recording.** [attach] never opens the microphone; only
 * [trigger] does, and that is only ever called from a tap.
 */
class VoiceOverlayHost(private val activity: BaseScreenActivity) {

    private val graph: AppGraph get() = AppGraph.require()

    private val controller: VoiceInstantController get() = graph.voiceInstant()

    private var overlay: VoiceOverlayView? = null
    private var trigger: VoiceTrigger? = null
    private var attached = false

    /** The last caption the router spoke, so a re-attach can restore it. */
    private var caption: String? = null

    private val listener = object : VoiceInstantController.Listener {
        override fun onState(snapshot: VoiceStateSnapshot) {
            if (snapshot.state == VoiceState.IDLE) caption = null
            overlay?.render(snapshot, caption)
            syncOverlayState(snapshot.state)
        }

        override fun onCaption(text: String) {
            caption = text
            overlay?.render(controller.snapshot(), text)
        }
    }

    /**
     * Mirrors the voice interaction into the §16 overlay stack.
     *
     * Two overlay entries rather than one per `VoiceState`: the brief names
     * `VOICE_LISTENING` and `VOICE_CONFIRMATION`, and the three middle states
     * (PROCESSING, EXECUTING, CONFIRMING) are all "the driver must answer or
     * wait, and Back cancels" — which is what `VOICE_CONFIRMATION` means here.
     * All three take Back, and none of them draws anything different on screen
     * once the caption is being shown; splitting them would give the machine
     * three stack entries for one Back press to unwind.
     *
     * A refused transition is not possible here: the overlay stack is
     * orthogonal to the primary state, so pushing a voice overlay from any
     * screen is legal by construction.
     */
    private fun syncOverlayState(state: VoiceState) {
        val machine = runCatching { graph.uiState }.getOrNull() ?: return
        when (state) {
            VoiceState.LISTENING -> {
                machine.popOverlay(AppStateMachine.Overlay.VOICE_CONFIRMATION)
                machine.pushOverlay(AppStateMachine.Overlay.VOICE_LISTENING)
            }
            VoiceState.PROCESSING,
            VoiceState.EXECUTING,
            VoiceState.CONFIRMING -> {
                machine.popOverlay(AppStateMachine.Overlay.VOICE_LISTENING)
                machine.pushOverlay(AppStateMachine.Overlay.VOICE_CONFIRMATION)
            }
            // Terminal and idle states leave nothing on the stack: the bar
            // either shows a result that Back does not dismiss (SUCCESS,
            // ERROR) or is gone entirely.
            VoiceState.IDLE, VoiceState.SUCCESS, VoiceState.ERROR -> {
                machine.popOverlay(AppStateMachine.Overlay.VOICE_LISTENING)
                machine.popOverlay(AppStateMachine.Overlay.VOICE_CONFIRMATION)
            }
        }
    }

    /**
     * Creates the bar and adds it above the screen's content.
     *
     * **Must tolerate being called twice.** `AppCompatDelegate.setContentView`
     * fires `onContentChanged` once for the sub-decor and again after the
     * screen's own layout is inflated, and the second inflation clears
     * `android.R.id.content`. So the bar view is kept and re-attached whenever
     * it has lost its parent, rather than being created once and assumed to
     * still be there — the alternative is a bar that exists in the field only
     * on the screens whose layout happened to be set first.
     */
    fun install() {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val bar = overlay ?: VoiceOverlayView(activity).also { created ->
            created.onConfirm = { yes -> controller.confirm(yes) }
            created.onCancel = { dismiss() }
            created.onRetry = {
                // Retrying is a fresh interaction, not a resume: the recogniser
                // session that failed is gone and the router has already
                // returned to IDLE, so the only correct next step is to open the
                // microphone again through the trigger (which also re-checks the
                // permission).
                trigger?.request()
            }
            created.onOpenSettings = { trigger?.openPermissionSettings() }
            overlay = created
        }
        if (trigger == null) trigger = VoiceTrigger(activity, controller)
        if (bar.parent === root) return
        (bar.parent as? ViewGroup)?.removeView(bar)
        root.addView(
            bar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    /** Starts rendering the current snapshot. Call from `onStart`. */
    fun attach() {
        if (attached) return
        attached = true
        controller.attach(listener)
        // A state that survived the screen switch — a SUCCESS the driver has
        // not dismissed, or a CONFIRMING that was on the previous screen — is
        // drawn immediately; otherwise the bar is hidden.
        overlay?.render(controller.snapshot(), caption)
    }

    /** Stops rendering. The interaction itself keeps running. Call from `onStop`. */
    fun detach() {
        if (!attached) return
        attached = false
        controller.detach()
        overlay?.hide()
    }

    /** Opens the microphone, requesting the permission when it is missing. */
    fun trigger(): Boolean = trigger?.request() ?: false

    /**
     * True when Back belongs to the overlay rather than to the screen.
     *
     * The snapshot's own rule is used ([VoiceStateSnapshot.backCancels]) rather
     * than a list written here: the router decides which states a Back cancels,
     * and a second copy of that rule would drift the first time a state is
     * added. The §16 machine's stack is then trimmed, so a Back that closes the
     * microphone does not leave `VOICE_LISTENING` on the stack for the next
     * Back press to close a second time.
     */
    fun handleBack(): Boolean {
        val snapshot = controller.snapshot()
        if (!snapshot.backCancels) return false
        runCatching { graph.uiState }.getOrNull()?.popTopOverlay()
        dismiss()
        return true
    }

    /** Closes the overlay and abandons whatever the interaction was doing. */
    fun dismiss() {
        caption = null
        controller.cancel()
        overlay?.render(controller.snapshot(), null)
    }

    /** Forwards the permission answer; see [BaseScreenActivity]. */
    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray): Boolean =
        trigger?.onRequestPermissionsResult(requestCode, grantResults) ?: false

    /** True while the bar is on screen, so a screen can suppress a hint behind it. */
    fun isShowing(): Boolean = controller.snapshot().overlayVisible

    /** Releases the view references; the process-owned controller stays alive. */
    fun release() {
        detach()
        overlay?.let { bar ->
            (bar.parent as? ViewGroup)?.removeView(bar)
        }
        overlay = null
        trigger = null
    }
}

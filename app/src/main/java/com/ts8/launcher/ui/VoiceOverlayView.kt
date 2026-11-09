package com.ts8.launcher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ts8.core.voice.VoiceError
import com.ts8.core.voice.VoiceState
import com.ts8.core.voice.VoiceStateSnapshot
import com.ts8.core.voice.VoiceStateStore
import com.ts8.launcher.R

/**
 * Draws one [VoiceStateSnapshot] and nothing else.
 *
 * **Why a plain view and not a dialog.** A `Dialog` on API 23 owns a window, a
 * back-press dispatch and its own dim layer; the driver would see the launcher
 * dim behind a voice prompt, and a dialog left up by a bug survives the screen
 * that created it. This view is a child of the activity's content root, so it
 * disappears when the screen does and Back reaches the activity first.
 *
 * **Why it never animates the bar's height.** The pill is one fixed shape and
 * only its contents change per state. Growing/shrinking it during CONFIRMING
 * would move the two buttons down exactly while the driver is aiming at them.
 *
 * **What it does not decide.** Which buttons *mean* what, whether an error is
 * retryable and whether Back cancels are all answered by the snapshot and by
 * `VoiceStateSnapshot.backCancels`; the view maps them to views. Adding a rule
 * here would put a voice decision in a layout class, which is the split
 * `VoiceRouter` exists to keep.
 *
 * **The `Voice service unavailable` sentence.** §10 requires that exact phrase
 * when the platform has no recogniser. It is rendered as the title with the
 * Vietnamese explanation as the caption, so the driver sees both the literal
 * state and what it means — see `voice_error_unavailable`.
 */
class VoiceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Answer to a confirmation prompt. */
    var onConfirm: ((Boolean) -> Unit)? = null

    /** Close / Back inside the overlay. */
    var onCancel: (() -> Unit)? = null

    /** "Nói lại" after a retryable failure. */
    var onRetry: (() -> Unit)? = null

    /** "Mở cài đặt quyền" after `PERMISSION_DENIED`. */
    var onOpenSettings: (() -> Unit)? = null

    private val pill: LinearLayout
    private val icon: ImageView
    private val title: TextView
    private val heard: TextView
    private val caption: TextView
    private val actions: LinearLayout
    private val btnYes: Button
    private val btnNo: Button
    private val btnRetry: Button
    private val btnSettings: Button
    private val btnClose: ImageButton

    /** Set while a confirmation is up, so the caller can query it cheaply. */
    var isConfirming: Boolean = false
        private set

    /** The last rendered snapshot; null before the first render. */
    var snapshot: VoiceStateSnapshot? = null
        private set

    init {
        LayoutInflater.from(context).inflate(R.layout.view_voice_overlay, this, true)
        pill = findViewById(R.id.voicePill)
        icon = findViewById(R.id.voiceIcon)
        title = findViewById(R.id.voiceTitle)
        heard = findViewById(R.id.voiceHeard)
        caption = findViewById(R.id.voiceCaption)
        actions = findViewById(R.id.voiceActions)
        btnYes = findViewById(R.id.btnVoiceYes)
        btnNo = findViewById(R.id.btnVoiceNo)
        btnRetry = findViewById(R.id.btnVoiceRetry)
        btnSettings = findViewById(R.id.btnVoiceSettings)
        btnClose = findViewById(R.id.btnVoiceClose)

        btnYes.setOnClickListener { onConfirm?.invoke(true) }
        btnNo.setOnClickListener { onConfirm?.invoke(false) }
        btnRetry.setOnClickListener { onRetry?.invoke() }
        btnSettings.setOnClickListener { onOpenSettings?.invoke() }
        btnClose.setOnClickListener { onCancel?.invoke() }

        visibility = View.GONE
    }

    /**
     * Renders [state], with [spoken] as the caption line.
     *
     * [spoken] is passed through rather than stored because the caption is the
     * router's own sentence, not something the view may compose: quoting the
     * wrong reason is the failure mode this parameter exists to avoid.
     */
    fun render(state: VoiceStateSnapshot, spoken: String?) {
        snapshot = state
        isConfirming = state.state == VoiceState.CONFIRMING

        if (!state.overlayVisible) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE

        title.setText(titleFor(state))

        // The transcript, quoted before anything else happens in the cabin.
        // Rendered from the snapshot rather than composed here so the words on
        // screen are the recogniser's own output, not a summary of it.
        val said = state.transcript?.takeIf { it.isNotBlank() }
        if (said == null) {
            heard.visibility = View.GONE
        } else {
            heard.text = context.getString(R.string.voice_heard, said)
            heard.visibility = View.VISIBLE
        }

        // The caption is the router's sentence when there is one, and the state
        // description otherwise — never an empty second line, which reads as a
        // rendering bug rather than "nothing to add".
        val line = spoken?.takeIf { it.isNotBlank() }
        if (line == null) {
            caption.visibility = View.GONE
        } else {
            caption.text = line
            caption.visibility = View.VISIBLE
        }

        val error = state.error
        btnYes.visibility = if (state.state == VoiceState.CONFIRMING) View.VISIBLE else View.GONE
        btnNo.visibility = if (state.state == VoiceState.CONFIRMING) View.VISIBLE else View.GONE

        // Retry is offered only where saying it again can plausibly work. A
        // missing recogniser is not retryable (the platform will answer the
        // same way), and neither is a denied permission — that one gets a
        // Settings button instead.
        val retryable = state.state == VoiceState.ERROR &&
            (error == VoiceError.NO_SPEECH ||
                error == VoiceError.NO_MATCH ||
                error == VoiceError.NOT_UNDERSTOOD ||
                error == VoiceError.COMMAND_FAILED ||
                // A network failure and a busy microphone both clear on their
                // own; offering "Nói lại" is the honest affordance for them.
                error == VoiceError.NETWORK_ERROR ||
                error == VoiceError.MIC_BUSY ||
                error == VoiceError.INTERNAL_ERROR)
        btnRetry.visibility = if (retryable) View.VISIBLE else View.GONE
        btnSettings.visibility =
            if (state.state == VoiceState.ERROR && error == VoiceError.PERMISSION_DENIED) {
                View.VISIBLE
            } else {
                View.GONE
            }

        // Close is pointless in CONFIRMING (the No button is the cancel) but
        // required everywhere else, including SUCCESS, so the driver can clear
        // a result without waiting out the auto-dismiss.
        btnClose.visibility =
            if (state.state == VoiceState.CONFIRMING) View.GONE else View.VISIBLE

        icon.setColorFilter(
            context.getColor(accentOrDanger(state))
        )
    }

    /** Hides the bar without changing the snapshot, e.g. when the screen stops. */
    fun hide() {
        isConfirming = false
        visibility = View.GONE
    }

    private fun accentOrDanger(state: VoiceStateSnapshot): Int = when (state.state) {
        VoiceState.ERROR -> R.color.danger
        VoiceState.SUCCESS -> R.color.success
        else -> R.color.accent_text
    }

    private fun titleFor(state: VoiceStateSnapshot): String = when (state.state) {
        VoiceState.IDLE -> ""
        VoiceState.LISTENING -> context.getString(R.string.voice_listening)
        VoiceState.PROCESSING -> context.getString(R.string.voice_processing)
        VoiceState.CONFIRMING -> context.getString(R.string.voice_confirming)
        VoiceState.EXECUTING -> context.getString(R.string.voice_executing)
        VoiceState.SUCCESS -> context.getString(R.string.voice_state_success)
        VoiceState.ERROR -> errorTitle(state.error)
    }

    /**
     * The error's own sentence, except for the platform-missing case where §10
     * fixes the wording at `Voice service unavailable`.
     */
    private fun errorTitle(error: VoiceError?): String = when (error) {
        VoiceError.SERVICE_UNAVAILABLE -> context.getString(R.string.voice_error_unavailable)
        VoiceError.PERMISSION_DENIED -> context.getString(R.string.voice_error_permission)
        VoiceError.NO_SPEECH, VoiceError.NO_MATCH ->
            context.getString(R.string.voice_error_no_match)
        VoiceError.TIMEOUT -> context.getString(R.string.voice_error_timeout)
        // The new states each carry their own sentence; the caption below the
        // title says what to do about it, so the title stays short enough for
        // the 1024x600 bar. LANGUAGE_UNAVAILABLE is the one that must not be
        // mistaken for "no voice on this device" — its title names the model.
        VoiceError.LANGUAGE_UNAVAILABLE -> context.getString(R.string.voice_error_language)
        VoiceError.MIC_BUSY -> context.getString(R.string.voice_error_mic_busy)
        VoiceError.NETWORK_ERROR, VoiceError.INTERNAL_ERROR -> context.getString(R.string.voice_state_error)
        VoiceError.CANCELLED -> context.getString(R.string.voice_error_cancelled)
        VoiceError.NOT_UNDERSTOOD -> context.getString(R.string.voice_state_error)
        VoiceError.COMMAND_FAILED, null -> context.getString(R.string.voice_state_error)
    }

    companion object {
        /**
         * The key `VoiceStateStore` writes to; re-exported so a screen can hand
         * the overlay the restored snapshot without knowing the store's shape.
         */
        const val STATE_KEY: String = VoiceStateStore.KEY
    }
}

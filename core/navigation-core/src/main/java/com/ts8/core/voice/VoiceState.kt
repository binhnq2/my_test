package com.ts8.core.voice

/**
 * The seven states Voice Instant is allowed to be in.
 *
 * **Why [CONFIRMING] is a state and not a boolean on the overlay.** A dialog
 * flag is invisible to everything that is not the dialog: Back would close a
 * window the router still believes is open, process recreation would restore a
 * "no" that never happened, and the music ducking started for the confirmation
 * prompt would never be undone because nobody counted it. As a state, "waiting
 * for yes/no" is the single fact the overlay renders from, the only state in
 * which Back is a cancel rather than a screen pop, and the one the router can
 * time out of on its own.
 *
 * The transitions are the contract; see [VoiceRouter]. A transcript may only
 * move the machine forward from [IDLE] or [ERROR]; everything else is a caller
 * bug, and the router drops it rather than guessing.
 */
enum class VoiceState {
    /** No microphone, no prompt, nothing pending. The overlay is not drawn. */
    IDLE,

    /** The platform recogniser is capturing. Only the app module can leave this
     *  state, by handing the router a transcript or an error. */
    LISTENING,

    /** A transcript arrived and the parser is deciding what it means. */
    PROCESSING,

    /**
     * A command was understood but the executor cannot choose for the driver
     * and must ask. Back cancels from here and only here.
     *
     * **Not the everyday path.** A destructive command such as `NavigateTo`
     * used to stop here for a yes/no tap; that prompt is gone, because it made
     * one utterance into two interactions. What is left is the genuine
     * ambiguity — `Bến Thành` naming two places — where choosing either one
     * would send the car somewhere the driver did not say.
     */
    CONFIRMING,

    /** The command was handed to the executor and has not returned yet. */
    EXECUTING,

    /** The executor reported success. Terminal until the overlay asks for IDLE. */
    SUCCESS,

    /** Something failed; [VoiceStateSnapshot.error] says what. Terminal until
     *  the overlay asks for IDLE. */
    ERROR
}

/**
 * Every way a voice command can fail, kept enumerable so the overlay has exactly
 * one branch per case and the app module can map `SpeechRecognizer` result codes
 * onto a closed set instead of inventing strings.
 *
 * None of these is ever silently swallowed: a failure the driver cannot see is
 * indistinguishable from a command still running, which is how an overlay ends
 * up stuck on "Đang xử lý…" until ignition off.
 */
enum class VoiceError {
    /**
     * No recogniser installed, or its service is not bound. Not a no-match.
     *
     * **Only this, and nothing adjacent.** The offline-pilot brief is explicit
     * that a busy microphone, a denied permission, a silence timeout, an
     * unreachable network or a single no-match must each keep their own state:
     * collapsing them here would make every one of them read on the overlay as
     * "the device has no voice service", which is true for exactly the first
     * case and actively misleads the driver for the rest. This value is
     * produced only when no `RecognitionService` exists, when
     * `isRecognitionAvailable()` is false, when the provider could not be
     * created or bound, or when it disconnected and is permanently unusable.
     */
    SERVICE_UNAVAILABLE,

    /** The recogniser heard nothing before the end-of-speech timeout. */
    NO_SPEECH,

    /** Speech was heard but no hypothesis came back. */
    NO_MATCH,

    /** The driver said nothing, or the confirmation went unanswered. */
    TIMEOUT,

    /** Speech was heard and transcribed, but it is not one of our commands. */
    NOT_UNDERSTOOD,

    /** The executor rejected or failed the command after it was accepted. */
    COMMAND_FAILED,

    /** `RECORD_AUDIO` was never granted. Distinct from unavailable: this is
     *  fixable by the driver in Settings, and the prompt must say so. */
    PERMISSION_DENIED,

    /**
     * The recogniser is reachable but its server could not be reached.
     *
     * Kept apart from [SERVICE_UNAVAILABLE] because they lead to opposite
     * actions: a missing service is permanent on this unit and the only fix is
     * an offline model, while a network failure is transient and is what
     * justifies switching to the offline provider for *this* request.
     */
    NETWORK_ERROR,

    /**
     * The recogniser exists but does not have the required language.
     *
     * On this hardware it is the common shape of a vendor ROM shipping an
     * English-only recogniser: the service answers, binds, and reports that it
     * cannot do Vietnamese. Reported as its own state because the driver's fix
     * is "install the Vietnamese model", not "there is no voice here".
     */
    LANGUAGE_UNAVAILABLE,

    /**
     * Another app holds the microphone.
     *
     * Never a reason to fall back: the offline provider needs the same device,
     * so switching would fail the same way one step later while doubling the
     * time before the driver is told anything.
     */
    MIC_BUSY,

    /**
     * The driver abandoned the interaction before an outcome arrived.
     *
     * Distinct from [TIMEOUT], which is the launcher giving up on them.
     */
    CANCELLED,

    /**
     * A failure none of the above describes — a native crash, a malformed
     * result, a provider invariant broken.
     *
     * The catch-all exists so that [COMMAND_FAILED], which means "the executor
     * refused", is never overloaded to also mean "the recogniser broke".
     */
    INTERNAL_ERROR
}

/**
 * Everything the overlay needs to render one frame, and nothing else.
 *
 * [sinceElapsedMs] is a monotonic timestamp (`SystemClock.elapsedRealtime` on
 * device, a fake in tests), not wall-clock: the overlay uses it to show "đang
 * nghe…" for at most [VoiceRouter]'s timeout, and a wall clock that jumps when
 * the head unit syncs GPS time would cut that short or stretch it by hours.
 *
 * [lastCommandId] is null when there is genuinely no command yet — it is not
 * filled with a synthetic id, because the diagnostics screen uses it to match a
 * spoken result to a log line and a fabricated id would match nothing.
 * [error] is likewise null for every non-[VoiceState.ERROR] state; the type is
 * nullable so "no answer" is representable instead of defaulting to
 * [VoiceError.NO_MATCH], which would read as a real recogniser verdict.
 *
 * **[transcript] is what the driver actually said.** It is carried here rather
 * than kept only in a log line because the overlay has to quote it back: once
 * the confirmation prompt is gone, "Bạn nói: …" is the *only* thing that tells
 * the driver the microphone heard the right words, and it is what makes a
 * misrecognition visible before the car acts on it. It is null before any
 * transcript has arrived, and it is deliberately **not** persisted — a reboot
 * must not resurrect a sentence nobody just said. See
 * [VoiceStateStore.restore].
 */
data class VoiceStateSnapshot(
    val state: VoiceState,
    /** Monotonic milliseconds at which this snapshot's [state] was entered. */
    val sinceElapsedMs: Long,
    /** Identifier of the command this state is about, or null before any parse. */
    val lastCommandId: String?,
    /** Why the last attempt failed; null unless [state] is [VoiceState.ERROR]. */
    val error: VoiceError?,
    /** The final transcript this state is about, or null before any speech. */
    val transcript: String? = null
) {
    /** True while the overlay must stay on screen. */
    val overlayVisible: Boolean get() = state != VoiceState.IDLE

    /**
     * True when Back means "cancel this voice interaction" rather than "leave
     * the screen". Deliberately includes the in-flight states as well as
     * [VoiceState.CONFIRMING]: the driver pressing Back while the recogniser is
     * still listening expects the microphone to stop.
     */
    val backCancels: Boolean get() = when (state) {
        VoiceState.LISTENING, VoiceState.PROCESSING, VoiceState.CONFIRMING, VoiceState.EXECUTING -> true
        VoiceState.IDLE, VoiceState.SUCCESS, VoiceState.ERROR -> false
    }

    companion object {
        /** The honest empty snapshot: idle, no command, no error. */
        fun idle(atElapsedMs: Long): VoiceStateSnapshot =
            VoiceStateSnapshot(VoiceState.IDLE, atElapsedMs, null, null)
    }
}

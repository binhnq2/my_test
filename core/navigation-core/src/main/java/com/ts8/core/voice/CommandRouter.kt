package com.ts8.core.voice

/**
 * The one seam between the voice decision logic and the app module.
 *
 * **Why an executor interface rather than direct calls to navigation, music and
 * the launcher.** This half is testable on a JVM exactly because it never
 * touches a screen, a media session or a volume stream: it is given a command
 * object and returns an outcome. The app module implements this against
 * `NavigationController`, `MusicPlayer` and `AudioManager`; [VoiceScenarios]
 * implements it with a recording fake. If the router called those directly,
 * every one of the rules in this package would need Robolectric to test, and
 * the failure modes that matter (a command running twice, a confirmation
 * leaking into the next session) would only be observable on the head unit.
 */
fun interface CommandExecutor {
    /**
     * Carries out [command].
     *
     * Implementations must be fast and must never throw: a thrown exception is
     * caught by [VoiceRouter] and reported as [CommandOutcome.Failed], but by
     * then the caller has already lost the chance to say *why*. Returning
     * [CommandOutcome.NeedsConfirmation] is how an executor asks for a prompt
     * the command object itself did not anticipate (for example a destination
     * that resolved to two places with the same name).
     */
    fun execute(command: VoiceCommand): CommandOutcome
}

/**
 * What came of executing a command.
 *
 * [Done.spoken] is the sentence the driver hears on success — a Vietnamese
 * confirmation of what actually happened ("Đang dẫn đường tới Đà Nẵng"), not a
 * generic "OK". Saying which action ran is the only feedback a driver gets
 * without looking away from the road, and a wrong one is how the driver learns
 * the microphone misheard them.
 *
 * [Failed.reason] is likewise driver-facing Vietnamese; the executor knows
 * whether it was "chưa có bài nào" or "không tìm thấy địa điểm" and the router
 * cannot tell those apart.
 */
sealed class CommandOutcome {

    /** The command ran. [spoken] is what the TTS layer must say. */
    data class Done(val spoken: String) : CommandOutcome()

    /**
     * The command did not run and must not run until the driver answers.
     * [prompt] is the question, already in Vietnamese; [command] is the exact
     * command to execute on "yes" — the executor may substitute a narrowed one
     * (one of two "Bến Thành" places), and the router must honour that, not the
     * original.
     */
    data class NeedsConfirmation(val prompt: String, val command: VoiceCommand) : CommandOutcome()

    /** The command ran but failed, or was refused. [reason] is driver-facing. */
    data class Failed(val reason: String) : CommandOutcome()
}

/**
 * Drives Voice Instant's state machine from a transcript to a spoken answer.
 *
 * **What this class is not.** It has no microphone, no `SpeechRecognizer`, no
 * `TextToSpeech` and no `Handler`. Text goes in through [onTranscript] and
 * Vietnamese strings come out through [Listener.onSpeak]; the app module owns
 * both platform ends. That split is the whole point: the rules that are easy to
 * get wrong — asking before destroying a route, running a command exactly once,
 * a confirmation that outlives its session — are all here, and all of them run
 * from a plain unit test with a fake clock.
 *
 * **The state diagram** (see [VoiceState] for why CONFIRMING is a state):
 *
 * ```
 *   IDLE ──transcript──> PROCESSING ──parse ok─────> EXECUTING ──Done──> SUCCESS
 *                              │                        ├────Failed───> ERROR
 *                              ├──executor asks again──> CONFIRMING
 *                              └──unrecognised─────────> ERROR
 *
 *   CONFIRMING ──yes──> EXECUTING        CONFIRMING ──no──> IDLE
 *   any in-flight state ──timeout──> IDLE
 *   any state ──cancel()──> IDLE
 * ```
 *
 * **Why a destructive command no longer stops at CONFIRMING.** It used to: the
 * router asked yes/no before "dẫn đường tới …" and before "phát nhạc", which
 * turned one utterance into two interactions and asked the driver to tap a
 * button while driving. The prompt is gone ([Config.requireConfirmation]
 * defaults to false); what replaced it is the transcript itself — the overlay
 * shows the sentence the recogniser produced, so a misheard command is visible
 * for the fraction of a second the action takes, and the action is undone by
 * saying it again. CONFIRMING survives only for the case the router genuinely
 * cannot choose, which is an executor returning
 * [CommandOutcome.NeedsConfirmation] (two places with the same name).
 *
 * **Timeout.** There is no timer thread here by design. The caller posts its own
 * delayed message (a `Handler` in the app, nothing in a test) and calls
 * [onTimeout] when [remainingMs] says it has expired; the router owns the
 * deadline so a backgrounded overlay cannot silently outlive its prompt.
 *
 * All public methods are synchronized: the recogniser callback, the TTS
 * callback and the overlay's Back key arrive on different threads, and a
 * transcript landing in the middle of a confirmation is the race that would
 * otherwise execute a destructive command twice.
 */
class VoiceRouter(
    private val executor: CommandExecutor,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val config: Config = Config()
) {

    /**
     * Runtime override for [Config.requireConfirmation].
     *
     * The router is built once per process, but `voice_auto_exec_v1` can be
     * flipped between two presses of the mic button. A constructor-only value
     * would keep asking (or keep skipping) until the app restarted — a switch
     * that appears to work and does not. Set from
     * `VoiceInstantController.start()`; defaults to the configured value so
     * every other construction site is unchanged.
     */
    @Volatile
    var requireConfirmation: Boolean = config.requireConfirmation

    data class Config(
        /**
         * How long a prompt may stay unanswered before the interaction is
         * abandoned. Eight seconds: long enough for a driver to say "đồng ý"
         * after the prompt finishes speaking, short enough that the overlay is
         * gone before a junction the driver needs to see.
         */
        val timeoutMs: Long = 8_000L,
        /**
         * False lets a [VoiceCommand] whose `needsConfirmation` is true run
         * without asking.
         *
         * **False is the shipped default.** A yes/no bar after every "dẫn đường
         * tới …" and every "phát nhạc" makes the feature two interactions where
         * the driver asked for one, and the answer it wants — a tap — is
         * exactly the thing a driver at the wheel should not have to give. The
         * replacement is not a silent action: the overlay quotes the transcript
         * back ("Bạn nói: …") and names the outcome, so a misrecognition is
         * still visible one line before the car commits to it.
         *
         * `needsConfirmation` on the command table and [VoiceState.CONFIRMING]
         * are kept for the case the router cannot decide alone — an executor
         * returning [CommandOutcome.NeedsConfirmation] because a destination
         * matched two places. That is a *disambiguation*, not a safety gate,
         * and it is the only path that turns this back on.
         */
        val requireConfirmation: Boolean = false
    )

    /** Platform ends of the interaction; implemented by the app module. */
    interface Listener {
        /** Every state change, on the calling thread. */
        fun onStateChanged(snapshot: VoiceStateSnapshot)

        /**
         * A sentence the TTS layer must speak. Emitted for prompts, successes,
         * failures, cancellation and timeout — never for a state change alone,
         * so the overlay and the voice cannot drift apart.
         */
        fun onSpeak(text: String)
    }

    @Volatile
    private var state: VoiceState = VoiceState.IDLE

    @Volatile
    private var sinceElapsedMs: Long = clock()

    @Volatile
    private var lastCommandId: String? = null

    @Volatile
    private var error: VoiceError? = null

    /**
     * The last final transcript this interaction is about.
     *
     * Kept only in memory. The overlay quotes it back so the driver can see the
     * microphone heard the right words — with the confirmation prompt gone,
     * that line is the only affordance that makes a misrecognition visible
     * before the car acts on it. It is cleared when a new interaction opens and
     * is never persisted: a sentence from a previous ignition cycle quoted as
     * "Bạn nói" would be a fabricated record of speech that did not happen.
     */
    @Volatile
    private var transcript: String? = null

    /** The last failure, retained past the IDLE that a timeout returns to. */
    @Volatile
    var lastError: VoiceError? = null
        private set

    /** The command waiting for a yes/no, or null in every other state. */
    @Volatile
    private var pending: VoiceCommand? = null

    /** Absolute deadline (monotonic ms) for the current in-flight state, or 0. */
    @Volatile
    private var deadlineElapsedMs: Long = 0L

    @Volatile
    private var listener: Listener? = null

    /**
     * Installs the platform ends. Null detaches.
     *
     * The listener is held weakly-by-convention rather than strongly: the app
     * module must clear it before its Activity dies, because this router can
     * outlive the overlay (the store keeps a SUCCESS on screen) and a stale
     * listener would speak into a destroyed `TextToSpeech`.
     */
    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /** The current state, without allocating a snapshot. */
    fun state(): VoiceState = state

    /**
     * An immutable copy of everything the overlay renders.
     *
     * A copy rather than a reference: the router mutates its fields under the
     * lock, and an overlay holding the live object would render a state and a
     * timestamp from two different moments.
     */
    fun snapshot(): VoiceStateSnapshot =
        VoiceStateSnapshot(state, sinceElapsedMs, lastCommandId, error, transcript)

    /** The command awaiting confirmation, for the overlay's "đồng ý / huỷ". */
    fun pendingCommand(): VoiceCommand? = pending

    /** Milliseconds left before [onTimeout] applies; 0 when nothing is pending. */
    fun remainingMs(): Long {
        val deadline = deadlineElapsedMs
        if (deadline == 0L) return 0L
        return (deadline - clock()).coerceAtLeast(0L)
    }

    // -- entry points the app module calls -----------------------------------

    /**
     * The microphone opened. Moves IDLE (or a finished state) to [VoiceState.LISTENING].
     *
     * Refused while a confirmation is pending: the driver is answering a
     * question, and opening the microphone would make their "đồng ý" the next
     * transcript instead of the answer.
     */
    @Synchronized
    fun onListeningStarted(speakPrompt: Boolean = true) {
        if (state == VoiceState.CONFIRMING || state == VoiceState.EXECUTING) return
        error = null
        lastCommandId = null
        pending = null
        // A new interaction must not quote the previous one's sentence.
        transcript = null
        enter(VoiceState.LISTENING)
        // Speak the listening prompt so the driver hears "Xin mời nói" and
        // knows the microphone is open — paired with the beep from
        // VoiceFeedback, this is the same hands-free cue Google Maps voice
        // uses. Gated by the voiceListeningPromptEnabled setting so a driver
        // who finds it repetitive can turn it off in Settings.
        if (speakPrompt) {
            speak(VietnameseVoicePrompts.PROMPT_LISTENING)
        }
    }

    /**
     * A final transcript from the recogniser.
     *
     * A transcript that arrives while a confirmation is pending is *not* an
     * answer — "đồng ý" is matched by [confirm], and the recogniser's own
     * hypothesis for it is unreliable enough that treating it as an answer
     * would both mis-fire and leave the prompt open. The transcript is dropped.
     */
    @Synchronized
    fun onTranscript(text: String?) {
        when (state) {
            VoiceState.CONFIRMING, VoiceState.EXECUTING -> return
            else -> Unit
        }

        // Recorded before the parse, not after: an unrecognised sentence still
        // has to be quoted back, and `VietnameseVoicePrompts.notUnderstood`
        // already folds null and blank to "Không nghe rõ".
        transcript = text?.trim()?.takeIf { it.isNotEmpty() }

        enter(VoiceState.PROCESSING)
        val parsed = IntentParser.parse(text)

        when (parsed) {
            is IntentParser.ParseResult.Command -> {
                lastCommandId = parsed.command.id
                val command = parsed.command
                if (command.needsConfirmation && requireConfirmation) {
                    pending = command
                    enter(VoiceState.CONFIRMING)
                    speak(VietnameseVoicePrompts.confirm(command))
                } else {
                    execute(command, alreadyConfirmed = false)
                }
            }
            is IntentParser.ParseResult.Ambiguous -> {
                // There is no command object here, so there is nothing to
                // confirm — the driver has to restate. The Error state carries
                // the failure so the overlay shows the question, not a spinner.
                lastCommandId = null
                fail(VoiceError.NOT_UNDERSTOOD, VietnameseVoicePrompts.ambiguous(parsed.candidates))
            }
            is IntentParser.ParseResult.Unrecognized -> {
                lastCommandId = null
                fail(
                    VoiceError.NOT_UNDERSTOOD,
                    VietnameseVoicePrompts.notUnderstood(parsed.text)
                )
            }
        }
    }

    /**
     * The answer to a confirmation prompt.
     *
     * Only legal in [VoiceState.CONFIRMING]; a "yes" arriving after a timeout is
     * dropped rather than re-executing a command the driver has already been
     * told was abandoned. That is the same class of bug as a double tap on a
     * destructive button, and it is the reason the pending command is cleared
     * *before* the executor runs.
     */
    @Synchronized
    fun confirm(yes: Boolean) {
        if (state != VoiceState.CONFIRMING) return
        val command = pending ?: run {
            // A CONFIRMING state with no command is a programming error, not a
            // user error. Do not guess which command was meant.
            fail(VoiceError.COMMAND_FAILED, VietnameseVoicePrompts.failed())
            return
        }
        pending = null
        if (!yes) {
            abandon(VoiceError.TIMEOUT, VietnameseVoicePrompts.cancelled(), keepError = false)
            return
        }
        execute(command, alreadyConfirmed = true)
    }

    /**
     * The driver answered a confirmation prompt by voice.
     *
     * **Why this is a separate entry point and not [onTranscript].** A
     * transcript arriving in CONFIRMING is *not* an answer — [onTranscript]
     * still drops it, and that rule is what keeps an ordinary sentence from
     * executing a command the prompt is holding. This method is called by the
     * controller after it has deliberately re-opened the microphone *for* the
     * prompt, so the two paths differ in intent rather than in text: one asks
     * "what should I do", the other "did you say yes".
     *
     * The match is [VoiceAnswer]'s — whole-utterance, negatives first — so
     * "không đồng ý" is a no rather than the yes its prefix would suggest.
     *
     * @return the answer that was applied, or null when [transcript] is not one
     *         of the two. Null leaves the prompt exactly as it was, which is the
     *         safe outcome: the driver still has the two buttons and the timer.
     */
    @Synchronized
    fun answer(transcript: String?): VoiceAnswer? {
        if (state != VoiceState.CONFIRMING) return null
        val parsed = VoiceAnswer.parse(transcript) ?: return null
        confirm(parsed == VoiceAnswer.YES)
        return parsed
    }

    /**
     * Ends a prompt with [reason], even though a prompt is normally protected.
     *
     * **The one caller is a microphone that can never answer.** [onRecognitionError]
     * refuses to act in CONFIRMING because a late error from a session that has
     * already finished must not replace the driver's question with a failure.
     * A denied permission is the opposite case: it is *why* the prompt can no
     * longer be answered, by tap or by voice, and leaving it up would strand
     * the driver on a question with no working answer.
     *
     * Deliberately narrow rather than a flag on [onRecognitionError]: every
     * other path that reaches a prompt with an error still has the buttons.
     */
    @Synchronized
    fun abortWith(reason: VoiceError) {
        pending = null
        fail(reason, VietnameseVoicePrompts.forError(reason))
    }

    /**
     * Restarts the deadline without leaving the current state.
     *
     * Called by the controller when it opens the microphone *for* the
     * confirmation prompt: the eight-second budget was sized for a tap, and
     * spending the first seconds of it on the spoken question would leave a
     * driver who answers normally with almost nothing. The state and its entry
     * timestamp are untouched — this is the same interaction, given its full
     * window back.
     *
     * A no-op in IDLE, where there is nothing to time out.
     */
    @Synchronized
    fun rearm() {
        if (state == VoiceState.IDLE) return
        deadlineElapsedMs = clock() + config.timeoutMs
    }

    /**
     * Back / the overlay's close button: abandon whatever is in flight.
     *
     * Safe in every state, including IDLE (no-op), so the overlay can wire Back
     * unconditionally rather than reconstructing which states are cancellable.
     */
    @Synchronized
    fun cancel() {
        if (state == VoiceState.IDLE) return
        pending = null
        abandon(VoiceError.TIMEOUT, VietnameseVoicePrompts.cancelled(), keepError = false)
    }

    /**
     * The platform reported a recogniser failure.
     *
     * [VoiceError.TIMEOUT] here is the recogniser's own end-of-speech silence,
     * which is mapped to NO_SPEECH by the caller; the router's own deadline is
     * separate and goes through [onTimeout].
     */
    @Synchronized
    fun onRecognitionError(reason: VoiceError) {
        if (state == VoiceState.CONFIRMING || state == VoiceState.EXECUTING) return

        // A recogniser cannot tell "no service" from "no match" reliably on
        // API 23; the app module classifies and passes the error in. Here it is
        // only turned into the right prompt and state.
        pending = null
        fail(reason, VietnameseVoicePrompts.forError(reason))
    }

    /**
     * The caller's timer fired.
     *
     * The meaning depends on where it fires:
     *
     * - **CONFIRMING / LISTENING / PROCESSING / EXECUTING** — the interaction
     *   was abandoned, so the machine returns to IDLE and speaks
     *   "Hết thời gian chờ". [lastError] records TIMEOUT for the log;
     *   [snapshot] carries no error for an IDLE state.
     * - **SUCCESS / ERROR** — the driver has already been told what happened;
     *   the timer here is the overlay's auto-dismiss. It closes silently,
     *   because announcing a timeout after "Đã thực hiện: mở bản đồ" would tell
     *   the driver their command failed when it did not.
     *
     * Also refuses to fire early: if [remainingMs] is still positive the timer
     * was posted against a previous, longer interaction and is ignored.
     */
    @Synchronized
    fun onTimeout() {
        if (deadlineElapsedMs == 0L) return
        if (clock() < deadlineElapsedMs) return
        val settling = state == VoiceState.SUCCESS || state == VoiceState.ERROR
        pending = null
        if (!settling) {
            lastError = VoiceError.TIMEOUT
            speak(VietnameseVoicePrompts.timeout())
        }
        enter(VoiceState.IDLE)
    }

    // -- internals -----------------------------------------------------------

    /**
     * Runs [command] exactly once.
     *
     * `alreadyConfirmed` exists to break the loop where an executor returns
     * [CommandOutcome.NeedsConfirmation] for a command the driver has just
     * confirmed: asking again would let a broken executor trap the driver in an
     * endless prompt, so the second ask is reported as a failure.
     */
    private fun execute(command: VoiceCommand, alreadyConfirmed: Boolean) {
        enter(VoiceState.EXECUTING)
        val outcome = try {
            executor.execute(command)
        } catch (e: Exception) {
            // A throwing executor is a bug, but the driver must still see an
            // answer; an unhandled exception here would leave EXECUTING on
            // screen with no timer left to end it.
            CommandOutcome.Failed(VietnameseVoicePrompts.failed())
        }

        when (outcome) {
            is CommandOutcome.Done -> {
                lastError = null
                enter(VoiceState.SUCCESS)
                speak(outcome.spoken)
            }
            is CommandOutcome.NeedsConfirmation -> {
                // Only a *second* ask is refused. The router-level destructive
                // gate is off by default, but an executor that returns this is
                // asking something different — "two places are called Bến
                // Thành, which one" — and there is no safe default for that, so
                // it still asks. Asking again about an answer the driver just
                // gave would trap them, which is what `alreadyConfirmed` stops.
                if (alreadyConfirmed) {
                    fail(VoiceError.COMMAND_FAILED, VietnameseVoicePrompts.failed())
                    return
                }
                // The executor narrowed the command; the confirmation is about
                // the one it returned, not the one we sent.
                pending = outcome.command
                lastCommandId = outcome.command.id
                enter(VoiceState.CONFIRMING)
                speak(outcome.prompt)
            }
            is CommandOutcome.Failed -> {
                fail(VoiceError.COMMAND_FAILED, outcome.reason)
            }
        }
    }

    /** Moves to IDLE, optionally speaking, and clears the deadline. */
    private fun abandon(reason: VoiceError, spoken: String, keepError: Boolean) {
        if (keepError) lastError = reason
        pending = null
        speak(spoken)
        enter(VoiceState.IDLE)
    }

    private fun fail(reason: VoiceError, spoken: String) {
        lastError = reason
        error = reason
        enter(VoiceState.ERROR)
        speak(spoken)
    }

    /**
     * Records a new state, stamps the monotonic clock and arms the deadline.
     *
     * SUCCESS and ERROR get a deadline too: the overlay must not stay up
     * forever if nobody dismisses it, and the caller drives that with the same
     * [onTimeout] it already posts.
     */
    private fun enter(next: VoiceState) {
        state = next
        sinceElapsedMs = clock()
        deadlineElapsedMs = if (next == VoiceState.IDLE) 0L else clock() + config.timeoutMs
        if (next != VoiceState.ERROR) error = null
        notifyState()
    }

    private fun notifyState() {
        listener?.onStateChanged(snapshot())
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        listener?.onSpeak(text)
    }
}

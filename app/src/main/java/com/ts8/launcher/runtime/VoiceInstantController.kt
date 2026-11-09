package com.ts8.launcher.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.ts8.core.voice.CommandExecutor
import com.ts8.core.voice.IntentParser
import com.ts8.core.voice.VoiceAnswer
import com.ts8.core.voice.VoiceCommand
import com.ts8.core.voice.VoiceError
import com.ts8.core.voice.VoiceRouter
import com.ts8.core.voice.VoiceState
import com.ts8.core.voice.VoiceStateSnapshot
import com.ts8.core.voice.VoiceStateStore
import com.ts8.core.voice.offline.RecognitionCallback
import com.ts8.core.voice.offline.RecognitionProviderRouter
import com.ts8.core.voice.offline.RecognitionRequest
import com.ts8.core.voice.offline.VoiceDiagnostics

/**
 * Voice Instant's process-level owner: microphone, router, speech in and out.
 *
 * **Why it lives here and not in the overlay Activity.** The overlay is created
 * and destroyed by whatever screen the driver is on; the recogniser session, the
 * ducked music and the pending confirmation must not end because a screen was
 * recreated. Keeping the controller in the application graph makes the overlay a
 * *renderer* of [snapshot] — the same rule the spec applies to the map holds
 * here too: no engine lives inside an Activity.
 *
 * **What this class is not.** It contains no command semantics. Which phrase
 * means what is [IntentParser]'s, when to confirm is [VoiceRouter]'s, and what a
 * command actually does is the app's [CommandExecutor]. This file is the wiring
 * the brief calls `VoiceInstantController`: permission check, session lifecycle,
 * the timeout timer, and the store hand-off.
 *
 * **One timer for the whole interaction.** [VoiceRouter] owns the deadline
 * ([VoiceRouter.remainingMs]) but cannot post anything; this class posts a
 * single delayed message and re-arms it on every state change. That is what
 * makes a `CONFIRMING` prompt close itself after eight seconds, and what makes
 * `SUCCESS`/`ERROR` auto-dismiss on the same budget instead of sitting there
 * until ignition off.
 *
 * **No background listening.** There is exactly one entry point that opens the
 * microphone ([start]) and every exit path funnels through [finishInteraction],
 * which cancels the recogniser. A process that dies mid-overlay is restored
 * **IDLE** by [VoiceStateStore], because the microphone died with it.
 */
class VoiceInstantController(
    context: Context,
    private val feedback: VoiceFeedback,
    private val executor: CommandExecutor,
    private val store: VoiceStateStore,
    /** `system` and `vosk`, chosen per request by [providerRouter]. */
    private val providers: RecognitionProviderRouter,
    /**
     * The offline vocabulary, rebuilt per request so a new map pack's place
     * names are recognised without restarting the process.
     */
    private val grammar: () -> String? = { null },
    /**
     * The vocabulary for answering a confirmation prompt (§9).
     *
     * Separate from [grammar] on purpose: the command grammar contains the
     * driver's whole request and is decoded while they are still speaking it;
     * this one contains only yes and no and is decoded *after* the question has
     * been spoken. Handing the command grammar to an answer session would let
     * "mở bản đồ" be decoded during a prompt that is waiting for a yes.
     */
    private val answerGrammar: () -> String? = { null },
    /**
     * Whether to play beep tones. Read from settings at session start so a
     * driver who toggles it in Settings gets the change on the next press.
     */
    private val beepEnabled: () -> Boolean = { true },
    /**
     * Whether to speak the listening prompt ("Xin mời nói") when mic opens.
     * Read from settings at session start.
     */
    private val listeningPromptEnabled: () -> Boolean = { true },
    /**
     * Whether a recognised command runs without the yes/no bar
     * (`voice_auto_exec_v1`). Read at every session start for the same reason
     * as [beepEnabled]: the switch must take effect on the next press, not on
     * the next cold start.
     */
    private val autoExecute: () -> Boolean = { true }
) {

    /**
     * What the overlay draws. Implemented by the screen.
     *
     * Both callbacks arrive on the main thread, because the router can be driven
     * from the recogniser's binder callback.
     */
    interface Listener {
        /** Every state change, so the overlay redraws exactly when it must. */
        fun onState(snapshot: VoiceStateSnapshot)

        /**
         * The sentence being spoken — the prompt, the confirmation question, the
         * result, or the failure reason.
         *
         * Carried separately from [VoiceStateSnapshot] because the reason is
         * Vietnamese prose ("Không hiểu lệnh: cà phê") assembled by the prompt
         * layer, while the snapshot only carries the closed [VoiceError] enum.
         * The overlay shows this line; a driver in a noisy cabin reads it when
         * they cannot hear it.
         */
        fun onCaption(text: String)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /**
     * The route for the current interaction, or null when the microphone is
     * closed.
     *
     * Held rather than recreated per callback so [cancel] reaches the provider
     * that actually holds the microphone — a route captured by a lambda would
     * be a second copy of the session, and the one thing this class must never
     * do is release a provider it is not looking at.
     */
    private var route: RecognitionProviderRouter.Session? = null

    /** The last partial forwarded to the overlay, so a repeat is dropped. */
    private var pendingPartial: String? = null

    /** Monotonic reading of the last forwarded partial, for the repaint gate. */
    private var lastPartialAtMs = 0L

    private val router = VoiceRouter(
        executor = CommandExecutor { command -> executor.execute(command) },
        clock = { SystemClock.elapsedRealtime() }
    )

    private var listener: Listener? = null

    /** True between [start] and the first terminal state of that interaction. */
    private var interactionActive = false

    /**
     * The last state handed to the overlay, so a transition *into* CONFIRMING
     * can be told apart from a redraw of the same state.
     */
    @Volatile
    private var publishedState: VoiceState = VoiceState.IDLE

    /**
     * How many times the microphone has been opened to hear a yes/no for the
     * prompt that is currently on screen.
     *
     * Bounded at [MAX_ANSWER_ATTEMPTS]. An unbounded retry would keep the
     * microphone open for as long as the driver says nothing, which is the
     * continuous listening this pilot forbids; the deadline still applies to
     * every attempt, so two tries fit inside one 8-second window.
     */
    private var answerAttempts = 0

    /** True when the microphone is open for an answer rather than a command. */
    @Volatile
    private var listeningForAnswer = false

    /** The pending router deadline message, so it can be replaced. */
    private val timeoutTick = Runnable { onTimeoutTick() }

    init {
        router.setListener(object : VoiceRouter.Listener {
            override fun onStateChanged(snapshot: VoiceStateSnapshot) = publish(snapshot)

            override fun onSpeak(text: String) {
                val target = listener
                if (target != null) main.post { runCatching { target.onCaption(text) } }
                main.post { feedback.speak(text) }
            }
        })
    }

    // -- observer ----------------------------------------------------------

    /** Attaches the overlay and immediately hands it the current state. */
    fun attach(listener: Listener) {
        this.listener = listener
        runCatching { listener.onState(router.snapshot()) }
    }

    /** Detaches without stopping an interaction that is still running. */
    fun detach() {
        listener = null
    }

    fun snapshot(): VoiceStateSnapshot = router.snapshot()

    /** The command awaiting confirmation, for the overlay's question line. */
    fun pendingCommand(): VoiceCommand? = router.pendingCommand()

    // -- start / stop ------------------------------------------------------

    /**
     * Opens the microphone and starts an interaction.
     *
     * Every precondition is checked here and reported through the router, so the
     * overlay shows the *same* surface as a failed recognition. The cases the
     * brief names are distinct and all reachable:
     *
     * - `PERMISSION_DENIED` — `RECORD_AUDIO` never granted; the overlay points
     *   the driver at Settings because it is fixable there.
     * - `SERVICE_UNAVAILABLE` — the unit has no recogniser, or the firmware
     *   disabled the one it has. The brief's exact sentence, `Voice service
     *   unavailable`, is what the overlay shows.
     * - a normal session.
     *
     * @return false only when an interaction is already running.
     */
    fun start(): Boolean {
        com.ts8.launcher.runtime.DiagnosticLogger.log("VoiceInstant: start() called interactionActive=$interactionActive")
        if (interactionActive) return false
        interactionActive = true
        listeningForAnswer = false
        answerAttempts = 0

        // Applied before any recognition can land: the transcript is routed
        // through this value, and the driver flipping the switch mid-drive must
        // not need to restart the app for it to hold.
        router.requireConfirmation = !autoExecute()

        // Duck and prepare before the microphone opens: the recogniser hears the
        // cabin through the same speakers, and a loud track is the single
        // largest cause of a wrong transcript on this unit.
        feedback.prepare()

        if (!hasRecordPermission()) {
            com.ts8.launcher.runtime.DiagnosticLogger.log("VoiceInstant: no RECORD_AUDIO permission -> PERMISSION_DENIED")
            router.onRecognitionError(VoiceError.PERMISSION_DENIED)
            persistAndArm()
            return true
        }

        feedback.begin()

        // Apply settings before the beep fires, so a driver who turned beep off
        // in Settings gets silence on the next press without a restart.
        feedback.beepEnabled = beepEnabled()
        feedback.beepStart()

        router.onListeningStarted(listeningPromptEnabled())

        // The vocabulary is built per request: it carries the active map pack's
        // place names, which change when the driver swaps packs.
        val request = RecognitionRequest(
            languageTag = SpeechRecognizerAdapter.DEFAULT_LANGUAGE,
            maxAlternatives = MAX_ALTERNATIVES,
            partialResults = true,
            grammar = runCatching { grammar() }.getOrNull()
        )
        com.ts8.launcher.runtime.DiagnosticLogger.log("VoiceInstant: creating session, grammar=${if (request.grammar != null) "present(${request.grammar!!.length} chars)" else "null"}")
        val opened = providers.newSession(request, callback).also { route = it }
        opened.begin()

        if (opened.active == null) {
            // Every provider refused and the router has already reported it
            // through [callback]; only the bookkeeping is left.
            com.ts8.launcher.runtime.DiagnosticLogger.log("VoiceInstant: no provider could open session (active=null)")
            Log.i(TAG, "no recognition provider could open a session")
            route = null
        } else {
            com.ts8.launcher.runtime.DiagnosticLogger.log("VoiceInstant: session opened, active=${opened.activeId}")
        }
        persistAndArm()
        return true
    }

    /**
     * One utterance's callbacks, from whichever provider the router chose.
     *
     * A single instance reused across interactions: it holds no per-utterance
     * state beyond [pendingPartial], and constructing a callback per press
     * would make the [route] it clears impossible to reason about.
     */
    private val callback = object : RecognitionCallback {

        override fun onReady() {
            // Already LISTENING; nothing to change. Logged because a unit that
            // takes seconds to open the microphone is worth knowing about.
            Log.i(TAG, "provider ready")
        }

        override fun onPartial(text: String) {
            // Overlay only — never parsed, never executed, never persisted. Both
            // the system adapter and Vosk throttle before this point, but the
            // guard is repeated here because the caption is drawn on the shared
            // overlay bar and a fast repaint is visible to the driver.
            val now = SystemClock.elapsedRealtime()
            if (now - lastPartialAtMs < PARTIAL_MIN_INTERVAL_MS) return
            if (text == pendingPartial) return
            lastPartialAtMs = now
            pendingPartial = text
            listener?.let { target -> main.post { runCatching { target.onCaption(text) } } }
        }

        override fun onFinal(hypotheses: List<String>, confidence: Float?) {
            val chosen = chooseHypothesis(hypotheses)
            val confidenceText = confidence?.toString() ?: "n/a"
            Log.i(
                TAG,
                "transcript: \"$chosen\" (of ${hypotheses.size}, confidence=$confidenceText)"
            )
            com.ts8.launcher.runtime.DiagnosticLogger.log("VoiceInstant: onFinal chosen=\"$chosen\" (${hypotheses.size} hypotheses, confidence=$confidenceText)")
            route = null
            // End beep confirms the driver's utterance was captured — the same
            // cue Google Maps voice uses when it stops listening.
            if (hypotheses.isNotEmpty() && chosen.isNotBlank()) {
                feedback.beepEnd()
            }
            if (listeningForAnswer) {
                listeningForAnswer = false
                deliverAnswer(hypotheses)
                return
            }
            router.onTranscript(chosen)
            persistAndArm()
        }

        override fun onError(error: VoiceError, detail: String?) {
            Log.w(TAG, "recognition failed: $error ($detail)")
            com.ts8.launcher.runtime.DiagnosticLogger.log("VoiceInstant: onError error=$error detail=$detail listeningForAnswer=$listeningForAnswer")
            route = null
            // Error beep tells the driver recognition failed without looking
            // at the screen — two short low tones.
            feedback.beepError()
            if (listeningForAnswer) {
                listeningForAnswer = false
                onAnswerError(error)
                return
            }
            router.onRecognitionError(error)
            persistAndArm()
        }
    }

    /**
     * Answers a confirmation prompt by tap.
     *
     * Only reachable for the one case that still asks — an executor returning
     * `CommandOutcome.NeedsConfirmation` because it cannot choose (two places
     * with the same name). The everyday destructive commands do not use this
     * path any more: they run straight off the transcript.
     *
     * The tap and the spoken answer share [applyAnswer] so the two cannot
     * diverge in what they tear down: both close the answer microphone, both
     * reset the attempt counter and both persist the resulting snapshot.
     */
    fun confirm(yes: Boolean) {
        applyAnswer { router.confirm(yes) }
    }

    /**
     * Back / the close button: abandon the interaction.
     *
     * Cancels the microphone first, so no transcript can land after the state
     * has moved to IDLE, then tells the router. [VoiceRouter.cancel] announces
     * the abandonment through [VoiceRouter.Listener.onSpeak], which is what
     * restores the music's level.
     */
    fun cancel() {
        cancelRoute()
        // The driver abandoned the interaction outright; no answer session may
        // be opened for a prompt that is about to be closed.
        listeningForAnswer = false
        answerAttempts = 0
        router.cancel()
        persistAndArm()
        finishIfIdle()
    }

    /**
     * The driver refused `RECORD_AUDIO`, or the firmware would not even ask.
     *
     * Separate from [start] because the platform answers asynchronously: the
     * trigger has already returned to the caller by the time the dialog is
     * dismissed, and there is no session to open. The overlay must still say
     * something, and it must be the fixable case rather than a generic failure,
     * so the error goes through the router exactly as a recogniser error does.
     */
    fun permissionRefused() {
        if (interactionActive) return
        interactionActive = true
        router.onRecognitionError(VoiceError.PERMISSION_DENIED)
        persistAndArm()
    }

    /** Called by the owning screen when it goes away, e.g. on `onPause`. */
    fun stop() {
        if (interactionActive) cancel()
    }

    /** Releases the recognisers and the TTS engine. Process teardown only. */
    fun release() {
        main.removeCallbacks(timeoutTick)
        cancelRoute()
        runCatching { providers.release() }
        feedback.release()
        router.setListener(null)
        listener = null
        interactionActive = false
    }

    /**
     * The last recognition session's measurements, for the diagnostics export.
     *
     * Null before the first interaction, and null from the system provider even
     * after one: it reports no model size, sample rate or confidence, and a
     * record filled with placeholders would read as measured-and-zero.
     */
    fun diagnostics(): VoiceDiagnostics? = runCatching { providers.lastDiagnostics() }.getOrNull()

    /**
     * The state the overlay should start with in a new process.
     *
     * [VoiceStateStore.restore] degrades every in-flight state to IDLE and
     * returns null when nothing was stored; null becomes the honest idle
     * snapshot rather than a crash.
     */
    fun restoredSnapshot(): VoiceStateSnapshot {
        val now = SystemClock.elapsedRealtime()
        return store.restore(now) ?: VoiceStateSnapshot.idle(now)
    }

    // -- internals ---------------------------------------------------------

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Picks the hypothesis the parser can act on.
     *
     * The recogniser's top result is not always the one Voice Instant can use:
     * "mở bản đồ" often arrives second behind "mở bạn đồ". Trying each in order
     * and taking the first that parses to a command turns a wrong ranking into a
     * correct action; if none parses, the **original first** hypothesis is
     * returned so the overlay quotes what was actually heard rather than the
     * last thing tried.
     */
    private fun chooseHypothesis(hypotheses: List<String>): String {
        if (hypotheses.size <= 1) return hypotheses.firstOrNull().orEmpty()
        for (candidate in hypotheses) {
            if (IntentParser.parse(candidate) is IntentParser.ParseResult.Command) return candidate
        }
        return hypotheses.first()
    }

    /**
     * Publishes a snapshot and tears down if it is the end of the interaction.
     *
     * The transition *into* CONFIRMING is also where the answer microphone
     * opens. It has to be here and not in the executor: any path that reaches a
     * prompt — today only a `NeedsConfirmation` — must be answerable by voice,
     * and a second call site would be the one a future executor forgot. It is
     * posted rather than called inline because this callback can run on a
     * binder thread and [maybeListenForAnswer] touches the main looper.
     */
    private fun publish(snapshot: VoiceStateSnapshot) {
        val previous = publishedState
        publishedState = snapshot.state

        listener?.let { target ->
            main.post { runCatching { target.onState(snapshot) } }
        }
        if (snapshot.state == VoiceState.CONFIRMING && previous != VoiceState.CONFIRMING) {
            answerAttempts = 0
            main.post { runCatching { maybeListenForAnswer() } }
        }
        if (snapshot.state == VoiceState.IDLE) finishInteraction()
    }

    /** Saves the snapshot and re-arms the deadline in one step. */
    private fun persistAndArm() {
        runCatching { store.save(router.snapshot()) }
        armTimeout()
    }

    /**
     * Tears down everything the interaction held.
     *
     * Reached from every path that lands on IDLE — success, failure, cancel and
     * the router's own timeout — so the microphone, the duck and the timer
     * cannot outlive the overlay because one path forgot.
     */
    private fun finishInteraction() {
        if (!interactionActive) return
        interactionActive = false
        main.removeCallbacks(timeoutTick)
        cancelRoute()
        listeningForAnswer = false
        answerAttempts = 0
        publishedState = VoiceState.IDLE
        pendingPartial = null
        feedback.end()
        runCatching { store.save(router.snapshot()) }
    }

    /**
     * Closes the route, and with it whichever microphone is open.
     *
     * [RecognitionProviderRouter.Session.cancel] is the one call the controller
     * relies on for "Back", "the timer expired" and "the screen went away"; it
     * delegates to the provider that actually holds the microphone, so a
     * provider that has already finished is untouched and a provider that is
     * capturing is stopped.
     */
    private fun cancelRoute() {
        val current = route ?: return
        route = null
        runCatching { current.cancel() }
    }

    // -- spoken confirmation (§9) ------------------------------------------

    /**
     * The driver answered: self-recognition is over, the router takes it.
     *
     * The overlay's two buttons stay — a driver who cannot or will not speak
     * still has to be able to answer — so this is an additional input, not a
     * replacement. When the utterance is not one of the two answers the prompt
     * is left exactly as it was (state, question and deadline), and the driver
     * is told what was heard so they can try again or tap.
     */
    private fun applyAnswer(apply: () -> Unit) {
        // Whatever the answer, the answer session is done and no later
        // transcript may be read as this prompt's reply.
        listeningForAnswer = false
        cancelRoute()
        answerAttempts = 0
        apply()
        persistAndArm()
        finishIfIdle()
    }

    /**
     * Picks the first hypothesis that *is* an answer, and applies it.
     *
     * The top result is not always usable: "đồng ý" often arrives second behind
     * "động đậy", and taking the top one would leave the driver staring at a
     * prompt they believe they answered. If none of the hypotheses is an
     * answer, the caption quotes the top one — the same rule the command path
     * follows, so the driver sees what was actually heard rather than the last
     * thing tried.
     */
    private fun deliverAnswer(hypotheses: List<String>) {
        for (candidate in hypotheses) {
            if (VoiceAnswer.parse(candidate) != null) {
                applyAnswer { router.answer(candidate) }
                return
            }
        }
        val heard = hypotheses.firstOrNull().orEmpty()
        Log.i(TAG, "answer not understood: \"$heard\"")
        val target = listener
        if (target != null) {
            val line = "Không nghe rõ câu trả lời: “$heard”. Nói “đồng ý” hoặc “huỷ”, hoặc chạm nút."
            main.post { runCatching { target.onCaption(line) } }
            main.post { runCatching { feedback.speak(line) } }
        }
        // The prompt stays open with its deadline untouched; one more attempt
        // is allowed, and the timer closes it either way.
        maybeListenForAnswer()
    }

    /**
     * The answer session failed.
     *
     * The prompt is *not* torn down: a microphone that was busy, a network
     * failure or a silence is not a decision by the driver, and replacing their
     * question with an error would lose the command they already gave. Only a
     * denial of the permission ends the prompt, because no further attempt can
     * succeed.
     */
    private fun onAnswerError(error: VoiceError) {
        when (error) {
            VoiceError.PERMISSION_DENIED -> {
                // The route refuses a recogniser error while a prompt is open,
                // and rightly so — a late failure from a *finished* session must
                // not replace the driver's question. This failure is not late:
                // it is the one that makes the prompt unanswerable. So the
                // prompt is closed explicitly, with the permission sentence the
                // driver can act on.
                answerAttempts = MAX_ANSWER_ATTEMPTS
                router.abortWith(VoiceError.PERMISSION_DENIED)
                persistAndArm()
                finishIfIdle()
            }
            // The driver pressed Back or the screen went away; [cancel] has
            // already run and there is nothing to protect.
            VoiceError.CANCELLED -> Unit
            else -> {
                Log.i(TAG, "answer attempt failed: $error; prompt kept open")
                maybeListenForAnswer()
            }
        }
    }

    /**
     * Opens the microphone for a yes/no, if the prompt has attempts left.
     *
     * Never called at the same time as a command session: it is reached only
     * from a transition into CONFIRMING and from a failed attempt, both of
     * which have already closed the previous route. That is also what keeps the
     * two providers from ever holding the microphone together.
     */
    private fun maybeListenForAnswer() {
        if (!interactionActive) return
        if (router.state() != VoiceState.CONFIRMING) return
        if (answerAttempts >= MAX_ANSWER_ATTEMPTS) return
        answerAttempts++
        listeningForAnswer = true
        router.rearm()
        armTimeout()

        val request = RecognitionRequest(
            languageTag = SpeechRecognizerAdapter.DEFAULT_LANGUAGE,
            maxAlternatives = ANSWER_MAX_ALTERNATIVES,
            partialResults = true,
            grammar = runCatching { answerGrammar() }.getOrNull(),
            maxUtteranceMs = ANSWER_MAX_UTTERANCE_MS
        )
        val session = providers.newSession(request, callback).also { route = it }
        session.begin()
        if (session.active == null) {
            // The answer providers refused outright; the tap buttons remain
            // and the prompt's own deadline closes it.
            route = null
            listeningForAnswer = false
        }
    }

    /** No-op unless the router is already home; keeps [cancel] ordering simple. */
    private fun finishIfIdle() {
        if (router.state() == VoiceState.IDLE) finishInteraction()
    }

    /**
     * Re-arms the router's deadline.
     *
     * One message, always replaced: re-arming on each state change is what makes
     * SUCCESS and ERROR auto-dismiss on the same budget a confirmation gets,
     * without a second timer anywhere.
     */
    private fun armTimeout() {
        main.removeCallbacks(timeoutTick)
        if (router.state() == VoiceState.IDLE) return
        val remaining = router.remainingMs()
        if (remaining <= 0L) {
            // Already past it (a slow main thread), but the router refuses to
            // fire early, so this is only legal once the deadline has passed.
            main.post(timeoutTick)
            return
        }
        main.postDelayed(timeoutTick, remaining + TIMEOUT_SLACK_MS)
    }

    private fun onTimeoutTick() {
        if (router.state() == VoiceState.IDLE) return
        router.onTimeout()
        val state = router.state()
        if (state == VoiceState.IDLE) {
            // `publish` already ran [finishInteraction] through the listener
            // path; persisting here covers a router built without a listener.
            runCatching { store.save(router.snapshot()) }
        } else {
            // The router ignored an early tick; re-arm against the real deadline.
            armTimeout()
        }
    }

    companion object {
        private const val TAG = "Ts8VoiceInstant"

        /** How many hypotheses to ask the engine for. */
        private const val MAX_ALTERNATIVES = 5

        /**
         * The overlay repaints at most four times a second on partials.
         *
         * Both providers already throttle; this is the second gate, on the side
         * that owns the caption, so a change in either provider cannot make the
         * bar stutter during a sentence.
         */
        private const val PARTIAL_MIN_INTERVAL_MS = 250L

        /**
         * The router measures with `elapsedRealtime` and its own `remainingMs`;
         * a `Handler` can fire a millisecond early on a busy looper, and the
         * router would then ignore that tick and never be re-armed. The slack
         * makes the post land after the deadline it is posted for.
         */
        private const val TIMEOUT_SLACK_MS = 80L

        /**
         * How many times the microphone may be opened for one prompt's answer.
         *
         * Two, because one mistimed press of the trigger (a driver who pressed
         * before the question finished speaking) would otherwise spend the
         * prompt's whole budget on a single silence. The count is reset on every
         * transition into CONFIRMING and on every answered prompt.
         */
        private const val MAX_ANSWER_ATTEMPTS = 2

        /**
         * How many hypotheses an answer session asks for.
         *
         * Fewer than a command session: the whole vocabulary is fifteen phrases,
         * so the alternatives beyond the third carry no information.
         */
        private const val ANSWER_MAX_ALTERNATIVES = 5

        /**
         * How long an answer utterance may run.
         *
         * Six seconds, not the command path's fifteen: the vocabulary is a word
         * or two, and a long capture after a question is a driver who did not
         * hear it. The silence timeout still ends it sooner.
         */
        private const val ANSWER_MAX_UTTERANCE_MS = 6_000L
    }
}

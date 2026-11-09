package com.ts8.core.voice.offline

import com.ts8.core.voice.VoiceError

/**
 * The one seam every speech-recognition backend sits behind.
 *
 * **Why this exists at all.** Before it, exactly one class in the app touched
 * `android.speech.SpeechRecognizer`, and everything above it assumed that class
 * was the only way to hear the driver. On the TS8 that assumption fails: the
 * vendor ROM ships no `RecognitionService`, so the feature reports "Voice
 * service unavailable" and there is nothing to fall back to. A second backend
 * (Vosk, reading a model off internal storage) needs to be interchangeable
 * with the first — not bolted on beside it — or the controller accumulates two
 * copies of the session lifecycle and the microphone release rules.
 *
 * **What a provider may not do.** It may not parse commands, hold a
 * `VoiceStateStore`, speak, or decide which provider runs next. It converts
 * audio into a transcript or a [VoiceError], and nothing else. The router owns
 * the choice, the controller owns the state machine, and the parser owns the
 * meaning — the same split the package already had.
 *
 * **Threading.** [start] and [cancel] are called on the main thread.
 * [RecognitionCallback] may be invoked from any thread; the router marshals.
 * The offline implementation does its inference on its own thread by
 * construction, because a 33 MB model on the main looper is an ANR, not a slow
 * frame.
 */
interface RecognitionProvider {

    /** Short id for diagnostics: `system`, `vosk`, or whatever replaces them. */
    val id: String

    /**
     * Whether this provider could be started right now, without side effects.
     *
     * A hint, never a guarantee: [start] may still fail, and the failure is
     * what the driver is told. Used by the router to answer "can the offline
     * backend take over" without trying it.
     */
    fun isUsable(): Boolean

    /**
     * Opens one recognition session.
     *
     * Implementations must deliver exactly one terminal callback
     * ([RecognitionCallback.onFinal] or [RecognitionCallback.onError]) per
     * successful start, and none after [cancel]. A provider that cannot start
     * reports through `onError` and returns false, so the router has one path
     * to handle rather than two.
     *
     * @return false when no session opened.
     */
    fun start(request: RecognitionRequest, callback: RecognitionCallback): Boolean

    /**
     * Abandons the session and releases the microphone.
     *
     * Must be safe to call in every state, including before [start] and twice
     * in a row. This is the single call the controller relies on for "the
     * driver pressed Back", "the timer expired" and "switch providers", so a
     * provider that only tolerates one of those leaks the microphone.
     */
    fun cancel()

    /** Frees native resources. Called on process teardown or low memory. */
    fun release()

    /**
     * A different pack is now active on disk.
     *
     * **Why the router must pass this on.** [release] frees everything and
     * assumes the provider may never be used again; this says the opposite —
     * free what is cached *because* the next request will open the new model.
     * An offline provider that only implemented [release] would keep decoding
     * with the previous pack until the process restarted, and an offline
     * provider that treated a load failure as permanent would keep reporting
     * the old pack's failure for the new one.
     *
     * Does nothing by default: a backend with no cached state has nothing to
     * invalidate.
     */
    fun onModelChanged() = Unit

    /**
     * The measurements of the last session, for the diagnostics export (§11).
     *
     * Null when the provider has nothing to say — the system recogniser reports
     * no model size, no sample rate and no confidence, and a record filled with
     * placeholders would read as "measured, and it was zero". Only the provider
     * knows which of these it actually observed, so it fills the record rather
     * than the controller guessing after the fact.
     */
    fun lastDiagnostics(): VoiceDiagnostics? = null
}

/**
 * What to recognise.
 *
 * [grammar] is the offline vocabulary: a JSON array of allowed phrases. Vosk
 * takes it directly; the system recogniser has no equivalent and ignores it.
 * Keeping it on the request rather than in the provider is what lets the
 * grammar be rebuilt when the active map pack changes (new place names)
 * without recreating the provider.
 */
data class RecognitionRequest(
    val languageTag: String,
    val maxAlternatives: Int = 5,
    val partialResults: Boolean = true,
    /** JSON array of phrases, or null for unconstrained recognition. */
    val grammar: String? = null,
    /** Silence that ends the utterance, in milliseconds. */
    val silenceTimeoutMs: Long = 1_500L,
    /** Hard cap on one utterance, in milliseconds. */
    val maxUtteranceMs: Long = 15_000L
)

/**
 * Provider → router. One terminal callback per session.
 *
 * [onPartial] is deliberately separate from [onFinal] and carries no promise of
 * ordering beyond "before the terminal callback": the overlay debounces it, and
 * nothing else may ever see it. A partial is a hypothesis about a sentence the
 * driver is still saying — "dẫn đường tới Đà" — and executing it would navigate
 * to a place that does not exist.
 */
interface RecognitionCallback {

    /** The microphone is open and the provider is capturing. */
    fun onReady()

    /** A live hypothesis. Overlay only; never parsed, never stored. */
    fun onPartial(text: String)

    /**
     * The utterance is complete.
     *
     * [hypotheses] is best-first and never empty; [confidence] is null when the
     * engine does not report one, which is the honest answer for both backends
     * here rather than a fabricated 1.0.
     */
    fun onFinal(hypotheses: List<String>, confidence: Float?)

    /** A closed failure. [detail] is for the log, never for the driver. */
    fun onError(error: VoiceError, detail: String?)
}

/**
 * Which backend the router is allowed to use.
 *
 * The three values exist because the same APK has to answer three different
 * questions on the same hardware: a test that wants the old behaviour
 * ([SYSTEM_ONLY]), a driver in a tunnel who wants their voice to keep working
 * ([AUTO]), and a fleet that has been told never to send audio anywhere
 * ([OFFLINE_ONLY]).
 */
enum class RecognitionPolicy {
    /** Never call the system recogniser. No network, whatever the cost. */
    OFFLINE_ONLY,

    /** Prefer the system recogniser; fall back to the offline model when it fails. */
    AUTO,

    /** The pre-pilot behaviour, kept for diagnostics and regression tests. */
    SYSTEM_ONLY;

    companion object {
        /** Parses a stored name, falling back to [AUTO] rather than throwing. */
        fun fromName(raw: String?): RecognitionPolicy =
            entries.firstOrNull { it.name == raw } ?: AUTO
    }
}

/**
 * Whether [this] failure is a reason to hand the request to the offline model.
 *
 * **This predicate is the whole fallback policy, in one place.** The brief
 * lists the four triggers and, more importantly, the five non-triggers, and
 * every one of those distinctions is a driver-visible difference:
 *
 * | Error | Fall back? | Why |
 * |---|---|---|
 * | `SERVICE_UNAVAILABLE` | yes | no recogniser, or it disconnected for good — the case the pilot exists for |
 * | `NETWORK_ERROR` | yes | transient, and the offline model answers the same request |
 * | `LANGUAGE_UNAVAILABLE` | yes | the unit has a recogniser but not Vietnamese |
 * | `MIC_BUSY` | **no** | the offline model needs the same microphone; switching fails identically |
 * | `PERMISSION_DENIED` | **no** | no grant means no audio for either backend |
 * | `CANCELLED` | **no** | the driver left; running their command anyway is worse than nothing |
 * | `TIMEOUT` | **no** | they said nothing, so there is nothing to re-recognise |
 * | `NO_MATCH` | **no** | the speech was heard and not decoded; a second engine on the same audio is a coin flip, and one healthy no-match must not cost two recognitions |
 * | `INTERNAL_ERROR` | **no** | unknown state; do not run a native model into it |
 * | `NO_SPEECH` / `NOT_UNDERSTOOD` / `COMMAND_FAILED` | **no** | not recogniser availability at all |
 *
 * The caller still has to check that a model is READY; this only answers the
 * half of the question that belongs to the error.
 */
fun VoiceError.allowsOfflineFallback(): Boolean = when (this) {
    VoiceError.SERVICE_UNAVAILABLE,
    VoiceError.NETWORK_ERROR,
    VoiceError.LANGUAGE_UNAVAILABLE -> true

    VoiceError.NO_SPEECH,
    VoiceError.NO_MATCH,
    VoiceError.TIMEOUT,
    VoiceError.NOT_UNDERSTOOD,
    VoiceError.COMMAND_FAILED,
    VoiceError.PERMISSION_DENIED,
    VoiceError.MIC_BUSY,
    VoiceError.CANCELLED,
    VoiceError.INTERNAL_ERROR -> false
}

package com.ts8.launcher.runtime

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.ts8.core.voice.VoiceError

/**
 * The one class in the app that touches `android.speech.SpeechRecognizer`.
 *
 * **Why an adapter and not a controller.** §10 asks for a replaceable adapter,
 * and the reason is concrete on this unit: the TS8's vendor firmware may ship
 * no recogniser at all, may ship one that only understands English, or may ship
 * a Google app that is disabled by the ROM. All three are invisible from the
 * calling code, which only wants "the driver said something" or "this failed
 * and here is why". Keeping every platform type behind this class is what lets
 * the rest of Voice Instant be written — and reasoned about — without knowing
 * which of those units it is running on.
 *
 * **Why it does not loop.** There is no continuous recognition here and no
 * restart-on-silence. The brief forbids recording in the background, and a
 * recogniser that restarts itself is exactly that: a microphone held open by a
 * screen the driver has left. One call in, at most one result out, then the
 * recogniser is destroyed.
 *
 * **Why `EXTRA_PREFER_OFFLINE` is set.** This is a head unit: the driver may be
 * in a tunnel or a car park with no data. The extra asks the platform for its
 * on-device model first. On API 23 the flag is advisory and a unit with no
 * offline Vietnamese model simply ignores it — which is why a recogniser that
 * then fails to reach the network is reported as `SERVICE_UNAVAILABLE` rather
 * than retried forever.
 *
 * **Threading.** `SpeechRecognizer` requires every method on the main thread,
 * and delivers its callbacks on it too. The adapter asserts this by construction
 * — it is only called from the main looper — and hops nothing.
 */
class SpeechRecognizerAdapter(
    private val context: Context,
    private val languageTag: String = DEFAULT_LANGUAGE
) {

    /** Final outcomes, all on the main thread. */
    interface Callback {
        /** The recogniser began capturing audio. */
        fun onReady()

        /** Audio is ending; the transcript is being decoded. */
        fun onProcessing()

        /**
         * A live, incomplete hypothesis. Overlay only.
         *
         * Never routed to the parser: "dẫn đường tới đà" is a prefix of a
         * destination, not a destination, and acting on it would navigate
         * somewhere the driver did not say. The overlay shows it so the driver
         * can see the microphone is working before the sentence ends.
         */
        fun onPartial(text: String)

        /** One or more hypotheses, best first. Never empty. */
        fun onResult(hypotheses: List<String>)

        /** A closed failure. Never null. */
        fun onError(error: VoiceError, detail: String?)
    }

    private var recognizer: SpeechRecognizer? = null
    private var callback: Callback? = null

    /**
     * True between [start] and the first terminal callback.
     *
     * The platform can deliver both `onError` and `onResults` for one session on
     * some firmware; without this flag the callback would see two outcomes for
     * one utterance and the router would run a command that had already been
     * reported as failed.
     */
    private var sessionActive = false

    /** Whether this unit can recognise at all, cached after the first probe. */
    @Volatile
    private var available: Boolean? = null

    /**
     * The language this adapter will ask for, as a BCP-47 tag.
     *
     * Vietnamese on this market. It is a constructor parameter rather than a
     * constant because a fleet in another country needs a different one, and a
     * recogniser asked for the wrong language returns confident nonsense —
     * which the parser then reports as "không hiểu lệnh", blaming the driver
     * for the unit's setup.
     */
    val language: String get() = languageTag

    /**
     * True when a recogniser exists and is not on the device's disabled list.
     *
     * `SpeechRecognizer.isRecognitionAvailable` answers "is there an activity
     * that handles the intent", not "will it work when asked"; a firmware with
     * the Google app disabled answers `true` and then fails every session with
     * `ERROR_CLIENT`. That is why this is only a hint and the real verdict is
     * whatever [start] reports — the brief's `Voice service unavailable` path is
     * driven by a failed session, not by this probe.
     */
    fun isAvailable(): Boolean {
        available?.let { return it }
        val probe = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }
            .getOrDefault(false)
        available = probe
        return probe
    }

    /**
     * Opens one recognition session.
     *
     * A second call while a session is running is refused rather than queued:
     * two overlapping recognisers on one microphone produce a session that
     * errors with `ERROR_RECOGNIZER_BUSY` and a transcript attributed to the
     * wrong utterance. The caller (the controller) is expected to cancel first.
     *
     * @return false when no session could be started and [Callback.onError] has
     *         already been invoked with the reason.
     */
    fun start(callback: Callback): Boolean {
        com.ts8.launcher.runtime.DiagnosticLogger.log("SpeechAdapter: start() called sessionActive=$sessionActive language=$languageTag")
        if (sessionActive) {
            // Reaching this is a controller bug — the router never starts twice
            // — so it is reported as an internal fault rather than a service
            // problem: `SERVICE_UNAVAILABLE` would trigger an offline fallback
            // for something the model cannot fix.
            callback.onError(VoiceError.INTERNAL_ERROR, "đang có phiên nhận dạng khác")
            return false
        }
        if (!isAvailable()) {
            com.ts8.launcher.runtime.DiagnosticLogger.log("SpeechAdapter: isAvailable=false -> SERVICE_UNAVAILABLE")
            callback.onError(
                VoiceError.SERVICE_UNAVAILABLE,
                "SpeechRecognizer.isRecognitionAvailable = false"
            )
            return false
        }

        destroyRecognizer()
        this.callback = callback

        com.ts8.launcher.runtime.DiagnosticLogger.log("SpeechAdapter: createSpeechRecognizer...")
        val created = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }
            .getOrNull()
        if (created == null) {
            com.ts8.launcher.runtime.DiagnosticLogger.log("SpeechAdapter: createSpeechRecognizer returned null")
            // A firmware that reports the service as available and then refuses
            // to construct it: the recogniser exists but is unusable.
            this.callback = null
            callback.onError(VoiceError.SERVICE_UNAVAILABLE, "createSpeechRecognizer = null")
            return false
        }
        created.setRecognitionListener(listener)
        recognizer = created

        val intent = runCatching { buildIntent() }.getOrElse { e ->
            com.ts8.launcher.runtime.DiagnosticLogger.log("SpeechAdapter: buildIntent failed: ${e.message}")
            destroyRecognizer()
            this.callback = null
            callback.onError(VoiceError.SERVICE_UNAVAILABLE, e.message)
            return false
        }

        sessionActive = true
        com.ts8.launcher.runtime.DiagnosticLogger.log("SpeechAdapter: startListening with language=$languageTag...")
        val started = runCatching { created.startListening(intent) }
        if (started.isFailure) {
            val err = started.exceptionOrNull()
            com.ts8.launcher.runtime.DiagnosticLogger.log("SpeechAdapter: startListening threw: ${err?.javaClass?.simpleName}: ${err?.message}")
            sessionActive = false
            destroyRecognizer()
            this.callback = null
            val detail = err?.let {
                "${it.javaClass.simpleName}: ${it.message}"
            }
            callback.onError(VoiceError.SERVICE_UNAVAILABLE, detail)
            return false
        }
        com.ts8.launcher.runtime.DiagnosticLogger.log("SpeechAdapter: startListening OK, session active")
        return true
    }

    /**
     * Ends the session if one is open.
     *
     * `stopListening` asks the recogniser to decode what it has; `cancel` throws
     * the audio away. Cancel is what Back wants — the driver is dismissing the
     * overlay, and a transcript arriving afterwards would execute a command they
     * just abandoned. Both are called because some Android 6 firmware ignores
     * `cancel` on a recogniser that never fired `onReady`.
     */
    fun cancel() {
        if (!sessionActive) {
            destroyRecognizer()
            return
        }
        sessionActive = false
        val r = recognizer
        runCatching { r?.cancel() }
        runCatching { r?.stopListening() }
        callback = null
        destroyRecognizer()
    }

    /**
     * Asks the recogniser to finish and decode.
     *
     * Used when the driver has stopped talking and the overlay's own timer is
     * about to expire: stopping early produces a transcript a second sooner
     * than letting the platform's end-of-speech silence do it.
     */
    fun finishListening() {
        if (!sessionActive) return
        val r = recognizer ?: return
        runCatching { r.stopListening() }
    }

    /** Releases the recogniser. Safe to call repeatedly. */
    fun release() {
        sessionActive = false
        callback = null
        destroyRecognizer()
    }

    // -- internals ----------------------------------------------------------

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            // Partial results are what let the overlay show "đang nghe…" with a
            // live hypothesis. They are *not* what gets executed: only the final
            // result reaches the router, because a partial hypothesis for
            // "dẫn đường tới Đà Nẵng" is "dẫn đường tới đà" — a different
            // destination.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Ask for a few hypotheses so the controller can prefer one that
            // parses. `MAX_RESULTS` is honoured by Google's recogniser and
            // ignored by others, which is why the controller does not depend on
            // more than one arriving.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_HYPOTHESES)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

    private fun destroyRecognizer() {
        val r = recognizer ?: return
        recognizer = null
        runCatching { r.destroy() }
    }

    /** Delivers one terminal outcome at most, then clears the session. */
    private fun terminate(block: (Callback) -> Unit) {
        if (!sessionActive) return
        sessionActive = false
        val target = callback
        callback = null
        destroyRecognizer()
        target?.let { block(it) }
    }

    private val listener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            if (sessionActive) callback?.onReady()
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (sessionActive) callback?.onProcessing()
        }

        override fun onError(error: Int) {
            val mapped = mapError(error)
            Log.i(TAG, "recognition error $error -> $mapped")
            com.ts8.launcher.runtime.DiagnosticLogger.log("SpeechAdapter: onError code=$error mapped=$mapped")
            terminate { it.onError(mapped, "SpeechRecognizer error $error") }
        }

        override fun onResults(results: Bundle?) {
            val hypotheses = hypothesesOf(results)
            if (hypotheses.isEmpty()) {
                // A session that ends with no hypothesis is a no-match, not a
                // no-speech: the platform heard something and could not decode
                // it. Reporting NO_SPEECH here would tell the driver to speak up
                // when they already had.
                terminate { it.onError(VoiceError.NO_MATCH, "kết quả rỗng") }
                return
            }
            terminate { it.onResult(hypotheses) }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!sessionActive) return
            // The platform delivers a list here too; the first is the live best
            // hypothesis, and the overlay only shows one line.
            val text = hypothesesOf(partialResults).firstOrNull() ?: return
            callback?.onPartial(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun hypothesesOf(results: Bundle?): List<String> {
        val raw = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?: return emptyList()
        return raw.mapNotNull { it?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_HYPOTHESES)
    }

    /**
     * Maps a platform error code onto the closed `VoiceError` set.
     *
     * **The distinctions the offline pilot depends on.** Before the pilot this
     * table collapsed five different failures into `SERVICE_UNAVAILABLE`, which
     * was harmless while there was nothing to fall back to and is actively
     * wrong now: the router decides whether to switch providers from the error,
     * so a misclassified one either sends the driver's audio to the network
     * after they were told a service is missing, or leaves them with nothing
     * when the offline model could have answered.
     *
     * | Platform code | `VoiceError` | Why |
     * |---|---|---|
     * | `ERROR_SPEECH_TIMEOUT` | `NO_SPEECH` | the genuine "you said nothing" |
     * | `ERROR_NO_MATCH` | `NO_MATCH` | heard, not decoded; no fallback |
     * | `ERROR_NETWORK`, `ERROR_NETWORK_TIMEOUT` | `NETWORK_ERROR` | reachable but unreachable *server*; the one case a retry through the offline model fixes |
     * | `ERROR_AUDIO` | `MIC_BUSY` | the microphone is held elsewhere; the offline provider needs the same device |
     * | `ERROR_INSUFFICIENT_PERMISSIONS` | `PERMISSION_DENIED` | fixable in Settings |
     * | 12/13 (`ERROR_LANGUAGE_NOT_SUPPORTED`/`_UNAVAILABLE`) | `LANGUAGE_UNAVAILABLE` | a service exists and cannot do Vietnamese |
     * | `ERROR_CLIENT`, `ERROR_SERVER*`, `ERROR_RECOGNIZER_BUSY` | `SERVICE_UNAVAILABLE` | no bound recogniser, or it disconnected |
     * | anything else | `INTERNAL_ERROR` | not `COMMAND_FAILED`: the executor did not run |
     *
     * `ERROR_RECOGNIZER_BUSY` stays `SERVICE_UNAVAILABLE` rather than
     * `MIC_BUSY` because it means *our own* recogniser is already holding a
     * session — a controller bug the overlay should surface as "voice is not
     * working", not something another app caused.
     *
     * Codes 12 and 13 are written as literals because the `SpeechRecognizer`
     * constants that name them arrived after API 23, and this module compiles
     * against that `android.jar`.
     */
    private fun mapError(error: Int): VoiceError = when (error) {
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceError.NO_SPEECH

        SpeechRecognizer.ERROR_NO_MATCH -> VoiceError.NO_MATCH

        SpeechRecognizer.ERROR_CLIENT -> VoiceError.SERVICE_UNAVAILABLE
        SpeechRecognizer.ERROR_SERVER -> VoiceError.SERVICE_UNAVAILABLE
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> VoiceError.SERVICE_UNAVAILABLE
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceError.SERVICE_UNAVAILABLE

        // The service answered and could not reach its backend. Transient, and
        // the only failure the offline model is guaranteed to handle better.
        SpeechRecognizer.ERROR_NETWORK -> VoiceError.NETWORK_ERROR
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> VoiceError.NETWORK_ERROR

        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceError.PERMISSION_DENIED

        // The audio input could not be opened: another app holds it, or the
        // firmware's audio HAL refused. Never a reason to switch — the offline
        // provider would request the same microphone and fail the same way.
        SpeechRecognizer.ERROR_AUDIO -> VoiceError.MIC_BUSY

        // ERROR_LANGUAGE_NOT_SUPPORTED (12) / ERROR_LANGUAGE_UNAVAILABLE (13).
        // A unit with no Vietnamese model resumes working once the driver
        // installs the offline model, so this must not read as "no service".
        12, 13 -> VoiceError.LANGUAGE_UNAVAILABLE

        else -> VoiceError.INTERNAL_ERROR
    }

    companion object {
        private const val TAG = "Ts8SpeechAdapter"

        /** Vietnamese; this unit's market and the only language its parser knows. */
        const val DEFAULT_LANGUAGE = "vi-VN"

        /** Enough alternatives to recover from one misheard word, and no more. */
        private const val MAX_HYPOTHESES = 5
    }
}

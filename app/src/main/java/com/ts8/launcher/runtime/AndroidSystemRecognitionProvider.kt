package com.ts8.launcher.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ts8.core.voice.VoiceError
import com.ts8.core.voice.offline.RecognitionCallback
import com.ts8.core.voice.offline.RecognitionProvider
import com.ts8.core.voice.offline.RecognitionRequest

/**
 * The platform recogniser (`android.speech.SpeechRecognizer`) behind the
 * [RecognitionProvider] seam.
 *
 * **Why a thin wrapper rather than rewriting the adapter.** The adapter already
 * owns the hard part — the platform's error table, the single-terminal rule, the
 * `destroy()` protocol on API 23 — and every line of it is battle-tested on this
 * unit family. This class adds exactly three things the router needs and the
 * adapter does not know about: an [id], an [isUsable] probe that costs nothing,
 * and the conversion from the adapter's `List<String>` result to the router's
 * `(hypotheses, confidence)` pair.
 *
 * **Why the confidence is null.** `RecognizerIntent.EXTRA_CONFIDENCE_SCORES` is
 * an optional extra: Google's recogniser fills it, most vendor recognisers on
 * Android 6 do not, and a missing array is indistinguishable from a zero score.
 * The diagnostics field is nullable precisely so this can say "not reported"
 * instead of inventing a 1.0 — see [com.ts8.core.voice.offline.VoiceDiagnostics].
 *
 * **Threading.** `SpeechRecognizer` requires every call on the main thread. The
 * controller already calls [start] and [cancel] there, but the hop below makes
 * that a property of this class rather than a contract the caller can silently
 * break: a recogniser created on a worker thread on Android 6 binds to no
 * looper and never delivers a callback, which reads as "the microphone is dead".
 */
class AndroidSystemRecognitionProvider(
    context: Context,
    private val adapterFactory: (Context) -> SpeechRecognizerAdapter = { SpeechRecognizerAdapter(it) }
) : RecognitionProvider {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val adapter = adapterFactory(appContext)

    override val id: String = ID

    /** The language this unit will ask for, for the diagnostics line. */
    val language: String get() = adapter.language

    /**
     * True when `SpeechRecognizer.isRecognitionAvailable` says a service exists.
     *
     * A hint, not a promise: a firmware with the Google app disabled answers
     * true and then fails every session with `ERROR_CLIENT`. The router uses it
     * only to avoid spending a full session on a unit that has no recogniser at
     * all; the real verdict is whatever [start] reports.
     */
    override fun isUsable(): Boolean = adapter.isAvailable()

    override fun start(request: RecognitionRequest, callback: RecognitionCallback): Boolean {
        com.ts8.launcher.runtime.DiagnosticLogger.log("SystemProvider: start() called onMainThread=${onMainThread()}")
        if (!onMainThread()) {
            // See the class comment: a recogniser bound to the wrong looper is
            // dead, and the caller cannot be expected to remember this rule.
            main.post { start(request, callback) }
            return true
        }
        val session = Session(callback)
        return session.open()
    }

    override fun cancel() {
        if (!onMainThread()) {
            main.post { adapter.cancel() }
            return
        }
        adapter.cancel()
    }

    override fun release() {
        if (!onMainThread()) {
            main.post { adapter.release() }
            return
        }
        adapter.release()
    }

    private fun onMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    /**
     * One utterance's callbacks, with the single-terminal guard.
     *
     * The adapter already guarantees at most one terminal event per session, but
     * this class is also reachable from a firmware that delivers both `onError`
     * and `onResults` after a cancel; the guard keeps the router's own accounting
     * honest without depending on the adapter's internals.
     */
    private inner class Session(private val callback: RecognitionCallback) {

        private var done = false

        fun open(): Boolean {
            val started = adapter.start(object : SpeechRecognizerAdapter.Callback {
                override fun onReady() {
                    if (!done) callback.onReady()
                }

                override fun onProcessing() {
                    // The adapter's own notion of "audio closed"; the router
                    // moves to PROCESSING on the transcript itself, so there is
                    // nothing to forward. Logged for the latency record.
                    Log.i(TAG, "system: end-of-speech")
                }

                override fun onPartial(text: String) {
                    // Overlay only. Forwarded as-is; the controller debounces.
                    if (!done) callback.onPartial(text)
                }

                override fun onResult(hypotheses: List<String>) {
                    if (done) return
                    done = true
                    callback.onFinal(hypotheses, null)
                }

                override fun onError(error: VoiceError, detail: String?) {
                    if (done) return
                    done = true
                    callback.onError(error, detail)
                }
            })
            if (!started) {
                // The adapter reported through `onError` above. The router has
                // already seen it, so returning false here is bookkeeping only.
                Log.i(TAG, "system: session did not open")
            }
            return started
        }
    }

    companion object {
        private const val TAG = "Ts8SystemProvider"

        /** The diagnostics name. Matches the `provider=` field of the record. */
        const val ID = "system"
    }
}

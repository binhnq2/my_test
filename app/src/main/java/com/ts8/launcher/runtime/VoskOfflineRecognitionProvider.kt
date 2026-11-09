package com.ts8.launcher.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.ts8.core.voice.VoiceError
import com.ts8.core.voice.offline.RecognitionCallback
import com.ts8.core.voice.offline.RecognitionProvider
import com.ts8.core.voice.offline.RecognitionRequest
import com.ts8.core.voice.offline.VadGate
import com.ts8.core.voice.offline.VoiceDiagnostics
import com.ts8.core.voice.offline.VoiceModelState
import com.ts8.core.voice.offline.VoiceModelStore
import com.ts8.core.voice.offline.VoskResult
import com.ts8.core.voice.offline.payloadBytes
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Vietnamese speech recognition that never leaves the unit.
 *
 * **Why the unit needs this at all.** The TS8's vendor ROM ships no
 * `RecognitionService`, so the platform path answers `Voice service unavailable`
 * and the microphone button has never done anything on the real hardware. Vosk
 * reads a Kaldi model off internal storage, needs no network, and works in a
 * tunnel — which is the entire reason the pilot exists.
 *
 * **The four rules this class is built around.**
 *
 * 1. *The microphone is released on every path.* Success, silence, the hard cap,
 *    the driver pressing Back, a permission loss, an unreadable device — all run
 *    through one `finally`. A microphone held open by a screen the driver has
 *    left is the failure the brief forbids by name, and on this hardware it also
 *    stops the phone app from making calls.
 * 2. *No inference on the main thread.* Model load, the decode loop and
 *    `getFinalResult` run on one dedicated worker. A 33 MB Kaldi model opening
 *    on the main looper is an ANR, not a slow frame.
 * 3. *The model is loaded once and released when idle.* Loading costs seconds
 *    and tens of megabytes; doing it per utterance would make every command feel
 *    broken, and holding it forever takes memory from the map renderer for the
 *    whole drive. [DEFAULT_UNLOAD_IDLE_MS] is the compromise, and both the load
 *    time and the peak native heap are recorded so it is measurable.
 * 4. *Nothing is stored.* Samples go into the decoder and are dropped; the
 *    diagnostics record measurements and the final transcript only.
 *
 * **Why the grammar is per-request but the model is cached.** The allowed
 * phrases include the active map pack's place names, which change when the
 * driver swaps packs. Rebuilding a *recogniser* is cheap next to reloading a
 * model, so the two have separate lifetimes.
 *
 * **What it does not do.** It does not parse commands, decide which provider
 * runs, or touch audio focus — the controller has already ducked the music by
 * the time [start] is called, and `IntentParser` owns the meaning of whatever
 * sentence comes out.
 */
class VoskOfflineRecognitionProvider(
    context: Context,
    private val modelStore: VoiceModelStore,
    /**
     * Builds the JSON grammar for the current place list.
     *
     * A lambda rather than a value so the active map pack can change between two
     * utterances without this class holding a `places.db` handle. Returning null
     * means "unconstrained", a configuration a pack's `metadata.json` may
     * declare.
     */
    private val grammar: () -> String?,
    /** Milliseconds the model may stay loaded with no session before it is freed. */
    private val unloadIdleMs: Long = DEFAULT_UNLOAD_IDLE_MS,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) : RecognitionProvider {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** One worker for the whole provider: model load, decode, result. */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ts8-vosk").apply { isDaemon = true }
    }

    override val id: String = ID

    // -- engine state --------------------------------------------------------
    //
    // Guarded by [engineLock] on the worker. The two @Volatile fields below are
    // the only ones the main thread reads.

    private val engineLock = Any()
    private var model: Model? = null
    private var recognizer: Recognizer? = null

    /**
     * The directory the cached [model] was opened from.
     *
     * **Why the path has to be remembered.** A new model installed while the old
     * one is resident changes what [VoiceModelStore.activeDirectory] answers but
     * not what is in memory. Without this comparison, `prepareEngine` would find
     * `model != null` and keep decoding with the pack the driver just replaced —
     * silently, and with no way for them to tell.
     */
    private var loadedFrom: String? = null

    /** The grammar the cached recogniser was built with; [NO_GRAMMAR] if none. */
    private var recognizerGrammar: String = NO_GRAMMAR

    /** Set when the native loader refused the pack; sticky for the process. */
    @Volatile
    private var unusableReason: String? = null

    /** The single live session, if any. Read from the main thread by [cancel]. */
    @Volatile
    private var session: Session? = null

    @Volatile
    private var last: VoiceDiagnostics? = null

    /** The queued idle-unload, so a new session can cancel it by reference. */
    private val unloadTask = Runnable { worker.execute { unloadNow("idle") } }

    // -- RecognitionProvider -------------------------------------------------

    override fun isUsable(): Boolean = unusableReason == null && modelStore.isReady()

    override fun lastDiagnostics(): VoiceDiagnostics? = last

    override fun start(request: RecognitionRequest, callback: RecognitionCallback): Boolean {
        com.ts8.launcher.runtime.DiagnosticLogger.log("VoskProvider: start() called session=${session != null}")
        if (session != null) {
            // The router never starts twice; reaching this is a controller bug,
            // and an error that allowed a fallback would put a second recogniser
            // on the same microphone.
            callback.onError(VoiceError.INTERNAL_ERROR, "vosk: đang có phiên nhận dạng khác")
            return false
        }
        if (!hasRecordPermission()) {
            com.ts8.launcher.runtime.DiagnosticLogger.log("VoskProvider: no RECORD_AUDIO permission")
            callback.onError(VoiceError.PERMISSION_DENIED, "vosk: thiếu RECORD_AUDIO")
            return false
        }
        val directory = modelStore.activeDirectory()
        com.ts8.launcher.runtime.DiagnosticLogger.log("VoskProvider: activeDirectory=${directory?.absolutePath ?: "null"}")
        if (directory == null) {
            // No model installed. `LANGUAGE_UNAVAILABLE`, not
            // `SERVICE_UNAVAILABLE`: Vosk is present and working, it simply has
            // no Vietnamese model to load — and that is the remedy the Settings
            // CTA offers.
            com.ts8.launcher.runtime.DiagnosticLogger.log("VoskProvider: no model directory -> LANGUAGE_UNAVAILABLE (state=${modelStore.snapshot().state})")
            callback.onError(
                VoiceError.LANGUAGE_UNAVAILABLE,
                "vosk: chưa cài mô hình (${modelStore.snapshot().state})"
            )
            return false
        }
        val reason = unusableReason
        if (reason != null) {
            com.ts8.launcher.runtime.DiagnosticLogger.log("VoskProvider: unusableReason=$reason")
            callback.onError(VoiceError.INTERNAL_ERROR, "vosk: $reason")
            return false
        }

        main.removeCallbacks(unloadTask)
        val created = Session(request, callback, directory)
        session = created
        com.ts8.launcher.runtime.DiagnosticLogger.log("VoskProvider: submitting session to worker, directory=${directory.absolutePath}")
        worker.execute(created)
        return true
    }

    override fun cancel() {
        session?.abandon(END_DRIVER)
    }

    override fun release() {
        cancel()
        main.removeCallbacks(unloadTask)
        // Queued rather than synchronous: a `shutdownNow` here would kill the
        // unload it is trying to guarantee. A daemon thread with an empty queue
        // costs nothing.
        worker.execute { unloadNow("release") }
    }

    /**
     * The driver installed or replaced a pack from Settings.
     *
     * **Why this has to be explicit.** [unusableReason] is sticky for the
     * process on purpose — a model that fails to load must not be retried on
     * every press — but a *replacement* pack is a different model, and leaving
     * the old verdict in place would report "không nạp được mô hình" forever on
     * a unit whose new pack works. The old model is freed on the worker so a
     * later session re-opens the path the store now names.
     */
    override fun onModelChanged() {
        unusableReason = null
        main.removeCallbacks(unloadTask)
        worker.execute { unloadNow("model reinstalled") }
    }

    // -- one utterance -------------------------------------------------------

    /**
     * The whole capture-decode-report cycle for one press of the microphone.
     *
     * A `Runnable` so everything it needs — `AudioRecord`, gate, buffers — is
     * created and destroyed inside one `run()`, and so [abandon] has exactly one
     * object to reach for.
     */
    private inner class Session(
        private val request: RecognitionRequest,
        private val callback: RecognitionCallback,
        private val directory: File
    ) : Runnable {

        /** Set by [abandon]; read by the loop on every iteration. */
        @Volatile
        private var abandoned = false

        /** Set when the loop has reported its own outcome, so [abandon] is inert. */
        @Volatile
        private var settled = false

        @Volatile
        private var record: AudioRecord? = null

        private val diagnostics = VoiceDiagnostics(
            providerId = ID,
            modelState = VoiceModelState.READY
        )

        /**
         * The driver left, the router timed out, or the provider was released.
         *
         * `stop()` is called from here — deliberately from the abandoning thread
         * — because that is what unblocks a worker sitting inside `read()`.
         * `release()` stays on the worker: releasing an `AudioRecord` while
         * another thread is inside it is not supported, and `stop()` then
         * `release()` is the documented order.
         */
        fun abandon(reason: String) {
            if (settled || abandoned) return
            abandoned = true
            diagnostics.endReason = reason
            val current = record ?: return
            runCatching { current.stop() }
        }

        override fun run() {
            val startedAt = clock()
            com.ts8.launcher.runtime.DiagnosticLogger.log("VoskSession: run() started, directory=${directory.absolutePath}")
            try {
                if (!openMicrophone()) {
                    com.ts8.launcher.runtime.DiagnosticLogger.log("VoskSession: openMicrophone failed")
                    return
                }
                com.ts8.launcher.runtime.DiagnosticLogger.log("VoskSession: openMicrophone OK, preparing engine...")
                if (!prepareEngine()) {
                    com.ts8.launcher.runtime.DiagnosticLogger.log("VoskSession: prepareEngine failed")
                    return
                }
                com.ts8.launcher.runtime.DiagnosticLogger.log("VoskSession: prepareEngine OK, starting capture loop")
                captureLoop(startedAt)
            } catch (e: SecurityException) {
                fail(VoiceError.PERMISSION_DENIED, "vosk: ${e.message}")
            } catch (e: Throwable) {
                fail(VoiceError.INTERNAL_ERROR, "vosk: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                settled = true
                closeMicrophone()
                // A recogniser carries per-utterance state; the next session
                // starts from clean so one abandoned sentence cannot leak into
                // the next transcript.
                synchronized(engineLock) { runCatching { recognizer?.reset() } }
                if (session === this) session = null
                scheduleUnload()
            }
        }

        // -- audio -----------------------------------------------------------

        /**
         * Opens the `AudioRecord`, preferring the recogniser input source.
         *
         * `VOICE_RECOGNITION` is the source the platform defines for exactly
         * this: unprocessed and tuned for far-field speech, which is what a
         * cabin is. A vendor ROM may refuse it; the fallback to `MIC` is one
         * retry, not a loop, and which one was used is recorded.
         */
        private fun openMicrophone(): Boolean {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) {
                fail(VoiceError.MIC_BUSY, "vosk: getMinBufferSize = $minBuffer")
                return false
            }
            // A few frames per read: enough that a slow worker does not drop
            // audio, small enough that the gate still sees 20 ms resolution.
            val bufferBytes = maxOf(minBuffer, FRAME_BYTES * BUFFER_FRAMES)

            for (source in SOURCES) {
                if (abandoned) return false
                val opened = runCatching {
                    AudioRecord(
                        source,
                        SAMPLE_RATE_HZ,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferBytes
                    )
                }.getOrElse { e ->
                    diagnostics.audioInitOk = false
                    diagnostics.audioSource = sourceName(source)
                    Log.w(TAG, "AudioRecord($source) threw: ${e.message}")
                    null
                } ?: continue

                if (opened.state != AudioRecord.STATE_INITIALIZED) {
                    diagnostics.audioInitOk = false
                    diagnostics.audioSource = sourceName(source)
                    runCatching { opened.release() }
                    continue
                }
                // Published before `startRecording` so an abandon arriving
                // during the call below can still stop it.
                record = opened
                val started = runCatching { opened.startRecording() }.isSuccess
                if (!started) {
                    record = null
                    diagnostics.audioInitOk = false
                    diagnostics.audioSource = sourceName(source)
                    runCatching { opened.release() }
                    continue
                }
                diagnostics.audioInitOk = true
                diagnostics.audioSource = sourceName(source)
                diagnostics.sampleRateHz = SAMPLE_RATE_HZ
                Log.i(TAG, "capturing at ${SAMPLE_RATE_HZ}Hz from ${sourceName(source)}")
                return true
            }

            fail(VoiceError.MIC_BUSY, "vosk: không mở được AudioRecord")
            return false
        }

        private fun closeMicrophone() {
            val current = record ?: return
            record = null
            // `stop` before `release`: on Android 6 releasing a *recording*
            // AudioRecord leaves the input device open until the process dies.
            runCatching {
                if (current.recordingState == AudioRecord.RECORDSTATE_RECORDING) current.stop()
            }
            runCatching { current.release() }
        }

        // -- model -----------------------------------------------------------

        /**
         * Loads the model if it is not already in memory and prepares the
         * recogniser for this request's grammar.
         *
         * On the worker. Load time and native heap around the load are the two
         * numbers §11 asks for and the two that decide whether the idle-unload
         * policy is right on real hardware.
         */
        private fun prepareEngine(): Boolean {
            synchronized(engineLock) {
                if (abandoned) return false

                val modelDir = File(directory, MODEL_SUBDIR)
                if (!modelDir.isDirectory) {
                    fail(VoiceError.LANGUAGE_UNAVAILABLE, "vosk: thiếu thư mục model/")
                    return false
                }

                // A different pack on disk than the one in memory: the driver
                // replaced the model from Settings while this process was
                // running. Free the old one first — the worker owns it, so
                // there is no decode to interrupt — and re-open below.
                val wantedPath = modelDir.absolutePath
                if (model != null && loadedFrom != wantedPath) {
                    unloadNow("model changed")
                }

                val current = model ?: loadModel(modelDir, wantedPath) ?: return false
                if (model !== current) {
                    // `loadModel` failed and already reported.
                    return false
                }

                val wanted = runCatching { grammar() }.getOrNull()
                val prepared = runCatching {
                    if (recognizer == null || recognizerGrammar != wantedKey(wanted)) {
                        runCatching { recognizer?.close() }
                        recognizer = null
                        recognizer = createRecognizer(current, wanted)
                        recognizerGrammar = wantedKey(wanted)
                    }
                    true
                }.getOrElse { e ->
                    // The validator said READY and the native side refused: this
                    // is the pack's own FAILED state, and the driver needs
                    // "install it again", not "corrupt".
                    val detail = "vosk: không tạo được recognizer: ${e.message ?: e.javaClass.simpleName}"
                    unusableReason = detail
                    modelStore.markLoadFailed(detail)
                    fail(VoiceError.INTERNAL_ERROR, detail)
                    false
                }
                if (!prepared) return false

                val manifest = modelStore.snapshot().manifest
                diagnostics.modelVersion = manifest?.modelVersion ?: directory.name
                diagnostics.modelSizeBytes = manifest?.size ?: directory.payloadBytes()
                diagnostics.modelSha256 = manifest?.sha256
                diagnostics.modelAbi = manifest?.supportedAbis?.firstOrNull() ?: "any"
                diagnostics.peakNativeHeapBytes = nativeHeap()
                return true
            }
        }

        /** Returns the loaded model, or null after reporting why it is not. */
        private fun loadModel(modelDir: File, path: String): Model? {
            val before = nativeHeap()
            val startedAt = clock()
            val loaded = runCatching { Model(modelDir.absolutePath) }.getOrElse { e ->
                val detail = "vosk: không nạp được mô hình: ${e.message ?: e.javaClass.simpleName}"
                unusableReason = detail
                modelStore.markLoadFailed(detail)
                fail(VoiceError.INTERNAL_ERROR, detail)
                return null
            }
            model = loaded
            loadedFrom = path
            diagnostics.modelLoadMs = clock() - startedAt
            diagnostics.modelWarm = false
            diagnostics.peakNativeHeapBytes = maxOf(nativeHeap(), before)
            Log.i(TAG, "model loaded in ${diagnostics.modelLoadMs} ms")
            return loaded
        }

        private fun createRecognizer(loaded: Model, grammarJson: String?): Recognizer {
            val created = if (grammarJson.isNullOrBlank()) {
                Recognizer(loaded, SAMPLE_RATE_HZ.toFloat())
            } else {
                Recognizer(loaded, SAMPLE_RATE_HZ.toFloat(), grammarJson)
            }
            // Alternatives let the controller prefer a hypothesis the parser can
            // act on, so a wrong top result costs nothing.
            runCatching {
                created.setMaxAlternatives(request.maxAlternatives.coerceIn(1, MAX_ALTERNATIVES))
            }
            return created
        }

        // -- decode ----------------------------------------------------------

        /**
         * Reads 20 ms frames until the gate says the utterance is over.
         *
         * **Why elapsed time comes from the sample count.** A wall clock jumps
         * when the head unit syncs GPS time, and a `Handler` timer drifts against
         * a blocking `read()`. Samples consumed is exactly how long the
         * microphone has been open, monotonic by construction, and makes the
         * gate's timeouts mean the same thing at any rate.
         */
        private fun captureLoop(startedAt: Long) {
            val gate = VadGate(
                sampleRateHz = SAMPLE_RATE_HZ,
                silenceTimeoutMs = request.silenceTimeoutMs,
                maxUtteranceMs = request.maxUtteranceMs
            )
            val rec = record ?: run {
                fail(VoiceError.MIC_BUSY, "vosk: AudioRecord biến mất")
                return
            }
            val engine = synchronized(engineLock) { recognizer } ?: run {
                fail(VoiceError.INTERNAL_ERROR, "vosk: recognizer = null")
                return
            }

            post { if (!abandoned) callback.onReady() }

            // The music was ducked a moment ago and the output is still ramping
            // down; a short settle stops the first word from being mixed with
            // the tail of the track. Bounded, and aborted by an abandon.
            settleBeforeCapture()

            val frame = ShortArray(gate.frameSamples)
            var samplesRead = 0L
            var lastPartialAt = 0L
            var lastPartial = ""
            var readFailures = 0

            while (!abandoned) {
                val read = runCatching { rec.read(frame, 0, frame.size) }.getOrElse { -1 }
                if (read <= 0) {
                    if (abandoned) break
                    // A released or stolen microphone answers 0 forever. Eight
                    // consecutive frames is 160 ms of nothing: far past a
                    // scheduling hiccup, far short of the hard cap.
                    if (++readFailures >= MAX_READ_FAILURES) {
                        fail(VoiceError.MIC_BUSY, "vosk: AudioRecord.read = $read")
                        return
                    }
                    Thread.sleep(FRAME_MS)
                    continue
                }
                readFailures = 0
                samplesRead += read
                val elapsedMs = samplesRead * 1000L / SAMPLE_RATE_HZ

                // The decoder gets exactly what the driver said. Trailing
                // silence in a frame costs nothing and keeps the acoustic
                // context continuous across the gate's windows.
                val chunk = if (read == frame.size) frame else frame.copyOf(read)
                runCatching { engine.acceptWaveForm(chunk, chunk.size) }

                if (request.partialResults) {
                    val now = clock()
                    if (now - lastPartialAt >= PARTIAL_MIN_INTERVAL_MS) {
                        lastPartialAt = now
                        val partial = VoskResult.parse(
                            runCatching { engine.partialResult }.getOrNull()
                        ).text
                        if (partial.isNotEmpty() && partial != lastPartial) {
                            lastPartial = partial
                            post { if (!abandoned) callback.onPartial(partial) }
                        }
                    }
                }

                when (gate.accept(chunk, elapsedMs)) {
                    VadGate.Verdict.CONTINUE -> Unit

                    VadGate.Verdict.END_NO_SPEECH -> {
                        diagnostics.endReason = END_NO_SPEECH
                        diagnostics.recognitionLatencyMs = clock() - startedAt
                        // The level evidence goes in the message on purpose.
                        // "No speech" from a deaf microphone and "no speech"
                        // from a silent driver are the same sentence in a log
                        // and opposite fixes: peak below the threshold means the
                        // mic heard nothing, peak well above it means the gate
                        // refused to believe what it heard.
                        fail(
                            VoiceError.NO_SPEECH,
                            "vosk: không có tiếng nói (peak=${gate.peakSample}, " +
                                "ngưỡng=${gate.threshold().toInt()}, frames=${gate.framesSeen})"
                        )
                        return
                    }

                    VadGate.Verdict.END_SILENCE,
                    VadGate.Verdict.END_MAX -> {
                        diagnostics.endReason = END_SILENCE
                        diagnostics.recognitionLatencyMs = clock() - startedAt
                        deliver(
                            VoskResult.parse(runCatching { engine.finalResult }.getOrNull())
                        )
                        return
                    }
                }
            }
        }

        private fun settleBeforeCapture() {
            val deadline = clock() + PRE_CAPTURE_SETTLE_MS
            while (!abandoned && clock() < deadline) {
                Thread.sleep(SETTLE_STEP_MS)
            }
        }

        private fun deliver(decoded: VoskResult.Decoded) {
            if (decoded.isEmpty) {
                // The gate heard speech and the engine produced nothing: a real
                // no-match, and the one the driver fixes by repeating.
                diagnostics.errorCode = "EMPTY_RESULT"
                fail(VoiceError.NO_MATCH, "vosk: kết quả rỗng")
                return
            }
            diagnostics.finalTranscript = decoded.text
            diagnostics.confidence = decoded.confidence
            diagnostics.error = null
            last = diagnostics
            val hypotheses = decoded.hypotheses
            val confidence = decoded.confidence
            post { if (!abandoned) callback.onFinal(hypotheses, confidence) }
        }

        /** Reports [error] at most once, and never after an abandon. */
        private fun fail(error: VoiceError, detail: String?) {
            if (abandoned) return
            diagnostics.error = error
            if (diagnostics.errorCode == null) diagnostics.errorCode = error.name
            last = diagnostics
            Log.i(TAG, "vosk failed: $error ($detail)")
            post { if (!abandoned) callback.onError(error, detail) }
        }

        private fun post(block: () -> Unit) {
            main.post { runCatching(block) }
        }
    }

    // -- engine lifecycle ----------------------------------------------------

    /**
     * Frees the model after [unloadIdleMs] with no session.
     *
     * Posted to the main looper and removed by the next [start], so the common
     * case — three commands in a row — never pays the unload at all.
     */
    private fun scheduleUnload() {
        main.removeCallbacks(unloadTask)
        main.postDelayed(unloadTask, unloadIdleMs)
    }

    /** Runs on [worker], so the free can never race a decode. */
    private fun unloadNow(reason: String) {
        synchronized(engineLock) {
            runCatching { recognizer?.close() }
            recognizer = null
            recognizerGrammar = NO_GRAMMAR
            val held = model ?: return
            model = null
            loadedFrom = null
            runCatching { held.close() }
            Log.i(TAG, "model released ($reason)")
        }
    }

    // -- helpers -------------------------------------------------------------

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun nativeHeap(): Long =
        runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(0L)

    private fun sourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.MIC -> "MIC"
        else -> "source-$source"
    }

    /** Grammar identity, so "null" and "empty" cannot be confused with unset. */
    private fun wantedKey(grammarJson: String?): String =
        if (grammarJson.isNullOrBlank()) NO_GRAMMAR else grammarJson

    companion object {
        private const val TAG = "Ts8VoskProvider"

        /** The diagnostics name, and the `provider=` field of the record. */
        const val ID = "vosk"

        /**
         * Vosk decodes at the rate its model was trained at, and the small
         * Vietnamese model is trained on 16 kHz. Anything else decodes to noise.
         */
        const val SAMPLE_RATE_HZ = 16_000

        /** The pack layout: the Kaldi directory lives under `model/`. */
        private const val MODEL_SUBDIR = "model"

        private const val FRAME_MS = VadGate.FRAME_MS.toLong()

        private const val FRAME_SAMPLES = SAMPLE_RATE_HZ * VadGate.FRAME_MS / 1000

        private const val FRAME_BYTES = FRAME_SAMPLES * 2

        /** Read buffers are a few frames, so a slow worker does not drop audio. */
        private const val BUFFER_FRAMES = 8

        /**
         * Preferred then fallback input source.
         *
         * `VOICE_RECOGNITION` first because the platform defines it for this
         * purpose; `MIC` because a vendor ROM that refuses the first must still
         * have a working microphone button.
         */
        private val SOURCES = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )

        /**
         * How long the model stays resident after the last utterance.
         *
         * Long enough that a driver giving three commands in a row pays the load
         * once, short enough that a car parked with the launcher open gets its
         * memory back before the map needs it.
         */
        const val DEFAULT_UNLOAD_IDLE_MS = 120_000L

        /**
         * Partials are throttled to roughly four a second.
         *
         * The overlay repaints on each one; at the decoder's own rate that is one
         * repaint per 20 ms frame, visible as a stutter in the bar exactly while
         * the driver is watching it.
         */
        private const val PARTIAL_MIN_INTERVAL_MS = 250L

        /** Let the ducked track's tail clear before the first frame is judged. */
        private const val PRE_CAPTURE_SETTLE_MS = 250L

        private const val SETTLE_STEP_MS = 20L

        /** 160 ms of an unreadable microphone before it is reported as busy. */
        private const val MAX_READ_FAILURES = 8

        /** Vosk's own ceiling on `setMaxAlternatives` is small; 10 is generous. */
        private const val MAX_ALTERNATIVES = 10

        /** Why the utterance ended, as the diagnostics spell it. */
        private const val END_DRIVER = "driver"
        private const val END_SILENCE = "silence"
        private const val END_NO_SPEECH = "no-speech"

        /** Sentinel for "the cached recogniser has no grammar". */
        private const val NO_GRAMMAR = "\u0000none"
    }
}

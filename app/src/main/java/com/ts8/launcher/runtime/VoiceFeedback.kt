package com.ts8.launcher.runtime

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.ts8.core.audio.AudioResult
import com.ts8.core.audio.VehicleAudioBridge
import com.ts8.core.music.AudioRoute
import java.util.Locale

/**
 * Speaks the router's sentences, and holds the music down while it does.
 *
 * Also produces short beep tones to give the driver non-verbal feedback:
 * - [beepStart]: a short high tone when the microphone opens, so the driver
 *   knows voice is active without looking at the screen.
 * - [beepEnd]: a soft tone when the interaction ends.
 *
 * **Why ToneGenerator and not SoundPool.** `ToneGenerator` writes directly
 * to `STREAM_DTMF` (or a chosen stream), needs no asset file, and works on
 * every Android 6 ROM — including vendor ROMs that strip the
 * `RingtoneManager` service. It is the lightest way to make a noise on the
 * TS8.
 */
class VoiceFeedback(
    context: Context,
    private val audioBridge: VehicleAudioBridge
) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** Null until the engine binds, and null forever on a unit without one. */
    private var tts: TextToSpeech? = null

    /** Null while the engine is still initialising. */
    @Volatile
    private var languageOk: Boolean? = null

    @Volatile
    private var initialising = false

    /** True while the bridge is ducked on this class's behalf. */
    private var ducked = false

    /** Runs after the current utterance ends, or immediately if none is speaking. */
    private var afterSpeech: (() -> Unit)? = null

    /**
     * The beep generator, created lazily and reused.
     *
     * `ToneGenerator` is cheap to construct but not free; holding one avoids
     * a per-press allocation on the audio thread. It writes to
     * `STREAM_MUSIC` so the ducked volume applies — the beep is heard at the
     * same level as TTS prompts, not at full ring volume.
     */
    @Volatile
    private var toneGen: ToneGenerator? = null

    /**
     * Whether beep tones are enabled. Read from [AppSettings.voiceBeepEnabled]
     * at session start, and checked here so a driver who turns it off in
     * Settings gets silence on the next press without a restart.
     */
    @Volatile
    var beepEnabled: Boolean = true

    /**
     * Plays a short start beep so the driver knows the microphone is open.
     *
     * Called from [VoiceInstantController.start] right after the session
     * opens. The tone is a brief high-pitched blip (TONE_PROP_BEEP, ~100ms)
     * on `STREAM_MUSIC` so it respects the ducked volume level.
     */
    fun beepStart() {
        if (!beepEnabled) return
        main.post {
            runCatching {
                val gen = toneGen ?: ToneGenerator(AudioManager.STREAM_MUSIC, BEEP_VOLUME).also { toneGen = it }
                gen.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_DURATION_MS)
            }.onFailure { Log.w(TAG, "beepStart failed: ${it.message}") }
        }
    }

    /**
     * Plays a soft end tone when the interaction completes.
     *
     * Lower volume and shorter than the start beep — it confirms "done"
     * without being startling.
     */
    fun beepEnd() {
        if (!beepEnabled) return
        main.post {
            runCatching {
                val gen = toneGen ?: ToneGenerator(AudioManager.STREAM_MUSIC, BEEP_VOLUME_END).also { toneGen = it }
                gen.startTone(ToneGenerator.TONE_PROP_BEEP2, BEEP_END_DURATION_MS)
            }.onFailure { Log.w(TAG, "beepEnd failed: ${it.message}") }
        }
    }

    /**
     * Plays an error tone — two short low beeps.
     *
     * Used when recognition fails so the driver knows to try again without
     * looking at the screen.
     */
    fun beepError() {
        if (!beepEnabled) return
        main.post {
            runCatching {
                val gen = toneGen ?: ToneGenerator(AudioManager.STREAM_MUSIC, BEEP_VOLUME).also { toneGen = it }
                gen.startTone(ToneGenerator.TONE_PROP_NACK, BEEP_ERROR_DURATION_MS)
            }.onFailure { Log.w(TAG, "beepError failed: ${it.message}") }
        }
    }

    /**
     * Prepares the engine.
     *
     * Idempotent and cheap after the first call. Called when the overlay opens
     * rather than at app start, so a driver who never uses voice never pays for
     * a TTS binding — and so a unit whose engine hangs on init does not delay
     * the launcher's cold start.
     */
    fun prepare() {
        if (tts != null || initialising) return
        initialising = true
        val created = runCatching {
            TextToSpeech(appContext) { status ->
                initialising = false
                if (status != TextToSpeech.SUCCESS) {
                    // No engine, or it failed to initialise: stay silent and let
                    // the overlay be the only feedback, rather than retrying
                    // forever on a firmware that will never have one.
                    Log.w(TAG, "TTS init failed: $status")
                    languageOk = false
                    return@TextToSpeech
                }
                val engine = tts ?: return@TextToSpeech
                val result = runCatching { engine.setLanguage(Locale("vi", "VN")) }
                    .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                languageOk = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
                if (languageOk != true) {
                    Log.w(TAG, "no Vietnamese TTS voice; voice feedback is visual only")
                }
                runCatching { engine.setSpeechRate(SPEECH_RATE) }
            }
        }.getOrNull()
        tts = created
        if (created == null) {
            initialising = false
            languageOk = false
            return
        }
        created.setOnUtteranceProgressListener(progress)
    }

    /**
     * True when a sentence can actually be spoken.
     *
     * Tri-state internally — null means "still initialising" — but callers get
     * a boolean, and an uninitialised engine answers `false`: promising to speak
     * and then not doing it is the failure this method exists to prevent.
     */
    fun canSpeak(): Boolean = languageOk == true

    /**
     * Speaks [text], queueing behind anything already speaking.
     *
     * `QUEUE_FLUSH` is deliberately **not** used: the "Đang nghe…" prompt and
     * the result sentence arrive close together, and flushing the first means
     * the driver hears the outcome with no context for it.
     */
    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (text.isBlank()) {
            onComplete?.invoke()
            return
        }
        prepare()
        val engine = tts
        if (engine == null || languageOk != true) {
            // No audio; the overlay carries the message. The completion still
            // runs, because a caller waiting on it must not hang on a mute unit.
            Log.i(TAG, "voice feedback unavailable; text only: $text")
            onComplete?.invoke()
            return
        }
        if (onComplete != null) afterSpeech = onComplete else afterSpeech = null
        val id = "utterance-${nextUtteranceId++}"
        val queued = runCatching { engine.speak(text, TextToSpeech.QUEUE_ADD, null, id) }
            .getOrDefault(TextToSpeech.ERROR)
        if (queued != TextToSpeech.SUCCESS) {
            afterSpeech = null
            Log.w(TAG, "speak() refused: $queued")
            onComplete?.invoke()
        }
    }

    /** Drops anything queued, e.g. when the driver presses Back mid-prompt. */
    fun stopSpeaking() {
        runCatching { tts?.stop() }
        afterSpeech = null
    }

    /**
     * Takes the music down for the duration of the interaction.
     *
     * If audio focus is refused the duck is skipped rather than forced: another
     * app owns the stream, and scaling `STREAM_MUSIC` under it would change
     * their volume, not ours.
     */
    fun begin() {
        prepare()
        val focus = runCatching { audioBridge.requestFocus(AudioRoute.NAVIGATION) }.getOrNull()
        if (focus !is AudioResult.Success) {
            // Nothing to duck, but TTS may still be heard over the other app's
            // stream on some firmware, so the interaction is not abandoned.
            Log.i(TAG, "no navigation focus; music not ducked for voice")
            return
        }
        val ducked = runCatching { audioBridge.duck() }.isSuccess
        this.ducked = ducked
    }

    /** Releases the duck and any queued speech. Safe when never begun. */
    fun end() {
        stopSpeaking()
        if (!ducked) return
        ducked = false
        runCatching { audioBridge.unduck() }
        runCatching { audioBridge.abandonFocus(AudioRoute.NAVIGATION) }
    }

    /** Releases the TTS engine and tone generator. The controller calls this when it is destroyed. */
    fun release() {
        end()
        val engine = tts
        tts = null
        languageOk = null
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        runCatching { toneGen?.release() }
        toneGen = null
    }

    // -- internals ----------------------------------------------------------

    private var nextUtteranceId = 0

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        /** Delivered on a binder thread; anything touching the UI is hopped. */
        override fun onDone(utteranceId: String?) {
            val next = afterSpeech ?: return
            afterSpeech = null
            main.post { runCatching { next() } }
        }

        @Deprecated("Deprecated in Java; the platform still calls it on API 23.")
        override fun onError(utteranceId: String?) {
            onDone(utteranceId)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            onDone(utteranceId)
        }
    }

    companion object {
        private const val TAG = "Ts8VoiceFeedback"

        /**
         * Slightly faster than default. Vietnamese prompts are short and the
         * driver is moving; the default rate reads as languid on a car speaker.
         */
        private const val SPEECH_RATE = 1.05f

        /** Tone volume (0-100). Moderate so it's audible over ducked music. */
        private const val BEEP_VOLUME = 80

        /** End beep is softer. */
        private const val BEEP_VOLUME_END = 60

        /** Start beep duration: ~100ms, short enough to not feel like a ringtone. */
        private const val BEEP_DURATION_MS = 100

        /** End beep duration: shorter and softer. */
        private const val BEEP_END_DURATION_MS = 80

        /** Error beep: two-tone nack, ~200ms. */
        private const val BEEP_ERROR_DURATION_MS = 200
    }
}

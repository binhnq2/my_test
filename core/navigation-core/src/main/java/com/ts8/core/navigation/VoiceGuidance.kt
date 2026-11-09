package com.ts8.core.navigation

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Build
import android.os.Bundle
import com.ts8.core.audio.AudioFocusListener
import com.ts8.core.audio.AudioResult
import com.ts8.core.audio.VehicleAudioBridge
import com.ts8.core.music.AudioRoute
import java.util.Locale
import java.util.UUID

/**
 * Vietnamese voice guidance.
 *
 * Two responsibilities the spec calls out explicitly, and this class owns
 * both:
 *
 * 1. **Music ducking.** Before an utterance, the media stream is lowered;
 *    after it finishes, the exact previous level is restored. Duck and unduck
 *    are paired through [UtteranceProgressListener] rather than a timer, so a
 *    long street name does not leave the music quiet forever.
 * 2. **Honest failure.** If the device has no Vietnamese TTS engine, the
 *    launcher says so and keeps working with visual guidance. It never
 *    pretends to speak.
 *
 * `TextToSpeech` on API 23 has no `speak(CharSequence, ...)` overload, so the
 * deprecated `String` overload is used deliberately, with the SDK check kept
 * for the newer path.
 */
class VoiceGuidance(
    context: Context,
    private val audioBridge: VehicleAudioBridge
) {
    private val appContext = context.applicationContext

    private var tts: TextToSpeech? = null
    private var initialised = false

    /** Null until init completes; false when the engine is missing or broken. */
    @Volatile
    private var vietnameseAvailable: Boolean? = null

    /** True while an utterance is in progress and the music is ducked. */
    @Volatile
    private var speaking = false

    private var speechRate = 1.0f
    private var pitch = 1.0f

    /** Callback for the UI to show "no Vietnamese voice" once, not per prompt. */
    var onAvailabilityChanged: ((Boolean) -> Unit)? = null

    /** True when the platform actually has a Vietnamese voice. */
    fun isVietnameseAvailable(): Boolean = vietnameseAvailable == true

    /** True while an utterance is playing. */
    fun isSpeaking(): Boolean = speaking

    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.5f, 2.0f)
        tts?.setSpeechRate(speechRate)
    }

    fun setPitch(value: Float) {
        pitch = value.coerceIn(0.5f, 2.0f)
        tts?.setPitch(pitch)
    }

    /**
     * Initialises the engine. Safe to call more than once; only the first call
     * starts an engine.
     */
    fun init(onReady: (Boolean) -> Unit = {}) {
        if (tts != null) {
            onReady(isVietnameseAvailable())
            return
        }
        tts = TextToSpeech(appContext) { status ->
            initialised = true
            if (status != TextToSpeech.SUCCESS) {
                vietnameseAvailable = false
                onAvailabilityChanged?.invoke(false)
                onReady(false)
                return@TextToSpeech
            }
            configureLocale(onReady)
        }
    }

    private fun configureLocale(onReady: (Boolean) -> Unit) {
        val engine = tts ?: return

        val vietnamese = Locale("vi", "VN")
        val result = engine.setLanguage(vietnamese)

        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            // Before giving up, check whether any installed voice lists
            // Vietnamese — some firmware ships the voice without advertising it
            // as the default.
            //
            // `availableLanguages` is a Set<String> of BCP-47 tags on API 23 and
            // a Set<Locale> on API 24+, so the elements are normalised through
            // toString() rather than assumed to be one type.
            val viTag = engine.availableLanguages
                .map { it.toString() }
                .firstOrNull { raw ->
                    val tag = raw.replace('_', '-')
                    runCatching { Locale.forLanguageTag(tag).language == "vi" }.getOrDefault(false)
                }

            if (viTag != null) {
                engine.setLanguage(Locale.forLanguageTag(viTag.replace('_', '-')))
                finishSetup(true, onReady)
            } else {
                // Keep the engine: English guidance is still better than none,
                // but the UI must tell the driver the voice is not Vietnamese.
                engine.setLanguage(Locale.US)
                finishSetup(false, onReady)
            }
        } else {
            finishSetup(true, onReady)
        }
    }

    private fun finishSetup(viAvailable: Boolean, onReady: (Boolean) -> Unit) {
        val engine = tts ?: return
        vietnameseAvailable = viAvailable
        engine.setSpeechRate(speechRate)
        engine.setPitch(pitch)

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                speaking = true
            }

            override fun onDone(utteranceId: String?) {
                endUtterance()
            }

            @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, errorCode)"))
            override fun onError(utteranceId: String?) {
                endUtterance()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                endUtterance()
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                endUtterance()
            }
        })

        onAvailabilityChanged?.invoke(viAvailable)
        onReady(viAvailable)
    }

    /**
     * Speaks an announcement, ducking music for its duration.
     *
     * @return false when there is no engine, so callers can log rather than
     *         assume the driver heard something.
     */
    fun speak(text: String, urgent: Boolean): Boolean {
        val engine = tts
        if (!initialised || engine == null || vietnameseAvailable == null) {
            return false
        }
        if (text.isBlank()) return false

        // Duck first, then speak: ducking after the first syllable clips the
        // start of the instruction.
        if (!speaking) {
            when (val r = audioBridge.duck()) {
                is AudioResult.Denied, is AudioResult.Failed, is AudioResult.Unsupported -> {
                    // Speaking without ducking is preferable to not speaking.
                    // The music will be too loud, but the instruction is safety
                    // relevant and must not be dropped.
                }
                // `duck()` is implemented with `setStreamVolume` under the
                // hood, so a firmware that ignores the write reports NoEffect
                // here. That is the same driving situation as the branch above
                // — music not lowered — and gets the same answer: speak anyway.
                is AudioResult.NoEffect -> Unit
                AudioResult.Success -> Unit
            }
        }
        speaking = true

        val utteranceId = "ts8-${UUID.randomUUID()}"
        val queueMode = if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            engine.speak(text, queueMode, Bundle(), utteranceId)
        } else {
            @Suppress("DEPRECATION")
            engine.speak(text, queueMode, null, utteranceId)
        }

        if (result != TextToSpeech.SUCCESS) {
            // Nothing was queued, so nothing will call onDone — undo the duck
            // here or the music stays quiet with no explanation.
            endUtterance()
            return false
        }
        return true
    }

    /** Stops any current utterance and restores the music level. */
    fun stop() {
        tts?.stop()
        endUtterance()
    }

    /**
     * Ducks and unducks must be balanced. `UtteranceProgressListener` fires
     * for each utterance, and `QUEUE_ADD` means several can be queued; the
     * bridge treats duck as idempotent, and unduck only fires once nothing is
     * left speaking.
     */
    private fun endUtterance() {
        speaking = false
        audioBridge.unduck()
    }

    /**
     * Requests navigation focus once at startup so a phone call is detected.
     * The listener itself is installed by the caller on the bridge.
     */
    fun acquireNavigationFocus(): AudioResult =
        audioBridge.requestFocus(AudioRoute.NAVIGATION)

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        initialised = false
        vietnameseAvailable = null
    }

    /** Opens the system TTS settings so the driver can install a voice. */
    fun openTtsSettings(): Intent = Intent("com.android.settings.TTS_SETTINGS")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** A diagnostic line for the diagnostics screen. */
    fun report(): String = when (vietnameseAvailable) {
        true -> "TTS: tiếng Việt khả dụng"
        false -> "TTS: không có giọng tiếng Việt — chỉ dẫn bằng hình ảnh"
        null -> "TTS: chưa khởi tạo"
    }

    /**
     * Adapter the app installs on the bridge so a call silences guidance
     * immediately rather than ducking.
     */
    fun asFocusListener(): AudioFocusListener = object : AudioFocusListener {
        override fun onFocusLostPermanent() {
            stop()
        }

        override fun onFocusLostTransient(mayDuck: Boolean) {
            // Guidance is short; finishing the sentence over a notification is
            // worse than stopping it.
            if (speaking) stop()
        }

        override fun onFocusGained() = Unit

        override fun onCallStarted() {
            stop()
        }

        override fun onCallEnded() = Unit

        override fun onAudioRouteChanged(route: AudioRoute) = Unit
    }
}

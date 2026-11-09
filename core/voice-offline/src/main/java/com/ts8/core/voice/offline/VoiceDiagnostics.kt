package com.ts8.core.voice.offline

import com.ts8.core.voice.VoiceError

/**
 * One voice interaction, as a record the driver can export.
 *
 * **What is in it, and what is deliberately absent.** The brief asks for the
 * provider chosen, the fallback reason, the model state/version/size/checksum/
 * ABI, model load time, peak memory, recognition latency, the audio
 * initialisation result, the sample rate, the timeout/error code, the final
 * transcript and the confidence. It asks equally clearly for **no audio**. So
 * this record holds measurements and the transcript, never samples, never a
 * file path to a recording — and it carries no location, because a voice
 * interaction is not a reason to record where the car is.
 *
 * **Why the transcript is here when partials are not.** A partial is a guess
 * in progress; the final is what the launcher acted on, and "it did the wrong
 * thing" cannot be investigated without it. Partials are shown on the overlay
 * and dropped, which is also what stops the log from growing one line per
 * frame during a sentence.
 *
 * **Why generation counts are plain numbers and not durations.** The TS8 has no
 * reliable wall clock across a power cut, and a duration computed from one that
 * jumps is worse than no duration. Everything here that is a time is a
 * monotonic delta measured on `elapsedRealtime` within one process.
 */
data class VoiceDiagnostics(
    /** `system`, `vosk`, … — whichever held the microphone at the end. */
    var providerId: String? = null,
    /** The policy in force when the request began. */
    var policy: RecognitionPolicy? = null,
    /** How many times the provider changed: 0 or 1, never more. */
    var switches: Int = 0,
    /** Every `provider:error` pair seen, in order. */
    var failureTrail: String? = null,

    // -- model -----------------------------------------------------------
    var modelState: VoiceModelState? = null,
    var modelVersion: String? = null,
    var modelSizeBytes: Long? = null,
    var modelSha256: String? = null,
    var modelAbi: String? = null,
    /** Milliseconds to open the native model; null when it was already open. */
    var modelLoadMs: Long? = null,
    /** True when the load was on the warm path (model already in memory). */
    var modelWarm: Boolean = false,
    /** `Debug.getNativeHeapAllocatedSize` peak observed around the load. */
    var peakNativeHeapBytes: Long? = null,

    // -- audio -----------------------------------------------------------
    /** Whether `AudioRecord` reached `STATE_INITIALIZED`. */
    var audioInitOk: Boolean? = null,
    var sampleRateHz: Int? = null,
    /** The `MediaRecorder.AudioSource` that was actually used. */
    var audioSource: String? = null,
    /** Milliseconds between the microphone opening and the final transcript. */
    var recognitionLatencyMs: Long? = null,
    /** Why the utterance ended: `silence`, `max-length`, `driver`, `error`. */
    var endReason: String? = null,

    // -- outcome ---------------------------------------------------------
    var error: VoiceError? = null,
    /** The closed error code the provider reported, before any mapping. */
    var errorCode: String? = null,
    var finalTranscript: String? = null,
    /** Null when the engine reports no confidence — never a made-up 1.0. */
    var confidence: Float? = null
) {

    /**
     * One line per field, in the order a support call reads them.
     *
     * Written as `key=value` with a leading marker so the existing diagnostics
     * export can carry it as-is. Values are escaped to a single line: a
     * transcript may contain a newline if the recogniser emits one, and a
     * record that splits across lines cannot be parsed back.
     */
    fun toLines(): List<String> {
        val out = ArrayList<String>(24)
        fun put(key: String, value: Any?) {
            if (value != null) out += "voice.$key=${escape(value.toString())}"
        }
        put("provider", providerId)
        put("policy", policy?.name)
        put("switches", switches)
        put("fallbackTrail", failureTrail)
        put("model.state", modelState?.name)
        put("model.version", modelVersion)
        put("model.size", modelSizeBytes)
        put("model.sha256", modelSha256)
        put("model.abi", modelAbi)
        put("model.loadMs", modelLoadMs)
        put("model.warm", modelWarm)
        put("model.peakNativeHeap", peakNativeHeapBytes)
        put("audio.initOk", audioInitOk)
        put("audio.sampleRate", sampleRateHz)
        put("audio.source", audioSource)
        put("latencyMs", recognitionLatencyMs)
        put("endReason", endReason)
        put("error", error?.name)
        put("errorCode", errorCode)
        put("transcript", finalTranscript)
        put("confidence", confidence)
        return out
    }

    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

    companion object {
        /**
         * The stated policy, in one sentence, for the diagnostics screen.
         *
         * Kept here rather than in a doc so a driver reading the export does
         * not have to be told twice that no audio was kept.
         */
        const val NO_AUDIO_NOTE: String =
            "Bản chẩn đoán chỉ chứa số đo và câu lệnh cuối cùng; không lưu âm thanh."
    }
}

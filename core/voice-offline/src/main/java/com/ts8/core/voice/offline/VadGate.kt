package com.ts8.core.voice.offline

/**
 * Decides when the driver has finished speaking, from the audio itself.
 *
 * **Why not rely on the recogniser's endpointing.** The system recogniser ends
 * an utterance for us. The offline path feeds `AudioRecord` to Vosk one buffer
 * at a time, and Vosk has no idea when to stop — left alone it decodes the
 * cabin's air-conditioning until the hard cap. Something has to close the
 * microphone, and that something has to be testable without a head unit.
 *
 * **Why energy and not a model.** A neural VAD would be another 1–2 MB and
 * another dependency for a decision that only has to be approximately right:
 * ending a little early loses a word the driver can repeat, and ending a little
 * late costs a second. What it must *not* do is end during a pause between two
 * words, which is what the [minSpeechMs] gate and the adaptive floor are for.
 *
 * **Why the floor adapts.** A fixed threshold either fires on the engine at
 * idle or misses a quiet speaker. The floor tracks the quietest frames seen so
 * far and the threshold sits a fixed ratio above it, so the same constant works
 * in a parked car and on a motorway — and it never rises above
 * [maxNoiseFloor] where it would start ignoring a soft voice.
 *
 * **What this class does not do.** It does not touch `AudioRecord`, does not
 * allocate in the hot path, and does not keep the audio. It is a state machine
 * over (frame, elapsed) and is exercised entirely by unit tests.
 */
class VadGate(
    private val sampleRateHz: Int = SAMPLE_RATE_HZ,
    /** Milliseconds of silence that end the utterance. */
    private val silenceTimeoutMs: Long = DEFAULT_SILENCE_TIMEOUT_MS,
    /** Hard cap, so a stuck microphone cannot hold the mic forever. */
    private val maxUtteranceMs: Long = DEFAULT_MAX_UTTERANCE_MS,
    /**
     * Speech must last this long before a silence ends the session.
     *
     * Without it, a cough or a door closing at the very start would be taken as
     * the whole utterance and the driver would be told "không nghe rõ" before
     * they had said anything.
     */
    private val minSpeechMs: Long = DEFAULT_MIN_SPEECH_MS,
    /** Silence before any speech at all that ends in NO_SPEECH. */
    private val noSpeechTimeoutMs: Long = DEFAULT_NO_SPEECH_TIMEOUT_MS
) {

    /** What the capture loop should do after this frame. */
    enum class Verdict {
        /** Keep reading. */
        CONTINUE,

        /**
         * The utterance ended because the driver stopped talking.
         * [speechHeard] says whether anything was ever said, which is the
         * difference between `NO_MATCH` and `NO_SPEECH`.
         */
        END_SILENCE,

        /** The hard cap was reached. Decode what we have. */
        END_MAX,

        /** Nothing was said at all before [noSpeechTimeoutMs]. */
        END_NO_SPEECH
    }

    /** True once a frame has been classified as speech. */
    var speechHeard: Boolean = false
        private set

    /** Peak absolute sample seen, for the diagnostics record. */
    var peakSample: Int = 0
        private set

    /** How many frames have been classified, for the diagnostics record. */
    var framesSeen: Int = 0
        private set

    private var totalSpeechMs: Long = 0L
    private var lastSpeechAtMs: Long = 0L
    private var noiseFloor: Double = INITIAL_FLOOR

    /** Frame length in samples implied by [sampleRateHz] and the 20 ms window. */
    val frameSamples: Int = (sampleRateHz * FRAME_MS / 1000).coerceAtLeast(1)

    /**
     * Classifies one frame.
     *
     * @param frame PCM-16 samples, at most [frameSamples] long.
     * @param elapsedMs milliseconds since the microphone opened, from a
     *        monotonic clock. Passed in rather than read so the state machine
     *        can be driven at any speed, and so a clock that jumps is the
     *        caller's bug rather than a hidden one.
     */
    fun accept(frame: ShortArray, elapsedMs: Long): Verdict {
        framesSeen++
        val level = rms(frame)
        if (level > peakSample) peakSample = level.toInt()

        val isSpeech = level > threshold()
        if (isSpeech) {
            speechHeard = true
            lastSpeechAtMs = elapsedMs
            totalSpeechMs += FRAME_MS
        } else if (!speechHeard) {
            // Track the floor only before the first word: after that the
            // "quiet" frames are the driver's own pauses, and letting them pull
            // the floor down would make the threshold drop into the noise.
            noiseFloor += (level - noiseFloor) * FLOOR_ADAPT_RATE
        }

        if (elapsedMs >= maxUtteranceMs) return Verdict.END_MAX
        if (!speechHeard) {
            return if (elapsedMs >= noSpeechTimeoutMs) Verdict.END_NO_SPEECH else Verdict.CONTINUE
        }

        val silentForMs = elapsedMs - lastSpeechAtMs
        if (totalSpeechMs < minSpeechMs) {
            // A cough, a door, a horn: enough to trip the classifier and not
            // enough to be a command. It gets the ordinary silence budget rather
            // than the "said nothing at all" one: the driver did make a sound,
            // so 1.5 s of quiet after it is a fair read, and using the longer
            // first-word budget here would hold the microphone for six seconds
            // after one cough.
            return if (silentForMs >= silenceTimeoutMs) Verdict.END_NO_SPEECH else Verdict.CONTINUE
        }
        return if (silentForMs >= silenceTimeoutMs) Verdict.END_SILENCE else Verdict.CONTINUE
    }

    /** True when this frame's level is above the current speech threshold. */
    fun isSpeech(frame: ShortArray): Boolean = rms(frame) > threshold()

    /** The level above which a frame counts as speech, for the log. */
    fun threshold(): Double = (noiseFloor * SPEECH_RATIO).coerceIn(MIN_THRESHOLD, maxNoiseFloor * SPEECH_RATIO)

    private fun rms(frame: ShortArray): Double {
        if (frame.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in frame) {
            val v = sample.toDouble()
            sum += v * v
        }
        return kotlin.math.sqrt(sum / frame.size)
    }

    /**
     * The highest the waking noise floor may drift to.
     *
     * Starts low so the very first frames are judged on their own merits rather
     * than against a guess, and is capped so a genuinely loud cabin cannot push
     * the threshold above a normal speaking voice.
     */
    private val maxNoiseFloor: Double = MAX_NOISE_FLOOR

    companion object {
        const val SAMPLE_RATE_HZ = 16_000

        /**
         * 20 ms. Short enough that endpointing is within one frame of the
         * driver stopping, long enough that the RMS is stable across a vowel.
         */
        const val FRAME_MS = 20

        const val DEFAULT_SILENCE_TIMEOUT_MS = 1_500L
        const val DEFAULT_MAX_UTTERANCE_MS = 15_000L
        const val DEFAULT_MIN_SPEECH_MS = 300L

        /**
         * The driver pressed the button and said nothing.
         *
         * **Six seconds, up from 2.5.** The TS8 field log settled this: sixteen
         * presses in a row ended `NO_SPEECH` at 2.5–2.7 s, and the single one
         * that recognised anything was the one where the driver started
         * speaking immediately (final at 6.1 s, "tới apollo"). Two and a half
         * seconds is shorter than the interaction it is measuring — a beep, a
         * spoken "Xin mời nói", and a breath before the first word — so the
         * microphone was closing before the driver had begun. Six seconds still
         * leaves the overlay closing itself well inside the eight-second budget
         * the router uses for an unanswered prompt.
         */
        const val DEFAULT_NO_SPEECH_TIMEOUT_MS = 6_000L

        /** Comfortably above the quantisation floor of a muted microphone. */
        private const val INITIAL_FLOOR = 120.0

        /**
         * Six times the floor. Speech against a quiet cabin is 10–30× the
         * noise; six leaves room for a soft voice without firing on tyre noise.
         */
        private const val SPEECH_RATIO = 6.0

        private const val MIN_THRESHOLD = 350.0

        /**
         * 16-bit samples: 3000 RMS is about −21 dBFS, already a loud cabin.
         * Past this the noise floor stops adapting, so the threshold cannot
         * climb out of the range of a speaking voice.
         */
        private const val MAX_NOISE_FLOOR = 3000.0

        private const val FLOOR_ADAPT_RATE = 0.05
    }
}

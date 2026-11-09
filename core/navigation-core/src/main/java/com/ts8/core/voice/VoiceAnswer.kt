package com.ts8.core.voice

/**
 * The two spoken answers a confirmation prompt accepts.
 *
 * **Why this is a separate parser and not part of [IntentParser].** The two
 * questions are different. [IntentParser] turns a sentence into an *action*, and
 * its whole design is that an unrecognised sentence produces no action at all.
 * This one runs only while the router is in [VoiceState.CONFIRMING], where the
 * only thing the driver can be saying is yes or no — and where "mở bản đồ" must
 * not be read as an action, because the prompt is still waiting for an answer.
 * Folding them into one entry point would make the confirmation state reachable
 * from an ordinary transcript, which is the bug the state exists to prevent.
 *
 * **Why matching is exact after folding, and not a prefix test.** "không đồng ý"
 * begins with a yes-word's syllables; a prefix match would run the command the
 * driver just refused. So the whole folded utterance has to be one of the listed
 * forms, and the negative list is checked first. Anything else returns null, and
 * the caller keeps the prompt open — a prompt that ignores an unclear answer is
 * safe, one that guesses is not.
 *
 * **Why the accent-free spellings are here too.** The platform recogniser on a
 * firmware with no Vietnamese pack returns `dong y`, and the Vosk model returns
 * `đồng ý`; [IntentParser.fold] makes both `dong y`, so one table serves the two
 * backends. The reverse is not claimed: an English acoustic model does not
 * transcribe Vietnamese reliably, and nothing here pretends it does.
 */
enum class VoiceAnswer {
    /** Go ahead: "đồng ý", "vâng", "ừ" … */
    YES,

    /** Do not go ahead: "huỷ", "không", "thôi" … */
    NO;

    companion object {

        /**
         * Whole folded utterances that mean yes.
         *
         * Kept to forms that are unambiguous in Vietnamese: `dung` alone is
         * excluded because it folds both "đúng" and "dừng", and answering a
         * confirmation prompt with the wrong one of those is exactly the
         * mis-fire a prompt exists to avoid.
         */
        private val YES_FORMS = setOf(
            "dong y",
            "vang",
            "u",
            "duoc",
            "phai",
            "ok",
            "o ke"
        )

        /**
         * Whole folded utterances that mean no. Checked before [YES_FORMS]
         * because "khong dong y" contains the yes phrase.
         */
        private val NO_FORMS = setOf(
            "huy",
            "huy bo",
            "huy lenh",
            "khong",
            "khong dong y",
            "khong can",
            "khong duoc",
            "thoi"
        )

        /**
         * The answer [transcript] is, or null when it is not an answer.
         *
         * Folds first so both spellings reach the same table; the transcript is
         * never stored or logged — it is an answer to a question that is about
         * to disappear.
         */
        fun parse(transcript: String?): VoiceAnswer? {
            val folded = IntentParser.fold(transcript)
            if (folded.isEmpty()) return null
            return when {
                folded in NO_FORMS -> NO
                folded in YES_FORMS -> YES
                else -> null
            }
        }
    }
}

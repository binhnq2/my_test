package com.ts8.core.voice.offline

import com.ts8.core.voice.IntentParser
import com.ts8.core.voice.VoiceCommand

/**
 * Builds the constrained vocabulary Vosk recognises, from the command table the
 * parser already owns.
 *
 * **Why this is a table and not a second parser.** §6 asks for a limited
 * grammar for the fixed commands and for place names to be drawn from the
 * active `places.db`. The phrases below are written out by hand; what they must
 * not do is acquire a *meaning* of their own, which would make them a second
 * command table — the exact thing the brief forbids when it says `IntentParser`
 * must be reused rather than a second parser written. So every phrase here is
 * checked against the *same* [IntentParser]: `CommandGrammarTest` asserts
 * `SelfCheck.disagreements()` is empty, and a drift fails that gate.
 *
 * **Why a constrained grammar at all.** A small Kaldi model decoding free
 * Vietnamese on a Cortex-A7 spends its accuracy budget on words the launcher
 * will never act on. Restricting the decoder to the phrases it can execute
 * turns a 15.7 % word error rate into something usable for commands, and it is
 * also what keeps memory and latency bounded: the search space is hundreds of
 * phrases, not the model's full vocabulary.
 *
 * **Why place names are capped.** A grammar of every POI in a province would be
 * tens of thousands of entries, and Vosk builds a decoding graph for all of
 * them at construction time — which on this CPU is measured in seconds and
 * hundreds of megabytes, on the same heap the map is using. [DEFAULT_POI_CAP]
 * keeps the live grammar at a size the device can afford; the cap is stated in
 * the diagnostics rather than applied silently.
 *
 * **What it does not do.** It does not guess at a place. A name the grammar does
 * not contain simply never comes out of the recogniser, which is honest — the
 * driver is told "không nghe rõ" instead of being sent somewhere approximate.
 */
object CommandGrammar {

    /**
     * Enough popular places to cover a city's landmarks without building a
     * graph the TS8 cannot decode. 200 phrases is roughly a 1–2 MB graph delta
     * for the small Vietnamese model, which is the budget this batch targets.
     */
    const val DEFAULT_POI_CAP = 200

    /**
     * The fixed phrases, as Vietnamese the driver would say them.
     *
     * Written with diacritics because the acoustic model is Vietnamese and
     * expects them; the parser folds them away afterwards, which is what lets
     * the same list serve `mở bản đồ` and `mo ban do` alike. Each entry must
     * parse to the command on its right — [SelfCheck] proves it.
     */
    val FIXED: List<Pair<String, VoiceCommand>> = listOf(
        "mở bản đồ" to VoiceCommand.OpenMap,
        "trang chủ" to VoiceCommand.GoHome,
        "mở nhạc" to VoiceCommand.OpenMusic,
        "phát nhạc" to VoiceCommand.PlayMusic,
        "tạm dừng" to VoiceCommand.PauseMusic,
        "dừng nhạc" to VoiceCommand.PauseMusic,
        "bài tiếp theo" to VoiceCommand.NextTrack,
        "chuyển bài" to VoiceCommand.NextTrack,
        "bài trước" to VoiceCommand.PreviousTrack,
        "quay lại bài" to VoiceCommand.PreviousTrack,
        "tăng âm lượng" to VoiceCommand.VolumeUp,
        "giảm âm lượng" to VoiceCommand.VolumeDown,
        "mở điện thoại" to VoiceCommand.OpenPhone,
        "gọi điện" to VoiceCommand.OpenPhone
    )

    /**
     * The phrases a confirmation prompt can be answered with (§9).
     *
     * **Why this is a second, tiny grammar rather than entries in [FIXED].**
     * The command grammar is built once per press and decoded against the
     * driver's whole request; these phrases are recognised in a different
     * moment — after the question has been spoken, when the only legal
     * utterance is yes or no. Putting them in [FIXED] would also break
     * [SelfCheck]'s contract, which says every entry there parses to a command:
     * "đồng ý" deliberately parses to nothing.
     *
     * Every spelling here must be one [com.ts8.core.voice.VoiceAnswer] accepts
     * after folding, and [SelfCheck] proves that — a phrase the decoder can
     * produce and the answer table cannot read would leave the prompt open
     * while the driver believes they answered it.
     */
    val ANSWERS: List<String> = listOf(
        "đồng ý",
        "vâng",
        "ừ",
        "được",
        "phải",
        "ok",
        "ô kê",
        "huỷ",
        "huỷ bỏ",
        "huỷ lệnh",
        "không",
        "không đồng ý",
        "không cần",
        "không được",
        "thôi"
    )

    /** Renders [ANSWERS] as the JSON array Vosk expects. */
    fun answerGrammar(): String =
        ANSWERS.joinToString(prefix = "[", postfix = "]", separator = ",\n  ") {
            "\"" + it.replace("\"", "\\\"") + "\""
        }

    /** The templates that carry a place: `tìm …`, `dẫn đường …`. */
    const val SEARCH_PREFIX = "tìm"

    /**
     * The navigation template, which *must* be followed by a connector word
     * that [IntentParser] knows.
     *
     * `dẫn đường tới …` folds to `dan duong toi …`, and `IntentParser` requires
     * one of `toi` / `den` / `di` / `ve` between the trigger and the place. Emit
     * `dẫn đường bến thành` and the folded text is `dan duong ben thanh`, the
     * connector check leaves an empty destination, and the parser refuses a
     * phrase the grammar put there — the exact drift [SelfCheck] exists to
     * catch. Verified against the parser rather than assumed.
     */
    const val NAVIGATE_PREFIX = "dẫn đường tới"

    /** The template that carries an app name. */
    const val APP_PREFIX = "mở ứng dụng"

    /**
     * Builds the JSON array Vosk takes, for the fixed commands plus [places].
     *
     * @param places display names of popular POIs, already in Vietnamese. They
     *        are inserted verbatim: the grammar must contain what the acoustic
     *        model will produce, and re-folding them here would ask the decoder
     *        to match a spelling nobody says.
     */
    fun build(places: List<String> = emptyList(), poiCap: Int = DEFAULT_POI_CAP): String {
        val phrases = LinkedHashSet<String>(64)
        FIXED.forEach { phrases += it.first }

        for (place in usablePlaces(places, poiCap)) {
            phrases += "$SEARCH_PREFIX $place"
            phrases += "$NAVIGATE_PREFIX $place"
        }
        // The bare prefixes are included so a driver who pauses after "tìm" is
        // not decoded as a different, coincidentally similar phrase. In
        // practice the endpointing rule ends the utterance first; this is the
        // belt-and-braces case and costs two entries.
        phrases += SEARCH_PREFIX
        phrases += NAVIGATE_PREFIX

        return phrases.joinToString(prefix = "[", postfix = "]", separator = ",\n  ") {
            "\"" + it.replace("\"", "\\\"") + "\""
        }
    }

    /**
     * How many phrases [build] would emit for [places], without building it.
     *
     * The diagnostics record the number so a driver reporting "it is slow" can
     * be asked how many places their pack contributed. It runs the same
     * [usablePlaces] pass as [build], so the number quoted in the log is the
     * number of entries actually handed to the decoder — a count computed by a
     * second, slightly different filter would read as a lie the first time a
     * place name was dropped.
     */
    fun phraseCount(places: List<String>, poiCap: Int = DEFAULT_POI_CAP): Int =
        FIXED.size + 2 + usablePlaces(places, poiCap).size * 2

    /**
     * The places that will actually reach the grammar.
     *
     * Everything that would corrupt the JSON array or decode to nothing is
     * dropped *before* the cap is applied, so a pack with 200 unquotable
     * entries does not consume the budget and leave the real landmarks out.
     */
    private fun usablePlaces(places: List<String>, poiCap: Int): List<String> =
        places.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            // A place name containing a quote or a backslash would break the
            // JSON array; it cannot be spoken into the grammar usefully either.
            .filter { !it.contains('"') && !it.contains('\\') }
            // A control character reaches the decoder as an unspoken token.
            .filter { ch -> ch.none { it.isISOControl() } }
            .distinct()
            .take(poiCap.coerceAtLeast(0))
            .toList()

    /**
     * Proves every generated phrase parses back to the command it came from.
     *
     * **Why this is in `main` and not only in the tests.** The grammar and the
     * parser are two hand-maintained lists that must agree, and the failure is
     * silent: a phrase in the grammar that the parser does not recognise makes
     * the recogniser produce a sentence the launcher answers with "không hiểu
     * lệnh", which reads as a recognition bug rather than a table that drifted.
     * A unit test covers it, but keeping the check callable lets the Settings
     * diagnostics run it on the shipping build and report the same answer.
     */
    object SelfCheck {

        /** Every pair that disagreed, as `phrase -> expected, got`. */
        fun disagreements(): List<String> {
            val out = ArrayList<String>(4)
            for ((phrase, expected) in FIXED) {
                val parsed = IntentParser.parse(phrase)
                if (parsed !is IntentParser.ParseResult.Command) {
                    out += "$phrase -> không parse được ($parsed)"
                    continue
                }
                if (parsed.command != expected) {
                    out += "$phrase -> ${parsed.command}, cần $expected"
                }
            }
            // The argument-carrying templates are checked against a sample name,
            // because the parser's behaviour with an argument is what a POI
            // phrase will exercise.
            for ((prefix, expected) in listOf(
                SEARCH_PREFIX to { c: VoiceCommand -> c is VoiceCommand.SearchPlace },
                NAVIGATE_PREFIX to { c: VoiceCommand -> c is VoiceCommand.NavigateTo }
            )) {
                val sample = "$prefix bến thành"
                val parsed = IntentParser.parse(sample)
                val command = (parsed as? IntentParser.ParseResult.Command)?.command
                if (command == null || !expected(command)) {
                    out += "$sample -> không parse thành đúng loại lệnh (${parsed})"
                }
            }
            val appSample = "$APP_PREFIX nhạc"
            val appParsed = IntentParser.parse(appSample)
            if (appParsed !is IntentParser.ParseResult.Command) {
                out += "$appSample -> không parse được ($appParsed)"
            }

            // Every phrase the answer decoder can produce must be readable by
            // the answer table. A phrase here that parses to neither yes nor no
            // would make the recogniser return a sentence the prompt silently
            // ignores — the driver answers, nothing happens, and the prompt
            // expires as if they had said nothing.
            for (phrase in ANSWERS) {
                if (com.ts8.core.voice.VoiceAnswer.parse(phrase) == null) {
                    out += "$phrase -> không parse thành câu trả lời"
                }
            }
            // And no answer phrase may be an executable command: "dừng" would
            // otherwise be both "no" and "pause the music".
            for (phrase in ANSWERS) {
                if (IntentParser.parse(phrase) is IntentParser.ParseResult.Command) {
                    out += "$phrase -> vừa là câu trả lời vừa là lệnh"
                }
            }
            return out
        }

        /** True when the grammar and the parser agree on every entry. */
        fun ok(): Boolean = disagreements().isEmpty()
    }
}

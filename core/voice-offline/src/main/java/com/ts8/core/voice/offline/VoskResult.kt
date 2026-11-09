package com.ts8.core.voice.offline

/**
 * Reads the JSON strings Vosk returns, and nothing else.
 *
 * **Why this is its own class with its own tests.** Vosk answers in two shapes
 * depending on how the recogniser was configured — `{"text":"…"}` with the
 * default single alternative, and `{"alternatives":[{"text":…,"confidence":…}]}`
 * once `setMaxAlternatives` is used — and the second is the shape the
 * controller's hypothesis preference depends on. It also returns `text` as an
 * **empty string** for a silent utterance, which is a `NO_MATCH` and not a
 * transcript of length zero. Every one of those is a branch a native callback
 * cannot be asked to reproduce on this build host, so the parsing is pure,
 * lives here, and is asserted directly.
 *
 * **What it deliberately does not do.** It does not check spelling, fold
 * diacritics or repair words. Vosk returns what its Vietnamese acoustic model
 * heard; [com.ts8.core.voice.IntentParser] is the one that decides what a
 * sentence means, and a second, "helpful" normalisation pass here is how two
 * engines end up with two vocabularies.
 */
object VoskResult {

    /**
     * One decoded result.
     *
     * [hypotheses] is best-first and never empty for a *final* result; an empty
     * list means the engine produced nothing usable, which the caller reports
     * as `NO_MATCH`. [confidence] is null when the engine did not report one —
     * the single-alternative shape carries no score at all, and fabricating one
     * would make a wrong transcript look certain.
     */
    data class Decoded(
        val hypotheses: List<String>,
        val confidence: Float?
    ) {
        val isEmpty: Boolean get() = hypotheses.isEmpty()

        /** The best hypothesis, or the empty string when there was none. */
        val text: String get() = hypotheses.firstOrNull().orEmpty()
    }

    val EMPTY = Decoded(emptyList(), null)

    /**
     * Parses [json] — a `getPartialResult`/`getResult`/`getFinalResult` string.
     *
     * Never throws: a native string this class cannot read is a result the
     * caller must report as `NO_MATCH`, not a crash inside the microphone loop.
     * That matters more than it looks — the loop runs on its own thread, and an
     * exception there would leave `AudioRecord` open.
     */
    fun parse(json: String?): Decoded {
        if (json.isNullOrBlank()) return EMPTY
        val root = runCatching { MiniJson.parse(json) }.getOrNull() ?: return EMPTY

        // Shape 2: alternatives, best first, with optional confidence.
        val alternatives = (root as? MiniJson.Value.Obj)?.entries?.get("alternatives")?.asArr()
        if (alternatives != null) {
            return fromAlternatives(alternatives)
        }

        // Shape 1: a bare text field.
        val text = (root as? MiniJson.Value.Obj)?.entries?.str("text")
        if (text != null) {
            val trimmed = text.trim()
            return if (trimmed.isEmpty()) EMPTY else Decoded(listOf(trimmed), null)
        }

        // A root array of `{text, confidence}` objects: accepted because some
        // Vosk builds emit the alternative list without the wrapper, and
        // refusing it would lose a transcript the engine did produce.
        if (root is MiniJson.Value.Arr) return fromAlternatives(root.items)

        return EMPTY
    }

    private fun fromAlternatives(items: List<MiniJson.Value>): Decoded {
        val texts = ArrayList<String>(items.size)
        var best: Float? = null
        for (item in items) {
            val entry = item.asObj() ?: continue
            val text = entry.str("text")?.trim().orEmpty()
            if (text.isEmpty()) continue
            if (best == null) best = entry["confidence"].asFloat()
            if (!texts.contains(text)) texts += text
        }
        if (texts.isEmpty()) return EMPTY
        return Decoded(texts, best)
    }
}

/** Vosk writes `confidence` as a JSON number; null when absent or not numeric. */
private fun MiniJson.Value?.asFloat(): Float? =
    (this as? MiniJson.Value.Num)?.value?.toFloat()

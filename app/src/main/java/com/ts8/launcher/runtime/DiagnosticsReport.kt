package com.ts8.launcher.runtime

/**
 * The exported diagnostics file's content (§11), as data.
 *
 * **Why a typed data class and not text built inside the Activity.** The one
 * thing §11 says about this file is that it must **not** carry credentials and
 * must not carry personal location data *by default*. The reliable way to
 * guarantee that is not a filter that strips known-bad words — a filter is a
 * list of the leaks someone already thought of — but a type with nowhere to put
 * them: there is no `password`, no `token`, no coordinate, and no free-form
 * blob that could acquire one later. Every field is a label, a version, a state
 * name or a count.
 *
 * **Why the labels are passed in.** They come from `strings.xml` through
 * [Labels], so the line the driver reads on the Chẩn đoán screen and the line
 * written into the file are the same string from the same resource. Two copies
 * of "Phiên bản" are two things to keep in step, and the file exists precisely
 * to be cross-checked against the screen.
 *
 * **Why it is JVM-testable.** No Android type appears in this file, so the
 * redaction rule can be exercised by a unit test that renders a report and
 * looks for the shapes a credential would take. A rule checked only by a person
 * reading a screenshot is a rule that regresses silently.
 */
data class DiagnosticsReport(
    /** Wall-clock stamp of the export, local time, second resolution. */
    val generatedAt: String,
    val appVersion: String,
    val buildHash: String,
    val platform: String,
    val mode: String,
    val navigation: String,
    val mapPack: String,
    val mapPhase: String,
    val music: String,
    val voice: String,
    val parking: String,
    val volume: String,
    /** One line per online provider: name plus configuration state, never its value. */
    val providers: List<String>,
    /**
     * The offline voice record (§11), one `voice.key=value` line each.
     *
     * Separate from [voice] rather than folded into it: the summary line is one
     * sentence a driver reads on screen, and these are twenty fields a support
     * call greps. Empty when no voice interaction has happened since the
     * process started, which is the honest answer rather than a block of
     * zeroes.
     */
    val voiceDetails: List<String> = emptyList(),
    val labels: Labels
) {

    /** The heading the export carries, mirroring the screen's own title. */
    data class Labels(
        val title: String,
        val generatedAt: String,
        val appVersion: String,
        val buildHash: String,
        val platform: String,
        val mode: String,
        val navigation: String,
        val mapPack: String,
        val mapPhase: String,
        val music: String,
        val voice: String,
        val parking: String,
        val volume: String,
        val providers: String,
        val footer: String
    )

    /** The whole file, including the header that says what it is. */
    fun render(): String = buildString {
        appendLine("${labels.title} — TS8 Launcher")
        appendLine("${labels.generatedAt}: $generatedAt")
        appendLine()
        appendLine("${labels.appVersion}: $appVersion")
        appendLine("${labels.buildHash}: $buildHash")
        appendLine("${labels.platform}: $platform")
        appendLine()
        appendLine("${labels.mode}: $mode")
        appendLine("${labels.navigation}: $navigation")
        appendLine()
        appendLine("${labels.mapPack}: $mapPack")
        appendLine("${labels.mapPhase}: $mapPhase")
        appendLine()
        appendLine("${labels.music}: $music")
        appendLine("${labels.voice}: $voice")
        appendLine("${labels.parking}: $parking")
        appendLine("${labels.volume}: $volume")
        if (providers.isNotEmpty()) {
            appendLine()
            appendLine("${labels.providers}:")
            for (line in providers) appendLine("  · $line")
        }
        // §11's per-interaction record. Deliberately after the provider block
        // and before the footer: it is the longest section, and a support call
        // reading the file top-down reaches the summary it needs first.
        if (voiceDetails.isNotEmpty()) {
            appendLine()
            appendLine("${labels.voice}:")
            for (line in voiceDetails) appendLine("  · $line")
        }
        appendLine()
        appendLine(labels.footer)
    }

    companion object {
        /** Used when a value could not be read; never an empty field. */
        const val NONE = "không có"
    }
}

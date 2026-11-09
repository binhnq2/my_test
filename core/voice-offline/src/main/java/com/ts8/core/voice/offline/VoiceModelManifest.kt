package com.ts8.core.voice.offline

/**
 * The `metadata.json` of a voice-model pack, and the rules that decide whether
 * a pack may be activated.
 *
 * **The pack is untrusted input.** It arrives from a USB stick, which is to say
 * from whatever the driver happened to copy onto it. It carries no executable
 * code — Vosk loads a Kaldi directory, not a `.so` — but it does carry the
 * weights the launcher will use to interpret speech, so a pack that validates
 * wrongly does not crash: it silently makes every command worse. That is why
 * every field below is checked and why the verdict is an enum rather than a
 * boolean: [VoiceModelState.INCOMPATIBLE] and [VoiceModelState.CORRUPT] send
 * the driver to different fixes.
 *
 * **Why the manifest is parsed here and not in the app module.** The rules —
 * schema version, ABI set, checksum shape, whether the declared size matches
 * what is on disk — are identical for the Settings screen, for the importer and
 * for a unit test. Putting them in the module that owns no Activity is what
 * lets the sandbox suite assert them without a device.
 */
data class VoiceModelManifest(
    val schemaVersion: Int,
    val engine: String,
    val locale: String,
    /** The pack's own version, e.g. `vosk-model-small-vn-0.4`. */
    val modelVersion: String,
    val minSdk: Int,
    val supportedAbis: List<String>,
    /** Total unpacked bytes the pack declares. Checked against the disk. */
    val size: Long,
    val sha256: String,
    val license: String,
    val licenseUrl: String?,
    val sourceUrl: String,
    /** `GRAMMAR` or `FREE`. Decides whether `command_grammar.json` is required. */
    val vocabularyMode: String,
    val description: String?,
    /**
     * Whether the pack ships `command_grammar.json`.
     *
     * Not a manifest field: it is discovered on disk by [VoiceModelValidator],
     * because a pack that declares `GRAMMAR` and then omits the file must
     * validate as CORRUPT rather than be trusted for the claim it made about
     * itself.
     */
    val hasGrammar: Boolean = false
) {

    /** True when a device with [sdk] and [abi] can run this pack. */
    fun supports(sdk: Int, abi: String): Boolean =
        sdk >= minSdk && supportedAbis.any { it == abi } && engine == ENGINE_VOSK

    companion object {
        /** The only engine this build can load. A second one is a later batch. */
        const val ENGINE_VOSK = "vosk"

        /** The only schema this build understands. */
        const val SUPPORTED_SCHEMA = 1

        /** Vietnamese; the only language the parser behind this recognises. */
        const val LOCALE_VI = "vi-VN"

        const val VOCABULARY_GRAMMAR = "GRAMMAR"
        const val VOCABULARY_FREE = "FREE"

        /** Lowercase hex, exactly 64 characters — SHA-256, and nothing else. */
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        /** ABIs the TS8 can run. Anything else is a pack for a different unit. */
        val ACCEPTED_ABIS = listOf("armeabi-v7a", "arm64-v8a")

        /**
         * Parses [json] into a manifest, or explains what is wrong.
         *
         * Every failure is a [ParseError] with a driver-safe [reason]; the
         * caller turns it into [VoiceModelState.CORRUPT] (unreadable document)
         * or [VoiceModelState.INCOMPATIBLE] (readable but not for this device).
         * Splitting those here rather than at the call site keeps the two
         * verdicts consistent between the Settings screen, the importer and the
         * test suite.
         */
        fun parse(json: String): Result<VoiceModelManifest> = runCatching {
            val root = MiniJson.parseObject(json)

            val schema = root.integer("schemaVersion")
                ?: fail("metadata.json thiếu schemaVersion")
            val engine = root.str("engine")?.trim().orEmpty()
            if (engine.isEmpty()) fail("metadata.json thiếu engine")

            val locale = root.str("locale")?.trim().orEmpty()
            if (locale.isEmpty()) fail("metadata.json thiếu locale")

            val modelVersion = root.str("modelVersion")?.trim().orEmpty()
            if (modelVersion.isEmpty()) fail("metadata.json thiếu modelVersion")

            val minSdk = root.integer("minSdk")
                ?: fail("metadata.json thiếu minSdk")

            val abis = root.strings("supportedAbis")
                ?: fail("metadata.json thiếu supportedAbis")

            val size = root.long("size")
                ?: fail("metadata.json thiếu size")
            if (size <= 0L) fail("size phải lớn hơn 0")

            val sha = root.str("sha256")?.trim()?.lowercase().orEmpty()
            if (!SHA256.matches(sha)) fail("sha256 không phải chuỗi hex 64 ký tự")

            val license = root.str("license")?.trim().orEmpty()
            if (license.isEmpty()) fail("metadata.json thiếu license")

            val sourceUrl = root.str("sourceUrl")?.trim().orEmpty()
            if (sourceUrl.isEmpty()) fail("metadata.json thiếu sourceUrl")

            val vocabulary = root.str("vocabularyMode")?.trim()?.uppercase()
                ?: VOCABULARY_GRAMMAR

            VoiceModelManifest(
                schemaVersion = schema,
                engine = engine,
                locale = locale,
                modelVersion = modelVersion,
                minSdk = minSdk,
                supportedAbis = abis.map { it.trim() }.filter { it.isNotEmpty() },
                size = size,
                sha256 = sha,
                license = license,
                licenseUrl = root.str("licenseUrl")?.takeIf { it.isNotBlank() },
                sourceUrl = sourceUrl,
                vocabularyMode = vocabulary,
                description = root.str("description")?.takeIf { it.isNotBlank() }
            )
        }

        private fun fail(message: String): Nothing = throw IllegalStateException(message)
    }
}

/** Everything that can be wrong with a pack, each with its own verdict. */
enum class VoiceModelState {
    /** No pack installed. The Settings CTA offers the import. */
    NOT_INSTALLED,

    /** Names hash and ABI are being checked. Transient, never persisted. */
    VALIDATING,

    /** Installed, checksummed, compatible. The recogniser may load it. */
    READY,

    /** Valid pack, wrong unit: schema, engine, locale, ABI or minSdk. */
    INCOMPATIBLE,

    /** Damaged or tampered: checksum mismatch, missing files, bad manifest. */
    CORRUPT,

    /** It looked READY but the native loader refused it. Needs a reinstall. */
    FAILED
}

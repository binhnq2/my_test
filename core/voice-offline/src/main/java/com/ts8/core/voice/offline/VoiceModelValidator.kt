package com.ts8.core.voice.offline

import java.io.File
import java.security.MessageDigest

/**
 * Decides whether a directory on storage is a voice-model pack this unit can
 * actually load.
 *
 * **The order of the checks is the design.** Cheap and *decisive* first, so a
 * pack for another engine or another architecture is rejected in milliseconds
 * rather than after hashing 33 MB:
 *
 * 1. `metadata.json` parses — a pack whose manifest is unreadable is CORRUPT.
 * 2. schema, engine, locale, ABI, minSdk — a valid pack for another unit is
 *    INCOMPATIBLE, and can never become READY by being copied again.
 * 3. the directory has the Kaldi layout Vosk needs — CORRUPT without it.
 * 4. the declared byte total matches what is on disk — CORRUPT.
 * 5. the checksum matches — CORRUPT.
 *
 * **Why the size check is separate from the checksum.** The checksum is over
 * the *contents*, and computing it means reading all 33 MB. The size is one
 * `sum` pass over the directory listing. A truncated copy from an interrupted
 * USB write fails the size check in a second, and telling the driver "the copy
 * is incomplete, copy it again" is more useful than a hex mismatch.
 *
 * **What it does not do.** It does not check that the model is accurate, or
 * that this exact pack is licensed for redistribution — that second one is a
 * build-time decision recorded in `docs/VOICE_MODEL_LICENSE.md`, and it cannot
 * be re-derived at run time from a file the importer itself supplied.
 */
class VoiceModelValidator(
    /**
     * Hash of one file, injectable so the rules can be exercised on 3-byte
     * fixtures instead of a 33 MB model.
     */
    private val hasher: (File) -> String = ::sha256Of) {

    /** The verdict, plus everything the Settings screen needs to explain it. */
    data class Report(
        val state: VoiceModelState,
        val manifest: VoiceModelManifest?,
        /** Driver-facing Vietnamese reason; null when [state] is READY. */
        val reason: String?,
        val directory: File,
        /** Total bytes of the pack's *payload*, which may differ from `size`. */
        val actualBytes: Long
    ) {
        val ok: Boolean get() = state == VoiceModelState.READY
    }

    /**
     * Validates [directory].
     *
     * @param deviceAbis the ABIs this build ships, in preference order. Passed
     *        in rather than read from `Build` so the check is testable; the app
     *        module supplies `Build.SUPPORTED_ABIS` reordered against
     *        [VoiceModelManifest.ACCEPTED_ABIS].
     */
    fun validate(
        directory: File,
        deviceSdk: Int,
        deviceAbis: List<String>
    ): Report {
        if (!directory.isDirectory) {
            return Report(VoiceModelState.NOT_INSTALLED, null, "Chưa có mô hình", directory, 0L)
        }

        val manifestFile = File(directory, MANIFEST_NAME)
        if (!manifestFile.isFile) {
            return corrupt(directory, 0L, "Thiếu metadata.json")
        }

        val text = runCatching { manifestFile.readText(Charsets.UTF_8) }.getOrElse {
            return corrupt(directory, 0L, "Không đọc được metadata.json")
        }

        val parsed = VoiceModelManifest.parse(text)
        val manifest = parsed.getOrElse { e ->
            return corrupt(directory, 0L, "metadata.json không hợp lệ: ${e.message}")
        }

        // -- the "wrong pack for this unit" family -------------------------
        if (manifest.schemaVersion != VoiceModelManifest.SUPPORTED_SCHEMA) {
            return incompatible(
                directory, manifest, 0L,
                "Bản metadata ${manifest.schemaVersion} không được hỗ trợ " +
                    "(bản này cần ${VoiceModelManifest.SUPPORTED_SCHEMA})"
            )
        }
        if (manifest.engine != VoiceModelManifest.ENGINE_VOSK) {
            return incompatible(
                directory, manifest, 0L,
                "Bộ nhận dạng '${manifest.engine}' chưa được hỗ trợ trên bản này"
            )
        }
        if (manifest.locale != VoiceModelManifest.LOCALE_VI) {
            // Not CORRUPT: the files are fine, it is simply a pack for another
            // language, and the parser behind this recogniser only knows
            // Vietnamese. Accepting it would produce confident nonsense.
            return incompatible(
                directory, manifest, 0L,
                "Mô hình dành cho '${manifest.locale}', bản này chỉ dùng " +
                    VoiceModelManifest.LOCALE_VI
            )
        }
        if (manifest.minSdk > deviceSdk) {
            return incompatible(
                directory, manifest, 0L,
                "Mô hình cần Android API ${manifest.minSdk}, thiết bị là API $deviceSdk"
            )
        }
        val runnableOn = manifest.supportedAbis.filter { abi ->
            abi in VoiceModelManifest.ACCEPTED_ABIS && deviceAbis.contains(abi)
        }
        if (runnableOn.isEmpty()) {
            return incompatible(
                directory, manifest, 0L,
                "Mô hình chỉ hỗ trợ ${manifest.supportedAbis.joinToString(", ")}; " +
                    "thiết bị chạy ${deviceAbis.joinToString(", ").ifEmpty { "không rõ" }}"
            )
        }

        // -- the "damaged pack" family -------------------------------------
        val modelDir = File(directory, MODEL_DIR)
        if (!modelDir.isDirectory) {
            return corrupt(directory, 0L, "Thiếu thư mục model/")
        }
        if (!File(modelDir, "am/final.mdl").isFile) {
            // The acoustic model. Without it Vosk's constructor throws deep
            // inside JNI, which reads as a crash rather than a bad pack.
            return corrupt(directory, 0L, "Mô hình thiếu am/final.mdl")
        }
        if (!File(modelDir, "graph/HCLr.fst").isFile || !File(modelDir, "graph/Gr.fst").isFile) {
            return corrupt(directory, 0L, "Mô hình thiếu graph/ (HCLr.fst, Gr.fst)")
        }

        val grammarFile = File(directory, GRAMMAR_NAME)
        val needsGrammar = manifest.vocabularyMode == VoiceModelManifest.VOCABULARY_GRAMMAR
        if (needsGrammar && !grammarFile.isFile) {
            return corrupt(
                directory, 0L,
                "metadata.json khai báo GRAMMAR nhưng thiếu $GRAMMAR_NAME"
            )
        }
        if (grammarFile.isFile) {
            val grammarProblem = checkGrammar(grammarFile)
            if (grammarProblem != null) return corrupt(directory, 0L, grammarProblem)
        }

        val payloadBytes = directory.payloadBytes()
        if (manifest.size > 0L && payloadBytes != manifest.size) {
            return corrupt(
                directory, payloadBytes,
                "Dung lượng không khớp: có $payloadBytes byte, metadata ghi ${manifest.size}"
            )
        }

        val checksumProblem = verifyChecksums(directory)
        if (checksumProblem != null) return corrupt(directory, payloadBytes, checksumProblem)

        return Report(VoiceModelState.READY, manifest, null, directory, payloadBytes)
    }

    /**
     * Checks `checksums.sha256` against the files it names.
     *
     * The format is the one `sha256sum` writes — `<hex>  <relative path>` — so a
     * pack can be produced with the standard tool and checked by hand. A line
     * naming a file that does not exist is a failure, not a skip: a checksum
     * file that only covers the files that happen to be present would validate
     * a pack from which the acoustic model had been deleted.
     *
     * @return a driver-facing problem, or null when every line matched.
     */
    private fun verifyChecksums(directory: File): String? {
        val file = File(directory, CHECKSUMS_NAME)
        if (!file.isFile) return "Thiếu $CHECKSUMS_NAME"
        val lines = runCatching { file.readLines() }.getOrElse {
            return "Không đọc được $CHECKSUMS_NAME"
        }
        if (lines.none { it.isNotBlank() && !it.trimStart().startsWith('#') }) {
            return "$CHECKSUMS_NAME rỗng"
        }

        var checked = 0
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) continue

            // Two spaces separate the hash from the name in sha256sum output,
            // but a name with a leading space is written with a `*` marker, and
            // some tools use a single space. Split on the first run of spaces.
            val split = line.indexOfFirst { it == ' ' || it == '\t' }
            if (split <= 0) return "$CHECKSUMS_NAME: dòng không hợp lệ: $line"
            val expected = line.substring(0, split).lowercase()
            if (!HEX64.matches(expected)) {
                return "$CHECKSUMS_NAME: hash không hợp lệ ở dòng: $line"
            }
            val name = line.substring(split).trimStart().removePrefix("*").replace('\\', '/')
            if (name.isEmpty()) return "$CHECKSUMS_NAME: thiếu tên file ở dòng: $line"

            val target = File(directory, name)
            val root = runCatching { directory.canonicalFile }.getOrDefault(directory)
            val canonical = runCatching { target.canonicalFile }.getOrNull()
                ?: return "$CHECKSUMS_NAME: đường dẫn không đọc được: $name"
            // A checksums line is untrusted text like every other part of the
            // pack; without this it could point at a file outside the pack and
            // make the launcher hash something it does not own.
            if (!canonical.path.startsWith(root.path + File.separator)) {
                return "$CHECKSUMS_NAME: đường dẫn ra ngoài thư mục mô hình: $name"
            }
            if (!canonical.isFile) return "$CHECKSUMS_NAME: thiếu file $name"

            val actual = runCatching { hasher(canonical) }.getOrElse {
                return "$CHECKSUMS_NAME: không đọc được $name"
            }
            if (!actual.equals(expected, ignoreCase = true)) {
                return "Sai checksum: $name"
            }
            checked++
        }
        return if (checked == 0) "checksums.sha256 không kiểm tra file nào" else null
    }

    /**
     * Checks that `command_grammar.json` is a non-empty list of strings.
     *
     * Vosk takes the grammar as one JSON array of phrases. An empty array makes
     * the recogniser return nothing at all — every command becomes NO_MATCH and
     * the feature looks broken while reporting a healthy model, which is why an
     * empty grammar is refused here rather than handed to the native side.
     */
    private fun checkGrammar(file: File): String? {
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrElse {
            return "Không đọc được $GRAMMAR_NAME"
        }
        val value = runCatching { MiniJson.parse(text) }.getOrElse {
            return "$GRAMMAR_NAME không phải JSON hợp lệ"
        }
        val phrases = when (value) {
            is MiniJson.Value.Arr -> value.items
            // Accepting an object with a "grammar" key would be a second format
            // to keep in sync; the one Vosk accepts is a bare array.
            is MiniJson.Value.Obj -> value.entries["grammar"]?.asArr()
                ?: return "$GRAMMAR_NAME phải là một mảng chuỗi"
            else -> return "$GRAMMAR_NAME phải là một mảng chuỗi"
        }
        if (phrases.isEmpty()) return "$GRAMMAR_NAME rỗng"
        if (phrases.any { it.asString()?.isNotBlank() != true }) {
            return "$GRAMMAR_NAME chứa phần tử không phải chuỗi"
        }
        return null
    }

    private fun corrupt(directory: File, bytes: Long, reason: String): Report =
        Report(VoiceModelState.CORRUPT, null, reason, directory, bytes)

    private fun incompatible(
        directory: File,
        manifest: VoiceModelManifest,
        bytes: Long,
        reason: String
    ): Report = Report(VoiceModelState.INCOMPATIBLE, manifest, reason, directory, bytes)

    companion object {
        const val MANIFEST_NAME = "metadata.json"
        const val CHECKSUMS_NAME = "checksums.sha256"
        const val GRAMMAR_NAME = "command_grammar.json"
        const val LICENSES_NAME = "LICENSES.md"
        const val MODEL_DIR = "model"

        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * The pack's payload size: every regular file except the two that describe the
 * pack rather than belong to it.
 *
 * **Why the manifest and the checksum file are excluded.** They cannot be part
 * of the number they declare: `size` lives in `metadata.json`, so counting
 * `metadata.json` makes the declared value depend on its own digit count, and
 * `checksums.sha256` cannot contain its own hash. Summing the whole directory
 * therefore produces a mismatch on *every* well-formed pack — the check would
 * fail universally, which in practice means it gets relaxed until it passes
 * nothing. Excluding the two descriptor files leaves a number that is stable
 * when only the descriptors change and that still catches a truncated model,
 * which is what the check is for.
 */
fun File.payloadBytes(): Long {
    var total = 0L
    val stack = ArrayDeque<File>()
    stack.addLast(this)
    while (stack.isNotEmpty()) {
        val dir = stack.removeLast()
        val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
        for (child in children) {
            when {
                child.isDirectory -> stack.addLast(child)
                child.isFile ->
                    if (!child.isPackDescriptor()) {
                        total += runCatching { child.length() }.getOrDefault(0L)
                    }
            }
        }
    }
    return total
}

private fun File.isPackDescriptor(): Boolean =
    name == VoiceModelValidator.MANIFEST_NAME ||
        name == VoiceModelValidator.CHECKSUMS_NAME

/**
 * Every regular file under [this], summed. Symlinks are not followed.
 *
 * File-level rather than a member so the pack importer, the Settings screen
 * and the tests all measure a directory the same way — three copies of a
 * recursive sum is three chances to disagree about what counts.
 */
fun File.totalBytes(): Long {
    var total = 0L
    val stack = ArrayDeque<File>()
    stack.addLast(this)
    while (stack.isNotEmpty()) {
        val dir = stack.removeLast()
        val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
        for (child in children) {
            when {
                child.isDirectory -> stack.addLast(child)
                child.isFile -> total += runCatching { child.length() }.getOrDefault(0L)
            }
        }
    }
    return total
}

/**
 * Lowercase hex SHA-256 of one file, read in 64 KiB blocks.
 *
 * Blocked rather than `readBytes()` because the model's largest file is 25 MB
 * and the TS8 is a low-RAM unit: a second 25 MB allocation on the same heap
 * the map renderer is using is how a background validation turns into an OOM.
 */
fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    file.inputStream().use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

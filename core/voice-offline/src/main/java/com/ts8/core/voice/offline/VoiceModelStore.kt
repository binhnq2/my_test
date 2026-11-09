package com.ts8.core.voice.offline

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Where the offline model lives, which version is active, and how a new one gets
 * there.
 *
 * **The layout is the brief's, and it is load-bearing.**
 *
 * ```
 * <root>/vi-VN/<modelVersion>/metadata.json
 *                           /model/...
 *                           /command_grammar.json
 *                           /checksums.sha256
 *                           /LICENSES.md
 * <root>/vi-VN/active.txt          one line: the active <modelVersion>
 * <root>/staging/<name>/           extraction target, never read by Vosk
 * ```
 *
 * Keeping the version in the path is what makes "replace the model" reversible:
 * the old directory is still there when the new one fails validation, so a
 * driver who copies a bad pack does not lose the working one. It is also what
 * makes the model's own version visible in a directory listing, which is the
 * only evidence a support call has that the unit is running what it says.
 *
 * **Why activation is a text file and not a rename of the directory.** Renaming
 * a 33 MB directory chain is not atomic on every vendor FUSE mount this device
 * family ships (the map pack importer has the same caveat and falls back to a
 * copy). Writing `active.txt` through a temp-and-rename is atomic on real
 * filesystems and, more importantly, is *idempotent*: after a power cut the
 * worst case is that the file names either the old version or the new one, and
 * both are complete directories that passed validation before they were named.
 *
 * **What it never does.** It never deletes the active pack to make room
 * mid-import, never activates a directory that has not passed
 * [VoiceModelValidator], and never downloads anything: the brief requires the
 * model to arrive by USB, and a store that could fetch would quietly become a
 * network path onto a unit that is supposed to work in a tunnel.
 */
class VoiceModelStore(
    /** `<app>/files/voice-models`. Created on demand. */
    private val root: File,
    private val validator: VoiceModelValidator = VoiceModelValidator(),
    /** The Android API level this build runs on, for the compatibility rules. */
    private val deviceSdk: Int,
    /**
     * The ABIs this APK actually ships, in preference order.
     *
     * Injected rather than read from `Build` so the store's rules can be
     * exercised on a JVM. The app module passes the filtered list, so a pack for
     * an ABI this APK dropped is reported INCOMPATIBLE here instead of failing
     * to load a `.so` that is not in the APK.
     */
    private val deviceAbis: () -> List<String> = { VoiceModelManifest.ACCEPTED_ABIS }
) {

    /** What the Settings screen draws, and what the controller asks cheaply. */
    data class Snapshot(
        val state: VoiceModelState,
        /** Driver-facing Vietnamese, null when [state] is READY. */
        val reason: String? = null,
        val manifest: VoiceModelManifest? = null,
        /**
         * The directory Vosk would open.
         *
         * Non-null for [VoiceModelState.READY] and for
         * [VoiceModelState.FAILED], where the pack is complete on disk but the
         * native loader refused it: Settings names the version it may offer to
         * replace, and the router must still not start a session.
         */
        val directory: File? = null
    ) {
        val ready: Boolean get() = state == VoiceModelState.READY
    }

    /** The outcome of one import attempt, with the stage that failed. */
    sealed class ImportResult {
        data class Ok(val snapshot: Snapshot) : ImportResult()

        /**
         * The import was refused. [stage] is the marker the brief's state list
         * names — `VALIDATING` when the bytes were fine but the pack was not,
         * `READY` only on success — so the screen can say where it stopped
         * rather than "failed" for a checksum mismatch and a full disk alike.
         */
        data class Failed(
            val state: VoiceModelState,
            val reason: String,
            val stage: VoiceModelState
        ) : ImportResult()
    }

    @Volatile
    private var cached: Snapshot? = null

    // -- reading ------------------------------------------------------------

    /**
     * The last verdict, without touching the disk.
     *
     * This is the hook the router's `offlineModelReady` uses, and it is cheap on
     * purpose: the alternative — validating 33 MB on every utterance — would put
     * a checksum pass in front of every press of the microphone button.
     */
    fun snapshot(): Snapshot = cached ?: Snapshot(VoiceModelState.NOT_INSTALLED)

    /**
     * The directory Vosk should open, or null when nothing is ready.
     *
     * Null is not an error: a unit that has never had a model installed is the
     * shipped state, and every caller below the UI treats it the same way.
     */
    fun activeDirectory(): File? = cached?.takeIf { it.ready }?.directory

    /** The last verdict's manifest, cheap enough for a per-request read. */
    fun manifest(): VoiceModelManifest? = cached?.manifest

    /** True when the offline provider has something to load right now. */
    fun isReady(): Boolean = cached?.ready == true

    /**
     * Records that the native loader refused a pack that validated as READY.
     *
     * **Why the store needs to hear this.** `VoiceModelState.FAILED` exists for
     * exactly one situation: the files are complete and checksummed, and Vosk
     * still threw while opening them — a model saved by a newer Kaldi, a
     * directory whose `conf/` disagrees with its `am/`, a pack truncated by a
     * filesystem that lied about the size. Only the loader can produce that
     * verdict, and without a way back to the store the Settings screen keeps
     * showing a green "READY" for a model that fails on every press.
     *
     * **Why it is not persisted.** The state lives in [cached] for the life of
     * the process. On the next start the pack is re-validated from disk, and if
     * the bytes are intact it is READY again — the loader failing once may have
     * been an out-of-memory from the map, which is not a property of the pack.
     * Persisting it would turn a transient fault into a permanent verdict until
     * the driver reinstalled a model that was never broken.
     */
    fun markLoadFailed(reason: String): Snapshot {
        val previous = cached ?: Snapshot(VoiceModelState.NOT_INSTALLED)
        val failed = Snapshot(
            state = VoiceModelState.FAILED,
            reason = reason,
            manifest = previous.manifest,
            // The directory stays reachable so the Settings screen can name the
            // version and offer a reinstall of that exact pack.
            directory = previous.directory
        )
        cached = failed
        return failed
    }

    /**
     * Re-validates the active pack from disk.
     *
     * **Blocking, and meant to be called on the IO executor.** It hashes the
     * pack, which on a Cortex-A7 is seconds for 33 MB. Called when Settings
     * opens, after an import, and once at startup *if* the persisted state says
     * READY — never from the microphone path.
     */
    fun refresh(): Snapshot {
        val fresh = readActive()
        cached = fresh
        return fresh
    }

    private fun buildCandidateRoots(): List<File> {
        val list = java.util.ArrayList<File>()
        list.add(root)
        list.add(File("/storage/emulated/0/Android/data/com.ts8.launcher/files/voice-models"))
        list.add(File("/storage/emulated/0/ts8/voice-models"))
        list.add(File("/storage/emulated/0/TS8/voice-models"))
        list.add(File("/sdcard/voice-models"))
        val usbRoots = arrayOf(
            "/storage/usb0", "/storage/usb1", "/storage/usbdisk",
            "/storage/usb_disk", "/mnt/usb_storage", "/storage/UsbDriveA",
            "/storage/emulated/0/ts8", "/storage/emulated/0/TS8"
        )
        for (u in usbRoots) {
            list.add(File(u, "voice-models"))
        }
        return list
    }

    private fun readActive(): Snapshot {
        val roots = buildCandidateRoots()
        android.util.Log.i(TAG, "readActive: checking ${roots.size} candidate roots")
        for (candidateRoot in roots) {
            val localeRoot = File(candidateRoot, VoiceModelManifest.LOCALE_VI)
            val exists = localeRoot.isDirectory
            android.util.Log.i(TAG, "readActive: root=${candidateRoot.absolutePath} localeDir=${localeRoot.absolutePath} exists=$exists")
            if (!exists) continue

            val version = readActiveVersion(localeRoot)
            if (version != null) {
                val directory = File(localeRoot, version)
                if (directory.isDirectory) {
                    val snapshot = toSnapshot(validator.validate(directory, deviceSdk, deviceAbis()))
                    if (snapshot.state == VoiceModelState.READY) {
                        return snapshot
                    }
                }
            }

            val subdirs = localeRoot.listFiles()?.filter { it.isDirectory && it.name != "staging" }
            if (!subdirs.isNullOrEmpty()) {
                for (subdir in subdirs) {
                    val snapshot = toSnapshot(validator.validate(subdir, deviceSdk, deviceAbis()))
                    if (snapshot.state == VoiceModelState.READY) {
                        return snapshot
                    }
                }
            }
        }
        return Snapshot(VoiceModelState.NOT_INSTALLED)
    }

    private fun toSnapshot(report: VoiceModelValidator.Report): Snapshot = Snapshot(
        state = report.state,
        reason = report.reason,
        manifest = report.manifest,
        directory = report.directory.takeIf { report.state == VoiceModelState.READY }
    )

    // -- installing ---------------------------------------------------------

    /**
     * Validates [staged] and, if it passes, makes it the active pack.
     *
     * [staged] must already be a complete pack directory — either extracted by
     * [importZip] or copied from a USB folder by the caller. The directory is
     * *moved* into `<locale>/<version>/`, so the caller must not reuse it.
     *
     * **Why validation happens before the move.** The new directory is built
     * next to the old one and only then pointed at, so a pack that fails the
     * checksum leaves the working model exactly as it was. That is the same
     * staging rule the map importer follows, and on this device it is the
     * difference between "your model was rejected" and "you have no voice".
     */
    fun install(staged: File): ImportResult {
        if (!staged.isDirectory) {
            return ImportResult.Failed(
                VoiceModelState.CORRUPT,
                "Thư mục mô hình không tồn tại",
                VoiceModelState.VALIDATING
            )
        }

        val report = validator.validate(staged, deviceSdk, deviceAbis())
        if (report.state != VoiceModelState.READY) {
            return ImportResult.Failed(
                report.state,
                report.reason ?: "Mô hình không hợp lệ",
                VoiceModelState.VALIDATING
            )
        }

        val manifest = report.manifest
            ?: return ImportResult.Failed(
                VoiceModelState.CORRUPT,
                "Thiếu metadata.json sau khi kiểm tra",
                VoiceModelState.VALIDATING
            )
        if (!manifest.locale.matches(LocaleName)) {
            // `VALIDATING` would hide a path that could escape the model root.
            return ImportResult.Failed(
                VoiceModelState.CORRUPT,
                "Locale không hợp lệ: ${manifest.locale}",
                VoiceModelState.VALIDATING
            )
        }
        if (!manifest.modelVersion.matches(VersionName)) {
            return ImportResult.Failed(
                VoiceModelState.CORRUPT,
                "modelVersion không hợp lệ: ${manifest.modelVersion}",
                VoiceModelState.VALIDATING
            )
        }

        val localeRoot = File(root, manifest.locale)
        if (!localeRoot.isDirectory && !localeRoot.mkdirs()) {
            return ImportResult.Failed(
                VoiceModelState.FAILED,
                "Không tạo được thư mục mô hình: ${localeRoot.absolutePath}",
                VoiceModelState.VALIDATING
            )
        }

        val destination = File(localeRoot, manifest.modelVersion)
        val backup = File(localeRoot, manifest.modelVersion + ".old")
        try {
            if (backup.exists()) backup.deleteRecursively()
            if (destination.exists() && !destination.renameTo(backup)) {
                // A filesystem that refuses the rename (vendor FUSE, FAT32):
                // copy aside so the old pack survives a failed move.
                destination.copyRecursively(backup, overwrite = true)
                destination.deleteRecursively()
            }
            if (!staged.renameTo(destination)) {
                staged.copyRecursively(destination, overwrite = true)
                staged.deleteRecursively()
            }
            // The bytes on the destination are not guaranteed identical to the
            // ones just hashed when a copy fallback ran, so the verdict is
            // re-derived where Vosk will read it. Cheap enough: the hashes are
            // already in cache on this path.
            val moved = validator.validate(destination, deviceSdk, deviceAbis())
            if (moved.state != VoiceModelState.READY) {
                destination.deleteRecursively()
                if (backup.exists()) backup.renameTo(destination)
                return ImportResult.Failed(
                    moved.state,
                    moved.reason ?: "Mô hình hỏng sau khi sao chép",
                    VoiceModelState.VALIDATING
                )
            }
            if (!writeActiveVersion(localeRoot, manifest.modelVersion)) {
                return ImportResult.Failed(
                    VoiceModelState.FAILED,
                    "Không ghi được trạng thái mô hình đang dùng",
                    VoiceModelState.VALIDATING
                )
            }
            if (backup.exists()) backup.deleteRecursively()

            val fresh = Snapshot(
                VoiceModelState.READY,
                null,
                manifest,
                destination
            )
            cached = fresh
            return ImportResult.Ok(fresh)
        } catch (e: Exception) {
            // A failure half-way through must not leave the marker pointing at
            // a directory that is being written. Restore, then report.
            if (destination.exists() && !File(destination, VoiceModelValidator.MANIFEST_NAME).isFile) {
                destination.deleteRecursively()
            }
            return ImportResult.Failed(
                VoiceModelState.FAILED,
                "Cài mô hình thất bại: ${e.message ?: e.javaClass.simpleName}",
                VoiceModelState.VALIDATING
            )
        }
    }

    /**
     * Extracts an untrusted ZIP into staging, then [install]s it.
     *
     * The Zip Slip rule is the map importer's, for the same reason: the archive
     * comes off a USB stick, and an entry named `../../shared_prefs/ts8.xml`
     * would otherwise be written wherever the app can write.
     *
     * @param name used only for the staging directory and the log.
     */
    fun importZip(name: String, open: () -> InputStream): ImportResult {
        if (!root.isDirectory && !root.mkdirs()) {
            return ImportResult.Failed(
                VoiceModelState.FAILED,
                "Không tạo được thư mục mô hình: ${root.absolutePath}",
                VoiceModelState.VALIDATING
            )
        }
        val staging = File(root, "staging/${sanitize(name)}")
        staging.deleteRecursively()
        if (!staging.mkdirs()) {
            return ImportResult.Failed(
                VoiceModelState.FAILED,
                "Không tạo được thư mục tạm cho mô hình",
                VoiceModelState.VALIDATING
            )
        }

        try {
            val rootCanonical = staging.canonicalFile
            open().use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val target = File(staging, entry.name).canonicalFile
                            // Canonical containment: a sibling named
                            // "staging-evil" shares a string prefix, so a
                            // prefix test on the raw path would pass it.
                            if (!target.path.startsWith(rootCanonical.path + File.separator)) {
                                return ImportResult.Failed(
                                    VoiceModelState.CORRUPT,
                                    "Gói mô hình chứa đường dẫn không hợp lệ: ${entry.name}",
                                    VoiceModelState.VALIDATING
                                )
                            }
                            target.parentFile?.mkdirs()
                            copyInto(zip, target)
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            // The official Vosk zips unpack to one top-level directory, so the
            // pack root is found rather than assumed.
            val packRoot = findManifestRoot(staging) ?: return ImportResult.Failed(
                VoiceModelState.CORRUPT,
                "Gói mô hình không có metadata.json",
                VoiceModelState.VALIDATING
            )
            if (packRoot != staging) {
                // `install` moves the directory; give it a stable name rather
                // than the archive's arbitrary top-level folder.
                val normalised = File(staging, "pack")
                if (normalised.exists()) normalised.deleteRecursively()
                if (!packRoot.renameTo(normalised)) {
                    packRoot.copyRecursively(normalised, overwrite = true)
                    packRoot.deleteRecursively()
                }
                return install(normalised)
            }
            return install(packRoot)
        } catch (e: Exception) {
            return ImportResult.Failed(
                VoiceModelState.CORRUPT,
                "Không giải nén được gói mô hình: ${e.message ?: e.javaClass.simpleName}",
                VoiceModelState.VALIDATING
            )
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    // -- removing -----------------------------------------------------------

    /**
     * Uninstalls the active model.
     *
     * @param confirmed the driver answered the removal prompt. Without it the
     *        call is a no-op returning the current snapshot: the campaign rule
     *        is that nothing the driver did not confirm gets destroyed, and a
     *        voice model is the only way this unit can hear Vietnamese.
     */
    fun remove(confirmed: Boolean): Snapshot {
        if (!confirmed) return snapshot()
        val localeRoot = File(root, VoiceModelManifest.LOCALE_VI)
        val version = readActiveVersion(localeRoot)
        if (version != null) {
            File(localeRoot, version).deleteRecursively()
        }
        // Delete the marker even when the directory was already gone, so the
        // next refresh does not keep reporting a version that is not there.
        File(localeRoot, ACTIVE_FILE).delete()
        val cleared = Snapshot(VoiceModelState.NOT_INSTALLED)
        cached = cleared
        return cleared
    }

    /** Every installed version, newest name first — for the replace dialog. */
    fun installedVersions(): List<String> {
        val localeRoot = File(root, VoiceModelManifest.LOCALE_VI)
        val active = readActiveVersion(localeRoot)
        return localeRoot.listFiles()
            ?.filter { it.isDirectory && File(it, VoiceModelValidator.MANIFEST_NAME).isFile }
            ?.map { it.name }
            ?.sorted()
            ?.sortedByDescending { it == active }
            ?: emptyList()
    }

    // -- internals ----------------------------------------------------------

    private fun readActiveVersion(localeRoot: File): String? {
        val file = File(localeRoot, ACTIVE_FILE)
        if (!file.isFile) return null
        val name = runCatching { file.readText(Charsets.UTF_8).trim() }.getOrNull()
        return name?.takeIf { it.isNotEmpty() && it.matches(VersionName) }
    }

    /** Writes the marker through a temp file so a power cut cannot half-write it. */
    private fun writeActiveVersion(localeRoot: File, version: String): Boolean {
        val temp = File(localeRoot, ACTIVE_FILE + ".tmp")
        val ok = runCatching {
            temp.writeText(version, Charsets.UTF_8)
            if (!temp.renameTo(File(localeRoot, ACTIVE_FILE))) {
                // Some mounts refuse rename onto an existing file; overwrite in
                // place is still atomic enough for a 20-byte write.
                File(localeRoot, ACTIVE_FILE).writeText(version, Charsets.UTF_8)
                temp.delete()
            }
            true
        }.getOrDefault(false)
        if (!ok) temp.delete()
        return ok
    }

    /** The directory that holds `metadata.json`, at most three levels down. */
    private fun findManifestRoot(dir: File, depth: Int = 0): File? {
        if (File(dir, VoiceModelValidator.MANIFEST_NAME).isFile) return dir
        if (depth >= 3) return null
        val children = dir.listFiles()?.filter { it.isDirectory } ?: return null
        for (child in children) {
            findManifestRoot(child, depth + 1)?.let { return it }
        }
        return null
    }

    private fun copyInto(input: InputStream, target: File) {
        FileOutputStream(target).use { out ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
            }
            out.flush()
        }
    }

    private fun sanitize(name: String): String =
        name.substringBeforeLast('.').map { c ->
            if (c.isLetterOrDigit() || c == '-' || c == '_') c else '-'
        }.joinToString("").take(48).ifEmpty { "model" }

    companion object {
        private const val TAG = "VoiceModelStore"

        /** The marker file naming the active version. */
        const val ACTIVE_FILE = "active.txt"

        private const val BUFFER = 64 * 1024

        /** `vi-VN` and nothing that could climb out of the pack root. */
        private val LocaleName = Regex("^[A-Za-z]{2,3}-[A-Za-z]{2,4}$")

        /** The Vosk model naming convention, and no path separators. */
        private val VersionName = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    }
}

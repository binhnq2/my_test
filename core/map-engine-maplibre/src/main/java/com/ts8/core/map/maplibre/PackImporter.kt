package com.ts8.core.map.maplibre

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Installs a `.ts8map` archive (a ZIP) into app-private storage.
 *
 * **Why the import is staged rather than extracted in place.** A pack is ~20 MB
 * and the head unit loses power with the ignition. Extracting directly into the
 * live directory means a power cut leaves half a pack where a whole one used
 * to be, and the driver has no map at all. So extraction goes to a temporary
 * sibling, is validated there, and only then replaces the old directory — one
 * rename, which is atomic on the same filesystem (`rename(2)`).
 *
 * **Why the Zip Slip guard is not optional.** The archive is untrusted: it
 * comes off a USB stick. A `../` entry, an absolute path, or a symlink target
 * escaping the staging directory would write anywhere the app can write — a
 * launcher running as a system-ish app could overwrite its own prefs or another
 * app's data. Every entry is canonicalised and rejected unless it stays inside
 * the staging root, before a single byte is written.
 *
 * **What it does not do.** It does not decide whether the pack is good enough
 * to activate; [PackValidator] does, and the caller passes that verdict in. It
 * also does not retry a failed extraction: reporting the failure and keeping
 * the old pack is the required behaviour, not a loop.
 */
class PackImporter(
    /** Where packs are installed. Created if absent. */
    private val installRoot: File,
    /** Free-space source, injectable so the check is testable off-device. */
    private val freeBytes: (File) -> Long = { it.usableSpace }
) {

    /**
     * The outcome, with a stage so the UI can show "IMPORTING → VALIDATING →
     * ACTIVATING" rather than an indeterminate spinner.
     */
    sealed class Result {
        data class Ok(val directory: File, val bytes: Long) : Result()

        data class Failure(
            val code: PackFailureCode,
            val message: String,
            val diagnostics: PackDiagnostics
        ) : Result()
    }

    /**
     * Extracts [open] into the install root and validates the result.
     *
     * @param open supplies the archive stream once. Called on the IO thread.
     * @param probe the SQLite probe the validator uses; the caller passes the
     *        Android implementation in production and a fake in tests.
     * @param validate a hook that decides whether the extracted pack is usable.
     *        Defaults to [PackValidator.validate] on a freshly loaded pack.
     */
    fun import(
        archiveName: String,
        open: () -> InputStream,
        diagnostics: PackDiagnostics = PackDiagnostics(),
        probe: SqliteProbe = AndroidSqliteProbe(),
        validate: (MapPack, PackDiagnostics) -> PackValidator.Report = { pack, diag ->
            PackValidator(probe).validate(pack, diag)
        }
    ): Result {
        diagnostics.note(PackStage.IMPORTING, "archive=$archiveName root=${installRoot.absolutePath}")

        if (!installRoot.isDirectory && !installRoot.mkdirs()) {
            return fail(diagnostics, PackFailureCode.INSUFFICIENT_STORAGE,
                "Không tạo được thư mục cài đặt: ${installRoot.absolutePath}")
        }

        val free = runCatching { freeBytes(installRoot) }.getOrDefault(0L)
        diagnostics.note(PackStage.IMPORTING, "free=$free")

        // Staging directory under the install root, so the final rename stays on
        // one filesystem and cannot degrade into a copy that a power cut
        // interrupts halfway.
        val staging = File(installRoot, "staging-${sanitize(archiveName)}")
        staging.deleteRecursively()
        if (!staging.mkdirs()) {
            return fail(diagnostics, PackFailureCode.INSUFFICIENT_STORAGE,
                "Không tạo được thư mục tạm: ${staging.absolutePath}")
        }

        var written = 0L
        try {
            val root = staging.canonicalFile
            open().use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (entry.isDirectory) {
                            entry = zip.nextEntry
                            continue
                        }
                        val target = File(staging, name)
                        val canonical = target.canonicalFile
                        // Canonical containment, not a string prefix test: a
                        // sibling named "staging-x-evil" shares the prefix.
                        if (!canonical.path.startsWith(root.path + File.separator)) {
                            staging.deleteRecursively()
                            return fail(
                                diagnostics,
                                PackFailureCode.INVALID_METADATA,
                                "Zip Slip: entry '$name' escapes the pack directory"
                            )
                        }
                        canonical.parentFile?.mkdirs()
                        val copied = copyInto(zip, canonical)
                        written += copied
                        if (written > free) {
                            staging.deleteRecursively()
                            return fail(
                                diagnostics,
                                PackFailureCode.INSUFFICIENT_STORAGE,
                                "Hết dung lượng khi giải nén ($written > $free)"
                            )
                        }
                        diagnostics.file("$name $copied")
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }

            // The archive may have a single top-level directory (`danang-v1/`),
            // which is the same layout the folder packs use. Find the manifest
            // rather than assuming the metadata sits at the staging root.
            val packRoot = findManifestRoot(staging)
                ?: return fail(
                    diagnostics,
                    PackFailureCode.INVALID_METADATA,
                    "Archive không có metadata.json ở thư mục gốc"
                )

            val loaded = MapPack.load(packRoot, diagnostics)
            if (loaded is MapPack.LoadResult.Failure) {
                return Result.Failure(loaded.code, loaded.reason, diagnostics)
            }
            val pack = (loaded as MapPack.LoadResult.Ok).pack

            diagnostics.note(PackStage.VALIDATING_CHECKSUM, "validating extracted pack")
            val report = validate(pack, diagnostics)
            if (!report.ok) {
                // Refuse to activate a pack that cannot render. The old pack
                // stays exactly as it was, which the brief requires.
                return Result.Failure(
                    report.code ?: PackFailureCode.ACTIVATION_FAILED,
                    report.message ?: "Pack không vượt qua kiểm tra",
                    diagnostics
                )
            }

            diagnostics.note(PackStage.ACTIVATING, "installing ${pack.metadata.packId}")
            val destination = File(installRoot, sanitize(pack.metadata.packId))
            replaceAtomically(packRoot, destination, diagnostics)
            diagnostics.resolvedPath = destination.absolutePath
            diagnostics.note(PackStage.READY, destination.absolutePath)
            return Result.Ok(destination, written)
        } catch (e: Exception) {
            // The brief fixes the 13 failure codes and inventing a fourteenth
            // would change a contract both the UI and the docs quote. An
            // unreadable archive is, from the driver's point of view, an
            // invalid pack — and the detail carries the real exception text.
            return fail(diagnostics, PackFailureCode.INVALID_METADATA,
                "Giải nén thất bại: ${e.message}")
        } finally {
            // Cleans up whatever is left: an early return leaves the staging
            // directory behind, and on this device 20 MB of litter is 10 % of
            // the free space.
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    /**
     * Swaps the staged directory in, keeping the old one until the new one is
     * in place.
     *
     * Order matters: the old directory is moved aside first (same filesystem,
     * a rename), then the new one is renamed into place, then the old is
     * deleted. A failure between steps leaves a recoverable state — either the
     * new pack in place or the old one still present under its backup name.
     */
    private fun replaceAtomically(staged: File, destination: File, diagnostics: PackDiagnostics) {
        val backup = File(destination.parentFile, destination.name + ".old")
        if (backup.exists()) backup.deleteRecursively()

        if (destination.exists()) {
            if (!destination.renameTo(backup)) {
                // Fall back to a copy so a filesystem that refuses the rename
                // (a FAT32 stick, a vendor FUSE mount) still works.
                destination.copyRecursively(backup, overwrite = true)
                destination.deleteRecursively()
            }
            diagnostics.note(PackStage.ACTIVATING, "old pack moved to ${backup.name}")
        }
        if (!staged.renameTo(destination)) {
            staged.copyRecursively(destination, overwrite = true)
            staged.deleteRecursively()
        }
        if (backup.exists()) backup.deleteRecursively()
    }

    /** The directory that holds `metadata.json`, at most two levels down. */
    private fun findManifestRoot(root: File): File? {
        if (File(root, "metadata.json").isFile) return root
        val first = root.listFiles()?.filter { it.isDirectory } ?: return null
        for (child in first) {
            if (File(child, "metadata.json").isFile) return child
            val second = child.listFiles()?.filter { it.isDirectory } ?: continue
            for (grandchild in second) {
                if (File(grandchild, "metadata.json").isFile) return grandchild
            }
        }
        return null
    }

    private fun copyInto(input: InputStream, target: File): Long {
        var total = 0L
        FileOutputStream(target).use { out ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
                total += read
            }
            out.flush()
        }
        return total
    }

    private fun fail(
        diagnostics: PackDiagnostics,
        code: PackFailureCode,
        message: String
    ): Result.Failure {
        diagnostics.fail(PackStage.IMPORTING, code, message)
        return Result.Failure(code, message, diagnostics)
    }

    private fun sanitize(name: String): String =
        name.substringBeforeLast('.').map { c ->
            if (c.isLetterOrDigit() || c == '-' || c == '_') c else '-'
        }.joinToString("").take(48).ifEmpty { "pack" }

    companion object {
        private const val BUFFER = 64 * 1024
    }
}

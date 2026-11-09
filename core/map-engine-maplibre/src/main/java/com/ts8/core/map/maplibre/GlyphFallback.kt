package com.ts8.core.map.maplibre

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Ensures glyph PBF files are on **internal** storage so MapLibre's native
 * `LocalFileSource` can read them via a `file://` URL.
 *
 * **Why this exists.** MapLibre Native's `LocalFileSource` (C++ code) reads
 * files via raw `open()` syscalls, not through Android's Java File API.
 * On Android 6.0 (API 23), a pack on USB storage is readable from Java
 * (`File.isDirectory()` returns true) but the native `open()` call may be
 * blocked by SELinux or vold mount policy — the C++ code gets `EACCES` and
 * silently fails to load the glyph, which blocks the style from completing
 * its load cycle. The `setStyle()` callback never fires, and the map stays
 * stuck on "Đang tải kiểu hiển thị" forever.
 *
 * **The fix has two layers:**
 *
 * 1. **APK fallback.** 14 PBF files (7 per family × 2 families, ~1.3 MB)
 *    are bundled in `assets/fonts/` and extracted to `filesDir/fonts/`
 *    on first run. This saves packs that were copied without their
 *    `fonts/` directory.
 *
 * 2. **Pack font staging.** When a pack has its own `fonts/` directory
 *    (typically on USB), the PBF files are **copied** from the pack's
 *    location to `filesDir/fonts/` so MapLibre reads them from internal
 *    storage. This bypasses the USB native-read issue entirely. The copy
 *    is incremental: existing files with the same size are skipped.
 *
 * **Why assets, not raw resources.** MapLibre's glyph URL template is
 * `file://<dir>/{fontstack}/{range}.pbf`, and `LocalFileSource` only
 * reads filesystem paths — it cannot open an Android asset URI. The
 * extraction is a one-time copy; on subsequent starts the directory
 * already exists and the copy is skipped.
 */
object GlyphFallback {

    private const val TAG = "GlyphFallback"
    private const val ASSET_DIR = "fonts"
    private const val OUTPUT_DIR = "fonts"

    /**
     * The directory where fonts are staged on internal storage.
     *
     * Returns `null` only if staging failed and no directory exists —
     * which would mean the filesystem is genuinely unusable.
     */
    @Volatile
    private var cachedDir: File? = null

    /**
     * Ensures the APK-bundled fallback fonts are on disk and returns their directory.
     *
     * Thread-safe: the extraction is synchronized, and the result is
     * cached so subsequent calls are a field read.
     *
     * @param context any context; uses `applicationContext` internally
     * @return the directory containing `Noto Sans Bold/` and
     *         `Noto Sans Regular/` subdirectories with PBF files, or
     *         `null` if extraction failed
     */
    @Synchronized
    fun ensureExtracted(context: Context): File? {
        cachedDir?.let { if (it.isDirectory) return it }

        val appContext = context.applicationContext
        val outDir = File(appContext.filesDir, OUTPUT_DIR)

        // Fast path: already extracted in a previous run.
        if (isComplete(outDir)) {
            Log.i(TAG, "fonts already extracted at ${outDir.absolutePath}")
            cachedDir = outDir
            return outDir
        }

        // Slow path: extract from assets.
        Log.i(TAG, "extracting fallback fonts from assets to ${outDir.absolutePath}")
        outDir.mkdirs()
        val assetManager = appContext.assets
        var copied = 0
        try {
            val families = assetManager.list(ASSET_DIR)
            if (families == null || families.isEmpty()) {
                Log.e(TAG, "no font families found in assets/$ASSET_DIR — APK may not contain bundled fonts")
                return null
            }
            Log.i(TAG, "found ${families.size} font families in assets: ${families.joinToString()}")
            for (family in families) {
                val familyDir = File(outDir, family)
                familyDir.mkdirs()
                val files = assetManager.list("$ASSET_DIR/$family")
                if (files == null || files.isEmpty()) {
                    Log.w(TAG, "no PBF files in assets/$ASSET_DIR/$family")
                    continue
                }
                for (fileName in files) {
                    val outFile = File(familyDir, fileName)
                    if (outFile.exists() && outFile.length() > 0) continue
                    assetManager.open("$ASSET_DIR/$family/$fileName").use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    copied++
                }
                Log.i(TAG, "family '$family': ${files.size} files, dir=${familyDir.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "glyph extraction failed: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }

        Log.i(TAG, "extracted $copied glyph files to ${outDir.absolutePath}")
        cachedDir = if (isComplete(outDir)) outDir else null
        if (cachedDir == null) {
            Log.e(TAG, "extraction complete but isComplete() returned false — checking manually")
            outDir.walkTopDown().forEach { f ->
                Log.e(TAG, "  ${if (f.isDirectory) "DIR" else "FILE"} ${f.absolutePath} (${f.length()}B)")
            }
        }
        return cachedDir
    }

    /**
     * Ensures fonts are available on **internal** storage for MapLibre to read,
     * staging them from the pack's own `fonts/` directory or falling back to
     * the APK-bundled fonts.
     *
     * **Why this method exists.** On real TS8 hardware (Android 6.0, Spreadtrum),
     * a pack on USB storage has its `fonts/` directory readable from Java but
     * not from MapLibre's native C++ `LocalFileSource` — the `open()` syscall
     * is blocked by SELinux or vold mount policy. This method copies the PBF
     * files from the pack's location (which may be on USB) to `filesDir/fonts/`
     * (which is always on internal storage and always readable by native code).
     *
     * **Incremental copy.** Files that already exist with the same byte count
     * are skipped, so a 50 MB pack doesn't re-copy its 1.3 MB of fonts on every
     * start — only on first run or when the pack changes.
     *
     * @param context any context
     * @param packGlyphsDir the pack's own `fonts/` directory (may be on USB),
     *        or null when the pack has no fonts
     * @return the internal-storage directory containing staged PBF files,
     *         or null if staging failed entirely
     */
    @Synchronized
    fun ensureFontsOnInternal(
        context: Context,
        packGlyphsDir: File?
    ): File? {
        val appContext = context.applicationContext
        val outDir = File(appContext.filesDir, OUTPUT_DIR)

        // If pack has no fonts, use APK fallback.
        if (packGlyphsDir == null || !packGlyphsDir.isDirectory) {
            Log.i(TAG, "ensureFontsOnInternal: pack has no fonts dir, using APK fallback")
            return ensureExtracted(context)
        }

        // Check if we need to copy from the pack.
        // We copy if the output dir is not complete OR if the pack's fonts
        // are on a path that native code might not be able to read (USB/external).
        val packPath = packGlyphsDir.absolutePath
        val isOnInternal = packPath.startsWith(appContext.filesDir.absolutePath) ||
            packPath.startsWith("/data/data/") || packPath.startsWith("/data/user/")

        if (isOnInternal && isComplete(packGlyphsDir)) {
            // Pack fonts are already on internal storage — no need to copy.
            Log.i(TAG, "ensureFontsOnInternal: pack fonts already on internal storage at $packPath")
            cachedDir = packGlyphsDir
            return packGlyphsDir
        }

        // Need to stage fonts from pack (or APK) to internal storage.
        outDir.mkdirs()

        // Try copying from the pack's fonts directory first.
        if (packGlyphsDir.isDirectory) {
            Log.i(TAG, "ensureFontsOnInternal: staging fonts from pack ($packPath) to internal (${outDir.absolutePath})")
            var copied = 0
            try {
                val families = packGlyphsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
                if (families.isEmpty()) {
                    Log.w(TAG, "ensureFontsOnInternal: pack fonts dir has no family subdirs")
                } else {
                    for (family in families) {
                        val familyDir = File(outDir, family.name)
                        familyDir.mkdirs()
                        val pbfFiles = family.listFiles()?.filter { it.extension == "pbf" } ?: emptyList()
                        for (srcFile in pbfFiles) {
                            val destFile = File(familyDir, srcFile.name)
                            // Skip if already exists with same size (incremental copy).
                            if (destFile.exists() && destFile.length() == srcFile.length()) continue
                            srcFile.inputStream().use { input ->
                                FileOutputStream(destFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            copied++
                        }
                        Log.i(TAG, "ensureFontsOnInternal: family '${family.name}': ${pbfFiles.size} files")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ensureFontsOnInternal: copy from pack failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            Log.i(TAG, "ensureFontsOnInternal: copied $copied font files from pack to internal")
        }

        // Check if internal dir now has fonts. If not, fall back to APK assets.
        if (!isComplete(outDir)) {
            Log.w(TAG, "ensureFontsOnInternal: internal dir incomplete after pack copy, trying APK fallback")
            // Reset cache so ensureExtracted does its work.
            cachedDir = null
            ensureExtracted(context)?.let { return it }
        }

        cachedDir = if (isComplete(outDir)) outDir else null
        if (cachedDir == null) {
            Log.e(TAG, "ensureFontsOnInternal: FAILED — no fonts available on internal storage")
        } else {
            Log.i(TAG, "ensureFontsOnInternal: OK — fonts at ${cachedDir!!.absolutePath}")
            // Log the full tree for diagnostics.
            outDir.walkTopDown().forEach { f ->
                Log.i(TAG, "  ${if (f.isDirectory) "DIR" else "FILE"} ${f.name} (${f.length()}B)")
            }
        }
        return cachedDir
    }

    /**
     * True when the directory has at least one PBF file in a family
     * subdirectory.
     */
    private fun isComplete(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val bold = File(dir, "Noto Sans Bold")
        val regular = File(dir, "Noto Sans Regular")
        // Accept either family — a minimal pack might ship only one.
        return (bold.isDirectory && bold.listFiles()?.any { it.extension == "pbf" } == true) ||
            (regular.isDirectory && regular.listFiles()?.any { it.extension == "pbf" } == true)
    }
}

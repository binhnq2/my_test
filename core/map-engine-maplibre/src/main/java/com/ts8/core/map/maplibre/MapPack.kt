package com.ts8.core.map.maplibre

import android.content.Context
import com.ts8.core.map.GeoBounds
import com.ts8.core.map.GeoPoint
import com.ts8.core.map.MapStyleMode
import org.json.JSONObject
import java.io.File

/**
 * A map pack on disk, validated before it is handed to the renderer.
 *
 * **Why validation is a class rather than three lines in the engine.** A pack
 * lives on a USB stick. It can be half-copied, built by an older tool, or for a
 * different city. Every one of those fails inside MapLibre as an unhelpful
 * native crash or a silently blank map. Checking the manifest and the files it
 * names up front turns each of them into a message the driver can act on.
 *
 * Nothing here invents data: a pack that fails validation is reported as
 * unusable, and the map screen shows why. The spec forbids falling back to demo
 * tiles, and a blank grid that looks like a map is worse than an error.
 *
 * **Load vs. validate.** [load] is the cheap pass: manifest parsed, required
 * files stat'ed. It is safe on the main thread and is what discovery runs.
 * [PackValidator] is the expensive pass (checksums, SQLite opens, a real tile
 * query) and runs on the IO executor. Splitting them is what lets the UI show
 * `DISCOVERING → FOUND` instantly and then fill in the rest without a freeze.
 */
class MapPack private constructor(
    val directory: File,
    val metadata: Metadata
) {

    /** Absolute path of the tile container, unencoded. */
    val tilesPath: String get() = File(directory, metadata.basemap).absolutePath

    /**
     * The `mbtiles://` URL the style source gets.
     *
     * Percent-encoded exactly once, here — see [PackPaths] for why a raw path
     * breaks silently.
     */
    val tilesUri: String get() = PackPaths.mbtilesUrl(tilesPath)

    /**
     * The style the pack ships, or null when it ships none.
     *
     * Kept because a pack built by a newer tool may carry a hand-authored style.
     * The current build always generates one from the palette, so this is only
     * consulted by [PackValidator] and by the diagnostics report.
     */
    val stylePath: File?
        get() = File(directory, if (metadata.styleNight != null) metadata.styleNight!! else metadata.styleDay)
            .takeIf { it.isFile }

    fun styleFile(mode: MapStyleMode): File? {
        val name = when (mode) {
            MapStyleMode.DAY -> metadata.styleDay
            MapStyleMode.NIGHT -> metadata.styleNight ?: metadata.styleDay
            MapStyleMode.AUTO -> metadata.styleDay
        }
        return File(directory, name).takeIf { it.isFile }
    }

    /** True when the pack carries its own glyphs, so labels work offline. */
    val hasGlyphs: Boolean get() = metadata.glyphs != null && resolveGlyphsDir() != null

    /**
     * The glyph directory for this pack, or the APK-bundled fallback.
     *
     * **Why the fallback exists.** A pack copied without its `fonts/`
     * directory — the most common copy mistake — leaves the style with
     * no glyph source. MapLibre then hangs in style loading, showing
     * "Đang tải kiểu hiển thị" forever. The fallback fonts (14 PBF
     * files, ~700 KB) are bundled in the APK's `assets/fonts/` and
     * extracted to `filesDir/fonts/` by [GlyphFallback] on first use.
     *
     * Call [resolveGlyphsDirWithContext] when a [Context] is available,
     * as it triggers the fallback extraction. This no-arg version is
     * kept for existing call sites that only need the pack's own fonts.
     */
    fun resolveGlyphsDir(): File? {
        val declared = metadata.glyphs ?: return null
        // The manifest names a template ("fonts/{fontstack}/{range}.pbf"); the
        // directory is everything before the first placeholder.
        val cut = declared.indexOf('{')
        val root = if (cut > 0) declared.substring(0, cut).trimEnd('/') else declared
        return File(directory, root).takeIf { it.isDirectory }
    }

    /**
     * Resolves the glyph directory on **internal** storage, staging fonts
     * from the pack or APK fallback as needed.
     *
     * This is the preferred call site when a [Context] is available.
     * Fonts are always staged to `filesDir/fonts/` so MapLibre's native
     * C++ LocalFileSource can read them regardless of where the pack
     * lives (USB, external, internal).
     */
    fun resolveGlyphsDirWithContext(context: android.content.Context): File? {
        return GlyphFallback.ensureFontsOnInternal(context, resolveGlyphsDir())
    }


    /**
     * The sprite base path without extension, or null.
     *
     * MapLibre asks for `<base>.json` and `<base>@2x.png`; the pack stores the
     * pair. Returned only when both exist, because a half sprite renders worse
     * than no sprite (MapLibre drops every icon layer when the JSON 404s).
     */
    fun resolveSprite(): File? {
        val declared = metadata.sprite?.takeIf { it.isNotBlank() } ?: return null
        val base = File(directory, declared)
        val json = File(base.parentFile, base.name + ".json")
        return base.takeIf { json.isFile && File(base.parentFile, base.name + "@2x.png").isFile }
    }

    fun databaseFile(): File? = File(directory, metadata.placesDb).takeIf { it.isFile }

    fun routingDir(): File? = File(directory, metadata.routingGraph).takeIf { it.isDirectory }

    /** The pack's own bounds, used to clamp the camera to real data. */
    val bounds: GeoBounds?
        get() = metadata.bounds?.let { b ->
            if (b.size == 4) GeoBounds(b[0], b[1], b[2], b[3]) else null
        }

    val center: GeoPoint?
        get() = metadata.center?.let { c ->
            if (c.size >= 2) GeoPoint(c[1], c[0]) else null
        }

    /** Total bytes on disk, or 0 when the tree cannot be walked. */
    fun sizeBytes(): Long = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    override fun toString(): String = "MapPack(${metadata.packId}, ${directory.absolutePath})"

    /**
     * The manifest as read from `metadata.json`.
     *
     * Field names mirror the format spec exactly; unknown fields are ignored so
     * a newer builder's output still loads, and missing optional fields default
     * rather than throwing.
     */
    data class Metadata(
        val formatVersion: Int,
        val packId: String,
        val name: String,
        val bounds: DoubleArray?,
        val center: DoubleArray?,
        val defaultZoom: Double,
        val minZoom: Double,
        /** Highest zoom the tile store actually contains. */
        val maxZoom: Double,
        /**
         * Highest zoom the *camera* may reach, above [maxZoom].
         *
         * The two are deliberately different. Tiles stop at 14 because that is
         * the deepest level worth shipping, but the spec puts 3D buildings
         * behind a zoom-15 gate, and a camera clamped to the tile range can
         * never reach it. MapLibre overzooms the last available level, so the
         * extra zoom costs nothing on disk. A pack that omits the field falls
         * back to [maxZoom], which keeps an older pack working — 2D only.
         */
        val maxCameraZoom: Double,
        val basemap: String,
        val placesDb: String,
        val routingGraph: String,
        val styleDay: String,
        val styleNight: String?,
        val glyphs: String?,
        val sprite: String?,
        val attribution: String?,
        /** Data generation timestamp from the manifest, verbatim. */
        val generatedAt: String? = null
    )

    /**
     * Outcome of loading a pack directory.
     *
     * Declared at the top level of the file rather than nested in the
     * companion, because callers in other modules name it in their own public
     * signatures (`AppGraph.loadPack`), and a nested companion type has to be
     * imported through `MapPack.Companion.LoadResult`, which is noise at every
     * call site.
     */
    sealed class LoadResult {
        data class Ok(val pack: MapPack, val diagnostics: PackDiagnostics = PackDiagnostics()) : LoadResult()

        /**
         * [reason] is the driver-facing sentence; [code] and [diagnostics] carry
         * the machine-readable cause. The code is what lets the UI pick a
         * different action per failure instead of one generic message.
         */
        data class Failure(
            val reason: String,
            val code: PackFailureCode = PackFailureCode.PACK_NOT_FOUND,
            val diagnostics: PackDiagnostics = PackDiagnostics()
        ) : LoadResult()
    }

    companion object {
        /** The only format this build understands; readers reject anything else. */
        const val SUPPORTED_FORMAT_VERSION = 1

        /** Files the pack cannot work without, relative to the pack root. */
        private val REQUIRED = listOf("basemap.mbtiles")

        /**
         * Reads and validates a pack directory's manifest and required files.
         *
         * Never throws: a missing directory, malformed JSON, an unsupported
         * format version and missing files all come back as [LoadResult.Failure]
         * with a reason aimed at the driver, not at a log file.
         */
        fun load(directory: File, diagnostics: PackDiagnostics = PackDiagnostics()): LoadResult {
            diagnostics.note(PackStage.VALIDATING_METADATA, "root=${directory.absolutePath}")
            if (!directory.isDirectory) {
                diagnostics.fail(PackStage.DISCOVERING, PackFailureCode.PACK_NOT_FOUND, "not a directory")
                return LoadResult.Failure(
                    "Không tìm thấy thư mục map pack: ${directory.absolutePath}",
                    PackFailureCode.PACK_NOT_FOUND,
                    diagnostics
                )
            }
            val manifest = File(directory, "metadata.json")
            if (!manifest.isFile) {
                diagnostics.fail(PackStage.VALIDATING_METADATA, PackFailureCode.INVALID_METADATA, "metadata.json missing")
                return LoadResult.Failure(
                    "Thiếu metadata.json trong ${directory.absolutePath}",
                    PackFailureCode.INVALID_METADATA,
                    diagnostics
                )
            }
            if (!manifest.canRead()) {
                diagnostics.fail(PackStage.VALIDATING_METADATA, PackFailureCode.PERMISSION_DENIED, "metadata.json unreadable")
                return LoadResult.Failure(
                    "Không đọc được metadata.json — kiểm tra quyền truy cập bộ nhớ",
                    PackFailureCode.PERMISSION_DENIED,
                    diagnostics
                )
            }
            diagnostics.metadataBytes = manifest.length()

            val json = try {
                JSONObject(manifest.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                diagnostics.fail(PackStage.VALIDATING_METADATA, PackFailureCode.INVALID_METADATA, "json=${e.message}")
                return LoadResult.Failure(
                    "metadata.json không đọc được: ${e.message}",
                    PackFailureCode.INVALID_METADATA,
                    diagnostics
                )
            }

            val version = json.optInt("formatVersion", 0)
            diagnostics.formatVersion = version
            if (version != SUPPORTED_FORMAT_VERSION) {
                // A newer pack than this APK understands is a real case after a
                // pack update, and silently loading it would mis-render.
                diagnostics.fail(
                    PackStage.VALIDATING_METADATA,
                    PackFailureCode.UNSUPPORTED_SCHEMA,
                    "formatVersion=$version supported=$SUPPORTED_FORMAT_VERSION"
                )
                return LoadResult.Failure(
                    "Map pack định dạng v$version, bản này chỉ đọc được v$SUPPORTED_FORMAT_VERSION. " +
                        "Cập nhật ứng dụng hoặc dùng pack cũ hơn.",
                    PackFailureCode.UNSUPPORTED_SCHEMA,
                    diagnostics
                )
            }

            diagnostics.note(PackStage.VALIDATING_FILES, "checking ${REQUIRED.size} required file(s)")
            for (required in REQUIRED) {
                val file = File(directory, required)
                if (!file.isFile) {
                    diagnostics.fail(PackStage.VALIDATING_FILES, PackFailureCode.MBTILES_OPEN_FAILED, "missing $required")
                    return LoadResult.Failure(
                        "Map pack thiếu file bắt buộc: $required",
                        PackFailureCode.MBTILES_OPEN_FAILED,
                        diagnostics
                    )
                }
                if (file.length() <= 0L) {
                    diagnostics.fail(PackStage.VALIDATING_FILES, PackFailureCode.MBTILES_OPEN_FAILED, "$required is empty")
                    return LoadResult.Failure(
                        "File bản đồ rỗng: $required (0 byte)",
                        PackFailureCode.MBTILES_OPEN_FAILED,
                        diagnostics
                    )
                }
                diagnostics.file("$required size=${file.length()}")
            }

            val meta = Metadata(
                formatVersion = version,
                packId = json.optString("packId", directory.name),
                name = json.optString("name", directory.name),
                bounds = json.optJSONArray("bounds")?.let { a ->
                    DoubleArray(a.length()) { a.optDouble(it, 0.0) }
                },
                center = json.optJSONArray("center")?.let { a ->
                    DoubleArray(a.length()) { a.optDouble(it, 0.0) }
                },
                defaultZoom = json.optDouble("defaultZoom", 12.0),
                minZoom = json.optDouble("minZoom", 5.0),
                maxZoom = json.optDouble("maxZoom", 14.0),
                maxCameraZoom = json.optDouble("maxCameraZoom", json.optDouble("maxZoom", 14.0)),
                basemap = json.optString("basemap", "basemap.mbtiles"),
                placesDb = json.optString("placesDb", "places.db"),
                routingGraph = json.optString("routingGraph", "routing-graph"),
                styleDay = json.optJSONObject("style")?.optString("day", "style-day.json")
                    ?: "style-day.json",
                styleNight = json.optJSONObject("style")?.optString("night", "")?.takeIf { it.isNotEmpty() },
                glyphs = json.optString("glyphs", "").takeIf { it.isNotEmpty() },
                sprite = json.optString("sprite", "").takeIf { it.isNotEmpty() },
                attribution = json.optString("attribution", "").takeIf { it.isNotEmpty() },
                generatedAt = json.optString("generatedAt", "").takeIf { it.isNotEmpty() }
            )

            if (meta.maxZoom < meta.minZoom) {
                diagnostics.fail(
                    PackStage.VALIDATING_METADATA,
                    PackFailureCode.INVALID_METADATA,
                    "maxZoom=${meta.maxZoom} < minZoom=${meta.minZoom}"
                )
                return LoadResult.Failure(
                    "metadata.json: maxZoom nhỏ hơn minZoom",
                    PackFailureCode.INVALID_METADATA,
                    diagnostics
                )
            }

            diagnostics.packId = meta.packId
            diagnostics.resolvedPath = directory.absolutePath

            // A declared basemap that points somewhere other than the file the
            // required-files pass just checked is a manifest lie, and it would
            // surface later as "map pack has no tiles" with no clue where.
            val declared = File(directory, meta.basemap)
            if (!declared.isFile) {
                diagnostics.fail(
                    PackStage.VALIDATING_FILES,
                    PackFailureCode.MBTILES_OPEN_FAILED,
                    "basemap=${meta.basemap} missing"
                )
                return LoadResult.Failure(
                    "Map pack khai báo ${meta.basemap} nhưng không có file đó",
                    PackFailureCode.MBTILES_OPEN_FAILED,
                    diagnostics
                )
            }

            return LoadResult.Ok(MapPack(directory, meta), diagnostics)
        }

        /**
         * Finds a pack under the given roots, in priority order.
         *
         * **Depth matters.** The documented pack layout is
         * `<region>/<version>/metadata.json`, so a walk that only tries the root
         * and its immediate children never reaches a versioned pack. This walks
         * up to [MAX_DEPTH] levels but stops descending into a directory that
         * already looks like pack *content* (`fonts/`, `routing-graph/`,
         * `style/`, `sprites/`), so a large pack costs a handful of `stat`s
         * rather than a full tree scan.
         *
         * **Every candidate is recorded**, accepted or not, in
         * [PackDiagnostics.candidates] — the brief forbids concluding anything
         * about folders without the resolved paths on record.
         */
        fun discover(
            roots: List<File>,
            diagnostics: PackDiagnostics = PackDiagnostics(),
            storageReadable: Boolean = true,
            preferred: File? = null
        ): LoadResult {
            diagnostics.note(PackStage.DISCOVERING, "roots=${roots.size} storageReadable=$storageReadable")

            if (!storageReadable) {
                // On API 23 a denied READ_EXTERNAL_STORAGE makes File.isDirectory
                // return false without throwing, which is indistinguishable from
                // "the folder is not there". Without this check the loader would
                // report PACK_NOT_FOUND and the driver would go looking for a
                // file that is already in the right place.
                diagnostics.fail(PackStage.DISCOVERING, PackFailureCode.PERMISSION_DENIED, "storage permission not granted")
                return LoadResult.Failure(
                    "Chưa được cấp quyền đọc bộ nhớ nên không thể tìm map pack. " +
                        "Cấp quyền bộ nhớ rồi bấm “Kiểm tra lại”.",
                    PackFailureCode.PERMISSION_DENIED,
                    diagnostics
                )
            }

            // The pack the driver explicitly activated wins over sort order.
            // Without this, installing a second region silently switches the
            // map to whichever directory happens to sort first.
            if (preferred != null) {
                diagnostics.candidate("${preferred.absolutePath} preferred=true exists=${preferred.isDirectory}")
                if (File(preferred, "metadata.json").isFile) {
                    val chosen = load(preferred, diagnostics)
                    if (chosen is LoadResult.Ok) {
                        diagnostics.note(PackStage.FOUND, chosen.pack.directory.absolutePath)
                        return chosen
                    }
                    diagnostics.candidate("${preferred.absolutePath} preferred=invalid")
                }
            }

            var best: LoadResult.Failure? = null

            for (root in roots) {
                if (!root.isDirectory) {
                    diagnostics.candidate("${root.absolutePath} exists=false")
                    continue
                }
                diagnostics.candidate("${root.absolutePath} exists=true")

                val found = walk(root, depth = 0, diagnostics = diagnostics)
                if (found is LoadResult.Ok) {
                    diagnostics.note(PackStage.FOUND, found.pack.directory.absolutePath)
                    return found
                }
                if (found is LoadResult.Failure && best == null) {
                    // Keep the FIRST real failure, not the last. Walking a root
                    // ends on whatever subdirectory sorted last (usually
                    // `routing-graph/`), and reporting that leaves the driver
                    // staring at a path that has nothing to do with the problem.
                    best = found
                }
            }

            val failure = best ?: LoadResult.Failure(
                "Chưa cài map pack. Chép map pack vào bộ nhớ trong hoặc USB, rồi bấm “Kiểm tra lại”.",
                PackFailureCode.PACK_NOT_FOUND,
                diagnostics
            )
            diagnostics.fail(
                PackStage.DISCOVERING,
                failure.code,
                "no usable pack under ${roots.size} root(s)"
            )
            return failure.copy(diagnostics = diagnostics)
        }

        /** Depth-limited search that stops at pack-looking content. */
        private fun walk(root: File, depth: Int, diagnostics: PackDiagnostics): LoadResult {
            // A directory is a pack candidate if it has a manifest. Check that
            // before anything else so a valid pack at the root wins over its
            // own subdirectories.
            if (File(root, "metadata.json").isFile) {
                val result = load(root, diagnostics)
                if (result is LoadResult.Ok) return result
                diagnostics.candidate("${root.absolutePath} pack=invalid")
                return result
            }

            if (depth >= MAX_DEPTH) {
                diagnostics.candidate("${root.absolutePath} depth=limit")
                return LoadResult.Failure(
                    "Không thấy metadata.json trong ${root.absolutePath}",
                    PackFailureCode.PACK_NOT_FOUND,
                    diagnostics
                )
            }

            // `fonts/`, `routing-graph/`, `style/` and `sprites/` are pack
            // content. Descending into them on every scan would stat hundreds of
            // glyph files on a 1 GB device for no possible gain.
            if (root.name in CONTENT_DIRS) {
                diagnostics.candidate("${root.absolutePath} content=skip")
                return LoadResult.Failure(
                    "Thư mục ${root.absolutePath} là dữ liệu của pack, không phải pack",
                    PackFailureCode.PACK_NOT_FOUND,
                    diagnostics
                )
            }

            val children = root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }
            if (children.isNullOrEmpty()) {
                diagnostics.candidate("${root.absolutePath} children=0")
                return LoadResult.Failure(
                    "Không thấy metadata.json trong ${root.absolutePath}",
                    PackFailureCode.PACK_NOT_FOUND,
                    diagnostics
                )
            }

            var first: LoadResult.Failure? = null
            for (child in children) {
                val result = walk(child, depth + 1, diagnostics)
                if (result is LoadResult.Ok) return result
                if (first == null && result is LoadResult.Failure) first = result
            }
            return first ?: LoadResult.Failure(
                "Không thấy metadata.json trong ${root.absolutePath}",
                PackFailureCode.PACK_NOT_FOUND,
                diagnostics
            )
        }

        /** Standard places a pack is looked for, in priority order. */
        fun defaultRoots(context: Context): List<File> = buildList {
            // App-private storage first: always readable, survives a USB pull.
            add(File(context.filesDir, "map-pack"))
            @Suppress("DEPRECATION")
            val external = context.getExternalFilesDir(null)
            if (external != null) add(File(external, "map-pack"))
            // Then the shared locations a driver copies a pack into.
            add(File("/storage/emulated/0/ts8/map-pack"))
            add(File("/storage/emulated/0/TS8/map-pack"))
            add(File("/storage/emulated/0/ts8"))
            for (name in USB_ROOTS) {
                add(File(name, "ts8/map-pack"))
                add(File(name, "TS8/map-pack"))
            }
        }

        /**
         * How many directory levels below a root are searched.
         *
         * Two covers both documented layouts: `<root>/<region>/<version>/` and
         * `<root>/<packId>/`. Three would let a stray `Android/data/...` tree
         * pull the walk into another app's cache for no gain.
         */
        private const val MAX_DEPTH = 2

        /** Subdirectory names that are pack content, never a pack itself. */
        private val CONTENT_DIRS = setOf(
            "fonts", "routing-graph", "style", "sprites", "tiles", "glyphs"
        )

        private val USB_ROOTS = listOf(
            "/storage/usb0", "/storage/usb1", "/storage/usbdisk",
            "/storage/usb_disk", "/mnt/usb_storage", "/storage/UsbDriveA"
        )
    }
}

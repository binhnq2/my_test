package com.ts8.core.map.maplibre

import java.io.File
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.tan
import kotlin.math.cos

/**
 * The expensive half of pack validation: checksums, SQLite, a real tile query,
 * glyphs, sprite and the routing graph.
 *
 * **Why this is separate from [MapPack.load].** `load` answers "is there a pack
 * here" in a few `stat` calls and must stay main-thread cheap so the UI can
 * respond on the first frame. This class answers "does this pack actually
 * work", which means hashing 20 MB and opening two SQLite databases. That runs
 * on the IO executor.
 *
 * **Why the SQLite part is behind [SqliteProbe].** `android.database.sqlite`
 * is unimplemented in a plain JVM unit test, so a validator that called it
 * directly could only be tested under Robolectric — which cannot be fetched
 * over this build host's network. The probe keeps the *decision logic* (which
 * tables, which columns, what counts as a pass) in testable Kotlin, and confines
 * the platform call to one class.
 */
class PackValidator(private val probe: SqliteProbe = AndroidSqliteProbe()) {

    /**
     * Everything the rest of the app needs to know about a pack's health.
     *
     * The three optional parts are reported separately on purpose: the brief
     * requires the map and search to keep working when the routing graph is
     * missing, and the map and routing to keep working when `places.db` is
     * missing. A single boolean cannot express that.
     */
    data class Report(
        val ok: Boolean,
        val code: PackFailureCode?,
        val message: String?,
        val checksumOk: Boolean,
        val mbtilesOk: Boolean,
        val placesOk: Boolean,
        val routingOk: Boolean,
        val glyphsOk: Boolean,
        val spriteOk: Boolean,
        val tileQuery: String?,
        val diagnostics: PackDiagnostics
    ) {
        /** Navigation is offered only when there is a graph to route on. */
        val navigationAvailable: Boolean get() = mbtilesOk && routingOk

        /** Offline search is offered only when there is a place index. */
        val searchAvailable: Boolean get() = placesOk
    }

    /**
     * Validates [pack] in the order the brief specifies, stopping at the first
     * *blocking* failure. Missing glyphs/sprite/places/routing are recorded but
     * do not block the map, because each has a working degraded mode.
     */
    fun validate(pack: MapPack, diagnostics: PackDiagnostics = PackDiagnostics()): Report {
        val checksumOk = verifyChecksums(pack, diagnostics)
        if (!checksumOk) {
            return Report(
                ok = false,
                code = diagnostics.failureCode ?: PackFailureCode.CHECKSUM_MISMATCH,
                message = "Map pack bị hỏng: checksum không khớp. Chép lại pack hoặc chọn file khác.",
                checksumOk = false, mbtilesOk = false, placesOk = false,
                routingOk = false, glyphsOk = false, spriteOk = false,
                tileQuery = null, diagnostics = diagnostics
            )
        }

        val mbtiles = validateMbTiles(pack, diagnostics)
        if (!mbtiles.ok) {
            return Report(
                ok = false,
                code = PackFailureCode.MBTILES_OPEN_FAILED,
                message = "Không mở được file bản đồ ${pack.metadata.basemap}: ${mbtiles.detail}",
                checksumOk = true, mbtilesOk = false, placesOk = false,
                routingOk = false, glyphsOk = false, spriteOk = false,
                tileQuery = null, diagnostics = diagnostics
            )
        }

        val places = validatePlaces(pack, diagnostics)
        val routing = validateRouting(pack, diagnostics)
        val glyphs = validateGlyphs(pack, diagnostics)
        val sprite = validateSprite(pack, diagnostics)

        return Report(
            ok = true,
            code = null,
            message = null,
            checksumOk = true,
            mbtilesOk = true,
            placesOk = places,
            routingOk = routing,
            glyphsOk = glyphs,
            spriteOk = sprite,
            tileQuery = diagnostics.tileQuerySummary,
            diagnostics = diagnostics
        )
    }

    // -- checksum ----------------------------------------------------------

    /**
     * Verifies `checksums.sha256` when the pack ships one.
     *
     * A pack without the file is accepted: the format spec calls it part of the
     * layout, but older packs predate it, and refusing to open them would turn
     * a working map into an error. A pack *with* the file that fails is
     * rejected, because that is the only case where the file can tell us
     * something the `stat` checks cannot — a corrupted copy.
     */
    private fun verifyChecksums(pack: MapPack, diagnostics: PackDiagnostics): Boolean {
        diagnostics.note(PackStage.VALIDATING_CHECKSUM, "start")
        val manifest = File(pack.directory, "checksums.sha256")
        if (!manifest.isFile) {
            diagnostics.checksumSummary = "no checksums.sha256 (skipped)"
            diagnostics.note(PackStage.VALIDATING_CHECKSUM, "skipped: no manifest")
            return true
        }

        var checked = 0
        var mismatch = 0
        var missing = 0
        var firstBad: String? = null

        runCatching {
            manifest.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                // "<hex>  <relative path>" — two spaces, per sha256sum(1).
                val split = line.indexOf("  ")
                if (split <= 0) return@forEachLine
                val expected = line.substring(0, split).lowercase()
                val relative = line.substring(split + 2).trim()
                if (relative.isEmpty()) return@forEachLine

                val file = File(pack.directory, relative)
                if (!file.isFile) {
                    missing++
                    if (firstBad == null) firstBad = "$relative missing"
                    return@forEachLine
                }
                val actual = sha256(file)
                checked++
                if (actual != expected) {
                    mismatch++
                    if (firstBad == null) firstBad = "$relative mismatch"
                }
            }
        }.onFailure { e ->
            diagnostics.checksumSummary = "unreadable: ${e.message}"
            diagnostics.fail(PackStage.VALIDATING_CHECKSUM, PackFailureCode.CHECKSUM_MISMATCH, "manifest unreadable")
            return false
        }

        diagnostics.checksumSummary = "ok=$checked mismatch=$mismatch missing=$missing"
        return if (mismatch == 0 && missing == 0) {
            diagnostics.note(PackStage.VALIDATING_CHECKSUM, "ok=$checked")
            true
        } else {
            diagnostics.fail(
                PackStage.VALIDATING_CHECKSUM,
                PackFailureCode.CHECKSUM_MISMATCH,
                "first=$firstBad"
            )
            false
        }
    }

    // -- mbtiles -----------------------------------------------------------

    private data class PartResult(val ok: Boolean, val detail: String)

    /**
     * Opens the tile container, checks the tables MapLibre's reader queries,
     * then runs the same indexed lookup it will run — at the pack's own centre,
     * at the deepest zoom the pack has.
     *
     * A tile that exists per `metadata` but returns no row at a coordinate
     * inside the declared bounds means the pack is internally inconsistent, and
     * the driver would see a blank map over an otherwise "valid" pack. This is
     * the check that would have caught it.
     */
    private fun validateMbTiles(pack: MapPack, diagnostics: PackDiagnostics): PartResult {
        diagnostics.note(PackStage.VALIDATING_FILES, "mbtiles open")
        val db = File(pack.directory, pack.metadata.basemap)
        val opened = probe.openReadOnly(db.absolutePath)
        if (!opened.ok) {
            diagnostics.sqliteSummary = "open failed: ${opened.detail}"
            diagnostics.fail(PackStage.VALIDATING_FILES, PackFailureCode.MBTILES_OPEN_FAILED, opened.detail)
            return PartResult(false, opened.detail)
        }
        val session = opened.session!!
        try {
            val tables = session.tableNames()
            val hasMeta = tables.contains("metadata")
            val hasTiles = tables.contains("tiles")
            val hasFlat = tables.contains("map") && tables.contains("images")
            if (!hasTiles) {
                // `tiles` is what MapLibre's reader queries. A flat
                // (map+images) schema is legal MBTiles but unreadable by the
                // shipped native source, so it must be rejected here rather
                // than rendering blank.
                val detail = "tables=${tables.joinToString(",")} missing=tiles" +
                    if (hasFlat) " (flat schema unsupported)" else ""
                diagnostics.sqliteSummary = detail
                diagnostics.fail(PackStage.VALIDATING_FILES, PackFailureCode.MBTILES_OPEN_FAILED, detail)
                return PartResult(false, detail)
            }

            var format = "pbf"
            var minZoom = pack.metadata.minZoom.toInt()
            var maxZoom = pack.metadata.maxZoom.toInt()
            if (hasMeta) {
                val meta = session.rows("SELECT name, value FROM metadata")
                for (row in meta) {
                    if (row.size < 2) continue
                    when (row[0]) {
                        "format" -> format = row[1] ?: format
                        "minzoom" -> row[1]?.toIntOrNull()?.let { minZoom = it }
                        "maxzoom" -> row[1]?.toIntOrNull()?.let { maxZoom = it }
                    }
                }
            }
            diagnostics.sqliteSummary = "tables=${tables.joinToString(",")} format=$format " +
                "minzoom=$minZoom maxzoom=$maxZoom"

            // The reader maps XYZ y to TMS itself (`mbtiles_file_source.cpp`:
            // `y = (2^iz - 1) - iy`), so the probe asks with the TMS row that
            // the same formula produces. Probing with the XYZ row would "pass"
            // on a pack whose rows happen to be stored flipped, which is the
            // exact confusion this check exists to avoid.
            val tile = tileAt(pack, minZoom, maxZoom)
            if (tile == null) {
                val detail = "no tile at centre inside bounds"
                diagnostics.tileQuerySummary = detail
                diagnostics.fail(PackStage.VALIDATING_FILES, PackFailureCode.MBTILES_OPEN_FAILED, detail)
                return PartResult(false, detail)
            }
            val sql = "SELECT length(tile_data), hex(substr(tile_data,1,2)) FROM tiles " +
                "WHERE zoom_level=? AND tile_column=? AND tile_row=?"
            val rows = session.rows(sql, tile.z.toString(), tile.x.toString(), tile.tmsY.toString())
            val bytes = rows.firstOrNull()?.getOrNull(0)?.toIntOrNull()
            val magic = rows.firstOrNull()?.getOrNull(1)?.lowercase()
            val summary = "z=${tile.z} x=${tile.x} y_tms=${tile.tmsY} bytes=${bytes ?: 0} magic=${magic ?: "-"}"
            diagnostics.tileQuerySummary = summary
            if (rows.isEmpty() || bytes == null || bytes <= 0) {
                diagnostics.fail(PackStage.VALIDATING_FILES, PackFailureCode.MBTILES_OPEN_FAILED, "tile query empty at $summary")
                return PartResult(false, "tile query empty at $summary")
            }
            diagnostics.note(PackStage.VALIDATING_FILES, "tile ok $summary")
            return PartResult(true, summary)
        } finally {
            session.close()
        }
    }

    /** A tile coordinate in both schemes, so the log is unambiguous. */
    private data class TileCoord(val z: Int, val x: Int, val xyzY: Int, val tmsY: Int)

    private fun tileAt(pack: MapPack, minZoom: Int, maxZoom: Int): TileCoord? {
        val center = pack.center ?: return null
        val z = maxOf(minZoom, minOf(maxZoom, pack.metadata.defaultZoom.toInt()))
        val n = 1 shl z
        val lat = center.lat.coerceIn(-85.05112878, 85.05112878)
        val lon = center.lon
        val x = (((lon + 180.0) / 360.0) * n).toInt().coerceIn(0, n - 1)
        val latRad = lat * PI / 180.0
        val y = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
        return TileCoord(z = z, x = x, xyzY = y, tmsY = (n - 1) - y)
    }

    // -- places.db ---------------------------------------------------------

    /**
     * Offline search is optional. Present but broken is a real failure the
     * search screen must be able to name — a `places.db` copied from an older
     * pack has the right filename and the wrong schema.
     */
    private fun validatePlaces(pack: MapPack, diagnostics: PackDiagnostics): Boolean {
        val file = pack.databaseFile()
        if (file == null) {
            diagnostics.placeDbSummary = "absent (search unavailable)"
            diagnostics.note(PackStage.VALIDATING_FILES, "places.db absent")
            return false
        }
        val opened = probe.openReadOnly(file.absolutePath)
        if (!opened.ok) {
            diagnostics.placeDbSummary = "open failed: ${opened.detail}"
            diagnostics.fail(PackStage.VALIDATING_FILES, PackFailureCode.PLACE_DB_OPEN_FAILED, opened.detail)
            return false
        }
        val session = opened.session!!
        try {
            val tables = session.tableNames()
            if (!tables.contains("places")) {
                diagnostics.placeDbSummary = "missing table places; tables=${tables.joinToString(",")}"
                diagnostics.fail(PackStage.VALIDATING_FILES, PackFailureCode.PLACE_DB_OPEN_FAILED, "missing places table")
                return false
            }
            val count = session.rows("SELECT COUNT(*) FROM places").firstOrNull()?.getOrNull(0)?.toIntOrNull() ?: 0
            val hasFts = tables.contains("places_fts")
            diagnostics.placeDbSummary = "places=$count fts4=$hasFts"
            if (count <= 0) {
                diagnostics.fail(PackStage.VALIDATING_FILES, PackFailureCode.PLACE_DB_OPEN_FAILED, "places table empty")
                return false
            }
            diagnostics.note(PackStage.VALIDATING_FILES, "places ok $count rows fts4=$hasFts")
            return true
        } finally {
            session.close()
        }
    }

    // -- routing graph -----------------------------------------------------

    /**
     * Compatibility is magic + version, read from the first 8 bytes of each
     * file. That is exactly what `RoutingGraph.open` / `GraphIndex.open` check,
     * so this cannot disagree with the reader about whether a graph is usable.
     */
    private fun validateRouting(pack: MapPack, diagnostics: PackDiagnostics): Boolean {
        val dir = pack.routingDir()
        if (dir == null) {
            diagnostics.routingSummary = "absent (navigation unavailable)"
            diagnostics.note(PackStage.VALIDATING_FILES, "routing-graph absent")
            return false
        }
        val bin = File(dir, "graph.bin")
        val idx = File(dir, "graph.idx")
        if (!bin.isFile || !idx.isFile) {
            diagnostics.routingSummary = "incomplete files bin=${bin.isFile} idx=${idx.isFile}"
            diagnostics.fail(PackStage.VALIDATING_FILES, PackFailureCode.ROUTING_GRAPH_INCOMPATIBLE, diagnostics.routingSummary!!)
            return false
        }
        val binHead = readHead(bin, 8)
        val idxHead = readHead(idx, 4)
        val binOk = binHead.size >= 8 && String(binHead, 0, 4, Charsets.US_ASCII) == "TS8G" &&
            (binHead[4].toInt() and 0xFF or ((binHead[5].toInt() and 0xFF) shl 8)) == ROUTING_FORMAT_VERSION
        val idxOk = idxHead.size >= 4 && String(idxHead, 0, 4, Charsets.US_ASCII) == "TS8I"
        diagnostics.routingSummary = "bin=$binOk idx=$idxOk version=$ROUTING_FORMAT_VERSION"
        if (!binOk || !idxOk) {
            diagnostics.fail(
                PackStage.VALIDATING_FILES,
                PackFailureCode.ROUTING_GRAPH_INCOMPATIBLE,
                diagnostics.routingSummary!!
            )
            return false
        }
        diagnostics.note(PackStage.VALIDATING_FILES, "routing ok")
        return true
    }

    // `InputStream.readNBytes(int)` is Java 9 and is NOT backported by
    // desugar_jdk_libs 2.0.4 (it ships DesugarFile/DesugarInputStream but no
    // java.io.FileInputStream). On API 23 the call throws
    // NoSuchMethodError — and because it sits inside `runCatching`, which
    // catches Throwable, the throw was swallowed into an empty array. The
    // visible result was worse than a crash: every pack reported
    // "bin=false idx=false" and was rejected as ROUTING_GRAPH_INCOMPATIBLE on
    // exactly the device that cannot show a log. Read in a loop instead.
    private fun readHead(file: File, count: Int): ByteArray = runCatching {
        val buf = ByteArray(count)
        var read = 0
        file.inputStream().use { input ->
            while (read < count) {
                val n = input.read(buf, read, count - read)
                if (n <= 0) break
                read += n
            }
        }
        if (read == count) buf else buf.copyOf(read)
    }.getOrDefault(ByteArray(0))

    // -- glyphs / sprite ---------------------------------------------------

    /**
     * Labels need glyph PBFs. A pack that declares them but ships none renders
     * geometry with no text — which reads as "labels are broken", not "this
     * pack is incomplete". Reported as a code so the diagnostics panel can say
     * which it is.
     */
    private fun validateGlyphs(pack: MapPack, diagnostics: PackDiagnostics): Boolean {
        val declared = pack.metadata.glyphs
        if (declared == null) {
            diagnostics.styleSummary = (diagnostics.styleSummary ?: "") + " glyphs=undeclared"
            return false
        }
        val dir = pack.resolveGlyphsDir()
        if (dir == null) {
            diagnostics.styleSummary = (diagnostics.styleSummary ?: "") + " glyphs=missing dir"
            return false
        }
        // One real file must exist: a directory of empty subdirectories passes
        // isDirectory() and still renders nothing.
        val first = dir.listFiles()?.firstOrNull { it.isDirectory }
            ?.listFiles()?.firstOrNull { it.isFile && it.length() > 0L }
        diagnostics.styleSummary = (diagnostics.styleSummary ?: "") +
            " glyphs=${if (first != null) "ok" else "empty"}"
        if (first == null) {
            return false
        }
        return true
    }

    private fun validateSprite(pack: MapPack, diagnostics: PackDiagnostics): Boolean {
        val declared = pack.metadata.sprite
        if (declared.isNullOrBlank()) return false
        val resolved = pack.resolveSprite()
        if (resolved == null) {
            diagnostics.fail(PackStage.VALIDATING_FILES, PackFailureCode.SPRITE_MISSING, "declared=$declared files absent")
            return false
        }
        return true
    }

    companion object {
        /** `graph.bin` header byte 4..5, per FORMAT_SPECS §3.1. */
        const val ROUTING_FORMAT_VERSION = 1

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val bytes = digest.digest()
            val out = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            return out.toString()
        }

        private val HEX = "0123456789abcdef".toCharArray()
    }
}

/**
 * The smallest SQLite surface the validator needs.
 *
 * Narrow on purpose: every method here is one line in [AndroidSqliteProbe], so a
 * test fake is trivial and the validator's logic stays platform-free.
 */
interface SqliteProbe {
    fun openReadOnly(absolutePath: String): OpenResult

    data class OpenResult(val ok: Boolean, val session: Session?, val detail: String)

    interface Session {
        fun tableNames(): List<String>

        /**
         * Runs a query and returns rows as string cells. Only `metadata` and
         * integer aggregates are read, so text is enough and avoids pulling
         * cursor types into the interface.
         */
        fun rows(sql: String, vararg args: String): List<List<String?>>

        fun close()
    }
}

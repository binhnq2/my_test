package com.ts8.core.map.maplibre

import java.io.File

/**
 * Structured diagnostics for the map-pack pipeline.
 *
 * **Why a class and not `Log.d` calls.** "Map pack did not load" has half a
 * dozen genuinely different causes — no permission, no folder, a folder one
 * level too deep, a corrupt SQLite file, a graph built by an older tool — and
 * every one of them used to surface as the same blank screen. A driver on the
 * side of the road cannot read logcat, and a remote support call cannot either.
 * Collecting the pipeline as an ordered list of [Stage] events means the same
 * object can (a) be written to logcat verbatim, (b) be rendered in the Map
 * Management diagnostics panel, and (c) be asserted in a test.
 *
 * The stages below are the ones the repair brief names, in the order they must
 * occur. A failure records the stage it happened at plus one of [Code], so the
 * first bad transition is always identifiable rather than inferred.
 */
enum class PackStage {
    DISCOVERING,
    FOUND,
    VALIDATING_METADATA,
    VALIDATING_FILES,
    VALIDATING_CHECKSUM,
    IMPORTING,
    ACTIVATING,
    READY
}

/** The failure taxonomy the brief requires, one code per real cause. */
enum class PackFailureCode {
    /** No candidate directory on any root contained a manifest. */
    PACK_NOT_FOUND,

    /** Storage was not readable because the runtime permission was not held. */
    PERMISSION_DENIED,

    /** `metadata.json` missing, unreadable, or not valid JSON. */
    INVALID_METADATA,

    /** `formatVersion` is not one this build understands. */
    UNSUPPORTED_SCHEMA,

    /** A file does not match the hash recorded in `checksums.sha256`. */
    CHECKSUM_MISMATCH,

    /** Not enough room to stage an imported pack. */
    INSUFFICIENT_STORAGE,

    /** The `.mbtiles` file exists but SQLite cannot open or query it. */
    MBTILES_OPEN_FAILED,

    /** The style was rejected by the renderer before it could draw. */
    STYLE_LOAD_FAILED,

    /** The style needs label glyphs and the pack's glyph directory is absent. */
    GLYPH_MISSING,

    /** The pack declares a sprite but the sprite files are absent. */
    SPRITE_MISSING,

    /** `places.db` is present but SQLite cannot open it or a table is missing. */
    PLACE_DB_OPEN_FAILED,

    /** `routing-graph/` is absent, or its magic/version is not compatible. */
    ROUTING_GRAPH_INCOMPATIBLE,

    /** The staged pack could not be moved into place. */
    ACTIVATION_FAILED
}

/**
 * One observed step. [detail] is a stable, machine-greppable line; [message] is
 * the sentence shown to the driver for the step that failed.
 */
data class PackEvent(
    val stage: PackStage,
    val detail: String,
    val ok: Boolean = true
)

/**
 * The record of one traversal of the pack pipeline.
 *
 * Every field the brief asks for under "Log tối thiểu" has a home here:
 * candidate roots, the resolved canonical path, pack id/version/schema, file
 * existence + size + checksum, SQLite open result with required tables, a tile
 * query at a real z/x/y, the resolved style source URL, and routing-graph
 * compatibility.
 */
class PackDiagnostics {

    private val events = ArrayList<PackEvent>(24)

    /** Every directory that was considered, with why it was accepted or not. */
    val candidates = ArrayList<String>(16)

    var resolvedPath: String? = null
    var resolvedUri: String? = null
    var packId: String? = null
    var formatVersion: Int = -1
    var metadataBytes: Long = -1

    /** `"name size=NNN sha256=..."` per validated file. */
    val files = ArrayList<String>(24)
    var checksumSummary: String? = null
    var sqliteSummary: String? = null
    var placeDbSummary: String? = null
    var tileQuerySummary: String? = null
    var styleSummary: String? = null
    var routingSummary: String? = null

    /** Set on the event that made the pipeline stop. */
    var failureCode: PackFailureCode? = null
    var failureStage: PackStage? = null

    fun note(stage: PackStage, detail: String): PackDiagnostics = apply {
        events += PackEvent(stage, detail, ok = true)
    }

    fun fail(stage: PackStage, code: PackFailureCode, detail: String): PackDiagnostics = apply {
        events += PackEvent(stage, detail, ok = false)
        failureCode = code
        failureStage = stage
    }

    fun candidate(detail: String): PackDiagnostics = apply {
        if (candidates.size < MAX_CANDIDATES) candidates += detail
    }

    fun file(detail: String): PackDiagnostics = apply {
        if (files.size < MAX_FILES) files += detail
    }

    val failed: Boolean get() = failureCode != null

    /** Ordered stage trail, e.g. `DISCOVERING → FOUND → VALIDATING_METADATA`. */
    fun trail(): String = events.joinToString(" → ") { it.stage.name }

    /**
     * The full report as newline-separated `key=value` lines.
     *
     * Deliberately free of any coordinate, so it can be copied out with the
     * brief's "no personal location outside diagnostics mode" rule intact — a
     * tile z/x/y is a pack fact, not a driver position.
     */
    fun report(): String = buildString {
        appendLine("stage_trail=${trail()}")
        appendLine("failure=${failureStage?.name ?: "none"}${failureCode?.let { " code=$it" }.orEmpty()}")
        appendLine("candidates=${candidates.size}")
        for (c in candidates) appendLine("  candidate=$c")
        resolvedPath?.let { appendLine("resolved_path=$it") }
        resolvedUri?.let { appendLine("resolved_uri=$it") }
        packId?.let { appendLine("pack_id=$it") }
        if (formatVersion >= 0) appendLine("format_version=$formatVersion")
        if (metadataBytes >= 0) appendLine("metadata_bytes=$metadataBytes")
        for (f in files) appendLine("  file=$f")
        checksumSummary?.let { appendLine("checksum=$it") }
        sqliteSummary?.let { appendLine("mbtiles=$it") }
        tileQuerySummary?.let { appendLine("tile_query=$it") }
        placeDbSummary?.let { appendLine("places_db=$it") }
        styleSummary?.let { appendLine("style=$it") }
        routingSummary?.let { appendLine("routing=$it") }
    }

    /** Compact one-liner for the Map Management list. */
    fun summary(): String = buildString {
        append(failureCode?.name ?: "READY")
        resolvedPath?.let { append(" · ").append(File(it).name) }
        checksumSummary?.let { append(" · ").append(it) }
    }

    private companion object {
        const val MAX_CANDIDATES = 24
        const val MAX_FILES = 32
    }
}

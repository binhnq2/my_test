package com.ts8.core.music

/**
 * Incremental scan bookkeeping.
 *
 * A full MediaStore walk on this head unit takes tens of seconds and pins the
 * CPU while the map is trying to render. The spec requires incremental scans,
 * so this interface is the contract the scanner honours: it records what was
 * seen last time and only re-probes what changed.
 */
interface MediaScanner {
    /**
     * Runs a scan.
     *
     * @param forceFullScan ignore the saved state and re-read everything. Used
     *        only by the diagnostics screen and after a database rebuild.
     * @param onProgress called on a background thread with 0..1.
     * @return a summary of what changed.
     */
    fun scan(forceFullScan: Boolean = false, onProgress: (Float) -> Unit = {}): ScanResult

    /** True when a scan can run right now (storage readable, not already running). */
    fun canScan(): Boolean

    /** Cancels a running scan. Safe to call when nothing is running. */
    fun cancel()
}

data class ScanResult(
    val scannedFiles: Int,
    val added: Int,
    val updated: Int,
    val removed: Int,
    /** Files skipped because their codec failed a decode probe. */
    val unsupported: Int,
    val durationMs: Long,
    val usbScanned: Boolean,
    val error: String? = null
) {
    val changedAnything: Boolean get() = added > 0 || updated > 0 || removed > 0
}

/**
 * Codec support as this device actually implements it.
 *
 * The spec requires FLAC to be offered only after a codec test, because the
 * TS8 firmware's MediaCodec list is not the AOSP one. Claiming support and then
 * failing at playback is the exact behaviour the spec forbids.
 */
interface CodecSupport {
    /** True when the platform reports a decoder for this MIME type. */
    fun isSupported(mimeType: String): Boolean

    /**
     * Verifies a codec by actually decoding a short generated buffer of that
     * format. Called once at startup for FLAC and any other format whose
     * support the device may not report honestly.
     */
    fun probe(mimeType: String): Boolean

    /** Formats this device can actually decode right now. */
    fun supportedMimeTypes(): Set<String>

    /** Human-readable note for the diagnostics screen. */
    fun report(): List<CodecReport>
}

data class CodecReport(
    val mimeType: String,
    val displayName: String,
    val platformClaims: Boolean,
    val probePassed: Boolean?
) {
    val usable: Boolean get() = platformClaims && (probePassed == null || probePassed)
}

/** Shared constants for the music feature. */
object MusicFormats {
    const val MIME_MP3 = "audio/mpeg"
    const val MIME_AAC = "audio/mp4a-latm"
    const val MIME_M4A = "audio/mp4"
    const val MIME_OGG = "audio/ogg"
    const val MIME_WAV = "audio/x-wav"
    const val MIME_FLAC = "audio/flac"

    /** Always offered: the platform guarantees these. */
    val ALWAYS_SUPPORTED = setOf(MIME_MP3, MIME_AAC, MIME_M4A, MIME_OGG, MIME_WAV)

    /** Offered only when [CodecSupport.probe] passes. */
    val PROBE_REQUIRED = setOf(MIME_FLAC)

    private val byExtension = mapOf(
        "mp3" to MIME_MP3,
        "aac" to MIME_AAC,
        "m4a" to MIME_M4A,
        "mp4" to MIME_M4A,
        "ogg" to MIME_OGG,
        "oga" to MIME_OGG,
        "opus" to MIME_OGG,
        "wav" to MIME_WAV,
        "flac" to MIME_FLAC
    )

    /** MIME type for a file name, or null when the extension is not audio. */
    fun mimeForFileName(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return null
        return byExtension[name.substring(dot + 1).lowercase()]
    }

    fun isAudioFileName(name: String): Boolean = mimeForFileName(name) != null

    /** Display name for the badge in the music screen. */
    fun displayName(mime: String): String = when (mime) {
        MIME_MP3 -> "MP3"
        MIME_AAC, MIME_M4A -> "AAC"
        MIME_OGG -> "OGG"
        MIME_WAV -> "WAV"
        MIME_FLAC -> "FLAC"
        else -> mime.substringAfterLast('/').uppercase()
    }
}

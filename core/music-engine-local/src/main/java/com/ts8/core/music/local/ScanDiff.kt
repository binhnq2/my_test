package com.ts8.core.music.local

import com.ts8.core.music.MusicFormats
import com.ts8.core.music.Track

/**
 * Stable ids for files that the platform database does not number.
 *
 * MediaStore hands out small sequential ids, so a USB file identified by a
 * path hash is pushed into a different range with bit 62 set. Without that, a
 * hash that happened to land on a MediaStore id would silently overwrite an
 * unrelated track — a class of bug that only shows up on one car.
 *
 * FNV-1a over the UTF-16 code units of the path: cheap, no allocation, and
 * stable across reboots because it depends only on the path, which is what
 * makes incremental rescan possible.
 */
object TrackIds {
    /** Bit marking an id as a path hash rather than a MediaStore row id. */
    private const val USB_BIT = 1L shl 62

    private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
    private const val FNV_PRIME = 0x100000001b3L

    /**
     * 62-bit FNV-1a hash of a path, with bit 62 always set.
     *
     * The result is positive and larger than any realistic MediaStore row id,
     * so the two id spaces cannot collide even by accident.
     */
    fun forUsbPath(path: String): Long {
        var hash = FNV_OFFSET
        for (ch in path) {
            hash = hash xor (ch.code.toLong() and 0xFFFF)
            hash *= FNV_PRIME
        }
        return (hash and (USB_BIT - 1)) or USB_BIT
    }

    fun isUsb(id: Long): Boolean = (id and USB_BIT) != 0L
}

/**
 * One file the scanner found, before it becomes a [Track].
 *
 * Split from [Track] because the diff runs on data the scanner already has in
 * hand: building full [Track] objects for files that turn out to be unchanged
 * is the main avoidable cost of a 2 000-file rescan.
 */
data class ScannedFile(
    val path: String,
    val sizeBytes: Long,
    val modifiedSeconds: Long,
    val id: Long,
    val sourceKind: Track.SourceKind,
    val mimeType: String?,
    /** Parsed from the container when the fast reader succeeded. */
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumId: Long? = null,
    val artistId: Long? = null,
    val durationMs: Long = 0,
    val trackNumber: Int = 0,
    val year: Int = 0
)

/** What a scan did. Mirrors [com.ts8.core.music.ScanResult]'s counts. */
data class ScanDiff(
    val added: List<ScannedFile>,
    val updated: List<ScannedFile>,
    val removed: List<Long>,
    val unchanged: Int
)

/**
 * Incremental scan diff — pure logic, no Android types.
 *
 * The rule the spec asks for is "incremental, not a full rescan every boot".
 * Reduced to what it actually means: a file is *updated* when its size or mtime
 * changed, *unchanged* otherwise, and a row is *removed* when no file claimed
 * it. Track metadata is not compared, because reading tags to decide whether to
 * re-read tags is the full scan this exists to avoid.
 */
object ScanDiffCalculator {

    /**
     * @param existing id → (size, mtime) as currently stored.
     * @param found everything the file-system/MediaStore walk produced.
     * @param scopedTo if given, only rows from this source kind may be removed;
     *        a scan that did not look at the USB stick must not delete USB rows.
     */
    fun diff(
        existing: Map<Long, Pair<Long, Long>>,
        existingKind: Map<Long, Track.SourceKind>,
        found: List<ScannedFile>,
        scopedTo: Track.SourceKind? = null
    ): ScanDiff {
        val added = ArrayList<ScannedFile>()
        val updated = ArrayList<ScannedFile>()
        var unchanged = 0

        val seen = HashSet<Long>(found.size * 2)
        for (file in found) {
            seen.add(file.id)
            val prior = existing[file.id]
            when {
                prior == null -> added.add(file)
                prior.first != file.sizeBytes || prior.second != file.modifiedSeconds ->
                    updated.add(file)
                else -> unchanged++
            }
        }

        val removed = ArrayList<Long>()
        for (id in existing.keys) {
            if (id in seen) continue
            if (scopedTo != null && existingKind[id] != scopedTo) continue
            removed.add(id)
        }

        return ScanDiff(added, updated, removed, unchanged)
    }

    /**
     * Whether a file should be indexed at all.
     *
     * Two independent filters, and both matter:
     * - The extension must be a known audio container, otherwise a USB stick
     *   full of photos costs a tag read each.
     * - A codec the device cannot decode must not enter the library, or the
     *   driver taps a track and gets a failure. FLAC is the case the spec calls
     *   out; the caller passes the result of the codec probe.
     *
     * The extension is authoritative when it is one we know: the TS8's
     * MediaStore reports `audio/mpeg` for `.m4a` files often enough that
     * trusting the reported type alone loses real tracks.
     */
    fun shouldIndex(name: String, mimeType: String?, isCodecUsable: (String) -> Boolean): Boolean {
        val byName = MusicFormats.mimeForFileName(name)
        if (byName != null) return isCodecUsable(byName)

        // No known extension: accept only a MIME type this build definitely
        // decodes, so a tagged-but-oddly-named file is not lost.
        if (mimeType == null) return false
        if (mimeType !in MusicFormats.ALWAYS_SUPPORTED && mimeType !in MusicFormats.PROBE_REQUIRED) {
            return false
        }
        return isCodecUsable(mimeType)
    }

    /** Fallback title for an untagged file: the file name without extension. */
    fun titleFromFileName(name: String): String {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        return stem.replace('_', ' ').trim().ifEmpty { name }
    }
}

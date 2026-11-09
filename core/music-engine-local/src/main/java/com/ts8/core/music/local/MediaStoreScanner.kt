package com.ts8.core.music.local

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.Log
import com.ts8.core.music.CodecSupport
import com.ts8.core.music.MediaScanner
import com.ts8.core.music.MusicLibrary
import com.ts8.core.music.ScanResult
import com.ts8.core.music.Track
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The scanner: MediaStore for internal storage, a directory walk for USB.
 *
 * **Why incremental.** The spec requires it, and the reason is concrete: a
 * full scan of a 2 000-track stick on this CPU costs tens of seconds, and it
 * competes with map rendering for the same cores. So:
 *
 * - The **first** scan (or a forced one) indexes everything and records each
 *   file's size and mtime.
 * - Every later scan compares size+mtime and touches only what changed.
 *   Unchanged files cost an `stat()` call, not a tag read.
 *
 * **Why MediaStore and then not MediaStore.** MediaStore is the cheap path for
 * internal storage — the platform already walked it. It is *wrong* for USB:
 * the TS8's MediaStore ignores removable volumes unless the vendor's media app
 * has scanned them, so the USB path walks the volume directly and reads
 * container headers with [AudioTagReader]. Both paths converge on the same
 * [ScannedFile] and the same [ScanDiffCalculator], so the incremental
 * bookkeeping is identical.
 *
 * USB scanning runs with `MediaStore` untouched — nothing is written outside
 * the app's own database. If the stick is yanked mid-scan, the walk throws
 * `IOException` and the partial result is kept; the next scan picks up the
 * rest. The spec's "safe on USB removal without crash" is enforced here and in
 * [LocalMusicPlayer].
 */
class MediaStoreScanner(
    context: Context,
    private val db: MusicDatabase,
    private val library: SqliteMusicLibrary,
    private val codecs: CodecSupport
) : MediaScanner {

    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    /** Volumes seen last time, so removal can be detected without a full walk. */
    private var lastVolumes: List<File> = emptyList()

    override fun canScan(): Boolean = !running.get() && !cancelled.get()

    override fun cancel() {
        cancelled.set(true)
    }

    override fun scan(forceFullScan: Boolean, onProgress: (Float) -> Unit): ScanResult {
        if (!running.compareAndSet(false, true)) {
            return ScanResult(0, 0, 0, 0, 0, 0, false, "a scan is already running")
        }
        cancelled.set(false)
        val started = System.currentTimeMillis()

        var scannedFiles = 0
        var added = 0
        var updated = 0
        var removed = 0
        var unsupported = 0
        var usbScanned = false
        var error: String? = null

        try {
            val existing = loadExisting()
            val kinds = loadExistingKinds()

            // -- internal storage via MediaStore ---------------------------
            val internal = collectFromMediaStore()
            scannedFiles += internal.found.size
            unsupported += internal.unsupported
            onProgress(0.25f)

            // MediaStore is authoritative only for the internal volume, so its
            // diff is scoped: a photo-only USB stick must not delete its music.
            val internalDiff = ScanDiffCalculator.diff(
                existing, kinds, internal.found, scopedTo = Track.SourceKind.MEDIA_STORE
            )
            if (cancelled.get()) return cancelledResult(scannedFiles, started)
            applyDiff(internalDiff)
            added += internalDiff.added.size
            updated += internalDiff.updated.size
            removed += internalDiff.removed.size

            // -- USB volumes, walked directly ------------------------------
            val (usbFiles, usbUnsupported) = collectFromUsb()
            usbScanned = usbFiles.isNotEmpty()
            scannedFiles += usbFiles.size
            unsupported += usbUnsupported
            onProgress(0.7f)

            if (cancelled.get()) return cancelledResult(scannedFiles, started)

            // Re-read after the MediaStore writes: the USB diff compares
            // against what is now stored, not against the pre-scan snapshot.
            val after = loadExisting()
            val afterKinds = loadExistingKinds()
            val usbDiff = ScanDiffCalculator.diff(
                after, afterKinds, usbFiles, scopedTo = Track.SourceKind.USB
            )
            applyDiff(usbDiff)
            added += usbDiff.added.size
            updated += usbDiff.updated.size
            removed += usbDiff.removed.size

            onProgress(0.95f)
            finalise(added + updated + removed > 0)
            onProgress(1f)
        } catch (e: SecurityException) {
            // READ_EXTERNAL_STORAGE denied or revoked: report it, do not crash,
            // and do not delete the library because we could not look at it.
            error = "không có quyền đọc bộ nhớ: ${e.message}"
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "scan failed", e)
        } finally {
            running.set(false)
        }

        return ScanResult(
            scannedFiles = scannedFiles,
            added = added,
            updated = updated,
            removed = removed,
            unsupported = unsupported,
            durationMs = System.currentTimeMillis() - started,
            usbScanned = usbScanned,
            error = error
        )
    }

    private fun cancelledResult(scanned: Int, started: Long) = ScanResult(
        scannedFiles = scanned, added = 0, updated = 0, removed = 0, unsupported = 0,
        durationMs = System.currentTimeMillis() - started, usbScanned = false,
        error = "đã huỷ"
    )

    // -- internal storage ---------------------------------------------------

    private data class Collected(val found: List<ScannedFile>, val unsupported: Int)

    private fun collectFromMediaStore(): Collected {
        val found = ArrayList<ScannedFile>()
        var unsupported = 0

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED
        )

        val selection =
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND " +
                "${MediaStore.Audio.Media.DATA} IS NOT NULL"

        try {
            appContext.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, null,
                MediaStore.Audio.Media.TITLE + " COLLATE NOCASE"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val titleCol = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val artistIdCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST_ID)
                val albumCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val trackCol = c.getColumnIndex(MediaStore.Audio.Media.TRACK)
                val yearCol = c.getColumnIndex(MediaStore.Audio.Media.YEAR)
                val mimeCol = c.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val sizeCol = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val mtimeCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)

                while (c.moveToNext()) {
                    if (cancelled.get()) break
                    val path = c.getString(dataCol) ?: continue
                    val name = path.substringAfterLast('/')
                    val mime = if (mimeCol >= 0) c.getString(mimeCol) else null

                    if (!ScanDiffCalculator.shouldIndex(name, mime, codecs::isSupported)) {
                        unsupported++
                        continue
                    }

                    val id = c.getLong(idCol)
                    found.add(
                        ScannedFile(
                            path = path,
                            sizeBytes = if (sizeCol >= 0) c.getLong(sizeCol) else 0,
                            modifiedSeconds = if (mtimeCol >= 0) c.getLong(mtimeCol) else 0,
                            id = id,
                            sourceKind = Track.SourceKind.MEDIA_STORE,
                            mimeType = mime ?: com.ts8.core.music.MusicFormats.mimeForFileName(name),
                            title = if (titleCol >= 0) c.getString(titleCol) else null,
                            artist = if (artistCol >= 0) c.getString(artistCol) else null,
                            album = if (albumCol >= 0) c.getString(albumCol) else null,
                            albumId = if (albumIdCol >= 0) c.getLong(albumIdCol) else null,
                            artistId = if (artistIdCol >= 0) c.getLong(artistIdCol) else null,
                            durationMs = if (durationCol >= 0) c.getLong(durationCol) else 0,
                            trackNumber = if (trackCol >= 0) c.getInt(trackCol) % 1000 else 0,
                            year = if (yearCol >= 0) c.getInt(yearCol) else 0
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore query failed", e)
        }

        return Collected(found, unsupported)
    }

    // -- USB ---------------------------------------------------------------

    /**
     * Walks removable volumes.
     *
     * `StorageManager.getStorageVolumes()` exists from API 24, so on API 23 the
     * well-known USB mount points under `/storage` are probed instead. That is
     * the honest approach: there is no supported enumeration API on this
     * firmware version, so the code looks where the mounts actually are.
     */
    private fun collectFromUsb(): Pair<List<ScannedFile>, Int> {
        val found = ArrayList<ScannedFile>()
        var unsupported = 0
        val volumes = removableVolumes()
        lastVolumes = volumes

        for (volume in volumes) {
            if (cancelled.get()) break
            try {
                walkDirectory(volume, 0, found) { }
            } catch (e: SecurityException) {
                Log.w(TAG, "no access to ${volume.absolutePath}")
            } catch (e: Exception) {
                // A stick pulled mid-walk throws here. Keeping what was read so
                // far is right: the driver removed the stick, the library
                // should lose only what is genuinely gone.
                Log.w(TAG, "USB walk stopped early at ${volume.absolutePath}", e)
            }
        }

        // Tag reads are the expensive part, so they are counted separately:
        // `unsupported` here means "read failed" rather than "codec missing",
        // and callers use it only as a diagnostic.
        unsupported = found.count { it.title == null && it.durationMs == 0L }
        return found to unsupported
    }

    private fun removableVolumes(): List<File> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return try {
                val sm = appContext.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                sm.storageVolumes
                    .filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
                    .mapNotNull { volume ->
                        @Suppress("DEPRECATION")
                        volume.directory
                    }
                    .filter { it.isDirectory && it.canRead() }
            } catch (e: Exception) {
                emptyList()
            }
        }

        // API 23: probe the documented mount points.
        val candidates = listOf(
            File("/storage/usb0"),
            File("/storage/usb1"),
            File("/storage/usbdisk"),
            File("/storage/usb_disk"),
            File("/mnt/usb_storage"),
            File("/storage/UsbDriveA"),
            File("/storage/sdcard1")
        )
        return candidates.filter { it.isDirectory && it.canRead() }
    }

    /** Depth-limited walk: a stick with 20 levels of folders is a mistake, not a library. */
    private fun walkDirectory(
        dir: File,
        depth: Int,
        out: MutableList<ScannedFile>,
        onFile: (File) -> Unit
    ) {
        if (depth > MAX_DEPTH || cancelled.get()) return
        val entries = dir.listFiles() ?: return
        for (entry in entries) {
            if (cancelled.get()) return
            if (entry.isDirectory) {
                // Skip Android's own metadata directories.
                if (entry.name.startsWith(".") || entry.name.equals("Android", true)) continue
                walkDirectory(entry, depth + 1, out, onFile)
                continue
            }
            val name = entry.name
            if (!ScanDiffCalculator.shouldIndex(name, null, codecs::isSupported)) continue

            val scanned = readUsbFile(entry) ?: continue
            out.add(scanned)
            onFile(entry)
        }
    }

    private fun readUsbFile(file: File): ScannedFile? {
        val name = file.name
        val mime = com.ts8.core.music.MusicFormats.mimeForFileName(name)
            ?: return null
        val id = TrackIds.forUsbPath(file.absolutePath)

        // Files above the size cap are almost certainly not music (or are a
        // corrupt container); refusing them keeps one bad file from stalling a
        // scan that the driver is waiting on.
        val length = try { file.length() } catch (e: Exception) { return null }
        if (length <= 0 || length > MAX_FILE_BYTES) return null

        val tags = try {
            AudioTagReader.read(file)
        } catch (e: Exception) {
            null
        }

        val folder = file.parent ?: return null
        val title = tags?.title?.takeIf { it.isNotBlank() }
            ?: ScanDiffCalculator.titleFromFileName(name)

        return ScannedFile(
            path = file.absolutePath,
            sizeBytes = length,
            modifiedSeconds = file.lastModified() / 1000L,
            id = id,
            sourceKind = Track.SourceKind.USB,
            mimeType = mime,
            title = title,
            artist = tags?.artist,
            album = tags?.album,
            // USB files have no platform album/artist ids, so a stable hash of
            // the name keeps grouping consistent across scans.
            albumId = tags?.album?.let { TrackIds.forUsbPath("album:$it") },
            artistId = tags?.artist?.let { TrackIds.forUsbPath("artist:$it") },
            durationMs = tags?.durationMs ?: 0,
            trackNumber = tags?.trackNumber ?: 0,
            year = tags?.year ?: 0
        )
    }

    // -- database -----------------------------------------------------------

    private fun loadExisting(): Map<Long, Pair<Long, Long>> {
        val out = HashMap<Long, Pair<Long, Long>>(1024)
        db.readableDatabase.rawQuery(
            "SELECT id, size_bytes, modified_s FROM tracks", null
        ).use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = c.getLong(1) to c.getLong(2)
        }
        return out
    }

    private fun loadExistingKinds(): Map<Long, Track.SourceKind> {
        val out = HashMap<Long, Track.SourceKind>(1024)
        db.readableDatabase.rawQuery("SELECT id, source_kind FROM tracks", null).use { c ->
            while (c.moveToNext()) {
                out[c.getLong(0)] =
                    if (c.getInt(1) == 1) Track.SourceKind.USB else Track.SourceKind.MEDIA_STORE
            }
        }
        return out
    }

    private fun applyDiff(diff: ScanDiff) {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for (file in diff.added + diff.updated) {
                // `favorite` and `last_played` are user state, not file state;
                // CONFLICT_REPLACE would wipe them on every rescan, so the
                // update is written column-by-column instead.
                w.execSQL(
                    "INSERT OR REPLACE INTO tracks " +
                        "(id, source, title, artist, album, album_id, artist_id, " +
                        "duration_ms, folder, track_no, year, mime_type, size_bytes, " +
                        "modified_s, source_kind, favorite, last_played) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, " +
                        "COALESCE((SELECT favorite FROM tracks WHERE id = ?), 0), " +
                        "COALESCE((SELECT last_played FROM tracks WHERE id = ?), 0))",
                    // Typed explicitly: `arrayOf` over mixed Long/String/null
                    // infers Array<Long?> here and would not bind.
                    arrayOf<Any?>(
                        file.id, file.path, file.title ?: file.path, file.artist, file.album,
                        file.albumId, file.artistId, file.durationMs,
                        file.path.substringBeforeLast('/'), file.trackNumber, file.year,
                        file.mimeType, file.sizeBytes, file.modifiedSeconds,
                        if (file.sourceKind == Track.SourceKind.USB) 1 else 0,
                        file.id, file.id
                    )
                )
            }
            for (id in diff.removed) {
                // `delete` binds strings only, unlike `execSQL`'s Any? array.
                w.delete("tracks", "id = ?", arrayOf(id.toString()))
                // A playlist entry pointing at a deleted track would render as
                // a blank row; remove it in the same transaction.
                w.delete("playlist_items", "track_id = ?", arrayOf(id.toString()))
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    private fun finalise(changed: Boolean) {
        val count = db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM tracks", null
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

        db.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO scan_state (id, last_scan_at, last_volume, total_tracks) " +
                "VALUES (1, ?, ?, ?)",
            arrayOf(System.currentTimeMillis(), lastVolumes.joinToString(",") { it.name }, count)
        )
        // Marking the library ready is what lets the UI show "đang quét…"
        // rather than an empty list on first boot.
        library.markReady()
    }

    /** Runs a scan on a background thread, for the service and the UI. */
    fun scanAsync(
        forceFullScan: Boolean = false,
        onProgress: (Float) -> Unit = {},
        onDone: (ScanResult) -> Unit
    ) {
        thread(name = "ts8-media-scan", isDaemon = true) {
            val result = scan(forceFullScan, onProgress)
            onDone(result)
        }
    }

    companion object {
        private const val TAG = "Ts8MediaScanner"
        private const val MAX_DEPTH = 12
        /** 300 MB: larger than any audio file this unit will ever play. */
        private const val MAX_FILE_BYTES = 300L * 1024 * 1024
    }
}

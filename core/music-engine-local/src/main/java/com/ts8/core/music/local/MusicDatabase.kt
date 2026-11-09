package com.ts8.core.music.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.ts8.core.music.MusicCollection
import com.ts8.core.music.MusicLibrary
import com.ts8.core.music.Playlist
import com.ts8.core.music.Track

/**
 * The library store.
 *
 * SQLite because the whole point of incremental scanning is a persistent
 * index: the file system is walked once, and afterwards a scan only probes
 * what changed. The schema is deliberately flat (no foreign-key joins at read
 * time) because on this hardware a join per row in a 200-row page is visible.
 *
 * Artists and albums are stored as denormalised names on the track, plus a
 * small aggregate table for the browser lists. That keeps "all tracks in this
 * album" a single indexed scan.
 */
class MusicDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE tracks (
              id           INTEGER PRIMARY KEY,
              source       TEXT    NOT NULL,
              title        TEXT    NOT NULL,
              artist       TEXT,
              album        TEXT,
              album_id     INTEGER,
              artist_id    INTEGER,
              duration_ms  INTEGER NOT NULL DEFAULT 0,
              folder       TEXT,
              track_no     INTEGER NOT NULL DEFAULT 0,
              year         INTEGER NOT NULL DEFAULT 0,
              mime_type    TEXT,
              size_bytes   INTEGER NOT NULL DEFAULT 0,
              modified_s   INTEGER NOT NULL DEFAULT 0,
              source_kind  INTEGER NOT NULL DEFAULT 0,
              favorite     INTEGER NOT NULL DEFAULT 0,
              last_played  INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        // The scanner's incremental probe reads by (source) and (size, mtime).
        db.execSQL("CREATE UNIQUE INDEX idx_tracks_source ON tracks(source)")
        db.execSQL("CREATE INDEX idx_tracks_album ON tracks(album_id, track_no)")
        db.execSQL("CREATE INDEX idx_tracks_artist ON tracks(artist_id)")
        db.execSQL("CREATE INDEX idx_tracks_folder ON tracks(folder)")
        db.execSQL("CREATE INDEX idx_tracks_favorite ON tracks(favorite, last_played DESC)")
        db.execSQL("CREATE INDEX idx_tracks_played ON tracks(last_played DESC)")
        db.execSQL("CREATE INDEX idx_tracks_title ON tracks(title)")

        db.execSQL(
            """
            CREATE TABLE playlists (
              id         INTEGER PRIMARY KEY AUTOINCREMENT,
              name       TEXT    NOT NULL,
              created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE playlist_items (
              playlist_id INTEGER NOT NULL,
              track_id    INTEGER NOT NULL,
              position    INTEGER NOT NULL,
              PRIMARY KEY (playlist_id, track_id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_playlist_items ON playlist_items(playlist_id, position)")

        // Resume point, so a reboot resumes the track the driver was on rather
        // than the first one in the library.
        db.execSQL(
            """
            CREATE TABLE checkpoint (
              id         INTEGER PRIMARY KEY CHECK (id = 1),
              track_id   INTEGER NOT NULL,
              position_ms INTEGER NOT NULL,
              queue_json TEXT,
              saved_at   INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // Scan bookkeeping: what the last incremental pass saw, so removal can
        // be detected without a full MediaStore walk.
        db.execSQL(
            """
            CREATE TABLE scan_state (
              id            INTEGER PRIMARY KEY CHECK (id = 1),
              last_scan_at  INTEGER NOT NULL,
              last_volume   TEXT,
              total_tracks  INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Library data is a cache of the file system: rebuilding is always
        // correct and cheaper than a migration chain. The checkpoint and
        // playlists are user data, so they are preserved where the schema
        // allows it.
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS tracks")
            db.execSQL("DROP INDEX IF EXISTS idx_tracks_source")
            onCreate(db)
        }
    }

    companion object {
        const val DATABASE_NAME = "music-library.db"
        const val DATABASE_VERSION = 2

        /** Reads one track row. Column order matches [TRACK_COLUMNS]. */
        val TRACK_COLUMNS = arrayOf(
            "id", "source", "title", "artist", "album", "album_id", "artist_id",
            "duration_ms", "folder", "track_no", "year", "mime_type",
            "size_bytes", "modified_s", "source_kind"
        )

        fun readTrack(c: Cursor): Track = Track(
            id = c.getLong(0),
            source = c.getString(1),
            title = c.getString(2),
            artist = if (c.isNull(3)) null else c.getString(3),
            album = if (c.isNull(4)) null else c.getString(4),
            albumId = if (c.isNull(5)) null else c.getLong(5),
            artistId = if (c.isNull(6)) null else c.getLong(6),
            durationMs = c.getLong(7),
            folder = if (c.isNull(8)) null else c.getString(8),
            trackNumber = c.getInt(9),
            year = c.getInt(10),
            mimeType = if (c.isNull(11)) null else c.getString(11),
            sizeBytes = c.getLong(12),
            modifiedSeconds = c.getLong(13),
            sourceKind = if (c.getInt(14) == 1) Track.SourceKind.USB else Track.SourceKind.MEDIA_STORE
        )

        fun trackValues(t: Track): ContentValues = ContentValues().apply {
            put("id", t.id)
            put("source", t.source)
            put("title", t.title)
            put("artist", t.artist)
            put("album", t.album)
            put("album_id", t.albumId)
            put("artist_id", t.artistId)
            put("duration_ms", t.durationMs)
            put("folder", t.folder)
            put("track_no", t.trackNumber)
            put("year", t.year)
            put("mime_type", t.mimeType)
            put("size_bytes", t.sizeBytes)
            put("modified_s", t.modifiedSeconds)
            put("source_kind", if (t.sourceKind == Track.SourceKind.USB) 1 else 0)
        }
    }
}

/**
 * [MusicLibrary] over [MusicDatabase].
 *
 * Every method opens a read, does one indexed query and closes. The database is
 * small (a few thousand rows in practice) and SQLite keeps its page cache, so
 * this beats holding a cursor the UI has to remember to close.
 */
class SqliteMusicLibrary(private val db: MusicDatabase) : MusicLibrary {

    @Volatile
    private var ready = false

    fun markReady() {
        ready = true
    }

    override fun isReady(): Boolean = ready

    override fun trackCount(): Int = db.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM tracks", null
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    override fun totalDurationMs(): Long = db.readableDatabase.rawQuery(
        "SELECT COALESCE(SUM(duration_ms), 0) FROM tracks", null
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    override fun searchTracks(query: String, limit: Int): List<Track> =
        queryTracks(
            selection = "title LIKE ? OR artist LIKE ? OR album LIKE ?",
            args = Array(3) { "%${query.trim()}%" },
            orderBy = "title COLLATE NOCASE",
            limit = limit
        )

    override fun tracksInAlbum(albumId: Long): List<Track> =
        queryTracks("album_id = ?", arrayOf(albumId.toString()), "track_no, title", 0)

    override fun tracksByArtist(artistId: Long): List<Track> =
        queryTracks("artist_id = ?", arrayOf(artistId.toString()), "album, track_no", 0)

    override fun tracksInFolder(folder: String): List<Track> =
        queryTracks("folder = ?", arrayOf(folder), "title COLLATE NOCASE", 0)

    override fun albums(): List<MusicCollection> = collections(
        "SELECT album_id, album, COUNT(*), MIN(duration_ms) " +
            "FROM tracks WHERE album_id IS NOT NULL AND album IS NOT NULL " +
            "GROUP BY album_id ORDER BY album COLLATE NOCASE",
        MusicCollection.Kind.ALBUM
    )

    override fun artists(): List<MusicCollection> = collections(
        "SELECT artist_id, artist, COUNT(*), 0 " +
            "FROM tracks WHERE artist_id IS NOT NULL AND artist IS NOT NULL " +
            "GROUP BY artist_id ORDER BY artist COLLATE NOCASE",
        MusicCollection.Kind.ARTIST
    )

    override fun folders(): List<MusicCollection> = collections(
        "SELECT 0, folder, COUNT(*), 0 " +
            "FROM tracks WHERE folder IS NOT NULL " +
            "GROUP BY folder ORDER BY folder COLLATE NOCASE",
        MusicCollection.Kind.FOLDER
    )

    private fun collections(
        sql: String,
        kind: MusicCollection.Kind,
        args: Array<String>? = null
    ): List<MusicCollection> =
        db.readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<MusicCollection>(c.count)
            while (c.moveToNext()) {
                out.add(
                    MusicCollection(
                        id = c.getLong(0),
                        name = c.getString(1) ?: "",
                        kind = kind,
                        trackCount = c.getInt(2)
                    )
                )
            }
            out
        }

    override fun playlists(): List<Playlist> = db.readableDatabase.rawQuery(
        "SELECT p.id, p.name, p.created_at, COUNT(i.track_id) " +
            "FROM playlists p LEFT JOIN playlist_items i ON i.playlist_id = p.id " +
            "GROUP BY p.id ORDER BY p.created_at DESC",
        null
    ).use { c ->
        val out = ArrayList<Playlist>(c.count)
        while (c.moveToNext()) {
            out.add(Playlist(c.getLong(0), c.getString(1), c.getInt(3), c.getLong(2)))
        }
        out
    }

    override fun playlistTracks(playlistId: Long): List<Track> =
        queryTracks(
            "id IN (SELECT track_id FROM playlist_items WHERE playlist_id = ?)",
            arrayOf(playlistId.toString()),
            "id",
            0
        ).sortedBy { t ->
            // Preserve the playlist's own order.
            playlistPosition(playlistId, t.id)
        }

    private fun playlistPosition(playlistId: Long, trackId: Long): Int =
        db.readableDatabase.rawQuery(
            "SELECT position FROM playlist_items WHERE playlist_id = ? AND track_id = ?",
            arrayOf(playlistId.toString(), trackId.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else Int.MAX_VALUE }

    override fun favorites(): List<Track> =
        queryTracks("favorite = 1", null, "last_played DESC, title COLLATE NOCASE", 0)

    override fun recentlyPlayed(limit: Int): List<Track> =
        queryTracks("last_played > 0", null, "last_played DESC", limit)

    override fun allTracks(offset: Int, limit: Int): List<Track> =
        queryTracks(null, null, "title COLLATE NOCASE", limit, offset)

    /**
     * One storage kind's tracks, paged.
     *
     * `source_kind` is stored as a flag — 1 for USB, 0 for everything else —
     * because the scanner learns a track's origin from the volume it walked,
     * not from a per-track column the media database keeps. The mapping is
     * inverted here, next to the column, rather than in the UI: a screen that
     * had to know "0 means MediaStore" would be a second place the storage
     * encoding lives.
     */
    override fun tracksBySource(
        kind: Track.SourceKind,
        offset: Int,
        limit: Int
    ): List<Track> = queryTracks(
        selection = "source_kind = ?",
        args = arrayOf(if (kind == Track.SourceKind.USB) "1" else "0"),
        orderBy = "title COLLATE NOCASE",
        limit = limit,
        offset = offset
    )

    override fun sourceCount(kind: Track.SourceKind): Int =
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM tracks WHERE source_kind = ?",
            arrayOf(if (kind == Track.SourceKind.USB) "1" else "0")
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    override fun albumsBySource(kind: Track.SourceKind): List<MusicCollection> = collections(
        "SELECT album_id, album, COUNT(*), MIN(duration_ms) " +
            "FROM tracks WHERE album_id IS NOT NULL AND album IS NOT NULL " +
            "AND source_kind = ? " +
            "GROUP BY album_id ORDER BY album COLLATE NOCASE",
        MusicCollection.Kind.ALBUM,
        arrayOf(if (kind == Track.SourceKind.USB) "1" else "0")
    )

    override fun artistsBySource(kind: Track.SourceKind): List<MusicCollection> = collections(
        "SELECT artist_id, artist, COUNT(*), 0 " +
            "FROM tracks WHERE artist_id IS NOT NULL AND artist IS NOT NULL " +
            "AND source_kind = ? " +
            "GROUP BY artist_id ORDER BY artist COLLATE NOCASE",
        MusicCollection.Kind.ARTIST,
        arrayOf(if (kind == Track.SourceKind.USB) "1" else "0")
    )

    override fun track(id: Long): Track? =
        queryTracks("id = ?", arrayOf(id.toString()), null, 1).firstOrNull()

    private fun queryTracks(
        selection: String?,
        args: Array<String>?,
        orderBy: String?,
        limit: Int,
        offset: Int = 0
    ): List<Track> {
        val sql = buildString {
            append("SELECT ").append(MusicDatabase.TRACK_COLUMNS.joinToString(","))
            append(" FROM tracks")
            if (selection != null) append(" WHERE ").append(selection)
            if (orderBy != null) append(" ORDER BY ").append(orderBy)
            if (limit > 0) {
                append(" LIMIT ").append(limit)
                if (offset > 0) append(" OFFSET ").append(offset)
            }
        }
        return db.readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<Track>(minOf(c.count, if (limit > 0) limit else 256))
            while (c.moveToNext()) out.add(MusicDatabase.readTrack(c))
            out
        }
    }

    // -- mutations ---------------------------------------------------------

    override fun createPlaylist(name: String): Long {
        val values = ContentValues().apply {
            put("name", name.trim())
            put("created_at", System.currentTimeMillis())
        }
        return db.writableDatabase.insert("playlists", null, values)
    }

    override fun addToPlaylist(playlistId: Long, trackId: Long): Boolean {
        val dbW = db.writableDatabase
        val position = dbW.rawQuery(
            "SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_items WHERE playlist_id = ?",
            arrayOf(playlistId.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val values = ContentValues().apply {
            put("playlist_id", playlistId)
            put("track_id", trackId)
            put("position", position)
        }
        return dbW.insertWithOnConflict(
            "playlist_items", null, values, SQLiteDatabase.CONFLICT_IGNORE
        ) >= 0
    }

    override fun removeFromPlaylist(playlistId: Long, trackId: Long): Boolean =
        db.writableDatabase.delete(
            "playlist_items", "playlist_id = ? AND track_id = ?",
            arrayOf(playlistId.toString(), trackId.toString())
        ) > 0

    override fun deletePlaylist(playlistId: Long) {
        val w = db.writableDatabase
        w.delete("playlist_items", "playlist_id = ?", arrayOf(playlistId.toString()))
        w.delete("playlists", "id = ?", arrayOf(playlistId.toString()))
    }

    override fun setFavorite(trackId: Long, favorite: Boolean) {
        val values = ContentValues().apply { put("favorite", if (favorite) 1 else 0) }
        db.writableDatabase.update("tracks", values, "id = ?", arrayOf(trackId.toString()))
    }

    override fun isFavorite(trackId: Long): Boolean =
        db.readableDatabase.rawQuery(
            "SELECT favorite FROM tracks WHERE id = ?", arrayOf(trackId.toString())
        ).use { it.moveToFirst() && it.getInt(0) == 1 }

    override fun recordPlayed(trackId: Long, atMs: Long) {
        val values = ContentValues().apply { put("last_played", atMs) }
        db.writableDatabase.update("tracks", values, "id = ?", arrayOf(trackId.toString()))
    }
}

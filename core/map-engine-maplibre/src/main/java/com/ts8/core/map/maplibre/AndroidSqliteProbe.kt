package com.ts8.core.map.maplibre

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * The one place the validator touches `android.database.sqlite`.
 *
 * Opened **read-only** and with no `SQLiteOpenHelper`: a pack is never migrated,
 * never written, and holds a WAL that belongs to the builder. `openDatabase`
 * with `OPEN_READONLY` is what MapLibre's own native reader does, so a pack that
 * passes here opens there.
 */
class AndroidSqliteProbe : SqliteProbe {

    override fun openReadOnly(absolutePath: String): SqliteProbe.OpenResult {
        val file = File(absolutePath)
        if (!file.isFile) {
            return SqliteProbe.OpenResult(false, null, "file not found")
        }
        if (file.length() <= 0L) {
            return SqliteProbe.OpenResult(false, null, "file is empty (0 bytes)")
        }
        return try {
            val db = SQLiteDatabase.openDatabase(
                absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
            SqliteProbe.OpenResult(true, Session(db), "opened")
        } catch (e: Exception) {
            // A half-copied SQLite file is the common case on a USB stick, and
            // its message ("file is not a database") is exactly what the
            // diagnostics report should carry.
            SqliteProbe.OpenResult(false, null, e.message ?: e.javaClass.simpleName)
        }
    }

    private class Session(private val db: SQLiteDatabase) : SqliteProbe.Session {

        override fun tableNames(): List<String> = runCatching {
            val out = ArrayList<String>(8)
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    // FTS shadow tables carry the same prefix as the virtual
                    // table and would drown the list in noise.
                    out += name
                }
            }
            out
        }.getOrDefault(emptyList())

        override fun rows(sql: String, vararg args: String): List<List<String?>> = runCatching {
            val out = ArrayList<List<String?>>(4)
            db.rawQuery(sql, args).use { c: Cursor ->
                val columns = c.columnCount
                while (c.moveToNext()) {
                    val row = ArrayList<String?>(columns)
                    for (i in 0 until columns) {
                        if (c.isNull(i)) row += null
                        else row += runCatching { c.getString(i) }.getOrNull()
                    }
                    out += row
                }
            }
            out
        }.getOrDefault(emptyList())

        override fun close() {
            runCatching { db.close() }
        }
    }
}

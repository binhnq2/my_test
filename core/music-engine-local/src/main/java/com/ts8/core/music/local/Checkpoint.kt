package com.ts8.core.music.local

import android.content.ContentValues
import com.ts8.core.music.RepeatMode

/**
 * Where playback should resume after the head unit reboots.
 *
 * The spec requires the car to come back to the same track at roughly the same
 * position, not to the first item in the library. That means the checkpoint has
 * to carry the queue *in play order*; saving only a track id would resume into
 * a different context (right song, wrong album).
 */
data class MusicCheckpoint(
    val trackId: Long,
    val positionMs: Long,
    /** Position within the saved play order. */
    val index: Int,
    val shuffle: Boolean,
    val repeatMode: RepeatMode,
    /** Track ids, in play order. */
    val queueTrackIds: List<Long>,
    val savedAtMs: Long
)

interface CheckpointStore {
    fun save(checkpoint: MusicCheckpoint)

    fun load(): MusicCheckpoint?

    fun clear()
}

/**
 * Line-oriented text codec.
 *
 * Not JSON: `org.json` is Android-only (so this would not be unit-testable),
 * and a queue is a list of longs — a format that needs no parser to be
 * obviously correct is better here than one that needs a library. Unknown keys
 * are ignored and any malformed value invalidates the whole checkpoint, so a
 * half-written row degrades to "start from the top" rather than to a crash.
 */
object CheckpointCodec {
    private const val MAGIC = "TS8CP1"
    private const val END = "end"
    private const val MAX_INPUT = 64_000

    fun encode(cp: MusicCheckpoint): String = buildString {
        append(MAGIC).append('\n')
        append("track=").append(cp.trackId).append('\n')
        append("position=").append(cp.positionMs).append('\n')
        append("index=").append(cp.index).append('\n')
        append("shuffle=").append(if (cp.shuffle) 1 else 0).append('\n')
        append("repeat=").append(cp.repeatMode.name).append('\n')
        append("saved_at=").append(cp.savedAtMs).append('\n')
        append("queue=")
        cp.queueTrackIds.forEachIndexed { i, id ->
            if (i > 0) append(',')
            append(id)
        }
        append('\n')
        // Explicit terminator. Without it a row written by an older build that
        // lacks a newer key would decode identically to a row truncated just
        // before that key — the two cases have to be distinguishable.
        append(END).append('\n')
    }

    fun decode(raw: String?): MusicCheckpoint? {
        if (raw.isNullOrBlank() || raw.length > MAX_INPUT) return null
        val lines = raw.split('\n')
        if (lines.firstOrNull()?.trim() != MAGIC) return null
        if (lines.none { it.trim() == END }) return null

        var trackId: Long? = null
        var position = 0L
        var index = 0
        var shuffle = false
        var repeat = RepeatMode.OFF
        var savedAt = 0L
        var queue: List<Long> = emptyList()

        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            // The terminator is a bare token, not a key=value pair.
            if (line.trim() == END) continue
            val eq = line.indexOf('=')
            if (eq <= 0) return null
            val value = line.substring(eq + 1)
            val ok = when (line.substring(0, eq)) {
                "track" -> value.toLongOrNull()?.let { trackId = it } != null
                "position" -> value.toLongOrNull()?.let { position = it } != null
                "index" -> value.toIntOrNull()?.let { index = it } != null
                "shuffle" -> value.toIntOrNull()?.let { shuffle = it != 0 } != null
                "repeat" -> runCatching { RepeatMode.valueOf(value) }
                    .getOrNull()?.let { repeat = it } != null
                "saved_at" -> value.toLongOrNull()?.let { savedAt = it } != null
                "queue" -> parseQueue(value)?.let { queue = it } != null
                // Forward compatibility: a newer build may write keys this one
                // does not know. Ignoring them is safe; failing is not.
                else -> true
            }
            // A value that does not parse means the row was written by a
            // different schema or truncated mid-write. Either way the whole
            // checkpoint is untrustworthy.
            if (!ok) return null
        }

        val id = trackId ?: return null
        if (position < 0) return null
        return MusicCheckpoint(
            trackId = id,
            positionMs = position,
            index = index.coerceAtLeast(0),
            shuffle = shuffle,
            repeatMode = repeat,
            queueTrackIds = if (queue.isEmpty()) listOf(id) else queue,
            savedAtMs = savedAt
        )
    }

    private fun parseQueue(value: String): List<Long>? {
        if (value.isBlank()) return emptyList()
        val out = ArrayList<Long>()
        for (part in value.split(',')) {
            out.add(part.trim().toLongOrNull() ?: return null)
        }
        return out
    }
}

/** [CheckpointStore] on the library database's `checkpoint` row. */
class SqliteCheckpointStore(private val db: MusicDatabase) : CheckpointStore {

    override fun save(checkpoint: MusicCheckpoint) {
        val values = ContentValues().apply {
            put("id", 1)
            put("track_id", checkpoint.trackId)
            put("position_ms", checkpoint.positionMs)
            put("queue_json", CheckpointCodec.encode(checkpoint))
            put("saved_at", checkpoint.savedAtMs)
        }
        runCatching {
            db.writableDatabase.insertWithOnConflict(
                "checkpoint", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
            )
        }
    }

    override fun load(): MusicCheckpoint? = runCatching {
        db.readableDatabase.rawQuery(
            "SELECT queue_json FROM checkpoint WHERE id = 1", null
        ).use { if (it.moveToFirst()) CheckpointCodec.decode(it.getString(0)) else null }
    }.getOrNull()

    override fun clear() {
        runCatching { db.writableDatabase.delete("checkpoint", "id = 1", null) }
    }
}

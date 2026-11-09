package com.ts8.core.music.local

import android.content.Context
import com.ts8.core.audio.VehicleAudioBridge
import com.ts8.core.music.MediaScanner
import com.ts8.core.music.MusicBackend
import com.ts8.core.music.MusicLibrary
import com.ts8.core.music.MusicPlayer
import java.io.File

/**
 * The local music backend, wired together.
 *
 * This is the only class the app constructs for music: it owns the database,
 * the library, the codec probe, the scanner and the player, and hands them out
 * through the interfaces the UI knows. Keeping the wiring in one place is what
 * makes "the engine never lives in an Activity" true rather than aspirational —
 * the Activity receives a [MusicBackend] and cannot reach past it.
 */
class LocalMusicBackend(
    context: Context,
    audioBridge: VehicleAudioBridge
) : MusicBackend {

    private val appContext = context.applicationContext

    private val db = MusicDatabase(appContext)

    override val library: SqliteMusicLibrary = SqliteMusicLibrary(db)

    val codecs = AndroidCodecSupport(File(appContext.cacheDir, "codec-probe"))

    val scanner: MediaScanner = MediaStoreScanner(appContext, db, library, codecs)

    private val checkpointStore = SqliteCheckpointStore(db)

    override val player: LocalMusicPlayer = LocalMusicPlayer(
        context = appContext,
        audioBridge = audioBridge,
        checkpointStore = checkpointStore,
        library = library
    )

    val checkpoint: CheckpointStore get() = checkpointStore

    /** True when the library has at least one track. */
    fun hasTracks(): Boolean = library.trackCount() > 0

    /** Closes the database. Called when the service is destroyed for good. */
    fun close() {
        player.release()
        runCatching { db.close() }
    }

    companion object {
        /**
         * Formats this build can offer, as measured rather than assumed.
         * Called by the diagnostics screen and by the music UI's filter chips.
         */
        fun supportedFormats(codecs: AndroidCodecSupport): Set<String> =
            codecs.supportedMimeTypes()
    }
}

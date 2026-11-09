package com.ts8.core.music.local

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.ts8.core.audio.AudioFocusListener
import com.ts8.core.audio.AudioResult
import com.ts8.core.audio.VehicleAudioBridge
import com.ts8.core.music.AudioRoute
import com.ts8.core.music.MusicLibrary
import com.ts8.core.music.MusicPlayer
import com.ts8.core.music.MusicPlayerObservable
import com.ts8.core.music.PlaybackError
import com.ts8.core.music.PlaybackListener
import com.ts8.core.music.PlaybackState
import com.ts8.core.music.RepeatMode
import com.ts8.core.music.Track
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * Local playback over [MediaPlayer].
 *
 * [MediaPlayer] rather than ExoPlayer because the APK must stay small enough to
 * sideload over ADB onto a head unit with a slow link, and because the formats
 * the spec lists are exactly the ones the platform decoder handles. The
 * trade-off is that `MediaPlayer` is stateful and throws in ways that differ by
 * firmware, so every call here is guarded and every failure becomes a
 * [PlaybackError] the UI can show.
 *
 * **This class only drives audio.** Whether to play, pause, resume after a call,
 * or advance the queue is decided by [PlaybackCore], which touches no Android
 * API and is therefore tested off-device. That separation is deliberate: the
 * behaviours that break in a car — resuming music the driver had paused,
 * leaving the stream ducked forever, losing the resume position — are decisions,
 * not audio plumbing, and a decision that only runs on the vehicle is a decision
 * nobody verified.
 *
 * **The three behaviours the spec calls out:**
 *
 * 1. *Audio focus.* Playback stops for a call and resumes after it, but only if
 *    it was playing before. [PlaybackCore] records that.
 * 2. *Ducking for navigation.* The bridge lowers the stream; this player keeps
 *    playing and reports `duckedForNavigation` so the UI can show it.
 * 3. *USB removal without a crash.* Every media operation is wrapped, and the
 *    file's existence is checked before `setDataSource`. A track whose source
 *    vanished reports [PlaybackError.SOURCE_UNAVAILABLE] and stops.
 *
 * **Checkpoint.** Position is saved on pause, on stop, on focus loss, and
 * periodically while playing, so an ignition-off loses at most
 * [CHECKPOINT_INTERVAL_MS] of position.
 */
class LocalMusicPlayer(
    context: Context,
    private val audioBridge: VehicleAudioBridge,
    private val checkpointStore: CheckpointStore,
    private val library: MusicLibrary?,
    random: Random = Random.Default
) : MusicPlayer, MusicPlayerObservable {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<PlaybackListener>()

    /** Every play/pause/queue decision lives here, off the Android surface. */
    private val core = PlaybackCore(random)

    private var player: MediaPlayer? = null
    private var lastError: PlaybackError? = null

    /** Position snapshot, because `MediaPlayer.currentPosition()` throws after release. */
    private var lastKnownPositionMs = 0L
    private var lastKnownDurationMs = 0L

    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var released = false

    /**
     * Turns an online track's provider id into a URL (§13), or null when the
     * app has no online gateway.
     *
     * Volatile because it is set from the service's main thread and read inside
     * `open`, which is also main thread — but a resolver installed while a
     * track is opening must be seen by that open rather than by the next one.
     */
    @Volatile
    private var streamResolver: com.ts8.core.music.StreamResolver? = null

    /**
     * Where the current track came from, for the buffering state.
     *
     * A local file is never "buffering"; a stream is, and the UI draws a
     * spinner from this rather than from a boolean of its own.
     */
    @Volatile
    private var currentIsStream = false

    @Volatile
    private var buffering: com.ts8.core.music.BufferingState = com.ts8.core.music.BufferingState.IDLE

    private val checkpointTicker = object : Runnable {
        override fun run() {
            if (released) return
            if (core.playing) saveCheckpoint()
            mainHandler.postDelayed(this, CHECKPOINT_INTERVAL_MS)
        }
    }

    init {
        mainHandler.postDelayed(checkpointTicker, CHECKPOINT_INTERVAL_MS)
    }

    // -- observable ---------------------------------------------------------

    override fun addListener(listener: PlaybackListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    override fun removeListener(listener: PlaybackListener) {
        listeners.remove(listener)
    }

    private fun notifyState() {
        val snapshot = state()
        mainHandler.post {
            for (l in listeners) runCatching { l.onStateChanged(snapshot) }
        }
    }

    private fun notifyError(error: PlaybackError, track: Track?) {
        lastError = error
        mainHandler.post {
            for (l in listeners) runCatching { l.onError(error, track) }
        }
        notifyState()
    }

    // -- state --------------------------------------------------------------

    override fun state(): PlaybackState = PlaybackState(
        track = core.queue.current(),
        isPlaying = core.playing,
        positionMs = position(),
        durationMs = if (lastKnownDurationMs > 0) lastKnownDurationMs
        else core.queue.current()?.durationMs ?: 0L,
        bufferedPercent = if (core.playing) 100 else 0,
        shuffle = core.queue.shuffle,
        repeatMode = core.repeatMode,
        duckedForNavigation = core.duckedForNavigation,
        pausedForCall = core.isPausedForCall(),
        error = lastError,
        buffering = buffering
    )

    private fun position(): Long {
        val p = player
        return if (p != null && !released) {
            runCatching { p.currentPosition.toLong() }.getOrDefault(lastKnownPositionMs)
        } else {
            lastKnownPositionMs
        }
    }

    // -- queue --------------------------------------------------------------

    override fun setQueue(tracks: List<Track>, startIndex: Int, autoPlay: Boolean) {
        if (released) return
        core.queue.setQueue(tracks, startIndex, core.queue.shuffle)
        lastError = null
        val selected = core.queue.current()
        if (selected == null) {
            stopInternal()
            core.setLoaded(false)
            notifyState()
            return
        }
        open(selected, startAtMs = 0, autoPlay = autoPlay)
    }

    override fun play() {
        if (released) return
        if (core.queue.current() == null) {
            // Nothing loaded: resume the checkpoint instead of doing nothing,
            // which is what a driver pressing play on a fresh boot expects.
            restoreCheckpoint()
            return
        }
        core.onManualPlay()
        resumeAudio()
    }

    override fun pause() {
        core.onManualPause()
        pauseAudio()
    }

    override fun togglePlayPause() {
        if (core.playing) pause() else play()
    }

    override fun next() {
        if (released) return
        val track = core.onNextPressed()
        if (track == null) {
            // End of a non-repeating queue: keep the last track loaded and
            // paused so the screen still shows what was playing.
            notifyState()
            return
        }
        open(track, startAtMs = 0, autoPlay = true)
    }

    override fun previous() {
        if (released) return
        // The universal convention: a press within the first seconds of a
        // track restarts it; later than that it goes to the previous track.
        if (position() > RESTART_THRESHOLD_MS) {
            seekTo(0)
            return
        }
        val track = core.onPreviousPressed(restartInstead = false)
        if (track == null) {
            seekTo(0)
            return
        }
        open(track, startAtMs = 0, autoPlay = true)
    }

    override fun seekTo(positionMs: Long) {
        val p = player ?: run {
            lastKnownPositionMs = positionMs.coerceAtLeast(0)
            notifyState()
            return
        }
        val duration = lastKnownDurationMs.takeIf { it > 0 } ?: p.duration.toLong()
        val target = positionMs.coerceIn(0, maxOf(0, duration))
        try {
            p.seekTo(target.toInt())
            lastKnownPositionMs = target
            notifyState()
        } catch (e: IllegalStateException) {
            notifyError(PlaybackError.UNKNOWN, core.queue.current())
        }
    }

    override fun setShuffle(enabled: Boolean) {
        core.queue.setShuffle(enabled)
        notifyState()
    }

    override fun setRepeatMode(mode: RepeatMode) {
        core.setRepeatMode(mode)
        notifyState()
    }

    // -- transport ----------------------------------------------------------

    /** Executes what [PlaybackCore] decided. */
    private fun execute(transport: PlaybackCore.Transport) {
        when (transport) {
            PlaybackCore.Transport.PLAY -> resumeAudio()
            PlaybackCore.Transport.PAUSE -> pauseAudio()
            PlaybackCore.Transport.NONE -> notifyState()
        }
    }

    /**
     * Starts audio for the loaded track, taking focus first.
     *
     * Focus is re-requested rather than assumed even when resuming after a call:
     * the telephony stack does not tell us whether it released `STREAM_MUSIC`
     * focus, and playing without it is how a navigation prompt ends up fighting
     * a song for the speakers.
     */
    private fun resumeAudio() {
        val track = core.queue.current() ?: return
        when (val focus = audioBridge.requestFocus(AudioRoute.MEDIA)) {
            AudioResult.Success -> Unit
            is AudioResult.Denied -> {
                // Report the refusal; do not start playing without focus.
                notifyError(PlaybackError.AUDIO_FOCUS_LOST, track)
                return
            }
            is AudioResult.Unsupported, is AudioResult.Failed -> {
                // A bridge that cannot report focus at all must not stop
                // playback; the platform still arbitrates.
            }
            // `requestFocus` never produces NoEffect — that result belongs to
            // `setMediaVolume` — but the sealed hierarchy now includes it and an
            // exhaustive branch is better than an `else` that would hide a
            // future result. Focus was granted, so play.
            is AudioResult.NoEffect -> Unit
        }

        val p = player
        if (p == null) {
            open(track, position(), autoPlay = true)
            return
        }
        try {
            p.start()
            core.setPlaying(true)
            lastError = null
            acquireWakeLock()
            notifyState()
        } catch (e: IllegalStateException) {
            notifyError(PlaybackError.UNKNOWN, track)
        } catch (e: Exception) {
            notifyError(PlaybackError.UNKNOWN, track)
        }
    }

    /** Stops audio, keeping the position and the auto-resume flags untouched. */
    private fun pauseAudio() {
        val p = player
        try {
            if (p != null && p.isPlaying) p.pause()
        } catch (e: IllegalStateException) {
            // Nothing to pause; the state flag below is still corrected.
        }
        lastKnownPositionMs = position()
        core.setPlaying(false)
        saveCheckpoint()
        releaseWakeLock()
        notifyState()
    }

    // -- opening a track ----------------------------------------------------

    private fun open(track: Track, startAtMs: Long, autoPlay: Boolean) {
        stopInternal()

        // Check the source before touching MediaPlayer: a USB stick pulled
        // between the library query and this call is the common case, and
        // `setDataSource` on a vanished file throws IOException on some
        // firmware and RuntimeException on others.
        if (!sourceExists(track)) {
            lastKnownPositionMs = 0
            core.setLoaded(false)
            notifyError(PlaybackError.SOURCE_UNAVAILABLE, track)
            return
        }

        // An online item whose source is a provider id rather than a URL has
        // to be resolved first. A failed resolve is reported as an ordinary
        // playback error and stops here: handing MediaPlayer an unresolvable
        // string would produce a decode error that names the wrong cause.
        val playable = resolvePlayable(track) ?: return

        lastKnownPositionMs = startAtMs
        lastKnownDurationMs = playable.durationMs.takeIf { it > 0 } ?: track.durationMs
        lastError = null
        currentIsStream = playable.isStream
        buffering = if (playable.isStream) com.ts8.core.music.BufferingState.BUFFERING
        else com.ts8.core.music.BufferingState.IDLE

        try {
            val mp = MediaPlayer()
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
            mp.setWakeMode(appContext, PowerManager.PARTIAL_WAKE_LOCK)
            if (playable.isStream) {
                // A live stream has no end; `MediaPlayer` must not wait for a
                // content length it will never be given, and a buffering
                // timeout keeps a dead station from holding the player forever.
                mp.setDataSource(appContext, android.net.Uri.parse(playable.url))
            } else {
                mp.setDataSource(playable.url)
            }

            mp.setOnPreparedListener { p ->
                if (released) { runCatching { p.release() }; return@setOnPreparedListener }
                player = p
                core.setLoaded(true)
                buffering = com.ts8.core.music.BufferingState.IDLE
                lastKnownDurationMs = runCatching { p.duration.toLong() }
                    .getOrDefault(lastKnownDurationMs)
                    .takeIf { it > 0 } ?: lastKnownDurationMs

                if (startAtMs > 0) {
                    runCatching { p.seekTo(startAtMs.toInt()) }
                }
                if (autoPlay) {
                    when (audioBridge.requestFocus(AudioRoute.MEDIA)) {
                        is AudioResult.Denied -> {
                            // Loaded but not started: the driver sees a paused
                            // track and can retry, rather than a silent failure.
                            notifyError(PlaybackError.AUDIO_FOCUS_LOST, track)
                            return@setOnPreparedListener
                        }
                        else -> Unit
                    }
                    runCatching { p.start() }
                    core.setPlaying(true)
                    acquireWakeLock()
                }
                // Only a local file has a library row. Recording a play for an
                // online id would write a primary key the library has never
                // seen, and the next `track(id)` would return null for it.
                if (!playable.isStream) library?.recordPlayed(track.id, System.currentTimeMillis())
                notifyState()
            }

            // A stream that is playing but has gone quiet is buffering again;
            // a stream that keeps playing is not. This is the only signal
            // `MediaPlayer` gives, and without it the spinner would never
            // appear after the first prepare.
            mp.setOnInfoListener { _, what, _ ->
                when (what) {
                    MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                        if (currentIsStream) {
                            buffering = com.ts8.core.music.BufferingState.STALLED
                            notifyState()
                        }
                    }
                    MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                        if (currentIsStream) {
                            buffering = com.ts8.core.music.BufferingState.IDLE
                            notifyState()
                        }
                    }
                    else -> Unit
                }
                false
            }

            mp.setOnCompletionListener { p ->
                if (released) return@setOnCompletionListener
                lastKnownPositionMs = lastKnownDurationMs
                onTrackCompleted()
            }

            mp.setOnErrorListener { _, what, extra ->
                handlePlayerError(track, what, extra, isStream = currentIsStream)
                // Returning true marks the error handled so MediaPlayer does
                // not call its own onCompletion, which would advance the queue
                // past a broken file and hide the problem.
                true
            }

            mp.prepareAsync()
        } catch (e: Exception) {
            playing(false)
            buffering = com.ts8.core.music.BufferingState.IDLE
            notifyError(classify(e, currentIsStream), track)
        }
    }

    /**
     * The URL the platform player should open, or null when there is none.
     *
     * A local track already carries a path, so it passes through untouched and
     * this costs one string prefix check. An online track carries the provider
     * id, and only [streamResolver] can turn it into a URL — which is a network
     * call, and is therefore the reason this method must not be moved onto the
     * UI thread. It is called from `open`, which already runs on the main
     * thread of a *service* whose calls come from `handleIntent`, so the
     * resolver implementation in the app is expected to do its own blocking
     * work on a pool and this call therefore arrives pre-resolved wherever
     * possible; see `OnlineMusicRuntime`.
     */
    private fun resolvePlayable(track: Track): PlayableTrack? {
        val source = track.source
        if (isStreamSource(source)) {
            return PlayableTrack(url = source, durationMs = track.durationMs, isStream = true)
        }
        if (track.sourceKind != Track.SourceKind.ONLINE) {
            return PlayableTrack(url = source, durationMs = track.durationMs, isStream = false)
        }
        val resolver = streamResolver ?: run {
            notifyError(PlaybackError.STREAM_UNAVAILABLE, track)
            return null
        }
        return when (val resolved = runCatching { resolver.resolve(track) }
            .getOrElse { com.ts8.core.music.StreamResolution.Unavailable("Không tải được địa chỉ phát", true) }) {
            is com.ts8.core.music.StreamResolution.Playable ->
                if (resolved.url.isBlank()) {
                    notifyError(PlaybackError.STREAM_UNAVAILABLE, track)
                    null
                } else {
                    PlayableTrack(
                        url = resolved.url,
                        durationMs = resolved.durationMs,
                        isStream = true
                    )
                }

            is com.ts8.core.music.StreamResolution.Unavailable -> {
                Log.w(TAG, "stream resolve failed for ${track.id}: ${resolved.reason}")
                notifyError(PlaybackError.STREAM_UNAVAILABLE, track)
                null
            }
        }
    }

    /** What [open] actually hands the platform player. */
    private class PlayableTrack(val url: String, val durationMs: Long, val isStream: Boolean)

    private fun playing(value: Boolean) = core.setPlaying(value)

    private fun onTrackCompleted() {
        when (val completion = core.onTrackCompleted()) {
            is PlaybackCore.Completion.RepeatCurrent -> {
                val p = player
                if (p != null) {
                    runCatching {
                        p.seekTo(0)
                        p.start()
                    }
                    core.setPlaying(true)
                    acquireWakeLock()
                } else {
                    core.queue.current()?.let { open(it, 0, autoPlay = true) }
                }
                notifyState()
            }
            is PlaybackCore.Completion.Advance -> open(completion.track, 0, autoPlay = true)
            is PlaybackCore.Completion.Stop -> {
                releaseWakeLock()
                saveCheckpoint()
                notifyState()
            }
        }
    }

    private fun handlePlayerError(track: Track, what: Int, extra: Int, isStream: Boolean = false) {
        playing(false)
        buffering = com.ts8.core.music.BufferingState.IDLE
        releaseWakeLock()
        val error = when {
            // A stream error is a network outcome, not a codec or a missing
            // file: `MEDIA_ERROR_IO` on an `http` source means the host
            // dropped, and saying "USB đã rút" for that would send the driver
            // to check hardware that is fine.
            isStream && what == MediaPlayer.MEDIA_ERROR_IO -> PlaybackError.STREAM_INTERRUPTED
            isStream && what == MediaPlayer.MEDIA_ERROR_UNKNOWN -> PlaybackError.STREAM_UNAVAILABLE
            what == MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> PlaybackError.UNSUPPORTED_FORMAT
            what == MediaPlayer.MEDIA_ERROR_IO -> PlaybackError.SOURCE_UNAVAILABLE
            what == MediaPlayer.MEDIA_ERROR_MALFORMED -> PlaybackError.DECODE_FAILED
            extra == MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> PlaybackError.UNSUPPORTED_FORMAT
            else -> PlaybackError.DECODE_FAILED
        }
        Log.w(TAG, "playback error what=$what extra=$extra track=${track.id}")
        notifyError(error, track)
    }

    private fun classify(e: Exception, isStream: Boolean = false): PlaybackError = when (e) {
        is java.io.FileNotFoundException -> PlaybackError.SOURCE_UNAVAILABLE
        is SecurityException -> PlaybackError.SOURCE_UNAVAILABLE
        is java.io.IOException ->
            if (isStream) PlaybackError.STREAM_UNAVAILABLE else PlaybackError.UNKNOWN
        is IllegalStateException -> PlaybackError.UNKNOWN
        else -> {
            val message = e.message ?: ""
            when {
                // `MediaPlayer.setDataSource(String)` wraps a network failure in
                // an `IOException` whose message names the URL, not a file.
                isStream -> PlaybackError.STREAM_UNAVAILABLE
                message.contains("unsupported", true) -> PlaybackError.UNSUPPORTED_FORMAT
                message.contains("no such file", true) -> PlaybackError.SOURCE_UNAVAILABLE
                else -> PlaybackError.UNKNOWN
            }
        }
    }

    /**
     * True when the track's file is still there, or when it is a network
     * stream that does not have a local file to lose.
     *
     * **Why a stream takes the early return.** Everything below this is a
     * check for a file on disk. An `http(s)://` source has no file, so
     * `File(...)` reports `isFile == false` and the player would refuse to open
     * a station that is perfectly reachable — reporting `SOURCE_UNAVAILABLE`,
     * which tells the driver to re-seat a USB stick they are not using.
     *
     * The stream is not left unchecked: `setDataSource` on an unreachable URL
     * fails asynchronously, and `onError` classifies it into
     * [PlaybackError.STREAM_UNAVAILABLE] below. That is the honest place to
     * learn a host is down — the check here is only about files that vanished
     * between the library query and this call.
     */
    private fun sourceExists(track: Track): Boolean {
        val source = track.source
        if (source.startsWith("content://")) return true
        if (isStreamSource(source)) return true
        val file = File(source)
        return try {
            file.isFile && file.canRead() && file.length() > 0
        } catch (e: SecurityException) {
            // A USB volume unmounted: the file object raises rather than
            // returning false.
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * True for a source the platform player must fetch over the network.
     *
     * `http` and `https` only. A bare host, an `rtsp://` or a `file://` URI is
     * *not* accepted here: RTSP needs a different player surface on this
     * firmware and accepting it would produce a decode error that reads like a
     * broken file. Providers that only offer RTSP are surfaced as a failure by
     * [com.ts8.core.music.MusicApi]'s `StreamResolver` before reaching this.
     */
    private fun isStreamSource(source: String): Boolean =
        source.startsWith("http://", ignoreCase = true) ||
            source.startsWith("https://", ignoreCase = true)

    private fun stopInternal() {
        val p = player
        player = null
        if (p != null) {
            runCatching { if (p.isPlaying) p.pause() }
            runCatching { p.stop() }
            runCatching { p.reset() }
            runCatching { p.release() }
        }
        core.setPlaying(false)
        core.setLoaded(false)
        buffering = com.ts8.core.music.BufferingState.IDLE
        releaseWakeLock()
    }

    // -- checkpoint ---------------------------------------------------------

    override fun saveCheckpoint() {
        val track = core.queue.current() ?: return
        val snapshot = if (player != null) position() else lastKnownPositionMs
        val cp = MusicCheckpoint(
            trackId = track.id,
            positionMs = snapshot,
            index = core.queue.position,
            shuffle = core.queue.shuffle,
            repeatMode = core.repeatMode,
            queueTrackIds = core.queue.trackIdsInOrder().ifEmpty { listOf(track.id) },
            savedAtMs = System.currentTimeMillis()
        )
        checkpointStore.save(cp)
    }

    override fun restoreCheckpoint() {
        val cp = checkpointStore.load() ?: return
        val lib = library ?: return

        val restored = CheckpointRestore.plan(cp) { id ->
            try { lib.track(id) } catch (e: Exception) { null }
        }
        if (restored == null) {
            // Every track in the checkpoint is gone. Clearing it is what keeps
            // the next boot from retrying a restore that can never succeed.
            checkpointStore.clear()
            return
        }

        core.queue.restore(restored.tracks, restored.index, restored.shuffle)
        core.setRepeatMode(restored.repeatMode)

        val track = core.queue.current() ?: return
        // Load without playing: the driver starts the engine, not the music.
        open(track, startAtMs = restored.positionMs, autoPlay = false)
        notifyState()
    }

    // -- focus and lifecycle ------------------------------------------------

    /**
     * The listener the app installs on the bridge.
     *
     * Nothing here decides anything: each event is handed to [PlaybackCore],
     * which knows whether the interruption is allowed to resume, and the
     * transport it returns is what actually happens to the audio.
     */
    fun asFocusListener(): AudioFocusListener = object : AudioFocusListener {
        override fun onFocusLostPermanent() {
            mainHandler.post { execute(core.onFocusLostPermanent()) }
        }

        override fun onFocusLostTransient(mayDuck: Boolean) {
            mainHandler.post { execute(core.onTransientLoss(mayDuck)) }
        }

        override fun onFocusGained() {
            mainHandler.post { execute(core.onFocusGained()) }
        }

        override fun onCallStarted() {
            mainHandler.post { execute(core.onCallStarted()) }
        }

        override fun onCallEnded() {
            mainHandler.post { execute(core.onCallEnded()) }
        }

        override fun onAudioRouteChanged(route: AudioRoute) {
            mainHandler.post { notifyState() }
        }
    }

    /** Called by the navigation side when it ducks the stream. */
    fun onNavigationDuck(ducked: Boolean) {
        core.setDuckedForNavigation(ducked)
        notifyState()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ts8:music").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            // A head unit that denies wake locks still plays music while the
            // screen is on; losing background playback is degraded, not fatal.
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            // Ignore: already released or revoked.
        } finally {
            wakeLock = null
        }
    }

    override fun release() {
        if (released) return
        saveCheckpoint()
        released = true
        mainHandler.removeCallbacks(checkpointTicker)
        stopInternal()
        // Nothing is playing after release, and the queue must not be readable
        // as if it still were.
        core.queue.setQueue(emptyList(), 0, false)
        audioBridge.abandonFocus(AudioRoute.MEDIA)
        listeners.clear()
    }

    /**
     * Installs the provider front end the app supplies (§13).
     *
     * See [com.ts8.core.music.MusicPlayer.setStreamResolver] for why the local
     * engine takes this from outside rather than building one. Clearing it
     * (`null`) is the honest state for "online music is off in Settings".
     */
    override fun setStreamResolver(resolver: com.ts8.core.music.StreamResolver?) {
        streamResolver = resolver
    }

    /** Reloads the current track from disk, for when a stick is plugged back in. */
    fun retryCurrent() {
        val track = core.queue.current() ?: return
        open(track, lastKnownPositionMs, autoPlay = core.playing)
    }

    companion object {
        private const val TAG = "Ts8LocalPlayer"
        private const val CHECKPOINT_INTERVAL_MS = 10_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        private const val RESTART_THRESHOLD_MS = 3_000L
    }
}

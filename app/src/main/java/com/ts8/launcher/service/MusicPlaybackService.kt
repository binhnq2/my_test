package com.ts8.launcher.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.session.MediaSession
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ts8.core.music.PlaybackError
import com.ts8.core.music.PlaybackListener
import com.ts8.core.music.PlaybackState
import com.ts8.core.music.Track
import com.ts8.core.music.local.LocalMusicBackend
import com.ts8.launcher.R
import com.ts8.launcher.ui.MusicFullscreenActivity
import com.ts8.launcher.di.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * The process-level owner of music playback.
 *
 * **Why a Service and not the Activity.** The spec requires music to keep
 * playing through screen changes, through the map opening, and through an
 * Activity recreate. A `MediaPlayer` owned by an Activity dies with it, so the
 * player, its queue and its resume position live here and the UI binds.
 *
 * **Why foreground.** On API 23 a background service is killed when the system
 * wants memory, and the head unit wants memory constantly while rendering the
 * map. A foreground service with an ongoing notification is the only way to
 * keep playing while the driver is on the navigation screen with the map busy.
 *
 * **MediaSession.** Uses `MediaSessionCompat`, whose framework backport works
 * on API 23 and keeps the media buttons and the (vendor) Bluetooth AVRCP
 * controls driving this player. A raw `android.media.session.MediaSession` is
 * used *in addition* on API 21+ so the transport controls reach the system's
 * media notification on firmware that ignores the compat shim.
 *
 * **USB removal.** The bridge between the scan and playback is explicit:
 * [onStorageRemoved] stops playback *before* the file vanishes, saves the
 * checkpoint, and reports [PlaybackError.SOURCE_UNAVAILABLE] — so pulling the
 * stick pauses safely instead of throwing inside `MediaPlayer`.
 */
class MusicPlaybackService : Service(), PlaybackListener {

    private val binder = LocalBinder()

    /** Set once [onCreate] has built the backend; null after [onDestroy]. */
    @Volatile
    private var backend: LocalMusicBackend? = null

    private lateinit var graph: AppGraph
    private var session: MediaSessionCompat? = null
    private var frameworkSession: MediaSession? = null

    private var scope: CoroutineScope? = null
    private var scanJob: Job? = null

    @Volatile
    private var lastState: PlaybackState = PlaybackState()

    /** Cached so every `onStateChanged` does not re-read prefs. */
    private lateinit var prefs: SharedPreferences

    inner class LocalBinder : Binder() {
        /** The backend, or null when the service is shutting down. */
        fun backend(): LocalMusicBackend? = backend

        fun service(): MusicPlaybackService = this@MusicPlaybackService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.require()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val created = LocalMusicBackend(this, graph.audioBridge)
        backend = created
        graph.attachMusicBackend(created)

        // §13: the player opens vector streams through the app's online gateway
        // rather than through any provider the local engine could name. Installed
        // unconditionally — an online track whose provider is unconfigured gets a
        // STREAM_UNAVAILABLE with the provider's own Vietnamese reason, which is
        // a better answer than a silent no-op. Local playback is untouched by
        // this: a MEDIA_STORE or USB source never consults the resolver.
        runCatching { created.player.setStreamResolver(graph.onlineMusic.streamResolver()) }
            .onFailure { Log.w(TAG, "stream resolver unavailable: ${it.message}") }

        // The player is the focus listener: a call, a nav prompt or another app
        // taking the stream must reach it, and nothing else in the process
        // should be reacting to focus.
        runCatching {
            graph.installFocusListener(created.player.asFocusListener())
        }

        created.player.addListener(this)
        created.library.markReady()

        createChannels()
        buildMediaSession(created, graph)
        restoreCheckpoint(created)

        startForegroundSafe()
        kickOffScan(created)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handleIntent(intent)
        // START_STICKY so the system restarts the service after a low-memory
        // kill; the checkpoint is what makes that restart seamless.
        return START_STICKY
    }

    override fun onDestroy() {
        scanJob?.cancel()
        backend?.let { b ->
            runCatching { b.player.saveCheckpoint() }
            runCatching { b.player.removeListener(this) }
            runCatching { b.player.release() }
            graph.detachMusicBackend(b)
            runCatching { b.close() }
        }
        backend = null
        session?.release()
        session = null
        frameworkSession?.release()
        frameworkSession = null
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    // -- intents -----------------------------------------------------------

    /**
     * Handles the transport intents the notification, the media buttons and the
     * UI all send. One action vocabulary so the three callers cannot drift.
     */
    private fun handleIntent(intent: Intent?) {
        val b = backend ?: return
        when (intent?.action) {
            ACTION_PLAY -> b.player.play()
            ACTION_PAUSE -> b.player.pause()
            ACTION_TOGGLE -> b.player.togglePlayPause()
            ACTION_NEXT -> b.player.next()
            ACTION_PREV -> b.player.previous()
            ACTION_STOP -> {
                b.player.pause()
                b.player.saveCheckpoint()
                stopSelf()
            }
            ACTION_STORAGE_REMOVED -> onStorageRemoved(intent.getStringExtra(EXTRA_PATH))
        }
    }

    /**
     * Stops playback because a USB/SD volume went away.
     *
     * Deliberately *before* the path is unreadable: `MediaPlayer` throws
     * `IOException` from a background thread if its file vanishes mid-read, and
     * that is the crash the spec calls out. Pausing here means the file is
     * still open and the position is still known.
     */
    private fun onStorageRemoved(path: String?) {
        val b = backend ?: return
        val current = lastState.track
        val affectsCurrent = path == null || current == null ||
            current.source.startsWith(path) ||
            !File(current.source).exists()
        if (!affectsCurrent) return
        runCatching {
            b.player.pause()
            b.player.saveCheckpoint()
        }
        prefs.edit().putString(KEY_USB_REMOVED_AT, System.currentTimeMillis().toString()).apply()
        publish(PlaybackError.SOURCE_UNAVAILABLE)
    }

    // -- scan --------------------------------------------------------------

    /**
     * Runs an incremental scan off the main thread.
     *
     * The scanner itself decides what changed; this only schedules it and gets
     * out of the way. A scan while the map is drawing is the exact contention
     * the spec warns about, so it is also delayed slightly past cold start.
     */
    private fun kickOffScan(b: LocalMusicBackend) {
        scanJob?.cancel()
        scanJob = scope?.launch {
            runCatching {
                if (!b.scanner.canScan()) return@launch
                val result = b.scanner.scan(forceFullScan = false)
                Log.i(TAG, "scan: scanned=${result.scannedFiles} added=${result.added} " +
                    "updated=${result.updated} removed=${result.removed} " +
                    "unsupported=${result.unsupported} in ${result.durationMs} ms")
                b.library.markReady()
                if (result.changedAnything) {
                    // The queue may now point at tracks that no longer exist.
                    // Reloading the checkpoint by id drops the stale entries
                    // instead of playing silence.
                    publish(null)
                }
            }.onFailure { Log.w(TAG, "scan failed: ${it.message}") }
        }
    }

    // -- session -----------------------------------------------------------

    private fun buildMediaSession(b: LocalMusicBackend, graph: AppGraph) {
        val compat = MediaSessionCompat(this, "Ts8Music").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = b.player.play()
                override fun onPause() = b.player.pause()
                override fun onSkipToNext() = b.player.next()
                override fun onSkipToPrevious() = b.player.previous()
                override fun onSeekTo(pos: Long) = b.player.seekTo(pos)
                override fun onStop() = handleIntent(Intent(ACTION_STOP))
            })
            isActive = true
        }
        session = compat

        // The framework session is what makes the transport actions reach the
        // system's own media UI on this firmware. On API 23 it needs a
        // framework PlaybackState with the actions declared, and the compat
        // session does not populate that on its own.
        runCatching {
            val fs = MediaSession(this, "Ts8MusicFramework")
            fs.setCallback(object : MediaSession.Callback() {
                override fun onPlay() = b.player.play()
                override fun onPause() = b.player.pause()
                override fun onSkipToNext() = b.player.next()
                override fun onSkipToPrevious() = b.player.previous()
                override fun onSeekTo(pos: Long) = b.player.seekTo(pos)
                override fun onStop() = handleIntent(Intent(ACTION_STOP))
            }, null)
            fs.isActive = true
            frameworkSession = fs
        }.onFailure { Log.w(TAG, "framework MediaSession unavailable: ${it.message}") }

        // Nothing here needs the graph beyond the audio bridge already installed
        // by onCreate; the parameter is kept so a later SyuAudioBridge can be
        // wired into the session's transport without changing this signature.
        @Suppress("UNUSED_EXPRESSION")
        graph
    }

    /**
     * Mirrors the player state into both sessions and the notification.
     *
     * Called from `onStateChanged` and after a transport action, so the
     * metadata and the notification never describe a different track than the
     * one playing.
     */
    private fun publish(error: PlaybackError?) {
        val b = backend ?: return
        val st = runCatching { b.player.state() }.getOrNull() ?: return
        if (error != null) lastState = st.copy(error = error) else lastState = st

        val track = lastState.track
        session?.let { s ->
            s.setMetadata(
                MediaMetadataCompatBuilder.build(track)
            )
            s.setPlaybackState(PlaybackStateCompatBuilder.build(lastState))
        }
        frameworkSession?.let { fs ->
            runCatching {
                fs.setMetadata(FrameworkMetadataBuilder.build(track))
                fs.setPlaybackState(FrameworkPlaybackStateBuilder.build(lastState, this))
            }
        }
        updateNotification(lastState)
    }

    override fun onStateChanged(state: PlaybackState) {
        lastState = state
        // The listener arrives on the main thread; publishing touches
        // MediaSession and Notification, both of which require it.
        publish(null)
    }

    override fun onError(error: PlaybackError, track: Track?) {
        Log.w(TAG, "playback error $error on ${track?.id}")
        lastState = lastState.copy(error = error, isPlaying = false)
        publish(error)
    }

    // -- checkpoint --------------------------------------------------------

    private fun restoreCheckpoint(b: LocalMusicBackend) {
        runCatching {
            val cp = b.checkpoint.load() ?: return
            if (cp.queueTrackIds.isEmpty()) return
            // Rebuild the queue from ids: a track that vanished (USB pulled
            // overnight) drops out rather than becoming a hole in the queue.
            val tracks = cp.queueTrackIds.mapNotNull { b.library.track(it) }
            if (tracks.isEmpty()) {
                b.checkpoint.clear()
                return
            }
            // Shuffle and repeat are restored before the queue so the first
            // `next()` after resume follows the same order as before.
            b.player.setShuffle(cp.shuffle)
            b.player.setRepeatMode(cp.repeatMode)
            val index = tracks.indexOfFirst { it.id == cp.trackId }.coerceAtLeast(0)
            b.player.setQueue(tracks, index, autoPlay = false)
            if (cp.positionMs > 0) b.player.seekTo(cp.positionMs)
        }
    }

    // -- notification ------------------------------------------------------

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PLAYBACK, "Phát nhạc", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
    }

    private fun updateNotification(st: PlaybackState) {
        val n = buildNotification(st)
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, n)
        }
    }

    private fun buildNotification(st: PlaybackState): Notification {
        val track = st.track
        val title = track?.title ?: getString(R.string.home_music_empty)
        val subtitle = track?.subtitle?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)

        val builder = NotificationCompat.Builder(this, CHANNEL_PLAYBACK)
            .setSmallIcon(R.drawable.ic_nav_music)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(st.isPlaying)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())

        if (track != null) {
            builder
                .addAction(
                    R.drawable.ic_prev, getString(R.string.music_previous),
                    serviceAction(ACTION_PREV, 1)
                )
                .addAction(
                    if (st.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                    getString(if (st.isPlaying) R.string.music_pause else R.string.music_play),
                    serviceAction(ACTION_TOGGLE, 2)
                )
                .addAction(
                    R.drawable.ic_next, getString(R.string.music_next),
                    serviceAction(ACTION_NEXT, 3)
                )
        }

        val style = androidx.media.app.NotificationCompat.MediaStyle()
        if (track != null) {
            style.setShowActionsInCompactView(0, 1, 2)
        }
        session?.sessionToken?.let { style.setMediaSession(it) }
        builder.setStyle(style)

        return builder.build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MusicFullscreenActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 0, intent, pendingFlags())
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MusicPlaybackService::class.java).setAction(action)
        return PendingIntent.getService(this, requestCode, intent, pendingFlags())
    }

    /**
     * `FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE`, with no version branch.
     *
     * `FLAG_IMMUTABLE` was added in API 23 — this app's `minSdk` — and the
     * value is a compile-time constant, so naming it here needs no guard.
     * (The platform only *requires* mutability to be declared when `targetSdk`
     * is 31+; this APK targets 30, so it is a deliberate choice rather than a
     * requirement.)
     */
    private fun pendingFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /**
     * Enters the foreground, tolerating a platform that refuses.
     *
     * `startForeground` on API 23 starts a notification; on API 26+ it requires
     * a channel, which [createChannels] made. A refusal is not fatal — playback
     * still works, it is just killable — so it is logged rather than crashing
     * a launcher.
     */
    private fun startForegroundSafe() {
        val n = buildNotification(lastState)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        }.onFailure { Log.w(TAG, "startForeground refused: ${it.message}") }
    }

    companion object {
        private const val TAG = "Ts8MusicService"
        private const val CHANNEL_PLAYBACK = "ts8.playback"
        private const val NOTIFICATION_ID = 0x7853
        private const val PREFS = "ts8.music.service"
        private const val KEY_USB_REMOVED_AT = "usb_removed_at"

        const val ACTION_PLAY = "com.ts8.launcher.music.PLAY"
        const val ACTION_PAUSE = "com.ts8.launcher.music.PAUSE"
        const val ACTION_TOGGLE = "com.ts8.launcher.music.TOGGLE"
        const val ACTION_NEXT = "com.ts8.launcher.music.NEXT"
        const val ACTION_PREV = "com.ts8.launcher.music.PREV"
        const val ACTION_STOP = "com.ts8.launcher.music.STOP"
        const val ACTION_STORAGE_REMOVED = "com.ts8.launcher.music.STORAGE_REMOVED"
        const val EXTRA_PATH = "path"

        /** Starts the service without a transport action, so it can scan. */
        fun start(context: Context) {
            runCatching {
                context.startService(Intent(context, MusicPlaybackService::class.java))
            }
        }

        /** Sends a transport action to the running service. */
        fun send(context: Context, action: String) {
            runCatching {
                context.startService(
                    Intent(context, MusicPlaybackService::class.java).setAction(action)
                )
            }
        }

        /** Tells the service a volume was unmounted. */
        fun notifyStorageRemoved(context: Context, path: String?) {
            send(context, ACTION_STORAGE_REMOVED)
        }
    }
}

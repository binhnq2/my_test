package com.ts8.launcher.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.ts8.core.music.MusicBackend
import com.ts8.core.music.PlaybackError
import com.ts8.core.music.PlaybackListener
import com.ts8.core.music.PlaybackState
import com.ts8.core.music.Track
import com.ts8.core.music.local.LocalMusicBackend
import com.ts8.launcher.service.MusicPlaybackService
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The UI's connection to the playback service.
 *
 * **Why the screens do not bind directly.** Three screens show playback state
 * — Home's music card, the Navigation strip and the full music screen — and
 * each one would otherwise hold its own `ServiceConnection` and its own
 * `PlaybackListener`. Service binding is not free and, worse, each listener
 * would receive state in a slightly different order relative to `onResume`,
 * so the three cards could disagree about whether music is playing.
 *
 * **Why reference counted.** The service is started with `startService` as
 * well as bound: it keeps playing when no screen is bound (that is the whole
 * point of the foreground service), and it is not killed when the last screen
 * unbinds. The connection itself stays open while at least one screen is
 * attached, and closes when the last one leaves, which keeps the binder
 * reference out of the process's memory when nothing is visible.
 *
 * **API 23 note.** `bindService` is asynchronous even when the service is
 * already running, so a screen that binds in `onStart` and renders in
 * `onResume` would show "chưa phát nhạc" for one frame. [state] therefore
 * returns the last known snapshot, which is correct across a screen switch.
 */
class MusicRuntime(context: Context) {

    /** See the note on `AppGraph`: the instance is a static field, so only the
     *  application context may be kept. */
    private val context: Context = context.applicationContext

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** Every attached screen's callback. */
    private val observers = CopyOnWriteArrayList<Observer>()

    @Volatile
    private var bound = false
    private var binding = false
    private var refCount = 0

    /**
     * The backend, once bound.
     *
     * Reaches past [MusicBackend] to the local implementation because the
     * scanner lives there; the UI needs `library` and `player` from the
     * interface, and the service owns everything else.
     */
    @Volatile
    var backend: LocalMusicBackend? = null
        private set

    interface Observer {
        /** Playback state changed. Main thread. */
        fun onPlaybackState(state: PlaybackState)

        /** The backend became available (or went away, with a null argument). */
        fun onBackendChanged(backend: MusicBackend?) {}
    }

    /** Internal listener forwarded from the player into [observers]. */
    private val playbackListener = object : PlaybackListener {
        override fun onStateChanged(state: PlaybackState) = publish(state)

        override fun onError(error: PlaybackError, track: Track?) {
            // The player also pushes a state carrying the error, so the UI gets
            // both; this path exists to log which track failed.
            Log.w(TAG, "playback error $error on ${track?.title}")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val local = service as? MusicPlaybackService.LocalBinder
            val b = local?.backend()
            binding = false
            if (b == null) {
                bound = false
                Log.w(TAG, "bound to a service with no backend")
                return
            }
            bound = true
            backend = b
            b.player.addListener(playbackListener)
            main.post {
                for (o in observers) runCatching { o.onBackendChanged(b) }
                publish(b.player.state())
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The process is still alive (or the service is restarting); the
            // player object is gone, so its listener registration went with it.
            bound = false
            binding = false
            backend = null
            stopTicking()
            main.post {
                for (o in observers) runCatching { o.onBackendChanged(null) }
            }
        }
    }

    /** Last snapshot, so a screen switching in has something to draw at once. */
    @Volatile
    private var lastState: PlaybackState = PlaybackState()

    fun state(): PlaybackState = lastState

    // -- position ticking ------------------------------------------------------
    //
    // §13/§14: the seek bar and the elapsed counter must advance while a track
    // plays. Nothing used to produce a `PlaybackState` between *events*, so a
    // screen sat on the position it was given when playback started until the
    // track changed — a bar frozen at 0:00 for four minutes, which reads as a
    // broken player.
    //
    // Three places could have owned this and only one is right:
    //
    // - `LocalMusicPlayer` could call `notifyState()` in its checkpoint ticker.
    //   That ticker runs every ten seconds (its job is the resume point) and
    //   its notifications fan out to the *service* as well, which rebuilds the
    //   MediaSession and the notification on every tick. Ten notification
    //   rebuilds a minute is real work on this SoC for a number nobody is
    //   reading in the notification shade.
    // - The screens could each run their own ticker, which is three tickers
    //   drifting apart and three chances to leak a `Handler` callback.
    // - Here, which is the seam where a UI-facing snapshot already exists.
    //
    // Publishing to [observers] and **not** to the service is deliberate: the
    // session's position is already correct because both session builders put
    // `st.positionMs` in the extras and the platform extrapolates from the
    // update time. Only the visible UI needs a heartbeat.

    /** True while [uiTicker] is queued, so it cannot be posted twice. */
    private var ticking = false

    private val uiTicker = object : Runnable {
        override fun run() {
            ticking = false
            val b = backend
            // Stop when nothing is playing: a paused player's position does not
            // change, and a ticker left running on a paused screen would wake
            // the CPU once a second to redraw the same digits.
            if (b == null || !lastState.isPlaying) return
            publish(b.player.state())
            scheduleTick()
        }
    }

    private fun scheduleTick() {
        if (ticking) return
        ticking = true
        main.postDelayed(uiTicker, POSITION_TICK_MS)
    }

    private fun stopTicking() {
        ticking = false
        main.removeCallbacks(uiTicker)
    }

    /** Attaches an observer, starting the service and binding on first use.
     *
     * Always fail-soft: a firmware that refuses the service start leaves the
     * launcher usable with an empty music card, which is what the spec means by
     * graceful degradation.
     */
    fun attach(observer: Observer) {
        if (!observers.contains(observer)) observers.add(observer)
        refCount++
        observer.onPlaybackState(lastState)
        val b = backend
        if (b != null) {
            observer.onBackendChanged(b)
            scheduleTick()
            return
        }
        if (binding) return

        binding = true
        // startService first: the service is what survives the unbind, and
        // starting it before binding avoids an onServiceConnected for a service
        // that is about to be stopped and restarted.
        MusicPlaybackService.start(appContext)
        val intent = Intent(appContext, MusicPlaybackService::class.java)
        val ok = runCatching { appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
        if (!ok) {
            binding = false
            Log.w(TAG, "bindService refused; music UI will stay empty")
        }
    }

    fun detach(observer: Observer) {
        observers.remove(observer)
        if (refCount > 0) refCount--
        if (refCount > 0) return
        // The last screen has gone: nothing is watching the position, and the
        // service's own checkpoint ticker already covers what must persist.
        stopTicking()
        unbind()
    }

    private fun unbind() {
        backend?.player?.removeListener(playbackListener)
        if (bound || binding) {
            runCatching { appContext.unbindService(connection) }
        }
        bound = false
        binding = false
        backend = null
    }

    private fun publish(state: PlaybackState) {
        lastState = state
        // A transition into or out of playing starts or stops the heartbeat.
        // Done here rather than in the observer callback so a track that starts
        // from any screen — a voice command, the notification, a queue tap —
        // begins ticking without that screen having to know it must.
        if (state.isPlaying) scheduleTick() else stopTicking()
        main.post {
            for (o in observers) runCatching { o.onPlaybackState(state) }
        }
    }

    // -- transport -----------------------------------------------------------
    //
    // These go through the running service's intents rather than the bound
    // player, so a tap works even in the window before the binding completes
    // (which is the state the very first screen after boot is in).

    fun play() = MusicPlaybackService.send(appContext, MusicPlaybackService.ACTION_PLAY)
    fun pause() = MusicPlaybackService.send(appContext, MusicPlaybackService.ACTION_PAUSE)
    fun toggle() = MusicPlaybackService.send(appContext, MusicPlaybackService.ACTION_TOGGLE)
    fun next() = MusicPlaybackService.send(appContext, MusicPlaybackService.ACTION_NEXT)
    fun previous() = MusicPlaybackService.send(appContext, MusicPlaybackService.ACTION_PREV)

    /** Seek needs the bound player: the service has no seek intent because a
     *  position in an `Intent` would be a second source of truth. */
    fun seekTo(positionMs: Long) {
        backend?.player?.seekTo(positionMs)
    }

    fun setShuffle(enabled: Boolean) {
        backend?.player?.setShuffle(enabled)
    }

    fun setRepeatMode(mode: com.ts8.core.music.RepeatMode) {
        backend?.player?.setRepeatMode(mode)
    }

    fun setQueue(tracks: List<Track>, startIndex: Int, autoPlay: Boolean = true) {
        backend?.player?.setQueue(tracks, startIndex, autoPlay)
    }

    companion object {
        private const val TAG = "Ts8MusicRuntime"

        /**
         * 500 ms, not 1000: a seek-bar thumb that moves in one-second steps on
         * a 4:00 track is visibly stuttering, and half a second is roughly the
         * shortest interval a driver reads as smooth without the redraw cost
         * of a 60 Hz animation — which §17 forbids anyway.
         */
        private const val POSITION_TICK_MS = 500L

        /**
         * One runtime per process. The Application holds it so that two
         * Activities attaching at the same moment (Home resumes while the
         * music screen finishes its own `onStart`) share one binding.
         */
        @Volatile
        private var instance: MusicRuntime? = null

        fun get(context: Context): MusicRuntime =
            instance ?: synchronized(this) {
                instance ?: MusicRuntime(context.applicationContext).also { instance = it }
            }
    }
}

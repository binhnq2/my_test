package com.ts8.core.music.local

import com.ts8.core.music.RepeatMode
import com.ts8.core.music.Track
import kotlin.random.Random

/**
 * The decisions playback makes, separated from the object that plays audio.
 *
 * Every branch here used to live inside `LocalMusicPlayer`, tangled with
 * `MediaPlayer` calls and a `Handler`, which meant it could only be tested on a
 * device — and the behaviours that actually break in a car are exactly these
 * ones: whether music resumes after a call the driver never asked it to
 * resume, whether a navigation prompt leaves the music quiet forever, whether
 * a shutdown loses the resume position.
 *
 * Nothing in this class touches Android. `LocalMusicPlayer` owns the
 * `MediaPlayer` and asks [PlaybackCore] what to do; the answer is a
 * [Transport] the player executes. That split is also what makes a future
 * `SyuAudioBridge` testable: the bridge changes, the decisions do not.
 */
class PlaybackCore(
    private val random: Random = Random.Default
) {
    /** What the player should do next. */
    enum class Transport {
        /** Start or resume audio. */
        PLAY,

        /** Stop audio, keeping the position. */
        PAUSE,

        /** Do nothing to the transport; only flags changed. */
        NONE
    }

    val queue = PlaybackQueue(random)

    var repeatMode: RepeatMode = RepeatMode.OFF
        private set

    /** True while audio is actually coming out. */
    var playing: Boolean = false
        private set

    /** True once a track is loaded, playing or not. */
    var loaded: Boolean = false
        private set

    /** True when playback should resume once the call ends. */
    private var pausedForCall = false

    /** True when playback should resume once focus returns. */
    private var pausedForFocus = false

    /** True while the stream is lowered for a navigation prompt. */
    var duckedForNavigation: Boolean = false
        private set

    fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
    }

    fun setLoaded(loaded: Boolean) {
        this.loaded = loaded
        if (!loaded) playing = false
    }

    fun setPlaying(playing: Boolean) {
        this.playing = playing && loaded
    }

    fun setDuckedForNavigation(ducked: Boolean) {
        duckedForNavigation = ducked
    }

    fun isPausedForCall(): Boolean = pausedForCall

    /** True when the queue wraps at the end, which repeat-all and repeat-one imply. */
    fun wrapsAtEnd(): Boolean = repeatMode != RepeatMode.OFF

    // -- focus events -------------------------------------------------------

    /**
     * A call started.
     *
     * A ring or an active call stops the music outright — never ducks. The
     * driver has to hear the caller, and music under a conversation is worse
     * than silence.
     */
    fun onCallStarted(): Transport {
        if (!playing) return Transport.NONE
        pausedForCall = true
        playing = false
        return Transport.PAUSE
    }

    /**
     * The call ended.
     *
     * Resumes **only** if this class paused the music for the call. A track the
     * driver had paused by hand stays paused; restarting it is the single most
     * irritating thing a car player can do.
     */
    fun onCallEnded(): Transport {
        if (!pausedForCall) return Transport.NONE
        pausedForCall = false
        if (!loaded) return Transport.NONE
        playing = true
        return Transport.PLAY
    }

    /**
     * Focus was lost for a short time.
     *
     * @param mayDuck true when the system allows lowering the volume instead of
     *        stopping. A prompt or a notification ducks; a full transient loss
     *        pauses.
     */
    fun onTransientLoss(mayDuck: Boolean): Transport {
        if (!playing) return Transport.NONE
        if (mayDuck) {
            duckedForNavigation = true
            return Transport.NONE
        }
        pausedForFocus = true
        playing = false
        return Transport.PAUSE
    }

    /** Focus returned. Resumes only what this class paused. */
    fun onFocusGained(): Transport {
        val wasDucked = duckedForNavigation
        duckedForNavigation = false
        if (pausedForFocus) {
            pausedForFocus = false
            if (!loaded) return Transport.NONE
            playing = true
            return Transport.PLAY
        }
        // Focus came back from a duck: the stream is restored elsewhere, but
        // playback never stopped, so there is nothing to start.
        return if (wasDucked) Transport.NONE else Transport.NONE
    }

    /** Focus was lost permanently: another app is playing. Always pauses. */
    fun onFocusLostPermanent(): Transport {
        duckedForNavigation = false
        if (!playing) return Transport.NONE
        pausedForFocus = true
        playing = false
        return Transport.PAUSE
    }

    /** A manual pause clears both auto-resume flags. */
    fun onManualPause() {
        pausedForCall = false
        pausedForFocus = false
        playing = false
    }

    /** The user pressed play; clear the auto-resume flags so nothing doubles up. */
    fun onManualPlay() {
        pausedForCall = false
        pausedForFocus = false
        playing = loaded
    }

    // -- queue advancement --------------------------------------------------

    /** What to do when the current track finishes. */
    sealed class Completion {
        /** Play the same track again from the start (repeat-one). */
        object RepeatCurrent : Completion()

        /** Move to [track] and play it. */
        data class Advance(val track: Track) : Completion()

        /** The queue ended; stop and keep the last track loaded. */
        object Stop : Completion()
    }

    fun onTrackCompleted(): Completion {
        if (repeatMode == RepeatMode.ONE) return Completion.RepeatCurrent
        val next = queue.next(wrapsAtEnd())
        return if (next == null) {
            playing = false
            Completion.Stop
        } else {
            Completion.Advance(next)
        }
    }

    /**
     * Next pressed by hand.
     *
     * Returns null at the end of a non-repeating queue, and changes nothing in
     * that case: a driver pressing next on the last song should keep hearing
     * the last song, not have the UI claim playback stopped while audio
     * continues. Stopping is [onTrackCompleted]'s job, where the track really
     * has ended.
     */
    fun onNextPressed(): Track? = queue.next(wrapsAtEnd())

    fun onPreviousPressed(restartInstead: Boolean): Track? {
        // A press within the first seconds restarts the track; the caller
        // decides the threshold, because it depends on the player's position.
        if (restartInstead) return queue.current()
        val track = queue.previous(wrapsAtEnd())
        return track ?: queue.current()
    }
}

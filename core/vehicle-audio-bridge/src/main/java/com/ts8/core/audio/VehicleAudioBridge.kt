package com.ts8.core.audio

import com.ts8.core.music.AudioRoute

/**
 * The single seam between the launcher and whatever actually owns the vehicle's
 * audio hardware.
 *
 * Today there is one implementation — [AndroidVehicleAudioBridge], built on the
 * public Android audio APIs the TS8 firmware exposes. The head unit's own MCU
 * exposes a deeper source-switching interface (radio, Bluetooth, auxiliary,
 * DVD) that is **not** part of the public SDK; the spec puts that in a later
 * phase. Everything in the app talks to this interface, so swapping in a
 * `SyuAudioBridge` later is a constructor change in one place.
 *
 * Design rules this interface encodes:
 *
 * - **Never claim a switch that did not happen.** Every request returns a
 *   [AudioResult] and the caller surfaces a failure. The spec forbids
 *   pretending a privileged action succeeded.
 * - **Always be able to restore.** Anything the bridge pauses, it can resume;
 *   anything it ducks, it unducks. A bridge that takes the rail and forgets to
 *   give it back leaves the driver in silence.
 * - **No blocking calls on the main thread.**
 */
interface VehicleAudioBridge {

    /** The audio route the app currently drives. */
    fun currentRoute(): AudioRoute

    /**
     * Requests audio focus for [route].
     *
     * For [AudioRoute.MEDIA] this is a normal focus request: transient
     * exclusive when a call arrives, may-duck when navigation speaks.
     */
    fun requestFocus(route: AudioRoute): AudioResult

    /** Gives focus back, restoring anything this bridge took. */
    fun abandonFocus(route: AudioRoute)

    /** True while this bridge holds focus for [route]. */
    fun hasFocus(route: AudioRoute): Boolean

    /**
     * Temporarily lowers the media stream for an announcement.
     *
     * Implementations must be idempotent: two navigation prompts in a row must
     * not duck twice and leave the music quiet afterwards.
     */
    fun duck(): AudioResult

    /** Restores the media stream to the level it had before [duck]. */
    fun unduck()

    /** True when the media stream is currently ducked by this bridge. */
    fun isDucked(): Boolean

    /**
     * Pauses a non-media source (FM/AM radio, or a paired phone's playback)
     * because the launcher needs the speakers.
     *
     * On stock Android 6 this cannot pause the radio: the radio output is owned
     * by a vendor app with no public API. The implementation returns
     * [AudioResult.Unsupported] in that case and the caller must not pretend it
     * happened. Bluetooth playback, by contrast, *can* be paused through
     * AVRCP, and [canPause]/[pause] handle it.
     */
    fun pauseExternalSource(): AudioResult

    /** Resumes a source suspended by [pauseExternalSource]. */
    fun resumeExternalSource(): AudioResult

    /** Whether this device can suspend the external source at all. */
    fun canPauseExternalSource(): Boolean

    fun setMediaVolume(index: Int): AudioResult

    fun mediaVolume(): Int

    fun maxMediaVolume(): Int

    /**
     * Whether the platform reports the stream as fixed at one level.
     *
     * Needed because a read-back alone cannot tell "this firmware ignores me"
     * from "this device has no volume knob": both read back unchanged. A fixed
     * stream is [AudioResult.Unsupported] territory — hide the control — while
     * a non-fixed stream that refuses to move is NO_EFFECT — keep the control
     * and say so once.
     *
     * Defaults to `false` so a bridge written before this existed still
     * compiles and behaves as it did.
     */
    fun isVolumeFixed(): Boolean = false

    /** Registers for focus loss/regain from other apps and system events. */
    fun setFocusListener(listener: AudioFocusListener)

    /** Releases all resources and restores the pre-bridge state. */
    fun release()
}

/** Result of a request that may be refused. Never throws. */
sealed class AudioResult {
    object Success : AudioResult()

    /**
     * The write was accepted by the platform and the value **did not change**.
     *
     * **Why this is a distinct type and not a [Success].** On this class of
     * head unit the Android media stream is frequently not the one the cabin
     * amplifier listens to: the MCU owns a mixer the public SDK cannot see, so
     * `AudioManager.setStreamVolume` returns normally, the read-back returns the
     * old number, and nothing sounds different. Before this type existed that
     * sequence was reported as `Success` and the UI moved its label to a value
     * the driver could not hear — the app claimed a change it had not made.
     *
     * [requested] and [observed] are carried so the caller can show, and the
     * diagnostics log can record, exactly what happened: a driver who taps "+"
     * on a stream pinned at 7 is owed the numbers 8 and 7, not a generic error.
     */
    data class NoEffect(val requested: Int, val observed: Int) : AudioResult()

    /**
     * The action is possible but was refused right now (another app holds
     * focus, a call is in progress).
     */
    data class Denied(val reason: DenyReason) : AudioResult()

    /**
     * The platform simply cannot do this. Distinct from [Denied]: no retry will
     * help, and the UI should not offer the control.
     */
    data class Unsupported(val detail: String) : AudioResult()

    data class Failed(val detail: String) : AudioResult()
}

enum class DenyReason {
    /** Another app currently owns audio focus. */
    FOCUS_HELD_BY_OTHER,

    /** A phone call is active or ringing. */
    CALL_IN_PROGRESS,

    /** The system refused for a reason it did not report. */
    SYSTEM_REFUSED
}

/** Events the app must react to. All arrive on the main thread. */
interface AudioFocusListener {
    /** Focus lost permanently — another app is playing. Pause playback. */
    fun onFocusLostPermanent()

    /**
     * Focus lost for a short time. [mayDuck] is true when the system allows
     * lowering the volume instead of stopping.
     */
    fun onFocusLostTransient(mayDuck: Boolean)

    /** Focus returned after a transient loss. */
    fun onFocusGained()

    /** A call started; playback must stop immediately, not duck. */
    fun onCallStarted()

    /** The call ended. */
    fun onCallEnded()

    /** The audio output device changed (e.g. the head unit switched sources). */
    fun onAudioRouteChanged(route: AudioRoute)
}

/** Focus states as the bridge reports them, independent of Android constants. */
enum class FocusState { NONE, GAINED, TRANSIENT, TRANSIENT_DUCKING, LOSS_TRANSIENT, LOSS }

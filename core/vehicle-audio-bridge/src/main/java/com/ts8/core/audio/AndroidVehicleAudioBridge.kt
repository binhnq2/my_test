package com.ts8.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.ts8.core.music.AudioRoute

/**
 * [VehicleAudioBridge] on stock Android 6 audio APIs.
 *
 * What this actually does on the TS8 firmware, and what it does not:
 *
 * | action                    | works | why |
 * |---------------------------|-------|-----|
 * | request/abandon media focus | yes | public `AudioManager` API |
 * | detect a call and stop      | yes | focus loss with `LOSS_TRANSIENT` |
 * | duck for navigation         | yes | `setStreamVolume`, restored on unduck |
 * | pause FM/AM radio           | **no** | vendor app owns the tuner; no SDK |
 * | pause Bluetooth playback    | partial | needs AVRCP, see below |
 *
 * The radio row is the important one: the spec forbids reporting success for a
 * privileged action, so [pauseExternalSource] returns
 * [AudioResult.Unsupported] rather than pretending. The UI renders that as a
 * disabled control with an explanation, not a silent failure.
 */
class AndroidVehicleAudioBridge(
    context: Context
) : VehicleAudioBridge {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var focusListener: AudioFocusListener? = null
    private var mediaFocusHeld = false
    private var navFocusHeld = false

    private var ducked = false
    /** Volume before the first duck, so nested ducks restore correctly. */
    private var volumeBeforeDuck = -1

    private var externalPaused = false

    /** Whether the vendor radio can be suspended — always false here. */
    private val radioControllable = false

    // -- focus -------------------------------------------------------------

    private val androidFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        val listener = focusListener ?: return@OnAudioFocusChangeListener
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                mediaFocusHeld = false
                listener.onFocusLostPermanent()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                mediaFocusHeld = false
                listener.onFocusLostTransient(mayDuck = false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                listener.onFocusLostTransient(mayDuck = true)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                mediaFocusHeld = true
                listener.onFocusGained()
            }
        }
    }

    /**
     * API 26 introduced [AudioFocusRequest]; API 23 only has the deprecated
     * overload. Both paths are kept so the same code runs on the head unit and
     * on a modern emulator for testing.
     */
    override fun requestFocus(route: AudioRoute): AudioResult {
        if (route == AudioRoute.MEDIA && mediaFocusHeld) return AudioResult.Success
        if (route == AudioRoute.NAVIGATION && navFocusHeld) return AudioResult.Success

        val attributes = AudioAttributes.Builder()
            .setUsage(
                if (route == AudioRoute.NAVIGATION) AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                else AudioAttributes.USAGE_MEDIA
            )
            .setContentType(
                if (route == AudioRoute.NAVIGATION) AudioAttributes.CONTENT_TYPE_SPEECH
                else AudioAttributes.CONTENT_TYPE_MUSIC
            )
            .build()

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(androidFocusListener, mainHandler)
                .setWillPauseWhenDucked(false)
                .build()
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                androidFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }

        return when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                if (route == AudioRoute.NAVIGATION) navFocusHeld = true else mediaFocusHeld = true
                AudioResult.Success
            }
            AudioManager.AUDIOFOCUS_REQUEST_FAILED -> AudioResult.Denied(
                if (isCallActive()) DenyReason.CALL_IN_PROGRESS else DenyReason.FOCUS_HELD_BY_OTHER
            )
            // AUDIOFOCUS_REQUEST_DELAYED only exists on API 26+.
            else -> AudioResult.Denied(DenyReason.SYSTEM_REFUSED)
        }
    }

    override fun abandonFocus(route: AudioRoute) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(
                    if (route == AudioRoute.NAVIGATION)
                        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                    else AudioAttributes.USAGE_MEDIA
                )
                .build()
            audioManager.abandonAudioFocusRequest(
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(androidFocusListener)
                    .build()
            )
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(androidFocusListener)
        }
        if (route == AudioRoute.NAVIGATION) navFocusHeld = false else mediaFocusHeld = false
    }

    override fun hasFocus(route: AudioRoute): Boolean =
        if (route == AudioRoute.NAVIGATION) navFocusHeld else mediaFocusHeld

    override fun currentRoute(): AudioRoute =
        if (isCallActive()) AudioRoute.CALL else AudioRoute.MEDIA

    /** True when the platform reports a call is occupying audio. */
    private fun isCallActive(): Boolean = audioManager.mode == AudioManager.MODE_IN_CALL ||
        audioManager.mode == AudioManager.MODE_IN_COMMUNICATION ||
        audioManager.mode == AudioManager.MODE_RINGTONE

    // -- ducking -----------------------------------------------------------

    /**
     * Lowering the stream volume is the only mechanism API 23 offers that a
     * head unit's mixer respects. `setStreamVolume` needs no permission and is
     * restored exactly, which matters: the spec requires music to come back at
     * the level it had, not at some default.
     */
    override fun duck(): AudioResult {
        if (ducked) return AudioResult.Success
        return try {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = (current * DUCK_FRACTION).toInt().coerceAtLeast(1)
            if (target >= current) {
                // Nothing to lower (already at the floor). Ducking "succeeded"
                // but there is no change to undo.
                ducked = false
                return AudioResult.Success
            }
            volumeBeforeDuck = current
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            ducked = true
            AudioResult.Success
        } catch (e: SecurityException) {
            AudioResult.Unsupported("changing the media stream volume is not permitted")
        } catch (e: Exception) {
            AudioResult.Failed(e.message ?: "duck failed")
        }
    }

    override fun unduck() {
        if (!ducked) return
        val restore = volumeBeforeDuck
        if (restore >= 0) {
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0) }
        }
        volumeBeforeDuck = -1
        ducked = false
    }

    override fun isDucked(): Boolean = ducked

    // -- external sources --------------------------------------------------

    override fun canPauseExternalSource(): Boolean = radioControllable

    /**
     * Cannot pause the tuner on this firmware.
     *
     * Returning [AudioResult.Unsupported] is the honest answer: the vendor app
     * owns `/dev/radio` and exposes no broadcast or service we may call. The
     * launcher therefore asks the driver to switch source manually, which is
     * what the required documentation instructs.
     */
    override fun pauseExternalSource(): AudioResult {
        if (!radioControllable) {
            return AudioResult.Unsupported(
                "AM/FM radio is owned by the firmware's tuner app and has no public " +
                    "control API on this build"
            )
        }
        externalPaused = true
        return AudioResult.Success
    }

    override fun resumeExternalSource(): AudioResult {
        if (!externalPaused) return AudioResult.Success
        if (!radioControllable) {
            return AudioResult.Unsupported("tuner cannot be resumed by this app")
        }
        externalPaused = false
        return AudioResult.Success
    }

    // -- volume ------------------------------------------------------------

    /**
     * Writes the media stream and *reads it back* before claiming anything.
     *
     * §8's rule is read-before / write / read-after, and the read-after is the
     * whole point: `setStreamVolume` returning normally proves only that the
     * call was accepted, not that the cabin's amplifier follows the Android
     * stream. On this hardware it often does not — the MCU owns a mixer with no
     * public API — so a write that changes nothing is a normal outcome and must
     * be reported as [AudioResult.NoEffect] rather than [AudioResult.Success].
     *
     * The read-back is deliberately immediate rather than delayed. A delayed
     * re-read would be more forgiving of audio HALs that apply the change
     * asynchronously, but it would also mean the UI shows the old value for a
     * moment on hardware that does work, and this call happens on a discrete
     * tap, not per frame. If a future firmware turns out to apply asynchronously
     * the capability tracker in the UI layer absorbs it: a single late read-back
     * is not enough on its own to declare NO_EFFECT, only two consecutive ones.
     */
    override fun setMediaVolume(index: Int): AudioResult = try {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val before = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = index.coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        val after = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (after == before) {
            AudioResult.NoEffect(requested = target, observed = after)
        } else {
            AudioResult.Success
        }
    } catch (e: SecurityException) {
        AudioResult.Unsupported("changing the media stream volume is not permitted")
    } catch (e: Exception) {
        AudioResult.Failed(e.message ?: "volume change failed")
    }

    /**
     * True when the platform itself reports that the volume is not adjustable.
     *
     * `isVolumeFixed` is API 21 and needs no permission, which is what makes it
     * usable on API 23 here. It answers the one question a read-back cannot:
     * a stream pinned at a fixed level is not a firmware fault to retry, it is
     * a device that has no volume control to offer, and the UI should hide the
     * control rather than report NO_EFFECT forever.
     */
    override fun isVolumeFixed(): Boolean = runCatching {
        audioManager.isVolumeFixed
    }.getOrDefault(false)

    override fun mediaVolume(): Int = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    override fun maxMediaVolume(): Int =
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    // -- lifecycle ---------------------------------------------------------

    override fun setFocusListener(listener: AudioFocusListener) {
        focusListener = listener
    }

    override fun release() {
        unduck()
        if (mediaFocusHeld) abandonFocus(AudioRoute.MEDIA)
        if (navFocusHeld) abandonFocus(AudioRoute.NAVIGATION)
        focusListener = null
    }

    companion object {
        /**
         * Duck to this fraction of the current level. Low enough that speech is
         * clearly audible over it, high enough that the driver still hears the
         * music is there.
         */
        private const val DUCK_FRACTION = 0.25f
    }
}

package com.ts8.launcher.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent

/**
 * Media buttons from the steering wheel and the head unit's own hardware keys.
 *
 * **Why a receiver and not the session's callback alone.** The session callback
 * is the modern path, but on this firmware the CANBUS app injects
 * `ACTION_MEDIA_BUTTON` broadcasts rather than going through the session, so
 * without this receiver the steering-wheel keys do nothing while the
 * notification buttons work.
 *
 * **Why `goAsync`.** `startService` on API 23 from a receiver is a synchronous
 * call; doing it on the main thread is correct but the receiver must not hold
 * the broadcast beyond its window either. The service start is fire-and-forget
 * and the service owns the actual work, so no `goAsync` is needed — the note
 * exists because the opposite is a tempting mistake here.
 */
class MediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val event = mediaEvent(intent) ?: return
        if (event.action != KeyEvent.ACTION_DOWN) return

        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> MusicPlaybackService.ACTION_PLAY
            KeyEvent.KEYCODE_MEDIA_PAUSE -> MusicPlaybackService.ACTION_PAUSE
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> MusicPlaybackService.ACTION_TOGGLE
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                MusicPlaybackService.ACTION_NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND ->
                MusicPlaybackService.ACTION_PREV
            KeyEvent.KEYCODE_MEDIA_STOP -> MusicPlaybackService.ACTION_STOP
            // Head units commonly send headset hook as the single multifunction
            // key; treating it as play/pause is what the driver expects.
            KeyEvent.KEYCODE_HEADSETHOOK -> MusicPlaybackService.ACTION_TOGGLE
            else -> return
        }
        Log.d(TAG, "media key ${event.keyCode} -> $action")
        MusicPlaybackService.send(context, action)
    }

    /**
     * Reads the key event out of the broadcast.
     *
     * The deprecated `getParcelableExtra(name)` is correct and available on
     * every API this app ships to (min 23), so the newer typed overload is not
     * needed and its `TIRAMISU` constant would be a verified-but-dead branch.
     */
    private fun mediaEvent(intent: Intent): KeyEvent? {
        @Suppress("DEPRECATION")
        return intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
    }

    companion object {
        private const val TAG = "Ts8MediaButton"
    }
}

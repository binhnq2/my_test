package com.ts8.launcher.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Parcelable
import android.util.Log

/**
 * Tells the music service that a storage volume went away.
 *
 * **Why a manifest receiver for storage.** `MEDIA_UNMOUNTED`, `MEDIA_EJECT` and
 * `MEDIA_REMOVED` are delivered when the driver pulls the stick, and they are
 * the *only* advance warning before the files stop being readable. Handling
 * them here means playback pauses with its position saved, rather than
 * `MediaPlayer` throwing an `IOException` from a background thread — the exact
 * crash the spec forbids.
 *
 * **Why every storage action, not just unmount.** Vendor firmware on this head
 * unit sends `MEDIA_EJECT` on some sticks and `MEDIA_UNMOUNTED` on others, and
 * `MEDIA_REMOVED` without a prior unmount when the driver yanks it. All three
 * funnel into one call, which is idempotent on the service side.
 *
 * **USB device attach** is deliberately not used to start a scan: the volume is
 * not mounted yet at that point, so a scan would see nothing. The media actions
 * are the ones that fire after the filesystem is ready.
 *
 * **API 23 note.** The volume extra is read as `Parcelable` and reflected on,
 * never typed as `StorageVolume`. `StorageVolume` is API 24, so a typed read is
 * a `NoSuchMethodError` on this unit on the very path that is supposed to
 * prevent a crash — the one place where being precise about the class would
 * defeat the purpose of the code.
 */
class UsbMediaReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.data?.path ?: volumePathFrom(intent)
        when (intent.action) {
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_EJECT,
            Intent.ACTION_MEDIA_REMOVED,
            Intent.ACTION_MEDIA_BAD_REMOVAL -> {
                Log.i(TAG, "storage gone: ${intent.action} path=$path")
                MusicPlaybackService.notifyStorageRemoved(context, path)
            }
            Intent.ACTION_MEDIA_MOUNTED -> {
                // A stick was inserted: the incremental scanner picks up only
                // the new files, and the service is already running.
                Log.i(TAG, "storage mounted: $path")
                MusicPlaybackService.start(context)
            }
        }
    }

    /**
     * Extracts a volume's mount point without naming `StorageVolume`.
     *
     * `getPath()` is the method that exists on API 23 but is hidden;
     * `getDirectory()` is public but API 30+. Both are tried reflectively on
     * whatever `Parcelable` the intent carried, and a failure returns null —
     * which tells the service "a volume changed, re-check everything". That is
     * the safe answer: the service re-verifies each track's file instead of
     * trusting a path that may not have been read correctly.
     */
    private fun volumePathFrom(intent: Intent): String? {
        val volume: Parcelable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_VOLUME, Parcelable::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_VOLUME)
        } ?: return null

        @Suppress("PrivateApi")
        return runCatching {
            val cls = volume.javaClass
            val legacy = runCatching { cls.getMethod("getPath") }.getOrNull()
            val modern = if (legacy == null) {
                runCatching { cls.getMethod("getDirectory") }.getOrNull()
            } else {
                null
            }
            val raw = (legacy ?: modern)?.invoke(volume)
            // `getDirectory()` returns a File; `getPath()` returns a String.
            (raw as? String) ?: (raw as? java.io.File)?.absolutePath
        }.getOrNull()
    }

    companion object {
        private const val TAG = "Ts8UsbMedia"
        private const val EXTRA_VOLUME = "android.os.storage.extra.STORAGE_VOLUME"
    }
}

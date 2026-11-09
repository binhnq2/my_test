package com.ts8.launcher.ui

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors

/**
 * Album art for list rows.
 *
 * **Why not the platform's own art loader.** The launcher must not depend on
 * Glide or Picasso: both pull a request pipeline and a thread pool the head unit
 * does not need, and the only images this app ever shows are album covers
 * addressed by album id. A single background thread with a counted LRU is the
 * whole requirement.
 *
 * **Why the decode is bounded.** The library can hold thousands of rows and the
 * driver scrolls fast. Decoding every requested cover at full resolution would
 * allocate a few megabytes per screenful on a device with 1 GB of RAM, so this
 * decodes with `inSampleSize` straight to the size the row shows and keeps only
 * the last [cacheKilobytes] of results.
 *
 * **Absence is answered, not guessed.** A track with no album id (every USB
 * file in this build) returns null, and the row draws its neutral icon. The
 * loader never invents a default cover.
 */
class AlbumArtLoader(context: Context, private val targetPx: Int) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ts8-album-art").apply { isDaemon = true }
    }

    private val cache = object : LruCache<Long, Bitmap>(cacheKilobytes) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount / 1024
    }

    /** In-flight keys, so a fast scroll does not queue the same cover twice. */
    private val pending = HashSet<Long>()

    /**
     * Returns a cached cover immediately, or null and starts a decode.
     *
     * The callback always runs on the main thread; [onLoaded] is skipped when
     * the image is already cached, so callers must set the cached one first.
     */
    fun load(albumId: Long?, onLoaded: (Long, Bitmap) -> Unit): Bitmap? {
        if (albumId == null || albumId <= 0L) return null
        cache.get(albumId)?.let { return it }
        synchronized(pending) {
            if (!pending.add(albumId)) return null
        }
        executor.execute {
            val bitmap = decode(albumId)
            main.post {
                synchronized(pending) { pending.remove(albumId) }
                if (bitmap != null) {
                    cache.put(albumId, bitmap)
                    onLoaded(albumId, bitmap)
                }
            }
        }
        return null
    }

    private fun decode(albumId: Long): Bitmap? {
        val uri: Uri = ContentUris.withAppendedId(ALBUM_ART_URI, albumId)
        return runCatching {
            // Pass one: measure without allocating pixels.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            appContext.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            var sample = 1
            var width = bounds.outWidth
            var height = bounds.outHeight
            while (width / 2 >= targetPx && height / 2 >= targetPx) {
                width /= 2
                height /= 2
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            // Pass two: decode at the row's size.
            appContext.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }.getOrNull()
    }

    fun clear() {
        cache.evictAll()
    }

    /** Called when the owning screen goes away. */
    fun shutdown() {
        cache.evictAll()
        executor.shutdownNow()
    }

    companion object {
        /**
         * Deprecated at API 29 in favour of `ContentResolver.loadThumbnail`.
         * This app runs on API 23, where this endpoint is the only route, and
         * the deprecation is a compile-time warning rather than a runtime risk.
         */
        @Suppress("DEPRECATION")
        private val ALBUM_ART_URI: Uri =
            Uri.parse("content://media/external/audio/albumart")

        /** 4 MB: roughly 25 covers at row size, well inside a 1 GB device. */
        private const val cacheKilobytes = 4 * 1024
    }
}

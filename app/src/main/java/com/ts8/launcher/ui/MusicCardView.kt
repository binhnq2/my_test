package com.ts8.launcher.ui

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.ts8.core.music.PlaybackError
import com.ts8.core.music.PlaybackState
import com.ts8.core.music.RepeatMode
import com.ts8.core.music.Track
import com.ts8.launcher.R
import java.util.concurrent.Executors

/**
 * The music card that Home and the navigation screen both show.
 *
 * **Passive by design.** The card never touches `MusicRuntime`, the player or a
 * service. A screen binds once, and pushes each `PlaybackState` in with [bind];
 * taps leave through the callbacks below. If the card subscribed to the player
 * itself, three screens showing a card would mean three subscriptions and a
 * state that can disagree between them the moment one card's listener is
 * registered a frame later.
 *
 * **Artwork is real or absent.** The design shows an album cover. The engine
 * stores a MediaStore album id, not a bitmap, so the cover is resolved through
 * the platform's album-art endpoint for MediaStore tracks. USB tracks have no
 * album id and this build extracts no embedded pictures, so they show the
 * neutral tile. Drawing a stock cover would be claiming artwork the file does
 * not have.
 *
 * **Two shapes, one view.** `musicCompact` in XML shrinks the artwork block and
 * drops the heart/shuffle/repeat/queue row, which is what the navigation screen
 * needs to give the maneuver card its space.
 */
class MusicCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** What a tap on a control should do. The screen decides, not the card. */
    var onPlayPause: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onPrevious: (() -> Unit)? = null
    var onToggleFavorite: (() -> Unit)? = null
    var onToggleShuffle: (() -> Unit)? = null
    var onCycleRepeat: (() -> Unit)? = null
    var onOpenQueue: (() -> Unit)? = null

    private val artwork: ImageView
    private val artworkPlaceholder: ImageView
    private val artworkFrame: FrameLayout
    private val title: TextView
    private val subtitle: TextView
    private val position: ProgressBar
    private val positionText: TextView
    private val durationText: TextView
    private val playPause: ImageButton
    private val nextButton: ImageButton
    private val previousButton: ImageButton
    private val favorite: ImageButton
    private val shuffle: ImageButton
    private val repeatButton: ImageButton
    private val errorText: TextView

    /** Guards against binding the same track twice while a decode is running. */
    private var artworkTrackId: Long = -1

    private val artworkLoader = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ts8-artwork").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    init {
        LayoutInflater.from(context).inflate(R.layout.view_music_card, this, true)

        artwork = findViewById(R.id.artwork)
        artworkPlaceholder = findViewById(R.id.artworkPlaceholder)
        artworkFrame = findViewById(R.id.artworkFrame)
        title = findViewById(R.id.trackTitle)
        subtitle = findViewById(R.id.trackSubtitle)
        position = findViewById(R.id.position)
        positionText = findViewById(R.id.positionText)
        durationText = findViewById(R.id.durationText)
        playPause = findViewById(R.id.btnPlayPause)
        nextButton = findViewById(R.id.btnNext)
        previousButton = findViewById(R.id.btnPrevious)
        favorite = findViewById(R.id.btnFavorite)
        shuffle = findViewById(R.id.btnShuffle)
        repeatButton = findViewById(R.id.btnRepeat)
        errorText = findViewById(R.id.errorText)

        val a = context.obtainStyledAttributes(attrs, R.styleable.MusicCardView)
        val compact = a.getBoolean(R.styleable.MusicCardView_musicCompact, false)
        a.recycle()

        if (compact) {
            // The design's compact card keeps a secondary row, but only three
            // of the four icons: heart, shuffle, queue. Repeat is the one it
            // drops — measured on the Navigation panel, where the row under the
            // transport controls holds exactly three glyphs at x 1342, 1408 and
            // 1481 in sheet coordinates, and none of them is the circular-arrow
            // repeat mark.
            //
            // This used to hide the whole row. That was wrong in both
            // directions: it removed three icons the design keeps, and it left
            // the card's bottom edge empty where the sheet is not.
            repeatButton.visibility = GONE
            artworkFrame.minimumHeight =
                resources.getDimensionPixelSize(R.dimen.music_artwork_size_compact)
            val small = resources.getDimensionPixelSize(R.dimen.music_artwork_size_compact) / 2
            val lp = artworkPlaceholder.layoutParams
            lp.width = small
            lp.height = small
            artworkPlaceholder.layoutParams = lp
        }

        playPause.setOnClickListener { onPlayPause?.invoke() }
        nextButton.setOnClickListener { onNext?.invoke() }
        previousButton.setOnClickListener { onPrevious?.invoke() }
        favorite.setOnClickListener { onToggleFavorite?.invoke() }
        shuffle.setOnClickListener { onToggleShuffle?.invoke() }
        repeatButton.setOnClickListener { onCycleRepeat?.invoke() }
        findViewById<ImageButton>(R.id.btnQueue).setOnClickListener { onOpenQueue?.invoke() }
    }

    /**
     * Renders a state snapshot.
     *
     * Called on the main thread with every `PlaybackState` the screen receives,
     * up to once a second during playback. Everything here is a property write,
     * so that is cheap; the only real work is the artwork decode, which is
     * memoised by track id.
     */
    fun bind(state: PlaybackState) {
        val track = state.track
        if (track == null) {
            title.setText(R.string.home_music_empty)
            subtitle.setText(R.string.home_music_hint)
            position.progress = 0
            positionText.text = ClockFormat.trackPosition(0)
            durationText.text = ClockFormat.trackPosition(0)
            setArtworkPlaceholder()
            artworkTrackId = -1
        } else {
            title.text = track.title.ifBlank { context.getString(R.string.music_unknown_track) }
            subtitle.text = track.subtitle.ifBlank {
                context.getString(R.string.music_unknown_artist)
            }
            position.progress = (state.progressFraction * PROGRESS_MAX).toInt()
            positionText.text = ClockFormat.trackPosition(state.positionMs)
            durationText.text = ClockFormat.trackPosition(state.durationMs)
            if (artworkTrackId != track.id) {
                artworkTrackId = track.id
                loadArtwork(track)
            }
        }

        playPause.setImageResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        playPause.contentDescription = context.getString(
            if (state.isPlaying) R.string.music_pause else R.string.music_play
        )

        // A transport with nothing in the queue is a dead tap, so it is
        // disabled rather than silently doing nothing.
        val enabled = track != null
        playPause.isEnabled = enabled
        playPause.alpha = if (enabled) 1f else 0.4f
        nextButton.isEnabled = enabled
        previousButton.isEnabled = enabled

        applyFavorite()
        shuffle.setColorFilter(
            context.getColor(if (state.shuffle) R.color.accent else R.color.text_secondary)
        )
        repeatButton.setColorFilter(
            context.getColor(
                if (state.repeatMode == RepeatMode.OFF) R.color.text_secondary else R.color.accent
            )
        )
        repeatButton.contentDescription = context.getString(
            if (state.repeatMode == RepeatMode.ONE) {
                R.string.music_repeat_one
            } else {
                R.string.music_repeat
            }
        )

        val error = state.error
        if (error == null) {
            errorText.visibility = GONE
        } else {
            errorText.visibility = VISIBLE
            errorText.text = describeError(error)
        }
    }

    /** Mirrors the library's favourite flag; the screen sets it. */
    var isFavorite: Boolean = false
        set(value) {
            field = value
            applyFavorite()
        }

    private fun applyFavorite() {
        favorite.setImageResource(
            if (isFavorite) R.drawable.ic_heart_filled else R.drawable.ic_heart
        )
        favorite.setColorFilter(
            context.getColor(if (isFavorite) R.color.accent else R.color.text_secondary)
        )
    }

    private fun describeError(error: PlaybackError): String = context.getString(
        when (error) {
            PlaybackError.SOURCE_UNAVAILABLE -> R.string.music_source_unavailable
            PlaybackError.UNSUPPORTED_FORMAT -> R.string.qs_unavailable
            PlaybackError.DECODE_FAILED -> R.string.generic_error
            // Focus loss is not an error to report: the player resumes by
            // itself when the call or the other app is done.
            PlaybackError.AUDIO_FOCUS_LOST -> R.string.music_pause
            // A stream failure is not a USB failure, and the card has room for
            // one line: the sentence names the network so the driver does not
            // go looking for a stick.
            PlaybackError.STREAM_UNAVAILABLE -> R.string.music_stream_unavailable
            PlaybackError.STREAM_INTERRUPTED -> R.string.music_stream_interrupted
            PlaybackError.UNKNOWN -> R.string.generic_error
        }
    )

    // -- artwork -----------------------------------------------------------

    /**
     * Resolves album art for a MediaStore track.
     *
     * The URI is the platform's own album-art endpoint, which exists on API 23
     * — deprecated at 29 but this app runs on 23, where it is the only route. A
     * USB track has no album id (the scanner assigns a synthetic one), so it
     * never reaches here and keeps the placeholder.
     */
    private fun loadArtwork(track: Track) {
        val albumId = track.albumId
        if (albumId == null || albumId <= 0L) {
            setArtworkPlaceholder()
            return
        }
        val uri = ContentUris.withAppendedId(ARTWORK_URI, albumId)
        artworkLoader.execute {
            val bitmap = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }.getOrNull()
            main.post {
                // A decode that finished after the track changed must not paint
                // the previous cover over the new title.
                if (artworkTrackId != track.id) return@post
                if (bitmap == null) setArtworkPlaceholder() else setArtwork(bitmap)
            }
        }
    }

    private fun setArtwork(bitmap: Bitmap) {
        artwork.setImageBitmap(bitmap)
        artwork.visibility = VISIBLE
        artworkPlaceholder.visibility = GONE
    }

    private fun setArtworkPlaceholder() {
        artwork.setImageDrawable(null)
        artwork.visibility = GONE
        artworkPlaceholder.visibility = VISIBLE
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // The card is recreated with its screen; keeping a decode thread alive
        // for a view that is gone would leak for the life of the process.
        artworkLoader.shutdownNow()
    }

    companion object {
        private const val PROGRESS_MAX = 1000
        private val ARTWORK_URI: Uri = Uri.parse("content://media/external/audio/albumart")
    }
}

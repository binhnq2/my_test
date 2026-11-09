package com.ts8.launcher.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.ts8.core.music.MusicCollection
import com.ts8.core.music.Playlist
import com.ts8.core.music.Track
import com.ts8.launcher.R

/**
 * One list for every music tab.
 *
 * **Why one adapter and not four.** The playlist tab lists tracks, the songs
 * tab lists tracks, the albums and artists tabs list collections. Those are two
 * shapes, not four, and a second adapter would duplicate the view holder, the
 * diffing and the artwork lookup for no gain. The row model below is the union:
 * a title, a subtitle, an optional album id for the cover, an optional chevron,
 * and the payload the row plays or opens.
 *
 * **Why the payload is kept opaque.** The adapter does not know what a track id
 * means to the player; the screen supplies a callback that receives the row it
 * rendered. That keeps the adapter free of `MusicLibrary` calls, which are
 * SQLite reads that must not happen during a scroll.
 */
class MusicRowAdapter(
    private val artLoader: AlbumArtLoader
) : RecyclerView.Adapter<MusicRowAdapter.RowHolder>() {

    /** What a row shows, independent of where it came from. */
    data class Row(
        /** Stable key for diffing; unique within one list. */
        val key: String,
        val title: String,
        val subtitle: String?,
        /** Album id for a real cover, null when the row has none. */
        val albumId: Long?,
        /** True when tapping opens a collection instead of playing. */
        val opensCollection: Boolean,
        /** Set on the row that is playing right now. */
        val playing: Boolean = false,
        /** The track this row plays; null for a collection row. */
        val track: Track? = null,
        /** The collection this row opens; null for a track row. */
        val collection: MusicCollection? = null,
        /**
         * A row that is a group heading rather than something to play.
         *
         * §13 requires the list to separate `Trên thiết bị` from `Trực tuyến`.
         * A heading row rather than two RecyclerViews: the two groups scroll as
         * one list, which is what keeps a search result readable on a 600 px
         * panel, and the adapter already knows how to draw a row.
         */
        val header: Boolean = false,
        /** Resolved artwork URL for an online row; null for a local one. */
        val artworkUrl: String? = null
    )

    private var rows: List<Row> = emptyList()

    /** Fired with the index and the row. The screen decides what to do. */
    var onRowClick: ((Row) -> Unit)? = null

    fun submit(next: List<Row>) {
        val old = rows
        rows = next
        // A full rebind would drop every decoded cover and re-request it; the
        // diff keeps untouched rows and their bitmaps in place.
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = next.size
            override fun areItemsTheSame(a: Int, b: Int): Boolean = old[a].key == next[b].key
            override fun areContentsTheSame(a: Int, b: Int): Boolean = old[a] == next[b]
        }).dispatchUpdatesTo(this)
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder =
        RowHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.view_music_row, parent, false),
            artLoader
        )

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        val row = rows[position]
        holder.bind(row)
        holder.itemView.setOnClickListener { onRowClick?.invoke(row) }
    }

    class RowHolder(
        view: View,
        private val artLoader: AlbumArtLoader
    ) : RecyclerView.ViewHolder(view) {

        private val artwork: ImageView = view.findViewById(R.id.rowArtwork)
        private val icon: ImageView = view.findViewById(R.id.rowIcon)
        private val title: TextView = view.findViewById(R.id.rowTitle)
        private val subtitle: TextView = view.findViewById(R.id.rowSubtitle)
        private val chevron: ImageView = view.findViewById(R.id.rowChevron)
        private val playingDot: View = view.findViewById(R.id.rowPlayingDot)

        /** Which row the current decode belongs to, so a recycled holder that
         *  scrolls away does not paint the previous album's cover. */
        private var boundKey: String? = null

        fun bind(row: Row) {
            boundKey = row.key
            title.text = row.title
            if (row.subtitle.isNullOrBlank()) {
                subtitle.visibility = View.GONE
            } else {
                subtitle.text = row.subtitle
                subtitle.visibility = View.VISIBLE
            }
            chevron.visibility = if (row.opensCollection) View.VISIBLE else View.GONE
            playingDot.visibility = if (row.playing) View.VISIBLE else View.GONE

            if (row.header) {
                // A heading is not a place: a cover, a dot and a chevron would
                // all be wrong on it. The row is styled by the group label
                // itself so it reads as a divider, not as a broken item.
                artwork.setImageDrawable(null)
                artwork.visibility = View.GONE
                icon.visibility = View.GONE
                itemView.isClickable = false
                title.alpha = 0.7f
                return
            }
            itemView.isClickable = true
            title.alpha = 1f

            // An online row may carry a cover URL; the album-art loader only
            // knows MediaStore ids, so an online row keeps the placeholder tile
            // rather than showing a local file's cover under a station's name.
            if (row.artworkUrl != null) {
                artwork.setImageDrawable(null)
                artwork.visibility = View.GONE
                icon.visibility = View.VISIBLE
                return
            }
            val cached = artLoader.load(row.albumId) { albumId, bitmap ->
                // Stale guard: the holder may have been reused for another row
                // by the time the decode finishes.
                if (boundKey == row.key && row.albumId == albumId) {
                    showArtwork(bitmap)
                }
            }
            if (cached != null) showArtwork(cached) else showPlaceholder()
        }

        private fun showArtwork(bitmap: android.graphics.Bitmap) {
            artwork.setImageBitmap(bitmap)
            artwork.visibility = View.VISIBLE
            icon.visibility = View.GONE
        }

        private fun showPlaceholder() {
            artwork.setImageDrawable(null)
            artwork.visibility = View.GONE
            icon.visibility = View.VISIBLE
        }
    }

    companion object {
        /** Builds the row for a track. */
        fun trackRow(track: Track, playing: Boolean): Row = Row(
            key = "t:${track.id}",
            title = track.title.ifBlank { "—" },
            subtitle = track.subtitle.ifBlank { null },
            albumId = track.albumId,
            opensCollection = false,
            playing = playing,
            track = track
        )

        /** Builds the row for an album, artist or folder. */
        fun collectionRow(collection: MusicCollection, trackCountLabel: (Int) -> String): Row = Row(
            key = "c:${collection.kind}:${collection.id}",
            title = collection.name.ifBlank { "—" },
            subtitle = trackCountLabel(collection.trackCount),
            // Only albums carry artwork; artists and folders do not.
            albumId = if (collection.kind == MusicCollection.Kind.ALBUM) collection.id else null,
            opensCollection = true,
            collection = collection
        )

        /** Builds the row for a playlist. */
        fun playlistRow(playlist: Playlist, trackCountLabel: (Int) -> String): Row = Row(
            key = "p:${playlist.id}",
            title = playlist.name.ifBlank { "—" },
            subtitle = trackCountLabel(playlist.trackCount),
            albumId = null,
            opensCollection = true
        )

        /**
         * A group heading.
         *
         * The key is prefixed `h:` so it can never collide with a track (`t:`),
         * a collection (`c:`) or a playlist (`p:`) key in the same list — the
         * diff in [submit] compares keys, and two rows sharing one would make
         * `DiffUtil` move the wrong row.
         */
        fun headerRow(label: String): Row = Row(
            key = "h:$label",
            title = label,
            subtitle = null,
            albumId = null,
            opensCollection = false,
            header = true
        )

        /**
         * The row for a search result from an online provider.
         *
         * The provider's display name is carried in the subtitle so a driver
         * sees which catalogue a station came from without a second chip
         * competing with the row's own text — and so a podcast episode and a
         * radio station with the same name are distinguishable.
         *
         * No [Track] is attached yet: turning a result into a playable track
         * costs a network round trip, which must not happen during a bind. The
         * screen resolves it on tap.
         */
        fun onlineRow(online: com.ts8.core.music.online.OnlineTrack, providerLabel: String): Row = Row(
            key = "o:${online.providerId}:${online.id}",
            title = online.title.ifBlank { "—" },
            subtitle = listOfNotNull(
                online.subtitle.takeIf { it.isNotBlank() },
                providerLabel
            ).joinToString(" · ").ifBlank { null },
            albumId = null,
            opensCollection = false,
            artworkUrl = online.artworkUrl
        )
    }
}

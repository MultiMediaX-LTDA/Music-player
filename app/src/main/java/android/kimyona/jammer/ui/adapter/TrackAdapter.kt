package android.kimyona.jammer.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.kimyona.jammer.R
import android.kimyona.jammer.core.media.AlbumArtLoader
import android.kimyona.jammer.data.entity.Track

/**
 * Adapter de tracks com suporte a:
 * - Carregamento real de capas de álbum via AlbumArtLoader
 * - Multi-artist display (parse de artistsJoined)
 * - Click e long-click para context menu
 */
class TrackAdapter(
    private val onClick: (Track) -> Unit,
    private val onLongClick: ((Track, View) -> Unit)? = null
) : ListAdapter<Track, TrackAdapter.TrackViewHolder>(TrackDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_track, parent, false)
        return TrackViewHolder(view, onClick, onLongClick)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: TrackViewHolder) {
        super.onViewRecycled(holder)
        AlbumArtLoader.clear(holder.coverView)
    }

    class TrackViewHolder(
        itemView: View,
        private val onClick: (Track) -> Unit,
        private val onLongClick: ((Track, View) -> Unit)?
    ) : RecyclerView.ViewHolder(itemView) {

        val coverView: ImageView = itemView.findViewById(R.id.ivItemCover)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvItemTitle)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvItemArtist)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvItemDuration)

        fun bind(track: Track) {
            tvTitle.text = track.title
            tvArtist.text = formatArtists(track)
            tvDuration.text = formatDuration(track.durationMs)

            // Carrega capa real do arquivo de música
            AlbumArtLoader.loadThumbnail(
                itemView.context,
                track.path,
                coverView,
                R.drawable.album_placeholder
            )

            itemView.setOnClickListener { onClick(track) }
            itemView.setOnLongClickListener { view ->
                onLongClick?.invoke(track, view)
                true
            }
        }

        private fun formatArtists(track: Track): String {
            // Prioriza artistsJoined (multi-artist) sobre artist simples
            return track.artistsJoined?.replace(";", ", ") 
                ?: track.artist 
                ?: "Unknown Artist"
        }

        private fun formatDuration(ms: Long): String {
            if (ms <= 0) return "--:--"
            val seconds = ms / 1000
            val minutes = seconds / 60
            val secs = seconds % 60
            return "%d:%02d".format(minutes, secs)
        }
    }

    class TrackDiffCallback : DiffUtil.ItemCallback<Track>() {
        override fun areItemsTheSame(oldItem: Track, newItem: Track) =
            oldItem.path == newItem.path

        override fun areContentsTheSame(oldItem: Track, newItem: Track) =
            oldItem == newItem
    }
}

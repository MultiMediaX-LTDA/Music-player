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
import android.kimyona.jammer.data.entity.Track

/**
 * Adapter de lista de tracks.
 * Mostra capa (placeholder por enquanto), título e artista.
 */
class TrackAdapter(
    private val onClick: (Track) -> Unit
) : ListAdapter<Track, TrackAdapter.TrackViewHolder>(TrackDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_track, parent, false)
        return TrackViewHolder(view, onClick)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class TrackViewHolder(
        itemView: View,
        private val onClick: (Track) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val ivCover: ImageView = itemView.findViewById(R.id.ivItemCover)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvItemTitle)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvItemArtist)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvItemDuration)

        fun bind(track: Track) {
            tvTitle.text = track.title
            tvArtist.text = track.artist
            tvDuration.text = formatDuration(track.durationMs)
            ivCover.setImageResource(R.drawable.album_placeholder_vinyl)

            itemView.setOnClickListener { onClick(track) }
        }

        private fun formatDuration(ms: Long): String {
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

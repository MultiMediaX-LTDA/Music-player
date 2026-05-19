package android.kimyona.jammer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import android.kimyona.jammer.R
import android.kimyona.jammer.core.media.MediaScanner

class TrackAdapter(
    private var tracks: List<MediaScanner.Track> = emptyList()
) : RecyclerView.Adapter<TrackAdapter.TrackViewHolder>() {

    var onTrackClick: ((MediaScanner.Track) -> Unit)? = null

    fun updateList(newList: List<MediaScanner.Track>) {
        tracks = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_track, parent, false)
        return TrackViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        holder.bind(tracks[position])
    }

    override fun getItemCount(): Int = tracks.size

    inner class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleText: TextView = itemView.findViewById(R.id.trackTitle)
        private val artistText: TextView = itemView.findViewById(R.id.trackArtist)

        fun bind(track: MediaScanner.Track) {
            titleText.text = track.title
            artistText.text = "${track.artist} — ${track.album}"
            
            itemView.setOnClickListener {
                onTrackClick?.invoke(track)
            }
        }
    }
}

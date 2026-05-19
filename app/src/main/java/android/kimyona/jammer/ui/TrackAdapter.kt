package android.kimyona.jammer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import android.kimyona.jammer.R
import android.kimyona.jammer.core.media.MediaScanner

/**
 * Adapter para mostrar a lista de músicas/vídeos na tela.
 */
class TrackAdapter(
    private var tracks: List<MediaScanner.Track> = emptyList()
) : RecyclerView.Adapter<TrackAdapter.TrackViewHolder>() {

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

    class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleText: TextView = itemView.findViewById(R.id.trackTitle)
        private val artistText: TextView = itemView.findViewById(R.id.trackArtist)
        private val formatText: TextView = itemView.findViewById(R.id.trackFormat)

        fun bind(track: MediaScanner.Track) {
// Dentro do bind() do TrackViewHolder, adicione:

val badge = when {
    track.isFromHiddenFolder -> "👻 ${track.extension.uppercase()} (Oculto)"
    track.isNative -> "✅ ${track.extension.uppercase()} (Nativo)"
    track.needsFFmpeg -> "⚠️ ${track.extension.uppercase()} (FFmpeg)"
    else -> "❓ ${track.extension.uppercase()} (Desconhecido)"
}
formatText.text = badge
            }
            formatText.text = badge
        }
    }
}

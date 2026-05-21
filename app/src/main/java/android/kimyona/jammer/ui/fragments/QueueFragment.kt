package android.kimyona.jammer.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.kimyona.jammer.R
import android.kimyona.jammer.ui.adapter.TrackAdapter
import android.kimyona.jammer.ui.viewmodel.PlayerViewModel

/**
 * Fragment da fila de reprodução.
 * Mostra o que vai tocar em seguida.
 */
class QueueFragment : Fragment() {

    private val viewModel: PlayerViewModel by activityViewModels()
    private lateinit var adapter: TrackAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_queue, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerQueue)

        adapter = TrackAdapter { track ->
            // Toca a partir dessa posição na fila
            viewModel.playTrack(track)
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // Observa todas as tracks como "fila" por enquanto
        // No futuro: usar QueueDao específico
        viewModel.allTracks.observe(viewLifecycleOwner) { tracks ->
            adapter.submitList(tracks)
        }
    }
}

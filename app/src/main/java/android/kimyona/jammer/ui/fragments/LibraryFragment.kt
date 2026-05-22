package android.kimyona.jammer.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.kimyona.jammer.R
import android.kimyona.jammer.data.entity.Track
import android.kimyona.jammer.ui.adapter.TrackAdapter
import android.kimyona.jammer.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch

/**
 * Fragment da biblioteca — lista todas as músicas.
 * Com busca em tempo real.
 * MVP FIX: dispara scan automaticamente se banco vazio.
 */
class LibraryFragment : Fragment() {

    private val viewModel: PlayerViewModel by activityViewModels()
    private lateinit var adapter: TrackAdapter
    private var hasTriggeredScan = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_library, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerLibrary)
        val searchView = view.findViewById<SearchView>(R.id.searchView)

        adapter = TrackAdapter { track ->
            viewModel.playTrack(track)
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // Observa tracks — dispara scan se vazio (MVP FIX)
        viewModel.allTracks.observe(viewLifecycleOwner) { tracks ->
            adapter.submitList(tracks)
            if (tracks.isNullOrEmpty() && !hasTriggeredScan) {
                hasTriggeredScan = true
                viewModel.scanLibrary()
            }
        }

        // Busca
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(query: String?): Boolean {
                query?.let {
                    viewModel.search(it).observe(viewLifecycleOwner) { results ->
                        adapter.submitList(results)
                    }
                }
                return true
            }
        })
    }
}

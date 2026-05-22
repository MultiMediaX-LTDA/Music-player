package android.kimyona.jammer.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SearchView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.kimyona.jammer.R
import android.kimyona.jammer.ui.adapter.TrackAdapter
import android.kimyona.jammer.ui.viewmodel.PlayerViewModel

class LibraryFragment : Fragment() {

    private val viewModel: PlayerViewModel by activityViewModels()
    private lateinit var adapter: TrackAdapter
    private lateinit var tvScanStatus: TextView
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
        tvScanStatus = view.findViewById(R.id.tvScanStatus)

        adapter = TrackAdapter { track ->
            viewModel.playTrack(track)
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        viewModel.allTracks.observe(viewLifecycleOwner) { tracks ->
            adapter.submitList(tracks)
            if (tracks.isNullOrEmpty()) {
                tvScanStatus.text = "No tracks found. Tap menu → Sync to rescan."
                if (!hasTriggeredScan) {
                    hasTriggeredScan = true
                    tvScanStatus.text = "Scanning library..."
                    viewModel.scanLibrary()
                }
            } else {
                tvScanStatus.text = "${tracks.size} tracks loaded"
            }
        }

        viewModel.scanProgress.observe(viewLifecycleOwner) { progress ->
            progress?.let {
                tvScanStatus.text = it
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
            }
        }

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(query: String?): Boolean {
                query?.let {
                    viewModel.search(it).observe(viewLifecycleOwner) { results ->
                        adapter.submitList(results)
                        tvScanStatus.text = "${results?.size ?: 0} results for '$it'"
                    }
                }
                return true
            }
        })
    }
}

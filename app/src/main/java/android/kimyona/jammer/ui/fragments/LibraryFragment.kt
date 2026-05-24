package android.kimyona.jammer.ui.fragments

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.PopupMenu
import android.widget.SearchView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.kimyona.jammer.R
import android.kimyona.jammer.data.JammerDatabase
import android.kimyona.jammer.data.entity.Playlist
import android.kimyona.jammer.data.entity.PlaylistTrackCrossRef
import android.kimyona.jammer.ui.adapter.TrackAdapter
import android.kimyona.jammer.ui.viewmodel.PlayerViewModel
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * LibraryFragment evoluído — com ações de contexto (queue, favoritar, playlist).
 */
class LibraryFragment : Fragment() {

    private val viewModel: PlayerViewModel by activityViewModels()
    private lateinit var adapter: TrackAdapter
    private lateinit var tvScanStatus: TextView
    private var hasTriggeredScan = false

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context?.contentResolver?.takePersistableUriPermission(it, takeFlags)

            val prefs = requireContext().getSharedPreferences(
                "jammer_prefs", android.content.Context.MODE_PRIVATE
            )
            val folders = prefs.getStringSet("saf_folders", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
            folders.add(it.toString())
            prefs.edit().putStringSet("saf_folders", folders).apply()

            tvScanStatus.text = "Scanning added folder..."
            viewModel.scanSAF(it)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            tvScanStatus.text = "Permission granted! Scanning..."
            viewModel.scanLibrary()
        } else {
            tvScanStatus.text = "Permission DENIED. Tap FAB to retry."
        }
    }

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
        val fabRescan = view.findViewById<FloatingActionButton>(R.id.fabRescan)
        val btnAddFolder = view.findViewById<Button>(R.id.btnAddFolder)

        adapter = TrackAdapter(
            onClick = { track ->
                viewModel.playTrack(track)
            },
            onLongClick = { track, anchorView ->
                showTrackContextMenu(track, anchorView)
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        viewModel.allTracks.observe(viewLifecycleOwner) { tracks ->
            adapter.submitList(tracks)
            if (tracks.isNullOrEmpty()) {
                tvScanStatus.text = "📂 No tracks found.\nTap '+' to pick a music folder."
            } else {
                tvScanStatus.text = "🎵 ${tracks.size} tracks loaded"
            }
        }

        viewModel.scanProgress.observe(viewLifecycleOwner) { progress ->
            progress?.let {
                // Only show scan progress, don't overwrite track count permanently
                tvScanStatus.text = it
            }
        }

        fabRescan.setOnClickListener {
            checkPermissionAndScan()
        }

        btnAddFolder.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        // Auto-scan logic
        val prefs = requireContext().getSharedPreferences(
            "jammer_prefs", android.content.Context.MODE_PRIVATE
        )
        val autoScan = prefs.getBoolean("auto_scan_enabled", true)

        Log.d("LibraryFragment", "autoScan=$autoScan, triggered=$hasTriggeredScan")

        if (autoScan && !hasTriggeredScan) {
            hasTriggeredScan = true
            Log.d("LibraryFragment", "Triggering auto-scan")
            checkPermissionAndScan()
        } else if (!autoScan) {
            tvScanStatus.text = "Manual mode: tap '+' or FAB"
        }

        // Re-scan saved SAF folders
        val savedFolders = prefs.getStringSet("saf_folders", emptySet()) ?: emptySet()
        for (uriString in savedFolders) {
            try {
                val uri = Uri.parse(uriString)
                val persisted = context?.contentResolver?.persistedUriPermissions?.any {
                    it.uri == uri && it.isReadPermission
                } ?: false
                if (persisted) {
                    viewModel.scanSAF(uri)
                }
            } catch (e: Exception) {
                Log.e("LibraryFragment", "Failed to scan saved folder: $uriString", e)
            }
        }

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(query: String?): Boolean {
                query?.let {
                    viewModel.search(it).observe(viewLifecycleOwner) { results ->
                        adapter.submitList(results)
                        tvScanStatus.text = "${results?.size ?: 0} results"
                    }
                }
                return true
            }
        })
    }

    /**
     * Mostra popup menu com ações para a track.
     */
    private fun showTrackContextMenu(track: android.kimyona.jammer.data.entity.Track, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.track_context_menu, popup.menu)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_play -> {
                    viewModel.playTrack(track)
                    true
                }
                R.id.action_add_queue -> {
                    viewModel.addToQueue(track)
                    Toast.makeText(requireContext(), "Added to queue", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.action_favorite -> {
                    viewModel.toggleFavorite(track)
                    Toast.makeText(requireContext(), "Toggled favorite", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.action_add_playlist -> {
                    showAddToPlaylistDialog(track)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /**
     * Dialog para selecionar playlist e adicionar a track.
     */
    private fun showAddToPlaylistDialog(track: android.kimyona.jammer.data.entity.Track) {
        CoroutineScope(Dispatchers.IO).launch {
            val playlists = JammerDatabase.getDatabase(requireContext()).playlistDao().getAll()
                .value ?: emptyList()

            withContext(Dispatchers.Main) {
                if (playlists.isEmpty()) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Sem Playlists")
                        .setMessage("Crie uma playlist primeiro.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@withContext
                }

                val playlistNames = playlists.map { it.name }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle("Adicionar a Playlist")
                    .setItems(playlistNames) { _, which ->
                        val playlist = playlists[which]
                        CoroutineScope(Dispatchers.IO).launch {
                            val crossRef = PlaylistTrackCrossRef(
                                playlistId = playlist.id,
                                trackPath = track.path,
                                position = 0
                            )
                            JammerDatabase.getDatabase(requireContext())
                                .playlistDao().addTrack(crossRef)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    requireContext(),
                                    "Added to ${playlist.name}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }
    }

    private fun checkPermissionAndScan() {
        val hasPerm: Boolean
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPerm = ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            hasPerm = ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

        if (!hasPerm) {
            tvScanStatus.text = "Requesting permission..."
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            return
        }

        tvScanStatus.text = "🔍 Scanning MediaStore..."
        viewModel.scanLibrary()
    }
}

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
import android.widget.SearchView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.kimyona.jammer.R
import android.kimyona.jammer.ui.adapter.TrackAdapter
import android.kimyona.jammer.ui.viewmodel.PlayerViewModel
import com.google.android.material.floatingactionbutton.FloatingActionButton

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

        adapter = TrackAdapter { track ->
            viewModel.playTrack(track)
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        viewModel.allTracks.observe(viewLifecycleOwner) { tracks ->
            adapter.submitList(tracks)
            if (tracks.isNullOrEmpty()) {
                tvScanStatus.text = "0 tracks. Tap '+' to add folder or FAB to scan."
            } else {
                tvScanStatus.text = tracks.size.toString() + " tracks loaded"
            }
        }

        viewModel.scanProgress.observe(viewLifecycleOwner) { progress ->
            progress?.let { tvScanStatus.text = it }
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
        val dbEmpty = viewModel.allTracks.value.isNullOrEmpty()

        Log.d("LibraryFragment", "autoScan=" + autoScan + ", triggered=" + hasTriggeredScan + ", dbEmpty=" + dbEmpty)

        if (autoScan && !hasTriggeredScan && dbEmpty) {
            hasTriggeredScan = true
            Log.d("LibraryFragment", "Triggering auto-scan")
            checkPermissionAndScan()
        } else if (!autoScan) {
            tvScanStatus.text = "Manual mode: tap '+' or FAB"
        } else if (!dbEmpty) {
            val count = viewModel.allTracks.value?.size ?: 0
            tvScanStatus.text = count.toString() + " tracks in database"
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
                Log.e("LibraryFragment", "Failed to scan saved folder: " + uriString, e)
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
                        tvScanStatus.text = (results?.size ?: 0).toString() + " results"
                    }
                }
                return true
            }
        })
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

        tvScanStatus.text = "Scanning library..."
        viewModel.scanLibrary()
    }
}

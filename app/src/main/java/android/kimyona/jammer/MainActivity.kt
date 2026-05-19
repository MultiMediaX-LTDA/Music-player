package android.kimyona.jammer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.kimyona.jammer.core.crash.CrashReporter
import android.kimyona.jammer.core.media.MediaScanner
import android.kimyona.jammer.ui.TrackAdapter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var trackAdapter: TrackAdapter
    private lateinit var scanner: MediaScanner
    private var allTracks: List<MediaScanner.Track> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReporter(this).install()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scanner = MediaScanner(this)
        trackAdapter = TrackAdapter()

        val recycler = findViewById<RecyclerView>(R.id.trackRecycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = trackAdapter

        // Click na track: abre tela de player
        trackAdapter.onTrackClick = { track ->
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra("track_path", track.path)
                putExtra("track_title", track.title)
                putExtra("track_artist", track.artist)
            }
            startActivity(intent)
        }

        // Busca simples
        val searchField = findViewById<EditText>(R.id.searchField)
        searchField.doAfterTextChanged { text ->
            val query = text?.toString()?.lowercase() ?: ""
            val filtered = if (query.isEmpty()) allTracks else {
                allTracks.filter {
                    it.title.lowercase().contains(query) ||
                    it.artist.lowercase().contains(query) ||
                    it.album.lowercase().contains(query)
                }
            }
            trackAdapter.updateList(filtered)
        }

        findViewById<Button>(R.id.scanButton).setOnClickListener {
            requestPermissionAndScan()
        }
    }

    private fun requestPermissionAndScan() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            performScan()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(permission), 100)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            performScan()
        }
    }

    private fun performScan() {
        lifecycleScope.launch {
            allTracks = scanner.scanAll()
            trackAdapter.updateList(allTracks)
        }
    }
}

package android.kimyona.jammer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
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
import android.view.View

class MainActivity : AppCompatActivity() {

    private lateinit var trackAdapter: TrackAdapter
    private lateinit var scanner: MediaScanner
    private lateinit var scanProgress: ProgressBar
    private var allTracks: List<MediaScanner.Track> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReporter(this).install()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        scanProgress = findViewById(R.id.scanProgress)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ (API 30+): precisa de MANAGE_EXTERNAL_STORAGE
            if (Environment.isExternalStorageManager()) {
                performScan()
            } else {
                Toast.makeText(this, "Ative 'Acesso a todos os arquivos' para o Jammer", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivityForResult(intent, 200)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ (API 33+): READ_MEDIA_AUDIO
            val permission = Manifest.permission.READ_MEDIA_AUDIO
            if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
                performScan()
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(permission), 100)
            }
        } else {
            // Android 10 e abaixo: READ_EXTERNAL_STORAGE
            val permission = Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
                performScan()
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(permission), 100)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            performScan()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 200) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                performScan()
            } else {
                Toast.makeText(this, "Permissão necessária para escanear músicas", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun performScan() {
        scanProgress.visibility = View.VISIBLE
        scanProgress.isIndeterminate = false
        scanProgress.progress = 0

        lifecycleScope.launch {
            allTracks = scanner.scanAll { current, total ->
                runOnUiThread {
                    val percentage = (current * 100 / total)
                    scanProgress.progress = percentage
                }
            }

            trackAdapter.updateList(allTracks)
            scanProgress.visibility = View.GONE
            Toast.makeText(this@MainActivity, "Encontradas ${allTracks.size} músicas", Toast.LENGTH_SHORT).show()
        }
    }
}

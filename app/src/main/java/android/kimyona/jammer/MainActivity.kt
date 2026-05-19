package android.kimyona.jammer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.kimyona.jammer.core.crash.CrashReporter
import android.kimyona.jammer.core.ffmpeg.FFmpegWrapper
import android.kimyona.jammer.core.media.MediaScanner
import android.kimyona.jammer.ui.TrackAdapter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var trackAdapter: TrackAdapter
    private lateinit var scanner: MediaScanner
    private lateinit var ffmpeg: FFmpegWrapper

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReporter(this).install()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scanner = MediaScanner(this)
        ffmpeg = FFmpegWrapper()
        trackAdapter = TrackAdapter()

        val recycler = findViewById<RecyclerView>(R.id.trackRecycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = trackAdapter

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
            val tracks = scanner.scanAll()
            trackAdapter.updateList(tracks)
        }
    }
}

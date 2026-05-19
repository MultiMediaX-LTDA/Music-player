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
import androidx.appcompat.app.AlertDialog
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
            requestAllPermissionsAndScan()
        }
    }

    /**
     * Verifica e solicita todas as permissões necessárias:
     * 1. MANAGE_EXTERNAL_STORAGE (acesso total, especial)
     * 2. READ_MEDIA_AUDIO (Android 13+) ou READ_EXTERNAL_STORAGE (legacy)
     */
    private fun requestAllPermissionsAndScan() {
        // Android 11+ precisa de MANAGE_EXTERNAL_STORAGE pra escrever na /sdcard/
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle("Permissão de armazenamento")
                    .setMessage(
                        "O Jammer precisa de acesso total ao armazenamento para:

" +
                        "• Salvar crash reports em /sdcard/Jammer/
" +
                        "• Ler suas músicas e vídeos de qualquer pasta
" +
                        "• Permitir que você edite arquivos do app diretamente

" +
                        "Nenhum dado é enviado sem sua permissão."
                    )
                    .setPositiveButton("Conceder") { _, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
                return
            }
        }

        // Permissão de leitura de mídia
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

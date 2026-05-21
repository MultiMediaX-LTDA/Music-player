package android.kimyona.jammer.ui.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.kimyona.jammer.R
import android.kimyona.jammer.ui.MainActivity

/**
 * Tela de boas-vindas / onboarding.
 * - Pede permissões (READ_MEDIA_AUDIO, MANAGE_EXTERNAL_STORAGE)
 * - Escolhe modo de scan: Automático ou Manual
 * - Se manual, abre SAF pra escolher pastas
 * - Se automático + API 30+, pede MANAGE_EXTERNAL_STORAGE
 * - Salva preferências em SharedPreferences
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var rgScanMode: RadioGroup
    private lateinit var rbAuto: RadioButton
    private lateinit var rbManual: RadioButton
    private lateinit var btnGrantPermissions: Button
    private lateinit var btnPickFolders: Button
    private lateinit var btnStart: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvPermissionInfo: TextView

    private var hasAudioPermission = false
    private var hasManageStorage = false
    private var selectedScanMode = "auto" // "auto" ou "manual"
    private val selectedFolders = mutableListOf<String>()

    // Launcher pra permissão de áudio (API 33+)
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasAudioPermission = isGranted
        updateUI()
    }

    // Launcher pra múltiplas permissões (API < 33)
    private val multiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasAudioPermission = permissions.entries.any { it.value }
        updateUI()
    }

    // Launcher pra SAF - escolher pasta (Tree)
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            selectedFolders.add(it.toString())
            tvStatus.text = "Pastas selecionadas: ${selectedFolders.size}"
            btnStart.isEnabled = true
        }
    }

    // Launcher pra pedir MANAGE_EXTERNAL_STORAGE (Settings)
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        hasManageStorage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
        updateUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Se já completou onboarding, pula direto pro MainActivity
        val prefs = getSharedPreferences("jammer_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("onboarding_complete", false)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_onboarding)

        rgScanMode = findViewById(R.id.rgScanMode)
        rbAuto = findViewById(R.id.rbAuto)
        rbManual = findViewById(R.id.rbManual)
        btnGrantPermissions = findViewById(R.id.btnGrantPermissions)
        btnPickFolders = findViewById(R.id.btnPickFolders)
        btnStart = findViewById(R.id.btnStart)
        tvStatus = findViewById(R.id.tvStatus)
        tvPermissionInfo = findViewById(R.id.tvPermissionInfo)

        rgScanMode.setOnCheckedChangeListener { _, checkedId ->
            selectedScanMode = when (checkedId) {
                R.id.rbAuto -> "auto"
                R.id.rbManual -> "manual"
                else -> "auto"
            }
            updateUI()
        }

        btnGrantPermissions.setOnClickListener {
            requestPermissions()
        }

        btnPickFolders.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        btnStart.setOnClickListener {
            savePreferencesAndStart()
        }

        updateUI()
    }

    private fun requestPermissions() {
        when {
            // API 33+ (Android 13+)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    audioPermissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
                } else {
                    hasAudioPermission = true
                }

                // Se modo auto, pede MANAGE_EXTERNAL_STORAGE também
                if (selectedScanMode == "auto" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (!Environment.isExternalStorageManager()) {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        manageStorageLauncher.launch(intent)
                    } else {
                        hasManageStorage = true
                    }
                }
            }
            // API 30-32 (Android 11-12)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                multiPermissionLauncher.launch(arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ))
                if (selectedScanMode == "auto" && !Environment.isExternalStorageManager()) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    manageStorageLauncher.launch(intent)
                }
            }
            // API < 30
            else -> {
                multiPermissionLauncher.launch(arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ))
                hasManageStorage = true // Não precisa nesse caso
            }
        }
        updateUI()
    }

    private fun updateUI() {
        val audioPermText = if (hasAudioPermission) "✅ Áudio" else "❌ Áudio"
        val storagePermText = if (hasManageStorage) "✅ Storage" else "❌ Storage"
        tvPermissionInfo.text = "Permissões: $audioPermText | $storagePermText"

        when (selectedScanMode) {
            "auto" -> {
                btnPickFolders.visibility = View.GONE
                tvStatus.text = "Modo Automático: escaneia todo o celular"
                btnStart.isEnabled = hasAudioPermission && hasManageStorage
            }
            "manual" -> {
                btnPickFolders.visibility = View.VISIBLE
                tvStatus.text = "Modo Manual: escolha as pastas (${selectedFolders.size} selecionadas)"
                btnStart.isEnabled = hasAudioPermission && selectedFolders.isNotEmpty()
            }
        }

        btnGrantPermissions.isEnabled = !hasAudioPermission || 
            (selectedScanMode == "auto" && !hasManageStorage)
    }

    private fun savePreferencesAndStart() {
        val prefs = getSharedPreferences("jammer_prefs", MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("onboarding_complete", true)
            putString("scan_mode", selectedScanMode)
            putStringSet("selected_folders", selectedFolders.toSet())
            putBoolean("auto_scan_enabled", selectedScanMode == "auto")
            apply()
        }

        Toast.makeText(this, "Configurações salvas! Bem-vindo ao Jammer.", Toast.LENGTH_SHORT).show()

        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }
}

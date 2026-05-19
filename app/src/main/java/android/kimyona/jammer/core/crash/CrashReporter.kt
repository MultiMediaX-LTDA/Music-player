package android.kimyona.jammer.core.crash

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CrashReporter privacy-respecting do Jammer.
 * Salva em /sdcard/Jammer/crash-reports/ (visível, editável, deletável).
 * Pergunta antes de enviar qualquer coisa.
 */
class CrashReporter(private val context: Context) {

    companion object {
        private const val TAG = "JammerCrash"
        private const val REPORT_DIR = "Jammer/crash-reports"
        private const val DEV_EMAIL = "androidkimona.devi@keemail.me"
    }

    /**
     * Pasta pública: /sdcard/Jammer/crash-reports/
     * Acessível por qualquer gerenciador de arquivos.
     */
    private val crashDir: File by lazy {
        val sdcard = Environment.getExternalStorageDirectory() ?: File("/sdcard")
        File(sdcard, REPORT_DIR).apply { mkdirs() }
    }

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            val reportFile = saveCrash(throwable)
            Log.i(TAG, "Crash salvo em: ${reportFile.absolutePath}")
        }
    }

    /**
     * Salva o crash em JSON na pasta pública.
     */
    private fun saveCrash(throwable: Throwable): File {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val file = File(crashDir, "crash_$timestamp.json")
        val json = JSONObject().apply {
            put("device", Build.MODEL)
            put("model", Build.DEVICE)
            put("codename", Build.BOARD)
            put("api", Build.VERSION.SDK_INT)
            put("build", Build.DISPLAY)
            put("timestamp", timestamp)
            put("app_version", "1.0.0-Tidal")
            put("stacktrace", throwable.stackTraceToString())
        }
        file.writeText(json.toString(2))
        return file
    }

    /**
     * Mostra dialog perguntando se o usuário quer enviar o crash por e-mail.
     * Chamado manualmente (ex: botão "Enviar relatório" nas configurações).
     */
    fun showSendDialog() {
        val reports = crashDir.listFiles()?.filter { it.name.endsWith(".json") } ?: emptyList()
        if (reports.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle("Sem relatórios")
                .setMessage("Nenhum crash report encontrado.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        // Pega o mais recente
        val latest = reports.maxByOrNull { it.lastModified() } ?: return

        AlertDialog.Builder(context)
            .setTitle("Enviar relatório de crash?")
            .setMessage(
                "Arquivo: ${latest.name}

" +
                "O relatório contém apenas:
" +
                "• Modelo do celular
" +
                "• Versão do Android
" +
                "• Stacktrace do erro

" +
                "NENHUM dado pessoal é coletado."
            )
            .setPositiveButton("Enviar por e-mail") { _, _ ->
                sendEmail(latest)
            }
            .setNegativeButton("Apenas salvar", null)
            .setNeutralButton("Abrir pasta") { _, _ ->
                openCrashFolder()
            }
            .show()
    }

    /**
     * Abre Intent de e-mail com o JSON anexado.
     */
    private fun sendEmail(file: File) {
        val uri = Uri.fromFile(file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(DEV_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "[Jammer Crash Report] ${file.name}")
            putExtra(Intent.EXTRA_TEXT,
                "Crash report gerado automaticamente pelo Jammer.

" +
                "Anexo: ${file.name}
" +
                "Local: ${file.parent}

" +
                "Nenhum dado pessoal foi coletado."
            )
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Enviar crash report via...")
        context.startActivity(chooser)
    }

    /**
     * Abre a pasta dos crash reports no gerenciador de arquivos.
     */
    private fun openCrashFolder() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(crashDir.absolutePath), "resource/folder")
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Nenhum gerenciador de arquivos encontrado para abrir pasta")
        }
    }
}

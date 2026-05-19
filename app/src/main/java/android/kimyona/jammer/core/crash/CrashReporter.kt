package android.kimyona.jammer.core.crash

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashReporter(private val context: Context) {

    companion object {
        private const val TAG = "JammerCrash"
    }

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            try {
                val reportFile = saveCrash(throwable)
                Log.i(TAG, "Crash salvo em: ${reportFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao salvar crash report: ${e.message}")
            }
            // Mata o processo pra não congelar o app
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun saveCrash(throwable: Throwable): File {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        
        // USA A PASTA PRIVADA DO APP — não precisa de permissão de storage
        val crashDir = File(context.getExternalFilesDir(null), "Jammer/crash-reports").apply { mkdirs() }
        
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
}

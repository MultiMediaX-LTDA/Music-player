package android.kimyona.jammer

import android.app.Application
import android.kimyona.jammer.core.crash.CrashReporter
import android.kimyona.jammer.data.JammerDatabase
import android.util.Log
import rikka.shizuku.ShizukuProvider

/**
 * Application class — inicializa banco, crash reporter e Shizuku provider.
 * Shizuku é opcional: se o usuário não tiver o app Shizuku instalado,
 * o provider simplesmente não faz nada e o Jammer continua funcionando normalmente.
 */
class JammerApp : Application() {

    val database: JammerDatabase by lazy {
        JammerDatabase.getDatabase(this)
    }

    override fun onCreate() {
        super.onCreate()

        // Crash reporter (privacy-respecting, local only)
        CrashReporter(this).install()

        // Inicializa Shizuku provider — necessário para que o sistema reconheça
        // que este app pode usar a API do Shizuku. Não requer permissões aqui.
        try {
            ShizukuProvider.enableMultiProcessSupport(false)
            ShizukuProvider.requestBinderForNonProviderProcess(this)
            Log.i("JammerApp", "Shizuku provider initialized")
        } catch (e: Exception) {
            Log.w("JammerApp", "Shizuku not available: ${e.message}")
        }
    }
}

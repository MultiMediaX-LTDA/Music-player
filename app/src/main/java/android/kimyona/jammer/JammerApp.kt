package android.kimyona.jammer

import android.app.Application
import android.kimyona.jammer.core.crash.CrashReporter
import android.kimyona.jammer.data.JammerDatabase

/**
 * Application class — inicializa banco, crash reporter e outras dependências globais.
 */
class JammerApp : Application() {

    val database: JammerDatabase by lazy {
        JammerDatabase.getDatabase(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Inicializa crash reporter (privacy-respecting)
        CrashReporter(this).install()
    }
}

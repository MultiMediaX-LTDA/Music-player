package android.kimyona.jammer

import android.app.Application
import android.kimyona.jammer.data.JammerDatabase

/**
 * Application class — inicializa banco e outras dependências globais.
 */
class JammerApp : Application() {

    val database: JammerDatabase by lazy {
        JammerDatabase.getDatabase(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Inicializações futuras: crash reporter, tema, etc.
    }
}

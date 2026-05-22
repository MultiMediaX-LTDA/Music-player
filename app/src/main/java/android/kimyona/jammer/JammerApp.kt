package android.kimyona.jammer

import android.app.Application
import android.kimyona.jammer.core.crash.CrashReporter
import android.kimyona.jammer.data.JammerDatabase

/**
 * Application class — inicializa banco e crash reporter.
 */
class JammerApp : Application() {

    val database: JammerDatabase by lazy {
        JammerDatabase.getDatabase(this)
    }

    override fun onCreate() {
        super.onCreate()
        CrashReporter(this).install()
    }
}

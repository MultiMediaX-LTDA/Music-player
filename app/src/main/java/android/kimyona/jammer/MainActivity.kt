package android.kimyona.jammer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.kimyona.jammer.core.crash.CrashReporter  // ← Adiciona essa linha

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReporter(this).install()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
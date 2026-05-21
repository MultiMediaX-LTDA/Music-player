package android.kimyona.jammer

object RustBridge {
    init {
        System.loadLibrary("jammer_scanner")
    }
    
    external fun scanDirectory(dir: String): String
}

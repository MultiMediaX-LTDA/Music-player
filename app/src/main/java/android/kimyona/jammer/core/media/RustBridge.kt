package android.kimyona.jammer.core.media

import android.kimyona.jammer.data.entity.Track
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridge JNI para o scanner Rust.
 * Escaneia pastas específicas que MediaStore não pega (Opus, OGG, etc.)
 */
class RustBridge {

    init {
        System.loadLibrary("jammer_scanner")
    }

    /**
     * Escaneia diretórios extras, ignorando paths já conhecidos.
     * @param dirs Pastas para escanear
     * @param exclude Paths já no banco (para não duplicar)
     * @return Lista de tracks encontradas pelo Rust
     */
    suspend fun scanDirectories(dirs: Array<String>, exclude: Array<String>): List<Track> {
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<Track>()

            dirs.forEach { dir ->
                Log.d("JammerScanner", "Scanning directory: $dir")
                val json = nativeScanDirectory(dir)
                Log.d("JammerScanner", "Raw JSON from Rust: $json")
                val tracks = parseJsonTracks(json, exclude.toSet())
                Log.d("JammerScanner", "Parsed ${tracks.size} tracks from $dir")
                results.addAll(tracks)
            }

            results
        }
    }

    private fun parseJsonTracks(json: String, exclude: Set<String>): List<Track> {
        val tracks = mutableListOf<Track>()

        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val path = obj.getString("path")

                // Ignora se já existe no banco
                if (path in exclude) continue

                tracks.add(Track(
                    path = path,
                    title = obj.optString("title", "Unknown Title"),
                    artist = obj.optString("artist", "Unknown Artist"),
                    album = obj.optString("album", "Unknown Album"),
                    durationMs = obj.optLong("duration_ms", 0),
                    format = obj.optString("format", "UNKNOWN"),
                    artistsJoined = obj.optString("artist", null),
                    genresJoined = null // TODO: parse quando Rust suportar
                ))
            }
        } catch (e: Exception) {
            Log.e("RustBridge", "Parse error: ${e.message}")
        }

        return tracks
    }

    // JNI native
    private external fun nativeScanDirectory(dir: String): String
}

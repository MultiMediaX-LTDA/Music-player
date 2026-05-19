package android.kimyona.jammer.core.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Escaneia o celular e encontra todos os arquivos de midia.
 * Classifica cada um como "nativo" ou "precisa de FFmpeg".
 * AGORA TAMBEM escaneia pastas ocultas (dotfiles).
 */
class MediaScanner(private val context: Context) {

    companion object {
        private const val TAG = "JammerScanner"
        
        // Extensoes de audio e video que o Jammer reconhece
        private val AUDIO_EXTS = setOf(
            "mp3", "flac", "ogg", "opus", "aac", "m4a", "wav", 
            "wma", "mid", "midi"
        )
        private val VIDEO_EXTS = setOf(
            "mp4", "mkv", "webm", "mov", "3gp", 
            "avi", "wmv", "flv", "mpeg", "mpg", "bik"
        )
        private val ALL_EXTS = AUDIO_EXTS + VIDEO_EXTS
    }

    data class Track(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        val path: String,
        val duration: Long,
        val extension: String,
        val isNative: Boolean,
        val needsFFmpeg: Boolean,
        val isFromHiddenFolder: Boolean = false  // NOVO: veio de dotfile?
    )

    /**
     * Escaneia TUDO: MediaStore + pastas ocultas.
     */
    suspend fun scanAll(): List<Track> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<Track>()

        // 1. Escaneia via MediaStore (musica normal)
        tracks.addAll(scanMediaStore())

        // 2. Escaneia pastas ocultas (dotfiles)
        tracks.addAll(scanHiddenFolders())

        // Remove duplicados (mesmo path)
        val unique = tracks.distinctBy { it.path }

        Log.i(TAG, "Total scanned: ${unique.size} files (${tracks.size - unique.size} duplicados removidos)")
        unique
    }

    /**
     * Escaneia via MediaStore (metodo original).
     */
    private fun scanMediaStore(): List<Track> {
        val result = mutableListOf<Track>()

        // Audio
        result.addAll(scanMediaType(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio"))

        // Video
        result.addAll(scanMediaType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video"))

        return result
    }

    private fun scanMediaType(uri: Uri, type: String): List<Track> {
        val result = mutableListOf<Track>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.TITLE,
            MediaStore.MediaColumns.ARTIST,
            MediaStore.MediaColumns.ALBUM,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DURATION
        )

        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.ALBUM)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)

            while (cursor.moveToNext()) {
                val path = cursor.getString(pathCol) ?: continue
                val ext = path.substringAfterLast('.', "").lowercase()
                val formatInfo = SupportedFormats.getByExtension(ext)

                result.add(
                    Track(
                        id = cursor.getLong(idCol),
                        title = cursor.getString(titleCol) ?: "Unknown",
                        artist = cursor.getString(artistCol) ?: "Unknown Artist",
                        album = cursor.getString(albumCol) ?: "Unknown Album",
                        path = path,
                        duration = cursor.getLong(durationCol),
                        extension = ext,
                        isNative = formatInfo?.isNative ?: false,
                        needsFFmpeg = formatInfo?.needsFFmpeg ?: false,
                        isFromHiddenFolder = false
                    )
                )
            }
        }

        return result
    }

    /**
     * NOVO: Escaneia pastas ocultas (dotfiles) diretamente pelo sistema de arquivos.
     * O MediaStore nao indexa arquivos em pastas que comecam com ".".
     */
    private fun scanHiddenFolders(): List<Track> {
        val result = mutableListOf<Track>()
        
        // Pasta raiz do armazenamento interno
        val root = Environment.getExternalStorageDirectory() 
            ?: File("/storage/emulated/0")
        
        Log.i(TAG, "Scanning hidden folders in: ${root.absolutePath}")

        // Encontra todas as pastas ocultas recursivamente
        val hiddenDirs = findHiddenDirectories(root)
        Log.i(TAG, "Found ${hiddenDirs.size} hidden directories")

        var nextId = -1L  // IDs negativos pra nao conflitar com MediaStore

        for (dir in hiddenDirs) {
            val files = dir.listFiles() ?: continue
            for (file in files) {
                if (!file.isFile) continue
                
                val ext = file.extension.lowercase()
                if (ext !in ALL_EXTS) continue

                val formatInfo = SupportedFormats.getByExtension(ext)
                
                // Tenta extrair metadata do nome do arquivo
                val (title, artist, album) = parseFilename(file.nameWithoutExtension)

                result.add(
                    Track(
                        id = nextId--,
                        title = title,
                        artist = artist,
                        album = album,
                        path = file.absolutePath,
                        duration = 0L,  // Nao temos duration sem MediaStore
                        extension = ext,
                        isNative = formatInfo?.isNative ?: false,
                        needsFFmpeg = formatInfo?.needsFFmpeg ?: false,
                        isFromHiddenFolder = true  // Marca como vindo de dotfile
                    )
                )
            }
        }

        Log.i(TAG, "Found ${result.size} tracks in hidden folders")
        return result
    }

    /**
     * Encontra recursivamente todas as pastas que comecam com "."
     */
    private fun findHiddenDirectories(root: File): List<File> {
        val hidden = mutableListOf<File>()
        val stack = ArrayDeque<File>()
        stack.add(root)

        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue

            for (child in children) {
                if (!child.isDirectory) continue
                
                // Ignora pastas do sistema conhecidas
                if (child.name in SYSTEM_FOLDERS) continue
                
                if (child.name.startsWith(".")) {
                    hidden.add(child)
                    // Tambem escaneia SUBPASTAS da pasta oculta
                    stack.add(child)
                } else {
                    // Continua escaneando pastas normais pra achar dotfiles mais fundo
                    stack.add(child)
                }
            }
        }

        return hidden
    }

    /**
     * Tenta extrair artista/titulo do nome do arquivo.
     * Suporta formatos como "Artista - Titulo", "Artista - Album - Titulo", etc.
     */
    private fun parseFilename(filename: String): Triple<String, String, String> {
        // Remove numeros de track no inicio (ex: "01 - ", "1. ")
        var clean = filename.replace(Regex("^\\d+[.\\s-]+"), "")
        
        val parts = clean.split(" - ", limit = 3)
        
        return when (parts.size) {
            3 -> Triple(parts[2], parts[0], parts[1])  // Artista - Album - Titulo
            2 -> Triple(parts[1], parts[0], "Unknown Album")  // Artista - Titulo
            else -> Triple(clean, "Unknown Artist", "Unknown Album")
        }
    }

    companion object {
        // Pastas do sistema pra ignorar (nao sao dotfiles de musica)
        private val SYSTEM_FOLDERS = setOf(
            "Android", "DCIM", "Documents", "Download", "Movies", 
            "Music", "Notifications", "Pictures", "Podcasts", "Ringtones",
            "alarms", "media", "MIUI", "Samsung"
        )
    }
}

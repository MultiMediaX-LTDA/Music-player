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
 * API 35 compativel - usa paths corretos para Android 15.
 */
 
class MediaScanner(private val context: Context) {

    companion object {
        private const val TAG = "JammerScanner"

        private val AUDIO_EXTS = setOf(
            "mp3", "flac", "ogg", "opus", "aac", "m4a", "wav",
            "wma", "mid", "midi"
        )
        private val VIDEO_EXTS = setOf(
            "mp4", "mkv", "webm", "mov", "3gp",
            "avi", "wmv", "flv", "mpeg", "mpg", "bik"
        )
        private val ALL_EXTS = AUDIO_EXTS + VIDEO_EXTS

        private val SYSTEM_FOLDERS = setOf(
            "Android", "DCIM", "Documents", "Download", "Movies",
            "Notifications", "Pictures", "Podcasts", "Ringtones",
            "alarms", "media", "MIUI", "Samsung", "cache", "tmp", "temp",
            "data", "obb", "tencent", "baidu", "alipay"
        )
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
        val isFromHiddenFolder: Boolean = false
    )

    suspend fun scanAll(onProgress: (Int, Int) -> Unit = { _, _ -> }): List<Track> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<Track>()
        tracks.addAll(scanMediaStore())
        tracks.addAll(scanAllFolders(onProgress))
        val unique = tracks.distinctBy { it.path }
        Log.i(TAG, "Total scanned: ${unique.size} files (${tracks.size - unique.size} duplicados removidos)")
        unique
    }

    private fun scanMediaStore(): List<Track> {
        val result = mutableListOf<Track>()
        result.addAll(scanMediaType(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio"))
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
     * Scaneia TODAS as pastas manualmente.
     * API 35: usa context.getExternalFilesDir(null)?.parentFile como fallback.
     */
    private fun scanAllFolders(): List<Track> {
        val result = mutableListOf<Track>()

        // API 35: Environment.getExternalStorageDirectory() nao funciona mais
        // Usamos o path real do storage interno
        val root = getStorageRoot()

        Log.i(TAG, "Scanning ALL folders in: ${root.absolutePath}")
        val allDirs = findAllDirectories(root)
        Log.i(TAG, "Found ${allDirs.size} directories to scan")

        var nextId = -1L
        var trackCount = 0

        for (dir in allDirs) {
            val files = dir.listFiles() ?: continue
            for (file in files) {
                if (!file.isFile) continue
                val ext = file.extension.lowercase()
                if (ext !in ALL_EXTS) continue

                val formatInfo = SupportedFormats.getByExtension(ext)
                val (title, artist, album) = parseFilename(file.nameWithoutExtension)

                result.add(
                    Track(
                        id = nextId--,
                        title = title,
                        artist = artist,
                        album = album,
                        path = file.absolutePath,
                        duration = 0L,
                        extension = ext,
                        isNative = formatInfo?.isNative ?: false,
                        needsFFmpeg = formatInfo?.needsFFmpeg ?: false,
                        isFromHiddenFolder = dir.name.startsWith(".")
                    )
                )
                trackCount++
            }
        }

        Log.i(TAG, "Found $trackCount tracks in all folders")
        return result
    }

    /**
     * API 35: retorna o path correto do armazenamento interno.
     */
    private fun getStorageRoot(): File {
        // Tenta o path padrao do Android
        val standardPath = File("/storage/emulated/0")
        if (standardPath.exists() && standardPath.isDirectory) {
            return standardPath
        }

        // Fallback: usa o diretorio de files do app pra deduzir o path
        val fallback = context.getExternalFilesDir(null)?.parentFile?.parentFile?.parentFile
        if (fallback != null && fallback.exists()) {
            return fallback
        }

        // Ultimo recurso
        return File("/sdcard")
    }

    private fun findAllDirectories(root: File): List<File> {
        val all = mutableListOf<File>()
        val stack = ArrayDeque<File>()
        stack.add(root)

        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue

            for (child in children) {
                if (!child.isDirectory) continue
                val name = child.name

                if (name in SYSTEM_FOLDERS) continue
                if (name == "cache" || name == "files" || name == "databases") continue

                all.add(child)
                stack.add(child)
            }
        }

        return all
    }

    private fun parseFilename(filename: String): Triple<String, String, String> {
        var clean = filename.replace(Regex("""^\d+[.\s-]+"""), "")
        val parts = clean.split(" - ", limit = 3)

        return when (parts.size) {
            3 -> Triple(parts[2], parts[0], parts[1])
            2 -> Triple(parts[1], parts[0], "Unknown Album")
            else -> Triple(clean, "Unknown Artist", "Unknown Album")
        }
    }
}

package android.kimyona.jammer.core.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Escaneia o celular e encontra todos os arquivos de mídia.
 * Classifica cada um como "nativo" ou "precisa de FFmpeg".
 */
class MediaScanner(private val context: Context) {

    companion object {
        private const val TAG = "JammerScanner"
    }

    data class Track(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        val path: String,
        val duration: Long,      // em milissegundos
        val extension: String,
        val isNative: Boolean,   // ExoPlayer toca direto?
        val needsFFmpeg: Boolean // Precisa converter?
    )

    /**
     * Escaneia todas as músicas e vídeos do armazenamento.
     */
    suspend fun scanAll(): List<Track> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<Track>()

        // Escaneia áudio
        tracks.addAll(scanMediaType(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio"))

        // Escaneia vídeo
        tracks.addAll(scanMediaType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video"))

        Log.i(TAG, "Total scanned: ${tracks.size} files")
        tracks
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
                        needsFFmpeg = formatInfo?.needsFFmpeg ?: false
                    )
                )
            }
        }

        return result
    }
}

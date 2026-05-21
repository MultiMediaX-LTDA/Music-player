package android.kimyona.jammer.data.repository

import android.kimyona.jammer.core.media.RustBridge

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.kimyona.jammer.data.JammerDatabase
import android.kimyona.jammer.data.entity.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class MediaRepository(
    private val context: Context,
    private val db: JammerDatabase,
    private val rustBridge: RustBridge
) {
    private val TAG = "MediaRepository"

    // LiveData observável pela UI
    val allTracks = db.trackDao().getAll()
    val favorites = db.trackDao().getFavorites()

    /**
     * SCAN HÍBRIDO — Fase 1: MediaStore (instantâneo), Fase 2: Rust (background)
     */
    fun scanLibrary(): Flow<ScanProgress> = flow {
        emit(ScanProgress.Starting)

        // FASE 1: MediaStore — pega tudo que o Android reconhece como áudio
        val mediaStoreTracks = withContext(Dispatchers.IO) {
            scanMediaStore()
        }
        emit(ScanProgress.MediaStoreDone(mediaStoreTracks.size))

        // Salva no banco
        db.trackDao().insertAll(mediaStoreTracks)

        // FASE 2: Rust — escaneia pastas específicas que MediaStore perde
        // (Opus como .ogg, pastas de apps de download, etc.)
        val seenPaths = mediaStoreTracks.map { it.path }.toSet()
        val extraPaths = getExtraScanPaths()

        if (extraPaths.isNotEmpty()) {
            emit(ScanProgress.RustScanning)
            val rustTracks = withContext(Dispatchers.IO) {
                rustBridge.scanDirectories(extraPaths.toTypedArray(), seenPaths.toTypedArray())
            }
            db.trackDao().insertAll(rustTracks)
            emit(ScanProgress.RustDone(rustTracks.size))
        }

        emit(ScanProgress.Complete(db.trackDao().count()))
    }.flowOn(Dispatchers.IO)

    private fun scanMediaStore(): List<Track> {
        val tracks = mutableListOf<Track>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.TRACK
        )

        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val pathIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val titleIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val yearIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val trackIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)

            while (cursor.moveToNext()) {
                val path = cursor.getString(pathIdx) ?: continue
                val mime = cursor.getString(mimeIdx) ?: ""

                // Só áudio de verdade
                if (!mime.startsWith("audio/")) continue

                val format = path.substringAfterLast('.', "UNKNOWN").uppercase()

                tracks.add(Track(
                    path = path,
                    title = cursor.getString(titleIdx) ?: "Unknown Title",
                    artist = cursor.getString(artistIdx) ?: "Unknown Artist",
                    album = cursor.getString(albumIdx) ?: "Unknown Album",
                    durationMs = cursor.getLong(durationIdx),
                    format = format,
                    year = cursor.getInt(yearIdx).takeIf { it > 0 },
                    trackNumber = cursor.getInt(trackIdx).takeIf { it > 0 }
                ))
            }
        }

        Log.d(TAG, "MediaStore found ${tracks.size} tracks")
        return tracks
    }

    private fun getExtraScanPaths(): List<String> {
        val paths = mutableListOf<String>()
        val extDir = android.os.Environment.getExternalStorageDirectory()

        // Pastas comuns que MediaStore ignora ou perde
        val candidates = listOf(
            "Download",
            "Telegram/Telegram Audio",
            "WhatsApp/Media/WhatsApp Audio",
            "DCIM",
            "Music",
            "Documents"
        )

        candidates.forEach { candidate ->
            val dir = java.io.File(extDir, candidate)
            if (dir.exists() && dir.isDirectory) {
                paths.add(dir.absolutePath)
            }
        }

        return paths
    }

    fun searchTracks(query: String) = db.trackDao().search("%$query%")

    suspend fun toggleFavorite(path: String) {
        val track = db.trackDao().getByPath(path) ?: return
        db.trackDao().setFavorite(path, !track.isFavorite)
    }

    suspend fun clearDatabase() {
        db.trackDao().deleteAll()
        db.queueDao().clear()
    }

    // Progresso do scan
    sealed class ScanProgress {
        object Starting : ScanProgress()
        data class MediaStoreDone(val count: Int) : ScanProgress()
        object RustScanning : ScanProgress()
        data class RustDone(val count: Int) : ScanProgress()
        data class Complete(val total: Int) : ScanProgress()
    }
}

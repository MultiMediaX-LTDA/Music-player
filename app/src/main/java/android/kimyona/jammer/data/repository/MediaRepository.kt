package android.kimyona.jammer.data.repository

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
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
    private val rustBridge: android.kimyona.jammer.core.media.RustBridge
) {
    private val TAG = "JammerScan"

    val allTracks = db.trackDao().getAll()
    val favorites = db.trackDao().getFavorites()

    fun scanLibrary(): Flow<ScanProgress> = flow {
        emit(ScanProgress.Starting)

        val tracks = withContext(Dispatchers.IO) {
            val mediaStoreTracks = scanMediaStore()
            Log.d(TAG, "MediaStore: ${mediaStoreTracks.size} tracks")

            if (mediaStoreTracks.isNotEmpty()) {
                db.trackDao().insertAll(mediaStoreTracks)
            }

            mediaStoreTracks
        }

        emit(ScanProgress.Complete(tracks.size))
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

        try {
            val cursor = context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                MediaStore.Audio.Media.DATE_ADDED + " DESC"
            )

            if (cursor == null) {
                Log.e(TAG, "MediaStore cursor is NULL")
                return tracks
            }

            Log.d(TAG, "MediaStore cursor count: ${cursor.count}")

            if (cursor.count == 0) {
                cursor.close()
                return tracks
            }

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
                val mime = cursor.getString(mimeIdx) ?: "unknown"
                Log.d(TAG, "MediaStore: $path | mime=$mime")

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
            cursor.close()
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore query failed: ${e.message}", e)
        }

        Log.d(TAG, "MediaStore total: ${tracks.size}")
        return tracks
    }

    /**
     * Scan via SAF (Storage Access Framework) - para pastas que MediaStore ignora
     */
    fun scanWithSAF(treeUri: Uri): List<Track> {
        val tracks = mutableListOf<Track>()
        val audioExts = setOf("opus", "ogg", "mp3", "flac", "m4a", "aac", "wav", "wma", "mid", "midi")

        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return tracks

        fun scanDocument(doc: DocumentFile) {
            if (doc.isDirectory) {
                doc.listFiles()?.forEach { scanDocument(it) }
            } else {
                val ext = doc.name?.substringAfterLast('.', "")?.lowercase() ?: ""
                if (ext in audioExts) {
                    tracks.add(Track(
                        path = doc.uri.toString(),
                        title = doc.name ?: "Unknown",
                        artist = "Unknown Artist",
                        album = "Unknown Album",
                        durationMs = 0L,
                        format = ext.uppercase()
                    ))
                    Log.d(TAG, "SAF found: ${doc.name}")
                }
            }
        }

        scanDocument(tree)
        Log.d(TAG, "SAF total: ${tracks.size}")
        return tracks
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

    sealed class ScanProgress {
        object Starting : ScanProgress()
        data class MediaStoreDone(val count: Int) : ScanProgress()
        object RustScanning : ScanProgress()
        data class RustDone(val count: Int) : ScanProgress()
        data class Complete(val total: Int) : ScanProgress()
    }
}

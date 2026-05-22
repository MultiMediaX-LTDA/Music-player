package android.kimyona.jammer.data.repository

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import android.kimyona.jammer.core.ffmpeg.FFmpegWrapper
import android.kimyona.jammer.core.media.MediaScanner
import android.kimyona.jammer.core.media.RustBridge
import android.kimyona.jammer.data.JammerDatabase
import android.kimyona.jammer.data.entity.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

class MediaRepository(
    private val context: Context,
    private val db: JammerDatabase,
    private val rustBridge: RustBridge
) {
    private val TAG = "JammerScan"
    private val ffmpeg = FFmpegWrapper()

    val allTracks = db.trackDao().getAll()
    val favorites = db.trackDao().getFavorites()

    /**
     * Scan principal: tenta MediaStore, depois fallback manual, depois Rust.
     * Nunca retorna vazio se houver música no celular.
     */
    fun scanLibrary(): Flow<ScanProgress> = flow {
        emit(ScanProgress.Starting)

        val tracks = withContext(Dispatchers.IO) {
            // 1. MediaStore (rápido, mas pode estar vazio)
            val mediaStoreTracks = scanMediaStore()
            Log.d(TAG, "MediaStore: ${mediaStoreTracks.size} tracks")
            emit(ScanProgress.MediaStoreDone(mediaStoreTracks.size))

            // 2. Se MediaStore vazio ou incompleto, escaneia pastas conhecidas manualmente (Kotlin)
            val manualTracks = if (mediaStoreTracks.isEmpty()) {
                Log.d(TAG, "MediaStore empty — falling back to manual scanner")
                scanManualFolders()
            } else emptyList()
            Log.d(TAG, "Manual scan: ${manualTracks.size} tracks")

            // 3. Rust scanner para pastas que MediaStore não pega (pastas ocultas, formatos exóticos)
            val rustTracks = scanWithRust(manualTracks.map { it.path }.toTypedArray())
            Log.d(TAG, "Rust scan: ${rustTracks.size} tracks")

            // 4. Merge e deduplica
            val merged = (mediaStoreTracks + manualTracks + rustTracks)
                .distinctBy { it.path }
                .sortedBy { it.title.lowercase() }

            Log.d(TAG, "Total unique tracks: ${merged.size}")

            if (merged.isNotEmpty()) {
                db.trackDao().insertAll(merged)
            }

            merged
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
            context.contentResolver.query(
                uri, projection, null, null,
                MediaStore.Audio.Media.DATE_ADDED + " DESC"
            )?.use { cursor ->
                if (cursor.count == 0) return@use

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
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore query failed: ${e.message}", e)
        }

        return tracks
    }

    /**
     * Fallback manual: escaneia pastas comuns de música no filesystem.
     * Não precisa de permissão MANAGE_EXTERNAL_STORAGE — usa paths públicos.
     */
    private fun scanManualFolders(): List<Track> {
        val commonPaths = listOf(
            "/storage/emulated/0/Music",
            "/storage/emulated/0/.Music",
            "/storage/emulated/0/Download",
            "/storage/emulated/0/Audio",
            "/sdcard/Music",
            "/sdcard/.Music",
            "/sdcard/Download"
        )

        val scanner = MediaScanner(context)
        val allTracks = mutableListOf<MediaScanner.Track>()

        for (path in commonPaths) {
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) {
                Log.d(TAG, "Manual scanning: $path")
                try {
                    val tracks = scanner.scanAll { current, total ->
                        Log.d(TAG, "  $current / $total folders")
                    }
                    allTracks.addAll(tracks)
                } catch (e: SecurityException) {
                    Log.w(TAG, "No access to $path")
                }
            }
        }

        return allTracks.map { it.toEntity() }
    }

    /**
     * Scan via Rust para pastas que MediaStore não indexa.
     */
    private suspend fun scanWithRust(excludePaths: Array<String>): List<Track> {
        val dirsToScan = listOf(
            "/storage/emulated/0/Music",
            "/storage/emulated/0/.Music",
            "/storage/emulated/0/Download"
        ).filter { File(it).exists() }.toTypedArray()

        return if (dirsToScan.isNotEmpty()) {
            rustBridge.scanDirectories(dirsToScan, excludePaths)
        } else emptyList()
    }

    /**
     * Scan via SAF (Storage Access Framework) — para pastas que o usuário escolhe manualmente.
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

    /**
     * Converte MediaScanner.Track (scanner Kotlin manual) para entity do Room.
     */
    private fun MediaScanner.Track.toEntity(): Track {
        return Track(
            path = this.path,
            title = this.title,
            artist = this.artist,
            album = this.album,
            durationMs = this.duration,
            format = this.extension.uppercase(),
            year = null,
            trackNumber = null
        )
    }

    sealed class ScanProgress {
        object Starting : ScanProgress()
        data class MediaStoreDone(val count: Int) : ScanProgress()
        data class Complete(val total: Int) : ScanProgress()
    }
}

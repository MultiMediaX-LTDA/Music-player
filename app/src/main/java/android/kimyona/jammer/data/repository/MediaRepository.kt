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
import android.kimyona.jammer.core.media.SupportedFormats
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
     * NÃO usa MediaScanner.scanAll() que escaneia TUDO — isso é lento e pode crashar.
     * Em vez disso, escaneia apenas pastas específicas de música.
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

        val audioExts = setOf("opus", "ogg", "mp3", "flac", "m4a", "aac", "wav", "wma", "mid", "midi")
        val allTracks = mutableListOf<Track>()
        var nextId = -1L

        for (path in commonPaths) {
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) {
                Log.d(TAG, "Skipping non-existent path: $path")
                continue
            }

            Log.d(TAG, "Scanning: $path")
            try {
                val files = dir.listFiles()
                if (files == null) {
                    Log.w(TAG, "Cannot list files in: $path (permission denied)")
                    continue
                }

                for (file in files) {
                    if (!file.isFile) continue
                    val ext = file.extension.lowercase()
                    if (ext !in audioExts) continue

                    val formatInfo = SupportedFormats.getByExtension(ext)
                    val (title, artist, album) = parseFilename(file.nameWithoutExtension)

                    allTracks.add(Track(
                        path = file.absolutePath,
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = 0L,
                        format = ext.uppercase(),
                        year = null,
                        trackNumber = null
                    ))
                    nextId--
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "No access to $path: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning $path: ${e.message}")
            }
        }

        Log.d(TAG, "Manual scan found ${allTracks.size} tracks")
        return allTracks
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

    sealed class ScanProgress {
        object Starting : ScanProgress()
        data class MediaStoreDone(val count: Int) : ScanProgress()
        data class Complete(val total: Int) : ScanProgress()
    }
}

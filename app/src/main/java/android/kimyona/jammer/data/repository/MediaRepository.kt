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
     */
    fun scanLibrary(): Flow<ScanProgress> = flow {
        emit(ScanProgress.Starting)
        Log.d(TAG, "=== scanLibrary() STARTED ===")

        try {
            // 1. MediaStore
            Log.d(TAG, "Step 1: Scanning MediaStore...")
            val mediaStoreTracks = scanMediaStore()
            Log.d(TAG, "MediaStore found: ${mediaStoreTracks.size} tracks")
            emit(ScanProgress.MediaStoreDone(mediaStoreTracks.size))

            // 2. Manual fallback
            val manualTracks = if (mediaStoreTracks.isEmpty()) {
                Log.d(TAG, "Step 2: MediaStore empty, scanning manual folders...")
                scanManualFolders()
            } else {
                Log.d(TAG, "Step 2: Skipping manual scan (MediaStore found tracks)")
                emptyList()
            }
            Log.d(TAG, "Manual scan found: ${manualTracks.size} tracks")

            // 3. Rust scanner
            Log.d(TAG, "Step 3: Running Rust scanner...")
            val rustTracks = scanWithRust(
                (mediaStoreTracks + manualTracks).map { it.path }.toTypedArray()
            )
            Log.d(TAG, "Rust scan found: ${rustTracks.size} tracks")

            // 4. Merge
            val merged = (mediaStoreTracks + manualTracks + rustTracks)
                .distinctBy { it.path }
                .sortedBy { it.title.lowercase() }

            Log.d(TAG, "Total unique tracks: ${merged.size}")

            if (merged.isNotEmpty()) {
                Log.d(TAG, "Inserting ${merged.size} tracks into database...")
                db.trackDao().insertAll(merged)
                Log.d(TAG, "Database insert complete")
            } else {
                Log.w(TAG, "No tracks found anywhere!")
            }

            emit(ScanProgress.Complete(merged.size))
            Log.d(TAG, "=== scanLibrary() COMPLETE: ${merged.size} tracks ===")
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL ERROR in scanLibrary: ${e.message}", e)
            emit(ScanProgress.Complete(0))
        }
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
            Log.d(TAG, "Querying MediaStore...")
            context.contentResolver.query(
                uri, projection, null, null,
                MediaStore.Audio.Media.DATE_ADDED + " DESC"
            )?.use { cursor ->
                Log.d(TAG, "MediaStore cursor count: ${cursor.count}")
                if (cursor.count == 0) return@use

                val pathIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val titleIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val yearIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val trackIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)

                while (cursor.moveToNext()) {
                    val path = cursor.getString(pathIdx) ?: continue
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
     * Escaneia pastas de música conhecidas, incluindo subdiretórios (max 3 níveis).
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

        for (path in commonPaths) {
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) {
                Log.d(TAG, "Path does not exist: $path")
                continue
            }

            Log.d(TAG, "Scanning path: $path")
            try {
                val count = scanDirectoryRecursive(dir, audioExts, allTracks, 0)
                Log.d(TAG, "  Found $count tracks in $path")
            } catch (e: SecurityException) {
                Log.w(TAG, "No access to $path: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning $path: ${e.message}")
            }
        }

        Log.d(TAG, "Manual scan total: ${allTracks.size} tracks")
        return allTracks
    }

    /**
     * Escaneia diretório recursivamente com limite de profundidade.
     * Retorna número de tracks encontrados nesta branch.
     */
    private fun scanDirectoryRecursive(
        dir: File,
        audioExts: Set<String>,
        tracks: MutableList<Track>,
        depth: Int
    ): Int {
        if (depth > 3) {
            Log.d(TAG, "  Max depth reached at: ${dir.path}")
            return 0
        }

        var count = 0
        try {
            val files = dir.listFiles()
            if (files == null) {
                Log.w(TAG, "  Cannot list: ${dir.path} (null)")
                return 0
            }

            for (file in files) {
                if (file.isFile) {
                    val ext = file.extension.lowercase()
                    if (ext in audioExts) {
                        val (title, artist, album) = parseFilename(file.nameWithoutExtension)
                        tracks.add(Track(
                            path = file.absolutePath,
                            title = title,
                            artist = artist,
                            album = album,
                            durationMs = 0L,
                            format = ext.uppercase(),
                            year = null,
                            trackNumber = null
                        ))
                        count++
                    }
                } else if (file.isDirectory) {
                    count += scanDirectoryRecursive(file, audioExts, tracks, depth + 1)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "  SecurityException: ${dir.path}")
        }

        return count
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
     * Scan via SAF (Storage Access Framework).
     */
    fun scanWithSAF(treeUri: Uri): List<Track> {
        val tracks = mutableListOf<Track>()
        val audioExts = setOf("opus", "ogg", "mp3", "flac", "m4a", "aac", "wav", "wma", "mid", "midi")

        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return tracks

        fun scanDocument(doc: DocumentFile, depth: Int = 0) {
            if (depth > 3) return
            if (doc.isDirectory) {
                doc.listFiles()?.forEach { scanDocument(it, depth + 1) }
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
                }
            }
        }

        scanDocument(tree)
        Log.d(TAG, "SAF scan: ${tracks.size} tracks")
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

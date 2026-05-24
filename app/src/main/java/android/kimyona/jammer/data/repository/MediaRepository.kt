package android.kimyona.jammer.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.LiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import android.kimyona.jammer.data.JammerDatabase
import android.kimyona.jammer.data.entity.ContentRating
import android.kimyona.jammer.data.entity.ReleaseType
import android.kimyona.jammer.data.entity.Track
import android.kimyona.jammer.core.media.MediaScanner

/**
 * MediaRepository — classe completa.
 * Responsável por escanear mídia, persistir no Room, e expor queries.
 */
class MediaRepository(private val context: Context) {

    private val db = JammerDatabase.getDatabase(context)
    private val scanner = MediaScanner(context)

    val allTracks: LiveData<List<Track>> = db.trackDao().getAll()
    val favorites: LiveData<List<Track>> = db.trackDao().getFavorites()

    // ─── Scan ───────────────────────────────────────────────────────────────

    suspend fun scanLibrary(): Flow<ScanProgress> = flow {
        emit(ScanProgress.Running(0, 1))
        try {
            val scanned = scanner.scanAll()

            val entities = scanned.mapIndexed { index, scannedTrack ->
                emit(ScanProgress.Running(index + 1, scanned.size))
                Track(
                    path = scannedTrack.path,
                    title = scannedTrack.title,
                    artist = scannedTrack.artist,
                    album = scannedTrack.album,
                    durationMs = scannedTrack.duration,
                    format = scannedTrack.extension.uppercase(),
                    dateAdded = System.currentTimeMillis()
                )
            }

            withContext(Dispatchers.IO) {
                db.trackDao().insertAll(entities)
            }

            emit(ScanProgress.Done(entities.size))
        } catch (e: Exception) {
            Log.e("MediaRepository", "Scan failed: ${e.message}", e)
            emit(ScanProgress.Error(e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)

    suspend fun scanSAF(uri: Uri): Flow<ScanProgress> = flow {
        emit(ScanProgress.Running(0, 1))
        try {
            val treePath = getSAFPath(uri) ?: run {
                emit(ScanProgress.Error("Invalid SAF uri"))
                return@flow
            }

            val scanned = scanner.scanAll()
            val prefix = treePath
            val filtered = scanned.filter { it.path.startsWith(prefix) }

            val entities = filtered.mapIndexed { index, scannedTrack ->
                emit(ScanProgress.Running(index + 1, filtered.size))
                Track(
                    path = scannedTrack.path,
                    title = scannedTrack.title,
                    artist = scannedTrack.artist,
                    album = scannedTrack.album,
                    durationMs = scannedTrack.duration,
                    format = scannedTrack.extension.uppercase(),
                    dateAdded = System.currentTimeMillis()
                )
            }

            withContext(Dispatchers.IO) {
                db.trackDao().insertAll(entities)
            }

            emit(ScanProgress.Done(entities.size))
        } catch (e: Exception) {
            Log.e("MediaRepository", "SAF scan failed: ${e.message}", e)
            emit(ScanProgress.Error(e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)

    private fun getSAFPath(uri: Uri): String? {
        val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
        return if (docId.startsWith("primary:")) {
            "/storage/emulated/0/${docId.removePrefix("primary:")}"
        } else null
    }

    // ─── Queries ──────────────────────────────────────────────────────────

    fun searchTracks(query: String): LiveData<List<Track>> =
        db.trackDao().search("%$query%")

    fun getByContentRating(rating: ContentRating): LiveData<List<Track>> =
        db.trackDao().getByContentRating(rating.name)

    fun getByReleaseType(type: ReleaseType): LiveData<List<Track>> =
        db.trackDao().getByReleaseType(type.name)

    fun searchByContentRating(query: String, rating: ContentRating): LiveData<List<Track>> =
        db.trackDao().searchByContentRating("%$query%", rating.name)

    fun searchByReleaseType(query: String, type: ReleaseType): LiveData<List<Track>> =
        db.trackDao().searchByReleaseType("%$query%", type.name)

    // ─── Metadata writers ─────────────────────────────────────────────────

    suspend fun setAlias(path: String, alias: String?) =
        db.trackDao().setAlias(path, alias)

    suspend fun setContentRating(path: String, rating: ContentRating) =
        db.trackDao().setContentRating(path, rating.name)

    suspend fun setReleaseType(path: String, type: ReleaseType?) =
        db.trackDao().setReleaseType(path, type?.name)

    suspend fun setArtistsJoined(path: String, artistsJoined: String?) =
        db.trackDao().setArtistsJoined(path, artistsJoined)

    suspend fun toggleFavorite(path: String, current: Boolean) =
        db.trackDao().setFavorite(path, !current)

    suspend fun getTrackByPath(path: String): Track? =
        db.trackDao().getByPath(path)

    // ─── Sealed class para progresso de scan ────────────────────────────────

    sealed class ScanProgress {
        data class Running(val current: Int, val total: Int) : ScanProgress()
        data class Done(val count: Int) : ScanProgress()
        data class Error(val message: String) : ScanProgress()
    }
}

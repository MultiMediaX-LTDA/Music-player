package android.kimyona.jammer.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import android.kimyona.jammer.data.entity.ContentRating
import android.kimyona.jammer.data.entity.ReleaseType
import android.kimyona.jammer.data.entity.Track
import android.kimyona.jammer.data.repository.MediaRepository
import android.kimyona.jammer.service.JammerPlaybackService

/**
 * PlayerViewModel — BULLETPROOF EDITION.
 *
 * Conecta com JammerPlaybackService via MediaBrowserCompat.
 * Expõe todos os LiveData que a UI precisa.
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)
    private val handler = Handler(Looper.getMainLooper())

    // ─── MediaBrowser / Controller ──────────────────────────────────────────
    private var mediaBrowser: MediaBrowserCompat? = null
    private var mediaController: MediaControllerCompat? = null

    // ─── LiveData ───────────────────────────────────────────────────────────
    val allTracks: LiveData<List<Track>> = repository.allTracks

    private val _currentTrack = MutableLiveData<Track?>(null)
    val currentTrack: LiveData<Track?> = _currentTrack

    private val _isPlaying = MutableLiveData(false)
    val isPlaying: LiveData<Boolean> = _isPlaying

    private val _currentPosition = MutableLiveData(0L)
    val currentPosition: LiveData<Long> = _currentPosition

    private val _shuffleEnabled = MutableLiveData(false)
    val shuffleEnabled: LiveData<Boolean> = _shuffleEnabled

    private val _repeatMode = MutableLiveData(RepeatMode.NONE)
    val repeatMode: LiveData<RepeatMode> = _repeatMode

    private val _queueTracks = MutableLiveData<List<Track>>(emptyList())
    val queueTracks: LiveData<List<Track>> = _queueTracks

    private val _showMiniPlayer = MutableLiveData(false)
    val showMiniPlayer: LiveData<Boolean> = _showMiniPlayer

    private val _scanProgress = MutableLiveData<String?>(null)
    val scanProgress: LiveData<String?> = _scanProgress

    // ─── Scan state ─────────────────────────────────────────────────────────
    private var isScanning = false

    // ─── Position updater ───────────────────────────────────────────────────
    private val positionRunnable = object : Runnable {
        override fun run() {
            try {
                val pos = mediaController?.playbackState?.position ?: 0L
                _currentPosition.postValue(pos)
            } catch (e: Exception) {
                Log.e("PlayerVM", "Position update error", e)
            }
            handler.postDelayed(this, 1000)
        }
    }

    init {
        connectMediaBrowser()
        startPositionUpdates()
    }

    // ─── MediaBrowser connection ────────────────────────────────────────────

    private fun connectMediaBrowser() {
        try {
            mediaBrowser = MediaBrowserCompat(
                getApplication(),
                ComponentName(getApplication(), JammerPlaybackService::class.java),
                object : MediaBrowserCompat.ConnectionCallback() {
                    override fun onConnected() {
                        try {
                            mediaBrowser?.sessionToken?.let { token ->
                                mediaController = MediaControllerCompat(getApplication(), token)
                                mediaController?.registerCallback(controllerCallback)
                                updatePlaybackState()
                            }
                        } catch (e: Exception) {
                            Log.e("PlayerVM", "MediaController init failed", e)
                        }
                    }

                    override fun onConnectionFailed() {
                        Log.e("PlayerVM", "MediaBrowser connection failed")
                    }

                    override fun onConnectionSuspended() {
                        Log.w("PlayerVM", "MediaBrowser connection suspended")
                    }
                },
                null
            )
            mediaBrowser?.connect()
        } catch (e: Exception) {
            Log.e("PlayerVM", "connectMediaBrowser failed", e)
        }
    }

    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            _isPlaying.postValue(state?.state == PlaybackStateCompat.STATE_PLAYING)
            state?.position?.let { _currentPosition.postValue(it) }
        }

        override fun onMetadataChanged(metadata: android.support.v4.media.MediaMetadataCompat?) {
            metadata?.let { meta ->
                val path = meta.getString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_MEDIA_URI)
                    ?: meta.getString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE)
                if (path != null) {
                    viewModelScope.launch {
                        try {
                            val track = repository.getTrackByPath(path)
                            _currentTrack.postValue(track)
                            _showMiniPlayer.postValue(track != null)
                        } catch (e: Exception) {
                            Log.e("PlayerVM", "Metadata lookup failed", e)
                        }
                    }
                }
            }
        }
    }

    private fun updatePlaybackState() {
        try {
            val state = mediaController?.playbackState
            _isPlaying.value = state?.state == PlaybackStateCompat.STATE_PLAYING
        } catch (e: Exception) {
            Log.e("PlayerVM", "updatePlaybackState error", e)
        }
    }

    // ─── Playback controls ──────────────────────────────────────────────────

    fun playTrack(track: Track) {
        try {
            mediaController?.transportControls?.playFromMediaId(track.path, null)
            _currentTrack.value = track
            _showMiniPlayer.value = true
        } catch (e: Exception) {
            Log.e("PlayerVM", "playTrack error", e)
        }
    }

    fun togglePlayPause() {
        try {
            if (_isPlaying.value == true) {
                mediaController?.transportControls?.pause()
            } else {
                mediaController?.transportControls?.play()
            }
        } catch (e: Exception) {
            Log.e("PlayerVM", "togglePlayPause error", e)
        }
    }

    fun skipNext() {
        try { mediaController?.transportControls?.skipToNext() }
        catch (e: Exception) { Log.e("PlayerVM", "skipNext error", e) }
    }

    fun skipPrevious() {
        try { mediaController?.transportControls?.skipToPrevious() }
        catch (e: Exception) { Log.e("PlayerVM", "skipPrevious error", e) }
    }

    fun seekTo(positionMs: Long) {
        try { mediaController?.transportControls?.seekTo(positionMs) }
        catch (e: Exception) { Log.e("PlayerVM", "seekTo error", e) }
    }

    fun toggleShuffle() {
        try {
            val newState = !(_shuffleEnabled.value ?: false)
            _shuffleEnabled.value = newState
            // Notifica o service via custom action
            mediaController?.transportControls?.sendCustomAction(
                "ACTION_SET_SHUFFLE",
                android.os.Bundle().apply { putBoolean("enabled", newState) }
            )
        } catch (e: Exception) {
            Log.e("PlayerVM", "toggleShuffle error", e)
        }
    }

    fun toggleRepeat() {
        try {
            val current = _repeatMode.value ?: RepeatMode.NONE
            val next = when (current) {
                RepeatMode.NONE -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.NONE
            }
            _repeatMode.value = next
            mediaController?.transportControls?.sendCustomAction(
                "ACTION_SET_REPEAT",
                android.os.Bundle().apply { putInt("mode", next.ordinal) }
            )
        } catch (e: Exception) {
            Log.e("PlayerVM", "toggleRepeat error", e)
        }
    }

    // ─── Queue ──────────────────────────────────────────────────────────────

    fun addToQueue(track: Track) {
        try {
            val current = _queueTracks.value ?: emptyList()
            _queueTracks.value = current + track
            mediaController?.transportControls?.sendCustomAction(
                "ACTION_ADD_TO_QUEUE",
                android.os.Bundle().apply { putString("path", track.path) }
            )
        } catch (e: Exception) {
            Log.e("PlayerVM", "addToQueue error", e)
        }
    }

    fun removeFromQueue(index: Int) {
        try {
            val current = _queueTracks.value ?: emptyList()
            if (index in current.indices) {
                _queueTracks.value = current.toMutableList().apply { removeAt(index) }
            }
        } catch (e: Exception) {
            Log.e("PlayerVM", "removeFromQueue error", e)
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        try {
            val current = _queueTracks.value?.toMutableList() ?: return
            if (fromIndex in current.indices && toIndex in current.indices) {
                val item = current.removeAt(fromIndex)
                current.add(toIndex, item)
                _queueTracks.value = current
            }
        } catch (e: Exception) {
            Log.e("PlayerVM", "moveQueueItem error", e)
        }
    }

    fun clearQueue() {
        _queueTracks.value = emptyList()
    }

    // ─── Favorites ──────────────────────────────────────────────────────────

    fun toggleFavorite(track: Track) {
        viewModelScope.launch {
            try {
                repository.toggleFavorite(track.path, track.isFavorite)
            } catch (e: Exception) {
                Log.e("PlayerVM", "toggleFavorite error", e)
            }
        }
    }

    // ─── Scan ───────────────────────────────────────────────────────────────

    fun scanLibrary() {
        if (isScanning) return
        isScanning = true
        _scanProgress.value = "🔍 Scanning…"
        viewModelScope.launch {
            try {
                repository.scanLibrary().collect { progress ->
                    when (progress) {
                        is MediaRepository.ScanProgress.Running -> {
                            _scanProgress.value = "Scanning ${progress.current}/${progress.total}…"
                        }
                        is MediaRepository.ScanProgress.Done -> {
                            _scanProgress.value = "✅ ${progress.count} tracks found"
                            isScanning = false
                        }
                        is MediaRepository.ScanProgress.Error -> {
                            _scanProgress.value = "❌ ${progress.message}"
                            isScanning = false
                        }
                    }
                }
            } catch (e: Exception) {
                _scanProgress.value = "❌ Scan failed: ${e.message}"
                isScanning = false
            }
        }
    }

    fun scanSAF(uri: Uri) {
        if (isScanning) return
        isScanning = true
        _scanProgress.value = "🔍 Scanning folder…"
        viewModelScope.launch {
            try {
                repository.scanSAF(uri).collect { progress ->
                    when (progress) {
                        is MediaRepository.ScanProgress.Running -> {
                            _scanProgress.value = "Scanning ${progress.current}/${progress.total}…"
                        }
                        is MediaRepository.ScanProgress.Done -> {
                            _scanProgress.value = "✅ ${progress.count} tracks found"
                            isScanning = false
                        }
                        is MediaRepository.ScanProgress.Error -> {
                            _scanProgress.value = "❌ ${progress.message}"
                            isScanning = false
                        }
                    }
                }
            } catch (e: Exception) {
                _scanProgress.value = "❌ SAF scan failed: ${e.message}"
                isScanning = false
            }
        }
    }

    // ─── Search & Filter ────────────────────────────────────────────────────

    fun searchTracks(query: String): LiveData<List<Track>> =
        repository.searchTracks(query)

    fun searchWithFilter(
        query: String,
        contentRating: ContentRating? = null,
        releaseType: ReleaseType? = null
    ): LiveData<List<Track>> = when {
        contentRating != null -> repository.searchByContentRating(query, contentRating)
        releaseType != null -> repository.searchByReleaseType(query, releaseType)
        else -> repository.searchTracks(query)
    }

    fun filterByContentRating(rating: ContentRating): LiveData<List<Track>> =
        repository.getByContentRating(rating)

    fun filterByReleaseType(type: ReleaseType): LiveData<List<Track>> =
        repository.getByReleaseType(type)

    // ─── Metadata writers ───────────────────────────────────────────────────

    fun setAlias(path: String, alias: String?) {
        viewModelScope.launch {
            try { repository.setAlias(path, alias) }
            catch (e: Exception) { Log.e("PlayerVM", "setAlias error", e) }
        }
    }

    fun setContentRating(path: String, rating: ContentRating) {
        viewModelScope.launch {
            try { repository.setContentRating(path, rating) }
            catch (e: Exception) { Log.e("PlayerVM", "setContentRating error", e) }
        }
    }

    fun setReleaseType(path: String, type: ReleaseType?) {
        viewModelScope.launch {
            try { repository.setReleaseType(path, type) }
            catch (e: Exception) { Log.e("PlayerVM", "setReleaseType error", e) }
        }
    }

    fun setArtistsJoined(path: String, artistsJoined: String?) {
        viewModelScope.launch {
            try { repository.setArtistsJoined(path, artistsJoined) }
            catch (e: Exception) { Log.e("PlayerVM", "setArtistsJoined error", e) }
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    fun getDuration(): Long {
        return try {
            mediaController?.metadata?.getLong(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0L
        } catch (e: Exception) { 0L }
    }

    private fun startPositionUpdates() {
        handler.postDelayed(positionRunnable, 1000)
    }

    private fun stopPositionUpdates() {
        handler.removeCallbacks(positionRunnable)
    }

    override fun onCleared() {
        super.onCleared()
        stopPositionUpdates()
        try {
            mediaController?.unregisterCallback(controllerCallback)
        } catch (_: Exception) {}
        try {
            mediaBrowser?.disconnect()
        } catch (_: Exception) {}
    }

    // ─── Enums ──────────────────────────────────────────────────────────────

    enum class RepeatMode { NONE, ALL, ONE }
}

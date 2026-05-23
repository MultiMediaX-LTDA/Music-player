package android.kimyona.jammer.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import android.kimyona.jammer.data.JammerDatabase
import android.kimyona.jammer.data.entity.Track
import android.kimyona.jammer.data.repository.MediaRepository
import android.kimyona.jammer.service.JammerPlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PlayerViewModel — BULLETPROOF EDITION.
 *
 * Correções:
 * - NUNCA consulta Room na main thread
 * - Position polling via coroutine (não Handler + Room)
 * - Service binding com null-safety
 * - Track lookup via Flow/LiveData, não query síncrona
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val db = JammerDatabase.getDatabase(application)
    private val repository: MediaRepository

    private val _isPlaying = MutableLiveData(false)
    val isPlaying: LiveData<Boolean> = _isPlaying

    private val _currentPosition = MutableLiveData(0L)
    val currentPosition: LiveData<Long> = _currentPosition

    private val _currentTrack = MutableLiveData<Track?>()
    val currentTrack: LiveData<Track?> = _currentTrack

    private val _scanProgress = MutableLiveData<String>()
    val scanProgress: LiveData<String> = _scanProgress

    private val _shuffleEnabled = MutableLiveData(false)
    val shuffleEnabled: LiveData<Boolean> = _shuffleEnabled

    private val _repeatMode = MutableLiveData(RepeatMode.NONE)
    val repeatMode: LiveData<RepeatMode> = _repeatMode

    private val _queueTracks = MutableLiveData<List<Track>>(emptyList())
    val queueTracks: LiveData<List<Track>> = _queueTracks

    private val _queueSize = MutableLiveData(0)
    val queueSize: LiveData<Int> = _queueSize

    private val _showMiniPlayer = MutableLiveData(false)
    val showMiniPlayer: LiveData<Boolean> = _showMiniPlayer

    private val _currentAlbumArt = MutableLiveData<android.graphics.Bitmap?>(null)
    val currentAlbumArt: LiveData<android.graphics.Bitmap?> = _currentAlbumArt

    private var playbackService: JammerPlaybackService? = null
    private var serviceBound = false
    private var pollingJob: Job? = null

    enum class RepeatMode { NONE, ALL, ONE }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                val binder = service as JammerPlaybackService.LocalBinder
                playbackService = binder.getService()
                serviceBound = true
                Log.d("JammerVM", "Service bound!")
                startPositionPolling()
            } catch (e: Exception) {
                Log.e("JammerVM", "onServiceConnected error: ${e.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            serviceBound = false
            stopPositionPolling()
            Log.d("JammerVM", "Service disconnected")
        }
    }

    init {
        val rustBridge = android.kimyona.jammer.core.media.RustBridge()
        repository = MediaRepository(application, db, rustBridge)

        try {
            val intent = Intent(application, JammerPlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                application.startForegroundService(intent)
            } else {
                application.startService(intent)
            }
            application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e("JammerVM", "Service init error: ${e.message}")
        }
    }

    // ==================== POSITION POLLING (COROUTINE) ====================

    private fun startPositionPolling() {
        stopPositionPolling()
        pollingJob = viewModelScope.launch {
            var lastPath: String? = null
            while (isActive) {
                try {
                    val svc = playbackService
                    if (svc != null) {
                        _currentPosition.postValue(svc.getCurrentPosition())
                        _isPlaying.postValue(svc.isPlaying())

                        val currentPath = svc.getCurrentTrackPath()
                        if (currentPath != null && currentPath != lastPath) {
                            lastPath = currentPath
                            // Busca track em background — NUNCA na main thread
                            val track = withContext(Dispatchers.IO) {
                                try {
                                    db.trackDao().getByPath(currentPath)
                                } catch (e: Exception) {
                                    Log.e("JammerVM", "DB query error: ${e.message}")
                                    null
                                }
                            }
                            _currentTrack.postValue(track)
                            _showMiniPlayer.postValue(track != null)

                            // Carrega capa em background
                            if (track != null) {
                                val art = withContext(Dispatchers.IO) {
                                    try {
                                        android.kimyona.jammer.core.media.AlbumArtLoader
                                            .extractEmbeddedArt(track.path)
                                    } catch (e: Exception) {
                                        null
                                    }
                                }
                                _currentAlbumArt.postValue(art)
                            }
                        }

                        // Atualiza queue
                        val queuePaths = svc.getQueue()
                        if (queuePaths.isNotEmpty()) {
                            val queueList = withContext(Dispatchers.IO) {
                                val list = mutableListOf<Track>()
                                for (path in queuePaths) {
                                    try {
                                        db.trackDao().getByPath(path)?.let { list.add(it) }
                                    } catch (e: Exception) {
                                        Log.e("JammerVM", "Queue DB error: ${e.message}")
                                    }
                                }
                                list
                            }
                            _queueTracks.postValue(queueList)
                            _queueSize.postValue(queueList.size)
                        }

                        _shuffleEnabled.postValue(svc.isShuffleEnabled())
                    }
                } catch (e: Exception) {
                    Log.e("JammerVM", "Polling error: ${e.message}")
                }
                delay(500)
            }
        }
    }

    private fun stopPositionPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    // ==================== PLAYBACK ====================

    fun scanLibrary() {
        viewModelScope.launch {
            try {
                repository.scanLibrary().collect { progress ->
                    _scanProgress.value = when (progress) {
                        is MediaRepository.ScanProgress.Starting -> "Starting scan..."
                        is MediaRepository.ScanProgress.MediaStoreDone -> "MediaStore: ${progress.count} tracks"
                        is MediaRepository.ScanProgress.Complete -> "Done! ${progress.total} tracks total"
                    }
                }
            } catch (e: Exception) {
                Log.e("JammerVM", "scanLibrary error: ${e.message}")
                _scanProgress.value = "Scan failed: ${e.message}"
            }
        }
    }

    fun scanSAF(uri: Uri) {
        viewModelScope.launch {
            try {
                val tracks = repository.scanWithSAF(uri)
                if (tracks.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        db.trackDao().insertAll(tracks)
                    }
                    _scanProgress.value = "Added ${tracks.size} tracks from folder"
                }
            } catch (e: Exception) {
                Log.e("JammerVM", "scanSAF error: ${e.message}")
            }
        }
    }

    fun playTrack(track: Track) {
        try {
            playbackService?.playSingle(track.path)
                ?: run {
                    val intent = Intent(getApplication(), JammerPlaybackService::class.java).apply {
                        action = JammerPlaybackService.ACTION_PLAY_SINGLE
                        putExtra("path", track.path)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        getApplication<Application>().startForegroundService(intent)
                    } else {
                        getApplication<Application>().startService(intent)
                    }
                }
            _currentTrack.value = track
            _isPlaying.value = true
            _showMiniPlayer.value = true

            viewModelScope.launch {
                val art = withContext(Dispatchers.IO) {
                    try {
                        android.kimyona.jammer.core.media.AlbumArtLoader.extractEmbeddedArt(track.path)
                    } catch (e: Exception) { null }
                }
                _currentAlbumArt.value = art
            }
        } catch (e: Exception) {
            Log.e("JammerVM", "playTrack error: ${e.message}")
        }
    }

    fun playPlaylist(tracks: List<Track>, startIndex: Int = 0) {
        try {
            val paths = tracks.map { it.path }
            playbackService?.playTracks(paths, startIndex)
                ?: run {
                    val intent = Intent(getApplication(), JammerPlaybackService::class.java).apply {
                        action = JammerPlaybackService.ACTION_PLAY_LIST
                        putStringArrayListExtra("paths", ArrayList(paths))
                        putExtra("startIndex", startIndex)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        getApplication<Application>().startForegroundService(intent)
                    } else {
                        getApplication<Application>().startService(intent)
                    }
                }
            _currentTrack.value = tracks.getOrNull(startIndex)
            _isPlaying.value = true
            _showMiniPlayer.value = true
        } catch (e: Exception) {
            Log.e("JammerVM", "playPlaylist error: ${e.message}")
        }
    }

    fun togglePlayPause() {
        try {
            playbackService?.togglePlayPause()
                ?: sendServiceIntent(JammerPlaybackService.ACTION_TOGGLE)
        } catch (e: Exception) {
            Log.e("JammerVM", "togglePlayPause error: ${e.message}")
        }
    }

    fun seekTo(positionMs: Long) {
        try {
            playbackService?.seekTo(positionMs)
                ?: sendServiceIntent(JammerPlaybackService.ACTION_SEEK, "positionMs" to positionMs)
            _currentPosition.value = positionMs
        } catch (e: Exception) {
            Log.e("JammerVM", "seekTo error: ${e.message}")
        }
    }

    fun skipNext() {
        try {
            playbackService?.skipToNext()
                ?: sendServiceIntent(JammerPlaybackService.ACTION_SKIP_NEXT)
        } catch (e: Exception) {
            Log.e("JammerVM", "skipNext error: ${e.message}")
        }
    }

    fun skipPrevious() {
        try {
            playbackService?.skipToPrevious()
                ?: sendServiceIntent(JammerPlaybackService.ACTION_SKIP_PREV)
        } catch (e: Exception) {
            Log.e("JammerVM", "skipPrevious error: ${e.message}")
        }
    }

    // ==================== SHUFFLE & REPEAT ====================

    fun toggleShuffle() {
        try {
            val newValue = !(_shuffleEnabled.value ?: false)
            _shuffleEnabled.value = newValue
            playbackService?.setShuffle(newValue)
                ?: sendServiceIntent(
                    JammerPlaybackService.ACTION_SET_SHUFFLE,
                    "enabled" to newValue
                )
        } catch (e: Exception) {
            Log.e("JammerVM", "toggleShuffle error: ${e.message}")
        }
    }

    fun setShuffle(enabled: Boolean) {
        _shuffleEnabled.value = enabled
        try {
            playbackService?.setShuffle(enabled)
        } catch (e: Exception) {
            Log.e("JammerVM", "setShuffle error: ${e.message}")
        }
    }

    fun toggleRepeat() {
        try {
            val nextMode = when (_repeatMode.value) {
                RepeatMode.NONE -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.NONE
                null -> RepeatMode.NONE
            }
            _repeatMode.value = nextMode
            playbackService?.let {
                val serviceMode = when (nextMode) {
                    RepeatMode.NONE -> JammerPlaybackService.RepeatMode.NONE
                    RepeatMode.ALL -> JammerPlaybackService.RepeatMode.ALL
                    RepeatMode.ONE -> JammerPlaybackService.RepeatMode.ONE
                }
                it.setRepeat(serviceMode)
            }
        } catch (e: Exception) {
            Log.e("JammerVM", "toggleRepeat error: ${e.message}")
        }
    }

    fun setRepeat(mode: RepeatMode) {
        _repeatMode.value = mode
    }

    // ==================== QUEUE ====================

    fun addToQueue(track: Track) {
        try {
            playbackService?.addToQueue(track.path)
                ?: sendServiceIntent(
                    JammerPlaybackService.ACTION_ADD_TO_QUEUE,
                    "path" to track.path
                )
            val currentQueue = _queueTracks.value?.toMutableList() ?: mutableListOf()
            currentQueue.add(track)
            _queueTracks.value = currentQueue
            _queueSize.value = currentQueue.size
        } catch (e: Exception) {
            Log.e("JammerVM", "addToQueue error: ${e.message}")
        }
    }

    fun removeFromQueue(index: Int) {
        try {
            playbackService?.removeFromQueue(index)
            val currentQueue = _queueTracks.value?.toMutableList() ?: return
            if (index in currentQueue.indices) {
                currentQueue.removeAt(index)
                _queueTracks.value = currentQueue
                _queueSize.value = currentQueue.size
            }
        } catch (e: Exception) {
            Log.e("JammerVM", "removeFromQueue error: ${e.message}")
        }
    }

    fun clearQueue() {
        try {
            playbackService?.clearQueue()
                ?: sendServiceIntent(JammerPlaybackService.ACTION_CLEAR_QUEUE)
            _queueTracks.value = emptyList()
            _queueSize.value = 0
        } catch (e: Exception) {
            Log.e("JammerVM", "clearQueue error: ${e.message}")
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        try {
            playbackService?.moveQueueItem(fromIndex, toIndex)
            val currentQueue = _queueTracks.value?.toMutableList() ?: return
            if (fromIndex in currentQueue.indices && toIndex in currentQueue.indices) {
                val item = currentQueue.removeAt(fromIndex)
                currentQueue.add(toIndex, item)
                _queueTracks.value = currentQueue
            }
        } catch (e: Exception) {
            Log.e("JammerVM", "moveQueueItem error: ${e.message}")
        }
    }

    fun getDuration(): Long {
        return try { playbackService?.getDuration() ?: 0L } catch (e: Exception) { 0L }
    }

    // ==================== DATA ====================

    val allTracks = repository.allTracks
    val favorites = repository.favorites

    fun search(query: String) = repository.searchTracks(query)

    fun toggleFavorite(track: Track) {
        viewModelScope.launch {
            try {
                repository.toggleFavorite(track.path)
            } catch (e: Exception) {
                Log.e("JammerVM", "toggleFavorite error: ${e.message}")
            }
        }
    }

    // ==================== HELPERS ====================

    private fun sendServiceIntent(action: String, vararg extras: Pair<String, Any>) {
        try {
            val intent = Intent(getApplication(), JammerPlaybackService::class.java).apply {
                this.action = action
                extras.forEach { (key, value) ->
                    when (value) {
                        is String -> putExtra(key, value)
                        is Long -> putExtra(key, value)
                        is Int -> putExtra(key, value)
                        is Boolean -> putExtra(key, value)
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getApplication<Application>().startForegroundService(intent)
            } else {
                getApplication<Application>().startService(intent)
            }
        } catch (e: Exception) {
            Log.e("JammerVM", "sendServiceIntent error: ${e.message}")
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            stopPositionPolling()
            if (serviceBound) {
                getApplication<Application>().unbindService(serviceConnection)
            }
        } catch (e: Exception) {
            Log.e("JammerVM", "onCleared error: ${e.message}")
        }
    }
}

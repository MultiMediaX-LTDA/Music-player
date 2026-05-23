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
import androidx.lifecycle.viewModelScope
import android.kimyona.jammer.data.JammerDatabase
import android.kimyona.jammer.data.entity.Track
import android.kimyona.jammer.data.repository.MediaRepository
import android.kimyona.jammer.service.JammerPlaybackService
import kotlinx.coroutines.launch

/**
 * PlayerViewModel evoluído — suporta shuffle, repeat, queue funcional e mini-player.
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

    // === SHUFFLE & REPEAT ===
    private val _shuffleEnabled = MutableLiveData(false)
    val shuffleEnabled: LiveData<Boolean> = _shuffleEnabled

    private val _repeatMode = MutableLiveData(RepeatMode.NONE)
    val repeatMode: LiveData<RepeatMode> = _repeatMode

    // === QUEUE ===
    private val _queueTracks = MutableLiveData<List<Track>>(emptyList())
    val queueTracks: LiveData<List<Track>> = _queueTracks

    private val _queueSize = MutableLiveData(0)
    val queueSize: LiveData<Int> = _queueSize

    // === MINI-PLAYER ===
    private val _showMiniPlayer = MutableLiveData(false)
    val showMiniPlayer: LiveData<Boolean> = _showMiniPlayer

    private val _currentAlbumArt = MutableLiveData<android.graphics.Bitmap?>(null)
    val currentAlbumArt: LiveData<android.graphics.Bitmap?> = _currentAlbumArt

    private var playbackService: JammerPlaybackService? = null
    private var serviceBound = false

    private val positionHandler = Handler(Looper.getMainLooper())
    private var positionRunnable: Runnable? = null

    enum class RepeatMode { NONE, ALL, ONE }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as JammerPlaybackService.LocalBinder
            playbackService = binder.getService()
            serviceBound = true
            Log.d("JammerVM", "Service bound!")
            startPositionPolling()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            serviceBound = false
            stopPositionPolling()
        }
    }

    init {
        val rustBridge = android.kimyona.jammer.core.media.RustBridge()
        repository = MediaRepository(application, db, rustBridge)

        val intent = Intent(application, JammerPlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            application.startForegroundService(intent)
        } else {
            application.startService(intent)
        }
        application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    // ==================== POSITION POLLING ====================

    private fun startPositionPolling() {
        positionRunnable = object : Runnable {
            override fun run() {
                playbackService?.let { svc ->
                    _currentPosition.postValue(svc.getCurrentPosition())
                    _isPlaying.postValue(svc.isPlaying())

                    val currentPath = svc.getCurrentTrackPath()
                    if (currentPath != null && currentPath != _currentTrack.value?.path) {
                        viewModelScope.launch {
                            val track = db.trackDao().getByPath(currentPath)
                            _currentTrack.postValue(track)
                            _showMiniPlayer.postValue(track != null)

                            // Carrega capa para mini-player
                            track?.let { t ->
                                val art = android.kimyona.jammer.core.media.AlbumArtLoader
                                    .extractEmbeddedArt(t.path)
                                _currentAlbumArt.postValue(art)
                            }
                        }
                    }

                    // Atualiza queue
                    val queuePaths = svc.getQueue()
                    if (queuePaths.isNotEmpty()) {
                        viewModelScope.launch {
                            val queueList = mutableListOf<Track>()
                            for (path in queuePaths) {
                                db.trackDao().getByPath(path)?.let { queueList.add(it) }
                            }
                            _queueTracks.postValue(queueList)
                            _queueSize.postValue(queueList.size)
                        }
                    }

                    // Atualiza shuffle/repeat state do service
                    _shuffleEnabled.postValue(svc.isShuffleEnabled())
                }
                positionHandler.postDelayed(this, 500)
            }
        }
        positionHandler.postDelayed(positionRunnable!!, 500)
    }

    private fun stopPositionPolling() {
        positionRunnable?.let { positionHandler.removeCallbacks(it) }
    }

    // ==================== PLAYBACK ====================

    fun scanLibrary() {
        viewModelScope.launch {
            Log.d("JammerScanner", "=== scanLibrary() started ===")
            repository.scanLibrary().collect { progress ->
                Log.d("JammerScanner", "Progress: $progress")
                _scanProgress.value = when (progress) {
                    is MediaRepository.ScanProgress.Starting -> "Starting scan..."
                    is MediaRepository.ScanProgress.MediaStoreDone -> "MediaStore: ${progress.count} tracks"
                    is MediaRepository.ScanProgress.Complete -> "Done! ${progress.total} tracks total"
                }
            }
        }
    }

    fun scanSAF(uri: Uri) {
        viewModelScope.launch {
            val tracks = repository.scanWithSAF(uri)
            if (tracks.isNotEmpty()) {
                db.trackDao().insertAll(tracks)
                _scanProgress.value = "Added ${tracks.size} tracks from folder"
            }
        }
    }

    fun playTrack(track: Track) {
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

        // Carrega capa
        viewModelScope.launch {
            val art = android.kimyona.jammer.core.media.AlbumArtLoader.extractEmbeddedArt(track.path)
            _currentAlbumArt.value = art
        }
    }

    fun playPlaylist(tracks: List<Track>, startIndex: Int = 0) {
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
    }

    fun togglePlayPause() {
        playbackService?.togglePlayPause()
            ?: sendServiceIntent(JammerPlaybackService.ACTION_TOGGLE)
    }

    fun seekTo(positionMs: Long) {
        playbackService?.seekTo(positionMs)
            ?: sendServiceIntent(JammerPlaybackService.ACTION_SEEK, "positionMs" to positionMs)
        _currentPosition.value = positionMs
    }

    fun skipNext() {
        playbackService?.skipToNext()
            ?: sendServiceIntent(JammerPlaybackService.ACTION_SKIP_NEXT)
    }

    fun skipPrevious() {
        playbackService?.skipToPrevious()
            ?: sendServiceIntent(JammerPlaybackService.ACTION_SKIP_PREV)
    }

    // ==================== SHUFFLE & REPEAT ====================

    fun toggleShuffle() {
        val newValue = !(_shuffleEnabled.value ?: false)
        _shuffleEnabled.value = newValue
        playbackService?.setShuffle(newValue)
            ?: sendServiceIntent(
                JammerPlaybackService.ACTION_SET_SHUFFLE,
                "enabled" to newValue
            )
    }

    fun setShuffle(enabled: Boolean) {
        _shuffleEnabled.value = enabled
        playbackService?.setShuffle(enabled)
    }

    fun toggleRepeat() {
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
    }

    fun setRepeat(mode: RepeatMode) {
        _repeatMode.value = mode
    }

    // ==================== QUEUE ====================

    fun addToQueue(track: Track) {
        playbackService?.addToQueue(track.path)
            ?: sendServiceIntent(
                JammerPlaybackService.ACTION_ADD_TO_QUEUE,
                "path" to track.path
            )

        // Atualiza lista de queue
        val currentQueue = _queueTracks.value?.toMutableList() ?: mutableListOf()
        currentQueue.add(track)
        _queueTracks.value = currentQueue
        _queueSize.value = currentQueue.size
    }

    fun removeFromQueue(index: Int) {
        playbackService?.removeFromQueue(index)

        val currentQueue = _queueTracks.value?.toMutableList() ?: return
        if (index in currentQueue.indices) {
            currentQueue.removeAt(index)
            _queueTracks.value = currentQueue
            _queueSize.value = currentQueue.size
        }
    }

    fun clearQueue() {
        playbackService?.clearQueue()
            ?: sendServiceIntent(JammerPlaybackService.ACTION_CLEAR_QUEUE)
        _queueTracks.value = emptyList()
        _queueSize.value = 0
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        playbackService?.moveQueueItem(fromIndex, toIndex)

        val currentQueue = _queueTracks.value?.toMutableList() ?: return
        if (fromIndex in currentQueue.indices && toIndex in currentQueue.indices) {
            val item = currentQueue.removeAt(fromIndex)
            currentQueue.add(toIndex, item)
            _queueTracks.value = currentQueue
        }
    }

    fun getDuration(): Long {
        return playbackService?.getDuration() ?: 0L
    }

    // ==================== DATA ====================

    val allTracks = repository.allTracks
    val favorites = repository.favorites

    fun search(query: String) = repository.searchTracks(query)

    fun toggleFavorite(track: Track) {
        viewModelScope.launch {
            repository.toggleFavorite(track.path)
        }
    }

    // ==================== HELPERS ====================

    private fun sendServiceIntent(action: String, vararg extras: Pair<String, Any>) {
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
    }

    override fun onCleared() {
        super.onCleared()
        stopPositionPolling()
        if (serviceBound) {
            getApplication<Application>().unbindService(serviceConnection)
        }
    }
}

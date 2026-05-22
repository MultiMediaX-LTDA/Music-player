package android.kimyona.jammer.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
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

    private var playbackService: JammerPlaybackService? = null
    private var serviceBound = false

    private val positionHandler = Handler(Looper.getMainLooper())
    private var positionRunnable: Runnable? = null

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
        // RustBridge é criado aqui, mas agora é DEFENSIVO — não crasha sem .so
        val rustBridge = android.kimyona.jammer.core.media.RustBridge()
        repository = MediaRepository(application, db, rustBridge)

        val intent = Intent(application, JammerPlaybackService::class.java)
        application.startService(intent)
        application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

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
                        }
                    }
                }
                positionHandler.postDelayed(this, 500)
            }
        }
        positionHandler.postDelayed(positionRunnable!!, 500)
    }

    private fun stopPositionPolling() {
        positionRunnable?.let { positionHandler.removeCallbacks(it) }
    }

    fun scanLibrary() {
        viewModelScope.launch {
            Log.d("JammerScanner", "=== scanLibrary() started ===")

            repository.scanLibrary().collect { progress ->
                Log.d("JammerScanner", "Progress: $progress")
                _scanProgress.value = when (progress) {
                    is MediaRepository.ScanProgress.Starting -> {
                        "Starting scan..."
                    }
                    is MediaRepository.ScanProgress.MediaStoreDone -> {
                        "MediaStore: ${progress.count} tracks"
                    }
                    is MediaRepository.ScanProgress.Complete -> {
                        "Done! ${progress.total} tracks total"
                    }
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
                getApplication<Application>().startService(intent)
            }
        _currentTrack.value = track
        _isPlaying.value = true
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
                getApplication<Application>().startService(intent)
            }
        _currentTrack.value = tracks.getOrNull(startIndex)
        _isPlaying.value = true
    }

    fun togglePlayPause() {
        playbackService?.togglePlayPause()
            ?: run {
                val intent = Intent(getApplication(), JammerPlaybackService::class.java).apply {
                    action = JammerPlaybackService.ACTION_TOGGLE
                }
                getApplication<Application>().startService(intent)
            }
    }

    fun seekTo(positionMs: Long) {
        playbackService?.seekTo(positionMs)
            ?: run {
                val intent = Intent(getApplication(), JammerPlaybackService::class.java).apply {
                    action = JammerPlaybackService.ACTION_SEEK
                    putExtra("positionMs", positionMs)
                }
                getApplication<Application>().startService(intent)
            }
        _currentPosition.value = positionMs
    }

    fun skipNext() {
        playbackService?.skipToNext()
            ?: run {
                val intent = Intent(getApplication(), JammerPlaybackService::class.java).apply {
                    action = JammerPlaybackService.ACTION_SKIP_NEXT
                }
                getApplication<Application>().startService(intent)
            }
    }

    fun skipPrevious() {
        playbackService?.skipToPrevious()
            ?: run {
                val intent = Intent(getApplication(), JammerPlaybackService::class.java).apply {
                    action = JammerPlaybackService.ACTION_SKIP_PREV
                }
                getApplication<Application>().startService(intent)
            }
    }

    fun getDuration(): Long {
        return playbackService?.getDuration() ?: 0L
    }

    val allTracks = repository.allTracks
    val favorites = repository.favorites

    fun search(query: String) = repository.searchTracks(query)

    override fun onCleared() {
        super.onCleared()
        stopPositionPolling()
        if (serviceBound) {
            getApplication<Application>().unbindService(serviceConnection)
        }
    }
}

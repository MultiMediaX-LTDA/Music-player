package android.kimyona.jammer.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import android.kimyona.jammer.data.JammerDatabase
import android.kimyona.jammer.data.entity.Track
import android.kimyona.jammer.data.repository.MediaRepository
import android.kimyona.jammer.service.JammerPlaybackService
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * ViewModel que conecta UI ao Service de playback.
 * Sobrevive rotação de tela e troca de Activity.
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val db = JammerDatabase.getDatabase(application)
    private val repository: MediaRepository

    // Estado observável pela UI
    private val _isPlaying = MutableLiveData(false)
    val isPlaying: LiveData<Boolean> = _isPlaying

    private val _currentPosition = MutableLiveData(0L)
    val currentPosition: LiveData<Long> = _currentPosition

    private val _currentTrack = MutableLiveData<Track?>()
    val currentTrack: LiveData<Track?> = _currentTrack

    private val _scanProgress = MutableLiveData<String>()
    val scanProgress: LiveData<String> = _scanProgress

    // Service connection
    private var playbackService: JammerPlaybackService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            // Service não usa Binder, acessamos via instância direta
            // (simplificado — em produção use Binder)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            serviceBound = false
        }
    }

    init {
        val rustBridge = android.kimyona.jammer.core.media.RustBridge()
        repository = MediaRepository(application, db, rustBridge)

        // Bind ao service
        val intent = Intent(application, JammerPlaybackService::class.java)
        application.startService(intent)
        application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Inicia scan da biblioteca.
     * Mostra progresso na UI.
     */
    fun scanLibrary() {
        viewModelScope.launch {
            repository.scanLibrary().collect { progress ->
                _scanProgress.value = when (progress) {
                    is MediaRepository.ScanProgress.Starting -> "Starting scan..."
                    is MediaRepository.ScanProgress.MediaStoreDone ->
                        "Found ${progress.count} tracks from MediaStore"
                    is MediaRepository.ScanProgress.RustScanning ->
                        "Scanning extra folders with Rust..."
                    is MediaRepository.ScanProgress.RustDone ->
                        "Found ${progress.count} extra tracks"
                    is MediaRepository.ScanProgress.Complete ->
                        "Done! ${progress.total} tracks total"
                }
            }
        }
    }

    fun playTrack(track: Track) {
        // TODO: bind service properly and call playSingle
        val intent = Intent(getApplication(), JammerPlaybackService::class.java).apply {
            action = "PLAY_SINGLE"
            putExtra("path", track.path)
        }
        getApplication<Application>().startService(intent)
        _currentTrack.value = track
        _isPlaying.value = true
    }

    fun playPlaylist(tracks: List<Track>, startIndex: Int = 0) {
        val paths = tracks.map { it.path }
        val intent = Intent(getApplication(), JammerPlaybackService::class.java).apply {
            action = "PLAY_LIST"
            putStringArrayListExtra("paths", ArrayList(paths))
            putExtra("startIndex", startIndex)
        }
        getApplication<Application>().startService(intent)
        _currentTrack.value = tracks.getOrNull(startIndex)
        _isPlaying.value = true
    }

    fun togglePlayPause() {
        // TODO: via service
        _isPlaying.value = !(_isPlaying.value ?: false)
    }

    fun seekTo(positionMs: Long) {
        _currentPosition.value = positionMs
        // TODO: via service
    }

    fun skipNext() {
        // TODO: via service
    }

    fun skipPrevious() {
        // TODO: via service
    }

    val allTracks = repository.allTracks
    val favorites = repository.favorites

    fun search(query: String) = repository.searchTracks(query)

    override fun onCleared() {
        super.onCleared()
        if (serviceBound) {
            getApplication<Application>().unbindService(serviceConnection)
        }
    }
}

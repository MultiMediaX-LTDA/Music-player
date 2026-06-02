package android.kimyona.jammer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import android.kimyona.jammer.util.WeakRefRunnable
import android.kimyona.jammer.R
import android.kimyona.jammer.core.media.AlbumArtLoader
import android.kimyona.jammer.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class JammerPlaybackService : MediaBrowserServiceCompat() {

    private val TAG = "JammerService"
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "jammer_playback"

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSessionCompat? = null
    private var mediaSessionConnector: MediaSessionConnector? = null

    private val positionHandler = Handler(Looper.getMainLooper())
    private var positionRunnable: WeakRefRunnable<JammerPlaybackService>? = null
    private val seekHandler = Handler(Looper.getMainLooper())
    

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var currentPlaylist = mutableListOf<String>()
    private var playbackOrder = mutableListOf<Int>()
    private var currentPlaybackIndex = 0

    enum class RepeatMode { NONE, ALL, ONE }
    private var repeatMode = RepeatMode.NONE
    private var isShuffleEnabled = false

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    private var noisyReceiver: BroadcastReceiver? = null
private var playerListener: Player.Listener? = null

    companion object {
        const val ACTION_PLAY_SINGLE = "android.kimyona.jammer.PLAY_SINGLE"
        const val ACTION_PLAY_LIST = "android.kimyona.jammer.PLAY_LIST"
        const val ACTION_TOGGLE = "android.kimyona.jammer.TOGGLE"
        const val ACTION_SKIP_NEXT = "android.kimyona.jammer.SKIP_NEXT"
        const val ACTION_SKIP_PREV = "android.kimyona.jammer.SKIP_PREV"
        const val ACTION_SEEK = "android.kimyona.jammer.SEEK"
        const val ACTION_ADD_TO_QUEUE = "android.kimyona.jammer.ADD_TO_QUEUE"
        const val ACTION_CLEAR_QUEUE = "android.kimyona.jammer.CLEAR_QUEUE"
        const val ACTION_SET_SHUFFLE = "android.kimyona.jammer.SET_SHUFFLE"
        const val ACTION_SET_REPEAT = "android.kimyona.jammer.SET_REPEAT"
    }

    // ==================== LIFECYCLE ====================

    override fun onCreate() {
        super.onCreate()
        try {
            Log.d(TAG, "onCreate")
            createNotificationChannel()
            audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            initializePlayer()
            initializeMediaSession()
            registerNoisyReceiver()
            startPositionUpdates()
        } catch (e: Exception) {
            Log.e(TAG, "onCreate failed: ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            Log.d(TAG, "onStartCommand: action=${intent?.action}")

            // Garante foreground IMEDIATAMENTE
            val emptyNotification = buildEmptyNotification()
            startForegroundSafe(emptyNotification)

            when (intent?.action) {
                ACTION_PLAY_SINGLE -> {
                    val path = intent.getStringExtra("path")
                    path?.let { playSingle(it) }
                }
                ACTION_PLAY_LIST -> {
                    val paths = intent.getStringArrayListExtra("paths")
                    val startIndex = intent.getIntExtra("startIndex", 0)
                    paths?.let { playTracks(it, startIndex) }
                }
                ACTION_TOGGLE -> togglePlayPause()
                ACTION_SKIP_NEXT -> skipToNext()
                ACTION_SKIP_PREV -> skipToPrevious()
                ACTION_SEEK -> {
                    val pos = intent.getLongExtra("position", 0)
                    debouncedSeekTo(pos)
                }
                ACTION_ADD_TO_QUEUE -> {
                    val path = intent.getStringExtra("path")
                    path?.let { addToQueue(it) }
                }
                ACTION_CLEAR_QUEUE -> clearQueue()
                ACTION_SET_SHUFFLE -> {
                    val enabled = intent.getBooleanExtra("enabled", false)
                    setShuffle(enabled)
                }
                ACTION_SET_REPEAT -> {
                    val modeOrdinal = intent.getIntExtra("mode", 0)
                    setRepeat(RepeatMode.entries[modeOrdinal])
                }
                else -> {
                    MediaButtonReceiver.handleIntent(mediaSession, intent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand error: ${e.message}", e)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    // ==================== PLAYER INIT ====================

    private fun initializePlayer() {
        try {
playerListener = object : Player.Listener {
    override fun onPlaybackStateChanged(state: Int) {
        try {
            if (state == Player.STATE_ENDED) {
                onTrackEnded()
            }
            updatePlaybackState(isPlaying)
        } catch (e: Exception) {
            Log.e(TAG, "onPlaybackStateChanged error", e)
        }
    }
    override fun onIsPlayingChanged(isPlaying: Boolean) {
        try {
            updatePlaybackState(isPlaying)
            if (isPlaying) {
                startPositionUpdates()
            } else {
                stopPositionUpdates()
            }
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "onIsPlayingChanged error", e)
        }
    }
    override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
        Log.e(TAG, "Player error: ${error.errorCodeName} - ${error.message}")
        skipToNext()
    }
}
player = ExoPlayer.Builder(this).build().apply {
    addListener(playerListener!!)
}       
            Log.d(TAG, "ExoPlayer initialized")
        } catch (e: Exception) {
            Log.e(TAG, "initializePlayer failed: ${e.message}", e)
            throw e
        }
    }

    private fun initializeMediaSession() {
        try {
            Log.d(TAG, "Initializing MediaSession...")
            mediaSession = MediaSessionCompat(this, TAG).apply {
                isActive = true
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        try { togglePlayPause() } catch (e: Exception) { Log.e(TAG, "onPlay error", e) }
                    }
                    override fun onPause() {
                        try { togglePlayPause() } catch (e: Exception) { Log.e(TAG, "onPause error", e) }
                    }
                    override fun onSkipToNext() {
                        try { skipToNext() } catch (e: Exception) { Log.e(TAG, "onSkipToNext error", e) }
                    }
                    override fun onSkipToPrevious() {
                        try { skipToPrevious() } catch (e: Exception) { Log.e(TAG, "onSkipToPrevious error", e) }
                    }
                    override fun onSeekTo(pos: Long) {
                        try { seekTo(pos) } catch (e: Exception) { Log.e(TAG, "onSeekTo error", e) }
                    }
                    override fun onStop() {
                        try { stopPlayback() } catch (e: Exception) { Log.e(TAG, "onStop error", e) }
                    }
                })
            }

            val p = player ?: throw IllegalStateException("Player is null")
            mediaSessionConnector = MediaSessionConnector(mediaSession!!).apply {
                setPlayer(p)
            }

            sessionToken = mediaSession!!.sessionToken
            Log.d(TAG, "MediaSession initialized")
        } catch (e: Exception) {
            Log.e(TAG, "initializeMediaSession failed: ${e.message}", e)
            throw e
        }
    }

    // ==================== AUDIO FOCUS ====================

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val am = audioManager ?: return false

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { focusChange ->
                        when (focusChange) {
                            AudioManager.AUDIOFOCUS_LOSS,
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                                player?.pause()
                                updateNotification()
                                hasAudioFocus = false
                            }
                            AudioManager.AUDIOFOCUS_GAIN -> {
                                player?.play()
                                updateNotification()
                                hasAudioFocus = true
                            }
                        }
                    }
                    .build()
                audioFocusRequest = request
                val result = am.requestAudioFocus(request)
                hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            } else {
                @Suppress("DEPRECATION")
                val result = am.requestAudioFocus(
                    { focusChange ->
                        when (focusChange) {
                            AudioManager.AUDIOFOCUS_LOSS,
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                                player?.pause()
                                updateNotification()
                                hasAudioFocus = false
                            }
                            AudioManager.AUDIOFOCUS_GAIN -> {
                                player?.play()
                                updateNotification()
                                hasAudioFocus = true
                            }
                        }
                    },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
                hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            }
            hasAudioFocus
        } catch (e: Exception) {
            Log.e(TAG, "requestAudioFocus error: ${e.message}")
            false
        }
    }

    private fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
            hasAudioFocus = false
        } catch (e: Exception) {
            Log.e(TAG, "abandonAudioFocus error: ${e.message}")
        }
    }

    // ==================== NOISY RECEIVER ====================

    private fun registerNoisyReceiver() {
        try {
            noisyReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                        Log.d(TAG, "Audio becoming noisy - pausing")
                        player?.pause()
                        updateNotification()
                    }
                }
            }
            registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        } catch (e: Exception) {
            Log.e(TAG, "registerNoisyReceiver error: ${e.message}")
        }
    }

    // ==================== PLAYBACK CONTROL ====================

    fun playSingle(path: String) {
        Log.d(TAG, "playSingle: $path")
        try {
            currentPlaylist = mutableListOf(path)
            playbackOrder = mutableListOf(0)
            currentPlaybackIndex = 0
            loadAndPlay(path)
        } catch (e: Exception) {
            Log.e(TAG, "playSingle error: ${e.message}", e)
        }
    }

    fun playTracks(paths: List<String>, startIndex: Int = 0) {
        Log.d(TAG, "playTracks: ${paths.size} tracks, start=$startIndex")
        try {
            if (paths.isEmpty()) {
                Log.w(TAG, "playTracks called with empty list!")
                return
            }
            currentPlaylist = paths.toMutableList()
            rebuildPlaybackOrder()
            currentPlaybackIndex = startIndex.coerceIn(0, playbackOrder.size - 1)
            val effectiveIndex = playbackOrder[currentPlaybackIndex]
            val path = currentPlaylist.getOrNull(effectiveIndex) ?: return
            loadAndPlay(path)
        } catch (e: Exception) {
            Log.e(TAG, "playTracks error: ${e.message}", e)
        }
    }

    private fun loadAndPlay(path: String) {
        try {
            if (!requestAudioFocus()) {
                Log.w(TAG, "Audio focus not granted - playing anyway")
            }

            // Suporta tanto File paths quanto content:// URIs
            val uri = when {
                path.startsWith("content://") -> Uri.parse(path)
                else -> Uri.fromFile(java.io.File(path))
            }

            player?.setMediaItem(MediaItem.fromUri(uri))
            player?.prepare()
            player?.play()
            updateMetadata(path)
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "loadAndPlay error: ${e.message}", e)
        }
    }

    fun togglePlayPause() {
        try {
            player?.let {
                if (it.isPlaying) {
                    it.pause()
                } else {
                    if (requestAudioFocus()) it.play()
                }
                updateNotification()
            }
        } catch (e: Exception) {
            Log.e(TAG, "togglePlayPause error: ${e.message}")
        }
    }

    fun skipToNext() {
        try {
            if (currentPlaylist.isEmpty() || playbackOrder.isEmpty()) return

            if (repeatMode == RepeatMode.ONE) {
                player?.seekTo(0)
                player?.play()
                return
            }

            currentPlaybackIndex = (currentPlaybackIndex + 1) % playbackOrder.size
            val effectiveIndex = playbackOrder[currentPlaybackIndex]
            val path = currentPlaylist.getOrNull(effectiveIndex) ?: return
            loadAndPlay(path)
        } catch (e: Exception) {
            Log.e(TAG, "skipToNext error: ${e.message}", e)
        }
    }

    fun skipToPrevious() {
        try {
            if (currentPlaylist.isEmpty() || playbackOrder.isEmpty()) return

            if (repeatMode == RepeatMode.ONE) {
                player?.seekTo(0)
                player?.play()
                return
            }

            currentPlaybackIndex = if (currentPlaybackIndex > 0) {
                currentPlaybackIndex - 1
            } else {
                playbackOrder.size - 1
            }
            val effectiveIndex = playbackOrder[currentPlaybackIndex]
            val path = currentPlaylist.getOrNull(effectiveIndex) ?: return
            loadAndPlay(path)
        } catch (e: Exception) {
            Log.e(TAG, "skipToPrevious error: ${e.message}", e)
        }
    }

    private fun onTrackEnded() {
        try {
            if (currentPlaylist.isEmpty() || playbackOrder.isEmpty()) return

            if (repeatMode == RepeatMode.ONE) {
                player?.seekTo(0)
                player?.play()
                return
            }

            if (currentPlaybackIndex < playbackOrder.size - 1) {
                currentPlaybackIndex++
                val effectiveIndex = playbackOrder[currentPlaybackIndex]
                val path = currentPlaylist.getOrNull(effectiveIndex) ?: return
                loadAndPlay(path)
            } else if (repeatMode == RepeatMode.ALL) {
                currentPlaybackIndex = 0
                val effectiveIndex = playbackOrder[0]
                val path = currentPlaylist.getOrNull(effectiveIndex) ?: return
                loadAndPlay(path)
            } else {
                player?.pause()
                updateNotification()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onTrackEnded error: ${e.message}", e)
        }
    }

    fun seekTo(positionMs: Long) {
        try {
            player?.seekTo(positionMs)
        } catch (e: Exception) {
            Log.e(TAG, "seekTo error: ${e.message}")
        }
    }
    
private var seekRunnable: WeakRefRunnable<JammerPlaybackService>? = null

private fun debouncedSeekTo(positionMs: Long) {
    try {
        seekRunnable?.let { seekHandler.removeCallbacks(it) }
        seekRunnable = object : WeakRefRunnable<JammerPlaybackService>(this) {
            override fun runWithRef(service: JammerPlaybackService) {
                try {
                    service.player?.seekTo(positionMs)
                    service.updatePlaybackState(service.player?.isPlaying ?: false)
                    service.updateNotification()
                } catch (e: Exception) {
                    Log.e(TAG, "debouncedSeekTo error: ${e.message}")
                }
            }
        }
        seekHandler.postDelayed(seekRunnable!!, 300)
    } catch (e: Exception) {
        Log.e(TAG, "debouncedSeekTo setup error: ${e.message}")
    }
}

    fun stopPlayback() {
        try {
            player?.stop()
            player?.clearMediaItems()
            abandonAudioFocus()
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "stopPlayback error: ${e.message}")
        }
    }

    // ==================== SHUFFLE & REPEAT ====================

    fun setShuffle(enabled: Boolean) {
        try {
            isShuffleEnabled = enabled
            val oldEffective = playbackOrder.getOrNull(currentPlaybackIndex) ?: 0
            rebuildPlaybackOrder()
            currentPlaybackIndex = playbackOrder.indexOf(oldEffective).coerceAtLeast(0)
            Log.d(TAG, "Shuffle: $enabled, order=$playbackOrder")
        } catch (e: Exception) {
            Log.e(TAG, "setShuffle error: ${e.message}")
        }
    }

    fun isShuffleEnabled(): Boolean = isShuffleEnabled

    fun setRepeat(mode: RepeatMode) {
        try {
            repeatMode = mode
            player?.repeatMode = when (mode) {
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            Log.d(TAG, "Repeat: $mode")
        } catch (e: Exception) {
            Log.e(TAG, "setRepeat error: ${e.message}")
        }
    }

    fun getRepeatMode(): RepeatMode = repeatMode

    // ==================== QUEUE ====================

    fun addToQueue(path: String) {
        try {
            currentPlaylist.add(path)
            playbackOrder.add(currentPlaylist.size - 1)
            Log.d(TAG, "Added to queue: $path. Size: ${currentPlaylist.size}")
        } catch (e: Exception) {
            Log.e(TAG, "addToQueue error: ${e.message}")
        }
    }

    fun removeFromQueue(index: Int) {
        try {
            if (index < 0 || index >= currentPlaylist.size) return
            val removedOriginalIndex = playbackOrder.getOrNull(currentPlaybackIndex) ?: -1
            currentPlaylist.removeAt(index)
            rebuildPlaybackOrder()
            if (removedOriginalIndex == index) {
                if (currentPlaybackIndex >= playbackOrder.size) currentPlaybackIndex = 0
                if (playbackOrder.isNotEmpty()) {
                    val eff = playbackOrder[currentPlaybackIndex]
                    currentPlaylist.getOrNull(eff)?.let { loadAndPlay(it) }
                }
            } else {
                currentPlaybackIndex = currentPlaybackIndex.coerceIn(0, (playbackOrder.size - 1).coerceAtLeast(0))
            }
            Log.d(TAG, "Removed index $index. Size: ${currentPlaylist.size}")
        } catch (e: Exception) {
            Log.e(TAG, "removeFromQueue error: ${e.message}")
        }
    }

    fun clearQueue() {
        try {
            currentPlaylist.clear()
            playbackOrder.clear()
            currentPlaybackIndex = 0
            player?.stop()
            player?.clearMediaItems()
            abandonAudioFocus()
            updateNotification()
            Log.d(TAG, "Queue cleared")
        } catch (e: Exception) {
            Log.e(TAG, "clearQueue error: ${e.message}")
        }
    }

    fun getQueue(): List<String> = currentPlaylist.toList()
    fun getQueueSize(): Int = currentPlaylist.size

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        try {
            if (fromIndex == toIndex) return
            if (fromIndex < 0 || fromIndex >= currentPlaylist.size) return
            if (toIndex < 0 || toIndex >= currentPlaylist.size) return

            val item = currentPlaylist.removeAt(fromIndex)
            currentPlaylist.add(toIndex, item)
            rebuildPlaybackOrder()

            val currentOriginal = playbackOrder.getOrNull(currentPlaybackIndex) ?: 0
            when {
                fromIndex == currentOriginal -> {
                    currentPlaybackIndex = playbackOrder.indexOf(toIndex).coerceAtLeast(0)
                }
                else -> {
                    currentPlaybackIndex = playbackOrder.indexOf(currentOriginal).coerceAtLeast(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "moveQueueItem error: ${e.message}")
        }
    }

    // ==================== HELPERS ====================

    private fun rebuildPlaybackOrder() {
        playbackOrder = if (isShuffleEnabled) {
            (currentPlaylist.indices).shuffled().toMutableList()
        } else {
            (currentPlaylist.indices).toMutableList()
        }
    }

    fun getCurrentPosition(): Long = try { player?.currentPosition ?: 0L } catch (e: Exception) { 0L }
    fun getDuration(): Long = try { player?.duration ?: 0L } catch (e: Exception) { 0L }
    fun isPlaying(): Boolean = try { player?.isPlaying ?: false } catch (e: Exception) { false }
    fun getCurrentTrackPath(): String? = try {
        val eff = playbackOrder.getOrNull(currentPlaybackIndex)
        if (eff != null) currentPlaylist.getOrNull(eff) else null
    } catch (e: Exception) { null }
    fun getCurrentIndex(): Int = currentPlaybackIndex

    // ==================== METADATA & NOTIFICATION ====================

    private fun updateMetadata(path: String) {
        val retriever = MediaMetadataRetriever()
        try {
            when {
                path.startsWith("content://") -> {
                    val fd = contentResolver.openFileDescriptor(Uri.parse(path), "r")
                    fd?.use { retriever.setDataSource(it.fileDescriptor) }
                }
                else -> retriever.setDataSource(path)
            }

            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: java.io.File(path).nameWithoutExtension
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown Artist"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Unknown Album"
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

            mediaSession?.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, path)
                    .build()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Metadata error: ${e.message}")
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun updatePlaybackState(isPlaying: Boolean) {
        try {
            val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            val position = getCurrentPosition()
            mediaSession?.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(state, position, 1f)
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_STOP
                    )
                    .build()
            )
        } catch (e: Exception) {
            Log.e(TAG, "updatePlaybackState error: ${e.message}")
        }
    }

    private var lastNotificationUpdate = 0L
    
    private fun updateNotification() {
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdate < 2000) return // Rate limit: max 1x a cada 2s
        lastNotificationUpdate = now
        
        serviceScope.launch {
            try {
                val notification = buildNotification()
                startForegroundSafe(notification)
            } catch (e: Exception) {
                Log.e(TAG, "updateNotification failed: ${e.message}")
            }
        }
    }

    private fun startForegroundSafe(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForegroundSafe failed: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID, "Jammer Playback", NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Media playback controls"
                    setShowBadge(false)
                }
                val manager = getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)
            } catch (e: Exception) {
                Log.e(TAG, "createNotificationChannel error: ${e.message}")
            }
        }
    }

    private fun buildEmptyNotification(): Notification {
        return try {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Jammer")
                .setContentText("Ready to play")
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setSilent(true)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "buildEmptyNotification error: ${e.message}")
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Jammer")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
        }
    }

    private suspend fun buildNotification(): Notification {
        return try {
            val playPauseAction = if (player?.isPlaying == true) {
                NotificationCompat.Action(
                    R.drawable.ic_pause, "Pause",
                    createPendingIntent(ACTION_TOGGLE, 0)
                )
            } else {
                NotificationCompat.Action(
                    R.drawable.ic_play, "Play",
                    createPendingIntent(ACTION_TOGGLE, 0)
                )
            }

            val contentIntent = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val albumArt = try {
                withContext(Dispatchers.IO) {
                    getCurrentTrackPath()?.let { path ->
                        AlbumArtLoader.loadForNotification(applicationContext, path)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Album art load error: ${e.message}")
                null
            }

            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(
                    mediaSession?.controller?.metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: "Jammer"
                )
                .setContentText(
                    mediaSession?.controller?.metadata?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: "Unknown"
                )
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .addAction(R.drawable.ic_prev, "Previous", createPendingIntent(ACTION_SKIP_PREV, 1))
                .addAction(playPauseAction)
                .addAction(R.drawable.ic_next, "Next", createPendingIntent(ACTION_SKIP_NEXT, 2))
                .setStyle(
                    androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession?.sessionToken)
                        .setShowActionsInCompactView(1)
                )

            if (albumArt != null) {
                builder.setLargeIcon(albumArt)
            }

            builder.build()
        } catch (e: Exception) {
            Log.e(TAG, "buildNotification error: ${e.message}")
            buildEmptyNotification()
        }
    }

    private fun createPendingIntent(action: String, requestCode: Int): PendingIntent {
        return PendingIntent.getService(
            this, requestCode,
            Intent(this, JammerPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

private fun startPositionUpdates() {
    try {
        stopPositionUpdates()
        positionRunnable = object : WeakRefRunnable<JammerPlaybackService>(this) {
            override fun runWithRef(service: JammerPlaybackService) {
                try {
                    service.player?.let {
                        service.updatePlaybackState(it.isPlaying)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Position update error: ${e.message}")
                }
                // Só agenda próximo se ainda estiver tocando
                if (service.player?.isPlaying == true) {
                    service.positionHandler.postDelayed(this, 1000)
                }
            }
        }
        positionHandler.postDelayed(positionRunnable!!, 1000)
    } catch (e: Exception) {
        Log.e(TAG, "startPositionUpdates error: ${e.message}")
    }
}

private fun stopPositionUpdates() {
    try {
        positionRunnable?.let { positionHandler.removeCallbacks(it) }
        positionRunnable = null
    } catch (e: Exception) {
        Log.e(TAG, "stopPositionUpdates error: ${e.message}")
    }
}

    // ==================== MEDIA BROWSER ====================

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        return BrowserRoot("root", null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.sendResult(mutableListOf())
    }

        override fun onDestroy() {
        serviceJob.cancel()
        try {
            positionRunnable?.let { positionHandler.removeCallbacks(it) }
            seekRunnable?.let { seekHandler.removeCallbacks(it) }
            abandonAudioFocus()
            noisyReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy cleanup error: ${e.message}")
        }
        try {
    mediaSessionConnector?.setPlayer(null)
} catch (e: Exception) {
    Log.e(TAG, "MediaSessionConnector cleanup error: ${e.message}")
}
        try {
            mediaSession?.release()
        } catch (e: Exception) {
            Log.e(TAG, "MediaSession release error: ${e.message}")
        }
        try {
    playerListener?.let { player?.removeListener(it) }
    playerListener = null
} catch (e: Exception) {
    Log.e(TAG, "PlayerListener cleanup error: ${e.message}")
}
        try {
            player?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Player release error: ${e.message}")
        }
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }
}

package android.kimyona.jammer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Binder
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
import android.kimyona.jammer.R
import android.kimyona.jammer.core.media.AlbumArtLoader
import android.kimyona.jammer.ui.MainActivity
import java.io.File
import java.util.Collections

/**
 * Jammer Playback Service - Evoluído do MVP.
 *
 * Features completas:
 * - Shuffle mode (aleatório)
 * - Repeat modes (none / all / one)
 * - Queue funcional (add, remove, reorder, clear)
 * - Notificações com capa de álbum real
 * - MediaSession completa
 * - Foreground service robusto
 */
class JammerPlaybackService : MediaBrowserServiceCompat() {

    private val TAG = "JammerService"
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "jammer_playback"

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSessionCompat? = null
    private var mediaSessionConnector: MediaSessionConnector? = null
    private val binder = LocalBinder()

    private val positionHandler = Handler(Looper.getMainLooper())
    private var positionRunnable: Runnable? = null

    // === QUEUE ===
    private var currentPlaylist = mutableListOf<String>()
    private var currentIndex = 0
    private var shuffledOrder = mutableListOf<Int>()

    // === MODES ===
    enum class RepeatMode { NONE, ALL, ONE }
    private var repeatMode = RepeatMode.NONE
    private var isShuffleEnabled = false

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

    inner class LocalBinder : Binder() {
        fun getService(): JammerPlaybackService = this@JammerPlaybackService
    }

    // ==================== LIFECYCLE ====================

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate started")

        createNotificationChannel()

        try {
            val notification = buildEmptyNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "startForeground() called successfully")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground() failed: ${e.message}", e)
            stopSelf()
            return
        }

        initializePlayer()
        initializeMediaSession()
        startPositionUpdates()

        Log.d(TAG, "Service onCreate complete")
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")

        if (player?.isPlaying == true) {
            updateNotification()
        }

        MediaButtonReceiver.handleIntent(mediaSession, intent)

        when (intent?.action) {
            ACTION_PLAY_SINGLE -> {
                val path = intent.getStringExtra("path") ?: return START_NOT_STICKY
                playSingle(path)
            }
            ACTION_PLAY_LIST -> {
                val paths = intent.getStringArrayListExtra("paths") ?: return START_NOT_STICKY
                val startIndex = intent.getIntExtra("startIndex", 0)
                playTracks(paths, startIndex)
            }
            ACTION_TOGGLE -> togglePlayPause()
            ACTION_SKIP_NEXT -> skipToNext()
            ACTION_SKIP_PREV -> skipToPrevious()
            ACTION_SEEK -> {
                val pos = intent.getLongExtra("positionMs", 0)
                seekTo(pos)
            }
            ACTION_ADD_TO_QUEUE -> {
                val path = intent.getStringExtra("path") ?: return START_NOT_STICKY
                addToQueue(path)
            }
            ACTION_CLEAR_QUEUE -> clearQueue()
            ACTION_SET_SHUFFLE -> {
                val enabled = intent.getBooleanExtra("enabled", false)
                setShuffle(enabled)
            }
            ACTION_SET_REPEAT -> {
                val mode = intent.getSerializableExtra("mode") as? RepeatMode ?: RepeatMode.NONE
                setRepeat(mode)
            }
        }

        return START_NOT_STICKY
    }

    // ==================== PLAYER INIT ====================

    private fun initializePlayer() {
        Log.d(TAG, "Initializing ExoPlayer...")
        player = ExoPlayer.Builder(this).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> updateNotification()
                        Player.STATE_ENDED -> onTrackEnded()
                    }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateNotification()
                }
            })
        }
        Log.d(TAG, "ExoPlayer initialized")
    }

    private fun initializeMediaSession() {
        Log.d(TAG, "Initializing MediaSession...")
        mediaSession = MediaSessionCompat(this, TAG).apply {
            isActive = true
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { togglePlayPause() }
                override fun onPause() { togglePlayPause() }
                override fun onSkipToNext() { skipToNext() }
                override fun onSkipToPrevious() { skipToPrevious() }
                override fun onSeekTo(pos: Long) { seekTo(pos) }
            })
        }

        mediaSessionConnector = MediaSessionConnector(mediaSession!!).apply {
            setPlayer(player!!)
        }

        sessionToken = mediaSession!!.sessionToken
        Log.d(TAG, "MediaSession initialized")
    }

    // ==================== PLAYBACK CONTROL ====================

    fun playSingle(path: String) {
        Log.d(TAG, "playSingle: $path")
        currentPlaylist = mutableListOf(path)
        currentIndex = 0
        rebuildShuffledOrder()
        loadAndPlay(path)
    }

    fun playTracks(paths: List<String>, startIndex: Int = 0) {
        Log.d(TAG, "playTracks: ${paths.size} tracks, start=$startIndex")
        if (paths.isEmpty()) {
            Log.w(TAG, "playTracks called with empty list!")
            return
        }
        currentPlaylist = paths.toMutableList()
        currentIndex = startIndex.coerceIn(0, paths.size - 1)
        rebuildShuffledOrder()
        loadAndPlay(currentPlaylist[getEffectiveIndex()])
    }

    private fun loadAndPlay(path: String) {
        player?.setMediaItem(MediaItem.fromUri(path))
        player?.prepare()
        player?.play()
        updateMetadata(path)
        updateNotification()
    }

    fun togglePlayPause() {
        player?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    fun skipToNext() {
        if (currentPlaylist.isEmpty()) return

        if (repeatMode == RepeatMode.ONE) {
            player?.seekTo(0)
            player?.play()
            return
        }

        currentIndex = if (isShuffleEnabled) {
            val currentShuffledPos = shuffledOrder.indexOf(currentIndex)
            val nextShuffledPos = (currentShuffledPos + 1) % shuffledOrder.size
            shuffledOrder[nextShuffledPos]
        } else {
            (currentIndex + 1) % currentPlaylist.size
        }

        val path = currentPlaylist[getEffectiveIndex()]
        loadAndPlay(path)
    }

    fun skipToPrevious() {
        if (currentPlaylist.isEmpty()) return

        if (repeatMode == RepeatMode.ONE) {
            player?.seekTo(0)
            player?.play()
            return
        }

        currentIndex = if (isShuffleEnabled) {
            val currentShuffledPos = shuffledOrder.indexOf(currentIndex)
            val prevShuffledPos = if (currentShuffledPos > 0) currentShuffledPos - 1 else shuffledOrder.size - 1
            shuffledOrder[prevShuffledPos]
        } else {
            if (currentIndex > 0) currentIndex - 1 else currentPlaylist.size - 1
        }

        val path = currentPlaylist[getEffectiveIndex()]
        loadAndPlay(path)
    }

    private fun onTrackEnded() {
        if (currentPlaylist.isEmpty()) return

        if (repeatMode == RepeatMode.ONE) {
            player?.seekTo(0)
            player?.play()
            return
        }

        if (isShuffleEnabled) {
            val currentShuffledPos = shuffledOrder.indexOf(currentIndex)
            if (currentShuffledPos < shuffledOrder.size - 1) {
                currentIndex = shuffledOrder[currentShuffledPos + 1]
                loadAndPlay(currentPlaylist[getEffectiveIndex()])
            } else if (repeatMode == RepeatMode.ALL) {
                currentIndex = shuffledOrder[0]
                loadAndPlay(currentPlaylist[getEffectiveIndex()])
            }
        } else {
            if (currentIndex < currentPlaylist.size - 1) {
                currentIndex++
                loadAndPlay(currentPlaylist[getEffectiveIndex()])
            } else if (repeatMode == RepeatMode.ALL) {
                currentIndex = 0
                loadAndPlay(currentPlaylist[getEffectiveIndex()])
            }
        }
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    // ==================== SHUFFLE & REPEAT ====================

    fun setShuffle(enabled: Boolean) {
        isShuffleEnabled = enabled
        if (enabled) {
            rebuildShuffledOrder()
            // Move current track to start of shuffle
            val currentEffective = shuffledOrder.indexOf(currentIndex)
            if (currentEffective > 0) {
                Collections.swap(shuffledOrder, 0, currentEffective)
            }
        }
        Log.d(TAG, "Shuffle: $enabled")
    }

    fun isShuffleEnabled(): Boolean = isShuffleEnabled

    fun setRepeat(mode: RepeatMode) {
        repeatMode = mode
        player?.repeatMode = when (mode) {
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        Log.d(TAG, "Repeat: $mode")
    }

    fun getRepeatMode(): RepeatMode = repeatMode

    // ==================== QUEUE ====================

    fun addToQueue(path: String) {
        currentPlaylist.add(path)
        if (isShuffleEnabled) {
            shuffledOrder.add(currentPlaylist.size - 1)
        }
        Log.d(TAG, "Added to queue: $path. Queue size: ${currentPlaylist.size}")
    }

    fun removeFromQueue(index: Int) {
        if (index < 0 || index >= currentPlaylist.size) return
        if (index == currentIndex) {
            // Se remove a track atual, pula pra próxima
            skipToNext()
        }
        currentPlaylist.removeAt(index)
        if (currentIndex >= index && currentIndex > 0) {
            currentIndex--
        }
        rebuildShuffledOrder()
        Log.d(TAG, "Removed from queue at index $index. Queue size: ${currentPlaylist.size}")
    }

    fun clearQueue() {
        currentPlaylist.clear()
        shuffledOrder.clear()
        currentIndex = 0
        player?.stop()
        player?.clearMediaItems()
        Log.d(TAG, "Queue cleared")
    }

    fun getQueue(): List<String> = currentPlaylist.toList()
    fun getQueueSize(): Int = currentPlaylist.size

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        if (fromIndex < 0 || fromIndex >= currentPlaylist.size) return
        if (toIndex < 0 || toIndex >= currentPlaylist.size) return

        val item = currentPlaylist.removeAt(fromIndex)
        currentPlaylist.add(toIndex, item)

        // Ajusta currentIndex
        when {
            fromIndex == currentIndex -> currentIndex = toIndex
            fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex--
            fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex++
        }

        rebuildShuffledOrder()
    }

    // ==================== HELPERS ====================

    private fun rebuildShuffledOrder() {
        shuffledOrder = (0 until currentPlaylist.size).shuffled().toMutableList()
    }

    private fun getEffectiveIndex(): Int {
        return if (isShuffleEnabled && shuffledOrder.isNotEmpty()) {
            shuffledOrder[0].coerceIn(0, currentPlaylist.size - 1)
        } else {
            currentIndex.coerceIn(0, currentPlaylist.size - 1)
        }
    }

    fun getCurrentPosition(): Long = player?.currentPosition ?: 0L
    fun getDuration(): Long = player?.duration ?: 0L
    fun isPlaying(): Boolean = player?.isPlaying ?: false
    fun getCurrentTrackPath(): String? = currentPlaylist.getOrNull(currentIndex)
    fun getCurrentIndex(): Int = currentIndex

    // ==================== METADATA & NOTIFICATION ====================

    private fun updateMetadata(path: String) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: File(path).nameWithoutExtension
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: "Unknown Artist"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?: "Unknown Album"
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            mediaSession?.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                    .build()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Metadata error: ${e.message}")
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun updateNotification() {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateNotification failed: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Jammer Playback", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Media playback controls"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildEmptyNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jammer")
            .setContentText("Ready to play")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun buildNotification(): Notification {
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

        // Carrega capa real para a notificação
        val albumArt = getCurrentTrackPath()?.let { path ->
            AlbumArtLoader.loadForNotification(this, path)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(
                mediaSession?.controller?.metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE)
                    ?: "Jammer"
            )
            .setContentText(
                mediaSession?.controller?.metadata?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST)
                    ?: "Unknown"
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

        // Adiciona capa de álbum na notificação se disponível
        if (albumArt != null) {
            builder.setLargeIcon(albumArt)
        }

        return builder.build()
    }

    private fun createPendingIntent(action: String, requestCode: Int): PendingIntent {
        return PendingIntent.getService(
            this, requestCode,
            Intent(this, JammerPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun startPositionUpdates() {
        positionRunnable = object : Runnable {
            override fun run() {
                player?.let {
                    mediaSession?.setPlaybackState(
                        PlaybackStateCompat.Builder()
                            .setState(
                                if (it.isPlaying) PlaybackStateCompat.STATE_PLAYING
                                else PlaybackStateCompat.STATE_PAUSED,
                                it.currentPosition, 1f
                            )
                            .setActions(
                                PlaybackStateCompat.ACTION_PLAY or
                                PlaybackStateCompat.ACTION_PAUSE or
                                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                                PlaybackStateCompat.ACTION_SEEK_TO
                            )
                            .build()
                    )
                }
                positionHandler.postDelayed(this, 1000)
            }
        }
        positionHandler.postDelayed(positionRunnable!!, 1000)
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
        super.onDestroy()
        positionRunnable?.let { positionHandler.removeCallbacks(it) }
        mediaSession?.release()
        player?.release()
        Log.d(TAG, "Service destroyed")
    }
}

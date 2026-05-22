package android.kimyona.jammer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.Binder
import android.os.Build
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
import android.kimyona.jammer.ui.MainActivity
import java.io.File

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
    private var currentPlaylist = mutableListOf<String>()
    private var currentIndex = 0

    companion object {
        const val ACTION_PLAY_SINGLE = "android.kimyona.jammer.PLAY_SINGLE"
        const val ACTION_PLAY_LIST = "android.kimyona.jammer.PLAY_LIST"
        const val ACTION_TOGGLE = "android.kimyona.jammer.TOGGLE"
        const val ACTION_SKIP_NEXT = "android.kimyona.jammer.SKIP_NEXT"
        const val ACTION_SKIP_PREV = "android.kimyona.jammer.SKIP_PREV"
        const val ACTION_SEEK = "android.kimyona.jammer.SEEK"
    }

    inner class LocalBinder : Binder() {
        fun getService(): JammerPlaybackService = this@JammerPlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate started")

        // CRITICAL: Create notification channel FIRST
        createNotificationChannel()

        // CRITICAL: Call startForeground() IMMEDIATELY before any heavy work
        // This prevents ForegroundServiceDidNotStartInTimeException
        try {
            val notification = buildEmptyNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "startForeground() called successfully")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground() failed: ${e.message}", e)
            // If startForeground fails, we can't be a foreground service
            // Stop the service to avoid crash
            stopSelf()
            return
        }

        // Now do the heavy initialization
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

        // Ensure we're in foreground state
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
        }

        return START_NOT_STICKY
    }

    private fun initializePlayer() {
        Log.d(TAG, "Initializing ExoPlayer...")
        player = ExoPlayer.Builder(this).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> updateNotification()
                        Player.STATE_ENDED -> skipToNext()
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
                override fun onPlay() {
                    togglePlayPause()
                }

                override fun onPause() {
                    togglePlayPause()
                }

                override fun onSkipToNext() {
                    skipToNext()
                }

                override fun onSkipToPrevious() {
                    skipToPrevious()
                }

                override fun onSeekTo(pos: Long) {
                    seekTo(pos)
                }
            })
        }

        mediaSessionConnector = MediaSessionConnector(mediaSession!!).apply {
            setPlayer(player!!)
        }

        sessionToken = mediaSession!!.sessionToken
        Log.d(TAG, "MediaSession initialized")
    }

    fun playSingle(path: String) {
        Log.d(TAG, "playSingle: $path")
        currentPlaylist = mutableListOf(path)
        currentIndex = 0
        player?.setMediaItem(MediaItem.fromUri(path))
        player?.prepare()
        player?.play()
        updateMetadata(path)
        updateNotification()
    }

    fun playTracks(paths: List<String>, startIndex: Int = 0) {
        Log.d(TAG, "playTracks: ${paths.size} tracks, start=$startIndex")
        if (paths.isEmpty()) {
            Log.w(TAG, "playTracks called with empty list!")
            return
        }
        currentPlaylist = paths.toMutableList()
        currentIndex = startIndex.coerceIn(0, paths.size - 1)

        val mediaItems = paths.map { MediaItem.fromUri(it) }
        player?.setMediaItems(mediaItems)
        player?.prepare()
        player?.seekTo(currentIndex, 0)
        player?.play()

        updateMetadata(paths[currentIndex])
        updateNotification()
    }

    fun togglePlayPause() {
        player?.let {
            if (it.isPlaying) {
                it.pause()
            } else {
                it.play()
            }
        }
    }

    fun skipToNext() {
        if (currentPlaylist.isEmpty()) return
        currentIndex = (currentIndex + 1) % currentPlaylist.size
        player?.seekTo(currentIndex, 0)
        player?.play()
        updateMetadata(currentPlaylist[currentIndex])
        updateNotification()
    }

    fun skipToPrevious() {
        if (currentPlaylist.isEmpty()) return
        currentIndex = if (currentIndex > 0) currentIndex - 1 else currentPlaylist.size - 1
        player?.seekTo(currentIndex, 0)
        player?.play()
        updateMetadata(currentPlaylist[currentIndex])
        updateNotification()
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    fun getCurrentPosition(): Long = player?.currentPosition ?: 0L
    fun getDuration(): Long = player?.duration ?: 0L
    fun isPlaying(): Boolean = player?.isPlaying ?: false
    fun getCurrentTrackPath(): String? = currentPlaylist.getOrNull(currentIndex)

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
            retriever.release()
        }
    }

    private fun updateNotification() {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
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
                CHANNEL_ID,
                "Jammer Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Media playback controls"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        }
    }

    private fun buildEmptyNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jammer")
            .setContentText("Ready to play")
            .setSmallIcon(R.drawable.ic_music_note)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun buildNotification(): Notification {
        val playPauseAction = if (player?.isPlaying == true) {
            NotificationCompat.Action(
                R.drawable.ic_pause,
                "Pause",
                PendingIntent.getService(
                    this, 0,
                    Intent(this, JammerPlaybackService::class.java).setAction(ACTION_TOGGLE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_play,
                "Play",
                PendingIntent.getService(
                    this, 0,
                    Intent(this, JammerPlaybackService::class.java).setAction(ACTION_TOGGLE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(mediaSession?.controller?.metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: "Jammer")
            .setContentText(mediaSession?.controller?.metadata?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: "Unknown")
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(R.drawable.ic_prev, "Previous", PendingIntent.getService(this, 1, Intent(this, JammerPlaybackService::class.java).setAction(ACTION_SKIP_PREV), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(playPauseAction)
            .addAction(R.drawable.ic_next, "Next", PendingIntent.getService(this, 2, Intent(this, JammerPlaybackService::class.java).setAction(ACTION_SKIP_NEXT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession?.sessionToken)
                .setShowActionsInCompactView(1))
            .build()
    }

    private fun startPositionUpdates() {
        positionRunnable = object : Runnable {
            override fun run() {
                player?.let {
                    mediaSession?.setPlaybackState(
                        PlaybackStateCompat.Builder()
                            .setState(
                                if (it.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
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

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        return BrowserRoot("root", null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
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

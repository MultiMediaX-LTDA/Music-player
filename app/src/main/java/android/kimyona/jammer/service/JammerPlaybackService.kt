package android.kimyona.jammer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.IBinder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator
import android.kimyona.jammer.R
import android.kimyona.jammer.ui.MainActivity
import kotlinx.coroutines.*

/**
 * Serviço de playback em foreground.
 * CORRIGIDO v3: MediaMetadata com .toString(), ic_music_note removido.
 */
class JammerPlaybackService : MediaBrowserServiceCompat() {

    companion object {
        const val CHANNEL_ID = "jammer_playback_channel"
        const val NOTIFICATION_ID = 1
        const val TAG = "JammerService"
        const val ACTION_PLAY_SINGLE = "PLAY_SINGLE"
        const val ACTION_PLAY_LIST = "PLAY_LIST"
        const val ACTION_TOGGLE = "TOGGLE"
        const val ACTION_SKIP_NEXT = "SKIP_NEXT"
        const val ACTION_SKIP_PREV = "SKIP_PREVIOUS"
        const val ACTION_SEEK = "SEEK"
    }

    inner class LocalBinder : Binder() {
        fun getService(): JammerPlaybackService = this@JammerPlaybackService
    }
    private val binder = LocalBinder()

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var mediaSessionConnector: MediaSessionConnector
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var currentQueue: List<MediaItem> = emptyList()
    private var currentIndex: Int = 0

    private val positionHandler = Handler(Looper.getMainLooper())
    private var positionRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        createNotificationChannel()
        initializePlayer()
        initializeMediaSession()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildEmptyNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, buildEmptyNotification())
        }
        startPositionUpdates()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                updateMediaSessionPlaybackState()
                updateNotification()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateMediaSessionPlaybackState()
                updateNotification()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Player error: ${error.message}")
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentIndex = exoPlayer.currentMediaItemIndex
                updateMediaSessionMetadata()
                updateNotification()
            }
        })
    }

    private fun initializeMediaSession() {
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSessionCompat(this, TAG).apply {
            setSessionActivity(sessionActivityPendingIntent)
            isActive = true
        }

        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                exoPlayer.play()
            }
            override fun onPause() {
                exoPlayer.pause()
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

        mediaSessionConnector = MediaSessionConnector(mediaSession).apply {
            setPlayer(exoPlayer)
            setQueueNavigator(object : TimelineQueueNavigator(mediaSession) {
                override fun getMediaDescription(
                    player: Player,
                    windowIndex: Int
                ): MediaDescriptionCompat {
                    return currentQueue.getOrNull(windowIndex)?.let {
                        MediaDescriptionCompat.Builder()
                            .setMediaId(it.mediaId)
                            .setTitle(it.mediaMetadata.title?.toString())
                            .setSubtitle(it.mediaMetadata.artist?.toString())
                            .build()
                    } ?: MediaDescriptionCompat.Builder().setTitle("Jammer").build()
                }
            })
        }

        sessionToken = mediaSession.sessionToken
    }

    private fun updateMediaSessionPlaybackState() {
        val state = if (exoPlayer.isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO

        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, exoPlayer.currentPosition, 1.0f)
            .setActions(actions)
            .build()

        mediaSession.setPlaybackState(playbackState)
    }

    private fun updateMediaSessionMetadata() {
        val currentItem = currentQueue.getOrNull(currentIndex)
        val metadata = android.support.v4.media.MediaMetadataCompat.Builder()
            .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE,
                currentItem?.mediaMetadata?.title?.toString() ?: "Unknown")
            .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST,
                currentItem?.mediaMetadata?.artist?.toString() ?: "Unknown")
            .putLong(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION,
                exoPlayer.duration)
            .build()
        mediaSession.setMetadata(metadata)
    }

    private fun startPositionUpdates() {
        positionRunnable = object : Runnable {
            override fun run() {
                if (exoPlayer.isPlaying) {
                    updateMediaSessionPlaybackState()
                }
                positionHandler.postDelayed(this, 1000)
            }
        }
        positionHandler.postDelayed(positionRunnable!!, 1000)
    }

    fun playTracks(paths: List<String>, startIndex: Int = 0) {
        currentQueue = paths.map { path ->
            MediaItem.Builder()
                .setUri(path)
                .setMediaId(path)
                .setMediaMetadata(
                    com.google.android.exoplayer2.MediaMetadata.Builder()
                        .setTitle(path.substringAfterLast('/'))
                        .build()
                )
                .build()
        }

        currentIndex = startIndex
        exoPlayer.setMediaItems(currentQueue, startIndex, 0)
        exoPlayer.prepare()
        exoPlayer.play()

        updateMediaSessionMetadata()
        updateMediaSessionPlaybackState()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    fun playSingle(path: String) {
        playTracks(listOf(path), 0)
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            exoPlayer.play()
        }
    }

    fun skipToNext() {
        if (currentIndex < currentQueue.size - 1) {
            currentIndex++
            exoPlayer.seekToNextMediaItem()
        }
    }

    fun skipToPrevious() {
        if (currentIndex > 0) {
            currentIndex--
            exoPlayer.seekToPreviousMediaItem()
        }
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
    }

    fun getCurrentPosition(): Long = exoPlayer.currentPosition
    fun getDuration(): Long = if (exoPlayer.duration > 0) exoPlayer.duration else 0L
    fun isPlaying(): Boolean = exoPlayer.isPlaying
    fun getCurrentTrackPath(): String? = currentQueue.getOrNull(currentIndex)?.mediaId

    private fun updateNotification() {
        val notification = buildNotification()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun buildEmptyNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jammer")
            .setContentText("Ready to play")
            .setSmallIcon(android.R.drawable.ic_media_play)  // Ícone do sistema
            .setOngoing(true)
            .build()
    }

    private fun buildNotification(): Notification {
        val isPlaying = exoPlayer.isPlaying

        val prevAction = NotificationCompat.Action(
            android.R.drawable.ic_media_previous,
            "Previous",
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                this,
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
        )

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                "Pause",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    PlaybackStateCompat.ACTION_PAUSE
                )
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                "Play",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    PlaybackStateCompat.ACTION_PLAY
                )
            )
        }

        val nextAction = NotificationCompat.Action(
            android.R.drawable.ic_media_next,
            "Next",
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                this,
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            )
        )

        val currentItem = currentQueue.getOrNull(currentIndex)
        val title = currentItem?.mediaMetadata?.title?.toString() ?: "Jammer"
        val artist = currentItem?.mediaMetadata?.artist?.toString() ?: "Unknown Artist"

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(android.R.drawable.ic_media_play)  // Ícone do sistema
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.album_placeholder_vinyl))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(false)
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jammer Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Music playback controls"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        positionRunnable?.let { positionHandler.removeCallbacks(it) }
        exoPlayer.release()
        mediaSession.release()
        serviceJob.cancel()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        return BrowserRoot("root", null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        result.sendResult(mutableListOf())
    }
}

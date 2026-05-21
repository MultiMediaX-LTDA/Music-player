package android.kimyona.jammer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
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
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import android.kimyona.jammer.R
import android.kimyona.jammer.ui.MainActivity
import kotlinx.coroutines.*

/**
 * Serviço de playback em foreground.
 * Roda em background mesmo sem Activity ativa.
 * Notificação persistente com controles de mídia.
 */
class JammerPlaybackService : MediaBrowserServiceCompat() {

    companion object {
        const val CHANNEL_ID = "jammer_playback_channel"
        const val NOTIFICATION_ID = 1
        const val TAG = "JammerService"
    }

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var mediaSessionConnector: MediaSessionConnector
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    // Estado atual
    private var currentQueue: List<MediaItem> = emptyList()
    private var currentIndex: Int = 0

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        createNotificationChannel()
        initializePlayer()
        initializeMediaSession()
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
                updateNotification()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateNotification()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Player error: ${error.message}")
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
                            .setTitle(it.mediaMetadata.title)
                            .setSubtitle(it.mediaMetadata.artist)
                            .build()
                    } ?: MediaDescriptionCompat.Builder().setTitle("Jammer").build()
                }
            })
        }

        sessionToken = mediaSession.sessionToken
    }

    /**
     * Inicia playback com uma lista de paths.
     * Chamado pela Activity ou por intents externos.
     */
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

        startForeground(NOTIFICATION_ID, buildNotification())
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
    fun getDuration(): Long = exoPlayer.duration
    fun isPlaying(): Boolean = exoPlayer.isPlaying

    /**
     * Atualiza a notificação com estado atual.
     */
    private fun updateNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val playPauseAction = if (exoPlayer.isPlaying) {
            NotificationCompat.Action(
                R.drawable.ic_pause,
                "Pause",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    PlaybackStateCompat.ACTION_PAUSE
                )
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_play,
                "Play",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    PlaybackStateCompat.ACTION_PLAY
                )
            )
        }

        val currentItem = currentQueue.getOrNull(currentIndex)
        val title = currentItem?.mediaMetadata?.title ?: "Jammer"
        val artist = currentItem?.mediaMetadata?.artist ?: "Unknown"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.album_placeholder))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                R.drawable.ic_prev,
                "Previous",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
            )
            .addAction(playPauseAction)
            .addAction(
                R.drawable.ic_next,
                "Next",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                )
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(1, 2)
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jammer Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls for Jammer music playback"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    // --- MediaBrowserServiceCompat required methods ---

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot("root", null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        // TODO: expor playlists/folders pro Android Auto / Wear OS
        result.sendResult(mutableListOf())
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaSession.release()
        exoPlayer.release()
        serviceJob.cancel()
        Log.d(TAG, "Service destroyed")
    }
}

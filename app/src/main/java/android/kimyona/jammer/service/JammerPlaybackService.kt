package android.kimyona.jammer.service

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import android.kimyona.jammer.ui.MainActivity

class JammerPlaybackService : MediaLibraryService() {

    private val TAG = "JammerService"

    private var player: ExoPlayer? = null
    private var mediaLibrarySession: MediaLibrarySession? = null

    private var currentPlaylist = mutableListOf<String>()
    private var playbackOrder = mutableListOf<Int>()
    private var currentPlaybackIndex = 0

    enum class RepeatMode { NONE, ALL, ONE }
    private var repeatMode = RepeatMode.NONE
    private var isShuffleEnabled = false

    companion object {
        const val ACTION_SET_SHUFFLE = "android.kimyona.jammer.SET_SHUFFLE"
        const val ACTION_SET_REPEAT = "android.kimyona.jammer.SET_REPEAT"
        const val ACTION_CLEAR_QUEUE = "android.kimyona.jammer.CLEAR_QUEUE"
        const val ACTION_PLAY_SINGLE = "android.kimyona.jammer.PLAY_SINGLE"
        const val EXTRA_PATH = "path"
    }

    // LIFECYCLE

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        if (intent?.action == ACTION_PLAY_SINGLE) {
            val path = intent.getStringExtra(EXTRA_PATH)
            if (path != null) {
                playSingle(path)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

override fun onCreate() {
    super.onCreate()
    Log.d(TAG, "onCreate")

    player = ExoPlayer.Builder(this)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    // Adiciona listener para detectar fim da track
    player?.addListener(object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                onTrackEnded()
            }
            hasAudioFocus
        } catch (e: Exception) {
            Log.e(TAG, "requestAudioFocus error: ${e.message}")
            false
        }
    })

    val sessionCallback = JammerSessionCallback()

    mediaLibrarySession = MediaLibrarySession.Builder(this, player!!, sessionCallback)
        .setSessionActivity(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()
}

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        mediaLibrarySession?.run {
            player?.release()
            release()
        }
        mediaLibrarySession = null
        player = null
        super.onDestroy()
    }

    // PLAYBACK CONTROL

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
            val uri = when {
                path.startsWith("content://") -> Uri.parse(path)
                else -> Uri.fromFile(java.io.File(path))
            }
            player?.setMediaItem(MediaItem.fromUri(uri))
            player?.prepare()
            player?.play()
        } catch (e: Exception) {
            Log.e(TAG, "loadAndPlay error: ${e.message}", e)
        }
    }

    fun togglePlayPause() {
        try {
            player?.let {
                if (it.isPlaying) it.pause() else it.play()
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

    fun stopPlayback() {
        try {
            player?.stop()
            player?.clearMediaItems()
        } catch (e: Exception) {
            Log.e(TAG, "stopPlayback error: ${e.message}")
        }
    }

    // SHUFFLE & REPEAT

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
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                RepeatMode.NONE -> Player.REPEAT_MODE_OFF
            }
            Log.d(TAG, "Repeat: $mode")
        } catch (e: Exception) {
            Log.e(TAG, "setRepeat error: ${e.message}")
        }
    }

    fun getRepeatMode(): RepeatMode = repeatMode

    // QUEUE

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

    // HELPERS

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

    // SESSION CALLBACK

    private inner class JammerSessionCallback : MediaLibrarySession.Callback {

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            val updatedItems = mediaItems.map { item ->
                val path = item.requestMetadata.mediaUri?.toString()
                    ?: item.localConfiguration?.uri?.toString()
                    ?: item.mediaId

                item.buildUpon()
                    .setUri(path)
                    .setMediaId(path)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(item.mediaMetadata.title ?: "Unknown")
                            .setArtist(item.mediaMetadata.artist ?: "Unknown Artist")
                            .setAlbumTitle(item.mediaMetadata.albumTitle)
                            .build()
                    )
                    .build()
            }
            return Futures.immediateFuture(updatedItems)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Jammer Library")
                        .setIsBrowsable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = MediaItem.Builder()
                .setMediaId(mediaId)
                .setUri(mediaId)
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_SET_SHUFFLE, Bundle.EMPTY))
                .add(SessionCommand(ACTION_SET_REPEAT, Bundle.EMPTY))
                .add(SessionCommand(ACTION_CLEAR_QUEUE, Bundle.EMPTY))
                .add(SessionCommand("ACTION_ADD_TO_QUEUE", Bundle.EMPTY))
                .build()

            return MediaSession.ConnectionResult.accept(
                sessionCommands,
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_SET_SHUFFLE -> {
                    val enabled = args.getBoolean("enabled", false)
                    setShuffle(enabled)
                }
                ACTION_SET_REPEAT -> {
                    val modeOrdinal = args.getInt("mode", RepeatMode.NONE.ordinal)
                    val mode = RepeatMode.values()[modeOrdinal]
                    setRepeat(mode)
                }
                ACTION_CLEAR_QUEUE -> {
                    clearQueue()
                }
                "ACTION_ADD_TO_QUEUE" -> {
                    val path = args.getString("path")
                    if (path != null) {
                        addToQueue(path)
                    }
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }
}
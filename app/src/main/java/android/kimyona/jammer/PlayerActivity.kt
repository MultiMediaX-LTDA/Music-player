package android.kimyona.jammer

import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var currentTime: TextView
    private lateinit var totalTime: TextView
    private lateinit var btnPlayPause: Button
    private var isPlaying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        titleText = findViewById<TextView>(R.id.playerTitle)
        artistText = findViewById<TextView>(R.id.playerArtist)
        seekBar = findViewById<SeekBar>(R.id.playerSeekBar)
        currentTime = findViewById<TextView>(R.id.playerCurrentTime)
        totalTime = findViewById<TextView>(R.id.playerTotalTime)
        btnPlayPause = findViewById<Button>(R.id.btnPlayPause)

        val path = intent.getStringExtra("track_path") ?: ""
        val title = intent.getStringExtra("track_title") ?: "Unknown"
        val artist = intent.getStringExtra("track_artist") ?: "Unknown"

        titleText.text = title
        artistText.text = artist

        if (path.isNotEmpty()) {
            initPlayer(path)
        }

        btnPlayPause.setOnClickListener { togglePlayPause() }
        findViewById<Button>(R.id.btnPrev).setOnClickListener { /* TODO */ }
        findViewById<Button>(R.id.btnNext).setOnClickListener { /* TODO */ }
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                player?.let {
                    val pos = (it.duration * seekBar!!.progress / 100)
                    it.seekTo(pos)
                }
            }
        })
    }

    private fun initPlayer(path: String) {
        player = ExoPlayer.Builder(this).build()
        player?.setMediaItem(MediaItem.fromUri(path))
        player?.prepare()
        player?.play()
        isPlaying = true
        btnPlayPause.text = "⏸"

        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    totalTime.text = formatTime(player?.duration ?: 0)
                    seekBar.max = 100
                }
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                btnPlayPause.text = if (playing) "⏸" else "▶"
            }
        })

        // Atualiza seekbar a cada 1 segundo
        val runnable = object : Runnable {
            override fun run() {
                player?.let {
                    if (it.duration > 0) {
                        seekBar.progress = (it.currentPosition * 100 / it.duration).toInt()
                        currentTime.text = formatTime(it.currentPosition)
                    }
                }
                seekBar.postDelayed(this, 1000)
            }
        }
        seekBar.postDelayed(runnable, 1000)
    }

    private fun togglePlayPause() {
        player?.let { if (isPlaying) it.pause() else it.play() }
    }

    private fun formatTime(ms: Long): String {
        val sec = (ms / 1000) % 60
        val min = (ms / 1000) / 60
        return "%d:%02d".format(min, sec)
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}

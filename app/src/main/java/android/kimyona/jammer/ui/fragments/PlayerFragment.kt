package android.kimyona.jammer.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import android.kimyona.jammer.R
import android.kimyona.jammer.ui.viewmodel.PlayerViewModel

/**
 * Fragment do player em tela cheia.
 * CORRIGIDO: findViewById para todos os views, estado vazio funcional.
 */
class PlayerFragment : Fragment() {

    private val viewModel: PlayerViewModel by activityViewModels()

    private lateinit var ivCover: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton

    private var isSeeking = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_player, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // CORREÇÃO: findViewById com R.id.XXX (não findViewWithTag!)
        ivCover = view.findViewById(R.id.ivCover)
        tvTitle = view.findViewById(R.id.tvTitle)
        tvArtist = view.findViewById(R.id.tvArtist)
        seekBar = view.findViewById(R.id.seekBar)
        tvCurrentTime = view.findViewById(R.id.tvCurrentTime)
        tvTotalTime = view.findViewById(R.id.tvTotalTime)
        btnPlayPause = view.findViewById(R.id.btnPlayPause)
        btnPrev = view.findViewById(R.id.btnPrev)
        btnNext = view.findViewById(R.id.btnNext)

        // Estado inicial vazio
        showEmptyState()

        // Observa track atual
        viewModel.currentTrack.observe(viewLifecycleOwner) { track ->
            if (track != null) {
                showTrack(track)
            } else {
                showEmptyState()
            }
        }

        // Observa estado de play/pause
        viewModel.isPlaying.observe(viewLifecycleOwner) { playing ->
            btnPlayPause.setImageResource(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play
            )
        }

        // Observa posição — atualiza seekbar e tempos
        viewModel.currentPosition.observe(viewLifecycleOwner) { posMs ->
            if (!isSeeking) {
                val duration = viewModel.getDuration()
                if (duration > 0) {
                    seekBar.max = duration.toInt()
                    seekBar.progress = posMs.toInt()
                    tvTotalTime.text = formatTime(duration)
                } else {
                    val trackDuration = viewModel.currentTrack.value?.durationMs ?: 0
                    if (trackDuration > 0) {
                        seekBar.max = trackDuration.toInt()
                        seekBar.progress = posMs.toInt()
                        tvTotalTime.text = formatTime(trackDuration)
                    }
                }
                tvCurrentTime.text = formatTime(posMs)
            }
        }

        // Controles
        btnPlayPause.setOnClickListener { viewModel.togglePlayPause() }
        btnPrev.setOnClickListener { viewModel.skipPrevious() }
        btnNext.setOnClickListener { viewModel.skipNext() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvCurrentTime.text = formatTime(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
                seekBar?.let {
                    viewModel.seekTo(it.progress.toLong())
                }
            }
        })
    }

    private fun showEmptyState() {
        tvTitle.text = "No track playing"
        tvArtist.text = "Select a song from Library"
        ivCover.setImageResource(R.drawable.album_placeholder_vinyl)
        tvCurrentTime.text = "0:00"
        tvTotalTime.text = "0:00"
        seekBar.max = 100
        seekBar.progress = 0
        seekBar.isEnabled = false
        btnPlayPause.isEnabled = false
        btnPrev.isEnabled = false
        btnNext.isEnabled = false
    }

    private fun showTrack(track: android.kimyona.jammer.data.entity.Track) {
        tvTitle.text = track.title
        tvArtist.text = track.artist
        ivCover.setImageResource(R.drawable.album_placeholder_vinyl)
        seekBar.isEnabled = true
        btnPlayPause.isEnabled = true
        btnPrev.isEnabled = true
        btnNext.isEnabled = true

        val duration = viewModel.getDuration()
        if (duration > 0) {
            seekBar.max = duration.toInt()
            tvTotalTime.text = formatTime(duration)
        } else if (track.durationMs > 0) {
            seekBar.max = track.durationMs.toInt()
            tvTotalTime.text = formatTime(track.durationMs)
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }
}

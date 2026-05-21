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
 * Capa, título, artista, seekbar, controles.
 * SeekBar agora se move sozinha e respeita duration real.
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

        ivCover = view.findViewById(R.id.ivCover)
        tvTitle = view.findViewById(R.id.tvTitle)
        tvArtist = view.findViewById(R.id.tvArtist)
        seekBar = view.findViewById(R.id.seekBar)
        btnPlayPause = view.findViewById(R.id.btnPlayPause)
        btnPrev = view.findViewById(R.id.btnPrev)
        btnNext = view.findViewById(R.id.btnNext)

        // Adiciona TextViews de tempo se não existirem no layout
        // Se o layout não tem, criamos dinamicamente ou usamos tags
        tvCurrentTime = view.findViewWithTag("tvCurrentTime") as? TextView
            ?: TextView(requireContext()).apply { tag = "tvCurrentTime" }
        tvTotalTime = view.findViewWithTag("tvTotalTime") as? TextView
            ?: TextView(requireContext()).apply { tag = "tvTotalTime" }

        // Observa track atual
        viewModel.currentTrack.observe(viewLifecycleOwner) { track ->
            track?.let {
                tvTitle.text = it.title
                tvArtist.text = it.artist
                ivCover.setImageResource(R.drawable.album_placeholder_vinyl)
            }
        }

        // Observa estado de play/pause
        viewModel.isPlaying.observe(viewLifecycleOwner) { playing ->
            btnPlayPause.setImageResource(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play
            )
        }

        // Observa posição — atualiza seekbar
        viewModel.currentPosition.observe(viewLifecycleOwner) { posMs ->
            if (!isSeeking) {
                val duration = viewModel.getDuration()
                if (duration > 0) {
                    seekBar.max = duration.toInt()
                    seekBar.progress = posMs.toInt()
                }
                tvCurrentTime?.text = formatTime(posMs)
            }
        }

        // Duration inicial quando track muda
        viewModel.currentTrack.observe(viewLifecycleOwner) {
            val duration = viewModel.getDuration()
            if (duration > 0) {
                seekBar.max = duration.toInt()
                tvTotalTime?.text = formatTime(duration)
            }
        }

        // Controles
        btnPlayPause.setOnClickListener { viewModel.togglePlayPause() }
        btnPrev.setOnClickListener { viewModel.skipPrevious() }
        btnNext.setOnClickListener { viewModel.skipNext() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvCurrentTime?.text = formatTime(progress.toLong())
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

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }
}

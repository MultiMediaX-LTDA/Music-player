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
 */
class PlayerFragment : Fragment() {

    private val viewModel: PlayerViewModel by activityViewModels()

    private lateinit var ivCover: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton

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

        // Observa track atual
        viewModel.currentTrack.observe(viewLifecycleOwner) { track ->
            track?.let {
                tvTitle.text = it.title
                tvArtist.text = it.artist
                // TODO: carregar capa do arquivo ou placeholder
                ivCover.setImageResource(R.drawable.album_placeholder_vinyl)
            }
        }

        // Observa estado de play/pause
        viewModel.isPlaying.observe(viewLifecycleOwner) { playing ->
            btnPlayPause.setImageResource(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play
            )
        }

        // Controles
        btnPlayPause.setOnClickListener { viewModel.togglePlayPause() }
        btnPrev.setOnClickListener { viewModel.skipPrevious() }
        btnNext.setOnClickListener { viewModel.skipNext() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let {
                    viewModel.seekTo(it.progress.toLong())
                }
            }
        })
    }
}

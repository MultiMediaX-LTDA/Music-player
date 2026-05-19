package android.kimyona.jammer.core.ffmpeg

import android.util.Log
import io.github.jamaismagic.ffmpeg.FFmpegKit
import io.github.jamaismagic.ffmpeg.FFprobeKit
import io.github.jamaismagic.ffmpeg.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wrapper simples do FFmpeg para o Jammer.
 * Usado para converter formatos exóticos e extrair informações de mídia.
 */
class FFmpegWrapper {

    companion object {
        private const val TAG = "JammerFFmpeg"
    }

    /**
     * Investiga um arquivo de mídia e retorna informações básicas.
     * Usa ffprobe (incluso no ffmpeg-kit).
     */
    suspend fun probeMedia(path: String): MediaProbeResult = withContext(Dispatchers.IO) {
        // Correção: Escapadas as aspas internas do comando
        val command = "-v quiet -print_format json -show_format -show_streams \"$path\""
        val session = FFprobeKit.execute(command)
        val output = session.output ?: "{}"
        val returnCode = session.returnCode

        if (ReturnCode.isSuccess(returnCode)) {
            // Correção: Escapadas as aspas internas da Regex para não quebrar a String
            val duration = extractValue(output, "\"duration\":\\s*\"?([^\",}]+)\"?").toDoubleOrNull() ?: 0.0
            val formatName = extractValue(output, "\"format_name\":\\s*\"([^\"]+)\"")
            val codec = extractValue(output, "\"codec_name\":\\s*\"([^\"]+)\"")

            Log.i(TAG, "Probe OK: $path -> format=$formatName, codec=$codec, duration=$duration")
            MediaProbeResult(success = true, duration = duration, format = formatName, codec = codec, rawJson = output)
        } else {
            Log.e(TAG, "Probe failed: ${session.state} / ${session.output}")
            MediaProbeResult(success = false, rawJson = output)
        }
    }

    /**
     * Converte qualquer áudio para MP3 192kbps.
     * Útil para WMA, MIDI, etc.
     */
    suspend fun convertAudioToMp3(inputPath: String, outputPath: String): Boolean = withContext(Dispatchers.IO) {
        // Correção: Escapadas as aspas ao redor das variáveis de caminho de arquivo
        val command = "-i \"$inputPath\" -vn -ar 44100 -ac 2 -b:a 192k -y \"$outputPath\""
        execute(command)
    }

    /**
     * Converte qualquer vídeo para MP4 (H.264 + AAC).
     * Útil para AVI, FLV, BIK, WMV, etc.
     */
    suspend fun convertVideoToMp4(inputPath: String, outputPath: String): Boolean = withContext(Dispatchers.IO) {
        // Correção: Escapadas as aspas internas
        val command = "-i \"$inputPath\" -c:v libx264 -preset fast -crf 23 -c:a aac -b:a 192k -y \"$outputPath\""
        execute(command)
    }

    /**
     * Extrai apenas o áudio de um vídeo para M4A (AAC).
     * Útil para video -> audio converter.
     */
    suspend fun extractAudio(inputPath: String, outputPath: String): Boolean = withContext(Dispatchers.IO) {
        // Correção: Escapadas as aspas internas
        val command = "-i \"$inputPath\" -vn -c:a aac -b:a 192k -y \"$outputPath\""
        execute(command)
    }

    /**
     * Executa um comando FFmpeg genérico.
     */
    private fun execute(command: String): Boolean {
        Log.i(TAG, "FFmpeg command: $command")
        val session = FFmpegKit.execute(command)
        val success = ReturnCode.isSuccess(session.returnCode)
        if (!success) {
            Log.e(TAG, "FFmpeg failed: ${session.output}")
        } else {
            Log.i(TAG, "FFmpeg success!")
        }
        return success
    }

    /**
     * Extrai um valor de uma string usando regex simples.
     */
    private fun extractValue(text: String, pattern: String): String {
        val regex = Regex(pattern)
        val match = regex.find(text)
        return match?.groupValues?.getOrNull(1) ?: "unknown"
    }

    data class MediaProbeResult(
        val success: Boolean,
        val duration: Double = 0.0,
        val format: String = "",
        val codec: String = "",
        val rawJson: String = ""
    )
}
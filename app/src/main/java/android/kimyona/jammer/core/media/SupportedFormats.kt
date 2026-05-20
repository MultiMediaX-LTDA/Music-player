package android.kimyona.jammer.core.media

/**
 * Lista todos os formatos que o Jammer suporta.
 * Nativo = ExoPlayer toca direto.
 * FFmpeg = precisa converter antes de tocar.
 */
 
object SupportedFormats {

    data class FormatInfo(
        val extension: String,
        val type: String,        // "audio" ou "video"
        val isNative: Boolean,     // true = ExoPlayer toca nativo
        val needsFFmpeg: Boolean,  // true = precisa converter
        val description: String
    )

    val ALL = listOf(
        // === ÁUDIO NATIVO (ExoPlayer toca direto) ===
        FormatInfo("mp3",  "audio", true,  false, "MPEG-1 Audio Layer III"),
        FormatInfo("flac", "audio", true,  false, "Free Lossless Audio Codec"),
        FormatInfo("ogg",  "audio", true,  false, "Ogg Vorbis"),
        FormatInfo("opus", "audio", true,  false, "Opus Interactive Audio Codec"),
        FormatInfo("aac",  "audio", true,  false, "Advanced Audio Coding"),
        FormatInfo("m4a",  "audio", true,  false, "MPEG-4 Audio"),
        FormatInfo("wav",  "audio", true,  false, "Waveform Audio File Format"),

        // === VÍDEO NATIVO (ExoPlayer toca direto) ===
        FormatInfo("mp4",  "video", true,  false, "MPEG-4 Part 14"),
        FormatInfo("mkv",  "video", true,  false, "Matroska Video"),
        FormatInfo("webm", "video", true,  false, "WebM"),
        FormatInfo("mov",  "video", true,  false, "QuickTime File Format"),
        FormatInfo("3gp",  "video", true,  false, "3GPP Multimedia File"),

        // === ÁUDIO QUE PRECISA DE FFMPEG ===
        FormatInfo("wma",  "audio", false, true, "Windows Media Audio"),
        FormatInfo("mid",  "audio", false, true, "MIDI (Musical Instrument Digital Interface)"),
        FormatInfo("midi", "audio", false, true, "MIDI"),

        // === VÍDEO QUE PRECISA DE FFMPEG ===
        FormatInfo("avi",  "video", false, true, "Audio Video Interleave"),
        FormatInfo("wmv",  "video", false, true, "Windows Media Video"),
        FormatInfo("flv",  "video", false, true, "Flash Video"),
        FormatInfo("mpeg", "video", false, true, "MPEG-1/2 Video"),
        FormatInfo("mpg",  "video", false, true, "MPEG-1/2 Video"),
        FormatInfo("bik",  "video", false, true, "Bink Video (usado em jogos)"),
    )

    fun getByExtension(ext: String): FormatInfo? {
        return ALL.find { it.extension.equals(ext.trimStart('.'), ignoreCase = true) }
    }

    fun isNative(ext: String): Boolean {
        return getByExtension(ext)?.isNative ?: false
    }

    fun needsFFmpeg(ext: String): Boolean {
        return getByExtension(ext)?.needsFFmpeg ?: false
    }
}

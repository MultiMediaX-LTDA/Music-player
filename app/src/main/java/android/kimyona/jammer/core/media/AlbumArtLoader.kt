package android.kimyona.jammer.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AlbumArtLoader — BULLETPROOF EDITION.
 *
 * Correções:
 * - extractEmbeddedArt é suspend (background thread)
 * - load() / loadThumbnail() usam coroutines internamente
 * - Nunca chama MediaMetadataRetriever na main thread
 * - Cache de bitmaps para notificações
 */
object AlbumArtLoader {

    private val notificationCache = androidx.collection.LruCache<String, Bitmap>(20)

    /**
     * Extrai capa embutida — SÓ chamar de background thread (suspend).
     */
    suspend fun extractEmbeddedArt(path: String?): Bitmap? = withContext(Dispatchers.IO) {
        if (path.isNullOrEmpty() || !File(path).exists()) return@withContext null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val art = retriever.embeddedPicture
            if (art != null) {
                BitmapFactory.decodeByteArray(art, 0, art.size)
            } else null
        } catch (e: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * Carrega capa em ImageView (async, seguro para UI thread).
     * Usa Glide com transição suave.
     */
    fun load(context: Context, path: String?, imageView: ImageView, placeholderRes: Int) {
        if (path.isNullOrEmpty()) {
            imageView.setImageResource(placeholderRes)
            return
        }
        try {
            Glide.with(context)
                .load(File(path))
                .placeholder(placeholderRes)
                .error(placeholderRes)
                .centerCrop()
                .into(imageView)
        } catch (e: Exception) {
            imageView.setImageResource(placeholderRes)
        }
    }

    /**
     * Carrega thumbnail com tamanho específico.
     */
    fun loadThumbnail(
        context: Context,
        path: String?,
        imageView: ImageView,
        placeholderRes: Int,
        sizeDp: Int = 56
    ) {
        if (path.isNullOrEmpty()) {
            imageView.setImageResource(placeholderRes)
            return
        }
        try {
            Glide.with(context)
                .load(File(path))
                .placeholder(placeholderRes)
                .error(placeholderRes)
                .override(sizeDp, sizeDp)
                .centerCrop()
                .into(imageView)
        } catch (e: Exception) {
            imageView.setImageResource(placeholderRes)
        }
    }

    /**
     * Carrega capa para notificação — com cache LruCache.
     * Pode ser chamado de qualquer thread (usa cache se disponível).
     */
    suspend fun loadForNotification(context: Context, path: String?): Bitmap? {
        if (path.isNullOrEmpty()) return null
        // Verifica cache
        notificationCache.get(path)?.let { return it }
        // Extrai em background
        val bitmap = extractEmbeddedArt(path)
        if (bitmap != null) {
            notificationCache.put(path, bitmap)
        }
        return bitmap
    }

    /**
     * Limpa cache do Glide para uma ImageView.
     */
    fun clear(imageView: ImageView) {
        try {
            Glide.with(imageView.context).clear(imageView)
        } catch (e: Exception) {
            // Ignora
        }
    }

    /**
     * Limpa cache de notificações.
     */
    fun clearNotificationCache() {
        notificationCache.evictAll()
    }
}

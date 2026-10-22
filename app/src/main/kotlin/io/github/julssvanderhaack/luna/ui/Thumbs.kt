package io.github.julssvanderhaack.luna.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.ThumbnailUtils
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import io.github.julssvanderhaack.luna.fs.FileEntry
import io.github.julssvanderhaack.luna.fs.Kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Thumbnails for local photos, videos, songs (cover), PDFs (first page)
 * and APKs (app icon). Three at a time, 48 MB in memory; failures are
 * remembered so a broken file isn't retried on every scroll.
 */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val failed = HashSet<String>()
    private val gate = Semaphore(3)

    private fun key(e: FileEntry, px: Int) = "${e.path}|${e.modified}|$px"

    fun cached(e: FileEntry, px: Int): Bitmap? = cache.get(key(e, px))

    fun hasThumb(e: FileEntry) = e.kind in setOf(Kind.IMAGE, Kind.VIDEO, Kind.AUDIO, Kind.PDF, Kind.APK)

    suspend fun load(context: Context, e: FileEntry, px: Int): Bitmap? {
        val k = key(e, px)
        cache.get(k)?.let { return it }
        synchronized(failed) { if (k in failed) return null }
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                val b = runCatching { make(context, e, px) }.getOrNull()
                if (b != null) cache.put(k, b) else synchronized(failed) { failed += k }
                b
            }
        }
    }

    private fun make(context: Context, e: FileEntry, px: Int): Bitmap? {
        val f = File(e.path)
        val size = Size(px, px)
        return when (e.kind) {
            Kind.IMAGE -> ThumbnailUtils.createImageThumbnail(f, size, null)
            Kind.VIDEO -> ThumbnailUtils.createVideoThumbnail(f, size, null)
            Kind.AUDIO -> ThumbnailUtils.createAudioThumbnail(f, size, null)
            Kind.PDF -> pdf(f, px)
            Kind.APK -> {
                val pm = context.packageManager
                val info = pm.getPackageArchiveInfo(f.path, 0)?.applicationInfo ?: return null
                info.sourceDir = f.path
                info.publicSourceDir = f.path
                info.loadIcon(pm).toBitmap(px, px)
            }
            else -> null
        }
    }

    private fun pdf(f: File, px: Int): Bitmap? =
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                if (r.pageCount == 0) return null
                r.openPage(0).use { page ->
                    val scale = px.toFloat() / maxOf(page.width, page.height)
                    val b = createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1))
                    Canvas(b).drawColor(Color.WHITE)
                    page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    b
                }
            }
        }
}

/** Null until (and unless) a thumbnail exists; local files only. */
@Composable
fun rememberThumb(e: FileEntry, px: Int, local: Boolean): ImageBitmap? {
    val context = LocalContext.current
    val thumb by produceState(Thumbs.cached(e, px)?.asImageBitmap(), e.path, e.modified, px, local) {
        if (value == null && local && Thumbs.hasThumb(e)) value = Thumbs.load(context, e, px)?.asImageBitmap()
    }
    return thumb
}

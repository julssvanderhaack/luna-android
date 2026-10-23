package io.github.julssvanderhaack.luna.ui

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.media.ThumbnailUtils
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
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
import io.github.julssvanderhaack.luna.fs.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Thumbnails for local photos, videos, songs (cover), PDFs (first page) and
 * APKs (app icon), fastest source first:
 *
 * 1. memory (an LRU sized from the app's heap);
 * 2. Android's own thumbnail for files MediaStore indexes: the gallery has
 *    usually made it already, so a camera folder opens almost instantly;
 * 3. Luna's disk cache (cacheDir/thumbs), so a folder is only decoded once;
 * 4. decoding the file, a few at a time, then saving to the disk cache.
 *
 * Failures are remembered so a broken file isn't retried on every scroll.
 */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 5).toInt().coerceIn(24 * 1024, 128 * 1024),
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val failed = HashSet<String>()
    private val cores = Runtime.getRuntime().availableProcessors()
    /**
     * Full decodes are CPU-bound: one per core, leaving one for the UI, on
     * background-priority threads so a fling never waits for a thumbnail.
     */
    private val decoder = Executors.newFixedThreadPool((cores - 1).coerceIn(2, 4)) { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "luna-thumbs")
    }.asCoroutineDispatcher()
    /** MediaStore and disk reads are mostly waiting: more of them at once. */
    private val fastGate = Semaphore(6)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val DISK_MAX = 200L * 1024 * 1024
    private const val DISK_TRIM_TO = 150L * 1024 * 1024
    private var diskDir: File? = null

    /** Folder → its files' MediaStore ids, asked once per folder (and again after a minute). */
    private class DirIds(val at: Long, val ids: Deferred<Map<String, Long>>)
    private val dirIds = LruCache<String, DirIds>(24)

    /** Called once from the Application: sets up the disk cache and trims it in the background. */
    fun init(context: Context) {
        val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
        diskDir = dir
        io.launch {
            val files = dir.listFiles() ?: return@launch
            var total = files.sumOf { it.length() }
            if (total <= DISK_MAX) return@launch
            for (f in files.sortedBy { it.lastModified() }) {
                total -= f.length()
                f.delete()
                if (total <= DISK_TRIM_TO) break
            }
        }
    }

    private fun key(e: FileEntry, px: Int) = "${e.path}|${e.modified}|$px"

    fun cached(e: FileEntry, px: Int): Bitmap? = cache.get(key(e, px))

    fun hasThumb(e: FileEntry) = e.kind in THUMB_KINDS

    private val THUMB_KINDS = setOf(Kind.IMAGE, Kind.VIDEO, Kind.AUDIO, Kind.PDF, Kind.APK)
    private val MEDIA_KINDS = setOf(Kind.IMAGE, Kind.VIDEO, Kind.AUDIO)

    suspend fun load(context: Context, e: FileEntry, px: Int): Bitmap? {
        val k = key(e, px)
        cache.get(k)?.let { return it }
        synchronized(failed) { if (k in failed) return null }
        val b = withContext(Dispatchers.IO) { fastGate.withPermit { fromMediaStore(context, e, px) ?: fromDisk(e, px) } }
            ?: withContext(decoder) { runCatching { make(context, e, px) }.getOrNull()?.also { toDisk(e, px, it) } }
        // upload to the GPU now, off the UI thread, instead of on the first frame that draws it
        b?.prepareToDraw()
        if (b != null) cache.put(k, b) else synchronized(failed) { failed += k }
        return b
    }

    // ---- MediaStore -------------------------------------------------------

    private suspend fun fromMediaStore(context: Context, e: FileEntry, px: Int): Bitmap? {
        if (e.kind !in MEDIA_KINDS) return null
        val id = e.mediaId.takeIf { it > 0 } ?: idsIn(context, e.parent)[e.path] ?: return null
        // a photo Android hasn't thumbnailed yet would be made at 512 px and saved; Luna's own is quicker
        if (e.kind == Kind.IMAGE && !systemThumbExists(context, e.path, id)) return null
        val base = when (e.kind) {
            Kind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            Kind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        return runCatching { context.contentResolver.loadThumbnail(ContentUris.withAppendedId(base, id), Size(px, px), null) }.getOrNull()
    }

    /** Mount points of the storage volumes, to find which one a file is on without a binder call each time. */
    @Volatile private var roots: List<String> = emptyList()

    private fun volumeRoot(context: Context, path: String): String? {
        fun find() = roots.firstOrNull { path.startsWith("$it/") }
        return find() ?: run {
            roots = context.getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { it.directory?.path }
            find()
        }
    }

    /** Since Android 11 MediaStore keeps photo thumbnails as <volume>/Pictures/.thumbnails/<id>.jpg. */
    private fun systemThumbExists(context: Context, path: String, id: Long): Boolean {
        val root = volumeRoot(context, path) ?: return false
        return File("$root/Pictures/.thumbnails/$id.jpg").exists()
    }

    private suspend fun idsIn(context: Context, dir: String): Map<String, Long> {
        val now = System.currentTimeMillis()
        val d = synchronized(dirIds) {
            dirIds.get(dir)?.takeIf { now - it.at < 60_000 } ?: DirIds(now, io.async { queryIds(context, dir) }).also { dirIds.put(dir, it) }
        }
        return runCatching { d.ids.await() }.getOrDefault(emptyMap())
    }

    /** One query for a whole folder, by volume and relative path (equality, so it stays cheap on big libraries). */
    private fun queryIds(context: Context, dir: String): Map<String, Long> {
        val vol = context.getSystemService(StorageManager::class.java).getStorageVolume(File(dir)) ?: return emptyMap()
        val root = vol.directory?.path ?: return emptyMap()
        val name = vol.mediaStoreVolumeName ?: return emptyMap()
        val rel = dir.removePrefix(root).trim('/')
        if (rel.isEmpty()) return emptyMap()
        val q = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${FileColumns.RELATIVE_PATH} = ? AND ${FileColumns.MEDIA_TYPE} IN (1, 2, 3)")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("$rel/"))
        }
        val out = HashMap<String, Long>()
        context.contentResolver.query(MediaStore.Files.getContentUri(name), arrayOf(FileColumns._ID, FileColumns.DISPLAY_NAME), q, null)?.use { c ->
            while (c.moveToNext()) out[Paths.join(dir, c.getString(1) ?: continue)] = c.getLong(0)
        }
        return out
    }

    // ---- disk cache -------------------------------------------------------

    private fun diskFile(e: FileEntry, px: Int): File? {
        val dir = diskDir ?: return null
        val sha = MessageDigest.getInstance("SHA-1").digest("${e.path}|${e.modified}|${e.size}|$px".toByteArray())
        return File(dir, sha.joinToString("") { "%02x".format(it) })
    }

    private fun fromDisk(e: FileEntry, px: Int): Bitmap? {
        val f = diskFile(e, px)?.takeIf { it.exists() } ?: return null
        return BitmapFactory.decodeFile(f.path)
    }

    /** Written after the thumbnail is on screen, not before. */
    private fun toDisk(e: FileEntry, px: Int, b: Bitmap) {
        val f = diskFile(e, px) ?: return
        io.launch {
            runCatching {
                val tmp = File(f.path + ".tmp")
                tmp.outputStream().use { out ->
                    if (b.hasAlpha()) b.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, out)
                    else b.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                tmp.renameTo(f)
            }
        }
    }

    // ---- decoding ---------------------------------------------------------

    private fun make(context: Context, e: FileEntry, px: Int): Bitmap? {
        val f = File(e.path)
        val size = Size(px, px)
        return when (e.kind) {
            Kind.IMAGE -> image(f, px)
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

    /**
     * Decoded straight at the size shown: the decoder subsamples while it
     * reads (a 12 MP JPEG at 1/8 is a fraction of the work) and the short
     * side comes out at [px], since the tiles crop to a square.
     */
    private fun image(f: File, px: Int): Bitmap = exifThumb(f, px) ?: decode(f, px)

    /**
     * Camera JPEGs carry a small preview (often 512×384) in their EXIF block,
     * near the start of the file: reading it skips decoding megabytes of
     * photo. Used only when it's sharp enough for [px] and has the photo's
     * shape (some cameras letterbox it).
     */
    private fun exifThumb(f: File, px: Int): Bitmap? {
        if (f.extension.lowercase() !in setOf("jpg", "jpeg")) return null
        return runCatching {
            val exif = ExifInterface(f)
            val bytes = exif.thumbnailBytes ?: return null
            val tb = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, tb)
            val ib = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, ib) // header only
            if (tb.outWidth <= 0 || ib.outWidth <= 0) return null
            if (minOf(tb.outWidth, tb.outHeight) < px * 0.7f) return null
            val ta = tb.outWidth.toFloat() / tb.outHeight
            val ia = ib.outWidth.toFloat() / ib.outHeight
            if (kotlin.math.abs(ta - ia) > 0.03f) return null
            val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val degrees = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees == 0f) b
            else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(degrees) }, true)
        }.getOrNull()
    }

    private fun decode(f: File, px: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = px.toFloat() / minOf(w, h)
            if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // so it can go to the disk cache
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

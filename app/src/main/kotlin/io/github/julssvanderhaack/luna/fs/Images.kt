package io.github.julssvanderhaack.luna.fs

import io.github.julssvanderhaack.luna.tr
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.IOException

enum class ImageFormat(val ext: String, val label: String, val compress: Bitmap.CompressFormat) {
    JPG("jpg", "JPG", Bitmap.CompressFormat.JPEG),
    PNG("png", "PNG", Bitmap.CompressFormat.PNG),
    WEBP("webp", "WebP", Bitmap.CompressFormat.WEBP_LOSSY),
}

/**
 * What desktop Luna does with ImageMagick: rotate in place, and shrink or
 * convert into copies next to the original. The camera's EXIF (date, GPS,
 * model…) is carried over to JPEGs.
 */
object Images {
    /** Ones the platform can both decode and write back. */
    fun canEdit(e: FileEntry) = e.ext in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif", "avif")

    private val exifTags = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_ISO_SPEED_RATINGS, ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        "LensModel", "LensMake",
    )

    /** Decoded upright (EXIF rotation applied), at most [maxSide] px on the long side. */
    private fun decode(f: File, maxSide: Int = 0): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, info, _ ->
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        if (maxSide > 0) {
            val long = maxOf(info.size.width, info.size.height)
            if (long > maxSide) {
                val s = maxSide.toDouble() / long
                d.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
            }
        }
    }

    private fun formatOf(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "png" -> ImageFormat.PNG
        "webp" -> ImageFormat.WEBP
        else -> ImageFormat.JPG
    }

    private fun write(bmp: Bitmap, out: File, format: ImageFormat, exifFrom: File?) {
        val tmp = File(out.parentFile, ".${out.name}.luna-tmp")
        try {
            tmp.outputStream().use { if (!bmp.compress(format.compress, if (format == ImageFormat.PNG) 100 else 92, it)) throw IOException(tr("No se ha podido guardar la imagen")) }
            if (exifFrom != null && format == ImageFormat.JPG) runCatching {
                val src = ExifInterface(exifFrom.path)
                val dst = ExifInterface(tmp.path)
                exifTags.forEach { t -> src.getAttribute(t)?.let { dst.setAttribute(t, it) } }
                // the pixels are upright now
                dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                dst.saveAttributes()
            }
            if (out.exists() && !out.delete() || !tmp.renameTo(out)) throw IOException(tr("No se ha podido guardar «%1»", out.name))
        } finally {
            tmp.delete()
        }
    }

    /** Turns the picture itself by [degrees] (90, 180, 270), in place. */
    fun rotate(f: File, degrees: Int) {
        val src = decode(f)
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        // a new modification time also tells the thumbnail caches it changed
        write(rotated, f, formatOf(f.name), f)
    }

    /** A copy at most [maxSide] px on the long side: «foto (1920 px).jpg». Returns it. */
    fun resize(f: File, maxSide: Int): File {
        val bmp = decode(f, maxSide)
        val stem = f.name.substringBeforeLast('.')
        val fmt = formatOf(f.name)
        val name = Paths.freeName("$stem ($maxSide px).${fmt.ext}", false) { File(f.parentFile, it).exists() }
        return File(f.parentFile, name).also { write(bmp, it, fmt, f) }
    }

    /** A copy in [format] next to the original. Returns it. */
    fun convert(f: File, format: ImageFormat): File {
        val bmp = decode(f)
        val name = Paths.freeName("${f.name.substringBeforeLast('.')}.${format.ext}", false) { File(f.parentFile, it).exists() }
        return File(f.parentFile, name).also { write(bmp, it, format, f) }
    }
}

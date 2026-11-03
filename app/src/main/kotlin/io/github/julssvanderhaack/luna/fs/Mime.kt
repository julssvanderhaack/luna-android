package io.github.julssvanderhaack.luna.fs

import io.github.julssvanderhaack.luna.tr
import android.webkit.MimeTypeMap

object Mime {
    /** What Android calls this file, with a few types its table lacks. */
    fun of(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        return when (ext) {
            "md", "log", "ini", "conf", "cfg", "toml", "yaml", "yml", "nfo", "lrc", "srt", "kt", "kts", "sh", "py", "c", "h", "cpp", "rs", "go", "qml" -> "text/plain"
            "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            "mkv" -> "video/x-matroska"
            "heic" -> "image/heic"
            "avif" -> "image/avif"
            "apk" -> "application/vnd.android.package-archive"
            "epub" -> "application/epub+zip"
            "7z" -> "application/x-7z-compressed"
            else -> "application/octet-stream"
        }
    }

    /** "Imagen JPG", "Documento PDF": the type column of the detailed view. */
    fun describe(name: String): String {
        val ext = name.substringAfterLast('.', "").takeIf { '.' in name }?.uppercase().orEmpty()
        val word = when (Kind.of(name)) {
            Kind.FOLDER -> tr("Carpeta")
            Kind.IMAGE -> tr("Imagen")
            Kind.VIDEO -> tr("Vídeo")
            Kind.AUDIO -> tr("Audio")
            Kind.PDF -> return tr("Documento PDF")
            Kind.TEXT -> tr("Texto")
            Kind.CODE -> tr("Código")
            Kind.DOCUMENT -> tr("Documento")
            Kind.SHEET -> tr("Hoja de cálculo")
            Kind.SLIDES -> tr("Presentación")
            Kind.ARCHIVE -> tr("Comprimido")
            Kind.APK -> return tr("Instalador de Android")
            Kind.EBOOK -> tr("Libro")
            Kind.FONT -> tr("Fuente")
            Kind.OTHER -> return if (ext.isEmpty()) tr("Archivo") else tr("Archivo %1", ext)
        }
        return if (ext.isEmpty()) word else "$word $ext"
    }

    /** "Abrir como": the families a file can be handed over as, whatever its name says. */
    enum class As(val mime: String) {
        TEXT("text/plain"), IMAGE("image/*"), AUDIO("audio/*"), VIDEO("video/*"), PDF("application/pdf"), ANY("*/*")
    }
}

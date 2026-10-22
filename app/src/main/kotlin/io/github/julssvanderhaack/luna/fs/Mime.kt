package io.github.julssvanderhaack.luna.fs

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

    /** "Abrir como": the families a file can be handed over as, whatever its name says. */
    enum class As(val mime: String) {
        TEXT("text/plain"), IMAGE("image/*"), AUDIO("audio/*"), VIDEO("video/*"), PDF("application/pdf"), ANY("*/*")
    }
}

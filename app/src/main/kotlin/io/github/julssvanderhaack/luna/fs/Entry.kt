package io.github.julssvanderhaack.luna.fs

/** One file or folder, local or remote. [path] is absolute within its [Vfs]. */
data class FileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    /** Milliseconds since the epoch. */
    val modified: Long,
    val isLink: Boolean = false,
) {
    val hidden get() = name.startsWith('.')
    val ext get() = if (isDir) "" else name.substringAfterLast('.', "").lowercase()
    val kind get() = if (isDir) Kind.FOLDER else Kind.of(name)
    val parent get() = Paths.parent(path)
}

/** POSIX path helpers shared by the local and the SFTP file systems. */
object Paths {
    fun join(dir: String, name: String) = if (dir.endsWith('/')) dir + name else "$dir/$name"

    fun parent(path: String): String {
        val p = path.trimEnd('/')
        val i = p.lastIndexOf('/')
        return if (i <= 0) "/" else p.substring(0, i)
    }

    fun name(path: String) = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }

    /** "foto.jpg" → "foto (2).jpg", skipping names [taken] already has. */
    fun freeName(name: String, isDir: Boolean, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val dot = if (isDir) -1 else name.lastIndexOf('.').takeIf { it > 0 } ?: -1
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (taken("$base ($i)$ext")) i++
        return "$base ($i)$ext"
    }

    /** True when [path] is [dir] or lies inside it: you can't copy a folder into itself. */
    fun isInside(path: String, dir: String) = path == dir || path.startsWith(dir.trimEnd('/') + "/")
}

enum class Kind {
    FOLDER, IMAGE, VIDEO, AUDIO, PDF, TEXT, CODE, DOCUMENT, SHEET, SLIDES, ARCHIVE, APK, EBOOK, FONT, OTHER;

    companion object {
        private val byExt: Map<String, Kind> = buildMap {
            fun put(k: Kind, vararg exts: String) = exts.forEach { put(it, k) }
            put(IMAGE, "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "avif", "svg", "dng", "raw", "tif", "tiff", "ico")
            put(VIDEO, "mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "wmv", "flv", "ts", "mpg", "mpeg")
            put(AUDIO, "mp3", "flac", "ogg", "opus", "m4a", "aac", "wav", "wma", "ape", "amr", "mid", "midi", "alac", "aiff")
            put(PDF, "pdf")
            put(TEXT, "txt", "md", "log", "ini", "conf", "cfg", "csv", "srt", "lrc", "nfo", "toml", "yaml", "yml")
            put(CODE, "c", "h", "cpp", "hpp", "cc", "kt", "kts", "java", "py", "js", "ts", "json", "xml", "html", "htm", "css", "sh", "rs", "go", "qml", "sql", "gradle")
            put(DOCUMENT, "doc", "docx", "odt", "rtf", "pages")
            put(SHEET, "xls", "xlsx", "ods", "numbers")
            put(SLIDES, "ppt", "pptx", "odp", "key")
            put(ARCHIVE, "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "iso")
            put(APK, "apk", "apks", "xapk")
            put(EBOOK, "epub", "mobi", "azw3", "fb2", "cbz", "cbr")
            put(FONT, "ttf", "otf", "woff", "woff2")
        }

        fun of(name: String): Kind = byExt[name.substringAfterLast('.', "").lowercase()] ?: OTHER
    }
}

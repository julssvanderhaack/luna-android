package io.github.julssvanderhaack.luna.fs

import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns

/** The home screen's shortcuts, answered by MediaStore instead of walking the disk. */
enum class Category(val title: String, val media: Boolean) {
    RECENT("Recientes", false),
    CAMERA("Cámara", true),
    IMAGES("Imágenes", true),
    VIDEOS("Vídeos", true),
    AUDIO("Audio", false),
    DOCUMENTS("Documentos", false),
    APKS("Instaladores APK", false),
    ARCHIVES("Comprimidos", false),
}

object MediaQueries {
    /** App-private folders and hidden files aren't "my" recent files. */
    private const val NOT_APP_FILES =
        "${FileColumns.RELATIVE_PATH} NOT LIKE 'Android/%' AND ${FileColumns.RELATIVE_PATH} NOT LIKE '.%' " +
            "AND ${FileColumns.RELATIVE_PATH} NOT LIKE '%/.%' AND ${FileColumns.DISPLAY_NAME} NOT LIKE '.%'"

    private fun selection(c: Category): Pair<String, Array<String>> = when (c) {
        Category.RECENT -> "${FileColumns.MIME_TYPE} IS NOT NULL AND ${FileColumns.SIZE} > 0 AND $NOT_APP_FILES" to emptyArray()
        Category.CAMERA -> "${FileColumns.RELATIVE_PATH} LIKE 'DCIM/Camera/%' AND ${FileColumns.MEDIA_TYPE} IN (1, 3)" to emptyArray()
        Category.IMAGES -> "${FileColumns.MEDIA_TYPE} = 1 AND $NOT_APP_FILES" to emptyArray()
        Category.VIDEOS -> "${FileColumns.MEDIA_TYPE} = 3 AND $NOT_APP_FILES" to emptyArray()
        Category.AUDIO -> "${FileColumns.MEDIA_TYPE} = 2 AND $NOT_APP_FILES" to emptyArray()
        Category.DOCUMENTS -> "(${FileColumns.MIME_TYPE} LIKE 'text/%' OR ${FileColumns.MIME_TYPE} IN (?, ?, ?, ?) " +
            "OR ${FileColumns.MIME_TYPE} LIKE 'application/vnd.openxmlformats-officedocument%' " +
            "OR ${FileColumns.MIME_TYPE} LIKE 'application/vnd.oasis.opendocument%' " +
            "OR ${FileColumns.MIME_TYPE} LIKE 'application/vnd.ms-%') AND $NOT_APP_FILES" to
            arrayOf("application/pdf", "application/msword", "application/rtf", "application/epub+zip")
        Category.APKS -> "(${FileColumns.MIME_TYPE} = ? OR ${FileColumns.DISPLAY_NAME} LIKE '%.apk') AND $NOT_APP_FILES" to
            arrayOf("application/vnd.android.package-archive")
        Category.ARCHIVES -> "(${FileColumns.MIME_TYPE} IN (?, ?, ?, ?, ?) OR ${FileColumns.DISPLAY_NAME} LIKE '%.7z' " +
            "OR ${FileColumns.DISPLAY_NAME} LIKE '%.rar') AND $NOT_APP_FILES" to
            arrayOf("application/zip", "application/x-tar", "application/gzip", "application/x-7z-compressed", "application/vnd.rar")
    }

    fun query(context: Context, c: Category, limit: Int = 2000): List<FileEntry> {
        val (where, args) = selection(c)
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        @Suppress("DEPRECATION")
        val cols = arrayOf(FileColumns.DATA, FileColumns.DISPLAY_NAME, FileColumns.SIZE, FileColumns.DATE_MODIFIED)
        val q = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${FileColumns.DATE_MODIFIED} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val out = ArrayList<FileEntry>()
        context.contentResolver.query(uri, cols, q, null)?.use { cur ->
            while (cur.moveToNext()) {
                val path = cur.getString(0) ?: continue
                out += FileEntry(
                    name = cur.getString(1) ?: Paths.name(path),
                    path = path,
                    isDir = false,
                    size = cur.getLong(2),
                    modified = cur.getLong(3) * 1000,
                )
            }
        }
        return out
    }
}

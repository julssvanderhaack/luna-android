package io.github.julssvanderhaack.luna.fs

import io.github.julssvanderhaack.luna.tr
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Something in the trash: the file itself in files/ and where it came from. */
data class TrashItem(
    val entry: FileEntry,
    val originalPath: String,
    /** Milliseconds since the epoch. */
    val deleted: Long,
    val info: File,
)

/**
 * A trash per volume, laid out like the freedesktop one desktop Luna and
 * Nautilus use (files/ + info/<name>.trashinfo), in <volume>/.Trash-luna.
 * Moving there is a rename, so throwing away a 4 GB video is instant and a
 * USB stick's trash stays on the stick.
 */
object Trash {
    const val DIR = ".Trash-luna"
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    fun dir(volumeRoot: String) = File(volumeRoot, DIR)

    /** Moves [f] into the trash of [volumeRoot]. */
    fun put(f: File, volumeRoot: String, now: Long = System.currentTimeMillis()): TrashItem {
        val root = dir(volumeRoot)
        val files = File(root, "files")
        val info = File(root, "info")
        if (!files.isDirectory && !files.mkdirs() || !info.isDirectory && !info.mkdirs()) {
            throw IOException(tr("No se puede crear la papelera en %1", volumeRoot))
        }
        // keeps the gallery and Recientes from showing what was thrown away
        File(root, ".nomedia").takeIf { !it.exists() }?.createNewFile()
        val name = Paths.freeName(f.name, false) { File(files, it).exists() || File(info, "$it.trashinfo").exists() }
        val infoFile = File(info, "$name.trashinfo")
        val date = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), ZoneId.systemDefault())
        infoFile.writeText("[Trash Info]\nPath=${encode(f.absolutePath)}\nDeletionDate=${stamp.format(date)}\n")
        val target = File(files, name)
        if (!f.renameTo(target)) {
            infoFile.delete()
            throw IOException(tr("No se ha podido mover «%1» a la papelera", f.name))
        }
        return TrashItem(LocalVfs.entry(target), f.absolutePath, now, infoFile)
    }

    fun list(volumeRoot: String): List<TrashItem> {
        val root = dir(volumeRoot)
        val infos = File(root, "info").listFiles { f -> f.name.endsWith(".trashinfo") } ?: return emptyList()
        return infos.mapNotNull { info ->
            val file = File(File(root, "files"), info.name.removeSuffix(".trashinfo"))
            if (!file.exists() && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
                info.delete()
                return@mapNotNull null
            }
            val lines = runCatching { info.readLines() }.getOrDefault(emptyList())
            val path = lines.firstOrNull { it.startsWith("Path=") }?.removePrefix("Path=")?.let(::decode) ?: return@mapNotNull null
            val deleted = lines.firstOrNull { it.startsWith("DeletionDate=") }?.removePrefix("DeletionDate=")?.let {
                runCatching { LocalDateTime.parse(it, stamp).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
            } ?: info.lastModified()
            TrashItem(LocalVfs.entry(file), path, deleted, info)
        }.sortedByDescending { it.deleted }
    }

    /** Puts it back where it was ("nombre (2)" if that's taken now); returns the path it went to. */
    fun restore(item: TrashItem, exact: Boolean = false): String {
        val orig = File(item.originalPath)
        val parent = orig.parentFile ?: throw IOException(tr("Ruta no válida"))
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException(tr("No se puede recrear %1", parent.path))
        val target = if (exact && !orig.exists()) orig else File(parent, Paths.freeName(orig.name, item.entry.isDir) { File(parent, it).exists() })
        if (!File(item.entry.path).renameTo(target)) throw IOException(tr("No se ha podido restaurar «%1»", orig.name))
        item.info.delete()
        return target.path
    }

    /** Gone for good. */
    fun purge(item: TrashItem) {
        File(item.entry.path).let { if (it.isDirectory && !java.nio.file.Files.isSymbolicLink(it.toPath())) it.deleteRecursively() else it.delete() }
        item.info.delete()
    }

    private fun encode(path: String) = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    private fun decode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
}

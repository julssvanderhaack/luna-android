package io.github.julssvanderhaack.luna.fs

import io.github.julssvanderhaack.luna.tr
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * What the browser and the copy engine need from a file system. Every call
 * blocks: callers run them on Dispatchers.IO.
 */
interface Vfs {
    /** "local", or "sftp:<server id>": clipboard entries remember where they came from. */
    val id: String
    fun list(dir: String): List<FileEntry>
    fun stat(path: String): FileEntry?
    fun exists(path: String) = stat(path) != null
    fun mkdir(path: String)
    fun createFile(path: String)
    /** Rename or move within this file system. Returns false if it has to be copied instead. */
    fun move(from: String, to: String): Boolean
    fun deleteOne(path: String, isDir: Boolean)
    fun openRead(path: String): InputStream
    fun openWrite(path: String): OutputStream
    fun setModified(path: String, millis: Long) {}

    /** Owner, group and rwx bits, when the file system has them. */
    fun permissions(path: String): Perms? = null
    fun chmod(path: String, mode: Int): Unit = throw IOException(tr("Aquí no se pueden cambiar los permisos"))

    /** Removes a file, or a folder and everything inside it. */
    fun delete(entry: FileEntry) {
        if (entry.isDir && !entry.isLink) list(entry.path).forEach { delete(it) }
        deleteOne(entry.path, entry.isDir && !entry.isLink)
    }
}

/** POSIX permissions: [mode] holds the 12 low bits (setuid, setgid, sticky, rwxrwxrwx). */
data class Perms(val mode: Int, val owner: String, val group: String, val canChange: Boolean) {
    val octal get() = "%04o".format(mode and 0xFFF)
    val text get() = buildString {
        val chars = "rwxrwxrwx"
        for (i in 0 until 9) append(if (mode and (1 shl (8 - i)) != 0) chars[i] else '-')
    }
}

object LocalVfs : Vfs {
    override val id = "local"

    /** One lstat per file (plus a stat for links) instead of the five calls java.io.File makes. */
    fun entry(f: File): FileEntry {
        val p = f.toPath()
        val own = try {
            Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) {
            return FileEntry(f.name, f.path, false, 0, 0)
        }
        // a link shows what it points to; a broken one stays a plain entry
        val a = if (own.isSymbolicLink) runCatching { Files.readAttributes(p, BasicFileAttributes::class.java) }.getOrNull() ?: own else own
        return FileEntry(
            name = f.name,
            path = f.path,
            isDir = a.isDirectory,
            size = if (a.isRegularFile) a.size() else 0,
            modified = a.lastModifiedTime().toMillis(),
            isLink = own.isSymbolicLink,
        )
    }

    override fun list(dir: String): List<FileEntry> {
        val f = File(dir)
        val names = f.list() ?: throw IOException(
            when {
                !f.exists() -> tr("La carpeta ya no existe")
                dir.startsWith("/storage/") || dir.startsWith("/sdcard") -> tr("Sin permiso para leer esta carpeta")
                else -> tr("Sin permiso para leer esta carpeta.\nEs del sistema: Android solo deja entrar con root.")
            },
        )
        return names.map { entry(File(f, it)) }
    }

    override fun stat(path: String) = File(path).takeIf { it.exists() }?.let { entry(it) }

    override fun mkdir(path: String) {
        if (!File(path).mkdir()) throw IOException(tr("No se ha podido crear la carpeta"))
    }

    override fun createFile(path: String) {
        if (!File(path).createNewFile()) throw IOException(tr("No se ha podido crear el archivo"))
    }

    override fun move(from: String, to: String) = File(from).renameTo(File(to))

    override fun deleteOne(path: String, isDir: Boolean) {
        val f = File(path)
        if (!f.delete() && f.exists()) throw IOException(tr("No se ha podido borrar %1", f.name))
    }

    override fun openRead(path: String): InputStream = FileInputStream(path)
    override fun openWrite(path: String): OutputStream = FileOutputStream(path)
    override fun setModified(path: String, millis: Long) {
        File(path).setLastModified(millis)
    }

    override fun permissions(path: String): Perms? = runCatching {
        val p = File(path).toPath()
        val mode = (Files.getAttribute(p, "unix:mode", LinkOption.NOFOLLOW_LINKS) as Int) and 0xFFF
        val owner = Files.getOwner(p, LinkOption.NOFOLLOW_LINKS).name
        val group = runCatching { (Files.getAttribute(p, "unix:group", LinkOption.NOFOLLOW_LINKS) as java.security.Principal).name }.getOrDefault("")
        // shared storage is FUSE with fixed permissions; only Luna's own files (and root) can change
        Perms(mode, owner, group, canChange = !path.startsWith("/storage/") && !path.startsWith("/sdcard") && File(path).canWrite())
    }.getOrNull()

    override fun chmod(path: String, mode: Int) {
        val set = java.nio.file.attribute.PosixFilePermissions.fromString(Perms(mode, "", "", true).text)
        Files.setPosixFilePermissions(File(path).toPath(), set)
    }
}

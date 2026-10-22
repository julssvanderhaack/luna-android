package io.github.julssvanderhaack.luna.fs

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

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

    /** Removes a file, or a folder and everything inside it. */
    fun delete(entry: FileEntry) {
        if (entry.isDir && !entry.isLink) list(entry.path).forEach { delete(it) }
        deleteOne(entry.path, entry.isDir && !entry.isLink)
    }
}

object LocalVfs : Vfs {
    override val id = "local"

    fun entry(f: File) = FileEntry(
        name = f.name,
        path = f.path,
        isDir = f.isDirectory,
        size = if (f.isFile) f.length() else 0,
        modified = f.lastModified(),
        isLink = runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false),
    )

    override fun list(dir: String): List<FileEntry> {
        val f = File(dir)
        val names = f.list() ?: throw IOException(if (!f.exists()) "La carpeta ya no existe" else "Sin permiso para leer esta carpeta")
        return names.map { entry(File(f, it)) }
    }

    override fun stat(path: String) = File(path).takeIf { it.exists() }?.let { entry(it) }

    override fun mkdir(path: String) {
        if (!File(path).mkdir()) throw IOException("No se ha podido crear la carpeta")
    }

    override fun createFile(path: String) {
        if (!File(path).createNewFile()) throw IOException("No se ha podido crear el archivo")
    }

    override fun move(from: String, to: String) = File(from).renameTo(File(to))

    override fun deleteOne(path: String, isDir: Boolean) {
        val f = File(path)
        if (!f.delete() && f.exists()) throw IOException("No se ha podido borrar ${f.name}")
    }

    override fun openRead(path: String): InputStream = FileInputStream(path)
    override fun openWrite(path: String): OutputStream = FileOutputStream(path)
    override fun setModified(path: String, millis: Long) {
        File(path).setLastModified(millis)
    }
}

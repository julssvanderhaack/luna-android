package io.github.julssvanderhaack.luna.fs

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What a running operation reports to the progress card. */
data class Progress(
    val title: String,
    val current: String = "",
    val done: Long = 0,
    val total: Long = 0,
) {
    val fraction get() = if (total <= 0) null else (done.toFloat() / total).coerceIn(0f, 1f)
}

/** Sizes and counts of a selection, for progress totals and Propiedades. */
data class Tally(val files: Int = 0, val folders: Int = 0, val bytes: Long = 0) {
    operator fun plus(o: Tally) = Tally(files + o.files, folders + o.folders, bytes + o.bytes)
}

/**
 * Copy, move, delete and zip between any two [Vfs]: local↔local, local↔SFTP,
 * SFTP↔SFTP. Names that already exist get " (2)" like desktop Luna's paste.
 * Every loop checks for cancellation.
 */
class FileOps(private val report: (Progress) -> Unit) {
    private val buf = ByteArray(256 * 1024)
    private var progress = Progress("")

    suspend fun tally(vfs: Vfs, entries: List<FileEntry>): Tally {
        var t = Tally()
        for (e in entries) {
            currentCoroutineContext().ensureActive()
            t += if (e.isDir && !e.isLink) Tally(folders = 1) + tally(vfs, vfs.list(e.path)) else Tally(files = 1, bytes = e.size)
        }
        return t
    }

    /** Returns the paths created in [dstDir], so the caller can tell MediaStore. */
    suspend fun transfer(src: Vfs, entries: List<FileEntry>, dst: Vfs, dstDir: String, move: Boolean): List<String> {
        for (e in entries) if (e.isDir && src.id == dst.id && Paths.isInside(dstDir, e.path)) {
            throw IOException("No se puede ${if (move) "mover" else "copiar"} «${e.name}» dentro de sí misma")
        }
        progress = Progress(if (move) "Moviendo" else "Copiando")
        // a move inside one file system is a rename, nothing to count
        val sameFs = src.id == dst.id
        if (!(move && sameFs)) progress = progress.copy(total = tally(src, entries).bytes)
        report(progress)
        val created = ArrayList<String>()
        for (e in entries) {
            currentCoroutineContext().ensureActive()
            if (move && sameFs && e.parent == dstDir.trimEnd('/').ifEmpty { "/" }) continue
            val target = Paths.join(dstDir, Paths.freeName(e.name, e.isDir) { dst.exists(Paths.join(dstDir, it)) })
            if (move && sameFs && src.move(e.path, target)) {
                created += target
                continue
            }
            copyTree(src, e, dst, target)
            created += target
            if (move) src.delete(e)
        }
        return created
    }

    private suspend fun copyTree(src: Vfs, e: FileEntry, dst: Vfs, target: String) {
        currentCoroutineContext().ensureActive()
        if (e.isDir) {
            dst.mkdir(target)
            for (c in src.list(e.path)) copyTree(src, c, dst, Paths.join(target, c.name))
        } else {
            progress = progress.copy(current = e.name)
            report(progress)
            try {
                src.openRead(e.path).use { input ->
                    dst.openWrite(target).use { output -> pump(input, output) }
                }
            } catch (t: Throwable) {
                // don't leave half a file behind
                runCatching { dst.deleteOne(target, false) }
                throw t
            }
            if (e.modified > 0) runCatching { dst.setModified(target, e.modified) }
        }
    }

    private suspend fun pump(input: java.io.InputStream, output: java.io.OutputStream) {
        var last = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            progress = progress.copy(done = progress.done + n)
            // reporting every chunk would flood the UI with recompositions
            val now = System.nanoTime()
            if (now - last > 100_000_000) {
                last = now
                report(progress)
                currentCoroutineContext().ensureActive()
            }
        }
    }

    suspend fun delete(vfs: Vfs, entries: List<FileEntry>) {
        progress = Progress("Eliminando", total = entries.size.toLong())
        entries.forEachIndexed { i, e ->
            currentCoroutineContext().ensureActive()
            report(progress.copy(current = e.name, done = i.toLong()))
            vfs.delete(e)
        }
    }

    /** Zips [entries] (which share a folder) into [zipPath], keeping folder structure. */
    suspend fun zip(vfs: Vfs, entries: List<FileEntry>, zipPath: String) {
        progress = Progress("Comprimiendo", total = tally(vfs, entries).bytes)
        report(progress)
        try {
            ZipOutputStream(BufferedOutputStream(vfs.openWrite(zipPath))).use { zip ->
                suspend fun add(e: FileEntry, prefix: String) {
                    currentCoroutineContext().ensureActive()
                    val name = prefix + e.name
                    if (e.isDir) {
                        zip.putNextEntry(ZipEntry("$name/").apply { time = e.modified })
                        zip.closeEntry()
                        for (c in vfs.list(e.path)) add(c, "$name/")
                    } else {
                        progress = progress.copy(current = e.name)
                        zip.putNextEntry(ZipEntry(name).apply { time = e.modified })
                        vfs.openRead(e.path).use { pump(it, zip) }
                        zip.closeEntry()
                    }
                }
                for (e in entries) add(e, "")
            }
        } catch (t: Throwable) {
            runCatching { vfs.deleteOne(zipPath, false) }
            throw t
        }
    }

    /** Extracts into a new folder named after the zip; returns it. Entries can't escape it ("zip slip"). */
    suspend fun unzip(vfs: Vfs, zipEntry: FileEntry): String {
        val dir = zipEntry.parent
        val base = zipEntry.name.substringBeforeLast('.')
        val out = Paths.join(dir, Paths.freeName(base, true) { vfs.exists(Paths.join(dir, it)) })
        vfs.mkdir(out)
        progress = Progress("Extrayendo")
        report(progress)
        ZipInputStream(BufferedInputStream(vfs.openRead(zipEntry.path))).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val ze = zip.nextEntry ?: break
                val rel = ze.name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
                if (rel.isEmpty() || ".." in rel) continue
                var cur = out
                for ((i, part) in rel.withIndex()) {
                    val next = Paths.join(cur, part)
                    val last = i == rel.lastIndex
                    if (!last || ze.isDirectory) {
                        if (!vfs.exists(next)) vfs.mkdir(next)
                    } else {
                        progress = progress.copy(current = part)
                        vfs.openWrite(next).use { pump(zip, it) }
                        if (ze.time > 0) runCatching { vfs.setModified(next, ze.time) }
                    }
                    cur = next
                }
            }
        }
        return out
    }
}

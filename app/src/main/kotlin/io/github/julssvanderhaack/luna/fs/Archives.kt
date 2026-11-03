package io.github.julssvanderhaack.luna.fs

import io.github.julssvanderhaack.luna.tr
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ArchiveOutputStream
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Date

enum class ArchiveFormat(val ext: String, val label: String) {
    ZIP(".zip", "ZIP"),
    TAR_GZ(".tar.gz", "tar.gz"),
    TAR_XZ(".tar.xz", tr("tar.xz (más pequeño, más lento)")),
    SEVEN_Z(".7z", "7z"),
}

/** One member of an archive; [path] has no leading or trailing slash. */
data class ArchiveItem(val path: String, val isDir: Boolean, val size: Long, val modified: Long) {
    val name get() = path.substringAfterLast('/')
}

/**
 * Zip, 7z and tar (plain, gz, bz2, xz, zst-less) through Apache Commons
 * Compress, like desktop Luna does through libarchive: list as a tree, take
 * one member out, extract everything or create one.
 */
object Archives {
    private val multiExt = listOf(".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst", ".tar.lz")

    /** "fotos.tar.gz" → "fotos". */
    fun stem(name: String): String {
        val lower = name.lowercase()
        multiExt.firstOrNull { lower.endsWith(it) }?.let { return name.dropLast(it.length) }
        return name.substringBeforeLast('.').ifEmpty { name }
    }

    /** What Luna can open (rar isn't: its format is closed). */
    fun canOpen(name: String): Boolean {
        val l = name.lowercase()
        return listOf(".zip", ".7z", ".tar", ".tgz", ".tbz2", ".txz", ".gz", ".bz2", ".xz", ".jar", ".apk", ".cbz", ".epub")
            .any { l.endsWith(it) } || multiExt.any { l.endsWith(it) }
    }

    private enum class Type { ZIP, SEVEN_Z, STREAM }

    private fun type(f: File): Type {
        val head = ByteArray(6)
        val n = FileInputStream(f).use { it.read(head) }
        return when {
            n >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> Type.ZIP
            n >= 6 && head.contentEquals(byteArrayOf('7'.code.toByte(), 'z'.code.toByte(), 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)) -> Type.SEVEN_Z
            n >= 4 && head[0] == 'R'.code.toByte() && head[1] == 'a'.code.toByte() && head[2] == 'r'.code.toByte() ->
                throw IOException(tr("Los RAR no se pueden abrir: su formato es cerrado"))
            else -> Type.STREAM
        }
    }

    private fun zip(f: File) = ZipFile.builder().setFile(f).get()
    private fun sevenZ(f: File) = SevenZFile.builder().setFile(f).get()

    /** tar (compressed or not), or a single compressed file presented as one member. */
    private fun <T> stream(f: File, single: (InputStream, String) -> T, archive: (ArchiveInputStream<out ArchiveEntry>) -> T): T {
        var input: InputStream = BufferedInputStream(FileInputStream(f))
        try {
            val compressed = runCatching { CompressorStreamFactory.detect(input) }.getOrNull()
            if (compressed != null) input = BufferedInputStream(CompressorStreamFactory().createCompressorInputStream(compressed, input))
            val kind = runCatching { ArchiveStreamFactory.detect(input) }.getOrNull()
            return when {
                kind != null -> archive(ArchiveStreamFactory().createArchiveInputStream(kind, input))
                compressed != null -> single(input, stem(f.name))
                else -> throw IOException(tr("No es un archivo comprimido que Luna conozca"))
            }
        } finally {
            input.close()
        }
    }

    private fun clean(name: String): String? {
        val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || ".." in parts) return null
        return parts.joinToString("/")
    }

    fun list(f: File): List<ArchiveItem> = when (type(f)) {
        Type.ZIP -> zip(f).use { z ->
            z.entries.toList().mapNotNull { e -> clean(e.name)?.let { ArchiveItem(it, e.isDirectory, e.size.coerceAtLeast(0), e.time) } }
        }
        Type.SEVEN_Z -> sevenZ(f).use { z ->
            z.entries.mapNotNull { e ->
                clean(e.name)?.let { ArchiveItem(it, e.isDirectory, e.size, if (e.hasLastModifiedDate) e.lastModifiedDate.time else 0) }
            }
        }
        Type.STREAM -> stream(f, { _, name -> listOf(ArchiveItem(name, false, -1, f.lastModified())) }) { a ->
            generateSequence { a.nextEntry }.mapNotNull { e ->
                clean(e.name)?.let { ArchiveItem(it, e.isDirectory, e.size.coerceAtLeast(0), e.lastModifiedDate?.time ?: 0) }
            }.toList()
        }
    }

    /** Copies member [path] of [f] to [out]. */
    fun extractOne(f: File, path: String, out: File) {
        out.parentFile?.mkdirs()
        when (type(f)) {
            Type.ZIP -> zip(f).use { z ->
                val e = z.entries.toList().firstOrNull { clean(it.name) == path } ?: throw IOException(tr("No está en el archivo"))
                z.getInputStream(e).use { i -> out.outputStream().use { i.copyTo(it) } }
            }
            Type.SEVEN_Z -> sevenZ(f).use { z ->
                val e = z.entries.firstOrNull { clean(it.name) == path } ?: throw IOException(tr("No está en el archivo"))
                z.getInputStream(e).use { i -> out.outputStream().use { i.copyTo(it) } }
            }
            Type.STREAM -> stream(f, { i, _ -> out.outputStream().use { i.copyTo(it) } }) { a ->
                while (true) {
                    val e = a.nextEntry ?: throw IOException(tr("No está en el archivo"))
                    if (clean(e.name) == path) {
                        out.outputStream().use { a.copyTo(it) }
                        break
                    }
                }
            }
        }
    }

    /** Everything in [f] into [outDir] on [vfs]. */
    suspend fun extract(f: File, vfs: Vfs, outDir: String, ops: FileOps) {
        val made = HashSet<String>()
        fun dirs(rel: List<String>): String {
            var cur = outDir
            for (p in rel) {
                cur = Paths.join(cur, p)
                if (made.add(cur) && !vfs.exists(cur)) vfs.mkdir(cur)
            }
            return cur
        }
        suspend fun one(name: String, isDir: Boolean, time: Long, input: InputStream) {
            currentCoroutineContext().ensureActive()
            val rel = clean(name)?.split('/') ?: return
            if (isDir) {
                dirs(rel)
                return
            }
            val target = Paths.join(dirs(rel.dropLast(1)), rel.last())
            ops.current(rel.last())
            vfs.openWrite(target).use { ops.pump(input, it) }
            if (time > 0) runCatching { vfs.setModified(target, time) }
        }
        when (type(f)) {
            Type.ZIP -> zip(f).use { z ->
                val all = z.entries.toList()
                ops.begin(tr("Extrayendo"), all.sumOf { it.size.coerceAtLeast(0) })
                for (e in all) z.getInputStream(e).use { one(e.name, e.isDirectory, e.time, it) }
            }
            Type.SEVEN_Z -> sevenZ(f).use { z ->
                ops.begin(tr("Extrayendo"), z.entries.sumOf { it.size })
                for (e in z.entries) z.getInputStream(e).use { one(e.name, e.isDirectory, if (e.hasLastModifiedDate) e.lastModifiedDate.time else 0, it) }
            }
            Type.STREAM -> {
                ops.begin(tr("Extrayendo"))
                // like stream(), but one() suspends, so it can't go in its plain lambdas
                var input: InputStream = BufferedInputStream(FileInputStream(f))
                try {
                    val compressed = runCatching { CompressorStreamFactory.detect(input) }.getOrNull()
                    if (compressed != null) input = BufferedInputStream(CompressorStreamFactory().createCompressorInputStream(compressed, input))
                    val kind = runCatching { ArchiveStreamFactory.detect(input) }.getOrNull()
                    if (kind == null) {
                        if (compressed == null) throw IOException(tr("No es un archivo comprimido que Luna conozca"))
                        one(stem(f.name), false, f.lastModified(), input)
                    } else {
                        val a: ArchiveInputStream<out ArchiveEntry> = ArchiveStreamFactory().createArchiveInputStream(kind, input)
                        while (true) {
                            val e = a.nextEntry ?: break
                            one(e.name, e.isDirectory, e.lastModifiedDate?.time ?: 0, a)
                        }
                    }
                } finally {
                    input.close()
                }
            }
        }
    }

    /** [entries] (sharing a folder) into a new archive at [target] on [vfs]. */
    suspend fun create(vfs: Vfs, entries: List<FileEntry>, target: String, format: ArchiveFormat, ops: FileOps) {
        when (format) {
            ArchiveFormat.ZIP -> ZipArchiveOutputStream(BufferedOutputStream(vfs.openWrite(target))).use { z ->
                z.setEncoding("UTF-8")
                write(vfs, entries, z, ops) { name, e -> ZipArchiveEntry(if (e.isDir) "$name/" else name).apply { time = e.modified; if (!e.isDir) size = e.size } }
            }
            ArchiveFormat.TAR_GZ -> tar(GzipCompressorOutputStream(BufferedOutputStream(vfs.openWrite(target)))).use { writeTar(vfs, entries, it, ops) }
            ArchiveFormat.TAR_XZ -> tar(XZCompressorOutputStream(BufferedOutputStream(vfs.openWrite(target)))).use { writeTar(vfs, entries, it, ops) }
            ArchiveFormat.SEVEN_Z -> {
                // 7z writes its index at the start, after the data: it needs a file it can seek in
                val tmp = File.createTempFile("luna", ".7z")
                try {
                    SevenZOutputFile(tmp).use { z ->
                        suspend fun add(e: FileEntry, prefix: String) {
                            currentCoroutineContext().ensureActive()
                            val name = prefix + e.name
                            val ze = SevenZArchiveEntry().apply {
                                this.name = name
                                isDirectory = e.isDir
                                lastModifiedDate = Date(e.modified)
                            }
                            z.putArchiveEntry(ze)
                            if (!e.isDir) {
                                ops.current(e.name)
                                vfs.openRead(e.path).use { input ->
                                    val out = object : OutputStream() {
                                        override fun write(b: Int) = z.write(b)
                                        override fun write(b: ByteArray, off: Int, len: Int) = z.write(b, off, len)
                                    }
                                    ops.pump(input, out)
                                }
                            }
                            z.closeArchiveEntry()
                            if (e.isDir) for (c in vfs.list(e.path)) add(c, "$name/")
                        }
                        for (e in entries) add(e, "")
                    }
                    tmp.inputStream().use { i -> vfs.openWrite(target).use { i.copyTo(it) } }
                } finally {
                    tmp.delete()
                }
            }
        }
    }

    fun tar(out: OutputStream) = TarArchiveOutputStream(out).apply {
        setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
        setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
        setAddPaxHeadersForNonAsciiNames(true)
    }

    suspend fun writeTar(vfs: Vfs, entries: List<FileEntry>, out: TarArchiveOutputStream, ops: FileOps) =
        write(vfs, entries, out, ops) { name, e ->
            TarArchiveEntry(if (e.isDir) "$name/" else name).apply {
                setModTime(e.modified)
                if (!e.isDir) size = e.size
            }
        }

    private suspend fun <E : ArchiveEntry> write(
        vfs: Vfs, entries: List<FileEntry>, out: ArchiveOutputStream<E>, ops: FileOps, make: (String, FileEntry) -> E,
    ) {
        suspend fun add(e: FileEntry, prefix: String) {
            currentCoroutineContext().ensureActive()
            val name = prefix + e.name
            if (e.isDir) {
                out.putArchiveEntry(make(name, e))
                out.closeArchiveEntry()
                for (c in vfs.list(e.path)) add(c, "$name/")
            } else {
                ops.current(e.name)
                out.putArchiveEntry(make(name, e))
                vfs.openRead(e.path).use { ops.pump(it, out) }
                out.closeArchiveEntry()
            }
        }
        for (e in entries) add(e, "")
    }

    /** A tar stream (from a decrypted .tar.gpg) into [outDir] on [vfs]. */
    suspend fun readTar(input: InputStream, vfs: Vfs, outDir: String, ops: FileOps) {
        val a = TarArchiveInputStream(input)
        val made = HashSet<String>()
        while (true) {
            currentCoroutineContext().ensureActive()
            val e = a.nextEntry ?: break
            val rel = clean(e.name)?.split('/') ?: continue
            var cur = outDir
            for ((i, p) in rel.withIndex()) {
                cur = Paths.join(cur, p)
                val last = i == rel.lastIndex
                if (!last || e.isDirectory) {
                    if (made.add(cur) && !vfs.exists(cur)) vfs.mkdir(cur)
                } else {
                    ops.current(p)
                    vfs.openWrite(cur).use { ops.pump(a, it) }
                    runCatching { vfs.setModified(cur, e.modTime.time) }
                }
            }
        }
    }
}

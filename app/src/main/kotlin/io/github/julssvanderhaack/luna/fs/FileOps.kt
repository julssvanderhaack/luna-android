package io.github.julssvanderhaack.luna.fs

import io.github.julssvanderhaack.luna.tr
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException

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

/** A name that already exists where something is being pasted. */
data class Conflict(val incoming: FileEntry, val existing: FileEntry)

enum class Choice { REPLACE, KEEP_BOTH, SKIP }

data class Answer(val choice: Choice, val forAll: Boolean = false)

/**
 * What a paste did, item by item (source → the path it ended up at), so it
 * can be undone. [replaced] = something was overwritten: that can't be undone.
 */
data class Transfer(val pairs: List<Pair<String, String>>, val replaced: Boolean) {
    val created get() = pairs.map { it.second }
}

/**
 * Copy, move, delete and compress between any two [Vfs]: local↔local,
 * local↔remote, remote↔remote. A name that's already taken asks [ask]
 * (replace — merge for folders —, keep both as «nombre (2)», or skip), like
 * desktop Luna's paste. Every loop checks for cancellation.
 */
class FileOps(
    private val report: (Progress) -> Unit,
    private val ask: suspend (Conflict) -> Answer = { Answer(Choice.KEEP_BOTH, true) },
) {
    private val buf = ByteArray(256 * 1024)
    private var progress = Progress("")
    private var allFiles: Choice? = null
    private var allDirs: Choice? = null

    suspend fun tally(vfs: Vfs, entries: List<FileEntry>): Tally {
        var t = Tally()
        for (e in entries) {
            currentCoroutineContext().ensureActive()
            t += if (e.isDir && !e.isLink) Tally(folders = 1) + tally(vfs, vfs.list(e.path)) else Tally(files = 1, bytes = e.size)
        }
        return t
    }

    suspend fun transfer(src: Vfs, entries: List<FileEntry>, dst: Vfs, dstDir: String, move: Boolean): Transfer {
        for (e in entries) if (e.isDir && src.id == dst.id && Paths.isInside(dstDir, e.path)) {
            throw IOException(if (move) tr("No se puede mover «%1» dentro de sí misma", e.name) else tr("No se puede copiar «%1» dentro de sí misma", e.name))
        }
        progress = Progress(if (move) tr("Moviendo") else tr("Copiando"))
        // a move inside one file system is a rename, nothing to count
        val sameFs = src.id == dst.id
        if (!(move && sameFs)) progress = progress.copy(total = tally(src, entries).bytes)
        report(progress)
        val state = Placed()
        for (e in entries) place(src, e, dst, norm(dstDir), move, sameFs, state)
        return Transfer(state.pairs, state.replaced)
    }

    private class Placed {
        val pairs = ArrayList<Pair<String, String>>()
        var replaced = false
    }

    private fun norm(dir: String) = dir.trimEnd('/').ifEmpty { "/" }

    private suspend fun decide(c: Conflict): Choice {
        (if (c.incoming.isDir) allDirs else allFiles)?.let { return it }
        val a = ask(c)
        if (a.forAll) if (c.incoming.isDir) allDirs = a.choice else allFiles = a.choice
        return a.choice
    }

    private suspend fun place(src: Vfs, e: FileEntry, dst: Vfs, dir: String, move: Boolean, sameFs: Boolean, st: Placed) {
        currentCoroutineContext().ensureActive()
        val here = sameFs && e.parent == dir
        if (move && here) return
        var name = e.name
        val existing = dst.stat(Paths.join(dir, name))
        if (existing != null) {
            // pasting a copy next to its original: always "nombre (2)"
            val choice = if (here) Choice.KEEP_BOTH else decide(Conflict(e, existing))
            when (choice) {
                Choice.SKIP -> {
                    progress = progress.copy(done = progress.done + if (e.isDir) 0 else e.size)
                    return
                }
                Choice.KEEP_BOTH -> name = Paths.freeName(e.name, e.isDir) { dst.exists(Paths.join(dir, it)) }
                Choice.REPLACE -> if (e.isDir && existing.isDir) {
                    // folders merge: what's inside is placed one by one, asking again on clashes
                    val into = Paths.join(dir, name)
                    for (c in src.list(e.path)) place(src, c, dst, into, move, sameFs, st)
                    if (move) runCatching { if (src.list(e.path).isEmpty()) src.deleteOne(e.path, true) }
                    return
                } else {
                    dst.delete(existing)
                    st.replaced = true
                }
            }
        }
        val target = Paths.join(dir, name)
        if (move && sameFs && src.move(e.path, target)) {
            st.pairs += e.path to target
            return
        }
        copyTree(src, e, dst, target)
        st.pairs += e.path to target
        if (move) src.delete(e)
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

    suspend fun pump(input: java.io.InputStream, output: java.io.OutputStream) {
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

    /** Lets [Archives] and [Pgp] share the progress card. */
    fun begin(title: String, total: Long = 0) {
        progress = Progress(title, total = total)
        report(progress)
    }

    fun current(name: String) {
        progress = progress.copy(current = name)
        report(progress)
    }

    suspend fun delete(vfs: Vfs, entries: List<FileEntry>) {
        progress = Progress(tr("Eliminando"), total = entries.size.toLong())
        entries.forEachIndexed { i, e ->
            currentCoroutineContext().ensureActive()
            report(progress.copy(current = e.name, done = i.toLong()))
            vfs.delete(e)
        }
    }

    /** Local files into the trash of the volume each is on ([rootOf]). */
    suspend fun trash(entries: List<FileEntry>, rootOf: (String) -> String?): List<TrashItem> {
        progress = Progress(tr("Moviendo a la papelera"), total = entries.size.toLong())
        val out = ArrayList<TrashItem>()
        entries.forEachIndexed { i, e ->
            currentCoroutineContext().ensureActive()
            report(progress.copy(current = e.name, done = i.toLong()))
            val root = rootOf(e.path) ?: throw IOException(tr("«%1» no está en un almacenamiento con papelera", e.name))
            out += Trash.put(File(e.path), root)
        }
        return out
    }

    /** Compresses [entries] (which share a folder) into [target], keeping folder structure. */
    suspend fun compress(vfs: Vfs, entries: List<FileEntry>, target: String, format: ArchiveFormat) {
        begin(tr("Comprimiendo"), tally(vfs, entries).bytes)
        try {
            Archives.create(vfs, entries, target, format, this)
        } catch (t: Throwable) {
            runCatching { vfs.deleteOne(target, false) }
            throw t
        }
    }

    /**
     * Extracts [archive] (a local copy of [entry]) into a new folder next to
     * [entry] on [vfs], named after it; returns that folder. Entries can't
     * escape it ("zip slip").
     */
    suspend fun extract(vfs: Vfs, entry: FileEntry, archive: File): String {
        val dir = entry.parent
        val out = Paths.join(dir, Paths.freeName(Archives.stem(entry.name), true) { vfs.exists(Paths.join(dir, it)) })
        vfs.mkdir(out)
        try {
            Archives.extract(archive, vfs, out, this)
        } catch (t: Throwable) {
            runCatching { vfs.delete(FileEntry(Paths.name(out), out, true, 0, 0)) }
            throw t
        }
        return out
    }

    /** Counts bytes read from an archive towards the progress bar. */
    fun advance(n: Long) {
        progress = progress.copy(done = progress.done + n)
    }
}

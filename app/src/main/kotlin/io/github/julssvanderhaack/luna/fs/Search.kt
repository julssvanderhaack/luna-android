package io.github.julssvanderhaack.luna.fs

import io.github.julssvanderhaack.luna.tr
import java.io.File

enum class Age(val label: String, val millis: Long) {
    ANY(tr("Cualquier fecha"), 0),
    DAY(tr("Últimas 24 horas"), 86_400_000),
    WEEK(tr("Última semana"), 7 * 86_400_000L),
    MONTH(tr("Último mes"), 30 * 86_400_000L),
    YEAR(tr("Último año"), 365 * 86_400_000L),
}

enum class SizeRange(val label: String, val min: Long, val max: Long) {
    ANY(tr("Cualquier tamaño"), 0, Long.MAX_VALUE),
    SMALL(tr("Menos de 1 MB"), 0, 1_000_000),
    MEDIUM(tr("De 1 a 100 MB"), 1_000_000, 100_000_000),
    LARGE(tr("Más de 100 MB"), 100_000_000, Long.MAX_VALUE),
}

/** Desktop Luna's search options: inside the files, modified since, size. */
data class SearchFilter(val contents: Boolean = false, val age: Age = Age.ANY, val size: SizeRange = SizeRange.ANY) {
    val narrows get() = age != Age.ANY || size != SizeRange.ANY

    fun keeps(e: FileEntry, now: Long = System.currentTimeMillis()): Boolean {
        if (age != Age.ANY && now - e.modified > age.millis) return false
        // a size limit is about files: folders don't have one
        if (size != SizeRange.ANY && (e.isDir || e.size < size.min || e.size >= size.max)) return false
        return true
    }
}

object ContentSearch {
    private const val MAX = 32L * 1024 * 1024

    /** Text-like files worth reading: no photos, videos or zips, nothing huge. */
    fun worth(e: FileEntry) = !e.isDir && e.size in 1..MAX &&
        e.kind !in setOf(Kind.IMAGE, Kind.VIDEO, Kind.AUDIO, Kind.ARCHIVE, Kind.APK, Kind.FONT, Kind.PDF, Kind.DOCUMENT, Kind.SHEET, Kind.SLIDES, Kind.EBOOK)

    /** True when [f]'s text contains [folded] (already run through [fold]); binary files never match. */
    fun contains(f: File, folded: String): Boolean = runCatching {
        f.inputStream().buffered().use { input ->
            val head = ByteArray(1024)
            input.mark(head.size)
            val n = input.read(head)
            if (n > 0 && head.take(n).any { it == 0.toByte() }) return false
            input.reset()
            input.reader(Charsets.UTF_8).buffered().useLines { lines -> lines.any { fold(it).contains(folded) } }
        }
    }.getOrDefault(false)
}

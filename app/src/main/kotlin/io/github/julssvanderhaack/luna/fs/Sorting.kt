package io.github.julssvanderhaack.luna.fs

import java.text.Normalizer

enum class SortBy { NAME, DATE, SIZE, TYPE }

data class SortOrder(val by: SortBy = SortBy.NAME, val descending: Boolean = false, val foldersFirst: Boolean = true)

private val MARKS = Regex("\\p{M}+")

/** Lower-case, accents removed: "Álbum" and "album" sort and search the same. */
fun fold(s: String): String {
    // most names are plain ASCII: skip the Unicode normaliser for them
    if (s.all { it < '\u0080' }) return s.lowercase()
    return MARKS.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), "")
}

/** "foto 2" before "foto 10", like desktop Luna. */
fun naturalCompare(a: String, b: String): Int = naturalCompareFolded(fold(a), fold(b))

/** [naturalCompare] on names already passed through [fold]. */
fun naturalCompareFolded(x: String, y: String): Int {
    var i = 0
    var j = 0
    while (i < x.length && j < y.length) {
        val c = x[i]
        val d = y[j]
        if (c.isDigit() && d.isDigit()) {
            val si = i
            val sj = j
            while (i < x.length && x[i].isDigit()) i++
            while (j < y.length && y[j].isDigit()) j++
            val n1 = x.substring(si, i).trimStart('0')
            val n2 = y.substring(sj, j).trimStart('0')
            if (n1.length != n2.length) return n1.length - n2.length
            val cmp = n1.compareTo(n2)
            if (cmp != 0) return cmp
        } else {
            if (c != d) return c - d
            i++
            j++
        }
    }
    return (x.length - i) - (y.length - j)
}

fun sortEntries(list: List<FileEntry>, order: SortOrder): List<FileEntry> {
    // fold each name once, not on every comparison (a 2000-file folder makes ~40 000)
    class Keyed(val e: FileEntry, val key: String)
    val byName = Comparator<Keyed> { a, b -> naturalCompareFolded(a.key, b.key) }
    val main: Comparator<Keyed> = when (order.by) {
        SortBy.NAME -> byName
        SortBy.DATE -> compareBy<Keyed> { it.e.modified }.then(byName)
        SortBy.SIZE -> compareBy<Keyed> { if (it.e.isDir) -1 else it.e.size }.then(byName)
        SortBy.TYPE -> compareBy<Keyed> { it.e.ext }.then(byName)
    }
    val directed = if (order.descending) main.reversed() else main
    val full = if (order.foldersFirst) compareBy<Keyed> { !it.e.isDir }.then(directed) else directed
    return list.map { Keyed(it, fold(it.name)) }.sortedWith(full).map { it.e }
}

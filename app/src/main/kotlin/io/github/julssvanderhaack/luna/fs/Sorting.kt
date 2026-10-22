package io.github.julssvanderhaack.luna.fs

import java.text.Normalizer

enum class SortBy { NAME, DATE, SIZE, TYPE }

data class SortOrder(val by: SortBy = SortBy.NAME, val descending: Boolean = false, val foldersFirst: Boolean = true)

/** Lower-case, accents removed: "Álbum" and "album" sort and search the same. */
fun fold(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

/** "foto 2" before "foto 10", like desktop Luna. */
fun naturalCompare(a: String, b: String): Int {
    val x = fold(a)
    val y = fold(b)
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
    val byName = Comparator<FileEntry> { a, b -> naturalCompare(a.name, b.name) }
    val main: Comparator<FileEntry> = when (order.by) {
        SortBy.NAME -> byName
        SortBy.DATE -> compareBy<FileEntry> { it.modified }.then(byName)
        SortBy.SIZE -> compareBy<FileEntry> { if (it.isDir) -1 else it.size }.then(byName)
        SortBy.TYPE -> compareBy<FileEntry> { it.ext }.then(byName)
    }
    val directed = if (order.descending) main.reversed() else main
    val full = if (order.foldersFirst) compareBy<FileEntry> { !it.isDir }.then(directed) else directed
    return list.sortedWith(full)
}

package io.github.julssvanderhaack.luna.fs

import org.json.JSONObject
import java.io.File

/**
 * Folders you go to, ranked like zoxide (visits weighted by how recent they
 * are), for "Ir a una carpeta". Keys are Loc keys (local paths, "sftp:id:path").
 */
class Visits(dir: File) {
    private val file = File(dir, "visits.json")
    private data class Visit(var count: Double, var last: Long)
    private val map: MutableMap<String, Visit> = load()
    private var dirty = 0

    private fun load(): MutableMap<String, Visit> = runCatching {
        val o = JSONObject(file.readText())
        o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { Visit(it.getDouble(0), it.getLong(1)) } }.toMutableMap()
    }.getOrDefault(HashMap())

    @Synchronized
    fun add(key: String, now: Long = System.currentTimeMillis()) {
        val v = map.getOrPut(key) { Visit(0.0, now) }
        v.count += 1
        v.last = now
        // like zoxide: once the total gets big, everything shrinks and the forgotten drop out
        if (map.values.sumOf { it.count } > 2000) {
            map.values.forEach { it.count *= 0.9 }
            map.entries.removeAll { it.value.count < 1 }
        }
        if (++dirty >= 3) save()
    }

    @Synchronized
    fun forget(key: String) {
        map.remove(key)
        save()
    }

    @Synchronized
    fun save() {
        dirty = 0
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, org.json.JSONArray().put(v.count).put(v.last)) }
        runCatching { file.writeText(o.toString()) }
    }

    /** Every key with its score right now. */
    @Synchronized
    fun scores(now: Long = System.currentTimeMillis()): Map<String, Double> = map.mapValues { (_, v) ->
        val age = now - v.last
        v.count * when {
            age < 3_600_000 -> 4.0
            age < 86_400_000 -> 2.0
            age < 7 * 86_400_000L -> 0.5
            else -> 0.25
        }
    }

    companion object {
        /**
         * Ranks [candidates] (key → shown path) for [query]: every word must
         * appear (accents and case ignored, any order); a last folder name equal
         * to the last word goes first, then by score.
         */
        fun rank(query: String, candidates: Map<String, String>, scores: Map<String, Double>): List<String> {
            val words = fold(query).split(' ', '/').filter { it.isNotEmpty() }
            if (words.isEmpty()) return candidates.keys.sortedByDescending { scores[it] ?: 0.0 }
            return candidates.filter { (_, shown) -> val f = fold(shown); words.all { f.contains(it) } }
                .keys.sortedWith(
                    compareByDescending<String> { fold(Paths.name(candidates.getValue(it))) == words.last() }
                        .thenByDescending { fold(Paths.name(candidates.getValue(it))).contains(words.last()) }
                        .thenByDescending { scores[it] ?: 0.0 }
                        .thenBy { candidates.getValue(it).length },
                )
        }
    }
}

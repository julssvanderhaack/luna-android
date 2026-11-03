package io.github.julssvanderhaack.luna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every tr("…") in the code has an English line, with the same %1, %2… */
class I18nTest {
    private fun unescape(s: String) = s.replace(Regex("\\\\(.)")) { m ->
        when (val c = m.groupValues[1]) {
            "n" -> "\n"
            "t" -> "\t"
            else -> c
        }
    }

    private val table = File("src/main/assets/i18n/en.tsv").readLines()
        .filter { '\t' in it && !it.startsWith("#") }
        .associate { l -> l.substringBefore('\t').let(::unescape) to l.substringAfter('\t').let(::unescape) }

    @Test
    fun everyStringIsTranslated() {
        val call = Regex("""\btr\(\s*"((?:[^"\\]|\\.)*)"""")
        val missing = File("src/main/kotlin").walk().filter { it.extension == "kt" }
            .flatMap { f -> call.findAll(f.readText()).map { unescape(it.groupValues[1]) } }
            .filter { it !in table }.toSet()
        assertTrue("Missing in en.tsv: $missing", missing.isEmpty())
    }

    @Test
    fun placeholdersMatch() {
        val ph = Regex("%\\d")
        table.forEach { (es, en) ->
            assertEquals(en, ph.findAll(es).map { it.value }.sorted().toList(), ph.findAll(en).map { it.value }.sorted().toList())
        }
    }

    @Test
    fun replacesPlaceholders() {
        assertEquals("«a» y b", tr("«%1» y %2", "a", "b"))
        assertEquals("100 % de 7", tr("100 % de %1", 7))
    }
}

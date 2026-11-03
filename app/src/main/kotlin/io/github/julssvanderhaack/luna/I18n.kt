package io.github.julssvanderhaack.luna

import android.content.Context
import java.util.Locale

/**
 * Luna is written in Spanish, like the desktop version; other languages come from
 * assets/i18n/<lang>.tsv ("source<TAB>translation", \n and \t escaped). A string missing
 * from the table is shown in Spanish. The language is chosen in Ajustes → Idioma; by
 * default Spanish if the phone is in Spanish, English otherwise.
 */
object I18n {
    val LANGUAGES = listOf("" to "Automático", "es" to "Español", "en" to "English")
    private const val PREFS = "i18n"

    @Volatile
    private var table: Map<String, String> = emptyMap()

    /** "es" or "en": what is shown now. */
    var lang = "es"
        private set
    val locale: Locale get() = if (lang == "es") Locale.forLanguageTag("es-ES") else Locale.forLanguageTag("en-US")

    fun chosen(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lang", "") ?: ""

    fun choose(ctx: Context, lang: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("lang", lang).commit()
    }

    fun init(ctx: Context) {
        lang = chosen(ctx).ifEmpty { if (Locale.getDefault().language == "es") "es" else "en" }
        table = if (lang == "es") emptyMap() else runCatching {
            ctx.assets.open("i18n/$lang.tsv").bufferedReader().useLines { lines ->
                lines.mapNotNull { l ->
                    val tab = l.indexOf('\t')
                    if (tab <= 0 || l.startsWith("#")) null else unescape(l.substring(0, tab)) to unescape(l.substring(tab + 1))
                }.toMap()
            }
        }.getOrDefault(emptyMap())
    }

    private fun unescape(s: String) = if ('\\' !in s) s else buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> append('\n')
                    't' -> append('\t')
                    else -> append(s[i + 1])
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    fun lookup(s: String) = table[s] ?: s
}

private val PLACEHOLDER = Regex("%(\\d)")

/** The text in the current language, with %1, %2… replaced by [a]. */
fun tr(s: String, vararg a: Any?): String {
    val t = I18n.lookup(s)
    if (a.isEmpty()) return t
    return PLACEHOLDER.replace(t) { m -> a.getOrNull(m.groupValues[1].toInt() - 1)?.toString() ?: m.value }
}

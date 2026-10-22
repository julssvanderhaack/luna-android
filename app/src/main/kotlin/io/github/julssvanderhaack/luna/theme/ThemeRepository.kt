package io.github.julssvanderhaack.luna.theme

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer

/**
 * Built-in themes are the Omarchy colors.toml files in assets/themes (see
 * tools/import-omarchy-themes.sh); the user's own live in files/themes. Both
 * use the same format, so a theme can travel between desktop and phone.
 */
class ThemeRepository(private val context: Context) {
    private val userDir = File(context.filesDir, "themes")
    private val _themes = MutableStateFlow<List<Palette>>(emptyList())
    val themes: StateFlow<List<Palette>> = _themes

    suspend fun load() = withContext(Dispatchers.IO) {
        val builtIn = context.assets.list("themes").orEmpty()
            .filter { it.endsWith(".toml") }
            .map { file ->
                val text = context.assets.open("themes/$file").bufferedReader().use { it.readText() }
                Palette.parseToml(file.removeSuffix(".toml"), text, builtIn = true)
            }
        val own = userDir.listFiles { f -> f.extension == "toml" }.orEmpty()
            .map { Palette.parseToml(it.nameWithoutExtension, it.readText()) }
        _themes.value = (own.sortedBy { it.name } + builtIn.sortedBy { it.name })
    }

    fun find(name: String?): Palette? = _themes.value.firstOrNull { it.name == name }

    /** Saves [palette] as a user theme, renaming it if it would shadow a built-in one. Returns the saved name. */
    suspend fun save(palette: Palette, replacing: String? = null): String = withContext(Dispatchers.IO) {
        val name = freeName(slug(palette.name), except = replacing)
        userDir.mkdirs()
        if (replacing != null && replacing != name) File(userDir, "$replacing.toml").delete()
        File(userDir, "$name.toml").writeText(palette.copy(name = name).toToml())
        load()
        name
    }

    suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        File(userDir, "$name.toml").delete()
        load()
    }

    /** Imports a colors.toml picked with the system file chooser. Returns the new theme's name, or null if it had no colours. */
    suspend fun import(uri: Uri, suggestedName: String): String? = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: return@withContext null
        val p = Palette.parseToml(suggestedName, text)
        if (p.color("background") == null && p.color("foreground") == null) return@withContext null
        save(p)
    }

    suspend fun export(palette: Palette, uri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(palette.toToml()) }
    }

    private fun freeName(base: String, except: String?): String {
        val taken = _themes.value.map { it.name }.toSet() - setOfNotNull(except)
        if (base !in taken) return base
        var i = 2
        while ("$base-$i" in taken) i++
        return "$base-$i"
    }

    companion object {
        fun slug(name: String): String {
            val plain = Normalizer.normalize(name.trim().lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
            return plain.replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "tema" }
        }

        /** "tokyo-night" → "Tokyo Night". */
        fun displayName(name: String) =
            name.split('-', '_').filter { it.isNotEmpty() }
                .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }
}

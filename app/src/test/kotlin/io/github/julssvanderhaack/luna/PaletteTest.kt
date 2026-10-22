package io.github.julssvanderhaack.luna

import io.github.julssvanderhaack.luna.theme.Palette
import io.github.julssvanderhaack.luna.theme.Rgb
import io.github.julssvanderhaack.luna.theme.ThemeRepository
import io.github.julssvanderhaack.luna.theme.contrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PaletteTest {
    private val themeDir = File("src/main/assets/themes")

    @Test
    fun parsesOmarchyToml() {
        val p = Palette.parseToml(
            "x",
            """
            mode = "light"
            # comment
            accent = "#89b4fa"
            background="#1e1e2e"
            not a colour line
            """.trimIndent(),
        )
        assertEquals("#89b4fa", p.values["accent"])
        assertEquals("#1e1e2e", p.values["background"])
        assertFalse(p.resolve().dark)
    }

    @Test
    fun rgbParsing() {
        assertEquals(0xaabbcc, Rgb.parse("#abc")!!.value)
        assertEquals(0x112233, Rgb.parse("112233ff")!!.value)
        assertNull(Rgb.parse("#12"))
        assertNull(Rgb.parse("zzzzzz"))
    }

    @Test
    fun oldStyleThemeUsesColorN() {
        // aetheria-style: no mode/selection/red, only colorN and selection_background
        val p = Palette.parseToml(
            "old",
            """
            accent = "#BE3F50"
            foreground = "#14B9B5"
            background = "#0e091d"
            selection_background = "#14B9B5"
            selection_foreground = "#0e091d"
            color1 = "#c8e967"
            color8 = "#c53253"
            """.trimIndent(),
        ).resolve()
        assertTrue(p.dark)
        assertEquals(Rgb(0x14b9b5), p.selection)
        assertEquals(Rgb(0x0e091d), p.onSelection)
        assertEquals(Rgb(0xc8e967), p.red)
    }

    @Test
    fun tomlRoundTripKeepsUnknownKeys() {
        val p = Palette.parseToml("t", "mode = \"dark\"\nhyprland_active_border = \"#ff0000\"\naccent = \"#00ff00\"\n")
        val back = Palette.parseToml("t", p.with("accent", "#0000ff").toToml())
        assertEquals("#ff0000", back.values["hyprland_active_border"])
        assertEquals("#0000ff", back.values["accent"])
        assertEquals("dark", back.values["mode"])
    }

    @Test
    fun everyBundledThemeIsReadable() {
        val files = themeDir.listFiles { f -> f.extension == "toml" }!!
        assertTrue("themes missing: run tools/import-omarchy-themes.sh", files.size >= 20)
        for (f in files) {
            val r = Palette.parseToml(f.nameWithoutExtension, f.readText()).resolve()
            val name = f.nameWithoutExtension
            assertTrue("$name: text on background", contrast(r.foreground, r.background) >= 4.5)
            assertTrue("$name: secondary text on background", contrast(r.foregroundDim, r.background) >= 2.5)
            assertTrue("$name: text on accent", contrast(r.onAccent, r.accent) >= 3.0)
            assertTrue("$name: text on selection", contrast(r.onSelection, r.selection) >= 3.0)
        }
    }

    @Test
    fun names() {
        assertEquals("Tokyo Night", ThemeRepository.displayName("tokyo-night"))
        assertEquals("mi-tema-azul", ThemeRepository.slug("  Mi Tema Azul! "))
        assertEquals("cancion", ThemeRepository.slug("Canción"))
    }
}

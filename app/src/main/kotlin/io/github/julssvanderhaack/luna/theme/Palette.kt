package io.github.julssvanderhaack.luna.theme

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** A colour as 0xRRGGBB, kept independent of Android/Compose so it can be unit tested. */
@JvmInline
value class Rgb(val value: Int) {
    val r get() = (value shr 16) and 0xff
    val g get() = (value shr 8) and 0xff
    val b get() = value and 0xff

    fun hex() = "#%06x".format(value and 0xffffff)

    /** WCAG relative luminance, 0..1. */
    fun luminance(): Double {
        fun ch(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)
    }

    /** Linear blend: t = 0 → this, t = 1 → other. */
    fun mix(other: Rgb, t: Double): Rgb {
        fun m(a: Int, b: Int) = (a + (b - a) * t).toInt().coerceIn(0, 255)
        return of(m(r, other.r), m(g, other.g), m(b, other.b))
    }

    companion object {
        fun of(r: Int, g: Int, b: Int) = Rgb((r shl 16) or (g shl 8) or b)

        /** Accepts #rgb, #rrggbb and #rrggbbaa (alpha ignored), with or without '#'. */
        fun parse(s: String?): Rgb? {
            if (s == null) return null
            var h = s.trim().removePrefix("#").removePrefix("0x")
            if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
            if (h.length == 8) h = h.substring(0, 6)
            if (h.length != 6) return null
            return h.toIntOrNull(16)?.let { Rgb(it) }
        }

        val WHITE = Rgb(0xffffff)
        val BLACK = Rgb(0x000000)
    }
}

fun contrast(a: Rgb, b: Rgb): Double {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

/** The candidate with the best contrast against [bg]; the first one wins if it reaches 4.5:1. */
fun readableOn(bg: Rgb, first: Rgb, second: Rgb): Rgb {
    val candidates = listOf(first, second)
    candidates.firstOrNull { contrast(it, bg) >= 4.5 }?.let { return it }
    return (candidates + listOf(Rgb.WHITE, Rgb.BLACK)).maxBy { contrast(it, bg) }
}

/**
 * An Omarchy colors.toml: the raw key → value map, as in the file, so that
 * keys the app doesn't use survive an edit + export round trip.
 */
data class Palette(
    val name: String,
    val values: Map<String, String>,
    val builtIn: Boolean = false,
) {
    fun color(key: String): Rgb? = Rgb.parse(values[key])

    fun with(key: String, value: String) = copy(values = values + (key to value))

    /** Colours the UI uses, with the same fallbacks as desktop Sonata (theme.cpp) plus old-style themes (color0..15). */
    fun resolve(): ResolvedPalette {
        val bg = color("background") ?: Rgb(0x1a1b26)
        val fg = color("foreground") ?: Rgb(0xc0caf5)
        val dark = when (values["mode"]?.trim()?.lowercase()) {
            "dark" -> true
            "light" -> false
            else -> bg.luminance() < 0.2
        }
        val toward = if (dark) Rgb.WHITE else Rgb.BLACK
        val bgLight = color("lighter_background") ?: bg.mix(fg, 0.10)
        val bgDark = color("dark_background") ?: bg.mix(if (dark) Rgb.BLACK else Rgb.WHITE, 0.25)
        val bgDarker = color("darker_background") ?: bgDark.mix(if (dark) Rgb.BLACK else Rgb.WHITE, 0.25)
        var fgDim = color("dark_foreground") ?: color("color8") ?: fg.mix(bg, 0.5)
        // fgDim in some themes is nearly the background; keep it readable
        if (abs(fgDim.luminance() - bg.luminance()) < 0.08 || contrast(fgDim, bg) < 2.5) fgDim = fg.mix(bg, 0.45)
        val accent = color("accent") ?: color("blue") ?: color("color4") ?: Rgb(0x7aa2f7)
        val selection = color("selection") ?: color("selection_background") ?: bgLight.mix(toward, 0.08)
        val onSelection = color("selection_foreground")?.takeIf { contrast(it, selection) >= 3.0 }
            ?: readableOn(selection, fg, bg)
        val muted = color("muted") ?: color("color8") ?: bgLight.mix(fg, 0.25)
        val red = color("red") ?: color("color1") ?: Rgb(0xf7768e)
        val tertiary = color("cyan") ?: color("magenta") ?: color("color6") ?: accent
        // file-type colours; only bright enough versions are any use on the background
        fun ansi(key: String, n: Int, fallback: Rgb): Rgb {
            val c = color(key) ?: color("color$n") ?: fallback
            return if (contrast(c, bg) < 2.0) c.mix(fg, 0.4) else c
        }
        return ResolvedPalette(
            dark = dark,
            background = bg,
            backgroundDark = bgDark,
            backgroundDarker = bgDarker,
            backgroundLight = bgLight,
            foreground = fg,
            foregroundDim = fgDim,
            accent = accent,
            onAccent = readableOn(accent, bg, fg),
            selection = selection,
            onSelection = onSelection,
            muted = muted,
            red = red,
            tertiary = tertiary,
            green = ansi("green", 2, Rgb(0x9ece6a)),
            yellow = ansi("yellow", 3, Rgb(0xe0af68)),
            blue = ansi("blue", 4, Rgb(0x7aa2f7)),
            magenta = ansi("magenta", 5, Rgb(0xbb9af7)),
            cyan = ansi("cyan", 6, Rgb(0x7dcfff)),
            orange = ansi("orange", 3, Rgb(0xff9e64)),
            redKind = ansi("red", 1, red),
        )
    }

    /** Every colour in the file, for the colour picker's quick choices. */
    fun swatches(): List<Rgb> = values.values.mapNotNull { Rgb.parse(it) }.distinct()

    fun toToml(): String = buildString {
        values["mode"]?.let { append("mode = \"").append(it).append("\"\n\n") }
        for ((k, v) in values) if (k != "mode") append(k).append(" = \"").append(v).append("\"\n")
    }

    companion object {
        /** Keys the theme editor shows, in this order. */
        val EDITABLE = listOf(
            "accent", "background", "dark_background", "lighter_background",
            "foreground", "dark_foreground", "selection", "muted", "red",
        )

        private val LINE = Regex("""^\s*([A-Za-z0-9_]+)\s*=\s*"([^"]*)"""")

        /** Same tolerant line parser desktop Sonata uses: only `key = "value"` lines matter. */
        fun parseToml(name: String, text: String, builtIn: Boolean = false): Palette {
            val map = LinkedHashMap<String, String>()
            for (line in text.lineSequence()) {
                val m = LINE.find(line) ?: continue
                map[m.groupValues[1]] = m.groupValues[2]
            }
            return Palette(name, map, builtIn)
        }
    }
}

data class ResolvedPalette(
    val dark: Boolean,
    val background: Rgb,
    val backgroundDark: Rgb,
    val backgroundDarker: Rgb,
    val backgroundLight: Rgb,
    val foreground: Rgb,
    val foregroundDim: Rgb,
    val accent: Rgb,
    val onAccent: Rgb,
    val selection: Rgb,
    val onSelection: Rgb,
    val muted: Rgb,
    val red: Rgb,
    val tertiary: Rgb,
    val green: Rgb = Rgb(0x9ece6a),
    val yellow: Rgb = Rgb(0xe0af68),
    val blue: Rgb = Rgb(0x7aa2f7),
    val magenta: Rgb = Rgb(0xbb9af7),
    val cyan: Rgb = Rgb(0x7dcfff),
    val orange: Rgb = Rgb(0xff9e64),
    val redKind: Rgb = Rgb(0xf7768e),
)

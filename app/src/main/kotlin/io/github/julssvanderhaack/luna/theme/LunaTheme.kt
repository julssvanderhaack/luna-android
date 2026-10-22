package io.github.julssvanderhaack.luna.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** The Omarchy palette itself, for what Material roles don't cover (file-type colours). */
val LocalPalette = staticCompositionLocalOf { Palette("default", emptyMap()).resolve() }

fun Rgb.compose() = Color(0xff000000 or value.toLong())

/** Omarchy palette → Material 3 roles. The mapping is documented in the README. */
fun ResolvedPalette.colorScheme(): ColorScheme {
    val toward = if (dark) Rgb.WHITE else Rgb.BLACK
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent.compose(),
        onPrimary = onAccent.compose(),
        primaryContainer = background.mix(accent, 0.28).compose(),
        onPrimaryContainer = foreground.compose(),
        inversePrimary = accent.compose(),
        secondary = accent.mix(foreground, 0.35).compose(),
        onSecondary = readableOn(accent.mix(foreground, 0.35), background, foreground).compose(),
        secondaryContainer = selection.compose(),
        onSecondaryContainer = onSelection.compose(),
        tertiary = tertiary.compose(),
        onTertiary = readableOn(tertiary, background, foreground).compose(),
        tertiaryContainer = background.mix(tertiary, 0.28).compose(),
        onTertiaryContainer = foreground.compose(),
        background = background.compose(),
        onBackground = foreground.compose(),
        surface = background.compose(),
        onSurface = foreground.compose(),
        surfaceVariant = backgroundLight.compose(),
        onSurfaceVariant = foregroundDim.compose(),
        surfaceTint = accent.compose(),
        surfaceDim = backgroundDark.compose(),
        surfaceBright = backgroundLight.mix(toward, 0.06).compose(),
        surfaceContainerLowest = backgroundDarker.compose(),
        surfaceContainerLow = backgroundDark.compose(),
        surfaceContainer = background.mix(backgroundLight, 0.5).compose(),
        surfaceContainerHigh = backgroundLight.compose(),
        surfaceContainerHighest = backgroundLight.mix(toward, 0.06).compose(),
        inverseSurface = foreground.compose(),
        inverseOnSurface = background.compose(),
        outline = muted.compose(),
        outlineVariant = background.mix(muted, 0.5).compose(),
        error = red.compose(),
        onError = readableOn(red, background, foreground).compose(),
        errorContainer = background.mix(red, 0.28).compose(),
        onErrorContainer = foreground.compose(),
    )
}

@Composable
fun LunaTheme(palette: ResolvedPalette, dynamic: Boolean, content: @Composable () -> Unit) {
    val scheme = if (dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val ctx = LocalContext.current
        if (palette.dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        palette.colorScheme()
    }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

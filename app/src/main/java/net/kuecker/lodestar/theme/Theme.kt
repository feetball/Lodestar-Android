package net.kuecker.lodestar.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding

/** The active colour palette (normal or night). Provided once at the root by [LodestarTheme]. */
val LocalPalette = staticCompositionLocalOf { Palette.normal }

/** Shorthand for the current palette inside composables. */
val palette: Palette
    @Composable @ReadOnlyComposable get() = LocalPalette.current

/** Material colours derived from a palette, so every stock component follows night mode (pure red, no white). */
fun Palette.colorScheme(): ColorScheme = darkColorScheme(
    primary = accent,
    onPrimary = onAccent,
    primaryContainer = surfaceRaised,
    onPrimaryContainer = primaryText,
    inversePrimary = accent,
    secondary = accent,
    onSecondary = onAccent,
    secondaryContainer = surfaceRaised,
    onSecondaryContainer = primaryText,
    tertiary = accent,
    onTertiary = onAccent,
    tertiaryContainer = surfaceRaised,
    onTertiaryContainer = primaryText,
    background = background,
    onBackground = primaryText,
    surface = background,
    onSurface = primaryText,
    surfaceVariant = surface,
    onSurfaceVariant = secondaryText,
    surfaceTint = Color.Transparent,
    inverseSurface = primaryText,
    inverseOnSurface = background,
    error = critical,
    onError = onAccent,
    errorContainer = surfaceRaised,
    onErrorContainer = critical,
    outline = stroke,
    outlineVariant = stroke,
    scrim = Color.Black,
    surfaceBright = surfaceRaised,
    surfaceDim = background,
    surfaceContainer = surface,
    surfaceContainerHigh = surfaceRaised,
    surfaceContainerHighest = surfaceRaised,
    surfaceContainerLow = surface,
    surfaceContainerLowest = background,
)

/**
 * Applies [palette] to everything below: [LocalPalette], Material colours and content colour. With [redFilter] in
 * night mode a red multiply layer turns any remaining non-red pixel red (experimental setting, as on iOS).
 */
@Composable
fun LodestarTheme(palette: Palette, redFilter: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = palette.colorScheme()) {
            CompositionLocalProvider(LocalContentColor provides palette.primaryText) {
                if (palette.isNight && redFilter) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.drawWithContent {
                            drawContent()
                            drawRect(Color.Red, blendMode = BlendMode.Multiply)
                        },
                    ) { content() }
                } else {
                    content()
                }
            }
        }
    }
}

/** Fills the background with the palette's screen colour. */
fun Modifier.screenBackground(palette: Palette): Modifier = background(palette.background)

/** Standard card container used by the Tonight, detail and settings screens. */
fun Modifier.card(palette: Palette, padding: Dp = 14.dp): Modifier {
    val shape = RoundedCornerShape(16.dp)
    return clip(shape).background(palette.surface, shape).border(0.5.dp, palette.stroke, shape).padding(padding)
}

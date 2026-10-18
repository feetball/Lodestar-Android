package net.kuecker.lodestar.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The colours used throughout the app. Lodestar has two palettes: a dark "normal" palette and a pure-red "night"
 * palette that preserves dark adaptation (no green or blue light at all).
 */
@Immutable
data class Palette(
    val isNight: Boolean,
    /** Screen background. */
    val background: Color,
    /** Cards, panels and list rows. */
    val surface: Color,
    /** Raised elements on top of [surface] (selected rows, buttons). */
    val surfaceRaised: Color,
    /** Hairlines and card borders. */
    val stroke: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val tertiaryText: Color,
    /** Interactive elements and highlights. */
    val accent: Color,
    /** Foreground drawn on top of [accent] fills. */
    val onAccent: Color,
    /** Guidance arrows and the target marker. */
    val guidance: Color,
    /** "On target" state. */
    val positive: Color,
    val warning: Color,
    val critical: Color,
    // Sky overlay
    val constellationLine: Color,
    val constellationLabel: Color,
    val horizonLine: Color,
    val gridLine: Color,
    val deepSkyMarker: Color,
    val planetMarker: Color,
    val milkyWay: Color,
    val dslrFrame: Color,
) {
    /** Star colour for a B−V colour index (normal mode), or a red of matching brightness at night. */
    fun starColor(colorIndex: Double?): Color {
        if (isNight) return guidance
        if (colorIndex == null || !colorIndex.isFinite()) return Color(0.95f, 0.95f, 0.95f)
        // Approximate B−V → sRGB tint, kept pale so faint stars stay legible.
        val t = min(max((colorIndex + 0.3) / 2.0, 0.0), 1.0)
        val red = 0.72 + 0.28 * min(1.0, t * 1.6)
        val green = 0.80 + 0.12 * (1 - abs(t - 0.4) * 1.6)
        val blue = 1.0 - 0.55 * t
        return Color(red.toFloat(), max(0.6, min(green, 1.0)).toFloat(), max(0.45, blue).toFloat())
    }

    companion object {
        private fun rgb(r: Double, g: Double, b: Double, a: Double = 1.0) =
            Color(r.toFloat(), g.toFloat(), b.toFloat(), a.toFloat())

        val normal = Palette(
            isNight = false,
            background = rgb(0.020, 0.027, 0.051),
            surface = rgb(0.063, 0.078, 0.122),
            surfaceRaised = rgb(0.094, 0.114, 0.172),
            stroke = rgb(0.149, 0.188, 0.290),
            primaryText = rgb(0.949, 0.957, 0.973),
            secondaryText = rgb(0.655, 0.686, 0.761),
            tertiaryText = rgb(0.420, 0.451, 0.537),
            accent = rgb(0.961, 0.722, 0.255),
            onAccent = rgb(0.08, 0.06, 0.02),
            guidance = rgb(0.961, 0.722, 0.255),
            positive = rgb(0.298, 0.851, 0.392),
            warning = rgb(1.0, 0.624, 0.039),
            critical = rgb(1.0, 0.271, 0.227),
            constellationLine = rgb(0.47, 0.55, 0.80, 0.45),
            constellationLabel = rgb(0.55, 0.62, 0.85, 0.8),
            horizonLine = rgb(0.30, 0.62, 0.95, 0.8),
            gridLine = rgb(1.0, 1.0, 1.0, 0.10),
            deepSkyMarker = rgb(0.55, 0.80, 0.95),
            planetMarker = rgb(1.0, 0.80, 0.45),
            milkyWay = rgb(0.70, 0.75, 0.95, 0.10),
            dslrFrame = rgb(0.961, 0.722, 0.255, 0.9),
        )

        /** Pure-red palette. [intensity] (0.25…1) scales every element's brightness. */
        fun night(intensity: Double): Palette {
            val k = min(max(intensity, 0.25), 1.0)
            fun red(level: Double, opacity: Double = 1.0) = rgb(min(1.0, level * k), 0.0, 0.0, opacity)
            return Palette(
                isNight = true,
                background = Color.Black,
                surface = red(0.10),
                surfaceRaised = red(0.16),
                stroke = red(0.26),
                primaryText = red(0.92),
                secondaryText = red(0.66),
                tertiaryText = red(0.44),
                accent = red(1.0),
                onAccent = Color.Black,
                guidance = red(1.0),
                positive = red(1.0),
                warning = red(0.85),
                critical = red(1.0),
                constellationLine = red(0.55, 0.55),
                constellationLabel = red(0.6, 0.8),
                horizonLine = red(0.7, 0.8),
                gridLine = red(0.5, 0.18),
                deepSkyMarker = red(0.8),
                planetMarker = red(1.0),
                milkyWay = red(0.6, 0.10),
                dslrFrame = red(0.9, 0.9),
            )
        }
    }
}

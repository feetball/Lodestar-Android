package net.kuecker.lodestar.features.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.planning.PolarScopeReading
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.Palette
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Room outside the dial for the hour numbers, in dp. */
private const val LABEL_INSET = 22

/** Radius of the circle the pole star travels on, as a fraction of the dial's radius. */
private const val STAR_CIRCLE_FRACTION = 0.58f

/** Radius of the small circle around the pole, as a fraction of the dial's radius. */
private const val INNER_CIRCLE_FRACTION = 0.25f

/** The hour numbers and the star's name: more distinct labels than the text measurer caches by default. */
private const val LABEL_CACHE_SIZE = 16

/** Font sizes of the labels in dp: the dial's geometry is fixed, so its labels do not follow the system font size. */
private const val EMPHASIZED_LABEL_SIZE = 12
private const val REGULAR_LABEL_SIZE = 11

/** Text styles of the reticle's labels: [emphasized] for the quarter hours and the star's name. */
private class ReticleLabels(val emphasized: TextStyle, val regular: TextStyle)

/**
 * A polar-scope reticle with the pole star drawn where it stands now: a clock face, concentric circles around the
 * pole and the star on its circle. 12 o'clock is straight up (towards the zenith). The reticle is square and
 * fills the width it is given; everything is drawn in palette colours.
 */
@Composable
internal fun ToolsPolarReticle(reading: PolarScopeReading, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val typography = MaterialTheme.typography
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = LABEL_CACHE_SIZE)
    val labels = remember(palette, typography, density) {
        with(density) {
            ReticleLabels(
                emphasized = typography.labelMedium.copy(
                    color = palette.primaryText,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = EMPHASIZED_LABEL_SIZE.dp.toSp(),
                    lineHeight = TextUnit.Unspecified,
                ),
                regular = typography.labelSmall.copy(
                    color = palette.secondaryText,
                    fontSize = REGULAR_LABEL_SIZE.dp.toSp(),
                    lineHeight = TextUnit.Unspecified,
                ),
            )
        }
    }
    val spoken = "${reading.referenceStarName} at ${ToolsPolarClock.text(reading.angle)} o'clock, " +
        "${AngleFormatter.formatDegrees(reading.separationFromPole, 2)} from the pole"
    Canvas(
        modifier.aspectRatio(1f).semantics {
            contentDescription = "Polar scope reticle"
            stateDescription = spoken
        },
    ) {
        drawReticle(reading, palette, measurer, labels)
    }
}

private fun DrawScope.drawReticle(
    reading: PolarScopeReading,
    palette: Palette,
    measurer: TextMeasurer,
    labels: ReticleLabels,
) {
    val labelInset = LABEL_INSET.dp.toPx()
    val dial = min(size.width, size.height) / 2 - labelInset
    if (dial <= 0f) return
    val center = this.center

    // The point at [radius] from the centre, [degrees] clockwise from 12 o'clock.
    fun point(radius: Float, degrees: Double): Offset {
        val radians = AstroMath.radians(degrees)
        return Offset(center.x + radius * sin(radians).toFloat(), center.y - radius * cos(radians).toFloat())
    }

    // The dial and its concentric circles.
    drawCircle(palette.surface, radius = dial, center = center)
    drawCircle(palette.stroke, radius = dial, center = center, style = Stroke(1.5.dp.toPx()))
    drawCircle(palette.stroke, radius = dial * INNER_CIRCLE_FRACTION, center = center, style = Stroke(1.dp.toPx()))
    val starRadius = dial * STAR_CIRCLE_FRACTION
    drawCircle(palette.tertiaryText, radius = starRadius, center = center, style = Stroke(1.5.dp.toPx()))

    // Cross hairs through the pole.
    val crossHairs = Path().apply {
        moveTo(center.x - dial, center.y)
        lineTo(center.x + dial, center.y)
        moveTo(center.x, center.y - dial)
        lineTo(center.x, center.y + dial)
    }
    drawPath(crossHairs, palette.gridLine, style = Stroke(1.dp.toPx()))

    // Clock ticks every 6°, longer on the hours, and the hour numbers.
    val ticks = Path()
    for (step in 0 until 60) {
        val angle = step * 6.0
        val inner = point(dial - (if (step % 5 == 0) 9 else 4).dp.toPx(), angle)
        val outer = point(dial, angle)
        ticks.moveTo(inner.x, inner.y)
        ticks.lineTo(outer.x, outer.y)
    }
    drawPath(ticks, palette.secondaryText, style = Stroke(1.dp.toPx()))
    for (hour in 1..12) {
        val style = if (hour % 3 == 0) labels.emphasized else labels.regular
        drawCenteredText(measurer, hour.toString(), style, point(dial + labelInset / 2 + 1.dp.toPx(), hour * 30.0))
    }

    // The pole.
    drawCircle(palette.primaryText, radius = 3.5.dp.toPx(), center = center, style = Stroke(1.5.dp.toPx()))

    // The pole star on its circle, joined to the pole by a line.
    val star = point(starRadius, reading.angle)
    drawLine(
        palette.guidance.copy(alpha = palette.guidance.alpha * 0.6f),
        start = center,
        end = star,
        strokeWidth = 1.dp.toPx(),
    )
    drawCircle(palette.guidance, radius = 10.dp.toPx(), center = star, style = Stroke(1.5.dp.toPx()))
    drawCircle(palette.guidance, radius = 4.5.dp.toPx(), center = star)
    drawCenteredText(measurer, reading.referenceStarName, labels.emphasized, point(starRadius * 0.62f, reading.angle))
}

/** Draws [text] with its centre at [at]. */
private fun DrawScope.drawCenteredText(measurer: TextMeasurer, text: String, style: TextStyle, at: Offset) {
    val layout = measurer.measure(text, style)
    drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
}

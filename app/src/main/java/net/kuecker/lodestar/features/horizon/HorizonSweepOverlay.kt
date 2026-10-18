package net.kuecker.lodestar.features.horizon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.Palette
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** The height of the 360° strip in dp: the plot and, below it, the compass letters. */
private const val STRIP_HEIGHT = 84

/** Room under the plot for the compass letters, in dp. */
private const val STRIP_LABEL_HEIGHT = 16

/** Font size of the strip's letters in dp: the strip is drawn to a fixed geometry, so they ignore the font scale. */
private const val STRIP_LABEL_SIZE = 11

/** The compass letters under the strip, at 0°, 90°, 180°, 270° and 360°. */
private val STRIP_LETTERS = listOf("N", "E", "S", "W", "N")

/** The crosshair that marks where the skyline has to run: arms, a ring and a level line across the picture. */
@Composable
internal fun HorizonCrosshair(modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Canvas(modifier.clearAndSetSemantics { }) {
        val center = this.center
        val ring = 26.dp.toPx()
        val gap = 8.dp.toPx()
        val arm = 22.dp.toPx()
        fun drawMarks(color: Color, width: Float) {
            drawCircle(color, radius = ring, center = center, style = Stroke(width))
            drawLine(color, Offset(center.x - ring - arm, center.y), Offset(center.x - gap, center.y), width)
            drawLine(color, Offset(center.x + gap, center.y), Offset(center.x + ring + arm, center.y), width)
            drawLine(color, Offset(center.x, center.y - ring - arm), Offset(center.x, center.y - gap), width)
            drawLine(color, Offset(center.x, center.y + gap), Offset(center.x, center.y + ring + arm), width)
        }
        // A level line across the picture helps to keep the skyline on it while turning.
        val level = palette.accent.copy(alpha = palette.accent.alpha * 0.35f)
        drawLine(palette.background.copy(alpha = 0.4f), Offset(0f, center.y), Offset(size.width, center.y), 3.dp.toPx())
        drawLine(level, Offset(0f, center.y), Offset(size.width, center.y), 1.dp.toPx())
        // A dark outline keeps the marks readable over a bright picture.
        drawMarks(palette.background.copy(alpha = 0.55f), 4.dp.toPx())
        drawMarks(palette.accent, 2.dp.toPx())
    }
}

/** The live direction of the phone's back camera: azimuth and altitude. */
@Composable
internal fun HorizonReadout(state: HorizonSweepState, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val azimuth = state.azimuth?.let(AppFormat::azimuth) ?: AppFormat.MISSING
    val altitude = state.altitude?.let(AppFormat::altitude) ?: AppFormat.MISSING
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        for ((label, value) in listOf("Azimuth" to azimuth, "Altitude" to altitude)) {
            Column {
                Text(label, color = palette.secondaryText, style = MaterialTheme.typography.labelMedium)
                Text(
                    value,
                    color = palette.primaryText,
                    style = MaterialTheme.typography.titleLarge.monospacedDigits(),
                )
            }
        }
    }
}

/**
 * The skyline round the whole circle, north at both ends: what has been recorded is drawn in the accent colour and
 * filled, the stored skyline of the site (if any) as a faint line, and a vertical mark shows where the camera points
 * now. Redrawn ten times a second.
 */
@Composable
internal fun HorizonStrip(state: HorizonSweepState, base: HorizonProfile?, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val typography = MaterialTheme.typography
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = STRIP_LETTERS.size)
    val labelStyle = remember(palette, typography, density) {
        typography.labelSmall.copy(
            color = palette.secondaryText,
            fontSize = with(density) { STRIP_LABEL_SIZE.dp.toSp() },
            lineHeight = TextUnit.Unspecified,
        )
    }
    val summary = "${AppFormat.percent(state.coverage)} of the circle recorded"
    Canvas(
        modifier.fillMaxWidth().height(STRIP_HEIGHT.dp).semantics {
            contentDescription = "Skyline round the whole circle"
            stateDescription = if (state.recordedBins > 0) {
                "$summary, ${HorizonFormat.highest(state.profile)}"
            } else {
                "Nothing recorded yet"
            }
        },
    ) {
        drawStrip(state, base, palette, measurer, labelStyle)
    }
}

private fun DrawScope.drawStrip(
    state: HorizonSweepState,
    base: HorizonProfile?,
    palette: Palette,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
) {
    val top = 4.dp.toPx()
    val bottom = size.height - STRIP_LABEL_HEIGHT.dp.toPx()
    val plotHeight = bottom - top
    val width = size.width
    if (plotHeight <= 0f || width <= 0f) return
    val profile = state.profile

    // The scale follows the skyline: at least −5…30°, more room when something tall comes along.
    val lowest = min(0.0, min(profile.minimumAltitude, base?.minimumAltitude ?: 0.0))
    val highest = max(profile.maximumAltitude, base?.maximumAltitude ?: 0.0)
    val low = floor((lowest - 2) / 5) * 5
    val high = max(30.0, ceil((highest + 8) / 10) * 10)
    fun x(azimuth: Double): Float = (azimuth / HorizonProfile.binCount * width).toFloat()
    fun y(altitude: Double): Float = (top + (high - altitude) / (high - low) * plotHeight).toFloat()

    // The sky box, the true horizon and the compass letters.
    drawRect(palette.surface, topLeft = Offset(0f, top), size = Size(width, plotHeight))
    drawLine(
        palette.stroke, Offset(0f, y(0.0)), Offset(width, y(0.0)), 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
    )
    for (index in STRIP_LETTERS.indices) {
        val layout = measurer.measure(STRIP_LETTERS[index], labelStyle)
        val at = x(index * 90.0)
        val left = (at - layout.size.width / 2f).coerceIn(0f, max(0f, width - layout.size.width))
        drawLine(palette.stroke, Offset(at, bottom), Offset(at, bottom + 3.dp.toPx()), 1.dp.toPx())
        drawText(layout, topLeft = Offset(left, bottom + 3.dp.toPx()))
    }

    // The stored skyline, faint, for comparison.
    if (base != null && !base.isFlat) {
        val line = Path()
        for (index in 0 until HorizonProfile.binCount) {
            val px = x(index.toDouble())
            val py = y(base.altitudes[index])
            if (index == 0) line.moveTo(px, py) else line.lineTo(px, py)
        }
        drawPath(line, palette.tertiaryText, style = Stroke(1.dp.toPx()))
    }

    // What this sweep has recorded: each run of recorded degrees filled down to the plot's floor.
    val fill = Path()
    val line = Path()
    val mask = state.recordedMask
    var index = 0
    while (index < HorizonProfile.binCount) {
        if (!mask[index]) {
            index++
            continue
        }
        val first = index
        while (index + 1 < HorizonProfile.binCount && mask[index + 1]) index++
        val last = index
        index++
        val left = x(first - 0.5)
        val right = x(last + 0.5)
        fill.moveTo(left, bottom)
        fill.lineTo(left, y(profile.altitudes[first]))
        line.moveTo(left, y(profile.altitudes[first]))
        for (bin in first..last) {
            val px = x(bin.toDouble())
            val py = y(profile.altitudes[bin])
            fill.lineTo(px, py)
            line.lineTo(px, py)
        }
        fill.lineTo(right, y(profile.altitudes[last]))
        fill.lineTo(right, bottom)
        fill.close()
        line.lineTo(right, y(profile.altitudes[last]))
    }
    drawPath(fill, palette.accent.copy(alpha = palette.accent.alpha * 0.3f))
    drawPath(line, palette.accent, style = Stroke(2.dp.toPx()))

    // Where the camera points now.
    state.azimuth?.let { azimuth ->
        val at = x(azimuth)
        drawLine(palette.primaryText, Offset(at, top), Offset(at, bottom), 1.5.dp.toPx())
        val marker = Path().apply {
            moveTo(at - 4.dp.toPx(), top)
            lineTo(at + 4.dp.toPx(), top)
            lineTo(at, top + 6.dp.toPx())
            close()
        }
        drawPath(marker, palette.primaryText)
    }
}

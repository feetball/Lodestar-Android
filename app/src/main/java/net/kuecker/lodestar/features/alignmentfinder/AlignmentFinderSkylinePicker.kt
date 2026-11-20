package net.kuecker.lodestar.features.alignmentfinder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.Palette
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The height of the strip in dp: the plot and, below it, the compass letters. */
private const val PICKER_HEIGHT = 104

/** Room under the plot for the compass letters, in dp. */
private const val PICKER_LABEL_HEIGHT = 16

/** Font size of the letters in dp: the strip is drawn to a fixed geometry, so they ignore the font scale. */
private const val PICKER_LABEL_SIZE = 11

/** The compass letters under the strip, at 0°, 90°, 180°, 270° and 360°. */
private val PICKER_LETTERS = listOf("N", "E", "S", "W", "N")

/** How far the window rises above the skyline in the strip, in degrees (the finder's "just above the skyline"). */
private const val WINDOW_ABOVE_SKYLINE = 10.0

/**
 * The site's skyline round the whole circle, north at both ends, with the picked direction marked and the window that
 * the finder searches (the tolerance either side, from the skyline up to 10° above it) shaded. Tapping or dragging picks
 * an azimuth, snapped to whole degrees.
 *
 * @param azimuth The picked azimuth in whole degrees, 0…359.
 * @param tolerance The tolerance either side of it in whole degrees.
 */
@Composable
internal fun AlignmentFinderSkylinePicker(
    profile: HorizonProfile,
    azimuth: Int,
    tolerance: Int,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val typography = MaterialTheme.typography
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = PICKER_LETTERS.size)
    val labelStyle = remember(palette, typography, density) {
        typography.labelSmall.copy(
            color = palette.secondaryText,
            fontSize = with(density) { PICKER_LABEL_SIZE.dp.toSp() },
            lineHeight = TextUnit.Unspecified,
        )
    }
    val latestPick = rememberUpdatedState(onPick)
    fun pick(x: Float, width: Float) {
        if (width <= 0f) return
        val degrees = (x / width * HorizonProfile.binCount).roundToInt().coerceIn(0, HorizonProfile.binCount)
        latestPick.value(degrees % HorizonProfile.binCount)
    }
    val skyline = profile.altitude(azimuth.toDouble())
    Canvas(
        modifier
            .fillMaxWidth()
            .height(PICKER_HEIGHT.dp)
            .pointerInput(Unit) { detectTapGestures { pick(it.x, size.width.toFloat()) } }
            .pointerInput(Unit) {
                detectDragGestures(onDragStart = { pick(it.x, size.width.toFloat()) }) { change, _ ->
                    pick(change.position.x, size.width.toFloat())
                }
            }
            .semantics {
                contentDescription = "Skyline round the whole circle. Tap or drag to pick a direction."
                stateDescription = "${AlignmentFinderText.direction(azimuth)}, skyline ${AlignmentFinderText.altitude(skyline)} high"
            },
    ) {
        drawPicker(profile, azimuth, tolerance, palette, measurer, labelStyle)
    }
}

private fun DrawScope.drawPicker(
    profile: HorizonProfile,
    azimuth: Int,
    tolerance: Int,
    palette: Palette,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
) {
    val top = 4.dp.toPx()
    val bottom = size.height - PICKER_LABEL_HEIGHT.dp.toPx()
    val plotHeight = bottom - top
    val width = size.width
    if (plotHeight <= 0f || width <= 0f) return

    // The scale follows the skyline: at least −5…30°, more room when something tall comes along.
    val low = floor((min(0.0, profile.minimumAltitude) - 2) / 5) * 5
    val high = max(30.0, ceil((profile.maximumAltitude + WINDOW_ABOVE_SKYLINE + 2) / 10) * 10)
    fun x(degrees: Double): Float = (degrees / HorizonProfile.binCount * width).toFloat()
    fun y(altitude: Double): Float = (top + (high - altitude) / (high - low) * plotHeight).toFloat()

    drawRect(palette.surface, topLeft = Offset(0f, top), size = Size(width, plotHeight))
    drawLine(
        palette.stroke, Offset(0f, y(0.0)), Offset(width, y(0.0)), 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
    )
    for (index in PICKER_LETTERS.indices) {
        val layout = measurer.measure(PICKER_LETTERS[index], labelStyle)
        val at = x(index * 90.0)
        val left = (at - layout.size.width / 2f).coerceIn(0f, max(0f, width - layout.size.width))
        drawLine(palette.stroke, Offset(at, bottom), Offset(at, bottom + 3.dp.toPx()), 1.dp.toPx())
        drawText(layout, topLeft = Offset(left, bottom + 3.dp.toPx()))
    }

    // The ground and the skyline.
    val ground = Path().apply { moveTo(0f, bottom) }
    val line = Path()
    for (index in 0..HorizonProfile.binCount) {
        val px = x(index.toDouble())
        val py = y(profile.altitudes[index % HorizonProfile.binCount])
        ground.lineTo(px, py)
        if (index == 0) line.moveTo(px, py) else line.lineTo(px, py)
    }
    ground.lineTo(width, bottom)
    ground.close()
    drawPath(ground, palette.accent.copy(alpha = palette.accent.alpha * 0.28f))
    drawPath(line, palette.accent, style = Stroke(2.dp.toPx()))

    // The window above the picked point, in one or two pieces when it runs through north.
    val skyline = profile.altitude(azimuth.toDouble())
    val windowTop = y(min(skyline + WINDOW_ABOVE_SKYLINE, high))
    val windowBottom = y(skyline)
    val first = azimuth - tolerance.toDouble()
    val last = azimuth + tolerance.toDouble()
    val shade = palette.guidance.copy(alpha = palette.guidance.alpha * 0.35f)
    val spans = when {
        first < 0 -> listOf(0.0 to last, first + HorizonProfile.binCount to HorizonProfile.binCount.toDouble())
        last > HorizonProfile.binCount -> listOf(first to HorizonProfile.binCount.toDouble(), 0.0 to last - HorizonProfile.binCount)
        else -> listOf(first to last)
    }
    for ((from, to) in spans) {
        drawRect(shade, topLeft = Offset(x(from), windowTop), size = Size(max(x(to) - x(from), 1f), max(windowBottom - windowTop, 1f)))
    }

    // The picked direction.
    val at = x(azimuth.toDouble())
    drawLine(palette.guidance, Offset(at, top), Offset(at, bottom), 1.5.dp.toPx())
    drawCircle(palette.guidance, 4.dp.toPx(), Offset(at, y(skyline)))
    drawRect(palette.stroke, topLeft = Offset(0f, top), size = Size(width, plotHeight), style = Stroke(0.75.dp.toPx()))
}

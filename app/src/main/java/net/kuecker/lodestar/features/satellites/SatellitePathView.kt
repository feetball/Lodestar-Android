package net.kuecker.lodestar.features.satellites

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kuecker.lodestar.kit.satellites.SatelliteLook
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.Palette
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** One point of a pass on the sky disc: `x` to the right and `y` up, the horizon on the unit circle, the zenith at 0. */
internal class SatellitePathPoint(val x: Double, val y: Double, val isVisible: Boolean)

/**
 * The geometry of the sky-path drawing: the sky seen from below, north at the top and east on the left, as on a star
 * chart held overhead.
 */
internal object SatellitePathModel {
    /** The place on the disc of the direction ([azimuth], [altitude]) in degrees: the horizon is radius 1, the zenith 0. */
    fun project(azimuth: Double, altitude: Double): Pair<Double, Double> {
        val radius = (90.0 - altitude.coerceIn(0.0, 90.0)) / 90.0
        val angle = Math.toRadians(azimuth)
        return -sin(angle) * radius to cos(angle) * radius
    }

    /** The track as points on the disc, each marked visible when the satellite can be seen there. */
    fun points(looks: List<SatelliteLook>): List<SatellitePathPoint> = looks.map { look ->
        val (x, y) = project(look.azimuth, look.altitude)
        SatellitePathPoint(x, y, look.isVisible())
    }
}

/**
 * The sky path of a pass: rings at 30° and 60° above the horizon, the compass points, the track (solid where the satellite
 * can be seen, dashed where it cannot) and the rise, peak and set. [description] is what a screen reader says. Palette
 * colours only.
 */
@Composable
internal fun SatellitePathView(
    looks: List<SatelliteLook>,
    peak: SatelliteLook?,
    description: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val measurer = rememberTextMeasurer()
    val compassStyle = MaterialTheme.typography.labelMedium.copy(color = palette.primaryText, fontSize = 13.sp)
    val markStyle = MaterialTheme.typography.labelSmall.copy(color = palette.secondaryText, fontSize = 11.sp)
    Canvas(
        modifier.fillMaxWidth().widthIn(max = 360.dp).aspectRatio(1f).semantics { contentDescription = description },
    ) {
        drawPass(looks, peak, palette, measurer, compassStyle, markStyle)
    }
}

private fun DrawScope.drawPass(
    looks: List<SatelliteLook>,
    peak: SatelliteLook?,
    palette: Palette,
    measurer: TextMeasurer,
    compassStyle: TextStyle,
    markStyle: TextStyle,
) {
    val margin = 22.dp.toPx()
    val radius = size.minDimension / 2 - margin
    if (radius <= 0f) return
    val center = Offset(size.width / 2, size.height / 2)
    fun place(x: Double, y: Double) = Offset(center.x + (x * radius).toFloat(), center.y - (y * radius).toFloat())

    // Rings (horizon, 30°, 60°) and the cross. The night grid colour is faint enough for a star chart's many lines, but
    // here the rings are what the caption refers to, so at night they are drawn at a higher alpha.
    val gridColor = if (palette.isNight) palette.gridLine.copy(alpha = 0.6f) else palette.gridLine
    for (fraction in listOf(1f, 2f / 3f, 1f / 3f)) {
        drawCircle(gridColor, radius * fraction, center, style = Stroke(1.dp.toPx()))
    }
    drawCircle(palette.horizonLine, radius, center, style = Stroke(1.5.dp.toPx()))
    drawLine(gridColor, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 1.dp.toPx())
    drawLine(gridColor, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 1.dp.toPx())
    for ((label, x, y) in listOf(Triple("N", 0.0, 1.0), Triple("E", -1.0, 0.0), Triple("S", 0.0, -1.0), Triple("W", 1.0, 0.0))) {
        val text = measurer.measure(label, compassStyle)
        val at = place(x * (1 + 14.dp.toPx() / radius), y * (1 + 14.dp.toPx() / radius))
        drawText(text, topLeft = Offset(at.x - text.size.width / 2f, at.y - text.size.height / 2f))
    }

    val points = SatellitePathModel.points(looks)
    if (points.size < 2) return
    val dashed = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
    val hiddenColor = if (palette.isNight) palette.secondaryText else palette.tertiaryText
    for (index in 1 until points.size) {
        val from = points[index - 1]
        val to = points[index]
        val isVisible = from.isVisible && to.isVisible
        drawLine(
            if (isVisible) palette.accent else hiddenColor,
            place(from.x, from.y), place(to.x, to.y),
            strokeWidth = (if (isVisible) 3.5.dp else 1.5.dp).toPx(),
            cap = StrokeCap.Round,
            pathEffect = if (isVisible) null else dashed,
        )
    }

    // Rise, peak and set.
    val first = points.first()
    val last = points.last()
    mark("Rise", place(first.x, first.y), center, false, palette, measurer, markStyle)
    mark("Set", place(last.x, last.y), center, false, palette, measurer, markStyle)
    peak?.let {
        val (x, y) = SatellitePathModel.project(it.azimuth, it.altitude)
        mark("Peak", place(x, y), center, true, palette, measurer, markStyle)
    }
}

/**
 * A dot at [at] with [label] on the side away from the [center]; the peak is a filled, larger dot. The label has a plate
 * in the background colour, so that the horizon ring does not run through its letters.
 */
private fun DrawScope.mark(
    label: String,
    at: Offset,
    center: Offset,
    isFilled: Boolean,
    palette: Palette,
    measurer: TextMeasurer,
    style: TextStyle,
) {
    val away = Offset(at.x - center.x, at.y - center.y)
    val length = hypot(away.x, away.y).takeIf { it > 1e-3f } ?: 1f
    val text = measurer.measure(label, style)
    val offset = 12.dp.toPx()
    val x = at.x + away.x / length * (offset + text.size.width / 2f) - text.size.width / 2f
    val y = at.y + away.y / length * (offset + text.size.height / 2f) - text.size.height / 2f
    val pad = 2.dp.toPx()
    drawRoundRect(
        palette.background,
        topLeft = Offset(x - pad, y - pad / 2f),
        size = Size(text.size.width + 2 * pad, text.size.height + pad),
        cornerRadius = CornerRadius(3.dp.toPx()),
    )
    val dot = (if (isFilled) 6.dp else 4.5.dp).toPx()
    if (isFilled) {
        drawCircle(palette.accent, dot, at)
    } else {
        drawCircle(palette.background, dot, at)
        drawCircle(palette.primaryText, dot, at, style = Stroke(2.dp.toPx()))
    }
    drawText(text, topLeft = Offset(x, y))
}

package net.kuecker.lodestar.features.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.Palette

/** Coordinate space of the drawing code below. */
private const val DRAWING_SIZE = 64f

private val lineStyle = Stroke(width = 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** A small drawing of a phone mount, seen the way the photographer sees it. Drawn with palette colours only. */
@Composable
internal fun MountIllustration(layout: MountLayout, modifier: Modifier = Modifier, side: Dp = 56.dp) {
    val palette = LocalPalette.current
    Canvas(modifier.size(side)) {
        scale(minOf(size.width, size.height) / DRAWING_SIZE, pivot = Offset.Zero) {
            when (layout) {
                is MountLayout.BackCamera -> drawBackCamera(palette, layout.rotation)
                is MountLayout.FlatOnTop -> drawFlatOnTop(palette, layout.topEdgeForward)
                MountLayout.Custom -> drawCustom(palette)
            }
        }
    }
}

/**
 * The camera seen from behind (its lens, a circle with a cross, points away from the viewer) with the phone on top
 * of it, turned by [rotation] degrees; the small bar marks the phone's top edge.
 */
private fun DrawScope.drawBackCamera(palette: Palette, rotation: Double) {
    val bodyOrigin = Offset(6f, 41f)
    val bodySize = Size(52f, 20f)
    drawRoundRect(palette.surfaceRaised, bodyOrigin, bodySize, CornerRadius(4f))
    drawRoundRect(palette.stroke, bodyOrigin, bodySize, CornerRadius(4f), style = lineStyle)
    drawRoundRect(palette.stroke, Offset(25f, 37f), Size(14f, 5f), CornerRadius(2f), style = lineStyle)
    drawOval(palette.accent, Offset(25f, 44f), Size(14f, 14f), style = lineStyle)
    val cross = Path().apply {
        moveTo(29f, 48f)
        lineTo(35f, 54f)
        moveTo(35f, 48f)
        lineTo(29f, 54f)
    }
    drawPath(cross, palette.accent, style = lineStyle)

    translate(32f, 19f) {
        rotate(rotation.toFloat(), pivot = Offset.Zero) {
            val screenOrigin = Offset(-9f, -16f)
            val screenSize = Size(18f, 32f)
            drawRoundRect(palette.surface, screenOrigin, screenSize, CornerRadius(4f))
            drawRoundRect(palette.primaryText, screenOrigin, screenSize, CornerRadius(4f), style = lineStyle)
            drawRoundRect(palette.primaryText, Offset(-3.5f, -13f), Size(7f, 2.5f), CornerRadius(1.25f))
        }
    }
}

/**
 * The camera from the side, the lens pointing right (the photographer stands on the left), with the phone lying on
 * top; the dot marks the phone's top edge and the arrow the direction of the lens.
 */
private fun DrawScope.drawFlatOnTop(palette: Palette, topEdgeForward: Boolean) {
    val bodyOrigin = Offset(6f, 35f)
    val bodySize = Size(34f, 22f)
    drawRoundRect(palette.surfaceRaised, bodyOrigin, bodySize, CornerRadius(3f))
    drawRoundRect(palette.stroke, bodyOrigin, bodySize, CornerRadius(3f), style = lineStyle)
    val lensOrigin = Offset(40f, 39f)
    val lensSize = Size(16f, 14f)
    drawRoundRect(palette.surfaceRaised, lensOrigin, lensSize, CornerRadius(2f))
    drawRoundRect(palette.stroke, lensOrigin, lensSize, CornerRadius(2f), style = lineStyle)

    val phoneOrigin = Offset(9f, 28f)
    val phoneSize = Size(29f, 5f)
    drawRoundRect(palette.surface, phoneOrigin, phoneSize, CornerRadius(1.5f))
    drawRoundRect(palette.primaryText, phoneOrigin, phoneSize, CornerRadius(1.5f), style = lineStyle)
    val markerX = if (topEdgeForward) 34.5f else 12.5f
    drawCircle(palette.primaryText, radius = 1.75f, center = Offset(markerX, 30.5f))

    val arrow = Path().apply {
        moveTo(42f, 20f)
        lineTo(58f, 20f)
        moveTo(54f, 16.5f)
        lineTo(58f, 20f)
        lineTo(54f, 23.5f)
    }
    drawPath(arrow, palette.accent, style = lineStyle)
}

private fun DrawScope.drawCustom(palette: Palette) {
    drawRoundRect(palette.primaryText, Offset(22f, 10f), Size(20f, 36f), CornerRadius(4f), style = lineStyle)
    drawLine(palette.accent, Offset(32f, 52f), Offset(32f, 60f), strokeWidth = lineStyle.width, cap = StrokeCap.Round)
}

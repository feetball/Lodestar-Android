package net.kuecker.lodestar.features.mount

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kuecker.lodestar.kit.mount.SkyWatcherMountMode
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.Palette
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The words for a screen reader, which cannot see the picture. */
internal fun mountHomeDescription(mode: SkyWatcherMountMode): String = when (mode) {
    SkyWatcherMountMode.ALT_AZIMUTH ->
        "Picture of the alt-azimuth home position: the tripod and the mount base level, the telescope level and pointing north."
    SkyWatcherMountMode.EQUATORIAL ->
        "Picture of the equatorial home position: the polar axis pointing at the celestial pole, the counterweight bar " +
            "pointing down at the ground, the telescope parallel to the polar axis and pointing at the pole."
}

/**
 * A simple side view of the home position, drawn in the palette: for an alt-azimuth mount the level tripod with the telescope
 * level and pointing north, for an equatorial mount the polar axis at the pole with the counterweight bar pointing down and the
 * telescope parallel to the polar axis. The picture is 160 by 100 units, scaled to fit and centred.
 */
@Composable
internal fun MountHomeDrawing(mode: SkyWatcherMountMode, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(DRAWING_HEIGHT.dp).semantics { contentDescription = mountHomeDescription(mode) }) {
        val unit = min(size.width / WIDTH_UNITS, size.height / HEIGHT_UNITS)
        val left = (size.width - WIDTH_UNITS * unit) / 2
        val top = (size.height - HEIGHT_UNITS * unit) / 2
        withTransform({
            translate(left, top)
            scale(unit, unit, Offset.Zero)
        }) {
            when (mode) {
                SkyWatcherMountMode.ALT_AZIMUTH -> drawAltAzimuth(palette, measurer, unit)
                SkyWatcherMountMode.EQUATORIAL -> drawEquatorial(palette, measurer, unit)
            }
        }
    }
}

private const val DRAWING_HEIGHT = 150
private const val WIDTH_UNITS = 160f
private const val HEIGHT_UNITS = 100f
private const val LINE = 2f

private fun DrawScope.line(from: Offset, to: Offset, color: Color, width: Float = LINE) {
    drawLine(color, from, to, strokeWidth = width, cap = StrokeCap.Round)
}

private fun DrawScope.arrow(from: Offset, to: Offset, color: Color) {
    line(from, to, color)
    val dx = to.x - from.x
    val dy = to.y - from.y
    val length = kotlin.math.sqrt(dx * dx + dy * dy)
    if (length < 1f) return
    val ux = dx / length
    val uy = dy / length
    val head = Path().apply {
        moveTo(to.x, to.y)
        lineTo(to.x - 7f * ux + 3.5f * uy, to.y - 7f * uy - 3.5f * ux)
        lineTo(to.x - 7f * ux - 3.5f * uy, to.y - 7f * uy + 3.5f * ux)
        close()
    }
    drawPath(head, color)
}

private fun DrawScope.label(measurer: TextMeasurer, text: String, at: Offset, color: Color, unit: Float, bold: Boolean = false) {
    // The picture is scaled by [unit]; the text is drawn at its own size in screen pixels, at the scaled place.
    val style = TextStyle(fontSize = LABEL_SIZE.sp, color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    val layout = measurer.measure(text, style)
    withTransform({ scale(1f / unit, 1f / unit, Offset.Zero) }) {
        drawText(layout, topLeft = Offset(at.x * unit, at.y * unit))
    }
}

private const val LABEL_SIZE = 12f

private fun DrawScope.drawAltAzimuth(palette: Palette, measurer: TextMeasurer, unit: Float) {
    val ink = palette.secondaryText
    val accent = palette.accent
    // Ground and tripod.
    line(Offset(8f, 92f), Offset(152f, 92f), palette.stroke)
    line(Offset(60f, 64f), Offset(36f, 92f), ink)
    line(Offset(60f, 64f), Offset(84f, 92f), ink)
    line(Offset(60f, 64f), Offset(60f, 92f), ink)
    // The base of the mount, level.
    drawRoundRect(ink, Offset(50f, 56f), Size(20f, 8f), CornerRadius(2f), style = Stroke(LINE))
    line(Offset(60f, 56f), Offset(60f, 46f), ink)
    // The telescope, level, pointing right (north).
    drawRoundRect(accent, Offset(34f, 34f), Size(66f, 12f), CornerRadius(4f), style = Stroke(LINE))
    drawRoundRect(accent, Offset(98f, 36f), Size(8f, 8f), CornerRadius(1f), style = Stroke(LINE))
    // The level reference.
    val dashed = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
    drawLine(palette.stroke, Offset(20f, 40f), Offset(128f, 40f), strokeWidth = 1f, pathEffect = dashed)
    arrow(Offset(112f, 40f), Offset(138f, 40f), accent)
    label(measurer, "N", Offset(141f, 32f), accent, unit, bold = true)
    label(measurer, "level", Offset(8f, 26f), ink, unit)
    label(measurer, "level base", Offset(76f, 70f), ink, unit)
}

private fun DrawScope.drawEquatorial(palette: Palette, measurer: TextMeasurer, unit: Float) {
    val ink = palette.secondaryText
    val accent = palette.accent
    val angle = Math.toRadians(POLAR_ANGLE.toDouble())
    val along = Offset(cos(angle).toFloat(), -sin(angle).toFloat())
    val down = Offset(-along.y, along.x)
    val pivot = Offset(52f, 66f)
    line(Offset(8f, 92f), Offset(152f, 92f), palette.stroke)
    line(Offset(pivot.x, pivot.y + 4f), Offset(30f, 92f), ink)
    line(Offset(pivot.x, pivot.y + 4f), Offset(74f, 92f), ink)
    // The polar axis, pointing up at the pole.
    val top = Offset(pivot.x + along.x * 40f, pivot.y + along.y * 40f)
    drawCircle(ink, 3f, pivot, style = Stroke(LINE))
    arrow(pivot, Offset(pivot.x + along.x * 62f, pivot.y + along.y * 62f), accent)
    label(measurer, "pole", Offset(top.x + 26f, top.y - 12f), accent, unit, bold = true)
    // The counterweight bar, at a right angle to the polar axis, pointing down at the ground, with its weight.
    val weight = Offset(pivot.x + down.x * 26f, pivot.y + down.y * 26f)
    line(pivot, weight, ink, 3f)
    drawCircle(ink, 6f, weight, style = Stroke(LINE))
    label(measurer, "counterweight", Offset(weight.x + 10f, weight.y - 6f), ink, unit)
    // The telescope, parallel to the polar axis.
    val offset = Offset(-down.x * 10f, -down.y * 10f)
    val start = Offset(pivot.x + offset.x + along.x * 8f, pivot.y + offset.y + along.y * 8f)
    val end = Offset(pivot.x + offset.x + along.x * 44f, pivot.y + offset.y + along.y * 44f)
    line(start, end, accent, 6f)
}

private const val POLAR_ANGLE = 50f

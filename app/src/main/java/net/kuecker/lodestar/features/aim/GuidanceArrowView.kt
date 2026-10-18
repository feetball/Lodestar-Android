package net.kuecker.lodestar.features.aim

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kuecker.lodestar.kit.pointing.Guidance
import net.kuecker.lodestar.kit.pointing.GuidanceState
import net.kuecker.lodestar.shared.ScreenAxes
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The big arrow around the reticle that points the way to the target, replaced by a ring, a check mark and the words
 * "On target" once the camera is there.
 *
 * It redraws about 30 times a second on its own timeline, so the arrow follows the phone smoothly without the
 * surrounding screen being touched.
 */
@Composable
internal fun GuidanceArrowView(model: AimViewModel, isActive: Boolean, modifier: Modifier = Modifier) {
    val frame = rememberAimFrame(model, 33, isActive)
    AimArrowContent(
        guidance = frame?.guidance,
        rotation = model.hudRotation,
        axes = model.screenAxes,
        modifier = modifier.fillMaxSize().clearAndSetSemantics {},
    )
}

/** Distance of the texts from the reticle: inside the ring, below the reticle as the reader sees it. */
private val LABEL_OFFSET = 50.dp

/**
 * The ring and arrow around the middle of the available space, and the texts. [rotation] turns the texts so they read
 * upright; the arrow lives in the phone's own screen axes ([axes]), so it ignores the HUD rotation: it always points
 * the way on the glass.
 */
@Composable
private fun AimArrowContent(guidance: Guidance?, rotation: AimHUDRotation, axes: ScreenAxes, modifier: Modifier) {
    val palette = LocalPalette.current
    val arrow = remember { Path() }
    val checkMark = remember { Path() }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (guidance == null) return@Box
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            // The ring grows with the screen but leaves room for the panels on a small one.
            val radius = min(100.dp.toPx(), max(70.dp.toPx(), size.height * 0.115f))
            if (guidance.state == GuidanceState.ON_TARGET) {
                drawCircle(palette.positive, radius, center, style = Stroke(5.dp.toPx()))
                // A ring, a check mark and a word: the state does not depend on colour alone. The check mark sits
                // above the reticle as the reader sees it and turns with the HUD so it stays upright.
                val checkCenter = center + rotation.rotated(Offset(0f, -LABEL_OFFSET.toPx()))
                checkMark.rewind()
                addCheckMark(checkMark, 1.dp.toPx())
                rotate(degrees = rotation.degrees.toFloat(), pivot = checkCenter) {
                    translate(left = checkCenter.x, top = checkCenter.y) {
                        drawPath(
                            checkMark, palette.positive,
                            style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                }
            } else {
                drawCircle(palette.guidance.copy(alpha = 0.25f), radius, center, style = Stroke(1.dp.toPx()))
                val scale = 0.65f + 0.35f * min(1.0, guidance.separation / 20).toFloat()
                arrow.rewind()
                addArrowShape(arrow, 64.dp.toPx() * scale, 46.dp.toPx() * scale)
                // The arrow orbits the reticle and points along the shortest way to the target. The angle counts
                // counter-clockwise from screen-right with y pointing up; drawing turns clockwise, with y down.
                rotate(degrees = -Math.toDegrees(screenAngle(guidance.arrowAngle, axes)).toFloat(), pivot = center) {
                    translate(left = center.x + radius, top = center.y) {
                        drawPath(arrow, palette.guidance)
                        drawPath(arrow, Color.Black.copy(alpha = 0.55f), style = Stroke(1.5.dp.toPx()))
                    }
                }
            }
        }
        if (guidance.state == GuidanceState.ON_TARGET) {
            ArrowLabel("On target", palette.positive, 22.sp, rotation)
        } else {
            ArrowLabel(AimFormat.angle(guidance.separation), palette.guidance, 26.sp, rotation)
        }
    }
}

/** A text that turns with the HUD so it reads upright, below the reticle as the reader sees it. */
@Composable
private fun ArrowLabel(text: String, color: Color, size: TextUnit, rotation: AimHUDRotation) {
    val blur = with(LocalDensity.current) { 3.dp.toPx() }
    val shift = rotation.rotated(DpOffset(0.dp, LABEL_OFFSET))
    AimFixedTextSize {
        Text(
            text,
            color = color,
            style = TextStyle(
                fontSize = size,
                fontWeight = FontWeight.SemiBold,
                shadow = Shadow(Color.Black.copy(alpha = 0.7f), blurRadius = blur),
            ).monospacedDigits(),
            softWrap = false,
            maxLines = 1,
            modifier = Modifier.offset(shift.x, shift.y).rotate(rotation.degrees.toFloat()),
        )
    }
}

/** A bold check mark centred on the origin, as a polyline of about 34 × 26 units ([unit] pixels each). */
private fun addCheckMark(path: Path, unit: Float) {
    path.moveTo(-14 * unit, 0f)
    path.lineTo(-5 * unit, 10 * unit)
    path.lineTo(15 * unit, -11 * unit)
}

/**
 * An arrow that points right, centred on the origin: a shaft and a triangular head. [width] and [height] are the size
 * of its bounding box in pixels.
 */
private fun addArrowShape(path: Path, width: Float, height: Float) {
    val headLength = width * 0.46f
    val shaftHalf = height * 0.17f
    val right = width / 2
    val left = -width / 2
    val half = height / 2
    path.moveTo(right, 0f)
    path.lineTo(right - headLength, -half)
    path.lineTo(right - headLength, -shaftHalf)
    path.lineTo(left, -shaftHalf)
    path.lineTo(left, shaftHalf)
    path.lineTo(right - headLength, shaftHalf)
    path.lineTo(right - headLength, half)
    path.close()
}

/**
 * The direction [deviceAngle] (radians counter-clockwise from the device's +x, as `Guidance.arrowAngle`) has on the
 * screen, as radians counter-clockwise from the screen's right. They agree while the display is in the phone's natural
 * orientation.
 */
private fun screenAngle(deviceAngle: Double, axes: ScreenAxes): Double {
    val x = cos(deviceAngle)
    val y = sin(deviceAngle)
    return atan2(x * axes.up.x + y * axes.up.y, x * axes.right.x + y * axes.right.y)
}

package net.kuecker.lodestar.features.aim

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
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
        panels = model.hudPanels,
        modifier = modifier.fillMaxSize().clearAndSetSemantics {},
    )
}

/** Distance of the texts from the reticle: inside the ring, below the reticle as the reader sees it. */
private val LABEL_OFFSET = 54.dp

/** Half the height of the distance readout (figure and caption) with its backing, and the space kept to the panels. */
private val READOUT_HALF_HEIGHT = 24.dp
private val READOUT_GAP = 6.dp

/**
 * Where the distance readout goes, in dp below the reticle as the reader sees it: in its place below the reticle when
 * there are at least [room] dp before the panels begin, above the reticle when the panels leave less (the cards at
 * the bottom of the HUD cover the middle of a small screen), and then no lower than the panels' top edge.
 */
private fun readoutOffset(room: Dp): Dp {
    if (room >= LABEL_OFFSET + READOUT_HALF_HEIGHT + READOUT_GAP) return LABEL_OFFSET
    val clearOfPanels = room - READOUT_GAP - READOUT_HALF_HEIGHT
    return if (clearOfPanels < -LABEL_OFFSET) clearOfPanels else -LABEL_OFFSET
}

/** Distance from the ring to the middle of the "On target" label above it. */
private val ON_TARGET_LABEL_GAP = 30.dp

/** The ring grows with the screen but leaves room for the panels on a small one. [heightPx] is the stage's height. */
private fun Density.ringRadius(heightPx: Float): Float = min(100.dp.toPx(), max(70.dp.toPx(), heightPx * 0.115f))

/**
 * The ring and arrow around the middle of the available space, and the texts. [rotation] turns the texts so they read
 * upright; the arrow lives in the phone's own screen axes ([axes]), so it ignores the HUD rotation: it always points
 * the way on the glass. [panels] is where the HUD's stack of panels lies (in window pixels): the distance readout
 * keeps clear of it.
 */
@Composable
private fun AimArrowContent(
    guidance: Guidance?,
    rotation: AimHUDRotation,
    axes: ScreenAxes,
    panels: Rect?,
    modifier: Modifier,
) {
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val arrow = remember { Path() }
    // Where the middle of this stage, and so the reticle, lies in the window.
    var centerY by remember { mutableFloatStateOf(Float.NaN) }
    BoxWithConstraints(
        modifier.onGloballyPositioned { centerY = it.positionInRoot().y + it.size.height / 2f },
        contentAlignment = Alignment.Center,
    ) {
        if (guidance == null) return@BoxWithConstraints
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = ringRadius(size.height)
            if (guidance.state == GuidanceState.ON_TARGET) {
                // A ring, a check mark and a word: the state does not depend on colour alone. The check mark goes
                // with the word, on its backing above the ring, so it never lands on the target's name, which the
                // sky chart draws anywhere inside the ring.
                drawCircle(palette.positive, radius, center, style = Stroke(5.dp.toPx()))
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
            // Above the ring, where the panels at the bottom of the screen and the sky labels inside the ring do
            // not reach it.
            val ringDp = with(density) { ringRadius(constraints.maxHeight.toFloat()).toDp() }
            ArrowLabel(
                "On target", palette.positive, 22.sp, rotation, offset = -(ringDp + ON_TARGET_LABEL_GAP),
                icon = Icons.Filled.CheckCircle,
            )
        } else {
            // The room between the reticle and the panels, as the reader sees it: the panels lie at the bottom of an
            // upright HUD and at the top of the screen when it is upside down. A sideways HUD has them at the side.
            val roomPx = if (panels == null || centerY.isNaN()) Float.POSITIVE_INFINITY else when (rotation) {
                AimHUDRotation.UPRIGHT -> panels.top - centerY
                AimHUDRotation.UPSIDE_DOWN -> centerY - panels.bottom
                AimHUDRotation.CLOCKWISE, AimHUDRotation.COUNTERCLOCKWISE -> Float.POSITIVE_INFINITY
            }
            val offset = readoutOffset(with(density) { roomPx.toDp() })
            ArrowLabel(AimFormat.angle(guidance.separation), palette.guidance, 26.sp, rotation, offset, caption = "away")
        }
    }
}

/**
 * A text on a backing that turns with the HUD so it reads upright, [offset] below the reticle as the reader sees it
 * (above it when negative), with a small [caption] under it that says what the figure is and an [icon] before the
 * text. The backing keeps the sky labels under it from running through the digits.
 */
@Composable
private fun ArrowLabel(
    text: String,
    color: Color,
    size: TextUnit,
    rotation: AimHUDRotation,
    offset: Dp = LABEL_OFFSET,
    caption: String? = null,
    icon: ImageVector? = null,
) {
    val palette = LocalPalette.current
    val shadow = Shadow(Color.Black.copy(alpha = 0.7f), blurRadius = with(LocalDensity.current) { 3.dp.toPx() })
    val shift = rotation.rotated(DpOffset(0.dp, offset))
    AimFixedTextSize {
        Column(
            Modifier
                .offset(shift.x, shift.y)
                .rotate(rotation.degrees.toFloat())
                .background(palette.surface.copy(alpha = AIM_PANEL_ALPHA), RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                Text(
                    text,
                    color = color,
                    style = TextStyle(fontSize = size, fontWeight = FontWeight.SemiBold, shadow = shadow).monospacedDigits(),
                    softWrap = false,
                    maxLines = 1,
                )
            }
            if (caption != null) {
                Text(
                    caption,
                    color = color,
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, shadow = shadow),
                    softWrap = false,
                    maxLines = 1,
                )
            }
        }
    }
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

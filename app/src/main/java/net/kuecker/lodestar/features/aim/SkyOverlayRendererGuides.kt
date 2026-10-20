package net.kuecker.lodestar.features.aim

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontWeight
import net.kuecker.lodestar.features.smallbodies.SmallBodyText
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.pointing.GuidanceState
import net.kuecker.lodestar.kit.pointing.PinholeProjection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// The guides drawn over the sky: altitude/azimuth grid, horizon with compass points, DSLR frame, target marker with
// its edge indicator, and the phone's reticle.

// Grid

/** The altitude circles and azimuth lines, with the altitude labels, in views that show at most 50° along the long side. */
internal fun SkyOverlayRenderer.drawGrid() {
    if (!(fieldOfView <= SkyOverlayRenderer.GRID_MAXIMUM_FIELD_OF_VIEW)) return
    val direction = HorizontalCoordinate(boresight)
    val style = AimLabelStyle(10f, FontWeight.Normal, palette.secondaryText.faded(0.75f))
    val world = scratch.world
    world.clear()
    for (circle in AimSkyGrid.altitudeCircles) {
        if (!(abs(circle.altitude - direction.altitude) <= viewRadius + 2)) continue
        world.addPolyline(circle.points, closed = true, frustum = frustum)
        val labelDirection = HorizontalCoordinate(direction.azimuth, circle.altitude).unitVector
        if (projector.project(labelDirection) && isVisible(projector.x, projector.y, 0f)) {
            addLabel(circle.label, projector.x, projector.y, style, 7, offsetX = 22f, offsetY = -8f)
        }
    }
    for (line in AimSkyGrid.azimuthLines) {
        world.addPolyline(line, closed = false, frustum = frustum)
    }
    strokeWorld(world, palette.gridLine, 0.75f)
}

// Horizon

/** Degrees of azimuth are labelled in views narrower than this many degrees. */
private const val AZIMUTH_LABEL_FIELD_OF_VIEW = 30.0

/** The horizon with its ticks and the compass points; in narrow views the azimuth in degrees as well. */
internal fun SkyOverlayRenderer.drawHorizon() {
    val world = scratch.world
    world.clear()
    world.addPolyline(AimSkyGrid.horizon, closed = true, frustum = frustum)
    strokeWorld(world, palette.horizonLine, 1.5f)

    // Ticks stand on the horizon: 5 dp high every 5°, 9 dp every 10°.
    val focalLength = projector.projection.focalLength
    world.clear()
    for (tick in AimSkyGrid.horizonTicks) {
        val base = tick.base
        val height = (if (tick.isMajor) 9 else 5) * density / focalLength
        world.addSegment(base.x, base.y, base.z, base.x, base.y, base.z + height, frustum)
    }
    strokeWorld(world, palette.horizonLine, 1f)

    val cardinalStyle = AimLabelStyle(17f, FontWeight.Bold, palette.primaryText)
    val intercardinalStyle = AimLabelStyle(12f, FontWeight.SemiBold, palette.secondaryText)
    for (label in AimSkyGrid.compassLabels) {
        if (projector.project(label.direction) && isVisible(projector.x, projector.y, 0f)) {
            addLabel(
                label.text, projector.x, projector.y, if (label.isCardinal) cardinalStyle else intercardinalStyle, 2,
                offsetY = if (label.isCardinal) -19f else -16f,
            )
        }
    }
    if (!(fieldOfView <= AZIMUTH_LABEL_FIELD_OF_VIEW)) return
    val degreeStyle = AimLabelStyle(10f, FontWeight.Normal, palette.secondaryText.faded(0.8f))
    for (label in AimSkyGrid.azimuthLabels) {
        if (projector.project(label.base) && isVisible(projector.x, projector.y, 0f)) {
            addLabel(label.text, projector.x, projector.y, degreeStyle, 8, offsetY = 16f)
        }
    }
}

// DSLR frame

/** The DSLR's picture as an outline on the sky, with a crosshair at its centre. */
internal fun SkyOverlayRenderer.drawDSLRFrame() {
    val outline = PinholeProjection.dslrFrameOutline(
        frame.pointing, configuration.lensFrame.horizontal, configuration.lensFrame.vertical, samplesPerEdge = 4,
    )
    val world = scratch.world
    world.clear()
    world.addPolyline(outline, closed = true, frustum = frustum)
    strokeWorld(world, palette.dslrFrame, 2f, StrokeCap.Round, StrokeJoin.Round)
    if (!projector.project(frame.pointing.boresightVector) || !isVisible(projector.x, projector.y, 0f)) return
    val centerX = projector.x
    val centerY = projector.y
    val arm = 9f * density
    val crosshair = scratch.path
    crosshair.rewind()
    crosshair.moveTo(centerX - arm, centerY)
    crosshair.lineTo(centerX + arm, centerY)
    crosshair.moveTo(centerX, centerY - arm)
    crosshair.lineTo(centerX, centerY + arm)
    drawPath(crosshair, palette.dslrFrame, style = Stroke(1.5f * density))
}

// Target

/** Distance in dp from the left and right edges at which the off-screen indicator sits. */
private const val EDGE_SIDE_INSET = 36f

/** Distances in dp from the top and bottom edges, which leave room for the status chips and the control panel. */
private const val EDGE_TOP_INSET = 96f
private const val EDGE_BOTTOM_INSET = 240f

/**
 * The marker and name of what guidance leads to (the target, or the panel of a running mosaic), or an arrow at the
 * screen edge towards it when it is out of view.
 */
internal fun SkyOverlayRenderer.drawTarget() {
    val target = frame.target
    if (target != null) {
        // A comet's compact name ("Pons-Brooks"): its full designation would not fit beside the ring.
        drawAimMarker(target.direction, SmallBodyText.displayName(target.obj))
        return
    }
    frame.satellite?.let {
        drawAimMarker(it.direction, it.label)
        return
    }
    val panel = frame.panel ?: return
    drawAimMarker(panel.direction, panel.label)
}

private fun SkyOverlayRenderer.drawAimMarker(direction: Vector3, name: String) {
    val isOnTarget = frame.guidance?.state == GuidanceState.ON_TARGET
    val color = if (isOnTarget) palette.positive else palette.guidance
    if (!projector.project(direction) || !isVisible(projector.x, projector.y, 0f)) {
        drawEdgeIndicator(direction, color)
        return
    }
    val center = Offset(projector.x, projector.y)
    // A pulsing ring marks the target; once the camera is on it the ring stops and thickens.
    val pulse = if (isOnTarget) 0.0 else 3 * sin(time * 2 * PI / 1.6)
    val radius = (21 + pulse).toFloat()
    drawCircle(color, radius * density, center, style = Stroke((if (isOnTarget) 3.5f else 2f) * density))
    drawCircle(color, 2.5f * density, center)
    addLabel(name, center.x, center.y, AimLabelStyle(14f, FontWeight.Bold, color), 0, offsetY = -(radius + 15))
}

/** An arrow at the screen edge in the direction of a target that is not in view. */
private fun SkyOverlayRenderer.drawEdgeIndicator(direction: Vector3, color: Color) {
    val screen = worldToScreen * direction
    var headingX = screen.x
    var headingY = -screen.y
    val length = hypot(headingX, headingY)
    if (length > 1e-6) {
        headingX /= length
        headingY /= length
    } else {
        headingX = 0.0
        headingY = -1.0
    }

    val left = EDGE_SIDE_INSET * density
    val top = EDGE_TOP_INSET * density
    val right = size.width - EDGE_SIDE_INSET * density
    val bottom = size.height - EDGE_BOTTOM_INSET * density
    if (!(right - left > 0 && bottom - top > 0)) return
    val centerX = size.width / 2
    val centerY = size.height / 2
    var distance = Double.POSITIVE_INFINITY
    if (headingX > 0.001) {
        distance = min(distance, (right - centerX) / headingX)
    } else if (headingX < -0.001) {
        distance = min(distance, (left - centerX) / headingX)
    }
    if (headingY > 0.001) {
        distance = min(distance, (bottom - centerY) / headingY)
    } else if (headingY < -0.001) {
        distance = min(distance, (top - centerY) / headingY)
    }
    if (!distance.isFinite()) return
    distance = max(distance, 0.0)
    val tipX = (centerX + headingX * distance).toFloat()
    val tipY = (centerY + headingY * distance).toFloat()

    val arrow = scratch.path
    arrow.rewind()
    arrow.moveTo(16f * density, 0f)
    arrow.lineTo(-9f * density, -12f * density)
    arrow.lineTo(-3f * density, 0f)
    arrow.lineTo(-9f * density, 12f * density)
    arrow.close()
    val angle = Math.toDegrees(atan2(headingY, headingX)).toFloat()
    withTransform({
        translate(tipX, tipY)
        rotate(angle, Offset.Zero)
    }) {
        drawPath(arrow, color)
        drawPath(arrow, Color.Black.faded(0.5f), style = Stroke(density))
    }
    // The distance is not repeated here: the arrow around the reticle carries it, labelled.
}

// Reticle

/** The phone camera's axis: a ring with four ticks at the centre of the screen. */
internal fun SkyOverlayRenderer.drawReticle() {
    val center = Offset(size.width / 2, size.height / 2)
    val color = palette.primaryText.faded(0.7f)
    drawCircle(color, 15f * density, center, style = Stroke(density))
    drawCircle(color, 1.5f * density, center)
    val ticks = scratch.path
    ticks.rewind()
    for (quarter in 0 until 4) {
        val dx = if (quarter % 2 == 0) (1 - quarter) else 0
        val dy = if (quarter % 2 == 1) (2 - quarter) else 0
        ticks.moveTo(center.x + dx * 15f * density, center.y + dy * 15f * density)
        ticks.lineTo(center.x + dx * 23f * density, center.y + dy * 23f * density)
    }
    drawPath(ticks, color, style = Stroke(density))
}

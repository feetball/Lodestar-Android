package net.kuecker.lodestar.features.aim

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.text.font.FontWeight
import net.kuecker.lodestar.kit.foundation.AstroMath

// The two layers that need data from the person's session: the ground below the recorded skyline and the panels of a
// running mosaic.

// Skyline

/** Opacity of the shading over the camera picture: the real ground stays visible through it. */
private const val GROUND_OPACITY_OVER_CAMERA = 0.5f

/** Opacity of the ground in the virtual sky, where it covers the stars it hides. */
private const val GROUND_OPACITY_VIRTUAL = 0.92f

/**
 * The ground below the recorded skyline, shaded, and the skyline itself as a line. Drawn over the sky so that stars,
 * constellations and objects behind the trees or the ridge are darkened. Nothing happens for an open horizon.
 */
internal fun SkyOverlayRenderer.drawSkyline() {
    val skyline = content?.skyline ?: return
    val clip = scratch.polygonClip
    val path = scratch.path
    path.rewind()
    path.fillType = PathFillType.NonZero
    var isEmpty = true
    for (fan in skyline.fans) {
        val count = clip.clip(fan, fan.size / 3, frustum.planes)
        if (count < 3) continue
        val points = scratch.points(2 * count)
        projector.projectAll(clip.result, count, points)
        var isStarted = false
        for (index in 0 until count) {
            val x = points[2 * index]
            if (x.isNaN()) continue
            if (isStarted) path.lineTo(x, points[2 * index + 1]) else path.moveTo(x, points[2 * index + 1])
            isStarted = true
        }
        if (isStarted) {
            path.close()
            isEmpty = false
        }
    }
    if (!isEmpty) {
        // Over the picture the ground is darkened; in the virtual sky, which is as dark as the background already,
        // it takes the colour of the panels so that it can be told from the sky.
        val ground = if (isCameraPicture) {
            palette.background.faded(GROUND_OPACITY_OVER_CAMERA)
        } else {
            palette.surface.faded(GROUND_OPACITY_VIRTUAL)
        }
        drawPath(path, ground)
    }
    val world = scratch.world
    world.clear()
    // The line ends where it began (azimuth 360° is 0°), so it needs no closing segment.
    world.addPolyline(skyline.line, closed = false, frustum = frustum)
    strokeWorld(world, palette.horizonLine, 2f, StrokeCap.Round, StrokeJoin.Round)
}

// Mosaic

/** Stroke widths in dp of the panels' outlines. */
private const val PENDING_WIDTH = 1.25f
private const val DONE_WIDTH = 1f
private const val NEXT_WIDTH = 2f
private const val CURRENT_WIDTH = 3.5f

/** Opacity of the fill of the panel being shot. */
private const val CURRENT_FILL_OPACITY = 0.14f

/** The most panel numbers shown. */
private const val NUMBER_LIMIT = 12

/**
 * The outlines of all panels of the running mosaic as the phone's camera sees them: panels still to be shot in a
 * faint line, shot ones dimmed, the next one marked a little stronger, and the panel being shot thick and tinted, with
 * the number of each panel in the shooting order. Nothing happens without a mosaic.
 */
internal fun SkyOverlayRenderer.drawMosaic() {
    val mosaic = mosaic ?: return
    val count = mosaic.panelCount
    val step = mosaicStep
    val inView = scratch.panelsInView(count)
    // Panels whose bounding circle does not reach into the view are not even tried.
    for (index in 0 until count) {
        val centers = mosaic.centers
        val dot = boresight.x * centers[3 * index] + boresight.y * centers[3 * index + 1] + boresight.z * centers[3 * index + 2]
        inView[index] = AstroMath.acosDeg(dot) <= viewRadius + mosaic.radii[index]
    }
    strokePanels(mosaic, inView, step, AimMosaicPointings.PENDING, palette.dslrFrame.faded(0.45f), PENDING_WIDTH)
    strokePanels(mosaic, inView, step, AimMosaicPointings.DONE, palette.positive.faded(0.4f), DONE_WIDTH)
    strokePanels(mosaic, inView, step, AimMosaicPointings.NEXT, palette.guidance.faded(0.8f), NEXT_WIDTH)
    strokePanels(mosaic, inView, step, AimMosaicPointings.CURRENT, palette.guidance, CURRENT_WIDTH)
    if (step in 0 until count) fillCurrentPanel(mosaic, mosaic.order[step])
    addPanelNumbers(mosaic, inView, step)
}

/** Strokes the outlines of the panels of one [kind] that reach into the view. */
private fun SkyOverlayRenderer.strokePanels(
    mosaic: AimMosaicPointings, inView: BooleanArray, step: Int, kind: Int, color: Color, width: Float,
) {
    val world = scratch.world
    world.clear()
    for (index in 0 until mosaic.panelCount) {
        if (!inView[index] || mosaic.kind(index, step) != kind) continue
        world.addPolyline(mosaic.outlines[index], closed = true, frustum = frustum)
    }
    strokeWorld(world, color, width, StrokeCap.Round, StrokeJoin.Round)
}

/** Tints the panel being shot, cut to the view. */
private fun SkyOverlayRenderer.fillCurrentPanel(mosaic: AimMosaicPointings, index: Int) {
    val ring = mosaic.corners[index]
    val clip = scratch.polygonClip
    val count = clip.clip(ring, ring.size / 3, frustum.planes)
    if (count < 3) return
    val points = scratch.points(2 * count)
    projector.projectAll(clip.result, count, points)
    val path = scratch.path
    path.rewind()
    path.fillType = PathFillType.NonZero
    var isStarted = false
    for (vertex in 0 until count) {
        val x = points[2 * vertex]
        if (x.isNaN()) continue
        if (isStarted) path.lineTo(x, points[2 * vertex + 1]) else path.moveTo(x, points[2 * vertex + 1])
        isStarted = true
    }
    if (!isStarted) return
    path.close()
    drawPath(path, palette.guidance.faded(CURRENT_FILL_OPACITY))
}

/**
 * Queues the number of each panel whose centre is on the screen, up to a dozen; the panel being shot is named by the
 * target ring instead.
 */
private fun SkyOverlayRenderer.addPanelNumbers(mosaic: AimMosaicPointings, inView: BooleanArray, step: Int) {
    val centers = mosaic.centers
    val style = AimLabelStyle(11f, FontWeight.SemiBold, palette.dslrFrame.faded(0.8f))
    var shown = 0
    for (index in 0 until mosaic.panelCount) {
        if (shown >= NUMBER_LIMIT) return
        if (!inView[index] || mosaic.positions[index] == step) continue
        if (!projector.project(centers[3 * index], centers[3 * index + 1], centers[3 * index + 2])) continue
        if (!isVisible(projector.x, projector.y, 0f)) continue
        addLabel(mosaic.numbers[index], projector.x, projector.y, style, 9)
        shown += 1
    }
}

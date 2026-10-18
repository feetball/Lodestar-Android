package net.kuecker.lodestar.features.mount

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import net.kuecker.lodestar.features.aim.AimLabelStyle
import net.kuecker.lodestar.features.aim.SkyOverlayRenderer
import net.kuecker.lodestar.services.MountMarker

/** Half the width of the marker's diamond, dp. */
private const val MARKER_RADIUS = 14f

/** Priority of the marker's label among the labels of a frame (the target's name is 0). */
private const val MARKER_LABEL_PRIORITY = 1

/**
 * Draws where the connected mount reports pointing: a diamond with a dot at its centre and the word "Mount" below. It
 * is a different shape from the target's ring, so the two can be told apart when they meet. Nothing is drawn while the
 * marker is off screen or there is none. The path and label are the renderer's reusable ones, so a frame allocates
 * nothing here.
 */
internal fun SkyOverlayRenderer.drawMountMarker() {
    val marker: MountMarker = mountMarker ?: return
    if (!projector.project(marker.direction) || !isVisible(projector.x, projector.y, 0f)) return
    val center = Offset(projector.x, projector.y)
    val color = palette.deepSkyMarker
    val radius = MARKER_RADIUS * density
    val diamond = scratch.path
    diamond.rewind()
    diamond.moveTo(center.x, center.y - radius)
    diamond.lineTo(center.x + radius, center.y)
    diamond.lineTo(center.x, center.y + radius)
    diamond.lineTo(center.x - radius, center.y)
    diamond.close()
    drawPath(diamond, color, style = Stroke(2f * density, join = StrokeJoin.Round))
    drawCircle(color, 2f * density, center)
    addLabel("Mount", center.x, center.y, AimLabelStyle(12f, FontWeight.SemiBold, color), MARKER_LABEL_PRIORITY, offsetY = MARKER_RADIUS + 12f)
}

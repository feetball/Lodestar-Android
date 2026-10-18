package net.kuecker.lodestar.features.smallbodies

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import net.kuecker.lodestar.features.aim.AimLabelStyle
import net.kuecker.lodestar.features.aim.SkyOverlayRenderer
import net.kuecker.lodestar.features.aim.faded
import kotlin.math.hypot

/** The faintest magnitude whose name the overlay writes in any view. */
private const val NAME_MAGNITUDE = 8.0

/** Fainter ones are named in views narrower than [NARROW_VIEW] degrees, up to this magnitude. */
private const val NARROW_NAME_MAGNITUDE = 9.0
private const val NARROW_VIEW = 40.0

/** The most names written in one frame. */
private const val NAME_LIMIT = 6

/** Bodies below the horizon are drawn this much fainter, like the stars below it. */
private const val BELOW_HORIZON_OPACITY = 0.4f

/**
 * The comets and asteroids of the sky content: a dot for each (bigger for the brighter) and, for a comet, a ring and a
 * short line along its ion tail. Bodies the recorded skyline hides are left out; the target is drawn by the reticle,
 * which also names it. Projects each body once and allocates nothing per frame beyond the label queue's own work.
 */
internal fun SkyOverlayRenderer.drawSmallBodies() {
    val content = content ?: return
    val snapshot = content.snapshot
    val markers = snapshot.smallBodies
    if (markers.isEmpty()) return
    val targetID = snapshot.target?.obj?.id
    val ringWidth = Stroke(density)
    val cometNames = AimLabelStyle(11f, FontWeight.Medium, SmallBodyMarkerStyle.color(palette, isComet = true))
    val asteroidNames = AimLabelStyle(11f, FontWeight.Medium, SmallBodyMarkerStyle.color(palette, isComet = false))
    var names = 0
    for (index in markers.indices) {
        val marker = markers[index]
        if (marker.isBehindSkyline || !projector.project(marker.direction)) continue
        val x = projector.x
        val y = projector.y
        if (!isVisible(x, y, 40f)) continue
        val opacity = if (marker.direction.z < 0) BELOW_HORIZON_OPACITY else 1f
        val color = SmallBodyMarkerStyle.color(palette, marker.isComet).faded(opacity)
        val radius = SmallBodyMarkerStyle.dotRadius(marker.magnitude, marker.isComet)
        val center = Offset(x, y)
        if (marker.isComet) {
            val end = marker.tailEnd
            if (end != null && projector.project(end)) {
                val dx = projector.x - x
                val dy = projector.y - y
                val length = hypot(dx, dy)
                if (length > 1e-3f) {
                    val scale = SmallBodyMarkerStyle.tailLength(marker.magnitude) * density / length
                    drawLine(
                        color.faded(0.75f), center, Offset(x + dx * scale, y + dy * scale),
                        strokeWidth = 1.5f * density, cap = StrokeCap.Round,
                    )
                }
            }
            drawCircle(color.faded(0.5f), (radius + 2.5f) * density, center, style = ringWidth)
        }
        drawCircle(color, radius * density, center)
        if (marker.id != targetID && names < NAME_LIMIT && isNamed(marker.magnitude, fieldOfView)) {
            addLabel(SmallBodyText.displayName(marker.obj), x, y, if (marker.isComet) cometNames else asteroidNames, 2, offsetY = radius + 12)
            names += 1
        }
    }
}

/** Whether a body of [magnitude] is named in a view [fieldOfView] degrees wide. */
internal fun isNamed(magnitude: Double?, fieldOfView: Double): Boolean {
    if (magnitude == null || !magnitude.isFinite()) return false
    return magnitude <= NAME_MAGNITUDE || (fieldOfView < NARROW_VIEW && magnitude <= NARROW_NAME_MAGNITUDE)
}

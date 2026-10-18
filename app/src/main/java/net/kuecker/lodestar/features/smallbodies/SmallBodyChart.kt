package net.kuecker.lodestar.features.smallbodies

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.features.skychart.PlanisphereProjection
import net.kuecker.lodestar.features.skychart.PlanisphereRenderer
import net.kuecker.lodestar.features.skychart.PlanisphereViewport
import net.kuecker.lodestar.features.skychart.SkyChartLabel
import net.kuecker.lodestar.features.skychart.SkyChartPickable
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import net.kuecker.lodestar.shared.SkySnapshot
import net.kuecker.lodestar.theme.Palette
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** How the dots and tails of comets and asteroids look, in dp; the chart and the Aim overlay share them. */
internal object SmallBodyMarkerStyle {
    /** The radius of the dot: bright ones are bigger, an unknown magnitude is a small dot. */
    fun dotRadius(magnitude: Double?, isComet: Boolean): Float {
        if (magnitude == null || !magnitude.isFinite()) return 2.5f
        val radius = if (isComet) 4.5 - 0.35 * (magnitude - 4.0) else 3.4 - 0.3 * (magnitude - 5.0)
        return max(if (isComet) 2.5 else 2.0, min(radius, if (isComet) 4.5 else 3.4)).toFloat()
    }

    /** The length of the line along a comet's tail: a little longer for the brighter comets. */
    fun tailLength(magnitude: Double?): Float {
        if (magnitude == null || !magnitude.isFinite()) return 12f
        return (12.0 + max(0.0, min((8.0 - magnitude) * 2.0, 10.0))).toFloat()
    }

    /** The zoom from which the chart writes the name of a body of [magnitude]: the brightest are named at once. */
    fun labelZoom(magnitude: Double?): Float = when {
        magnitude == null || !magnitude.isFinite() -> 3f
        magnitude <= 6.5 -> 1f
        magnitude <= 8.0 -> 1.8f
        else -> 3f
    }

    /** The marker's colour: icy blue for a comet, the quiet text colour for an asteroid. */
    fun color(palette: Palette, isComet: Boolean) = if (isComet) palette.deepSkyMarker else palette.secondaryText
}

/**
 * A comet or an asteroid on the planisphere: a dot and, for a comet, the direction of its ion tail. The coordinates are
 * chart coordinates (zenith at the origin, horizon radius 1).
 */
internal class SmallBodyChartMarker(
    val obj: CelestialObject,
    val x: Float,
    val y: Float,
    /** The unit direction of the tail in chart space; both 0 for an asteroid or a tail with no direction. */
    val tailX: Float,
    val tailY: Float,
    val magnitude: Double?,
    val horizontal: HorizontalCoordinate,
    val isComet: Boolean,
    val isTarget: Boolean,
) {
    val id: String get() = obj.id
    val name: String get() = SmallBodyText.displayName(obj)
    val hasTail: Boolean get() = tailX != 0f || tailY != 0f
}

/** Turns the snapshot's comets and asteroids into chart markers, labels and pickables. */
internal object SmallBodyChartMarkers {
    /** The markers of the bodies above the horizon and not hidden by the recorded skyline. */
    fun build(markers: List<SkySnapshot.SmallBodyMarker>, targetID: String?): List<SmallBodyChartMarker> =
        markers.filter { it.direction.z >= 0 && !it.isBehindSkyline }.map { marker ->
            val point = PlanisphereProjection.chartPoint(marker.direction)
            var tailX = 0.0
            var tailY = 0.0
            marker.tailEnd?.let { end ->
                val tip = PlanisphereProjection.chartPoint(end)
                val dx = tip.x - point.x
                val dy = tip.y - point.y
                val length = hypot(dx, dy)
                if (length > 1e-12) {
                    tailX = dx / length
                    tailY = dy / length
                }
            }
            SmallBodyChartMarker(
                marker.obj, point.x.toFloat(), point.y.toFloat(), tailX.toFloat(), tailY.toFloat(), marker.magnitude,
                marker.horizontal, isComet = marker.isComet, isTarget = marker.id == targetID,
            )
        }

    /** The names. The target already has its own label; the others appear from the zoom their brightness needs. */
    fun labels(markers: List<SmallBodyChartMarker>): List<SkyChartLabel> =
        markers.filter { !it.isTarget }.map { marker ->
            val rank = (marker.magnitude ?: 12.0).toInt()
            SkyChartLabel(
                "smallBody.${marker.id}", marker.name, SkyChartLabel.Style.BODY, marker.x, marker.y,
                priority = (if (marker.isComet) 75 else 55) - rank, minimumZoom = SmallBodyMarkerStyle.labelZoom(marker.magnitude),
                offset = 9f,
            )
        }

    fun pickables(markers: List<SmallBodyChartMarker>): List<SkyChartPickable> =
        markers.map { SkyChartPickable(it.id, SkyChartPickable.Kind.SMALL_BODY, it.x, it.y, it.obj) }

    /** The most bodies the screen-reader summary names. */
    private const val SPOKEN_LIMIT = 5

    /** "Comets and asteroids: Tsuchinshan-ATLAS, comet, altitude 34 degrees, southwest. …", or `null` when none is shown. */
    fun spokenSummary(markers: List<SmallBodyChartMarker>): String? {
        if (markers.isEmpty()) return null
        val named = markers.take(SPOKEN_LIMIT).joinToString(" ") { description(it) }
        val more = if (markers.size > SPOKEN_LIMIT) " And ${markers.size - SPOKEN_LIMIT} more." else ""
        return "Comets and asteroids above the horizon. $named$more"
    }

    /** "Tsuchinshan-ATLAS, comet, altitude 34 degrees, southwest." */
    fun description(marker: SmallBodyChartMarker): String {
        val direction = AngleFormatter.compassPoint(marker.horizontal.azimuth).fullName.lowercase()
        val altitude = marker.horizontal.altitude.roundedHalfAwayFromZero().toInt()
        val kind = marker.obj.category.displayName.lowercase()
        return "${marker.name}, $kind, altitude $altitude degrees, $direction."
    }
}

/**
 * The comets and asteroids on the chart as synthetic elements that accessibility services can activate to select them,
 * standing in for the dots the canvas draws.
 */
@Composable
internal fun SmallBodyAccessibleMarkers(markers: List<SmallBodyChartMarker>, onSelect: (CelestialObject) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        for (marker in markers) {
            Box(
                Modifier.size(1.dp).semantics(mergeDescendants = true) {
                    contentDescription = SmallBodyChartMarkers.description(marker)
                    role = Role.Button
                    onClick(label = "Shows the object's card") {
                        onSelect(marker.obj)
                        true
                    }
                },
            )
        }
    }
}

/** Draws the comets and asteroids of a chart scene. Allocates nothing per marker. */
internal object SmallBodyChartPainter {
    /** How far along its tail (chart units) the direction of a tail is probed; small, so the chart is nearly linear there. */
    private const val PROBE = 0.02f

    /**
     * Draws [markers] into [scope]: a dot for each, with a ring and a short line along the ion tail for a comet, and
     * reserves the rectangle each occupies so that labels keep clear of it.
     */
    fun draw(
        scope: DrawScope,
        markers: List<SmallBodyChartMarker>,
        viewport: PlanisphereViewport,
        palette: Palette,
        density: Float,
        occupied: PlanisphereRenderer.RectList,
    ) {
        if (markers.isEmpty()) return
        val margin = 12f * density
        val width = viewport.layout.width
        val height = viewport.layout.height
        val ring = Stroke(density)
        with(scope) {
            for (index in markers.indices) {
                val marker = markers[index]
                val x = viewport.screenX(marker.x, marker.y)
                val y = viewport.screenY(marker.x, marker.y)
                if (x < -margin || x >= width + margin || y < -margin || y >= height + margin) continue
                val color = SmallBodyMarkerStyle.color(palette, marker.isComet)
                val radius = SmallBodyMarkerStyle.dotRadius(marker.magnitude, marker.isComet) * density
                var reach = radius
                if (marker.isComet) {
                    if (marker.hasTail) {
                        val dx = viewport.screenX(marker.x + marker.tailX * PROBE, marker.y + marker.tailY * PROBE) - x
                        val dy = viewport.screenY(marker.x + marker.tailX * PROBE, marker.y + marker.tailY * PROBE) - y
                        val length = hypot(dx, dy)
                        if (length > 1e-3f) {
                            val tail = SmallBodyMarkerStyle.tailLength(marker.magnitude) * density
                            val scale = tail / length
                            drawLine(
                                color.copy(alpha = color.alpha * 0.75f), Offset(x, y), Offset(x + dx * scale, y + dy * scale),
                                strokeWidth = 1.5f * density, cap = StrokeCap.Round,
                            )
                            reach = max(reach, tail)
                        }
                    }
                    drawCircle(color.copy(alpha = color.alpha * 0.5f), radius + 2.5f * density, Offset(x, y), style = ring)
                }
                drawCircle(color, radius, Offset(x, y))
                val reserved = max(reach, radius + 2.5f * density)
                occupied.add(x - reserved, y - reserved, x + reserved, y + reserved)
            }
        }
    }
}

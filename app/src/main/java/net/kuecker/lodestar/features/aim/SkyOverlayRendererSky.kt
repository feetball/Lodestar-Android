package net.kuecker.lodestar.features.aim

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import net.kuecker.lodestar.kit.catalog.ObjectCategory
import net.kuecker.lodestar.shared.SkySnapshot
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// The celestial layers: Milky Way, constellations, deep-sky objects, stars, the Sun, the Moon and the planets.

// Milky Way

/** The Milky Way: one even-odd fill per brightness level, the outlines cut to the viewport first. */
internal fun SkyOverlayRenderer.drawMilkyWay() {
    if (!configuration.showMilkyWay) return
    val layers = content?.milkyWay ?: return
    val clip = scratch.polygonClip
    val path = scratch.path
    for (layer in layers) {
        path.rewind()
        path.fillType = PathFillType.EvenOdd
        var isEmpty = true
        for (ring in layer.rings) {
            if (!(boresight.angle(ring.center) <= ring.radius + viewRadius)) continue
            val count = clip.clip(ring.vertices, ring.count, frustum.planes)
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
        if (!isEmpty) drawPath(path, palette.milkyWay)
    }
}

// Constellations

/** The constellation figures and, by preference, their names. */
internal fun SkyOverlayRenderer.drawConstellations() {
    val content = content ?: return
    val figures = content.snapshot.constellations
    if (figures.isEmpty()) return
    if (configuration.showConstellationLines) {
        val world = scratch.world
        world.clear()
        for (figure in figures) {
            for (line in figure.lines) {
                world.addPolyline(line, closed = false, frustum = frustum)
            }
        }
        strokeWorld(world, palette.constellationLine, 1f)
    }
    if (configuration.showConstellationNames) {
        val style = AimLabelStyle(11f, FontWeight.Medium, palette.constellationLabel)
        val names = content.constellationNames
        val points = scratch.points(2 * names.size)
        projector.projectAll(content.constellationLabelVectors, names.size, points)
        for (index in names.indices) {
            if (isVisible(points[2 * index], points[2 * index + 1], 0f)) {
                addLabel(names[index], points[2 * index], points[2 * index + 1], style, 5)
            }
        }
    }
}

// Deep-sky objects

/** Fainter objects are left out the wider the view, so a wide view stays readable. */
internal fun deepSkyMagnitudeCutoff(fieldOfView: Double): Double = when {
    fieldOfView >= 70 -> 7.0
    fieldOfView >= 40 -> 8.5
    fieldOfView >= 20 -> 10.0
    else -> Double.POSITIVE_INFINITY
}

/** Names are shown in views narrower than this many degrees. */
private const val DEEP_SKY_NAME_FIELD_OF_VIEW = 40.0

/** The most deep-sky names shown at once. */
private const val DEEP_SKY_NAME_LIMIT = 12

/** The deep-sky objects as chart symbols, brighter than the cutoff for the field of view, with names when zoomed in. */
internal fun SkyOverlayRenderer.drawDeepSky() {
    if (!configuration.showDeepSkyObjects) return
    val content = content ?: return
    val markers = content.deepSky
    if (markers.isEmpty()) return
    val shown = content.deepSkyCount(deepSkyMagnitudeCutoff(fieldOfView))
    val points = scratch.points(2 * shown)
    projector.projectAll(content.deepSkyVectors, shown, points)
    var namesShown = 0
    val nameStyle = AimLabelStyle(11f, FontWeight.Normal, palette.deepSkyMarker)
    val outline = Stroke(density)
    for (index in 0 until shown) {
        val x = points[2 * index]
        val y = points[2 * index + 1]
        if (!isVisible(x, y, 16f)) continue
        val marker = markers[index]
        val extent = min(max(marker.diameter * pointsPerDegree, 8.0), 44.0).toFloat()
        drawSymbol(marker.shape, x, y, extent, outline)
        if (fieldOfView < DEEP_SKY_NAME_FIELD_OF_VIEW && namesShown < DEEP_SKY_NAME_LIMIT) {
            addLabel(marker.name, x, y, nameStyle, 6, offsetY = extent / 2 + 10)
            namesShown += 1
        }
    }
}

/**
 * The chart symbol for a deep-sky object, [extent] dp across: ellipse for galaxies, circle with a dot for clusters,
 * square for nebulae.
 */
private fun SkyOverlayRenderer.drawSymbol(shape: AimDeepSkyMarker.Shape, x: Float, y: Float, extent: Float, stroke: Stroke) {
    val color = palette.deepSkyMarker
    val size = extent * density
    val half = size / 2
    val center = Offset(x, y)
    when (shape) {
        AimDeepSkyMarker.Shape.ELLIPSE -> withTransform({
            translate(x, y)
            rotate(-30f, Offset.Zero)
        }) {
            drawOval(color, topLeft = Offset(-half, -half * 0.5f), size = Size(size, half), style = stroke)
        }
        AimDeepSkyMarker.Shape.CLUSTER_DOT -> {
            drawCircle(color, half, center, style = stroke)
            drawCircle(color, 1.2f * density, center, style = stroke)
        }
        AimDeepSkyMarker.Shape.SQUARE ->
            drawRect(color, Offset(x - half * 0.8f, y - half * 0.8f), Size(size * 0.8f, size * 0.8f), style = stroke)
        AimDeepSkyMarker.Shape.CIRCLE -> drawCircle(color, half * 0.8f, center, style = stroke)
    }
}

// Stars

/** Radius in dp of the stars in each brightness class. */
private val STAR_RADII = floatArrayOf(3.6f, 3.0f, 2.5f, 2.0f, 1.6f, 1.25f, 1.0f)

/** Opacity of the stars in each brightness class. */
private val STAR_OPACITIES = floatArrayOf(1f, 1f, 1f, 0.95f, 0.9f, 0.8f, 0.65f)

/** B−V colour index standing for each tint class. */
private val TINT_COLOR_INDICES = doubleArrayOf(0.0, 0.6, 1.4)

/** Stars below the horizon are drawn this much fainter. */
private const val BELOW_HORIZON_OPACITY = 0.35f

/** Stars with names shown at once. */
private const val STAR_NAME_LIMIT = 14

/**
 * The stars as round dots, one batched draw per group of equal look, and the names of the brightest ones.
 * Projects each group in one go and packs the dots that are on the screen to the front of a reused buffer.
 */
internal fun SkyOverlayRenderer.drawStars() {
    val content = content ?: return
    val paint = scratch.starPaint
    val margin = 4f * density
    val right = size.width + margin
    val bottom = size.height + margin
    for (group in content.starGroups) {
        val count = group.count
        val points = scratch.points(2 * count)
        projector.projectAll(group.vectors, count, points)
        // Keep the dots on the screen, packed to the front of the buffer. Stars behind the camera are NaN.
        var kept = 0
        for (index in 0 until count) {
            val x = points[2 * index]
            val y = points[2 * index + 1]
            if (x >= -margin && x < right && y >= -margin && y < bottom) {
                points[2 * kept] = x
                points[2 * kept + 1] = y
                kept += 1
            }
        }
        if (kept == 0) continue
        val opacity = STAR_OPACITIES[min(group.brightness, STAR_OPACITIES.lastIndex)] *
            (if (group.isBelowHorizon) BELOW_HORIZON_OPACITY else 1f)
        val color = palette.starColor(TINT_COLOR_INDICES[min(group.tint, TINT_COLOR_INDICES.lastIndex)]).faded(opacity)
        paint.strokeWidth = 2 * STAR_RADII[min(group.brightness, STAR_RADII.lastIndex)] * density
        paint.color = color.toArgb()
        drawIntoCanvas { it.nativeCanvas.drawPoints(points, 0, 2 * kept, paint) }
    }
    val style = AimLabelStyle(11f, FontWeight.Normal, palette.primaryText.faded(0.9f))
    val named = content.namedStars
    val points = scratch.points(2 * named.size)
    projector.projectAll(content.namedStarVectors, named.size, points)
    var namesShown = 0
    for ((index, star) in named.withIndex()) {
        if (namesShown >= STAR_NAME_LIMIT) break
        val x = points[2 * index]
        val y = points[2 * index + 1]
        if (!isVisible(x, y, 0f) || star.direction.z < 0) continue
        addLabel(star.name, x, y, style, 4, offsetY = -14f)
        namesShown += 1
    }
}

// Sun, Moon and planets

/** The Sun, the Moon (with its phase) and the planets, with their names. */
internal fun SkyOverlayRenderer.drawBodies() {
    val content = content ?: return
    val bodies = content.snapshot.bodies
    val style = AimLabelStyle(12f, FontWeight.SemiBold, palette.planetMarker)
    for (body in bodies) {
        if (!projector.project(body.direction)) continue
        val x = projector.x
        val y = projector.y
        if (!isVisible(x, y, 24f)) continue
        val color = palette.planetMarker.faded(if (body.direction.z < 0) 0.5f else 1f)
        val center = Offset(x, y)
        val disc = body.angularDiameter * pointsPerDegree / 2
        val radius: Float
        when (body.obj.category) {
            ObjectCategory.MOON -> {
                radius = max(disc, 8.0).toFloat()
                drawMoon(body, center, radius, litLimbAngle(body, content.sun, center), color)
            }
            ObjectCategory.SUN -> {
                radius = max(disc, 7.0).toFloat()
                drawCircle(color, radius * density, center)
                drawCircle(color, (radius + 4) * density, center, style = Stroke(density))
            }
            else -> {
                radius = min(max(3.4 - 0.35 * (body.magnitude ?: 2.0), 2.5), 6.5).toFloat()
                drawCircle(color, radius * density, center)
                drawCircle(color.faded(0.6f), (radius + 3) * density, center, style = Stroke(0.75f * density))
            }
        }
        addLabel(body.obj.name, x, y, style, 1, offsetY = radius + 13)
    }
}

/**
 * The screen angle (radians, clockwise from screen-right) of the direction in which the Moon's bright limb
 * points, which is towards the Sun.
 */
private fun SkyOverlayRenderer.litLimbAngle(moon: SkySnapshot.Body, sun: SkySnapshot.Body?, point: Offset): Double {
    if (sun == null) return 0.0
    val towardsSun = sun.direction - moon.direction * moon.direction.dot(sun.direction)
    if (!(towardsSun.length > 1e-6)) return 0.0
    val probe = moon.direction + towardsSun.normalized * 0.02
    if (!projector.project(probe)) return 0.0
    return atan2((projector.y - point.y).toDouble(), (projector.x - point.x).toDouble())
}

/** The Moon as a disc with its lit part drawn from the illuminated fraction; [radius] is in dp. */
private fun SkyOverlayRenderer.drawMoon(moon: SkySnapshot.Body, center: Offset, radius: Float, litAngle: Double, color: Color) {
    val r = radius * density
    val lit = scratch.path
    withTransform({
        translate(center.x, center.y)
        rotate(Math.toDegrees(litAngle).toFloat(), Offset.Zero)
    }) {
        drawCircle(color.faded(0.22f), r, Offset.Zero)
        // The lit area lies between the bright limb (right half circle) and the terminator (half ellipse whose
        // width follows the phase: it bulges left when more than half is lit and right when less).
        val squeeze = 2 * (moon.illuminatedFraction ?: 1.0) - 1
        lit.rewind()
        lit.fillType = PathFillType.NonZero
        val steps = 16
        for (step in 0..steps) {
            val t = PI * step / steps
            val x = r * sin(t).toFloat()
            val y = -r * cos(t).toFloat()
            if (step == 0) lit.moveTo(x, y) else lit.lineTo(x, y)
        }
        for (step in 0..steps) {
            val t = PI * (1 - step.toDouble() / steps)
            lit.lineTo(-r * (squeeze * sin(t)).toFloat(), -r * cos(t).toFloat())
        }
        lit.close()
        drawPath(lit, color)
        drawCircle(color, r, Offset.Zero, style = Stroke(density))
    }
}

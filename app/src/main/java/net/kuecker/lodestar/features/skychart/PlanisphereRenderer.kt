package net.kuecker.lodestar.features.skychart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawTransform
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import net.kuecker.lodestar.kit.catalog.ObjectGroup
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.theme.Palette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A compass direction written around the horizon circle. */
internal class PlanisphereCardinal(val title: String, val azimuth: Double) {
    val isPrincipal: Boolean get() = title.length == 1

    companion object {
        val all = listOf(
            PlanisphereCardinal("N", 0.0), PlanisphereCardinal("NE", 45.0),
            PlanisphereCardinal("E", 90.0), PlanisphereCardinal("SE", 135.0),
            PlanisphereCardinal("S", 180.0), PlanisphereCardinal("SW", 225.0),
            PlanisphereCardinal("W", 270.0), PlanisphereCardinal("NW", 315.0),
        )
    }
}

/**
 * Draws the chart into a [DrawScope]. One instance per canvas: it owns the scratch arrays, paths and paints that keep a
 * frame free of per-object allocations, and the cache of laid-out labels.
 *
 * All sizes below are the iOS app's point sizes read as dp. Stars are drawn in batches that share a size and colour with
 * one native `drawPoints` call each; lines and polygons that are fixed in chart space are drawn under the viewport
 * transform instead of being copied and transformed per frame.
 */
internal class PlanisphereRenderer(private val text: PlanisphereTextCache) {
    // The frame being drawn: set by draw() so that the helpers do not need a long parameter list.
    private lateinit var scene: SkyChartScene
    private lateinit var paths: SkyChartPaths
    private lateinit var viewport: PlanisphereViewport
    private lateinit var palette: Palette
    private lateinit var layers: SkyChartLayerOptions
    private var dp = 1f
    private var boundsLeft = 0f
    private var boundsTop = 0f
    private var boundsRight = 0f
    private var boundsBottom = 0f

    // Scratch objects reused by every frame.
    private val disc = Path()
    private val ticks = Path()
    private val reticle = Path()
    private val sunRays = Path()
    private val moon = Path()
    private val rimArrow = Path()
    private val deepSkyMarkers = Path()
    private val deepSkyNative = deepSkyMarkers.asAndroidPath()
    private val occupied = RectList()
    private var dots = FloatArray(0)
    private val dotPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
    }
    private var effectDensity = 0f
    private var gridDash: PathEffect? = null
    private var selectionDash: PathEffect? = null

    /**
     * Draws the whole chart. The compass letters and ticks go first, then the sky inside the horizon circle (clipped to
     * it) with the recorded skyline and the panels of a running mosaic over it, then the horizon ring and the labels.
     *
     * @param mosaicStep The place in the shooting order of the mosaic panel being shot; negative to draw no panels.
     */
    fun draw(
        scope: DrawScope,
        content: SkyChartContent,
        viewport: PlanisphereViewport,
        palette: Palette,
        layers: SkyChartLayerOptions,
        selectedDirection: Vector3?,
        mosaicStep: Int,
    ) {
        scene = content.scene
        paths = content.paths
        this.viewport = viewport
        this.palette = palette
        this.layers = layers
        dp = viewport.layout.density
        val margin = BOUNDS_MARGIN * dp
        boundsLeft = -margin
        boundsTop = -margin
        boundsRight = viewport.layout.width + margin
        boundsBottom = viewport.layout.height + margin
        if (effectDensity != dp) {
            effectDensity = dp
            gridDash = PathEffect.dashPathEffect(floatArrayOf(2f * dp, 4f * dp))
            selectionDash = PathEffect.dashPathEffect(floatArrayOf(4f * dp, 3f * dp))
        }
        with(scope) { drawChart(selectedDirection, mosaicStep) }
    }

    private fun DrawScope.drawChart(selectedDirection: Vector3?, mosaicStep: Int) {
        val center = Offset(viewport.centerX, viewport.centerY)
        disc.rewind()
        disc.addOval(Rect(center, viewport.radius))
        drawCircle(palette.surface, viewport.radius, center)
        occupied.clear()
        drawRim()

        clipPath(disc) {
            if (layers.milkyWay) drawMilkyWay()
            if (layers.altitudeGrid) drawGrid()
            if (layers.constellationLines) drawConstellationLines()
            drawStars()
            if (layers.deepSkyObjects) drawDeepSky()
            drawBodies()
            drawSkyline()
            drawMosaic(mosaicStep)
            drawTarget()
            drawSelection(selectedDirection)
        }

        drawCircle(palette.horizonLine, viewport.radius, center, style = Stroke(1.5f * dp))
        drawTargetRimIndicator()
        drawAltitudeLabels()
        drawLabels()
    }

    /** Applies the viewport's transform: chart space (zenith at the origin, horizon radius 1) to the screen. */
    private fun DrawTransform.applyViewport() {
        translate(viewport.centerX, viewport.centerY)
        rotate(Math.toDegrees(viewport.rotation).toFloat(), Offset.Zero)
        scale(viewport.radius, viewport.radius, Offset.Zero)
    }

    private fun isVisible(x: Float, y: Float): Boolean =
        x >= boundsLeft && x < boundsRight && y >= boundsTop && y < boundsBottom

    // Sky

    private fun DrawScope.drawMilkyWay() {
        withTransform({ applyViewport() }) {
            for (path in paths.milkyWay) drawPath(path, palette.milkyWay)
        }
    }

    private fun DrawScope.drawGrid() {
        val dashed = Stroke(0.75f * dp, pathEffect = gridDash)
        val center = Offset(viewport.centerX, viewport.centerY)
        for (altitude in ALTITUDE_CIRCLES) {
            val radius = viewport.radius * PlanisphereProjection.radiusOfAltitude(altitude).toFloat()
            drawCircle(palette.gridLine, radius, center, style = dashed)
        }
        for (azimuth in 0 until 360 step 90) {
            drawLine(palette.gridLine, center, viewport.rimPoint(azimuth.toDouble(), 0f), 0.75f * dp)
        }
    }

    private fun DrawScope.drawConstellationLines() {
        withTransform({ applyViewport() }) {
            drawPath(paths.constellationLines, palette.constellationLine, style = Stroke(1f * dp / viewport.radius))
        }
    }

    /** Stars are drawn in batches of equal size and colour; the brightest also get a soft halo. */
    private fun DrawScope.drawStars() {
        val canvas = drawContext.canvas.nativeCanvas
        for (group in scene.starGroups) {
            val source = group.points
            if (dots.size < source.size) dots = FloatArray(source.size)
            var count = 0
            var index = 0
            while (index < source.size) {
                val x = viewport.screenX(source[index], source[index + 1])
                val y = viewport.screenY(source[index], source[index + 1])
                if (isVisible(x, y)) {
                    dots[count] = x
                    dots[count + 1] = y
                    count += 2
                }
                index += 2
            }
            if (count == 0) continue
            val diameterDp = SkyChartStarStyle.diameters[group.sizeClass]
            val diameter = diameterDp * dp
            val color = palette.starColor(SkyChartStarStyle.colorIndices[group.colorClass])
            if (diameterDp >= HALO_DIAMETER) {
                dotPaint.strokeCap = android.graphics.Paint.Cap.ROUND
                dotPaint.strokeWidth = diameter * HALO_SCALE
                dotPaint.color = color.copy(alpha = color.alpha * HALO_OPACITY).toArgb()
                canvas.drawPoints(dots, 0, count, dotPaint)
            }
            dotPaint.strokeCap =
                if (diameterDp < SQUARE_DOT_LIMIT) android.graphics.Paint.Cap.SQUARE else android.graphics.Paint.Cap.ROUND
            dotPaint.strokeWidth = diameter
            dotPaint.color = color.copy(alpha = color.alpha * SkyChartStarStyle.opacities[group.sizeClass]).toArgb()
            canvas.drawPoints(dots, 0, count, dotPaint)
        }
    }

    /**
     * Galaxies are drawn as ellipses, nebulae as rounded squares and clusters as circles. The less prominent objects
     * appear as the chart is zoomed in: the scene holds everything the deep-sky detail setting allows, and zoomed out
     * the obscure ones are left out.
     */
    private fun DrawScope.drawDeepSky() {
        val prominenceLimit = chartDeepSkyProminenceLimit(viewport.zoom)
        deepSkyMarkers.rewind()
        val direction = android.graphics.Path.Direction.CW
        val list = scene.deepSky
        for (index in list.indices) {
            val marker = list[index]
            if (marker.prominence > prominenceLimit) continue
            val x = viewport.screenX(marker.x, marker.y)
            val y = viewport.screenY(marker.x, marker.y)
            if (!isVisible(x, y)) continue
            val extent = ((marker.extent ?: 0.0) * viewport.pixelsPerRadian(marker.x, marker.y)).toFloat()
            val size = min(max(extent, 5f * dp), 48f * dp)
            when (marker.group) {
                ObjectGroup.GALAXIES ->
                    deepSkyNative.addOval(x - size / 2, y - size * 0.3f, x + size / 2, y + size * 0.3f, direction)
                ObjectGroup.NEBULAE -> deepSkyNative.addRoundRect(
                    x - size / 2, y - size / 2, x + size / 2, y + size / 2, 1.5f * dp, 1.5f * dp, direction,
                )
                else -> deepSkyNative.addOval(x - size / 2, y - size / 2, x + size / 2, y + size / 2, direction)
            }
        }
        drawPath(deepSkyMarkers, palette.deepSkyMarker, style = Stroke(1f * dp))
    }

    // Recorded skyline

    /** The ground below the recorded skyline, darker than the sky, with the skyline as a line. Nothing for an open horizon. */
    private fun DrawScope.drawSkyline() {
        val ground = paths.skylineGround ?: return
        val line = paths.skylineLine ?: return
        withTransform({ applyViewport() }) {
            drawPath(ground, palette.background.copy(alpha = GROUND_OPACITY))
            drawPath(line, palette.horizonLine, style = Stroke(1.5f * dp / viewport.radius, join = StrokeJoin.Round))
        }
    }

    // Mosaic

    /**
     * The panels of the mosaic being shot: those still to shoot faint, shot ones dimmed, the next one a little stronger
     * and the current one thick and tinted, over the faint outline of the requested region; their numbers are written
     * by [drawMosaicNumbers].
     */
    private fun DrawScope.drawMosaic(step: Int) {
        val mosaic = scene.mosaic ?: return
        if (step < 0) return
        val outlines = paths.mosaicPanels
        val unit = dp / viewport.radius
        withTransform({ applyViewport() }) {
            paths.mosaicRegion?.let {
                drawPath(it, palette.secondaryText.copy(alpha = 0.35f), style = Stroke(1f * unit, join = StrokeJoin.Round))
            }
            strokePanels(mosaic, outlines, step, MOSAIC_PENDING, palette.dslrFrame.copy(alpha = 0.5f), 1f * unit)
            strokePanels(mosaic, outlines, step, MOSAIC_DONE, palette.positive.copy(alpha = 0.4f), 1f * unit)
            strokePanels(mosaic, outlines, step, MOSAIC_NEXT, palette.guidance.copy(alpha = 0.8f), 2f * unit)
            strokePanels(mosaic, outlines, step, MOSAIC_CURRENT, palette.guidance, 3f * unit)
            for (index in outlines.indices) {
                if (mosaic.positions[index] == step) {
                    drawPath(outlines[index], palette.guidance.copy(alpha = 0.14f))
                }
            }
        }
        drawMosaicNumbers(mosaic, step)
    }

    private fun DrawScope.strokePanels(
        mosaic: SkyChartMosaic, outlines: List<Path>, step: Int, kind: Int, color: Color, width: Float,
    ) {
        val stroke = Stroke(width, join = StrokeJoin.Round)
        for (index in outlines.indices) {
            val position = mosaic.positions[index]
            val panelKind = when {
                position < step -> MOSAIC_DONE
                position == step -> MOSAIC_CURRENT
                position == step + 1 -> MOSAIC_NEXT
                else -> MOSAIC_PENDING
            }
            if (panelKind == kind) drawPath(outlines[index], color, style = stroke)
        }
    }

    /**
     * Writes the number of the current panel at its centre, and the numbers of the others while there are few of them
     * or the chart is zoomed in; a number that would overlap another text is left out. The current panel goes first.
     */
    private fun DrawScope.drawMosaicNumbers(mosaic: SkyChartMosaic, step: Int) {
        val showsAll = mosaic.panelCount <= MOSAIC_NUMBERS_FEW || viewport.zoom >= MOSAIC_NUMBERS_ZOOM
        val others = palette.dslrFrame.copy(alpha = 0.85f)
        for (pass in 0..1) {
            for (index in 0 until mosaic.panelCount) {
                val isCurrent = mosaic.positions[index] == step
                if (isCurrent != (pass == 0) || !(isCurrent || showsAll)) continue
                val chartX = mosaic.centers[2 * index]
                if (chartX.isNaN()) continue
                val chartY = mosaic.centers[2 * index + 1]
                val x = viewport.screenX(chartX, chartY)
                val y = viewport.screenY(chartX, chartY)
                if (!isVisible(x, y)) continue
                val layout = text.layout(PlanisphereTextRole.MOSAIC, mosaic.numbers[index])
                val width = layout.size.width.toFloat()
                val height = layout.size.height.toFloat()
                val left = x - width / 2
                val top = y - height / 2
                if (!isCurrent && occupied.intersects(left, top, left + width, top + height)) continue
                drawText(layout, if (isCurrent) palette.guidance else others, Offset(left, top))
                occupied.add(left, top, left + width, top + height)
            }
        }
    }

    // Solar system

    /** Draws the Sun, Moon and planets, and reserves the screen rectangles they occupy. */
    private fun DrawScope.drawBodies() {
        val list = scene.bodies
        for (index in list.indices) {
            val body = list[index]
            if (!body.isShown) continue
            val x = viewport.screenX(body.x, body.y)
            val y = viewport.screenY(body.x, body.y)
            if (!isVisible(x, y)) continue
            when (body.kind) {
                SkyChartBodyMarker.Kind.SUN -> drawSun(x, y)
                SkyChartBodyMarker.Kind.MOON -> drawMoon(body, x, y)
                SkyChartBodyMarker.Kind.PLANET -> drawPlanet(body, x, y)
            }
            val reserved = 8f * dp
            occupied.add(x - reserved, y - reserved, x + reserved, y + reserved)
        }
    }

    private fun DrawScope.drawSun(x: Float, y: Float) {
        sunRays.rewind()
        for (step in 0 until 8) {
            val angle = step * PI / 4
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            sunRays.moveTo(x + dx * 8.5f * dp, y + dy * 8.5f * dp)
            sunRays.lineTo(x + dx * 12f * dp, y + dy * 12f * dp)
        }
        drawPath(sunRays, palette.accent, style = Stroke(1.5f * dp))
        drawCircle(palette.accent, 6f * dp, Offset(x, y))
    }

    /** The Moon as a disc with its lit part, turned towards the Sun. */
    private fun DrawScope.drawMoon(body: SkyChartBodyMarker, x: Float, y: Float) {
        val radius = 7.5f * dp
        val center = Offset(x, y)
        drawCircle(palette.surfaceRaised, radius, center)
        moon.rewind()
        SkyChartMoonShape.addLitOutline(
            moon, x, y, radius, body.illuminatedFraction ?: 0.5, body.towardsSunAngle + viewport.rotation,
        )
        drawPath(moon, palette.planetMarker)
        val rim = palette.planetMarker.copy(alpha = palette.planetMarker.alpha * 0.7f)
        drawCircle(rim, radius, center, style = Stroke(1f * dp))
    }

    private fun DrawScope.drawPlanet(body: SkyChartBodyMarker, x: Float, y: Float) {
        val radius = min(max(3.4 - 0.5 * (body.magnitude ?: 1.0), 2.5), 5.0).toFloat() * dp
        drawCircle(palette.planetMarker, radius, Offset(x, y))
    }

    // Target and selection

    /** Draws the reticle on the target when it is above the horizon, and reserves the rectangle it occupies. */
    private fun DrawScope.drawTarget() {
        val target = scene.target ?: return
        if (!target.isAboveHorizon) return
        val point = PlanisphereProjection.chartPoint(target.direction)
        val x = viewport.screenX(point.x.toFloat(), point.y.toFloat())
        val y = viewport.screenY(point.x.toFloat(), point.y.toFloat())
        if (!isVisible(x, y)) return
        val radius = 11f * dp
        val tick = 6f * dp
        reticle.rewind()
        PlanisphereShapes.circle(reticle, x, y, radius)
        PlanisphereShapes.axisTicks(reticle, x, y, radius, radius + tick)
        drawPath(reticle, palette.guidance, style = Stroke(2f * dp))
        occupied.add(x - radius - tick, y - radius - tick, x + radius + tick, y + radius + tick)
    }

    /** A target below the horizon is shown as an arrow on the horizon circle, towards its azimuth. */
    private fun DrawScope.drawTargetRimIndicator() {
        val target = scene.target ?: return
        if (target.isAboveHorizon) return
        rimArrow.rewind()
        viewport.addRimArrow(rimArrow, target.horizontal.azimuth)
        drawPath(rimArrow, palette.guidance)
    }

    private fun DrawScope.drawSelection(direction: Vector3?) {
        if (direction == null || direction.z < 0) return
        val point = PlanisphereProjection.chartPoint(direction)
        val center = viewport.screen(point)
        if (!isVisible(center.x, center.y)) return
        drawCircle(palette.primaryText, 15f * dp, center, style = Stroke(1.5f * dp, pathEffect = selectionDash))
    }

    // Rim and labels

    /** Draws the tick marks and compass letters around the horizon circle, and reserves the letters' rectangles. */
    private fun DrawScope.drawRim() {
        ticks.rewind()
        for (azimuth in 0 until 360 step 10) {
            val length = if (azimuth % 30 == 0) 7f else 4f
            val start = viewport.rimPoint(azimuth.toDouble(), 0f)
            val end = viewport.rimPoint(azimuth.toDouble(), length * dp)
            ticks.moveTo(start.x, start.y)
            ticks.lineTo(end.x, end.y)
        }
        drawPath(ticks, palette.horizonLine, style = Stroke(1f * dp))

        for (cardinal in PlanisphereCardinal.all) {
            val center = viewport.rimPoint(cardinal.azimuth, (if (cardinal.isPrincipal) 17f else 15f) * dp)
            if (!isVisible(center.x, center.y)) continue
            val role = if (cardinal.isPrincipal) PlanisphereTextRole.CARDINAL else PlanisphereTextRole.CARDINAL_MINOR
            val color = if (cardinal.title == "N") palette.accent else palette.primaryText
            val layout = text.layout(role, cardinal.title)
            val left = center.x - layout.size.width / 2f
            val top = center.y - layout.size.height / 2f
            drawText(layout, color, Offset(left, top))
            occupied.add(left, top, left + layout.size.width, top + layout.size.height)
        }
    }

    /** Writes the altitude of each circle of the grid next to it, towards south-south-east. */
    private fun DrawScope.drawAltitudeLabels() {
        if (!layers.altitudeGrid) return
        val direction = PlanisphereProjection.unitVectorTowardsAzimuth(157.5)
        for (index in ALTITUDE_CIRCLES.indices) {
            val radius = PlanisphereProjection.radiusOfAltitude(ALTITUDE_CIRCLES[index])
            val center = viewport.screen((direction.x * radius).toFloat(), (direction.y * radius).toFloat())
            if (!isVisible(center.x, center.y)) continue
            drawCentered(text.layout(PlanisphereTextRole.ALTITUDE, ALTITUDE_TEXTS[index]), palette.tertiaryText, center)
        }
    }

    private fun DrawScope.drawCentered(layout: TextLayoutResult, color: Color, center: Offset) {
        drawText(layout, color, Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
    }

    /**
     * Places the text labels in priority order, skipping any that would overlap one already placed or run off the
     * screen. A label that does not fit beside its point tries the other side of it, then above and below.
     */
    private fun DrawScope.drawLabels() {
        val screenLeft = 2f * dp
        val screenTop = 2f * dp
        val screenRight = viewport.layout.width - 2f * dp
        val screenBottom = viewport.layout.height - 2f * dp
        val paddingX = 3f * dp
        val paddingY = 1.5f * dp
        var placed = 0
        val labels = scene.labels
        for (index in labels.indices) {
            val label = labels[index]
            if (label.minimumZoom > viewport.zoom || !isEnabled(label.style)) continue
            val anchorX = viewport.screenX(label.x, label.y)
            val anchorY = viewport.screenY(label.x, label.y)
            if (!isVisible(anchorX, anchorY) ||
                hypot(anchorX - viewport.centerX, anchorY - viewport.centerY) >= viewport.radius
            ) {
                continue
            }
            val layout = text.layout(label.style.role, label.text)
            val width = layout.size.width.toFloat()
            val height = layout.size.height.toFloat()
            val gap = label.offset * dp
            val top = anchorY - height / 2
            // Centred on its point when it has no offset, otherwise to the right of the point, then left, above, below.
            val candidates = if (gap > 0f) 4 else 1
            var originX = 0f
            var originY = 0f
            var isPlaced = false
            for (candidate in 0 until candidates) {
                val x: Float
                val y: Float
                if (gap <= 0f) {
                    x = anchorX - width / 2
                    y = top
                } else {
                    when (candidate) {
                        0 -> { x = anchorX + gap; y = top }
                        1 -> { x = anchorX - gap - width; y = top }
                        2 -> { x = anchorX - width / 2; y = anchorY - gap - height / 2 }
                        else -> { x = anchorX - width / 2; y = anchorY + gap - height / 2 }
                    }
                }
                if (x >= screenLeft && y >= screenTop && x + width <= screenRight && y + height <= screenBottom &&
                    !occupied.intersects(x - paddingX, y - paddingY, x + width + paddingX, y + height + paddingY)
                ) {
                    originX = x
                    originY = y
                    isPlaced = true
                    break
                }
            }
            if (!isPlaced) continue
            occupied.add(originX, originY, originX + width, originY + height)
            drawText(layout, label.style.color(palette), Offset(originX, originY))
            placed += 1
            if (placed >= LABEL_LIMIT) break
        }
    }

    private fun isEnabled(style: SkyChartLabel.Style): Boolean = when (style) {
        SkyChartLabel.Style.CONSTELLATION -> layers.constellationNames
        SkyChartLabel.Style.STAR -> layers.starNames
        SkyChartLabel.Style.DEEP_SKY -> layers.deepSkyObjects
        SkyChartLabel.Style.BODY, SkyChartLabel.Style.TARGET -> true
    }

    /** Axis-aligned screen rectangles (left, top, right, bottom) that labels must keep clear of. */
    private class RectList {
        private var values = FloatArray(4 * INITIAL_CAPACITY)
        private var count = 0

        fun clear() {
            count = 0
        }

        fun add(left: Float, top: Float, right: Float, bottom: Float) {
            if (count * 4 + 4 > values.size) values = values.copyOf(values.size * 2)
            values[count * 4] = left
            values[count * 4 + 1] = top
            values[count * 4 + 2] = right
            values[count * 4 + 3] = bottom
            count += 1
        }

        /** Whether any rectangle overlaps the given one (touching edges do not count). */
        fun intersects(left: Float, top: Float, right: Float, bottom: Float): Boolean {
            for (index in 0 until count) {
                val offset = index * 4
                val overlapsHorizontally = values[offset] < right && left < values[offset + 2]
                val overlapsVertically = values[offset + 1] < bottom && top < values[offset + 3]
                if (overlapsHorizontally && overlapsVertically) return true
            }
            return false
        }

        private companion object {
            const val INITIAL_CAPACITY = 128
        }
    }

    private companion object {
        /** Altitudes (degrees) of the circles drawn inside the horizon circle. */
        val ALTITUDE_CIRCLES = doubleArrayOf(30.0, 60.0)
        val ALTITUDE_TEXTS = arrayOf("30°", "60°")

        /** The most text labels placed in one frame. */
        const val LABEL_LIMIT = 90

        /** The screen, grown a little (dp) so that markers half outside it are still drawn. */
        const val BOUNDS_MARGIN = 12f

        /** Stars with at least this dot diameter (dp) get a halo, drawn [HALO_SCALE] times as wide at [HALO_OPACITY]. */
        const val HALO_DIAMETER = 4f
        const val HALO_SCALE = 2.2f
        const val HALO_OPACITY = 0.16f

        /** Dots smaller than this diameter (dp) are squares. */
        const val SQUARE_DOT_LIMIT = 2f

        /** The opacity of the ground below the recorded skyline: stars, lines and objects behind it nearly vanish. */
        const val GROUND_OPACITY = 0.85f

        /** The roles panels play in the shooting order, for the strokes of the mosaic. */
        const val MOSAIC_PENDING = 0
        const val MOSAIC_DONE = 1
        const val MOSAIC_NEXT = 2
        const val MOSAIC_CURRENT = 3

        /** The numbers of all panels are written when there are at most this many, or from this zoom on. */
        const val MOSAIC_NUMBERS_FEW = 30
        const val MOSAIC_NUMBERS_ZOOM = 2f
    }
}

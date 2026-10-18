package net.kuecker.lodestar.features.skychart

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType

/** Which layers of the chart are drawn. */
internal data class SkyChartLayerOptions(
    val constellationLines: Boolean = true,
    val constellationNames: Boolean = true,
    val milkyWay: Boolean = true,
    val deepSkyObjects: Boolean = true,
    val starNames: Boolean = true,
    val altitudeGrid: Boolean = true,
    val cameraView: Boolean = true,
)

/** Builders for the simple shapes the chart draws, added to a [Path] that the caller owns and reuses. */
internal object PlanisphereShapes {
    /** Adds the circle of [radius] around ([centerX], [centerY]). */
    fun circle(path: Path, centerX: Float, centerY: Float, radius: Float) {
        path.addOval(Rect(centerX - radius, centerY - radius, centerX + radius, centerY + radius))
    }

    /** Adds the polyline through [points] (x, y pairs) as one subpath that is closed when [closed] is set. */
    fun polyline(path: Path, points: FloatArray, closed: Boolean = false) {
        if (points.size < 2) return
        path.moveTo(points[0], points[1])
        for (index in 2 until points.size step 2) path.lineTo(points[index], points[index + 1])
        if (closed) path.close()
    }

    /** Adds the polyline through [points] (x, y pairs) as one subpath that is closed when [closed] is set. */
    fun polyline(path: Path, points: DoubleArray, closed: Boolean = false) {
        if (points.size < 2) return
        path.moveTo(points[0].toFloat(), points[1].toFloat())
        for (index in 2 until points.size step 2) path.lineTo(points[index].toFloat(), points[index + 1].toFloat())
        if (closed) path.close()
    }

    /**
     * Adds four short lines that point away from the centre along the screen axes, each running from [inner] to
     * [outer] pixels from it: the ticks of a reticle or crosshair.
     */
    fun axisTicks(path: Path, centerX: Float, centerY: Float, inner: Float, outer: Float) {
        path.moveTo(centerX + inner, centerY)
        path.lineTo(centerX + outer, centerY)
        path.moveTo(centerX - inner, centerY)
        path.lineTo(centerX - outer, centerY)
        path.moveTo(centerX, centerY + inner)
        path.lineTo(centerX, centerY + outer)
        path.moveTo(centerX, centerY - inner)
        path.lineTo(centerX, centerY - outer)
    }
}

/**
 * The line and polygon geometry of a scene as paths in chart space, built once per scene (off the main thread) so that
 * a frame only has to transform them onto the screen.
 */
internal class SkyChartPaths(scene: SkyChartScene) {
    /**
     * One path per Milky Way level, filled with the even–odd rule. A level that inverts its fill (see
     * [SkyChartMilkyWayLevel]) includes a square that covers the whole chart.
     */
    val milkyWay: List<Path> = scene.milkyWay.map { level ->
        Path().apply {
            fillType = PathFillType.EvenOdd
            for (ring in level.rings) PlanisphereShapes.polyline(this, ring, closed = true)
            if (level.invertsFill) addRect(Rect(-2f, -2f, 2f, 2f))
        }
    }

    val constellationLines: Path = Path().apply {
        for (polyline in scene.constellationLines) PlanisphereShapes.polyline(this, polyline)
    }

    /**
     * The ground below the recorded skyline: the band between the skyline and the rim of the chart, filled with the
     * even–odd rule (a ring that reaches a little past the horizon circle, minus the sky inside the skyline). `null`
     * for an open horizon.
     */
    val skylineGround: Path? = scene.skyline?.let { skyline ->
        Path().apply {
            fillType = PathFillType.EvenOdd
            addOval(Rect(-SKYLINE_GROUND_REACH, -SKYLINE_GROUND_REACH, SKYLINE_GROUND_REACH, SKYLINE_GROUND_REACH))
            PlanisphereShapes.polyline(this, skylinePoints(skyline), closed = true)
        }
    }

    /** The skyline itself as a closed line, or `null` for an open horizon. */
    val skylineLine: Path? = scene.skyline?.let { skyline ->
        Path().apply { PlanisphereShapes.polyline(this, skylinePoints(skyline), closed = true) }
    }

    /** The outline of each panel of the mosaic (by panel index); empty without a mosaic. */
    val mosaicPanels: List<Path> = scene.mosaic?.outlines?.map { polylines ->
        Path().apply { for (polyline in polylines) PlanisphereShapes.polyline(this, polyline) }
    }.orEmpty()

    /** The rectangle the mosaic was asked to cover, or `null` without a mosaic. */
    val mosaicRegion: Path? = scene.mosaic?.let { mosaic ->
        Path().apply { for (polyline in mosaic.region) PlanisphereShapes.polyline(this, polyline) }
    }

    private companion object {
        /** How far past the horizon circle (chart units) the ground reaches, so that no edge shows at the rim. */
        const val SKYLINE_GROUND_REACH = 1.05f

        /** The skyline as chart points (x, y pairs), azimuth 0° first; north is up and east to the left. */
        fun skylinePoints(skyline: SkyChartSkyline): DoubleArray {
            val points = DoubleArray(2 * skyline.radii.size)
            for (degree in skyline.radii.indices) {
                val azimuth = PlanisphereProjection.unitVectorTowardsAzimuth(degree.toDouble())
                points[2 * degree] = azimuth.x * skyline.radii[degree]
                points[2 * degree + 1] = azimuth.y * skyline.radii[degree]
            }
            return points
        }
    }
}

/** A scene with its cached paths. */
internal class SkyChartContent(val scene: SkyChartScene, val paths: SkyChartPaths)

/**
 * Adds a small arrow on the horizon circle that points outwards at [azimuth] to [path]: how the chart shows something
 * that is below the horizon.
 */
internal fun PlanisphereViewport.addRimArrow(path: Path, azimuth: Double) {
    val density = layout.density
    val tip = rimPoint(azimuth, 12f * density)
    val left = rimPoint(azimuth - 3.5, 2f * density)
    val right = rimPoint(azimuth + 3.5, 2f * density)
    path.moveTo(tip.x, tip.y)
    path.lineTo(left.x, left.y)
    path.lineTo(right.x, right.y)
    path.close()
}

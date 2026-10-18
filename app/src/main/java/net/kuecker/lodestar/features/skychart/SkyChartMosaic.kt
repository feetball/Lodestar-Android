package net.kuecker.lodestar.features.skychart

import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.mosaic.MosaicPlan
import net.kuecker.lodestar.kit.mosaic.MosaicPoint

/**
 * The panels of the mosaic being shot as they stand in the sky of the scene's instant, in chart space: the outlines (the
 * parts above the horizon) and the place of each panel's centre. Indexed like `plan.panels`; immutable.
 */
internal class SkyChartMosaic(
    /** The plan the panels belong to; the views compare it with the session's plan to see whether it is still current. */
    val plan: MosaicPlan,
    /** The outline of each panel as chart polylines (x, y pairs): more than one, or none, where it crosses the horizon. */
    val outlines: List<List<FloatArray>>,
    /** The chart position of each panel's centre as x, y pairs; NaN for a centre below the horizon. */
    val centers: FloatArray,
    /** The place of each panel in the shooting order. */
    val positions: IntArray,
    /** The numbers written on the panels: the place in the shooting order, counted from 1. */
    val numbers: Array<String>,
    /** The rectangle that was asked for, as chart polylines. */
    val region: List<FloatArray>,
) {
    val panelCount: Int get() = outlines.size
}

/** Places a plan's panels in the chart. Pure, so it runs on any thread. */
internal object SkyChartMosaicBuilder {
    /** Edge segments per side of a panel: its edges are great circles, which bend in the chart. */
    private const val SEGMENTS_PER_EDGE = 8

    /** Points per side of the requested rectangle. */
    private const val POINTS_PER_EDGE = 16

    fun build(plan: MosaicPlan, frame: SkyFrame): SkyChartMosaic {
        val panels = plan.panels
        val outlines = ArrayList<List<FloatArray>>(panels.size)
        val centers = FloatArray(2 * panels.size)
        for ((index, panel) in panels.withIndex()) {
            val vertices = panel.outline(SEGMENTS_PER_EDGE).map { direction(it, frame) }
            outlines += if (vertices.isEmpty()) emptyList() else PlanisphereProjection.visiblePolylines(vertices + vertices.first())
            val center = direction(panel.center, frame)
            if (center.z >= 0) {
                val point = PlanisphereProjection.chartPoint(center)
                centers[2 * index] = point.x.toFloat()
                centers[2 * index + 1] = point.y.toFloat()
            } else {
                centers[2 * index] = Float.NaN
                centers[2 * index + 1] = Float.NaN
            }
        }
        val region = ArrayList<FloatArray>()
        for (loop in plan.regionOutline(POINTS_PER_EDGE)) {
            val vertices = loop.map { direction(it, frame) }
            if (vertices.isNotEmpty()) region += PlanisphereProjection.visiblePolylines(vertices + vertices.first())
        }
        return SkyChartMosaic(
            plan = plan,
            outlines = outlines,
            centers = centers,
            positions = IntArray(panels.size) { panels[it].shootingPosition },
            numbers = Array(panels.size) { (panels[it].shootingPosition + 1).toString() },
            region = region,
        )
    }

    /** The apparent (refracted) East–North–Up direction of a plan's point at the instant of [frame]. */
    private fun direction(point: MosaicPoint, frame: SkyFrame): Vector3 = when (point) {
        is MosaicPoint.Equatorial -> frame.horizontal(point.coordinate, null, true).unitVector
        is MosaicPoint.Horizontal -> point.coordinate.unitVector
    }
}

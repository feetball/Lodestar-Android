package net.kuecker.lodestar.kit.mosaic

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.phototools.FrameSize
import net.kuecker.lodestar.kit.phototools.LensSetup
import kotlin.math.abs
import kotlin.math.min

/**
 * Plans the panels of a mosaic: how many frames cover a region of the sky, where each one points, and in
 * what order to shoot them.
 *
 * ## Geometry
 *
 * The rows of a mosaic follow circles of constant latitude about a pole: the celestial pole for an
 * equatorial plan, the zenith for a horizontal plan. Every frame is a rectilinear (gnomonic) view whose top
 * edge points to that pole, so its edges are great-circle arcs. The number of rows follows from the extent
 * across the rows. The number of columns is worked out for each row from its real width on the sphere: a
 * row near the pole is shorter than one at the equator, and a frame also covers less longitude on the
 * side of its band that lies nearer the equator, so each row is spaced for the narrowest longitude width
 * the frame has within the band of latitudes that row is responsible for.
 *
 * The **overlap** is the fraction of the frame that neighbouring panels share, measured along the row at the
 * row's narrowest latitude and across the rows on the central meridian: with 25 % overlap a 54° frame
 * advances 40.5°. Because the edges of a frame are great circles, not circles of latitude, a frame's top
 * and bottom edges bow away from the grid towards the sides, which leaves slivers uncovered at the region's
 * edges. The planner checks every plan on a lattice of 41 × 61 points of the region (including its edges and
 * corners) and, when a point is outside every frame, adds a little overlap and extends the grid beyond the
 * region by the same fraction of the frame, repeating up to 20 times. The extension is at most 0.4 of a
 * frame. If the region is still not covered the plan carries [MosaicWarning.OverlapNotMet].
 *
 * ## Cost
 *
 * A plan takes about a millisecond for a dozen panels and a few milliseconds for 100; a plan that needs the
 * largest number of attempts is a few times slower. Plan when the user changes an input, not per frame.
 */
object MosaicPlanner {
    /** The overlap used when the caller has no preference: 25 % of the frame. */
    const val defaultOverlap: Double = 0.25

    /** The overlaps the planner accepts: 10 % to 50 % of the frame. */
    val overlapRange: ClosedFloatingPointRange<Double> = 0.10..0.50

    /** Plans with more panels than this carry [MosaicWarning.TooManyPanels]. */
    const val recommendedPanelLimit: Int = 100

    /** Plans with more panels than this are refused with [MosaicError.TooManyPanels]. */
    const val maximumPanelCount: Int = 500

    /**
     * Plans a mosaic for a camera with the given [frame] size (landscape, as `LensSetup.fieldOfView` reports it).
     *
     * @param region The part of the sky to cover.
     * @param frame The angular size of one frame in landscape orientation.
     * @param orientation How the camera is held: landscape keeps the long side along the rows.
     * @param overlap The fraction of the frame that neighbouring panels share, in [overlapRange].
     * @param observerLatitude The observer's latitude in degrees, [−90, 90], used to find which panels of an
     * equatorial plan set first; null orders them by right ascension alone (the western end first).
     * @param reverseOrder Reverses the shooting order, to start from the other end.
     * @throws MosaicError for a region, frame, overlap or latitude that is not usable, and when the mosaic
     * needs more than [maximumPanelCount] panels.
     */
    @Throws(MosaicError::class)
    fun plan(
        region: MosaicRegion,
        frame: FrameSize,
        orientation: MosaicOrientation = MosaicOrientation.LANDSCAPE,
        overlap: Double = defaultOverlap,
        observerLatitude: Double? = null,
        reverseOrder: Boolean = false,
    ): MosaicPlan = plan(
        region, frame, orientation, overlap, observerLatitude, reverseOrder, MosaicGeometry.maximumAttempts,
    )

    /** [plan] with a limit on the attempts that widen the layout to close gaps; the tests use it to reach the warning. */
    internal fun plan(
        region: MosaicRegion,
        frame: FrameSize,
        orientation: MosaicOrientation,
        overlap: Double,
        observerLatitude: Double?,
        reverseOrder: Boolean,
        maximumAttempts: Int,
    ): MosaicPlan {
        val extent = validated(region)
        if (!(frame.horizontal.isFinite() && frame.vertical.isFinite())) throw MosaicError.NonFiniteInput
        if (!(frame.horizontal > 0.0 && frame.horizontal < 180.0 && frame.vertical > 0.0 && frame.vertical < 180.0)) {
            throw MosaicError.InvalidFrame(frame.horizontal, frame.vertical)
        }
        if (!overlapRange.contains(overlap)) throw MosaicError.InvalidOverlap(overlap)
        if (observerLatitude != null && !(observerLatitude >= -90.0 && observerLatitude <= 90.0)) {
            throw MosaicError.InvalidLatitude(observerLatitude)
        }

        val oriented = orientation.oriented(frame)
        val tanHalfWidth = AstroMath.tanDeg(oriented.horizontal / 2)
        val tanHalfHeight = AstroMath.tanDeg(oriented.vertical / 2)
        val samples = MosaicGeometry.samples(extent)

        var attempt = 0
        var layout = MosaicGeometry.layout(extent, tanHalfWidth, tanHalfHeight, oriented.vertical, overlap, 0.0)
        var axes = MosaicGeometry.axes(layout, extent.basis)
        while (MosaicGeometry.coveredFraction(samples, axes, tanHalfWidth, tanHalfHeight, true) < 1.0 &&
            attempt + 1 < maximumAttempts
        ) {
            attempt += 1
            val padding = attempt.toDouble() * MosaicGeometry.attemptStep
            val effective = min(overlap + padding, MosaicGeometry.maximumOverlap)
            val next = try {
                MosaicGeometry.layout(extent, tanHalfWidth, tanHalfHeight, oriented.vertical, effective, padding)
            } catch (error: MosaicError.TooManyPanels) {
                break
            }
            layout = next
            axes = MosaicGeometry.axes(next, extent.basis)
        }
        val covered = MosaicGeometry.coveredFraction(samples, axes, tanHalfWidth, tanHalfHeight, false)

        val columnsPerRow = layout.columnLongitudes.map { it.size }
        val order = shootingOrder(region, columnsPerRow, axes, observerLatitude, reverseOrder)
        val position = IntArray(axes.size)
        for ((place, index) in order.withIndex()) position[index] = place

        val panels = ArrayList<MosaicPanel>(axes.size)
        var index = 0
        for (row in columnsPerRow.indices) {
            for (column in 0 until columnsPerRow[row]) {
                val frameAxes = axes[index]
                val corners = listOf(
                    point(MosaicGeometry.corner(frameAxes, -1.0, 1.0, tanHalfWidth, tanHalfHeight), extent.kind),
                    point(MosaicGeometry.corner(frameAxes, 1.0, 1.0, tanHalfWidth, tanHalfHeight), extent.kind),
                    point(MosaicGeometry.corner(frameAxes, 1.0, -1.0, tanHalfWidth, tanHalfHeight), extent.kind),
                    point(MosaicGeometry.corner(frameAxes, -1.0, -1.0, tanHalfWidth, tanHalfHeight), extent.kind),
                )
                panels.add(
                    MosaicPanel(
                        index = index,
                        row = row,
                        column = column,
                        center = point(frameAxes.center, extent.kind),
                        corners = corners,
                        shootingPosition = position[index],
                    ),
                )
                index += 1
            }
        }

        val warnings = ArrayList<MosaicWarning>()
        if (panels.size > recommendedPanelLimit) warnings.add(MosaicWarning.TooManyPanels(panels.size))
        if (covered < 1.0) warnings.add(MosaicWarning.OverlapNotMet(covered))

        return MosaicPlan(
            region = region,
            kind = extent.kind,
            orientation = orientation,
            frame = oriented,
            overlap = overlap,
            achievedOverlap = achievedOverlap(layout, oriented.vertical),
            rows = columnsPerRow.size,
            columnsPerRow = columnsPerRow,
            panels = panels,
            shootingOrder = order,
            pole = point(extent.basis.pole, extent.kind),
            coverage = coverage(extent, layout, oriented, covered, panels.size, tanHalfWidth, tanHalfHeight),
            warnings = warnings,
        )
    }

    /**
     * Plans a mosaic for a lens and sensor, see the version with a frame size.
     *
     * @param setup The camera and lens; its [LensSetup.fieldOfView] is the landscape frame.
     */
    @Throws(MosaicError::class)
    fun plan(
        region: MosaicRegion,
        setup: LensSetup,
        orientation: MosaicOrientation = MosaicOrientation.LANDSCAPE,
        overlap: Double = defaultOverlap,
        observerLatitude: Double? = null,
        reverseOrder: Boolean = false,
    ): MosaicPlan = plan(region, setup.fieldOfView, orientation, overlap, observerLatitude, reverseOrder)

    /**
     * The orientation that covers [region] with fewer panels; landscape when both need the same number
     * or when neither can be planned within [maximumPanelCount].
     *
     * Plans the region twice.
     *
     * @throws MosaicError for a region, frame or overlap that is not usable.
     */
    @Throws(MosaicError::class)
    fun recommendedOrientation(
        region: MosaicRegion,
        frame: FrameSize,
        overlap: Double = defaultOverlap,
    ): MosaicOrientation {
        val landscape = panelCount(region, frame, MosaicOrientation.LANDSCAPE, overlap)
        val portrait = panelCount(region, frame, MosaicOrientation.PORTRAIT, overlap)
        return if (portrait < landscape) MosaicOrientation.PORTRAIT else MosaicOrientation.LANDSCAPE
    }

    // MARK: Validation

    private fun validated(region: MosaicRegion): MosaicExtent = when (region) {
        is MosaicRegion.Equatorial -> {
            val center = region.center
            if (!(center.rightAscension.isFinite() && center.declination.isFinite() && region.width.isFinite() &&
                    region.height.isFinite() && region.positionAngle.isFinite())
            ) {
                throw MosaicError.NonFiniteInput
            }
            if (!(region.width > 0.0 && region.width <= 360.0)) throw MosaicError.InvalidWidth(region.width)
            if (!(region.height > 0.0 && region.height <= 180.0)) throw MosaicError.InvalidHeight(region.height)
            MosaicGeometry.extent(center, region.width, region.height, region.positionAngle)
        }

        is MosaicRegion.Horizontal -> {
            if (!(region.azimuthStart.isFinite() && region.azimuthSpan.isFinite() &&
                    region.minimumAltitude.isFinite() && region.maximumAltitude.isFinite())
            ) {
                throw MosaicError.NonFiniteInput
            }
            if (!(region.azimuthSpan > 0.0 && region.azimuthSpan <= 360.0)) {
                throw MosaicError.InvalidAzimuthSpan(region.azimuthSpan)
            }
            if (!(region.minimumAltitude >= -90.0 && region.maximumAltitude <= 90.0 &&
                    region.minimumAltitude < region.maximumAltitude)
            ) {
                throw MosaicError.InvalidAltitudeRange(region.minimumAltitude, region.maximumAltitude)
            }
            MosaicGeometry.extent(region.azimuthStart, region.azimuthSpan, region.minimumAltitude, region.maximumAltitude)
        }
    }

    private fun panelCount(
        region: MosaicRegion,
        frame: FrameSize,
        orientation: MosaicOrientation,
        overlap: Double,
    ): Int = try {
        plan(region, frame, orientation, overlap).panelCount
    } catch (error: MosaicError.TooManyPanels) {
        Int.MAX_VALUE
    }

    // MARK: Assembly

    private fun point(vector: Vector3, kind: MosaicFrameKind): MosaicPoint = MosaicGeometry.point(vector, kind)

    private fun achievedOverlap(layout: MosaicLayout, frameHeight: Double): Double? {
        var result: Double? = null
        for (row in layout.rowLatitudes.indices) {
            if (layout.columnLongitudes[row].size > 1) {
                val overlap = 1 - layout.columnSpacings[row] / layout.rowWidths[row]
                result = if (result == null) overlap else min(result, overlap)
            }
        }
        if (layout.rowLatitudes.size > 1) {
            val overlap = 1 - layout.rowStep / frameHeight
            result = if (result == null) overlap else min(result, overlap)
        }
        return result
    }

    private fun coverage(
        extent: MosaicExtent,
        layout: MosaicLayout,
        frame: FrameSize,
        coveredFraction: Double,
        panelCount: Int,
        tanHalfWidth: Double,
        tanHalfHeight: Double,
    ): MosaicCoverage {
        val rows = layout.rowLatitudes.size
        val middle = (extent.minimumLatitude + extent.maximumLatitude) / 2
        var central = 0
        for (row in 1 until rows) {
            if (abs(layout.rowLatitudes[row] - middle) < abs(layout.rowLatitudes[central] - middle)) central = row
        }
        val columns = layout.columnLongitudes[central]
        val span = if (extent.isRing) {
            360.0
        } else {
            min(360.0, (columns.first() - columns.last()) + layout.rowWidths[central])
        }
        return MosaicCoverage(
            widthDegrees = span * AstroMath.cosDeg(layout.rowLatitudes[central]),
            heightDegrees = (layout.rowLatitudes.first() - layout.rowLatitudes.last()) + frame.vertical,
            longitudeSpanDegrees = span,
            regionAreaSquareDegrees = extent.areaSquareDegrees,
            panelAreaSquareDegrees = panelCount.toDouble() * MosaicGeometry.frameAreaSquareDegrees(tanHalfWidth, tanHalfHeight),
            coveredFraction = coveredFraction,
        )
    }

    // MARK: Shooting order

    /**
     * The panel indices in shooting order: a serpentine through the rows. Horizontal plans start at the left
     * end of the bottom row. Equatorial plans start at the corner panel that sets first and move on to the
     * neighbouring row at the end of each row.
     */
    private fun shootingOrder(
        region: MosaicRegion,
        columnsPerRow: List<Int>,
        axes: List<MosaicAxes>,
        observerLatitude: Double?,
        reverseOrder: Boolean,
    ): List<Int> {
        val rows = columnsPerRow.size
        val offsets = IntArray(rows)
        for (row in 1 until rows) offsets[row] = offsets[row - 1] + columnsPerRow[row - 1]
        var startAtTop = false
        var startAtLeft = true
        if (region is MosaicRegion.Equatorial) {
            val candidates = listOf(
                Triple(true, true, offsets[0]),
                Triple(true, false, offsets[0] + columnsPerRow[0] - 1),
                Triple(false, true, offsets[rows - 1]),
                Triple(false, false, offsets[rows - 1] + columnsPerRow[rows - 1] - 1),
            )
            var best = Double.POSITIVE_INFINITY
            for ((top, left, index) in candidates) {
                val key = settingKey(axes[index].center, region.center.rightAscension, observerLatitude)
                if (key < best) {
                    best = key
                    startAtTop = top
                    startAtLeft = left
                }
            }
        }
        val result = ArrayList<Int>(axes.size)
        for (step in 0 until rows) {
            val row = if (startAtTop) step else rows - 1 - step
            val leftToRight = startAtLeft == (step % 2 == 0)
            val count = columnsPerRow[row]
            for (k in 0 until count) {
                result.add(offsets[row] + if (leftToRight) k else count - 1 - k)
            }
        }
        return if (reverseOrder) result.reversed() else result
    }

    /**
     * A number that is smaller for a panel that sets earlier: its right ascension relative to the region's
     * centre, plus the hour angle at which a star of its declination sets for the observer. Without a latitude
     * the stars are assumed to set at an hour angle of 90°, as on the equator.
     */
    private fun settingKey(center: Vector3, centerRightAscension: Double, observerLatitude: Double?): Double {
        val rightAscension = AstroMath.atan2Deg(center.y, center.x)
        val declination = AstroMath.asinDeg(center.z)
        return AstroMath.normalizeSignedDegrees(rightAscension - centerRightAscension) +
            settingHourAngle(declination, observerLatitude)
    }

    /** The hour angle, [0, 180], at which a star of [declination] sets: 180 if it never does, 0 if it never rises. */
    internal fun settingHourAngle(declination: Double, observerLatitude: Double?): Double {
        if (observerLatitude == null) return 90.0
        val numerator = AstroMath.sinDeg(observerLatitude) * AstroMath.sinDeg(declination)
        val denominator = AstroMath.cosDeg(observerLatitude) * AstroMath.cosDeg(declination)
        if (!(denominator > 1e-12)) {
            return if (numerator > 0) 180.0 else if (numerator < 0) 0.0 else 90.0
        }
        return AstroMath.acosDeg(AstroMath.clamp(-numerator / denominator, -1.0, 1.0))
    }
}

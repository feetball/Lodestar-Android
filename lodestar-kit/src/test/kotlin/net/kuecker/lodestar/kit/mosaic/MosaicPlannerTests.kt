package net.kuecker.lodestar.kit.mosaic

import net.kuecker.lodestar.kit.catalog.AngularSize
import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Matrix3
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.phototools.FieldOfView
import net.kuecker.lodestar.kit.phototools.FrameSize
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.kit.phototools.SensorFormat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Helpers shared by the mosaic tests. They are written independently of the planner: panel membership is
 * tested with the panel's corners (great-circle edges), and region points are generated from the region's
 * definition, not from the planner's grid.
 */
internal object MosaicTestSupport {
    /** A full-frame camera with a lens of the given focal length: the landscape frame. */
    fun frame(focalLength: Double): FrameSize = FieldOfView.of(SensorFormat.fullFrame, focalLength)

    /** The spherical quadrilateral of a panel's corners, as the normals of its four edges. */
    class Quad(panel: MosaicPanel) {
        private val normals: List<Vector3>
        private val signs: List<Double>

        init {
            val corners = panel.corners.map { it.unitVector }
            val centroid = (corners[0] + corners[1] + corners[2] + corners[3]).normalized
            normals = (0 until 4).map { corners[it].cross(corners[(it + 1) % 4]) }
            signs = normals.map { if (it.dot(centroid) >= 0) 1.0 else -1.0 }
        }

        /** Whether [point] lies on the inner side of all four edges (great-circle arcs between the corners). */
        fun contains(point: Vector3): Boolean {
            for (index in 0 until 4) {
                if (normals[index].dot(point) * signs[index] < -1e-9) return false
            }
            return true
        }
    }

    /** Whether [point] lies inside the spherical quadrilateral of [panel]'s corners. */
    fun contains(panel: MosaicPanel, point: Vector3): Boolean = Quad(panel).contains(point)

    /** The region's points on a lattice of [rows] by [columns], edges included. */
    fun points(region: MosaicRegion, rows: Int, columns: Int): List<Vector3> {
        val result = ArrayList<Vector3>()
        when (region) {
            is MosaicRegion.Horizontal -> {
                for (row in 0 until rows) {
                    val altitude = region.minimumAltitude +
                        (region.maximumAltitude - region.minimumAltitude) * row.toDouble() / (rows - 1).toDouble()
                    for (column in 0 until columns) {
                        val fraction = if (region.azimuthSpan >= 360.0) {
                            column.toDouble() / columns.toDouble()
                        } else {
                            column.toDouble() / (columns - 1).toDouble()
                        }
                        result.add(HorizontalCoordinate(region.azimuthStart + region.azimuthSpan * fraction, altitude).unitVector)
                    }
                }
            }

            is MosaicRegion.Equatorial -> {
                val center = region.center
                val centerVector = center.unitVector
                val rotation = Matrix3.rotation(centerVector, -AstroMath.radians(region.positionAngle))
                val span = if (cos(AstroMath.radians(center.declination)) > 1e-9) {
                    kotlin.math.min(360.0, region.width / cos(AstroMath.radians(center.declination)))
                } else {
                    360.0
                }
                for (row in 0 until rows) {
                    val declination = AstroMath.clamp(
                        center.declination + region.height * (row.toDouble() / (rows - 1).toDouble() - 0.5),
                        -90.0,
                        90.0,
                    )
                    for (column in 0 until columns) {
                        val fraction = if (span >= 360.0) column.toDouble() / columns.toDouble() else column.toDouble() / (columns - 1).toDouble()
                        val place = EquatorialCoordinate(center.rightAscension + span * (fraction - 0.5), declination)
                        result.add(rotation * place.unitVector)
                    }
                }
            }
        }
        return result
    }

    /** The number of [points] that no panel of [plan] contains. */
    fun uncovered(plan: MosaicPlan, points: List<Vector3>): Int {
        val quads = plan.panels.map { Quad(it) }
        return points.count { point -> quads.none { it.contains(point) } }
    }
}

class MosaicPlannerTests {
    private val lens = MosaicTestSupport::frame
    private val sky = EquatorialCoordinate.fromRightAscensionHours(5.0, 0.0)

    private fun equatorial(declination: Double, width: Double, height: Double, positionAngle: Double = 0.0) =
        MosaicRegion.Equatorial(EquatorialCoordinate.fromRightAscensionHours(5.0, declination), width, height, positionAngle)

    // MARK: Known counts

    @Test
    fun testSmallRegionNeedsOnePanel() {
        val plan = MosaicPlanner.plan(MosaicRegion.Equatorial(sky, 20.0, 15.0), lens(50.0))
        assertEquals(1, plan.rows)
        assertEquals(listOf(1), plan.columnsPerRow)
        assertEquals(1, plan.panelCount)
        assertEquals("1 × 1", plan.gridDescription)
        assertNull(plan.achievedOverlap)
        assertTrue(plan.warnings.isEmpty())
        assertEquals(1.0, plan.coverage.coveredFraction)
        val panel = plan.panels[0]
        assertEquals("R1C1", panel.id)
        assertEquals(0, panel.shootingPosition)
        assertEquals(listOf(0), plan.shootingOrder)
        assertEquals(0.0, panel.center.equatorialCoordinate!!.angularSeparation(sky), 1e-9)
        assertEquals(MosaicFrameKind.EQUATORIAL, plan.kind)
    }

    @Test
    fun testTwoColumnsAtTheEquatorAreSpacedByTheOverlap() {
        // At declination 0 a frame covers its own width everywhere in the band, so the spacing is 75 % of it.
        val frame = lens(50.0)
        val plan = MosaicPlanner.plan(equatorial(0.0, 40.0, 10.0), frame)
        assertEquals(listOf(2), plan.columnsPerRow)
        assertEquals("2 × 1", plan.gridDescription)
        val half = 0.375 * frame.horizontal
        val left = plan.panels[0].center.equatorialCoordinate!!
        val right = plan.panels[1].center.equatorialCoordinate!!
        assertEquals(75.0 + half, left.rightAscension, 1e-6)
        assertEquals(75.0 - half, right.rightAscension, 1e-6)
        assertEquals(0.0, left.declination, 1e-9)
        assertEquals(0.25, plan.achievedOverlap!!, 1e-12)
    }

    @Test
    fun testThreeByThreeAtTheEquator() {
        val frame = lens(50.0)
        val plan = MosaicPlanner.plan(equatorial(0.0, 80.0, 50.0), frame)
        assertEquals(3, plan.rows)
        assertEquals(listOf(3, 3, 3), plan.columnsPerRow)
        assertTrue(plan.isRectangular)
        assertEquals(3, plan.columns)
        val declinations = (0 until 3).map { plan.panel(it, 1)!!.center.equatorialCoordinate!!.declination }
        assertEquals(0.75 * frame.vertical, declinations[0], 1e-9)
        assertEquals(0.0, declinations[1], 1e-9)
        assertEquals(-0.75 * frame.vertical, declinations[2], 1e-9)
        val middle = (0 until 3).map { plan.panel(1, it)!!.center.equatorialCoordinate!!.rightAscension }
        assertEquals(75.0 + 0.75 * frame.horizontal, middle[0], 1e-6)
        assertEquals(75.0, middle[1], 1e-9)
        assertEquals(75.0 - 0.75 * frame.horizontal, middle[2], 1e-6)
        assertNull(plan.panel(3, 0))
        assertNull(plan.panel(0, 3))
        assertNull(plan.panel(-1, 0))
        // Rows are numbered from the pole side, columns from the east.
        assertEquals("R2C3", plan.panel(1, 2)!!.id)
        assertEquals(5, plan.panel(1, 2)!!.index)
    }

    @Test
    fun testRowsNearThePoleGetFewerPanels() {
        // A row nearer the pole is shorter on the sphere, so it needs fewer columns for the same right-ascension
        // span. Counts worked out with the closed-form width of a gnomonic frame (x and y edge solutions).
        val plan = MosaicPlanner.plan(equatorial(60.0, 80.0, 50.0), lens(50.0))
        assertEquals(3, plan.rows)
        assertEquals(listOf(1, 4, 5), plan.columnsPerRow)
        assertEquals("10 panels in 3 rows", plan.gridDescription)
        assertTrue(!plan.isRectangular)
        val declinations = (0 until 3).map { plan.panel(it, 0)!!.center.equatorialCoordinate!!.declination }
        val frame = lens(50.0)
        assertEquals(60.0 + 0.75 * frame.vertical, declinations[0], 1e-9)
        assertEquals(60.0, declinations[1], 1e-9)
        assertEquals(60.0 - 0.75 * frame.vertical, declinations[2], 1e-9)
    }

    @Test
    fun testRegionThatReachesThePoleIsClamped() {
        val plan = MosaicPlanner.plan(equatorial(70.0, 100.0, 50.0), lens(35.0))
        assertEquals(listOf(2, 6), plan.columnsPerRow)
        assertEquals(MosaicTestSupport.uncovered(plan, MosaicTestSupport.points(plan.region, 37, 53)), 0)
        // The top row sits just below the pole.
        assertTrue(plan.panels[0].center.equatorialCoordinate!!.declination < 90.0)
    }

    @Test
    fun testArchPanoramaOf24mmPortraitIsThreeByOne() {
        // 24 mm full frame held upright: 53.13° wide, 73.74° tall. A box of 110° of azimuth and 0° to 60° of
        // altitude: the frame covers 46.83° of azimuth at the horizon (closed form), 75 % of that is the step.
        val region = MosaicRegion.Horizontal(150.0, 110.0, 0.0, 60.0)
        val plan = MosaicPlanner.plan(region, lens(24.0), MosaicOrientation.PORTRAIT)
        assertEquals(MosaicFrameKind.HORIZONTAL, plan.kind)
        assertEquals(1, plan.rows)
        assertEquals(listOf(3), plan.columnsPerRow)
        assertEquals("3 × 1", plan.gridDescription)
        assertEquals(53.13010235415598, plan.frame.horizontal, 1e-9)
        assertEquals(73.73979529168804, plan.frame.vertical, 1e-9)
        val step = 0.75 * 46.82644889274108
        val azimuths = plan.panels.map { it.center.horizontalCoordinate!!.azimuth }
        assertEquals(205.0 - step, azimuths[0], 1e-6)
        assertEquals(205.0, azimuths[1], 1e-6)
        assertEquals(205.0 + step, azimuths[2], 1e-6)
        for (panel in plan.panels) assertEquals(30.0, panel.center.horizontalCoordinate!!.altitude, 1e-9)
        assertTrue(plan.warnings.isEmpty())
        assertEquals(0.25, plan.achievedOverlap!!, 1e-9)
    }

    @Test
    fun testPanoramaColumnsGrowWithTheAzimuthSpan() {
        // Closed-form frame width at the horizon 46.826°; columns = ceil((span − 46.826) / 35.12) + 1.
        val expected = listOf(60.0 to 2, 90.0 to 3, 110.0 to 3, 120.0 to 4, 150.0 to 4, 180.0 to 5)
        for ((span, columns) in expected) {
            val plan = MosaicPlanner.plan(MosaicRegion.Horizontal(0.0, span, 0.0, 60.0), lens(24.0), MosaicOrientation.PORTRAIT)
            assertEquals(listOf(columns), plan.columnsPerRow, "span $span")
        }
    }

    @Test
    fun testLandscapeFramesNeedTwoRowsForTheSameArch() {
        val plan = MosaicPlanner.plan(MosaicRegion.Horizontal(150.0, 110.0, 0.0, 60.0), lens(24.0))
        assertEquals(2, plan.rows)
        assertEquals(listOf(2, 2), plan.columnsPerRow)
        val altitudes = listOf(plan.panels[0], plan.panels[2]).map { it.center.horizontalCoordinate!!.altitude }
        val step = 0.75 * 53.13010235415598
        assertEquals(30.0 + step / 2, altitudes[0], 1e-9)
        assertEquals(30.0 - step / 2, altitudes[1], 1e-9)
    }

    @Test
    fun testFullCirclePanoramaWrapsAround() {
        val plan = MosaicPlanner.plan(MosaicRegion.Horizontal(0.0, 360.0, 0.0, 30.0), lens(24.0))
        assertEquals(listOf(7), plan.columnsPerRow)
        val azimuths = plan.panels.map { it.center.horizontalCoordinate!!.azimuth }
        for (index in azimuths.indices) {
            val next = azimuths[(index + 1) % azimuths.size]
            assertEquals(360.0 / 7.0, AstroMath.normalizeDegrees(next - azimuths[index]), 1e-9, "step $index")
        }
        // The achieved overlap is more than requested because 360° does not divide into steps of 75 %.
        assertTrue(plan.achievedOverlap!! > 0.25)
        assertEquals(360.0, plan.coverage.longitudeSpanDegrees)
    }

    @Test
    fun testMilkyWayCoreRegionAt135mm() {
        val plan = MosaicPlanner.plan(equatorial(-29.0, 50.0, 30.0), lens(135.0))
        assertEquals(listOf(5, 5, 5, 4), plan.columnsPerRow)
        assertEquals(19, plan.panelCount)
    }

    @Test
    fun testAndromedaAt600mmNeedsTwoPanels() {
        val center = EquatorialCoordinate(10.684792, 41.269056)
        val size = AngularSize.create(178.0, 70.0, 35.0)!!
        val region = MosaicRegion.fitting(center, size)
        val plan = MosaicPlanner.plan(region, lens(600.0))
        assertEquals(listOf(2), plan.columnsPerRow)
        assertEquals(1, MosaicPlanner.plan(region, lens(200.0)).panelCount)
    }

    // MARK: Coverage and overlap

    @Test
    fun testPanelsCoverTheRegionWithoutGaps() {
        val cases = listOf(
            MosaicRegion.Equatorial(sky, 20.0, 15.0) to lens(50.0),
            equatorial(0.0, 80.0, 50.0) to lens(50.0),
            equatorial(60.0, 80.0, 50.0) to lens(50.0),
            equatorial(70.0, 100.0, 50.0) to lens(35.0),
            equatorial(-29.0, 50.0, 30.0) to lens(135.0),
            equatorial(-60.0, 100.0, 60.0) to lens(24.0),
            equatorial(80.0, 40.0, 10.0) to lens(50.0),
            equatorial(30.0, 60.0, 40.0, 37.0) to lens(85.0),
            equatorial(0.0, 120.0, 70.0, 90.0) to lens(24.0),
            equatorial(88.0, 60.0, 30.0) to lens(24.0),
            MosaicRegion.Horizontal(150.0, 110.0, 0.0, 60.0) to lens(24.0),
            MosaicRegion.Horizontal(10.0, 200.0, -10.0, 80.0) to lens(14.0),
            MosaicRegion.Horizontal(0.0, 360.0, 0.0, 90.0) to lens(20.0),
            MosaicRegion.Horizontal(300.0, 150.0, 20.0, 70.0) to lens(35.0),
        )
        for ((region, frame) in cases) {
            for (orientation in MosaicOrientation.entries) {
                val plan = MosaicPlanner.plan(region, frame, orientation)
                val points = MosaicTestSupport.points(region, 37, 53)
                assertEquals(0, MosaicTestSupport.uncovered(plan, points), "$region $orientation")
                assertEquals(1.0, plan.coverage.coveredFraction)
                assertTrue(plan.warnings.isEmpty(), "$region $orientation")
                assertTrue(plan.achievedOverlap?.let { it >= 0.25 - 1e-9 } ?: true)
            }
        }
    }

    @Test
    fun testSweepOfLensesAndRegionsLeavesNoGaps() {
        // 14 mm to 135 mm, sites near the pole and in the south, with and without a position angle, small and large regions.
        var planned = 0
        for (focalLength in listOf(14.0, 24.0, 50.0, 135.0)) {
            for (declination in listOf(-60.0, 0.0, 45.0, 85.0)) {
                for ((width, height) in listOf(40.0 to 25.0, 90.0 to 60.0)) {
                    for (orientation in MosaicOrientation.entries) {
                        for (overlap in listOf(0.1, 0.5)) {
                            val region = equatorial(declination, width, height, if (planned % 2 == 0) 0.0 else 37.0)
                            val plan = try {
                                MosaicPlanner.plan(region, lens(focalLength), orientation, overlap)
                            } catch (error: MosaicError.TooManyPanels) {
                                continue
                            }
                            planned += 1
                            val label = "$focalLength mm, dec $declination, $width x $height, $orientation, $overlap"
                            assertEquals(0, MosaicTestSupport.uncovered(plan, MosaicTestSupport.points(region, 31, 47)), label)
                            assertTrue(plan.warnings.none { it is MosaicWarning.OverlapNotMet }, label)
                            assertTrue(plan.achievedOverlap?.let { it >= overlap - 1e-9 } ?: true, label)
                        }
                    }
                }
            }
        }
        for (focalLength in listOf(14.0, 24.0, 50.0)) {
            for (span in listOf(60.0, 200.0, 360.0)) {
                for ((low, high) in listOf(0.0 to 30.0, -10.0 to 80.0)) {
                    for (orientation in MosaicOrientation.entries) {
                        val region = MosaicRegion.Horizontal(40.0, span, low, high)
                        val plan = try {
                            MosaicPlanner.plan(region, lens(focalLength), orientation)
                        } catch (error: MosaicError.TooManyPanels) {
                            continue
                        }
                        planned += 1
                        val label = "$focalLength mm, span $span, $low to $high, $orientation"
                        assertEquals(0, MosaicTestSupport.uncovered(plan, MosaicTestSupport.points(region, 31, 47)), label)
                        assertTrue(plan.warnings.isEmpty(), label)
                    }
                }
            }
        }
        assertTrue(planned > 150, "$planned plans")
    }

    @Test
    fun testNeighbouringPanelsShareTheRequestedOverlapAtTheNarrowestAltitude() {
        // Walk along parallels of altitude and measure how much of a frame its right-hand neighbour also covers.
        val region = MosaicRegion.Horizontal(150.0, 110.0, 0.0, 60.0)
        for (overlap in listOf(0.1, 0.25, 0.4)) {
            val plan = MosaicPlanner.plan(region, lens(24.0), MosaicOrientation.PORTRAIT, overlap)
            var narrowest = 1.0
            val quads = plan.panels.map { MosaicTestSupport.Quad(it) }
            for (altitude in listOf(0.0, 15.0, 30.0, 45.0, 60.0)) {
                for (index in 0 until plan.panelCount - 1) {
                    var first = 0
                    var shared = 0
                    var azimuth = 100.0
                    while (azimuth < 320.0) {
                        val point = HorizontalCoordinate(azimuth, altitude).unitVector
                        if (quads[index].contains(point)) {
                            first += 1
                            if (quads[index + 1].contains(point)) shared += 1
                        }
                        azimuth += 0.02
                    }
                    if (first > 0) narrowest = kotlin.math.min(narrowest, shared.toDouble() / first.toDouble())
                    if (altitude == 0.0 && index == 0) {
                        assertEquals(overlap, shared.toDouble() / first.toDouble(), 0.004, "overlap $overlap at the horizon")
                    }
                }
            }
            assertTrue(narrowest >= overlap - 0.004, "overlap $overlap: narrowest $narrowest")
        }
    }

    @Test
    fun testFramesNeverLeaveGapsEvenWhenTheEdgesBow() {
        // With a 14 mm lens the frame edges bow so strongly that the tangent-line layout of three rows leaves slivers
        // at the sides; the planner has to add overlap and extend the grid.
        val region = MosaicRegion.Horizontal(10.0, 40.0, -60.0, 60.0)
        val frame = lens(14.0)
        val full = MosaicPlanner.plan(region, frame, MosaicOrientation.LANDSCAPE, 0.5)
        assertEquals(3, full.rows)
        assertEquals(1.0, full.coverage.coveredFraction)
        assertTrue(full.warnings.isEmpty())
        assertEquals(0, MosaicTestSupport.uncovered(full, MosaicTestSupport.points(region, 41, 41)))
        val limited = MosaicPlanner.plan(region, frame, MosaicOrientation.LANDSCAPE, 0.5, null, false, 1)
        assertEquals(2, limited.rows)
        val warning = limited.warnings.single() as MosaicWarning.OverlapNotMet
        assertTrue(warning.coveredFraction > 0.98 && warning.coveredFraction < 0.995, "${warning.coveredFraction}")
        assertEquals(warning.coveredFraction, limited.coverage.coveredFraction)
        assertTrue(MosaicTestSupport.uncovered(limited, MosaicTestSupport.points(region, 41, 41)) > 0)
    }

    @Test
    fun testCornersFormAGnomonicRectangle() {
        val plan = MosaicPlanner.plan(MosaicRegion.Horizontal(150.0, 110.0, 0.0, 60.0), lens(24.0), MosaicOrientation.PORTRAIT)
        for (panel in plan.panels) {
            val center = panel.center.unitVector
            val corners = panel.corners.map { it.unitVector }
            assertEquals(4, corners.size)
            // The diagonal of the frame is the lens's diagonal field of view.
            assertEquals(plan.frame.diagonal, corners[0].angle(corners[2]), 1e-9)
            assertEquals(plan.frame.diagonal, corners[1].angle(corners[3]), 1e-9)
            // The middle of the top and bottom edges is half the frame height from the centre, the sides half the width.
            assertEquals(plan.frame.vertical / 2, center.angle((corners[0] + corners[1]).normalized), 1e-9)
            assertEquals(plan.frame.vertical / 2, center.angle((corners[2] + corners[3]).normalized), 1e-9)
            assertEquals(plan.frame.horizontal / 2, center.angle((corners[0] + corners[3]).normalized), 1e-9)
            assertEquals(plan.frame.horizontal / 2, center.angle((corners[1] + corners[2]).normalized), 1e-9)
            // Top corners have the larger altitude on a level frame.
            assertTrue(panel.corners[0].horizontalCoordinate!!.altitude > panel.corners[3].horizontalCoordinate!!.altitude)
            // Left corners have the smaller azimuth, the camera looks along increasing azimuth to the right.
            assertTrue(AstroMath.normalizeSignedDegrees(panel.corners[1].horizontalCoordinate!!.azimuth - panel.corners[0].horizontalCoordinate!!.azimuth) > 0)
        }
    }

    @Test
    fun testPanelAreaIsTheSolidAngleOfTheFrame() {
        val plan = MosaicPlanner.plan(equatorial(0.0, 80.0, 50.0), lens(200.0))
        // A 10.29° × 6.87° frame is almost flat: the area is close to the product of its sides.
        val area = plan.coverage.panelAreaSquareDegrees / plan.panelCount
        assertEquals(10.2855 * 6.8673, area, 0.2)
        assertTrue(area < 10.2855 * 6.8673)
        assertEquals(3874.2788, plan.coverage.regionAreaSquareDegrees, 0.01)
    }

    @Test
    fun testCoverageExtents() {
        val frame = lens(50.0)
        val plan = MosaicPlanner.plan(equatorial(0.0, 80.0, 50.0), frame)
        // Three rows with 75 % spacing: two steps plus one frame height.
        assertEquals(2 * 0.75 * frame.vertical + frame.vertical, plan.coverage.heightDegrees, 1e-9)
        // The central row spans two steps plus one frame width.
        assertEquals(2 * 0.75 * frame.horizontal + frame.horizontal, plan.coverage.widthDegrees, 1e-6)
        assertEquals(plan.coverage.widthDegrees, plan.coverage.longitudeSpanDegrees, 1e-6)
        assertTrue(plan.coverage.panelAreaSquareDegrees > plan.coverage.regionAreaSquareDegrees)
    }

    // MARK: Position angle

    @Test
    fun testPositionAngleTurnsThePlanAboutItsCentre() {
        // Turning the region by the position angle turns every panel with it, about the region's centre.
        val center = EquatorialCoordinate.fromRightAscensionHours(5.0, 25.0).unitVector
        for (angle in listOf(37.0, 90.0, 200.0, -45.0)) {
            val base = MosaicPlanner.plan(equatorial(25.0, 90.0, 40.0), lens(85.0))
            val turned = MosaicPlanner.plan(equatorial(25.0, 90.0, 40.0, angle), lens(85.0))
            assertEquals(base.columnsPerRow, turned.columnsPerRow, "angle $angle")
            val rotation = Matrix3.rotation(center, -AstroMath.radians(angle))
            for (index in base.panels.indices) {
                val expected = rotation * base.panels[index].center.unitVector
                assertEquals(0.0, expected.angle(turned.panels[index].center.unitVector), 1e-8, "angle $angle panel $index")
                for (corner in 0 until 4) {
                    val expectedCorner = rotation * base.panels[index].corners[corner].unitVector
                    assertEquals(0.0, expectedCorner.angle(turned.panels[index].corners[corner].unitVector), 1e-8)
                }
            }
        }
    }

    @Test
    fun testPositionAngleOfNinetyRunsTheRowsNorthSouth() {
        // A region 60° wide and 10° tall whose width runs north-south at position angle 90.
        val plan = MosaicPlanner.plan(equatorial(0.0, 60.0, 10.0, 90.0), lens(50.0))
        assertEquals(listOf(2), plan.columnsPerRow)
        val first = plan.panels[0].center.equatorialCoordinate!!
        val second = plan.panels[1].center.equatorialCoordinate!!
        assertEquals(first.rightAscension, second.rightAscension, 1e-6)
        assertEquals(0.75 * lens(50.0).horizontal, abs(first.declination - second.declination), 1e-6)
        // The left panel is the eastern one for an unturned plan; turned by 90° it is the northern or southern end.
        assertEquals(75.0, first.rightAscension, 1e-6)
        // Unturned, the same region runs east-west.
        val unturned = MosaicPlanner.plan(equatorial(0.0, 60.0, 10.0, 0.0), lens(50.0))
        assertEquals(unturned.panels[0].center.equatorialCoordinate!!.declination, unturned.panels[1].center.equatorialCoordinate!!.declination, 1e-8)
    }

    @Test
    fun testFittingAnObjectTurnsTheRegionAlongItsMajorAxis() {
        // M 31: 178′ × 70′ with the major axis at position angle 35°. With a 20 % margin: 3.56° × 1.4°.
        val center = EquatorialCoordinate(10.684792, 41.269056)
        val region = MosaicRegion.fitting(center, AngularSize.create(178.0, 70.0, 35.0)!!) as MosaicRegion.Equatorial
        assertEquals(center, region.center)
        assertEquals(178.0 / 60.0 * 1.2, region.width, 1e-12)
        assertEquals(70.0 / 60.0 * 1.2, region.height, 1e-12)
        assertEquals(305.0, region.positionAngle, 1e-12)
        // The width axis of the region points along position angle 35°: the direction 90° after the height axis.
        val plan = MosaicPlanner.plan(region, lens(1200.0))
        assertTrue(plan.columnsPerRow[0] >= 2)
        val first = plan.panel(0, 0)!!.center.unitVector
        val last = plan.panel(0, plan.columnsPerRow[0] - 1)!!.center.unitVector
        val towards = (last - first)
        val centerVector = center.unitVector
        val east = Vector3(-centerVector.y, centerVector.x, 0.0).normalized
        val north = centerVector.cross(east)
        val direction = AstroMath.atan2Deg(towards.dot(east), towards.dot(north))
        // The planner puts the left panel at the east end of its row; the row runs along the major axis, so the direction
        // from the first to the last panel is the major axis (35°) turned half a circle (215°).
        assertEquals(215.0, AstroMath.normalizeDegrees(direction), 0.5)
        // An object without a minor axis gives a square region; without a position angle the width runs north-south.
        val round = MosaicRegion.fitting(center, AngularSize.create(150.0)!!, 0.0) as MosaicRegion.Equatorial
        assertEquals(2.5, round.width, 1e-12)
        assertEquals(2.5, round.height, 1e-12)
        assertEquals(270.0, round.positionAngle, 1e-12)
    }

    @Test
    fun testFittingACatalogObject() {
        val catalog = Catalog.loadBundled()
        val m31 = assertNotNull(catalog.objectWithID("dso.M31"))
        val region = MosaicRegion.fitting(m31, 0.5) as MosaicRegion.Equatorial
        assertEquals(m31.j2000, region.center)
        assertEquals(m31.angularSize!!.majorArcminutes / 60 * 1.5, region.width, 1e-12)
        assertFailsWith<MosaicError.ObjectNotFixed> { MosaicRegion.fitting(assertNotNull(catalog.objectWithID("moon"))) }
        assertFailsWith<MosaicError.ObjectNotFixed> { MosaicRegion.fitting(assertNotNull(catalog.objectWithID("special.ncp"))) }
        assertEquals(
            MosaicError.ObjectHasNoSize("star.hip32349"),
            assertFailsWith<MosaicError.ObjectHasNoSize> { MosaicRegion.fitting(assertNotNull(catalog.objectWithID("star.hip32349"))) },
        )
    }

    @Test
    fun testCenteredHorizontalBox() {
        val region = MosaicRegion.centered(180.0, 30.0, 120.0, 60.0) as MosaicRegion.Horizontal
        assertEquals(120.0, region.azimuthStart, 1e-12)
        assertEquals(120.0, region.azimuthSpan, 1e-12)
        assertEquals(0.0, region.minimumAltitude, 1e-12)
        assertEquals(60.0, region.maximumAltitude, 1e-12)
        val clamped = MosaicRegion.centered(10.0, 80.0, 100.0, 60.0) as MosaicRegion.Horizontal
        assertEquals(320.0, clamped.azimuthStart, 1e-12)
        assertEquals(90.0, clamped.maximumAltitude, 1e-12)
    }

    // MARK: Orientation and lens

    @Test
    fun testPortraitSwapsTheFrame() {
        val frame = lens(50.0)
        val region = equatorial(0.0, 20.0, 30.0)
        val landscape = MosaicPlanner.plan(region, frame, MosaicOrientation.LANDSCAPE)
        val portrait = MosaicPlanner.plan(region, frame, MosaicOrientation.PORTRAIT)
        assertEquals(frame, landscape.frame)
        assertEquals(frame.rotated, portrait.frame)
        assertEquals(MosaicOrientation.PORTRAIT, portrait.orientation)
        assertEquals(2, landscape.rows)
        assertEquals(1, portrait.rows)
        assertEquals(listOf(1), portrait.columnsPerRow)
        assertEquals(MosaicOrientation.PORTRAIT, MosaicPlanner.recommendedOrientation(region, frame))
        assertEquals(MosaicOrientation.LANDSCAPE, MosaicPlanner.recommendedOrientation(equatorial(0.0, 40.0, 10.0), frame))
        assertEquals(MosaicOrientation.LANDSCAPE, MosaicPlanner.recommendedOrientation(equatorial(0.0, 20.0, 15.0), frame))
        assertEquals(MosaicOrientation.PORTRAIT, MosaicOrientation.fromRawValue("portrait"))
        assertNull(MosaicOrientation.fromRawValue("diagonal"))
    }

    @Test
    fun testPlanWithALensSetupUsesItsFieldOfView() {
        val setup = LensSetup(SensorFormat.apsC, 35.0, 2.8)
        val region = equatorial(10.0, 30.0, 20.0)
        val viaSetup = MosaicPlanner.plan(region, setup, MosaicOrientation.PORTRAIT, 0.3)
        val viaFrame = MosaicPlanner.plan(region, setup.fieldOfView, MosaicOrientation.PORTRAIT, 0.3)
        assertEquals(viaFrame, viaSetup)
        assertEquals(0.3, viaSetup.overlap)
    }

    // MARK: Shooting order

    @Test
    fun testShootingOrderIsASerpentine() {
        val plan = MosaicPlanner.plan(equatorial(0.0, 80.0, 50.0), lens(50.0), observerLatitude = 40.0)
        assertEquals(9, plan.shootingOrder.size)
        assertEquals((0 until 9).toList(), plan.shootingOrder.sorted())
        for (step in 0 until 8) {
            val a = plan.panels[plan.shootingOrder[step]]
            val b = plan.panels[plan.shootingOrder[step + 1]]
            if (a.row == b.row) {
                assertEquals(1, abs(a.column - b.column))
            } else {
                assertEquals(1, abs(a.row - b.row))
                assertEquals(a.column, b.column)
            }
        }
        for ((place, index) in plan.shootingOrder.withIndex()) assertEquals(place, plan.panels[index].shootingPosition)
        assertEquals(plan.shootingOrder, plan.orderedPanels.map { it.index })
    }

    @Test
    fun testEquatorialOrderStartsWhereTheFirstPanelSets() {
        val region = equatorial(0.0, 80.0, 50.0)
        // From the northern hemisphere the western, southern corner sets first and the order runs along the rows.
        val north = MosaicPlanner.plan(region, lens(50.0), observerLatitude = 40.0)
        assertEquals(listOf(8, 7, 6, 3, 4, 5, 2, 1, 0), north.shootingOrder)
        // From the southern hemisphere the northern stars set first.
        val south = MosaicPlanner.plan(region, lens(50.0), observerLatitude = -40.0)
        assertEquals(listOf(2, 1, 0, 3, 4, 5, 8, 7, 6), south.shootingOrder)
        // Without a latitude the western end goes first.
        val row = MosaicPlanner.plan(equatorial(0.0, 40.0, 10.0), lens(50.0))
        assertEquals(listOf(1, 0), row.shootingOrder)
        // The reverse order starts where the other ends.
        val reversed = MosaicPlanner.plan(region, lens(50.0), observerLatitude = 40.0, reverseOrder = true)
        assertEquals(north.shootingOrder.reversed(), reversed.shootingOrder)
        assertEquals(0, reversed.panels[0].shootingPosition)
        assertEquals(8, reversed.panels[8].shootingPosition)
    }

    @Test
    fun testSettingHourAngleOfAStar() {
        assertEquals(90.0, MosaicPlanner.settingHourAngle(30.0, null), 1e-12)
        assertEquals(90.0, MosaicPlanner.settingHourAngle(0.0, 40.0), 1e-9)
        // cos H = −tan φ tan δ: a star at declination 20° seen from 40° N sets at an hour angle of 108.1°.
        assertEquals(AstroMath.acosDeg(-kotlin.math.tan(AstroMath.radians(40.0)) * kotlin.math.tan(AstroMath.radians(20.0))), MosaicPlanner.settingHourAngle(20.0, 40.0), 1e-9)
        assertEquals(180.0, MosaicPlanner.settingHourAngle(60.0, 40.0), 1e-9)
        assertEquals(0.0, MosaicPlanner.settingHourAngle(-60.0, 40.0), 1e-9)
        assertEquals(180.0, MosaicPlanner.settingHourAngle(10.0, 90.0), 1e-9)
        assertEquals(0.0, MosaicPlanner.settingHourAngle(-10.0, 90.0), 1e-9)
        assertEquals(90.0, MosaicPlanner.settingHourAngle(0.0, 90.0), 1e-9)
    }

    @Test
    fun testHorizontalOrderStartsAtTheLeftOfTheBottomRow() {
        val plan = MosaicPlanner.plan(MosaicRegion.Horizontal(150.0, 110.0, 0.0, 60.0), lens(24.0))
        // Two rows of two: bottom row left to right, then the top row right to left.
        assertEquals(listOf(2, 3, 1, 0), plan.shootingOrder)
        val first = plan.orderedPanels[0]
        assertEquals(1, first.row)
        assertEquals(0, first.column)
        val reversed = MosaicPlanner.plan(MosaicRegion.Horizontal(150.0, 110.0, 0.0, 60.0), lens(24.0), reverseOrder = true)
        assertEquals(listOf(0, 1, 3, 2), reversed.shootingOrder)
    }

    // MARK: Warnings and errors

    @Test
    fun testMoreThanOneHundredPanelsGetsAWarning() {
        val plan = MosaicPlanner.plan(equatorial(0.0, 150.0, 80.0), lens(135.0))
        assertTrue(plan.panelCount > MosaicPlanner.recommendedPanelLimit)
        assertEquals(listOf<MosaicWarning>(MosaicWarning.TooManyPanels(plan.panelCount)), plan.warnings)
        assertEquals(1.0, plan.coverage.coveredFraction)
    }

    @Test
    fun testMosaicTooLargeToPlanIsAnError() {
        val error = assertFailsWith<MosaicError.TooManyPanels> {
            MosaicPlanner.plan(equatorial(0.0, 360.0, 180.0), lens(200.0))
        }
        assertEquals(MosaicPlanner.maximumPanelCount, error.limit)
        assertTrue(error.count > error.limit)
        assertFailsWith<MosaicError.TooManyPanels> {
            MosaicPlanner.plan(MosaicRegion.Horizontal(0.0, 360.0, -90.0, 90.0), lens(1000.0))
        }
    }

    @Test
    fun testInvalidInputIsRejected() {
        val frame = lens(50.0)
        val good = equatorial(0.0, 20.0, 10.0)
        assertEquals(MosaicError.NonFiniteInput, assertFailsWith<MosaicError.NonFiniteInput> {
            MosaicPlanner.plan(MosaicRegion.Equatorial(sky, Double.NaN, 10.0), frame)
        })
        assertFailsWith<MosaicError.NonFiniteInput> { MosaicPlanner.plan(MosaicRegion.Equatorial(sky, 10.0, 10.0, Double.POSITIVE_INFINITY), frame) }
        assertFailsWith<MosaicError.NonFiniteInput> { MosaicPlanner.plan(MosaicRegion.Horizontal(Double.NaN, 10.0, 0.0, 10.0), frame) }
        assertEquals(MosaicError.InvalidWidth(0.0), assertFailsWith<MosaicError.InvalidWidth> { MosaicPlanner.plan(MosaicRegion.Equatorial(sky, 0.0, 10.0), frame) })
        assertFailsWith<MosaicError.InvalidWidth> { MosaicPlanner.plan(MosaicRegion.Equatorial(sky, 361.0, 10.0), frame) }
        assertFailsWith<MosaicError.InvalidWidth> { MosaicPlanner.plan(MosaicRegion.Equatorial(sky, -5.0, 10.0), frame) }
        assertEquals(MosaicError.InvalidHeight(181.0), assertFailsWith<MosaicError.InvalidHeight> { MosaicPlanner.plan(MosaicRegion.Equatorial(sky, 10.0, 181.0), frame) })
        assertFailsWith<MosaicError.InvalidHeight> { MosaicPlanner.plan(MosaicRegion.Equatorial(sky, 10.0, 0.0), frame) }
        assertEquals(MosaicError.InvalidAzimuthSpan(0.0), assertFailsWith<MosaicError.InvalidAzimuthSpan> { MosaicPlanner.plan(MosaicRegion.Horizontal(0.0, 0.0, 0.0, 10.0), frame) })
        assertFailsWith<MosaicError.InvalidAzimuthSpan> { MosaicPlanner.plan(MosaicRegion.Horizontal(0.0, 361.0, 0.0, 10.0), frame) }
        assertEquals(MosaicError.InvalidAltitudeRange(10.0, 10.0), assertFailsWith<MosaicError.InvalidAltitudeRange> { MosaicPlanner.plan(MosaicRegion.Horizontal(0.0, 10.0, 10.0, 10.0), frame) })
        assertFailsWith<MosaicError.InvalidAltitudeRange> { MosaicPlanner.plan(MosaicRegion.Horizontal(0.0, 10.0, 20.0, 10.0), frame) }
        assertFailsWith<MosaicError.InvalidAltitudeRange> { MosaicPlanner.plan(MosaicRegion.Horizontal(0.0, 10.0, -91.0, 10.0), frame) }
        assertFailsWith<MosaicError.InvalidAltitudeRange> { MosaicPlanner.plan(MosaicRegion.Horizontal(0.0, 10.0, 0.0, 91.0), frame) }
        for (overlap in listOf(0.05, 0.6, Double.NaN, -1.0, Double.POSITIVE_INFINITY)) {
            assertFailsWith<MosaicError.InvalidOverlap>("overlap $overlap") { MosaicPlanner.plan(good, frame, overlap = overlap) }
        }
        assertEquals(0.1, MosaicPlanner.plan(good, frame, overlap = 0.1).overlap)
        assertEquals(0.5, MosaicPlanner.plan(good, frame, overlap = 0.5).overlap)
        assertEquals(MosaicError.InvalidFrame(0.0, 20.0), assertFailsWith<MosaicError.InvalidFrame> { MosaicPlanner.plan(good, FrameSize(0.0, 20.0, 20.0)) })
        assertFailsWith<MosaicError.InvalidFrame> { MosaicPlanner.plan(good, FrameSize(180.0, 20.0, 20.0)) }
        assertFailsWith<MosaicError.InvalidFrame> { MosaicPlanner.plan(good, FrameSize(20.0, -1.0, 20.0)) }
        assertFailsWith<MosaicError.NonFiniteInput> { MosaicPlanner.plan(good, FrameSize(Double.NaN, 20.0, 20.0)) }
        assertEquals(MosaicError.InvalidLatitude(91.0), assertFailsWith<MosaicError.InvalidLatitude> { MosaicPlanner.plan(good, frame, observerLatitude = 91.0) })
        assertFailsWith<MosaicError.InvalidLatitude> { MosaicPlanner.plan(good, frame, observerLatitude = Double.NaN) }
        assertEquals(MosaicError.InvalidMargin(-0.1), assertFailsWith<MosaicError.InvalidMargin> { MosaicRegion.fitting(sky, AngularSize.create(60.0)!!, -0.1) })
        assertFailsWith<MosaicError.InvalidMargin> { MosaicRegion.fitting(sky, AngularSize.create(60.0)!!, 2.5) }
        assertFailsWith<MosaicError.InvalidMargin> { MosaicRegion.fitting(sky, AngularSize.create(60.0)!!, Double.NaN) }
        assertFailsWith<MosaicError.InvalidWidth> { MosaicRegion.fitting(sky, AngularSize.create(18_000.0)!!, 0.5) }
        assertNotNull(MosaicError.NonFiniteInput.message)
    }

    // MARK: Outline and values

    @Test
    fun testOutlineFollowsTheEdges() {
        val plan = MosaicPlanner.plan(MosaicRegion.Horizontal(150.0, 110.0, 0.0, 60.0), lens(24.0), MosaicOrientation.PORTRAIT)
        val panel = plan.panels[0]
        val outline = panel.outline(4)
        assertEquals(16, outline.size)
        // Every fourth point is a corner.
        for (corner in 0 until 4) {
            assertEquals(0.0, outline[corner * 4].unitVector.angle(panel.corners[corner].unitVector), 1e-9)
        }
        // The midpoint of the top edge is on the great circle through the top corners.
        val a = panel.corners[0].unitVector
        val b = panel.corners[1].unitVector
        val normal = a.cross(b).normalized
        for (index in 0..4) assertEquals(0.0, normal.dot(outline[index].unitVector), 1e-9)
        assertEquals(4, panel.outline(0).size)
        assertEquals(4 * 64, panel.outline(1000).size)
        assertNotNull(outline[0].horizontalCoordinate)
        assertNull(outline[0].equatorialCoordinate)
        val equatorialPlan = MosaicPlanner.plan(equatorial(0.0, 20.0, 10.0), lens(50.0))
        assertNotNull(equatorialPlan.panels[0].outline()[0].equatorialCoordinate)
    }

    @Test
    fun testRegionOutlineTracesTheRegion() {
        // A horizontal box: along the top edge from the left (the start of the azimuth range), down the right side,
        // back along the bottom edge and up the left side.
        val box = MosaicPlanner.plan(MosaicRegion.Horizontal(150.0, 110.0, 0.0, 60.0), lens(24.0))
        val loops = box.regionOutline(8)
        assertEquals(1, loops.size)
        val loop = loops[0].map { it.horizontalCoordinate!! }
        assertEquals(32, loop.size)
        assertEquals(HorizontalCoordinate(150.0, 60.0).unitVector.angle(loop[0].unitVector), 0.0, 1e-9)
        assertEquals(HorizontalCoordinate(260.0, 60.0).unitVector.angle(loop[8].unitVector), 0.0, 1e-9)
        assertEquals(HorizontalCoordinate(260.0, 0.0).unitVector.angle(loop[16].unitVector), 0.0, 1e-9)
        assertEquals(HorizontalCoordinate(150.0, 0.0).unitVector.angle(loop[24].unitVector), 0.0, 1e-9)
        for (index in 0 until 8) {
            assertEquals(60.0, loop[index].altitude, 1e-9)
            assertEquals(150.0 + 110.0 * index / 8.0, loop[index].azimuth, 1e-9)
            assertEquals(0.0, loop[16 + index].altitude, 1e-9)
            assertEquals(260.0 - 110.0 * index / 8.0, loop[16 + index].azimuth, 1e-9)
        }
        // An equatorial region: the top edge is at the upper declination, spread over the right-ascension span.
        val sky = MosaicPlanner.plan(equatorial(20.0, 40.0, 10.0), lens(50.0))
        val outline = sky.regionOutline(4)[0].map { it.equatorialCoordinate!! }
        val span = 40.0 / cos(AstroMath.radians(20.0))
        assertEquals(16, outline.size)
        assertEquals(25.0, outline[0].declination, 1e-9)
        assertEquals(75.0 + span / 2, outline[0].rightAscension, 1e-9)
        assertEquals(15.0, outline[8].declination, 1e-9)
        assertEquals(75.0 - span / 2, outline[8].rightAscension, 1e-9)
        // A region that wraps all the way round is two circles.
        val ring = MosaicPlanner.plan(MosaicRegion.Horizontal(0.0, 360.0, 0.0, 30.0), lens(24.0)).regionOutline(4)
        assertEquals(2, ring.size)
        assertEquals(listOf(16, 16), ring.map { it.size })
        assertTrue(ring[0].all { abs(it.horizontalCoordinate!!.altitude - 30.0) < 1e-9 })
        assertTrue(ring[1].all { abs(it.horizontalCoordinate!!.altitude) < 1e-9 })
        // The number of points is limited.
        assertEquals(8, box.regionOutline(0)[0].size)
        assertEquals(1024, box.regionOutline(1000)[0].size)
        // Turned by a position angle the outline turns with the panels.
        val center = EquatorialCoordinate.fromRightAscensionHours(5.0, 20.0).unitVector
        val rotation = Matrix3.rotation(center, -AstroMath.radians(37.0))
        val base = MosaicPlanner.plan(equatorial(20.0, 40.0, 10.0), lens(50.0)).regionOutline(6)[0]
        val turned = MosaicPlanner.plan(equatorial(20.0, 40.0, 10.0, 37.0), lens(50.0)).regionOutline(6)[0]
        for (index in base.indices) {
            assertEquals(0.0, (rotation * base[index].unitVector).angle(turned[index].unitVector), 1e-8)
        }
    }

    @Test
    fun testPlansAreValuesAndRepeatable() {
        val region = equatorial(35.0, 70.0, 45.0, 20.0)
        assertEquals(MosaicPlanner.plan(region, lens(50.0)), MosaicPlanner.plan(region, lens(50.0)))
        assertTrue(MosaicPlanner.plan(region, lens(50.0)) != MosaicPlanner.plan(region, lens(85.0)))
        val plan = MosaicPlanner.plan(region, lens(50.0))
        assertEquals(MosaicFrameKind.EQUATORIAL, MosaicFrameKind.fromRawValue("equatorial"))
        assertNull(MosaicFrameKind.fromRawValue("galactic"))
        assertEquals(plan.panelCount, plan.columnsPerRow.sum())
        assertEquals(plan.rows, plan.columnsPerRow.size)
        assertEquals(plan.columnsPerRow.max(), plan.columns)
    }

    @Test
    fun testPoleOfAnEquatorialPlanFollowsThePositionAngle() {
        val unturned = MosaicPlanner.plan(equatorial(30.0, 20.0, 10.0), lens(50.0))
        val pole = unturned.pole.equatorialCoordinate!!
        assertEquals(90.0, pole.declination, 1e-9)
        val turned = MosaicPlanner.plan(equatorial(30.0, 20.0, 10.0, 90.0), lens(50.0))
        // The pole of the rows swings round the centre with the position angle: 90° east it lies on the equator-like circle.
        val center = EquatorialCoordinate.fromRightAscensionHours(5.0, 30.0)
        assertEquals(90.0 - 30.0, center.angularSeparation(unturned.pole.equatorialCoordinate!!), 1e-9)
        assertEquals(90.0 - 30.0, center.angularSeparation(turned.pole.equatorialCoordinate!!), 1e-9)
        assertTrue(turned.pole.equatorialCoordinate!!.declination < 90.0)
        val horizontal = MosaicPlanner.plan(MosaicRegion.Horizontal(0.0, 90.0, 0.0, 30.0), lens(24.0))
        assertEquals(90.0, horizontal.pole.horizontalCoordinate!!.altitude, 1e-9)
    }

    @Test
    fun testPlanningIsFast() {
        val region = equatorial(60.0, 80.0, 50.0)
        val frame = lens(50.0)
        MosaicPlanner.plan(region, frame)
        val start = System.nanoTime()
        repeat(50) { MosaicPlanner.plan(region, frame) }
        val perPlanMilliseconds = (System.nanoTime() - start) / 50.0 / 1e6
        assertTrue(perPlanMilliseconds < 60.0, "$perPlanMilliseconds ms per plan")
    }
}

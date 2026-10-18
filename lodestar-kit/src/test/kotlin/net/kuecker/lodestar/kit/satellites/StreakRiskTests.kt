package net.kuecker.lodestar.kit.satellites

import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.pointing.DeviceAttitude
import net.kuecker.lodestar.kit.pointing.MountProfile
import net.kuecker.lodestar.kit.pointing.PointingSolution
import net.kuecker.lodestar.kit.satellites.SatelliteTestSupport.geostationary
import net.kuecker.lodestar.kit.satellites.SatelliteTestSupport.observer
import net.kuecker.lodestar.kit.satellites.SatelliteTestSupport.referenceSatellites
import java.time.Instant
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreakRiskTests {
    // MARK: Camera frame

    private fun assertVector(expected: Vector3, actual: Vector3, tolerance: Double = 1e-12) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
        assertEquals(expected.z, actual.z, tolerance)
    }

    @Test
    fun testALevelFrameLooksAlongTheBoresightWithTheTopUp() {
        val frame = assertNotNull(CameraFrame.create(HorizontalCoordinate(0.0, 0.0), 60.0, 40.0))
        assertVector(Vector3(0.0, 1.0, 0.0), frame.boresight)
        assertVector(Vector3(0.0, 0.0, 1.0), frame.up)
        assertVector(Vector3(1.0, 0.0, 0.0), frame.right)
        val east = assertNotNull(CameraFrame.create(HorizontalCoordinate(90.0, 0.0), 60.0, 40.0))
        assertVector(Vector3(1.0, 0.0, 0.0), east.boresight)
        assertVector(Vector3(0.0, -1.0, 0.0), east.right) // looking east, the right-hand side is south
        val raised = assertNotNull(CameraFrame.create(HorizontalCoordinate(0.0, 45.0), 60.0, 40.0))
        assertVector(Vector3(0.0, kotlin.math.sqrt(0.5), kotlin.math.sqrt(0.5)), raised.boresight)
        assertVector(Vector3(0.0, -kotlin.math.sqrt(0.5), kotlin.math.sqrt(0.5)), raised.up)
        assertVector(Vector3(1.0, 0.0, 0.0), raised.right)
        assertEquals(HorizontalCoordinate(0.0, 45.0).altitude, raised.boresightCoordinate.altitude, 1e-9)
    }

    @Test
    fun testPositiveRollTurnsTheCameraClockwise() {
        // Turned 90 degrees clockwise (as the photographer sees it) the picture's top points to the right of the
        // level up, to the east when looking north; the picture's right side then points down.
        val frame = assertNotNull(CameraFrame.create(HorizontalCoordinate(0.0, 0.0), 60.0, 40.0, 90.0))
        assertVector(Vector3(1.0, 0.0, 0.0), frame.up)
        assertVector(Vector3(0.0, 0.0, -1.0), frame.right)
        val tilted = assertNotNull(CameraFrame.create(HorizontalCoordinate(0.0, 0.0), 60.0, 40.0, 30.0))
        assertEquals(30.0, AstroMath.degrees(kotlin.math.atan2(tilted.up.x, tilted.up.z)), 1e-9)
        assertEquals(-30.0, AstroMath.degrees(kotlin.math.atan2(tilted.right.z, tilted.right.x)), 1e-9)
        val reversed = assertNotNull(CameraFrame.create(HorizontalCoordinate(0.0, 0.0), 60.0, 40.0, -30.0))
        assertVector(Vector3(-tilted.up.x, 0.0, tilted.up.z), reversed.up)
        // Axes stay orthonormal.
        for (frame2 in listOf(frame, tilted, reversed)) {
            assertEquals(0.0, frame2.boresight.dot(frame2.up), 1e-12)
            assertEquals(0.0, frame2.boresight.dot(frame2.right), 1e-12)
            assertEquals(0.0, frame2.up.dot(frame2.right), 1e-12)
            assertEquals(1.0, frame2.up.length, 1e-12)
            assertVector(frame2.right, frame2.boresight.cross(frame2.up))
        }
    }

    @Test
    fun testLookingAtTheZenithTheTopIsNorth() {
        val frame = assertNotNull(CameraFrame.create(HorizontalCoordinate(123.0, 90.0), 60.0, 40.0))
        assertVector(Vector3(0.0, 0.0, 1.0), frame.boresight)
        assertVector(Vector3(0.0, 1.0, 0.0), frame.up)
        assertVector(Vector3(-1.0, 0.0, 0.0), frame.right) // looking up with north at the top, west is on the right
        val nadir = assertNotNull(CameraFrame.create(HorizontalCoordinate(0.0, -90.0), 60.0, 40.0))
        assertVector(Vector3(0.0, 1.0, 0.0), nadir.up)
        assertVector(Vector3(1.0, 0.0, 0.0), nadir.right) // looking down like at a map, east is on the right
    }

    @Test
    fun testContainsUsesTheTangentPlane() {
        val frame = assertNotNull(CameraFrame.create(HorizontalCoordinate(90.0, 0.0), 60.0, 40.0)) // looking east
        fun direction(rightDegrees: Double, upDegrees: Double): Vector3 {
            // Gnomonic coordinates tan(right), tan(up) relative to the boresight.
            val x = kotlin.math.tan(rightDegrees * kotlin.math.PI / 180)
            val y = kotlin.math.tan(upDegrees * kotlin.math.PI / 180)
            return frame.boresight + frame.right * x + frame.up * y
        }
        assertTrue(frame.contains(frame.boresight))
        assertTrue(frame.contains(direction(29.9, 19.9)))
        assertFalse(frame.contains(direction(30.1, 0.0)))
        assertFalse(frame.contains(direction(0.0, 20.1)))
        assertFalse(frame.contains(direction(-30.1, 0.0)))
        assertFalse(frame.contains(direction(0.0, -20.1)))
        assertFalse(frame.contains(frame.boresight * -1.0))
        assertFalse(frame.contains(frame.right)) // 90 degrees away
        val position = assertNotNull(frame.position(direction(15.0, -10.0)))
        assertEquals(kotlin.math.tan(15 * kotlin.math.PI / 180) / kotlin.math.tan(30 * kotlin.math.PI / 180), position.x, 1e-12)
        assertEquals(-kotlin.math.tan(10 * kotlin.math.PI / 180) / kotlin.math.tan(20 * kotlin.math.PI / 180), position.y, 1e-12)
        assertNull(frame.position(frame.right))
        assertEquals(AstroMath.degrees(kotlin.math.atan(kotlin.math.sqrt(
            kotlin.math.tan(30 * kotlin.math.PI / 180).let { it * it } + kotlin.math.tan(20 * kotlin.math.PI / 180).let { it * it },
        ))), frame.cornerAngle, 1e-9)
    }

    @Test
    fun testInvalidFramesAreRefused() {
        val look = HorizontalCoordinate(0.0, 0.0)
        for ((width, height) in listOf(0.0 to 10.0, 10.0 to 0.0, -5.0 to 10.0, 171.0 to 10.0, 10.0 to 180.0, Double.NaN to 10.0, 10.0 to Double.POSITIVE_INFINITY)) {
            assertNull(CameraFrame.create(look, width, height), "$width x $height")
        }
        assertNull(CameraFrame.create(look, 10.0, 10.0, Double.NaN))
        assertNotNull(CameraFrame.create(look, 170.0, 170.0))
        assertNotNull(CameraFrame.create(look, 0.01, 0.01))
    }

    @Test
    fun testAFrameFromAMeasuredPointing() {
        // A phone held upright facing north: the camera looks north along the horizon with its top up.
        val matrix = Matrix3.fromColumns(Vector3(0.0, 0.0, -1.0), Vector3(-1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0))
        val attitude = assertNotNull(DeviceAttitude.create(matrix, 0.0, 0.0))
        val solution = PointingSolution.solve(attitude, MountProfile.backCameraPortrait)
        val frame = assertNotNull(CameraFrame.create(solution, 40.0, 30.0))
        assertVector(Vector3(0.0, 1.0, 0.0), frame.boresight, 1e-9)
        assertVector(Vector3(0.0, 0.0, 1.0), frame.up, 1e-9)
        assertVector(Vector3(1.0, 0.0, 0.0), frame.right, 1e-9)
        assertNull(CameraFrame.create(solution, 0.0, 30.0))
    }

    // MARK: Geometry helpers

    @Test
    fun testClippingASegmentAgainstTheFrame() {
        val clipper = SegmentClipper()
        assertTrue(clipper.clip(-2.0, 0.0, 2.0, 0.0))
        assertEquals(0.25, clipper.low, 1e-12)
        assertEquals(0.75, clipper.high, 1e-12)
        assertTrue(clipper.clip(0.5, 0.5, -0.5, -0.5))
        assertEquals(0.0, clipper.low, 1e-12)
        assertEquals(1.0, clipper.high, 1e-12)
        assertFalse(clipper.clip(-2.0, 2.0, 2.0, 1.5)) // passes above
        assertFalse(clipper.clip(2.0, -3.0, 3.0, 3.0)) // passes to the right
        assertTrue(clipper.clip(-1.5, 0.5, 0.5, 2.5)) // clips the upper left corner region: enters at y = 1 only at x > ...
        assertTrue(clipper.low > 0.0 && clipper.high < 1.0)
        assertFalse(clipper.clip(-1.5, 0.9, 0.9, 2.5)) // grazes beyond the corner
        assertTrue(clipper.clip(0.0, 0.0, 0.0, 0.0)) // a point inside
        assertFalse(clipper.clip(3.0, 0.0, 3.0, 0.0)) // a point outside
        assertTrue(clipper.clip(1.0, -3.0, 1.0, 3.0)) // along the right edge
        assertEquals(1.0 / 3.0, clipper.low, 1e-12)
        assertEquals(2.0 / 3.0, clipper.high, 1e-12)
        assertTrue(clipper.clip(-1.0, -1.0, 1.0, 1.0)) // diagonal corner to corner
    }

    @Test
    fun testRestrictingALinearQuantity() {
        val restriction = LinearRestriction()
        assertTrue(restriction.restrict(5.0, 6.0, 4.0))
        assertEquals(0.0, restriction.low, 0.0)
        assertEquals(1.0, restriction.high, 0.0)
        assertFalse(restriction.restrict(1.0, 2.0, 4.0))
        assertTrue(restriction.restrict(1.0, 5.0, 3.0)) // rises through the limit half way
        assertEquals(0.5, restriction.low, 1e-12)
        assertEquals(1.0, restriction.high, 0.0)
        assertTrue(restriction.restrict(5.0, 1.0, 4.0)) // falls through it a quarter of the way
        assertEquals(0.0, restriction.low, 0.0)
        assertEquals(0.25, restriction.high, 1e-12)
    }

    // MARK: Assessments

    private val epoch: Instant = Instant.parse("2026-10-18T00:00:00Z")
    private val equator = observer(0.0, 30.0)

    /** A geostationary satellite 30 degrees east of Greenwich as seen from (0, 30): at the zenith. */
    private fun geostationarySatellite(intrinsic: Double? = null): Satellite =
        Satellite(geostationary(30.0, epoch), intrinsic)

    private fun exposure(seconds: Double): DateInterval = DateInterval(epoch.addingTimeInterval(7200.0), seconds)

    @Test
    fun testASatelliteThatSitsInTheFrameStreaksTheWholeExposure() {
        val satellite = geostationarySatellite()
        val time = epoch.addingTimeInterval(7200.0)
        val look = assertNotNull(satellite.look(time, equator))
        assertTrue(look.isSunlit, "the test needs a lit satellite")
        val frame = assertNotNull(CameraFrame.create(look.horizontal, 4.0, 3.0))
        val assessment = StreakRisk.assess(listOf(satellite), equator, frame, exposure(30.0))
        val crossing = assessment.crossings.single()
        assertTrue(crossing.startsBeforeExposure && crossing.endsAfterExposure)
        assertEquals(time, crossing.entry.date)
        assertEquals(30.0, crossing.duration, 1e-6)
        val position = assertNotNull(crossing.entry.position)
        assertTrue(abs(position.x) < 0.05 && abs(position.y) < 0.05, "centre of the frame, got $position")
        assertEquals(1, assessment.evaluatedCount)
        assertEquals(1, assessment.aboveHorizonCount)
        assertEquals(1, assessment.sunlitAboveSummaryAltitudeCount)
        assertEquals(1.0, assessment.crossingsPerMinute * assessment.rateWindow / 60.0, 1e-9)
        assertEquals(0.5, assessment.crossingsPerMinute, 1e-12) // one crossing in the default window of 120 s
        assertNull(crossing.magnitude)
    }

    @Test
    fun testAFrameThatMissesTheSatelliteSeesNothing() {
        val satellite = geostationarySatellite()
        val look = assertNotNull(satellite.look(epoch.addingTimeInterval(7200.0), equator))
        val away = HorizontalCoordinate(look.azimuth + 90.0, look.altitude - 15.0)
        val frame = assertNotNull(CameraFrame.create(away, 4.0, 3.0))
        val assessment = StreakRisk.assess(listOf(satellite), equator, frame, exposure(30.0))
        assertTrue(assessment.crossings.isEmpty())
        assertEquals(0.0, assessment.crossingsPerMinute, 0.0)
        assertEquals(1, assessment.sunlitAboveSummaryAltitudeCount)
        // A frame that just fits the satellite.
        val tight = assertNotNull(CameraFrame.create(look.horizontal, 0.02, 0.02))
        assertEquals(1, StreakRisk.assess(listOf(satellite), equator, tight, exposure(30.0)).crossings.size)
    }

    @Test
    fun testTheHorizonAndTheMagnitudeLimitFilterCrossings() {
        val bright = geostationarySatellite(intrinsic = -1.8)
        val look = assertNotNull(bright.look(epoch.addingTimeInterval(7200.0), equator))
        val frame = assertNotNull(CameraFrame.create(look.horizontal, 4.0, 3.0))
        val unfiltered = StreakRisk.assess(listOf(bright), equator, frame, exposure(30.0))
        val crossing = unfiltered.crossings.single()
        val magnitude = assertNotNull(crossing.magnitude)
        assertTrue(magnitude in 4.5..6.5, "a geostationary satellite 36 000 km away is faint, magnitude $magnitude")
        // Dropped by a limiting magnitude brighter than it; kept by a fainter one; satellites without a known
        // magnitude are never dropped.
        assertTrue(StreakRisk.assess(listOf(bright), equator, frame, exposure(30.0), StreakOptions(limitingMagnitude = 3.0)).crossings.isEmpty())
        assertEquals(1, StreakRisk.assess(listOf(bright), equator, frame, exposure(30.0), StreakOptions(limitingMagnitude = 20.0)).crossings.size)
        assertEquals(1, StreakRisk.assess(listOf(geostationarySatellite()), equator, frame, exposure(30.0), StreakOptions(limitingMagnitude = 3.0)).crossings.size)
        // Streaks only count above the minimum altitude (the satellite stands at the zenith).
        assertEquals(1, StreakRisk.assess(listOf(bright), equator, frame, exposure(30.0), StreakOptions(minimumAltitude = 89.0)).crossings.size)
        assertTrue(StreakRisk.assess(listOf(bright), equator, frame, exposure(30.0), StreakOptions(minimumAltitude = 89.99)).crossings.isEmpty())
        assertEquals(1, StreakRisk.assess(listOf(bright), equator, frame, exposure(30.0), StreakOptions(summaryAltitude = 85.0)).sunlitAboveSummaryAltitudeCount)
        assertEquals(0, StreakRisk.assess(listOf(bright), equator, frame, exposure(30.0), StreakOptions(summaryAltitude = 89.99)).sunlitAboveSummaryAltitudeCount)
    }

    @Test
    fun testAnEmptyListAndAnUnpropagatableSatellite() {
        val frame = assertNotNull(CameraFrame.create(HorizontalCoordinate(0.0, 45.0), 60.0, 40.0))
        val empty = StreakRisk.assess(emptyList(), equator, frame, exposure(30.0))
        assertTrue(empty.crossings.isEmpty())
        assertEquals(0, empty.evaluatedCount)
        assertEquals(0.0, empty.crossingsPerMinute, 0.0)
        // The ISS element set is 8 days old by 2026-10-26: still propagates; 40 years later it does not.
        val iss = referenceSatellites().getValue("iss")
        val far = DateInterval(Instant.parse("2070-01-01T00:00:00Z"), 30.0)
        val result = StreakRisk.assess(listOf(iss), equator, frame, far)
        assertEquals(0, result.evaluatedCount)
        assertTrue(result.crossings.isEmpty())
    }

    @Test
    fun testStreakProbabilityFollowsAPoissonProcess() {
        val assessment = StreakAssessment(emptyList(), 100, 20, 5, crossingsPerMinute = 2.0, rateWindow = 120.0)
        assertEquals(1.0 - exp(-2.0), assessment.probabilityOfStreak(60.0), 1e-12)
        assertEquals(1.0 - exp(-1.0), assessment.probabilityOfStreak(30.0), 1e-12)
        assertEquals(0.0, assessment.probabilityOfStreak(0.0), 0.0)
        assertEquals(0.0, assessment.probabilityOfStreak(Double.NaN), 0.0)
        assertEquals(0.0, assessment.probabilityOfStreak(-5.0), 0.0)
    }

    @Test
    fun testAssessmentLimits() {
        val frame = assertNotNull(CameraFrame.create(HorizontalCoordinate(0.0, 45.0), 60.0, 40.0))
        val satellite = geostationarySatellite()
        // A negative or NaN duration is an exposure of zero seconds; the rate window is clamped.
        val zero = StreakRisk.assess(listOf(satellite), equator, frame, DateInterval(epoch.addingTimeInterval(7200.0), 0.0), StreakOptions(rateWindow = Double.NaN))
        assertEquals(120.0, zero.rateWindow, 0.0)
        val long = StreakRisk.assess(listOf(satellite), equator, frame, exposure(10_000.0), StreakOptions(rateWindow = 0.0))
        assertEquals(1.0, long.rateWindow, 0.0)
        assertEquals(1, long.evaluatedCount)
    }
}

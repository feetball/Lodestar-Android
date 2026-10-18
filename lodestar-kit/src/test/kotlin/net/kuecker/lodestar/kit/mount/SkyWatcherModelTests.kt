package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.CoordinateTransforms
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Vector3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests of the mount geometry ([SkyWatcherPointingModel]) and of the sky conversions. The geometry is checked against the
 * foundation's hour angle / horizontal transforms (home position: counterweight down and telescope at the pole; level and north) and
 * against hand-derived axis angles.
 */
class SkyWatcherModelTests {
    private val tolerance = 1e-9

    private fun assertVector(expected: Vector3, actual: Vector3, tolerance: Double = 1e-9, message: String = "") {
        assertEquals(expected.x, actual.x, tolerance, "$message x $actual")
        assertEquals(expected.y, actual.y, tolerance, "$message y $actual")
        assertEquals(expected.z, actual.z, tolerance, "$message z $actual")
    }

    private val north = SkyWatcherPointingModel.nominal(SkyWatcherMountMode.EQUATORIAL, false)
    private val south = SkyWatcherPointingModel.nominal(SkyWatcherMountMode.EQUATORIAL, true)
    private val altAz = SkyWatcherPointingModel.nominal(SkyWatcherMountMode.ALT_AZIMUTH, false)

    // MARK: Equatorial geometry

    @Test
    fun testEquatorialNamedPositions() {
        // Home: counterweight down, telescope at the pole.
        assertVector(Vector3(0.0, 0.0, 1.0), north.direction(0.0, 0.0))
        // Declination axis turned 90°: the telescope swings out to the horizon, west for a positive angle (the T position).
        assertVector(Vector3(0.0, -1.0, 0.0), north.direction(0.0, 90.0))
        assertVector(Vector3(0.0, 1.0, 0.0), north.direction(0.0, -90.0))
        // Right ascension axis turned a quarter: the telescope on the meridian at the equator (counterweight horizontal).
        assertVector(Vector3(1.0, 0.0, 0.0), north.direction(-90.0, 90.0))
        assertVector(Vector3(1.0, 0.0, 0.0), north.direction(90.0, -90.0))
        assertVector(Vector3(-1.0, 0.0, 0.0), north.direction(90.0, 90.0))
        // Declination axis turned half way round: towards the opposite pole.
        assertVector(Vector3(0.0, 0.0, -1.0), north.direction(33.0, 180.0))
        assertVector(Vector3(0.0, 0.0, -1.0), north.direction(-33.0, -180.0))
    }

    @Test
    fun testEquatorialAnglesMatchHourAngleAndDeclination() {
        // Pier side east (declination axis angle 0° to 180°): hour angle = axis1 + 90°, declination = 90° - axis2.
        for (axis1 in listOf(-85.0, -30.0, 0.0, 45.0, 80.0)) {
            for (axis2 in listOf(10.0, 60.0, 90.0, 150.0)) {
                val expected = CoordinateTransforms.localEquatorialVector(axis1 + 90.0, 90.0 - axis2)
                assertVector(expected, north.direction(axis1, axis2), message = "east $axis1 $axis2")
            }
        }
        // Pier side west (angle -180° to 0°): hour angle = axis1 - 90°, declination = 90° + axis2.
        for (axis1 in listOf(-80.0, -45.0, 0.0, 30.0, 85.0)) {
            for (axis2 in listOf(-10.0, -60.0, -90.0, -150.0)) {
                val expected = CoordinateTransforms.localEquatorialVector(axis1 - 90.0, 90.0 + axis2)
                assertVector(expected, north.direction(axis1, axis2), message = "west $axis1 $axis2")
            }
        }
    }

    @Test
    fun testEquatorialCounterweightIsDownBetweenTheMeridianLimits() {
        // For a target west of the meridian the east side keeps the counterweight down (|axis1| < 90°); east of the meridian the west side.
        val west = CoordinateTransforms.localEquatorialVector(60.0, 20.0)
        val byWest = north.solutions(west)
        val normal = byWest.first { it.pierSide == SkyWatcherPierSide.EAST }
        val through = byWest.first { it.pierSide == SkyWatcherPierSide.WEST }
        assertEquals(-30.0, normal.axis1, 1e-9)
        assertEquals(70.0, normal.axis2, 1e-9)
        assertEquals(150.0, through.axis1, 1e-9)
        assertEquals(-70.0, through.axis2, 1e-9)
        val east = CoordinateTransforms.localEquatorialVector(-60.0, 20.0)
        val byEast = north.solutions(east)
        val eastNormal = byEast.first { it.pierSide == SkyWatcherPierSide.EAST }
        val eastThrough = byEast.first { it.pierSide == SkyWatcherPierSide.WEST }
        assertEquals(-150.0, eastNormal.axis1, 1e-9)
        assertEquals(70.0, eastNormal.axis2, 1e-9)
        assertEquals(30.0, eastThrough.axis1, 1e-9)
        assertEquals(-70.0, eastThrough.axis2, 1e-9)
        // The counterweight hangs below the horizontal exactly when the angle of axis 1 is within 90° of home.
        assertTrue(abs(normal.axis1) < 90 && abs(through.axis1) > 90)
        assertTrue(abs(eastThrough.axis1) < 90 && abs(eastNormal.axis1) > 90)
    }

    @Test
    fun testSouthernHemisphereIsTheMirrorImage() {
        // Home in the south: the telescope points at the south celestial pole.
        assertVector(Vector3(0.0, 0.0, -1.0), south.direction(0.0, 0.0))
        for (axis1 in listOf(-70.0, 0.0, 55.0)) {
            for (axis2 in listOf(-120.0, -30.0, 20.0, 100.0)) {
                val mirrored = north.direction(axis1, axis2)
                assertVector(Vector3(mirrored.x, -mirrored.y, -mirrored.z), south.direction(axis1, axis2), message = "$axis1 $axis2")
            }
        }
        // The counterweight rule is the same: a target at hour angle +60° and declination -20° needs the normal side.
        val target = CoordinateTransforms.localEquatorialVector(60.0, -20.0)
        val solutions = south.solutions(target)
        for (solution in solutions) assertVector(target, south.direction(solution.axis1, solution.axis2), message = "south")
        // In the mirrored mount the hour angle of the sky runs the other way: hour angle +60° looks like -60° (declination +20°).
        val normal = solutions.first { it.pierSide == SkyWatcherPierSide.EAST }
        val expected = north.solutions(CoordinateTransforms.localEquatorialVector(-60.0, 20.0)).first { it.pierSide == SkyWatcherPierSide.EAST }
        assertEquals(expected.axis1, normal.axis1, 1e-9)
        assertEquals(expected.axis2, normal.axis2, 1e-9)
    }

    // MARK: Alt-azimuth geometry

    @Test
    fun testAltAzimuthNamedPositions() {
        assertVector(Vector3(0.0, 1.0, 0.0), altAz.direction(0.0, 0.0))
        assertVector(Vector3(1.0, 0.0, 0.0), altAz.direction(90.0, 0.0))
        assertVector(Vector3(0.0, -1.0, 0.0), altAz.direction(180.0, 0.0))
        assertVector(Vector3(-1.0, 0.0, 0.0), altAz.direction(-90.0, 0.0))
        assertVector(Vector3(0.0, 0.0, 1.0), altAz.direction(123.0, 90.0))
        for (azimuth in listOf(0.0, 33.0, 120.0, 200.0, 359.0)) {
            for (altitude in listOf(-10.0, 0.0, 25.0, 60.0, 89.0)) {
                assertVector(HorizontalCoordinate(azimuth, altitude).unitVector, altAz.direction(azimuth, altitude), message = "$azimuth $altitude")
            }
        }
    }

    @Test
    fun testAltAzimuthSolutionsAreNormalAndOverTheTop() {
        val target = HorizontalCoordinate(120.0, 30.0).unitVector
        val solutions = altAz.solutions(target)
        val normal = solutions.first { it.pierSide == SkyWatcherPierSide.EAST }
        val over = solutions.first { it.pierSide == SkyWatcherPierSide.WEST }
        assertEquals(120.0, normal.axis1, 1e-9)
        assertEquals(30.0, normal.axis2, 1e-9)
        assertEquals(-60.0, over.axis1, 1e-9)
        assertEquals(150.0, over.axis2, 1e-9)
    }

    // MARK: Errors

    @Test
    fun testErrorsAreAppliedAsDocumented() {
        // An index error shifts the encoder angles.
        val shifted = SkyWatcherPointingModel(SkyWatcherMountMode.ALT_AZIMUTH, false, SkyWatcherPointingErrors(axis1Index = 3.0, axis2Index = -2.0))
        assertVector(altAz.direction(33.0, 28.0), shifted.direction(30.0, 30.0))
        // The solutions undo it.
        val target = HorizontalCoordinate(120.0, 30.0).unitVector
        val solution = shifted.solutions(target).first { it.pierSide == SkyWatcherPierSide.EAST }
        assertEquals(117.0, solution.axis1, 1e-9)
        assertEquals(32.0, solution.axis2, 1e-9)
        // Cone error: the optical axis leaves the plane perpendicular to the altitude axis.
        val cone = SkyWatcherPointingModel(SkyWatcherMountMode.ALT_AZIMUTH, false, SkyWatcherPointingErrors(cone = 0.5))
        assertVector(Vector3(AstroMath.sinDeg(0.5), AstroMath.cosDeg(0.5), 0.0), cone.direction(0.0, 0.0))
        // Orthogonality error: the altitude axis is not horizontal, so pointing at the zenith side tilts towards x.
        val skew = SkyWatcherPointingModel(SkyWatcherMountMode.ALT_AZIMUTH, false, SkyWatcherPointingErrors(orthogonality = 0.5))
        assertVector(Vector3(AstroMath.sinDeg(0.5), 0.0, AstroMath.cosDeg(0.5)), skew.direction(0.0, 90.0))
        // Base tilt about y raises the east side: pointing east now has an altitude.
        val tilted = SkyWatcherPointingModel(SkyWatcherMountMode.ALT_AZIMUTH, false, SkyWatcherPointingErrors(baseTiltY = 1.0))
        assertEquals(-1.0, tilted.direction(90.0, 0.0).let { AstroMath.asinDeg(it.z) }, 1e-9)
        assertEquals(0.0, tilted.direction(0.0, 0.0).let { AstroMath.asinDeg(it.z) }, 1e-9)
        // Base tilt about x lifts the north side.
        val lifted = SkyWatcherPointingModel(SkyWatcherMountMode.ALT_AZIMUTH, false, SkyWatcherPointingErrors(baseTiltX = 1.0))
        assertEquals(1.0, lifted.direction(0.0, 0.0).let { AstroMath.asinDeg(it.z) }, 1e-9)
        assertEquals(0.0, lifted.direction(90.0, 0.0).let { AstroMath.asinDeg(it.z) }, 1e-9)
    }

    @Test
    fun testPolarAxisErrorsOfAnEquatorialMount() {
        val latitude = 40.0
        val sinLatitude = AstroMath.sinDeg(latitude)
        val cosLatitude = AstroMath.cosDeg(latitude)
        // The polar axis is the direction the telescope points at when axis 2 is 0 (whatever axis 1 says).
        fun poleAltitude(errors: SkyWatcherPointingErrors): Double {
            val axis = SkyWatcherPointingModel(SkyWatcherMountMode.EQUATORIAL, false, errors).direction(37.0, 0.0)
            return AstroMath.asinDeg(axis.x * cosLatitude + axis.z * sinLatitude)
        }
        fun poleAzimuth(errors: SkyWatcherPointingErrors): Double {
            val axis = SkyWatcherPointingModel(SkyWatcherMountMode.EQUATORIAL, false, errors).direction(37.0, 0.0)
            val northComponent = -axis.x * sinLatitude + axis.z * cosLatitude
            return AstroMath.atan2Deg(axis.y, northComponent)
        }
        val high = SkyWatcherPointingErrors(baseTiltY = 0.5)
        assertEquals(latitude + 0.5, poleAltitude(high), 1e-3)
        assertEquals(0.5, high.polarAxisAltitudeError, 0.0)
        val west = SkyWatcherPointingErrors(baseTiltX = 0.4)
        assertEquals(-0.4 / AstroMath.cosDeg(latitude), poleAzimuth(west), 2e-3)
        assertEquals(-0.4 / AstroMath.cosDeg(latitude), west.polarAxisAzimuthError(latitude), 1e-12)
        assertEquals(0.0, west.polarAxisAzimuthError(90.0), 0.0)
        assertEquals(latitude, poleAltitude(SkyWatcherPointingErrors()), 1e-9)
    }

    @Test
    fun testSolutionsRoundTripWithRandomErrors() {
        val random = SkyWatcherSimulatedRandom(2026)
        for (index in 0 until 600) {
            val mode = if (index % 2 == 0) SkyWatcherMountMode.EQUATORIAL else SkyWatcherMountMode.ALT_AZIMUTH
            val southern = (index / 2) % 2 == 1
            val errors = SkyWatcherPointingErrors(
                (random.next() - 0.5) * 20, (random.next() - 0.5) * 20, (random.next() - 0.5) * 4,
                (random.next() - 0.5) * 4, (random.next() - 0.5) * 2, (random.next() - 0.5) * 2,
            )
            val model = SkyWatcherPointingModel(mode, southern, errors)
            val longitude = random.next() * 360
            val latitude = AstroMath.asinDeg(random.next() * 2 - 1)
            val target = EquatorialCoordinate(longitude, latitude).unitVector
            val solutions = model.solutions(target, 0.0)
            assertEquals(2, solutions.size)
            for (solution in solutions) {
                val pointed = model.direction(solution.axis1, solution.axis2)
                assertTrue(pointed.angle(target) < 1e-7, "$index $mode $southern ${pointed.angle(target)}")
                assertTrue(abs(solution.axis1) <= 180.0 && abs(solution.axis2) <= 180.0)
            }
        }
    }

    @Test
    fun testPolesOfThePrimaryAxis() {
        // At the pole axis 1 is undefined: both solutions use the preferred angle.
        val solutions = north.solutions(Vector3(0.0, 0.0, 1.0), preferredAxis1Degrees = 25.0)
        for (solution in solutions) {
            assertEquals(25.0, solution.axis1, 1e-6)
            assertEquals(0.0, solution.axis2, 1e-6)
        }
        val zenith = altAz.solutions(Vector3(0.0, 0.0, 1.0), preferredAxis1Degrees = -40.0)
        assertEquals(-40.0, zenith.first { it.pierSide == SkyWatcherPierSide.EAST }.axis1, 1e-6)
        assertEquals(90.0, zenith.first { it.pierSide == SkyWatcherPierSide.EAST }.axis2, 1e-6)
    }

    @Test
    fun testPierSideFollowsTheModelAngle() {
        assertEquals(SkyWatcherPierSide.EAST, north.pierSide(3.0))
        assertEquals(SkyWatcherPierSide.EAST, north.pierSide(0.0))
        assertEquals(SkyWatcherPierSide.WEST, north.pierSide(-0.001))
        val shifted = SkyWatcherPointingModel(SkyWatcherMountMode.EQUATORIAL, false, SkyWatcherPointingErrors(axis1Index = 4.0, axis2Index = -5.0))
        assertEquals(SkyWatcherPierSide.WEST, shifted.pierSide(3.0))
        assertEquals(SkyWatcherPierSide.EAST, shifted.pierSide(6.0))
        assertEquals(94.0, shifted.modelAxis1(90.0), 1e-12)
        assertEquals(-176.0, shifted.modelAxis1(180.0), 1e-12)
        assertEquals(SkyWatcherPierSide.EAST, altAz.pierSide(89.0))
        assertEquals(SkyWatcherPierSide.WEST, altAz.pierSide(91.0))
        assertEquals(SkyWatcherPierSide.WEST, altAz.pierSide(-120.0))
    }

    // MARK: Sky conversions

    @Test
    fun testSkyConversionsFollowTheHourAngle() {
        val sidereal = 123.0
        // On the meridian: hour angle 0.
        assertVector(Vector3(AstroMath.cosDeg(30.0), 0.0, AstroMath.sinDeg(30.0)),
            SkyWatcherSky.localDirection(EquatorialCoordinate(sidereal, 30.0), sidereal, 40.0, SkyWatcherMountMode.EQUATORIAL))
        // West of the meridian (hour angle +90°, right ascension 90° less than the sidereal time): y = -1.
        assertVector(Vector3(0.0, -1.0, 0.0),
            SkyWatcherSky.localDirection(EquatorialCoordinate(sidereal - 90.0, 0.0), sidereal, 40.0, SkyWatcherMountMode.EQUATORIAL))
        // The same place in the horizon frame.
        val place = EquatorialCoordinate(200.0, 25.0)
        assertVector(CoordinateTransforms.equatorialToHorizontal(AstroMath.normalizeSignedDegrees(sidereal - 200.0), 25.0, 40.0).unitVector,
            SkyWatcherSky.localDirection(place, sidereal, 40.0, SkyWatcherMountMode.ALT_AZIMUTH))
        assertEquals(CoordinateTransforms.equatorialToHorizontal(AstroMath.normalizeSignedDegrees(sidereal - 200.0), 25.0, 40.0).altitude,
            SkyWatcherSky.altitude(place, sidereal, 40.0), 1e-12)
        assertEquals(0.0041780746, SkyWatcherSky.siderealDegreesPerSecond, 1e-9)
    }

    @Test
    fun testSkyConversionsRoundTrip() {
        val random = SkyWatcherSimulatedRandom(7)
        for (index in 0 until 400) {
            val mode = if (index % 2 == 0) SkyWatcherMountMode.EQUATORIAL else SkyWatcherMountMode.ALT_AZIMUTH
            val latitude = (random.next() - 0.5) * 170
            val sidereal = random.next() * 360
            val place = EquatorialCoordinate(random.next() * 360, AstroMath.asinDeg(random.next() * 1.96 - 0.98))
            val direction = SkyWatcherSky.localDirection(place, sidereal, latitude, mode)
            assertEquals(1.0, direction.length, 1e-12)
            val back = SkyWatcherSky.apparentPlace(direction, sidereal, latitude, mode)
            assertTrue(back.angularSeparation(place) < 1e-9, "$index ${back.angularSeparation(place)}")
        }
    }

    // MARK: Alignment

    private data class Star(val first: Double, val second: Double)

    private fun directionOf(star: Star, mode: SkyWatcherMountMode): Vector3 =
        if (mode == SkyWatcherMountMode.EQUATORIAL) {
            CoordinateTransforms.localEquatorialVector(star.first, star.second)
        } else {
            HorizontalCoordinate(star.first, star.second).unitVector
        }

    /** What the encoders read when the real mount (the model [truth]) points at [direction], on the side that keeps the counterweight down. */
    private fun observe(truth: SkyWatcherPointingModel, direction: Vector3): SkyWatcherAlignmentPoint {
        val solutions = truth.solutions(direction, 0.0)
        val usable = if (truth.mode == SkyWatcherMountMode.EQUATORIAL) {
            solutions.filter { abs(truth.modelAxis1(it.axis1)) <= 95.0 }.minByOrNull { abs(truth.modelAxis1(it.axis1)) }
        } else {
            solutions.firstOrNull { it.pierSide == SkyWatcherPierSide.EAST }
        }
        val solution = usable ?: solutions[0]
        return SkyWatcherAlignmentPoint(solution.axis1, solution.axis2, direction, SkyWatcherTestSupport.start)
    }

    private val equatorialStars = listOf(Star(60.0, 40.0), Star(-70.0, 50.0), Star(15.0, 10.0), Star(-20.0, -10.0), Star(-100.0, 20.0))
    private val altAzimuthStars = listOf(Star(290.0, 43.0), Star(54.0, 41.0), Star(208.0, 57.0), Star(120.0, 30.0), Star(10.0, 65.0))

    private fun stars(mode: SkyWatcherMountMode) = if (mode == SkyWatcherMountMode.EQUATORIAL) equatorialStars else altAzimuthStars

    @Test
    fun testPredictedErrorShrinksWithMoreStars() {
        for (mode in SkyWatcherMountMode.entries) {
            val errors = if (mode == SkyWatcherMountMode.EQUATORIAL) SkyWatcherTestSupport.equatorialErrors else SkyWatcherTestSupport.altAzimuthErrors
            val truth = SkyWatcherPointingModel(mode, false, errors)
            val predicted = (1..4).map { count ->
                SkyWatcherAlignmentSolver.solve(stars(mode).take(count).map { observe(truth, directionOf(it, mode)) }, mode, false).predictedErrorDegrees
            }
            // One star leaves about a degree (the priors), more stars bring it down.
            assertTrue(predicted[0] > 0.3 && predicted[0] < 3.0, "$mode $predicted")
            assertTrue(predicted[1] < predicted[0], "$mode $predicted")
            assertTrue(predicted[2] < predicted[1], "$mode $predicted")
            assertTrue(predicted[3] < predicted[2], "$mode $predicted")
            assertTrue(predicted[3] < 0.3, "$mode $predicted")
        }
        assertEquals(8, SkyWatcherAlignmentSolver.maximumPoints)
        assertEquals(0.1, SkyWatcherAlignmentPriors.standard.measurementSigmaDegrees, 0.0)
    }

    @Test
    fun testOneStarFindsTheIndexOffsetsExactly() {
        for (mode in SkyWatcherMountMode.entries) {
            for (southern in listOf(false, true)) {
                if (mode == SkyWatcherMountMode.ALT_AZIMUTH && southern) continue
                val truth = SkyWatcherPointingModel(mode, southern, SkyWatcherPointingErrors(axis1Index = 3.7, axis2Index = -2.4))
                for (star in stars(mode)) {
                    val point = observe(truth, directionOf(star, mode))
                    val fit = SkyWatcherAlignmentSolver.solve(listOf(point), mode, southern)
                    assertEquals(3.7, fit.model.errors.axis1Index, 1e-5, "$mode $southern $star")
                    assertEquals(-2.4, fit.model.errors.axis2Index, 1e-5, "$mode $southern $star")
                    assertEquals(0.0, fit.model.errors.baseTiltX, 1e-6)
                    assertEquals(0.0, fit.model.errors.baseTiltY, 1e-6)
                    assertEquals(0.0, fit.model.errors.orthogonality, 1e-6)
                    assertEquals(0.0, fit.model.errors.cone, 1e-6)
                    assertTrue(fit.maximumResidualDegrees < 1e-6)
                    assertEquals(1, fit.residualsDegrees.size)
                }
            }
        }
    }

    @Test
    fun testTwoStarsFindTheBaseTilt() {
        for (mode in SkyWatcherMountMode.entries) {
            val truth = SkyWatcherPointingModel(mode, false, SkyWatcherPointingErrors(axis1Index = 2.0, axis2Index = 1.1, baseTiltX = 0.5, baseTiltY = -0.4))
            val points = listOf(0, 1).map { observe(truth, directionOf(stars(mode)[it], mode)) }
            val fit = SkyWatcherAlignmentSolver.solve(points, mode, false)
            assertEquals(2.0, fit.model.errors.axis1Index, 0.5, "$mode")
            assertEquals(1.1, fit.model.errors.axis2Index, 0.5, "$mode")
            assertEquals(0.5, fit.model.errors.baseTiltX, 0.5, "$mode")
            assertEquals(-0.4, fit.model.errors.baseTiltY, 0.5, "$mode")
            assertTrue(fit.maximumResidualDegrees < 0.1, "$mode ${fit.residualsDegrees}")
            // Two stars cannot pin the tilt down completely (the prior shrinks it), but the model predicts the other stars far better
            // than the index offsets of one star alone.
            val oneStar = SkyWatcherAlignmentSolver.solve(points.take(1), mode, false)
            var worstTwo = 0.0
            var worstOne = 0.0
            for (other in stars(mode).drop(2)) {
                val direction = directionOf(other, mode)
                val real = observe(truth, direction)
                worstTwo = maxOf(worstTwo, fit.model.direction(real.axis1Degrees, real.axis2Degrees).angle(direction))
                worstOne = maxOf(worstOne, oneStar.model.direction(real.axis1Degrees, real.axis2Degrees).angle(direction))
            }
            assertTrue(worstTwo < 0.6, "$mode $worstTwo")
            assertTrue(worstTwo < worstOne, "$mode two stars $worstTwo, one star $worstOne")
        }
    }

    @Test
    fun testThreeStarsRecoverAllSixErrors() {
        for (mode in SkyWatcherMountMode.entries) {
            val errors = if (mode == SkyWatcherMountMode.EQUATORIAL) SkyWatcherTestSupport.equatorialErrors else SkyWatcherTestSupport.altAzimuthErrors
            val truth = SkyWatcherPointingModel(mode, false, errors)
            val points = stars(mode).take(3).map { observe(truth, directionOf(it, mode)) }
            val fit = SkyWatcherAlignmentSolver.solve(points, mode, false)
            // Index offsets and tilts are well determined; orthogonality and cone only weakly (the prior shrinks them).
            assertEquals(errors.axis1Index, fit.model.errors.axis1Index, 0.1, "$mode")
            assertEquals(errors.axis2Index, fit.model.errors.axis2Index, 0.1, "$mode")
            assertEquals(errors.baseTiltX, fit.model.errors.baseTiltX, 0.1, "$mode")
            assertEquals(errors.baseTiltY, fit.model.errors.baseTiltY, 0.1, "$mode")
            assertEquals(errors.orthogonality, fit.model.errors.orthogonality, 0.15, "$mode")
            assertEquals(errors.cone, fit.model.errors.cone, 0.15, "$mode")
            assertTrue(fit.isPlausible)
            assertTrue(fit.maximumResidualDegrees < 0.05, "$mode ${fit.residualsDegrees}")
            assertTrue(fit.rmsDegrees <= fit.maximumResidualDegrees)
            // The fitted model points at the two stars that were not used to within a fifth of a degree, although both are outside the
            // region the three stars span (extrapolation is the weak spot).
            for (other in stars(mode).drop(3)) {
                val direction = directionOf(other, mode)
                val real = observe(truth, direction)
                val predicted = fit.model.direction(real.axis1Degrees, real.axis2Degrees)
                assertTrue(predicted.angle(direction) < 0.2, "$mode $other ${predicted.angle(direction)}")
            }
        }
    }

    @Test
    fun testSouthernHemisphereAlignment() {
        val truth = SkyWatcherPointingModel(SkyWatcherMountMode.EQUATORIAL, true, SkyWatcherTestSupport.equatorialErrors)
        val points = equatorialStars.take(3).map { observe(truth, directionOf(it, SkyWatcherMountMode.EQUATORIAL)) }
        val fit = SkyWatcherAlignmentSolver.solve(points, SkyWatcherMountMode.EQUATORIAL, true)
        assertEquals(SkyWatcherTestSupport.equatorialErrors.axis1Index, fit.model.errors.axis1Index, 0.1)
        assertEquals(SkyWatcherTestSupport.equatorialErrors.baseTiltY, fit.model.errors.baseTiltY, 0.15)
        assertTrue(fit.maximumResidualDegrees < 0.05)
        assertTrue(fit.model.isSouthernHemisphere)
    }

    @Test
    fun testResidualsExposeAWrongStar() {
        for (mode in SkyWatcherMountMode.entries) {
            val truth = SkyWatcherPointingModel(mode, false, SkyWatcherPointingErrors(axis1Index = 2.0, axis2Index = 1.0))
            val good = stars(mode).take(2).map { observe(truth, directionOf(it, mode)) }
            // The user centred another star than the one the app says: 20° off.
            val third = stars(mode)[2]
            val wrongDirection = directionOf(Star(third.first + 20.0, third.second - 8.0), mode)
            val real = observe(truth, directionOf(third, mode))
            val wrong = SkyWatcherAlignmentPoint(real.axis1Degrees, real.axis2Degrees, wrongDirection, real.timestamp)
            // Free errors can explain a lot, but not without becoming larger than a hand-made set-up can have.
            val fit = SkyWatcherAlignmentSolver.solve(good + wrong, mode, false)
            assertTrue(fit.maximumResidualDegrees > 0.5 || !fit.isPlausible, "$mode ${fit.residualsDegrees} ${fit.model.errors}")
            val twoStars = SkyWatcherAlignmentSolver.solve(listOf(good[0], wrong), mode, false)
            assertTrue(twoStars.maximumResidualDegrees > 0.5 || !twoStars.isPlausible, "$mode ${twoStars.residualsDegrees} ${twoStars.model.errors}")
        }
    }

    @Test
    fun testAlignmentInputsAreValidated() {
        val point = SkyWatcherAlignmentPoint(10.0, 20.0, Vector3(0.0, 1.0, 0.0), SkyWatcherTestSupport.start)
        for (bad in listOf(
            emptyList(),
            listOf(point.copy(axis1Degrees = Double.NaN)),
            listOf(point.copy(axis2Degrees = Double.POSITIVE_INFINITY)),
            listOf(point.copy(direction = Vector3(Double.NaN, 0.0, 0.0))),
            listOf(point.copy(direction = Vector3(0.0, 0.0, 0.0))),
            List(9) { point },
        )) {
            assertFailsWith<MountError.InvalidInput> { SkyWatcherAlignmentSolver.solve(bad, SkyWatcherMountMode.ALT_AZIMUTH, false) }
        }
        // A direction that is not a unit vector is normalised.
        val fit = SkyWatcherAlignmentSolver.solve(listOf(point.copy(direction = Vector3(0.0, 5.0, 0.0))), SkyWatcherMountMode.ALT_AZIMUTH, false)
        assertTrue(fit.maximumResidualDegrees < 1e-6)
    }
}

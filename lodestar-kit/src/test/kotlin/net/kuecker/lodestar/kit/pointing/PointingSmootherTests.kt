package net.kuecker.lodestar.kit.pointing

import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.pointing.PointingTestSupport as Support
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PointingSmootherTests {
    private val axis = Vector3(0.3, -0.5, 0.8)
    private val base = Matrix3.rotation(Vector3(1.0, 2.0, 3.0), 0.9)

    /**
     * The base orientation turned by `degrees` about a fixed axis, so the angle between two such
     * attitudes is the difference of their parameters.
     */
    private fun attitude(degrees: Double, at: Double, referenceAzimuth: Double = 0.0): DeviceAttitude =
        assertNotNull(
            DeviceAttitude.create(
                Matrix3.rotation(axis, AstroMath.radians(degrees)) * base, referenceAzimuth, at,
            ),
        )

    /** Rotation angle in degrees between two attitudes, from the trace of AᵀB. */
    private fun angle(a: DeviceAttitude, b: DeviceAttitude): Double {
        val product = a.matrix.transposed * b.matrix
        val trace = product[0, 0] + product[1, 1] + product[2, 2]
        return AstroMath.acosDeg((trace - 1) / 2)
    }

    /** The smallest angle in degrees that [angle] can tell from zero (one ulp of the trace). */
    private val angleResolution = 2e-6

    // MARK: Quaternions

    @Test
    fun testQuaternionMatrixRoundTrip() {
        val random = PointingTestRandom(401)
        val samples = ArrayList<Matrix3>()
        repeat(2000) { samples.add(random.rotation()) }
        // Half turns about each axis and about the diagonals exercise every branch of the conversion.
        for (axis in listOf(
            Vector3(1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0), Vector3(0.0, 0.0, 1.0),
            Vector3(1.0, 1.0, 0.0), Vector3(1.0, 1.0, 1.0), Vector3(-1.0, 2.0, 1.0),
        )) {
            for (angle in listOf(PI, PI - 1e-6, 3.0, 0.0)) {
                samples.add(Matrix3.rotation(axis, angle))
            }
        }
        samples.add(Matrix3.identity)
        for (matrix in samples) {
            val q = AttitudeQuaternion(matrix)
            assertEquals(1.0, q.dot(q), 1e-12)
            Support.assertMatrix(q.matrix, matrix, accuracy = 1e-12)
            Support.assertRotation(q.matrix, accuracy = 1e-12)
        }
    }

    @Test
    fun testQuaternionAngleMatchesTheRotationAngle() {
        val random = PointingTestRandom(402)
        repeat(200) {
            val a = random.rotation()
            val degrees = random.uniform(0.0, 180.0)
            val b = Matrix3.rotation(random.unitVector(), AstroMath.radians(degrees)) * a
            assertEquals(degrees, AttitudeQuaternion(a).angle(AttitudeQuaternion(b)), 1e-6)
        }
    }

    @Test
    fun testSlerpFollowsTheShortestArc() {
        val random = PointingTestRandom(403)
        repeat(200) {
            val a = random.rotation()
            val degrees = random.uniform(0.0, 179.0)
            val rotation = random.unitVector()
            val b = Matrix3.rotation(rotation, AstroMath.radians(degrees)) * a
            val qa = AttitudeQuaternion(a)
            val qb = AttitudeQuaternion(b)
            Support.assertMatrix(qa.slerp(qb, 0.0).matrix, a, accuracy = 1e-9)
            Support.assertMatrix(qa.slerp(qb, 1.0).matrix, b, accuracy = 1e-9)
            val t = random.uniform(0.05, 0.95)
            val expected = Matrix3.rotation(rotation, AstroMath.radians(degrees * t)) * a
            Support.assertMatrix(qa.slerp(qb, t).matrix, expected, accuracy = 1e-9)
        }
    }

    @Test
    fun testSlerpTakesTheShortSideOfADoubleCover() {
        // q and −q are the same rotation: interpolating towards −q must not go the long way round.
        val a = AttitudeQuaternion(Matrix3.rotationZ(0.2))
        val b = AttitudeQuaternion(Matrix3.rotationZ(0.4))
        b.w = -b.w
        b.x = -b.x
        b.y = -b.y
        b.z = -b.z
        Support.assertMatrix(a.slerp(b, 0.5).matrix, Matrix3.rotationZ(0.3), accuracy = 1e-12)
    }

    @Test
    fun testSlerpIsStableForNearlyEqualQuaternions() {
        val a = AttitudeQuaternion(base)
        for (tiny in listOf(0.0, 1e-12, 1e-9, 1e-6, 1e-4)) {
            val b = AttitudeQuaternion(Matrix3.rotation(Vector3(0.0, 0.0, 1.0), tiny) * base)
            val middle = a.slerp(b, 0.5)
            assertEquals(1.0, middle.dot(middle), 1e-12)
            Support.assertMatrix(
                middle.matrix, Matrix3.rotation(Vector3(0.0, 0.0, 1.0), tiny / 2) * base, accuracy = 1e-12,
            )
        }
    }

    // MARK: Filtering

    @Test
    fun testFirstSampleIsPassedThrough() {
        val smoother = AttitudeSmoother()
        val first = attitude(12.0, at = 100.0, referenceAzimuth = 33.0)
        assertEquals(first, smoother.update(first))
    }

    @Test
    fun testAConstantInputStaysConstant() {
        val smoother = AttitudeSmoother()
        for (k in 0 until 120) {
            val input = attitude(7.0, at = 100 + k.toDouble() / 60)
            Support.assertMatrix(smoother.update(input).matrix, input.matrix, accuracy = 1e-12)
        }
    }

    @Test
    fun testOutputKeepsTheTimestampAndReferenceAzimuthOfTheSample() {
        val smoother = AttitudeSmoother()
        smoother.update(attitude(0.0, at = 10.0, referenceAzimuth = 25.0))
        val output = smoother.update(attitude(0.4, at = 10.02, referenceAzimuth = 25.0))
        Support.assertExactly(10.02, output.timestamp)
        assertEquals(25.0, output.referenceAzimuth, 1e-12)
        Support.assertRotation(output.matrix, accuracy = 1e-12)
    }

    @Test
    fun testAtRestTheTimeConstantIsExponential() {
        // With the lag limit out of the way the filter is a plain exponential smoother.
        val smoother = AttitudeSmoother(timeConstant = 0.12, maximumLag = 1e12)
        smoother.update(attitude(0.0, at = 0.0))
        val target = 10.0
        for (k in 1..30) {
            val output = smoother.update(attitude(target, at = k.toDouble() / 60))
            val expectedFraction = 1 - exp(-(k.toDouble() / 60) / 0.12)
            assertEquals(expectedFraction, angle(output, attitude(0.0, at = 0.0)) / target, 1e-9, "sample $k")
        }
    }

    @Test
    fun testNoiseIsAveragedOutWhileStill() {
        val random = PointingTestRandom(411)
        val smoother = AttitudeSmoother()
        val truth = attitude(0.0, at = 0.0)
        var rawSquares = 0.0
        var filteredSquares = 0.0
        val count = 600
        for (k in 0 until count) {
            val noisy = assertNotNull(
                DeviceAttitude.create(random.smallRotation(0.01) * truth.matrix, timestamp = k.toDouble() / 60),
            )
            val filtered = smoother.update(noisy)
            if (k >= 60) {
                rawSquares += angle(noisy, truth).pow(2)
                filteredSquares += angle(filtered, truth).pow(2)
            }
        }
        assertTrue(sqrt(filteredSquares / rawSquares) < 0.5)
    }

    @Test
    fun testLagStaysBelowTheLimitWhileTurning() {
        for (speed in listOf(2.0, 10.0, 30.0, 90.0, 240.0)) {
            val smoother = AttitudeSmoother()
            var lag = 0.0
            for (k in 0 until 240) {
                val time = k.toDouble() / 60
                val raw = attitude(speed * time, at = time)
                val filtered = smoother.update(raw)
                if (k > 120) lag = maxOf(lag, angle(filtered, raw))
            }
            assertTrue(lag < 0.5, "speed $speed°/s: lag $lag")
            assertTrue(lag > 0.0, "speed $speed°/s: lag $lag")
        }
    }

    @Test
    fun testTheFilteredAttitudeNeverGetsAheadOfTheTrueOne() {
        val smoother = AttitudeSmoother()
        val start = attitude(0.0, at = 0.0)
        for (k in 0 until 180) {
            val time = k.toDouble() / 60
            val filtered = smoother.update(attitude(45 * time, at = time))
            assertTrue(angle(start, filtered) <= 45 * time + 1e-9)
        }
    }

    @Test
    fun testAFastStepIsNotSmoothedAway() {
        // A jump of a few degrees in one frame is real motion: the output must follow at once.
        val smoother = AttitudeSmoother()
        for (k in 0 until 30) smoother.update(attitude(0.0, at = k.toDouble() / 60))
        val stepped = attitude(5.0, at = 0.5)
        assertTrue(angle(smoother.update(stepped), stepped) < 0.05)
    }

    @Test
    fun testStepResponseNeverOvershoots() {
        val smoother = AttitudeSmoother(timeConstant = 0.12, maximumLag = 1.0)
        for (k in 0 until 30) smoother.update(attitude(0.0, at = k.toDouble() / 60))
        var previousDistance = Double.POSITIVE_INFINITY
        for (k in 30 until 400) {
            val target = attitude(3.0, at = k.toDouble() / 60)
            val output = smoother.update(target)
            val distance = angle(output, target)
            // Platform-specific tolerance (Swift: 1e-9): `angle` is acos((trace − 1) / 2), which cannot resolve
            // less than one ulp of the trace, about 1.2e-6°. Once the filter has converged the last bit of the
            // trace flickers with the platform's `sin`/`exp`, so the distance is monotone only to that resolution
            // (the error itself shrinks steadily, by 13 % per sample).
            assertTrue(distance <= previousDistance + angleResolution, "sample $k: $distance > $previousDistance")
            previousDistance = distance
        }
        assertTrue(previousDistance < 1e-3)
    }

    // MARK: Resets

    @Test
    fun testALargeJumpRestartsTheFilter() {
        val smoother = AttitudeSmoother(timeConstant = 0.12, maximumLag = 1e12)
        smoother.update(attitude(0.0, at = 0.0))
        // 15° is smoothed (the lag limit is disabled), 25° exceeds the 20° jump threshold.
        val moderate = attitude(15.0, at = 1.0 / 60)
        assertTrue(angle(smoother.update(moderate), moderate) > 10)
        val jump = attitude(25.0, at = 2.0 / 60)
        val output = smoother.update(jump)
        assertTrue(angle(output, jump) < 1e-9)
        // And the filter carries on smoothly from the new place.
        val next = attitude(26.0, at = 3.0 / 60)
        assertTrue(angle(smoother.update(next), next) > 0.5)
    }

    @Test
    fun testALongPauseRestartsTheFilter() {
        val smoother = AttitudeSmoother(timeConstant = 0.12, maximumLag = 1e12)
        smoother.update(attitude(0.0, at = 0.0))
        val soon = attitude(3.0, at = 0.4)
        assertTrue(angle(smoother.update(soon), soon) > 1e-3) // 0.4 s is still filtered
        val late = attitude(6.0, at = 1.0)
        assertTrue(angle(smoother.update(late), late) < 1e-9) // 0.6 s gap restarts
    }

    @Test
    fun testAChangeOfReferenceAzimuthRestartsTheFilter() {
        val smoother = AttitudeSmoother(timeConstant = 0.12, maximumLag = 1e12)
        smoother.update(attitude(0.0, at = 0.0, referenceAzimuth = 0.0))
        val sameFrame = attitude(2.0, at = 1.0 / 60, referenceAzimuth = 0.0)
        assertTrue(angle(smoother.update(sameFrame), sameFrame) > 1)
        val newFrame = attitude(3.0, at = 2.0 / 60, referenceAzimuth = 40.0)
        val output = smoother.update(newFrame)
        assertTrue(angle(output, newFrame) < 1e-9)
        assertEquals(40.0, output.referenceAzimuth, 1e-12)
    }

    @Test
    fun testRepeatedTimestampsRestartTheFilter() {
        // Samples without usable timestamps (all 0 by default) are passed through, never frozen.
        val smoother = AttitudeSmoother(timeConstant = 0.12, maximumLag = 1e12)
        val first = attitude(0.0, at = 0.0)
        assertEquals(first, smoother.update(first))
        for (degrees in listOf(2.0, 4.0, 6.0)) {
            val sample = attitude(degrees, at = 0.0)
            assertEquals(sample, smoother.update(sample))
        }
    }

    @Test
    fun testTimeGoingBackwardsRestartsTheFilter() {
        val smoother = AttitudeSmoother(timeConstant = 0.12, maximumLag = 1e12)
        smoother.update(attitude(0.0, at = 5.0))
        val earlier = attitude(2.0, at = 4.99)
        assertTrue(angle(smoother.update(earlier), earlier) < 1e-9)
    }

    @Test
    fun testResetPassesTheNextSampleThrough() {
        val smoother = AttitudeSmoother(timeConstant = 0.12, maximumLag = 1e12)
        smoother.update(attitude(0.0, at = 0.0))
        smoother.reset()
        val sample = attitude(5.0, at = 1.0 / 60)
        assertEquals(sample, smoother.update(sample))
    }

    @Test
    fun testInvalidParametersFallBackToDefaults() {
        val smoother = AttitudeSmoother(
            timeConstant = -1.0, maximumLag = Double.NaN, jumpThreshold = 0.0, maximumTimeGap = Double.POSITIVE_INFINITY,
        )
        assertEquals(0.12, smoother.timeConstant)
        assertEquals(0.5, smoother.maximumLag)
        assertEquals(20.0, smoother.jumpThreshold)
        assertEquals(0.5, smoother.maximumTimeGap)
        val custom = AttitudeSmoother(timeConstant = 0.3, maximumLag = 2.0, jumpThreshold = 10.0, maximumTimeGap = 1.0)
        assertEquals(0.3, custom.timeConstant)
        assertEquals(10.0, custom.jumpThreshold)
    }

    @Test
    fun testSmoothedPointingStaysConsistentWithThePhoneGeometry() {
        // End to end: pan a level camera at 20°/s and compare the boresight from the smoothed
        // attitude with the true one. The azimuth lags by less than the limit, the altitude is untouched.
        val smoother = AttitudeSmoother()
        for (k in 0 until 300) {
            val time = k.toDouble() / 60
            val azimuth = 100 + 20 * time
            val frame = Support.cameraFrame(HorizontalCoordinate(azimuth, 30.0), 0.0)
            val raw = Support.attitude(
                mount = MountProfile.backCameraPortrait, frame = frame, referenceAzimuth = 15.0, timestamp = time,
            )
            val smoothed = smoother.update(raw)
            val boresight = PointingSolution.solve(smoothed, MountProfile.backCameraPortrait).boresight
            if (k > 120) {
                val lag = Support.azimuthDifference(azimuth, boresight.azimuth) * cos(AstroMath.radians(30.0))
                assertTrue(lag > 0, "lag $lag")
                assertTrue(lag < 0.5, "lag $lag")
                assertEquals(30.0, boresight.altitude, 0.01)
            }
        }
    }
}

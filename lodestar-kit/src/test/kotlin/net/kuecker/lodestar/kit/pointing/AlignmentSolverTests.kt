package net.kuecker.lodestar.kit.pointing

import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.pointing.PointingTestSupport as Support
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Shared scenario generation for the alignment tests: a "truth" (compass error and mount
 * misalignment) from which phone attitudes are synthesised for known targets.
 */
internal data class AlignmentTestScenario(
    /** The compass error in degrees (corrected azimuth = measured + heading). */
    val headingOffset: Double,
    /**
     * The true device-frame rotation of the camera axes relative to the mount's nominal axes
     * (a rotation vector in degrees).
     */
    val correction: Vector3,
    val mount: MountProfile,
) {
    /**
     * The point recorded when the camera is centred on `target` with the given roll and the phone
     * reporting through a reference frame of azimuth `referenceAzimuth`. `noiseDegrees` turns the
     * reported attitude by a random rotation of that size.
     */
    fun point(
        target: HorizontalCoordinate,
        roll: Double,
        referenceAzimuth: Double = 0.0,
        noiseDegrees: Double = 0.0,
        random: PointingTestRandom,
        index: Int = 0,
        name: String? = null,
    ): AlignmentPoint {
        // corrected = Rz(−heading) · raw, hence raw = Rz(+heading) · corrected.
        val raw = Matrix3.rotationZ(AstroMath.radians(headingOffset)) * target.unitVector
        val frame = Support.cameraFrame(HorizontalCoordinate(raw), roll)
        val exact = Support.attitude(
            mount = mount, correction = correction, frame = frame, referenceAzimuth = referenceAzimuth,
        )
        var reported = exact
        if (noiseDegrees > 0) {
            reported = assertNotNull(
                DeviceAttitude.create(random.smallRotation(noiseDegrees) * exact.matrix, referenceAzimuth),
            )
        }
        return AlignmentPoint(
            reported, target, instantFromTimeIntervalSince1970(1_800_000_000.0 + index.toDouble()), name,
        )
    }

    /** The pointing the true system has for a phone attitude: what the DSLR really looks at. */
    fun trueBoresight(attitude: DeviceAttitude): HorizontalCoordinate {
        val turn = Matrix3.rotation(correction, AstroMath.radians(correction.length))
        val raw = attitude.deviceToWorld * (turn * mount.cameraAxis)
        return HorizontalCoordinate(Matrix3.rotationZ(-AstroMath.radians(headingOffset)) * raw)
    }
}

class AlignmentSolverTests {
    /** Pointing error in degrees of `model` for a phone attitude, against the truth. */
    private fun pointingError(model: AlignmentModel, scenario: AlignmentTestScenario, attitude: DeviceAttitude): Double {
        val predicted = PointingSolution.solve(attitude, scenario.mount, model).boresight
        return predicted.angularSeparation(scenario.trueBoresight(attitude))
    }

    /** Phone attitudes (from random targets and rolls) at which a model is judged. */
    private fun validationAttitudes(
        scenario: AlignmentTestScenario,
        count: Int,
        random: PointingTestRandom,
    ): List<DeviceAttitude> = (0 until count).map {
        val target = random.horizontal(5.0..75.0)
        scenario.point(target, random.uniform(-40.0, 40.0), random = random).attitude
    }

    // MARK: One point

    @Test
    fun testOnePointIsExactAtThePointForEveryMountAndRoll() {
        val random = PointingTestRandom(501)
        for (mount in MountProfile.presets) {
            repeat(60) {
                val scenario = AlignmentTestScenario(
                    headingOffset = random.uniform(-40.0, 40.0),
                    correction = random.unitVector() * random.uniform(0.0, 4.0),
                    mount = mount,
                )
                val target = random.horizontal(-50.0..75.0)
                val point = scenario.point(
                    target, random.uniform(-180.0, 180.0), referenceAzimuth = random.uniform(-180.0, 180.0), random = random,
                )
                val model = AlignmentModel.solve(listOf(point), mount)
                assertEquals(1, model.points.size)
                assertEquals(1, model.residuals.size)
                assertEquals(0.0, model.residuals[0], 1e-7, mount.id)
                assertEquals(0.0, model.rmsResidual, 1e-7)
                // The public pointing code agrees with the solver: the boresight is the target.
                val solved = PointingSolution.solve(point.attitude, mount, model).boresight
                assertEquals(0.0, solved.angularSeparation(target), 1e-7, mount.id)
                // The correction never turns the picture about the optical axis.
                assertEquals(0.0, model.deviceCorrection.dot(mount.cameraAxis), 1e-9)
            }
        }
    }

    @Test
    fun testOnePointWithOnlyACompassErrorRecoversTheOffset() {
        val random = PointingTestRandom(502)
        for (mount in MountProfile.presets) {
            val scenario = AlignmentTestScenario(headingOffset = 12.0, correction = Vector3.zero, mount = mount)
            val point = scenario.point(HorizontalCoordinate(215.0, 33.0), random.uniform(-90.0, 90.0), random = random)
            val model = AlignmentModel.solve(listOf(point), mount)
            assertEquals(12.0, model.headingOffset, 1e-9, mount.id)
            assertEquals(0.0, model.deviceCorrection.length, 1e-9, mount.id)
        }
    }

    @Test
    fun testOnePointWithOnlyAMountTiltRecoversTheTilt() {
        // A level camera whose lens sits 2° above the phone's nominal axis: the phone reports an
        // altitude 2° too low, and the correction is the rotation about the camera's right axis.
        val mount = MountProfile.backCameraPortrait
        val random = PointingTestRandom(503)
        val scenario = AlignmentTestScenario(headingOffset = 0.0, correction = Vector3(2.0, 0.0, 0.0), mount = mount)
        val point = scenario.point(HorizontalCoordinate(0.0, 30.0), 0.0, random = random)
        val model = AlignmentModel.solve(listOf(point), mount)
        assertEquals(0.0, model.headingOffset, 1e-9)
        Support.assertVector(model.deviceCorrection, Vector3(2.0, 0.0, 0.0), accuracy = 1e-9)
    }

    @Test
    fun testOnePointBlamesTheAltitudeOnTheMountAndTheAzimuthOnTheCompass() {
        // The phone reports (az 90°, alt 20°); the camera really looks at (az 100°, alt 25°).
        val mount = MountProfile.backCameraPortrait
        val reported = HorizontalCoordinate(90.0, 20.0)
        val attitude = Support.attitude(mount = mount, frame = Support.cameraFrame(reported, 0.0))
        val point = AlignmentPoint(attitude, HorizontalCoordinate(100.0, 25.0), instantFromTimeIntervalSince1970(0.0))
        val model = AlignmentModel.solve(listOf(point), mount)
        assertEquals(10.0, model.headingOffset, 1e-9)
        assertEquals(5.0, model.deviceCorrection.length, 1e-9)
        // Raising the lens by 5° about the camera's right axis (device +x): positive rotation about x.
        Support.assertVector(model.deviceCorrection, Vector3(5.0, 0.0, 0.0), accuracy = 1e-9)
    }

    @Test
    fun testOnePointErrorElsewhereIsBounded() {
        // Truth: 12° compass error plus a 2° mount misalignment in a random direction. After aligning
        // on one object, the camera is wrong by no more than a few degrees anywhere else.
        val random = PointingTestRandom(504)
        var worst = 0.0
        for (mount in MountProfile.presets) {
            repeat(40) {
                val scenario = AlignmentTestScenario(headingOffset = 12.0, correction = random.unitVector() * 2.0, mount = mount)
                val point = scenario.point(random.horizontal(10.0..65.0), random.uniform(-30.0, 30.0), random = random)
                val model = AlignmentModel.solve(listOf(point), mount)
                for (attitude in validationAttitudes(scenario, 10, random)) {
                    worst = maxOf(worst, pointingError(model, scenario, attitude))
                }
            }
        }
        assertTrue(worst < 5, "worst error $worst")
    }

    /** A compass error: the canonical 12° most of the time, anywhere on the circle otherwise. */
    private fun compassError(random: PointingTestRandom): Double =
        if (random.nextBits() % 3uL == 0uL) 12.0 else random.uniform(-180.0, 180.0)

    // MARK: Several points

    @Test
    fun testTwoToFivePointsRecoverTheTruthWithoutNoise() {
        val random = PointingTestRandom(505)
        for (mount in MountProfile.presets) {
            for (count in 2..5) {
                repeat(12) {
                    val scenario = AlignmentTestScenario(
                        headingOffset = compassError(random), correction = random.unitVector() * 2.0, mount = mount,
                    )
                    val points = (0 until count).map { index ->
                        // Spread in altitude and azimuth, and some roll, so the effects can be told apart.
                        val altitude = 15 + 55 * index.toDouble() / maxOf(count - 1, 1).toDouble() + random.uniform(-5.0, 5.0)
                        scenario.point(
                            HorizontalCoordinate(random.uniform(0.0, 360.0), altitude), random.uniform(-45.0, 45.0),
                            random = random, index = index,
                        )
                    }
                    val model = AlignmentModel.solve(points, mount)
                    assertEquals(count, model.points.size)
                    assertTrue(model.rmsResidual < 0.01, "${mount.id} $count points: rms ${model.rmsResidual}")
                    for (attitude in validationAttitudes(scenario, 8, random)) {
                        val error = pointingError(model, scenario, attitude)
                        assertTrue(error < 0.05, "${mount.id} $count points: error $error")
                    }
                }
            }
        }
    }

    @Test
    fun testCompassOffsetAndPureTiltAreSeparatedByTwoPoints() {
        // Truth without twist so that the parameters themselves can be compared.
        val mount = MountProfile.backCameraPortrait
        val random = PointingTestRandom(506)
        val truth = Vector3(1.5, -1.0, 0.0)
        val scenario = AlignmentTestScenario(headingOffset = -17.0, correction = truth, mount = mount)
        val points = listOf(
            scenario.point(HorizontalCoordinate(40.0, 20.0), 0.0, random = random, index = 0),
            scenario.point(HorizontalCoordinate(250.0, 62.0), 25.0, random = random, index = 1),
        )
        val model = AlignmentModel.solve(points, mount)
        assertEquals(-17.0, model.headingOffset, 0.05)
        Support.assertVector(model.deviceCorrection, truth, accuracy = 0.05)
    }

    @Test
    fun testPointsInAMixOfReferenceFramesAreSolvedTogether() {
        // The compass-referenced points and the points taken after switching to a gyro frame (a
        // different reference azimuth) describe the same camera and share one answer.
        val mount = MountProfile.backCameraLandscapeLeft
        val random = PointingTestRandom(507)
        val scenario = AlignmentTestScenario(headingOffset = 9.0, correction = Vector3(1.0, 2.0, 0.0), mount = mount)
        val points = ArrayList<AlignmentPoint>()
        for ((index, psi) in listOf(0.0, 0.0, 133.0, 133.0, -75.0).withIndex()) {
            val target = HorizontalCoordinate(random.uniform(0.0, 360.0), 15 + 12 * index.toDouble())
            points.add(
                scenario.point(target, random.uniform(-30.0, 30.0), referenceAzimuth = psi, random = random, index = index),
            )
        }
        val model = AlignmentModel.solve(points, mount)
        assertTrue(model.rmsResidual < 0.01)
        assertEquals(9.0, model.headingOffset, 0.05)
    }

    @Test
    fun testNoisyPointsDegradeGracefully() {
        val random = PointingTestRandom(508)
        val errors = ArrayList<Double>()
        var worstResidual = 0.0
        for (mount in MountProfile.presets) {
            repeat(15) {
                val scenario = AlignmentTestScenario(headingOffset = 12.0, correction = random.unitVector() * 2.0, mount = mount)
                val points = (0 until 5).map { index ->
                    scenario.point(
                        HorizontalCoordinate(random.uniform(0.0, 360.0), 15 + 14 * index.toDouble()),
                        random.uniform(-45.0, 45.0), noiseDegrees = 0.2, random = random, index = index,
                    )
                }
                val model = AlignmentModel.solve(points, mount)
                worstResidual = maxOf(worstResidual, model.rmsResidual)
                for (attitude in validationAttitudes(scenario, 6, random)) {
                    errors.add(pointingError(model, scenario, attitude))
                }
            }
        }
        // Per-axis noise of 0.2° puts the input pointing about 0.3° off; the fit does at least as well.
        assertTrue(errors.fold(0.0) { a, b -> a + b } / errors.size < 0.4)
        assertTrue((errors.maxOrNull() ?: Double.POSITIVE_INFINITY) < 1.5)
        assertTrue(worstResidual < 0.8)
    }

    @Test
    fun testResidualsAreTheDistancesFromTheTargets() {
        val random = PointingTestRandom(509)
        val mount = MountProfile.flatOnTopTopEdgeForward
        val scenario = AlignmentTestScenario(headingOffset = 5.0, correction = Vector3(0.0, 1.5, 0.0), mount = mount)
        val points = (0 until 4).map { index ->
            scenario.point(
                HorizontalCoordinate(random.uniform(0.0, 360.0), 20 + 12 * index.toDouble()),
                random.uniform(-30.0, 30.0), noiseDegrees = 0.3, random = random, index = index,
            )
        }
        val model = AlignmentModel.solve(points, mount)
        assertEquals(4, model.residuals.size)
        for ((point, residual) in model.points.zip(model.residuals)) {
            val solved = PointingSolution.solve(point.attitude, mount, model).boresight
            assertEquals(solved.angularSeparation(point.target), residual, 1e-7)
        }
        val expectedRMS = sqrt(model.residuals.map { it * it }.fold(0.0) { a, b -> a + b } / 4)
        assertEquals(expectedRMS, model.rmsResidual, 1e-12)
        assertTrue(model.rmsResidual > 0.01)
    }

    @Test
    fun testTheFitIsNoWorseThanAnyOtherParameters() {
        // The solution is a minimum of the (prior-regularised) squared error: nudging either
        // parameter does not reduce the sum of squared residuals.
        val random = PointingTestRandom(510)
        val mount = MountProfile.backCameraPortrait
        val scenario = AlignmentTestScenario(headingOffset = 20.0, correction = Vector3(2.0, -1.0, 0.0), mount = mount)
        val points = (0 until 4).map { index ->
            scenario.point(
                HorizontalCoordinate(random.uniform(0.0, 360.0), 20 + 14 * index.toDouble()),
                random.uniform(-30.0, 30.0), noiseDegrees = 0.3, random = random, index = index,
            )
        }
        val model = AlignmentModel.solve(points, mount)
        fun cost(heading: Double, correction: Vector3): Double {
            val trial = AlignmentModel(
                headingOffset = heading, deviceCorrection = correction, points = emptyList(), residuals = emptyList(),
                pointingUncertainty = null, calibratedMount = mount,
            )
            val sumOfSquares = points.fold(0.0) { total, point ->
                val boresight = PointingSolution.solve(point.attitude, mount, trial).boresight
                total + (boresight.angularSeparation(point.target) / 0.25).pow(2)
            }
            return sumOfSquares + (correction.length / 10).pow(2)
        }
        val best = cost(model.headingOffset, model.deviceCorrection)
        for (delta in listOf(Vector3(0.05, 0.0, 0.0), Vector3(0.0, 0.05, 0.0), Vector3(-0.05, 0.0, 0.0), Vector3(0.0, -0.05, 0.0))) {
            assertTrue(cost(model.headingOffset, model.deviceCorrection + delta) >= best - 1e-9)
        }
        for (delta in listOf(0.05, -0.05)) {
            assertTrue(cost(model.headingOffset + delta, model.deviceCorrection) >= best - 1e-9)
        }
    }

    // MARK: Uncertainty

    @Test
    fun testUncertaintyShrinksAsWellSpreadPointsAreAdded() {
        val random = PointingTestRandom(520)
        val mount = MountProfile.backCameraPortrait
        val scenario = AlignmentTestScenario(headingOffset = 12.0, correction = Vector3(1.5, -1.0, 0.0), mount = mount)
        // (azimuth, altitude, roll) of five objects spread over the sky.
        val spread = listOf(
            Triple(40.0, 15.0, 0.0), Triple(250.0, 62.0, 30.0), Triple(130.0, 38.0, -40.0),
            Triple(320.0, 72.0, 10.0), Triple(200.0, 25.0, 60.0),
        )
        var model = AlignmentModel.identity
        assertNull(model.pointingUncertainty)
        var previous = Double.POSITIVE_INFINITY
        val values = ArrayList<Double>()
        for ((index, item) in spread.withIndex()) {
            val point = scenario.point(HorizontalCoordinate(item.first, item.second), item.third, random = random, index = index)
            model = model.adding(point, mount)
            val uncertainty = assertNotNull(model.pointingUncertainty)
            assertTrue(uncertainty < previous, "uncertainty $uncertainty after ${index + 1} points, previous $previous")
            previous = uncertainty
            values.add(uncertainty)
        }
        assertTrue(values[0] > 10) // one point says nothing about the rest of the sky
        assertTrue(values[1] < 1.0, "${values[1]}")
        assertTrue(values[2] < 0.6, "${values[2]}")
        assertTrue(values[4] < 0.35, "${values[4]}")
    }

    @Test
    fun testUncertaintyFlagsPointsThatCannotSeparateCompassFromMount() {
        // All at one altitude with the camera level: a compass error and a pan error of the mount look
        // alike, however many points there are.
        val random = PointingTestRandom(521)
        val mount = MountProfile.backCameraPortrait
        val scenario = AlignmentTestScenario(headingOffset = 12.0, correction = Vector3(1.5, -1.0, 0.0), mount = mount)
        var model = AlignmentModel.identity
        for (index in 0 until 4) {
            val point = scenario.point(HorizontalCoordinate(index * 90.0, 30.0), 0.0, random = random, index = index)
            model = model.adding(point, mount)
            assertTrue(assertNotNull(model.pointingUncertainty) > 10)
        }
        // The fit itself is perfect on those points: only the uncertainty tells them apart.
        assertTrue(model.rmsResidual < 0.05)
        // Two points that differ in altitude, or in roll, are enough to tell the effects apart.
        val differentAltitudes = AlignmentModel.solve(
            listOf(
                scenario.point(HorizontalCoordinate(30.0, 20.0), 0.0, random = random, index = 0),
                scenario.point(HorizontalCoordinate(200.0, 65.0), 0.0, random = random, index = 1),
            ),
            mount,
        )
        assertTrue(assertNotNull(differentAltitudes.pointingUncertainty) < 1.5)
        val differentRolls = AlignmentModel.solve(
            listOf(
                scenario.point(HorizontalCoordinate(30.0, 40.0), 0.0, random = random, index = 0),
                scenario.point(HorizontalCoordinate(200.0, 45.0), 90.0, random = random, index = 1),
            ),
            mount,
        )
        assertTrue(assertNotNull(differentRolls.pointingUncertainty) < 1.5)
    }

    @Test
    fun testUncertaintyPredictsTheErrorAcrossTheSky() {
        // With points centred to within 0.25° (the assumed accuracy), the predicted figure matches the
        // actual root-mean-square error over directions spread evenly over the sky and random rolls.
        val random = PointingTestRandom(522)
        var predictedSum = 0.0
        var actualSquares = 0.0
        val scenarios = 400
        repeat(scenarios) {
            val mount = MountProfile.presets[(random.nextBits() % MountProfile.presets.size.toULong()).toInt()]
            val tilt = random.unitVector() * random.uniform(0.0, 3.0)
            val correction = tilt - mount.cameraAxis * mount.cameraAxis.dot(tilt)
            val scenario = AlignmentTestScenario(headingOffset = random.uniform(-30.0, 30.0), correction = correction, mount = mount)
            val count = 3 + (random.nextBits() % 4uL).toInt()
            val points = (0 until count).map { index ->
                scenario.point(
                    random.horizontal(8.0..72.0), random.uniform(-45.0, 45.0), noiseDegrees = 0.25, random = random, index = index,
                )
            }
            val model = AlignmentModel.solve(points, mount)
            predictedSum += assertNotNull(model.pointingUncertainty)
            var squares = 0.0
            repeat(10) {
                val altitude = AstroMath.degrees(asin(random.uniform(0.05, 0.97))) // evenly spread over the sky
                val probe = scenario.point(
                    HorizontalCoordinate(random.uniform(0.0, 360.0), altitude), random.uniform(-180.0, 180.0), random = random,
                ).attitude
                val error = pointingError(model, scenario, probe)
                squares += error * error
            }
            actualSquares += squares / 10
        }
        val predicted = predictedSum / scenarios.toDouble()
        val actual = sqrt(actualSquares / scenarios.toDouble())
        assertEquals(1.0, actual / predicted, 0.25, "predicted $predicted°, actual $actual°")
    }

    // MARK: Degenerate input

    @Test
    fun testDegenerateConfigurationsStayFinite() {
        val random = PointingTestRandom(511)
        val mount = MountProfile.backCameraPortrait
        val scenario = AlignmentTestScenario(headingOffset = 30.0, correction = Vector3(1.0, 1.0, 0.0), mount = mount)
        val target = HorizontalCoordinate(120.0, 40.0)
        val single = scenario.point(target, 0.0, random = random)

        // The same point five times.
        val repeated = AlignmentModel.solve(List(5) { single }, mount)
        assertEquals(5, repeated.residuals.size)
        assertTrue(repeated.rmsResidual < 1e-3) // the weak prior pulls the correction a hair towards zero

        // Contradictory points: one phone attitude, two different targets.
        val contradiction = AlignmentPoint(single.attitude, HorizontalCoordinate(130.0, 45.0), single.timestamp)
        val model = AlignmentModel.solve(listOf(single, contradiction), mount)
        assertTrue(model.headingOffset.isFinite())
        assertTrue(model.deviceCorrection.isFinite)
        assertTrue(model.residuals.all { it.isFinite() })
        assertTrue(model.deviceCorrection.length < AlignmentModel.maximumDeviceCorrection)

        // Same altitude and roll everywhere: the compass and the pan error cannot be told apart, but the
        // result must still reproduce the points.
        val flat = (0 until 4).map { index ->
            scenario.point(HorizontalCoordinate(index * 80.0, 30.0), 0.0, random = random, index = index)
        }
        val flatModel = AlignmentModel.solve(flat, mount)
        assertTrue(flatModel.rmsResidual < 0.05)
        assertTrue(flatModel.deviceCorrection.isFinite)
    }

    @Test
    fun testRandomScenariosAlwaysGiveConsistentFiniteModels() {
        val random = PointingTestRandom(512)
        repeat(300) {
            val mount = MountProfile.presets[(random.nextBits() % MountProfile.presets.size.toULong()).toInt()]
            val scenario = AlignmentTestScenario(
                headingOffset = random.uniform(-180.0, 180.0),
                correction = random.unitVector() * random.uniform(0.0, 6.0), mount = mount,
            )
            val count = (random.nextBits() % 6uL).toInt() + 1
            val points = (0 until count).map { index ->
                scenario.point(
                    random.horizontal(-60.0..70.0), random.uniform(-180.0, 180.0),
                    referenceAzimuth = random.uniform(-180.0, 180.0), noiseDegrees = random.uniform(0.0, 0.5),
                    random = random, index = index,
                )
            }
            val model = AlignmentModel.solve(points, mount)
            assertTrue(model.headingOffset.isFinite())
            assertTrue(abs(model.headingOffset) <= 180)
            assertTrue(model.deviceCorrection.isFinite)
            assertEquals(count, model.residuals.size)
            // The reported residuals are what the public pointing code produces.
            for ((point, residual) in model.points.zip(model.residuals)) {
                val solved = PointingSolution.solve(point.attitude, mount, model).boresight
                assertEquals(solved.angularSeparation(point.target), residual, 1e-6)
            }
            // One point is always matched exactly.
            if (count == 1) assertTrue(model.rmsResidual < 1e-6)
        }
    }
}

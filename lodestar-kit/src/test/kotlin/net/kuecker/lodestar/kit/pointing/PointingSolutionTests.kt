package net.kuecker.lodestar.kit.pointing

import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.pointing.PointingTestSupport as Support
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PointingSolutionTests {
    @Test
    fun testBoresightAndCameraAxesMatchThePhysicalCameraForEveryMount() {
        val random = PointingTestRandom(101)
        for (expected in Support.expectedMounts) {
            repeat(200) {
                val boresight = random.horizontal(-85.0..85.0)
                val roll = random.uniform(-180.0, 180.0)
                val frame = Support.cameraFrame(boresight, roll)
                val psi = random.uniform(-180.0, 180.0)
                val attitude = Support.attitude(mount = expected.mount, frame = frame, referenceAzimuth = psi)
                val solution = PointingSolution.solve(attitude, expected.mount)

                Support.assertVector(solution.boresightVector, frame.forward, accuracy = 1e-9, message = expected.mount.id)
                Support.assertVector(solution.cameraUpVector, frame.up, accuracy = 1e-9, message = expected.mount.id)
                Support.assertVector(solution.cameraRightVector, frame.right, accuracy = 1e-9, message = expected.mount.id)
                assertEquals(0.0, Support.azimuthDifference(solution.boresight.azimuth, boresight.azimuth), 1e-7)
                assertEquals(boresight.altitude, solution.boresight.altitude, 1e-7)
                assertEquals(0.0, Support.azimuthDifference(solution.roll, roll), 1e-6, expected.mount.id)

                // The screen's right and up expressed in the world, from the hand-derived table.
                val screenRight = frame.right * expected.screenRight.x + frame.up * expected.screenUp.x
                val screenUp = frame.right * expected.screenRight.y + frame.up * expected.screenUp.y
                Support.assertVector(solution.screenRightVector, screenRight, accuracy = 1e-9, message = expected.mount.id)
                Support.assertVector(solution.screenUpVector, screenUp, accuracy = 1e-9, message = expected.mount.id)
            }
        }
    }

    @Test
    fun testSolutionVectorsAreOrthonormalAndRightHanded() {
        val random = PointingTestRandom(102)
        for (mount in MountProfile.presets) {
            repeat(100) {
                val attitude = assertNotNull(DeviceAttitude.create(random.rotation(), random.uniform(-180.0, 180.0)))
                val s = PointingSolution.solve(attitude, mount)
                for (vector in listOf(s.boresightVector, s.cameraUpVector, s.cameraRightVector, s.screenRightVector, s.screenUpVector)) {
                    assertEquals(1.0, vector.length, 1e-12)
                }
                assertEquals(0.0, s.boresightVector.dot(s.cameraUpVector), 1e-12)
                assertEquals(0.0, s.boresightVector.dot(s.cameraRightVector), 1e-12)
                assertEquals(0.0, s.boresightVector.dot(s.screenRightVector), 1e-12)
                assertEquals(0.0, s.boresightVector.dot(s.screenUpVector), 1e-12)
                // The photographer looks along the boresight, so right × up points back at them.
                Support.assertVector(s.cameraRightVector.cross(s.cameraUpVector), s.boresightVector * -1.0, accuracy = 1e-12)
                Support.assertVector(s.screenRightVector.cross(s.screenUpVector), s.boresightVector * -1.0, accuracy = 1e-12)
                Support.assertVector(s.boresightVector, HorizontalCoordinate(s.boresightVector).unitVector, accuracy = 1e-12)
            }
        }
    }

    @Test
    fun testRollIsPositiveWhenTheRightSideIsLower() {
        val boresight = HorizontalCoordinate(120.0, 25.0)
        for (roll in listOf(-90.0, -10.0, 0.0, 10.0, 45.0, 90.0, 170.0)) {
            val frame = Support.cameraFrame(boresight, roll)
            // The helper itself must obey the physical statement: clockwise means the right side dips.
            if (roll > 0) assertTrue(frame.right.z < 0)
            if (roll < 0) assertTrue(frame.right.z > 0)
            val attitude = Support.attitude(mount = MountProfile.backCameraPortrait, frame = frame)
            assertEquals(roll, PointingSolution.solve(attitude, MountProfile.backCameraPortrait).roll, 1e-8)
        }
        // Level: the camera's right side is horizontal; turned 20° clockwise it dips below the horizon.
        val level = Support.cameraFrame(HorizontalCoordinate(0.0, 0.0), 0.0)
        Support.assertVector(level.right, Vector3(1.0, 0.0, 0.0), accuracy = 1e-15)
        Support.assertVector(level.up, Vector3(0.0, 0.0, 1.0), accuracy = 1e-15)
        val rolled = Support.cameraFrame(HorizontalCoordinate(0.0, 0.0), 20.0)
        assertTrue(rolled.right.z < 0)
        assertTrue(rolled.up.x > 0)
    }

    @Test
    fun testRollIsZeroAtTheZenithAndNadir() {
        for (altitude in listOf(90.0, -90.0, 89.99999, -89.99999)) {
            val frame = Support.cameraFrame(HorizontalCoordinate(33.0, altitude), 0.0)
            val attitude = Support.attitude(mount = MountProfile.backCameraPortrait, frame = frame)
            val solution = PointingSolution.solve(attitude, MountProfile.backCameraPortrait)
            assertEquals(0.0, solution.roll, 1e-12, "altitude $altitude")
            assertTrue(solution.cameraUpVector.isFinite)
        }
    }

    @Test
    fun testHeadingOffsetTurnsTheWorldAboutTheVertical() {
        val random = PointingTestRandom(103)
        repeat(100) {
            val frame = Support.cameraFrame(random.horizontal(-80.0..80.0), random.uniform(-90.0, 90.0))
            val attitude = Support.attitude(
                mount = MountProfile.backCameraLandscapeLeft, frame = frame, referenceAzimuth = random.uniform(-180.0, 180.0),
            )
            val offset = random.uniform(-180.0, 180.0)
            val alignment = AlignmentModel(
                headingOffset = offset, deviceCorrection = Vector3.zero, points = emptyList(), residuals = emptyList(),
                pointingUncertainty = null, calibratedMount = null,
            )
            val plain = PointingSolution.solve(attitude, MountProfile.backCameraLandscapeLeft)
            val corrected = PointingSolution.solve(attitude, MountProfile.backCameraLandscapeLeft, alignment)
            assertEquals(0.0, Support.azimuthDifference(corrected.boresight.azimuth, plain.boresight.azimuth + offset), 1e-9)
            assertEquals(plain.boresight.altitude, corrected.boresight.altitude, 1e-9)
            assertEquals(plain.roll, corrected.roll, 1e-7)
            Support.assertVector(corrected.cameraUpVector, PointingMath.yawed(plain.cameraUpVector, offset), accuracy = 1e-9)
            Support.assertVector(corrected.cameraRightVector, PointingMath.yawed(plain.cameraRightVector, offset), accuracy = 1e-9)
        }
    }

    @Test
    fun testDeviceCorrectionTurnsTheCameraAxisInTheDeviceFrame() {
        // Upright portrait phone facing north.
        val attitude = Support.attitude(Vector3(1.0, 0.0, 0.0), Vector3(0.0, 0.0, 1.0), Vector3(0.0, -1.0, 0.0))
        val mount = MountProfile.backCameraPortrait
        fun solution(correction: Vector3): PointingSolution {
            val alignment = AlignmentModel(
                headingOffset = 0.0, deviceCorrection = correction, points = emptyList(), residuals = emptyList(),
                pointingUncertainty = null, calibratedMount = null,
            )
            return PointingSolution.solve(attitude, mount, alignment)
        }
        // About the camera's right axis (device x): the lens tilts up.
        val tilt = solution(Vector3(2.0, 0.0, 0.0))
        assertEquals(2.0, tilt.boresight.altitude, 1e-9)
        assertEquals(0.0, Support.azimuthDifference(tilt.boresight.azimuth, 0.0), 1e-9)
        // About the camera's up axis (device y): the lens turns to the left.
        val pan = solution(Vector3(0.0, 2.0, 0.0))
        assertEquals(0.0, Support.azimuthDifference(pan.boresight.azimuth, -2.0), 1e-9)
        assertEquals(0.0, pan.boresight.altitude, 1e-9)
        // About the optical axis (device z): the picture turns, the lens does not move. Positive
        // rotation about +z is counter-clockwise for the photographer, i.e. negative roll.
        val twist = solution(Vector3(0.0, 0.0, 2.0))
        assertEquals(0.0, twist.boresight.altitude, 1e-9)
        assertEquals(0.0, Support.azimuthDifference(twist.boresight.azimuth, 0.0), 1e-9)
        assertEquals(-2.0, twist.roll, 1e-9)
    }

    @Test
    fun testAlignmentSolvedForAnotherMountIsIgnored() {
        val frame = Support.cameraFrame(HorizontalCoordinate(200.0, 35.0), 0.0)
        val attitude = Support.attitude(mount = MountProfile.backCameraPortrait, frame = frame)
        val alignment = AlignmentModel(
            headingOffset = 25.0, deviceCorrection = Vector3(3.0, 0.0, 0.0), points = emptyList(), residuals = emptyList(),
            pointingUncertainty = null, calibratedMount = MountProfile.backCameraLandscapeLeft,
        )
        assertFalse(alignment.isApplicable(MountProfile.backCameraPortrait))
        assertTrue(alignment.isApplicable(MountProfile.backCameraLandscapeLeft))
        val ignored = PointingSolution.solve(attitude, MountProfile.backCameraPortrait, alignment)
        val plain = PointingSolution.solve(attitude, MountProfile.backCameraPortrait)
        assertEquals(plain, ignored)
        // The identity applies to every mount.
        assertTrue(AlignmentModel.identity.isApplicable(MountProfile.flatOnTopTopEdgeBack))
    }

    @Test
    fun testSolutionFollowsThePhoneWhenItIsRotatedOnTheTripod() {
        // Pan a level camera through a full turn: azimuth follows, altitude and roll stay put.
        for (step in 0 until 36) {
            val azimuth = step.toDouble() * 10
            val frame = Support.cameraFrame(HorizontalCoordinate(azimuth, 15.0), 0.0)
            val attitude = Support.attitude(mount = MountProfile.flatOnTopTopEdgeForward, frame = frame, referenceAzimuth = 40.0)
            val solution = PointingSolution.solve(attitude, MountProfile.flatOnTopTopEdgeForward)
            assertEquals(0.0, Support.azimuthDifference(solution.boresight.azimuth, azimuth), 1e-9)
            assertEquals(15.0, solution.boresight.altitude, 1e-9)
            assertEquals(0.0, solution.roll, 1e-8)
        }
    }
}

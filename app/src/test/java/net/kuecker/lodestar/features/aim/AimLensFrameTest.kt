package net.kuecker.lodestar.features.aim

import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Matrix3
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.phototools.LensProjection
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.kit.phototools.SensorFormat
import net.kuecker.lodestar.kit.pointing.AlignmentModel
import net.kuecker.lodestar.kit.pointing.DeviceAttitude
import net.kuecker.lodestar.kit.pointing.MountProfile
import net.kuecker.lodestar.kit.pointing.PinholeProjection
import net.kuecker.lodestar.kit.pointing.PointingSolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AimLensFrameTest {
    /** The pointing of a level, upright phone whose back camera looks at [azimuth] / [altitude]. */
    private fun solution(azimuth: Double, altitude: Double): PointingSolution {
        val forward = HorizontalCoordinate(azimuth, altitude).unitVector
        val up = (Vector3(0.0, 0.0, 1.0) - forward * forward.z).normalized
        val back = -forward
        val right = up.cross(back)
        fun northWestUp(v: Vector3) = Vector3(v.y, -v.x, v.z)
        val attitude = assertNotNull(
            DeviceAttitude.create(Matrix3(northWestUp(right), northWestUp(up), northWestUp(back)), 0.0, 0.0),
        )
        return PointingSolution.solve(attitude, MountProfile.backCameraPortrait, AlignmentModel.identity)
    }

    private fun AimLensFrame.directions(): List<Vector3> =
        List(count) { Vector3(world[3 * it], world[3 * it + 1], world[3 * it + 2]) }

    @Test
    fun aRectilinearFrameIsTheOutlineOfBefore() {
        for ((azimuth, altitude) in listOf(0.0 to 0.0, 130.0 to 35.0, 250.0 to 70.0)) {
            val pointing = solution(azimuth, altitude)
            for (focalLength in listOf(14.0, 50.0, 300.0)) {
                val lens = LensSetup(SensorFormat.apsC, focalLength, 2.8)
                val frame = AimLensFrame()
                frame.update(lens)
                frame.intoWorld(pointing)
                val before = PinholeProjection.dslrFrameOutline(
                    pointing, lens.fieldOfView.horizontal, lens.fieldOfView.vertical, AimLensFrame.RECTILINEAR_SAMPLES_PER_EDGE,
                )
                val now = frame.directions()
                assertEquals(before.size, now.size)
                for (index in before.indices) {
                    assertEquals(0.0, before[index].angle(now[index]), 1e-9, "$azimuth/$altitude $focalLength mm point $index")
                }
            }
        }
    }

    @Test
    fun aFisheyeFrameIsCurvedAndCentredOnTheOpticalAxis() {
        val pointing = solution(40.0, 30.0)
        val lens = LensSetup(SensorFormat.fullFrame, 15.0, 2.8, null, LensProjection.FISHEYE_EQUISOLID)
        val frame = AimLensFrame()
        frame.update(lens)
        frame.intoWorld(pointing)
        assertEquals(4 * AimLensFrame.FISHEYE_SAMPLES_PER_EDGE, frame.count)
        val directions = frame.directions()
        for (direction in directions) assertEquals(1.0, direction.length, 1e-9)
        // The middle of the top edge, 47.16° from the axis, the corners beyond 90°.
        assertEquals(47.156357, directions[AimLensFrame.FISHEYE_SAMPLES_PER_EDGE / 2].angle(pointing.boresightVector), 1e-5)
        assertTrue(directions[0].angle(pointing.boresightVector) > 90.0)
        // The picture's top is towards the camera's up vector.
        assertTrue(directions[AimLensFrame.FISHEYE_SAMPLES_PER_EDGE / 2].dot(pointing.cameraUpVector) > 0.5)
    }

    @Test
    fun theBorderIsKeptWhileTheLensStaysTheSame() {
        val lens = LensSetup(SensorFormat.fullFrame, 24.0, 2.8)
        val frame = AimLensFrame()
        frame.update(lens)
        val world = frame.world
        frame.update(LensSetup(SensorFormat.fullFrame, 24.0, 2.8))
        assertTrue(world === frame.world)
        frame.update(LensSetup(SensorFormat.fullFrame, 50.0, 2.8, null, LensProjection.RECTILINEAR))
        assertEquals(4 * AimLensFrame.RECTILINEAR_SAMPLES_PER_EDGE, frame.count)
    }
}

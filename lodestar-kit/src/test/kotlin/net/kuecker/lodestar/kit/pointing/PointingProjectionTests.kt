package net.kuecker.lodestar.kit.pointing

import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.pointing.PointingTestSupport as Support
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PointingProjectionTests {
    private fun makeProjection(width: Double = 390.0, height: Double = 844.0, longSideFOV: Double = 65.0): PinholeProjection =
        assertNotNull(PinholeProjection.fromLongSideFieldOfView(width, height, longSideFOV))

    /** Where the sky direction (`azimuth`, `altitude`) appears in the viewport. */
    private fun pixel(projection: PinholeProjection, attitude: DeviceAttitude, azimuth: Double, altitude: Double): ProjectedPoint =
        assertNotNull(
            projection.project(HorizontalCoordinate(azimuth, altitude).unitVector, attitude, headingOffset = 0.0),
        )

    /** A phone held upright in portrait, back camera towards `boresight` with no roll. */
    private fun uprightAttitude(facing: HorizontalCoordinate, referenceAzimuth: Double = 0.0): DeviceAttitude {
        val frame = Support.cameraFrame(facing, 0.0)
        return Support.attitude(mount = MountProfile.backCameraPortrait, frame = frame, referenceAzimuth = referenceAzimuth)
    }

    // MARK: Geometry

    @Test
    fun testCentreOfTheViewportIsThePhoneCameraDirection() {
        val random = PointingTestRandom(301)
        val projection = makeProjection()
        repeat(100) {
            val attitude = assertNotNull(DeviceAttitude.create(random.rotation(), random.uniform(-180.0, 180.0)))
            val offset = random.uniform(-180.0, 180.0)
            val axis = attitude.withHeadingOffset(offset).deviceToWorld * DeviceAttitude.backCameraAxis
            val centre = assertNotNull(projection.project(axis, attitude, offset))
            assertEquals(195.0, centre.x, 1e-9)
            assertEquals(422.0, centre.y, 1e-9)
            assertEquals(1.0, centre.depth, 1e-12)
            Support.assertVector(assertNotNull(projection.unproject(195.0, 422.0, attitude, offset)), axis)
        }
    }

    @Test
    fun testFieldOfViewReachesTheViewportEdges() {
        val projection = makeProjection(width = 390.0, height = 844.0, longSideFOV = 65.0)
        val attitude = uprightAttitude(HorizontalCoordinate(0.0, 0.0))
        val half = 32.5
        val top = pixel(projection, attitude, 0.0, half)
        assertEquals(0.0, top.y, 1e-9)
        assertEquals(195.0, top.x, 1e-9)
        val bottom = pixel(projection, attitude, 0.0, -half)
        assertEquals(844.0, bottom.y, 1e-9)
        // The short side spans its own, smaller, field of view.
        val horizontal = projection.horizontalFieldOfView
        assertTrue(horizontal < 65)
        val right = pixel(projection, attitude, horizontal / 2, 0.0)
        assertEquals(390.0, right.x, 1e-9)
        assertEquals(422.0, right.y, 1e-9)
        val left = pixel(projection, attitude, -horizontal / 2, 0.0)
        assertEquals(0.0, left.x, 1e-9)
        assertEquals(65.0, projection.longSideFieldOfView, 1e-9)
        assertEquals(65.0, projection.verticalFieldOfView, 1e-9)
    }

    @Test
    fun testLongSideIsTheWidthOfALandscapeViewport() {
        val projection = makeProjection(width = 844.0, height = 390.0, longSideFOV = 65.0)
        assertEquals(65.0, projection.horizontalFieldOfView, 1e-9)
        assertTrue(projection.verticalFieldOfView < 65)
        assertEquals(65.0, projection.longSideFieldOfView, 1e-9)
    }

    @Test
    fun testExplicitFocalLengthAndFieldOfViewAreConsistent() {
        val fromFOV = makeProjection(width = 390.0, height = 844.0, longSideFOV = 70.0)
        val fromFocal = assertNotNull(PinholeProjection.create(390.0, 844.0, fromFOV.focalLength))
        assertEquals(fromFOV, fromFocal)
        assertEquals(422 / tan(AstroMath.radians(35.0)), fromFOV.focalLength, 1e-9)
    }

    @Test
    fun testScreenOrientationMatchesTheSky() {
        // Upright phone facing north: east is to the right of the picture, up is up the screen,
        // and the y coordinate grows downwards.
        val projection = makeProjection()
        val attitude = uprightAttitude(HorizontalCoordinate(0.0, 10.0))
        fun point(azimuth: Double, altitude: Double): ProjectedPoint = pixel(projection, attitude, azimuth, altitude)
        assertTrue(point(5.0, 10.0).x > 195)
        assertEquals(422.0, point(5.0, 10.0).y, 4.0)
        assertTrue(point(-5.0, 10.0).x < 195)
        assertTrue(point(0.0, 15.0).y < 422)
        assertTrue(point(0.0, 5.0).y > 422)
    }

    @Test
    fun testScreenOrientationFollowsAPhoneRolledInTheHand() {
        // Phone turned 90° clockwise (as seen from behind): its right edge now points down. The
        // sky's "up" therefore appears towards the left of the screen and east towards the top.
        val facing = HorizontalCoordinate(0.0, 0.0)
        val attitude = Support.attitude(mount = MountProfile.backCameraPortrait, frame = Support.cameraFrame(facing, 90.0))
        val projection = makeProjection()
        val above = pixel(projection, attitude, 0.0, 5.0)
        assertTrue(above.x < 195)
        assertEquals(422.0, above.y, 1e-6)
        val east = pixel(projection, attitude, 5.0, 0.0)
        assertTrue(east.y < 422)
        assertEquals(195.0, east.x, 1e-6)
    }

    @Test
    fun testProjectAndUnprojectAreInverse() {
        val random = PointingTestRandom(302)
        val projection = makeProjection(width = 430.0, height = 932.0, longSideFOV = 80.0)
        repeat(500) {
            val attitude = assertNotNull(DeviceAttitude.create(random.rotation(), random.uniform(-180.0, 180.0)))
            val offset = random.uniform(-30.0, 30.0)
            // Pixels anywhere (also outside the viewport) survive the round trip.
            val x = random.uniform(-500.0, 900.0)
            val y = random.uniform(-900.0, 1800.0)
            val ray = assertNotNull(projection.unproject(x, y, attitude, offset))
            assertEquals(1.0, ray.length, 1e-12)
            val back = assertNotNull(projection.project(ray * 7.0, attitude, offset))
            assertEquals(x, back.x, 1e-6)
            assertEquals(y, back.y, 1e-6)
        }
    }

    @Test
    fun testUnprojectedRaysMatchTheSkyDirections() {
        // Upright phone facing north at 10° altitude: the top of the viewport is the direction 32.5° higher.
        val projection = makeProjection()
        val attitude = uprightAttitude(HorizontalCoordinate(0.0, 10.0))
        val ray = assertNotNull(projection.unproject(195.0, 0.0, attitude, 0.0))
        val coordinate = HorizontalCoordinate(ray)
        assertEquals(42.5, coordinate.altitude, 1e-9)
        assertEquals(0.0, Support.azimuthDifference(coordinate.azimuth, 0.0), 1e-9)
    }

    @Test
    fun testDirectionsBehindOrGrazingTheCameraAreNotProjected() {
        val projection = makeProjection()
        val facing = HorizontalCoordinate(30.0, 20.0)
        val attitude = uprightAttitude(facing)
        assertNull(projection.project(facing.unitVector * -1.0, attitude, 0.0))
        // Exactly 90° off-axis (the image plane) and just short of it.
        val sideways = Support.cameraFrame(facing, 0.0).right
        assertNull(projection.project(sideways, attitude, 0.0))
        val grazing = (facing.unitVector * 1e-8 + sideways).normalized
        assertNull(projection.project(grazing, attitude, 0.0))
        val nearly = (facing.unitVector * 0.01 + sideways).normalized
        assertNotNull(projection.project(nearly, attitude, 0.0))
        assertNull(projection.project(Vector3.zero, attitude, 0.0))
        assertNull(projection.project(Vector3(Double.NaN, 0.0, 1.0), attitude, 0.0))
        assertNull(projection.project(Vector3(0.0, Double.POSITIVE_INFINITY, 1.0), attitude, 0.0))
        assertNull(projection.unproject(Double.NaN, 3.0, attitude, 0.0))
        assertNull(projection.unproject(3.0, Double.POSITIVE_INFINITY, attitude, 0.0))
    }

    @Test
    fun testDepthShrinksAwayFromTheCentre() {
        val projection = makeProjection()
        val attitude = uprightAttitude(HorizontalCoordinate(0.0, 0.0))
        var previous = 2.0
        var degrees = 0.0
        while (degrees <= 80.0) {
            val point = pixel(projection, attitude, degrees, 0.0)
            assertEquals(cos(AstroMath.radians(degrees)), point.depth, 1e-12)
            assertTrue(point.depth < previous)
            previous = point.depth
            degrees += 10.0
        }
    }

    @Test
    fun testBatchProjectionMatchesSingleProjection() {
        val random = PointingTestRandom(303)
        val projection = makeProjection()
        val attitude = assertNotNull(DeviceAttitude.create(random.rotation(), 12.0))
        val directions = (0 until 200).map { random.unitVector() }
        val batch = projection.project(directions + Vector3.zero, attitude, 7.0)
        assertEquals(directions.size + 1, batch.size)
        for ((direction, projected) in directions.zip(batch)) {
            assertEquals(projection.project(direction, attitude, 7.0), projected)
        }
        assertNull(batch[directions.size])
        assertTrue(batch.any { it == null })
        assertTrue(batch.any { it != null })
    }

    @Test
    fun testHeadingOffsetAppliesToTheOverlay() {
        val projection = makeProjection()
        // The phone reports azimuth 0; with a +12° compass correction it really faces azimuth 12.
        val attitude = uprightAttitude(HorizontalCoordinate(0.0, 15.0))
        val star = HorizontalCoordinate(12.0, 15.0).unitVector
        val centred = assertNotNull(projection.project(star, attitude, 12.0))
        assertEquals(195.0, centred.x, 1e-9)
        assertEquals(422.0, centred.y, 1e-9)
        val uncorrected = assertNotNull(projection.project(star, attitude, 0.0))
        assertTrue(uncorrected.x > 195)
        // Equivalent to turning the reference frame.
        val turned = attitude.withHeadingOffset(12.0)
        val viaFrame = assertNotNull(projection.project(star, turned, 0.0))
        assertEquals(centred, viaFrame)
    }

    @Test
    fun testContainsHonoursTheMargin() {
        val projection = makeProjection()
        fun point(x: Double, y: Double) = ProjectedPoint(x = x, y = y, depth = 1.0)
        assertTrue(projection.contains(point(0.0, 0.0)))
        assertTrue(projection.contains(point(390.0, 844.0)))
        assertFalse(projection.contains(point(-1.0, 10.0)))
        assertFalse(projection.contains(point(10.0, 845.0)))
        assertTrue(projection.contains(point(-10.0, 850.0), margin = 10.0))
        assertFalse(projection.contains(point(-11.0, 850.0), margin = 10.0))
    }

    @Test
    fun testInvalidProjectionsAreRejected() {
        assertNull(PinholeProjection.fromLongSideFieldOfView(0.0, 800.0, 60.0))
        assertNull(PinholeProjection.fromLongSideFieldOfView(400.0, -1.0, 60.0))
        assertNull(PinholeProjection.fromLongSideFieldOfView(Double.NaN, 800.0, 60.0))
        assertNull(PinholeProjection.fromLongSideFieldOfView(400.0, 800.0, 0.0))
        assertNull(PinholeProjection.fromLongSideFieldOfView(400.0, 800.0, 180.0))
        assertNull(PinholeProjection.fromLongSideFieldOfView(400.0, 800.0, Double.NaN))
        assertNull(PinholeProjection.fromLongSideFieldOfView(400.0, 800.0, -10.0))
        assertNull(PinholeProjection.create(400.0, 800.0, 0.0))
        assertNull(PinholeProjection.create(400.0, 800.0, Double.POSITIVE_INFINITY))
        assertNotNull(PinholeProjection.fromLongSideFieldOfView(400.0, 800.0, 120.0))
    }

    // MARK: DSLR frame outline

    private fun outlineSolution(mount: MountProfile = MountProfile.backCameraPortrait, roll: Double = 20.0): PointingSolution {
        val frame = Support.cameraFrame(HorizontalCoordinate(60.0, 30.0), roll)
        return PointingSolution.solve(Support.attitude(mount = mount, frame = frame), mount)
    }

    @Test
    fun testOutlineCornersAndEdgesLieOnTheFrameBorder() {
        val solution = outlineSolution()
        val samples = 6
        val outline = PinholeProjection.dslrFrameOutline(solution, 40.0, 27.0, samples)
        assertEquals(4 * samples, outline.size)
        val halfWidth = tan(AstroMath.radians(20.0))
        val halfHeight = tan(AstroMath.radians(13.5))
        // Picture-plane (gnomonic) coordinates of each sample: right and up of the picture.
        val plane = outline.map { direction ->
            val along = direction.dot(solution.boresightVector)
            assertEquals(1.0, direction.length, 1e-12)
            assertTrue(along > 0)
            Pair(direction.dot(solution.cameraRightVector) / along, direction.dot(solution.cameraUpVector) / along)
        }
        for (point in plane) {
            assertEquals(1.0, maxOf(abs(point.first) / halfWidth, abs(point.second) / halfHeight), 1e-9)
        }
        // The loop is the full rectangle, run clockwise as seen by the photographer: the shoelace
        // area in the right/up plane is negative.
        var doubleArea = 0.0
        for (index in plane.indices) {
            val a = plane[index]
            val b = plane[(index + 1) % plane.size]
            doubleArea += a.first * b.second - b.first * a.second
        }
        assertEquals(-4 * halfWidth * halfHeight, doubleArea / 2, 1e-9)

        // First sample is the top-left corner, then the top-right corner after `samples` points.
        val topLeft = outline[0]
        val topRight = outline[samples]
        assertEquals(-halfWidth, topLeft.dot(solution.cameraRightVector) / topLeft.dot(solution.boresightVector), 1e-9)
        assertEquals(halfHeight, topLeft.dot(solution.cameraUpVector) / topLeft.dot(solution.boresightVector), 1e-9)
        assertEquals(halfWidth, topRight.dot(solution.cameraRightVector) / topRight.dot(solution.boresightVector), 1e-9)
        assertEquals(halfHeight, topRight.dot(solution.cameraUpVector) / topRight.dot(solution.boresightVector), 1e-9)
        // Corner distance from the boresight: atan(√(tan²(h/2) + tan²(v/2))).
        val corner = AstroMath.degrees(atan(sqrt(halfWidth * halfWidth + halfHeight * halfHeight)))
        assertEquals(corner, topLeft.angle(solution.boresightVector), 1e-9)
        // The middle of the top edge is 13.5° above the boresight.
        val middle = outline[samples / 2]
        assertEquals(13.5, middle.angle(solution.boresightVector), 1e-9)
        assertEquals(0.0, middle.dot(solution.cameraRightVector), 1e-12)
        assertTrue(middle.dot(solution.cameraUpVector) > 0)
    }

    @Test
    fun testOutlineOfTheFieldOfViewMatchesThePhoneViewWhenAxesCoincide() {
        // DSLR aligned with the phone camera: its frame is a centred rectangle in the phone view.
        val projection = makeProjection(width = 390.0, height = 844.0, longSideFOV = 70.0)
        val frame = Support.cameraFrame(HorizontalCoordinate(200.0, 25.0), 0.0)
        val attitude = Support.attitude(mount = MountProfile.backCameraPortrait, frame = frame)
        val solution = PointingSolution.solve(attitude, MountProfile.backCameraPortrait)
        val outline = PinholeProjection.dslrFrameOutline(solution, 30.0, 20.0, 1)
        assertEquals(4, outline.size)
        val points = outline.map { assertNotNull(projection.project(it, attitude, 0.0)) }
        val halfWidth = projection.focalLength * tan(AstroMath.radians(15.0))
        val halfHeight = projection.focalLength * tan(AstroMath.radians(10.0))
        val expected = listOf(
            Pair(195 - halfWidth, 422 - halfHeight), Pair(195 + halfWidth, 422 - halfHeight),
            Pair(195 + halfWidth, 422 + halfHeight), Pair(195 - halfWidth, 422 + halfHeight),
        )
        for ((point, corner) in points.zip(expected)) {
            assertEquals(corner.first, point.x, 1e-6)
            assertEquals(corner.second, point.y, 1e-6)
        }
        // Straight picture edges stay straight in the rectilinear view: mid-edge samples are collinear.
        val fine = PinholeProjection.dslrFrameOutline(solution, 30.0, 20.0, 4)
        val top = fine.subList(0, 4).map { assertNotNull(projection.project(it, attitude, 0.0)) }
        for (point in top) assertEquals(top[0].y, point.y, 1e-6)
    }

    @Test
    fun testOutlineRejectsInvalidArguments() {
        val solution = outlineSolution()
        assertTrue(PinholeProjection.dslrFrameOutline(solution, 0.0, 20.0, 4).isEmpty())
        assertTrue(PinholeProjection.dslrFrameOutline(solution, 30.0, 180.0, 4).isEmpty())
        assertTrue(PinholeProjection.dslrFrameOutline(solution, Double.NaN, 20.0, 4).isEmpty())
        assertTrue(PinholeProjection.dslrFrameOutline(solution, 30.0, -5.0, 4).isEmpty())
        assertTrue(PinholeProjection.dslrFrameOutline(solution, 30.0, 20.0, 0).isEmpty())
        assertTrue(PinholeProjection.dslrFrameOutline(solution, 30.0, 20.0, -3).isEmpty())
        assertEquals(4000, PinholeProjection.dslrFrameOutline(solution, 30.0, 20.0, 5000).size)
        // A very wide lens still gives finite directions.
        val wide = PinholeProjection.dslrFrameOutline(solution, 120.0, 100.0, 3)
        assertEquals(12, wide.size)
        assertTrue(wide.all { it.isFinite })
    }

    @Test
    fun testOutlineFollowsTheMountOrientation() {
        // With the phone turned to landscape in the clamp the DSLR's up is the phone's side edge, but
        // the outline is defined by the camera's own axes and does not change with the mount.
        val frame = Support.cameraFrame(HorizontalCoordinate(60.0, 30.0), 20.0)
        val a = PointingSolution.solve(
            Support.attitude(mount = MountProfile.backCameraPortrait, frame = frame), MountProfile.backCameraPortrait,
        )
        val landscape = Support.attitude(mount = MountProfile.backCameraLandscapeRight, frame = frame)
        val b = PointingSolution.solve(landscape, MountProfile.backCameraLandscapeRight)
        val outlineA = PinholeProjection.dslrFrameOutline(a, 40.0, 27.0, 3)
        val outlineB = PinholeProjection.dslrFrameOutline(b, 40.0, 27.0, 3)
        for ((u, v) in outlineA.zip(outlineB)) Support.assertVector(u, v, accuracy = 1e-9)
    }
}

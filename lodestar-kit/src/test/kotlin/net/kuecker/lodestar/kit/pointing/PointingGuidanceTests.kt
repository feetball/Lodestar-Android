package net.kuecker.lodestar.kit.pointing

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.pointing.PointingTestSupport as Support
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class PointingGuidanceTests {
    private fun solution(
        mount: MountProfile,
        boresight: HorizontalCoordinate,
        roll: Double = 0.0,
        referenceAzimuth: Double = 0.0,
    ): PointingSolution {
        val frame = Support.cameraFrame(boresight, roll)
        val attitude = Support.attitude(mount = mount, frame = frame, referenceAzimuth = referenceAzimuth)
        return PointingSolution.solve(attitude, mount)
    }

    private fun guidance(solution: PointingSolution, target: HorizontalCoordinate, threshold: Double = 0.5): Guidance {
        val tracker = GuidanceTracker(onTargetThreshold = threshold)
        return assertNotNull(tracker.update(solution, target))
    }

    /** The angle of `a` relative to `b`, wrapped to (−π, π]. */
    private fun angleDifference(a: Double, b: Double): Double =
        AstroMath.radians(AstroMath.normalizeSignedDegrees(AstroMath.degrees(a - b)))

    // MARK: On target

    @Test
    fun testPointingExactlyAtTheTargetIsOnTarget() {
        val random = PointingTestRandom(201)
        for (mount in MountProfile.presets) {
            repeat(100) {
                val target = random.horizontal(-60.0..80.0)
                val s = solution(mount, target, random.uniform(-180.0, 180.0), random.uniform(-180.0, 180.0))
                val g = guidance(s, target)
                assertEquals(0.0, g.separation, 1e-6)
                assertEquals(0.0, g.panDelta, 1e-6)
                assertEquals(0.0, g.tiltDelta, 1e-6)
                assertEquals(GuidanceState.ON_TARGET, g.state)
                assertTrue(g.isTargetInFront)
            }
        }
    }

    @Test
    fun testSeparationIsTheGreatCircleDistance() {
        val random = PointingTestRandom(202)
        repeat(200) {
            val boresight = random.horizontal(-80.0..80.0)
            val target = random.horizontal()
            val s = solution(MountProfile.backCameraPortrait, boresight, random.uniform(-90.0, 90.0))
            assertEquals(boresight.angularSeparation(target), guidance(s, target).separation, 1e-6)
        }
    }

    // MARK: Pan, tilt and the arrow for a level camera

    @Test
    fun testTargetFiveDegreesEastAtTwentyDegreesAltitude() {
        val boresight = HorizontalCoordinate(100.0, 20.0)
        for (mount in listOf(MountProfile.backCameraPortrait, MountProfile.flatOnTopTopEdgeForward)) {
            val s = solution(mount, boresight)
            val g = guidance(s, HorizontalCoordinate(105.0, 20.0))
            assertEquals(5.0, g.panDelta, 1e-9)
            assertEquals(0.0, g.tiltDelta, 1e-9)
            // Two points at equal altitude: the great circle between them bulges slightly upwards.
            assertEquals(0.0, g.arrowAngle, 0.03, mount.id)
            assertTrue(g.arrowAngle > 0)
            assertEquals(5 * cos(AstroMath.radians(20.0)), g.separation, 0.02)
        }
    }

    @Test
    fun testTargetThreeDegreesHigherPointsTheArrowUp() {
        val boresight = HorizontalCoordinate(250.0, 30.0)
        for (mount in listOf(MountProfile.backCameraPortrait, MountProfile.flatOnTopTopEdgeForward)) {
            val s = solution(mount, boresight)
            val g = guidance(s, HorizontalCoordinate(250.0, 33.0))
            assertEquals(3.0, g.tiltDelta, 1e-9)
            assertEquals(0.0, g.panDelta, 1e-9)
            assertEquals(PI / 2, g.arrowAngle, 1e-9, mount.id)
            assertEquals(3.0, g.separation, 1e-9)
        }
    }

    @Test
    fun testArrowExamplesForEveryMount() {
        // A level camera facing north (roll 0). Arrow angles in the phone's screen axes (0 = right edge,
        // π/2 = top edge), written out by hand from how each mount sits on the camera:
        //  - portrait:           the camera's right is the right edge, its up the top edge;
        //  - upside down:        both reversed;
        //  - landscape (top edge left): the camera's up is the right edge, its right the bottom edge;
        //  - landscape (top edge right): the camera's up is the left edge, its right the top edge;
        //  - flat on top:        the camera's right is the right edge and tilting up is drawn towards the top edge.
        val half = PI / 2
        val expectations = listOf(
            Triple(MountProfile.backCameraPortrait, 0.0, half),
            Triple(MountProfile.backCameraPortraitUpsideDown, PI, -half),
            Triple(MountProfile.backCameraLandscapeLeft, -half, 0.0),
            Triple(MountProfile.backCameraLandscapeRight, half, PI),
            Triple(MountProfile.flatOnTopTopEdgeForward, 0.0, half),
            Triple(MountProfile.flatOnTopTopEdgeBack, PI, -half),
        )
        val boresight = HorizontalCoordinate(0.0, 0.0)
        for ((mount, east, up) in expectations) {
            val s = solution(mount, boresight)
            val towardsEast = guidance(s, HorizontalCoordinate(4.0, 0.0))
            assertEquals(0.0, angleDifference(towardsEast.arrowAngle, east), 1e-9, "${mount.id} east")
            assertEquals(4.0, towardsEast.panDelta, 1e-9)
            val towardsUp = guidance(s, HorizontalCoordinate(0.0, 4.0))
            assertEquals(0.0, angleDifference(towardsUp.arrowAngle, up), 1e-9, "${mount.id} up")
            assertEquals(4.0, towardsUp.tiltDelta, 1e-9)
        }
    }

    @Test
    fun testArrowPointsLeftAndDownForTargetsToTheLeftAndBelow() {
        val boresight = HorizontalCoordinate(10.0, 0.0)
        val s = solution(MountProfile.backCameraPortrait, boresight)
        val left = guidance(s, HorizontalCoordinate(5.0, 0.0))
        assertEquals(-5.0, left.panDelta, 1e-9)
        assertEquals(PI, abs(left.arrowAngle), 1e-9)
        val below = guidance(s, HorizontalCoordinate(10.0, -4.0))
        assertEquals(-4.0, below.tiltDelta, 1e-9)
        assertEquals(-PI / 2, below.arrowAngle, 1e-9)
        val right = guidance(s, HorizontalCoordinate(14.0, 0.0))
        assertEquals(0.0, right.arrowAngle, 1e-9)
    }

    @Test
    fun testSmallOffsetsGivePanTiltAndArrowThatAgree() {
        val random = PointingTestRandom(203)
        for (mount in listOf(MountProfile.backCameraPortrait, MountProfile.flatOnTopTopEdgeForward)) {
            repeat(200) {
                val boresight = random.horizontal(-50.0..60.0)
                val s = solution(mount, boresight)
                val target = HorizontalCoordinate(
                    boresight.azimuth + random.uniform(-2.0, 2.0), boresight.altitude + random.uniform(-2.0, 2.0),
                )
                val g = guidance(s, target)
                val eastward = g.panDelta * cos(AstroMath.radians(boresight.altitude))
                // Level camera: screen right is east-ish, screen up is up.
                val expected = atan2(g.tiltDelta, eastward)
                if (hypot(eastward, g.tiltDelta) > 0.3) {
                    assertEquals(0.0, angleDifference(g.arrowAngle, expected), 0.05)
                }
                // Panning right always means a rightward arrow (the east component of the tangent has
                // the sign of the azimuth difference).
                assertTrue(g.panDelta * cos(g.arrowAngle) > -1e-9)
            }
        }
    }

    // MARK: Every mount, every roll

    @Test
    fun testArrowFollowsTheMountMappingForAnyRollAndDirection() {
        val random = PointingTestRandom(204)
        for (expected in Support.expectedMounts) {
            repeat(300) {
                val boresight = random.horizontal(-70.0..80.0)
                val roll = random.uniform(-180.0, 180.0)
                val s = solution(expected.mount, boresight, roll, random.uniform(-180.0, 180.0))
                // A target at angle `distance` along the direction `phi` of the picture (0 = camera right,
                // 90° = camera up), written in the camera's own axes.
                val frame = Support.cameraFrame(boresight, roll)
                val phi = random.uniform(-180.0, 180.0)
                val distance = random.uniform(0.01, 170.0)
                val direction = frame.right * cos(AstroMath.radians(phi)) + frame.up * sin(AstroMath.radians(phi))
                val targetVector = frame.forward * cos(AstroMath.radians(distance)) + direction * sin(AstroMath.radians(distance))
                val g = guidance(s, HorizontalCoordinate(targetVector))
                // The mapping table says where the camera's right/up are drawn on the screen.
                val onScreen = expected.screenRight * cos(AstroMath.radians(phi)) + expected.screenUp * sin(AstroMath.radians(phi))
                assertEquals(
                    0.0, angleDifference(g.arrowAngle, atan2(onScreen.y, onScreen.x)), 1e-6,
                    "${expected.mount.id} roll $roll φ $phi",
                )
                assertEquals(distance, g.separation, 1e-6)
            }
        }
    }

    @Test
    fun testArrowOfABackCameraMountIsTheDirectionToMoveTheCameraOnTheScreen() {
        // Rotate the phone slightly about the in-plane device axis that moves the back camera towards the
        // arrow's direction on the screen: the separation must shrink by the size of the rotation.
        val random = PointingTestRandom(205)
        val backCameraMounts = listOf(
            MountProfile.backCameraPortrait, MountProfile.backCameraPortraitUpsideDown,
            MountProfile.backCameraLandscapeLeft, MountProfile.backCameraLandscapeRight,
        )
        val step = 0.05
        for (mount in backCameraMounts) {
            repeat(200) {
                val boresight = random.horizontal(-60.0..70.0)
                val roll = random.uniform(-180.0, 180.0)
                val frame = Support.cameraFrame(boresight, roll)
                val attitude = Support.attitude(mount = mount, frame = frame, referenceAzimuth = random.uniform(-180.0, 180.0))
                val target = random.horizontal(-60.0..80.0)
                val before = PointingSolution.solve(attitude, mount)
                val separation = boresight.angularSeparation(target)
                if (!(separation > 3 && separation < 150)) return@repeat
                val g = guidance(before, target)

                val arrow = Vector3(cos(g.arrowAngle), sin(g.arrowAngle), 0.0)
                // Turning the device by +step about n = (sy, −sx, 0) moves its back camera (−z) along (sx, sy, 0).
                val axis = Vector3(arrow.y, -arrow.x, 0.0)
                val turn = Matrix3.rotation(axis, AstroMath.radians(step))
                val moved = assertNotNull(DeviceAttitude.create(turn.transposed * attitude.matrix, attitude.referenceAzimuth))
                val after = PointingSolution.solve(moved, mount)
                val improvement = separation - after.boresight.angularSeparation(target)
                assertEquals(step, improvement, 0.05 * step, "${mount.id} roll $roll")
            }
        }
    }

    @Test
    fun testPanAndTiltNumbersMoveATripodHeadOntoTheTarget() {
        // Physical meaning of the numbers: panning the head clockwise (seen from above) by `panDelta`
        // and then raising the lens about the horizontal axis across the view by `tiltDelta` puts the
        // camera on the target, whatever the mount and however the camera is rolled.
        val random = PointingTestRandom(207)
        for (mount in MountProfile.presets) {
            repeat(100) {
                val start = random.horizontal(-50.0..75.0)
                val target = random.horizontal(-50.0..75.0)
                val psi = random.uniform(-180.0, 180.0)
                val attitude = Support.attitude(
                    mount = mount, frame = Support.cameraFrame(start, random.uniform(-180.0, 180.0)), referenceAzimuth = psi,
                )
                val g = guidance(PointingSolution.solve(attitude, mount), target)

                // The head turns the whole camera: clockwise from above is a negative turn about "up".
                val pan = Matrix3.rotationZ(-AstroMath.radians(g.panDelta))
                val panned = pan * (attitude.deviceToWorld * mount.cameraAxis)
                val level = panned.cross(Vector3(0.0, 0.0, 1.0)).normalized // horizontal, across the view
                val tilt = Matrix3.rotation(level, AstroMath.radians(g.tiltDelta))
                val turn = tilt * pan
                val result = HorizontalCoordinate(turn * (attitude.deviceToWorld * mount.cameraAxis))
                assertEquals(0.0, result.angularSeparation(target), 1e-7, mount.id)
            }
        }
    }

    @Test
    fun testFollowingTheArrowAlwaysLeadsToTheTarget() {
        // A closed loop: at each step the phone is turned by 1° about the device axis that moves its back
        // camera along the arrow on the screen. The separation must fall steadily until the state
        // says "on target" — over the zenith and behind the camera included.
        val random = PointingTestRandom(206)
        val backCameraMounts = listOf(
            MountProfile.backCameraPortrait, MountProfile.backCameraPortraitUpsideDown,
            MountProfile.backCameraLandscapeLeft, MountProfile.backCameraLandscapeRight,
        )
        for (mount in backCameraMounts) {
            repeat(40) {
                val start = random.horizontal(-30.0..85.0)
                val target = random.horizontal(0.0..88.0)
                var attitude = Support.attitude(
                    mount = mount, frame = Support.cameraFrame(start, random.uniform(-180.0, 180.0)),
                    referenceAzimuth = random.uniform(-180.0, 180.0),
                )
                val tracker = GuidanceTracker(onTargetThreshold = 0.6)
                val initial = start.angularSeparation(target)
                var previous = initial
                var steps = 0
                while (true) {
                    val g = assertNotNull(tracker.update(PointingSolution.solve(attitude, mount), target))
                    if (g.state == GuidanceState.ON_TARGET) break
                    // One degree per step, plus a little for the last, overshooting step.
                    if (!(steps <= ceil(initial).toInt() + 2)) {
                        fail("no convergence for ${mount.id} from $start to $target")
                    }
                    val axis = Vector3(sin(g.arrowAngle), -cos(g.arrowAngle), 0.0)
                    val turn = Matrix3.rotation(axis, AstroMath.radians(1.0))
                    attitude = assertNotNull(DeviceAttitude.create(turn.transposed * attitude.matrix, attitude.referenceAzimuth))
                    assertTrue(g.separation < previous + 1e-9)
                    previous = g.separation
                    steps += 1
                }
            }
        }
    }

    @Test
    fun testArrowAngleIsContinuousAroundTheBoresight() {
        for (mount in MountProfile.presets) {
            val boresight = HorizontalCoordinate(300.0, 40.0)
            val roll = 37.0
            val s = solution(mount, boresight, roll)
            val frame = Support.cameraFrame(boresight, roll)
            var previous: Double? = null
            for (step in 0..72) {
                val phi = AstroMath.radians(step.toDouble() * 5)
                val direction = frame.right * cos(phi) + frame.up * sin(phi)
                val radius = AstroMath.radians(4.0)
                val target = HorizontalCoordinate(frame.forward * cos(radius) + direction * sin(radius))
                val angle = guidance(s, target).arrowAngle
                if (previous != null) {
                    // Moving the target counter-clockwise around the picture turns the arrow the same
                    // way by the same amount (the screen mapping is a proper rotation).
                    assertEquals(AstroMath.radians(5.0), angleDifference(angle, previous), 1e-6, mount.id)
                }
                previous = angle
            }
        }
    }

    // MARK: Flags

    @Test
    fun testPanDeltaWrapsAcrossNorth() {
        val s1 = solution(MountProfile.backCameraPortrait, HorizontalCoordinate(359.0, 10.0))
        assertEquals(3.0, guidance(s1, HorizontalCoordinate(2.0, 10.0)).panDelta, 1e-9)
        val s2 = solution(MountProfile.backCameraPortrait, HorizontalCoordinate(2.0, 10.0))
        assertEquals(-3.0, guidance(s2, HorizontalCoordinate(359.0, 10.0)).panDelta, 1e-9)
        assertEquals(180.0, guidance(s2, HorizontalCoordinate(182.0, 10.0)).panDelta, 1e-9)
    }

    @Test
    fun testNearZenithFlag() {
        val high = solution(MountProfile.backCameraPortrait, HorizontalCoordinate(0.0, 88.0))
        assertTrue(guidance(high, HorizontalCoordinate(90.0, 60.0)).isNearZenith)
        val low = solution(MountProfile.backCameraPortrait, HorizontalCoordinate(0.0, 60.0))
        assertTrue(guidance(low, HorizontalCoordinate(90.0, 86.0)).isNearZenith)
        assertFalse(guidance(low, HorizontalCoordinate(90.0, 85.0)).isNearZenith)
        val nadir = solution(MountProfile.backCameraPortrait, HorizontalCoordinate(0.0, -87.0))
        assertTrue(guidance(nadir, HorizontalCoordinate(0.0, 10.0)).isNearZenith)
        assertFalse(guidance(low, HorizontalCoordinate(10.0, 70.0)).isNearZenith)
    }

    @Test
    fun testArrowStaysCorrectNearTheZenith() {
        // Boresight 2° from the zenith; the target is 3° further along the same meridian (over the
        // zenith). Pan/tilt are unreliable here, the arrow is not: it must still point along the
        // great circle, i.e. up on the screen for a camera whose top points that way.
        val boresight = HorizontalCoordinate(0.0, 88.0)
        val s = solution(MountProfile.backCameraPortrait, boresight, 0.0)
        val target = HorizontalCoordinate(180.0, 89.0)
        val g = guidance(s, target)
        assertTrue(g.isNearZenith)
        assertEquals(3.0, g.separation, 1e-9)
        assertEquals(PI / 2, g.arrowAngle, 1e-9)
    }

    @Test
    fun testTargetBelowTheHorizon() {
        val s = solution(MountProfile.backCameraPortrait, HorizontalCoordinate(0.0, 5.0))
        assertTrue(guidance(s, HorizontalCoordinate(0.0, -0.1)).targetBelowHorizon)
        assertFalse(guidance(s, HorizontalCoordinate(0.0, 0.0)).targetBelowHorizon)
        assertFalse(guidance(s, HorizontalCoordinate(0.0, 30.0)).targetBelowHorizon)
    }

    @Test
    fun testTargetsBehindTheCamera() {
        val boresight = HorizontalCoordinate(40.0, 10.0)
        val s = solution(MountProfile.backCameraPortrait, boresight)
        assertTrue(guidance(s, HorizontalCoordinate(40.0 + 89, 0.0)).isTargetInFront)
        assertFalse(guidance(s, HorizontalCoordinate(40.0 + 100, 0.0)).isTargetInFront)
        // Exactly behind: there is no unique way round; the result must still be finite.
        val behind = guidance(s, HorizontalCoordinate(boresight.unitVector * -1.0))
        assertFalse(behind.isTargetInFront)
        assertEquals(180.0, behind.separation, 1e-6)
        assertTrue(behind.arrowAngle.isFinite())
        assertEquals(GuidanceState.FAR, behind.state)
    }

    // MARK: Tracker

    @Test
    fun testDefaultThresholdIsAQuarterOfTheShortSide() {
        assertEquals(9.45, GuidanceTracker.defaultOnTargetThreshold(54.4, 37.8), 1e-12)
        assertEquals(5.0, GuidanceTracker.defaultOnTargetThreshold(20.0, 30.0), 1e-12)
        assertEquals(0.575, GuidanceTracker.defaultOnTargetThreshold(3.4, 2.3), 1e-12)
        assertEquals(0.5, GuidanceTracker.defaultOnTargetThreshold(2.5, 1.7))
        assertEquals(0.5, GuidanceTracker.defaultOnTargetThreshold(Double.NaN, 30.0))
        assertEquals(0.5, GuidanceTracker.defaultOnTargetThreshold(-4.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun testOnTargetHasHysteresis() {
        // The target lies on the horizon east of a horizontal camera, so the separation equals Δaz.
        val s = solution(MountProfile.backCameraPortrait, HorizontalCoordinate(0.0, 0.0))
        val tracker = GuidanceTracker(onTargetThreshold = 1.0)
        fun state(separation: Double): GuidanceState =
            assertNotNull(tracker.update(s, HorizontalCoordinate(separation, 0.0))).state
        assertEquals(GuidanceState.CLOSE, state(3.0))
        assertEquals(GuidanceState.CLOSE, state(1.2)) // inside the hysteresis band but not yet on target
        assertEquals(GuidanceState.ON_TARGET, state(0.9))
        assertEquals(GuidanceState.ON_TARGET, state(1.2)) // stays on target inside the band
        assertEquals(GuidanceState.ON_TARGET, state(1.49))
        assertEquals(GuidanceState.CLOSE, state(1.51)) // leaves beyond 1.5 × threshold
        assertEquals(GuidanceState.CLOSE, state(1.2)) // and must get below the threshold again to re-enter
        assertEquals(GuidanceState.ON_TARGET, state(0.99))
        assertEquals(GuidanceState.FAR, state(7.0))
        assertEquals(GuidanceState.ON_TARGET, state(0.5))
    }

    @Test
    fun testResetForgetsTheOnTargetState() {
        val s = solution(MountProfile.backCameraPortrait, HorizontalCoordinate(0.0, 0.0))
        val tracker = GuidanceTracker(onTargetThreshold = 1.0)
        assertEquals(GuidanceState.ON_TARGET, assertNotNull(tracker.update(s, HorizontalCoordinate(0.5, 0.0))).state)
        assertEquals(GuidanceState.ON_TARGET, assertNotNull(tracker.update(s, HorizontalCoordinate(1.2, 0.0))).state)
        tracker.reset()
        assertEquals(GuidanceState.CLOSE, assertNotNull(tracker.update(s, HorizontalCoordinate(1.2, 0.0))).state)
    }

    @Test
    fun testCloseAndFarThresholds() {
        val s = solution(MountProfile.backCameraPortrait, HorizontalCoordinate(0.0, 0.0))
        fun state(tracker: GuidanceTracker, separation: Double): GuidanceState =
            assertNotNull(tracker.update(s, HorizontalCoordinate(separation, 0.0))).state
        // Default: three times the on-target threshold, at least 5°.
        val small = GuidanceTracker(onTargetThreshold = 1.0)
        assertEquals(5.0, small.closeThreshold)
        assertEquals(GuidanceState.CLOSE, state(small, 4.9))
        assertEquals(GuidanceState.FAR, state(small, 5.1))
        val wide = GuidanceTracker(onTargetThreshold = 9.0)
        assertEquals(27.0, wide.closeThreshold)
        assertEquals(GuidanceState.CLOSE, state(wide, 26.0))
        assertEquals(GuidanceState.FAR, state(wide, 28.0))
        val custom = GuidanceTracker(onTargetThreshold = 1.0, closeThreshold = 12.0)
        assertEquals(GuidanceState.CLOSE, state(custom, 11.0))
        assertEquals(GuidanceState.FAR, state(custom, 13.0))
        // A close threshold below the on-target threshold is raised to it.
        assertEquals(2.0, GuidanceTracker(onTargetThreshold = 2.0, closeThreshold = 1.0).closeThreshold)
    }

    @Test
    fun testInvalidThresholdsFallBack() {
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(0.5, GuidanceTracker(onTargetThreshold = bad).onTargetThreshold)
        }
        assertEquals(5.0, GuidanceTracker(onTargetThreshold = 1.0, closeThreshold = Double.NaN).closeThreshold)
    }

    @Test
    fun testNonFiniteTargetsGiveNoGuidance() {
        val s = solution(MountProfile.backCameraPortrait, HorizontalCoordinate(0.0, 0.0))
        val tracker = GuidanceTracker(onTargetThreshold = 1.0)
        assertNull(tracker.update(s, HorizontalCoordinate(Double.NaN, 10.0)))
        assertNull(tracker.update(s, HorizontalCoordinate(Double.POSITIVE_INFINITY, 10.0)))
        // And the tracker is not disturbed by them.
        assertEquals(GuidanceState.ON_TARGET, assertNotNull(tracker.update(s, HorizontalCoordinate(0.5, 0.0))).state)
    }

    @Test
    fun testGuidanceStateRawValuesAreStable() {
        assertEquals("onTarget", GuidanceState.ON_TARGET.rawValue)
        val serializer = ListSerializer(GuidanceState.serializer())
        val data = Json.encodeToString(serializer, listOf(GuidanceState.ON_TARGET, GuidanceState.CLOSE, GuidanceState.FAR))
        assertEquals("""["onTarget","close","far"]""", data)
        assertEquals(
            listOf(GuidanceState.ON_TARGET, GuidanceState.CLOSE, GuidanceState.FAR),
            Json.decodeFromString(serializer, data),
        )
    }
}

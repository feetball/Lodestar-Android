package net.kuecker.lodestar.kit.pointing

import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.pointing.PointingTestSupport as Support
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The pointing code working on real sky positions from `SkyFrame`. */
class PointingIntegrationTests {
    private val observer = Observer(GeographicLocation(latitude = 40.015, longitude = -105.27, elevation = 1650.0))

    /** 2026-10-17 04:00 UTC, about 22:00 the evening before in Colorado. */
    private val date = instantFromTimeIntervalSince1970(1_792_209_600.0)

    private class Star(val name: String, val j2000: EquatorialCoordinate)

    private val stars = listOf(
        Star("Polaris", EquatorialCoordinate(rightAscension = 37.954561, declination = 89.264109)),
        Star("Vega", EquatorialCoordinate(rightAscension = 279.234735, declination = 38.783689)),
        Star("Altair", EquatorialCoordinate(rightAscension = 297.695827, declination = 8.868321)),
        Star("Deneb", EquatorialCoordinate(rightAscension = 310.357980, declination = 45.280339)),
        Star("Fomalhaut", EquatorialCoordinate(rightAscension = 344.412693, declination = -29.622237)),
    )

    private fun apparent(star: Star, frame: SkyFrame): HorizontalCoordinate =
        frame.horizontal(star.j2000, refraction = true)

    @Test
    fun testTheTestSkyHasTheStarsWeNeed() {
        val frame = SkyFrame(date, observer)
        for (star in stars) {
            assertTrue(apparent(star, frame).altitude > 10, star.name)
        }
        assertTrue(apparent(stars[1], frame).altitude < 80)
    }

    @Test
    fun testAimingAtAStarIsOnTargetForEveryMount() {
        val frame = SkyFrame(date, observer)
        val random = PointingTestRandom(701)
        for (mount in MountProfile.presets) {
            for (star in stars) {
                val target = apparent(star, frame)
                val attitude = Support.attitude(
                    mount = mount, frame = Support.cameraFrame(target, random.uniform(-60.0, 60.0)), referenceAzimuth = 7.7,
                )
                val solution = PointingSolution.solve(attitude, mount)
                val tracker = GuidanceTracker(onTargetThreshold = 0.5)
                val guidance = assertNotNull(tracker.update(solution, target))
                assertEquals(GuidanceState.ON_TARGET, guidance.state, "${star.name} ${mount.id}")
                assertEquals(0.0, guidance.separation, 1e-6)
                // What the camera sees is the star: the inverse sky transform returns its catalogue place.
                val seen = frame.j2000(solution.boresight, refracted = true)
                assertEquals(0.0, seen.angularSeparation(star.j2000) * 3600, 3.0, star.name)
            }
        }
    }

    @Test
    fun testTheCelestialPoleIsAtTheLatitudeAndTheArrowFindsIt() {
        val frame = SkyFrame(date, observer)
        val pole = frame.horizontalOfCelestialPole(north = true, refraction = true)
        assertEquals(40.015, pole.altitude, 0.05)
        // Camera 10° to the right of and 5° below the pole: the arrow points left and up on the screen.
        val mount = MountProfile.backCameraPortrait
        val reported = HorizontalCoordinate(
            pole.azimuth + 10 / cos(AstroMath.radians(pole.altitude)), pole.altitude - 5,
        )
        val attitude = Support.attitude(mount = mount, frame = Support.cameraFrame(reported, 0.0))
        val tracker = GuidanceTracker(onTargetThreshold = 0.5)
        val guidance = assertNotNull(tracker.update(PointingSolution.solve(attitude, mount), pole))
        assertTrue(guidance.panDelta < 0)
        assertTrue(guidance.tiltDelta > 0)
        assertTrue(sin(guidance.arrowAngle) > 0)
        assertTrue(cos(guidance.arrowAngle) < 0)
        assertEquals(GuidanceState.FAR, guidance.state)
    }

    @Test
    fun testAlignmentOnStarsRemovesCompassAndMountErrors() {
        // The phone's compass reads 6° off and the clamp tilts the lens 1.5°; align on Polaris, then on
        // Vega and Deneb, and check the result on stars never used.
        val frame = SkyFrame(date, observer)
        val mount = MountProfile.backCameraLandscapeLeft
        val random = PointingTestRandom(702)
        val scenario = AlignmentTestScenario(headingOffset = 6.0, correction = Vector3(0.8, 1.2, 0.0), mount = mount)
        fun point(star: Star, index: Int): AlignmentPoint =
            scenario.point(
                apparent(star, frame), random.uniform(-20.0, 20.0), random = random, index = index, name = star.name,
            )
        var model = AlignmentModel.identity.adding(point(stars[1], 0), mount)
        assertEquals("Vega", model.points.firstOrNull()?.name)
        model = model.adding(point(stars[3], 1), mount)
        model = model.adding(point(stars[0], 2), mount)
        assertTrue(model.rmsResidual < 0.02)
        assertEquals(6.0, model.headingOffset, 0.1)
        for (star in listOf(stars[2], stars[4])) {
            val target = apparent(star, frame)
            val attitude = scenario.point(target, 10.0, random = random).attitude
            val solution = PointingSolution.solve(attitude, mount, model)
            assertEquals(0.0, solution.boresight.angularSeparation(target), 0.05, star.name)
            val uncorrected = PointingSolution.solve(attitude, mount)
            assertTrue(uncorrected.boresight.angularSeparation(target) > 2, star.name)
        }
    }
}
